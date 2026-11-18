"""One bounded semantic check for selected-image edits or creative continuations."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Annotated, Literal, Self

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    StrictStr,
    ValidationError,
    model_validator,
)

from app.agent.brand_brief import require_project_memory_scope
from app.agent.models import ChatRunRequest
from app.harness.runtime import (
    HarnessViolation,
    RuntimeUsageCounters,
    bind_model_call_boundary,
    bind_runtime_usage,
)
from app.model_gateway.gateway import (
    ModelGateway,
    ModelGatewayError,
    ModelMessage,
    StructuredCompletionGateway,
)
from app.observability.tracing import start_span
from app.providers.java_context import AuthorizedRunContext
from app.providers.knowledge_context import ProjectMemoryContext
from app.tools.registry import ToolRuntimeModel

CreativeActionDecision = Literal["IMAGE", "VIDEO", "DISCUSSION", "UNCLEAR", "UNAVAILABLE"]
CreativeSourceDecision = Literal[
    "TEXT_ONLY", "CURRENT_ATTACHMENTS", "PREVIOUS_IMAGE_RESULT", "UNCLEAR"
]


@dataclass(frozen=True)
class CreativeActionVerdict:
    decision: CreativeActionDecision
    source_mode: CreativeSourceDecision


class CreativeContinuationMessage(ToolRuntimeModel):
    message_id: StrictStr = Field(pattern=r"^[1-9][0-9]{0,18}$")
    role: Literal["USER", "ASSISTANT"]
    text: StrictStr = Field(min_length=1, max_length=16000)

    @model_validator(mode="after")
    def nonempty_text(self) -> Self:
        if not self.text.strip():
            raise ValueError("Continuation history must contain original nonempty text")
        return self


class CreativeContinuationCandidate(ToolRuntimeModel):
    """A Java-scoped history snapshot, never a grant to create a paid task."""

    authorized: StrictBool
    review_required: StrictBool
    current_message_id: StrictStr = Field(pattern=r"^[1-9][0-9]{0,18}$")
    conversation_id: StrictStr = Field(pattern=r"^[1-9][0-9]{0,18}$")
    project_id: StrictStr | None = Field(pattern=r"^[1-9][0-9]{0,18}$")
    history_fingerprint: StrictStr = Field(pattern=r"^[0-9a-f]{64}$")
    history: tuple[CreativeContinuationMessage, ...] = Field(max_length=6)
    history_incomplete: StrictBool
    source_image_asset_id: StrictStr | None = Field(pattern=r"^[1-9][0-9]{0,18}$")
    current_attachment_asset_ids: tuple[
        Annotated[StrictStr, Field(pattern=r"^[1-9][0-9]{0,18}$")], ...
    ] = Field(max_length=8)

    @model_validator(mode="after")
    def validate_history_scope(self) -> Self:
        if self.authorized or not self.review_required:
            raise ValueError("A continuation candidate cannot authorize a creative task")
        if not self.history and not self.current_attachment_asset_ids:
            raise ValueError("A creative review needs current attachments or prior dialogue")
        if len(set(self.current_attachment_asset_ids)) != len(self.current_attachment_asset_ids):
            raise ValueError("Current attachment identifiers must be distinct")
        identifiers = [message.message_id for message in self.history]
        if len(set(identifiers)) != len(identifiers) or self.current_message_id in identifiers:
            raise ValueError("Continuation history must contain distinct prior messages")
        if sum(len(message.text) for message in self.history) > 16000:
            raise ValueError("Continuation history exceeds the review bound")
        return self


class CreativeActionReview(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    decision: Literal["IMAGE", "VIDEO", "DISCUSSION", "UNCLEAR"]
    source_mode: CreativeSourceDecision = Field(alias="sourceMode")

    @model_validator(mode="after")
    def require_consistent_source(self) -> Self:
        if self.decision == "IMAGE" and self.source_mode not in {
            "CURRENT_ATTACHMENTS",
            "PREVIOUS_IMAGE_RESULT",
        }:
            raise ValueError("An image continuation must preserve its reviewed source")
        if self.decision == "VIDEO" and self.source_mode == "UNCLEAR":
            raise ValueError("An executable video continuation needs a reviewed source mode")
        if self.decision in {"DISCUSSION", "UNCLEAR"} and self.source_mode != "UNCLEAR":
            raise ValueError("A nonexecution verdict cannot authorize a source")
        return self


def _candidate_matches_run(
    candidate: CreativeContinuationCandidate,
    request: ChatRunRequest,
    context: AuthorizedRunContext,
    memory: ProjectMemoryContext | None,
    *,
    require_history: bool = False,
) -> bool:
    try:
        require_project_memory_scope(context, memory)
    except ValueError:
        return False
    project_id = None if context.agent_identity is None else context.agent_identity.project_id
    project_id = project_id or context.selected_space_id
    if (
        candidate.current_message_id != request.message.message_id
        or candidate.conversation_id != request.conversation_id
        or candidate.project_id != project_id
        or context.user_context_ref != request.user_context_ref
        or candidate.current_attachment_asset_ids != request.message.attachment_asset_ids
        or candidate.current_attachment_asset_ids != context.attachment_asset_ids
        or (not candidate.current_attachment_asset_ids and not candidate.history)
    ):
        return False
    if require_history and (
        candidate.history_incomplete
        or not any(message.role == "USER" for message in candidate.history)
    ):
        return False
    source = context.conversation_memory
    cutoff = None if memory is None else memory.history_cutoff_at
    if source is None:
        return (
            bool(candidate.current_attachment_asset_ids) and not require_history
        ) or cutoff is None
    current = next(
        (item for item in source.messages if item.message_id == request.message.message_id), None
    )
    if (
        current is None
        or current.role != "USER"
        or current.content.get("text") != request.message.content
    ):
        return False
    # A selected-image edit needs the current USER binding. Prior-source modes
    # independently require complete, correctly bound history before execution.
    if candidate.current_attachment_asset_ids and not require_history:
        return True
    by_id = {message.message_id: message for message in source.messages}
    previous_sequence = 0
    for message in candidate.history:
        raw = by_id.get(message.message_id)
        if raw is None:
            # A fresh Java read may include a message outside the opening snapshot.
            # Without timestamps it cannot prove membership after a deletion fence.
            if cutoff is not None:
                return False
            continue
        if (
            raw.role != message.role
            or raw.content.get("text") != message.text
            or raw.sequence >= current.sequence
            or raw.sequence <= previous_sequence
            or (cutoff is not None and raw.created_at < cutoff)
        ):
            return False
        previous_sequence = raw.sequence
    return True


async def review_creative_action(
    *,
    gateway: ModelGateway | None,
    prompt: str | None,
    request: ChatRunRequest,
    context: AuthorizedRunContext,
    memory: ProjectMemoryContext | None,
    counters: RuntimeUsageCounters,
    candidate: CreativeContinuationCandidate | None,
) -> CreativeActionVerdict:
    """Use current USER and Java's raw dialogue; exclude scenes, summaries and tool data."""
    if (
        not prompt
        or not isinstance(gateway, StructuredCompletionGateway)
        or context.source != "JAVA_AUTHORIZED_CONTEXT"
        or candidate is None
    ):
        return CreativeActionVerdict("UNAVAILABLE", "UNCLEAR")
    try:
        candidate = CreativeContinuationCandidate.model_validate(
            candidate.model_dump(by_alias=True)
        )
    except ValidationError:
        return CreativeActionVerdict("UNAVAILABLE", "UNCLEAR")
    if not _candidate_matches_run(candidate, request, context, memory):
        return CreativeActionVerdict("UNAVAILABLE", "UNCLEAR")
    history_available = _candidate_matches_run(
        candidate, request, context, memory, require_history=True
    )
    if not history_available and not candidate.current_attachment_asset_ids:
        return CreativeActionVerdict("UNAVAILABLE", "UNCLEAR")
    messages = (
        ModelMessage("system", prompt),
        ModelMessage(
            "user",
            json.dumps(
                {
                    "currentUser": request.message.content,
                    "currentAttachmentCount": len(candidate.current_attachment_asset_ids),
                    "history": [
                        {"role": message.role, "text": message.text}
                        for message in candidate.history
                    ]
                    if history_available
                    else [],
                    "historyIncomplete": candidate.history_incomplete
                    or bool(candidate.history and not history_available),
                },
                ensure_ascii=False,
            ),
        ),
    )
    with start_span("agent.creative_action_review") as span:
        try:
            counters.before_workflow_step()
            with (
                bind_runtime_usage(counters),
                bind_model_call_boundary(
                    max_calls=1,
                    deadline_at=request.budget.deadline_at,
                    max_input_tokens=min(
                        request.budget.max_input_tokens,
                        counters.model_input_token_limit or request.budget.max_input_tokens,
                    ),
                ),
            ):
                parsed, usage = await gateway.complete_structured(
                    messages,
                    CreativeActionReview,
                    operation="readonly_creative_action_review",
                    max_tokens=200,
                    error_code="CREATIVE_ACTION_REVIEW_INVALID",
                    repair_instruction="Return only the required decision and sourceMode enums.",
                    repair_attempts=0,
                )
            counters.record_gateway_usage(usage)
            reviewed = CreativeActionReview.model_validate(parsed.model_dump(by_alias=True))
            if (
                reviewed.source_mode == "CURRENT_ATTACHMENTS"
                and not candidate.current_attachment_asset_ids
            ) or (
                reviewed.source_mode in {"PREVIOUS_IMAGE_RESULT", "TEXT_ONLY"}
                and not history_available
            ):
                verdict = CreativeActionVerdict("UNAVAILABLE", "UNCLEAR")
            else:
                verdict = CreativeActionVerdict(reviewed.decision, reviewed.source_mode)
        except ModelGatewayError as exc:
            if exc.code in {"RUN_DEADLINE_EXCEEDED", "RUN_DEADLINE_INVALID"}:
                raise
            counters.record_gateway_usage(exc.usage)
            verdict = CreativeActionVerdict("UNAVAILABLE", "UNCLEAR")
        except HarnessViolation as exc:
            if exc.code in {"RUN_DEADLINE_EXCEEDED", "RUN_DEADLINE_INVALID"}:
                raise
            verdict = CreativeActionVerdict("UNAVAILABLE", "UNCLEAR")
        except ValidationError:
            verdict = CreativeActionVerdict("UNAVAILABLE", "UNCLEAR")
        span.set("creative.review.decision", verdict.decision)
        span.set("creative.review.source_mode", verdict.source_mode)
        return verdict

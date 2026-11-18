"""One bounded, read-only semantic check before a proposed article write."""

from __future__ import annotations

import json
from typing import Literal

from pydantic import BaseModel, ConfigDict, ValidationError

from app.agent.models import ChatRunRequest
from app.agents.geo_continuation import ArticleContinuation
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

ArticleActionDecision = Literal[
    "NEW_DRAFT", "EDIT_EXISTING", "DISCUSSION", "UNCLEAR", "UNAVAILABLE"
]


class ArticleActionReview(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    decision: Literal["NEW_DRAFT", "EDIT_EXISTING", "DISCUSSION", "UNCLEAR"]


async def review_article_action(
    *,
    gateway: ModelGateway | None,
    prompt: str | None,
    request: ChatRunRequest,
    context: AuthorizedRunContext,
    memory: ProjectMemoryContext | None,
    counters: RuntimeUsageCounters,
    continuation: ArticleContinuation | None = None,
) -> ArticleActionDecision:
    """Never use tool/web output, summaries or model-provided authorization evidence."""
    if (
        not prompt
        or not isinstance(gateway, StructuredCompletionGateway)
        or context.source != "JAVA_AUTHORIZED_CONTEXT"
    ):
        return "UNAVAILABLE"
    history: list[dict[str, str]] = []
    history_incomplete = False
    source = context.conversation_memory
    if source is not None:
        current = next(
            (m for m in source.messages if m.message_id == request.message.message_id), None
        )
        if (
            current is None
            or current.role != "USER"
            or current.content.get("text") != request.message.content
        ):
            return "UNAVAILABLE"
        used = 0
        for message in reversed(source.messages):
            if message.sequence >= current.sequence or message.role not in {"USER", "ASSISTANT"}:
                continue
            if (
                memory is not None
                and memory.history_cutoff_at is not None
                and message.created_at < memory.history_cutoff_at
            ):
                continue
            text = message.content.get("text")
            if not isinstance(text, str) or not text.strip():
                continue
            if len(history) >= 6 or used + len(text) > 16000:
                history_incomplete = True
                break
            history.append({"role": message.role, "text": text})
            used += len(text)
        history.reverse()
    candidate = (
        {
            "sourceUser": continuation.source_text,
            "assistantOffer": continuation.offer_text,
            "currentUser": request.message.content,
        }
        if continuation is not None
        else None
    )
    messages = (
        ModelMessage("system", prompt),
        ModelMessage(
            "user",
            json.dumps(
                {
                    "currentUser": request.message.content,
                    "history": history,
                    "historyIncomplete": history_incomplete,
                    "serverContinuationCandidate": candidate,
                },
                ensure_ascii=False,
            ),
        ),
    )
    with start_span("agent.article_action_review") as span:
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
                    ArticleActionReview,
                    operation="readonly_article_action_review",
                    max_tokens=200,
                    error_code="ARTICLE_ACTION_REVIEW_INVALID",
                    repair_instruction="Return only the required decision enum.",
                    repair_attempts=0,
                )
            counters.record_gateway_usage(usage)
            decision = ArticleActionReview.model_validate(parsed.model_dump()).decision
        except ModelGatewayError as exc:
            if exc.code in {"RUN_DEADLINE_EXCEEDED", "RUN_DEADLINE_INVALID"}:
                raise
            counters.record_gateway_usage(exc.usage)
            decision = "UNAVAILABLE"
        except HarnessViolation as exc:
            if exc.code in {"RUN_DEADLINE_EXCEEDED", "RUN_DEADLINE_INVALID"}:
                raise
            decision = "UNAVAILABLE"
        except ValidationError:
            decision = "UNAVAILABLE"
        span.set("article.review.decision", decision)
        return decision

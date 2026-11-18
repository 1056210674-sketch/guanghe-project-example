"""Stable domain models for bounded, free Chat Runs."""

from datetime import datetime
from enum import StrEnum
from typing import Literal
from uuid import UUID

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    JsonValue,
    StrictBool,
    field_validator,
    model_validator,
)
from pydantic.alias_generators import to_camel

from app.common.enums import TaskType


class AgentModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class Intent(StrEnum):
    GENERAL = "GENERAL"
    GEO = "GEO"
    IMAGE = "IMAGE"
    VIDEO = "VIDEO"
    ARTICLE = "ARTICLE"
    MEDIA = "MEDIA"
    AMBIGUOUS_CONFIRMATION = "AMBIGUOUS_CONFIRMATION"


class DraftOperation(StrEnum):
    NEW = "NEW"
    REVISE = "REVISE"


class RunStatus(StrEnum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    STOPPED = "STOPPED"


class BusinessOutcomeStatus(StrEnum):
    ANSWERED = "ANSWERED"
    NEEDS_INPUT = "NEEDS_INPUT"
    DRAFT_READY = "DRAFT_READY"
    PARTIAL = "PARTIAL"
    FAILED = "FAILED"


BusinessOutcomeReason = Literal[
    "INPUT_REQUIRED",
    "TASK_DRAFT_PROPOSED",
    "READONLY_PLANNER_UNAVAILABLE",
    "READONLY_PLAN_INVALID",
    "READONLY_EVIDENCE_UNAVAILABLE",
    "READONLY_PARTIAL_EVIDENCE",
    "READONLY_SYNTHESIS_UNAVAILABLE",
    "GEO_CONTEXT_UNAVAILABLE",
    "TASK_PARAMETERS_INVALID",
    "TASK_PREPARATION_REJECTED",
    "RESPONSE_PROTOCOL_INVALID",
    "AGENT_RUN_FAILED",
    "DRAFT_VERIFIED",
]
BusinessFailureStage = Literal["LOOKUP", "PLAN", "TOOL", "SYNTHESIS", "PROTOCOL"]


class BusinessOutcome(AgentModel):
    """Runtime-owned result; never included in a model's output schema."""

    schema_version: Literal[1] = 1
    status: BusinessOutcomeStatus
    reason_code: BusinessOutcomeReason | None = None
    failure_stage: BusinessFailureStage | None = None


class RunProgressStage(StrEnum):
    UNDERSTANDING_REQUEST = "UNDERSTANDING_REQUEST"
    LOADING_BUSINESS_CONTEXT = "LOADING_BUSINESS_CONTEXT"
    LOADING_PRODUCT_CONTEXT = "LOADING_PRODUCT_CONTEXT"
    LOADING_REFERENCE_ASSETS = "LOADING_REFERENCE_ASSETS"
    ROUTING_INTENT = "ROUTING_INTENT"
    PLANNING_WORKFLOW = "PLANNING_WORKFLOW"
    RUNNING_SAFE_TOOL = "RUNNING_SAFE_TOOL"
    SEARCHING_WEB = "SEARCHING_WEB"
    PREPARING_TASK_DRAFT = "PREPARING_TASK_DRAFT"
    COMPOSING_RESPONSE = "COMPOSING_RESPONSE"


class RunProgressStatus(StrEnum):
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


class RunProgressPayload(AgentModel):
    stage_code: RunProgressStage
    status: RunProgressStatus
    ordinal: int = Field(ge=1, le=32)
    elapsed_ms: int = Field(ge=0)
    current: int | None = Field(default=None, ge=0)
    total: int | None = Field(default=None, ge=0)

    @model_validator(mode="after")
    def validate_progress_count(self) -> "RunProgressPayload":
        if (self.current is None) != (self.total is None):
            raise ValueError("current and total must be provided together")
        if self.current is not None and self.total is not None and self.current > self.total:
            raise ValueError("current must not exceed total")
        return self


class ChatMessage(AgentModel):
    message_id: str = Field(min_length=1, max_length=128)
    content: str = Field(min_length=1, max_length=16_000)
    reply_to_message_id: str | None = Field(default=None, max_length=128)
    attachment_asset_ids: tuple[str, ...] = Field(default=(), max_length=16)

    @field_validator("content")
    @classmethod
    def content_must_not_be_blank(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("message content cannot be blank")
        return value


class TrustedState(AgentModel):
    selected_space_id: str | None = Field(default=None, max_length=128)
    selected_product_id: str | None = Field(default=None, max_length=128)
    pending_interaction_id: str | None = Field(default=None, max_length=128)
    draft_id: str | None = Field(default=None, max_length=128)
    task_id: str | None = Field(default=None, max_length=128)


class RunBudget(AgentModel):
    max_model_calls: int = Field(default=4, ge=1, le=16)
    max_tool_calls: int = Field(default=6, ge=1, le=32)
    max_steps: int = Field(default=12, ge=5, le=64)
    max_context_loads: int = Field(default=1, ge=1, le=1)
    max_provider_calls: int = Field(default=0, ge=0, le=6)
    max_input_tokens: int = Field(default=32_000, ge=128, le=128_000)
    deadline_at: datetime

    @field_validator("deadline_at")
    @classmethod
    def deadline_must_include_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("deadlineAt must include a timezone")
        return value


class ChatRunRequest(AgentModel):
    run_id: UUID
    conversation_id: str = Field(min_length=1, max_length=128)
    user_context_ref: str = Field(min_length=1, max_length=512)
    message: ChatMessage
    trusted_state: TrustedState
    budget: RunBudget


class Usage(AgentModel):
    workflow_step_count: int = Field(ge=0)
    model_call_count: int = Field(ge=0)
    context_load_count: int = Field(ge=0)
    tool_call_count: int = Field(ge=0)
    provider_call_count: int = Field(ge=0)
    # Compatibility mirrors for consumers which predate the split counters.
    model_calls: int = Field(ge=0)
    tool_calls: int = Field(ge=0)
    steps: int = Field(ge=0)
    estimated_input_tokens: int = Field(ge=0)
    provider_input_tokens: int = Field(default=0, ge=0)
    provider_output_tokens: int = Field(default=0, ge=0)
    provider_latency_ms: int = Field(default=0, ge=0)
    provider: str | None = None
    model: str | None = None

    @model_validator(mode="after")
    def legacy_counters_must_match(self) -> "Usage":
        if (
            self.steps != self.workflow_step_count
            or self.model_calls != self.model_call_count
            or self.tool_calls != self.tool_call_count
        ):
            raise ValueError("legacy usage counters must mirror split counters")
        return self


class InteractionProposal(AgentModel):
    interaction_type: Literal["CLARIFICATION"] = "CLARIFICATION"
    intent: Intent
    question: str
    missing_fields: tuple[str, ...]
    choices: tuple[str, ...] = ()


class ResourceSelectionInteraction(AgentModel):
    interaction_type: Literal["RESOURCE_SELECTION"] = "RESOURCE_SELECTION"
    candidate_set_id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    question: str = Field(min_length=1, max_length=4_000)

    @property
    def missing_fields(self) -> tuple[str, ...]:
        """Source compatibility only; not part of the resource-card wire payload."""
        return ()

    @property
    def choices(self) -> tuple[str, ...]:
        return ()


def _task_parameter_schema(schema: dict[str, JsonValue]) -> None:
    # Deferred import keeps the core wire model independent of creative runtime
    # imports while publishing the same strict visual schema used by execution.
    from app.creative.image_brief import ImageVisualBrief

    schema["properties"] = {"visualBrief": ImageVisualBrief.model_json_schema(by_alias=True)}
    schema["description"] = (
        "IMAGE may include a frozen commerce-image-v1 visualBrief; absent on legacy drafts."
    )


class TaskDraftProposal(AgentModel):
    model_config = ConfigDict(
        json_schema_extra={
            "allOf": [
                {
                    "if": {
                        "properties": {"draftOperation": {"const": "REVISE"}},
                        "required": ["draftOperation"],
                    },
                    "then": {
                        "required": ["previousDraftId"],
                        "properties": {
                            "previousDraftId": {"type": "string", "minLength": 1, "maxLength": 128}
                        },
                    },
                    "else": {"properties": {"previousDraftId": {"type": "null"}}},
                }
            ],
        }
    )

    proposal_only: Literal[True] = True
    task_type: TaskType
    parameter_schema_version: Literal[1, 2] = 1
    space_id: str | None
    product_id: str | None
    source_message_id: str | None = None
    draft_operation: DraftOperation = DraftOperation.NEW
    previous_draft_id: str | None = Field(default=None, min_length=1, max_length=128)
    parameters: dict[str, JsonValue] = Field(json_schema_extra=_task_parameter_schema)
    warnings: tuple[str, ...] = ("请核对草稿内容和预计积分；只有你确认后才会开始执行。",)

    @model_validator(mode="after")
    def require_task_parameter_schema_version(self) -> "TaskDraftProposal":
        if (self.draft_operation is DraftOperation.REVISE) != (self.previous_draft_id is not None):
            raise ValueError("previousDraftId must be supplied only for REVISE")
        expected = 2 if self.task_type is TaskType.ARTICLE else 1
        if self.parameter_schema_version != expected:
            raise ValueError("parameter schema version does not match task type")
        return self


class RunOutcome(AgentModel):
    kind: Literal["MESSAGE", "INTERACTION", "TASK_DRAFT"]
    intent: Intent
    message: str
    interaction: InteractionProposal | ResourceSelectionInteraction | None = None
    task_draft: TaskDraftProposal | None = None
    usage: Usage
    # Java uses this marker only to project the Run's own frozen, authorized
    # report metadata. It is not a report ID or permission to perform an action.
    geo_context_used: bool = False
    business_outcome: BusinessOutcome | None = None


EventType = Literal[
    "run.started",
    "run.progress",
    "message.delta",
    "interaction.required",
    "task-draft.proposed",
    "run.completed",
    "run.failed",
    "usage.reported",
    "heartbeat",
]


class MessageDeltaPayload(AgentModel):
    """Append text by default; replace also permits clearing a provisional native turn."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        json_schema_extra={
            "allOf": [
                {
                    "if": {"properties": {"replace": {"const": True}}, "required": ["replace"]},
                    "else": {"properties": {"delta": {"minLength": 1}}},
                }
            ],
        },
    )
    message_id: str = Field(min_length=1, max_length=128)
    delta: str = Field(max_length=16000)
    replace: StrictBool = False

    @model_validator(mode="after")
    def validate_append(self) -> "MessageDeltaPayload":
        if not self.replace and not self.delta:
            raise ValueError("Append delta cannot be empty")
        return self


class ChatRunEvent(AgentModel):
    schema_version: Literal[1] = 1
    event_id: UUID
    event_type: EventType
    run_id: UUID
    occurred_at: datetime
    sequence: int = Field(ge=1)
    payload: dict[str, JsonValue]


class ChatRunAccepted(AgentModel):
    run_id: UUID
    status: RunStatus
    events_url: str


class ChatRunSnapshot(AgentModel):
    run_id: UUID
    conversation_id: str
    status: RunStatus
    event_count: int
    last_event: ChatRunEvent | None = None


class StopRunResponse(AgentModel):
    run_id: UUID
    accepted: bool
    status: RunStatus

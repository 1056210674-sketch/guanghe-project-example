"""Strict mirror of Java 7b057e4's proposal-only workflow contract."""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Annotated, Literal
from uuid import UUID

from pydantic import (
    AfterValidator,
    BaseModel,
    ConfigDict,
    Field,
    JsonValue,
    StrictInt,
    StrictStr,
    field_validator,
    model_validator,
)
from pydantic.alias_generators import to_camel

from app.common.enums import TaskType

_SNOWFLAKE = r"^[1-9][0-9]{0,18}$"
_STEP = r"^step-[1-9][0-9]?$"
_INPUT_REF = r"^(context|observation|task|asset|geo|article):[A-Za-z0-9._-]{1,100}$"
_OUTPUT_REF = r"^(observation|draft):[A-Za-z0-9._-]{1,100}$"


def _signed_long_id(value: str) -> str:
    if int(value) > 9_223_372_036_854_775_807:
        raise ValueError("Snowflake exceeds Java signed long")
    return value


SnowflakeId = Annotated[
    StrictStr,
    Field(pattern=_SNOWFLAKE),
    AfterValidator(_signed_long_id),
]


class WorkflowModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        allow_inf_nan=False,
    )


class WorkflowStepType(StrEnum):
    QUERY = "QUERY"
    ANALYZE = "ANALYZE"
    PREPARE = "PREPARE"
    SYNTHESIZE = "SYNTHESIZE"


class WorkflowToolName(StrEnum):
    CATALOG_INSPECT = "context.catalog.inspect"
    MEMORY_SEARCH = "context.memory.search"
    WORKSPACE_INSPECT = "context.task_workspace.inspect"
    COMPETITOR_COMPARE = "analysis.competitor.compare"
    EVIDENCE_AGGREGATE = "analysis.evidence.aggregate"
    TASK_DRAFT_PROPOSE = "task_draft.propose"


class WorkflowPlanBudget(WorkflowModel):
    max_steps: StrictInt = Field(ge=2, le=12)
    max_replans: StrictInt = Field(ge=0, le=2)
    max_model_calls: StrictInt = Field(ge=1, le=32)
    max_tool_calls: StrictInt = Field(ge=2, le=32)
    max_provider_calls: StrictInt = Field(ge=0, le=12)
    max_point_cost: StrictInt = Field(ge=1, le=1_000_000)
    deadline_at: datetime

    @field_validator("deadline_at")
    @classmethod
    def deadline_is_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("deadlineAt must include a timezone")
        return value


class WorkflowTaskDraftProposal(WorkflowModel):
    task_type: Literal[TaskType.IMAGE, TaskType.VIDEO, TaskType.GEO, TaskType.ARTICLE]
    parameter_schema_version: Literal[1, 2]
    parameters: dict[StrictStr, JsonValue] = Field(max_length=32)

    @model_validator(mode="after")
    def schema_version_and_article_authority(self) -> WorkflowTaskDraftProposal:
        expected = 2 if self.task_type is TaskType.ARTICLE else 1
        if self.parameter_schema_version != expected:
            raise ValueError("workflow task schema version does not match task type")
        if self.task_type is TaskType.ARTICLE and any(
            field in self.parameters
            for field in (
                "articleId",
                "articleVersion",
                "brief",
                "selectedTitle",
                "confirmedPlan",
                "confirmedOutline",
                "geoReportId",
                "geoRecommendationId",
            )
        ):
            raise ValueError("article workflow proposal contains Java-owned fields")
        return self


SuccessCriterion = Annotated[StrictStr, Field(min_length=1, max_length=200)]
StepRef = Annotated[StrictStr, Field(pattern=_STEP)]
InputRef = Annotated[StrictStr, Field(pattern=_INPUT_REF)]


class WorkflowPlanStepProposal(WorkflowModel):
    step_id: StrictStr = Field(pattern=_STEP)
    ordinal: StrictInt = Field(ge=1, le=12)
    type: WorkflowStepType
    tool_name: WorkflowToolName
    tool_version: Literal["1"] = "1"
    risk: Literal["QUERY", "ANALYZE", "PREPARE"]
    depends_on: tuple[StepRef, ...] = Field(default=(), max_length=11)
    success_criteria: tuple[SuccessCriterion, ...] = Field(min_length=1, max_length=5)
    input_refs: tuple[InputRef, ...] = Field(default=(), max_length=8)
    output_ref: StrictStr | None = Field(default=None, pattern=_OUTPUT_REF)
    task_draft: WorkflowTaskDraftProposal | None = None

    @model_validator(mode="after")
    def validate_shape(self) -> WorkflowPlanStepProposal:
        if len(self.depends_on) != len(set(self.depends_on)):
            raise ValueError("dependsOn must be unique")
        if len(self.success_criteria) != len(set(self.success_criteria)):
            raise ValueError("successCriteria must be unique")
        allowed = {
            WorkflowToolName.CATALOG_INSPECT: ({WorkflowStepType.QUERY}, "QUERY"),
            WorkflowToolName.MEMORY_SEARCH: ({WorkflowStepType.QUERY}, "QUERY"),
            WorkflowToolName.WORKSPACE_INSPECT: ({WorkflowStepType.QUERY}, "QUERY"),
            WorkflowToolName.COMPETITOR_COMPARE: ({WorkflowStepType.ANALYZE}, "ANALYZE"),
            WorkflowToolName.EVIDENCE_AGGREGATE: (
                {WorkflowStepType.ANALYZE, WorkflowStepType.SYNTHESIZE},
                "ANALYZE",
            ),
            WorkflowToolName.TASK_DRAFT_PROPOSE: ({WorkflowStepType.PREPARE}, "PREPARE"),
        }
        step_types, risk = allowed[self.tool_name]
        if self.type not in step_types or self.risk != risk:
            raise ValueError("tool, type, and risk do not match Java policy")
        if self.type is WorkflowStepType.PREPARE:
            if self.task_draft is None:
                raise ValueError("PREPARE must carry one proposal-only draft")
        elif self.task_draft is not None:
            raise ValueError("only PREPARE can carry taskDraft")
        return self


class WorkflowPlanProposalRequest(WorkflowModel):
    proposal_id: UUID
    conversation_id: SnowflakeId
    goal_summary: StrictStr = Field(min_length=1, max_length=500)
    budget: WorkflowPlanBudget
    steps: tuple[WorkflowPlanStepProposal, ...] = Field(min_length=2, max_length=12)

    @model_validator(mode="after")
    def validate_dag(self) -> WorkflowPlanProposalRequest:
        ids = tuple(step.step_id for step in self.steps)
        if len(ids) != len(set(ids)):
            raise ValueError("stepId must be unique")
        if tuple(step.ordinal for step in self.steps) != tuple(range(1, len(self.steps) + 1)):
            raise ValueError("step ordinals must be consecutive")
        ordinal = {step.step_id: step.ordinal for step in self.steps}
        for step in self.steps:
            if step.step_id != f"step-{step.ordinal}":
                raise ValueError("stepId and ordinal must match")
            if any(
                item not in ordinal or ordinal[item] >= step.ordinal for item in step.depends_on
            ):
                raise ValueError("dependencies must reference earlier steps")
        prepare_count = sum(step.type is WorkflowStepType.PREPARE for step in self.steps)
        if prepare_count < 2:
            raise ValueError("Java requires at least two PREPARE steps")
        if self.budget.max_steps < len(self.steps) or self.budget.max_tool_calls < len(self.steps):
            raise ValueError("workflow step budget is too small")
        if self.budget.max_provider_calls < prepare_count:
            raise ValueError("workflow provider budget is too small")
        return self


class WorkflowDraftReference(WorkflowModel):
    draft_id: SnowflakeId
    task_type: Literal[TaskType.IMAGE, TaskType.VIDEO, TaskType.GEO, TaskType.ARTICLE]
    pricing_version_id: SnowflakeId
    quoted_points: StrictInt = Field(ge=1)


class WorkflowPlanStep(WorkflowModel):
    step_id: StrictStr = Field(pattern=_STEP)
    ordinal: StrictInt = Field(ge=1, le=12)
    type: WorkflowStepType
    tool_name: StrictStr = Field(max_length=128)
    tool_version: Literal["1"]
    risk: Literal["QUERY", "ANALYZE", "PREPARE"]
    depends_on: tuple[StrictStr, ...] = Field(max_length=11)
    success_criteria: tuple[StrictStr, ...] = Field(min_length=1, max_length=5)
    input_refs: tuple[StrictStr, ...] = Field(max_length=8)
    output_ref: StrictStr | None
    task_draft: WorkflowDraftReference | None
    status: Literal["PENDING"]
    task_id: SnowflakeId | None
    task_status: StrictStr | None = Field(pattern=r"^[A-Z][A-Z0-9_]{0,63}$")
    task_result_ref: StrictStr | None = Field(max_length=128)


class WorkflowConfirmationPreview(WorkflowModel):
    plan_version: StrictInt = Field(ge=1)
    quoted_points: StrictInt = Field(ge=1)
    max_point_cost: StrictInt = Field(ge=1)
    expires_at: datetime
    consumption_enabled: Literal[False]


class WorkflowEvent(WorkflowModel):
    sequence: StrictInt = Field(ge=1)
    event_type: Literal["PLAN_PROPOSED", "PLAN_WAITING_CONFIRMATION", "PLAN_ABANDONED"]
    from_status: StrictStr | None
    to_status: Literal["DRAFT", "WAITING_CONFIRMATION", "ABANDONED"]
    stable_error_code: StrictStr | None = Field(default=None, pattern=r"^[A-Z][A-Z0-9_]{0,63}$")
    occurred_at: datetime


class WorkflowPlan(WorkflowModel):
    id: SnowflakeId
    conversation_id: SnowflakeId
    space_id: SnowflakeId
    product_id: SnowflakeId | None = None
    goal_summary: StrictStr = Field(min_length=1, max_length=500)
    status: Literal["WAITING_CONFIRMATION", "ABANDONED"]
    version: StrictInt = Field(ge=1)
    budget: WorkflowPlanBudget
    total_quoted_points: StrictInt = Field(ge=1)
    steps: tuple[WorkflowPlanStep, ...] = Field(min_length=2, max_length=12)
    confirmation: WorkflowConfirmationPreview
    events: tuple[WorkflowEvent, ...] = Field(min_length=2)
    abandoned_at: datetime | None = None
    created_at: datetime
    updated_at: datetime
    execution_enabled: bool
    execution_started: bool
    last_stable_error: StrictStr | None = Field(pattern=r"^[A-Z][A-Z0-9_]{0,63}$")

    @model_validator(mode="after")
    def validate_new_plan(self) -> WorkflowPlan:
        if self.status != "WAITING_CONFIRMATION":
            raise ValueError("new proposal must wait for confirmation")
        if self.execution_started or self.last_stable_error is not None:
            raise ValueError("new proposal cannot already execute or carry a failure")
        if any(
            step.task_id is not None
            or step.task_status is not None
            or step.task_result_ref is not None
            for step in self.steps
        ):
            raise ValueError("new proposal cannot contain task execution results")
        if self.confirmation.quoted_points != self.total_quoted_points:
            raise ValueError("confirmation quote must match plan total")
        if self.confirmation.max_point_cost != self.budget.max_point_cost:
            raise ValueError("confirmation cap must match plan budget")
        sequences = tuple(event.sequence for event in self.events)
        if sequences != tuple(range(1, len(sequences) + 1)):
            raise ValueError("event sequence must be consecutive")
        if tuple(event.event_type for event in self.events[:2]) != (
            "PLAN_PROPOSED",
            "PLAN_WAITING_CONFIRMATION",
        ):
            raise ValueError("proposal events are missing")
        return self

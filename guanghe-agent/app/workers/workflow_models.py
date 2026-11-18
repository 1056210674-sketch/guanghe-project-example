"""Strict Java P3 workflow-execution and Rabbit envelope models."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import Annotated, Literal, Self
from uuid import NAMESPACE_URL, UUID, uuid5

from pydantic import (
    AfterValidator,
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    JsonValue,
    StrictBool,
    StrictInt,
    StrictStr,
    StringConstraints,
    field_validator,
    model_validator,
)
from pydantic.alias_generators import to_camel

from app.providers.java_context import JavaRunContextResponse

_MAX_LONG = "9223372036854775807"


def _signed_long(value: str) -> str:
    if len(value) > len(_MAX_LONG) or (len(value) == len(_MAX_LONG) and value > _MAX_LONG):
        raise ValueError("identifier exceeds signed long")
    return value


def _task_result_ref(value: str) -> str:
    _prefix, identifier = value.split(":", 1)
    _signed_long(identifier)
    return value


BusinessId = Annotated[
    StrictStr,
    StringConstraints(pattern=r"^[1-9][0-9]{0,18}$"),
    AfterValidator(_signed_long),
]
StepId = Annotated[StrictStr, StringConstraints(pattern=r"^step-[1-9][0-9]?$")]
StableCode = Annotated[StrictStr, StringConstraints(pattern=r"^[A-Z][A-Z0-9_]{0,63}$")]
InputRef = Annotated[
    StrictStr,
    StringConstraints(min_length=1, max_length=128),
]
OutputRef = Annotated[StrictStr, StringConstraints(min_length=1, max_length=128)]
TaskResultRef = Annotated[
    StrictStr,
    StringConstraints(pattern=r"^(task|asset|geo-report|article):[1-9][0-9]{0,18}$"),
    AfterValidator(_task_result_ref),
]


class WorkflowModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        allow_inf_nan=False,
        hide_input_in_errors=True,
    )


class WorkflowExecutionMessage(WorkflowModel):
    schema_version: Literal[1]
    event_id: BusinessId
    event_type: Literal["WORKFLOW_EXECUTION_REQUESTED"]
    plan_id: BusinessId
    plan_version: StrictInt = Field(ge=1)
    attempt: StrictInt = Field(ge=1, le=32)
    resume_only: StrictBool = False
    traceparent: StrictStr = Field(pattern=r"^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")

    @field_validator("traceparent")
    @classmethod
    def validate_traceparent(cls, value: str) -> str:
        version, trace_id, parent_id, _flags = value.split("-")
        if version == "ff" or trace_id == "0" * 32 or parent_id == "0" * 16:
            raise ValueError("forbidden W3C trace identifier")
        return value


class WorkflowPlanIdentity(WorkflowModel):
    id: BusinessId
    conversation_id: BusinessId
    version: StrictInt = Field(ge=1)
    status: Literal["RUNNING"]
    goal_summary: StrictStr = Field(min_length=1, max_length=500)


class WorkflowStep(WorkflowModel):
    id: StepId
    ordinal: StrictInt = Field(ge=1, le=12)
    type: Literal["QUERY", "ANALYZE", "PREPARE", "SYNTHESIZE"]
    tool_name: Literal[
        "context.catalog.inspect",
        "context.memory.search",
        "context.task_workspace.inspect",
        "analysis.competitor.compare",
        "analysis.evidence.aggregate",
        "task_draft.propose",
    ]
    tool_version: Literal["1"]
    risk: Literal["QUERY", "ANALYZE", "PREPARE"]
    status: Literal["PENDING", "READY", "RUNNING", "WAITING", "SUCCEEDED", "FAILED", "SKIPPED"]
    depends_on: tuple[StepId, ...] = Field(max_length=12)
    success_criteria: tuple[
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=300)], ...
    ] = Field(min_length=1, max_length=8)
    input_refs: tuple[InputRef, ...] = Field(max_length=16)
    output_ref: OutputRef | None = None
    task_draft_id: BusinessId | None = None
    task_id: BusinessId | None = None
    task_status: (
        Literal[
            "QUEUED",
            "RUNNING",
            "RETRY_WAIT",
            "SUCCEEDED",
            "PARTIAL",
            "FAILED",
            "SKIPPED",
            "SYSTEM_TERMINATED",
        ]
        | None
    ) = None
    task_result_ref: TaskResultRef | None = None

    @model_validator(mode="after")
    def validate_tool_semantics(self) -> Self:
        expected = (
            "PREPARE"
            if self.tool_name == "task_draft.propose"
            else "QUERY"
            if self.tool_name.startswith("context.")
            else "ANALYZE"
        )
        if self.risk != expected:
            raise ValueError("tool risk does not match registry semantics")
        if self.type == "PREPARE" and self.tool_name != "task_draft.propose":
            raise ValueError("PREPARE must use the proposal-only tool")
        allowed_types = (
            {"QUERY"}
            if self.tool_name.startswith("context.")
            else {"PREPARE"}
            if self.tool_name == "task_draft.propose"
            else {"ANALYZE", "SYNTHESIZE"}
            if self.tool_name == "analysis.evidence.aggregate"
            else {"ANALYZE"}
        )
        if self.type not in allowed_types:
            raise ValueError("step type does not match the frozen Java tool policy")
        task_fields = (
            self.task_draft_id,
            self.task_id,
            self.task_status,
            self.task_result_ref,
        )
        if self.type != "PREPARE" and any(item is not None for item in task_fields):
            raise ValueError("task fields are only valid for PREPARE")
        if self.type == "PREPARE" and self.task_draft_id is None:
            raise ValueError("PREPARE requires a persisted taskDraftId")
        if self.type == "PREPARE":
            self._validate_prepare_lifecycle()
        if len(self.depends_on) != len(set(self.depends_on)):
            raise ValueError("dependencies must be unique")
        if len(self.success_criteria) != len(set(self.success_criteria)):
            raise ValueError("success criteria must be unique")
        return self

    def _validate_prepare_lifecycle(self) -> None:
        if self.task_id is None:
            if self.task_status is not None or self.task_result_ref is not None:
                raise ValueError("unactivated PREPARE cannot contain task result fields")
            if self.status not in {"PENDING", "READY", "RUNNING"}:
                raise ValueError("unactivated PREPARE has an invalid state")
            return
        if self.task_status is None:
            raise ValueError("activated PREPARE requires taskStatus")
        if self.task_status in {"QUEUED", "RUNNING", "RETRY_WAIT"}:
            if self.status != "WAITING" or self.task_result_ref is not None:
                raise ValueError("active Task must keep PREPARE in WAITING")
            return
        if self.task_status in {"SUCCEEDED", "PARTIAL"}:
            if self.status != "SUCCEEDED" or self.task_result_ref is None:
                raise ValueError("successful Task requires a durable result reference")
            return
        if self.status != "FAILED" or self.task_result_ref is not None:
            raise ValueError("failed Task must leave PREPARE failed without a result reference")


class WorkflowObservation(WorkflowModel):
    id: BusinessId
    step_id: StepId
    event_id: UUID
    tool_call_id: UUID
    step_attempt: StrictInt = Field(ge=1, le=10)
    status: Literal["SUCCEEDED", "FAILED", "WAITING"]
    result_type: StableCode
    result_ref: StrictStr | None = Field(default=None, max_length=256)
    summary: StrictStr = Field(min_length=1, max_length=1000)
    facts: dict[StrictStr, JsonValue] = Field(max_length=100)
    stable_error_code: StableCode | None = None
    retryable: StrictBool
    completed_at: AwareDatetime

    @model_validator(mode="after")
    def validate_failure_code(self) -> Self:
        if (self.status == "SUCCEEDED") != (self.stable_error_code is None):
            raise ValueError("only SUCCEEDED observations omit stableErrorCode")
        encoded = json.dumps(
            self.facts,
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
        ).encode()
        if len(encoded) > 32_768:
            raise ValueError("observation facts exceed Java's byte limit")
        return self


class WorkflowBudget(WorkflowModel):
    max_steps: StrictInt = Field(ge=2, le=12)
    max_replans: StrictInt = Field(ge=0, le=1)
    max_model_calls: StrictInt = Field(ge=1, le=32)
    max_tool_calls: StrictInt = Field(ge=2, le=32)
    max_provider_calls: StrictInt = Field(ge=0, le=12)
    authorized_max_points: StrictInt = Field(ge=1, le=1_000_000)
    deadline_at: AwareDatetime

    @field_validator("deadline_at")
    @classmethod
    def deadline_must_be_future(cls, value: datetime) -> datetime:
        if value <= datetime.now(UTC):
            raise ValueError("workflow deadline has expired")
        return value


class WorkflowExecutionContext(WorkflowModel):
    plan_id: BusinessId
    plan_version: StrictInt = Field(ge=1)
    execution_no: StrictInt = Field(ge=1, le=32)
    plan: WorkflowPlanIdentity
    steps: tuple[WorkflowStep, ...] = Field(min_length=2, max_length=12)
    observations: tuple[WorkflowObservation, ...]
    budget: WorkflowBudget
    resume_only: StrictBool
    authorized_context: JavaRunContextResponse
    authorized_context_schema_version: Literal[1]
    authorized_context_hash: StrictStr = Field(pattern=r"^[0-9a-f]{64}$")
    authorized_context_source: Literal["PLAN_SNAPSHOT"]

    @model_validator(mode="after")
    def validate_dag(self) -> Self:
        if self.plan.id != self.plan_id or self.plan.version != self.plan_version:
            raise ValueError("plan identity mismatch")
        if self.resume_only != (self.execution_no > 1):
            raise ValueError("resumeOnly must match the execution generation")
        if self.authorized_context.conversation_id != self.plan.conversation_id:
            raise ValueError("authorized Context conversation does not match the plan")
        if self.authorized_context.space is None:
            raise ValueError("workflow requires an authorized operating space")
        encoded_context = json.dumps(
            self.authorized_context.model_dump(mode="json", by_alias=True),
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
        ).encode()
        if len(encoded_context) > 196_608:
            raise ValueError("authorized Context exceeds the Java snapshot boundary")
        if len(self.steps) > self.budget.max_steps:
            raise ValueError("step count exceeds budget")
        ids = [step.id for step in self.steps]
        if len(ids) != len(set(ids)):
            raise ValueError("step IDs must be unique")
        if [step.ordinal for step in self.steps] != list(range(1, len(self.steps) + 1)):
            raise ValueError("step ordinals must be consecutive")
        ordinal = {step.id: step.ordinal for step in self.steps}
        for step in self.steps:
            if step.id != f"step-{step.ordinal}":
                raise ValueError("step identity and ordinal mismatch")
            if any(dep not in ordinal or ordinal[dep] >= step.ordinal for dep in step.depends_on):
                raise ValueError("workflow DAG is invalid")
        known_steps = set(ids)
        if any(item.step_id not in known_steps for item in self.observations):
            raise ValueError("observation references an unknown step")
        events = [item.event_id for item in self.observations]
        calls = [item.tool_call_id for item in self.observations]
        attempts = [(item.step_id, item.step_attempt) for item in self.observations]
        if (
            len(events) != len(set(events))
            or len(calls) != len(set(calls))
            or len(attempts) != len(set(attempts))
        ):
            raise ValueError("workflow observations must be idempotently unique")
        return self

    def assert_matches(self, message: WorkflowExecutionMessage, execution_no: int) -> None:
        if (
            self.plan_id != message.plan_id
            or self.plan_version != message.plan_version
            or self.execution_no != execution_no
            or self.resume_only != message.resume_only
        ):
            raise ValueError("execution context does not match delivery")


def stable_workflow_uuid(plan_id: str, execution_no: int, step_id: str, purpose: str) -> UUID:
    """Deterministic idempotency key containing no secret or business text."""
    return uuid5(NAMESPACE_URL, f"zhixiaohe:workflow:{plan_id}:{execution_no}:{step_id}:{purpose}")


__all__ = [
    "WorkflowBudget",
    "WorkflowExecutionContext",
    "WorkflowExecutionMessage",
    "WorkflowObservation",
    "WorkflowStep",
    "stable_workflow_uuid",
]

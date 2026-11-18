"""Validated Rabbit envelope and safe Worker diagnostics models."""

from datetime import datetime
from enum import StrEnum
from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, JsonValue, field_validator, model_validator
from pydantic.alias_generators import to_camel

from app.common.enums import TaskType
from app.providers.models import ProviderArtifact

DECIMAL_ID_PATTERN = r"^[1-9][0-9]*$"
TRACEPARENT_PATTERN = r"^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$"


class WorkerModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class TaskExecutionMessage(WorkerModel):
    schema_version: Literal[1]
    event_id: str = Field(pattern=DECIMAL_ID_PATTERN)
    event_type: Literal["TASK_EXECUTION_REQUESTED"]
    task_id: str = Field(pattern=DECIMAL_ID_PATTERN)
    task_type: TaskType
    user_id: str = Field(pattern=DECIMAL_ID_PATTERN)
    space_id: str = Field(pattern=DECIMAL_ID_PATTERN)
    attempt: int = Field(ge=1, strict=True)
    traceparent: str = Field(pattern=TRACEPARENT_PATTERN)
    created_at: datetime

    @field_validator("task_type")
    @classmethod
    def task_type_must_be_owned_by_python(cls, value: TaskType) -> TaskType:
        if value is TaskType.MEDIA:
            raise ValueError("MEDIA is routed to Java, not Python Worker")
        return value

    @field_validator("traceparent")
    @classmethod
    def traceparent_must_be_w3c_valid(cls, value: str) -> str:
        version, trace_id, parent_id, _flags = value.split("-")
        if version == "ff" or trace_id == "0" * 32 or parent_id == "0" * 16:
            raise ValueError("traceparent contains a forbidden W3C identifier")
        return value

    @field_validator("created_at")
    @classmethod
    def created_at_must_include_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("createdAt must include a timezone")
        return value


class DevWorkerTaskRequest(WorkerModel):
    task_type: TaskType
    parameters: dict[str, JsonValue] = Field(default_factory=dict)

    @model_validator(mode="after")
    def reject_media(self) -> Self:
        if self.task_type is TaskType.MEDIA:
            raise ValueError("MEDIA is routed to Java, not Python Worker")
        return self


class WorkerTaskStatus(StrEnum):
    QUEUED = "QUEUED"
    CLAIMING = "CLAIMING"
    RUNNING = "RUNNING"
    RESULT_READY = "RESULT_READY"
    HANDOFF_ACCEPTED = "HANDOFF_ACCEPTED"
    FAILED = "FAILED"
    SUBMISSION_UNKNOWN = "SUBMISSION_UNKNOWN"
    DUPLICATE = "DUPLICATE"
    REJECTED = "REJECTED"


class WorkerTaskSnapshot(WorkerModel):
    event_id: str
    task_id: str | None = None
    task_type: TaskType | None = None
    status: WorkerTaskStatus
    delivery_state: Literal["PENDING", "ACKED", "NACKED", "REJECTED"] = "PENDING"
    execution_no: int | None = None
    provider: str | None = None
    provider_model: str | None = None
    provider_task_id: str | None = None
    artifact_count: int = Field(default=0, ge=0)
    artifacts: tuple[ProviderArtifact, ...] = ()
    error_code: str | None = None
    updated_at: datetime


class WorkerStatusSnapshot(WorkerModel):
    status: Literal["STARTING", "READY", "DRAINING", "STOPPED", "NOT_READY"]
    service: str
    service_version: str
    worker_id: str
    task_type: str
    control_plane: str
    provider: str
    provider_model: str
    mock_mode: bool
    accepting: bool
    queue_depth: int = Field(ge=0)
    in_flight: int = Field(ge=0)
    processed: int = Field(ge=0)
    failed: int = Field(ge=0)
    reason_code: str | None = None


class DevWorkerTaskAccepted(WorkerModel):
    event_id: str
    task_id: str
    status: WorkerTaskStatus
    status_url: str

"""Java-authoritative workflow execution control plane over the shared HMAC client."""

from __future__ import annotations

from collections.abc import Awaitable
from typing import Literal, Protocol, cast
from uuid import UUID

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    StrictInt,
    StrictStr,
    ValidationError,
    model_validator,
)
from pydantic.alias_generators import to_camel

from app.common.settings import AppSettings
from app.integration.java_internal import (
    JavaInternalClientError,
    JavaInternalErrorCode,
    JavaInternalResponse,
    JavaWorkflowExecutionClientProtocol,
    build_java_internal_client,
)
from app.workers.control_plane import (
    ControlPlaneRejected,
    ControlPlaneUnavailable,
)
from app.workers.workflow_models import (
    BusinessId,
    StepId,
    WorkflowExecutionContext,
    WorkflowExecutionMessage,
    WorkflowObservation,
    WorkflowStep,
)


class _Model(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        hide_input_in_errors=True,
    )


class WorkflowClaim(_Model):
    claimed: StrictBool
    disposition: Literal["CLAIMED", "DUPLICATE", "TERMINAL"]
    execution_no: StrictInt | None = Field(default=None, ge=1, le=32)
    execution_token: StrictStr | None = Field(default=None, min_length=1, max_length=512)
    lease_expires_at: AwareDatetime | None = None


class WorkflowAccepted(_Model):
    accepted: StrictBool
    disposition: Literal["RECORDED", "DUPLICATE"]
    plan_status: Literal["RUNNING", "WAITING_INPUT", "SUCCEEDED", "FAILED"]
    plan_version: StrictInt = Field(ge=1)

    @model_validator(mode="after")
    def validate_disposition(self) -> WorkflowAccepted:
        if self.accepted != (self.disposition == "RECORDED"):
            raise ValueError("accepted must match the workflow disposition")
        return self


class WorkflowObservationAccepted(_Model):
    accepted: StrictBool
    disposition: Literal["RECORDED", "DUPLICATE"]
    plan_status: Literal["RUNNING", "WAITING_INPUT", "SUCCEEDED", "FAILED"]
    sequence: StrictInt = Field(ge=0)

    @model_validator(mode="after")
    def validate_disposition(self) -> WorkflowObservationAccepted:
        if self.accepted != (self.disposition == "RECORDED"):
            raise ValueError("accepted must match the observation disposition")
        return self


class WorkflowTaskActivation(_Model):
    accepted: StrictBool
    disposition: Literal["ACTIVATED", "DUPLICATE"]
    plan_id: BusinessId
    step_id: StepId
    task_id: BusinessId
    task_status: Literal[
        "QUEUED",
        "RUNNING",
        "RETRY_WAIT",
        "SUCCEEDED",
        "PARTIAL",
        "FAILED",
        "SKIPPED",
        "SYSTEM_TERMINATED",
    ]
    step_status: Literal["WAITING", "SUCCEEDED", "FAILED"]
    waiting_for_task: Literal[True]
    execution_no: StrictInt = Field(ge=1, le=32)

    @model_validator(mode="after")
    def validate_disposition(self) -> WorkflowTaskActivation:
        if self.accepted != (self.disposition == "ACTIVATED"):
            raise ValueError("accepted must match the activation disposition")
        return self


class WorkflowControlPlane(Protocol):
    async def probe(self) -> bool: ...
    async def claim(
        self,
        message: WorkflowExecutionMessage,
        *,
        consumer_name: str,
        worker_id: str,
        bundle_version: str,
    ) -> WorkflowClaim: ...
    async def context(
        self, plan_id: str, *, execution_no: int, execution_token: str, traceparent: str
    ) -> WorkflowExecutionContext: ...
    async def heartbeat(
        self,
        plan_id: str,
        *,
        execution_no: int,
        execution_token: str,
        stage_code: str,
        traceparent: str,
    ) -> None: ...
    async def step_state(
        self,
        plan_id: str,
        step_id: str,
        *,
        execution_no: int,
        execution_token: str,
        status: str,
        event_id: UUID,
        traceparent: str,
        stable_error_code: str | None = None,
    ) -> WorkflowAccepted: ...
    async def observation(
        self,
        plan_id: str,
        *,
        execution_no: int,
        execution_token: str,
        observation: WorkflowObservation,
        traceparent: str,
    ) -> WorkflowObservationAccepted: ...
    async def activate_task(
        self,
        plan_id: str,
        step_id: str,
        *,
        execution_no: int,
        execution_token: str,
        plan_version: int,
        task_draft_id: str,
        event_id: UUID,
        traceparent: str,
    ) -> WorkflowTaskActivation: ...
    async def replan(
        self,
        plan_id: str,
        *,
        execution_no: int,
        execution_token: str,
        expected_version: int,
        steps: tuple[WorkflowStep, ...],
        event_id: UUID,
        traceparent: str,
    ) -> WorkflowAccepted: ...
    async def complete(
        self,
        plan_id: str,
        *,
        execution_no: int,
        execution_token: str,
        status: str,
        event_id: UUID,
        traceparent: str,
        stable_error_code: str | None = None,
    ) -> WorkflowAccepted: ...
    async def aclose(self) -> None: ...


class JavaWorkflowControlPlane:
    def __init__(self, client: JavaWorkflowExecutionClientProtocol) -> None:
        self._client = client

    async def probe(self) -> bool:
        try:
            return (await self._client.reverse_call()).accepted
        except JavaInternalClientError as exc:
            raise _mapped(exc) from None

    async def claim(
        self,
        message: WorkflowExecutionMessage,
        *,
        consumer_name: str,
        worker_id: str,
        bundle_version: str,
    ) -> WorkflowClaim:
        payload = {
            "eventId": message.event_id,
            "consumerName": consumer_name,
            "workerId": worker_id,
            "supportedBundleVersion": bundle_version,
            "executionNo": message.attempt,
            "planVersion": message.plan_version,
        }
        response = await self._call(
            self._client.claim_workflow_execution(message.plan_id, payload, message.traceparent)
        )
        value = _parse(WorkflowClaim, response)
        if value.disposition == "CLAIMED" and (
            not value.claimed
            or value.execution_no is None
            or value.execution_no != message.attempt
            or value.execution_token is None
            or value.lease_expires_at is None
        ):
            raise ControlPlaneRejected("WORKFLOW_CLAIM_RESPONSE_INVALID")
        # Java returns the original executionNo/leaseExpiresAt for a duplicate claim so
        # operators can correlate the already-owned generation.  It never returns the
        # write-only execution token, and neither DUPLICATE nor TERMINAL may be executed.
        if value.disposition != "CLAIMED" and (value.claimed or value.execution_token is not None):
            raise ControlPlaneRejected("WORKFLOW_CLAIM_RESPONSE_INVALID")
        return value

    async def context(
        self, plan_id: str, *, execution_no: int, execution_token: str, traceparent: str
    ) -> WorkflowExecutionContext:
        response = await self._call(
            self._client.get_workflow_execution_context(
                plan_id,
                execution_no=execution_no,
                execution_token=execution_token,
                traceparent=traceparent,
            )
        )
        return _parse(WorkflowExecutionContext, response)

    async def heartbeat(
        self,
        plan_id: str,
        *,
        execution_no: int,
        execution_token: str,
        stage_code: str,
        traceparent: str,
    ) -> None:
        response = await self._call(
            self._client.heartbeat_workflow_execution(
                plan_id,
                {
                    "executionNo": execution_no,
                    "executionToken": execution_token,
                    "stageCode": stage_code,
                },
                traceparent,
            )
        )
        value = _parse(_Heartbeat, response)
        if not value.accepted:
            raise ControlPlaneRejected("WORKFLOW_HEARTBEAT_REJECTED")

    async def step_state(
        self,
        plan_id: str,
        step_id: str,
        *,
        execution_no: int,
        execution_token: str,
        status: str,
        event_id: UUID,
        traceparent: str,
        stable_error_code: str | None = None,
    ) -> WorkflowAccepted:
        payload: dict[str, object] = {
            "eventId": str(event_id),
            "executionNo": execution_no,
            "executionToken": execution_token,
            "status": status,
        }
        if stable_error_code is not None:
            payload["stableErrorCode"] = stable_error_code
        response = await self._call(
            self._client.report_workflow_step_state(plan_id, step_id, payload, traceparent)
        )
        value = _parse(WorkflowAccepted, response)
        return value

    async def observation(
        self,
        plan_id: str,
        *,
        execution_no: int,
        execution_token: str,
        observation: WorkflowObservation,
        traceparent: str,
    ) -> WorkflowObservationAccepted:
        payload = observation.model_dump(
            mode="json", by_alias=True, exclude={"id"}, exclude_none=True
        )
        payload["executionNo"] = execution_no
        payload["executionToken"] = execution_token
        response = await self._call(
            self._client.report_workflow_observation(
                plan_id, cast(dict[str, object], payload), traceparent
            )
        )
        value = _parse(WorkflowObservationAccepted, response)
        return value

    async def activate_task(
        self,
        plan_id: str,
        step_id: str,
        *,
        execution_no: int,
        execution_token: str,
        plan_version: int,
        task_draft_id: str,
        event_id: UUID,
        traceparent: str,
    ) -> WorkflowTaskActivation:
        response = await self._call(
            self._client.activate_workflow_step_task(
                plan_id,
                step_id,
                {
                    "eventId": str(event_id),
                    "executionNo": execution_no,
                    "executionToken": execution_token,
                    "planVersion": plan_version,
                    "taskDraftId": task_draft_id,
                },
                traceparent,
            )
        )
        value = _parse(WorkflowTaskActivation, response)
        if (
            value.plan_id != plan_id
            or value.step_id != step_id
            or value.execution_no != execution_no
        ):
            raise ControlPlaneRejected("WORKFLOW_TASK_ACTIVATION_RESPONSE_INVALID")
        return value

    async def replan(
        self,
        plan_id: str,
        *,
        execution_no: int,
        execution_token: str,
        expected_version: int,
        steps: tuple[WorkflowStep, ...],
        event_id: UUID,
        traceparent: str,
    ) -> WorkflowAccepted:
        revisions = [
            {
                "stepId": step.id,
                "dependsOn": list(step.depends_on),
                "successCriteria": list(step.success_criteria),
                "inputRefs": list(step.input_refs),
                "outputRef": step.output_ref,
            }
            for step in steps
            if step.status in {"PENDING", "READY"}
        ]
        response = await self._call(
            self._client.replan_workflow_execution(
                plan_id,
                {
                    "eventId": str(event_id),
                    "executionNo": execution_no,
                    "executionToken": execution_token,
                    "expectedPlanVersion": expected_version,
                    "newPlanVersion": expected_version + 1,
                    "steps": revisions,
                },
                traceparent,
            )
        )
        value = _parse(WorkflowAccepted, response)
        return value

    async def complete(
        self,
        plan_id: str,
        *,
        execution_no: int,
        execution_token: str,
        status: str,
        event_id: UUID,
        traceparent: str,
        stable_error_code: str | None = None,
    ) -> WorkflowAccepted:
        payload: dict[str, object] = {
            "eventId": str(event_id),
            "executionNo": execution_no,
            "executionToken": execution_token,
            "status": status,
        }
        if stable_error_code is not None:
            payload["stableErrorCode"] = stable_error_code
        response = await self._call(
            self._client.complete_workflow_execution(plan_id, payload, traceparent)
        )
        value = _parse(WorkflowAccepted, response)
        return value

    async def _call(self, operation: Awaitable[JavaInternalResponse]) -> dict[str, object]:
        try:
            response = await operation
        except JavaInternalClientError as exc:
            raise _mapped(exc) from None
        return response.payload  # type: ignore[union-attr]

    async def aclose(self) -> None:
        await self._client.aclose()


class _Heartbeat(_Model):
    accepted: Literal[True]
    lease_expires_at: AwareDatetime


def _parse[ModelT: BaseModel](model: type[ModelT], payload: dict[str, object]) -> ModelT:
    try:
        return model.model_validate(payload)
    except ValidationError:
        raise ControlPlaneRejected("WORKFLOW_CONTROL_RESPONSE_INVALID") from None


def _mapped(exc: JavaInternalClientError) -> ControlPlaneUnavailable | ControlPlaneRejected:
    if exc.code in {
        JavaInternalErrorCode.CONNECTION_FAILED,
        JavaInternalErrorCode.TIMEOUT,
        JavaInternalErrorCode.UPSTREAM_5XX,
        JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE,
    } or exc.status_code in {401, 503}:
        return ControlPlaneUnavailable("WORKFLOW_JAVA_CONTROL_UNAVAILABLE")
    return ControlPlaneRejected("WORKFLOW_JAVA_CONTROL_REJECTED")


def build_workflow_control_plane(settings: AppSettings) -> JavaWorkflowControlPlane:
    client = build_java_internal_client(settings)
    return JavaWorkflowControlPlane(cast(JavaWorkflowExecutionClientProtocol, client))


__all__ = [
    "JavaWorkflowControlPlane",
    "WorkflowAccepted",
    "WorkflowClaim",
    "WorkflowControlPlane",
    "WorkflowTaskActivation",
    "build_workflow_control_plane",
]

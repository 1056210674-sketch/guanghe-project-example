"""Pooled Java Internal HMAC client for M1 and future frozen wrappers."""

from __future__ import annotations

import json
import logging
import re
import time
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from enum import StrEnum
from typing import Literal, Protocol, cast
from urllib.parse import quote
from uuid import UUID, uuid4

import httpx

from app.common.settings import AppSettings
from app.observability.trace import child_trace_context
from app.security.internal_hmac import (
    CONTENT_SHA256_HEADER,
    KEY_ID_HEADER,
    NONCE_HEADER,
    SIGNATURE_HEADER,
    TIMESTAMP_HEADER,
    TRACEPARENT_HEADER,
    sign_content,
)

_REVERSE_CALL_PATH = "/internal/v1/test/reverse-call"
_RUN_CONTEXT_PATH_TEMPLATE = "/internal/v1/runs/{run_id}/context"
_WORKFLOW_PLAN_PATH_TEMPLATE = "/internal/v1/runs/{run_id}/workflow-plans"
_TASK_CLAIM_PATH_TEMPLATE = "/internal/v1/tasks/{task_id}/claim"
_TASK_CONTEXT_PATH_TEMPLATE = "/internal/v1/tasks/{task_id}/execution-context"
_TASK_HEARTBEAT_PATH_TEMPLATE = "/internal/v1/tasks/{task_id}/heartbeat"
_TASK_EVENT_PATH_TEMPLATE = "/internal/v1/tasks/{task_id}/execution-events"
_TASK_RESULT_PATH_TEMPLATE = "/internal/v1/tasks/{task_id}/result-artifacts"
_TASK_GEO_OBSERVATIONS_PATH_TEMPLATE = "/internal/v1/tasks/{task_id}/geo-observations"
_TASK_GEO_COMPLETION_PATH_TEMPLATE = "/internal/v1/tasks/{task_id}/geo-completion"
_WORKFLOW_EXECUTION_BASE_PATH_TEMPLATE = "/internal/v1/workflow-plans/{plan_id}"
_MAX_REQUEST_BYTES = 1_048_576
_MAX_RESPONSE_BYTES = 1_048_576
_MAX_USER_CONTEXT_REF_LENGTH = 512
_MAX_EXECUTION_TOKEN_LENGTH = 512
_MAX_SIGNED_LONG = 9_223_372_036_854_775_807
_logger = logging.getLogger(__name__)

USER_CONTEXT_REF_HEADER = "X-User-Context-Ref"
EXECUTION_NO_HEADER = "X-Execution-No"
EXECUTION_TOKEN_HEADER = "X-Execution-Token"

QueryItems = Sequence[tuple[str, str]]
JsonObject = Mapping[str, object]


class JavaInternalErrorCode(StrEnum):
    CONFIGURATION_UNAVAILABLE = "JAVA_INTERNAL_CONFIGURATION_UNAVAILABLE"
    CONNECTION_FAILED = "JAVA_INTERNAL_CONNECTION_FAILED"
    TIMEOUT = "JAVA_INTERNAL_TIMEOUT"
    AUTHENTICATION_REJECTED = "JAVA_INTERNAL_AUTHENTICATION_REJECTED"
    UPSTREAM_5XX = "JAVA_INTERNAL_UPSTREAM_5XX"
    HTTP_ERROR = "JAVA_INTERNAL_HTTP_ERROR"
    INVALID_RESPONSE = "JAVA_INTERNAL_INVALID_RESPONSE"
    INVALID_REQUEST = "JAVA_INTERNAL_INVALID_REQUEST"


class JavaInternalClientError(RuntimeError):
    """Safe classified error without upstream bodies, signatures, or credentials."""

    def __init__(
        self,
        code: JavaInternalErrorCode,
        *,
        status_code: int | None = None,
        business_code: str | None = None,
    ) -> None:
        self.code = code
        self.status_code = status_code
        # Only this audited rejection is needed by the worker. Never retain a
        # Java message, details, response body, or an arbitrary upstream code.
        self.business_code = (
            business_code
            if code is JavaInternalErrorCode.HTTP_ERROR
            and status_code == 409
            and business_code == "KNOWLEDGE_SOURCE_CHANGED"
            else None
        )
        super().__init__(code.value)


@dataclass(frozen=True, slots=True)
class JavaInternalResponse:
    status_code: int
    payload: dict[str, object]
    trace_id: str


@dataclass(frozen=True, slots=True)
class JavaReverseCallResult:
    accepted: bool
    caller_key_id: str
    trace_id: str


class JavaInternalClientProtocol(Protocol):
    async def get_run_context(
        self,
        run_id: UUID,
        user_context_ref: str,
        traceparent: str | None = None,
    ) -> JavaInternalResponse: ...

    async def reverse_call(self, traceparent: str | None = None) -> JavaReverseCallResult: ...

    async def aclose(self) -> None: ...


class JavaWorkflowPlanClientProtocol(Protocol):
    """Narrow workflow proposal subset implemented by the shared Java client."""

    async def propose_workflow_plan(
        self,
        run_id: UUID,
        user_context_ref: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse: ...


class JavaResourceClientProtocol(Protocol):
    async def search_run_resources(
        self,
        run_id: UUID,
        user_context_ref: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse: ...

    async def read_run_resource(
        self,
        run_id: UUID,
        user_context_ref: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse: ...


class JavaAgentActionClientProtocol(JavaResourceClientProtocol, Protocol):
    async def compact_run_history(
        self,
        run_id: UUID,
        user_context_ref: str,
        payload: JsonObject,
        *,
        checkpoint: bool = False,
        traceparent: str | None = None,
    ) -> JavaInternalResponse: ...

    async def execute_run_action(
        self,
        run_id: UUID,
        user_context_ref: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse: ...


class JavaWorkerInternalClientProtocol(Protocol):
    """Narrow task-control subset implemented by the shared Java client."""

    async def reverse_call(self, traceparent: str | None = None) -> JavaReverseCallResult: ...

    async def claim_task(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse: ...

    async def get_task_execution_context(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str | None = None,
    ) -> JavaInternalResponse: ...

    async def heartbeat_task(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse: ...

    async def report_task_execution_event(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse: ...

    async def report_task_result(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse: ...

    async def get_task_geo_observations(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        page: int,
        size: int,
        traceparent: str | None = None,
    ) -> JavaInternalResponse: ...

    async def report_task_geo_observation(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse: ...

    async def report_task_geo_completion(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse: ...

    async def aclose(self) -> None: ...


class JavaWorkflowExecutionClientProtocol(Protocol):
    """Frozen P3 workflow-execution subset of the shared signed Java client."""

    async def claim_workflow_execution(
        self, plan_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse: ...
    async def get_workflow_execution_context(
        self,
        plan_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str | None = None,
    ) -> JavaInternalResponse: ...
    async def heartbeat_workflow_execution(
        self, plan_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse: ...
    async def report_workflow_observation(
        self, plan_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse: ...
    async def report_workflow_step_state(
        self, plan_id: str, step_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse: ...
    async def activate_workflow_step_task(
        self, plan_id: str, step_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse: ...
    async def replan_workflow_execution(
        self, plan_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse: ...
    async def complete_workflow_execution(
        self, plan_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse: ...
    async def reverse_call(self, traceparent: str | None = None) -> JavaReverseCallResult: ...
    async def aclose(self) -> None: ...


class UnavailableJavaInternalClient:
    async def search_run_resources(
        self,
        run_id: UUID,
        user_context_ref: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def read_run_resource(
        self,
        run_id: UUID,
        user_context_ref: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def get(
        self,
        path: str,
        *,
        query: QueryItems = (),
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        del path, query, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def post_json(
        self,
        path: str,
        payload: JsonObject,
        *,
        query: QueryItems = (),
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        del path, payload, query, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def post_json_bytes(
        self,
        path: str,
        body: bytes,
        *,
        query: QueryItems = (),
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        del path, body, query, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def put_json(
        self,
        path: str,
        payload: JsonObject,
        *,
        query: QueryItems = (),
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        del path, payload, query, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def reverse_call(self, traceparent: str | None = None) -> JavaReverseCallResult:
        del traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def get_run_context(
        self,
        run_id: UUID,
        user_context_ref: str,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        del run_id, user_context_ref, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def propose_workflow_plan(
        self,
        run_id: UUID,
        user_context_ref: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        del run_id, user_context_ref, payload, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def claim_task(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        del task_id, payload, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def get_task_execution_context(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        del task_id, execution_no, execution_token, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def heartbeat_task(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        del task_id, payload, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def report_task_execution_event(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        del task_id, payload, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def report_task_result(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        del task_id, payload, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def get_task_geo_observations(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        page: int,
        size: int,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        del task_id, execution_no, execution_token, page, size, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def report_task_geo_observation(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        del task_id, payload, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def report_task_geo_completion(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        del task_id, payload, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def claim_workflow_execution(
        self, plan_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse:
        del plan_id, payload, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def get_workflow_execution_context(
        self,
        plan_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        del plan_id, execution_no, execution_token, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def heartbeat_workflow_execution(
        self, plan_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse:
        del plan_id, payload, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def report_workflow_observation(
        self, plan_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse:
        del plan_id, payload, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def report_workflow_step_state(
        self, plan_id: str, step_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse:
        del plan_id, step_id, payload, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def activate_workflow_step_task(
        self, plan_id: str, step_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse:
        del plan_id, step_id, payload, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def replan_workflow_execution(
        self, plan_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse:
        del plan_id, payload, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def complete_workflow_execution(
        self, plan_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse:
        del plan_id, payload, traceparent
        raise JavaInternalClientError(JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE)

    async def aclose(self) -> None:
        return None


class JavaInternalClient:
    async def compact_run_history(
        self,
        run_id: UUID,
        user_context_ref: str,
        payload: JsonObject,
        *,
        checkpoint: bool = False,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        """Read/checkpoint only the active Run's history through the existing HMAC pool."""
        if not _valid_user_context_ref(user_context_ref):
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
        try:
            body = json.dumps(
                payload, ensure_ascii=False, allow_nan=False, separators=(",", ":")
            ).encode()
        except (TypeError, ValueError) as exc:
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST) from exc
        operation = "checkpoint" if checkpoint else "source"
        return await self._request(
            "POST",
            f"/internal/v1/runs/{run_id}/compaction/{operation}",
            body=body,
            traceparent=traceparent,
            user_context_ref=user_context_ref,
            content_type="application/json",
        )

    async def execute_run_action(
        self,
        run_id: UUID,
        user_context_ref: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        if not _valid_user_context_ref(user_context_ref):
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
        try:
            body = json.dumps(
                payload, ensure_ascii=False, allow_nan=False, separators=(",", ":")
            ).encode()
        except (TypeError, ValueError) as exc:
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST) from exc
        return await self._request(
            "POST",
            f"/internal/v1/runs/{run_id}/actions",
            body=body,
            traceparent=traceparent,
            user_context_ref=user_context_ref,
            content_type="application/json",
        )

    """Sign and send the exact request target/body bytes through one bounded pool."""

    async def search_run_resources(
        self,
        run_id: UUID,
        user_context_ref: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        return await self._resource_post(run_id, user_context_ref, "search", payload, traceparent)

    async def read_run_resource(
        self,
        run_id: UUID,
        user_context_ref: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        return await self._resource_post(run_id, user_context_ref, "read", payload, traceparent)

    async def _resource_post(
        self,
        run_id: UUID,
        user_context_ref: str,
        operation: Literal["search", "read"],
        payload: JsonObject,
        traceparent: str | None,
    ) -> JavaInternalResponse:
        if not _valid_user_context_ref(user_context_ref):
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
        try:
            body = json.dumps(
                payload, ensure_ascii=False, allow_nan=False, separators=(",", ":")
            ).encode()
        except (TypeError, ValueError) as exc:
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST) from exc
        return await self._request(
            "POST",
            f"/internal/v1/runs/{run_id}/resources/{operation}",
            body=body,
            traceparent=traceparent,
            user_context_ref=user_context_ref,
            content_type="application/json",
        )

    def __init__(
        self,
        settings: AppSettings,
        client: httpx.AsyncClient,
        *,
        clock: Callable[[], float] = time.time,
        nonce_factory: Callable[[], UUID] = uuid4,
    ) -> None:
        if settings.internal_hmac_key_id is None or settings.internal_hmac_secret is None:
            raise ValueError("Internal HMAC client configuration is incomplete")
        secret = settings.internal_hmac_secret.get_secret_value().encode("utf-8")
        if len(secret) < 32:
            raise ValueError("Internal HMAC secret is too short")
        self._key_id = settings.internal_hmac_key_id
        self._secret = secret
        self._client = client
        self._clock = clock
        self._nonce_factory = nonce_factory

    async def get(
        self,
        path: str,
        *,
        query: QueryItems = (),
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        return await self._request(
            "GET",
            _path_with_query(path, query),
            body=b"",
            traceparent=traceparent,
        )

    async def post_json(
        self,
        path: str,
        payload: JsonObject,
        *,
        query: QueryItems = (),
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        try:
            body = json.dumps(
                payload,
                ensure_ascii=False,
                allow_nan=False,
                separators=(",", ":"),
            ).encode("utf-8")
        except (TypeError, ValueError) as exc:
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST) from exc
        return await self.post_json_bytes(
            path,
            body,
            query=query,
            traceparent=traceparent,
        )

    async def post_json_bytes(
        self,
        path: str,
        body: bytes,
        *,
        query: QueryItems = (),
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        """Sign caller-supplied JSON bytes without parsing and re-serializing the body."""
        try:
            parsed = cast(object, json.loads(body))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST) from exc
        if not isinstance(parsed, dict):
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
        return await self._request(
            "POST",
            _path_with_query(path, query),
            body=body,
            content_type="application/json",
            traceparent=traceparent,
        )

    async def put_json(
        self,
        path: str,
        payload: JsonObject,
        *,
        query: QueryItems = (),
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        try:
            body = json.dumps(
                payload, ensure_ascii=False, allow_nan=False, separators=(",", ":")
            ).encode("utf-8")
        except (TypeError, ValueError) as exc:
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST) from exc
        return await self._request(
            "PUT",
            _path_with_query(path, query),
            body=body,
            content_type="application/json",
            traceparent=traceparent,
        )

    async def reverse_call(self, traceparent: str | None = None) -> JavaReverseCallResult:
        response = await self.post_json(
            _REVERSE_CALL_PATH,
            {"probe": "python-m1"},
            query=(("mode", "python-m1"),),
            traceparent=traceparent,
        )
        accepted = response.payload.get("accepted")
        caller_key_id = response.payload.get("callerKeyId")
        trace_id = response.payload.get("traceId")
        if (
            accepted is not True
            or not isinstance(caller_key_id, str)
            or not isinstance(trace_id, str)
            or caller_key_id != self._key_id
            or trace_id != response.trace_id
        ):
            raise JavaInternalClientError(
                JavaInternalErrorCode.INVALID_RESPONSE,
                status_code=response.status_code,
            )
        _logger.info("java_reverse_call_completed", extra={"traceId": trace_id})
        return JavaReverseCallResult(
            accepted=True,
            caller_key_id=caller_key_id,
            trace_id=trace_id,
        )

    async def get_run_context(
        self,
        run_id: UUID,
        user_context_ref: str,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        """Load one Run's Java-authorized context through the shared signed pool."""
        if not _valid_user_context_ref(user_context_ref):
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
        return await self._request(
            "GET",
            _RUN_CONTEXT_PATH_TEMPLATE.format(run_id=run_id),
            body=b"",
            traceparent=traceparent,
            user_context_ref=user_context_ref,
        )

    async def propose_workflow_plan(
        self,
        run_id: UUID,
        user_context_ref: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        """Persist one proposal-only workflow through the shared HMAC pool."""
        if not _valid_user_context_ref(user_context_ref):
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
        try:
            body = json.dumps(
                payload,
                ensure_ascii=False,
                allow_nan=False,
                separators=(",", ":"),
            ).encode("utf-8")
        except (TypeError, ValueError) as exc:
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST) from exc
        return await self._request(
            "POST",
            _WORKFLOW_PLAN_PATH_TEMPLATE.format(run_id=run_id),
            body=body,
            content_type="application/json",
            traceparent=traceparent,
            user_context_ref=user_context_ref,
        )

    async def claim_task(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        return await self.post_json(
            _task_path(_TASK_CLAIM_PATH_TEMPLATE, task_id),
            payload,
            traceparent=traceparent,
        )

    async def get_task_execution_context(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        if (
            isinstance(execution_no, bool)
            or execution_no < 1
            or not _valid_execution_token(execution_token)
        ):
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
        return await self._request(
            "GET",
            _task_path(_TASK_CONTEXT_PATH_TEMPLATE, task_id),
            body=b"",
            traceparent=traceparent,
            execution_no=execution_no,
            execution_token=execution_token,
        )

    async def heartbeat_task(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        return await self.post_json(
            _task_path(_TASK_HEARTBEAT_PATH_TEMPLATE, task_id),
            payload,
            traceparent=traceparent,
        )

    async def report_task_execution_event(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        return await self.post_json(
            _task_path(_TASK_EVENT_PATH_TEMPLATE, task_id),
            payload,
            traceparent=traceparent,
        )

    async def report_task_result(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        return await self.post_json(
            _task_path(_TASK_RESULT_PATH_TEMPLATE, task_id),
            payload,
            traceparent=traceparent,
        )

    async def get_task_geo_observations(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        page: int,
        size: int,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        if (
            isinstance(execution_no, bool)
            or execution_no < 1
            or not _valid_execution_token(execution_token)
            or isinstance(page, bool)
            or not 1 <= page <= 5
            or isinstance(size, bool)
            or not 1 <= size <= 50
        ):
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
        return await self._request(
            "GET",
            _path_with_query(
                _task_path(_TASK_GEO_OBSERVATIONS_PATH_TEMPLATE, task_id),
                (("page", str(page)), ("size", str(size))),
            ),
            body=b"",
            traceparent=traceparent,
            execution_no=execution_no,
            execution_token=execution_token,
        )

    async def report_task_geo_observation(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        return await self.post_json(
            _task_path(_TASK_GEO_OBSERVATIONS_PATH_TEMPLATE, task_id),
            payload,
            traceparent=traceparent,
        )

    async def report_task_geo_completion(
        self,
        task_id: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        return await self.post_json(
            _task_path(_TASK_GEO_COMPLETION_PATH_TEMPLATE, task_id),
            payload,
            traceparent=traceparent,
        )

    async def claim_workflow_execution(
        self, plan_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse:
        return await self.post_json(
            f"{_workflow_path(plan_id)}/claim", payload, traceparent=traceparent
        )

    async def get_workflow_execution_context(
        self,
        plan_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        if (
            isinstance(execution_no, bool)
            or not 1 <= execution_no <= 32
            or not _valid_execution_token(execution_token)
        ):
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
        return await self._request(
            "GET",
            f"{_workflow_path(plan_id)}/execution-context",
            body=b"",
            traceparent=traceparent,
            execution_no=execution_no,
            execution_token=execution_token,
        )

    async def heartbeat_workflow_execution(
        self, plan_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse:
        return await self.post_json(
            f"{_workflow_path(plan_id)}/heartbeat", payload, traceparent=traceparent
        )

    async def report_workflow_observation(
        self, plan_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse:
        return await self.post_json(
            f"{_workflow_path(plan_id)}/observations", payload, traceparent=traceparent
        )

    async def report_workflow_step_state(
        self, plan_id: str, step_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse:
        if not re.fullmatch(r"step-[1-9][0-9]?", step_id):
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
        return await self.put_json(
            f"{_workflow_path(plan_id)}/steps/{step_id}/state", payload, traceparent=traceparent
        )

    async def activate_workflow_step_task(
        self, plan_id: str, step_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse:
        if not re.fullmatch(r"step-[1-9][0-9]?", step_id):
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
        return await self.post_json(
            f"{_workflow_path(plan_id)}/steps/{step_id}/task", payload, traceparent=traceparent
        )

    async def replan_workflow_execution(
        self, plan_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse:
        return await self.post_json(
            f"{_workflow_path(plan_id)}/replans", payload, traceparent=traceparent
        )

    async def complete_workflow_execution(
        self, plan_id: str, payload: JsonObject, traceparent: str | None = None
    ) -> JavaInternalResponse:
        return await self.post_json(
            f"{_workflow_path(plan_id)}/completion", payload, traceparent=traceparent
        )

    async def _request(
        self,
        method: str,
        raw_path_with_query: str,
        *,
        body: bytes,
        traceparent: str | None,
        content_type: str | None = None,
        user_context_ref: str | None = None,
        execution_no: int | None = None,
        execution_token: str | None = None,
    ) -> JavaInternalResponse:
        _validate_request_target(raw_path_with_query)
        if len(body) > _MAX_REQUEST_BYTES:
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
        trace = child_trace_context(traceparent)
        headers = {TRACEPARENT_HEADER: trace.traceparent, "Accept": "application/json"}
        if content_type is not None:
            headers["Content-Type"] = content_type
        if user_context_ref is not None:
            if not _valid_user_context_ref(user_context_ref):
                raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
            headers[USER_CONTEXT_REF_HEADER] = user_context_ref
        if (execution_no is None) != (execution_token is None):
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
        if execution_no is not None and execution_token is not None:
            if execution_no < 1 or not _valid_execution_token(execution_token):
                raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
            headers[EXECUTION_NO_HEADER] = str(execution_no)
            headers[EXECUTION_TOKEN_HEADER] = execution_token
        request = self._client.build_request(
            method,
            raw_path_with_query,
            content=body,
            headers=headers,
        )
        try:
            actual_target = request.url.raw_path.decode("ascii")
        except UnicodeDecodeError as exc:
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST) from exc
        if actual_target != raw_path_with_query:
            raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)

        timestamp = str(int(self._clock()))
        nonce = str(self._nonce_factory())
        signed = sign_content(
            secret=self._secret,
            timestamp=timestamp,
            nonce=nonce,
            method=request.method,
            raw_path_with_query=actual_target,
            body=body,
        )
        request.headers[KEY_ID_HEADER] = self._key_id
        request.headers[TIMESTAMP_HEADER] = timestamp
        request.headers[NONCE_HEADER] = nonce
        request.headers[CONTENT_SHA256_HEADER] = signed.body_sha256
        request.headers[SIGNATURE_HEADER] = signed.signature_base64

        try:
            response = await self._client.send(request)
        except httpx.TimeoutException as exc:
            _log_failure(JavaInternalErrorCode.TIMEOUT, trace.trace_id)
            raise JavaInternalClientError(JavaInternalErrorCode.TIMEOUT) from exc
        except httpx.RequestError as exc:
            _log_failure(JavaInternalErrorCode.CONNECTION_FAILED, trace.trace_id)
            raise JavaInternalClientError(JavaInternalErrorCode.CONNECTION_FAILED) from exc

        _raise_for_status(
            response,
            allow_task_context_rejection=(
                method == "GET"
                and execution_no is not None
                and execution_token is not None
                and re.fullmatch(
                    r"/internal/v1/tasks/[1-9][0-9]{0,18}/execution-context", actual_target
                )
                is not None
            ),
        )
        if len(response.content) > _MAX_RESPONSE_BYTES:
            raise JavaInternalClientError(
                JavaInternalErrorCode.INVALID_RESPONSE,
                status_code=response.status_code,
            )
        try:
            payload = cast(object, response.json())
        except ValueError as exc:
            raise JavaInternalClientError(
                JavaInternalErrorCode.INVALID_RESPONSE,
                status_code=response.status_code,
            ) from exc
        if not isinstance(payload, dict):
            raise JavaInternalClientError(
                JavaInternalErrorCode.INVALID_RESPONSE,
                status_code=response.status_code,
            )
        response_payload = cast(dict[object, object], payload)
        if not all(isinstance(key, str) for key in response_payload):
            raise JavaInternalClientError(
                JavaInternalErrorCode.INVALID_RESPONSE,
                status_code=response.status_code,
            )
        response_trace_id = response.headers.get("X-Trace-Id")
        if response_trace_id is not None and response_trace_id != trace.trace_id:
            raise JavaInternalClientError(
                JavaInternalErrorCode.INVALID_RESPONSE,
                status_code=response.status_code,
            )
        return JavaInternalResponse(
            status_code=response.status_code,
            payload=cast(dict[str, object], response_payload),
            trace_id=trace.trace_id,
        )

    async def aclose(self) -> None:
        await self._client.aclose()


def build_java_internal_client(settings: AppSettings) -> JavaInternalClientProtocol:
    """Create the application-owned pool without opening a connection yet."""
    if (
        settings.java_internal_base_url is None
        or settings.internal_hmac_key_id is None
        or settings.internal_hmac_secret is None
    ):
        return UnavailableJavaInternalClient()
    if len(settings.internal_hmac_secret.get_secret_value().encode("utf-8")) < 32:
        return UnavailableJavaInternalClient()
    client = httpx.AsyncClient(
        base_url=settings.java_internal_base_url,
        timeout=httpx.Timeout(connect=1.0, read=2.0, write=2.0, pool=1.0),
        limits=httpx.Limits(max_connections=10, max_keepalive_connections=5),
        follow_redirects=False,
        trust_env=False,
    )
    return JavaInternalClient(settings, client)


def _path_with_query(path: str, query: QueryItems) -> str:
    if "?" in path and query:
        raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
    if not query:
        return path
    encoded = "&".join(f"{quote(name, safe='')}={quote(value, safe='')}" for name, value in query)
    return f"{path}?{encoded}"


def _task_path(template: str, task_id: str) -> str:
    if not task_id.isascii() or not task_id.isdecimal() or not 1 <= len(task_id) <= 19:
        raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
    if task_id.startswith("0") or int(task_id) > _MAX_SIGNED_LONG:
        raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
    return template.format(task_id=task_id)


def _workflow_path(plan_id: str) -> str:
    if not plan_id.isascii() or not plan_id.isdecimal() or not 1 <= len(plan_id) <= 19:
        raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
    if plan_id.startswith("0") or int(plan_id) > _MAX_SIGNED_LONG:
        raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
    return _WORKFLOW_EXECUTION_BASE_PATH_TEMPLATE.format(plan_id=plan_id)


def _validate_request_target(raw_path_with_query: str) -> None:
    if (
        not raw_path_with_query.startswith("/internal/v1/")
        or "://" in raw_path_with_query
        or "#" in raw_path_with_query
        or "\r" in raw_path_with_query
        or "\n" in raw_path_with_query
    ):
        raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST)
    try:
        raw_path_with_query.encode("ascii")
    except UnicodeEncodeError as exc:
        raise JavaInternalClientError(JavaInternalErrorCode.INVALID_REQUEST) from exc


def _valid_user_context_ref(value: object) -> bool:
    if not isinstance(value, str) or not 1 <= len(value) <= _MAX_USER_CONTEXT_REF_LENGTH:
        return False
    try:
        encoded = value.encode("ascii")
    except UnicodeEncodeError:
        return False
    return all(0x21 <= byte <= 0x7E for byte in encoded)


def _valid_execution_token(value: object) -> bool:
    if not isinstance(value, str) or not 1 <= len(value) <= _MAX_EXECUTION_TOKEN_LENGTH:
        return False
    try:
        encoded = value.encode("ascii")
    except UnicodeEncodeError:
        return False
    return all(0x21 <= byte <= 0x7E for byte in encoded)


def _raise_for_status(
    response: httpx.Response, *, allow_task_context_rejection: bool = False
) -> None:
    if response.status_code == 401:
        raise JavaInternalClientError(
            JavaInternalErrorCode.AUTHENTICATION_REJECTED,
            status_code=response.status_code,
        )
    if response.status_code >= 500:
        raise JavaInternalClientError(
            JavaInternalErrorCode.UPSTREAM_5XX,
            status_code=response.status_code,
        )
    if not response.is_success:
        business_code = None
        if (
            allow_task_context_rejection
            and response.status_code == 409
            and len(response.content) <= _MAX_RESPONSE_BYTES
        ):
            try:
                error_payload: object = response.json()
            except ValueError:
                error_payload = None
            if (
                isinstance(error_payload, dict)
                and cast(dict[object, object], error_payload).get("code")
                == "KNOWLEDGE_SOURCE_CHANGED"
            ):
                business_code = "KNOWLEDGE_SOURCE_CHANGED"
        raise JavaInternalClientError(
            JavaInternalErrorCode.HTTP_ERROR,
            status_code=response.status_code,
            business_code=business_code,
        )


def _log_failure(code: JavaInternalErrorCode, trace_id: str) -> None:
    _logger.warning("java_internal_call_failed", extra={"traceId": trace_id, "errorCode": code})

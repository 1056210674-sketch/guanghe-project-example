from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from typing import cast
from uuid import UUID

import httpx
import pytest
from pydantic import SecretStr

from app.common.settings import AppSettings
from app.integration.java_internal import (
    JavaInternalClient,
    JavaInternalClientError,
    JavaInternalErrorCode,
    JavaInternalResponse,
    JavaWorkflowExecutionClientProtocol,
)
from app.workers.control_plane import ControlPlaneRejected, ControlPlaneUnavailable
from app.workers.workflow_control_plane import JavaWorkflowControlPlane
from app.workers.workflow_models import WorkflowExecutionMessage, WorkflowObservation, WorkflowStep

PLAN_ID = "90436416797933568"
TRACEPARENT = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
CONVERSATION_ID = "90436416797933570"
SPACE_ID = "90436416797933571"


def _response(payload: dict[str, object]) -> JavaInternalResponse:
    return JavaInternalResponse(
        status_code=200, payload=payload, trace_id="0123456789abcdef0123456789abcdef"
    )


class _Client:
    def __init__(self) -> None:
        self.calls: list[tuple[str, object]] = []
        self.failure: JavaInternalClientError | None = None

    async def reverse_call(self, traceparent: str | None = None):
        del traceparent
        if self.failure:
            raise self.failure
        return type("Probe", (), {"accepted": True})()

    async def claim_workflow_execution(
        self, plan_id: str, payload: object, traceparent: str | None = None
    ) -> JavaInternalResponse:
        self.calls.append(("claim", (plan_id, payload, traceparent)))
        return self._result(
            {
                "claimed": True,
                "disposition": "CLAIMED",
                "executionNo": 1,
                "executionToken": "token",
                "leaseExpiresAt": (datetime.now(UTC) + timedelta(minutes=2)).isoformat(),
            }
        )

    async def get_workflow_execution_context(
        self, plan_id: str, **kwargs: object
    ) -> JavaInternalResponse:
        self.calls.append(("context", (plan_id, kwargs)))
        return self._result(_context())

    async def heartbeat_workflow_execution(
        self, plan_id: str, payload: object, traceparent: str | None = None
    ) -> JavaInternalResponse:
        self.calls.append(("heartbeat", (plan_id, payload, traceparent)))
        return self._result(
            {
                "accepted": True,
                "leaseExpiresAt": (datetime.now(UTC) + timedelta(minutes=2)).isoformat(),
            }
        )

    async def report_workflow_observation(
        self, plan_id: str, payload: object, traceparent: str | None = None
    ) -> JavaInternalResponse:
        self.calls.append(("observation", (plan_id, payload, traceparent)))
        return self._result(
            {"accepted": True, "disposition": "RECORDED", "planStatus": "RUNNING", "sequence": 2}
        )

    async def report_workflow_step_state(
        self, plan_id: str, step_id: str, payload: object, traceparent: str | None = None
    ) -> JavaInternalResponse:
        self.calls.append(("state", (plan_id, step_id, payload, traceparent)))
        return self._accepted()

    async def activate_workflow_step_task(
        self, plan_id: str, step_id: str, payload: object, traceparent: str | None = None
    ) -> JavaInternalResponse:
        self.calls.append(("activate", (plan_id, step_id, payload, traceparent)))
        return self._result(
            {
                "accepted": True,
                "disposition": "ACTIVATED",
                "planId": plan_id,
                "stepId": step_id,
                "taskId": "90436416797933579",
                "taskStatus": "QUEUED",
                "stepStatus": "WAITING",
                "waitingForTask": True,
                "executionNo": 1,
            }
        )

    async def replan_workflow_execution(
        self, plan_id: str, payload: object, traceparent: str | None = None
    ) -> JavaInternalResponse:
        self.calls.append(("replan", (plan_id, payload, traceparent)))
        return self._accepted(version=2)

    async def complete_workflow_execution(
        self, plan_id: str, payload: object, traceparent: str | None = None
    ) -> JavaInternalResponse:
        self.calls.append(("complete", (plan_id, payload, traceparent)))
        return self._result(
            {
                "accepted": True,
                "disposition": "RECORDED",
                "planStatus": "SUCCEEDED",
                "planVersion": 2,
            }
        )

    def _accepted(self, version: int = 1) -> JavaInternalResponse:
        return self._result(
            {
                "accepted": True,
                "disposition": "RECORDED",
                "planStatus": "RUNNING",
                "planVersion": version,
            }
        )

    def _result(self, payload: dict[str, object]) -> JavaInternalResponse:
        if self.failure:
            raise self.failure
        return _response(payload)

    async def aclose(self) -> None:
        self.calls.append(("close", None))


def _message() -> WorkflowExecutionMessage:
    return WorkflowExecutionMessage.model_validate(
        {
            "schemaVersion": 1,
            "eventId": "90436416797933569",
            "eventType": "WORKFLOW_EXECUTION_REQUESTED",
            "planId": PLAN_ID,
            "planVersion": 1,
            "attempt": 1,
            "traceparent": TRACEPARENT,
        }
    )


def _authorized_context() -> dict[str, object]:
    return {
        "runId": "7bc33f41-a597-4d2d-b3bd-284ba54b1258",
        "conversationId": CONVERSATION_ID,
        "contextVersion": 1,
        "space": {
            "id": SPACE_ID,
            "name": "经营空间",
            "brand": {
                "name": "品牌",
                "aliases": [],
                "description": None,
                "tone": [],
                "audience": [],
                "sellingPoints": [],
                "prohibitedClaims": [],
            },
            "logoAssetId": None,
        },
        "product": None,
        "competitors": [],
        "attachmentAssetIds": [],
        "conversationMemory": {
            "messages": [
                {
                    "messageId": "90436416797933572",
                    "sequence": 1,
                    "role": "USER",
                    "content": {"text": "分析经营资料"},
                    "createdAt": "2026-09-10T08:00:00Z",
                    "replyToMessageId": None,
                }
            ],
            "summary": None,
            "lastMessageSequence": 1,
            "summaryThroughSequence": 0,
            "contextSource": "POSTGRESQL",
        },
        "taskWorkspace": {
            "currentGoal": "分析经营资料",
            "currentIntent": "GENERAL",
            "activeScope": {"spaceId": SPACE_ID, "productId": None, "competitorIds": []},
            "confirmedConstraints": {},
            "unresolvedQuestions": [],
            "pendingInteraction": None,
            "activeDraftId": None,
            "activeTaskId": None,
            "referenceAssetIds": [],
            "runningTasks": [],
            "contextVersion": 1,
        },
        "loadedToolGroups": ["TASK_PREPARATION"],
        "contextBudget": {
            "targetInputTokens": 32000,
            "reservedOutputTokens": 1000,
            "reservedToolTokens": 1000,
            "reservedSafetyTokens": 1000,
            "availableHistoryTokens": 29000,
            "estimatedHistoryTokens": 10,
            "truncated": False,
            "selectionStrategy": "TOKEN_BUDGET_REPLY_CHAIN_THEN_RECENCY",
        },
        "taskPreparationConstraints": {
            "geo": {
                "enabled": False,
                "minQuestions": 3,
                "maxQuestions": 0,
                "platformCodes": [],
                "maxProviderCalls": 0,
            }
        },
    }


def _context() -> dict[str, object]:
    step = {
        "id": "step-1",
        "ordinal": 1,
        "type": "QUERY",
        "toolName": "context.catalog.inspect",
        "toolVersion": "1",
        "risk": "QUERY",
        "status": "READY",
        "dependsOn": [],
        "successCriteria": ["done"],
        "inputRefs": ["context:catalog"],
        "outputRef": "observation:one",
    }
    second = {
        "id": "step-2",
        "ordinal": 2,
        "type": "ANALYZE",
        "toolName": "analysis.evidence.aggregate",
        "toolVersion": "1",
        "risk": "ANALYZE",
        "status": "PENDING",
        "dependsOn": ["step-1"],
        "successCriteria": ["done"],
        "inputRefs": ["observation:one"],
        "outputRef": "observation:two",
    }
    return {
        "planId": PLAN_ID,
        "planVersion": 1,
        "executionNo": 1,
        "plan": {
            "id": PLAN_ID,
            "conversationId": CONVERSATION_ID,
            "version": 1,
            "status": "RUNNING",
            "goalSummary": "分析经营资料",
        },
        "steps": [step, second],
        "observations": [],
        "resumeOnly": False,
        "budget": {
            "maxSteps": 2,
            "maxReplans": 1,
            "maxModelCalls": 2,
            "maxToolCalls": 2,
            "maxProviderCalls": 0,
            "authorizedMaxPoints": 10,
            "deadlineAt": (datetime.now(UTC) + timedelta(minutes=10)).isoformat(),
        },
        "authorizedContext": _authorized_context(),
        "authorizedContextSchemaVersion": 1,
        "authorizedContextHash": "a" * 64,
        "authorizedContextSource": "PLAN_SNAPSHOT",
    }


def test_java_workflow_control_plane_maps_all_frozen_dtos() -> None:
    async def scenario() -> _Client:
        client = _Client()
        control = JavaWorkflowControlPlane(cast(JavaWorkflowExecutionClientProtocol, client))
        message = _message()
        claim = await control.claim(
            message, consumer_name="consumer", worker_id="worker", bundle_version="bundle"
        )
        context = await control.context(
            PLAN_ID, execution_no=1, execution_token="token", traceparent=TRACEPARENT
        )
        await control.heartbeat(
            PLAN_ID,
            execution_no=1,
            execution_token="token",
            stage_code="WORKFLOW_EXECUTING",
            traceparent=TRACEPARENT,
        )
        await control.step_state(
            PLAN_ID,
            "step-1",
            execution_no=1,
            execution_token="token",
            status="RUNNING",
            event_id=UUID("503bcf88-a562-5d43-9c30-1d969227c547"),
            traceparent=TRACEPARENT,
        )
        activation = await control.activate_task(
            PLAN_ID,
            "step-2",
            execution_no=1,
            execution_token="token",
            plan_version=1,
            task_draft_id="90436416797933578",
            event_id=UUID("7bc33f41-a597-4d2d-b3bd-284ba54b1258"),
            traceparent=TRACEPARENT,
        )
        observation = WorkflowObservation.model_validate(
            {
                "id": PLAN_ID,
                "stepId": "step-1",
                "eventId": "d8197454-a576-56d4-b643-c43011c56688",
                "toolCallId": "2fd86838-0cb5-54cb-a280-6e627a8512aa",
                "stepAttempt": 1,
                "status": "SUCCEEDED",
                "resultType": "TOOL_OBSERVATION",
                "summary": "done",
                "facts": {},
                "retryable": False,
                "completedAt": datetime.now(UTC),
            }
        )
        accepted = await control.observation(
            PLAN_ID,
            execution_no=1,
            execution_token="token",
            observation=observation,
            traceparent=TRACEPARENT,
        )
        steps = tuple(WorkflowStep.model_validate(item) for item in _context()["steps"])  # type: ignore[arg-type]
        await control.replan(
            PLAN_ID,
            execution_no=1,
            execution_token="token",
            expected_version=1,
            steps=steps,
            event_id=UUID("b005ad44-e140-527a-9bd3-41f2a218a329"),
            traceparent=TRACEPARENT,
        )
        await control.complete(
            PLAN_ID,
            execution_no=1,
            execution_token="token",
            status="SUCCEEDED",
            event_id=UUID("f2f4cf47-e2d0-554c-9d9f-52df9860df27"),
            traceparent=TRACEPARENT,
        )
        assert claim.execution_token == "token"
        assert context.budget.max_provider_calls == 0
        assert activation.waiting_for_task is True
        assert accepted.sequence == 2
        return client

    client = asyncio.run(scenario())
    assert [name for name, _ in client.calls] == [
        "claim",
        "context",
        "heartbeat",
        "state",
        "activate",
        "observation",
        "replan",
        "complete",
    ]
    observation_payload = cast(tuple[object, object, object], client.calls[5][1])[1]
    assert "executionToken" in cast(dict[str, object], observation_payload)
    assert "id" not in cast(dict[str, object], observation_payload)


@pytest.mark.parametrize(
    ("error", "expected"),
    [
        (JavaInternalClientError(JavaInternalErrorCode.TIMEOUT), ControlPlaneUnavailable),
        (
            JavaInternalClientError(JavaInternalErrorCode.HTTP_ERROR, status_code=401),
            ControlPlaneUnavailable,
        ),
        (
            JavaInternalClientError(JavaInternalErrorCode.HTTP_ERROR, status_code=409),
            ControlPlaneRejected,
        ),
        (
            JavaInternalClientError(JavaInternalErrorCode.HTTP_ERROR, status_code=422),
            ControlPlaneRejected,
        ),
    ],
)
def test_java_workflow_error_classification(
    error: JavaInternalClientError, expected: type[Exception]
) -> None:
    async def scenario() -> None:
        client = _Client()
        client.failure = error
        control = JavaWorkflowControlPlane(cast(JavaWorkflowExecutionClientProtocol, client))
        with pytest.raises(expected):
            await control.claim(_message(), consumer_name="c", worker_id="w", bundle_version="b")

    asyncio.run(scenario())


def test_control_response_rejects_unknown_fields_without_leaking_body() -> None:
    class Bad(_Client):
        async def claim_workflow_execution(
            self, *args: object, **kwargs: object
        ) -> JavaInternalResponse:
            del args, kwargs
            return _response(
                {"claimed": False, "disposition": "TERMINAL", "secret": "not-returned"}
            )

    async def scenario() -> None:
        control = JavaWorkflowControlPlane(cast(JavaWorkflowExecutionClientProtocol, Bad()))
        with pytest.raises(ControlPlaneRejected) as captured:
            await control.claim(_message(), consumer_name="c", worker_id="w", bundle_version="b")
        assert "secret" not in str(captured.value)

    asyncio.run(scenario())


def test_duplicate_claim_keeps_correlation_metadata_without_execution_token() -> None:
    class Duplicate(_Client):
        async def claim_workflow_execution(
            self, *args: object, **kwargs: object
        ) -> JavaInternalResponse:
            del args, kwargs
            return _response(
                {
                    "claimed": False,
                    "disposition": "DUPLICATE",
                    "executionNo": 1,
                    "executionToken": None,
                    "leaseExpiresAt": (datetime.now(UTC) + timedelta(minutes=2)).isoformat(),
                }
            )

    async def scenario() -> None:
        control = JavaWorkflowControlPlane(cast(JavaWorkflowExecutionClientProtocol, Duplicate()))
        claim = await control.claim(
            _message(), consumer_name="consumer", worker_id="worker", bundle_version="bundle"
        )
        assert claim.disposition == "DUPLICATE"
        assert claim.execution_no == 1
        assert claim.execution_token is None

    asyncio.run(scenario())


def test_shared_java_client_signs_workflow_put_without_second_http_stack() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(
            200,
            json={
                "accepted": True,
                "disposition": "RECORDED",
                "planStatus": "RUNNING",
                "planVersion": 1,
            },
        )

    async def scenario() -> None:
        settings = AppSettings(
            environment="test",
            java_internal_base_url="http://127.0.0.1:8080",
            internal_hmac_key_id="test-key",
            internal_hmac_secret=SecretStr("x" * 32),
        )
        http = httpx.AsyncClient(
            base_url="http://127.0.0.1:8080",
            transport=httpx.MockTransport(handler),
        )
        client = JavaInternalClient(settings, http)
        await client.report_workflow_step_state(
            PLAN_ID,
            "step-1",
            {
                "eventId": "503bcf88-a562-5d43-9c30-1d969227c547",
                "executionNo": 1,
                "executionToken": "token",
                "status": "RUNNING",
            },
            TRACEPARENT,
        )
        await client.aclose()

    asyncio.run(scenario())
    assert len(captured) == 1
    request = captured[0]
    assert request.method == "PUT"
    assert request.url.path == f"/internal/v1/workflow-plans/{PLAN_ID}/steps/step-1/state"
    for header in (
        "X-Internal-Key-Id",
        "X-Internal-Timestamp",
        "X-Internal-Nonce",
        "X-Content-SHA256",
        "X-Internal-Signature",
        "traceparent",
    ):
        assert header in request.headers


def test_shared_java_client_signs_workflow_task_activation_post() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(
            200,
            json={
                "accepted": True,
                "disposition": "ACTIVATED",
                "planId": PLAN_ID,
                "stepId": "step-2",
                "taskId": "90436416797933579",
                "taskStatus": "QUEUED",
                "stepStatus": "WAITING",
                "waitingForTask": True,
                "executionNo": 32,
            },
        )

    async def scenario() -> None:
        settings = AppSettings(
            environment="test",
            java_internal_base_url="http://127.0.0.1:8080",
            internal_hmac_key_id="test-key",
            internal_hmac_secret=SecretStr("x" * 32),
        )
        client = JavaInternalClient(
            settings,
            httpx.AsyncClient(
                base_url="http://127.0.0.1:8080", transport=httpx.MockTransport(handler)
            ),
        )
        await client.activate_workflow_step_task(
            PLAN_ID,
            "step-2",
            {
                "eventId": "7bc33f41-a597-4d2d-b3bd-284ba54b1258",
                "executionNo": 32,
                "executionToken": "token",
                "planVersion": 1,
                "taskDraftId": "90436416797933578",
            },
            TRACEPARENT,
        )
        await client.aclose()

    asyncio.run(scenario())
    assert len(captured) == 1
    request = captured[0]
    assert request.method == "POST"
    assert request.url.path == (f"/internal/v1/workflow-plans/{PLAN_ID}/steps/step-2/task")
    assert request.headers["traceparent"].split("-")[1] == TRACEPARENT.split("-")[1]
    assert request.headers["X-Internal-Signature"]

from __future__ import annotations

import asyncio
import copy
import json
from datetime import UTC, datetime, timedelta
from typing import Literal, cast

import pytest
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from pydantic import ValidationError

from app.common.settings import AppSettings
from app.harness.runtime import HarnessViolation
from app.observability.tracing import configure_tracing, shutdown_tracing
from app.tools.registry import ToolExecutionError
from app.workers.control_plane import ControlPlaneRejected, ControlPlaneUnavailable
from app.workers.models import WorkerStatusSnapshot
from app.workers.transport import LocalMessageBroker, MessageBrokerUnavailable, RawDelivery
from app.workers.workflow_control_plane import (
    WorkflowAccepted,
    WorkflowClaim,
    WorkflowControlPlane,
    WorkflowObservationAccepted,
    WorkflowTaskActivation,
)
from app.workers.workflow_models import (
    WorkflowExecutionContext,
    WorkflowExecutionMessage,
    WorkflowObservation,
)
from app.workers.workflow_runtime import WorkflowBudgetCounter, WorkflowWorkerRuntime

PLAN_ID = "90436416797933568"
EVENT_ID = "90436416797933569"
TRACEPARENT = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
CONVERSATION_ID = "90436416797933571"
SPACE_ID = "90436416797933572"
PRODUCT_ID = "90436416797933573"
COMPETITOR_ID = "90436416797933574"
MESSAGE_ID = "90436416797933575"
TASK_DRAFT_ID = "90436416797933576"
TASK_ID = "90436416797933577"


def _message(**updates: object) -> dict[str, object]:
    value: dict[str, object] = {
        "schemaVersion": 1,
        "eventId": EVENT_ID,
        "eventType": "WORKFLOW_EXECUTION_REQUESTED",
        "planId": PLAN_ID,
        "planVersion": 1,
        "attempt": 1,
        "traceparent": TRACEPARENT,
    }
    value.update(updates)
    return value


def _authorized_context() -> dict[str, object]:
    return {
        "runId": "7bc33f41-a597-4d2d-b3bd-284ba54b1258",
        "conversationId": CONVERSATION_ID,
        "contextVersion": 1,
        "space": {
            "id": SPACE_ID,
            "name": "测试经营空间",
            "brand": {
                "name": "测试品牌",
                "aliases": [],
                "description": "可靠的日用品牌",
                "tone": ["简洁"],
                "audience": ["家庭用户"],
                "sellingPoints": ["耐用"],
                "prohibitedClaims": [],
            },
            "logoAssetId": None,
        },
        "product": {
            "id": PRODUCT_ID,
            "spaceId": SPACE_ID,
            "name": "陶瓷马克杯",
            "aliases": [],
            "category": "杯具",
            "modelSpec": None,
            "sellingPoints": ["易清洁"],
            "parameters": {"容量": "350ml"},
            "audience": ["办公用户"],
            "usageScenarios": ["办公室"],
            "prohibitedClaims": [],
            "primaryAssetId": None,
            "identityAssetIds": [],
        },
        "competitors": [
            {
                "id": COMPETITOR_ID,
                "spaceId": SPACE_ID,
                "name": "竞品杯",
                "aliases": [],
                "type": "DIRECT",
                "description": "同类杯具",
                "status": "ACTIVE",
            }
        ],
        "attachmentAssetIds": [],
        "conversationMemory": {
            "messages": [
                {
                    "messageId": MESSAGE_ID,
                    "sequence": 1,
                    "role": "USER",
                    "content": {"text": "比较商品和竞品后生成素材"},
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
            "currentGoal": "比较商品和竞品后生成素材",
            "currentIntent": "MEDIA",
            "activeScope": {
                "spaceId": SPACE_ID,
                "productId": PRODUCT_ID,
                "competitorIds": [COMPETITOR_ID],
            },
            "confirmedConstraints": {"style": "clean"},
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
            "reservedToolTokens": 2000,
            "reservedSafetyTokens": 1000,
            "availableHistoryTokens": 28000,
            "estimatedHistoryTokens": 20,
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


def _context(*, resumed: bool = False, max_provider_calls: int = 2) -> dict[str, object]:
    completed: list[dict[str, object]] = []
    status = "READY"
    if resumed:
        status = "SUCCEEDED"
        completed = [
            {
                "id": "90436416797933570",
                "stepId": "step-1",
                "eventId": "d8197454-a576-56d4-b643-c43011c56688",
                "toolCallId": "2fd86838-0cb5-54cb-a280-6e627a8512aa",
                "stepAttempt": 1,
                "status": "SUCCEEDED",
                "resultType": "TOOL_OBSERVATION",
                "resultRef": "observation:catalog",
                "summary": "done",
                "facts": {},
                "retryable": False,
                "completedAt": datetime.now(UTC).isoformat(),
            }
        ]
    return {
        "planId": PLAN_ID,
        "planVersion": 1,
        "executionNo": 2 if resumed else 1,
        "plan": {
            "id": PLAN_ID,
            "conversationId": CONVERSATION_ID,
            "version": 1,
            "status": "RUNNING",
            "goalSummary": "比较商品与竞品并准备媒体素材",
        },
        "steps": [
            {
                "id": "step-1",
                "ordinal": 1,
                "type": "QUERY",
                "toolName": "context.catalog.inspect",
                "toolVersion": "1",
                "risk": "QUERY",
                "status": status,
                "dependsOn": [],
                "successCriteria": ["catalog inspected"],
                "inputRefs": ["context:catalog"],
                "outputRef": "observation:catalog",
            },
            {
                "id": "step-2",
                "ordinal": 2,
                "type": "PREPARE",
                "toolName": "task_draft.propose",
                "toolVersion": "1",
                "risk": "PREPARE",
                "status": "PENDING",
                "dependsOn": ["step-1"],
                "successCriteria": ["proposal reference checked"],
                "inputRefs": ["observation:catalog", "task:draft"],
                "outputRef": "draft:prepared",
                "taskDraftId": TASK_DRAFT_ID,
                **(
                    {
                        "status": "SUCCEEDED",
                        "taskId": TASK_ID,
                        "taskStatus": "SUCCEEDED",
                        "taskResultRef": "asset:90436416797933578",
                    }
                    if resumed
                    else {}
                ),
            },
            {
                "id": "step-3",
                "ordinal": 3,
                "type": "SYNTHESIZE",
                "toolName": "analysis.evidence.aggregate",
                "toolVersion": "1",
                "risk": "ANALYZE",
                "status": "READY" if resumed else "PENDING",
                "dependsOn": ["step-1", "step-2"],
                "successCriteria": ["evidence synthesized"],
                "inputRefs": ["observation:catalog", "asset:90436416797933578"],
                "outputRef": "observation:synthesis",
            },
        ],
        "observations": completed,
        "budget": {
            "maxSteps": 3,
            "maxReplans": 1,
            "maxModelCalls": 2,
            "maxToolCalls": 3,
            "maxProviderCalls": max_provider_calls,
            "authorizedMaxPoints": 10,
            "deadlineAt": (datetime.now(UTC) + timedelta(minutes=10)).isoformat(),
        },
        "resumeOnly": resumed,
        "authorizedContext": _authorized_context(),
        "authorizedContextSchemaVersion": 1,
        "authorizedContextHash": "a" * 64,
        "authorizedContextSource": "PLAN_SNAPSHOT",
    }


class _Control:
    def __init__(
        self,
        *,
        disposition: Literal["CLAIMED", "DUPLICATE", "TERMINAL", "UNAVAILABLE"] = "CLAIMED",
        context: dict[str, object] | None = None,
    ) -> None:
        self.disposition = disposition
        self.payload = context or _context()
        self.calls: list[str] = []
        self.observations: list[WorkflowObservation] = []
        self.activation_arguments: list[dict[str, object]] = []

    async def probe(self) -> bool:
        self.calls.append("probe")
        return True

    async def claim(self, message: WorkflowExecutionMessage, **kwargs: object) -> WorkflowClaim:
        del message, kwargs
        self.calls.append("claim")
        if self.disposition == "UNAVAILABLE":
            raise ControlPlaneUnavailable("WORKFLOW_JAVA_CONTROL_UNAVAILABLE")
        if self.disposition != "CLAIMED":
            return WorkflowClaim(
                claimed=False,
                disposition=cast(Literal["DUPLICATE", "TERMINAL"], self.disposition),
            )
        return WorkflowClaim(
            claimed=True,
            disposition="CLAIMED",
            execution_no=cast(int, self.payload["executionNo"]),
            execution_token="execution-token",
            lease_expires_at=datetime.now(UTC) + timedelta(minutes=2),
        )

    async def context(self, *args: object, **kwargs: object) -> WorkflowExecutionContext:
        del args, kwargs
        self.calls.append("context")
        return WorkflowExecutionContext.model_validate(self.payload)

    async def heartbeat(self, *args: object, **kwargs: object) -> None:
        del args, kwargs
        self.calls.append("heartbeat")

    async def step_state(self, _plan_id: str, step_id: str, **kwargs: object) -> WorkflowAccepted:
        del kwargs
        self.calls.append(f"state:{step_id}")
        return WorkflowAccepted.model_validate(
            {
                "accepted": True,
                "disposition": "RECORDED",
                "planStatus": "RUNNING",
                "planVersion": 1,
            }
        )

    async def observation(self, _plan_id: str, **kwargs: object) -> WorkflowObservationAccepted:
        observation = cast(WorkflowObservation, kwargs["observation"])
        self.observations.append(observation)
        self.calls.append(f"observation:{observation.step_id}")
        return WorkflowObservationAccepted.model_validate(
            {
                "accepted": True,
                "disposition": "RECORDED",
                "planStatus": "RUNNING",
                "sequence": 1,
            }
        )

    async def activate_task(
        self, _plan_id: str, step_id: str, **kwargs: object
    ) -> WorkflowTaskActivation:
        self.activation_arguments.append(dict(kwargs))
        self.calls.append(f"activate:{step_id}")
        return WorkflowTaskActivation.model_validate(
            {
                "accepted": True,
                "disposition": "ACTIVATED",
                "planId": PLAN_ID,
                "stepId": step_id,
                "taskId": TASK_ID,
                "taskStatus": "QUEUED",
                "stepStatus": "WAITING",
                "waitingForTask": True,
                "executionNo": kwargs["execution_no"],
            }
        )

    async def replan(self, *args: object, **kwargs: object) -> WorkflowAccepted:
        del args, kwargs
        self.calls.append("replan")
        return WorkflowAccepted.model_validate(
            {
                "accepted": True,
                "disposition": "RECORDED",
                "planStatus": "RUNNING",
                "planVersion": 2,
            }
        )

    async def complete(self, *args: object, **kwargs: object) -> WorkflowAccepted:
        del args
        self.calls.append(f"complete:{kwargs['status']}")
        return WorkflowAccepted.model_validate(
            {
                "accepted": True,
                "disposition": "RECORDED",
                "planStatus": kwargs["status"],
                "planVersion": 1,
            }
        )

    async def aclose(self) -> None:
        self.calls.append("close")


def _runtime(
    control: _Control, *, recovery_seconds: float = 1.0
) -> tuple[WorkflowWorkerRuntime, LocalMessageBroker]:
    settings = AppSettings(
        environment="test",
        worker_task_type="WORKFLOW",
        worker_provider="REAL",
        worker_max_concurrency=1,
    )
    broker = LocalMessageBroker()
    return (
        WorkflowWorkerRuntime(
            settings=settings,
            bundle_version="agent-bundle-v1.6.0",
            control_plane=cast(WorkflowControlPlane, control),
            broker=broker,
            heartbeat_interval_seconds=60,
            dependency_recovery_base_seconds=recovery_seconds,
            dependency_recovery_max_seconds=recovery_seconds,
        ),
        broker,
    )


def test_workflow_envelope_and_context_are_strict_and_budgeted() -> None:
    parsed = WorkflowExecutionMessage.model_validate(_message())
    assert parsed.plan_id == PLAN_ID
    for invalid in (
        _message(extra=True),
        _message(planId="0"),
        _message(planId="9223372036854775808"),
        _message(traceparent="00-" + "0" * 32 + "-0123456789abcdef-01"),
    ):
        with pytest.raises(ValidationError):
            WorkflowExecutionMessage.model_validate(invalid)
    context = WorkflowExecutionContext.model_validate(_context())
    context.assert_matches(parsed, 1)
    context_with_activation_budget = WorkflowExecutionContext.model_validate(
        _context(max_provider_calls=1)
    )
    assert context_with_activation_budget.budget.max_provider_calls == 1
    malformed = _context()
    cast(dict[str, object], cast(list[object], malformed["steps"])[1])["dependsOn"] = ["step-2"]
    with pytest.raises(ValidationError):
        WorkflowExecutionContext.model_validate(malformed)


def test_workflow_budget_checks_every_action_before_increment() -> None:
    context = WorkflowExecutionContext.model_validate(_context(max_provider_calls=0))
    counters = WorkflowBudgetCounter(context)
    counters.before_step()
    counters.before_step()
    counters.before_step()
    with pytest.raises(HarnessViolation) as step_error:
        counters.before_step()
    assert step_error.value.code == "WORKFLOW_STEP_BUDGET_EXCEEDED"
    with pytest.raises(HarnessViolation) as provider_error:
        counters.before_provider_call()
    assert provider_error.value.code == "WORKFLOW_PROVIDER_BUDGET_EXCEEDED"
    assert counters.providers == 0
    counters.before_replan()
    with pytest.raises(HarnessViolation) as replan_error:
        counters.before_replan()
    assert replan_error.value.code == "WORKFLOW_REPLAN_BUDGET_EXCEEDED"
    assert counters.replans == 1


def test_invalid_delivery_rejects_and_transient_claim_nacks() -> None:
    async def scenario() -> tuple[list[str], list[str], WorkerStatusSnapshot]:
        invalid_control = _Control()
        runtime, _ = _runtime(invalid_control)
        invalid = RawDelivery(body=b"{}", delivery_id="bad")
        await runtime.process_delivery(invalid)
        unavailable = _Control(disposition="UNAVAILABLE")
        runtime2, _ = _runtime(unavailable)
        delivery = RawDelivery(
            body=json.dumps(_message()).encode(),
            delivery_id=EVENT_ID,
        )
        await runtime2.process_delivery(delivery)
        status = runtime2.status_snapshot()
        await runtime.close()
        await runtime2.close()
        return invalid.audit, delivery.audit, status

    invalid_audit, unavailable_audit, status = asyncio.run(scenario())
    assert invalid_audit == ["reject-dlq"]
    assert unavailable_audit == ["nack-requeue"]
    assert status.status == "NOT_READY"


@pytest.mark.parametrize("disposition", ["DUPLICATE", "TERMINAL"])
def test_duplicate_and_terminal_ack_without_tools(
    disposition: Literal["DUPLICATE", "TERMINAL"],
) -> None:
    async def scenario() -> tuple[list[str], list[str]]:
        control = _Control(disposition=disposition)
        runtime, _ = _runtime(control)
        delivery = RawDelivery(body=json.dumps(_message()).encode(), delivery_id=EVENT_ID)
        await runtime.process_delivery(delivery)
        return delivery.audit, control.calls

    audit, calls = asyncio.run(scenario())
    assert audit == ["ack"]
    assert calls == ["claim"]


def test_claimed_workflow_executes_registry_tools_and_proposal_reference() -> None:
    async def scenario(resumed: bool) -> tuple[list[str], list[str]]:
        control = _Control(context=_context(resumed=resumed))
        runtime, _ = _runtime(control)
        delivery = RawDelivery(
            body=json.dumps(
                _message(attempt=2, resumeOnly=True) if resumed else _message()
            ).encode(),
            delivery_id=EVENT_ID,
        )
        await runtime.process_delivery(delivery)
        return delivery.audit, control.calls

    fresh_audit, fresh_calls = asyncio.run(scenario(False))
    resumed_audit, resumed_calls = asyncio.run(scenario(True))
    assert fresh_audit == ["ack"]
    assert fresh_calls == [
        "claim",
        "context",
        "state:step-1",
        "observation:step-1",
        "state:step-2",
        "activate:step-2",
    ]
    assert resumed_audit == ["ack"]
    assert "state:step-1" not in resumed_calls
    assert "activate:step-2" not in resumed_calls
    assert resumed_calls[-1] == "complete:SUCCEEDED"


def test_v019_authorized_context_is_strict_and_drives_real_read_only_tools() -> None:
    async def scenario() -> _Control:
        control = _Control()
        runtime, _ = _runtime(control)
        delivery = RawDelivery(body=json.dumps(_message()).encode(), delivery_id=EVENT_ID)
        await runtime.process_delivery(delivery)
        return control

    control = asyncio.run(scenario())
    catalog = cast(dict[str, object], control.observations[0].facts["output"])
    serialized = json.dumps(catalog, ensure_ascii=False)
    assert "陶瓷马克杯" in serialized
    assert "竞品杯" in serialized
    assert control.calls[-1] == "activate:step-2"
    assert not any(call.startswith("complete:") for call in control.calls)

    for field, invalid in (
        ("authorizedContextSchemaVersion", 2),
        ("authorizedContextHash", "A" * 64),
        ("authorizedContextSource", "LIVE_LOOKUP"),
    ):
        payload = copy.deepcopy(_context())
        payload[field] = invalid
        with pytest.raises(ValidationError):
            WorkflowExecutionContext.model_validate(payload)
    unknown = copy.deepcopy(_context())
    cast(dict[str, object], unknown["authorizedContext"])["contextRef"] = "forbidden"
    with pytest.raises(ValidationError):
        WorkflowExecutionContext.model_validate(unknown)
    mismatched = copy.deepcopy(_context())
    workspace = cast(
        dict[str, object],
        cast(dict[str, object], mismatched["authorizedContext"])["taskWorkspace"],
    )
    cast(dict[str, object], workspace["activeScope"])["productId"] = None
    with pytest.raises(ValidationError):
        WorkflowExecutionContext.model_validate(mismatched)


@pytest.mark.parametrize("duplicate", [False, True])
def test_prepare_activation_enters_durable_wait_once_without_post_fence_writes(
    duplicate: bool,
) -> None:
    class ActivationControl(_Control):
        async def activate_task(
            self, _plan_id: str, step_id: str, **kwargs: object
        ) -> WorkflowTaskActivation:
            self.calls.append(f"activate:{step_id}")
            self.activation_arguments.append(dict(kwargs))
            return WorkflowTaskActivation.model_validate(
                {
                    "accepted": not duplicate,
                    "disposition": "DUPLICATE" if duplicate else "ACTIVATED",
                    "planId": PLAN_ID,
                    "stepId": step_id,
                    "taskId": TASK_ID,
                    "taskStatus": "QUEUED",
                    "stepStatus": "WAITING",
                    "waitingForTask": True,
                    "executionNo": kwargs["execution_no"],
                }
            )

    async def scenario() -> tuple[_Control, WorkerStatusSnapshot]:
        control = ActivationControl()
        runtime, _ = _runtime(control)
        delivery = RawDelivery(body=json.dumps(_message()).encode(), delivery_id=EVENT_ID)
        await runtime.process_delivery(delivery)
        return control, runtime.status_snapshot()

    control, status = asyncio.run(scenario())
    assert control.calls.count("activate:step-2") == 1
    assert control.calls[-1] == "activate:step-2"
    assert not any(call == "observation:step-2" for call in control.calls)
    assert not any(call.startswith("complete:") for call in control.calls)
    assert status.processed == 1
    assert status.failed == 0


def test_resume_generation_32_skips_activation_and_passes_task_result_to_synthesis() -> None:
    payload = _context(resumed=True)
    payload["executionNo"] = 32

    async def scenario() -> _Control:
        control = _Control(context=payload)
        runtime, _ = _runtime(control)
        delivery = RawDelivery(
            body=json.dumps(_message(attempt=32, resumeOnly=True)).encode(),
            delivery_id=EVENT_ID,
        )
        await runtime.process_delivery(delivery)
        return control

    control = asyncio.run(scenario())
    assert "activate:step-2" not in control.calls
    synthesis = next(item for item in control.observations if item.step_id == "step-3")
    output = cast(dict[str, object], synthesis.facts["output"])
    assert "taskResultRef=asset:90436416797933578" in cast(list[str], output["facts"])
    assert control.calls[-1] == "complete:SUCCEEDED"


def test_failed_task_terminates_once_without_reactivation() -> None:
    payload = _context(resumed=True)
    prepare = cast(dict[str, object], cast(list[object], payload["steps"])[1])
    prepare.update(status="FAILED", taskStatus="FAILED")
    prepare.pop("taskResultRef")

    async def scenario() -> _Control:
        control = _Control(context=payload)
        runtime, _ = _runtime(control)
        delivery = RawDelivery(
            body=json.dumps(_message(attempt=2, resumeOnly=True)).encode(),
            delivery_id=EVENT_ID,
        )
        await runtime.process_delivery(delivery)
        return control

    control = asyncio.run(scenario())
    assert "activate:step-2" not in control.calls
    assert control.calls.count("complete:FAILED") == 1
    assert "replan" not in control.calls


def test_activation_budget_and_control_failures_stop_before_duplicate_side_effects() -> None:
    budget_payload = _context(max_provider_calls=0)

    class UnavailableActivation(_Control):
        async def activate_task(self, *args: object, **kwargs: object) -> WorkflowTaskActivation:
            del args, kwargs
            self.calls.append("activate:step-2")
            raise ControlPlaneUnavailable("WORKFLOW_JAVA_CONTROL_UNAVAILABLE")

    class FencedActivation(_Control):
        async def activate_task(self, *args: object, **kwargs: object) -> WorkflowTaskActivation:
            del args, kwargs
            self.calls.append("activate:step-2")
            raise ControlPlaneRejected("WORKFLOW_JAVA_CONTROL_REJECTED")

    async def run(control: _Control) -> tuple[_Control, WorkerStatusSnapshot]:
        runtime, _ = _runtime(control)
        delivery = RawDelivery(body=json.dumps(_message()).encode(), delivery_id=EVENT_ID)
        await runtime.process_delivery(delivery)
        status = runtime.status_snapshot()
        await runtime.close()
        return control, status

    budget_control, _ = asyncio.run(run(_Control(context=budget_payload)))
    assert "activate:step-2" not in budget_control.calls
    assert budget_control.calls.count("complete:FAILED") == 1

    unavailable, unavailable_status = asyncio.run(run(UnavailableActivation()))
    assert unavailable.calls.count("activate:step-2") == 1
    assert not any(call.startswith("complete:") for call in unavailable.calls)
    assert unavailable_status.status == "NOT_READY"

    fenced, _ = asyncio.run(run(FencedActivation()))
    assert fenced.calls.count("activate:step-2") == 1
    assert not any(call.startswith("complete:") for call in fenced.calls)


def test_workflow_worker_recovers_control_plane_and_resumes_consumption() -> None:
    class RecoveringControl(_Control):
        def __init__(self) -> None:
            super().__init__(disposition="UNAVAILABLE")
            self.probe_calls = 0

        async def probe(self) -> bool:
            self.calls.append("probe")
            self.probe_calls += 1
            return self.probe_calls != 2

    async def scenario() -> tuple[str, bool, int]:
        control = RecoveringControl()
        runtime, _ = _runtime(control, recovery_seconds=0.001)
        assert await runtime.start()
        delivery = RawDelivery(body=json.dumps(_message()).encode(), delivery_id=EVENT_ID)
        await runtime.process_delivery(delivery)
        assert runtime.status_snapshot().status == "NOT_READY"

        for _ in range(100):
            if runtime.is_ready:
                break
            await asyncio.sleep(0.001)

        snapshot = runtime.status_snapshot()
        result = snapshot.status, snapshot.accepting, control.probe_calls
        await runtime.close()
        return result

    status, accepting, probe_calls = asyncio.run(scenario())
    assert status == "READY"
    assert accepting is True
    assert probe_calls == 3


def test_unclassified_workflow_failure_requeues_once_then_rejects_without_stopping() -> None:
    class BrokenClaim(_Control):
        async def claim(self, message: WorkflowExecutionMessage, **kwargs: object) -> WorkflowClaim:
            del message, kwargs
            self.calls.append("claim")
            raise ValueError("synthetic unexpected claim failure")

    async def settled(delivery: RawDelivery) -> None:
        for _ in range(100):
            if delivery.state != "PENDING":
                return
            await asyncio.sleep(0)
        raise AssertionError("Local workflow delivery was not settled")

    async def scenario() -> tuple[str, bool, str, bool, WorkerStatusSnapshot, list[str]]:
        control = BrokenClaim()
        runtime, broker = _runtime(control)
        assert await runtime.start()
        body = json.dumps(_message()).encode()
        first = await broker.publish(body)
        await settled(first)
        second = await broker.publish(body, redelivered=True)
        await settled(second)
        status = runtime.status_snapshot()
        await runtime.close()
        return (
            first.state,
            first.requeue,
            second.state,
            second.requeue,
            status,
            control.calls,
        )

    first_state, first_requeue, second_state, second_requeue, status, calls = asyncio.run(
        scenario()
    )
    assert first_state == "NACKED"
    assert first_requeue is True
    assert second_state == "REJECTED"
    assert second_requeue is False
    assert status.status == "READY"
    assert status.accepting is True
    assert status.reason_code is None
    assert status.failed == 2
    assert calls.count("claim") == 2


@pytest.mark.parametrize(
    ("current_generation", "expected_status", "expected_accepting"),
    ((1, "NOT_READY", False), (2, "READY", True)),
)
def test_workflow_settlement_failure_only_invalidates_its_broker_generation(
    current_generation: int, expected_status: str, expected_accepting: bool
) -> None:
    class BrokenClaim(_Control):
        async def claim(self, message: WorkflowExecutionMessage, **kwargs: object) -> WorkflowClaim:
            del message, kwargs
            self.calls.append("claim")
            raise ValueError("synthetic unexpected claim failure")

    class FailingNackDelivery(RawDelivery):
        async def nack(self, *, requeue: bool) -> None:
            del requeue
            raise MessageBrokerUnavailable("synthetic NACK failure", generation=1)

    class GenerationBroker(LocalMessageBroker):
        def __init__(self) -> None:
            super().__init__()
            self.invalidations: list[int | None] = []
            self.invalidated = asyncio.Event()

        async def publish(self, body: bytes, *, redelivered: bool = False) -> RawDelivery:
            if not self.accepting:
                raise RuntimeError("Worker broker is draining")
            delivery = FailingNackDelivery(body=body, redelivered=redelivered)
            await self._queue.put(delivery)
            return delivery

        async def invalidate(self, generation: int | None) -> bool:
            self.invalidations.append(generation)
            self.invalidated.set()
            return generation == current_generation

    async def scenario() -> tuple[WorkerStatusSnapshot, list[int | None], str]:
        settings = AppSettings(
            environment="test",
            worker_task_type="WORKFLOW",
            worker_provider="REAL",
            worker_max_concurrency=1,
        )
        broker = GenerationBroker()
        runtime = WorkflowWorkerRuntime(
            settings=settings,
            bundle_version="agent-bundle-v1.6.0",
            control_plane=cast(WorkflowControlPlane, BrokenClaim()),
            broker=broker,
            heartbeat_interval_seconds=60,
            dependency_recovery_base_seconds=10,
            dependency_recovery_max_seconds=10,
        )
        assert await runtime.start()
        delivery = await broker.publish(json.dumps(_message()).encode())
        await asyncio.wait_for(broker.invalidated.wait(), timeout=1)
        status = runtime.status_snapshot()
        await runtime.close()
        return status, broker.invalidations, delivery.state

    status, invalidations, delivery_state = asyncio.run(scenario())
    assert invalidations == [1]
    assert delivery_state == "PENDING"
    assert status.status == expected_status
    assert status.accepting is expected_accepting


def test_activation_idempotency_uuid_survives_lease_generation_change() -> None:
    async def event_for(payload: dict[str, object], message: dict[str, object]):
        control = _Control(context=payload)
        runtime, _ = _runtime(control)
        await runtime.process_delivery(
            RawDelivery(body=json.dumps(message).encode(), delivery_id=EVENT_ID)
        )
        return control.activation_arguments[0]["event_id"]

    first = asyncio.run(event_for(_context(), _message()))
    recovered = _context()
    recovered["executionNo"] = 2
    recovered["resumeOnly"] = True
    second = asyncio.run(event_for(recovered, _message(attempt=2, resumeOnly=True)))
    assert first == second


def test_workflow_trace_is_one_parented_tree_across_wait_and_resume() -> None:
    exporter = InMemorySpanExporter()
    tracing = configure_tracing(
        AppSettings(otel_enabled=True, otel_sample_ratio=1),
        process_role="workflow-worker",
        span_exporter=exporter,
        synchronous_export=True,
    )

    async def run(payload: dict[str, object], envelope: dict[str, object]) -> None:
        control = _Control(context=payload)
        runtime, _ = _runtime(control)
        await runtime.process_delivery(
            RawDelivery(body=json.dumps(envelope).encode(), delivery_id=EVENT_ID)
        )

    try:
        asyncio.run(run(_context(), _message()))
        fresh = list(exporter.get_finished_spans())
        exporter.clear()
        asyncio.run(run(_context(resumed=True), _message(attempt=2, resumeOnly=True)))
        resumed = list(exporter.get_finished_spans())

        fresh_by_name = {span.name: span for span in fresh}
        root = fresh_by_name["rabbit.consume"]
        assert root.context is not None
        assert f"{root.context.trace_id:032x}" == "0123456789abcdef0123456789abcdef"
        parent_name = {
            span.name: next(
                (
                    candidate.name
                    for candidate in fresh
                    if candidate.context is not None
                    and span.parent is not None
                    and candidate.context.span_id == span.parent.span_id
                ),
                None,
            )
            for span in fresh
        }
        assert parent_name["workflow.claim"] == "rabbit.consume"
        assert parent_name["workflow.execution"] == "rabbit.consume"
        assert parent_name["workflow.context.load"] == "workflow.execution"
        assert parent_name["workflow.task.activate"] == "workflow.step"
        assert parent_name["workflow.wait"] == "workflow.step"
        tool = next(span for span in fresh if span.name == "tool.context.catalog.inspect")
        assert tool.parent is not None
        assert any(
            item.name == "workflow.step"
            and item.context is not None
            and item.context.span_id == tool.parent.span_id
            for item in fresh
        )
        assert all(
            span.context is not None and span.context.trace_id == root.context.trace_id
            for span in fresh
        )
        assert any(span.name == "workflow.resume" for span in resumed)
        assert not any(span.name == "workflow.task.activate" for span in resumed)
        assert any(span.name == "workflow.complete" for span in resumed)
        rendered = repr(fresh + resumed)
        for forbidden in (
            "execution-token",
            "比较商品和竞品后生成素材",
            "陶瓷马克杯",
            "Authorization",
        ):
            assert forbidden not in rendered
    finally:
        shutdown_tracing(tracing)


def test_workflow_deterministic_fault_matrix_has_bounded_safe_outcomes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class ContextUnavailable(_Control):
        async def context(self, *args: object, **kwargs: object) -> WorkflowExecutionContext:
            del args, kwargs
            self.calls.append("context")
            raise ControlPlaneUnavailable("WORKFLOW_JAVA_CONTEXT_UNAVAILABLE")

    class Fenced(_Control):
        async def step_state(
            self, _plan_id: str, step_id: str, **kwargs: object
        ) -> WorkflowAccepted:
            del step_id, kwargs
            self.calls.append("fenced")
            raise ControlPlaneRejected("WORKFLOW_FENCING_REJECTED")

    async def run(
        control: _Control,
        payload: dict[str, object] | None = None,
    ) -> tuple[list[str], WorkerStatusSnapshot]:
        runtime, _ = _runtime(control)
        delivery = RawDelivery(
            body=json.dumps(payload or _message()).encode(), delivery_id=EVENT_ID
        )
        await runtime.process_delivery(delivery)
        status = runtime.status_snapshot()
        await runtime.close()
        return delivery.audit, status

    claim = _Control(disposition="UNAVAILABLE")
    claim_audit, claim_status = asyncio.run(run(claim))
    assert claim_audit == ["nack-requeue"]
    assert claim_status.status == "NOT_READY"

    context = ContextUnavailable()
    context_audit, context_status = asyncio.run(run(context))
    assert context_audit == ["ack"]
    assert context_status.status == "NOT_READY"
    assert not any(call.startswith("state:") for call in context.calls)

    def fail_payload(*_args: object, **_kwargs: object) -> dict[str, object]:
        raise ToolExecutionError("TOOL_EXECUTION_FAILED")

    monkeypatch.setattr("app.workers.workflow_runtime._payload_for", fail_payload)
    failed_tool = _Control()
    failed_audit, failed_status = asyncio.run(run(failed_tool))
    assert failed_audit == ["ack"]
    assert failed_tool.calls.count("replan") == 0
    assert failed_tool.calls.count("complete:FAILED") == 1
    assert failed_status.failed == 1
    monkeypatch.undo()

    waiting = _Control()
    waiting_audit, waiting_status = asyncio.run(run(waiting))
    assert waiting_audit == ["ack"]
    assert waiting.calls.count("activate:step-2") == 1
    assert waiting_status.processed == 1

    resumed = _Control(context=_context(resumed=True))
    resumed_audit, resumed_status = asyncio.run(run(resumed, _message(attempt=2, resumeOnly=True)))
    assert resumed_audit == ["ack"]
    assert resumed.calls.count("activate:step-2") == 0
    assert resumed.calls.count("complete:SUCCEEDED") == 1
    assert resumed_status.processed == 1

    fenced = Fenced()
    fenced_audit, fenced_status = asyncio.run(run(fenced))
    assert fenced_audit == ["ack"]
    assert fenced.calls.count("fenced") == 1
    assert not any(call.startswith("complete:") for call in fenced.calls)
    assert fenced_status.failed == 1

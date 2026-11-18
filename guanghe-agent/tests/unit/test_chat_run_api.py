"""Chat Run lifecycle, SSE, Stop, and local console tests."""

import asyncio
import json
from dataclasses import replace
from threading import Event
from typing import Any, cast
from uuid import UUID

import pytest
from fastapi.testclient import TestClient

from app.agent.models import ChatRunRequest, TaskDraftProposal
from app.api.application import create_app
from app.common.settings import AppSettings
from app.harness.runtime import record_model_call
from app.integration.java_internal import (
    JavaInternalClientProtocol,
    JavaInternalResponse,
    JsonObject,
)
from app.model_gateway.gateway import (
    GatewayUsage,
    ModelMessage,
    ModelMessages,
    ModelToolCall,
    ModelToolDefinition,
    ModelTurnResult,
)
from app.providers.java_context import (
    AgentIdentity,
    AuthorizedRunContext,
    LocalRunContextProvider,
    ProtocolFeatures,
)
from tests.internal_security import FakeInternalNonceStore, internal_settings, signed_headers
from tests.unit.test_chat_run_isolation import (
    _MonitorContextProvider,  # pyright: ignore[reportPrivateUsage]
    _NativeGateway,  # pyright: ignore[reportPrivateUsage]
)


def _sse_events(raw: str) -> list[dict[str, object]]:
    events: list[dict[str, object]] = []
    for line in raw.splitlines():
        if line.startswith("data: "):
            loaded = cast(dict[str, object], json.loads(line.removeprefix("data: ")))
            assert isinstance(loaded, dict)
            events.append(loaded)
    return events


def test_dev_console_and_complete_sse_run(monkeypatch: pytest.MonkeyPatch) -> None:
    class MonitorContext(LocalRunContextProvider):
        async def load(self, request: ChatRunRequest) -> AuthorizedRunContext:
            # Model output cannot mint this current, server-owned explicit action.
            return await _MonitorContextProvider(request.run_id).load(request)

    def context_factory(
        _settings: AppSettings, _client: JavaInternalClientProtocol
    ) -> LocalRunContextProvider:
        return MonitorContext()

    monkeypatch.setattr("app.api.application.build_run_context_provider", context_factory)
    settings = internal_settings(mock_event_delay_ms=0)
    with TestClient(create_app(settings, internal_nonce_store=FakeInternalNonceStore())) as client:
        console = client.get("/dev/agent")
        accepted = client.post(
            "/dev/v1/runs",
            json={
                "content": "按已选范围监测一次",
                "selectedSpaceId": "401",
                "selectedProductId": "402",
            },
        )
        assert accepted.status_code == 202
        events_url = accepted.json()["eventsUrl"]
        event_response = client.get(events_url, headers=signed_headers("GET", events_url))

    assert console.status_code == 200
    assert "Agent 开发控制台" in console.text
    events = _sse_events(event_response.text)
    event_types = [event["eventType"] for event in events]
    assert event_types[0] == "run.started"
    assert event_types[-3:] == ["task-draft.proposed", "usage.reported", "run.completed"]
    assert event_types.count("message.delta") >= 1
    progress_events = [event for event in events if event["eventType"] == "run.progress"]
    progress_payloads = [cast(dict[str, object], event["payload"]) for event in progress_events]
    assert [payload["ordinal"] for payload in progress_payloads] == sorted(
        cast(int, payload["ordinal"]) for payload in progress_payloads
    )
    assert [payload["elapsedMs"] for payload in progress_payloads] == sorted(
        cast(int, payload["elapsedMs"]) for payload in progress_payloads
    )
    assert len(
        {
            (payload["stageCode"], payload["status"], payload["ordinal"])
            for payload in progress_payloads
        }
    ) == len(progress_payloads)
    assert {"PREPARING_TASK_DRAFT"} == {payload["stageCode"] for payload in progress_payloads}
    assert [payload["status"] for payload in progress_payloads] == ["RUNNING", "SUCCEEDED"]
    assert all(
        set(payload) == {"stageCode", "status", "ordinal", "elapsedMs", "current", "total"}
        for payload in progress_payloads
    )
    proposal = next(event for event in events if event["eventType"] == "task-draft.proposed")
    payload = cast(dict[str, object], proposal["payload"])
    assert isinstance(payload, dict)
    draft = TaskDraftProposal.model_validate(payload)
    assert draft.proposal_only is True
    assert draft.task_type.value == "GEO"
    assert draft.space_id == "401"
    assert draft.product_id == "402"
    assert draft.parameters["mode"] == "MONITOR_RUN"
    assert draft.parameters["platformCodes"] == ["DOUBAO", "QWEN"]
    forbidden_business_fields = {
        "userId",
        "jwt",
        "price",
        "points",
        "confirmationToken",
        "paymentStatus",
        "taskStatus",
        "taskId",
        "chargedPoints",
    }
    assert forbidden_business_fields.isdisjoint(payload)
    assert forbidden_business_fields.isdisjoint(draft.parameters)
    usage_event = next(event for event in events if event["eventType"] == "usage.reported")
    usage = cast(dict[str, object], usage_event["payload"])
    assert usage["workflowStepCount"] == 1
    assert usage["modelCallCount"] == 0
    assert usage["contextLoadCount"] == 0
    assert usage["toolCallCount"] == 1
    assert usage["providerCallCount"] == 0
    assert usage["steps"] == usage["workflowStepCount"]
    assert usage["modelCalls"] == usage["modelCallCount"]
    assert usage["toolCalls"] == usage["toolCallCount"]
    completed = next(event for event in events if event["eventType"] == "run.completed")
    completed_payload = completed["payload"]
    assert isinstance(completed_payload, dict)
    assert completed_payload["domainGraph"] == "explicit-action-v1"


def test_incomplete_creative_requests_return_needs_input_without_task_creation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Java's current rejection replaces retired graph-generated interaction forms."""

    class CreativeContext(LocalRunContextProvider):
        async def load(self, request: ChatRunRequest) -> AuthorizedRunContext:
            return replace(
                await super().load(request),
                agent_identity=AgentIdentity(
                    installation_id="501",
                    agent_key="creative",
                    agent_version="1.0.0",
                    name="创作智能体",
                    project_id="401",
                    capabilities=("chat", "resources.read", "image.generate"),
                ),
                protocol_features=ProtocolFeatures(business_outcome=True),
            )

    class MissingInputJava:
        missing = ""
        calls: list[JsonObject]

        def __init__(self) -> None:
            self.calls = []

        async def execute_run_action(
            self,
            run_id: UUID,
            user_context_ref: str,
            payload: JsonObject,
            traceparent: str | None = None,
        ) -> JavaInternalResponse:
            del run_id, user_context_ref, traceparent
            assert payload["action"] == "image.generate"
            self.calls.append(payload)
            return JavaInternalResponse(
                200,
                {
                    "action": "image.generate",
                    "status": "WAIT_INPUT",
                    "data": {"reasonCode": self.missing, "missingFields": [self.missing]},
                },
                "test-trace",
            )

        async def aclose(self) -> None:
            pass

    class CreativeGateway(_NativeGateway):
        async def complete_turn(
            self, messages: ModelMessages, *, tools: tuple[ModelToolDefinition, ...], **kwargs: Any
        ) -> ModelTurnResult:
            del kwargs
            record_model_call(input_messages=messages)
            assert "image_generate" in {tool.name for tool in tools}
            if messages[-1].role == "tool":
                result = json.loads(messages[-1].content)
                assert result["status"] == "WAIT_INPUT"
                assert "taskId" not in result["data"]
                return ModelTurnResult(
                    ModelMessage(
                        "assistant", "尚未创建任务，请补充: " + result["data"]["reasonCode"]
                    ),
                    "stop",
                    GatewayUsage(request_count=1),
                )
            return ModelTurnResult(
                ModelMessage(
                    "assistant",
                    "",
                    (
                        ModelToolCall(
                            "image-candidate",
                            "image_generate",
                            '{"sourceMode":"CATALOG_PRODUCT","scene":"商品主图"}',
                        ),
                    ),
                ),
                "tool_calls",
                GatewayUsage(request_count=1),
            )

    def context_factory(
        _settings: AppSettings, _client: JavaInternalClientProtocol
    ) -> LocalRunContextProvider:
        return CreativeContext()

    monkeypatch.setattr("app.api.application.build_run_context_provider", context_factory)
    java = MissingInputJava()
    settings = internal_settings(mock_event_delay_ms=0, business_outcome_enabled=True)
    fixtures = (
        {
            "content": "为商品制作白底柔光主图",
            "selectedSpaceId": "401",
            "expectedMissing": "CREATIVE_SOURCE_REQUIRED",
        },
        {
            "content": "生成商品主图",
            "selectedSpaceId": "401",
            "selectedProductId": "402",
            "expectedMissing": "taskDescription",
        },
    )
    with TestClient(
        create_app(
            settings,
            gateway_factory=lambda _: CreativeGateway(),
            java_client_factory=lambda _: cast(JavaInternalClientProtocol, java),
            internal_nonce_store=FakeInternalNonceStore(),
        )
    ) as client:
        for fixture in fixtures:
            expected_missing = fixture["expectedMissing"]
            java.missing = expected_missing
            calls_before = len(java.calls)
            request_body = {
                key: value for key, value in fixture.items() if key != "expectedMissing"
            }
            accepted = client.post("/dev/v1/runs", json=request_body)
            assert accepted.status_code == 202
            events_url = accepted.json()["eventsUrl"]
            response = client.get(events_url, headers=signed_headers("GET", events_url))
            events = _sse_events(response.text)
            event_types = [event["eventType"] for event in events]
            assert event_types[-1] == "run.completed"
            assert not {"task-draft.proposed", "interaction.required", "run.failed"} & set(
                event_types
            )
            assert len(java.calls) == calls_before + 1
            progress_stages = {
                cast(dict[str, object], event["payload"])["stageCode"]
                for event in events
                if event["eventType"] == "run.progress"
            }
            assert "PREPARING_TASK_DRAFT" not in progress_stages
            assert "RUNNING_SAFE_TOOL" in progress_stages
            final = cast(dict[str, Any], events[-1]["payload"])
            assert final["status"] == "COMPLETED"
            assert final["businessOutcome"]["status"] == "NEEDS_INPUT"
            prose = "".join(
                str(cast(dict[str, Any], event["payload"])["delta"])
                for event in events
                if event["eventType"] == "message.delta"
            )
            assert expected_missing in prose and "尚未创建任务" in prose
            usage = next(
                cast(dict[str, Any], event["payload"])
                for event in events
                if event["eventType"] == "usage.reported"
            )
            assert usage["modelCallCount"] == 2 and usage["toolCallCount"] == 1


def test_stop_only_stops_current_free_run() -> None:
    class BlockedGateway(_NativeGateway):
        entered = Event()
        cancelled = Event()

        async def complete_turn(self, messages: ModelMessages, **kwargs: Any) -> ModelTurnResult:
            del kwargs
            record_model_call(input_messages=messages)
            self.entered.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                self.cancelled.set()
                raise
            raise AssertionError("Provider wait must be cancelled")

    gateway = BlockedGateway()
    settings = internal_settings(mock_event_delay_ms=0)
    with TestClient(
        create_app(
            settings,
            gateway_factory=lambda _: gateway,
            internal_nonce_store=FakeInternalNonceStore(),
        )
    ) as client:
        accepted = client.post("/dev/v1/runs", json={"content": "你好，请介绍能力"})
        assert accepted.status_code == 202
        assert gateway.entered.wait(timeout=3)
        run_id = accepted.json()["runId"]
        stop_url = f"/internal/v1/chat-runs/{run_id}/stop"
        stopped = client.post(stop_url, headers=signed_headers("POST", stop_url))
        events_url = accepted.json()["eventsUrl"]
        event_response = client.get(events_url, headers=signed_headers("GET", events_url))

    assert stopped.status_code == 202
    assert stopped.json()["accepted"] is True
    assert gateway.cancelled.is_set()
    events = _sse_events(event_response.text)
    assert events[-1]["eventType"] == "run.completed"
    final_payload = events[-1]["payload"]
    assert isinstance(final_payload, dict)
    assert final_payload["status"] == "STOPPED"
    assert sum(event["eventType"] == "run.completed" for event in events) == 1
    assert not {"message.delta", "task-draft.proposed", "run.failed"} & {
        event["eventType"] for event in events
    }


def test_dev_console_is_not_exposed_in_production() -> None:
    with TestClient(create_app(AppSettings(environment="production"))) as client:
        assert client.get("/dev/agent").status_code == 404
        assert client.post("/dev/v1/runs", json={"content": "hello"}).status_code == 404


def test_unknown_run_returns_safe_404() -> None:
    path = "/internal/v1/chat-runs/00000000-0000-0000-0000-000000000000/events"
    with TestClient(
        create_app(
            internal_settings(),
            internal_nonce_store=FakeInternalNonceStore(),
        )
    ) as client:
        response = client.get(path, headers=signed_headers("GET", path))

    assert response.status_code == 404
    assert response.json()["detail"]["code"] == "RUN_NOT_FOUND"

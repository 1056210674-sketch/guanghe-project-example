"""Real native adapter, readiness, read-only tool loop and public HTTP/SSE wiring."""

import asyncio
import json
from hashlib import sha256
from typing import Any, cast
from uuid import UUID

import httpx
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.api.application import create_app
from app.common.settings import ProviderSettings
from app.integration.java_internal import (
    JavaInternalClientProtocol,
    JavaInternalResponse,
    JsonObject,
)
from app.model_gateway.deepseek import DeepSeekAdapter
from tests.internal_security import FakeInternalNonceStore, internal_settings, signed_headers

_REASONING = "PRIVATE_PROVIDER_REASONING_DO_NOT_PUBLISH"


def _sse_events(raw: str) -> list[dict[str, Any]]:
    return [
        json.loads(line.removeprefix("data: "))
        for line in raw.splitlines()
        if line.startswith("data: ")
    ]


def _stream(*deltas: dict[str, Any], finish: str = "stop") -> httpx.Response:
    frames: list[dict[str, Any] | str] = [
        {"choices": [{"index": 0, "delta": delta}]} for delta in deltas
    ]
    frames.extend(
        [
            {"choices": [{"index": 0, "delta": {}, "finish_reason": finish}]},
            {"choices": [], "usage": {"prompt_tokens": 20, "completion_tokens": 4}},
            "[DONE]",
        ]
    )
    return httpx.Response(
        200,
        headers={"content-type": "text/event-stream"},
        text="".join(
            "data: "
            + (frame if isinstance(frame, str) else json.dumps(frame, ensure_ascii=False))
            + "\n\n"
            for frame in frames
        ),
    )


class _ReadOnlyJava:
    def __init__(self) -> None:
        self.calls: list[tuple[UUID, str, JsonObject]] = []
        self.closed = False

    async def execute_run_action(
        self,
        run_id: UUID,
        user_context_ref: str,
        payload: JsonObject,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        del traceparent
        assert payload["action"] == "context.read"
        assert payload["arguments"] == {}
        self.calls.append((run_id, user_context_ref, payload))
        return JavaInternalResponse(
            200,
            {
                "action": "context.read",
                "status": "COMPLETED",
                "data": {"brand": {"name": "测试品牌"}, "recentTasks": []},
            },
            "test-trace",
        )

    async def aclose(self) -> None:
        self.closed = True


def test_deepseek_adapter_reaches_console_run_and_sse() -> None:
    requests: list[dict[str, Any]] = []
    probes: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/models":
            probes.append(request.method)
            return httpx.Response(
                200,
                json={
                    "object": "list",
                    "data": [
                        {"id": "deepseek-v4-flash", "object": "model"},
                    ],
                },
            )
        assert request.url.path == "/chat/completions" and request.method == "POST"
        payload = json.loads(request.content)
        requests.append(payload)
        assert payload["stream"] is True
        assert payload["stream_options"] == {"include_usage": True}
        assert payload["thinking"] == {"type": "enabled"}
        assert payload["tool_choice"] == "auto"
        names = {tool["function"]["name"] for tool in payload["tools"]}
        assert "context_read" in names
        assert "classify_commerce_intent" not in names
        messages = payload["messages"]
        if messages[-1]["role"] == "tool":
            assert messages[-1]["tool_call_id"] == "provider-read-0"
            result = json.loads(messages[-1]["content"])
            assert result["status"] == "COMPLETED"
            assert result["data"]["brand"]["name"] == "测试品牌"
            # Reasoning is retained only inside the provider protocol for its tool continuation.
            assert messages[-2]["reasoning_content"] == _REASONING
            assert messages[-2]["tool_calls"][0]["function"]["arguments"] == "{}"
            return _stream(
                {"reasoning_content": _REASONING},
                {"content": "已读取"},
                {"content": "测试品牌；未启动监测。"},
            )
        if messages[-1]["content"] == "读取当前品牌资料，不启动监测":
            return _stream(
                {"reasoning_content": _REASONING},
                {
                    "tool_calls": [
                        {
                            "index": 0,
                            "id": "provider-read-0",
                            "type": "function",
                            "function": {"name": "context_read", "arguments": "{"},
                        }
                    ]
                },
                {"tool_calls": [{"index": 0, "function": {"arguments": "}"}}]},
                finish="tool_calls",
            )
        return _stream({"reasoning_content": _REASONING}, {"content": "真实"}, {"content": "回答"})

    provider = ProviderSettings(
        credential=SecretStr("integration-test-only"),
        model="deepseek-v4-flash",
        endpoint="https://deepseek.test",
        max_retries=0,
    )
    provider_client = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    adapter = DeepSeekAdapter(provider, client=provider_client)
    java = _ReadOnlyJava()
    settings = internal_settings(model_gateway="DEEPSEEK", deepseek=provider, mock_event_delay_ms=0)
    with TestClient(
        create_app(
            settings,
            gateway_factory=lambda _settings: adapter,
            java_client_factory=lambda _settings: cast(JavaInternalClientProtocol, java),
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")
        assert readiness.status_code == 200
        checks = {item["name"]: item for item in readiness.json()["checks"]}
        assert checks["modelGateway"]["status"] == "READY"
        capability = client.get(
            "/internal/v1/capabilities", headers=signed_headers("GET", "/internal/v1/capabilities")
        ).json()
        responses: list[str] = []
        accepted_ids: list[UUID] = []
        for content in ("你好，请说明你的能力", "读取当前品牌资料，不启动监测"):
            accepted = client.post("/dev/v1/runs", json={"content": content})
            assert accepted.status_code == 202
            accepted_ids.append(UUID(accepted.json()["runId"]))
            events_url = accepted.json()["eventsUrl"]
            event_response = client.get(events_url, headers=signed_headers("GET", events_url))
            assert event_response.status_code == 200
            responses.append(event_response.text)
    asyncio.run(provider_client.aclose())

    assert probes == ["GET"]
    assert capability["modelGateway"]["mode"] == "REAL"
    assert capability["modelGateway"]["status"] == "AVAILABLE"
    assert capability["intentRouter"]["routerMode"] == "NATIVE_LOOP"
    assert java.closed
    assert len(requests) == 3 and len(java.calls) == 1
    assert java.calls[0][0] == accepted_ids[1]
    assert java.calls[0][2]["toolCallId"] == sha256(b"provider-read-0").hexdigest()

    for raw, calls, tools, expected in zip(
        responses, (1, 2), (0, 1), ("真实回答", "已读取测试品牌；未启动监测。"), strict=True
    ):
        assert _REASONING not in raw and "provider-read-0" not in raw
        events = _sse_events(raw)
        assert events[0]["eventType"] == "run.started"
        assert events[0]["payload"]["mockMode"] is False
        assert events[0]["payload"]["modelVersion"].startswith("DEEPSEEK/deepseek-v4-flash")
        assert events[-1]["eventType"] == "run.completed"
        assert events[-1]["payload"]["status"] == "COMPLETED"
        assert events[-1]["payload"]["domainGraph"] == "native-tool-loop-v1"
        assert not {"task-draft.proposed", "interaction.required", "run.failed"} & {
            event["eventType"] for event in events
        }
        deltas = [
            event["payload"]["delta"] for event in events if event["eventType"] == "message.delta"
        ]
        assert "".join(deltas) == expected and len(deltas) >= 2
        usage = next(event["payload"] for event in events if event["eventType"] == "usage.reported")
        assert usage["providerInputTokens"] == 20 * calls
        assert usage["providerOutputTokens"] == 4 * calls
        assert usage["modelCallCount"] == usage["modelCalls"] == calls
        assert usage["toolCallCount"] == usage["toolCalls"] == tools
        assert usage["workflowStepCount"] == usage["steps"] == calls + tools
        assert usage["contextLoadCount"] == usage["providerCallCount"] == 0

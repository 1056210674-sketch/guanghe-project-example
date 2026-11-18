"""Diagnostic report reads use project scope, never ordinary GEO report bindings."""

import asyncio
import json
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from typing import Any, cast
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.agent.identity import GEO_CAPABILITIES, bind_agent_execution, resolve_execution
from app.agent.models import ChatMessage, ChatRunRequest, RunBudget, TrustedState
from app.harness.runtime import RuntimeHarness, RuntimeUsageCounters, bind_runtime_usage
from app.integration.java_internal import JavaAgentActionClientProtocol, JavaInternalResponse
from app.model_gateway.gateway import ModelToolCall
from app.providers.java_context import LocalRunContextProvider, current_authorized_project_id
from app.providers.resources import ResourceReadRequest, ResourceReadResponse
from app.tools.native import NativeTools, ResourceReadInput


def _response(scope: str = "11") -> dict[str, Any]:
    return {
        "observationId": "9001",
        "kind": "SPACE",
        "purpose": "GEO_DIAGNOSTIC_REPORT",
        "resourceId": scope,
        "name": "当前项目诊断报告",
        "spaceId": scope,
        "observedAt": datetime.now(UTC).isoformat(),
        "sourceHash": "a" * 64,
        "projection": {
            "diagnosticId": "11001",
            "version": 2,
            "reportStatus": "READY",
            "scope": "SINGLE_DIAGNOSTIC_SNAPSHOT",
            "summary": "本次AI回答，非消费者评价。",
        },
        "truncated": False,
    }


def test_diagnostic_purpose_is_available_in_native_input_and_frozen_response() -> None:
    assert (
        "GEO_DIAGNOSTIC_REPORT"
        in ResourceReadInput.model_json_schema()["properties"]["purpose"]["enum"]
    )
    request = ResourceReadRequest.model_validate(
        {"toolCallId": "read-test", "purpose": "GEO_DIAGNOSTIC_REPORT", "observationId": "9001"}
    )
    result = ResourceReadResponse.model_validate(_response())
    assert request.report_policy is None and not request.sample_refs
    assert result.kind == "SPACE" and result.report_id is None and result.report_attempt is None
    assert result.projection["diagnosticId"] == "11001"


@pytest.mark.parametrize(
    "update",
    [
        {"kind": "GEO_TOPIC"},
        {"resourceId": "12"},
        {"productId": "21"},
        {"reportId": "31"},
        {
            "reportAttempt": {
                "runId": "31",
                "status": "SUCCEEDED",
                "createdAt": "2026-10-14T00:00:00Z",
                "newerFailedAttempt": False,
            }
        },
    ],
)
def test_diagnostic_response_cannot_bind_ordinary_geo_or_other_project(
    update: dict[str, Any],
) -> None:
    with pytest.raises(ValidationError):
        ResourceReadResponse.model_validate({**_response(), **update})


@pytest.mark.parametrize(
    "update",
    [
        {"reportPolicy": "LATEST_USABLE"},
        {"sampleRefs": ["opaque-unit"]},
        {"reportId": "11"},
        {"diagnosticId": "11001"},
    ],
)
def test_diagnostic_read_uses_only_server_references_not_stat_policy_or_free_ids(
    update: dict[str, Any],
) -> None:
    with pytest.raises(ValidationError):
        ResourceReadRequest.model_validate(
            {
                "toolCallId": "read-test",
                "purpose": "GEO_DIAGNOSTIC_REPORT",
                "observationId": "9001",
                **update,
            }
        )


@pytest.mark.parametrize("different_project", [False, True])
def test_real_native_read_accepts_only_current_project_and_does_not_create_actions(
    different_project: bool,
) -> None:
    async def scenario() -> None:
        request = ChatRunRequest(
            run_id=uuid4(),
            conversation_id="123",
            user_context_ref="test-diagnostic-read",
            message=ChatMessage(message_id="124", content="读当前项目的诊断报告，接着讨论内容优化"),
            trusted_state=TrustedState(),
            budget=RunBudget(
                max_tool_calls=3, deadline_at=datetime.now(UTC) + timedelta(minutes=1)
            ),
        )
        context = replace(
            await LocalRunContextProvider().load(request),
            selected_space_id="11",
            selected_product_id=None,
        )
        scope = current_authorized_project_id(context)
        assert scope is not None

        class Client:
            calls = 0

            async def read_run_resource(self, *args: Any) -> JavaInternalResponse:
                self.calls += 1
                assert args[2]["purpose"] == "GEO_DIAGNOSTIC_REPORT"
                assert args[2]["observationId"] == "9001"
                assert "reportId" not in args[2] and "diagnosticId" not in args[2]
                return JavaInternalResponse(
                    payload=_response("12" if different_project else scope),
                    status_code=200,
                    trace_id="a" * 32,
                )

            async def execute_run_action(self, *args: Any) -> JavaInternalResponse:
                raise AssertionError("Read-only diagnosis cannot create a task or charge")

        client = Client()
        counters = RuntimeUsageCounters(request, RuntimeHarness())
        with (
            bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
            bind_runtime_usage(counters),
        ):
            tools = NativeTools(
                request, context, cast(JavaAgentActionClientProtocol, client), counters
            )
            definition = next(tool for tool in tools.catalog() if tool.name == "resources_read")
            assert "GEO_DIAGNOSTIC_REPORT" in definition.description
            result = json.loads(
                await tools.execute(
                    ModelToolCall(
                        str(uuid4()),
                        "resources_read",
                        json.dumps({"purpose": "GEO_DIAGNOSTIC_REPORT", "observationId": "9001"}),
                    )
                )
            )
            if different_project:
                assert result["status"] == "ERROR"
            else:
                assert result["status"] == "COMPLETED"
                assert result["data"]["reportId"] is None
                assert result["data"]["projection"]["diagnosticId"] == "11001"
        assert client.calls == 1

    asyncio.run(scenario())

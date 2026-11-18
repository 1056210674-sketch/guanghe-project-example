"""Functional native-loop recovery; rejected turns execute no side effects."""

import asyncio
import json
from typing import cast

import pytest

from app.agent.identity import GEO_CAPABILITIES, bind_agent_execution, resolve_execution
from app.agent.loop import GeoAgentLoop
from app.agent.models import Usage
from app.harness.runtime import (
    RuntimeHarness,
    RuntimeUsageCounters,
    bind_runtime_usage,
    record_model_call,
)
from app.integration.java_internal import JavaAgentActionClientProtocol
from app.model_gateway.gateway import (
    GatewayUsage,
    LocalModelGateway,
    ModelGatewayError,
    ModelMessage,
    ModelToolCall,
    ModelToolDefinition,
    ModelTurnResult,
)
from tests.unit.test_monitor_continuation import JavaRecorder, context, monitor, request


class RepairGateway(LocalModelGateway):
    def __init__(self, *, repeated: bool = False, receipt: bool = False) -> None:
        self.calls = 0
        self.repeated = repeated
        self.receipt = receipt
        self.catalogs: list[set[str]] = []

    async def complete_turn(
        self,
        messages: tuple[ModelMessage, ...],
        *,
        tools: tuple[ModelToolDefinition, ...],
        max_tokens: int,
        thinking: bool = True,
        operation: str = "native_tool_loop",
    ) -> ModelTurnResult:
        record_model_call(input_messages=messages)
        self.calls += 1
        self.catalogs.append({tool.name for tool in tools})
        if self.receipt and self.calls == 1:
            return ModelTurnResult(
                message=ModelMessage("assistant", "", (monitor(),), reasoning_content=""),
                finish_reason="tool_calls",
                usage=GatewayUsage(request_count=1),
            )
        if self.calls == 1 or self.repeated or self.receipt:
            raise ModelGatewayError(
                "DEEPSEEK_NATIVE_TOOL_CALL_INVALID",
                failure_stage="native_tool_unavailable",
                usage=GatewayUsage(request_count=1, input_tokens=20, output_tokens=5),
            )
        assert messages[-1].role == "system"
        assert "没有任何工具被执行" in messages[-1].content
        assert not any(message.tool_calls for message in messages)
        return ModelTurnResult(
            message=ModelMessage(
                "assistant", "已确认这次分析使用的资料，下面是优化方案。", reasoning_content=""
            ),
            finish_reason="stop",
            usage=GatewayUsage(request_count=1, input_tokens=30, output_tokens=10),
        )


class ReadThenRepairGateway(LocalModelGateway):
    def __init__(self) -> None:
        self.calls = 0
        self.catalogs: list[set[str]] = []
        self.before_rejected_turn: tuple[ModelMessage, ...] = ()

    async def complete_turn(
        self,
        messages: tuple[ModelMessage, ...],
        *,
        tools: tuple[ModelToolDefinition, ...],
        max_tokens: int,
        thinking: bool = True,
        operation: str = "native_tool_loop",
    ) -> ModelTurnResult:
        record_model_call(input_messages=messages)
        self.calls += 1
        self.catalogs.append({tool.name for tool in tools})
        if self.calls == 1:
            return ModelTurnResult(
                message=ModelMessage(
                    "assistant",
                    "",
                    (ModelToolCall("read-before-rejection", "context_read", "{}"),),
                    reasoning_content="",
                ),
                finish_reason="tool_calls",
                usage=GatewayUsage(request_count=1, input_tokens=10, output_tokens=2),
            )
        if self.calls == 2:
            self.before_rejected_turn = messages
            assert messages[-1].role == "tool"
            assert messages[-1].tool_call_id == "read-before-rejection"
            assert json.loads(messages[-1].content)["data"]["proof"] == "retained-observation"
            raise ModelGatewayError(
                "DEEPSEEK_NATIVE_TOOL_CALL_INVALID",
                failure_stage="native_tool_unavailable",
                usage=GatewayUsage(request_count=1, input_tokens=20, output_tokens=5),
            )
        assert self.calls == 3
        assert messages[:-1] == self.before_rejected_turn
        assert messages[-1].role == "system"
        assert "没有任何工具被执行" in messages[-1].content
        return ModelTurnResult(
            message=ModelMessage(
                "assistant", "已基于刚才读取的资料完成分析。", reasoning_content=""
            ),
            finish_reason="stop",
            usage=GatewayUsage(request_count=1, input_tokens=30, output_tokens=10),
        )


def test_repair_preserves_successful_read_without_replaying_it() -> None:
    async def run() -> None:
        req = request("根据当前资料给我优化方案，不要启动监测或生成文章")
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        gateway = ReadThenRepairGateway()
        java = JavaRecorder({"proof": "retained-observation"})
        with (
            bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
            bind_runtime_usage(counters),
        ):
            outcome = await GeoAgentLoop(
                gateway, cast(JavaAgentActionClientProtocol, java), "test"
            ).ainvoke(req, authorized_context=context(), usage_counters=counters)
        assert outcome.kind == "MESSAGE"
        assert gateway.calls == counters.model_call_count == 3
        assert java.calls == ["context.read"]
        assert counters.tool_call_count == 1
        assert counters.provider_input_tokens == 60
        assert counters.provider_output_tokens == 17
        assert gateway.catalogs[0] == gateway.catalogs[1] == gateway.catalogs[2]
        assert not {"geo_monitor", "brand_save"} & gateway.catalogs[2]
        assert "article_generate" in gateway.catalogs[2]  # Candidate still needs write review.

    asyncio.run(run())


@pytest.mark.parametrize("exhausted_budget", ["steps", "model_calls"])
def test_repair_after_read_does_not_exceed_remaining_budget(exhausted_budget: str) -> None:
    async def run() -> None:
        req = request("根据当前资料给我优化方案，不要启动监测或生成文章")
        limits = {"max_steps": 5} if exhausted_budget == "steps" else {"max_model_calls": 2}
        req = req.model_copy(update={"budget": req.budget.model_copy(update=limits)})
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        if exhausted_budget == "steps":
            # Two earlier steps leave exactly one read and two model turns.
            counters.before_workflow_step()
            counters.before_workflow_step()
        gateway = ReadThenRepairGateway()
        java = JavaRecorder({"proof": "retained-observation"})
        with (
            bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
            bind_runtime_usage(counters),
            pytest.raises(ModelGatewayError, match="DEEPSEEK_NATIVE_TOOL_CALL_INVALID") as error,
        ):
            await GeoAgentLoop(gateway, cast(JavaAgentActionClientProtocol, java), "test").ainvoke(
                req, authorized_context=context(), usage_counters=counters
            )
        assert gateway.calls == counters.model_call_count == 2
        assert java.calls == ["context.read"]
        assert counters.tool_call_count == 1
        assert counters.workflow_step_count == (5 if exhausted_budget == "steps" else 3)
        usage = cast(Usage, cast(object, error.value.usage))
        assert usage.provider_input_tokens == 30
        assert usage.provider_output_tokens == 7

    asyncio.run(run())


def test_rejected_turn_recovers_once_without_new_authority_or_side_effects() -> None:
    async def run() -> None:
        req = request("是的，是我们的域名。你获取的消息都是准确的")
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        java = JavaRecorder({})
        gateway = RepairGateway()
        with (
            bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
            bind_runtime_usage(counters),
        ):
            outcome = await GeoAgentLoop(
                gateway, cast(JavaAgentActionClientProtocol, java), "test"
            ).ainvoke(req, authorized_context=context(), usage_counters=counters)
        assert outcome.kind == "MESSAGE"
        assert gateway.calls == counters.model_call_count == 2
        assert counters.provider_input_tokens == 50
        assert counters.provider_output_tokens == 15
        assert counters.tool_call_count == 0
        assert java.calls == []
        assert gateway.catalogs[0] == gateway.catalogs[1]
        assert not {"geo_monitor", "brand_save"} & gateway.catalogs[1]
        assert "article_generate" in gateway.catalogs[1]  # Candidate is not authorization.

    asyncio.run(run())


@pytest.mark.parametrize("limit", [1, 6])
def test_repeated_bad_turn_is_bounded_by_repair_and_model_budget(limit: int) -> None:
    async def run() -> None:
        req = request("资料准确")
        req = req.model_copy(
            update={"budget": req.budget.model_copy(update={"max_model_calls": limit})}
        )
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        gateway = RepairGateway(repeated=True)
        java = JavaRecorder({})
        with (
            bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
            bind_runtime_usage(counters),
            pytest.raises(ModelGatewayError, match="DEEPSEEK_NATIVE_TOOL_CALL_INVALID") as error,
        ):
            await GeoAgentLoop(gateway, cast(JavaAgentActionClientProtocol, java), "test").ainvoke(
                req, authorized_context=context(), usage_counters=counters
            )
        assert gateway.calls == counters.model_call_count == min(limit, 2)
        # The outer loop attaches its cumulative Run usage, exactly once per response.
        usage = cast(Usage, cast(object, error.value.usage))
        assert usage.model_call_count == min(limit, 2)
        assert usage.provider_input_tokens == 20 * min(limit, 2)
        assert usage.provider_output_tokens == 5 * min(limit, 2)
        assert java.calls == []

    asyncio.run(run())


@pytest.mark.parametrize("unknown", [False, True])
def test_receipt_or_ambiguous_write_never_replayed_for_protocol_repair(unknown: bool) -> None:
    async def run() -> None:
        req = request("现在开始一次监测，范围广州")
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        gateway = RepairGateway(receipt=True)
        java = JavaRecorder({}, unknown_monitor=unknown)
        with (
            bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
            bind_runtime_usage(counters),
        ):
            outcome = await GeoAgentLoop(
                gateway, cast(JavaAgentActionClientProtocol, java), "test"
            ).ainvoke(req, authorized_context=context(), usage_counters=counters)
        assert outcome.kind == "MESSAGE"
        assert gateway.calls == 2
        assert java.calls == ["geo.monitor"]

    asyncio.run(run())

"""Model-selected continuation through the actual loop, without paid network calls."""

import asyncio
import copy
import json
from typing import Any, cast

import pytest

from app.agent.article_action_review import ArticleActionReview
from app.agent.identity import GEO_CAPABILITIES, bind_agent_execution, resolve_execution
from app.agent.loop import GeoAgentLoop
from app.harness.runtime import (
    HarnessViolation,
    RuntimeHarness,
    RuntimeUsageCounters,
    bind_runtime_usage,
    record_model_call,
)
from app.integration.java_internal import JavaAgentActionClientProtocol, JavaInternalResponse
from app.model_gateway.gateway import (
    GatewayUsage,
    LocalModelGateway,
    ModelMessage,
    ModelToolCall,
    ModelTurnResult,
)
from app.providers.knowledge_context import ProjectMemoryMutation
from app.tools.native import NativeTools
from tests.unit.test_article_continuation import ArticleJavaRecorder, candidate, receipt
from tests.unit.test_monitor_continuation import context, request, tool


class RuntimeJava(ArticleJavaRecorder):
    def __init__(
        self,
        *,
        reads: list[dict[str, Any]] | None = None,
        article_status: str = "COMPLETED",
        article_data: Any = None,
        cancel: bool = False,
    ) -> None:
        super().__init__({"articleContinuation": candidate()})
        self.reads = reads or [copy.deepcopy(self.data)]
        self.article_status = article_status
        self.article_data = receipt() if article_data is None else article_data
        self.cancel = cancel
        self.call_ids: list[str] = []

    async def execute_run_action(self, *args: Any) -> JavaInternalResponse:
        action = args[2]["action"]
        self.calls.append(action)
        self.call_ids.append(args[2]["toolCallId"])
        if action == "context.read":
            data = copy.deepcopy(self.reads[min(self.calls.count(action) - 1, len(self.reads) - 1)])
            status = data.pop("_status", "COMPLETED")
        elif action == "article.generate":
            self.article_arguments.append(args[2]["arguments"])
            if self.cancel:
                raise asyncio.CancelledError
            data, status = self.article_data, self.article_status
        else:
            raise AssertionError(f"Unexpected action {action}")
        return JavaInternalResponse(
            200, {"action": action, "status": status, "data": data}, "0" * 32
        )


class RuntimeGateway(LocalModelGateway):
    def __init__(self, calls: tuple[ModelToolCall, ...], *, allow_answer: bool = False) -> None:
        self.first_calls = calls
        self.allow_answer = allow_answer
        self.requests: list[tuple[ModelMessage, ...]] = []
        self.catalogs: list[set[str]] = []

    async def complete_turn(self, messages: Any, **kwargs: Any) -> ModelTurnResult:
        raise AssertionError("The streaming path should be used")

    async def complete_structured(self, messages: Any, response_model: Any, **kwargs: Any) -> Any:
        record_model_call(input_messages=messages)
        return ArticleActionReview(decision="NEW_DRAFT"), GatewayUsage(request_count=1)

    async def complete_turn_stream(self, messages: Any, **kwargs: Any) -> ModelTurnResult:
        record_model_call(input_messages=messages)
        self.requests.append(messages)
        self.catalogs.append({t.name for t in kwargs["tools"]})
        if len(self.requests) == 1:
            await kwargs["on_delta"]("先读取已确认的写作提议。")
            return ModelTurnResult(
                ModelMessage(
                    "assistant",
                    "先读取已确认的写作提议。",
                    self.first_calls,
                    reasoning_content="private-thinking-never-visible",
                ),
                "tool_calls",
                GatewayUsage(input_tokens=31, output_tokens=9, request_count=1),
            )
        if (
            not self.allow_answer
            and len(self.requests) == 2
            and not any(c.name == "article_generate" for c in self.first_calls)
        ):
            return ModelTurnResult(
                ModelMessage("assistant", "", (tool("article_generate"),)),
                "tool_calls",
                GatewayUsage(request_count=1),
            )
        assert len(self.requests) <= 3
        await kwargs["on_delta"]("本轮仅查看已有状态。")
        return ModelTurnResult(
            ModelMessage("assistant", "本轮仅查看已有状态。", reasoning_content="private"),
            "stop",
            GatewayUsage(input_tokens=20, output_tokens=5, request_count=1),
        )


async def run_loop(
    java: RuntimeJava,
    gateway: RuntimeGateway,
    *,
    content: str = "好的",
    budget: dict[str, int] | None = None,
) -> tuple[Any, RuntimeUsageCounters, list[tuple[str, str]]]:
    req = request(content)
    if budget:
        req = req.model_copy(update={"budget": req.budget.model_copy(update=budget)})
    counters = RuntimeUsageCounters(req, RuntimeHarness())
    events: list[tuple[str, str]] = []
    shown = ""

    async def delta(text: str) -> None:
        nonlocal shown
        shown += text
        events.append(("delta", text))

    async def replace(text: str) -> None:
        nonlocal shown
        shown = text
        events.append(("replace", text))

    async def progress(stage: Any, status: Any, *_: Any) -> None:
        events.append((stage.value, status.value))

    with (
        bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
        bind_runtime_usage(counters),
    ):
        result = await GeoAgentLoop(
            gateway,
            cast(JavaAgentActionClientProtocol, java),
            "test",
            article_review_prompt="review",
        ).ainvoke(
            req,
            authorized_context=context(),
            usage_counters=counters,
            on_delta=delta,
            on_replace=replace,
            on_progress=progress,
        )
    assert result.message == shown
    assert "private-thinking" not in str(events)
    assert not any(
        call.id.startswith("runtime_article_continuation_")
        for messages in gateway.requests
        for message in messages
        for call in message.tool_calls
    )
    return result, counters, events


@pytest.mark.parametrize(
    "content", ("好", "好的", "认可", "好，就按这个写", "继续", "继续做", "继续写")
)
def test_model_selects_confirmed_offer_once_and_streams_response(content: str) -> None:
    async def run() -> None:
        java = RuntimeJava()
        gateway = RuntimeGateway((tool("context_read"),))
        result, counters, events = await run_loop(java, gateway, content=content)
        assert java.calls == ["context.read", "article.generate"]
        assert java.article_arguments[0]["continuationReview"]["decision"] == "NEW_DRAFT"
        assert len(gateway.requests) == 3
        assert all("article_generate" in catalog for catalog in gateway.catalogs)
        assert counters.model_call_count == 4
        assert counters.tool_call_count == 2
        assert counters.workflow_step_count == 6
        assert counters.provider_input_tokens == 51
        assert counters.provider_output_tokens == 14
        assert result.business_outcome.status.value == "ANSWERED"
        assert result.message == "本轮仅查看已有状态。"
        assert events[-3:] == [
            ("COMPOSING_RESPONSE", "RUNNING"),
            ("delta", result.message),
            ("COMPOSING_RESPONSE", "SUCCEEDED"),
        ]
        assert ("replace", "") in events

    asyncio.run(run())


@pytest.mark.parametrize(
    "revoked",
    (
        {},
        {"articleContinuation": candidate(projectId="999")},
        {"articleContinuation": candidate(), "_status": "UNAVAILABLE"},
        {"articleContinuation": candidate(), "knowledge": {"memory": {"projectId": "999"}}},
        {
            "articleContinuation": candidate(
                authorized=False,
                reviewRequired=False,
                submissionState="ALREADY_STARTED",
                preparation={"id": "501", "articleId": "502", "status": "SUCCEEDED"},
            )
        },
    ),
)
def test_whole_batch_finishes_before_fresh_read_revokes_pending(revoked: dict[str, Any]) -> None:
    async def run() -> None:
        java = RuntimeJava(reads=[{"articleContinuation": candidate()}, revoked])
        gateway = RuntimeGateway((tool("context_read"), tool("context_read")), allow_answer=True)
        await run_loop(java, gateway)
        assert java.calls == ["context.read", "context.read"]
        assert not java.article_arguments
        assert "article_generate" in gateway.catalogs[1]

    asyncio.run(run())


def test_repeated_successful_read_still_requires_model_to_call_article() -> None:
    async def run() -> None:
        java = RuntimeJava()
        gateway = RuntimeGateway((tool("context_read"), tool("context_read")))
        _, counters, _ = await run_loop(java, gateway)
        assert java.calls == ["context.read", "context.read", "article.generate"]
        assert counters.tool_call_count == 3
        assert len(gateway.requests) == 3

    asyncio.run(run())


@pytest.mark.parametrize("budget", ({"max_steps": 2}, {"max_tool_calls": 1}))
def test_harness_budget_exhaustion_does_not_submit(
    budget: dict[str, int],
) -> None:
    async def run() -> None:
        java = RuntimeJava()
        gateway = RuntimeGateway((tool("context_read"),))
        result, counters, _ = await run_loop(java, gateway, budget=budget)
        assert java.calls == ["context.read"]
        assert counters.tool_call_count == 1
        if "max_steps" in budget:
            assert result.business_outcome.status.value == "PARTIAL"
            assert "未完成" in result.message
        else:
            # The model may still explain an unexecuted tool after exhausting
            # tool budget; an ordinary answer is not a task receipt.
            assert result.message == "本轮仅查看已有状态。"
            assert not java.article_arguments

    asyncio.run(run())


def test_last_model_call_read_cannot_create_a_task_without_model_choice() -> None:
    async def run() -> None:
        java = RuntimeJava()
        gateway = RuntimeGateway((tool("context_read"),))
        result, counters, _ = await run_loop(java, gateway, budget={"max_model_calls": 1})
        assert java.calls == ["context.read"]
        assert counters.model_call_count == 1
        assert result.business_outcome.status.value == "PARTIAL"

    asyncio.run(run())


@pytest.mark.parametrize("status", ("WAIT_INPUT", "UNAVAILABLE", "MALFORMED"))
def test_non_successful_submission_stops_without_fabricating_an_article(status: str) -> None:
    async def run() -> None:
        java = RuntimeJava(
            article_status="COMPLETED" if status == "MALFORMED" else status,
            article_data={},
        )
        gateway = RuntimeGateway((tool("context_read"),))
        result, counters, events = await run_loop(java, gateway)
        assert java.calls == ["context.read", "article.generate"]
        assert len(gateway.requests) == 3
        assert counters.tool_call_count == 2
        assert "正在生成" not in result.message
        assert result.business_outcome.status.value == (
            "NEEDS_INPUT" if status == "WAIT_INPUT" else "PARTIAL"
        )
        assert ("RUNNING_SAFE_TOOL", "FAILED") in events
        if status == "MALFORMED":
            assert "正在生成" not in result.message

    asyncio.run(run())


@pytest.mark.parametrize("status", ("WAIT_INPUT", "UNAVAILABLE", "MALFORMED"))
def test_consumed_attempt_cannot_be_reopened_by_another_context_read(status: str) -> None:
    async def run() -> None:
        from tests.unit.test_article_action_review import ReviewGateway

        req = request("好的")
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        java = RuntimeJava(
            article_status="COMPLETED" if status == "MALFORMED" else status,
            article_data={},
        )
        with (
            bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
            bind_runtime_usage(counters),
        ):
            tools = NativeTools(
                req,
                context(),
                cast(JavaAgentActionClientProtocol, java),
                counters,
                gateway=ReviewGateway(),
                article_review_prompt="review",
            )
            await tools.execute(tool("context_read"))
            pending = tools.article_continuation_candidate
            assert pending is not None
            call = tool("article_generate")
            result = await tools.execute(call)
            assert tools.article_continuation_attempted
            assert tools.article_continuation_candidate is None
            assert await tools.execute(call) == result
            assert await tools.execute(tool("article_generate", tone="CONCISE")) == result
            await tools.execute(tool("context_read"))
            assert tools.article_continuation_candidate is None
            assert "article_generate" in {t.name for t in tools.catalog()}
            await tools.execute(tool("article_generate"))
        assert java.calls == ["context.read", "article.generate", "context.read"]

    asyncio.run(run())


def test_candidate_is_raw_data_not_a_runtime_tool_call() -> None:
    async def run() -> None:
        req = request("好的")
        java = RuntimeJava()
        with bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))):
            ids: list[str] = []
            for _ in range(2):
                counters = RuntimeUsageCounters(req, RuntimeHarness())
                tools = NativeTools(
                    req, context(), cast(JavaAgentActionClientProtocol, java), counters
                )
                await tools.execute(tool("context_read"))
                pending = tools.article_continuation_candidate
                assert pending is not None
                ids.append(pending.offer_message_id)
                assert pending.source_text == candidate()["sourceText"]
                assert not pending.authorized and pending.review_required
                assert not isinstance(pending, ModelToolCall)
                assert not java.article_arguments
            assert ids[0] == ids[1]

    asyncio.run(run())


def test_deadline_after_read_prevents_runtime_submission(monkeypatch: pytest.MonkeyPatch) -> None:
    async def run() -> None:
        java = RuntimeJava()
        gateway = RuntimeGateway((tool("context_read"),))
        original = RuntimeHarness.validate_action_deadline

        def deadline(req: Any) -> None:
            if java.calls:
                raise HarnessViolation("RUN_DEADLINE_EXCEEDED", "deadline")
            original(req)

        monkeypatch.setattr(RuntimeHarness, "validate_action_deadline", staticmethod(deadline))
        with pytest.raises(HarnessViolation) as captured:
            await run_loop(java, gateway)
        assert captured.value.code == "RUN_DEADLINE_EXCEEDED"
        assert java.calls == ["context.read"]
        assert len(gateway.requests) == 1

    asyncio.run(run())


def test_stop_during_runtime_submission_propagates_without_retry() -> None:
    async def run() -> None:
        java = RuntimeJava(cancel=True)
        gateway = RuntimeGateway((tool("context_read"),))
        with pytest.raises(asyncio.CancelledError):
            await run_loop(java, gateway)
        assert java.calls == ["context.read", "article.generate"]
        assert len(gateway.requests) == 2

    asyncio.run(run())


def test_direct_article_request_remains_model_driven() -> None:
    async def run() -> None:
        java = RuntimeJava()
        gateway = RuntimeGateway((tool("article_generate"),), allow_answer=True)
        result, counters, _ = await run_loop(java, gateway, content="直接生成一篇文章")
        assert java.calls == ["article.generate"]
        assert "article_generate" in gateway.catalogs[0]
        assert counters.model_call_count == 3
        assert result.message == "本轮仅查看已有状态。"

    asyncio.run(run())


@pytest.mark.parametrize("status", ("WAIT_INPUT", "UNAVAILABLE", "COMPLETED"))
def test_other_mutation_in_same_batch_blocks_automatic_article(status: str) -> None:
    class OtherWriteJava(RuntimeJava):
        async def execute_run_action(self, *args: Any) -> JavaInternalResponse:
            if args[2]["action"] != "memory.forget":
                return await super().execute_run_action(*args)
            self.calls.append("memory.forget")
            data: dict[str, Any] = {}
            if status == "COMPLETED":
                entry = {
                    "id": "901",
                    "scope": "PROJECT",
                    "projectId": "401",
                    "kind": "FACT",
                    "key": "brand.legacy",
                    "label": "旧资料",
                    "value": None,
                    "version": 2,
                    "deleted": True,
                    "sourceType": "USER_MESSAGE",
                    "sourceMessageId": "126",
                    "sourceConversationId": "123",
                    "updatedAt": "2026-10-10T08:00:00Z",
                }
                data = {
                    "entry": entry,
                    "memory": {
                        "schemaVersion": 1,
                        "projectId": "401",
                        "entries": [entry],
                        "revision": "1",
                        "historyCutoffAt": None,
                    },
                }
                ProjectMemoryMutation.model_validate(data)
            return JavaInternalResponse(
                200, {"action": "memory.forget", "status": status, "data": data}, "0" * 32
            )

    async def run() -> None:
        java = OtherWriteJava()
        gateway = RuntimeGateway(
            (
                tool("context_read"),
                tool("memory_forget", key="brand.legacy", expectedVersion=1, sourceQuote="好的"),
                tool("context_read"),
                tool("article_generate"),
            ),
            allow_answer=True,
        )
        await run_loop(java, gateway)
        assert java.calls == ["context.read", "memory.forget", "context.read"]
        assert not java.article_arguments
        assert "article_generate" in gateway.catalogs[1]

    asyncio.run(run())


def test_read_candidate_does_not_force_model_to_generate() -> None:
    async def run() -> None:
        java = RuntimeJava()
        gateway = RuntimeGateway((tool("context_read"),), allow_answer=True)
        result, counters, _ = await run_loop(java, gateway)
        assert java.calls == ["context.read"]
        assert counters.model_call_count == 2
        assert result.message == "本轮仅查看已有状态。"
        assert not java.article_arguments

    asyncio.run(run())


def test_model_can_recover_missing_candidate_then_choose_generation_in_same_run() -> None:
    class ReadThenGenerate(RuntimeGateway):
        async def complete_turn_stream(self, messages: Any, **kwargs: Any) -> ModelTurnResult:
            record_model_call(input_messages=messages)
            self.requests.append(messages)
            self.catalogs.append({t.name for t in kwargs["tools"]})
            assert "article_generate" in self.catalogs[-1]
            index = len(self.requests)
            if index == 2:
                assert (
                    json.loads(messages[-1].content)["reasonCode"]
                    == "ARTICLE_CONTINUATION_REQUIRED"
                )
            if index <= 3:
                alias = "context_read" if index == 2 else "article_generate"
                return ModelTurnResult(
                    ModelMessage("assistant", "", (tool(alias),)),
                    "tool_calls",
                    GatewayUsage(request_count=1),
                )
            assert index == 4
            await kwargs["on_delta"]("完整稿正在生成。")
            return ModelTurnResult(
                ModelMessage("assistant", "完整稿正在生成。"), "stop", GatewayUsage(request_count=1)
            )

    async def run() -> None:
        java, gateway = RuntimeJava(), ReadThenGenerate(())
        result, counters, _ = await run_loop(java, gateway)
        assert java.calls == ["context.read", "article.generate"]
        assert len(gateway.requests) == 4
        assert counters.model_call_count == 5  # Four decisions + one existing review.
        assert len(java.article_arguments) == 1
        assert result.business_outcome.status.value == "ANSWERED"

    asyncio.run(run())


@pytest.mark.parametrize("unknown", (False, True))
def test_same_batch_article_attempt_prevents_a_second_runtime_submission(unknown: bool) -> None:
    async def run() -> None:
        # Defensive executor path: even if an internal caller supplies both calls,
        # the batch receipt/unknown fence wins over the earlier read-time pending.
        java = RuntimeJava(article_data={} if unknown else receipt())
        gateway = RuntimeGateway((tool("context_read"), tool("article_generate")))
        result, counters, _ = await run_loop(java, gateway)
        assert java.calls == ["context.read", "article.generate"]
        assert counters.tool_call_count == 2
        assert len(gateway.requests) == 2
        assert result.business_outcome.status.value == ("PARTIAL" if unknown else "ANSWERED")

    asyncio.run(run())


def test_fresh_read_budget_failure_revokes_pending_before_any_network_request() -> None:
    async def run() -> None:
        req = request("好的")
        req = req.model_copy(update={"budget": req.budget.model_copy(update={"max_tool_calls": 1})})
        java = RuntimeJava()
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        with bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))):
            tools = NativeTools(req, context(), cast(JavaAgentActionClientProtocol, java), counters)
            await tools.execute(tool("context_read"))
            assert tools.article_continuation_candidate is not None
            with pytest.raises(HarnessViolation) as captured:
                await tools.execute(tool("context_read"))
            assert captured.value.code == "TOOL_CALL_BUDGET_EXCEEDED"
            assert tools.article_continuation_candidate is None
            assert "article_generate" in {t.name for t in tools.catalog()}
        assert java.calls == ["context.read"]

    asyncio.run(run())

"""Offline functional protocol checks, not claims about live LLM answer quality."""

import asyncio
import json
from collections.abc import Callable
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from typing import Any, cast
from uuid import uuid4

import pytest

from app.agent.brand_brief import project_brand_brief
from app.agent.identity import GEO_CAPABILITIES, bind_agent_execution, resolve_execution
from app.agent.loop import GeoAgentLoop
from app.agent.models import ChatMessage, ChatRunRequest, RunBudget, TrustedState
from app.common.settings import default_bundle_path
from app.harness.runtime import (
    HarnessViolation,
    RuntimeHarness,
    RuntimeUsageCounters,
    bind_runtime_usage,
)
from app.integration.java_internal import JavaAgentActionClientProtocol, JavaInternalResponse
from app.model_gateway.gateway import (
    GatewayUsage,
    LocalModelGateway,
    ModelMessage,
    ModelToolCall,
    ModelToolDefinition,
    ModelTurnResult,
)
from app.prompts.loader import load_bundle
from app.providers.java_context import (
    AuthorizedRunContext,
    BrandSnapshot,
    ConversationMemory,
    ConversationMemoryMessage,
    SpaceSnapshot,
)
from app.providers.knowledge_context import KnowledgeContext, ProjectMemoryContext
from app.tools.native import NativeTools
from app.tools.native_authorization import user_requested_native_actions
from tests.unit.test_article_action_review import ReviewGateway


class ProjectStore:
    """In-memory Java contract fixture; never a production fallback or real write."""

    def __init__(self, project: str = "401") -> None:
        self.project = project
        self.name: str | None = None
        self.entries: dict[str, dict[str, Any]] = {}
        self.calls: list[str] = []
        self.source = ""
        self.message_id = "124"

    def memory(self) -> ProjectMemoryContext:
        return ProjectMemoryContext.model_validate(
            {
                "schemaVersion": 1,
                "projectId": self.project,
                "entries": list(self.entries.values()),
                "revision": str(len(self.calls)),
                "historyCutoffAt": None,
            }
        )

    def response(self, payload: dict[str, Any]) -> JavaInternalResponse:
        return JavaInternalResponse(200, payload, "0" * 32)

    async def compact_run_history(self, *args: Any, **kwargs: Any) -> JavaInternalResponse:
        return self.response(
            {
                "version": 0,
                "throughSequence": 0,
                "summary": "",
                "sourceHash": "0" * 64,
                "sourceThroughSequence": 0,
                "needsCompaction": False,
                "hasMore": False,
                "messages": [],
                "recentMessages": [],
                "memory": self.memory().model_dump(mode="json", by_alias=True),
            }
        )

    async def execute_run_action(self, *args: Any) -> JavaInternalResponse:
        sent = args[2]
        action = sent["action"]
        data = sent["arguments"]
        self.calls.append(action)
        if action == "brand.save":
            assert data["name"] in self.source
            self.name = data["name"]
            result: dict[str, Any] = {
                "projectId": self.project,
                "name": self.name,
                "aliases": [],
                "sourceMessageId": self.message_id,
            }
        elif action == "memory.read":
            result = self.memory().model_dump(mode="json", by_alias=True)
        elif action == "memory.upsert":
            assert data["sourceQuote"] in self.source
            assert data["value"] in data["sourceQuote"]
            assert data["scope"] == "PROJECT"
            old = self.entries.get(data["key"])
            assert data["expectedVersion"] == (old["version"] if old else 0)
            entry = {
                "id": str(500 + len(self.entries)),
                "scope": "PROJECT",
                "projectId": self.project,
                "kind": data["kind"],
                "key": data["key"],
                "label": data["label"],
                "value": data["value"],
                "version": data["expectedVersion"] + 1,
                "deleted": False,
                "sourceType": "USER_MESSAGE",
                "sourceMessageId": self.message_id,
                "sourceConversationId": "123",
                "updatedAt": datetime.now(UTC).isoformat(),
            }
            if data.get("mode") == "APPEND":
                assert old and not old["deleted"] and data["key"] == "brand.categories"
                entry["value"] = old["value"] + "\n" + data["value"]
            self.entries[data["key"]] = entry
            result = {
                "entry": entry,
                "memory": self.memory().model_dump(mode="json", by_alias=True),
            }
        else:
            raise AssertionError(f"Unexpected business action: {action}")
        return self.response({"action": action, "status": "COMPLETED", "data": result})

    def context(self) -> AuthorizedRunContext:
        return AuthorizedRunContext(
            source="JAVA_AUTHORIZED_CONTEXT",
            user_context_ref="test-intake-context",
            selected_space_id=self.project,
            selected_product_id=None,
            attachment_asset_ids=(),
            loaded_tool_groups=("TASK_PREPARATION",),
            space=SpaceSnapshot(
                id=self.project,
                name="代理内部项目标签，不是公开品牌",
                logo_asset_id=None,
                brand=BrandSnapshot(
                    name=self.name,
                    aliases=(),
                    description=None,
                    tone=(),
                    audience=(),
                    selling_points=(),
                    prohibited_claims=(),
                ),
            )
            if self.name
            else None,
        )


Step = Callable[[dict[str, Any], set[str]], ModelMessage]


class ScriptedGateway(LocalModelGateway):
    def __init__(self, steps: list[Step]) -> None:
        self.steps = iter(steps)
        self.count = 0

    async def complete_turn(
        self,
        messages: tuple[ModelMessage, ...],
        *,
        tools: tuple[ModelToolDefinition, ...],
        max_tokens: int,
        thinking: bool = True,
        operation: str = "native_tool_loop",
    ) -> ModelTurnResult:
        self.count += 1
        assert operation == "geo_native_loop"
        data = json.loads(
            messages[0].content.split("<context_data>")[1].split("</context_data>")[0]
        )
        names = {tool.name for tool in tools}
        assert "geo_monitor" not in names
        # Natural writing keeps the tool discoverable; execution still needs semantic
        # NEW_DRAFT admission. Intake itself must not create any paid business action.
        assert "article_generate" in names
        message = next(self.steps)(data["authorizedSnapshot"], names)
        return ModelTurnResult(
            message=message,
            finish_reason="tool_calls" if message.tool_calls else "stop",
            usage=GatewayUsage(request_count=1),
        )


def tool(alias: str, **arguments: Any) -> ModelToolCall:
    return ModelToolCall(
        id=str(uuid4()), name=alias, arguments=json.dumps(arguments, ensure_ascii=False)
    )


def ask(*calls: ModelToolCall) -> ModelMessage:
    return ModelMessage(role="assistant", content="", reasoning_content="", tool_calls=calls)


def done(text: str = "根据已知资料给出优化方案，本轮没有启动监测或生成文章。") -> ModelMessage:
    return ModelMessage(role="assistant", content=text)


async def run_turn(store: ProjectStore, content: str, steps: list[Step]) -> None:
    store.source = content
    req = ChatRunRequest(
        run_id=uuid4(),
        conversation_id="123",
        user_context_ref="test-intake-context",
        message=ChatMessage(message_id=store.message_id, content=content),
        trusted_state=TrustedState(selected_space_id=store.project),
        budget=RunBudget(
            max_input_tokens=40000,
            max_model_calls=8,
            max_tool_calls=10,
            max_steps=24,
            deadline_at=datetime.now(UTC) + timedelta(minutes=1),
        ),
    )
    prompt = next(
        p.system
        for p in load_bundle(default_bundle_path()).bundle.prompts
        if p.code == "GEO_NATIVE_LOOP_V1"
    )
    counters = RuntimeUsageCounters(req, RuntimeHarness())
    gateway = ScriptedGateway(steps)
    with (
        bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
        bind_runtime_usage(counters),
    ):
        outcome = await GeoAgentLoop(
            gateway,
            cast(JavaAgentActionClientProtocol, store),
            prompt,
        ).ainvoke(req, authorized_context=store.context(), usage_counters=counters)
    assert outcome.kind == "MESSAGE"
    assert gateway.count == len(steps)


def test_progressive_intake_refreshes_identity_and_memory_without_executing_tasks() -> None:
    asyncio.run(_progressive_intake())


async def _progressive_intake() -> None:
    store = ProjectStore()

    def first(snapshot: dict[str, Any], names: set[str]) -> ModelMessage:
        assert "brand_save" in names
        assert snapshot["brandBrief"]["missingRecordedFields"] == [
            "brandIdentity",
            "offerings",
            "serviceArea",
            "targetRegions",
        ]
        return ask(tool("brand_save", name="小行星咖啡店"))

    def saved(snapshot: dict[str, Any], names: set[str]) -> ModelMessage:
        assert snapshot["currentBrandIdentity"]["name"] == "小行星咖啡店"
        assert snapshot["brandBrief"]["fields"]["brandIdentity"]["value"]["name"] == "小行星咖啡店"
        return ask(tool("memory_read"))

    await run_turn(
        store,
        "帮小行星咖啡店做GEO优化",
        [first, saved, lambda s, n: done("主要卖什么，门店在哪里？")],
    )

    content = "主营咖啡、曲奇和蛋糕；门店在广州天河，蛋糕仅周边配送"

    def save_details(snapshot: dict[str, Any], names: set[str]) -> ModelMessage:
        assert snapshot["brandBrief"]["fields"]["brandIdentity"]["value"]["name"] == store.name
        return ask(
            *[
                tool(
                    "memory_upsert",
                    scope="PROJECT",
                    kind="FACT",
                    key=key,
                    label=label,
                    value=value,
                    expectedVersion=0,
                    sourceQuote=content,
                )
                for key, label, value in (
                    ("brand.categories", "主营业务", "咖啡、曲奇和蛋糕"),
                    ("brand.service_area", "实际服务范围", "门店在广州天河，蛋糕仅周边配送"),
                )
            ]
        )

    def need_target(snapshot: dict[str, Any], names: set[str]) -> ModelMessage:
        assert snapshot["brandBrief"]["missingRecordedFields"] == ["targetRegions"]
        return done("希望在哪些地域相关的需求中被找到？")

    await run_turn(store, content, [save_details, need_target])
    more = "还卖手工饼干"
    await run_turn(
        store,
        more,
        [
            lambda s, n: ask(
                tool(
                    "memory_upsert",
                    scope="PROJECT",
                    kind="FACT",
                    key="brand.categories",
                    label="主营业务",
                    value="手工饼干",
                    expectedVersion=1,
                    sourceQuote=more,
                    mode="APPEND",
                )
            ),
            need_target,
        ],
    )
    assert store.entries["brand.categories"]["value"] == "咖啡、曲奇和蛋糕\n手工饼干"
    target = "长期希望覆盖广州天河"

    def plan(snapshot: dict[str, Any], names: set[str]) -> ModelMessage:
        assert snapshot["brandBrief"]["missingRecordedFields"] == []
        assert snapshot["brandBrief"]["fields"]["targetRegions"]["value"] == "广州天河"
        assert snapshot["brandBrief"]["fields"]["serviceArea"]["value"] != "广州天河"
        assert snapshot["brandBrief"]["fields"]["targetQueries"]["status"] == "NOT_RECORDED"
        return done()

    await run_turn(
        store,
        target,
        [
            lambda s, n: ask(
                tool(
                    "memory_upsert",
                    scope="PROJECT",
                    kind="FACT",
                    key="geo.target_regions",
                    label="目标曝光地域",
                    value="广州天河",
                    expectedVersion=0,
                    sourceQuote=target,
                )
            ),
            plan,
        ],
    )
    # A fresh run reads the same project memory; a one-off region remains only USER input.
    await run_turn(store, "接着帮我规划", [plan])
    before = dict(store.entries)
    await run_turn(store, "这次活动主要面向广东福建，给我方案", [plan])
    assert store.entries == before
    assert set(store.calls) <= {"brand.save", "memory.read", "memory.upsert"}
    other = ProjectStore("402")
    await run_turn(
        other,
        "给我一个起步建议",
        [
            lambda s, n: (
                done()
                if len(s["brandBrief"]["missingRecordedFields"]) == 4
                else pytest.fail("跨项目泄漏")
            )
        ],
    )


@pytest.mark.parametrize(
    "content", ["广东福建", "就这样", "继续", "主营咖啡和蛋糕，门店在广州", "长期覆盖广东"]
)
def test_intake_answers_never_authorize_tasks(content: str) -> None:
    assert not {"geo.monitor", "article.generate"} & user_requested_native_actions(content)


@pytest.mark.parametrize(
    "content,decision",
    [
        ("帮小行星咖啡店做GEO优化", "DISCUSSION"),
        ("主营咖啡和蛋糕，门店在广州", "DISCUSSION"),
        ("长期覆盖广东", "UNCLEAR"),
    ],
)
def test_incomplete_intake_cannot_submit_article_even_if_model_proposes_tool(
    content: str, decision: str
) -> None:
    async def run() -> None:
        store = ProjectStore()
        assert (
            len(project_brand_brief(store.context(), store.memory())["missingRecordedFields"]) == 4
        )
        req = ChatRunRequest(
            run_id=uuid4(),
            conversation_id="123",
            user_context_ref="test-intake-context",
            message=ChatMessage(message_id=store.message_id, content=content),
            trusted_state=TrustedState(selected_space_id=store.project),
            budget=RunBudget(deadline_at=datetime.now(UTC) + timedelta(minutes=1)),
        )
        gateway = ReviewGateway(decision)
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        with bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))):
            tools = NativeTools(
                req,
                store.context(),
                cast(JavaAgentActionClientProtocol, store),
                counters,
                gateway=gateway,
                article_review_prompt="read-only writing intent review",
            )
            assert "article_generate" in {item.name for item in tools.catalog()}
            result = json.loads(await tools.execute(tool("article_generate")))
            await tools.execute(tool("article_generate", tone="CONCISE"))
        assert result["reasonCode"] == "ARTICLE_ACTION_" + decision
        assert len(gateway.review_messages) == 1
        assert store.calls == []
        assert tools.last_article_receipt is None

    asyncio.run(run())


@pytest.mark.parametrize(
    "question,answer,allowed",
    [
        ("你的品牌名称叫什么？", "小行星咖啡店", True),
        ("请告诉我准确品牌名。", "小行星", True),
        (None, "小行星咖啡店", False),
        ("希望在哪个地域曝光？", "广东福建", False),
        ("你要监测哪个品牌？", "小行星咖啡店", False),
        ("竞品的品牌名称叫什么？", "小行星咖啡店", False),
        ("你的品牌名称叫什么？", "不知道", False),
        ("你的品牌名称叫什么？", "继续", False),
    ],
)
def test_brand_name_short_reply_only_opens_brand_save(
    question: str | None, answer: str, allowed: bool
) -> None:
    actions = user_requested_native_actions(answer, brand_name_question=question)
    assert actions == (frozenset({"brand.save"}) if allowed else frozenset())


def test_deleted_fact_is_not_restored_from_other_brand_material() -> None:
    store = ProjectStore()
    store.name = "小行星咖啡店"
    store.entries["brand.categories"] = {
        "id": "501",
        "scope": "PROJECT",
        "projectId": "401",
        "kind": "FACT",
        "key": "brand.categories",
        "label": "主营业务",
        "value": None,
        "version": 2,
        "deleted": True,
        "sourceType": "USER_MESSAGE",
        "sourceMessageId": "124",
        "sourceConversationId": "123",
        "updatedAt": datetime.now(UTC).isoformat(),
    }
    brief = project_brand_brief(store.context(), store.memory())
    assert brief["fields"]["offerings"]["status"] == "DELETED"
    assert brief["fields"]["offerings"]["value"] is None
    assert brief["fields"]["brandIdentity"]["value"]["name"] != store.context().space.name  # type: ignore[union-attr]


def test_memory_tool_rejects_invented_normalization_without_java_write() -> None:
    asyncio.run(_reject_invented_normalization())


async def _reject_invented_normalization() -> None:
    store = ProjectStore()
    req = ChatRunRequest(
        run_id=uuid4(),
        conversation_id="123",
        user_context_ref="test-context",
        message=ChatMessage(message_id="124", content="广州天河"),
        trusted_state=TrustedState(),
        budget=RunBudget(deadline_at=datetime.now(UTC) + timedelta(minutes=1)),
    )
    counters = RuntimeUsageCounters(req, RuntimeHarness())
    with bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))):
        tools = NativeTools(
            req, store.context(), cast(JavaAgentActionClientProtocol, store), counters
        )
        result = json.loads(
            await tools.execute(
                tool(
                    "memory_upsert",
                    scope="PROJECT",
                    kind="FACT",
                    key="brand.service_area",
                    label="实际服务范围",
                    value="广东福建均可配送",
                    expectedVersion=0,
                    sourceQuote="广州天河",
                )
            )
        )
    assert result["reasonCode"] == "TOOL_INPUT_INVALID"
    assert store.calls == []


def test_short_brand_answer_uses_only_immediate_authorized_assistant_question() -> None:
    asyncio.run(_short_brand_answer())


async def _short_brand_answer() -> None:
    store = ProjectStore()
    req = ChatRunRequest(
        run_id=uuid4(),
        conversation_id="123",
        user_context_ref="test-context",
        message=ChatMessage(message_id="124", content="小行星咖啡店"),
        trusted_state=TrustedState(),
        budget=RunBudget(deadline_at=datetime.now(UTC) + timedelta(minutes=1)),
    )
    question = ConversationMemoryMessage(
        message_id="122",
        sequence=1,
        role="ASSISTANT",
        content={"text": "你的品牌名称叫什么？"},
        created_at=datetime.now(UTC),
        reply_to_message_id=None,
    )
    current = ConversationMemoryMessage(
        message_id="124",
        sequence=2,
        role="USER",
        content={"text": req.message.content},
        created_at=datetime.now(UTC),
        reply_to_message_id="122",
    )
    memory = ConversationMemory(
        messages=(question, current),
        summary=None,
        last_message_sequence=2,
        summary_through_sequence=0,
        context_source="POSTGRESQL",
    )
    with bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))):
        for ctx, allowed in (
            (replace(store.context(), conversation_memory=memory), True),
            (store.context(), False),
        ):
            tools = NativeTools(
                req,
                ctx,
                cast(JavaAgentActionClientProtocol, store),
                RuntimeUsageCounters(req, RuntimeHarness()),
            )
            catalog = {item.name for item in tools.catalog()}
            assert ("brand_save" in catalog) is allowed
            assert "geo_monitor" not in catalog
            assert "article_generate" in catalog


def test_cross_project_memory_is_rejected_before_model_or_tool_observation() -> None:
    asyncio.run(_cross_project_memory())


async def _cross_project_memory() -> None:
    store = ProjectStore("401")
    other = ProjectStore("402")
    req = ChatRunRequest(
        run_id=uuid4(),
        conversation_id="123",
        user_context_ref="test-context",
        message=ChatMessage(message_id="124", content="我有什么资料"),
        trusted_state=TrustedState(),
        budget=RunBudget(deadline_at=datetime.now(UTC) + timedelta(minutes=1)),
    )
    gateway = ScriptedGateway([])
    loop = GeoAgentLoop(gateway, cast(JavaAgentActionClientProtocol, other), "test")
    with pytest.raises(HarnessViolation, match="current project") as caught:
        await loop.ainvoke(
            req,
            authorized_context=store.context(),
            usage_counters=RuntimeUsageCounters(req, RuntimeHarness()),
        )
    assert caught.value.code == "MEMORY_PROJECT_MISMATCH"
    assert gateway.count == 0
    with bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))):
        tools = NativeTools(
            req,
            store.context(),
            cast(JavaAgentActionClientProtocol, other),
            RuntimeUsageCounters(req, RuntimeHarness()),
        )
        result = json.loads(await tools.execute(tool("memory_read")))
    assert result["status"] == "ERROR"
    assert tools.memory_context is None
    assert "402" not in json.dumps(result)


def test_projectless_account_memory_remains_valid_then_brand_receipt_binds_project() -> None:
    asyncio.run(_projectless_brand_binding())


async def _projectless_brand_binding() -> None:
    class ProjectlessStore(ProjectStore):
        def memory(self) -> ProjectMemoryContext:
            if self.name is not None:
                return super().memory()
            return ProjectMemoryContext.model_validate(
                {
                    "schemaVersion": 1,
                    "projectId": None,
                    "revision": "initial",
                    "historyCutoffAt": None,
                    "entries": [
                        {
                            "id": "501",
                            "scope": "ACCOUNT",
                            "projectId": None,
                            "kind": "PREFERENCE",
                            "key": "style.tone",
                            "label": "表达偏好",
                            "value": "所有项目简短回答",
                            "version": 1,
                            "deleted": False,
                            "sourceType": "USER_MESSAGE",
                            "sourceMessageId": "120",
                            "sourceConversationId": "123",
                            "updatedAt": datetime.now(UTC).isoformat(),
                        }
                    ],
                }
            )

        def context(self) -> AuthorizedRunContext:
            return replace(
                super().context(),
                selected_space_id=None,
                space=None,
                knowledge_context=KnowledgeContext(
                    documents=(),
                    preferences=(),
                    business_history=None,
                    truncated=False,
                    source_count=0,
                    returned_characters=0,
                    memory=self.memory(),
                ),
            )

    store = ProjectlessStore()

    def initial(snapshot: dict[str, Any], names: set[str]) -> ModelMessage:
        assert snapshot["currentMemory"]["projectId"] is None
        assert snapshot["currentMemory"]["entries"][0]["scope"] == "ACCOUNT"
        return ask(tool("brand_save", name="小行星咖啡店"))

    def bound(snapshot: dict[str, Any], names: set[str]) -> ModelMessage:
        assert snapshot["currentBrandIdentity"]["projectId"] == "401"
        assert snapshot["brandBrief"]["fields"]["offerings"]["status"] == "NOT_LOADED"
        assert "currentMemory" not in snapshot
        return ask(tool("memory_read"))

    def ready(snapshot: dict[str, Any], names: set[str]) -> ModelMessage:
        assert snapshot["currentMemory"]["projectId"] == "401"
        assert snapshot["brandBrief"]["fields"]["brandIdentity"]["value"]["name"] == store.name
        return done("主要卖什么，门店在哪里？")

    await run_turn(store, "我的咖啡店叫小行星咖啡店", [initial, bound, ready])
    assert store.calls == ["brand.save", "memory.read"]

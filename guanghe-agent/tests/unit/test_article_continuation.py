"""Scoped article acceptance through the real executor/loop, without paid calls."""

import asyncio
import copy
import json
from dataclasses import replace
from pathlib import Path
from typing import Any, cast

import pytest
from pydantic import ValidationError

from app.agent.identity import (
    CREATIVE_CAPABILITIES,
    GEO_CAPABILITIES,
    AgentExecution,
    bind_agent_execution,
    resolve_execution,
)
from app.agent.loop import GeoAgentLoop
from app.agents.catalog import parse_catalog
from app.harness.runtime import RuntimeHarness, RuntimeUsageCounters, bind_runtime_usage
from app.integration.java_internal import JavaAgentActionClientProtocol, JavaInternalResponse
from app.model_gateway.gateway import (
    GatewayUsage,
    LocalModelGateway,
    ModelMessage,
    ModelToolDefinition,
    ModelTurnResult,
)
from app.providers.java_context import AgentIdentity, AuthorizedRunContext
from app.tools.native import NativeTools
from app.tools.native_authorization import (
    is_article_offer_confirmation,
    user_requested_native_actions,
)
from tests.unit.test_monitor_continuation import JavaRecorder, context, request, tool


def candidate(**changes: Any) -> dict[str, Any]:
    return {
        "authorized": False,
        "reviewRequired": True,
        "action": "article.generate",
        "currentMessageId": "126",
        "conversationId": "123",
        "projectId": "401",
        "sourceMessageId": "124",
        "offerMessageId": "125",
        "requestText": "用户需求: 提升广州品牌可见度。已接受提议: 先写一篇品牌介绍文章。",
        "sourceText": "提升广州品牌可见度。",
        "offerText": "我可以把这些修改整合成一整版新稿，保留四段结构。",
        "submissionState": "NOT_ATTEMPTED",
        **changes,
    }


def receipt() -> dict[str, Any]:
    return {
        "id": "501",
        "templateCode": "ARTICLE_FULL_V1",
        "status": "RUNNING",
        "conversationId": "123",
        "articleId": None,
        "articleVersion": None,
        "quotedPoints": 8,
        "settledPoints": 0,
        "releasedPoints": 0,
        "deadlineAt": "2026-10-10T10:00:00Z",
        "errorCode": None,
        "stages": [],
        "publicationRequiresConfirmation": True,
        "knowledgeSourcesCurrent": True,
    }


class ArticleJavaRecorder(JavaRecorder):
    def __init__(
        self,
        data: dict[str, Any],
        *,
        unknown_article: bool = False,
        context_status: str = "COMPLETED",
    ) -> None:
        super().__init__(data)
        self.unknown_article = unknown_article
        self.context_status = context_status
        self.article_arguments: list[dict[str, Any]] = []

    async def execute_run_action(self, *args: Any) -> JavaInternalResponse:
        action = args[2]["action"]
        self.calls.append(action)
        if action == "context.read":
            data, status = copy.deepcopy(self.data), self.context_status
        elif action == "article.generate":
            self.article_arguments.append(args[2]["arguments"])
            data, status = None if self.unknown_article else receipt(), "COMPLETED"
        else:
            raise AssertionError(f"Unexpected action {action}")
        return JavaInternalResponse(
            200, {"action": action, "status": status, "data": data}, "0" * 32
        )


async def denied(
    data: dict[str, Any],
    *,
    content: str = "好",
    ctx: AuthorizedRunContext | None = None,
    capability: bool = True,
    context_status: str = "COMPLETED",
    keep_projection: bool = False,
) -> None:
    req = request(content)
    counters = RuntimeUsageCounters(req, RuntimeHarness())
    java = ArticleJavaRecorder(data, context_status=context_status)
    capabilities = GEO_CAPABILITIES if capability else GEO_CAPABILITIES - {"article.generate"}
    with (
        bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(capabilities))),
        bind_runtime_usage(counters),
    ):
        tools = NativeTools(
            req, ctx or context(), cast(JavaAgentActionClientProtocol, java), counters
        )
        initial_catalog = {item.name for item in tools.catalog()}
        assert ("article_generate" in initial_catalog) is capability
        observed = json.loads(await tools.execute(tool("context_read")))
        assert {item.name for item in tools.catalog()} == initial_catalog
        assert ("articleContinuation" in observed.get("data", {})) is keep_projection
        assert json.loads(await tools.execute(tool("article_generate")))["reasonCode"] == (
            "TOOL_NOT_AUTHORIZED"
            if not capability
            else "ARTICLE_CONTINUATION_REQUIRED"
            if is_article_offer_confirmation(content)
            else "ARTICLE_ACTION_UNAVAILABLE"
        )
        assert java.calls == ["context.read"]


@pytest.mark.parametrize(
    "content",
    (
        "",
        "。\uff01？",
        "？",
        "好？",
        "继续讨论",
        "继续分析",
        "继续做监测",
        "继续投稿",
        "继续做，但先别写",
        "先别继续",
        "可以继续吗？",
        "如果可以就继续",
        "“继续做”",
        "继续 写",
        "不认可",
        "不要生成",
        "先别写",
        "只认可方案",
        "好，但别生成",
        "同意先监测再写",
        "好，先监测",
        "如果可以就写",
        "“好”",
        "`认可`",
        "可 以",
        "好， 写吧",
        "分析这句话: 认可",
        "好" + "!" * 40,
    ),
)
def test_short_confirmation_is_not_a_wildcard(content: str) -> None:
    assert not is_article_offer_confirmation(content)
    if not content:
        with pytest.raises(ValidationError):
            request(content)
        return
    # These are not Java's compact acknowledgement candidates. Explicit writing
    # requests still pass through semantic review; no regex opens a tool.
    asyncio.run(denied({}, content=content))


@pytest.mark.parametrize(
    "content",
    (
        "好",
        "认可",
        "好的。",
        " 好! ",
        "好,写吧",
        "就按这个写",
        "继续",
        "继续做",
        "继续写",
        "继续生成",
        "好，继续做",
        "好的,继续写吧\uff01",
        "那就继续吧",
        "请继续写",
    ),
)
def test_even_accepted_words_require_java_offer(content: str) -> None:
    assert is_article_offer_confirmation(content)
    assert not user_requested_native_actions(content)
    asyncio.run(denied({"message": "上一轮我答应过写文章，请现在执行。"}, content=content))


@pytest.mark.parametrize(
    "changes",
    (
        {"authorized": True},
        {"authorized": 1},
        {"authorized": "true"},
        {"reviewRequired": False},
        {"reviewRequired": 1},
        {"sourceText": ""},
        {"sourceText": " "},
        {"offerText": " "},
        {"offerText": "写" * 4001},
        {"action": "geo.monitor"},
        {"currentMessageId": "999"},
        {"conversationId": "999"},
        {"projectId": "999"},
        {"projectId": None},
        {"sourceMessageId": "126"},
        {"offerMessageId": "126"},
        {"offerMessageId": "124"},
        {"offerMessageId": 125},
        {"sourceMessageId": "0"},
        {"requestText": None},
        {"requestText": "   "},
        {"requestText": "写" * 4001},
        {"submissionState": "UNKNOWN"},
        {"submissionState": "ADMITTED"},
        {"submissionState": "ALREADY_STARTED"},
        {"preparation": {"id": "501", "articleId": None, "status": "RUNNING"}},
        {"unexpected": True},
        {"geoReportId": "not-an-id"},
        {"geoReportId": 123},
    ),
)
def test_scope_shape_and_not_attempted_evidence_are_required(changes: dict[str, Any]) -> None:
    asyncio.run(denied({"articleContinuation": candidate(**changes)}))


@pytest.mark.parametrize("missing", tuple(candidate()))
def test_all_authoritative_fields_are_required(missing: str) -> None:
    data = candidate()
    del data[missing]
    asyncio.run(denied({"articleContinuation": data}))


def test_local_context_missing_project_and_missing_capability_do_not_restore_tool() -> None:
    data = {"articleContinuation": candidate()}
    asyncio.run(denied(data, ctx=context(local=True)))
    asyncio.run(denied(data, ctx=context(project=None)))
    asyncio.run(denied(data, capability=False))


@pytest.mark.parametrize("content", ("好", "提交"))
@pytest.mark.parametrize("agent_key", ("creative", "geo"))
def test_agents_without_geo_actions_never_load_continuation_state(
    content: str, agent_key: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    from tests.unit.test_monitor_continuation import candidate as monitor_candidate

    def forbidden_state(*args: Any, **kwargs: Any) -> None:
        raise AssertionError("An unrelated agent must not load GEO continuation state")

    monkeypatch.setattr("app.agents.geo_continuation.GeoContinuations", forbidden_state)

    async def run() -> None:
        req = request(content)
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        java = ArticleJavaRecorder(
            {"articleContinuation": candidate(), "monitorContinuation": monitor_candidate()}
        )
        capabilities = (
            CREATIVE_CAPABILITIES
            if agent_key == "creative"
            else GEO_CAPABILITIES - {"article.generate", "geo.monitor"}
        )
        with (
            bind_agent_execution(resolve_execution(agent_key, "1.0.0", tuple(capabilities))),
            bind_runtime_usage(counters),
        ):
            tools = NativeTools(req, context(), cast(JavaAgentActionClientProtocol, java), counters)
            observed = json.loads(await tools.execute(tool("context_read")))
            assert observed["status"] == "COMPLETED"
            assert not {"articleContinuation", "monitorContinuation"} & observed["data"].keys()
            assert not {"article_generate", "geo_monitor"} & {t.name for t in tools.catalog()}
            assert tools.article_continuation_candidate is None
            assert not tools.article_continuation_attempted
            for alias in ("article_generate", "geo_monitor"):
                result = json.loads(await tools.execute(tool(alias)))
                assert result["reasonCode"] == "TOOL_NOT_AUTHORIZED"
            assert java.calls == ["context.read"]
            assert not tools.has_task_receipt

    asyncio.run(run())


def _plain_execution(
    context_policy: str = "plain", continuation_policy: str = "none"
) -> AgentExecution:
    raw = json.loads((Path(__file__).parents[2] / "app/agents/runtime-catalog.json").read_bytes())
    for registration in raw["agents"]:
        if registration["agentKey"] == "geo":
            registration["contextPolicy"] = context_policy
            registration["continuationPolicy"] = continuation_policy
    catalog = parse_catalog(json.dumps(raw).encode())
    return resolve_execution(
        "geo",
        "1.0.0",
        tuple(GEO_CAPABILITIES),
        catalog=catalog,
        execution_release=catalog.agents[("geo", "1.0.0")].execution_release,
        catalog_hash=catalog.catalog_hash,
    )


@pytest.mark.parametrize(
    ("content", "context_policy", "continuation_policy"),
    (
        ("好", "plain", "none"),
        ("提交", "plain", "none"),
        ("好", "geo-brand", "none"),
        ("提交", "plain", "geo-article"),
    ),
)
def test_only_configured_continuation_policy_accepts_candidate_even_with_capabilities(
    content: str, context_policy: str, continuation_policy: str
) -> None:
    from tests.unit.test_article_action_review import ReviewGateway
    from tests.unit.test_monitor_continuation import candidate as monitor_candidate

    async def run() -> None:
        req = request(content)
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        java = ArticleJavaRecorder(
            {"articleContinuation": candidate(), "monitorContinuation": monitor_candidate()}
        )
        with (
            bind_agent_execution(_plain_execution(context_policy, continuation_policy)),
            bind_runtime_usage(counters),
        ):
            tools = NativeTools(
                req,
                context(),
                cast(JavaAgentActionClientProtocol, java),
                counters,
                gateway=ReviewGateway("DISCUSSION"),
                article_review_prompt="review",
            )
            observed = json.loads(await tools.execute(tool("context_read")))
            assert "monitorContinuation" not in observed["data"]
            assert ("articleContinuation" in observed["data"]) is (
                continuation_policy == "geo-article"
            )
            assert (tools.article_continuation_candidate is not None) is (
                continuation_policy == "geo-article"
            )
            for alias in ("article_generate", "geo_monitor"):
                result = json.loads(await tools.execute(tool(alias)))
                assert result["reasonCode"] in {
                    "TOOL_NOT_AUTHORIZED",
                    "ARTICLE_ACTION_DISCUSSION",
                    "ARTICLE_CONTINUATION_REQUIRED",
                }
            assert java.calls == ["context.read"]
            assert not tools.has_task_receipt

    asyncio.run(run())


@pytest.mark.parametrize("identity_key", (None, "geo", "unknown"))
def test_unbound_legacy_continuation_only_accepts_absent_identity(identity_key: str | None) -> None:
    async def run() -> None:
        req, ctx = request("好"), context()
        if identity_key is not None:
            ctx = replace(
                ctx,
                agent_identity=AgentIdentity(
                    installation_id="701",
                    agent_key=identity_key,
                    agent_version="1.0.0",
                    name="Fixture",
                    project_id="401",
                    capabilities=tuple(GEO_CAPABILITIES),
                ),
            )
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        java = ArticleJavaRecorder({"articleContinuation": candidate()})
        with bind_runtime_usage(counters):
            tools = NativeTools(req, ctx, cast(JavaAgentActionClientProtocol, java), counters)
            observed = json.loads(await tools.execute(tool("context_read")))
            assert ("articleContinuation" in observed["data"]) is (identity_key is None)
            assert (tools.article_continuation_candidate is not None) is (identity_key is None)
            assert java.calls == ["context.read"]
            assert not tools.has_task_receipt

    asyncio.run(run())


def test_plain_none_policy_keeps_explicit_article_semantic_admission() -> None:
    from tests.unit.test_article_action_review import ReviewGateway

    async def run() -> None:
        req = request("把上面扩成完整稿")
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        java, gateway = ArticleJavaRecorder({}), ReviewGateway("NEW_DRAFT")
        with bind_agent_execution(_plain_execution()), bind_runtime_usage(counters):
            tools = NativeTools(
                req,
                context(),
                cast(JavaAgentActionClientProtocol, java),
                counters,
                gateway=gateway,
                article_review_prompt="review",
            )
            result = json.loads(await tools.execute(tool("article_generate")))
            assert result["status"] == "COMPLETED"
            assert java.calls == ["article.generate"]
            assert len(gateway.review_messages) == 1
            assert tools.has_task_receipt
            assert not tools.article_continuation_attempted

    asyncio.run(run())


def test_invalid_memory_makes_entire_context_unusable() -> None:
    asyncio.run(
        denied(
            {
                "articleContinuation": candidate(),
                "knowledge": {"memory": {"projectId": "999"}},
            }
        )
    )


@pytest.mark.parametrize("status", ("WAIT_INPUT", "UNAVAILABLE"))
def test_uncompleted_read_cannot_supply_authorization(status: str) -> None:
    asyncio.run(denied({"articleContinuation": candidate()}, context_status=status))


@pytest.mark.parametrize("status", ("RUNNING", "SUCCEEDED", "FAILED"))
def test_consumed_offer_only_exposes_existing_preparation(status: str) -> None:
    asyncio.run(
        denied(
            {
                "articleContinuation": candidate(
                    authorized=False,
                    reviewRequired=False,
                    submissionState="ALREADY_STARTED",
                    preparation={
                        "id": "501",
                        "articleId": "502" if status == "SUCCEEDED" else None,
                        "status": status,
                    },
                )
            },
            keep_projection=True,
        )
    )


def test_successful_consumed_offer_must_have_real_article_reference() -> None:
    asyncio.run(
        denied(
            {
                "articleContinuation": candidate(
                    authorized=False,
                    reviewRequired=False,
                    submissionState="ALREADY_STARTED",
                    preparation={"id": "501", "articleId": None, "status": "SUCCEEDED"},
                )
            }
        )
    )


@pytest.mark.parametrize("next_state", ("CONSUMED", "INVALID", "UNAVAILABLE", "BAD_MEMORY"))
def test_new_authority_read_revokes_previous_continuation(next_state: str) -> None:
    asyncio.run(_revoke_previous_continuation(next_state))


async def _revoke_previous_continuation(next_state: str) -> None:
    req = request("好")
    counters = RuntimeUsageCounters(req, RuntimeHarness())
    java = ArticleJavaRecorder({"articleContinuation": candidate()})
    with (
        bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
        bind_runtime_usage(counters),
    ):
        tools = NativeTools(req, context(), cast(JavaAgentActionClientProtocol, java), counters)
        await tools.execute(tool("context_read"))
        assert "article_generate" in {item.name for item in tools.catalog()}
        assert tools.article_continuation_candidate is not None
        if next_state == "CONSUMED":
            java.data = {
                "articleContinuation": candidate(
                    authorized=False,
                    reviewRequired=False,
                    submissionState="ALREADY_STARTED",
                    preparation={"id": "501", "articleId": "502", "status": "SUCCEEDED"},
                )
            }
        elif next_state == "UNAVAILABLE":
            java.context_status = "UNAVAILABLE"
        elif next_state == "BAD_MEMORY":
            java.data["knowledge"] = {"memory": {"projectId": "999"}}
        else:
            java.data = {"articleContinuation": candidate(projectId="999")}
        await tools.execute(tool("context_read"))
        assert "article_generate" in {item.name for item in tools.catalog()}
        assert tools.article_continuation_candidate is None
        denied_result = json.loads(await tools.execute(tool("article_generate")))
        assert denied_result["reasonCode"] == "ARTICLE_CONTINUATION_REQUIRED"
        assert java.calls == ["context.read", "context.read"]


def test_context_read_does_not_revoke_direct_current_user_writing_command() -> None:
    asyncio.run(_direct_command())


async def _direct_command() -> None:
    from tests.unit.test_article_action_review import ReviewGateway

    req = request("按你刚才的方案生成一篇文章")
    counters = RuntimeUsageCounters(req, RuntimeHarness())
    java = ArticleJavaRecorder({})
    with (
        bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
        bind_runtime_usage(counters),
    ):
        tools = NativeTools(
            req,
            context(),
            cast(JavaAgentActionClientProtocol, java),
            counters,
            gateway=ReviewGateway("NEW_DRAFT"),
            article_review_prompt="review",
        )
        assert "article_generate" in {item.name for item in tools.catalog()}
        await tools.execute(tool("context_read"))
        assert "article_generate" in {item.name for item in tools.catalog()}
        await tools.execute(tool("article_generate"))
        assert java.calls == ["context.read", "article.generate"]


@pytest.mark.parametrize("unknown_article", (False, True))
@pytest.mark.parametrize("identity_project", (False, True))
def test_authorized_offer_preserves_receipt_unknown_and_no_monitor_fences(
    unknown_article: bool,
    identity_project: bool,
) -> None:
    asyncio.run(_receipt_fences(unknown_article, identity_project))


async def _receipt_fences(unknown_article: bool, identity_project: bool) -> None:
    from tests.unit.test_article_action_review import ReviewGateway

    req = request("认可")
    counters = RuntimeUsageCounters(req, RuntimeHarness())
    java = ArticleJavaRecorder(
        {"articleContinuation": candidate(geoReportId="601")},
        unknown_article=unknown_article,
    )
    ctx = context()
    if identity_project:
        ctx = replace(
            context(project=None),
            agent_identity=AgentIdentity(
                installation_id="701",
                agent_key="geo",
                agent_version="1.0.0",
                name="GEO",
                project_id="401",
                capabilities=tuple(GEO_CAPABILITIES),
            ),
        )
    with (
        bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
        bind_runtime_usage(counters),
    ):
        tools = NativeTools(
            req,
            ctx,
            cast(JavaAgentActionClientProtocol, java),
            counters,
            gateway=ReviewGateway(),
            article_review_prompt="review",
        )
        assert "article_generate" in {item.name for item in tools.catalog()}
        observed = json.loads(await tools.execute(tool("context_read")))
        assert observed["data"]["articleContinuation"]["authorized"] is False
        assert "article_generate" in {item.name for item in tools.catalog()}
        assert not {"geo_monitor", "brand_save"} & {item.name for item in tools.catalog()}
        first_call = tool("article_generate")
        first = json.loads(await tools.execute(first_call))
        assert json.loads(await tools.execute(first_call)) == first
        assert json.loads(await tools.execute(tool("article_generate", tone="CONCISE"))) == first
        assert java.calls == ["context.read", "article.generate"]
        assert java.article_arguments == [
            {
                "continuationReview": {
                    "currentMessageId": "126",
                    "sourceMessageId": "124",
                    "offerMessageId": "125",
                    "decision": "NEW_DRAFT",
                }
            }
        ]  # Internal executor proof, never model arguments.
        assert tools.has_unknown_write is unknown_article
        assert tools.has_task_receipt is not unknown_article
        assert tools.article_continuation_candidate is None
        assert tools.article_continuation_attempted


class ArticleCatalogGateway(LocalModelGateway):
    def __init__(self) -> None:
        self.turn = 0

    async def complete_turn(
        self,
        messages: tuple[ModelMessage, ...],
        *,
        tools: tuple[ModelToolDefinition, ...],
        max_tokens: int,
        thinking: bool = True,
        operation: str = "native_tool_loop",
    ) -> ModelTurnResult:
        self.turn += 1
        names = {item.name for item in tools}
        assert "geo_monitor" not in names
        assert "article_generate" in names
        calls = (
            (tool("context_read"),)
            if self.turn == 1
            else ((tool("article_generate"),) if self.turn == 2 else ())
        )
        return ModelTurnResult(
            message=ModelMessage(
                role="assistant",
                content="文章已受理，正在生成。" if not calls else "",
                reasoning_content="",
                tool_calls=calls,
            ),
            finish_reason="tool_calls" if calls else "stop",
            usage=GatewayUsage(request_count=1),
        )

    async def complete_structured(self, messages: Any, response_model: Any, **kwargs: Any) -> Any:
        from app.agent.article_action_review import ArticleActionReview

        return ArticleActionReview(decision="NEW_DRAFT"), GatewayUsage(request_count=1)


@pytest.mark.parametrize("content", ("好", "继续", "继续做", "继续写", "好，继续做"))
def test_real_loop_executes_authorized_offer_and_returns_durable_article_receipt(
    content: str,
) -> None:
    asyncio.run(_refresh_catalog(content))


async def _refresh_catalog(content: str) -> None:
    req = request(content)
    counters = RuntimeUsageCounters(req, RuntimeHarness())
    java = ArticleJavaRecorder({"articleContinuation": candidate()})
    gateway = ArticleCatalogGateway()
    with (
        bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
        bind_runtime_usage(counters),
    ):
        outcome = await GeoAgentLoop(
            gateway,
            cast(JavaAgentActionClientProtocol, java),
            "test",
            article_review_prompt="review",
        ).ainvoke(
            req,
            authorized_context=context(),
            usage_counters=counters,
        )
    assert outcome.kind == "MESSAGE"
    assert gateway.turn == 3
    assert java.calls == ["context.read", "article.generate"]
    assert java.article_arguments[0]["continuationReview"]["decision"] == "NEW_DRAFT"
    assert "文章已受理" in outcome.message


@pytest.mark.parametrize(
    "content",
    (
        "按你刚才的方案生成一篇文章",
        "按照你刚才的方案生成一篇文章",
        "按这份报告写一篇文章",
    ),
)
def test_explicit_based_on_proposal_request_only_opens_article(content: str) -> None:
    assert user_requested_native_actions(content) == {"article.generate"}


@pytest.mark.parametrize(
    "content",
    (
        "不要按你刚才的方案生成一篇文章",
        "如何按你刚才的方案生成一篇文章",
        "如果可以按方案生成一篇文章",
        "解释这句话: \u2018按你刚才的方案生成一篇文章\u2019",
        "`按你刚才的方案生成一篇文章`",
        "帮我做GEO优化",
    ),
)
def test_based_on_proposal_does_not_bypass_negation_consultation_or_quotes(content: str) -> None:
    assert "article.generate" not in user_requested_native_actions(content)

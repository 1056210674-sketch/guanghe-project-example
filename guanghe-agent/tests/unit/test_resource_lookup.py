"""Real graph/client paths with adversarial server references and multi-turn projections."""

import asyncio
import copy
import json
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from typing import Any, TypeVar, cast
from uuid import UUID

import httpx
import pytest
from jsonschema import Draft202012Validator
from pydantic import BaseModel, SecretStr, ValidationError

from app.agent.geo_readonly import GeoReadOnlyRuntime
from app.agent.graphs.commerce import CommerceAssistantGraph
from app.agent.models import ResourceSelectionInteraction
from app.agent.resources import (
    ResourceLookupRuntime,
    report_article_discussion_requested,
    should_lookup_resources,
)
from app.common.settings import AppSettings, default_bundle_path
from app.harness.runtime import (
    HarnessViolation,
    RuntimeHarness,
    bind_runtime_usage,
    record_model_call,
)
from app.integration.java_internal import JavaInternalClient, JavaInternalResponse
from app.model_gateway.gateway import (
    GatewayUsage,
    LocalModelGateway,
    ModelGatewayError,
    ModelMessages,
)
from app.prompts.assembly import CommercePromptSet
from app.prompts.loader import load_bundle
from app.providers.java_context import (
    AgentIdentity,
    AuthorizedRunContext,
    ConversationMemory,
    GeoInsightContext,
    ProtocolFeatures,
    TaskWorkspace,
)
from app.providers.resources import (
    ResourceReadRequest,
    ResourceReadResponse,
    ResourceSearchRequest,
    ResourceSearchResponse,
)
from app.security.internal_hmac import CONTENT_SHA256_HEADER, SIGNATURE_HEADER
from app.tools.registry import ToolObservation
from tests.unit.test_chat_geo_context import geo_payload
from tests.unit.test_geo_chat_grounding_adversarial import (
    _context,  # pyright: ignore[reportPrivateUsage]
    _request,  # pyright: ignore[reportPrivateUsage]
)

_T = TypeVar("_T", bound=BaseModel)
_NOW = datetime.now(UTC)


def search_payload(*, status: str = "UNIQUE", complete: bool = True) -> dict[str, Any]:
    candidates = [
        {
            "choiceId": "choice-alpha",
            "kind": "GEO_TOPIC",
            "resourceId": "201",
            "name": "轻行杯",
            "spaceId": "11",
            "spaceName": "旗舰店",
            "hasReport": True,
            "exact": True,
        }
    ]
    if status in {"MULTIPLE", "INCOMPLETE"}:
        candidates.append(
            {**candidates[0], "choiceId": "choice-beta", "resourceId": "202", "name": "轻行杯大号"}
        )
    if status == "NOT_FOUND":
        candidates = []
    return {
        "candidateSetId": "901",
        "kind": "GEO_TOPIC",
        "status": status,
        "totalMatches": len(candidates) if complete else 9,
        "complete": complete,
        "candidates": candidates,
        "expiresAt": (_NOW + timedelta(hours=1)).isoformat(),
    }


def read_payload(*, purpose: str = "LATEST_GEO_REPORT", no_report: bool = False) -> dict[str, Any]:
    return {
        "observationId": "902",
        "kind": "GEO_TOPIC" if purpose == "LATEST_GEO_REPORT" else "PRODUCT",
        "purpose": purpose,
        "resourceId": "201" if purpose == "LATEST_GEO_REPORT" else "301",
        "name": "轻行杯",
        "spaceId": "11",
        "productId": "301",
        "observedAt": _NOW.isoformat(),
        "sourceHash": "a" * 64,
        **({"reportId": "101"} if purpose == "LATEST_GEO_REPORT" and not no_report else {}),
        **(
            {
                "reportAttempt": {
                    "runId": "903",
                    "status": "FAILED",
                    "createdAt": _NOW.isoformat(),
                    "newerFailedAttempt": False,
                }
            }
            if no_report
            else {}
        ),
        "projection": geo_payload()
        if purpose == "LATEST_GEO_REPORT" and not no_report
        else {"items": [{"name": "杯子白底图", "type": "IMAGE"}], "total": 1},
        "truncated": False,
    }


def memory(*, after_chat: bool = False, reply: bool = False) -> ConversationMemory:
    messages = [
        {
            "messageId": "798",
            "sequence": 1,
            "role": "USER",
            "content": {"text": "看看轻行杯的报告"},
            "createdAt": _NOW.isoformat(),
            "replyToMessageId": None,
        },
        {
            "messageId": "799",
            "sequence": 2,
            "role": "ASSISTANT",
            "content": {
                "interaction": {
                    "interactionType": "RESOURCE_SELECTION",
                    "candidateSetId": "901",
                    "choices": ["轻行杯", "轻行杯大号"],
                }
            },
            "createdAt": _NOW.isoformat(),
            "replyToMessageId": None,
        },
    ]
    if after_chat:
        messages.append(
            {
                "messageId": "800",
                "sequence": 3,
                "role": "ASSISTANT",
                "content": {"text": "天气问题的回答"},
                "createdAt": _NOW.isoformat(),
                "replyToMessageId": None,
            }
        )
    messages.append(
        {
            "messageId": "801",
            "sequence": len(messages) + 1,
            "role": "USER",
            "content": {"text": "第一个"},
            "createdAt": _NOW.isoformat(),
            "replyToMessageId": "799" if reply else None,
        }
    )
    return ConversationMemory.model_validate(
        {
            "messages": messages,
            "summary": None,
            "lastMessageSequence": len(messages),
            "summaryThroughSequence": 0,
            "contextSource": "POSTGRESQL",
        }
    )


class Client:
    def __init__(self) -> None:
        self.search = search_payload()
        self.read = read_payload()
        self.calls: list[tuple[str, dict[str, object]]] = []
        self.fail = False
        self.evidence: dict[str, Any] | None = None

    async def search_run_resources(
        self, run_id: UUID, user_context_ref: str, payload: Any, traceparent: str | None = None
    ) -> JavaInternalResponse:
        self.calls.append(("search", dict(payload)))
        if self.fail:
            from app.integration.java_internal import JavaInternalClientError, JavaInternalErrorCode

            raise JavaInternalClientError(JavaInternalErrorCode.TIMEOUT)
        return JavaInternalResponse(200, self.search, "a" * 32)

    async def read_run_resource(
        self, run_id: UUID, user_context_ref: str, payload: Any, traceparent: str | None = None
    ) -> JavaInternalResponse:
        self.calls.append(("read", dict(payload)))
        result = self.evidence if payload.get("purpose") == "GEO_ANSWER_EVIDENCE" else self.read
        return JavaInternalResponse(200, result or self.read, "a" * 32)


class Gateway(LocalModelGateway):
    def __init__(
        self,
        reference: str = "SEARCH",
        purpose: str = "LATEST_GEO_REPORT",
        *,
        focus: str = "OVERVIEW",
    ) -> None:
        super().__init__()
        self.reference = reference
        self.purpose = purpose
        self.focus = focus
        self.inputs: list[tuple[str, ModelMessages]] = []
        self.fail = False

    async def complete_structured(
        self,
        messages: ModelMessages,
        response_model: type[_T],
        *,
        operation: str,
        max_tokens: int,
        error_code: str,
        repair_instruction: str,
        repair_attempts: int = 1,
    ) -> tuple[_T, GatewayUsage]:
        record_model_call()
        self.inputs.append((operation, messages))
        if self.fail:
            raise ModelGatewayError(
                "DEEPSEEK_RESOURCE_LOOKUP_INVALID", usage=GatewayUsage(request_count=1)
            )
        if operation == "readonly_geo_selection":
            catalog = json.loads(
                messages[0].content.split("<geo-fact-catalog>")[1].split("</geo-fact-catalog>")[0]
            )
            payload = {
                "scopeRef": catalog["scopeRef"],
                "focus": self.focus,
                "factRefs": [key for key in catalog["facts"] if key.startswith("report.")][:1],
                "quoteRefs": [],
                "linkRefs": ["report"],
            }
        else:
            assert operation == "readonly_resource_lookup"
            assert repair_attempts == 1
            payload = {
                "action": "READ",
                "kind": "PRODUCT",
                "keyword": "轻行杯",
                "purpose": self.purpose,
                "reference": self.reference,
                "reportPolicy": "LATEST_ATTEMPT",
            }
        return response_model.model_validate(payload), GatewayUsage(request_count=1)


def runtime(
    client: Client, gateway: Gateway, observations: list[ToolObservation] | None = None
) -> ResourceLookupRuntime:
    harness = RuntimeHarness()
    return ResourceLookupRuntime(
        gateway=gateway,
        harness=harness,
        java_client=client,
        lookup_prompt="Readonly lookup",
        geo_runtime=GeoReadOnlyRuntime(
            gateway=gateway, harness=harness, selector_prompt="Select facts"
        ),
        tool_names=("context.resources.search", "context.resources.read"),
        observer=None if observations is None else observations.append,
    )


def execute(
    client: Client, gateway: Gateway, text: str, context: AuthorizedRunContext | None = None
) -> Any:
    async def scenario() -> Any:
        request = _request(text)
        counters = RuntimeHarness().new_usage_counters(request)
        with bind_runtime_usage(counters):
            result = await runtime(client, gateway).execute(
                request=request,
                context=context or replace(_context(), selected_product_id=None),
                counters=counters,
                on_delta=None,
            )
        return result, counters

    return asyncio.run(scenario())


def test_single_report_short_path_has_actual_search_read_and_grounded_renderer() -> None:
    client, gateway = Client(), Gateway()
    result, counters = execute(client, gateway, "看看轻行杯的报告")
    assert result.business_outcome.status == "ANSWERED"
    assert [item[0] for item in client.calls] == ["search", "read"]
    assert counters.tool_call_count == 2 and counters.model_call_count == 2
    assert counters.provider_call_count == 0
    assert "0%" in result.text and "轻行杯" in result.text
    assert "已生成" not in result.text and result.interaction is None
    assert client.calls[0][1]["toolCallId"] != client.calls[1][1]["toolCallId"]
    planning = gateway.inputs[0][1][-1].content
    assert (
        "reportId" not in planning
        and "sourceHash" not in planning
        and "authorized-current-run" not in planning
    )


def report_memory(*, after_chat: bool = False, reply: bool = False) -> ConversationMemory:
    payload = memory(after_chat=after_chat, reply=reply).model_dump(mode="json", by_alias=True)
    payload["messages"][1]["content"] = {  # type: ignore[index]
        "text": "已查看这份报告。",
        "geoContext": {"status": "READY", "report": {"reportId": "101"}},
    }
    return ConversationMemory.model_validate(payload)


def scoped_geo(
    *, selection_source: str = "EXPLICIT_REPORT", can_create: bool = True
) -> GeoInsightContext:
    payload = cast(dict[str, Any], geo_payload())
    payload["selectionSource"] = selection_source
    payload["report"].update(spaceId="11", productId="301", canCreateArticle=can_create)
    return GeoInsightContext.model_validate(payload)


def active_article_workspace() -> TaskWorkspace:
    return TaskWorkspace.model_validate(
        {
            "currentGoal": "生成咖啡文章",
            "currentIntent": "ARTICLE",
            "activeScope": {"spaceId": "11", "productId": "301", "competitorIds": []},
            "confirmedConstraints": {"activeArticle": {"articleId": "501"}},
            "unresolvedQuestions": [],
            "pendingInteraction": None,
            "activeDraftId": "502",
            "activeTaskId": None,
            "referenceAssetIds": [],
            "runningTasks": [],
            "contextVersion": 1,
        }
    )


@pytest.mark.parametrize(
    "text",
    [
        "那帮我写一篇吧",
        "那帮我写一篇吧，先让我看看怎么写",
        "先让我看看怎么写",
        "好的",
        "继续",
    ],
)
def test_report_followup_discusses_direction_with_one_frozen_read_not_an_article_proposal(
    text: str,
) -> None:
    client, gateway = Client(), Gateway()
    client.read["projection"] = scoped_geo().model_dump(
        mode="json", by_alias=True, exclude_unset=True
    )
    context = replace(
        _context(),
        last_report_read_context=ResourceReadResponse.model_validate(client.read),
        conversation_memory=report_memory(),
    )
    assert report_article_discussion_requested(_request(text), context)
    result, counters = execute(client, gateway, text, context)
    assert result.business_outcome.status == "ANSWERED" and result.geo_context_used
    assert result.interaction is None
    assert [kind for kind, _ in client.calls] == ["read"]
    assert client.calls[0][1]["observationId"] == "902" and "reportPolicy" not in client.calls[0][1]
    assert [operation for operation, _ in gateway.inputs] == ["readonly_geo_selection"]
    assert counters.tool_call_count == 1 and counters.model_call_count == 1
    assert (
        "选购介绍" in result.text
        and "准备标题方案" in result.text
        and "投稿仍需单独确认" in result.text
    )
    assert "已创建" not in result.text and "已生成" not in result.text


@pytest.mark.parametrize(
    "after_chat,reply,expected", [(False, False, True), (True, False, False), (True, True, True)]
)
def test_vague_article_followup_requires_adjacent_report_metadata_or_explicit_reply(
    after_chat: bool,
    reply: bool,
    expected: bool,
) -> None:
    context = replace(
        _context(),
        last_report_read_context=ResourceReadResponse.model_validate(read_payload()),
        conversation_memory=report_memory(after_chat=after_chat, reply=reply),
    )
    assert report_article_discussion_requested(_request("那帮我写一篇吧"), context) is expected
    assert should_lookup_resources(_request("那帮我写一篇吧"), context) is expected


@pytest.mark.parametrize("text", ["继续", "好的", "那帮我写一篇吧"])
def test_active_article_keeps_its_existing_path_over_vague_report_continuation(text: str) -> None:
    context = replace(
        _context(),
        last_report_read_context=ResourceReadResponse.model_validate(read_payload()),
        conversation_memory=report_memory(),
        task_workspace=active_article_workspace(),
    )
    assert not report_article_discussion_requested(_request(text), context)
    assert not should_lookup_resources(_request(text), context)
    assert report_article_discussion_requested(_request("根据这份报告写一篇文章"), context)


@pytest.mark.parametrize(
    "text",
    [
        "给咖啡店写一篇欢迎文章",
        "写一篇关于财务报告的文章",
        "生成一张商品图",
        "帮我投一篇文章",
        "不要根据这份报告写文章了，写咖啡文章",
        "AI推荐一个写作工具",
    ],
)
def test_non_geo_article_and_topic_switch_do_not_wake_readonly_tools(text: str) -> None:
    context = replace(
        _context(),
        last_report_read_context=ResourceReadResponse.model_validate(read_payload()),
        conversation_memory=report_memory(),
    )
    assert not report_article_discussion_requested(_request(text), context)
    assert not should_lookup_resources(_request(text), context)


def test_no_report_article_followup_is_barrier_against_legacy_and_no_creation_entry() -> None:
    client, gateway = Client(), Gateway()
    client.read = read_payload(no_report=True)
    context = replace(
        _context(),
        geo_insight_context=scoped_geo(),
        last_report_read_context=ResourceReadResponse.model_validate(client.read),
    )
    result, counters = execute(client, gateway, "那帮我写一篇吧", context)
    assert result.business_outcome.status == "ANSWERED" and not result.geo_context_used
    assert "尚无可读取报告" in result.text and "最近可用" in result.text
    assert "选购介绍" not in result.text and "准备标题方案" not in result.text
    assert counters.model_call_count == 0
    assert [kind for kind, _ in client.calls] == ["read"]
    assert "reportPolicy" not in client.calls[0][1]


def test_explicit_legacy_report_direction_uses_only_strict_frozen_scope() -> None:
    client, gateway = Client(), Gateway()
    context = replace(_context(), geo_insight_context=scoped_geo())
    result, counters = execute(client, gateway, "根据这份报告写一篇文章", context)
    assert result.geo_context_used and result.business_outcome.status == "ANSWERED"
    assert client.calls == [] and counters.tool_call_count == 0 and counters.model_call_count == 1
    assert "轻行杯" in result.text and "准备标题方案" in result.text


_CONTENT_PLAN_REQUEST = (
    "结合这份报告，我先应该做哪些内容？给我一个简单的行动计划，暂时别生成文章也别发布。"
)


def _platform_report_context() -> AuthorizedRunContext:
    payload = scoped_geo().model_dump(mode="json", by_alias=True, exclude_unset=True)
    payload["report"].update(spaceId="601", productId=None)
    payload["report"]["target"].update(kind="BRAND", id="601", name="小行星")
    return replace(
        _context(),
        selected_space_id=None,
        selected_product_id=None,
        geo_insight_context=GeoInsightContext.model_validate(payload),
        agent_identity=AgentIdentity(
            installation_id="501",
            agent_key="geo",
            agent_version="1.0.0",
            name="GEO",
            project_id="601",
            capabilities=("chat", "resources.read", "geo.monitor", "article.generate"),
        ),
    )


@pytest.mark.parametrize(
    "text",
    [
        _CONTENT_PLAN_REQUEST,
        "基于这份报告先给内容计划，不要生成文章，不用发布。",
        "根据这份报告，先不执行，应该写些什么内容？",
    ],
)
def test_generic_project_explicit_report_plan_does_not_reask_target_or_prepare(text: str) -> None:
    context = _platform_report_context()
    client, gateway = Client(), Gateway(focus="CONTENT_PLAN")
    assert report_article_discussion_requested(_request(text), context)
    result, counters = execute(client, gateway, text, context)
    assert result.geo_context_used and result.business_outcome.status == "ANSWERED"
    assert result.interaction is None
    assert client.calls == []
    assert [operation for operation, _ in gateway.inputs] == ["readonly_geo_selection"]
    assert counters.tool_call_count == counters.provider_call_count == 0
    assert counters.model_call_count == 1
    assert "小行星" in result.text and "0%" in result.text and "失败 2 条" in result.text
    assert all(f"{index}." in result.text for index in (1, 2, 3))
    assert "不是遗漏原因或效果结论" in result.text
    assert "没有生成文章" in result.text and "不承诺" in result.text
    assert "准备标题方案" not in result.text and "请告诉我要查看" not in result.text


@pytest.mark.parametrize(
    "mutation",
    [
        "foreign_report",
        "report_product",
        "missing_identity",
        "deleted_installation",
        "unknown_version",
        "no_capability",
        "local_source",
    ],
)
def test_platform_report_binding_cannot_be_inferred_from_report_or_prose(mutation: str) -> None:
    context = _platform_report_context()
    identity = context.agent_identity
    assert identity is not None
    if mutation in {"foreign_report", "report_product"}:
        assert context.geo_insight_context is not None
        payload = context.geo_insight_context.model_dump(
            mode="json", by_alias=True, exclude_unset=True
        )
        payload["report"]["spaceId" if mutation == "foreign_report" else "productId"] = "602"
        context = replace(context, geo_insight_context=GeoInsightContext.model_validate(payload))
    elif mutation == "missing_identity":
        context = replace(context, agent_identity=None)
    elif mutation == "deleted_installation":
        context = replace(
            context, agent_identity=identity.model_copy(update={"installation_id": None})
        )
    elif mutation == "unknown_version":
        context = replace(
            context, agent_identity=identity.model_copy(update={"agent_version": "9.0.0"})
        )
    elif mutation == "no_capability":
        context = replace(
            context, agent_identity=identity.model_copy(update={"capabilities": ("chat",)})
        )
    else:
        context = replace(context, source="LOCAL_TRUSTED_STATE")
    client, gateway = Client(), Gateway(focus="CONTENT_PLAN")
    result, counters = execute(client, gateway, _CONTENT_PLAN_REQUEST, context)
    assert not result.geo_context_used and result.business_outcome.status == "NEEDS_INPUT"
    assert counters.provider_call_count == 0
    assert "小行星" not in result.text and "先做哪些内容" not in result.text


@pytest.mark.parametrize(
    "text",
    [
        "不要根据这份报告写文章了，写咖啡文章",
        "不用这份报告，换个话题",
        "把这句话翻译成英文:暂时别生成文章也别发布",
        "只查看这份报告的内容",
        "这份报告内容是什么意思？",
    ],
)
def test_readonly_planning_fix_keeps_topic_switch_and_unrelated_text(text: str) -> None:
    assert not report_article_discussion_requested(_request(text), _platform_report_context())


@pytest.mark.parametrize(
    "selection_source,space_id", [("AUTO_SINGLE_TOPIC", "11"), ("EXPLICIT_REPORT", "12")]
)
def test_auto_or_mismatched_legacy_report_must_confirm_a_new_resource_source(
    selection_source: str,
    space_id: str,
) -> None:
    client, gateway = Client(), Gateway()
    context = replace(
        _context(),
        geo_insight_context=scoped_geo(selection_source=selection_source),
        selected_space_id=space_id,
    )
    result, counters = execute(client, gateway, "根据这份报告写一篇文章", context)
    assert result.business_outcome.status == "NEEDS_INPUT" and not result.geo_context_used
    assert isinstance(result.interaction, ResourceSelectionInteraction)
    assert [kind for kind, _ in client.calls] == ["search"] and counters.tool_call_count == 1
    assert "准备标题方案" not in result.text


def test_resource_anchor_for_temporary_b_overrides_sidebar_a_and_legacy_a() -> None:
    client, gateway = Client(), Gateway()
    client.read["spaceId"] = "22"
    client.read["productId"] = "302"
    projection = scoped_geo().model_dump(mode="json", by_alias=True, exclude_unset=True)
    projection["report"].update(spaceId="22", productId="302")
    projection["report"]["target"].update(id="302", name="临时查看的水壶")
    client.read["projection"] = projection
    context = replace(
        _context(),
        geo_insight_context=scoped_geo(),
        last_report_read_context=ResourceReadResponse.model_validate(client.read),
    )
    result, _ = execute(client, gateway, "根据这份报告写一篇文章", context)
    assert result.geo_context_used and "临时查看的水壶" in result.text
    assert "轻行杯" not in result.text and context.selected_space_id == "11"
    assert context.selected_product_id == "301"
    assert [kind for kind, _ in client.calls] == ["read"]


@pytest.mark.parametrize(
    "text", ["这份报告更新了吗", "这份报告看看最新又跑了吗", "最新报告", "又测了吗"]
)
def test_explicit_refresh_never_reuses_frozen_observation(text: str) -> None:
    client, gateway = Client(), Gateway()
    context = replace(
        _context(),
        selected_product_id=None,
        last_report_read_context=ResourceReadResponse.model_validate(client.read),
    )
    result, _ = execute(client, gateway, text, context)
    assert result.geo_context_used
    assert [kind for kind, _ in client.calls] == ["search", "read"]
    assert client.calls[0][1]["keyword"] == "轻行杯"
    assert "observationId" not in client.calls[1][1]
    assert client.calls[1][1]["reportPolicy"] == "LATEST_ATTEMPT"


def test_explicit_legacy_report_refresh_does_not_take_legacy_direction_shortcut() -> None:
    client, gateway = Client(), Gateway()
    context = replace(_context(), geo_insight_context=scoped_geo())
    result, _ = execute(client, gateway, "根据这份报告看看最新报告再写一篇文章", context)
    assert not result.geo_context_used and result.business_outcome.status == "NEEDS_INPUT"
    assert [kind for kind, _ in client.calls] == ["search"]
    assert client.calls[0][1]["keyword"] == "保温杯自然发现"


def test_failed_frozen_read_never_uses_legacy_direction_or_creates_report_entry() -> None:
    client, gateway = Client(), Gateway()
    context = replace(
        _context(),
        geo_insight_context=scoped_geo(),
        last_report_read_context=ResourceReadResponse.model_validate(client.read),
    )
    client.read["reportId"] = "102"
    result, _ = execute(client, gateway, "根据这份报告写一篇文章", context)
    assert result.business_outcome.status == "FAILED" and not result.geo_context_used
    assert "准备标题方案" not in result.text
    assert [kind for kind, _ in client.calls] == ["read"]


def test_ineligible_report_can_discuss_facts_but_does_not_claim_article_entry_ready() -> None:
    client, gateway = Client(), Gateway()
    context = replace(_context(), geo_insight_context=scoped_geo(can_create=False))
    result, _ = execute(client, gateway, "根据这份报告写一篇文章", context)
    assert result.geo_context_used and "还不能用于准备文章方案" in result.text
    assert "你可以在下方" not in result.text


@pytest.mark.parametrize(
    "text",
    [
        "先别花钱，我想写给办公室的人看",
        "不要花钱，面向第一次买保温杯的读者",
        "方向改成选购介绍",
    ],
)
def test_audience_direction_followup_is_readonly_and_keeps_same_report(text: str) -> None:
    client, gateway = Client(), Gateway()
    client.read["projection"] = scoped_geo().model_dump(
        mode="json", by_alias=True, exclude_unset=True
    )
    context = replace(
        _context(),
        last_report_read_context=ResourceReadResponse.model_validate(client.read),
        conversation_memory=report_memory(),
    )
    result, counters = execute(client, gateway, text, context)
    assert result.geo_context_used and result.business_outcome.status == "ANSWERED"
    assert "你的补充要求" in result.text and text in result.text
    assert result.interaction is None and "已生成" not in result.text
    assert [kind for kind, _ in client.calls] == ["read"] and counters.tool_call_count == 1
    assert "reportPolicy" not in client.calls[0][1]


def test_three_turn_actual_graph_keeps_report_source_and_no_article_proposal() -> None:
    class ContextProvider:
        is_ready = True
        reason_code = None

        def __init__(self) -> None:
            self.context = replace(
                _context(),
                selected_product_id=None,
                protocol_features=ProtocolFeatures(resource_lookup=True),
            )

        async def load(self, request: Any) -> AuthorizedRunContext:
            return self.context

    async def scenario() -> None:
        provider, client, gateway = ContextProvider(), Client(), Gateway()
        client.read["projection"] = scoped_geo().model_dump(
            mode="json", by_alias=True, exclude_unset=True
        )
        loaded = load_bundle(default_bundle_path())
        graph = CommerceAssistantGraph(
            provider,
            gateway=gateway,
            prompt_set=CommercePromptSet.from_bundle(loaded.bundle, loaded.bundle.profiles[0]),
            tool_names=loaded.bundle.profiles[0].tool_names,
            java_client=cast(Any, client),
            resource_lookup_enabled=True,
        )
        messages: list[dict[str, Any]] = []
        texts = [
            "看看轻行杯可见度报告，简单说说",
            "那帮我写一篇吧，先让我看看怎么写",
            "先别花钱，我想写给办公室的人看",
        ]
        for turn, text in enumerate(texts):
            message_id = str(801 + turn * 2)
            messages.append(
                {
                    "messageId": message_id,
                    "sequence": len(messages) + 1,
                    "role": "USER",
                    "content": {"text": text},
                    "createdAt": _NOW.isoformat(),
                    "replyToMessageId": None,
                }
            )
            provider.context = replace(
                provider.context,
                conversation_memory=ConversationMemory.model_validate(
                    {
                        "messages": messages,
                        "summary": None,
                        "lastMessageSequence": len(messages),
                        "summaryThroughSequence": 0,
                        "contextSource": "POSTGRESQL",
                    }
                ),
            )
            request = _request(text)
            request = request.model_copy(
                update={"message": request.message.model_copy(update={"message_id": message_id})}
            )
            outcome = await graph.ainvoke(request)
            assert outcome.kind == "MESSAGE" and outcome.geo_context_used
            assert (
                outcome.business_outcome is not None
                and outcome.business_outcome.status == "ANSWERED"
            )
            assert outcome.task_draft is None and outcome.interaction is None
            if turn == 2:
                assert "办公室" in outcome.message
            messages.append(
                {
                    "messageId": str(802 + turn * 2),
                    "sequence": len(messages) + 1,
                    "role": "ASSISTANT",
                    "content": {
                        "text": outcome.message,
                        "geoContext": {"status": "READY", "report": {"reportId": "101"}},
                    },
                    "createdAt": _NOW.isoformat(),
                    "replyToMessageId": None,
                }
            )
            provider.context = replace(
                provider.context,
                last_report_read_context=ResourceReadResponse.model_validate(client.read),
            )
        assert [kind for kind, _ in client.calls] == ["search", "read", "read", "read"]
        assert all(
            payload["observationId"] == "902" and "reportPolicy" not in payload
            for kind, payload in client.calls[2:]
            if kind == "read"
        )
        assert [operation for operation, _ in gateway.inputs] == [
            "readonly_resource_lookup",
            "readonly_geo_selection",
            "readonly_geo_selection",
            "readonly_geo_selection",
        ]

    asyncio.run(scenario())


@pytest.mark.parametrize("status,complete", [("MULTIPLE", True), ("INCOMPLETE", False)])
def test_multi_or_partial_candidates_do_not_read_first_match(status: str, complete: bool) -> None:
    client = Client()
    client.search = search_payload(status=status, complete=complete)
    result, counters = execute(client, Gateway(), "查轻行杯的报告")
    assert result.business_outcome.status == "NEEDS_INPUT"
    assert isinstance(result.interaction, ResourceSelectionInteraction)
    assert set(result.interaction.model_dump(by_alias=True)) == {
        "interactionType",
        "candidateSetId",
        "question",
    }
    assert len(client.calls) == counters.tool_call_count == 1


@pytest.mark.parametrize("cross_space", [False, True])
def test_unique_topic_with_sidebar_product_requires_confirmation_not_failed_read(
    cross_space: bool,
) -> None:
    client = Client()
    if cross_space:
        client.search["candidates"][0]["spaceId"] = "12"
    result, counters = execute(client, Gateway(), "查轻行杯的报告", _context())
    assert result.business_outcome.status == "NEEDS_INPUT"
    assert isinstance(result.interaction, ResourceSelectionInteraction)
    assert [op for op, _ in client.calls] == ["search"]
    assert counters.tool_call_count == 1


def test_not_found_is_authoritative_empty_result_not_model_completion() -> None:
    client = Client()
    client.search = search_payload(status="NOT_FOUND")
    result, _ = execute(client, Gateway(), "查轻行杯报告")
    assert result.business_outcome.status == "NEEDS_INPUT"
    assert "没有找到" in result.text and [op for op, _ in client.calls] == ["search"]


@pytest.mark.parametrize(
    "after_chat,reply,expected_read",
    [(False, False, True), (True, False, False), (True, True, True)],
)
def test_ordinal_only_follows_active_card_or_explicit_reply(
    after_chat: bool, reply: bool, expected_read: bool
) -> None:
    client = Client()
    context = replace(
        _context(),
        pending_resource_context=ResourceSearchResponse.model_validate(
            search_payload(status="MULTIPLE")
        ),
        conversation_memory=memory(after_chat=after_chat, reply=reply),
    )
    result, _ = execute(client, Gateway(), "第一个", context)
    assert bool(client.calls) is expected_read
    if expected_read:
        assert client.calls[0][0] == "read"
        assert client.calls[0][1]["choiceId"] == "choice-alpha"
        assert result.business_outcome.status == "ANSWERED"
    else:
        assert result.business_outcome.status == "NEEDS_INPUT"


@pytest.mark.parametrize("text", ["不是第二个", "不要第二个", "第二个和第三个", "都选", "取消"])
def test_negative_or_multiple_card_reply_cannot_auto_select(text: str) -> None:
    client = Client()
    context = replace(
        _context(),
        pending_resource_context=ResourceSearchResponse.model_validate(
            search_payload(status="MULTIPLE")
        ),
        conversation_memory=memory(),
    )
    result, _ = execute(client, Gateway(), text, context)
    assert result.business_outcome.status == "NEEDS_INPUT"
    assert client.calls == []


def test_full_name_only_selects_from_immediately_linked_server_card() -> None:
    client = Client()
    context = replace(
        _context(),
        pending_resource_context=ResourceSearchResponse.model_validate(
            search_payload(status="MULTIPLE")
        ),
        conversation_memory=memory(),
    )
    assert should_lookup_resources(_request("就轻行杯吧"), context)
    result, _ = execute(client, Gateway(), "就轻行杯吧", context)
    assert result.business_outcome.status == "ANSWERED"
    assert [op for op, _ in client.calls] == ["read"]
    assert client.calls[0][1]["choiceId"] == "choice-alpha"
    stale = replace(context, conversation_memory=memory(after_chat=True))
    assert not should_lookup_resources(_request("就轻行杯吧"), stale)


def test_report_anchor_survives_asset_read_and_never_refreshes_latest() -> None:
    client, gateway = Client(), Gateway()
    context = replace(
        _context(),
        last_read_context=ResourceReadResponse.model_validate(
            read_payload(purpose="ASSET_INVENTORY")
        ),
        last_report_read_context=ResourceReadResponse.model_validate(read_payload()),
    )
    result, _ = execute(client, gateway, "这份报告说了什么", context)
    assert result.business_outcome.status == "ANSWERED"
    assert len(client.calls) == 1
    assert client.calls[0][1]["observationId"] == "902"
    assert "reportPolicy" not in client.calls[0][1]
    assert [op for op, _ in gateway.inputs] == ["readonly_geo_selection"]


def test_latest_failed_attempt_is_barrier_against_old_legacy_ready() -> None:
    client, gateway = Client(), Gateway()
    client.read = read_payload(no_report=True)
    context = replace(
        _context(), last_read_context=ResourceReadResponse.model_validate(client.read)
    )
    result, _ = execute(client, gateway, "这份报告怎么样", context)
    assert result.business_outcome.status == "ANSWERED"
    assert "失败" in result.text and "没有" not in result.text[:5]
    assert "0%" not in result.text and "轻行杯" in result.text
    assert gateway.inputs == []
    assert client.calls[0][1]["observationId"] == "902"


@pytest.mark.parametrize(
    "text",
    ["帮我看看光禾现在怎么样，之前不是测过吗？", "我们商品能被AI看见吗？", "AI推荐我们品牌了吗？"],
)
def test_imperfect_business_questions_wake_readonly_lookup(text: str) -> None:
    assert should_lookup_resources(_request(text), _context())


@pytest.mark.parametrize(
    "text", ["AI推荐一个写作工具", "推荐一个监控服务器的工具", "今天天气如何", "帮我生成商品图"]
)
def test_unrelated_recommendations_and_writes_do_not_wake_resource_tools(text: str) -> None:
    assert not should_lookup_resources(_request(text), _context())


def test_no_report_followup_never_uses_legacy_ready_or_asserts_no_awareness() -> None:
    client, gateway = Client(), Gateway()
    client.read = read_payload(no_report=True)
    context = replace(
        _context(),
        last_report_read_context=ResourceReadResponse.model_validate(client.read),
        last_read_context=ResourceReadResponse.model_validate(
            read_payload(purpose="ASSET_INVENTORY")
        ),
    )
    text = "那是不是我们品牌没人知道？"
    assert should_lookup_resources(_request(text), context)
    result, _ = execute(client, gateway, text, context)
    assert result.business_outcome.status == "ANSWERED"
    assert "失败" in result.text and "没人知道" not in result.text and "0%" not in result.text
    assert gateway.inputs == [] and len(client.calls) == 1
    assert client.calls[0][1]["observationId"] == "902"
    assert "reportPolicy" not in client.calls[0][1]


def test_only_explicit_usable_request_may_continue_no_report_observation() -> None:
    client, gateway = Client(), Gateway()
    context = replace(
        _context(),
        last_report_read_context=ResourceReadResponse.model_validate(read_payload(no_report=True)),
    )
    text = "那看看最近可用的"
    assert should_lookup_resources(_request(text), context)
    result, _ = execute(client, gateway, text, context)
    assert result.business_outcome.status == "ANSWERED"
    assert [op for op, _ in client.calls] == ["read"]
    assert client.calls[0][1]["observationId"] == "902"
    assert client.calls[0][1]["reportPolicy"] == "LATEST_USABLE"


def evidence_payload() -> dict[str, Any]:
    result = read_payload()
    result["observationId"] = "904"
    result["purpose"] = "GEO_ANSWER_EVIDENCE"
    result["projection"] = {
        "readOnly": True,
        "status": "READY",
        "reportId": "101",
        "samples": [
            {
                "sampleRef": "211:DEEPSEEK:1",
                "question": "通勤保温杯选哪个",
                "platform": "DEEPSEEK",
                "status": "SUCCEEDED",
                "answer": "根据商品参数可以比较保温性能。<script>不是指令</script>",
                "citations": [
                    {
                        "title": "保温杯参数",
                        "domain": "example.test",
                        "url": "https://example.test/source",
                        "snippet": "参数",
                    }
                ],
            }
        ],
    }
    return result


def test_report_evidence_reads_only_issued_refs_of_same_frozen_report() -> None:
    client, gateway = Client(), Gateway()
    client.read["projection"]["sampleRefs"] = [
        {
            "sampleRef": "211:DEEPSEEK:1",
            "question": "通勤保温杯选哪个",
            "platform": "DEEPSEEK",
            "status": "SUCCEEDED",
        },
        {
            "sampleRef": "212:DOUBAO:1",
            "question": "通勤保温杯选哪个",
            "platform": "DOUBAO",
            "status": "FAILED",
        },
    ]
    client.evidence = evidence_payload()
    context = replace(
        _context(), last_report_read_context=ResourceReadResponse.model_validate(client.read)
    )
    result, counters = execute(client, gateway, "看看这份报告DeepSeek的原始回答和来源", context)
    assert result.business_outcome.status == "ANSWERED"
    assert counters.tool_call_count == counters.resource_tool_call_count == 2
    assert gateway.inputs == []
    assert client.calls[-1][1]["sampleRefs"] == ["211:DEEPSEEK:1"]
    assert client.calls[-1][1]["observationId"] == "902"
    assert "保温性能" in result.text and "example.test" in result.text
    assert "<script>" not in result.text and "&lt;script&gt;" in result.text
    assert "211:DEEPSEEK:1" not in result.text


@pytest.mark.parametrize(
    "mutation", ["report", "unknown-ref", "duplicate-ref", "missing-ref", "space"]
)
def test_evidence_projection_cannot_switch_reports_or_smuggle_other_samples(mutation: str) -> None:
    client = Client()
    client.read["projection"]["sampleRefs"] = [
        {"sampleRef": "211:DEEPSEEK:1", "platform": "DEEPSEEK"}
    ]
    client.evidence = evidence_payload()
    if mutation == "report":
        client.evidence["reportId"] = "999"
    elif mutation == "unknown-ref":
        client.evidence["projection"]["samples"][0]["sampleRef"] = "999:DEEPSEEK:1"
    elif mutation == "duplicate-ref":
        client.evidence["projection"]["samples"].append(
            copy.deepcopy(client.evidence["projection"]["samples"][0])
        )
    elif mutation == "missing-ref":
        client.evidence["projection"]["samples"] = []
    elif mutation == "space":
        client.evidence["spaceId"] = "12"
    context = replace(
        _context(), last_report_read_context=ResourceReadResponse.model_validate(client.read)
    )
    result, _ = execute(client, Gateway(), "这份报告的原文", context)
    assert result.business_outcome.status == "FAILED"
    assert "保温性能" not in result.text


def test_new_explicit_latest_request_searches_again_not_reuses_anchor() -> None:
    client = Client()
    context = replace(
        _context(),
        selected_product_id=None,
        last_read_context=ResourceReadResponse.model_validate(read_payload()),
    )
    result, _ = execute(client, Gateway(), "查轻行杯最新报告", context)
    assert result.business_outcome.status == "ANSWERED"
    assert [op for op, _ in client.calls] == ["search", "read"]
    assert client.calls[-1][1]["reportPolicy"] == "LATEST_ATTEMPT"


def test_resource_cross_id_response_is_failed_before_rendering() -> None:
    client = Client()
    client.read["resourceId"] = "999"
    result, _ = execute(client, Gateway(), "查轻行杯报告")
    assert result.business_outcome.status == "FAILED"
    assert result.business_outcome.failure_stage == "TOOL"
    assert "0%" not in result.text


def test_failure_counts_attempt_and_keeps_tool_stage_no_model_fallback() -> None:
    client, gateway = Client(), Gateway()
    client.fail = True
    result, counters = execute(client, gateway, "查轻行杯报告")
    assert result.business_outcome.status == "FAILED"
    assert result.business_outcome.failure_stage == "LOOKUP"
    assert counters.resource_tool_call_count == counters.tool_call_count == 1
    assert [op for op, _ in gateway.inputs] == ["readonly_resource_lookup"]


def test_resource_fixed_budget_is_per_run_including_failures_and_tighter_limit() -> None:
    request = _request("查报告")
    counters = RuntimeHarness().new_usage_counters(request)
    for _ in range(6):
        counters.before_resource_tool_call()
        counters.before_tool_call()
    with pytest.raises(HarnessViolation) as capped:
        counters.before_resource_tool_call()
    assert capped.value.code == "RESOURCE_TOOL_BUDGET_EXCEEDED"
    another = RuntimeHarness().new_usage_counters(request)
    assert another.resource_tool_call_count == 0
    tighter = RuntimeHarness().new_usage_counters(
        request.model_copy(
            update={"budget": request.budget.model_copy(update={"max_tool_calls": 1})}
        )
    )
    tighter.before_resource_tool_call()
    tighter.before_tool_call()
    with pytest.raises(HarnessViolation) as limited:
        tighter.before_resource_tool_call()
    assert limited.value.code == "TOOL_CALL_BUDGET_EXCEEDED"


@pytest.mark.parametrize(
    "mutation",
    [
        "extra",
        "id-number",
        "bad-hash",
        "blank-choice",
        "too-big",
        "coerced-truncated",
        "missing-space",
    ],
)
def test_resource_read_strict_boundary_rejects_malformed_projection(mutation: str) -> None:
    payload = read_payload()
    if mutation == "extra":
        payload["userId"] = "4"
    elif mutation == "id-number":
        payload["observationId"] = 902
    elif mutation == "bad-hash":
        payload["sourceHash"] = "A" * 64
    elif mutation == "blank-choice":
        with pytest.raises(ValidationError):
            ResourceReadRequest.model_validate(
                {
                    "toolCallId": "call",
                    "purpose": "CATALOG_SUMMARY",
                    "candidateSetId": "901",
                    "choiceId": " ",
                }
            )
        return
    elif mutation == "too-big":
        payload["projection"] = {"data": "x" * 16385}
    elif mutation == "coerced-truncated":
        payload["truncated"] = "false"
    elif mutation == "missing-space":
        del payload["spaceId"]
    with pytest.raises(ValidationError):
        ResourceReadResponse.model_validate(payload)


@pytest.mark.parametrize(
    "mutation",
    ["not-exact", "incomplete-unique", "wrong-total", "duplicate-choice", "extra", "bool-string"],
)
def test_search_never_infers_uniqueness_from_a_partial_page(mutation: str) -> None:
    payload = search_payload()
    if mutation == "not-exact":
        payload["candidates"][0]["exact"] = False
    elif mutation == "incomplete-unique":
        payload["complete"] = False
    elif mutation == "wrong-total":
        payload["totalMatches"] = 0
    elif mutation == "duplicate-choice":
        payload["candidates"].append(copy.deepcopy(payload["candidates"][0]))
    elif mutation == "extra":
        payload["nextSql"] = "select"
    elif mutation == "bool-string":
        payload["complete"] = "true"
    with pytest.raises(ValidationError):
        ResourceSearchResponse.model_validate(payload)


def test_attachments_do_not_inherit_selection_or_read_references() -> None:
    context = replace(
        _context(),
        last_read_context=ResourceReadResponse.model_validate(read_payload()),
        last_report_read_context=ResourceReadResponse.model_validate(read_payload()),
        pending_resource_context=ResourceSearchResponse.model_validate(search_payload()),
    )
    detached = context.current_attachment_only()
    assert (
        detached.last_read_context
        is detached.last_report_read_context
        is detached.pending_resource_context
        is detached.resource_selection
        is None
    )
    request = _request("这份报告").model_copy(
        update={
            "message": _request("这份报告").message.model_copy(
                update={"attachment_asset_ids": ("123",)}
            )
        }
    )
    assert should_lookup_resources(request, context) is False
    assert "lastReadContext" not in context.model_context_json()


@pytest.mark.parametrize(
    "local,java,enabled", [(False, True, False), (True, False, False), (True, True, True)]
)
def test_graph_resource_output_requires_both_frozen_feature_switches(
    local: bool, java: bool, enabled: bool
) -> None:
    class ContextProvider:
        is_ready = True
        reason_code = None

        async def load(self, request: Any) -> AuthorizedRunContext:
            return replace(
                _context(),
                selected_product_id=None,
                protocol_features=ProtocolFeatures(resource_lookup=java),
            )

    client, gateway = Client(), Gateway()
    loaded = load_bundle(default_bundle_path())
    graph = CommerceAssistantGraph(
        ContextProvider(),
        gateway=gateway,
        prompt_set=CommercePromptSet.from_bundle(loaded.bundle, loaded.bundle.profiles[0]),
        tool_names=loaded.bundle.profiles[0].tool_names,
        java_client=cast(Any, client),
        resource_lookup_enabled=local,
    )
    outcome = asyncio.run(graph.ainvoke(_request("查轻行杯的报告")))
    assert bool(client.calls) is enabled
    if enabled:
        assert outcome.intent == "GENERAL" and outcome.geo_context_used is True
        assert (
            outcome.business_outcome is not None and outcome.business_outcome.status == "ANSWERED"
        )


def test_java_resource_call_uses_existing_hmac_bytes_and_authorization_header() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json=search_payload())

    async def scenario() -> None:
        settings = AppSettings(
            java_internal_base_url="http://127.0.0.1:8080",
            internal_hmac_key_id="test-key",
            internal_hmac_secret=SecretStr("a" * 32),
        )
        async with httpx.AsyncClient(
            base_url="http://127.0.0.1:8080", transport=httpx.MockTransport(handler)
        ) as http:
            client = JavaInternalClient(settings, http)
            request = _request("查报告")
            payload = ResourceSearchRequest(
                tool_call_id="stable-tool-id", kind="PRODUCT", keyword="轻行杯"
            ).model_dump(mode="json", by_alias=True, exclude_none=True)
            await client.search_run_resources(request.run_id, request.user_context_ref, payload)

    asyncio.run(scenario())
    assert len(captured) == 1
    actual = captured[0]
    assert actual.url.path.endswith("/resources/search")
    assert actual.headers["X-User-Context-Ref"] == "authorized-current-run"
    assert actual.headers["Content-Type"] == "application/json"
    assert SIGNATURE_HEADER in actual.headers and CONTENT_SHA256_HEADER in actual.headers
    assert json.loads(actual.content)["toolCallId"] == "stable-tool-id"


def test_resource_interaction_wire_schema_rejects_model_choices_and_arbitrary_ids() -> None:
    from pathlib import Path

    schema = json.loads(
        (
            Path(__file__).resolve().parents[2] / "contracts/events/chat-run-event-v1.schema.json"
        ).read_text(encoding="utf-8")
    )
    validator = Draft202012Validator(schema)
    event: dict[str, Any] = {
        "schemaVersion": 1,
        "eventId": "a",
        "eventType": "interaction.required",
        "runId": "a",
        "occurredAt": "date",
        "sequence": 1,
        "payload": {
            "interactionType": "RESOURCE_SELECTION",
            "candidateSetId": "901",
            "question": "选择哪个？",
        },
    }
    assert validator.is_valid(event)  # pyright: ignore[reportUnknownMemberType]
    event["payload"]["choices"] = ["模型虚构选项"]
    assert not validator.is_valid(event)  # pyright: ignore[reportUnknownMemberType]

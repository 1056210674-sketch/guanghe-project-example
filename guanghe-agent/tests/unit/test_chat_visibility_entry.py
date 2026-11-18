"""The merchant entry reads saved evidence or prepares a bounded, unpaid first draft."""

import asyncio
from dataclasses import replace
from typing import Any, cast

import pytest
from pydantic import JsonValue

from app.agent.graphs.commerce import CommerceAssistantGraph
from app.agent.graphs.domains import (
    DomainGraphError,
    article_requests_geo_report,
    prepare_task_draft,
    prepare_task_draft_input,
    requests_product_visibility,
)
from app.agent.models import DraftOperation, Intent
from app.agent.resources import should_lookup_resources
from app.agent.run_manager import ChatRunManager
from app.common.runtime import initialize_runtime
from app.common.settings import AppSettings
from app.harness.runtime import record_model_call
from app.integration.java_internal import JavaInternalResponse, JavaWorkflowPlanClientProtocol
from app.model_gateway.gateway import (
    DeltaHandler,
    GatewayUsage,
    IntentResult,
    LocalModelGateway,
    ModelMessage,
    ModelMessages,
    ModelToolCall,
    ModelToolDefinition,
    ModelTurnResult,
    RouterDecision,
    TaskParametersResult,
    TextResult,
    default_intent_route,
    is_geo_guidance_request,
    is_geo_status_consultation,
    last_user_content,
    requests_domain_execution,
)
from app.providers.java_context import (
    AuthorizedRunContext,
    ConversationMemory,
    GeoInsightContext,
    PendingInteraction,
    ProtocolFeatures,
    TaskPreparationConstraints,
)
from app.providers.resources import FrozenResourceSelection, ResourceSearchResponse
from app.tools.registry import TaskDraftPrepareInput
from tests.unit.test_chat_geo_context import geo_payload
from tests.unit.test_chat_run_isolation import _collect  # pyright: ignore[reportPrivateUsage]
from tests.unit.test_commerce_graph import (
    _java_context,  # pyright: ignore[reportPrivateUsage]
    _MappedJavaContextProvider,  # pyright: ignore[reportPrivateUsage]
    _request,  # pyright: ignore[reportPrivateUsage]
    _with_active_article_workspace,  # pyright: ignore[reportPrivateUsage]
    _with_active_image_workspace,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_monitor_continuation import JavaRecorder
from tests.unit.test_resource_lookup import Client as ResourceClient
from tests.unit.test_resource_lookup import Gateway as ResourceGateway
from tests.unit.test_resource_lookup import memory as resource_memory
from tests.unit.test_resource_lookup import search_payload

ENTRY = (
    "分析当前商品的AI可见度\uff1a优先解读已有报告；没有报告时先拟监测问题、"
    "推荐可用平台并给出报价，等我确认后再监测。"
)
QUESTIONS = ["适合办公室的家电有哪些？", "选择家电需要比较哪些信息？", "家电选购有哪些可靠资料？"]


def _context(status: str = "NO_REPORT", *, existing_topic: bool = False) -> AuthorizedRunContext:
    context = _java_context("1", user_context_ref="authorized-visibility-entry")
    payload = cast(dict[str, Any], geo_payload())
    if status == "READY":
        payload["report"].update(
            {
                "spaceId": context.selected_space_id,
                "productId": context.selected_product_id,
                "canCreateArticle": True,
                "articleUnavailableReason": None,
            }
        )
    else:
        payload.update(
            {
                "status": status,
                "selectionSource": "NONE",
                "report": None,
                "trend": None,
                "competitors": None,
                "links": {"overviewPath": "/app/geo", "reportPath": None, "competitivePath": None},
            }
        )
    if existing_topic:
        payload["topicChoices"] = [
            {
                "topicId": "201",
                "spaceId": context.selected_space_id,
                "productId": context.selected_product_id,
                "name": "已有家电专题",
                "href": "/app/geo?topicId=201",
            }
        ]
    return replace(
        context,
        geo_insight_context=GeoInsightContext.model_validate(payload),
        task_preparation_constraints=TaskPreparationConstraints.model_validate(
            {
                "geo": {
                    "enabled": True,
                    "minQuestions": 3,
                    "maxQuestions": 5,
                    "platformCodes": ["DOUBAO", "DEEPSEEK", "QWEN"],
                    "maxProviderCalls": 15,
                }
            }
        ),
    )


class _Gateway(LocalModelGateway):
    def __init__(self, intent: Intent = Intent.GENERAL, *, revision: bool = False) -> None:
        self.intent = intent
        self.revision = revision
        self.suggestions = 0

    async def classify_intent(self, messages: ModelMessages) -> IntentResult:
        del messages
        route = default_intent_route(self.intent)
        if self.revision:
            route = route.model_copy(update={"draft_operation": DraftOperation.REVISE})
        return IntentResult(route)

    async def suggest_task_parameters(
        self, intent: Intent, messages: ModelMessages
    ) -> TaskParametersResult:
        del messages
        self.suggestions += 1
        parameters: dict[str, JsonValue] = (
            {"topic": "家电选购", "questions": list[JsonValue](QUESTIONS)}
            if intent is Intent.GEO
            else {"topic": "家电选购注意事项", "geoReportId": "999"}
        )
        return TaskParametersResult(summary="准备可核对的草稿", parameters=parameters)


def _invoke(content: str, context: AuthorizedRunContext, gateway: _Gateway | None = None):
    request = _request(
        content, space_id=context.selected_space_id, product_id=context.selected_product_id
    )
    return asyncio.run(
        CommerceAssistantGraph(
            context_provider=_MappedJavaContextProvider({request.run_id: context}),
            gateway=gateway or _Gateway(),
        ).ainvoke(request)
    )


@pytest.mark.parametrize("content", (ENTRY, "帮我看看这个商品在AI里怎么样", "分析下可见度"))
def test_first_analysis_prepares_questions_platforms_but_never_executes(content: str) -> None:
    gateway = _Gateway()
    outcome = _invoke(content, _context(), gateway)
    assert outcome.kind == "TASK_DRAFT" and outcome.task_draft is not None
    assert outcome.task_draft.proposal_only is True
    assert outcome.task_draft.parameters["questions"] == QUESTIONS
    assert outcome.task_draft.parameters["platformCodes"] == ["DOUBAO", "DEEPSEEK"]
    assert outcome.task_draft.parameters["mode"] == "QUESTION_DISCOVERY"
    assert (
        not {"price", "points", "confirmationToken", "taskId"}
        & outcome.task_draft.parameters.keys()
    )
    assert outcome.usage.provider_call_count == 0 and not outcome.geo_context_used
    assert gateway.suggestions == 1


@pytest.mark.parametrize("content", (ENTRY, "帮我看看这个商品在AI里怎么样"))
def test_same_entry_reads_existing_report_without_creating_a_draft(content: str) -> None:
    gateway = _Gateway()
    outcome = _invoke(content, _context("READY"), gateway)
    assert outcome.kind == "MESSAGE" and outcome.task_draft is None
    assert outcome.geo_context_used and "2026/09/24" in outcome.message
    assert gateway.suggestions == 0 and outcome.usage.tool_call_count == 0


@pytest.mark.parametrize("status", ("NEEDS_SCOPE", "NEEDS_TOPIC", "UNAVAILABLE"))
def test_unresolved_is_not_permission_for_first_monitor(status: str) -> None:
    outcome = _invoke(ENTRY, _context(status))
    assert outcome.task_draft is None and outcome.geo_context_used


def test_existing_topic_without_report_is_not_duplicated() -> None:
    outcome = _invoke(ENTRY, _context(existing_topic=True))
    assert outcome.task_draft is None
    assert "本轮没有新建专题或开始监测" in outcome.message
    assert "/app/geo?topicId=201" in outcome.message


@pytest.mark.parametrize(
    "content",
    (
        "什么是AI可见度",
        "解释一下AI可见度",
        "先不要监测，帮我分析可见度",
        "分析可见度以后再说",
        "好的",
        "继续",
        "取消",
        "写两句咖啡店欢迎语",
    ),
)
def test_explanation_cancel_ack_and_other_chat_do_not_start_first_monitor(content: str) -> None:
    assert not requests_product_visibility(content)
    outcome = _invoke(content, _context())
    assert outcome.task_draft is None and not outcome.geo_context_used


GUIDANCE = (
    "GEO是什么",
    "什么是AI可见度",
    "可见度什么意思",
    "解释一下AI可见度",
    "GEO和SEO是一回事吗，讲讲区别",
    "可见度怎么算",
    "GEO监测报告是什么",
    "我主要想让大家问AI的时候能看到我的店，现在不知道怎么开始",
    "不做这个了，帮我解释一下什么叫可见度，用简单点的话说",
    "我经营一家咖啡店，做GEO应该从哪里开始",
    "怎样开始让AI更容易提到我的品牌",
    "我不懂，不知道怎么入手",
    "怎么提高我的品牌可见度，先给建议",
    "先聊聊如何提升AI可见度，不用监测",
    "只聊别监测",
)


STATUS_CONSULTATIONS = (
    "七个平台只成功了三个，这能算全部监测成功吗？",
    "GEO监测是否完成？",
    "任务完成了没？",
    "是否完成",
    "失败样本怎么解读？",
    "请解读这些失败样本",
    "样本采集超时意味着什么？",
    "失败样本是不是说明我的店没有被提到？",
)


class _MisroutedStatusGateway(_Gateway):
    """A status question must remain read-only even if the model would prepare."""

    def __init__(self) -> None:
        super().__init__(Intent.GEO)
        self.classification_calls = 0
        self.answer_calls = 0

    async def classify_intent(self, messages: ModelMessages) -> IntentResult:
        self.classification_calls += 1
        return await super().classify_intent(messages)

    async def answer_general(
        self, messages: ModelMessages, *, on_delta: DeltaHandler | None = None
    ) -> TextResult:
        self.answer_calls += 1
        return await super().answer_general(messages, on_delta=on_delta)


@pytest.mark.parametrize("content", STATUS_CONSULTATIONS)
@pytest.mark.parametrize("status", ("NEEDS_SCOPE", "NO_REPORT", "READY"))
def test_monitor_status_consultation_never_prepares_new_work(content: str, status: str) -> None:
    gateway = _MisroutedStatusGateway()
    assert is_geo_status_consultation(content)
    assert is_geo_guidance_request(content)
    assert not requests_domain_execution(content)
    outcome = _invoke(content, _context(status), gateway)
    assert outcome.kind == "MESSAGE" and outcome.intent is Intent.GENERAL
    assert outcome.task_draft is None and outcome.interaction is None
    assert outcome.usage.tool_call_count == 0
    assert gateway.suggestions == gateway.classification_calls == 0
    assert gateway.answer_calls == 1


@pytest.mark.parametrize(
    "content",
    (
        "这份报告里两个样本失败，怎么解读？",
        "这次监测结果能算全部成功吗？",
        "上次报告中的失败样本是不是说明没人提及？",
    ),
)
def test_report_status_consultation_keeps_authorized_evidence_not_a_new_monitor(
    content: str,
) -> None:
    gateway = _MisroutedStatusGateway()
    assert is_geo_status_consultation(content)
    assert not is_geo_guidance_request(content)
    outcome = _invoke(content, _context("READY"), gateway)
    assert outcome.task_draft is None and outcome.geo_context_used
    assert gateway.suggestions == gateway.classification_calls == gateway.answer_calls == 0


@pytest.mark.parametrize(
    "content",
    (
        "重新监测",
        "再跑一次",
        "更新可见度",
        "七个平台只成功了三个，这能算全部成功吗？重新监测",
        "失败样本怎么解读？再跑一次",
        "任务是否完成？再帮我跑一次",
        "失败样本是不是没有提及？现在帮我更新可见度",
    ),
)
def test_explicit_new_monitoring_is_not_swallowed_by_status_consultation(content: str) -> None:
    assert requests_domain_execution(content)
    assert not is_geo_status_consultation(content)
    assert not is_geo_guidance_request(content)
    gateway = _Gateway(Intent.GEO)
    outcome = _invoke(content, _context(), gateway)
    assert outcome.task_draft is not None and outcome.task_draft.proposal_only
    assert gateway.suggestions == 1


class _MisroutedGuidanceGateway(_Gateway):
    """Reproduce the old router result; the shared boundary must prevent it."""

    def __init__(self) -> None:
        super().__init__(Intent.GEO)
        self.answer_calls = 0
        self.classification_calls = 0

    async def classify_intent(self, messages: ModelMessages) -> IntentResult:
        del messages
        self.classification_calls += 1
        return IntentResult(
            default_intent_route(Intent.GEO).model_copy(
                update={"decision": RouterDecision.ANSWER, "draft_operation": None}
            )
        )

    async def answer_general(
        self, messages: ModelMessages, *, on_delta: DeltaHandler | None = None
    ) -> TextResult:
        self.answer_calls += 1
        return await super().answer_general(messages, on_delta=on_delta)


@pytest.mark.parametrize("content", GUIDANCE)
@pytest.mark.parametrize("status", ("NEEDS_SCOPE", "NO_REPORT", "READY"))
def test_guidance_never_requires_report_product_or_resource_selection(
    content: str, status: str
) -> None:
    context = _context(status)
    if status == "NEEDS_SCOPE":
        context = replace(
            context,
            space=None,
            product=None,
            selected_space_id=None,
            selected_product_id=None,
            competitors=(),
        )
    context = replace(context, protocol_features=ProtocolFeatures(resource_lookup=True))
    request = _request(
        content, space_id=context.selected_space_id, product_id=context.selected_product_id
    )
    client, gateway = ResourceClient(), _MisroutedGuidanceGateway()
    assert is_geo_guidance_request(content)
    assert not should_lookup_resources(request, context)
    assert not requests_product_visibility(content)
    outcome = asyncio.run(
        CommerceAssistantGraph(
            context_provider=_MappedJavaContextProvider({request.run_id: context}),
            gateway=gateway,
            java_client=cast(Any, client),
            resource_lookup_enabled=True,
            tool_names=("task_draft.propose", "context.resources.search", "context.resources.read"),
        ).ainvoke(request)
    )
    assert outcome.kind == "MESSAGE" and outcome.intent is Intent.GENERAL
    assert outcome.interaction is None and outcome.task_draft is None
    assert not outcome.geo_context_used and outcome.usage.tool_call_count == 0
    assert client.calls == [] and gateway.suggestions == 0
    assert gateway.answer_calls == 1 and gateway.classification_calls == 0


@pytest.mark.parametrize(
    "content",
    (
        GUIDANCE[7],
        GUIDANCE[8],
        "GEO是什么",
        "只聊别监测",
        "不看上次报告了，解释一下可见度",
        "换个话题，聊聊咖啡",
        "刚才说的不做了",
        *STATUS_CONSULTATIONS,
    ),
)
def test_guidance_leaves_previous_candidates_and_pending_task_untouched(content: str) -> None:
    context = _with_active_image_workspace(_context("NEEDS_SCOPE"))
    assert context.task_workspace is not None
    workspace = context.task_workspace.model_copy(
        update={
            "current_intent": "GEO",
            "pending_interaction": PendingInteraction(
                message_id="799",
                interaction_type="CLARIFICATION",
                intent="GEO",
                question="要查看哪个专题？",
                missing_fields=("resource",),
                choices=("轻行杯", "轻行杯大号"),
            ),
        }
    )
    candidates = ResourceSearchResponse.model_validate(search_payload(status="MULTIPLE"))
    context = replace(
        context,
        pending_resource_context=candidates,
        conversation_memory=resource_memory(),
        task_workspace=workspace,
        protocol_features=ProtocolFeatures(resource_lookup=True),
    )
    request = _request(
        content, space_id=context.selected_space_id, product_id=context.selected_product_id
    )
    request = request.model_copy(
        update={
            "trusted_state": request.trusted_state.model_copy(
                update={"pending_interaction_id": "799"}
            )
        }
    )
    client, gateway = ResourceClient(), _MisroutedGuidanceGateway()
    assert not should_lookup_resources(request, context)
    outcome = asyncio.run(
        CommerceAssistantGraph(
            context_provider=_MappedJavaContextProvider({request.run_id: context}),
            gateway=gateway,
            java_client=cast(Any, client),
            resource_lookup_enabled=True,
            tool_names=("task_draft.propose", "context.resources.search", "context.resources.read"),
        ).ainvoke(request)
    )
    assert outcome.kind == "MESSAGE" and outcome.intent is Intent.GENERAL
    assert (
        outcome.interaction is None and outcome.task_draft is None and not outcome.geo_context_used
    )
    assert client.calls == [] and gateway.answer_calls == 1
    assert context.pending_resource_context is candidates and context.task_workspace is workspace


def test_current_authorized_selection_is_not_overridden_by_plain_text_guidance() -> None:
    context = replace(
        _context("READY"),
        resource_selection=FrozenResourceSelection(
            candidate_set_id="901",
            choice_id="choice-alpha",
            kind="GEO_TOPIC",
            resource_id="201",
            name="轻行杯",
            space_id="11",
            product_id="301",
        ),
    )
    assert should_lookup_resources(_request("解释一下可见度"), context)


@pytest.mark.parametrize(
    "content",
    (
        "看上次报告",
        "这次为什么降低",
        "查商品可见度",
        "刚才报告里可见度下降为什么",
        "解释可见度，并帮我查上次报告",
        "讲讲这份报告是什么意思",
        "解释可见度，再看看这次监测结果",
        "这次可见度怎么计算出来的",
    ),
)
def test_specific_evidence_and_mixed_report_questions_keep_grounded_path(content: str) -> None:
    assert not is_geo_guidance_request(content)
    gateway = _MisroutedGuidanceGateway()
    outcome = _invoke(content, _context("READY"), gateway)
    assert outcome.geo_context_used and outcome.task_draft is None
    assert gateway.answer_calls == 0


@pytest.mark.parametrize(
    "content",
    (
        "解释可见度，现在监测",
        "解释一下GEO，并帮我开始监测",
        "我不知道怎么开始，请现在监测我的商品",
        "解释可见度，再监测一次",
        "解释一下GEO，然后帮我跑监测",
        "不知道怎么开始，我想监测一下",
        "GEO是什么，请帮我做GEO监测",
        "我不知道怎么开始，帮我重跑监测",
        "解释一下可见度，帮我做一下 GEO 监测",
        "解释一下可见度再帮我重跑监测",
        "我不知道怎么开始现在请帮我监测",
    ),
)
def test_mixed_explicit_monitoring_is_not_swallowed_by_guidance(content: str) -> None:
    assert not is_geo_guidance_request(content)
    gateway = _Gateway(Intent.GEO)
    outcome = _invoke(content, _context(), gateway)
    assert outcome.task_draft is not None and outcome.task_draft.proposal_only
    assert gateway.suggestions == 1


@pytest.mark.parametrize(
    "content",
    (
        "怎么提高可见度，直接生成一篇文章",
        "GEO是什么，帮我制作一张图",
        "解释可见度，并发布文章",
        "不知道怎么开始，帮我创建文章",
        "怎么提升可见度，帮我来一张图",
    ),
)
def test_mixed_creative_and_publishing_intents_still_require_semantic_routing(content: str) -> None:
    assert not is_geo_guidance_request(content)


@pytest.mark.parametrize(
    "content",
    (
        "解释一下可见度，再看店铺报告",
        "什么是 GEO？再读一下监测报告",
        "解释一下可见度，并帮我查上次报告",
        "帮我看看上次监测报告",
    ),
)
@pytest.mark.parametrize("pending", (False, True))
def test_mixed_report_read_resolves_real_resources_without_requiring_bound_product(
    content: str, pending: bool
) -> None:
    context = replace(
        _context("NEEDS_SCOPE"),
        space=None,
        product=None,
        selected_space_id=None,
        selected_product_id=None,
        conversation_memory=resource_memory() if pending else None,
        pending_resource_context=(
            ResourceSearchResponse.model_validate(search_payload(status="MULTIPLE"))
            if pending
            else None
        ),
        protocol_features=ProtocolFeatures(resource_lookup=True),
    )
    request = _request(content)
    client, gateway = ResourceClient(), ResourceGateway()
    assert not is_geo_guidance_request(content)
    assert should_lookup_resources(request, context)
    outcome = asyncio.run(
        CommerceAssistantGraph(
            context_provider=_MappedJavaContextProvider({request.run_id: context}),
            gateway=gateway,
            java_client=cast(Any, client),
            resource_lookup_enabled=True,
            tool_names=("task_draft.propose", "context.resources.search", "context.resources.read"),
        ).ainvoke(request)
    )
    assert [call[0] for call in client.calls] == ["search", "read"]
    assert outcome.geo_context_used and outcome.task_draft is None


@pytest.mark.parametrize(
    "content",
    (
        "解释一下可见度，再查上次报告，然后请现在监测",
        "我不知道怎么开始，帮我重跑监测",
        "解释一下可见度，帮我做一下 GEO 监测",
    ),
)
def test_mixed_execution_cannot_be_swallowed_by_resource_lookup(content: str) -> None:
    context = replace(
        _context("NEEDS_SCOPE"), protocol_features=ProtocolFeatures(resource_lookup=True)
    )
    request = _request(content)
    client, gateway = ResourceClient(), _Gateway(Intent.GEO)
    assert not should_lookup_resources(request, context)
    assert not requests_product_visibility(content)
    outcome = asyncio.run(
        CommerceAssistantGraph(
            context_provider=_MappedJavaContextProvider({request.run_id: context}),
            gateway=gateway,
            java_client=cast(Any, client),
            resource_lookup_enabled=True,
            tool_names=("task_draft.propose", "context.resources.search", "context.resources.read"),
        ).ainvoke(request)
    )
    assert client.calls == [] and not outcome.geo_context_used
    assert outcome.task_draft is not None and outcome.task_draft.proposal_only


@pytest.mark.parametrize("content", ("报告是什么意思", "报告什么意思"))
def test_ambiguous_report_explanation_is_left_to_semantic_routing(content: str) -> None:
    assert not is_geo_guidance_request(content)


@pytest.mark.parametrize("content", ("帮我看看上次监测报告", "请查看上次生成的图片素材"))
def test_read_verbs_govern_historical_execution_nouns(content: str) -> None:
    assert not requests_domain_execution(content)
    assert should_lookup_resources(_request(content), _context("NEEDS_SCOPE"))


@pytest.mark.parametrize("content", ("刚才说的不做了", "先聊别的", "先别监测了"))
def test_abandoning_discussion_does_not_resume_previous_geo_flow(content: str) -> None:
    outcome = _invoke(content, _context("NEEDS_SCOPE"))
    assert outcome.kind == "MESSAGE" and not outcome.geo_context_used
    assert outcome.task_draft is None and outcome.interaction is None


def test_clear_product_and_missing_category_cannot_prepare_arbitrary_object() -> None:
    context = _context()
    outcome = _invoke(ENTRY, replace(context, product=None, selected_product_id=None))
    assert outcome.task_draft is None
    assert context.product is not None
    outcome = _invoke(
        ENTRY, replace(context, product=context.product.model_copy(update={"category": None}))
    )
    assert outcome.kind == "INTERACTION" and "类目" in outcome.message


def test_named_target_questions_are_replaced_with_neutral_category_questions() -> None:
    prepared = prepare_task_draft_input(
        TaskDraftPrepareInput(
            intent=Intent.GEO,
            request_text="分析商品可见度",
            candidate_summary="商品监测草稿",
            candidate_parameters={
                "topic": "轻行",
                "questions": ["轻行杯好吗？", "品牌别名好吗？", "竞品好吗？"],
            },
            geo_default_topic="轻行杯",
            geo_default_category="保温杯",
            geo_excluded_names=("轻行杯", "品牌别名", "竞品"),
            geo_allowed_platform_codes=("DEEPSEEK",),
            geo_max_provider_calls=3,
        )
    )
    proposal = prepare_task_draft(prepared)
    questions = proposal.parameters["questions"]
    assert isinstance(questions, list) and len(questions) == 3
    assert all(isinstance(q, str) and "保温杯" in q and "轻行" not in q for q in questions)


@pytest.mark.parametrize("platforms", (["KIMI"], ["DOUBAO", "DOUBAO"], ["DOUBAO", "DEEPSEEK"]))
def test_platform_subset_uniqueness_and_provider_budget_are_checked(platforms: list[str]) -> None:
    with pytest.raises(DomainGraphError, match="GEO_PARAMETERS_INVALID"):
        prepare_task_draft_input(
            TaskDraftPrepareInput(
                intent=Intent.GEO,
                request_text="准备首次监测",
                candidate_summary="监测草稿",
                candidate_parameters={
                    "topic": "家电",
                    "questions": list[JsonValue](QUESTIONS),
                    "platformCodes": list[JsonValue](platforms),
                },
                geo_allowed_platform_codes=("DOUBAO", "DEEPSEEK"),
                geo_max_provider_calls=3,
            )
        )


@pytest.mark.parametrize("suffix", ("", "，先不要发", "，先不投稿", "，不要发布"))
def test_report_article_binds_only_authorized_report_and_not_model_id(suffix: str) -> None:
    content = "根据这份报告写一篇文章" + suffix
    assert article_requests_geo_report(content)
    outcome = _invoke(content, _context("READY"), _Gateway(Intent.ARTICLE))
    assert outcome.task_draft is not None
    assert outcome.task_draft.parameters["geoReportId"] == "101"
    assert outcome.usage.provider_call_count == 0 and not outcome.geo_context_used


def test_plain_article_and_negated_report_creation_do_not_inherit_report() -> None:
    assert not article_requests_geo_report("不要根据这份报告写文章")
    assert not article_requests_geo_report("根据这份报告，不要写文章")
    outcome = _invoke("写一篇家电选购文章", _context("READY"), _Gateway(Intent.ARTICLE))
    assert outcome.task_draft is not None and "geoReportId" not in outcome.task_draft.parameters


def _with_immediate_geo_answer(
    context: AuthorizedRunContext, content: str, *, previous_report_id: str = "101"
) -> AuthorizedRunContext:
    memory = ConversationMemory.model_validate(
        {
            "messages": [
                {
                    "messageId": "1900012345678904001",
                    "sequence": 1,
                    "role": "ASSISTANT",
                    "content": {
                        "text": "这份报告里商品还没被明确提及。",
                        "geoContext": {
                            "status": "READY",
                            "selectionSource": "EXPLICIT_REPORT",
                            "report": {
                                "reportId": previous_report_id,
                                "spaceId": context.selected_space_id,
                                "productId": context.selected_product_id,
                            },
                        },
                    },
                    "createdAt": "2026-09-30T10:00:00Z",
                    "replyToMessageId": None,
                },
                {
                    "messageId": "1900012345678904002",
                    "sequence": 2,
                    "role": "USER",
                    "content": {"text": content},
                    "createdAt": "2026-09-30T10:01:00Z",
                    "replyToMessageId": None,
                },
            ],
            "summary": None,
            "lastMessageSequence": 2,
            "summaryThroughSequence": 0,
            "contextSource": "POSTGRESQL",
        }
    )
    return replace(context, conversation_memory=memory)


def test_colloquial_followup_article_inherits_only_the_immediately_discussed_report() -> None:
    content = "那帮我写点能发的文章吧。"
    context = _with_immediate_geo_answer(_context("READY"), content)
    gateway = _Gateway(Intent.ARTICLE)
    outcome = _invoke(content, context, gateway)
    assert outcome.kind == "TASK_DRAFT" and outcome.task_draft is not None
    assert outcome.task_draft.proposal_only
    assert outcome.task_draft.parameters["geoReportId"] == "101"
    assert gateway.suggestions == 1


def test_colloquial_followup_report_switch_or_missing_report_needs_clarification() -> None:
    content = "那帮我写点能发的文章吧。"
    baseline = _with_immediate_geo_answer(_context("READY"), content)
    assert baseline.geo_insight_context is not None
    assert baseline.geo_insight_context.report is not None
    different = baseline.geo_insight_context.report.model_copy(update={"report_id": "202"})
    switched = replace(
        baseline,
        geo_insight_context=baseline.geo_insight_context.model_copy(update={"report": different}),
    )
    missing = replace(baseline, geo_insight_context=None)
    for context in (switched, missing):
        gateway = _Gateway(Intent.ARTICLE)
        outcome = _invoke(content, context, gateway)
        assert outcome.kind == "INTERACTION" and outcome.task_draft is None
        assert "本轮未创建文章草稿" in outcome.message
        assert gateway.suggestions == 0


@pytest.mark.parametrize(
    "content",
    (
        "那帮我写一篇普通文章，不按这份报告。",
        "那换个话题，写一篇母婴文章。",
    ),
)
def test_colloquial_followup_does_not_inherit_after_opt_out_or_topic_switch(content: str) -> None:
    context = _with_immediate_geo_answer(_context("READY"), content)
    outcome = _invoke(content, context, _Gateway(Intent.ARTICLE))
    assert outcome.task_draft is None or "geoReportId" not in outcome.task_draft.parameters


@pytest.mark.parametrize("content", ("那不要写文章，先解释一下。", "那怎么写文章更好？"))
def test_colloquial_followup_explanation_or_refusal_does_not_create_article(content: str) -> None:
    context = _with_immediate_geo_answer(_context("READY"), content)
    outcome = _invoke(content, context)
    assert outcome.task_draft is None


def test_colloquial_followup_requires_immediately_previous_geo_answer() -> None:
    content = "那帮我写点能发的文章吧。"
    context = _with_immediate_geo_answer(_context("READY"), content)
    assert context.conversation_memory is not None
    messages = context.conversation_memory.messages
    non_geo = messages[0].model_copy(update={"content": {"text": "这次聊点别的。"}})
    memory = context.conversation_memory.model_copy(update={"messages": (non_geo, messages[1])})
    outcome = _invoke(
        content, replace(context, conversation_memory=memory), _Gateway(Intent.ARTICLE)
    )
    assert outcome.task_draft is not None
    assert "geoReportId" not in outcome.task_draft.parameters


def test_report_article_requires_current_scope_and_available_evidence() -> None:
    context = _context("READY")
    assert (
        context.geo_insight_context is not None and context.geo_insight_context.report is not None
    )
    for update in ({"product_id": "999"}, {"can_create_article": False}):
        report = context.geo_insight_context.report.model_copy(update=update)
        invalid = replace(
            context,
            geo_insight_context=context.geo_insight_context.model_copy(update={"report": report}),
        )
        outcome = _invoke("根据这份报告写一篇文章", invalid, _Gateway(Intent.ARTICLE))
        assert outcome.task_draft is None and "本轮未创建文章草稿" in outcome.message


def test_new_report_article_does_not_silently_bind_a_later_auto_report() -> None:
    context = _context("READY")
    assert context.geo_insight_context is not None
    context = replace(
        context,
        geo_insight_context=context.geo_insight_context.model_copy(
            update={"selection_source": "AUTO_SINGLE_TOPIC"}
        ),
    )
    gateway = _Gateway(Intent.ARTICLE)
    outcome = _invoke("根据这份报告写一篇文章", context, gateway)
    assert outcome.task_draft is None and gateway.suggestions == 0
    assert "点击原报告卡" in outcome.message


def test_unrelated_chat_does_not_get_captured_by_active_article() -> None:
    context = _with_active_article_workspace(_context("READY"))
    outcome = _invoke("写两句咖啡店欢迎语", context)
    assert outcome.intent is Intent.GENERAL and outcome.task_draft is None
    assert not outcome.geo_context_used and "继续当前文章" not in outcome.message


@pytest.mark.parametrize(
    "content",
    (
        "把监测问题改得更偏通勤场景，平台不变",
        "修改当前待确认方案\uff1a\n" + ENTRY + "\n问题重点放在送礼场景，平台不变",
        ENTRY + "问题重点放在送礼场景，平台不变",
    ),
)
def test_geo_revision_keeps_selected_platform_without_restarting_a_topic(content: str) -> None:
    context = _with_active_image_workspace(_context())
    assert context.task_workspace is not None
    context = replace(
        context,
        task_workspace=context.task_workspace.model_copy(
            update={
                "current_intent": "GEO",
                "confirmed_constraints": {
                    "topic": "家电选购",
                    "questions": QUESTIONS,
                    "platformCodes": ["DEEPSEEK"],
                },
            }
        ),
    )
    outcome = _invoke(content, context, _Gateway(Intent.GEO, revision=True))
    assert outcome.task_draft is not None
    assert outcome.task_draft.draft_operation is DraftOperation.REVISE
    assert outcome.task_draft.parameters["platformCodes"] == ["DEEPSEEK"]
    assert context.task_workspace is not None
    assert outcome.task_draft.previous_draft_id == context.task_workspace.active_draft_id


@pytest.mark.parametrize("pending_conflict", (False, True))
@pytest.mark.parametrize(
    "content",
    (
        "把这篇文章语气改得自然一些",
        "根据这份报告，把这篇文章改得自然一些",
        "根据这份报告，把文章写得通俗些，先不要发",
    ),
)
def test_article_revision_keeps_original_source_even_if_latest_report_differs(
    content: str, pending_conflict: bool
) -> None:
    context = _pending_report_article_context(pending_conflict=pending_conflict)
    gateway = _Gateway(Intent.ARTICLE, revision=True)
    outcome = _invoke(content, context, gateway)
    assert outcome.task_draft is not None
    assert outcome.task_draft.draft_operation is DraftOperation.REVISE
    assert outcome.task_draft.parameters["geoReportId"] == "99"
    assert context.task_workspace is not None
    assert outcome.task_draft.previous_draft_id == context.task_workspace.active_draft_id
    assert outcome.task_draft.proposal_only is True
    # Java resolves the immutable article identity from the authorized previous
    # draft; the model cannot substitute its own article or confirmation IDs.
    assert not {"articleId", "articleVersion", "confirmationToken", "taskId"} & (
        outcome.task_draft.parameters.keys()
    )
    assert gateway.suggestions == 1 and outcome.usage.provider_call_count == 0


def _pending_report_article_context(*, pending_conflict: bool = False) -> AuthorizedRunContext:
    context = _with_active_image_workspace(_context("READY"))
    assert context.task_workspace is not None
    return replace(
        context,
        task_workspace=context.task_workspace.model_copy(
            update={
                "current_intent": "ARTICLE",
                "pending_interaction": PendingInteraction(
                    message_id="1900012345678904001",
                    interaction_type="CLARIFICATION",
                    intent="ARTICLE",
                    question="你要继续当前文章，还是结束当前流程并开始一篇新文章？",
                    missing_fields=("activeArticleDecision",),
                    choices=("继续当前文章", "开始新文章"),
                )
                if pending_conflict
                else None,
                "confirmed_constraints": {
                    "mode": "TITLE",
                    "articleId": "1900012345678904777",
                    "articleVersion": 1,
                    "topic": "原报告选购文章",
                    "geoReportId": "99",
                    "tone": "专业",
                    "activeArticle": {
                        "articleId": "1900012345678904777",
                        "articleVersion": 1,
                        "workflowStage": "TITLE_INPUT",
                        "title": "当前文章",
                    },
                },
            }
        ),
    )


@pytest.mark.parametrize("draft_article_id", (None, "1900012345678904888"))
def test_article_revision_cannot_bypass_conflict_for_unmatched_article(
    draft_article_id: str | None,
) -> None:
    context = _pending_report_article_context()
    assert context.task_workspace is not None
    constraints = {**context.task_workspace.confirmed_constraints, "articleId": draft_article_id}
    context = replace(
        context,
        task_workspace=context.task_workspace.model_copy(
            update={"confirmed_constraints": constraints}
        ),
    )
    gateway = _Gateway(Intent.ARTICLE, revision=True)
    outcome = _invoke("把文章写得通俗些，先不要发", context, gateway)
    assert outcome.task_draft is None and gateway.suggestions == 0
    assert outcome.interaction is not None
    assert outcome.interaction.missing_fields == ("activeArticleDecision",)


@pytest.mark.parametrize("update", ({"active_draft_id": None}, {"current_intent": "IMAGE"}))
def test_article_revision_still_requires_authorized_pending_article_draft(
    update: dict[str, object],
) -> None:
    context = _pending_report_article_context()
    assert context.task_workspace is not None
    context = replace(context, task_workspace=context.task_workspace.model_copy(update=update))
    gateway = _Gateway(Intent.ARTICLE, revision=True)
    outcome = _invoke("把文章写得通俗些，先不要发", context, gateway)
    assert outcome.task_draft is None and gateway.suggestions == 0


def test_new_article_still_asks_how_to_handle_existing_article() -> None:
    gateway = _Gateway(Intent.ARTICLE)
    outcome = _invoke("另外新写一篇咖啡文章", _pending_report_article_context(), gateway)
    assert outcome.task_draft is None and gateway.suggestions == 0
    assert outcome.interaction is not None
    assert outcome.interaction.missing_fields == ("activeArticleDecision",)


def test_pending_article_confirmation_never_revises_or_confirms() -> None:
    gateway = _Gateway(Intent.ARTICLE, revision=True)
    outcome = _invoke("确认", _pending_report_article_context(), gateway)
    assert outcome.task_draft is None and gateway.suggestions == 0
    assert outcome.intent is Intent.AMBIGUOUS_CONFIRMATION


def test_pending_article_ordinary_ack_stays_general() -> None:
    gateway = _Gateway(Intent.GENERAL)
    outcome = _invoke("好的", _pending_report_article_context(), gateway)
    assert outcome.task_draft is None and gateway.suggestions == 0
    assert outcome.intent is Intent.GENERAL


def test_completed_marker_is_emitted_only_after_readonly_geo() -> None:
    class ReadonlyGateway(LocalModelGateway):
        async def complete_turn(
            self,
            messages: ModelMessages,
            *,
            tools: tuple[ModelToolDefinition, ...],
            max_tokens: int = 4096,
            thinking: bool = True,
            operation: str = "agent_loop",
        ) -> ModelTurnResult:
            del tools, max_tokens, thinking
            assert operation == "native_agent_loop"
            record_model_call(input_messages=messages)
            observations = sum(message.role == "tool" for message in messages)
            calls: tuple[ModelToolCall, ...] = ()
            if last_user_content(messages) == ENTRY:
                if observations == 0:
                    calls = (
                        ModelToolCall(
                            "search-1",
                            "resources_search",
                            '{"kind":"GEO_TOPIC","keyword":"轻行杯"}',
                        ),
                    )
                elif observations == 1:
                    calls = (
                        ModelToolCall(
                            "read-1",
                            "resources_read",
                            '{"purpose":"LATEST_GEO_REPORT","candidateSetId":"901","choiceId":"choice-alpha"}',
                        ),
                    )
            return ModelTurnResult(
                ModelMessage("assistant", "" if calls else "已完成只读回答。", calls),
                "tool_calls" if calls else "stop",
                GatewayUsage(request_count=1),
            )

    async def scenario() -> None:
        # Neither Run attaches a report. Only the first explicitly reads one via Java;
        # its successful marker must not leak into the following ordinary response.
        context = replace(_context("READY"), geo_insight_context=None)

        class ReadonlyClient(ResourceClient):
            async def compact_run_history(self, *args: Any, **kwargs: Any) -> JavaInternalResponse:
                result = await JavaRecorder({}).compact_run_history(*args, **kwargs)
                payload = cast(dict[str, Any], result.payload)
                payload["memory"]["projectId"] = context.selected_space_id
                return result

        requests = [_request(content) for content in (ENTRY, "谢谢，写两句欢迎语")]
        resources = ReadonlyClient()
        # The lifecycle marker depends on the server reference, not duplicating a full
        # report already present in the authorized context (covered by report tests).
        resources.read["projection"] = {"summary": "已保存的测试报告"}
        manager = ChatRunManager(
            initialize_runtime(AppSettings(mock_event_delay_ms=0)),
            ReadonlyGateway(),
            _MappedJavaContextProvider({request.run_id: context for request in requests}),
            cast(JavaWorkflowPlanClientProtocol, resources),
        )
        try:
            for request, expected in zip(requests, (True, False), strict=True):
                await manager.start(request)
                events = await _collect(manager, request.run_id)
                completed = next(event for event in events if event.event_type == "run.completed")
                assert completed.payload.get("geoContextUsed", False) is expected
                assert "geoReportId" not in completed.payload
                assert not any(event.event_type == "task-draft.proposed" for event in events)
                assert [name for name, _ in resources.calls] == ["search", "read"]
        finally:
            await manager.close()

    asyncio.run(scenario())

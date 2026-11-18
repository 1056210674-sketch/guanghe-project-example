"""Bounded authorized resource lookup; models choose intent, never database references."""

from __future__ import annotations

import html
import json
import re
from dataclasses import dataclass, field, replace
from datetime import UTC, datetime
from typing import Literal, cast
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field, JsonValue, ValidationError

from app.agent.geo_readonly import GeoReadOnlyRuntime
from app.agent.models import (
    BusinessOutcome,
    BusinessOutcomeStatus,
    ChatRunRequest,
    Intent,
    InteractionProposal,
    ResourceSelectionInteraction,
)
from app.harness.runtime import RuntimeHarness, RuntimeUsageCounters
from app.integration.java_internal import (
    JavaInternalClientError,
    JavaInternalErrorCode,
    JavaResourceClientProtocol,
)
from app.model_gateway.gateway import (
    DeltaHandler,
    GatewayUsage,
    ModelGateway,
    ModelGatewayError,
    ModelMessage,
    StructuredCompletionGateway,
    is_geo_guidance_request,
    requests_domain_execution,
)
from app.providers.java_context import (
    AuthorizedRunContext,
    GeoInsightContext,
    current_authorized_project_id,
)
from app.providers.resources import (
    FrozenResourceSelection,
    ReportPolicy,
    ResourceCandidate,
    ResourceKind,
    ResourceReadRequest,
    ResourceReadResponse,
    ResourceSearchRequest,
    ResourceSearchResponse,
    ResourceSelection,
)
from app.tools.registry import (
    DEFAULT_TOOL_REGISTRY,
    ToolExecutionError,
    ToolExecutor,
    ToolGroup,
    ToolObserver,
    ToolPolicy,
)

_RESOURCE_TOOLS = frozenset({"context.resources.search", "context.resources.read"})
_ORDINAL = re.compile(
    r"^(?:选(?:择)?|就)?\s*(?:第)?([一二三四五六1-6])(?:个|项)?(?:吧)?[。\uff01!\s]*$"
)
_REPORT_REFERENCE = ("这份报告", "这个报告", "刚才的报告", "上面的报告", "这份监测")
_READ_MARKERS = (
    "看看",
    "查看",
    "看一下",
    "查询",
    "查一下",
    "读一下",
    "分析",
    "查下",
    "查",
    "看",
)
_WRITE_MARKERS = ("生成", "制作", "创建", "新建", "发文", "投稿", "开始监测", "跑监测")
_NEGATION = ("不要", "不是", "不选", "取消", "不用", "换个话题", "都选", "和第", "与第")
_MONITORING_REFERENCE = ("测过", "监测过", "之前测", "之前监测")
_GEO_DISCOVERY_REFERENCE = ("AI推荐", "AI 推荐", "被AI看见", "被 AI 看见")
_AWARENESS_REFERENCE = (
    "没人知道",
    "没有人知道",
    "知名度",
    "被提到",
    "被推荐",
    "可见度",
    "AI推荐",
    "AI 推荐",
)
_EVIDENCE_REFERENCE = ("原文", "原始回答", "具体回答", "回答内容", "引用", "来源", "证据")
_LATEST_USABLE_REFERENCE = ("最近可用", "上一份能用", "上一份可用")
_REPORT_REFRESH = (
    "更新报告",
    "更新后的报告",
    "最新报告",
    "最新的报告",
    "最新又跑",
    "又测了吗",
    "又跑了吗",
)
_BARE_REPORT_CONTINUATION = frozenset({"好的", "继续"})
_DIRECTION_REFERENCE = (
    "写给",
    "面向",
    "受众",
    "读者",
    "人群",
    "方向",
    "语气",
    "主打",
    "突出",
    "先讨论",
)
_PLATFORM_NAMES = {
    "DOUBAO": "豆包",
    "DEEPSEEK": "DeepSeek",
    "QWEN": "通义",
    "WENXIN": "文心",
    "KIMI": "Kimi",
    "YUANBAO": "元宝",
    "ZHIPU": "智谱",
}


def _follows_report(text: str, context: AuthorizedRunContext) -> bool:
    if _report_anchor(context) is None:
        return False
    if any(marker in text for marker in _REPORT_REFERENCE):
        return True
    # Narrow follow-ups, not general questions about a different named brand.
    return any(
        marker in text
        for marker in ("我们品牌", "我们的品牌", "这个品牌", "那是不是我们", "所以我们", "这个商品")
    ) and any(marker in text for marker in _AWARENESS_REFERENCE)


class ResourceLookupDecision(BaseModel):
    """No IDs, arbitrary fields, prose or business outcome enter the model's schema."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)
    action: Literal["READ", "CLARIFY"]
    kind: ResourceKind
    keyword: str = Field(max_length=200)
    purpose: Literal["CATALOG_SUMMARY", "LATEST_GEO_REPORT", "ASSET_INVENTORY"]
    reference: Literal["SEARCH", "LAST_REPORT", "LAST_READ", "SELECTION", "SIDEBAR"]
    report_policy: ReportPolicy = Field(alias="reportPolicy")


@dataclass(frozen=True, slots=True)
class ResourceLookupResult:
    text: str
    business_outcome: BusinessOutcome
    usage: GatewayUsage = field(default_factory=GatewayUsage)
    interaction: InteractionProposal | ResourceSelectionInteraction | None = None
    geo_context_used: bool = False


class ResourceLookupError(RuntimeError):
    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


def _linked_card(context: AuthorizedRunContext, request: ChatRunRequest) -> tuple[str, str] | None:
    """Only a server card adjacent to this turn, or this turn's explicit reply chain."""
    pending = context.pending_resource_context
    memory = context.conversation_memory
    if pending is None or memory is None or pending.expires_at <= datetime.now(UTC):
        return None
    current = next((m for m in memory.messages if m.message_id == request.message.message_id), None)
    reply = None if current is None else current.reply_to_message_id
    earlier = [m for m in memory.messages if m.message_id != request.message.message_id]
    if not earlier:
        return None
    card = next((m for m in earlier if m.message_id == reply), None) if reply else earlier[-1]
    if card is None or card.role != "ASSISTANT":
        return None
    interaction = card.content.get("interaction")
    if (
        not isinstance(interaction, dict)
        or interaction.get("interactionType") != "RESOURCE_SELECTION"
    ):
        return None
    resource = interaction.get("resource")
    set_id = interaction.get("candidateSetId")
    if set_id is None and isinstance(resource, dict):
        set_id = resource.get("candidateSetId")
    if set_id != pending.candidate_set_id:
        return None
    # Recover the user query that produced the card, not assistant-authored instructions.
    before = [m for m in earlier if m.sequence < card.sequence and m.role == "USER"]
    goal = "" if not before else before[-1].content.get("text", "")
    return card.message_id, goal if isinstance(goal, str) else ""


def _named_choice(text: str, pending: ResourceSearchResponse | None) -> ResourceCandidate | None:
    if pending is None or any(marker in text for marker in _NEGATION):
        return None
    matches = [
        item
        for item in pending.candidates
        if re.fullmatch(
            r"(?:选(?:择)?|就|我要看)?\s*" + re.escape(item.name) + r"(?:吧)?[。\uff01!\s]*", text
        )
    ]
    return matches[0] if len(matches) == 1 else None


def _legacy_explicit_report(context: AuthorizedRunContext) -> GeoInsightContext | None:
    # A resource observation, including noReport, always wins over the old projection.
    if _report_anchor(context) is not None:
        return None
    geo = context.geo_insight_context
    report = None if geo is None else geo.report
    if (
        geo is None
        or geo.status != "READY"
        or geo.selection_source != "EXPLICIT_REPORT"
        or report is None
        or report.space_id is None
        or report.space_id != current_authorized_project_id(context)
        or report.product_id != context.selected_product_id
    ):
        return None
    return geo


def _article_in_progress(context: AuthorizedRunContext) -> bool:
    workspace = context.task_workspace
    return workspace is not None and (
        isinstance(workspace.confirmed_constraints.get("activeArticle"), dict)
        or (workspace.current_intent == "ARTICLE" and workspace.active_draft_id is not None)
        or any(task.task_type == "ARTICLE" for task in workspace.running_tasks)
    )


def _linked_report_message(context: AuthorizedRunContext, request: ChatRunRequest) -> bool:
    anchor = _report_anchor(context)
    legacy = _legacy_explicit_report(context) if anchor is None else None
    report_id = (
        anchor.report_id
        if anchor is not None
        else (None if legacy is None or legacy.report is None else legacy.report.report_id)
    )
    memory = context.conversation_memory
    if report_id is None or memory is None:
        return False
    current = next(
        (item for item in memory.messages if item.message_id == request.message.message_id), None
    )
    if current is None or current.role != "USER":
        return False
    earlier = [item for item in memory.messages if item.sequence < current.sequence]
    if not earlier:
        return False
    previous = (
        next((item for item in earlier if item.message_id == current.reply_to_message_id), None)
        if current.reply_to_message_id is not None
        else earlier[-1]
    )
    if previous is None or previous.role != "ASSISTANT":
        return False
    geo = previous.content.get("geoContext")
    report = geo.get("report") if isinstance(geo, dict) else None
    return (
        isinstance(geo, dict)
        and geo.get("status") == "READY"
        and isinstance(report, dict)
        and report.get("reportId") == report_id
    )


def report_article_discussion_requested(
    request: ChatRunRequest, context: AuthorizedRunContext
) -> bool:
    if request.message.attachment_asset_ids:
        return False
    text = "".join(request.message.content.split()).strip("。\uff01？!?，,. ")
    # Negated execution is not cancellation of an explicit read-only content plan.
    # Keep topic switches/cancellation intact; this only removes the action clause.
    text = re.sub(
        r"(?:暂时|现在|先)?(?:不要|不用|别|先不)(?:自动)?"
        r"(?:生成(?:文章|正文)?|发布|投稿|创建任务|执行)",
        "",
        text,
    )
    # Avoiding spending is not a cancellation of this read-only discussion.
    text = re.sub(r"(?:先)?(?:不要|不用|别|不|先不)(?:花钱|付费|扣费|扣积分)", "", text)
    if any(
        marker in text
        for marker in ("不要", "不用", "不写", "先不", "取消", "换个话题", "说点别的")
    ):
        return False
    explicit = any(
        marker in text
        for marker in (
            *_REPORT_REFERENCE,
            "根据报告",
            "基于报告",
            "监测结果",
            "最新报告",
            "最新的报告",
        )
    )
    article = any(
        marker in text
        for marker in (
            "文章",
            "稿子",
            "发文",
            "写一篇",
            "写篇",
            "怎么写",
            "如何写",
            "行动计划",
            "内容计划",
            "内容方向",
        )
    ) or (
        "内容" in text
        and any(marker in text for marker in ("做哪些", "应该写", "写些什么", "写什么"))
    )
    if explicit and article:
        return True
    # A genuine article card retains its own existing confirmation/resume semantics.
    if _article_in_progress(context):
        return False
    linked = _linked_report_message(context, request)
    if text in _BARE_REPORT_CONTINUATION:
        return linked
    if (
        linked
        and article
        and (
            text.startswith(("那", "那么", "就", "接着", "然后", "先让我", "先看看"))
            or text in {"怎么写", "如何写", "先让我看看怎么写"}
        )
    ):
        return True
    if linked and any(marker in text for marker in _DIRECTION_REFERENCE):
        return True
    anchor = _report_anchor(context)
    # An exact vague follow-up to a noReport barrier must not create an unrelated
    # sidebar article merely because that older sidebar still has a usable report.
    return (
        anchor is not None
        and anchor.report_id is None
        and re.fullmatch(
            r"(?:那|那么|就)(?:帮我|给我|为我)?(?:写|生成|起草)(?:一篇|篇)(?:文章|稿子)?(?:吧|好了)?",
            text,
        )
        is not None
    )


def _explicit_refresh(text: str, context: AuthorizedRunContext) -> bool:
    if _report_anchor(context) is None and _legacy_explicit_report(context) is None:
        return False
    if any(marker in text for marker in ("不要", "不看", "换成", "换个", "和", "以及")):
        return (
            False  # A new named object must be resolved by the bounded query, not the old anchor.
        )
    return (
        any(marker in text for marker in _REPORT_REFRESH)
        and (
            any(marker in text for marker in _REPORT_REFERENCE)
            or text.startswith(("最新", "更新", "最近", "又"))
        )
    ) or any(marker in text for marker in ("这份报告更新了吗", "这个报告更新了吗"))


def _new_report_requested(text: str) -> bool:
    return any(marker in text for marker in _REPORT_REFRESH) or any(
        marker in text for marker in ("这份报告更新了吗", "这个报告更新了吗")
    )


def should_lookup_resources(request: ChatRunRequest, context: AuthorizedRunContext) -> bool:
    if request.message.attachment_asset_ids:
        return False
    text = request.message.content.strip()
    if context.resource_selection is None and is_geo_guidance_request(text):
        return False
    if context.resource_selection is None and requests_domain_execution(text):
        return False  # Mixed read + new task requests belong to the semantic router.
    if report_article_discussion_requested(request, context) or _explicit_refresh(text, context):
        return True
    if context.resource_selection is not None:
        return True
    if _ORDINAL.fullmatch(text):
        return True  # Stale ordinals are clarified; never leak into free-form catalog guessing.
    if _linked_card(context, request) is not None and (
        _named_choice(text, context.pending_resource_context) is not None
        or (any(marker in text for marker in _NEGATION) or re.search(r"第[一二三四五六1-6]", text))
    ):
        return True
    if any(marker in text for marker in ("文章", "稿子", "写一篇", "写篇")) and any(
        marker in text
        for marker in ("不要", "不用", "不写", "先不", "取消", "换个话题", "说点别的")
    ):
        return False
    if any(marker in text for marker in ("文章", "稿子")) and any(
        marker in text for marker in ("写", "起草", "生成")
    ):
        return False
    if any(marker in text for marker in _WRITE_MARKERS):
        first_write = min(text.index(marker) for marker in _WRITE_MARKERS if marker in text)
        if not any(marker in text[:first_write] for marker in _READ_MARKERS):
            return False
        # "view previously generated images" is a read. Explicit mixed task
        # commands were already handed to the semantic router above.
    if _follows_report(text, context) or any(marker in text for marker in _MONITORING_REFERENCE):
        return True
    if _report_anchor(context) is not None and any(
        marker in text for marker in _LATEST_USABLE_REFERENCE
    ):
        return True
    if any(marker in text for marker in _GEO_DISCOVERY_REFERENCE) and any(
        marker in text for marker in ("商品", "品牌", "我们", "我家", "本店")
    ):
        return True
    if any(marker in text for marker in _REPORT_REFERENCE):
        return True
    if any(marker in text for marker in ("报告", "素材", "可见度", "竞品", "趋势")):
        # A subject is not a reading verb. Explanations/method questions were
        # already excluded above; unresolved bare subjects go to semantic routing.
        return any(marker in text for marker in _READ_MARKERS)
    return any(marker in text for marker in ("商品资料", "商品信息", "经营资料", "经营空间列表"))


def _plain(value: str) -> str:
    value = html.escape(" ".join(value.split()), quote=False)
    for character in "\\`*_[]()#!|":
        value = value.replace(character, "\\" + character)
    return value


def _report_anchor(context: AuthorizedRunContext) -> ResourceReadResponse | None:
    if context.last_report_read_context is not None:
        return context.last_report_read_context
    last = context.last_read_context
    return (
        last
        if last is not None and last.purpose in {"LATEST_GEO_REPORT", "GEO_ANSWER_EVIDENCE"}
        else None
    )


def _conflicts_with_sidebar(candidate: ResourceCandidate, context: AuthorizedRunContext) -> bool:
    """A unique account match is not permission to silently change the visible scope."""
    if context.selected_space_id is not None and candidate.space_id != context.selected_space_id:
        return True
    if candidate.kind == "SPACE":
        return (
            context.selected_space_id is not None
            and candidate.resource_id != context.selected_space_id
        )
    if candidate.kind == "PRODUCT":
        return (
            context.selected_product_id is not None
            and candidate.resource_id != context.selected_product_id
        )
    # The candidate shape deliberately contains no productId for GEO_TOPIC.
    # A topic in the same space may still belong to another product: require the card.
    return context.selected_product_id is not None


class ResourceLookupRuntime:
    """Short path with strict server capabilities, one bounded model repair and no writes."""

    def __init__(
        self,
        *,
        gateway: ModelGateway,
        harness: RuntimeHarness,
        java_client: JavaResourceClientProtocol,
        lookup_prompt: str,
        geo_runtime: GeoReadOnlyRuntime,
        tool_names: tuple[str, ...],
        observer: ToolObserver | None,
    ) -> None:
        self._gateway = gateway
        self._harness = harness
        self._client = java_client
        self._prompt = lookup_prompt
        self._geo = geo_runtime
        self._tool_names = frozenset(tool_names) & _RESOURCE_TOOLS
        self._observer = observer

    @property
    def available(self) -> bool:
        return self._tool_names == _RESOURCE_TOOLS

    async def execute(
        self,
        *,
        request: ChatRunRequest,
        context: AuthorizedRunContext,
        counters: RuntimeUsageCounters,
        on_delta: DeltaHandler | None,
    ) -> ResourceLookupResult:
        usage = GatewayUsage()
        stage: Literal["LOOKUP", "PLAN", "TOOL", "SYNTHESIS", "PROTOCOL"] = "PLAN"
        try:
            linked = _linked_card(context, request)
            text = request.message.content.strip()
            ordinal = _ORDINAL.fullmatch(text)
            chosen: ResourceSelection | None = context.resource_selection
            expected_candidate: ResourceCandidate | None = None
            goal = text
            named_choice = (
                _named_choice(text, context.pending_resource_context)
                if linked is not None
                else None
            )
            if (
                linked is not None
                and ordinal is None
                and chosen is None
                and (
                    any(marker in text for marker in _NEGATION)
                    or re.search(r"第[一二三四五六1-6]", text)
                )
            ):
                return await self._clarify("请明确选择一个对象，暂时不会替你选择。", on_delta)
            if named_choice is not None and chosen is None:
                assert context.pending_resource_context is not None and linked is not None
                expected_candidate = named_choice
                chosen = ResourceSelection(
                    candidate_set_id=context.pending_resource_context.candidate_set_id,
                    choice_id=named_choice.choice_id,
                )
                goal = linked[1] or "查看该专题的监测报告"
            elif ordinal is not None and chosen is None:
                if linked is None:
                    return await self._clarify("请告诉我要查看的商品或专题名称。", on_delta)
                assert context.pending_resource_context is not None
                index = "一二三四五六".find(ordinal.group(1))
                index = int(ordinal.group(1)) - 1 if index < 0 else index
                if index >= len(context.pending_resource_context.candidates):
                    return await self._clarify("这个选项不在当前列表里，请重新选择。", on_delta)
                candidate = context.pending_resource_context.candidates[index]
                expected_candidate = candidate
                chosen = ResourceSelection(
                    candidate_set_id=context.pending_resource_context.candidate_set_id,
                    choice_id=candidate.choice_id,
                )
                goal = linked[1] or "查看该商品的监测报告"
            elif chosen is not None and linked is not None:
                goal = linked[1] or "查看该商品的监测报告"
            if chosen is not None and any(marker in text for marker in _NEGATION):
                return await self._clarify(
                    "请明确要查看哪一个商品或专题，暂时不会替你选择。", on_delta
                )
            goal_request = request.model_copy(
                update={"message": request.message.model_copy(update={"content": goal})}
            )
            discussion = report_article_discussion_requested(goal_request, context)
            legacy = (
                _legacy_explicit_report(context)
                if discussion and chosen is None and not _new_report_requested(goal)
                else None
            )
            if legacy is not None:
                stage = "SYNTHESIS"
                return await self._render_report(
                    request=goal_request,
                    context=context,
                    snapshot=legacy,
                    counters=counters,
                    usage=usage,
                    discussion=True,
                    on_delta=on_delta,
                )
            decision, usage = await self._decision(
                request, context, goal, chosen is not None, counters, discussion=discussion
            )
            counters.record_gateway_usage(usage)
            if decision.purpose == "LATEST_GEO_REPORT" and decision.reference in {
                "SEARCH",
                "SIDEBAR",
            }:
                decision = decision.model_copy(update={"kind": "GEO_TOPIC"})
            if (
                isinstance(chosen, FrozenResourceSelection)
                and chosen.kind != "GEO_TOPIC"
                and decision.purpose == "LATEST_GEO_REPORT"
            ):
                # Product selections are not report capabilities; resolve server topics by name.
                decision = decision.model_copy(
                    update={"reference": "SEARCH", "kind": "GEO_TOPIC", "keyword": chosen.name}
                )
                chosen = None
            if decision.action == "CLARIFY":
                return await self._clarify(
                    "请告诉我要查看的商品或专题名称，或者选择当前列表中的一个。", on_delta, usage
                )
            stage = "LOOKUP"

            async def search(payload: BaseModel) -> BaseModel:
                response = await self._call_java("search", request, payload)
                return ResourceSearchResponse.model_validate(response)

            async def read(payload: BaseModel) -> BaseModel:
                response = await self._call_java("read", request, payload)
                return ResourceReadResponse.model_validate(response)

            executor = ToolExecutor(
                DEFAULT_TOOL_REGISTRY,
                ToolPolicy(
                    allowed_tool_names=self._tool_names,
                    allowed_tool_groups=frozenset({ToolGroup.TASK_PREPARATION}),
                ),
                {
                    "context.resources.search": ResourceSearchRequest.model_validate,
                    "context.resources.read": ResourceReadRequest.model_validate,
                },
                {"context.resources.search": search, "context.resources.read": read},
                observer=self._observer,
            )

            async def invoke(name: str, payload: BaseModel) -> BaseModel:
                counters.before_resource_tool_call()
                counters.before_workflow_step()
                result = await executor.execute(
                    run_id=request.run_id,
                    name=name,
                    version="1",
                    payload=payload,
                    counters=counters,
                    authorized_tool_groups=context.loaded_tool_groups,
                )
                return result.output

            read_args: dict[str, object] = {"toolCallId": str(uuid4()), "purpose": decision.purpose}
            anchor = (
                _report_anchor(context)
                if decision.reference == "LAST_REPORT"
                else context.last_read_context
            )
            if chosen is not None and decision.reference != "SEARCH":
                read_args.update(candidateSetId=chosen.candidate_set_id, choiceId=chosen.choice_id)
                if decision.purpose == "LATEST_GEO_REPORT":
                    read_args["reportPolicy"] = decision.report_policy
            elif decision.reference in {"LAST_REPORT", "LAST_READ"}:
                if anchor is None:
                    return await self._clarify(
                        "本轮没有可确认的已读对象，请告诉我商品或专题名称。", on_delta, usage
                    )
                read_args["observationId"] = anchor.observation_id
                # Omit reportPolicy: an observation is a frozen report, never a request for latest.
                # A saved noReport status is not a report; explicitly asking for a usable
                # older report is the sole policy exception, still reauthorized by Java.
                if (
                    anchor.report_id is None
                    and decision.purpose == "LATEST_GEO_REPORT"
                    and decision.report_policy == "LATEST_USABLE"
                    and any(marker in goal for marker in _LATEST_USABLE_REFERENCE)
                ):
                    read_args["reportPolicy"] = "LATEST_USABLE"
            else:
                keyword = decision.keyword.strip()
                kind = decision.kind
                if decision.reference == "SIDEBAR":
                    selected = context.product if context.product is not None else context.space
                    if selected is None:
                        return await self._clarify(
                            "请告诉我要查看的商品或经营空间名称。", on_delta, usage
                        )
                    keyword = selected.name
                    kind = (
                        "GEO_TOPIC"
                        if decision.purpose == "LATEST_GEO_REPORT"
                        else "PRODUCT"
                        if context.product is not None
                        else "SPACE"
                    )
                if not keyword:
                    return await self._clarify("请告诉我要查看的商品或专题名称。", on_delta, usage)
                result = await invoke(
                    "context.resources.search",
                    ResourceSearchRequest(
                        tool_call_id=str(uuid4()),
                        kind=kind,
                        keyword=keyword,
                    ),
                )
                found = ResourceSearchResponse.model_validate(result)
                if found.kind != kind:
                    raise ResourceLookupError("RESOURCE_RESPONSE_INVALID")
                if found.status == "NOT_FOUND":
                    return await self._emit(
                        ResourceLookupResult(
                            "没有找到匹配的经营资料。请检查名称，或先在经营资料中录入。",
                            BusinessOutcome(
                                status=BusinessOutcomeStatus.NEEDS_INPUT,
                                reason_code="INPUT_REQUIRED",
                            ),
                            usage,
                        ),
                        on_delta,
                    )
                sidebar_conflict = found.status == "UNIQUE" and _conflicts_with_sidebar(
                    found.candidates[0], context
                )
                if found.status != "UNIQUE" or sidebar_conflict or discussion:
                    if not found.candidates:
                        return await self._clarify(
                            "搜索结果暂不完整，请补充更准确的名称后重试。", on_delta, usage
                        )
                    return await self._emit(
                        ResourceLookupResult(
                            "为避免混淆当前商品，请确认要临时查看的对象。"
                            if sidebar_conflict
                            else "找到了可能匹配的对象，请确认要查看的一个。"
                            if found.complete
                            else "结果尚未完整，请选择下列对象或补充名称。",
                            BusinessOutcome(
                                status=BusinessOutcomeStatus.NEEDS_INPUT,
                                reason_code="INPUT_REQUIRED",
                            ),
                            usage,
                            ResourceSelectionInteraction(
                                candidate_set_id=found.candidate_set_id, question="你要查看哪一个？"
                            ),
                        ),
                        on_delta,
                    )
                candidate: ResourceCandidate = found.candidates[0]
                expected_candidate = candidate
                read_args.update(
                    candidateSetId=found.candidate_set_id, choiceId=candidate.choice_id
                )
                if decision.purpose == "LATEST_GEO_REPORT":
                    read_args["reportPolicy"] = decision.report_policy
            stage = "TOOL"
            result = await invoke(
                "context.resources.read", ResourceReadRequest.model_validate(read_args)
            )
            observed = ResourceReadResponse.model_validate(result)
            if observed.purpose != decision.purpose:
                raise ResourceLookupError("RESOURCE_RESPONSE_INVALID")
            if expected_candidate is not None and (
                observed.kind != expected_candidate.kind
                or observed.resource_id != expected_candidate.resource_id
                or (
                    expected_candidate.space_id is not None
                    and observed.space_id != expected_candidate.space_id
                )
            ):
                raise ResourceLookupError("RESOURCE_RESPONSE_INVALID")
            if (
                anchor is not None
                and "observationId" in read_args
                and (
                    observed.kind != anchor.kind
                    or observed.resource_id != anchor.resource_id
                    or (
                        decision.reference == "LAST_REPORT"
                        and read_args.get("reportPolicy") != "LATEST_USABLE"
                        and observed.report_id != anchor.report_id
                    )
                )
            ):
                raise ResourceLookupError("RESOURCE_RESPONSE_INVALID")
            if isinstance(chosen, FrozenResourceSelection) and (
                observed.kind != chosen.kind or observed.resource_id != chosen.resource_id
            ):
                raise ResourceLookupError("RESOURCE_RESPONSE_INVALID")
            stage = "SYNTHESIS"
            if observed.purpose == "LATEST_GEO_REPORT":
                if observed.report_id is None:
                    return await self._emit(
                        ResourceLookupResult(
                            self._no_report(observed),
                            BusinessOutcome(status=BusinessOutcomeStatus.ANSWERED),
                            usage,
                        ),
                        on_delta,
                    )
                projection = dict(observed.projection)
                projection.pop("sampleRefs", None)
                stage = "PROTOCOL"
                snapshot = GeoInsightContext.model_validate(projection)
                if (
                    snapshot.status != "READY"
                    or snapshot.report is None
                    or snapshot.report.report_id != observed.report_id
                    or (
                        snapshot.report.space_id is not None
                        and snapshot.report.space_id != observed.space_id
                    )
                    or (
                        snapshot.report.product_id is not None
                        and snapshot.report.product_id != observed.product_id
                    )
                ):
                    raise ResourceLookupError("RESOURCE_RESPONSE_INVALID")
                if not discussion and any(marker in goal for marker in _EVIDENCE_REFERENCE):
                    sample_refs = self._evidence_refs(observed, goal)
                    if sample_refs:
                        stage = "TOOL"
                        evidence_result = await invoke(
                            "context.resources.read",
                            ResourceReadRequest(
                                tool_call_id=str(uuid4()),
                                purpose="GEO_ANSWER_EVIDENCE",
                                observation_id=observed.observation_id,
                                sample_refs=sample_refs,
                            ),
                        )
                        evidence = ResourceReadResponse.model_validate(evidence_result)
                        if (
                            evidence.purpose != "GEO_ANSWER_EVIDENCE"
                            or evidence.kind != "GEO_TOPIC"
                            or evidence.resource_id != observed.resource_id
                            or evidence.space_id != observed.space_id
                            or evidence.report_id != observed.report_id
                        ):
                            raise ResourceLookupError("RESOURCE_RESPONSE_INVALID")
                        stage = "PROTOCOL"
                        return await self._emit(
                            ResourceLookupResult(
                                self._render_evidence(evidence, sample_refs),
                                BusinessOutcome(status=BusinessOutcomeStatus.ANSWERED),
                                usage,
                                geo_context_used=True,
                            ),
                            on_delta,
                        )
                stage = "SYNTHESIS"
                return await self._render_report(
                    request=goal_request,
                    context=context,
                    snapshot=snapshot,
                    counters=counters,
                    usage=usage,
                    discussion=discussion,
                    on_delta=on_delta,
                )
            return await self._emit(
                ResourceLookupResult(
                    self._render_projection(observed),
                    BusinessOutcome(status=BusinessOutcomeStatus.ANSWERED),
                    usage,
                ),
                on_delta,
            )
        except (ModelGatewayError, ValidationError, ToolExecutionError, ResourceLookupError) as exc:
            if isinstance(exc, ModelGatewayError):
                usage += exc.usage
                counters.record_gateway_usage(exc.usage)
            reason: Literal[
                "READONLY_PLAN_INVALID",
                "READONLY_EVIDENCE_UNAVAILABLE",
                "READONLY_SYNTHESIS_UNAVAILABLE",
            ] = "READONLY_EVIDENCE_UNAVAILABLE"
            if stage == "PLAN":
                reason = "READONLY_PLAN_INVALID"
            elif stage == "SYNTHESIS":
                reason = "READONLY_SYNTHESIS_UNAVAILABLE"
            return await self._emit(
                ResourceLookupResult(
                    "这次暂时没能可靠读取所需资料，请稍后重试；没有开始监测、生成或投稿。",
                    BusinessOutcome(
                        status=BusinessOutcomeStatus.FAILED, reason_code=reason, failure_stage=stage
                    ),
                    usage,
                ),
                on_delta,
            )

    async def _decision(
        self,
        request: ChatRunRequest,
        context: AuthorizedRunContext,
        goal: str,
        selected: bool,
        counters: RuntimeUsageCounters,
        *,
        discussion: bool = False,
    ) -> tuple[ResourceLookupDecision, GatewayUsage]:
        anchor = _report_anchor(context)
        if _explicit_refresh(goal, context):
            legacy = _legacy_explicit_report(context)
            name = (
                anchor.name
                if anchor is not None
                else ("" if legacy is None or legacy.report is None else legacy.report.title)
            )
            return ResourceLookupDecision(
                action="READ",
                kind="GEO_TOPIC",
                keyword=name,
                purpose="LATEST_GEO_REPORT",
                reference="SEARCH",
                reportPolicy="LATEST_ATTEMPT",
            ), GatewayUsage()
        if anchor is not None and any(marker in goal for marker in _LATEST_USABLE_REFERENCE):
            return ResourceLookupDecision(
                action="READ",
                kind="GEO_TOPIC",
                keyword=anchor.name,
                purpose="LATEST_GEO_REPORT",
                reference="LAST_REPORT" if anchor.report_id is None else "SEARCH",
                reportPolicy="LATEST_USABLE",
            ), GatewayUsage()
        if (
            (_follows_report(goal, context) or discussion)
            and anchor is not None
            and not selected
            and not _new_report_requested(goal)
        ):
            return ResourceLookupDecision(
                action="READ",
                kind=anchor.kind,
                keyword="",
                purpose="LATEST_GEO_REPORT",
                reference="LAST_REPORT",
                reportPolicy="LATEST_ATTEMPT",
            ), GatewayUsage()
        if not isinstance(self._gateway, StructuredCompletionGateway):
            raise ResourceLookupError("RESOURCE_LOOKUP_UNAVAILABLE")
        # Names and booleans only; no preload of catalog lists, saved report body or arbitrary IDs.
        data = {
            "currentQuestion": goal,
            "hasSelection": selected,
            "sidebarProduct": None if context.product is None else context.product.name,
            "sidebarSpace": None if context.space is None else context.space.name,
            "lastReadName": None
            if context.last_read_context is None
            else context.last_read_context.name,
            "lastReportName": None if anchor is None else anchor.name,
        }
        messages, tokens = self._harness.fit_model_messages(
            request,
            (
                ModelMessage(role="system", content=self._prompt),
                ModelMessage(role="user", content=json.dumps(data, ensure_ascii=False)),
            ),
            target_input_tokens=request.budget.max_input_tokens,
            available_history_tokens=1,
        )
        counters.observe_estimated_input_tokens(tokens)
        parsed, usage = await self._gateway.complete_structured(
            messages,
            ResourceLookupDecision,
            operation="readonly_resource_lookup",
            max_tokens=500,
            error_code="DEEPSEEK_RESOURCE_LOOKUP_INVALID",
            repair_instruction="Return only schema fields; do not generate IDs.",
            repair_attempts=1,
        )
        decision = ResourceLookupDecision.model_validate(parsed)
        if _new_report_requested(goal):
            decision = decision.model_copy(
                update={
                    "reference": "SEARCH",
                    "kind": "GEO_TOPIC",
                    "purpose": "LATEST_GEO_REPORT",
                    "report_policy": "LATEST_ATTEMPT",
                }
            )
        if selected:
            decision = decision.model_copy(update={"reference": "SELECTION"})
        return decision, usage

    async def _render_report(
        self,
        *,
        request: ChatRunRequest,
        context: AuthorizedRunContext,
        snapshot: GeoInsightContext,
        counters: RuntimeUsageCounters,
        usage: GatewayUsage,
        discussion: bool,
        on_delta: DeltaHandler | None,
    ) -> ResourceLookupResult:
        rendered = await self._geo.execute(
            request=request,
            context=replace(context, geo_insight_context=snapshot),
            counters=counters,
            on_delta=None,
        )
        usage += rendered.usage
        business = BusinessOutcome(status=BusinessOutcomeStatus.ANSWERED)
        if rendered.fallback_code is not None:
            business = BusinessOutcome(
                status=BusinessOutcomeStatus.PARTIAL,
                reason_code="READONLY_PARTIAL_EVIDENCE",
                failure_stage="SYNTHESIS",
            )
        text = rendered.text
        if discussion and rendered.focus != "CONTENT_PLAN":
            text += "\n\n" + self._article_direction(snapshot, request.message.content)
        return await self._emit(
            ResourceLookupResult(text, business, usage, geo_context_used=True), on_delta
        )

    @staticmethod
    def _article_direction(snapshot: GeoInsightContext, goal: str) -> str:
        report = snapshot.report
        assert report is not None
        name = _plain(report.title if report.target is None else report.target.name)
        direction = (
            f"可以先围绕“{name}”写一篇选购介绍: 说明适用人群和使用场景，再介绍能核实的特点，"
            "帮助读者判断是否适合自己。没有确认的参数、价格和认证先不写。"
        )
        if any(marker in goal for marker in _DIRECTION_REFERENCE):
            direction += (
                "\n\n你的补充要求: “" + _plain(goal[:240]) + "”。这作为写作偏好，不是报告结论。"
            )
        if not report.can_create_article:
            return (
                direction
                + "\n\n目前这份报告还不能用于准备文章方案，先保留这个方向；本轮没有创建文章或投稿。"
            )
        return (
            direction
            + "\n\n你可以在下方修改方向，再准备标题方案。"
            + "核对报价并确认后才会生成文章，投稿仍需单独确认。"
            + "这只是内容方向，不代表报告证明了原因，也不承诺发文后可见度一定提升。"
        )

    async def _call_java(
        self, operation: Literal["search", "read"], request: ChatRunRequest, payload: BaseModel
    ) -> dict[str, object]:
        try:
            method = (
                self._client.search_run_resources
                if operation == "search"
                else self._client.read_run_resource
            )
            response = await method(
                request.run_id,
                request.user_context_ref,
                cast(
                    dict[str, object],
                    payload.model_dump(mode="json", by_alias=True, exclude_none=True),
                ),
            )
        except JavaInternalClientError as exc:
            code = (
                "RESOURCE_AUTHORIZATION_REJECTED"
                if exc.code is JavaInternalErrorCode.AUTHENTICATION_REJECTED
                or exc.status_code in {403, 404, 409, 410}
                else "RESOURCE_LOOKUP_UNAVAILABLE"
            )
            raise ResourceLookupError(code) from None
        return response.payload

    @staticmethod
    def _no_report(observed: ResourceReadResponse) -> str:
        prefix = f"{_plain(observed.name)}: "
        attempt = observed.report_attempt
        if attempt is None:
            return (
                prefix
                + "尚未找到已保存的监测报告。这不代表 AI 可见度为零；你可以选择查看最近可用报告。"
            )
        when = attempt.created_at.strftime("%Y/%m/%d %H:%M %z")
        status = {
            "FAILED": "失败",
            "SYSTEM_TERMINATED": "已终止",
            "QUEUED": "排队中",
            "RUNNING": "进行中",
            "RETRY_WAIT": "等待重试",
            "STOPPED": "已停止",
        }.get(attempt.status, "尚未产出报告")
        return (
            prefix
            + f"最近一次监测 ({when}) {status}，尚无可读取报告；"
            + "不会拿更早的报告充当本次结果。你可以选择查看最近可用报告。"
        )

    @staticmethod
    def _evidence_refs(observed: ResourceReadResponse, goal: str) -> tuple[str, ...]:
        metadata = observed.projection.get("sampleRefs")
        if not isinstance(metadata, list):
            return ()
        requested = {
            code
            for code, name in _PLATFORM_NAMES.items()
            if code.casefold() in goal.casefold() or name.casefold() in goal.casefold()
        }
        refs: list[str] = []
        for item in metadata[:6]:
            if not isinstance(item, dict):
                raise ResourceLookupError("RESOURCE_RESPONSE_INVALID")
            ref, platform = item.get("sampleRef"), item.get("platform")
            if not isinstance(ref, str) or not ref.strip() or len(ref) > 200 or ref in refs:
                raise ResourceLookupError("RESOURCE_RESPONSE_INVALID")
            if not requested or platform in requested:
                refs.append(ref)
        return tuple(refs[:3])

    @staticmethod
    def _render_evidence(observed: ResourceReadResponse, sample_refs: tuple[str, ...]) -> str:
        projection = observed.projection
        samples = projection.get("samples")
        if (
            projection.get("readOnly") is not True
            or projection.get("status") != "READY"
            or projection.get("reportId") != observed.report_id
            or not isinstance(samples, list)
            or len(samples) > 3
        ):
            raise ResourceLookupError("RESOURCE_RESPONSE_INVALID")
        seen: set[str] = set()
        lines = [f"{_plain(observed.name)} · 已保存的回答与来源"]
        for item in samples:
            if not isinstance(item, dict):
                raise ResourceLookupError("RESOURCE_RESPONSE_INVALID")
            ref = item.get("sampleRef")
            if not isinstance(ref, str) or ref not in sample_refs or ref in seen:
                raise ResourceLookupError("RESOURCE_RESPONSE_INVALID")
            seen.add(ref)
            question, platform, status = (
                item.get("question"),
                item.get("platform"),
                item.get("status"),
            )
            if not all(isinstance(value, str) for value in (question, platform, status)):
                raise ResourceLookupError("RESOURCE_RESPONSE_INVALID")
            readable_platform = _plain(_PLATFORM_NAMES.get(str(platform), str(platform)))
            lines.append(f"{readable_platform} · {_plain(str(question))}")
            answer = item.get("answer")
            if isinstance(answer, str) and answer.strip():
                lines.append(_plain(answer))
            else:
                lines.append("这个样本没有可查看的已保存回答，不能按未提及计入。")
            citations = item.get("citations")
            if not isinstance(citations, list) or len(citations) > 6:
                raise ResourceLookupError("RESOURCE_RESPONSE_INVALID")
            for citation in citations:
                if not isinstance(citation, dict):
                    raise ResourceLookupError("RESOURCE_RESPONSE_INVALID")
                title, domain = citation.get("title"), citation.get("domain")
                source = [
                    value for value in (title, domain) if isinstance(value, str) and value.strip()
                ]
                if source:
                    lines.append("- 来源: " + " · ".join(_plain(str(value)) for value in source))
            if not citations:
                lines.append("这条回答未返回可核验的引用来源。")
        if seen != set(sample_refs):
            raise ResourceLookupError("RESOURCE_RESPONSE_INVALID")
        lines.append("以上为这份报告中的部分已保存回答，不代表全部样本或当前实时回答。")
        if observed.truncated:
            lines.append("部分内容经过长度截取。")
        return "\n\n".join(lines)

    @staticmethod
    def _render_projection(observed: ResourceReadResponse) -> str:
        # Never render bounded projections as raw JSON, IDs or executable Markdown.
        lines = [
            f"{_plain(observed.name)} · "
            + ("已保存素材" if observed.purpose == "ASSET_INVENTORY" else "经营资料")
        ]
        labels = {
            "description": "介绍",
            "category": "分类",
            "sellingPoints": "卖点",
            "specifications": "规格",
            "audience": "适用人群",
            "name": "名称",
            "type": "类型",
            "status": "状态",
            "count": "数量",
            "total": "数量",
            "assetType": "素材类型",
        }

        def add(data: dict[str, JsonValue]) -> None:
            for key, label in labels.items():
                value = data.get(key)
                if isinstance(value, str) and value.strip():
                    lines.append(f"- {label}: {_plain(value[:1000])}")
                elif isinstance(value, int) and not isinstance(value, bool):
                    lines.append(f"- {label}: {value}")
                elif isinstance(value, list) and all(isinstance(item, str) for item in value):
                    lines.append(
                        f"- {label}: " + "；".join(_plain(str(item)[:200]) for item in value[:8])
                    )

        add(observed.projection)
        for key in ("product", "space", "brand"):
            nested = observed.projection.get(key)
            if isinstance(nested, dict):
                add(nested)
        for key in ("assets", "items"):
            items = observed.projection.get(key)
            if isinstance(items, list):
                for item in items[:6]:
                    if isinstance(item, dict):
                        add(item)
        if len(lines) == 1:
            lines.append("当前返回的资料中暂无可展示的内容。")
        if observed.purpose == "ASSET_INVENTORY":
            lines.append("这里只列出已保存素材的信息，未读取图片内容或开始创作。")
        if observed.truncated:
            lines.append("本次只展示部分资料，不代表全部记录。")
        return "\n\n".join(lines)

    @staticmethod
    async def _emit(
        result: ResourceLookupResult, on_delta: DeltaHandler | None
    ) -> ResourceLookupResult:
        if on_delta is not None:
            await on_delta(result.text)
        return result

    async def _clarify(
        self, text: str, on_delta: DeltaHandler | None, usage: GatewayUsage | None = None
    ) -> ResourceLookupResult:
        return await self._emit(
            ResourceLookupResult(
                text,
                BusinessOutcome(
                    status=BusinessOutcomeStatus.NEEDS_INPUT, reason_code="INPUT_REQUIRED"
                ),
                usage or GatewayUsage(),
                InteractionProposal(
                    intent=Intent.GENERAL, question=text, missing_fields=("resource",)
                ),
            ),
            on_delta,
        )

"""Framework-free legacy adapter for historical proposal callers.

Production chat uses the native loop or ExplicitActionAdapter, never this router.
"""

import asyncio
import logging
import re
from collections.abc import Awaitable, Callable
from dataclasses import replace
from typing import TypedDict, cast

from pydantic import JsonValue

from app.agent.general_response_review import (
    build_general_response_review,
    general_review_fallback,
    review_general_response,
)
from app.agent.geo_readonly import GeoReadOnlyRuntime
from app.agent.graphs.domains import (
    DOMAIN_DEFINITIONS,
    DomainState,
    build_domain_graphs,
    prepare_task_draft,
    prepare_task_draft_input,
    requests_product_visibility,
)
from app.agent.identity import (
    AgentIdentityError,
    require_capability,
    require_preparation_capability,
)
from app.agent.models import (
    BusinessOutcome,
    BusinessOutcomeStatus,
    ChatRunRequest,
    DraftOperation,
    Intent,
    InteractionProposal,
    RunOutcome,
    RunProgressStage,
    RunProgressStatus,
    Usage,
)
from app.agent.multistep import ReadOnlyMultistepRuntime, should_use_readonly_planner
from app.agent.progress import RunProgressHandler, notify_progress
from app.agent.resources import ResourceLookupRuntime, should_lookup_resources
from app.agent.workflow_plan import (
    WorkflowPlanError,
    WorkflowPlanProducer,
    render_workflow_plan,
    should_propose_workflow,
    workflow_delivery_intents,
)
from app.harness.runtime import (
    HarnessViolation,
    RuntimeHarness,
    RuntimeUsageCounters,
    bind_runtime_usage,
)
from app.integration.java_internal import (
    JavaResourceClientProtocol,
    JavaWorkflowPlanClientProtocol,
    UnavailableJavaInternalClient,
)
from app.model_gateway.gateway import (
    GENERAL_RESPONSE_INSTRUCTION,
    ClarificationReason,
    DeltaHandler,
    GatewayUsage,
    IntentRoute,
    LocalModelGateway,
    ModelGateway,
    ModelGatewayError,
    ModelMessages,
    RouterClarification,
    RouterComplexity,
    RouterContextPath,
    RouterDecision,
    default_intent_route,
    deterministic_intent,
    deterministic_intent_route,
    intent_is_explicitly_negated,
    is_geo_guidance_request,
    is_geo_status_consultation,
)
from app.model_gateway.router import (
    GatewayIntentRouterProvider,
    IntentRouter,
    build_intent_router_envelope,
    router_model_messages,
)
from app.observability.trace import current_trace_id
from app.observability.tracing import start_span
from app.prompts.assembly import CommercePromptSet
from app.providers.java_context import (
    AuthorizedRunContext,
    RunContextProvider,
    TaskWorkspace,
    general_response_fact_scope,
    model_messages_with_context,
)
from app.tools.registry import (
    DEFAULT_TOOL_REGISTRY,
    ToolExecutor,
    ToolGroup,
    ToolObserver,
    ToolPolicy,
)


class CommerceState(TypedDict):
    request: ChatRunRequest
    authorized_context: AuthorizedRunContext | None
    normalized_message: str
    intent: Intent
    intent_route: IntentRoute
    resource_lookup: bool
    outcome: RunOutcome | None
    estimated_input_tokens: int
    counters: RuntimeUsageCounters
    gateway_usage: GatewayUsage
    on_delta: DeltaHandler | None
    on_progress: RunProgressHandler | None
    on_context: Callable[[AuthorizedRunContext], None] | None


DomainNode = Callable[[CommerceState], Awaitable[dict[str, object]]]

_BARE_CONFIRMATION_PHRASES = frozenset(
    phrase + suffix for phrase in ("确认", "确定", "开始", "执行") for suffix in ("", "一下", "吧")
) | frozenset(("confirm", "go"))
_IGNORABLE_CONFIRMATION_PUNCTUATION = frozenset(
    "\uff0c\u3002\uff01\uff1f\u3001\uff1b\uff1a,.!?;:\u2026~\uff5e"
)
_WORKSPACE_EXACT_CONTINUATION_COMMANDS = frozenset(
    (
        "继续",
        "继续吧",
        "继续当前任务",
        "继续刚才那个",
        "继续上一个",
        "继续之前",
        "下一步",
        "下一步吧",
        "沿用",
    )
)
_WORKSPACE_EXPLICIT_ACTION_MARKERS = (
    "商品居中",
    "背景改",
    "背景换",
    "继续文章",
    "继续写",
    "回到文章",
    "回到标题",
    "换一批标题",
    "重新生成标题",
    "重做创作方案",
    "修改创作方案",
    "重做提纲",
    "修改提纲",
    "推荐媒体",
    "投稿",
)
_WORKSPACE_OPTION_REVISION = re.compile(
    r"(?:第[一二三四五]|第[1-5]|[1-5])(?:个|项)?[^。\uff01\uff1f!?]{0,40}"
    r"(?:改成|改为|换成|调整)"
)
_ARTICLE_CONTINUATION_MARKERS = (
    "继续",
    "下一步",
    "标题",
    "提纲",
    "正文",
    "推荐媒体",
    "投稿",
)
_ARTICLE_NEW_REQUEST_MARKERS = (
    "新文章",
    "新的一篇",
    "另一篇",
    "另写一篇",
    "另起一篇",
    "重新写一篇",
    "再写一篇",
)
_ARTICLE_START_NEW_CHOICE = "开始新文章"
_ARTICLE_CONTINUE_CHOICE = "继续当前文章"
_GENERAL_RECALL_MARKERS = (
    "是什么",
    "是哪一个",
    "哪个商品",
    "叫什么",
    "复述",
    "回忆",
    "解释",
    "为什么",
    "怎么",
    "如何",
    "讲讲",
    "介绍",
)


_PENDING_CHOICE_REPLY = re.compile(
    r"^\s*(?:选(?:择)?\s*)?(?P<choices>[1-9](?:\s*(?:[,，、和及+]|or|and)\s*[1-9])*)"
    r"\s*(?:项|个)?\s*$",
    re.IGNORECASE,
)
_PENDING_CHOICE_ORDINALS = {
    "第一个": 1,
    "第一项": 1,
    "第二个": 2,
    "第二项": 2,
    "第三个": 3,
    "第三项": 3,
    "第四个": 4,
    "第四项": 4,
    "第五个": 5,
    "第五项": 5,
}
_PENDING_CANCEL_MARKERS = ("取消", "不用了", "先不", "停止", "换个话题")
logger = logging.getLogger(__name__)


def _is_bare_confirmation(content: str) -> bool:
    normalized = "".join(
        character
        for character in content.casefold()
        if not character.isspace() and character not in _IGNORABLE_CONFIRMATION_PUNCTUATION
    )
    return normalized in _BARE_CONFIRMATION_PHRASES


def _compact_command(content: str) -> str:
    return "".join(
        character
        for character in content.casefold()
        if not character.isspace() and character not in _IGNORABLE_CONFIRMATION_PUNCTUATION
    )


class CommerceAssistantGraph:
    """Route each Run into a fresh, bounded general or domain execution path."""

    def __init__(
        self,
        context_provider: RunContextProvider,
        gateway: ModelGateway | None = None,
        system_prompt: str = "You are the bounded intelligence layer for Guanghe.",
        intent_prompt: str = "Return a JSON intent for the commerce request.",
        task_prompt: str = "Return proposal-only task parameters as JSON.",
        prompt_set: CommercePromptSet | None = None,
        harness: RuntimeHarness | None = None,
        tool_names: tuple[str, ...] = ("task_draft.propose",),
        tool_observer: ToolObserver | None = None,
        intent_router: IntentRouter | None = None,
        java_client: JavaWorkflowPlanClientProtocol | None = None,
        workflow_plan_max_point_cost: int = 1_000,
        workflow_plan_deadline_seconds: int = 86_400,
        resource_lookup_enabled: bool = False,
    ) -> None:
        self._gateway = gateway or LocalModelGateway()
        self._intent_router = intent_router or GatewayIntentRouterProvider(self._gateway)
        self._prompts = prompt_set or CommercePromptSet.legacy(
            system_prompt=system_prompt,
            intent_prompt=intent_prompt,
            task_prompt=task_prompt,
        )
        self._harness = harness or RuntimeHarness()
        self._context_provider = context_provider
        self._geo_readonly = GeoReadOnlyRuntime(
            gateway=self._gateway,
            harness=self._harness,
            selector_prompt=self._prompts.geo_readonly_selector,
        )
        self._resource_lookup_enabled = resource_lookup_enabled
        self._resources = ResourceLookupRuntime(
            gateway=self._gateway,
            harness=self._harness,
            java_client=cast(
                JavaResourceClientProtocol, java_client or UnavailableJavaInternalClient()
            ),
            lookup_prompt=self._prompts.resource_lookup,
            geo_runtime=self._geo_readonly,
            tool_names=tool_names,
            observer=tool_observer,
        )
        definitions = {
            definition.name: definition for definition in DEFAULT_TOOL_REGISTRY.definitions
        }
        domain_tool_names = frozenset(tool_names) & {"task_draft.propose"}
        allowed_groups = frozenset(
            definitions[name].tool_group for name in domain_tool_names if name in definitions
        )
        tool_executor = ToolExecutor(
            DEFAULT_TOOL_REGISTRY,
            ToolPolicy(
                allowed_tool_names=domain_tool_names,
                allowed_tool_groups=allowed_groups or frozenset(ToolGroup),
            ),
            {"task_draft.propose": prepare_task_draft_input},
            {"task_draft.propose": prepare_task_draft},
            observer=tool_observer,
        )
        self._domains = build_domain_graphs(
            gateway=self._gateway,
            prompts=self._prompts,
            harness=self._harness,
            tool_executor=tool_executor,
        )
        self._multistep = ReadOnlyMultistepRuntime(
            gateway=self._gateway,
            prompts=self._prompts,
            harness=self._harness,
            tool_names=tool_names,
            tool_observer=tool_observer,
        )
        self._workflow_plan = WorkflowPlanProducer(
            gateway=self._gateway,
            prompts=self._prompts,
            java_client=java_client or UnavailableJavaInternalClient(),
            harness=self._harness,
            tool_names=tool_names,
            max_point_cost=workflow_plan_max_point_cost,
            deadline_seconds=workflow_plan_deadline_seconds,
            observer=tool_observer,
        )

    @property
    def model_identity(self) -> str:
        identity = self._gateway.identity
        return f"{identity.provider}/{identity.model}:{identity.version}"

    @property
    def is_mock(self) -> bool:
        return self._gateway.is_mock

    @property
    def domain_graph_codes(self) -> tuple[str, ...]:
        return tuple(definition.graph_code for definition in DOMAIN_DEFINITIONS.values())

    @staticmethod
    def graph_for_intent(intent: Intent) -> str:
        definition = DOMAIN_DEFINITIONS.get(intent)
        if definition is not None:
            return definition.graph_code
        if intent is Intent.AMBIGUOUS_CONFIRMATION:
            return "commerce-confirmation-v1"
        return "commerce-general-v1"

    async def ainvoke(
        self,
        request: ChatRunRequest,
        *,
        on_delta: DeltaHandler | None = None,
        on_progress: RunProgressHandler | None = None,
        on_context: Callable[[AuthorizedRunContext], None] | None = None,
        authorized_context: AuthorizedRunContext | None = None,
        usage_counters: RuntimeUsageCounters | None = None,
    ) -> RunOutcome:
        estimated_tokens = self._harness.validate_request(request)
        counters = usage_counters or self._harness.new_usage_counters(request)
        counters.observe_estimated_input_tokens(estimated_tokens)
        initial = CommerceState(
            request=request,
            authorized_context=authorized_context,
            normalized_message="",
            intent=Intent.GENERAL,
            intent_route=default_intent_route(Intent.GENERAL),
            resource_lookup=False,
            outcome=None,
            estimated_input_tokens=estimated_tokens,
            counters=counters,
            gateway_usage=GatewayUsage(),
            on_delta=on_delta,
            on_progress=on_progress,
            on_context=on_context,
        )
        try:
            with bind_runtime_usage(counters):
                async with self._harness.enforce_deadline(request):
                    final_state = initial
                    for step in (self._normalize, self._load_context, self._route):
                        final_state.update(await step(final_state))  # type: ignore[typeddict-item]
                    routes: dict[str, DomainNode] = {
                        "commerce-general-v1": self._compose_general,
                        "commerce-resources-v1": self._compose_resources,
                        "commerce-full-article-receipt-v1": self._compose_full_article_receipt,
                        "commerce-geo-readonly-v1": self._compose_geo_readonly,
                        "commerce-confirmation-v1": self._compose_ambiguous,
                        "commerce-readonly-multistep-v1": self._compose_readonly_multistep,
                        "commerce-workflow-plan-v1": self._compose_workflow_plan,
                        "commerce-router-clarification-v1": self._compose_router_clarification,
                        **{
                            definition.graph_code: self._domain_node(intent)
                            for intent, definition in DOMAIN_DEFINITIONS.items()
                        },
                    }
                    final_state = cast(
                        CommerceState,
                        {
                            **final_state,
                            **await routes[self._select_route(final_state)](final_state),
                        },
                    )
                    final_state.update(self._validate(final_state))  # type: ignore[typeddict-item]
        except (Exception, asyncio.CancelledError) as exc:
            if isinstance(exc, ModelGatewayError):
                counters.record_gateway_usage(exc.usage)
            exc.usage = counters.usage(  # type: ignore[attr-defined]
                provider=self._gateway.identity.provider,
                model=self._gateway.identity.model,
            )
            raise
        outcome = final_state["outcome"]
        if outcome is None:
            raise RuntimeError("Agent graph completed without an outcome")
        if outcome.business_outcome is None:
            business_outcome = (
                BusinessOutcome(
                    status=BusinessOutcomeStatus.NEEDS_INPUT,
                    reason_code=(
                        "TASK_DRAFT_PROPOSED"
                        if outcome.task_draft is not None
                        else "INPUT_REQUIRED"
                    ),
                )
                if outcome.interaction is not None or outcome.task_draft is not None
                else BusinessOutcome(status=BusinessOutcomeStatus.ANSWERED)
            )
            outcome = outcome.model_copy(update={"business_outcome": business_outcome})
        return outcome

    async def _load_context(self, state: CommerceState) -> dict[str, object]:
        state["counters"].before_workflow_step()
        stage = self._context_progress_stage(state["request"])
        if stage is not None:
            await notify_progress(
                state["on_progress"],
                stage,
                RunProgressStatus.RUNNING,
            )
        reused = state["authorized_context"] is not None
        with start_span(
            "agent.context.reuse" if reused else "agent.context.load",
            attributes={
                "context.loadCount": state["counters"].context_load_count + (0 if reused else 1)
            },
        ) as span:
            try:
                context = state["authorized_context"]
                if context is None:
                    context = await self._context_provider.load(state["request"])
            except Exception as exc:
                span.fail(getattr(exc, "code", "AUTHORIZED_CONTEXT_LOAD_FAILED"))
                if stage is not None:
                    await notify_progress(
                        state["on_progress"],
                        stage,
                        RunProgressStatus.FAILED,
                    )
                raise
            span.set("context.source", context.source)
        if state["on_context"] is not None:
            state["on_context"](context)
        if stage is not None:
            await notify_progress(
                state["on_progress"],
                stage,
                RunProgressStatus.SUCCEEDED,
            )
        return {"authorized_context": context}

    @staticmethod
    async def _normalize(state: CommerceState) -> dict[str, object]:
        state["counters"].before_workflow_step()
        await notify_progress(
            state["on_progress"],
            RunProgressStage.UNDERSTANDING_REQUEST,
            RunProgressStatus.RUNNING,
        )
        normalized = " ".join(state["request"].message.content.split())
        await notify_progress(
            state["on_progress"],
            RunProgressStage.UNDERSTANDING_REQUEST,
            RunProgressStatus.SUCCEEDED,
        )
        return {"normalized_message": normalized}

    async def _route(self, state: CommerceState) -> dict[str, object]:
        await notify_progress(
            state["on_progress"],
            RunProgressStage.ROUTING_INTENT,
            RunProgressStatus.RUNNING,
        )
        try:
            result = await self._route_inner(state)
            route = result.get("intent_route")
            if (
                isinstance(route, IntentRoute)
                and route.decision is RouterDecision.PREPARE
                and route.primary_intent in DOMAIN_DEFINITIONS
            ):
                require_preparation_capability(route.primary_intent.value)
        except Exception:
            await notify_progress(
                state["on_progress"],
                RunProgressStage.ROUTING_INTENT,
                RunProgressStatus.FAILED,
            )
            raise
        await notify_progress(
            state["on_progress"],
            RunProgressStage.ROUTING_INTENT,
            RunProgressStatus.SUCCEEDED,
        )
        return result

    async def _route_inner(self, state: CommerceState) -> dict[str, object]:
        state["counters"].before_workflow_step()
        context = state["authorized_context"]
        if context is not None and context.article_preparation_mode == "ARTICLE_FULL_V1":
            # This is a current Java-persisted request mode, never a grant or a task proposal.
            # It cannot flow into the old TITLE draft, model routing or workflow expansion.
            route = default_intent_route(Intent.ARTICLE).model_copy(
                update={"decision": RouterDecision.ANSWER, "draft_operation": None}
            )
            return {"intent": Intent.ARTICLE, "intent_route": route, "resource_lookup": False}
        if context is not None and context.monitor_action_context is not None:
            # The Java snapshot supplies the current explicit selection. Models,
            # historical pending flows and free-text workflow hints cannot expand it.
            route = default_intent_route(Intent.GEO)
            return {"intent": Intent.GEO, "intent_route": route, "resource_lookup": False}
        if (
            context is not None
            and context.resource_selection is None
            and is_geo_guidance_request(state["normalized_message"])
        ):
            # Learning/method discussion needs neither a report nor a selected
            # product. Do this before resource lookup and pending-flow routing.
            route = default_intent_route(Intent.GENERAL)
            return {"intent": route.primary_intent, "intent_route": route}
        if (
            self._resource_lookup_enabled
            and self._resources.available
            and context is not None
            and context.protocol_features is not None
            and context.protocol_features.resource_lookup
            and should_lookup_resources(state["request"], context)
        ):
            return {
                "intent": Intent.GENERAL,
                "intent_route": default_intent_route(Intent.GENERAL),
                "resource_lookup": True,
            }
        if is_geo_status_consultation(state["normalized_message"]):
            # A real report reference keeps its authorized evidence path. Neither
            # an old pending draft nor a mistaken model route may turn a question
            # about completion/failed samples into another monitoring proposal.
            route = default_intent_route(Intent.GEO).model_copy(
                update={"decision": RouterDecision.ANSWER, "draft_operation": None}
            )
            return {"intent": route.primary_intent, "intent_route": route}
        workspace = None if context is None else context.task_workspace
        has_focused_geo_draft = bool(
            workspace is not None
            and workspace.current_intent == "GEO"
            and workspace.active_draft_id is not None
        )
        if (
            context is not None
            and not has_focused_geo_draft
            and requests_product_visibility(state["normalized_message"])
        ):
            geo = context.geo_insight_context
            can_prepare_first_monitor = bool(
                geo is not None
                and geo.status == "NO_REPORT"
                and not geo.topic_choices
                and context.product is not None
                and context.product.id == context.selected_product_id
                and context.selected_space_id == context.product.space_id
            )
            route = default_intent_route(Intent.GEO)
            if not can_prepare_first_monitor:
                route = route.model_copy(
                    update={"decision": RouterDecision.ANSWER, "draft_operation": None}
                )
            return {"intent": route.primary_intent, "intent_route": route}
        pending_continuation = self._resolve_pending_interaction(state)
        if pending_continuation is not None:
            intent, resolved_message = pending_continuation
            route = default_intent_route(intent)
            request = state["request"].model_copy(
                update={
                    "message": state["request"].message.model_copy(
                        update={"content": resolved_message}
                    )
                }
            )
            logger.info(
                "pending_interaction_resolved",
                extra={
                    "traceId": current_trace_id(),
                    "resolvedIntent": intent.value,
                },
            )
            return {
                "request": request,
                "normalized_message": resolved_message,
                "intent": intent,
                "intent_route": route,
            }
        if _is_bare_confirmation(state["normalized_message"]):
            route = default_intent_route(Intent.AMBIGUOUS_CONFIRMATION)
            return {"intent": route.primary_intent, "intent_route": route}
        context = state["authorized_context"]
        if context is None:
            raise RuntimeError("Authorized Run context was not loaded")
        router_prompt = self._prompts.for_intent_classifier()
        envelope = build_intent_router_envelope(context, state["request"])
        router_messages = router_model_messages(router_prompt, envelope)
        budget = context.context_budget
        _, estimated_tokens = self._harness.fit_model_messages(
            state["request"],
            router_messages,
            target_input_tokens=(
                state["request"].budget.max_input_tokens
                if budget is None
                else budget.target_input_tokens
            ),
            available_history_tokens=(
                state["request"].budget.max_input_tokens
                if budget is None
                else budget.available_history_tokens
            ),
        )
        state["counters"].observe_estimated_input_tokens(estimated_tokens)
        try:
            with start_span(
                "agent.complexity.classify",
                attributes={
                    "message.count": len(router_messages),
                    "estimatedInputTokens": estimated_tokens,
                },
            ) as span:
                try:
                    result = await self._intent_router.classify(
                        envelope,
                        system_prompt=router_prompt,
                    )
                except ModelGatewayError as exc:
                    span.fail(exc.code)
                    raise
                span.set("classifiedIntent", result.intent.value)
                span.set("router.decision", result.route.decision.value)
                span.set("router.complexity", result.route.complexity.value)
                span.set("router.fallbackRequired", result.route.fallback_required)
        except ModelGatewayError as exc:
            if (
                exc.code != "DEEPSEEK_INTENT_RESPONSE_INVALID"
                or not exc.structured_response_failure
            ):
                raise
            state["counters"].record_gateway_usage(exc.usage)
            if context.geo_insight_context is not None:
                # A failed classifier cannot route bound report evidence into free prose.
                route = default_intent_route(Intent.GENERAL).model_copy(
                    update={
                        "decision": RouterDecision.CLARIFY,
                        "requires_clarification": True,
                        "fallback_required": True,
                        "clarification": RouterClarification(
                            reason=ClarificationReason.AMBIGUOUS_REQUEST,
                            target=None,
                            question=(
                                "这次未能确定你想查看的内容，请说明是可见度、趋势还是竞品证据。"
                                f"也可[打开监测页面]({context.geo_insight_context.links.overview_path})查看。"
                            ),
                        ),
                    }
                )
                return {
                    "intent": route.primary_intent,
                    "intent_route": route,
                    "gateway_usage": state["gateway_usage"] + exc.usage,
                    "estimated_input_tokens": max(
                        state["estimated_input_tokens"], estimated_tokens
                    ),
                }
            route, fallback_type = self._fallback_route(state)
            logger.warning(
                "intent_classification_fallback",
                extra={
                    "traceId": current_trace_id(),
                    "failureStage": exc.failure_stage,
                    "attemptCount": exc.attempt_count,
                    "fallbackType": fallback_type,
                },
            )
            return {
                "intent": route.primary_intent,
                "intent_route": route,
                "gateway_usage": state["gateway_usage"] + exc.usage,
                "estimated_input_tokens": max(
                    state["estimated_input_tokens"],
                    estimated_tokens,
                ),
            }
        state["counters"].record_gateway_usage(result.usage)
        route = self._reconcile_authorized_context(state, result.route)
        if route.primary_intent is Intent.AMBIGUOUS_CONFIRMATION:
            # A malformed semantic decision is not authorization to prepare a draft.
            # Ask for the current goal; neither keywords nor old workspace state may
            # promote an uncertain request to an action.
            route, fallback_type = self._fallback_route(state)
            logger.info(
                "non_bare_confirmation_override",
                extra={
                    "traceId": current_trace_id(),
                    "fallbackType": fallback_type,
                },
            )
        elif (
            route.decision is RouterDecision.PREPARE
            and route.primary_intent in DOMAIN_DEFINITIONS
            and intent_is_explicitly_negated(state["normalized_message"], route.primary_intent)
        ):
            logger.info(
                "intent_domain_negation_override",
                extra={
                    "traceId": current_trace_id(),
                    "classifiedIntent": route.primary_intent.value,
                },
            )
            route = deterministic_intent_route(state["normalized_message"]).model_copy(
                update={
                    "primary_intent": Intent.GENERAL,
                    "decision": RouterDecision.ANALYZE,
                    "complexity": RouterComplexity.STANDARD,
                    "requires_clarification": False,
                    "fallback_required": False,
                    "clarification": None,
                    "draft_operation": None,
                }
            )
        elif route.decision is RouterDecision.PREPARE:
            intent = self._workspace_intent(state, route.primary_intent)
            if intent is not route.primary_intent:
                route = default_intent_route(intent)
        if route.draft_operation is DraftOperation.REVISE:
            workspace = context.task_workspace
            missing_attachment = bool(
                workspace is not None
                and workspace.confirmed_constraints.get("sourceMode") == "CURRENT_ATTACHMENTS"
                and not state["request"].message.attachment_asset_ids
            )
            if missing_attachment or (
                workspace is None
                or workspace.active_draft_id is None
                or workspace.current_intent != route.primary_intent.value
            ):
                # Never silently convert a missing/confirmed/other-domain
                # revision target into a new draft or choose a historical ID.
                route = route.model_copy(
                    update={
                        "decision": RouterDecision.CLARIFY,
                        "requires_clarification": True,
                        "draft_operation": None,
                        "clarification": RouterClarification(
                            reason=ClarificationReason.MISSING_CONTEXT,
                            target=(
                                RouterContextPath.REFERENCE_ASSET_IDS
                                if missing_attachment
                                else RouterContextPath.ACTIVE_DRAFT_ID
                            ),
                            question=None,
                        ),
                        "context_needs": (
                            RouterContextPath.REFERENCE_ASSET_IDS
                            if missing_attachment
                            else RouterContextPath.ACTIVE_DRAFT_ID,
                        ),
                    }
                )
        logger.info(
            "router_action_resolved",
            extra={
                "classifiedIntent": route.primary_intent.value,
                "draftOperation": None
                if route.draft_operation is None
                else route.draft_operation.value,
                "clarificationReason": None
                if route.clarification is None
                else route.clarification.reason.value,
                "clarificationTarget": None
                if route.clarification is None or route.clarification.target is None
                else route.clarification.target.value,
                "revisionTargetPresent": bool(
                    context.task_workspace and context.task_workspace.active_draft_id
                ),
                "revisionTargetFromAuthorizedContext": route.draft_operation
                is DraftOperation.REVISE,
            },
        )
        return {
            "intent": route.primary_intent,
            "intent_route": route,
            "gateway_usage": state["gateway_usage"] + result.usage,
            "estimated_input_tokens": max(
                state["estimated_input_tokens"],
                estimated_tokens,
            ),
        }

    @staticmethod
    def _resolve_pending_interaction(state: CommerceState) -> tuple[Intent, str] | None:
        """Continue only from Java-authorized structured interaction state."""
        context = state["authorized_context"]
        workspace = None if context is None else context.task_workspace
        pending = None if workspace is None else workspace.pending_interaction
        if (
            context is None
            or pending is None
            or state["request"].trusted_state.pending_interaction_id != pending.message_id
        ):
            return None
        try:
            intent = Intent(pending.intent)
        except ValueError:
            return None
        if intent not in DOMAIN_DEFINITIONS:
            return None
        content = state["normalized_message"]
        if any(marker in content.casefold() for marker in _PENDING_CANCEL_MARKERS):
            return None
        explicit = deterministic_intent(content)
        if explicit in DOMAIN_DEFINITIONS and explicit is not intent:
            return None
        # Free-form answers and unrelated chat must go through the semantic
        # router.  Only an unambiguous structured choice is consumed here;
        # otherwise a persisted clarification used to hijack every later turn.
        if not pending.choices:
            return None
        normalized = _compact_command(content)
        exact = next(
            (choice for choice in pending.choices if _compact_command(choice) == normalized),
            None,
        )
        if exact is not None:
            return intent, exact
        selected_indexes: tuple[int, ...] = ()
        match = _PENDING_CHOICE_REPLY.fullmatch(content)
        if match is not None:
            selected_indexes = tuple(
                int(value) for value in re.findall(r"[1-9]", match.group("choices"))
            )
        elif normalized in _PENDING_CHOICE_ORDINALS:
            selected_indexes = (_PENDING_CHOICE_ORDINALS[normalized],)
        if not selected_indexes or any(index > len(pending.choices) for index in selected_indexes):
            return None
        return intent, "；".join(pending.choices[index - 1] for index in selected_indexes)

    @staticmethod
    def _reconcile_authorized_context(
        state: CommerceState,
        route: IntentRoute,
    ) -> IntentRoute:
        """Remove context needs contradicted by the authoritative Java snapshot."""

        context = state["authorized_context"]
        if context is None or not route.context_needs:
            return route
        workspace = context.task_workspace
        available = {
            RouterContextPath.SPACE: context.selected_space_id is not None,
            RouterContextPath.PRODUCT: context.selected_product_id is not None,
            RouterContextPath.COMPETITORS: bool(context.competitors),
            RouterContextPath.CONVERSATION_MEMORY: context.conversation_memory is not None,
            RouterContextPath.TASK_WORKSPACE: workspace is not None,
            RouterContextPath.PENDING_INTERACTION: (
                workspace is not None and workspace.pending_interaction is not None
            ),
            RouterContextPath.ACTIVE_DRAFT_ID: (
                workspace is not None and workspace.active_draft_id is not None
            ),
            RouterContextPath.ACTIVE_TASK_ID: (
                workspace is not None and workspace.active_task_id is not None
            ),
            RouterContextPath.REFERENCE_ASSET_IDS: bool(context.attachment_asset_ids),
            RouterContextPath.RUNNING_TASKS: (
                workspace is not None and bool(workspace.running_tasks)
            ),
        }
        mismatch_target = (
            route.clarification.target
            if route.clarification is not None
            and route.clarification.reason is ClarificationReason.OBJECT_MISMATCH
            else None
        )
        remaining = tuple(
            path for path in route.context_needs if not available[path] or path is mismatch_target
        )
        if remaining == route.context_needs:
            return route
        logger.info(
            "router_context_needs_reconciled",
            extra={
                "traceId": current_trace_id(),
                "classifiedIntent": route.primary_intent.value,
                "removedContextNeeds": [
                    path.value for path in route.context_needs if path not in remaining
                ],
            },
        )
        # Removing a redundant object question does not resolve ambiguity about the
        # user's desired action. In particular CLARIFY must never become PREPARE.
        return route.model_copy(update={"context_needs": remaining})

    @staticmethod
    def _context_progress_stage(request: ChatRunRequest) -> RunProgressStage | None:
        if request.message.attachment_asset_ids:
            return RunProgressStage.LOADING_REFERENCE_ASSETS
        if request.trusted_state.selected_product_id is not None:
            return RunProgressStage.LOADING_PRODUCT_CONTEXT
        if request.trusted_state.selected_space_id is not None:
            return RunProgressStage.LOADING_BUSINESS_CONTEXT
        return None

    def _select_route(self, state: CommerceState) -> str:
        context = state["authorized_context"]
        if context is not None and context.article_preparation_mode == "ARTICLE_FULL_V1":
            return "commerce-full-article-receipt-v1"
        if context is not None and context.monitor_action_context is not None:
            return self.graph_for_intent(Intent.GEO)
        if state["resource_lookup"]:
            return "commerce-resources-v1"
        route = state["intent_route"]
        if route.primary_intent is Intent.AMBIGUOUS_CONFIRMATION:
            return "commerce-confirmation-v1"
        if (
            route.decision is RouterDecision.PREPARE
            and route.draft_operation is not DraftOperation.REVISE
            and not route.fallback_required
            and self._workflow_plan.available
            and should_propose_workflow(state["normalized_message"])
        ):
            return "commerce-workflow-plan-v1"
        if route.fallback_required or route.decision is RouterDecision.CLARIFY:
            return "commerce-router-clarification-v1"
        if route.primary_intent is Intent.MEDIA and route.decision is RouterDecision.PREPARE:
            return "commerce-router-clarification-v1"
        if route.decision is RouterDecision.PREPARE and route.primary_intent in DOMAIN_DEFINITIONS:
            return self.graph_for_intent(route.primary_intent)
        if route.primary_intent is Intent.GEO:
            return "commerce-geo-readonly-v1"
        if should_use_readonly_planner(route) and self._multistep.available:
            return "commerce-readonly-multistep-v1"
        return "commerce-general-v1"

    async def _compose_full_article_receipt(self, state: CommerceState) -> dict[str, object]:
        state["counters"].before_workflow_step()
        self._harness.validate_action_deadline(state["request"])
        context = state["authorized_context"]
        if context is None or context.source != "JAVA_AUTHORIZED_CONTEXT":
            raise AgentIdentityError("AGENT_CAPABILITY_DENIED")
        require_capability("article.generate")
        message = "已收到完整文章要求，接下来会生成稿件供你审阅；发布前仍需你确认。"
        if state["on_delta"] is not None:
            await state["on_delta"](message)
        return {
            "outcome": RunOutcome(
                kind="MESSAGE", intent=Intent.ARTICLE, message=message, usage=self._usage(state)
            )
        }

    async def _compose_resources(self, state: CommerceState) -> dict[str, object]:
        require_capability("resources.read")
        context = state["authorized_context"]
        if context is None:
            raise RuntimeError("Authorized Run context was not loaded")
        result = await self._resources.execute(
            request=state["request"],
            context=context,
            counters=state["counters"],
            on_delta=state["on_delta"],
        )
        return {
            "outcome": RunOutcome(
                kind="INTERACTION" if result.interaction is not None else "MESSAGE",
                intent=Intent.GENERAL,
                message=result.text,
                interaction=result.interaction,
                usage=self._usage(state),
                business_outcome=result.business_outcome,
                geo_context_used=result.geo_context_used,
            ),
            "gateway_usage": state["gateway_usage"] + result.usage,
        }

    async def _compose_general(self, state: CommerceState) -> dict[str, object]:
        state["counters"].before_workflow_step()
        context = state["authorized_context"]
        if context is None:
            raise RuntimeError("Authorized Run context was not loaded")
        messages, estimated_tokens = self._model_messages(
            state,
            self._prompts.for_general_response(GENERAL_RESPONSE_INSTRUCTION),
            final_system_instruction=general_response_fact_scope(context),
        )
        await notify_progress(
            state["on_progress"],
            RunProgressStage.COMPOSING_RESPONSE,
            RunProgressStatus.RUNNING,
        )
        review_enabled = self._prompts.general_response_review is not None and not self.is_mock
        gateway_usage = state["gateway_usage"]
        business_outcome = BusinessOutcome(status=BusinessOutcomeStatus.ANSWERED)
        with start_span(
            "agent.graph.general",
            attributes={
                "response.prompt.path": (
                    "DEDICATED_GENERAL"
                    if self._prompts.general_response is not None
                    else "LEGACY_CORE"
                ),
                "response.platform_evidence": "NOT_INSPECTED",
                "response.causal_evidence": "NOT_INSPECTED",
            },
        ) as span:
            try:
                # Reserve candidate + review headroom without enlarging Java's Run budget.
                # LOCAL fixtures remain explicitly unreviewed, not evidence of model quality.
                self._harness.validate_action_deadline(state["request"])
                if review_enabled and (
                    state["counters"].model_call_count + 2 > state["request"].budget.max_model_calls
                ):
                    reviewed = general_review_fallback("BUDGET_EXHAUSTED")
                    text = reviewed.text
                    business_outcome = reviewed.business_outcome
                    span.set("response.review", reviewed.decision_reason)
                else:
                    answer = await self._gateway.answer_general(
                        messages,
                        # Never publish candidate deltas before the factual review accepts it.
                        on_delta=None if review_enabled else state["on_delta"],
                    )
                    # Record paid candidate usage before any subsequent await can cancel.
                    state["counters"].record_gateway_usage(answer.usage)
                    gateway_usage += answer.usage
                    text = answer.text
                    if review_enabled:
                        prepared = build_general_response_review(
                            messages,
                            answer.text,
                            self._prompts.general_response_review or "",
                            context=context,
                        )
                        try:
                            self._harness.validate_action_deadline(state["request"])
                            self._harness.validate_next_model_call(
                                state["request"], state["counters"].model_call_count + 1
                            )
                            budget = context.context_budget
                            _, review_tokens = self._harness.fit_model_messages(
                                state["request"],
                                prepared.messages,
                                target_input_tokens=(
                                    state["request"].budget.max_input_tokens
                                    if budget is None
                                    else budget.target_input_tokens
                                ),
                                # Both messages are fixed: review the full supplied input and
                                # full candidate or fail closed, never trim then approve all.
                                available_history_tokens=0,
                            )
                        except HarnessViolation as exc:
                            if exc.code not in {
                                "MODEL_CALL_BUDGET_EXCEEDED",
                                "INPUT_TOKEN_BUDGET_EXCEEDED",
                            }:
                                raise
                            reviewed = general_review_fallback("BUDGET_EXHAUSTED")
                        else:
                            state["counters"].observe_estimated_input_tokens(review_tokens)
                            estimated_tokens = max(estimated_tokens, review_tokens)
                            reviewed = await review_general_response(
                                self._gateway,
                                prepared,
                                deadline_at=state["request"].budget.deadline_at,
                            )
                        state["counters"].record_gateway_usage(reviewed.usage)
                        gateway_usage += reviewed.usage
                        text = reviewed.text
                        business_outcome = reviewed.business_outcome
                        span.set("response.review", reviewed.decision_reason)
                        span.set("response.review.accepted", reviewed.accepted)
                        span.set("response.review.reason_codes", ",".join(reviewed.reason_codes))
                    else:
                        span.set(
                            "response.review", "MOCK_UNREVIEWED" if self.is_mock else "UNCONFIGURED"
                        )
                span.set("response.business_outcome", business_outcome.status.value)
                if business_outcome.reason_code is not None:
                    span.set("response.reason_code", business_outcome.reason_code)
                if business_outcome.failure_stage is not None:
                    span.set("response.failure_stage", business_outcome.failure_stage)
            except ModelGatewayError as exc:
                span.fail(exc.code)
                await notify_progress(
                    state["on_progress"],
                    RunProgressStage.COMPOSING_RESPONSE,
                    RunProgressStatus.FAILED,
                )
                raise
        await notify_progress(
            state["on_progress"],
            RunProgressStage.COMPOSING_RESPONSE,
            RunProgressStatus.FAILED
            if business_outcome.status is BusinessOutcomeStatus.FAILED
            else RunProgressStatus.SUCCEEDED,
        )
        return {
            "outcome": RunOutcome(
                kind="MESSAGE",
                intent=Intent.GENERAL,
                message=text,
                usage=self._usage(state),
                business_outcome=business_outcome,
            ),
            "gateway_usage": gateway_usage,
            "estimated_input_tokens": max(
                state["estimated_input_tokens"],
                estimated_tokens,
            ),
        }

    async def _compose_geo_readonly(self, state: CommerceState) -> dict[str, object]:
        require_capability("geo.read")
        state["counters"].before_workflow_step()
        context = state["authorized_context"]
        if context is None:
            raise RuntimeError("Authorized Run context was not loaded")
        await notify_progress(
            state["on_progress"], RunProgressStage.COMPOSING_RESPONSE, RunProgressStatus.RUNNING
        )
        result = await self._geo_readonly.execute(
            request=state["request"],
            context=context,
            counters=state["counters"],
            on_delta=state["on_delta"],
        )
        geo_status = (
            None if context.geo_insight_context is None else context.geo_insight_context.status
        )
        if geo_status in {"NEEDS_SCOPE", "NEEDS_TOPIC"}:
            business_outcome = BusinessOutcome(
                status=BusinessOutcomeStatus.NEEDS_INPUT, reason_code="INPUT_REQUIRED"
            )
        elif geo_status in {None, "UNAVAILABLE"}:
            business_outcome = BusinessOutcome(
                status=BusinessOutcomeStatus.FAILED,
                reason_code="GEO_CONTEXT_UNAVAILABLE",
                failure_stage="LOOKUP",
            )
        elif result.fallback_code is not None:
            business_outcome = BusinessOutcome(
                status=BusinessOutcomeStatus.PARTIAL,
                reason_code="RESPONSE_PROTOCOL_INVALID",
                failure_stage="SYNTHESIS",
            )
        else:
            business_outcome = BusinessOutcome(status=BusinessOutcomeStatus.ANSWERED)
        await notify_progress(
            state["on_progress"],
            RunProgressStage.COMPOSING_RESPONSE,
            RunProgressStatus.FAILED if result.fallback_code else RunProgressStatus.SUCCEEDED,
        )
        return {
            "outcome": RunOutcome(
                kind="MESSAGE",
                intent=Intent.GEO,
                message=result.text,
                usage=self._usage(state),
                geo_context_used=True,
                business_outcome=business_outcome,
            ),
            "gateway_usage": state["gateway_usage"] + result.usage,
            "estimated_input_tokens": max(
                state["estimated_input_tokens"], result.estimated_input_tokens
            ),
        }

    async def _compose_readonly_multistep(self, state: CommerceState) -> dict[str, object]:
        state["counters"].before_workflow_step()
        context = state["authorized_context"]
        if context is None:
            raise RuntimeError("Authorized Run context was not loaded")
        if state["intent_route"].primary_intent is not Intent.GEO:
            context = replace(context, geo_insight_context=None)
        result = await self._multistep.execute(
            request=state["request"],
            context=context,
            route=state["intent_route"],
            counters=state["counters"],
            on_delta=state["on_delta"],
            on_progress=state["on_progress"],
        )
        if result.fallback_code == "READONLY_SYNTHESIS_UNAVAILABLE":
            business_outcome = BusinessOutcome(
                status=BusinessOutcomeStatus.PARTIAL,
                reason_code="READONLY_SYNTHESIS_UNAVAILABLE",
                failure_stage="SYNTHESIS",
            )
        elif result.fallback_code is not None:
            business_outcome = BusinessOutcome(
                status=BusinessOutcomeStatus.FAILED,
                reason_code=(
                    "READONLY_PLANNER_UNAVAILABLE"
                    if result.fallback_code == "READONLY_PLANNER_UNAVAILABLE"
                    else "READONLY_PLAN_INVALID"
                    if result.fallback_code == "READONLY_PLAN_INVALID"
                    else "READONLY_EVIDENCE_UNAVAILABLE"
                ),
                failure_stage="TOOL"
                if result.fallback_code == "READONLY_EVIDENCE_UNAVAILABLE"
                else "PLAN",
            )
        elif any(item.status.value == "FAILED" for item in result.observations):
            business_outcome = BusinessOutcome(
                status=BusinessOutcomeStatus.PARTIAL,
                reason_code="READONLY_PARTIAL_EVIDENCE",
                failure_stage="TOOL",
            )
        else:
            business_outcome = BusinessOutcome(status=BusinessOutcomeStatus.ANSWERED)
        return {
            "outcome": RunOutcome(
                kind="MESSAGE",
                intent=state["intent"],
                message=result.text,
                usage=self._usage(state),
                business_outcome=business_outcome,
            ),
            "gateway_usage": state["gateway_usage"] + result.usage,
            "estimated_input_tokens": max(
                state["estimated_input_tokens"], result.estimated_input_tokens
            ),
        }

    async def _compose_workflow_plan(self, state: CommerceState) -> dict[str, object]:
        state["counters"].before_workflow_step()
        context = state["authorized_context"]
        if context is None:
            raise RuntimeError("Authorized Run context was not loaded")
        intents = workflow_delivery_intents(state["normalized_message"])
        missing = None
        if context.selected_space_id is None:
            missing = "selectedSpaceId"
        elif (
            any(intent in {Intent.IMAGE, Intent.VIDEO, Intent.ARTICLE} for intent in intents)
            and context.selected_product_id is None
        ):
            missing = "selectedProductId"
        elif "竞品" in state["normalized_message"] and len(context.competitors) < 2:
            missing = "competitorIds"
        if missing is not None:
            message = f"要准备这组任务，请先选择 {missing} 对应的授权对象。"
            response: dict[str, object] = {
                "outcome": RunOutcome(
                    kind="INTERACTION",
                    intent=Intent.GENERAL,
                    message=message,
                    interaction=InteractionProposal(
                        intent=Intent.GENERAL,
                        question=message,
                        missing_fields=(missing,),
                    ),
                    usage=self._usage(state),
                )
            }
            await self._complete_static_response_progress(state)
            return response
        await notify_progress(
            state["on_progress"],
            RunProgressStage.PLANNING_WORKFLOW,
            RunProgressStatus.RUNNING,
        )
        try:
            result = await self._workflow_plan.execute(
                request=state["request"],
                context=context,
                counters=state["counters"],
            )
        except WorkflowPlanError as exc:
            if exc.code not in {
                "WORKFLOW_PARAMETERS_INCOMPLETE",
                "WORKFLOW_TEXT_ONLY_SOURCE_UNSUPPORTED",
                "PRODUCT_IDENTITY_ASSET_REQUIRED",
                "WORKFLOW_POINT_BUDGET_TOO_SMALL",
                "WORKFLOW_POINT_BUDGET_UNCLEAR",
            }:
                raise
            await notify_progress(
                state["on_progress"],
                RunProgressStage.PLANNING_WORKFLOW,
                RunProgressStatus.FAILED,
            )
            if exc.code == "WORKFLOW_TEXT_ONLY_SOURCE_UNSUPPORTED":
                message = (
                    "你要求纯文字生图，我不会擅自改用商品参考图。当前组合计划暂不支持纯文字图片来源；"
                    "请先在「图片创作」单独选择纯文本生图，再单独创作文章。"
                    "这次没有创建组合计划或付费任务。"
                )
                missing_fields = ("workflowTextOnlySource",)
            elif exc.code == "PRODUCT_IDENTITY_ASSET_REQUIRED":
                message = (
                    "这组任务需要使用已选商品的参考图，但商品还没有可用主图。"
                    "请先补充主图，或将纯文字生图和其他任务分别创建。"
                    "这次没有创建组合计划或付费任务。"
                )
                missing_fields = ("productIdentityAsset",)
            elif exc.code == "WORKFLOW_POINT_BUDGET_TOO_SMALL":
                message = (
                    "你设定的积分上限为 0，当前组合任务无法在零积分内创建。"
                    "我不会创建计划或扣费；如果只想先讨论方案，可以继续直接提问。"
                )
                missing_fields = ("workflowPointBudget",)
            elif exc.code == "WORKFLOW_POINT_BUDGET_UNCLEAR":
                message = (
                    "我没能确定你说的积分上限，请用整数明确说，例如「总共不超过30积分」。"
                    "这次没有创建组合计划或付费任务。"
                )
                missing_fields = ("workflowPointBudget",)
            else:
                message = (
                    "这组任务的参数还没有完整确认，尚未创建计划。"
                    "请先明确各项创作的内容和数量；图片一次支持 1 至 4 张，我不会自动改成默认值。"
                )
                missing_fields = ("taskParameters",)
            return {
                "outcome": RunOutcome(
                    kind="INTERACTION",
                    intent=Intent.GENERAL,
                    message=message,
                    interaction=InteractionProposal(
                        intent=Intent.GENERAL,
                        question=message,
                        missing_fields=missing_fields,
                    ),
                    business_outcome=BusinessOutcome(
                        status=BusinessOutcomeStatus.NEEDS_INPUT,
                        reason_code="TASK_PREPARATION_REJECTED",
                        failure_stage="PLAN",
                    ),
                    usage=self._usage(state),
                )
            }
        except Exception:
            await notify_progress(
                state["on_progress"],
                RunProgressStage.PLANNING_WORKFLOW,
                RunProgressStatus.FAILED,
            )
            raise
        await notify_progress(
            state["on_progress"],
            RunProgressStage.PLANNING_WORKFLOW,
            RunProgressStatus.SUCCEEDED,
        )
        response: dict[str, object] = {
            "outcome": RunOutcome(
                kind="MESSAGE",
                intent=Intent.GENERAL,
                message=render_workflow_plan(result.plan),
                usage=self._usage(state),
            ),
            "gateway_usage": state["gateway_usage"] + result.usage,
        }
        await self._complete_static_response_progress(state)
        return response

    async def _compose_router_clarification(self, state: CommerceState) -> dict[str, object]:
        state["counters"].before_workflow_step()
        route = state["intent_route"]
        missing = tuple(item.value for item in route.context_needs[:1])
        clarification = route.clarification
        if clarification is not None and clarification.target is not None:
            missing = (clarification.target.value,)
        if route.primary_intent is Intent.MEDIA and route.decision is RouterDecision.PREPARE:
            message = (
                "当前对话尚未获得媒体投稿或资源操作工具授权；请改为咨询投稿方案，"
                "或前往文章投稿流程继续。"
            )
            missing = ("authorizedMediaWorkflow",)
        elif (
            clarification is not None
            and clarification.reason is ClarificationReason.OBJECT_MISMATCH
        ):
            context = state["authorized_context"]
            if clarification.target is RouterContextPath.PRODUCT:
                name = None if context is None or context.product is None else context.product.name
                selected = f"右侧当前选中的是“{name}”" if name else "右侧当前选中的商品"
                message = (
                    f"{selected}，与你这轮想做的对象不一致，不是要你重复补资料。"
                    "请在右侧切换到这次要用的商品，或上传它的参考图片；"
                    "如果仍用右侧商品，直接告诉我即可。"
                )
                missing = ("productSelectionMismatch",)
                if state["request"].message.attachment_asset_ids:
                    message = (
                        "这轮已附参考图片，不会混用右侧旧商品。"
                        "请说明是否以本轮图片中的对象为准，或重新附上要创作的对象图片。"
                    )
                    missing = ("referenceSelectionMismatch",)
            elif clarification.target is RouterContextPath.SPACE:
                name = None if context is None or context.space is None else context.space.name
                selected = f"“{name}”" if name else "右侧已选空间"
                message = (
                    f"当前经营空间是{selected}，与你这轮要求不一致。"
                    "请切换空间，或明确仍使用当前空间。"
                )
                missing = ("spaceSelectionMismatch",)
            else:
                message = (
                    "本轮参考图片与你想做的对象不一致。"
                    "请上传要用的图片，或说明仍使用本轮附件；不会混用旧商品素材。"
                )
                missing = ("referenceSelectionMismatch",)
        elif (
            clarification is not None
            and clarification.reason is ClarificationReason.AMBIGUOUS_REQUEST
        ):
            message = clarification.question or "你想调整哪一项内容？"
            missing = missing or ("requestTarget",)
        elif route.fallback_required:
            message = "当前目标或引用对象存在多个可信解释，请明确这轮要分析的一个对象或目标。"
            missing = missing or ("requestTarget",)
        elif missing:
            questions = {
                "space": "这次要分析哪个经营空间？请先选择一个经营空间。",
                "product": "这次要分析哪个商品？请先从右侧选择一个商品。",
                "competitors": "这次要对比哪些竞品？请先选择要纳入分析的竞品。",
                "conversationMemory": "你指的是前面对话中的哪项内容？请补充一个可识别的线索。",
                "taskWorkspace": "你想继续哪一项任务？请说明任务目标或当前阶段。",
                "taskWorkspace.pendingInteraction": "请先回答上一条消息提出的关键信息。",
                "taskWorkspace.activeDraftId": (
                    "当前没有可修改的、同一商品下的待确认草稿。"
                    "请打开要修改的未确认草稿；已确认任务不能在这里改写，"
                    "也可以明确让我独立新做一份。"
                ),
                "taskWorkspace.activeTaskId": "你想查看哪一项任务？请先选择对应任务。",
                "taskWorkspace.referenceAssetIds": (
                    "请重新附上要修改的原图，并说明这次要调整哪里；"
                    "我会先整理新的待确认草稿，不会改用右侧商品或自动执行。"
                ),
                "taskWorkspace.runningTasks": "你指的是哪项进行中的任务？请说明任务类型。",
            }
            message = questions.get(
                missing[0],
                "为了继续，请补充一个会影响判断的关键信息或明确本轮目标。",
            )
        else:
            message = "为了继续，请补充一个会影响判断的关键信息或明确本轮目标。"
            missing = ("requestDetails",)
        response: dict[str, object] = {
            "outcome": RunOutcome(
                kind="INTERACTION",
                intent=route.primary_intent,
                message=message,
                interaction=InteractionProposal(
                    intent=route.primary_intent,
                    question=message,
                    missing_fields=missing,
                    choices=(),
                ),
                usage=self._usage(state),
            )
        }
        await self._complete_static_response_progress(state)
        return response

    def _model_messages(
        self,
        state: CommerceState,
        base_prompt: str,
        *,
        final_system_instruction: str = "",
    ) -> tuple[ModelMessages, int]:
        context = state["authorized_context"]
        if context is None:
            raise RuntimeError("Authorized Run context was not loaded")
        if state["intent_route"].primary_intent is not Intent.GEO:
            context = replace(context, geo_insight_context=None)
        messages = model_messages_with_context(
            base_prompt,
            context,
            state["request"],
            final_system_instruction=final_system_instruction,
        )
        budget = context.context_budget
        fitted, estimated_tokens = self._harness.fit_model_messages(
            state["request"],
            messages,
            target_input_tokens=(
                state["request"].budget.max_input_tokens
                if budget is None
                else budget.target_input_tokens
            ),
            available_history_tokens=(
                state["request"].budget.max_input_tokens
                if budget is None
                else budget.available_history_tokens
            ),
        )
        if len(fitted) < len(messages):
            with start_span(
                "agent.context.trim",
                attributes={
                    "message.count.before": len(messages),
                    "message.count.after": len(fitted),
                    "estimatedInputTokens": estimated_tokens,
                },
            ) as span:
                span.fail("CONTEXT_INPUT_TRIMMED")
        state["counters"].observe_estimated_input_tokens(estimated_tokens)
        return fitted, estimated_tokens

    @staticmethod
    def _workspace_intent(state: CommerceState, classified: Intent) -> Intent:
        context = state["authorized_context"]
        workspace = None if context is None else context.task_workspace
        if (
            classified is Intent.MEDIA
            and workspace is not None
            and CommerceAssistantGraph._active_article(workspace) is not None
            and any(
                marker in state["normalized_message"].casefold()
                for marker in ("投稿", "推荐媒体", "发布文章")
            )
        ):
            return Intent.ARTICLE
        if classified not in {Intent.GENERAL, Intent.AMBIGUOUS_CONFIRMATION} or workspace is None:
            return classified
        contextual = CommerceAssistantGraph._authorized_workspace_intent(state)
        return classified if contextual is None else contextual

    @staticmethod
    def _active_article(workspace: TaskWorkspace) -> dict[str, JsonValue] | None:
        active = workspace.confirmed_constraints.get("activeArticle")
        if not isinstance(active, dict):
            return None
        if not isinstance(active.get("articleId"), str):
            return None
        return active

    @staticmethod
    def _authorized_workspace_intent(state: CommerceState) -> Intent | None:
        context = state["authorized_context"]
        workspace = None if context is None else context.task_workspace
        if workspace is None:
            return None
        content = state["normalized_message"].casefold()
        if any(marker in content for marker in _GENERAL_RECALL_MARKERS):
            return None
        compact = _compact_command(content)
        is_exact_continuation = compact in _WORKSPACE_EXACT_CONTINUATION_COMMANDS
        is_explicit_action = any(marker in content for marker in _WORKSPACE_EXPLICIT_ACTION_MARKERS)
        is_option_revision = _WORKSPACE_OPTION_REVISION.search(content) is not None
        if not (is_exact_continuation or is_explicit_action or is_option_revision):
            return None
        if (
            workspace.pending_interaction is None
            and workspace.active_draft_id is None
            and CommerceAssistantGraph._active_article(workspace) is None
        ):
            return None
        try:
            contextual = Intent(workspace.current_intent)
        except ValueError:
            return None
        return contextual if contextual in DOMAIN_DEFINITIONS else None

    def _fallback_route(self, state: CommerceState) -> tuple[IntentRoute, str]:
        explicit = deterministic_intent(state["normalized_message"])
        if explicit is Intent.GENERAL:
            # Free answers have no PREPARE authority. Keep simple explanations
            # available even when the optional structured router is malformed.
            return default_intent_route(Intent.GENERAL), "GENERAL_READONLY"
        return (
            default_intent_route(explicit).model_copy(
                update={
                    "decision": RouterDecision.CLARIFY,
                    "requires_clarification": True,
                    "fallback_required": True,
                    "draft_operation": None,
                    "clarification": RouterClarification(
                        reason=ClarificationReason.AMBIGUOUS_REQUEST,
                        target=None,
                        question="这轮你想具体准备什么内容？请说明一个目标，我会先整理草稿，不会直接执行。",
                    ),
                }
            ),
            "CONSERVATIVE_CLARIFY",
        )

    async def _compose_ambiguous(self, state: CommerceState) -> dict[str, object]:
        state["counters"].before_workflow_step()
        context = state["authorized_context"]
        workspace = None if context is None else context.task_workspace
        active_article = None if workspace is None else self._active_article(workspace)
        if active_article is not None:
            title = str(active_article.get("title") or "当前文章")
            stage = str(active_article.get("workflowStage") or "")
            confirmation_actions = {
                "TITLE_AWAITING_CONFIRMATION": "选择并确认标题",
                "PLAN_AWAITING_CONFIRMATION": "修改或确认文章创作方案",
                "BODY_READY": "检查并确认正文",
                "EDITING": "完成编辑并确认正文",
                "READY_TO_SUBMIT": "核对推荐媒体并确认投稿",
            }
            action = confirmation_actions.get(stage, "完成当前阶段的确认")
            message = (
                f"当前文章《{title}》正在等待你{action}。"
                "请在文章卡片中点击对应按钮；如需调整，请使用卡片中的修改或重新生成入口。"
            )
        elif workspace is not None and workspace.active_draft_id is not None:
            message = (
                "当前有待确认草稿。请在对应草稿卡片中核对并确认；如果要修改，直接告诉我修改要求。"
            )
        elif workspace is not None and workspace.running_tasks:
            message = (
                "当前任务已经在处理中，不需要再次确认。你可以继续聊天，"
                "任务完成后结果会回到原流程中。"
            )
        else:
            message = (
                "当前没有可直接确认的待办操作，我不会仅凭这句话开始任务或修改资料。"
                "请说明你想继续哪一步；如果是竞品入库或监测设置，请在对应页面核对后确认。"
            )
        response: dict[str, object] = {
            "outcome": RunOutcome(
                kind="INTERACTION",
                intent=Intent.AMBIGUOUS_CONFIRMATION,
                message=message,
                interaction=InteractionProposal(
                    intent=Intent.AMBIGUOUS_CONFIRMATION,
                    question=message,
                    missing_fields=("confirmationTargetId",),
                    choices=(),
                ),
                usage=self._usage(state),
            ),
        }
        await self._complete_static_response_progress(state)
        return response

    def _domain_node(self, intent: Intent) -> DomainNode:
        async def run_domain(state: CommerceState) -> dict[str, object]:
            state["counters"].before_workflow_step()
            context = state["authorized_context"]
            if context is None:
                raise RuntimeError("Authorized Run context was not loaded")
            if intent is Intent.ARTICLE and context.task_workspace is not None:
                active_response = await self._active_article_response(state, context.task_workspace)
                if active_response is not None:
                    return {"outcome": active_response}
            with start_span(f"agent.graph.{intent.value.lower()}") as span:
                try:
                    final = await self._domains[intent].ainvoke(
                        DomainState(
                            request=state["request"],
                            authorized_context=context,
                            normalized_message=state["normalized_message"],
                            estimated_input_tokens=state["estimated_input_tokens"],
                            counters=state["counters"],
                            gateway_usage=state["gateway_usage"],
                            missing_fields=(),
                            tool_observations=(),
                            outcome=None,
                            on_progress=state["on_progress"],
                            draft_operation=state["intent_route"].draft_operation
                            or DraftOperation.NEW,
                            previous_draft_id=(
                                context.task_workspace.active_draft_id
                                if state["intent_route"].draft_operation is DraftOperation.REVISE
                                and context.task_workspace is not None
                                else None
                            ),
                        )
                    )
                except Exception as exc:
                    span.fail(getattr(exc, "code", "DOMAIN_GRAPH_FAILED"))
                    raise
            return {
                "outcome": final["outcome"],
                "gateway_usage": final["gateway_usage"],
                "estimated_input_tokens": final["estimated_input_tokens"],
            }

        return run_domain

    async def _active_article_response(
        self,
        state: CommerceState,
        workspace: TaskWorkspace,
    ) -> RunOutcome | None:
        active = self._active_article(workspace)
        if active is None:
            return None
        if (
            state["intent_route"].draft_operation is DraftOperation.REVISE
            and workspace.current_intent == Intent.ARTICLE.value
            and workspace.active_draft_id is not None
            and workspace.confirmed_constraints.get("articleId") == active["articleId"]
        ):
            # The router validated the pending draft. When Java's snapshot also
            # ties it to this article, revise that draft instead of treating it
            # as a competing new workflow. Java still locks the previous draft
            # and preserves its article/report identity before accepting it.
            return None
        content = state["normalized_message"].casefold()
        pending = workspace.pending_interaction
        resolving_conflict = bool(
            pending is not None and "activeArticleDecision" in pending.missing_fields
        )
        awaiting_new_request = bool(
            pending is not None and "newArticleRequest" in pending.missing_fields
        )
        if awaiting_new_request:
            return None

        title = str(active.get("title") or "当前文章")
        stage = str(active.get("workflowStage") or "")
        normalized_choice = "".join(
            character
            for character in state["normalized_message"]
            if not character.isspace() and character not in _IGNORABLE_CONFIRMATION_PUNCTUATION
        )
        if resolving_conflict and normalized_choice == _ARTICLE_START_NEW_CHOICE:
            question = (
                "好的，我会结束当前前台流程。请告诉我新文章的主题、目标受众和最想传达的核心内容。"
            )
            outcome = RunOutcome(
                kind="INTERACTION",
                intent=Intent.ARTICLE,
                message=question,
                interaction=InteractionProposal(
                    intent=Intent.ARTICLE,
                    question=question,
                    missing_fields=("newArticleRequest",),
                    choices=(),
                ),
                usage=self._usage(state),
            )
        elif resolving_conflict and _ARTICLE_START_NEW_CHOICE in state["normalized_message"]:
            return None
        elif resolving_conflict and _ARTICLE_CONTINUE_CHOICE in state["normalized_message"]:
            message = self._article_resume_message(title, stage)
            outcome = RunOutcome(
                kind="MESSAGE",
                intent=Intent.ARTICLE,
                message=message,
                usage=self._usage(state),
            )
        elif any(marker in content for marker in _ARTICLE_NEW_REQUEST_MARKERS) or not any(
            marker in content for marker in _ARTICLE_CONTINUATION_MARKERS
        ):
            question = (
                f"这个对话里还有一篇进行中的文章《{title}》。"
                "你要继续当前文章，还是结束当前流程并开始一篇新文章？"
            )
            outcome = RunOutcome(
                kind="INTERACTION",
                intent=Intent.ARTICLE,
                message=question,
                interaction=InteractionProposal(
                    intent=Intent.ARTICLE,
                    question=question,
                    missing_fields=("activeArticleDecision",),
                    choices=(_ARTICLE_CONTINUE_CHOICE, _ARTICLE_START_NEW_CHOICE),
                ),
                usage=self._usage(state),
            )
        else:
            outcome = RunOutcome(
                kind="MESSAGE",
                intent=Intent.ARTICLE,
                message=self._article_resume_message(title, stage),
                usage=self._usage(state),
            )
        await self._complete_static_response_progress(state)
        return outcome

    @staticmethod
    def _article_resume_message(title: str, stage: str) -> str:
        stage_actions = {
            "TITLE_INPUT": "确认当前任务草稿后生成标题",
            "TITLE_GENERATING": "等待标题生成完成",
            "TITLE_AWAITING_CONFIRMATION": "回到文章卡片选择一个标题，或点击重新生成标题",
            "PLAN_READY": "确认当前任务草稿后生成文章创作方案",
            "PLAN_GENERATING": "等待文章创作方案生成完成",
            "PLAN_AWAITING_CONFIRMATION": "回到文章卡片修改或确认文章创作方案",
            "BODY_READY_TO_GENERATE": "确认正文任务草稿，继续生成正文",
            "BODY_QUEUED": "等待正文任务开始执行",
            "BODY_GENERATING": "等待正文生成完成",
            "BODY_READY": "回到文章卡片检查并确认正文",
            "EDITING": "回到文章卡片继续编辑并确认正文",
            "READY_TO_SUBMIT": "回到文章卡片查看推荐媒体并确认投稿",
        }
        action = stage_actions.get(stage, "回到文章卡片继续当前流程")
        return f"文章《{title}》仍在进行中，没有被刚才的对话打断。下一步请{action}。"

    @staticmethod
    async def _complete_static_response_progress(state: CommerceState) -> None:
        await notify_progress(
            state["on_progress"],
            RunProgressStage.COMPOSING_RESPONSE,
            RunProgressStatus.RUNNING,
        )
        await notify_progress(
            state["on_progress"],
            RunProgressStage.COMPOSING_RESPONSE,
            RunProgressStatus.SUCCEEDED,
        )

    def _validate(self, state: CommerceState) -> dict[str, object]:
        state["counters"].before_workflow_step()
        outcome = state["outcome"]
        if outcome is None:
            raise RuntimeError("Agent graph has no outcome to validate")
        validated = outcome.model_copy(update={"usage": self._usage(state)})
        self._harness.validate_outcome(state["request"], validated)
        return {"outcome": validated}

    def _usage(
        self,
        state: CommerceState,
    ) -> Usage:
        return state["counters"].usage(
            provider=self._gateway.identity.provider,
            model=self._gateway.identity.model,
        )

"""Bounded producer for Java-authoritative proposal-only workflow plans."""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from datetime import UTC, timedelta
from unicodedata import normalize
from uuid import UUID, uuid5

from pydantic import BaseModel, ValidationError

from app.agent.graphs.domains import (
    DomainGraphError,
    geo_max_questions,
    prepare_task_draft,
    prepare_task_draft_input,
)
from app.agent.models import ChatRunRequest, Intent, TaskDraftProposal
from app.agent.workflow_contract import (
    WorkflowPlan,
    WorkflowPlanBudget,
    WorkflowPlanProposalRequest,
    WorkflowPlanStepProposal,
    WorkflowStepType,
    WorkflowTaskDraftProposal,
    WorkflowToolName,
)
from app.common.enums import TaskType
from app.creative.brief import CreativeBriefError, select_creative_source
from app.harness.runtime import RuntimeHarness, RuntimeUsageCounters
from app.integration.java_internal import (
    JavaInternalClientError,
    JavaInternalErrorCode,
    JavaWorkflowPlanClientProtocol,
)
from app.model_gateway.gateway import (
    GatewayUsage,
    ModelGateway,
    ModelGatewayError,
    intent_is_explicitly_negated,
    task_parameter_instruction,
)
from app.observability.tracing import start_span
from app.prompts.assembly import CommercePromptSet
from app.providers.java_context import AuthorizedRunContext, model_messages_with_context
from app.tools.registry import (
    DEFAULT_TOOL_REGISTRY,
    RiskLevel,
    TaskDraftPrepareInput,
    ToolExecutor,
    ToolGroup,
    ToolObserver,
    ToolPolicy,
)

_WORKFLOW_NAMESPACE = UUID("31ff5384-47d0-42a6-9021-4247ef436b21")
logger = logging.getLogger(__name__)

_POINT_AMOUNT = r"(?P<amount>[^\s，。；,;]{1,32})"
_POINT_NUMERIC_TOKEN = (
    r"(?P<amount>[+\-]?[0-9]+(?:\.[0-9]+)?|"
    r"[零\u3007一二两三四五六七八九十百千]+)"
)
_POINT_CAP_PREFIX = re.compile(
    r"(?:不超过|不要超过|别超过|最多|最高|至多|不高于|不多于|"
    r"预算(?:控制在|为|是)?|控制在)\s*[\uFF1A:]?\s*" + _POINT_AMOUNT + r"\s*积分"
)
_POINT_CAP_SUFFIX = re.compile(
    r"(?<![0-9.\-+\u2212负零\u3007一二两三四五六七八九十百千])"
    + _POINT_NUMERIC_TOKEN
    + r"\s*积分(?:以内|以下|封顶|上限)"
)
_POINT_BUDGET_SIGNAL = re.compile(
    r"不超过|不要超过|别超过|最多|最高|至多|不高于|不多于|预算|控制在|"
    r"积分(?:以内|以下|封顶|上限)"
)
_CHINESE_DIGITS = {
    "零": 0,
    "\u3007": 0,
    "一": 1,
    "二": 2,
    "两": 2,
    "三": 3,
    "四": 4,
    "五": 5,
    "六": 6,
    "七": 7,
    "八": 8,
    "九": 9,
}
_CHINESE_UNITS = {"十": 10, "百": 100, "千": 1000}


def _java_normalized_text(value: str) -> str:
    """Mirror Java's NFKC-and-strip normalization for persisted plan text."""
    return normalize("NFKC", value).strip()


def user_point_ceiling(content: str) -> int | None:
    """Extract only an explicit maximum, never invent a spending authorization."""
    text = _java_normalized_text(content)
    if "积分" not in text or _POINT_BUDGET_SIGNAL.search(text) is None:
        return None
    amounts = [
        match.group("amount")
        for pattern in (_POINT_CAP_PREFIX, _POINT_CAP_SUFFIX)
        for match in pattern.finditer(text)
    ]
    if not amounts:
        raise WorkflowPlanError("WORKFLOW_POINT_BUDGET_UNCLEAR")
    ceilings = [_parse_point_amount(amount) for amount in amounts]
    return min(ceilings) if ceilings else None


def _parse_point_amount(value: str) -> int:
    if re.fullmatch(r"[0-9]{1,6}", value):
        return int(value)
    if value in _CHINESE_DIGITS:
        return _CHINESE_DIGITS[value]
    if not value or any(
        char not in _CHINESE_DIGITS and char not in _CHINESE_UNITS for char in value
    ):
        raise WorkflowPlanError("WORKFLOW_POINT_BUDGET_UNCLEAR")
    total = 0
    current: int | None = None
    previous_unit = 10_000
    explicit_zero = False
    for char in value:
        if char in _CHINESE_DIGITS:
            digit = _CHINESE_DIGITS[char]
            if digit == 0 and current is None:
                explicit_zero = True
                continue
            if current is not None:
                raise WorkflowPlanError("WORKFLOW_POINT_BUDGET_UNCLEAR")
            current = digit
            continue
        unit = _CHINESE_UNITS[char]
        if unit >= previous_unit or (current is None and (unit != 10 or total != 0)):
            raise WorkflowPlanError("WORKFLOW_POINT_BUDGET_UNCLEAR")
        total += (1 if current is None else current) * unit
        current = None
        previous_unit = unit
        explicit_zero = False
    if current is not None:
        if previous_unit > 10 and not explicit_zero:
            raise WorkflowPlanError("WORKFLOW_POINT_BUDGET_UNCLEAR")
        total += current
    if total > 1_000_000:
        raise WorkflowPlanError("WORKFLOW_POINT_BUDGET_UNCLEAR")
    return total


class WorkflowPlanError(RuntimeError):
    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


@dataclass(frozen=True, slots=True)
class WorkflowPlanResult:
    plan: WorkflowPlan
    usage: GatewayUsage


def workflow_delivery_intents(content: str) -> tuple[Intent, ...]:
    """Return explicit paid deliverables in a stable domain order."""
    normalized = content.casefold()
    markers = {
        Intent.IMAGE: ("图片", "主图", "商品图", "宣传图", "配图", "海报", "生图", "image"),
        Intent.VIDEO: ("视频", "短片", "video"),
        Intent.GEO: ("geo监测", "geo分析", "生成式引擎优化"),
        Intent.ARTICLE: ("文章", "文案", "推文", "article"),
    }
    return tuple(
        intent
        for intent in (Intent.GEO, Intent.IMAGE, Intent.VIDEO, Intent.ARTICLE)
        if any(marker in normalized for marker in markers[intent])
        and not intent_is_explicitly_negated(content, intent)
    )


def should_propose_workflow(content: str) -> bool:
    intents = workflow_delivery_intents(content)
    if len(intents) < 2:
        return False
    normalized = content.casefold()
    positive = any(
        marker in normalized for marker in ("准备", "生成", "制作", "创建", "做一", "做个")
    )
    plan_only = any(
        marker in normalized for marker in ("只给计划", "仅给计划", "计划和报价", "先报价")
    )
    fully_negated = any(
        marker in normalized
        for marker in (
            "都不要",
            "全部不要",
            "不做任何",
            "不要创建",
            "禁止创建",
            "不创建",
            "不要准备",
            "先别准备",
        )
    )
    detail_ready = any(
        marker in normalized
        for marker in (
            "竞品",
            "背景",
            "场景",
            "风格",
            "秒",
            "16:9",
            "9:16",
            "1:1",
            "无声",
            "有声",
            "白底",
            "蓝色",
            "主题",
            "提纲",
            "平台",
        )
    )
    return (positive or plan_only) and detail_ready and not fully_negated


def requests_text_only_creative_source(content: str) -> bool:
    """Honor explicit no-reference intent before selecting catalog image assets.

    Workflow-plan persistence currently authorizes only catalog/current-message
    references for IMAGE/VIDEO. A pure-text request must be explained as an
    unsupported combination, never silently changed to catalog sourcing.
    """
    normalized = "".join(normalize("NFKC", content).casefold().split())
    return any(
        marker in normalized
        for marker in (
            "纯文字生图",
            "纯文字生成图片",
            "纯文本生图",
            "纯文本生成图片",
            "仅凭文字生图",
            "只凭文字生图",
            "不使用商品参考图",
            "不用商品参考图",
            "不参考商品图",
            "不要商品图片作为参考",
        )
    )


class WorkflowPlanProducer:
    """Prepare independent drafts, then call the one registered Java proxy tool."""

    def __init__(
        self,
        *,
        gateway: ModelGateway,
        prompts: CommercePromptSet,
        java_client: JavaWorkflowPlanClientProtocol,
        harness: RuntimeHarness,
        tool_names: tuple[str, ...],
        max_point_cost: int,
        deadline_seconds: int,
        observer: ToolObserver | None = None,
    ) -> None:
        self._gateway = gateway
        self._prompts = prompts
        self._java_client = java_client
        self._harness = harness
        self._tool_names = frozenset(tool_names)
        self._max_point_cost = max_point_cost
        self._deadline_seconds = deadline_seconds
        self._observer = observer

    @property
    def available(self) -> bool:
        return "workflow_plan.propose" in self._tool_names

    async def execute(
        self,
        *,
        request: ChatRunRequest,
        context: AuthorizedRunContext,
        counters: RuntimeUsageCounters,
    ) -> WorkflowPlanResult:
        intents = workflow_delivery_intents(request.message.content)
        if len(intents) < 2:
            raise WorkflowPlanError("WORKFLOW_PLAN_NOT_COMPLEX")
        if context.selected_space_id is None:
            raise WorkflowPlanError("WORKFLOW_PLAN_SPACE_REQUIRED")
        if user_point_ceiling(request.message.content) == 0:
            raise WorkflowPlanError("WORKFLOW_POINT_BUDGET_TOO_SMALL")
        drafts: list[tuple[Intent, TaskDraftProposal]] = []
        usage = GatewayUsage()
        draft_executor = ToolExecutor(
            DEFAULT_TOOL_REGISTRY,
            ToolPolicy(
                allowed_tool_names=frozenset(("task_draft.propose",)),
                allowed_tool_groups=frozenset((ToolGroup.TASK_PREPARATION,)),
                allowed_risk_levels=frozenset((RiskLevel.PREPARE,)),
            ),
            {"task_draft.propose": prepare_task_draft_input},
            {"task_draft.propose": prepare_task_draft},
        )
        tool_catalog = draft_executor.model_tool_catalog(context.loaded_tool_groups)
        for intent in intents:
            max_geo_questions = geo_max_questions(context) if intent is Intent.GEO else 10
            if intent is Intent.GEO and max_geo_questions < 3:
                raise WorkflowPlanError("GEO_CAPABILITY_UNAVAILABLE")
            creative_source = None
            model_context = context
            if intent in {Intent.IMAGE, Intent.VIDEO}:
                if not request.message.attachment_asset_ids and requests_text_only_creative_source(
                    request.message.content
                ):
                    raise WorkflowPlanError("WORKFLOW_TEXT_ONLY_SOURCE_UNSUPPORTED")
                try:
                    creative_source = select_creative_source(
                        TaskType(intent.value),
                        context,
                        request,
                    )
                except CreativeBriefError as exc:
                    raise WorkflowPlanError(exc.code) from None
                if request.message.attachment_asset_ids:
                    # The parameter model may see the current attachments as the
                    # creative source, but it must not inherit catalog facts or
                    # historical workspace state in this mode.
                    model_context = context.current_attachment_only()
            messages = model_messages_with_context(
                self._prompts.for_task_parameters(
                    intent,
                    tool_catalog=tool_catalog,
                    parameter_instruction=task_parameter_instruction(
                        intent,
                        geo_max_questions=max_geo_questions,
                    ),
                ),
                model_context,
                request,
            )
            budget = context.context_budget
            messages, estimated_tokens = self._harness.fit_model_messages(
                request,
                messages,
                target_input_tokens=(
                    request.budget.max_input_tokens
                    if budget is None
                    else budget.target_input_tokens
                ),
                available_history_tokens=(
                    request.budget.max_input_tokens
                    if budget is None
                    else budget.available_history_tokens
                ),
            )
            counters.observe_estimated_input_tokens(estimated_tokens)
            remaining_model_calls = request.budget.max_model_calls - counters.model_call_count
            if remaining_model_calls < 1:
                raise WorkflowPlanError("WORKFLOW_PARAMETERS_INCOMPLETE")
            try:
                suggestion = await self._gateway.suggest_task_parameters(intent, messages)
            except ModelGatewayError as exc:
                counters.record_gateway_usage(exc.usage)
                usage += exc.usage
                if not exc.structured_response_failure:
                    raise
                raise WorkflowPlanError("WORKFLOW_PARAMETERS_INCOMPLETE") from None
            else:
                counters.record_gateway_usage(suggestion.usage)
                usage += suggestion.usage
            draft_input = TaskDraftPrepareInput(
                intent=intent,
                request_text=request.message.content,
                selected_space_id=context.selected_space_id,
                selected_product_id=(
                    None
                    if intent in {Intent.IMAGE, Intent.VIDEO}
                    and request.message.attachment_asset_ids
                    else context.selected_product_id
                ),
                source_mode=None if creative_source is None else creative_source[0],
                reference_asset_ids=() if creative_source is None else creative_source[1],
                source_message_id=(
                    request.message.message_id
                    if intent in {Intent.IMAGE, Intent.VIDEO}
                    and request.message.attachment_asset_ids
                    else None
                ),
                candidate_summary=suggestion.summary,
                candidate_parameters=suggestion.parameters,
                candidate_warnings=suggestion.warnings,
                geo_max_questions=max_geo_questions,
                geo_allowed_platform_codes=(
                    context.task_preparation_constraints.geo.platform_codes
                    if context.task_preparation_constraints is not None
                    else ()
                ),
                geo_max_provider_calls=(
                    context.task_preparation_constraints.geo.max_provider_calls
                    if context.task_preparation_constraints is not None
                    else 1000
                ),
            )
            try:
                prepared = prepare_task_draft_input(draft_input)
            except DomainGraphError as exc:
                logger.warning(
                    "workflow_task_parameter_fallback",
                    extra={
                        "failureStage": "DOMAIN_VALIDATION",
                        "stableErrorCode": exc.code,
                        "fallbackType": "SAFE_PARAMETER_CLARIFICATION",
                        "intent": intent.value,
                    },
                )
                raise WorkflowPlanError("WORKFLOW_PARAMETERS_INCOMPLETE") from None
            drafts.append((intent, prepare_task_draft(prepared)))
        proposal = self._proposal(request, drafts)

        async def call_java(payload: BaseModel) -> BaseModel:
            proposal_payload = WorkflowPlanProposalRequest.model_validate(payload)
            with start_span(
                "agent.workflow_plan.persist",
                attributes={
                    "workflow.plan.stepCount": len(proposal_payload.steps),
                    "workflow.plan.prepareCount": sum(
                        step.type is WorkflowStepType.PREPARE for step in proposal_payload.steps
                    ),
                },
            ) as span:
                try:
                    response = await self._java_client.propose_workflow_plan(
                        request.run_id,
                        request.user_context_ref,
                        proposal_payload.model_dump(mode="json", by_alias=True),
                        traceparent=None,
                    )
                except JavaInternalClientError as exc:
                    code = _map_java_error(exc)
                    span.fail(code)
                    raise WorkflowPlanError(code) from None
                try:
                    plan = WorkflowPlan.model_validate(response.payload)
                except ValidationError:
                    span.fail("WORKFLOW_PLAN_RESPONSE_INVALID")
                    raise WorkflowPlanError("WORKFLOW_PLAN_RESPONSE_INVALID") from None
                self._verify_response(proposal, plan, context)
                span.set("workflow.plan.status", plan.status)
                return plan

        executor = ToolExecutor(
            DEFAULT_TOOL_REGISTRY,
            ToolPolicy(
                allowed_tool_names=frozenset(("workflow_plan.propose",)),
                allowed_tool_groups=frozenset((ToolGroup.TASK_PREPARATION,)),
                allowed_risk_levels=frozenset((RiskLevel.PREPARE,)),
            ),
            {"workflow_plan.propose": WorkflowPlanProposalRequest.model_validate},
            {"workflow_plan.propose": call_java},
            observer=self._observer,
        )
        result = await executor.execute(
            run_id=request.run_id,
            name="workflow_plan.propose",
            version="1",
            payload=proposal,
            counters=counters,
            authorized_tool_groups=context.loaded_tool_groups,
        )
        return WorkflowPlanResult(plan=WorkflowPlan.model_validate(result.output), usage=usage)

    def _proposal(
        self,
        request: ChatRunRequest,
        drafts: list[tuple[Intent, TaskDraftProposal]],
    ) -> WorkflowPlanProposalRequest:
        steps = [
            WorkflowPlanStepProposal(
                step_id="step-1",
                ordinal=1,
                type=WorkflowStepType.QUERY,
                tool_name=WorkflowToolName.CATALOG_INSPECT,
                risk="QUERY",
                success_criteria=(
                    _java_normalized_text("读取当前 Run 已授权的经营空间、商品和竞品快照"),
                ),
                input_refs=("context:catalog",),
                output_ref="observation:catalog",
            )
        ]
        index = 2
        preparation_dependency = "step-1"
        if "对比" in request.message.content or "竞品" in request.message.content:
            steps.append(
                WorkflowPlanStepProposal(
                    step_id="step-2",
                    ordinal=2,
                    type=WorkflowStepType.ANALYZE,
                    tool_name=WorkflowToolName.COMPETITOR_COMPARE,
                    risk="ANALYZE",
                    depends_on=("step-1",),
                    success_criteria=(_java_normalized_text("形成基于当前授权竞品资料的差异比较"),),
                    input_refs=("observation:catalog",),
                    output_ref="observation:competitor-compare",
                )
            )
            index = 3
            preparation_dependency = "step-2"
        prepare_ids: list[str] = []
        for intent, draft in drafts:
            step_id = f"step-{index}"
            prepare_ids.append(step_id)
            steps.append(
                WorkflowPlanStepProposal(
                    step_id=step_id,
                    ordinal=index,
                    type=WorkflowStepType.PREPARE,
                    tool_name=WorkflowToolName.TASK_DRAFT_PROPOSE,
                    risk="PREPARE",
                    depends_on=(preparation_dependency,),
                    success_criteria=(
                        _java_normalized_text(
                            "文章标题候选草稿通过权限、参数和报价校验；尚未生成正文"
                            if intent is Intent.ARTICLE
                            else f"{intent.value} 草稿通过权限、参数和报价校验"
                        ),
                    ),
                    input_refs=("context:product",),
                    output_ref=f"draft:{intent.value.lower()}",
                    task_draft=WorkflowTaskDraftProposal.model_validate(
                        {
                            "taskType": draft.task_type.value,
                            "parameterSchemaVersion": draft.parameter_schema_version,
                            "parameters": draft.parameters,
                        }
                    ),
                )
            )
            index += 1
        steps.append(
            WorkflowPlanStepProposal(
                step_id=f"step-{index}",
                ordinal=index,
                type=WorkflowStepType.SYNTHESIZE,
                tool_name=WorkflowToolName.EVIDENCE_AGGREGATE,
                risk="ANALYZE",
                depends_on=tuple(prepare_ids),
                success_criteria=(_java_normalized_text("汇总各独立草稿及依赖，等待用户逐项确认"),),
                input_refs=tuple(f"observation:{item}" for item in prepare_ids),
                output_ref="observation:workflow-summary",
            )
        )
        proposal_id = uuid5(_WORKFLOW_NAMESPACE, str(request.run_id))
        return WorkflowPlanProposalRequest(
            proposal_id=proposal_id,
            conversation_id=request.conversation_id,
            goal_summary=_java_normalized_text(request.message.content)[:500],
            budget=WorkflowPlanBudget(
                max_steps=len(steps),
                max_replans=1,
                max_model_calls=max(1, min(32, request.budget.max_model_calls)),
                max_tool_calls=len(steps),
                max_provider_calls=len(drafts),
                max_point_cost=min(
                    self._max_point_cost,
                    user_point_ceiling(request.message.content) or self._max_point_cost,
                ),
                deadline_at=request.budget.deadline_at + timedelta(seconds=self._deadline_seconds),
            ),
            steps=tuple(steps),
        )

    @staticmethod
    def _verify_response(
        proposal: WorkflowPlanProposalRequest,
        plan: WorkflowPlan,
        context: AuthorizedRunContext,
    ) -> None:
        if (
            plan.conversation_id != proposal.conversation_id
            or plan.space_id != context.selected_space_id
            or plan.product_id != context.selected_product_id
            or plan.goal_summary != proposal.goal_summary.strip()
            or plan.budget != proposal.budget
            or len(plan.steps) != len(proposal.steps)
        ):
            raise WorkflowPlanError("WORKFLOW_PLAN_RESPONSE_MISMATCH")
        for expected, actual in zip(proposal.steps, plan.steps, strict=True):
            expected_type = None if expected.task_draft is None else expected.task_draft.task_type
            actual_type = None if actual.task_draft is None else actual.task_draft.task_type
            if (
                actual.step_id != expected.step_id
                or actual.ordinal != expected.ordinal
                or actual.type != expected.type
                or actual.tool_name != expected.tool_name.value
                or actual.tool_version != expected.tool_version
                or actual.risk != expected.risk
                or actual.depends_on != expected.depends_on
                or actual.success_criteria != expected.success_criteria
                or actual.input_refs != expected.input_refs
                or actual.output_ref != expected.output_ref
                or actual_type != expected_type
            ):
                raise WorkflowPlanError("WORKFLOW_PLAN_RESPONSE_MISMATCH")


def render_workflow_plan(plan: WorkflowPlan) -> str:
    lines = ["已整理成待确认组合计划(尚未确认、未扣积分、未开始执行):"]
    for step in plan.steps:
        dependency = "、".join(step.depends_on) if step.depends_on else "无"
        draft = ""
        if step.task_draft is not None:
            draft = f"; {step.task_draft.task_type.value} 草稿 {step.task_draft.draft_id}"
        lines.append(
            f"{step.ordinal}. {step.type.value} / {step.tool_name}(依赖: {dependency}){draft}"
        )
    lines.extend(
        (
            f"计划总报价: {plan.total_quoted_points} 积分; "
            f"最高积分上限: {plan.budget.max_point_cost}。",
            f"报价有效至: {plan.confirmation.expires_at.astimezone(UTC).isoformat()}。",
            f"计划编号: {plan.id}。请先检查草稿；确认后将按计划逐项执行。",
        )
    )
    return "\n".join(lines)


def _map_java_error(exc: JavaInternalClientError) -> str:
    return {
        JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE: "WORKFLOW_PLAN_JAVA_UNAVAILABLE",
        JavaInternalErrorCode.CONNECTION_FAILED: "WORKFLOW_PLAN_JAVA_UNAVAILABLE",
        JavaInternalErrorCode.TIMEOUT: "WORKFLOW_PLAN_JAVA_TIMEOUT",
        JavaInternalErrorCode.AUTHENTICATION_REJECTED: "WORKFLOW_PLAN_AUTHENTICATION_REJECTED",
        JavaInternalErrorCode.UPSTREAM_5XX: "WORKFLOW_PLAN_JAVA_UNAVAILABLE",
        JavaInternalErrorCode.HTTP_ERROR: "WORKFLOW_PLAN_REJECTED",
        JavaInternalErrorCode.INVALID_RESPONSE: "WORKFLOW_PLAN_RESPONSE_INVALID",
        JavaInternalErrorCode.INVALID_REQUEST: "WORKFLOW_PLAN_REQUEST_INVALID",
    }[exc.code]

"""One bounded factual review before a generated general answer may be shown."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime
from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

from app.agent.models import BusinessOutcome, BusinessOutcomeStatus
from app.harness.runtime import bind_model_call_boundary
from app.model_gateway.gateway import (
    GatewayUsage,
    ModelGateway,
    ModelGatewayError,
    ModelMessage,
    ModelMessages,
    StructuredCompletionGateway,
    serialize_messages,
)
from app.observability.tracing import start_span
from app.providers.java_context import (
    AuthorizedRunContext,
    general_response_evidence_scope,
    general_response_platform_scope,
)

GeneralReviewReasonCode = Literal[
    "GEO_KNOWLEDGE_INFERENCE",
    "GEO_CAUSAL_INFERENCE",
    "GEO_VISIBILITY_GUARANTEE",
    "UNSUPPORTED_FACT",
    "FALSE_OBSERVATION_CLAIM",
    "FALSE_EXECUTION_CLAIM",
    "SOURCE_SCOPE_VIOLATION",
    "INSTRUCTION_INJECTION",
    "QUESTION_NOT_ADDRESSED",
]
GeneralReviewAnswerTopic = Literal[
    "GENERAL",
    "CATEGORY_SCOPE",
    "SOURCE_EVIDENCE",
    "MENTION_VS_COLLECTION",
    "EFFECT_BOUNDARY",
    "ONBOARDING",
    "MONITOR_ENTRY",
]
GeneralReviewDecisionReason = Literal[
    "ACCEPTED",
    "REJECTED",
    "INVALID_OUTPUT",
    "UNAVAILABLE",
    "BUDGET_EXHAUSTED",
    "MOCK_UNREVIEWED",
    "SCOPED_GUIDANCE",
]
_GEO_REASONS: frozenset[GeneralReviewReasonCode] = frozenset(
    ("GEO_KNOWLEDGE_INFERENCE", "GEO_CAUSAL_INFERENCE", "GEO_VISIBILITY_GUARANTEE")
)
GENERAL_REVIEW_FALLBACK = (
    "这条回复中的部分判断没有足够依据，我先不沿用它。"
    "如果你在核对某次 AI 回答，可以保留提问、完整回答、平台、模式与时间，"
    "再逐项检查回答提到了什么、引用声称什么；缺少记录的部分保持未知。"
    "也可以直接把要解释或修改的原文发来，不必先做监测。"
)
GENERAL_REVIEW_UNAVAILABLE = "本次未能完成回复核对，暂时无法给出可靠答复。请稍后重试。"
GENERAL_TASK_NOT_ADDRESSED = (
    "这次没有完成你的请求，我先不把这条回复当作结果交付。请重试，或补充这项请求缺少的必要信息。"
)
GEO_REVIEW_FALLBACK = (
    "某次有效回答没有提到品牌，只说明这条回答未提及；"
    "采集失败则是没有取得可评估回答，不能当成未提及或零可见度。"
    "这两种情况都不能单独证明 AI 不知道品牌，或说明优化有没有效果。"
    "可以先按平台、模式、时间保存相同范围的问题与完整回答，分别记录有效提及和失败样本；"
    "再核对你自己的公开资料是否准确。这样能核对样本表现与资料质量，不能据此确定遗漏原因。"
)
_TOPIC_FALLBACKS: dict[GeneralReviewAnswerTopic, str] = {
    "CATEGORY_SCOPE": (
        "品类描述本身不是排他规则。比如写“咖啡店”，不等于只能在咖啡问题中出现。"
        "如果门店也卖曲奇、蛋糕，可以在自己的资料里清楚列出这些品类、名称和已确认的信息，"
        "不必为每个品类另起一个品牌。\n\n"
        "这样是把门店介绍写完整，不代表某个平台已经读取这些资料，"
        "也不能保证它在甜品问题中推荐你。实际有没有被提及，要看对应平台、模式和问题的有效回答；"
        "采集失败另记，不能算作未提及。"
    ),
    "SOURCE_EVIDENCE": (
        "不能把“链接存在、网页内容匹配”当成平台实际搜索或使用了它的证明。\n\n"
        "可以依次核对。独立打开网页只确认该页写了什么；"
        "可核验、绑定本次平台请求的原生结构化来源记录，只支持本次采样返回了哪些检索结果；"
        "本次原始回答中的引用标注，只说明回答标注了哪个来源。"
        "检索返回不等于回答采用，引用标注也不等于已经证明模型实际依赖该来源。"
        "只有与所声称的实际使用相匹配的额外证据，才可以作该范围内的判断。\n\n"
        "先保存平台、请求或采样标识、模式、时间、原始回答和原生来源记录，"
        "核对它们能否对应；只有链接、截图或转述时，明确写成“待核验来源主张”，不补出检索或使用结论。"
        "本轮普通对话没有独立打开网页或取得平台检索记录；其他平台或历史请求是否查过，仍需对应证据。"
    ),
    "MENTION_VS_COLLECTION": (
        "“没有提到”和“没采集成功”要分开看。"
        "取得了可评估的有效回答且没有目标名称，才能说该条回答未提及；"
        "超时、失败或未取得回答只能记为未评估，不能计成未提及或零可见度。\n\n"
        "可见度应在同一问题范围、平台和模式下，用有效回答中的目标提及比例描述，"
        "同时单列失败与缺失样本。保留原始问题、回答和时间，才能核对这次样本说了什么；"
        "单次未提及不证明平台不知道、未收录或从未提过这个品牌。"
    ),
    "EFFECT_BOUNDARY": (
        "前后可见度有变化，不足以证明发文或改资料造成了变化；"
        "没有变化也不能证明这些动作无效或与结果没有关系。"
        "没有观察到相应变化时，连“有关联”也不能先下结论。\n\n"
        "可以先保留动作与时间，再在相同问题范围、平台和模式下比较有效回答，"
        "单列失败、缺失和范围变化。这样能描述实际观察到的变化；"
        "若仅观察到同时变化，仍需考虑回答波动和其他变化，不能直接归因或承诺效果。"
    ),
}


class GeneralResponseReviewDecision(BaseModel):
    """No rewritten prose, claims, IDs, or arbitrary diagnostic text from the reviewer."""

    model_config = ConfigDict(extra="forbid", strict=True, frozen=True, populate_by_name=True)
    decision: Literal["ACCEPT", "REJECT"]
    reason_codes: list[GeneralReviewReasonCode] = Field(alias="reasonCodes", max_length=8)
    answer_topic: GeneralReviewAnswerTopic = Field(alias="answerTopic")

    @model_validator(mode="after")
    def require_consistent_reasons(self) -> Self:
        if self.decision == "ACCEPT" and self.reason_codes:
            raise ValueError("accepted review cannot have rejection reasons")
        if self.decision == "REJECT" and not self.reason_codes:
            raise ValueError("rejected review requires at least one reason")
        if len(set(self.reason_codes)) != len(self.reason_codes):
            raise ValueError("review reasons must be distinct")
        return self


@dataclass(frozen=True, slots=True)
class GeneralResponseReviewInput:
    """Caller must apply the existing Harness input/model budgets to these messages."""

    messages: ModelMessages = field(repr=False)
    candidate_text: str = field(repr=False)
    monitor_supported: bool = False
    monitor_preparation_available: bool = False
    project_material_available: bool = False
    geo_overview_path: str | None = field(default=None, repr=False)
    saved_geo_report_path: str | None = field(default=None, repr=False)


@dataclass(frozen=True, slots=True)
class GeneralResponseReviewResult:
    text: str = field(repr=False)
    usage: GatewayUsage
    accepted: bool
    decision_reason: GeneralReviewDecisionReason
    reason_codes: tuple[GeneralReviewReasonCode, ...] = ()
    answer_topic: GeneralReviewAnswerTopic = "GENERAL"

    @property
    def business_outcome(self) -> BusinessOutcome:
        """Delivery truth is runtime-owned, never an extra model-selected review field."""
        if self.decision_reason == "ACCEPTED" or (
            self.decision_reason in {"REJECTED", "SCOPED_GUIDANCE"}
            and self.answer_topic != "GENERAL"
        ):
            # A bounded, directly relevant guide can answer the question even though
            # the original candidate was rejected. An unavailable review cannot.
            return BusinessOutcome(status=BusinessOutcomeStatus.ANSWERED)
        return BusinessOutcome(
            status=BusinessOutcomeStatus.FAILED,
            reason_code="READONLY_SYNTHESIS_UNAVAILABLE",
            failure_stage="SYNTHESIS",
        )


def build_general_response_review(
    messages: ModelMessages,
    candidate_text: str,
    review_prompt: str,
    *,
    context: AuthorizedRunContext | None = None,
) -> GeneralResponseReviewInput:
    """Attach path-computed evidence; original messages/candidate remain untrusted data."""
    platform_scope = general_response_platform_scope(context)
    payload = json.dumps(
        {
            "evidenceScope": general_response_evidence_scope(),
            "platformScope": platform_scope,
            "sourceMessages": serialize_messages(messages),
            "candidateText": candidate_text,
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return GeneralResponseReviewInput(
        messages=(
            ModelMessage(role="system", content=review_prompt),
            ModelMessage(role="user", content=payload),
        ),
        candidate_text=candidate_text,
        monitor_supported=platform_scope["geoMonitorSupported"],
        monitor_preparation_available=platform_scope["geoMonitorPreparationAvailable"],
        project_material_available=platform_scope["providedProjectMaterialAvailable"],
        geo_overview_path=None
        if context is None or context.geo_insight_context is None
        else context.geo_insight_context.links.overview_path,
        saved_geo_report_path=None
        if (
            context is None
            or context.geo_insight_context is None
            or context.geo_insight_context.status != "READY"
            or context.geo_insight_context.report is None
        )
        else context.geo_insight_context.links.report_path,
    )


def _entry_guidance(prepared: GeneralResponseReviewInput, topic: GeneralReviewAnswerTopic) -> str:
    """Only fixed prose and an already-validated Java navigation target, no model facts."""
    if topic == "ONBOARDING":
        if prepared.project_material_available:
            return (
                "不用从零准备，你已经提供的项目资料可以先整理起来，缺的信息我不会编。"
                "先把已知的名称、主营内容和地区整理清楚，再准备顾客可能会问的问题。"
                "现在只补一个重点，你最希望哪类顾客、在什么需求下找到你？"
                "这里只是在整理下一步，还没有开始监测或发布。"
            )
        return (
            "不用一次准备齐。先告诉我店铺或品牌对外叫什么，其他资料可以一步步补。"
            "接下来再补所在地区和主要提供什么，把它们整理成资料，然后准备顾客可能会问的问题。"
            "没有给出的价格、营业时间等信息，我不会替你编；目前还没有开始监测或发布。"
        )
    if prepared.saved_geo_report_path is not None:
        next_step = (
            "如果想了解新情况，再准备一次监测、核对范围和报价，并按实际授权执行。"
            if prepared.monitor_preparation_available
            else "如果想了解新情况，请先核对当前项目的监测配置是否可用，"
            "再核对范围、报价与执行授权。"
            if prepared.monitor_supported
            else "如果想了解新情况，请先选择已启用的 GEO 智能体，再准备问题、核对报价与执行授权。"
        )
        return (
            f"已有一份监测报告，可以先[查看已保存的报告]({prepared.saved_geo_report_path})。"
            "请先核对报告时间、问题范围和平台，再看当时的有效回答是否提到你；"
            "历史样本不能证明 AI 现在一定知道或推荐你，也不能把采集失败当成未提及。"
            f"{next_step}不用为了查看旧报告重复新建监测。"
            "本轮没有开始新的监测、扣积分或发布，也没有替你解读报告的具体结果。"
        )
    if prepared.monitor_preparation_available:
        entry = (
            f"你可以在[监测入口]({prepared.geo_overview_path})准备监测。"
            if prepared.geo_overview_path is not None
            else "可以先进入 GEO 监测准备，核对本次要监测的对象和顾客问题。"
        )
        return (
            "GEO 智能体可以监测各平台回答里是否提到你，但这轮还没有取得实际结果，"
            "所以我不能现在就说 AI 已经知道或推荐了你。"
            f"{entry}先核对问题和平台，系统会提供本次报价；"
            "有有效授权后才会实际运行。当前没有开始监测、扣积分或发布，"
            "也不需要你先去各个平台逐个截图。"
        )
    if prepared.monitor_supported:
        return (
            "GEO 智能体支持监测，不过当前选择暂不能准备这项监测，本轮也没有取得实际结果。"
            "先核对当前项目和监测配置是否可用，再准备问题、核对本次报价及执行授权。"
            "现在没有开始监测、扣积分或发布，不能把没有结果当成 AI 不知道你。"
        )
    return (
        "本轮没有取得你的实际监测结果，也没有可用的 GEO 监测准备能力。"
        "如果要实际监测，请先选择已启用的 GEO 智能体，再准备监测对象和顾客问题。"
        "这不代表平台不能监测；当前没有开始监测、扣积分或发布。"
    )


def general_review_fallback(
    decision_reason: GeneralReviewDecisionReason,
    *,
    usage: GatewayUsage | None = None,
    reason_codes: tuple[GeneralReviewReasonCode, ...] = (),
    answer_topic: GeneralReviewAnswerTopic = "GENERAL",
) -> GeneralResponseReviewResult:
    """Fail closed without exposing a rejected candidate or pretending a model was free."""
    answer_topic = answer_topic if answer_topic in _TOPIC_FALLBACKS else "GENERAL"
    text = GENERAL_REVIEW_UNAVAILABLE
    if decision_reason in {"REJECTED", "SCOPED_GUIDANCE"}:
        text = _TOPIC_FALLBACKS.get(answer_topic)
        if (
            decision_reason == "REJECTED"
            and answer_topic == "GENERAL"
            and "QUESTION_NOT_ADDRESSED" in reason_codes
        ):
            text = GENERAL_TASK_NOT_ADDRESSED
        text = text or (
            GEO_REVIEW_FALLBACK
            if _GEO_REASONS.intersection(reason_codes)
            else GENERAL_REVIEW_FALLBACK
        )
    return GeneralResponseReviewResult(
        text=text,
        usage=usage if usage is not None else GatewayUsage(),
        accepted=False,
        decision_reason=decision_reason,
        reason_codes=reason_codes,
        answer_topic=answer_topic
        if decision_reason in {"REJECTED", "SCOPED_GUIDANCE"}
        else "GENERAL",
    )


async def review_general_response(
    gateway: ModelGateway,
    prepared: GeneralResponseReviewInput,
    *,
    deadline_at: datetime,
) -> GeneralResponseReviewResult:
    """Make at most one HTTP model request; cancellation and Harness failures propagate."""
    with start_span("agent.general_response.review") as span:
        result = await _review(gateway, prepared, deadline_at=deadline_at)
        span.set("review.accepted", result.accepted)
        span.set("review.decision_reason", result.decision_reason)
        span.set("review.reason_codes", ",".join(sorted(result.reason_codes)))
        span.set("review.answer_topic", result.answer_topic)
        span.set("review.business_outcome", result.business_outcome.status.value)
        if result.business_outcome.failure_stage is not None:
            span.set("review.failure_stage", result.business_outcome.failure_stage)
        return result


async def _review(
    gateway: ModelGateway,
    prepared: GeneralResponseReviewInput,
    *,
    deadline_at: datetime,
) -> GeneralResponseReviewResult:
    if gateway.is_mock:
        return general_review_fallback("MOCK_UNREVIEWED")
    if not isinstance(gateway, StructuredCompletionGateway):
        return general_review_fallback("UNAVAILABLE")

    usage = GatewayUsage()
    try:
        with bind_model_call_boundary(max_calls=1, deadline_at=deadline_at):
            parsed, usage = await gateway.complete_structured(
                prepared.messages,
                GeneralResponseReviewDecision,
                operation="readonly_general_response_review",
                max_tokens=400,
                error_code="GENERAL_RESPONSE_REVIEW_INVALID",
                repair_instruction=(
                    "Return only decision, reasonCodes and required answerTopic from the fixed "
                    "enum; never rewrite the answer."
                ),
                repair_attempts=0,
            )
        # Revalidate even constructed/mutated model instances; no trusted-review bypass.
        decision = GeneralResponseReviewDecision.model_validate(parsed.model_dump(by_alias=True))
    except ModelGatewayError as exc:
        # Provider adapters may wrap Harness failures after an earlier HTTP request.
        # Keep terminal deadlines terminal, with the original accumulated usage.
        if exc.code in {"RUN_DEADLINE_EXCEEDED", "RUN_DEADLINE_INVALID"}:
            raise
        if exc.code in {"MODEL_CALL_BUDGET_EXCEEDED", "INPUT_TOKEN_BUDGET_EXCEEDED"}:
            return general_review_fallback("BUDGET_EXHAUSTED", usage=exc.usage)
        return general_review_fallback(
            "INVALID_OUTPUT" if exc.structured_response_failure else "UNAVAILABLE",
            usage=exc.usage,
        )
    except ValidationError:
        return general_review_fallback("INVALID_OUTPUT", usage=usage)

    # A semantic topic is still chosen by the one bounded reviewer, not a keyword router.
    # Once source-proof is identified, an ACCEPT cannot release contradictory free-form
    # claims: give the fixed evidence ladder, and do not count this as original-answer acceptance.
    if decision.answer_topic == "MONITOR_ENTRY" or (
        decision.answer_topic == "ONBOARDING" and decision.decision == "REJECT"
    ):
        return GeneralResponseReviewResult(
            text=_entry_guidance(prepared, decision.answer_topic),
            usage=usage,
            accepted=False,
            decision_reason="SCOPED_GUIDANCE",
            reason_codes=tuple(decision.reason_codes),
            answer_topic=decision.answer_topic,
        )
    if decision.answer_topic == "SOURCE_EVIDENCE":
        return general_review_fallback(
            "SCOPED_GUIDANCE",
            usage=usage,
            reason_codes=tuple(decision.reason_codes),
            answer_topic=decision.answer_topic,
        )
    if decision.decision == "REJECT":
        return general_review_fallback(
            "REJECTED",
            usage=usage,
            reason_codes=tuple(decision.reason_codes),
            answer_topic=decision.answer_topic,
        )
    return GeneralResponseReviewResult(
        text=prepared.candidate_text,
        usage=usage,
        accepted=True,
        decision_reason="ACCEPTED",
        answer_topic=decision.answer_topic,
    )

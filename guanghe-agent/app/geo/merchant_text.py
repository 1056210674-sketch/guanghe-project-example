"""Bounded merchant prose projection; evidence and protocol identities stay unchanged."""

from __future__ import annotations

import re
from typing import cast

from pydantic import BaseModel

from app.geo.models import GeoObservation, GeoObservationStatus, GeoSynthesisAnalysis
from app.geo.worker_models import GeoSynthesisContext

PLATFORM_NAMES = {
    "DOUBAO": "豆包",
    "DEEPSEEK": "DeepSeek",
    "QWEN": "通义千问",
    "WENXIN": "文心一言",
    "KIMI": "Kimi",
    "YUANBAO": "腾讯元宝",
    "ZHIPU": "智谱",
}
_TECHNICAL_WORDS = {
    **PLATFORM_NAMES,
    "Java": "系统",
    "explicit-name-mention-v1": "名称核对规则",
    "targetMentioned": "名称提及结果",
    "visibilityEvidence": "完整回答的名称核对结果",
    "matchingVersion": "名称核对规则",
    "targetStatus": "监测对象核验情况",
    "authorizedContext.monitoringTarget": "监测对象",
    "monitoringTarget": "监测对象",
    "authorizedContext": "本次监测资料",
    "observations": "本次回答",
    "methodVersion": "分析方法",
    "stableErrorCode": "未完成原因",
    "missingSamples": "未完成回答",
    "recommendations": "内容创作建议",
    "contentBlueprint": "创作主题",
    "omittedCitationCount": "节选中省略的引用数量",
    "competitorSetVersion": "对照品牌列表版本",
    "competitors": "对照品牌",
    "PRODUCT": "商品",
    "BRAND": "品牌",
    "TARGET_UNRESOLVED": "监测对象尚未明确",
    "AVAILABLE": "可核验",
    "SUCCEEDED": "已取得回答",
    "FAILED": "未取得回答",
    "geo-synthesis-v1": "智能解读",
    "geo-deterministic-v1": "已保存证据汇总",
}


def merchant_synthesis(
    analysis: GeoSynthesisAnalysis,
    observations: tuple[GeoObservation, ...],
    context: BaseModel | None,
    visibility: BaseModel | None,
) -> GeoSynthesisAnalysis:
    """Project validated analysis once, without retries or changes to original evidence."""
    trusted = context if isinstance(context, GeoSynthesisContext) else None
    replacements = dict(_TECHNICAL_WORDS)
    identities: dict[str, str] = {}
    protected: set[str] = set()
    if trusted is not None:
        identities.update({trusted.topic.id: "当前专题", trusted.topic.space_id: "当前经营空间"})
        if trusted.topic.product_id:
            identities[trusted.topic.product_id] = "当前商品"
        protected.add(trusted.topic.name)
        if trusted.monitoring_target is not None:
            protected.update((trusted.monitoring_target.name, *trusted.monitoring_target.aliases))
        for competitor in trusted.competitors:
            identities[competitor.id] = competitor.name
            protected.update((competitor.name, *competitor.aliases))
        identities.update(
            (question.id, f"问题{index}") for index, question in enumerate(trusted.questions, 1)
        )
    for index, item in enumerate(observations, 1):
        label = identities.setdefault(item.question_id, f"问题{index}")
        replacements[f"{item.question_id}:{item.platform_code.value}:{item.sample_no}"] = (
            f"{label} ({PLATFORM_NAMES[item.platform_code.value]}，第{item.sample_no}轮)"
        )
        if item.stable_error_code:
            replacements[item.stable_error_code] = "本次未取得回答"
        for citation in item.citations:
            protected.add(citation.url)
            if citation.domain:
                protected.add(citation.domain)
    # Never erase arbitrary numbers or short IDs that could equally be a price/model.
    replacements.update((key, value) for key, value in identities.items() if len(key) >= 12)
    token_pattern = re.compile(
        r"(?<![A-Za-z0-9_])(?:"
        + "|".join(re.escape(key) for key in sorted(replacements, key=len, reverse=True))
        + r")(?![A-Za-z0-9_])"
    )
    protected_pattern = re.compile(
        "("
        + "|".join(re.escape(value) for value in sorted(protected, key=len, reverse=True))
        + ("|" if protected else "")
        + r"https?://[^\s<>\u3000-\u303f\uff00-\uffef]+|[¥￥$]\s*[\d,.]+|\d[\d,.]*\s*(?:元|%))"
    )

    def text(value: str, limit: int) -> str:
        parts = protected_pattern.split(value)
        for index in range(0, len(parts), 2):
            part = parts[index]
            part = re.sub(
                r"competitorSetVersion\s*(?:为|[:=])\s*null", "未记录对照品牌列表版本", part
            )
            part = re.sub(r"competitors\s*(?:为|[:=])\s*(?:空|\[\]|null)", "未设置对照品牌", part)
            for key, label in identities.items():
                part = re.sub(
                    r"(?:questionId|topicId|productId|spaceId|competitorId|问题ID|专题ID|商品ID)"
                    + r"\s*[:=\uff1a]?\s*[`\"']?"
                    + re.escape(key)
                    + r"(?![0-9])",
                    label,
                    part,
                )
            part = re.sub(
                r"targetMentioned\s*[:=\uff1a]\s*(true|false|null)(?![A-Za-z0-9_])",
                lambda match: {
                    "true": "全文明确提及",
                    "false": "全文未明确提及",
                    "null": "暂不可核验",
                }[match[1]],
                part,
            )
            part = token_pattern.sub(lambda match: replacements[match[0]], part)
            # Stable internal error namespaces are never useful merchant prose.
            part = re.sub(
                r"(?<![A-Za-z0-9_])(?:GEO|DEEPSEEK|MODEL|PROVIDER)_[A-Z][A-Z0-9_]+"
                r"(?![A-Za-z0-9_])",
                "本次未取得完整结果",
                part,
            )
            parts[index] = part
        result = "".join(parts)
        # Do not split product names, prices or evidence URLs just to fit a field.
        return result if len(result) <= limit else "请查看对应问题的平台回答及引用来源。"

    values = analysis.model_dump()
    values["summary"] = text(analysis.summary, 2_000)
    for field in (
        "coverage_gaps",
        "citation_structure",
        "competitor_signals",
        "findings",
        "content_blueprint",
    ):
        values[field] = tuple(text(value, 1_000) for value in values[field])
    values["recommendations"] = tuple(
        {
            "priority": item.priority,
            "title": text(item.title, 300),
            "rationale": text(item.rationale, 1_000),
        }
        for item in analysis.recommendations
    )

    facts = visibility.model_dump(mode="json", by_alias=True) if visibility else {}
    units = facts.get("units", [])
    flags = [
        cast(dict[str, object], item).get("targetMentioned")
        for item in units
        if isinstance(item, dict)
    ]
    valid_flags = [value for value in flags if isinstance(value, bool)]
    unknown_target = trusted is not None and trusted.monitoring_target is None
    zero_mentions = bool(valid_flags) and not any(valid_flags)
    if unknown_target or facts.get("targetStatus") == "TARGET_UNRESOLVED":
        values["summary"] = (
            "本次已保存的平台回答可供核对，但监测对象的公开名称尚未明确，暂不能判断商品或品牌的名称提及情况。"
            "下一步: 先核对经营资料中的公开名称与常用别名，"
            "再查看下方问题及平台回答是否符合本次监测目标。"
            "资料调整只影响后续监测，不会改写本次报告。"
        )
    elif zero_mentions:
        values["summary"] = (
            f"本次 {len(valid_flags)} 个可核验的有效回答中，未明确提及已确认的监测对象名称。"
            "这只反映本次问题与平台范围，不代表整个市场的认知，也不能据此判断商品质量或推广效果。"
            "下一步: 先核对商品或品牌的公开名称与常用别名，并查看下方原始回答；"
            "后续可按相同问题、平台和采样方式继续监测，创建任务前仍需确认报价。"
        )
    elif any(item.status is GeoObservationStatus.FAILED for item in observations):
        action = "下一步: 查看未完成的平台与问题及其已保存证据；如需再次监测，先确认范围与报价。"
        if len(values["summary"]) + len(action) + 2 <= 2_000:
            values["summary"] += "\n\n" + action
    if unknown_target or zero_mentions or facts.get("targetStatus") == "TARGET_UNRESOLVED":
        # This authorized context has identity but no product facts. Do not expose
        # creation buttons for unsupported marketing, including diagnostic actions.
        values["recommendations"] = ()
        values["content_blueprint"] = ()
    return GeoSynthesisAnalysis.model_validate(values)

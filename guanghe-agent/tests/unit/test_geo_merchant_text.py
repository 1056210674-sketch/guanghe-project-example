"""Merchant prose is a deterministic projection, not an extra model request."""

import copy
import json

import pytest

from app.geo.merchant_text import merchant_synthesis
from app.geo.models import GeoSynthesisAnalysis
from app.geo.recovery import GeoSynthesisRecoveryRequest

# Reuse the signed recovery fixtures; these test helpers are not application APIs.
from tests.unit.test_geo_synthesis_recovery import (
    _analysis,  # pyright: ignore[reportPrivateUsage]
    _payload,  # pyright: ignore[reportPrivateUsage]
)


def test_projection_maps_only_known_ids_and_internal_words_preserves_evidence_and_product() -> None:
    payload = _payload()
    question_id = "1900012345678902101"
    payload["authorizedContext"]["questions"][0]["id"] = question_id
    for item in payload["observations"] + payload["visibilityEvidence"]["units"]:
        item["questionId"] = question_id
    product = f"Java杯{question_id} Pro 2026"
    payload["authorizedContext"]["monitoringTarget"]["name"] = product
    url = f"https://shop.example/{question_id}?price=199&platform=DOUBAO"
    payload["observations"][0]["citations"][0]["url"] = url
    source = GeoSynthesisRecoveryRequest.model_validate_json(json.dumps(payload))
    original = source.model_dump_json()
    analysis = GeoSynthesisAnalysis.model_validate_json(
        json.dumps(
            {
                **_analysis(),
                "summary": f"Java 按 explicit-name-mention-v1 核验 {question_id}:DOUBAO:1，"
                f"targetMentioned=false，问题ID {question_id}；GEO_PROVIDER_TIMEOUT。"
                f"商品 {product} 价格 ￥199.00，SKU1900012345678902101；来源 {url}。",
                "findings": [
                    "questionId=101 的说明仅是普通数字价格101元，未知编号99999999999999保留。"
                ],
                "recommendations": [
                    {
                        "priority": "P1",
                        "title": "核对DOUBAO的targetMentioned",
                        "rationale": "Java核对",
                    }
                ],
            }
        )
    )
    projected = merchant_synthesis(
        analysis, source.observations, source.authorized_context, source.visibility_evidence
    )
    assert "问题1 (豆包，第1轮)" in projected.summary
    assert "全文未明确提及" in projected.summary
    assert "explicit-name-mention-v1" not in projected.summary
    assert "GEO_PROVIDER_TIMEOUT" not in projected.summary
    assert product in projected.summary and url in projected.summary
    assert "SKU1900012345678902101" in projected.summary
    assert "￥199.00" in projected.summary and "101元" in projected.findings[0]
    assert "99999999999999" in projected.findings[0]
    assert projected.recommendations[0].title == "核对豆包的名称提及结果"
    assert source.model_dump_json() == original


def test_zero_mentions_provides_nondestructive_next_actions_and_no_creation_buttons() -> None:
    payload = _payload()
    payload["visibilityEvidence"]["units"][0]["targetMentioned"] = False
    payload["observations"][0]["answerText"] = "对照品牌的回答。"
    source = GeoSynthesisRecoveryRequest.model_validate_json(json.dumps(payload))
    analysis = GeoSynthesisAnalysis.model_validate_json(
        json.dumps(
            {
                **_analysis(),
                "summary": "没有知名度，应该立即买广告。",
                "recommendations": [
                    {"priority": "P0", "title": "发布促销文章", "rationale": "零提及"}
                ],
                "contentBlueprint": ["主推无依据卖点"],
            }
        )
    )
    projected = merchant_synthesis(
        analysis, source.observations, source.authorized_context, source.visibility_evidence
    )
    assert "1 个可核验的有效回答" in projected.summary
    assert "下一步" in projected.summary and "公开名称" in projected.summary
    assert "相同问题、平台和采样方式" in projected.summary and "确认报价" in projected.summary
    assert "买广告" not in projected.summary
    assert projected.recommendations == projected.content_blueprint == ()
    assert "2 个可核验" not in projected.summary  # Failed answers are not a denominator.


def test_unknown_target_does_not_promote_topic_to_product_or_offer_content_actions() -> None:
    payload = _payload()
    payload["authorizedContext"]["monitoringTarget"] = None
    payload["visibilityEvidence"]["targetStatus"] = "TARGET_UNRESOLVED"
    for item in payload["visibilityEvidence"]["units"]:
        item["targetMentioned"] = None
    source = GeoSynthesisRecoveryRequest.model_validate_json(json.dumps(payload))
    projected = merchant_synthesis(
        GeoSynthesisAnalysis.model_validate_json(json.dumps(_analysis())),
        source.observations,
        source.authorized_context,
        source.visibility_evidence,
    )
    assert "公开名称尚未明确" in projected.summary
    assert "不会改写本次报告" in projected.summary
    assert "零提及" not in projected.summary and "可见度为0" not in projected.summary


def test_no_full_answer_flags_does_not_turn_excerpt_absence_into_zero() -> None:
    source = GeoSynthesisRecoveryRequest.model_validate_json(json.dumps(_payload()))
    analysis = GeoSynthesisAnalysis.model_validate_json(
        json.dumps(
            {
                **_analysis(),
                "summary": "提供的节选中未观察到目标，不能据此推断全文。",
                "recommendations": [
                    {
                        "priority": "P1",
                        "title": "围绕已证实的通勤用途说明选购条件",
                        "rationale": "成功回答明确描述通勤用途",
                    }
                ],
                "contentBlueprint": ["据已有通勤回答梳理条件，不编造性能"],
            }
        )
    )
    projected = merchant_synthesis(analysis, source.observations, source.authorized_context, None)
    assert "提供的节选" in projected.summary
    assert projected.recommendations == analysis.recommendations
    assert projected.content_blueprint == analysis.content_blueprint


@pytest.mark.parametrize(
    "field",
    [
        "summary",
        "findings",
        "coverageGaps",
        "citationStructure",
        "competitorSignals",
        "contentBlueprint",
    ],
)
def test_all_prose_fields_map_internal_tokens_without_touching_real_numbers(field: str) -> None:
    source = GeoSynthesisRecoveryRequest.model_validate_json(json.dumps(_payload()))
    value = "DOUBAO questionId=101 targetMentioned=true；Java; 499元；[1]；2026款"
    data = copy.deepcopy(_analysis())
    data[field] = value if field == "summary" else [value]
    projected = merchant_synthesis(
        GeoSynthesisAnalysis.model_validate_json(json.dumps(data)),
        source.observations,
        source.authorized_context,
        None,
    )
    output = projected.model_dump(mode="json", by_alias=True)[field]
    rendered = output if isinstance(output, str) else output[0]
    assert "豆包 问题1 全文明确提及" in rendered
    assert all(part in rendered for part in ("499元", "[1]", "2026款"))
    assert "Java" not in rendered


def test_projection_never_splits_evidence_literals_to_satisfy_bounds() -> None:
    source = GeoSynthesisRecoveryRequest.model_validate_json(json.dumps(_payload()))
    analysis = GeoSynthesisAnalysis.model_validate_json(
        json.dumps(
            {
                **_analysis(),
                "findings": ["101:DOUBAO:1 " * 75],
                "summary": "事实" * 1000,
            }
        )
    )
    projected = merchant_synthesis(analysis, source.observations, source.authorized_context, None)
    assert len(projected.summary) <= 2000
    assert all(len(value) <= 1000 for value in projected.findings)


def test_observed_internal_fields_are_merchant_labels_not_discarded_evidence() -> None:
    source = GeoSynthesisRecoveryRequest.model_validate_json(json.dumps(_payload()))
    data = {
        **_analysis(),
        "findings": [
            "recommendations与contentBlueprint留空；omittedCitationCount=8；"
            "competitorSetVersion为null，competitors为空，PRODUCT与BRAND；"
            "targetMentioned=false但不代表质量差，GEO_NEW_FAILURE导致回答未完成。"
        ],
    }
    projected = merchant_synthesis(
        GeoSynthesisAnalysis.model_validate_json(json.dumps(data)),
        source.observations,
        source.authorized_context,
        None,
    )
    finding = projected.findings[0]
    assert "节选中省略的引用数量=8" in finding
    assert "未设置对照品牌" in finding and "商品与品牌" in finding
    assert "全文未明确提及" in finding
    assert all(
        token not in finding
        for token in (
            "recommendations",
            "contentBlueprint",
            "omittedCitationCount",
            "competitorSetVersion",
            "competitors",
            "null",
            "targetMentioned",
            "GEO_NEW_FAILURE",
        )
    )

"""Full-answer match flags survive excerpt projection; no live providers."""

import asyncio
import hashlib
import json
from copy import deepcopy
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import httpx
import pytest
from pydantic import SecretStr, ValidationError

from app.common.settings import ProviderSettings
from app.geo.diagnostic_analysis import (
    _REPAIR,  # pyright: ignore[reportPrivateUsage]
    DiagnosticAnalysisRequest,
    DiagnosticSample,
    _grounded_schema,  # pyright: ignore[reportPrivateUsage]
    _measured_facts,  # pyright: ignore[reportPrivateUsage]
    _project_evidence,  # pyright: ignore[reportPrivateUsage]
    analyze_diagnostic,
    diagnostic_analysis_prompt,
)
from app.harness.runtime import estimate_model_messages
from app.model_gateway.deepseek import DeepSeekAdapter
from app.model_gateway.gateway import (
    ModelMessage,
    diagnostic_analysis_json_schema_messages,
    diagnostic_json_request_budget_messages,
    diagnostic_structured_repair_reserve,
)
from tests.unit.test_geo_diagnostic_delivery import (
    _payload,  # pyright: ignore[reportPrivateUsage]
    _report,  # pyright: ignore[reportPrivateUsage]
)


def test_v3_editorial_guidance_keeps_published_v2_bundle_immutable() -> None:
    bundle = (
        Path(__file__).resolve().parents[2] / "app/prompts/bundles/geo-diagnostic-analysis-v2.yaml"
    )
    assert hashlib.sha256(bundle.read_bytes().replace(b"\r\n", b"\n")).hexdigest() == (
        "a89aafdd8e470dba82e0d233c3fdea7cf0d54c6f14dc4cc8e094cd9231d72a76"
    )
    prompt = diagnostic_analysis_prompt()
    for guidance in ("摘要约200字", "通常5-8项", "不强凑数", "20-60字", "通常3-6个", "闭合 JSON"):
        assert guidance in prompt


def test_v4_keeps_published_v3_bundle_and_qualitative_count_guardrails() -> None:
    bundle = (
        Path(__file__).resolve().parents[2] / "app/prompts/bundles/geo-diagnostic-analysis-v3.yaml"
    )
    assert hashlib.sha256(bundle.read_bytes().replace(b"\r\n", b"\n")).hexdigest() == (
        "4fce5a368724629487d759d44b916cae272575c48caf8b2aad3985a2f7c39afc"
    )
    prompt = diagnostic_analysis_prompt()
    for rule in (
        "不是词出现次数",
        "summary/findings 侧重定性",
        "全部/均/各",
        "模型视图",
        "只有确切证据才否定信息",
    ):
        assert rule in prompt


def test_v5_keeps_published_v4_bytes_and_does_not_bake_customer_or_numeric_examples() -> None:
    bundle = (
        Path(__file__).resolve().parents[2] / "app/prompts/bundles/geo-diagnostic-analysis-v4.yaml"
    )
    assert hashlib.sha256(bundle.read_bytes().replace(b"\r\n", b"\n")).hexdigest() == (
        "77babf22a867d21ecaa2f1ee25c3bd983a4b575b1a209c7e1b348321de84b37c"
    )
    prompt = diagnostic_analysis_prompt()
    assert "库迪" not in prompt and "Cotti" not in prompt and "3/4" not in prompt


def test_v5_guides_balanced_recommendation_evidence_dates_and_friendly_prose() -> None:
    prompt = diagnostic_analysis_prompt()
    for guidance in (
        "诊断不预设负面或默认信息缺口",
        "区分名字提及、列入推荐、本地实证三层",
        "任一展示回答明确推荐自有品牌",
        "仅字面提及/未独立推荐",
        "引用正向与负向证据，不遗漏反例",
        "比较只限同一回答且双方引文明确支持",
        "不泛化平台偏好、排序或效果",
        "引文须直接支持实质主张",
        "无相关证据的建议省略",
        "不能用业务支撑平台等无关引文包装",
        "只有同期间、同口径确实相互冲突才称矛盾",
        "客户正文用友好自然用语",
        "不把未确认说成不存在",
    ):
        assert guidance in prompt


def test_v5_editorial_guidance_does_not_add_rigid_output_lexical_rejection() -> None:
    payload = DiagnosticAnalysisRequest.model_validate(_payload())
    report = _report()
    report["summary"] = (
        "profile.website、shownCount、matrix、冲突和未独立推荐属于文案指引，不增加输出关键词拒绝器。"
    )
    assert _grounded_schema(payload.evidence).model_validate(report)


def test_v6_keeps_published_v5_bytes_and_all_semantic_guidance() -> None:
    bundle = (
        Path(__file__).resolve().parents[2] / "app/prompts/bundles/geo-diagnostic-analysis-v5.yaml"
    )
    assert hashlib.sha256(bundle.read_bytes().replace(b"\r\n", b"\n")).hexdigest() == (
        "9b2ab302111b5d71edf36cb594246876ef9d475c2169305d837d428af5223d1c"
    )
    published = bundle.read_text(encoding="utf-8").splitlines()[2:]
    current = (
        bundle.with_name("geo-diagnostic-analysis-v6.yaml")
        .read_text(encoding="utf-8")
        .splitlines()[2:]
    )
    for line in published:
        if any(key in line for key in ("brandIdOrder 列", "matrixByStatus[status]", "仅事实压缩")):
            continue  # The same reversible index instructions are compacted, not removed.
        assert line.strip() in [value.strip() for value in current]


def test_v6_standard_json_guidance_and_repair_preserve_exact_decoded_quotes() -> None:
    prompt = diagnostic_analysis_prompt()
    for guidance in (
        "仅标准 JSON 对象",
        "ASCII 双引号",
        "双引号/反斜杠/换行须转义",
        "无围栏/JS对象/注释/NaN/Infinity/尾逗号/尾随字符",
        "单行连续原文",
        "quote 解码不删改",
    ):
        assert guidance in prompt
    assert "转义双引号/反斜杠/换行" in _REPAIR
    assert "引文不改" in _REPAIR


def test_v7_json_mode_keeps_v6_immutable_and_current_semantic_and_encoding_constraints() -> None:
    bundle = (
        Path(__file__).resolve().parents[2] / "app/prompts/bundles/geo-diagnostic-analysis-v6.yaml"
    )
    assert hashlib.sha256(bundle.read_bytes().replace(b"\r\n", b"\n")).hexdigest() == (
        "9bf1eb47b30cc2b6b7aa9e731c13561bcb1ee9ef8b3b86f65afdc88a90222d3b"
    )
    prompt = diagnostic_analysis_prompt()
    assert "不调用工具" in prompt
    assert "JSON Schema" in prompt
    assert "analyze_geo_diagnostic" not in prompt
    assert "无围栏/JS对象/注释/NaN/Infinity/尾逗号/尾随字符" in prompt


def test_v8_keeps_v7_bytes_and_changes_only_the_preparation_stage_instruction() -> None:
    bundle = (
        Path(__file__).resolve().parents[2] / "app/prompts/bundles/geo-diagnostic-analysis-v7.yaml"
    )
    assert hashlib.sha256(bundle.read_bytes().replace(b"\r\n", b"\n")).hexdigest() == (
        "e13c1ce0286d329ae103664f5f67cc75ac70ed3bcdc4e85a17af519d6e170d6b"
    )
    previous = "\n".join(line[2:] for line in bundle.read_text(encoding="utf-8").splitlines()[2:])
    version_eight = "\n".join(
        line[2:]
        for line in bundle.with_name("geo-diagnostic-analysis-v8.yaml")
        .read_text(encoding="utf-8")
        .splitlines()[2:]
    )
    assert version_eight == previous.replace(
        "准备阶段警告不泛化缺来源/资料。", "准备提示非本批证据/限制。"
    )


def test_v9_keeps_v8_immutable_and_scopes_each_claim_to_its_answer_and_region() -> None:
    bundle = (
        Path(__file__).resolve().parents[2] / "app/prompts/bundles/geo-diagnostic-analysis-v8.yaml"
    )
    assert hashlib.sha256(bundle.read_bytes().replace(b"\r\n", b"\n")).hexdigest() == (
        "38381ce818adcac49e92d16e2e5d4dc59055b0bc01e61709c1bc2be9b3a2cd0f"
    )
    prompt = diagnostic_analysis_prompt()
    for guidance in (
        "逐个核对并列主张的回答归属与量词",
        "不把单份事实扩展到同平台所有回答",
        "全国/全球规模仅作品牌背景",
        "不能整体称为本地实证",
        "excerptClipped 仅指供解读使用的回答摘录被裁剪",
        "不是原始回答被截断或采集失败",
        "客户限制说明也不用 Java、Python、业务控制面等实现术语",
    ):
        assert guidance in prompt
    assert all(name not in prompt for name in ("库迪", "瑞幸", "广州", "KIMI", "DEEPSEEK"))


def test_v10_preserves_published_v9_and_guides_per_unit_and_per_group_attribution() -> None:
    bundle = (
        Path(__file__).resolve().parents[2] / "app/prompts/bundles/geo-diagnostic-analysis-v9.yaml"
    )
    assert hashlib.sha256(bundle.read_bytes().replace(b"\r\n", b"\n")).hexdigest() == (
        "cb788ce135192afd7c5dfea0d8dd86a2bade5208bf15609cfe4294545c77004b"
    )
    prompt = diagnostic_analysis_prompt()
    for guidance in (
        "逐 unitKey 写各自细节，不能并给另一条回答",
        "建议侧重待做动作，不复述跨组年份/数量",
        "确需复述须逐条引用同组确有年份/数量的证据",
        "不把自然发现数据说成口碑数据",
    ):
        assert guidance in prompt
    assert all(name not in prompt for name in ("库迪", "瑞幸", "广州", "KIMI", "DEEPSEEK"))


def test_v10_model_view_preserves_distinct_same_platform_and_question_group_facts() -> None:
    raw = _six_sample_payload()
    samples = raw["evidence"]["samples"]
    first = next(
        sample
        for sample in samples
        if sample["questionId"] == "10001" and sample["platformCode"] == "KIMI"
    )
    second = next(
        sample
        for sample in samples
        if sample["questionId"] == "10002" and sample["platformCode"] == "KIMI"
    )
    reputation = next(
        sample
        for sample in samples
        if sample["category"] == "BRAND_REPUTATION" and sample["platformCode"] == "KIMI"
    )
    first.update(answerExcerpt="品牌甲在新年度有全国规模资料，可普通快取。", answerTruncated=False)
    second.update(answerExcerpt="品牌甲位于地铁站，提供通勤快取。", answerTruncated=False)
    reputation.update(answerExcerpt="品牌甲在旧年度提供咖啡服务。", answerTruncated=False)
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    frozen = payload.model_dump_json(by_alias=True, exclude_unset=True)
    messages, shown = _project_evidence(payload)
    view = _assert_native_budget(payload)
    assert "逐 unitKey 写各自细节，不能并给另一条回答" in messages[0].content
    by_key = {sample["unitKey"]: sample for sample in view["evidence"]["samples"]}
    question_categories = {
        question["id"]: question["category"] for question in view["evidence"]["questions"]
    }
    for sample in (first, second, reputation):
        assert by_key[sample["unitKey"]]["answerExcerpt"] == sample["answerExcerpt"]
        assert question_categories[sample["questionId"]] == sample["category"]
    report = _report(raw)
    report["findings"][0]["evidence"][0].update(
        unitKey=first["unitKey"], sourceId=None, quote="地铁站"
    )
    with pytest.raises(ValidationError):
        _grounded_schema(shown).model_validate(report)
    report = _report(raw)
    reputation_finding = next(f for f in report["findings"] if f["section"] == "REPUTATION")
    reputation_finding["evidence"][0].update(
        unitKey=first["unitKey"], sourceId=None, quote="新年度"
    )
    with pytest.raises(ValidationError):
        _grounded_schema(shown).model_validate(report)
    assert payload.model_dump_json(by_alias=True, exclude_unset=True) == frozen


@pytest.mark.parametrize("clipped", [False, True])
def test_model_excerpt_label_preserves_distinct_claims_and_immutable_wire(clipped: bool) -> None:
    raw = _payload()
    samples = raw["evidence"]["samples"]
    samples[0].update(answerExcerpt="品牌甲全国有多家门店。", answerTruncated=clipped)
    samples[1].update(answerExcerpt="品牌甲价格亲民；本地门店详情需核实。", answerTruncated=False)
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    frozen_wire = payload.model_dump_json(by_alias=True, exclude_unset=True)
    projected = _assert_native_budget(payload)["evidence"]["samples"]
    by_key = {sample["unitKey"]: sample for sample in projected}
    for original in samples:
        shown = by_key[original["unitKey"]]
        assert shown["answerExcerpt"] == original["answerExcerpt"]
        assert shown["excerptClipped"] is original["answerTruncated"]
        assert "answerTruncated" not in shown
    assert "全国" not in by_key[samples[1]["unitKey"]]["answerExcerpt"]
    assert payload.model_dump_json(by_alias=True, exclude_unset=True) == frozen_wire
    assert '"answerTruncated"' in frozen_wire and '"excerptClipped"' not in frozen_wire


def test_eight_limitations_keep_five_sampling_limits_and_only_exclude_three_stage_notes() -> None:
    raw = _six_sample_payload()
    prefix = "准备阶段提示（不代表最终诊断报告的全部证据）："  # noqa: RUF001
    sampling = [
        "单次采样快照不证明优化提升或因果。",
        "自然与口碑两组分开，不混入发现分母。",
        "失败和缺失是未知，不按零提及。",
        "回答仅展示有界摘录，完整原文在附录。",
        "AI 口碑不是消费者调查或市场份额。",
    ]
    preparation = [
        prefix + "本次未展示任何来源，口碑问题仅按确认资料中立生成。",
        prefix + "品牌官网未确认，需核实官方入口。",
        prefix + "别名仅沿用确认词，不新增未经引文支持的别名。",
    ]
    raw["evidence"]["limitations"] = sampling + preparation
    before = deepcopy(raw)
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    before_wire = payload.model_dump_json(by_alias=True, exclude_unset=True)
    view = _assert_native_budget(payload)
    assert view["evidence"]["limitations"] == sampling
    assert view["projection"]["clipped"] is True
    assert view["projection"]["answerCharLimit"] == 1600
    assert len(view["evidence"]["samples"]) == 6
    assert all(len(s["answerExcerpt"]) == 699 for s in view["evidence"]["samples"])
    assert view["evidence"]["matrixByStatus"]["SUCCEEDED"]["0:1"] == [0]
    assert view["evidence"]["metrics"]["discovery"]["KIMI"]["mentions"][0] == [2, 1.0]
    assert payload.evidence.limitations == tuple(sampling + preparation)
    assert raw == before
    assert payload.model_dump_json(by_alias=True, exclude_unset=True) == before_wire
    assert payload.input_hash == before["inputHash"]


@pytest.mark.parametrize(
    "note",
    [
        "准备资料需要核实，但没有明确阶段来源。",
        "官网与来源是本次采样需要记录的真实限制。",
        "本次未展示任何来源:未带阶段标记的 legacy 文本不能自动删。",
        "准备阶段提示（不代表最终诊断报告的全部证据）缺少完整冒号前缀。",  # noqa: RUF001
        " 准备阶段提示（不代表最终诊断报告的全部证据）：前置空格不属于精确前缀。",  # noqa: RUF001
        "准备阶段提示(不代表最终诊断报告的全部证据):ASCII 标点不属于精确前缀。",
    ],
)
def test_unknown_preparation_words_and_current_sampling_limits_are_not_keyword_filtered(
    note: str,
) -> None:
    raw = _payload()
    raw["evidence"]["limitations"] = [note]
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    view = _assert_native_budget(payload)
    assert view["evidence"]["limitations"] == [note]
    assert payload.evidence.limitations == (note,)


def test_projection_clipped_marks_stage_note_omission_without_changing_other_evidence() -> None:
    raw = _payload()
    original = DiagnosticAnalysisRequest.model_validate(raw)
    untouched = _assert_native_budget(original)
    assert untouched["projection"]["clipped"] is False
    raw["evidence"]["limitations"] = [
        "准备阶段提示（不代表最终诊断报告的全部证据）：仅属于上一阶段的提示。"  # noqa: RUF001
    ]
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    before = payload.model_dump_json()
    view = _assert_native_budget(payload)
    assert view["projection"]["clipped"] is True
    assert view["evidence"]["limitations"] == []
    assert view["evidence"] == untouched["evidence"]
    assert payload.model_dump_json() == before


def _recount(raw: dict[str, Any]) -> None:
    """Synthetic fixture metrics only; production matches are Java-owned."""
    evidence = raw["evidence"]

    def counts(samples: list[dict[str, Any]]) -> dict[str, int]:
        return {
            "expectedSamples": len(samples),
            "validSamples": sum(s["status"] == "SUCCEEDED" for s in samples),
            "failedSamples": sum(s["status"] == "FAILED" for s in samples),
            "missingSamples": sum(s["status"] == "MISSING" for s in samples),
        }

    metrics = evidence["metrics"]
    metrics.update(counts(evidence["samples"]))
    for category, key in (
        ("BRAND_DISCOVERY", "discovery"),
        ("BRAND_REPUTATION", "reputation"),
    ):
        for row in metrics[key]:
            samples = [
                s
                for s in evidence["samples"]
                if s["category"] == category and s["platformCode"] == row["platformCode"]
            ]
            row.update(counts(samples))
            if key == "discovery":
                for mention in row["mentions"]:
                    mention["mentionedSamples"] = sum(
                        s["status"] == "SUCCEEDED" and mention["brandId"] in s["matchedBrandIds"]
                        for s in samples
                    )
                    mention["mentionRate"] = (
                        mention["mentionedSamples"] / row["validSamples"]
                        if row["validSamples"]
                        else None
                    )
            else:
                row["recognizedSamples"] = sum(
                    s["status"] == "SUCCEEDED" and "OWN" in s["matchedBrandIds"] for s in samples
                )


def _six_sample_payload() -> dict[str, Any]:
    raw = _payload()
    evidence = raw["evidence"]
    evidence["platformCodes"] = ["DEEPSEEK", "KIMI"]
    evidence["samples"] = [
        {
            **deepcopy(sample),
            "unitKey": f"{sample['questionId']}:{platform}:1",
            "platformCode": platform,
            "answerExcerpt": "实际回答" * 50 + "品牌甲" + "其他内容" * 124,
            "answerTruncated": True,
            "matchedBrandIds": []
            if sample["questionId"] == "10002" and platform == "DEEPSEEK"
            else ["OWN"],
        }
        for sample in evidence["samples"]
        for platform in evidence["platformCodes"]
    ]
    for key in ("discovery", "reputation"):
        evidence["metrics"][key] = [
            {**deepcopy(evidence["metrics"][key][0]), "platformCode": platform}
            for platform in evidence["platformCodes"]
        ]
    evidence["sources"] = [
        {**deepcopy(evidence["sources"][0]), "id": f"source{i}", "snippet": "公开资料" * 125}
        for i in range(65)
    ]
    _recount(raw)
    return raw


def _assert_native_budget(payload: DiagnosticAnalysisRequest) -> dict[str, Any]:
    messages, shown = _project_evidence(payload)
    schema = _grounded_schema(shown).model_json_schema(by_alias=True)
    json_messages = diagnostic_analysis_json_schema_messages(messages, schema)
    repair = diagnostic_structured_repair_reserve(_REPAIR, schema, output_mode="JSON_OBJECT")
    repaired = (
        ModelMessage(
            role="system",
            content=json_messages[0].content + "\n\n[STRUCTURED OUTPUT REPAIR]\n" + repair,
        ),
        *json_messages[1:],
    )
    assert estimate_model_messages(messages) <= 18000
    for view in (json_messages, repaired):
        assert estimate_model_messages(diagnostic_json_request_budget_messages(view)) <= 24000
    return json.loads(messages[1].content)


@pytest.mark.parametrize("flags", [["invented"], ["OWN", "OWN"], ["OWN"] * 7, [0]])
def test_match_flags_reject_unknown_duplicate_overlimit_and_wrong_type(flags: list[object]) -> None:
    raw = _payload()
    raw["evidence"]["samples"][0]["matchedBrandIds"] = flags
    with pytest.raises(ValidationError):
        DiagnosticAnalysisRequest.model_validate(raw)


@pytest.mark.parametrize("status", ["FAILED", "MISSING"])
def test_unavailable_units_cannot_have_full_answer_matches(status: str) -> None:
    sample = _payload()["evidence"]["samples"][0]
    sample.update(
        status=status,
        answerExcerpt=None,
        answerTruncated=False,
        sourceIds=[],
        matchedBrandIds=["OWN"],
    )
    with pytest.raises(ValidationError):
        DiagnosticSample.model_validate(sample)
    sample["matchedBrandIds"] = []
    assert DiagnosticSample.model_validate(sample).matched_brand_ids == ()


@pytest.mark.parametrize("group", ["discovery", "reputation"])
def test_complete_known_match_groups_require_exact_java_aggregate(group: str) -> None:
    raw = _six_sample_payload()
    row = raw["evidence"]["metrics"][group][1]
    if group == "discovery":
        row["mentions"][0].update(mentionedSamples=1, mentionRate=0.5)
    else:
        row["recognizedSamples"] = 0
    with pytest.raises(ValidationError, match="match flags conflict"):
        DiagnosticAnalysisRequest.model_validate(raw)


@pytest.mark.parametrize("aggregate,valid", [(0, False), (1, True), (2, True)])
def test_mixed_legacy_unknown_flags_bound_aggregate_without_guessing(
    aggregate: int, valid: bool
) -> None:
    raw = _six_sample_payload()
    sample = next(
        s
        for s in raw["evidence"]["samples"]
        if s["questionId"] == "10001" and s["platformCode"] == "KIMI"
    )
    sample.pop("matchedBrandIds")
    row = raw["evidence"]["metrics"]["discovery"][1]
    row["mentions"][0].update(mentionedSamples=aggregate, mentionRate=aggregate / 2)
    if valid:
        parsed = DiagnosticAnalysisRequest.model_validate(raw)
        assert parsed.evidence.samples[1].matched_brand_ids is None
        assert (
            _assert_native_budget(parsed)["evidence"]["matrixByStatus"]["SUCCEEDED"]["0:1"] is None
        )
    else:
        with pytest.raises(ValidationError, match="match flags conflict"):
            DiagnosticAnalysisRequest.model_validate(raw)


@pytest.mark.parametrize("explicit_null", [False, True])
def test_legacy_retry_accepts_missing_or_null_without_rewriting_original_hash(
    explicit_null: bool,
) -> None:
    raw = _payload()
    for sample in raw["evidence"]["samples"]:
        sample["matchedBrandIds"] = None
        if not explicit_null:
            sample.pop("matchedBrandIds")
    before = deepcopy(raw)
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    projection = _assert_native_budget(payload)
    assert raw == before and payload.input_hash == before["inputHash"]
    assert all(s.matched_brand_ids is None for s in payload.evidence.samples)
    assert projection["evidence"]["measuredFacts"] == list(_measured_facts(payload.evidence))
    assert "自有:0/2份" in next(
        text for text in projection["evidence"]["measuredFacts"] if "自然发现合计" in text
    )
    assert all(
        value is None for value in projection["evidence"]["matrixByStatus"]["SUCCEEDED"].values()
    )


def test_missing_legacy_flags_do_not_consume_new_null_defaults_at_wire_byte_boundary() -> None:
    raw = _payload(maximal=True)
    for sample in raw["evidence"]["samples"]:
        sample.pop("matchedBrandIds")
    # Fill a valid old wire to just below 256KiB with actual source text, not a
    # mock budget. New default nulls would add ~1.6KiB and break recovery.
    for source in raw["evidence"]["sources"]:
        source["snippet"] = "实" * 500
    while len(json.dumps(raw, ensure_ascii=False, separators=(",", ":")).encode()) > 261000:
        next(s for s in raw["evidence"]["sources"] if s["snippet"])["snippet"] = ""
    parsed = DiagnosticAnalysisRequest.model_validate(raw)
    assert len(parsed.model_dump_json(by_alias=True, exclude_unset=True).encode()) <= 262144
    assert len(parsed.model_dump_json(by_alias=True).encode()) > 262144


def test_real_scale_six_answer_view_prioritizes_answers_and_keeps_full_match_authority() -> None:
    raw = _six_sample_payload()
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    original = payload.model_dump_json()
    projection = _assert_native_budget(payload)
    view = projection["evidence"]
    assert projection["projection"]["omittedSources"] > 0
    # Sources are reduced first, so these six saved answers keep their full 699-char excerpts.
    assert projection["projection"]["answerCharLimit"] == 1600
    assert len(view["samples"]) == 6
    assert all("品牌甲" in s["answerExcerpt"] for s in view["samples"])
    assert view["brandIdOrder"] == ["OWN", "user-kept-c1"]
    assert view["matrixByStatus"]["SUCCEEDED"]["1:1"] == [0]
    assert view["matrixByStatus"]["SUCCEEDED"]["1:0"] == []
    assert view["metrics"]["discovery"]["KIMI"]["mentions"][0] == [2, 1.0]
    assert view["metrics"]["discovery"]["DEEPSEEK"]["mentions"][0] == [1, 0.5]
    assert view["metrics"]["reputation"]["KIMI"]["recognizedSamples"] == 1
    assert payload.model_dump_json() == original
    prompt = diagnostic_analysis_prompt()
    for policy in (
        "摘录不含品牌不等于全文不含品牌",
        "不反推关联未建",
        "准备提示非本批证据/限制",
        "null 旧 V1 未知，非 []",
        "旧年份/来源主张非当前已确认品牌事实",
        "不复制输入/Schema/外壳",
    ):
        assert policy in prompt


def test_six_sample_measured_facts_use_original_weighted_answers_not_frequency_or_projection() -> (
    None
):
    raw = _six_sample_payload()
    for sample in raw["evidence"]["samples"]:
        # A real competitor shares the own-brand three of four discovery answer
        # flags, regardless of how often either name occurs in visible excerpts.
        if sample["category"] == "BRAND_DISCOVERY" and "OWN" in sample["matchedBrandIds"]:
            sample["matchedBrandIds"].append("user-kept-c1")
        sample["answerExcerpt"] = "品牌甲品牌甲品牌甲" + "实" * 690
        sample["sourceIds"] = ["source64"]
    _recount(raw)
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    before = payload.model_dump_json()
    view = _assert_native_budget(payload)
    facts = view["evidence"]["measuredFacts"]
    assert "不是词出现次数" in facts[0]
    assert next(text for text in facts if "自然发现DEEPSEEK" in text) == (
        "自然发现DEEPSEEK被提及: 自有:1/2份；竞品1:1/2份。"
    )
    assert next(text for text in facts if "自然发现KIMI" in text) == (
        "自然发现KIMI被提及: 自有:2/2份；竞品1:2/2份。"
    )
    overall = next(text for text in facts if "自然发现合计" in text)
    assert overall == "自然发现合计(按回答份数加权): 自有:3/4份；竞品1:3/4份。"
    assert "4/4" not in overall
    assert "本次解读使用的证据保留来源65项；至少6份有效回答在其中保留引用" in facts[-1]
    assert "完整原始引用数量以最终附录为准" in facts[-1]
    assert all(s.get("sourceIds") == ["source64"] for s in view["evidence"]["samples"])
    assert view["projection"]["answerCharLimit"] == 1600
    assert payload.model_dump_json() == before


def test_measured_facts_do_not_turn_unavailable_discovery_into_zero() -> None:
    raw = _six_sample_payload()
    for sample in raw["evidence"]["samples"]:
        if sample["category"] == "BRAND_DISCOVERY":
            sample.update(
                status="FAILED",
                answerExcerpt=None,
                answerTruncated=False,
                stableErrorCode="GEO_PROVIDER_TIMEOUT",
                sourceIds=[],
                matchedBrandIds=[],
            )
    _recount(raw)
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    facts = _measured_facts(payload.evidence)
    natural = [text for text in facts if text.startswith("自然发现")]
    assert all("未知(无有效回答)" in text and "0/0" not in text for text in natural)


def test_weighted_measured_facts_sum_counts_not_platform_rates_or_reputation() -> None:
    raw = _six_sample_payload()
    raw["evidence"]["samples"][0].update(
        status="FAILED",
        answerExcerpt=None,
        answerTruncated=False,
        stableErrorCode="GEO_PROVIDER_TIMEOUT",
        sourceIds=[],
        matchedBrandIds=[],
    )
    _recount(raw)
    evidence = DiagnosticAnalysisRequest.model_validate(raw).evidence
    facts = _measured_facts(evidence)
    overall = next(text for text in facts if "自然发现合计" in text)
    assert "自有:2/3份" in overall and "自有:1/2份" not in overall
    assert sum(row.valid_samples for row in evidence.metrics.reputation) == 2


@pytest.mark.parametrize("kept_sources", [0, 5])
def test_measured_source_counts_describe_only_java_frozen_view_not_original_absence(
    kept_sources: int,
) -> None:
    raw = _six_sample_payload()
    raw["evidence"]["sources"] = raw["evidence"]["sources"][:kept_sources]
    if not kept_sources:
        for sample in raw["evidence"]["samples"]:
            sample["sourceIds"] = []
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    facts = _measured_facts(payload.evidence)
    assert f"本次解读使用的证据保留来源{kept_sources}项" in facts[-1]
    assert f"至少{6 if kept_sources else 0}份有效回答在其中保留引用" in facts[-1]
    assert all(internal not in facts[-1] for internal in ("Java", "Python", "业务控制面"))
    assert "完整原始引用数量以最终附录为准" in facts[-1]
    assert "原始保存来源" not in facts[-1]


def test_projected_sources_do_not_force_public_research_or_allow_unshown_citations() -> None:
    raw = _payload(maximal=True)
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    _, shown = _project_evidence(payload)
    assert shown.sources and len(shown.sources) < len(payload.evidence.sources)
    omitted = next(s for s in payload.evidence.sources if s.id not in {s.id for s in shown.sources})
    report = _report(raw)
    assert _grounded_schema(shown).model_validate(report)
    public: dict[str, Any] = {**deepcopy(report["findings"][0]), "section": "PUBLIC_RESEARCH"}
    public["evidence"][0].update(unitKey=None, sourceId=omitted.id)
    report["findings"].append(public)
    with pytest.raises(ValidationError):
        _grounded_schema(shown).model_validate(report)


def test_maximum_long_brand_ids_compact_flags_remain_reversible_for_all_states() -> None:
    raw = _payload(maximal=True)
    evidence = raw["evidence"]
    new_ids = {
        q["id"]: str(1000000000000000000 + index) for index, q in enumerate(evidence["questions"])
    }
    for question in evidence["questions"]:
        question["id"] = new_ids[question["id"]]
    for sample in evidence["samples"]:
        sample["questionId"] = new_ids[sample["questionId"]]
        sample["unitKey"] = f"{sample['questionId']}:{sample['platformCode']}:1"
    evidence["competitors"] = [
        {**deepcopy(evidence["competitors"][0]), "id": f"c{i}" + "x" * 126, "name": f"竞品{i}"}
        for i in range(5)
    ]
    brand_ids = ["OWN", *(c["id"] for c in evidence["competitors"])]
    for sample in evidence["samples"]:
        sample.update(answerExcerpt="实" * 250, matchedBrandIds=brand_ids[:])
    for sample, status in zip(evidence["samples"][:2], ["FAILED", "MISSING"], strict=True):
        sample.update(
            status=status,
            answerExcerpt=None,
            answerTruncated=False,
            stableErrorCode="GEO_PROVIDER_TIMEOUT" if status == "FAILED" else None,
            sourceIds=[],
            matchedBrandIds=[],
        )
    names = {"OWN": "品牌甲", **{c["id"]: c["name"] for c in evidence["competitors"]}}
    for row in evidence["metrics"]["discovery"]:
        row["mentions"] = [
            {"brandId": brand_id, "name": name, "mentionedSamples": 0, "mentionRate": 0.0}
            for brand_id, name in names.items()
        ]
    _recount(raw)
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    before = payload.model_dump_json()
    view = _assert_native_budget(payload)["evidence"]
    assert view["brandIdOrder"] == brand_ids
    decoded = {
        f"{view['questions'][int(unit.split(':')[0])]['id']}:"
        f"{view['platformCodes'][int(unit.split(':')[1])]}:1": (
            status,
            [view["brandIdOrder"][index] for index in flags],
        )
        for status, units in view["matrixByStatus"].items()
        for unit, flags in units.items()
    }
    assert decoded == {
        s["unitKey"]: (s["status"], s["matchedBrandIds"]) for s in evidence["samples"]
    }
    assert len(decoded) == 70
    assert payload.model_dump_json() == before
    fields = ("expectedSamples", "validSamples", "failedSamples", "missingSamples")
    assert dict(zip(fields, view["metrics"]["counts"], strict=True)) == {
        key: evidence["metrics"][key] for key in fields
    }
    for group in ("discovery", "reputation"):
        for original_row in evidence["metrics"][group]:
            row = view["metrics"][group][original_row["platformCode"]]
            assert dict(zip(fields, row["counts"], strict=True)) == {
                key: original_row[key] for key in fields
            }
            if group == "discovery":
                assert dict(zip(brand_ids, row["mentions"], strict=True)) == {
                    m["brandId"]: [m["mentionedSamples"], m["mentionRate"]]
                    for m in original_row["mentions"]
                }
            else:
                assert row["recognizedSamples"] == original_row["recognizedSamples"]


def test_match_flags_do_not_authorize_unseen_quotes_even_when_own_is_known() -> None:
    raw = _payload(maximal=True)
    for sample in raw["evidence"]["samples"]:
        sample["matchedBrandIds"] = ["OWN"]
    raw["evidence"]["samples"][0]["answerExcerpt"] = "实" * 690 + "隐藏独有尾部"
    _recount(raw)
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    projection = _assert_native_budget(payload)
    first = projection["evidence"]["samples"][0]
    assert projection["evidence"]["matrixByStatus"]["SUCCEEDED"]["0:0"] == [0]
    assert "品牌甲" not in first["answerExcerpt"]
    report = _report(raw)
    report["findings"][0]["evidence"][0]["quote"] = "隐藏独有尾部"
    _, shown = _project_evidence(payload)
    with pytest.raises(ValidationError):
        _grounded_schema(shown).model_validate(report)
    assert _grounded_schema(payload.evidence).model_validate(report)


@pytest.mark.parametrize("legacy_unknown", [False, True])
def test_v2_native_payload_and_one_repair_keep_flags_metrics_and_legacy_unknown_limits(
    legacy_unknown: bool,
) -> None:
    raw = _six_sample_payload()
    if legacy_unknown:
        raw["evidence"]["samples"][1].pop("matchedBrandIds")
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    before = deepcopy(raw)
    requests: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        data = json.loads(request.content)
        requests.append(data)
        messages = data["messages"]
        system, user = messages[0]["content"], json.loads(messages[1]["content"])
        assert "摘录不含品牌不等于全文不含品牌" in system
        assert "不输出索引键" in system
        view = user["evidence"]
        assert len(view["samples"]) == 6
        assert all(s["excerptClipped"] and "answerTruncated" not in s for s in view["samples"])
        assert view["matrixByStatus"]["SUCCEEDED"]["0:1"] == (None if legacy_unknown else [0])
        assert view["metrics"]["discovery"]["KIMI"]["mentions"][0] == [2, 1.0]
        assert view["metrics"]["reputation"]["KIMI"]["recognizedSamples"] == 1
        assert data["response_format"] == {"type": "json_object"}
        assert "tools" not in data and "tool_choice" not in data
        assert system.count("[DIAGNOSTIC REPORT JSON SCHEMA]") == 1
        schema = json.loads(system.split("[DIAGNOSTIC REPORT JSON SCHEMA]\n", 1)[1].splitlines()[1])
        assert set(schema["required"]) == set(_report(raw))
        assert schema == _grounded_schema(payload.evidence).model_json_schema(by_alias=True)
        native_messages = tuple(
            ModelMessage(role=m["role"], content=m["content"]) for m in messages
        )
        assert (
            estimate_model_messages(diagnostic_json_request_budget_messages(native_messages))
            <= 24000
        )
        report = {"private-wrapper": _report(raw)} if len(requests) == 1 else _report(raw)
        if len(requests) == 2:
            assert "SAFE DIAGNOSTIC STRUCTURED OUTPUT FEEDBACK" in system
            assert "private-wrapper" not in system
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {"content": json.dumps(report)},
                    }
                ],
                "usage": {"prompt_tokens": 20, "completion_tokens": 10},
            },
        )

    async def scenario():
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            gateway = DeepSeekAdapter(
                ProviderSettings(
                    credential=SecretStr("test-only"), model="test-model", max_retries=0
                ),
                client=client,
            )
            return await analyze_diagnostic(payload, gateway, received_at=datetime.now(UTC))

    result = asyncio.run(scenario())
    assert len(requests) == result.synthesis_usage.provider_call_count == 2
    assert result.prompt_version == "GEO_DIAGNOSTIC_ANALYSIS_V11"
    assert any("历史样本" in text for text in result.report.limitations) is legacy_unknown
    assert any("原始回答并未因此截断" in text for text in result.report.limitations)
    assert all(
        internal not in text
        for text in result.report.limitations
        for internal in ("Java", "Python", "业务控制面", "模型视图")
    )
    assert raw == before

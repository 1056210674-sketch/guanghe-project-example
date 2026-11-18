"""Consulting report delivery from frozen evidence, without any provider/network call."""

import asyncio
import json
from copy import deepcopy
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol, cast

import httpx
import pytest
from jsonschema import Draft202012Validator
from jsonschema import ValidationError as JsonSchemaValidationError
from pydantic import SecretStr, ValidationError

from app.common.settings import ProviderSettings
from app.geo.diagnostic_analysis import (
    DiagnosticAnalysisRequest,
    DiagnosticReport,
    _grounded_schema,  # pyright: ignore[reportPrivateUsage]
    _project_evidence,  # pyright: ignore[reportPrivateUsage]
    analyze_diagnostic,
)
from app.model_gateway.deepseek import DeepSeekAdapter
from tests.unit.test_geo_diagnostic_delivery import (
    _payload,  # pyright: ignore[reportPrivateUsage]
    _report,  # pyright: ignore[reportPrivateUsage]
)


class _JsonSchemaValidator(Protocol):
    def validate(self, instance: object) -> None: ...


_CONSULTING_FIELDS = {
    "currentState": 1000,
    "businessImpact": 800,
    "deliverable": 600,
    "acceptance": 600,
    "ownerRole": 120,
    "timeframe": 120,
}


def _consulting_report() -> dict[str, Any]:
    report = _report()
    ref = report["findings"][0]["evidence"][0]
    reputation = next(f for f in report["findings"] if f["section"] == "REPUTATION")["evidence"]
    report["strategy"]["cognitionFindings"] = [
        {
            "title": "业务说明需要落到选择条件",
            "observation": "本批口碑回答建议核实服务条件。",
            "businessImpact": "补充明确条件可帮助客户判断业务适配，实际效果需复测。",
            "evidence": deepcopy(reputation),
        }
    ]
    report["strategy"]["keywordLayers"] = [
        {
            "layer": "CATEGORY",
            "questionIds": ["10001"],
            "assessment": "本批行业发现问题得到服务条件方面的回答。",
            "nextAction": "围绕选型条件补充可核实业务说明。",
            "evidence": [deepcopy(ref)],
        },
        {
            "layer": "BRAND",
            "questionIds": ["10003"],
            "assessment": "本批点名问答仍强调核实服务条件。",
            "nextAction": "完善品牌可公开的服务适用边界。",
            "evidence": deepcopy(reputation),
        },
    ]
    report["contentTopics"][0]["intentLayer"] = "DECISION"
    return report


def test_v11_model_schema_requires_non_null_strategy_and_action_details_only_for_generation() -> (
    None
):
    payload = DiagnosticAnalysisRequest.model_validate(_payload())
    generated_model = _grounded_schema(payload.evidence)
    schema = generated_model.model_json_schema(by_alias=True)
    assert set(schema["required"]) == {
        "title",
        "summary",
        "findings",
        "recommendations",
        "contentTopics",
        "limitations",
        "strategy",
    }
    assert schema["properties"]["strategy"] == {"$ref": "#/$defs/DiagnosticStrategy"}
    items = schema["properties"]["recommendations"]
    assert (items["minItems"], items["maxItems"]) == (1, 15)
    recommendation = schema["$defs"][items["items"]["$ref"].rsplit("/", 1)[1]]
    assert set(recommendation["required"]) == {
        "priority",
        "title",
        "action",
        "basisEvidence",
        *_CONSULTING_FIELDS,
    }
    legacy_schema = DiagnosticReport.model_json_schema(by_alias=True)
    assert "strategy" not in legacy_schema["required"]
    assert legacy_schema["properties"]["strategy"]["anyOf"][-1] == {"type": "null"}
    legacy_recommendation = legacy_schema["$defs"]["DiagnosticRecommendation"]
    for field, max_length in _CONSULTING_FIELDS.items():
        field_schema = recommendation["properties"][field]
        assert {key: value for key, value in field_schema.items() if key != "title"} == {
            "type": "string",
            "minLength": 1,
            "maxLength": max_length,
        }
        assert field not in legacy_recommendation["required"]
        assert legacy_recommendation["properties"][field]["anyOf"] == [
            {"type": "string", "minLength": 1, "maxLength": max_length},
            {"type": "null"},
        ]
    report = _consulting_report()
    cast(_JsonSchemaValidator, Draft202012Validator(schema)).validate(report)
    generated = generated_model.model_validate(report)
    # The private subtype must not change the report's public JSON representation.
    assert generated.model_dump(by_alias=True) == DiagnosticReport.model_validate(
        report
    ).model_dump(by_alias=True)


@pytest.mark.parametrize("field", ["strategy", *_CONSULTING_FIELDS])
@pytest.mark.parametrize("missing", [True, False], ids=["missing", "null"])
def test_v11_schema_and_runtime_both_reject_absent_consulting_fields(
    field: str, missing: bool
) -> None:
    payload = DiagnosticAnalysisRequest.model_validate(_payload())
    generated_model = _grounded_schema(payload.evidence)
    report = _consulting_report()
    target = report if field == "strategy" else report["recommendations"][0]
    if missing:
        target.pop(field)
    else:
        target[field] = None
    validator = cast(
        _JsonSchemaValidator,
        Draft202012Validator(generated_model.model_json_schema(by_alias=True)),
    )
    with pytest.raises(JsonSchemaValidationError):
        validator.validate(report)
    with pytest.raises(ValidationError) as error:
        generated_model.model_validate(report)
    expected_location = (field,) if field == "strategy" else ("recommendations", 0, field)
    field_error = next(item for item in error.value.errors() if item["loc"] == expected_location)
    assert field_error["type"] == (
        "missing" if missing else "model_type" if field == "strategy" else "string_type"
    )
    # The same absent/null fields remain supported when reading historical reports.
    old = DiagnosticReport.model_validate(report)
    old_target = old.model_dump(by_alias=True)
    if field != "strategy":
        old_target = old_target["recommendations"][0]
    assert old_target[field] is None


def test_v11_delivery_keeps_grounded_consulting_actions_and_one_model_request() -> None:
    raw = _payload()
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    frozen = payload.model_dump_json(by_alias=True)
    report = _consulting_report()
    requests: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        data = json.loads(request.content)
        requests.append(data)
        assert data["max_tokens"] == 16384
        assert data["response_format"] == {"type": "json_object"}
        assert "tools" not in data
        view = json.loads(data["messages"][1]["content"])["evidence"]
        source = view["sources"][0]
        assert source["url"] == raw["evidence"]["sources"][0]["url"]
        assert source["title"] == raw["evidence"]["sources"][0]["title"]
        return httpx.Response(
            200,
            json={
                "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(report)}}],
                "usage": {"prompt_tokens": 100, "completion_tokens": 200},
            },
        )

    async def scenario():
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            gateway = DeepSeekAdapter(
                ProviderSettings(
                    credential=SecretStr("test-only"), model="unchanged-model", max_retries=0
                ),
                client=client,
            )
            return await analyze_diagnostic(payload, gateway, received_at=datetime.now(UTC))

    result = asyncio.run(scenario())
    assert result.prompt_version == "GEO_DIAGNOSTIC_ANALYSIS_V11"
    assert len(requests) == result.synthesis_usage.provider_call_count == 1
    assert result.synthesis_usage.model == "unchanged-model"
    assert result.report.strategy is not None
    assert (
        result.report.strategy.core_judgment.evidence[0].quote
        == (report["strategy"]["coreJudgment"]["evidence"][0]["quote"])
    )
    assert result.report.strategy.roadmap[0].recommendation_indexes == (0,)
    assert result.report.recommendations[0].acceptance == report["recommendations"][0]["acceptance"]
    assert result.report.content_topics[0].intent_layer == "DECISION"
    assert payload.model_dump_json(by_alias=True) == frozen


@pytest.mark.parametrize(
    "invalid",
    [
        "unknown_question",
        "wrong_category",
        "unrelated_question_evidence",
        "source_only_layer",
        "duplicate_layer",
        "duplicate_question",
        "roadmap_out_of_range",
        "roadmap_duplicate_index",
        "forged_quote",
        "missing_action_detail",
        "invented_url",
    ],
)
def test_v11_rejects_untraceable_strategy_without_dropping_old_evidence_rules(invalid: str) -> None:
    payload = DiagnosticAnalysisRequest.model_validate(_payload())
    report = _consulting_report()
    strategy = report["strategy"]
    layer = strategy["keywordLayers"][0]
    if invalid == "unknown_question":
        layer["questionIds"] = ["99999"]
    elif invalid == "wrong_category":
        layer["layer"] = "BRAND"
        strategy["keywordLayers"].pop()
    elif invalid == "unrelated_question_evidence":
        layer["evidence"][0]["unitKey"] = "10002:DOUBAO:1"
    elif invalid == "source_only_layer":
        layer["evidence"][0].update(unitKey=None, sourceId="source0")
    elif invalid == "duplicate_layer":
        strategy["keywordLayers"].append(deepcopy(layer))
    elif invalid == "duplicate_question":
        layer["questionIds"].append("10001")
    elif invalid == "roadmap_out_of_range":
        strategy["roadmap"][0]["recommendationIndexes"] = [1]
    elif invalid == "roadmap_duplicate_index":
        strategy["roadmap"][0]["recommendationIndexes"] = [0, 0]
    elif invalid == "forged_quote":
        strategy["coreJudgment"]["evidence"][0]["quote"] = "不存在于取得证据的主张"
    elif invalid == "missing_action_detail":
        report["recommendations"][0].pop("acceptance")
    else:
        report["recommendations"][0]["deliverable"] = "https://invented.example/claim"
    with pytest.raises(ValidationError):
        _grounded_schema(payload.evidence).model_validate(report)


def test_saved_legacy_report_is_readable_but_new_generation_requires_strategy() -> None:
    report = _report()
    report.pop("strategy")
    for field in (
        "currentState",
        "businessImpact",
        "deliverable",
        "acceptance",
        "ownerRole",
        "timeframe",
    ):
        report["recommendations"][0].pop(field)
    old = DiagnosticReport.model_validate(report)
    assert old.strategy is None and old.recommendations[0].acceptance is None
    payload = DiagnosticAnalysisRequest.model_validate(_payload())
    with pytest.raises(ValidationError):
        _grounded_schema(payload.evidence).model_validate(report)


def test_long_contiguous_window_and_actual_source_identity_survive_small_report_projection() -> (
    None
):
    raw = _payload()
    original_excerpt = "确认的服务信息。" * 150
    raw["evidence"]["samples"][0].update(answerExcerpt=original_excerpt, answerTruncated=True)
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    messages, shown = _project_evidence(payload)
    view = json.loads(messages[1].content)
    assert shown.samples[0].answer_excerpt == original_excerpt
    assert view["projection"]["answerCharLimit"] == 1600
    assert view["evidence"]["sources"][0]["url"] == "https://example.com/0"
    assert view["evidence"]["matrixByStatus"]["SUCCEEDED"] == {"0:0": [], "1:0": [], "2:0": []}


def test_maximal_projection_preserves_classified_matrix_and_never_erases_all_sources() -> None:
    payload = DiagnosticAnalysisRequest.model_validate(_payload(maximal=True))
    frozen = payload.model_dump_json(by_alias=True)
    messages, shown = _project_evidence(payload)
    view = json.loads(messages[1].content)["evidence"]
    assert shown.sources and all(source["url"] for source in view["sources"])
    assert len(view["matrixByStatus"]["SUCCEEDED"]) == 70
    assert shown.metrics == payload.evidence.metrics
    assert {(s.category, s.platform_code) for s in shown.samples} == {
        (s.category, s.platform_code) for s in payload.evidence.samples
    }
    assert payload.model_dump_json(by_alias=True) == frozen


def test_cited_sources_round_robin_by_sample_after_category_platform_coverage() -> None:
    raw = _payload()
    raw["evidence"]["sources"] = [
        {**raw["evidence"]["sources"][0], "id": f"source{index}"} for index in range(7)
    ]
    for sample, source_ids in zip(
        raw["evidence"]["samples"],
        [["source0", "source1", "source2"], ["source3", "source4"], ["source5", "source6"]],
        strict=True,
    ):
        sample["sourceIds"] = source_ids
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    messages, shown = _project_evidence(payload)
    # Group coverage: first discovery and reputation sources; subsequent rounds
    # take one source per sample, not all sources from the first discovery answer.
    assert [source.id for source in shown.sources] == [
        "source0",
        "source5",
        "source3",
        "source1",
        "source6",
        "source4",
        "source2",
    ]
    assert json.loads(messages[1].content)["projection"]["answerCharLimit"] == 1600


def test_group_source_seed_uses_later_answer_when_first_answer_has_no_sources() -> None:
    raw = _payload()
    raw["evidence"]["sources"] = [
        {**raw["evidence"]["sources"][0], "id": f"source{index}"} for index in range(3)
    ]
    for sample, source_ids in zip(
        raw["evidence"]["samples"], [[], ["source1"], ["source2"]], strict=True
    ):
        sample["sourceIds"] = source_ids
    _, shown = _project_evidence(DiagnosticAnalysisRequest.model_validate(raw))
    assert [source.id for source in shown.sources][:2] == ["source1", "source2"]


def test_diagnostic_120_second_boundary_and_http_override_are_isolated_from_other_operations() -> (
    None
):
    raw = _payload()
    raw["deadlineAt"] = (datetime.now(UTC) + timedelta(seconds=180)).isoformat()
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    timeouts: list[dict[str, float]] = []
    report = _consulting_report()

    def handler(request: httpx.Request) -> httpx.Response:
        timeouts.append(request.extensions["timeout"])
        return httpx.Response(
            200,
            json={
                "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(report)}}],
                "usage": {"prompt_tokens": 100, "completion_tokens": 200},
            },
        )

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler), timeout=30
        ) as client:
            gateway = DeepSeekAdapter(
                ProviderSettings(
                    credential=SecretStr("test-only"), model="unchanged", max_retries=0
                ),
                client=client,
            )
            # A request received 90 seconds ago still has 30 seconds in the
            # diagnostic-specific 120-second total window; old 60 would reject.
            result = await analyze_diagnostic(
                payload, gateway, received_at=datetime.now(UTC) - timedelta(seconds=90)
            )
            assert result.synthesis_usage.provider_call_count == 1
            messages, _ = _project_evidence(payload)
            await gateway.complete_structured(
                messages,
                DiagnosticReport,
                operation="unrelated_json_operation",
                max_tokens=1024,
                error_code="TEST_RESPONSE_INVALID",
                repair_instruction="test-only",
                repair_attempts=0,
            )
            assert client.timeout.read == 30

    asyncio.run(scenario())
    assert timeouts == [
        {"connect": 10.0, "read": 120.0, "write": 30.0, "pool": 10.0},
        {"connect": 30.0, "read": 30.0, "write": 30.0, "pool": 30.0},
    ]

"""Offline diagnostic sampling and evidence-only delivery; no business writes."""

import asyncio
import json
from copy import deepcopy
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from typing import Any, cast

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr, ValidationError

from app.api.application import create_app
from app.common.runtime import initialize_runtime
from app.common.settings import AppSettings, ProviderSettings
from app.geo.diagnostic_analysis import (
    _REPAIR,  # pyright: ignore[reportPrivateUsage]
    DiagnosticAnalysisError,
    DiagnosticAnalysisRequest,
    DiagnosticSample,
    _grounded_schema,  # pyright: ignore[reportPrivateUsage]
    _project_evidence,  # pyright: ignore[reportPrivateUsage]
    analyze_diagnostic,
)
from app.geo.models import GeoObservationStatus, GeoPlatformCode, GeoSynthesis
from app.geo.providers import GeoProviderRequest
from app.geo.worker_models import (
    GeoCompletion,
    GeoDiagnosticSynthesisContext,
    GeoDiagnosticTaskParameters,
    GeoExecutionContext,
    GeoSynthesisUsage,
    GeoTaskParameters,
)
from app.harness.runtime import (
    bind_model_call_boundary,
    estimate_model_messages,
)
from app.model_gateway.deepseek import DeepSeekAdapter
from app.model_gateway.gateway import (
    ModelGatewayError,
    ModelMessage,
    diagnostic_analysis_json_schema_messages,
    diagnostic_json_request_budget_messages,
)
from app.workers.control_plane import (
    ControlPlaneUnavailable,
    LocalWorkerControlPlane,
    WorkerControlPlane,
)
from app.workers.java_control_plane import JavaWorkerControlPlane
from app.workers.models import TaskExecutionMessage, WorkerTaskStatus
from app.workers.runtime import WorkerRuntime
from app.workers.transport import LocalMessageBroker, RawDelivery
from tests.internal_security import FakeInternalNonceStore, internal_settings, signed_headers
from tests.unit.test_geo_worker import (
    _Adapter,  # pyright: ignore[reportPrivateUsage]
    _backend,  # pyright: ignore[reportPrivateUsage]
    _context,  # pyright: ignore[reportPrivateUsage]
    _ControlPlane,  # pyright: ignore[reportPrivateUsage]
    _execute,  # pyright: ignore[reportPrivateUsage]
    _Gateway,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_geo_worker import (
    _message as _geo_message,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_java_worker_control_plane import (
    EXECUTION_TOKEN,
    TASK_ID,
    TEST_TRACEPARENT,
    _FakeWorkerJavaClient,  # pyright: ignore[reportPrivateUsage]
    _geo_context_payload,  # pyright: ignore[reportPrivateUsage]
    _response,  # pyright: ignore[reportPrivateUsage]
)

PATH = "/internal/v1/geo/diagnostic-analyses"


def _profile() -> dict[str, Any]:
    return {
        "brandName": "品牌甲",
        "website": None,
        "introduction": "工程服务",
        "brandTerms": ["品牌甲"],
        "region": "北京",
    }


def _competitor() -> dict[str, Any]:
    return {
        "id": "user-kept-c1",
        "name": "品牌乙",
        "brandTerms": ["品牌乙"],
        "reason": "人工修改的同类业务理由",
        "sourceIds": [],
        "origin": "USER",
        "edited": True,
    }


def _payload(*, maximal: bool = False) -> dict[str, Any]:
    platforms = [p.value for p in GeoPlatformCode] if maximal else ["DOUBAO"]
    count = 10 if maximal else 3
    questions = [
        {
            "id": str(10001 + i),
            "category": "BRAND_DISCOVERY" if i < count - 1 else "BRAND_REPUTATION",
            "text": ("哪些工程服务商适合需求？" if i < count - 1 else "品牌甲服务如何？"),
        }
        for i in range(count)
    ]
    samples = [
        {
            "unitKey": f"{q['id']}:{p}:1",
            "questionId": q["id"],
            "category": q["category"],
            "platformCode": p,
            "status": "SUCCEEDED",
            "answerExcerpt": "工程服务商应先核实实际业务与服务条件。",
            "answerTruncated": False,
            "stableErrorCode": None,
            "sourceIds": ["source0"],
            "matchedBrandIds": [],
        }
        for q in questions
        for p in platforms
    ]
    discovery = [
        {
            "platformCode": p,
            "expectedSamples": count - 1,
            "validSamples": count - 1,
            "failedSamples": 0,
            "missingSamples": 0,
            "mentions": [
                {"brandId": "OWN", "name": "品牌甲", "mentionedSamples": 0, "mentionRate": 0.0},
                {
                    "brandId": "user-kept-c1",
                    "name": "品牌乙",
                    "mentionedSamples": 0,
                    "mentionRate": 0.0,
                },
            ],
        }
        for p in platforms
    ]
    reputation = [
        {
            "platformCode": p,
            "expectedSamples": 1,
            "validSamples": 1,
            "failedSamples": 0,
            "missingSamples": 0,
            "recognizedSamples": 0,
        }
        for p in platforms
    ]
    sources = [
        {
            "id": f"source{i}",
            "title": "实际公开来源",
            "url": f"https://example.com/{i}",
            "snippet": "工程服务商应先核实实际业务与服务条件。",
        }
        for i in range(100 if maximal else 1)
    ]
    result: dict[str, Any] = {
        "jobId": "11001",
        "inputHash": "a" * 64,
        "deadlineAt": (datetime.now(UTC) + timedelta(seconds=60)).isoformat(),
        "title": "单次诊断",
        "evidence": {
            "profile": _profile(),
            "competitors": [_competitor()],
            "questions": questions,
            "platformCodes": platforms,
            "metrics": {
                "expectedSamples": len(samples),
                "validSamples": len(samples),
                "failedSamples": 0,
                "missingSamples": 0,
                "discovery": discovery,
                "reputation": reputation,
            },
            "samples": samples,
            "sources": sources,
            "limitations": [],
        },
    }
    if maximal:
        for s in samples:
            s["answerExcerpt"] = "实" * 700
        for source in sources:
            source["snippet"] = "实" * 100
        result["evidence"]["profile"]["introduction"] = "实" * 6000
    return result


def _report(raw: dict[str, Any] | None = None) -> dict[str, Any]:
    evidence = (raw or _payload())["evidence"]
    samples = [s for s in evidence["samples"] if s["status"] == "SUCCEEDED"]
    sample = samples[0]
    ref = {"unitKey": sample["unitKey"], "sourceId": None, "quote": sample["answerExcerpt"][:8]}
    findings: list[dict[str, Any]] = []
    for category, section in [("BRAND_DISCOVERY", "DISCOVERY"), ("BRAND_REPUTATION", "REPUTATION")]:
        match = next((s for s in samples if s["category"] == category), None)
        if match:
            finding = {
                "section": section,
                "heading": "本批观察",
                "description": "先核实服务条件。",
                "evidence": [
                    {
                        "unitKey": match["unitKey"],
                        "sourceId": None,
                        "quote": match["answerExcerpt"][:8],
                    }
                ],
            }
            findings.append(finding)
            if section == "DISCOVERY" and evidence["competitors"]:
                findings.append({**deepcopy(finding), "section": "COMPETITION"})
    return {
        "title": "单次诊断",
        "summary": "本次回答仅呈现同批观察。",
        "findings": findings,
        "recommendations": [
            {
                "priority": "P1",
                "title": "核实信息",
                "action": "检查原始回答与实际业务。",
                "basisEvidence": [deepcopy(ref)],
                "currentState": "回答建议先核实工程服务条件。",
                "businessImpact": "明确服务边界便于客户判断适配程度。",
                "deliverable": "一份经品牌确认的服务范围说明。",
                "acceptance": "检查说明是否回应本次顾客问题，再按原问题复测。",
                "ownerRole": "建议由品牌业务负责人确认。",
                "timeframe": "建议第一阶段完成。",
            }
        ],
        "contentTopics": [
            {
                "title": "服务选择核实指南",
                "audienceQuestion": "如何核实工程服务条件？",
                "angle": "解释选择前应核对的实际资料。",
                "coveredBrandTerms": ["品牌甲"],
                "basisEvidence": [deepcopy(ref)],
            }
        ],
        "limitations": [],
        "strategy": {
            "version": "consulting-v1",
            "coreJudgment": {
                "headline": "先将服务选择条件说明清楚",
                "description": "本批回答关注实际业务与服务条件，建议先准备可核查的服务说明。",
                "evidence": [deepcopy(ref)],
            },
            "cognitionFindings": [],
            "keywordLayers": [],
            "roadmap": [
                {"stage": "建议第一阶段", "objective": "完善服务说明", "recommendationIndexes": [0]}
            ],
        },
    }


def _diagnostic_context():
    base = _context()
    data = base.synthesis_context.model_dump(mode="json", by_alias=True)
    data["competitorSetVersion"] = None
    data["questions"][-1]["category"] = "BRAND_REPUTATION"
    data["questions"][-1]["text"] = "品牌甲服务如何？"
    data["competitors"] = [
        {"id": "user-kept-c1", "name": "品牌乙", "aliases": ["品牌乙"], "competitorType": "DIRECT"}
    ]
    data["monitoringTarget"] = {"kind": "BRAND", "name": "品牌甲", "aliases": ["品牌甲"]}
    data["diagnostic"] = {
        "diagnosticId": "11001",
        "profile": _profile(),
        "competitors": [_competitor()],
        "sources": [],
        "warnings": [],
    }
    return replace(
        base,
        mode="DIAGNOSTIC_RUN",
        synthesis_context=GeoDiagnosticSynthesisContext.model_validate(data),
    )


@pytest.mark.parametrize("succeed", [True, False])
def test_diagnostic_sampling_handoff_including_all_failed_has_no_model_or_metric_read(
    succeed: bool,
) -> None:
    gateway = _Gateway()
    adapter = _Adapter(GeoPlatformCode.DOUBAO, succeed=succeed)
    control = _ControlPlane()
    outcome = asyncio.run(
        _execute(_backend((adapter,), gateway=gateway), _diagnostic_context(), control)
    )
    assert outcome.succeeded
    assert not control.failures and len(control.completions) == 1
    assert gateway.calls == control.visibility_reads == 0
    assert len(control.observations) == 3
    completion = control.completions[0]
    assert completion.synthesis.method_version == "geo-diagnostic-sampling-v1"
    assert not completion.synthesis.findings and not completion.synthesis.recommendations
    assert completion.synthesis_usage == GeoSynthesisUsage.diagnostic_sampling()
    assert len(completion.synthesis.missing_samples) == (0 if succeed else 3)
    assert all(
        item.status is (GeoObservationStatus.SUCCEEDED if succeed else GeoObservationStatus.FAILED)
        for item in control.observations.values()
    )


def test_diagnostic_resume_skips_both_saved_successes_and_failures() -> None:
    async def scenario() -> None:
        first = _ControlPlane()
        await _execute(
            _backend((_Adapter(GeoPlatformCode.DOUBAO, succeed=False),)),
            _diagnostic_context(),
            first,
        )
        adapter = _Adapter(GeoPlatformCode.DOUBAO)
        resumed = _ControlPlane(observations=tuple(first.observations.values()))
        context = replace(
            _diagnostic_context(), execution_no=2, completed_unit_keys=frozenset(first.observations)
        )
        await _execute(_backend((adapter,)), context, resumed)
        assert adapter.calls == 0 and resumed.observations == first.observations
        assert len(resumed.completions) == 1 and not resumed.failures

    asyncio.run(scenario())


def test_diagnostic_provider_request_is_only_the_question_without_target_or_history() -> None:
    class InspectAdapter(_Adapter):
        async def observe(self, request: GeoProviderRequest):
            assert set(request.__dataclass_fields__) == {
                "question_id",
                "sample_no",
                "question",
                "traceparent",
            }
            context = _diagnostic_context()
            expected = context.question_text(request.question_id)
            assert request.question == expected
            if request.question_id != context.question_ids[-1]:
                assert "品牌甲" not in request.question and "品牌乙" not in request.question
            return await super().observe(request)

    asyncio.run(
        _execute(
            _backend((InspectAdapter(GeoPlatformCode.DOUBAO),)),
            _diagnostic_context(),
            _ControlPlane(),
        )
    )


def _diagnostic_wire() -> dict[str, Any]:
    raw = cast(dict[str, Any], _geo_context_payload())
    raw["inputSnapshot"]["parameters"]["mode"] = "DIAGNOSTIC_RUN"
    data = raw["authorizedContext"]
    data["competitorSetVersion"] = None
    data["questions"][-1]["category"] = "BRAND_REPUTATION"
    data["competitors"] = [
        {"id": "user-kept-c1", "name": "品牌乙", "aliases": [], "competitorType": "DIRECT"}
    ]
    data["monitoringTarget"] = {"kind": "BRAND", "name": "品牌甲", "aliases": []}
    data["diagnostic"] = {
        "diagnosticId": "11001",
        "profile": _profile(),
        "competitors": [_competitor()],
        "sources": [],
        "warnings": [],
    }
    return raw


def _map_context(raw: dict[str, Any]):
    client = _FakeWorkerJavaClient()
    client.context_result = _response(raw)
    return asyncio.run(
        JavaWorkerControlPlane(client).get_execution_context(
            TASK_ID, execution_no=1, execution_token=EXECUTION_TOKEN, traceparent=TEST_TRACEPARENT
        )
    )


def test_diagnostic_signed_context_accepts_200_char_names_and_stable_manual_ids() -> None:
    raw = _diagnostic_wire()
    raw["authorizedContext"]["competitors"][0]["name"] = "字" * 200
    raw["authorizedContext"]["diagnostic"]["competitors"][0]["name"] = "字" * 200
    mapped = _map_context(raw)
    assert isinstance(mapped, GeoExecutionContext)
    assert isinstance(mapped.synthesis_context, GeoDiagnosticSynthesisContext)
    assert (
        mapped.mode == "DIAGNOSTIC_RUN"
        and mapped.synthesis_context.diagnostic.competitors[0].edited
    )
    assert len(mapped.synthesis_context.competitors[0].name) == 200


def test_diagnostic_sampling_uses_the_actual_signed_provider_budget_not_a_new_fixed_cap() -> None:
    raw = _diagnostic_wire()
    raw["budget"]["maxProviderCalls"] = 201
    mapped = _map_context(raw)
    assert isinstance(mapped, GeoExecutionContext) and mapped.max_provider_calls == 201


@pytest.mark.parametrize(
    "mutation", ["deep", "budget", "mode", "category", "compkind", "targetkind", "extra", "id"]
)
def test_diagnostic_signed_context_rejects_cross_mode_or_unfrozen_values(mutation: str) -> None:
    raw = _diagnostic_wire()
    if mutation == "deep":
        raw["inputSnapshot"]["parameters"]["sampleMode"] = "DEEP"
    elif mutation == "budget":
        raw["budget"]["maxProviderCalls"] = 1001
    elif mutation == "mode":
        raw["inputSnapshot"]["parameters"]["mode"] = "MONITOR_RUN"
    elif mutation == "category":
        raw["authorizedContext"]["questions"][0]["category"] = "OTHER"
    elif mutation == "compkind":
        raw["authorizedContext"]["competitors"][0]["competitorType"] = "INDIRECT"
    elif mutation == "targetkind":
        raw["authorizedContext"]["monitoringTarget"]["kind"] = "PRODUCT"
    elif mutation == "extra":
        raw["authorizedContext"]["diagnostic"]["price"] = 0
    else:
        raw["authorizedContext"]["competitors"][0]["id"] = "x" * 129
    with pytest.raises(ControlPlaneUnavailable):
        _map_context(raw)


@pytest.mark.parametrize(
    "task_status,run_status,settled",
    [
        ("RUNNING", "SUCCEEDED", 10),
        ("RUNNING", "PARTIAL", 5),
        ("RUNNING", "FAILED", 0),
        ("SUCCEEDED", "SUCCEEDED", 10),
        ("PARTIAL", "PARTIAL", 5),
        ("PARTIAL", "FAILED", 0),
        ("FAILED", "SUCCEEDED", 10),
        ("SYSTEM_TERMINATED", "SYSTEM_TERMINATED", 0),
    ],
)
def test_diagnostic_sampling_accepts_running_handoff_and_zero_settlement(
    task_status: str, run_status: str, settled: int
) -> None:
    client = _FakeWorkerJavaClient()
    client.geo_completion_result = _response(
        {
            "accepted": True,
            "taskStatus": task_status,
            "runStatus": run_status,
            "reportId": None,
            "settledPoints": settled,
            "releasedPoints": 10 - settled,
        }
    )
    status = asyncio.run(
        JavaWorkerControlPlane(client).report_geo_completion(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            completion=GeoCompletion(
                synthesis=GeoSynthesis(
                    summary="仅采样交接", method_version="geo-diagnostic-sampling-v1"
                ),
                synthesis_usage=GeoSynthesisUsage.diagnostic_sampling(),
            ),
            traceparent=TEST_TRACEPARENT,
        )
    )
    assert status == task_status


@pytest.mark.parametrize("succeed", [True, False])
def test_worker_runtime_diagnostic_handoff_is_not_report_or_pdf_ready(succeed: bool) -> None:
    context = _diagnostic_context()

    class RuntimeControl(_ControlPlane):
        def __init__(self) -> None:
            super().__init__()
            self.local = LocalWorkerControlPlane()

        async def probe(self) -> bool:
            return True

        async def claim(self, message: TaskExecutionMessage, **kwargs: object):
            return await self.local.claim(
                message,
                consumer_name=cast(str, kwargs["consumer_name"]),
                worker_id=cast(str, kwargs["worker_id"]),
                bundle_version=cast(str, kwargs["bundle_version"]),
            )

        async def get_execution_context(self, task_id: str, **kwargs: object):
            del task_id, kwargs
            return context

        async def aclose(self) -> None:
            return None

    async def scenario() -> None:
        control = RuntimeControl()
        gateway = _Gateway()
        worker = WorkerRuntime(
            initialize_runtime(
                AppSettings(environment="test", worker_task_type="GEO", worker_provider="REAL")
            ),
            provider=None,
            geo_backend=_backend(
                tuple(_Adapter(code, succeed=succeed) for code in GeoPlatformCode), gateway=gateway
            ),
            control_plane=cast(WorkerControlPlane, control),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        message = _geo_message()
        delivery = RawDelivery(body=message.model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(message.event_id)
        assert snapshot is not None
        assert snapshot.status is WorkerTaskStatus.HANDOFF_ACCEPTED
        assert snapshot.provider_model == "deterministic-geo-diagnostic-sampling-v1"
        assert snapshot.artifact_count == 0 and delivery.state == "ACKED"
        assert worker.status_snapshot().processed == 1
        assert gateway.calls == control.visibility_reads == 0
        assert len(control.completions) == 1 and not control.failures
        await worker.close()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "mutation",
    [
        "extra",
        "missing",
        "failedzero",
        "repcategory",
        "denominator",
        "rate",
        "platform",
        "source",
        "duplicate",
        "matrix",
        "timezone",
        "overbound",
    ],
)
def test_analysis_rejects_corrupted_java_frozen_evidence(mutation: str) -> None:
    raw = _payload()
    evidence = raw["evidence"]
    if mutation == "extra":
        evidence["samples"][0]["hiddenContext"] = "private"
    elif mutation == "missing":
        evidence["samples"][0]["status"] = "MISSING"
    elif mutation == "failedzero":
        evidence["metrics"]["failedSamples"] = 1
    elif mutation == "repcategory":
        evidence["samples"][-1]["category"] = "BRAND_DISCOVERY"
    elif mutation == "denominator":
        evidence["metrics"]["discovery"][0]["validSamples"] += 1
    elif mutation == "rate":
        evidence["metrics"]["discovery"][0]["mentions"][0]["mentionRate"] = 0.5
    elif mutation == "platform":
        evidence["metrics"]["reputation"][0]["platformCode"] = "KIMI"
    elif mutation == "source":
        evidence["samples"][0]["sourceIds"] = ["invented"]
    elif mutation == "duplicate":
        evidence["samples"].append(deepcopy(evidence["samples"][0]))
    elif mutation == "matrix":
        evidence["samples"].pop()
    elif mutation == "timezone":
        raw["deadlineAt"] = "2026-10-14T12:00:00+08:00"
    else:
        evidence["sources"][0]["snippet"] = "x" * 501
    with pytest.raises(ValidationError):
        DiagnosticAnalysisRequest.model_validate(raw)


@pytest.mark.parametrize(
    "mutation",
    [
        "badquote",
        "failedunit",
        "unknownunit",
        "wrongcategory",
        "badsource",
        "bothnone",
        "dualunrelated",
        "wrapper",
        "extra",
        "url",
        "researchunit",
        "discoverysource",
    ],
)
def test_report_strict_grounding_and_output_shape_cannot_be_unwrapped_or_filtered(
    mutation: str,
) -> None:
    payload = DiagnosticAnalysisRequest.model_validate(_payload())
    raw = _report()
    ref = raw["findings"][0]["evidence"][0]
    if mutation == "badquote":
        ref["quote"] = "编造证据"
    elif mutation in {"failedunit", "unknownunit"}:
        ref["unitKey"] = "99999:DOUBAO:1"
    elif mutation == "wrongcategory":
        raw["findings"][0]["section"] = "REPUTATION"
    elif mutation == "badsource":
        ref["unitKey"] = None
        ref["sourceId"] = "invented"
    elif mutation == "bothnone":
        ref["unitKey"] = None
    elif mutation == "dualunrelated":
        ref["sourceId"] = "source0"
        ref["quote"] = "错误引用"
    elif mutation == "wrapper":
        raw = {"report": raw}
    elif mutation == "extra":
        raw["price"] = 0
    elif mutation == "url":
        raw["summary"] = "https://invented.example.com"
    elif mutation == "researchunit":
        raw["findings"][0]["section"] = "PUBLIC_RESEARCH"
    else:
        ref["unitKey"] = None
        ref["sourceId"] = "source0"
    with pytest.raises(ValidationError):
        _grounded_schema(payload.evidence).model_validate(raw)


def test_maximal_chinese_projection_has_real_native_repair_budget_and_does_not_mutate_input() -> (
    None
):
    payload = DiagnosticAnalysisRequest.model_validate(_payload(maximal=True))
    original = payload.model_dump_json()
    messages, shown = _project_evidence(payload)
    assert estimate_model_messages(messages) <= 18000
    assert payload.model_dump_json() == original
    assert shown.metrics is payload.evidence.metrics
    projected = json.loads(messages[1].content)
    assert projected["projection"]["clipped"]
    assert {
        f"{projected['evidence']['questions'][int(key.split(':')[0])]['id']}:"
        f"{projected['evidence']['platformCodes'][int(key.split(':')[1])]}:1": status
        for status, units in projected["evidence"]["matrixByStatus"].items()
        for key in units
    } == {s.unit_key: s.status for s in payload.evidence.samples}
    assert "origin" not in messages[1].content and "edited" not in messages[1].content
    assert projected["evidence"]["sources"]
    assert all("url" in source and "title" in source for source in projected["evidence"]["sources"])
    assert {s.category for s in shown.samples} == {"BRAND_DISCOVERY", "BRAND_REPUTATION"}
    assert {s.platform_code for s in shown.samples} == set(payload.evidence.platform_codes)
    assert all(s.answer_excerpt and s.answer_excerpt in "实" * 700 for s in shown.samples)


@pytest.mark.parametrize(
    "first,second",
    [
        ("wrapper", "valid"),
        ("quote", "valid"),
        ("wrapper", "wrapper"),
        ("quote", "quote"),
        ("trailing", "valid"),
        ("trailing", "trailing"),
        ("unescaped_quote", "valid"),
        ("unescaped_quote", "unescaped_quote"),
        ("unescaped_newline", "valid"),
        ("unescaped_newline", "unescaped_newline"),
        ("invalid_escape", "valid"),
        ("invalid_escape", "invalid_escape"),
        ("trailing_comma", "valid"),
        ("trailing_comma", "trailing_comma"),
        ("length", "valid"),
        ("length", "length"),
        ("empty", "valid"),
        ("empty", "empty"),
        ("null_content", "valid"),
        ("null_content", "null_content"),
        ("tool_response", "valid"),
        ("tool_response", "tool_response"),
    ],
)
def test_json_analysis_repairs_once_with_safe_feedback_and_actual_24k_bound(
    first: str, second: str
) -> None:
    raw = _payload(maximal=True)
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    requests: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        data = json.loads(request.content)
        requests.append(data)
        assert data["max_tokens"] == 16384
        assert data["response_format"] == {"type": "json_object"}
        assert "tools" not in data and "tool_choice" not in data
        system = data["messages"][0]["content"]
        assert system.count("[DIAGNOSTIC REPORT JSON SCHEMA]") == 1
        schema = json.loads(system.split("[DIAGNOSTIC REPORT JSON SCHEMA]\n", 1)[1].splitlines()[1])
        assert schema == _grounded_schema(payload.evidence).model_json_schema(by_alias=True)
        assert schema["additionalProperties"] is False
        assert set(schema["required"]) == {
            "title",
            "summary",
            "findings",
            "recommendations",
            "contentTopics",
            "limitations",
            "strategy",
        }
        messages = tuple(
            ModelMessage(role=m["role"], content=m["content"]) for m in data["messages"]
        )
        assert estimate_model_messages(diagnostic_json_request_budget_messages(messages)) <= 24000
        shown = next(
            s for s in json.loads(messages[1].content)["evidence"]["samples"] if s["answerExcerpt"]
        )
        report = _report(raw)
        for ref in [
            report["findings"][0]["evidence"][0],
            report["recommendations"][0]["basisEvidence"][0],
            report["contentTopics"][0]["basisEvidence"][0],
        ]:
            ref["unitKey"] = shown["unitKey"]
            ref["quote"] = shown["answerExcerpt"][:8]
        kind = first if len(requests) == 1 else second
        if kind == "wrapper":
            report = {"private-input-unknown-name": report}
        elif kind == "quote":
            report["findings"][0]["evidence"][0]["quote"] = "不在真实摘录内"
        argument = json.dumps(report)
        if kind == "empty":
            argument = " \n\t"
        if kind == "trailing":
            argument += "x"
        elif kind in {"unescaped_quote", "unescaped_newline", "invalid_escape"}:
            bad_summary = {
                "unescaped_quote": '"private-json-canary "broken" quote"',
                "unescaped_newline": '"private-json-canary\nline"',
                "invalid_escape": '"private-json-canary\\q"',
            }[kind]
            argument = argument.replace(json.dumps(report["summary"]), bad_summary, 1)
        elif kind == "trailing_comma":
            argument = argument[:-1] + ",}"
        if len(requests) == 2:
            system = messages[0].content
            assert "SAFE DIAGNOSTIC STRUCTURED OUTPUT FEEDBACK" in system
            assert "private-input-unknown-name" not in system and "不在真实摘录内" not in system
            assert "private-json-canary" not in system
            assert "转义双引号/反斜杠/换行" in system
            expected_feedback = (
                "diag_quote_absent"
                if first == "quote"
                else "truncated"
                if first == "length"
                else "empty_content"
                if first in {"empty", "null_content"}
                else "unexpected_finish_reason"
                if first == "tool_response"
                else "invalid_"
            )
            assert expected_feedback in system
            if first in {
                "trailing",
                "unescaped_quote",
                "unescaped_newline",
                "invalid_escape",
                "trailing_comma",
            }:
                feedback = json.loads(
                    system.split("[SAFE DIAGNOSTIC STRUCTURED OUTPUT FEEDBACK]\n", 1)[
                        1
                    ].splitlines()[0]
                )
                assert feedback == {"failureStage": "invalid_json", "validationErrors": []}
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "length"
                        if kind == "length"
                        else "tool_calls"
                        if kind == "tool_response"
                        else "stop",
                        "message": {
                            "content": None if kind == "null_content" else argument,
                        },
                    }
                ],
                "usage": {"prompt_tokens": 20, "completion_tokens": 10},
            },
        )

    async def scenario():
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as http:
            gateway = DeepSeekAdapter(
                ProviderSettings(
                    credential=SecretStr("test-only"), model="deepseek-test", max_retries=0
                ),
                client=http,
            )
            return await analyze_diagnostic(payload, gateway, received_at=datetime.now(UTC))

    if second == "valid":
        response = asyncio.run(scenario())
        assert response.synthesis_usage.provider_call_count == 2
        assert response.prompt_version == "GEO_DIAGNOSTIC_ANALYSIS_V11"
        assert any("消费者真实评价" in text for text in response.report.limitations)
    else:
        with pytest.raises(DiagnosticAnalysisError):
            asyncio.run(scenario())
    assert len(requests) == 2


def test_json_analysis_escapes_round_trip_without_changing_exact_evidence() -> None:
    raw = _payload()
    quote = '实际条件含"引号"与路径\\现场\n下一行核实。'
    raw["evidence"]["samples"][0]["answerExcerpt"] = quote
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    before = payload.model_dump_json()
    report = _report(raw)
    for ref in [
        report["findings"][0]["evidence"][0],
        report["findings"][1]["evidence"][0],
        report["recommendations"][0]["basisEvidence"][0],
        report["contentTopics"][0]["basisEvidence"][0],
    ]:
        ref["quote"] = quote
    encoded = json.dumps(report, ensure_ascii=False)
    assert '\\"引号\\"' in encoded and "\\\\现场" in encoded and "\\n下一行" in encoded
    requests: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        data = json.loads(request.content)
        requests.append(data)
        assert data["max_tokens"] == 16384
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {"content": encoded},
                    }
                ],
                "usage": {"prompt_tokens": 20, "completion_tokens": 10},
            },
        )

    async def scenario():
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as http:
            gateway = DeepSeekAdapter(
                ProviderSettings(
                    credential=SecretStr("test-only"), model="deepseek-test", max_retries=0
                ),
                client=http,
            )
            return await analyze_diagnostic(payload, gateway, received_at=datetime.now(UTC))

    result = asyncio.run(scenario())
    assert len(requests) == result.synthesis_usage.provider_call_count == 1
    assert result.report.findings[0].evidence[0].quote == quote
    assert result.report.recommendations[0].basis_evidence[0].quote == quote
    assert payload.model_dump_json() == before


@pytest.mark.parametrize("stage", ["schema", "repair"])
def test_json_analysis_actual_http_boundary_counts_full_schema_and_repair(stage: str) -> None:
    payload = DiagnosticAnalysisRequest.model_validate(_payload())
    messages, shown = _project_evidence(payload)
    model = _grounded_schema(shown)
    injected = diagnostic_analysis_json_schema_messages(
        messages, model.model_json_schema(by_alias=True)
    )
    initial_request_tokens = estimate_model_messages(
        diagnostic_json_request_budget_messages(injected)
    )
    # These deliberately smaller TEST limits demonstrate that the actual POST
    # counts schema and repair, not only the original facts. V11 is bounded at 24K.
    limit = estimate_model_messages(messages) if stage == "schema" else initial_request_tokens
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            json={
                "choices": [{"finish_reason": "stop", "message": {"content": "{}x"}}],
                "usage": {"prompt_tokens": 20, "completion_tokens": 10},
            },
        )

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            gateway = DeepSeekAdapter(
                ProviderSettings(
                    credential=SecretStr("test-only"), model="test-model", max_retries=0
                ),
                client=client,
            )
            with bind_model_call_boundary(
                max_calls=2,
                deadline_at=datetime.now(UTC) + timedelta(seconds=60),
                max_input_tokens=limit,
            ):
                with pytest.raises(ModelGatewayError) as error:
                    await gateway.complete_structured(
                        messages,
                        model,
                        operation="readonly_geo_diagnostic_analysis",
                        max_tokens=6144,
                        error_code="GEO_DIAGNOSTIC_ANALYSIS_RESPONSE_INVALID",
                        repair_instruction=_REPAIR,
                    )
                assert error.value.code == "INPUT_TOKEN_BUDGET_EXCEEDED"

    asyncio.run(scenario())
    assert len(requests) == (0 if stage == "schema" else 1)


def test_signed_analysis_invalid_input_is_sanitized_and_no_provider_is_called() -> None:
    raw = _payload()
    raw["evidence"]["private"] = "private evidence must not leak"
    body = json.dumps(raw).encode()
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    with TestClient(app) as client:
        response = client.post(
            PATH,
            content=body,
            headers={**signed_headers("POST", PATH, body=body), "Content-Type": "application/json"},
        )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "GEO_DIAGNOSTIC_ANALYSIS_INPUT_INVALID"
    assert "private evidence" not in response.text and "private" not in response.text


def test_diagnostic_parameters_keep_only_the_existing_six_fields() -> None:
    raw = _diagnostic_wire()["inputSnapshot"]["parameters"]
    assert set(GeoDiagnosticTaskParameters.model_validate(raw).model_dump(by_alias=True)) == {
        "mode",
        "topicId",
        "questionIds",
        "platformCodes",
        "sampleMode",
        "region",
    }
    with pytest.raises(ValidationError):
        GeoTaskParameters.model_validate(raw)


@pytest.mark.parametrize("answer_size,source_size", [(700, 100), (250, 500)])
def test_full_matrix_or_full_sources_chinese_fit_projection_not_enlarged_budget(
    answer_size: int, source_size: int
) -> None:
    raw = _payload(maximal=True)
    for sample in raw["evidence"]["samples"]:
        sample["answerExcerpt"] = "实" * answer_size
    for source in raw["evidence"]["sources"]:
        source["snippet"] = "实" * source_size
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    before = payload.model_dump_json()
    messages, shown = _project_evidence(payload)
    assert estimate_model_messages(messages) <= 18000
    assert shown.metrics == payload.evidence.metrics
    assert before == payload.model_dump_json()


def test_simultaneous_maximum_text_is_rejected_by_wire_byte_budget_before_model() -> None:
    raw = _payload(maximal=True)
    for source in raw["evidence"]["sources"]:
        source["snippet"] = "实" * 500
    with pytest.raises(ValidationError, match="byte budget"):
        DiagnosticAnalysisRequest.model_validate(raw)


def test_failed_and_missing_samples_stay_unknown_not_discovery_zero_or_reputation_denominator() -> (
    None
):
    raw = _payload()
    for sample, status in zip(raw["evidence"]["samples"][:2], ["FAILED", "MISSING"], strict=True):
        sample.update(
            status=status,
            answerExcerpt=None,
            answerTruncated=False,
            stableErrorCode="GEO_PROVIDER_TIMEOUT" if status == "FAILED" else None,
            sourceIds=[],
        )
    metrics = raw["evidence"]["metrics"]
    metrics.update(validSamples=1, failedSamples=1, missingSamples=1)
    metrics["discovery"][0].update(validSamples=0, failedSamples=1, missingSamples=1)
    for mention in metrics["discovery"][0]["mentions"]:
        mention["mentionRate"] = None
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    assert payload.evidence.metrics.discovery[0].mentions[0].mention_rate is None
    assert payload.evidence.metrics.reputation[0].valid_samples == 1
    report = _report()
    with pytest.raises(ValidationError):
        _grounded_schema(payload.evidence).model_validate(report)
    report = _report(raw)
    assert _grounded_schema(payload.evidence).model_validate(report)


@pytest.mark.parametrize("length", [100, 101, 128, 129])
def test_diagnostic_error_code_preserves_the_existing_128_character_boundary(length: int) -> None:
    sample = _payload()["evidence"]["samples"][0]
    sample.update(
        status="FAILED",
        answerExcerpt=None,
        answerTruncated=False,
        stableErrorCode="A" * length,
        sourceIds=[],
    )
    if length > 128:
        with pytest.raises(ValidationError):
            DiagnosticSample.model_validate(sample)
    else:
        assert DiagnosticSample.model_validate(sample).stable_error_code == "A" * length


def test_source_only_public_research_and_related_dual_quotes_are_exact() -> None:
    payload = DiagnosticAnalysisRequest.model_validate(_payload())
    report = _report()
    ref = report["findings"][0]["evidence"][0]
    ref["sourceId"] = "source0"
    assert _grounded_schema(payload.evidence).model_validate(report)
    public: dict[str, Any] = {**deepcopy(report["findings"][0]), "section": "PUBLIC_RESEARCH"}
    public["evidence"][0]["unitKey"] = None
    report["findings"].append(public)
    assert _grounded_schema(payload.evidence).model_validate(report)


def test_quote_outside_model_projection_is_rejected_even_when_in_full_original_answer() -> None:
    raw = _payload(maximal=True)
    raw["evidence"]["samples"][0]["answerExcerpt"] = "实" * 690 + "尾部独有不可见证据"
    payload = DiagnosticAnalysisRequest.model_validate(raw)
    _, shown = _project_evidence(payload)
    report = _report(raw)
    report["findings"][0]["evidence"][0]["quote"] = "尾部独有不可见证据"
    with pytest.raises(ValidationError):
        _grounded_schema(shown).model_validate(report)
    assert _grounded_schema(payload.evidence).model_validate(report)


@pytest.mark.parametrize("section", ["DISCOVERY", "REPUTATION", "COMPETITION"])
def test_available_sections_cannot_be_omitted_or_replaced_by_public_research(section: str) -> None:
    payload = DiagnosticAnalysisRequest.model_validate(_payload())
    report = _report()
    report["findings"] = [item for item in report["findings"] if item["section"] != section]
    with pytest.raises(ValidationError):
        _grounded_schema(payload.evidence).model_validate(report)


@pytest.mark.parametrize("change", ["competitor", "newalias", "badquote", "wrapper", "empty"])
def test_content_blueprint_requires_own_confirmed_terms_and_actual_basis(change: str) -> None:
    payload = DiagnosticAnalysisRequest.model_validate(_payload())
    report = _report()
    topic = report["contentTopics"][0]
    if change == "competitor":
        topic["coveredBrandTerms"] = ["品牌乙"]
    elif change == "newalias":
        topic["coveredBrandTerms"] = ["虚构自有别名"]
    elif change == "badquote":
        topic["basisEvidence"][0]["quote"] = "虚构选题证据"
    elif change == "wrapper":
        topic["result"] = {}
    else:
        report["contentTopics"] = []
    with pytest.raises(ValidationError):
        _grounded_schema(payload.evidence).model_validate(report)


def test_compact_samples_reject_repeated_question_text_and_preserve_full_question_dictionary() -> (
    None
):
    raw = _payload(maximal=True)
    for question in raw["evidence"]["questions"]:
        question["text"] = "真实问题全文" * 100
    parsed = DiagnosticAnalysisRequest.model_validate(raw)
    assert len(parsed.evidence.questions[0].text) == 600
    raw["evidence"]["samples"][0]["questionText"] = raw["evidence"]["questions"][0]["text"]
    with pytest.raises(ValidationError):
        DiagnosticAnalysisRequest.model_validate(raw)


@pytest.mark.parametrize("expired", [False, True])
def test_signed_analysis_success_is_report_only_and_expired_deadline_calls_no_model(
    expired: bool,
) -> None:
    calls: list[dict[str, Any]] = []
    raw = _payload()
    if expired:
        raw["deadlineAt"] = (datetime.now(UTC) - timedelta(seconds=1)).isoformat()

    def handler(request: httpx.Request) -> httpx.Response:
        data = json.loads(request.content)
        calls.append(data)
        assert data["response_format"] == {"type": "json_object"}
        assert "tool_choice" not in data and "tools" not in data
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {"content": json.dumps(_report(raw))},
                    }
                ],
                "usage": {"prompt_tokens": 20, "completion_tokens": 10},
            },
        )

    http = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    gateway = DeepSeekAdapter(
        ProviderSettings(
            credential=SecretStr("test-only"),
            model="deepseek-test",
            max_retries=0,
        ),
        client=http,
    )
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    body = json.dumps(raw).encode()
    with TestClient(app) as client:
        app.state.geo_visual_gateway = gateway
        response = client.post(
            PATH,
            content=body,
            headers={
                **signed_headers("POST", PATH, body=body),
                "Content-Type": "application/json",
            },
        )
    asyncio.run(http.aclose())
    assert response.status_code == (504 if expired else 200)
    assert len(calls) == (0 if expired else 1)
    if not expired:
        result = response.json()
        assert set(result) == {"jobId", "inputHash", "promptVersion", "report", "synthesisUsage"}
        assert result["jobId"] == raw["jobId"] and result["inputHash"] == raw["inputHash"]
        assert result["synthesisUsage"]["providerCallCount"] == 1
        assert "pdf" not in result and "status" not in result

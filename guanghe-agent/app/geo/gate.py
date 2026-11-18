"""Explicit one-shot GEO capability probe; no Java task or Worker is created."""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
from dataclasses import asdict, dataclass
from datetime import UTC, datetime, timedelta
from typing import NoReturn

from app.common.settings import AppSettings
from app.geo.models import GeoCapability, GeoObservation, GeoObservationStatus, GeoPlatformCode
from app.geo.providers import build_geo_adapters
from app.geo.runtime import (
    GeoProviderRegistry,
    GeoSampleExecutor,
    GeoSynthesisError,
    GeoSynthesizer,
    capability_from_observation,
)
from app.model_gateway import build_model_gateway
from app.observability.trace import (
    new_trace_context,
    reset_current_trace_id,
    set_current_trace_id,
)

_QUESTION = "请基于当前公开网络资料，简要说明生成式人工智能回答为什么需要标注信息来源。"
_QUESTION_ID = "900000000000000001"


class GeoGateError(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class GeoSampleEvidence:
    platform_code: str
    status: str
    provider: str
    model: str
    request_count: int
    citation_count: int
    citation_domains: tuple[str, ...]
    answer_length: int
    answer_sha256: str | None
    input_tokens: int
    output_tokens: int
    search_requests: int
    latency_ms: int
    stable_error_code: str | None


@dataclass(frozen=True, slots=True)
class GeoGateReport:
    question_id: str
    capabilities: tuple[dict[str, object], ...]
    samples: tuple[GeoSampleEvidence, ...]
    synthesis_status: str
    synthesis_method_version: str | None
    synthesis_sha256: str | None
    model_calls: int
    provider_input_tokens: int
    provider_output_tokens: int
    synthesis_input_tokens: int
    synthesis_output_tokens: int
    synthesis_latency_ms: int
    monetary_cost: None = None

    def public_json(self) -> str:
        return json.dumps(asdict(self), ensure_ascii=False, separators=(",", ":"))


async def execute_gate(settings: AppSettings) -> GeoGateReport:
    if settings.environment != "test":
        raise GeoGateError("GEO_GATE_TEST_ENVIRONMENT_REQUIRED")
    adapters = build_geo_adapters(settings)
    registry = GeoProviderRegistry(adapters)
    executor = GeoSampleExecutor(registry)
    observations: list[GeoObservation] = []
    capabilities: list[GeoCapability] = []
    trace = new_trace_context()
    trace_token = set_current_trace_id(trace.trace_id)
    try:
        for code in GeoPlatformCode:
            adapter = registry.require(code)
            observation = await executor.execute(
                platform_code=code,
                question_id=_QUESTION_ID,
                sample_no=1,
                question=_QUESTION,
                max_provider_calls=(
                    4
                    if code is GeoPlatformCode.KIMI
                    else 3
                    if code is GeoPlatformCode.DEEPSEEK
                    else 2
                ),
                deadline_at=datetime.now(UTC) + timedelta(seconds=120),
                traceparent=trace.traceparent,
            )
            observations.append(observation)
            capabilities.append(capability_from_observation(adapter, observation))

        synthesis_status = "NOT_RUN"
        synthesis_method: str | None = None
        synthesis_hash: str | None = None
        model_calls = 0
        synthesis_input_tokens = 0
        synthesis_output_tokens = 0
        synthesis_latency_ms = 0
        synthesizer: GeoSynthesizer | None = None
        if any(item.status is GeoObservationStatus.SUCCEEDED for item in observations):
            try:
                gateway = build_model_gateway(settings)
                synthesizer = GeoSynthesizer(gateway)
                synthesis, synthesis_usage = await synthesizer.synthesize_with_usage(
                    tuple(observations)
                )
            except (GeoSynthesisError, RuntimeError):
                synthesis_status = "FAILED"
            else:
                synthesis_status = "SUCCEEDED"
                synthesis_method = synthesis.method_version
                synthesis_hash = hashlib.sha256(
                    synthesis.model_dump_json(by_alias=True).encode("utf-8")
                ).hexdigest()
                model_calls = 1
                synthesis_input_tokens = synthesis_usage.input_tokens
                synthesis_output_tokens = synthesis_usage.output_tokens
                synthesis_latency_ms = synthesis_usage.latency_ms
            finally:
                if synthesizer is not None:
                    await synthesizer.aclose()
        samples = tuple(_evidence(item) for item in observations)
        return GeoGateReport(
            question_id=_QUESTION_ID,
            capabilities=tuple(_public_capability(item) for item in capabilities),
            samples=samples,
            synthesis_status=synthesis_status,
            synthesis_method_version=synthesis_method,
            synthesis_sha256=synthesis_hash,
            model_calls=model_calls,
            provider_input_tokens=sum(item.usage.input_tokens for item in observations),
            provider_output_tokens=sum(item.usage.output_tokens for item in observations),
            synthesis_input_tokens=synthesis_input_tokens,
            synthesis_output_tokens=synthesis_output_tokens,
            synthesis_latency_ms=synthesis_latency_ms,
        )
    finally:
        reset_current_trace_id(trace_token)
        await registry.aclose()


def _evidence(item: GeoObservation) -> GeoSampleEvidence:
    answer = item.answer_text
    return GeoSampleEvidence(
        platform_code=item.platform_code.value,
        status=item.status.value,
        provider=item.provider,
        model=item.model,
        request_count=item.provider_call_count,
        citation_count=len(item.citations),
        citation_domains=tuple(
            sorted({citation.domain for citation in item.citations if citation.domain is not None})
        ),
        answer_length=len(answer or ""),
        answer_sha256=hashlib.sha256(answer.encode("utf-8")).hexdigest() if answer else None,
        input_tokens=item.usage.input_tokens,
        output_tokens=item.usage.output_tokens,
        search_requests=item.usage.search_requests,
        latency_ms=item.latency_ms,
        stable_error_code=item.stable_error_code,
    )


def _public_capability(item: GeoCapability) -> dict[str, object]:
    payload = item.model_dump(mode="json", by_alias=True)
    host = item.endpoint_host
    if host is not None and host.endswith(".cn-beijing.maas.aliyuncs.com"):
        payload["endpointHost"] = "cn-beijing.maas.aliyuncs.com"
    return payload


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Bounded GEO Provider capability Gate")
    parser.add_argument(
        "--execute",
        action="store_true",
        help="make one real sample request per configured online-search platform",
    )
    return parser


def _fail(code: str) -> NoReturn:
    raise SystemExit(code)


def main() -> None:
    args = _parser().parse_args()
    if not args.execute:
        _fail("GEO_GATE_EXECUTION_REQUIRES_EXPLICIT_FLAG")
    try:
        report = asyncio.run(execute_gate(AppSettings()))
    except GeoGateError as exc:
        _fail(str(exc))
    print(report.public_json())


if __name__ == "__main__":
    main()

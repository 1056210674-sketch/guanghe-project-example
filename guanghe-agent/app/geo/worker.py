"""GEO task execution backend plugged into the existing bounded Worker runtime."""

from __future__ import annotations

import asyncio
import logging
from collections import Counter
from datetime import UTC, datetime
from typing import Protocol

from app.common.enums import ProviderStatus
from app.common.settings import AppSettings
from app.geo.merchant_text import PLATFORM_NAMES
from app.geo.models import (
    GeoObservation,
    GeoObservationStatus,
    GeoPlatformCode,
    GeoSynthesis,
    GeoVisibilityEvidence,
)
from app.geo.providers import build_geo_adapters
from app.geo.runtime import (
    GeoProviderRegistry,
    GeoSampleExecutor,
    GeoSynthesisError,
    GeoSynthesizer,
)
from app.geo.synthesis_grounding import canonical_synthesis_facts, validate_visibility_evidence
from app.geo.worker_models import (
    GeoCompletion,
    GeoExecutionContext,
    GeoExecutionOutcome,
    GeoObservationDisposition,
    GeoSynthesisContext,
    GeoSynthesisUsage,
)
from app.harness.runtime import (
    HarnessViolation,
    bind_model_call_boundary,
    bind_provider_call_boundary,
)
from app.model_gateway import build_model_gateway
from app.model_gateway.gateway import ModelGatewayError
from app.observability.trace import (
    parse_traceparent,
    reset_current_trace_id,
    set_current_trace_id,
)
from app.observability.tracing import safe_ref, start_span
from app.providers.models import SubmissionState
from app.workers.control_plane import ControlPlaneRejected, ControlPlaneUnavailable
from app.workers.lease import ExecutionLeaseMonitor
from app.workers.models import TaskExecutionMessage

_logger = logging.getLogger(__name__)
_TRANSIENT_SAMPLE_ERRORS = frozenset(
    {
        "GEO_PROVIDER_TIMEOUT",
        "GEO_PROVIDER_NETWORK_UNAVAILABLE",
        "GEO_PROVIDER_RATE_LIMITED",
        "GEO_PROVIDER_MAINTENANCE",
    }
)


class GeoWorkerControlPlane(Protocol):
    async def heartbeat(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        stage_code: str,
        traceparent: str,
    ) -> None: ...

    async def report_progress(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        stage_code: str,
        traceparent: str,
    ) -> None: ...

    async def list_geo_observations(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str,
    ) -> tuple[GeoObservation, ...]: ...

    async def get_geo_visibility_evidence(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str,
    ) -> GeoVisibilityEvidence: ...

    async def report_geo_observation(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        observation: GeoObservation,
        traceparent: str,
    ) -> GeoObservationDisposition: ...

    async def report_geo_completion(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        completion: GeoCompletion,
        traceparent: str,
    ) -> str: ...

    async def report_failure(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        code: str,
        submission_state: SubmissionState,
        traceparent: str,
        provider: str | None = None,
        model: str | None = None,
        provider_request_id: str | None = None,
        provider_task_id: str | None = None,
    ) -> None: ...


class GeoWorkerBackend:
    """Run one Java-authorized GEO matrix without owning broker or task state."""

    code = "GEO_MULTI_PLATFORM"
    model = "geo-synthesis-v1"
    is_mock = False
    status = ProviderStatus.CONFIGURED
    _SUPPORTED_SYNTHESIS_MODELS = frozenset(("deepseek-v4-flash", "deepseek-flash"))

    def __init__(
        self,
        registry: GeoProviderRegistry,
        executor: GeoSampleExecutor,
        synthesizer: GeoSynthesizer,
    ) -> None:
        self._registry = registry
        self._executor = executor
        self._synthesizer = synthesizer

    async def probe(self) -> bool:
        if self._registry.platform_codes != frozenset(GeoPlatformCode):
            return False
        if self._synthesizer.identity.model not in self._SUPPORTED_SYNTHESIS_MODELS:
            return False
        return await self._synthesizer.probe()

    async def execute(
        self,
        message: TaskExecutionMessage,
        context: GeoExecutionContext,
        *,
        execution_token: str,
        control_plane: GeoWorkerControlPlane,
        heartbeat_interval_seconds: float,
    ) -> GeoExecutionOutcome:
        trace = parse_traceparent(message.traceparent)
        if trace is None:
            raise ControlPlaneRejected("GEO_TRACEPARENT_INVALID")
        if context.synthesis_context.topic.space_id != message.space_id:
            raise ControlPlaneRejected("JAVA_GEO_SPACE_IDENTITY_MISMATCH")
        trace_token = set_current_trace_id(trace.trace_id)
        stage = ["GEO_SAMPLING"]
        lease_monitor: ExecutionLeaseMonitor | None = None
        try:
            await self._announce_stage(
                control_plane,
                message,
                context,
                execution_token,
                stage[0],
            )
            lease_monitor = ExecutionLeaseMonitor(
                lambda: self._renew_lease(
                    control_plane,
                    message,
                    context,
                    execution_token,
                    stage,
                ),
                interval_seconds=heartbeat_interval_seconds,
            )
            lease_monitor.start(name=f"geo-worker-heartbeat-{message.task_id}")
            existing = await control_plane.list_geo_observations(
                message.task_id,
                execution_no=context.execution_no,
                execution_token=execution_token,
                traceparent=message.traceparent,
            )
            lease_monitor.raise_if_lost()
            self._validate_persisted_observations(context, existing)
            existing_keys = {_unit_key(item) for item in existing}
            if frozenset(existing_keys) != context.completed_unit_keys:
                raise ControlPlaneRejected("JAVA_GEO_COMPLETED_UNITS_MISMATCH")
            used_provider_calls = sum(item.provider_call_count for item in existing)
            if used_provider_calls > context.max_provider_calls:
                raise ControlPlaneRejected("JAVA_GEO_PROVIDER_USAGE_EXCEEDS_BUDGET")
            remaining_provider_calls = context.max_provider_calls - used_provider_calls

            remaining = tuple(
                unit for unit in context.units() if _unit_key_parts(*unit) not in existing_keys
            )
            if remaining:
                if remaining_provider_calls > 0:
                    with bind_provider_call_boundary(
                        max_calls=remaining_provider_calls,
                        deadline_at=context.deadline_at,
                    ):
                        await lease_monitor.run(
                            lambda: self._execute_remaining(
                                message,
                                context,
                                execution_token=execution_token,
                                control_plane=control_plane,
                                units=remaining,
                                max_provider_calls=remaining_provider_calls,
                                stage=stage,
                            ),
                            name=f"geo-sampling-{message.task_id}",
                        )
                else:
                    await lease_monitor.run(
                        lambda: self._execute_remaining(
                            message,
                            context,
                            execution_token=execution_token,
                            control_plane=control_plane,
                            units=remaining,
                            max_provider_calls=0,
                            stage=stage,
                        ),
                        name=f"geo-sampling-{message.task_id}",
                    )

            persisted = await control_plane.list_geo_observations(
                message.task_id,
                execution_no=context.execution_no,
                execution_token=execution_token,
                traceparent=message.traceparent,
            )
            lease_monitor.raise_if_lost()
            self._validate_persisted_observations(context, persisted, require_complete=True)
            persisted = self._ordered_observations(context, persisted)
            successful = tuple(
                item for item in persisted if item.status is GeoObservationStatus.SUCCEEDED
            )
            if context.mode == "DIAGNOSTIC_RUN":
                # This is only the immutable sampling handoff. Java owns classified
                # metrics, settlement, analysis leases and PDF readiness, even if every
                # sample failed. No ordinary discovery synthesis or fallback is run.
                lease_monitor.raise_if_lost()
                java_status = await self._handoff(
                    message,
                    context,
                    control_plane,
                    execution_token,
                    GeoCompletion(
                        synthesis=GeoSynthesis(
                            summary=(
                                "诊断采样矩阵已保存，成功或失败状态均按实际记录保留。"
                                "这只是采样交接，不是诊断解读或 PDF 就绪；"
                                "解读、失败范围和报告交付由业务控制面继续处理。"
                            ),
                            missing_samples=tuple(
                                _unit_key(item)
                                for item in persisted
                                if item.status is GeoObservationStatus.FAILED
                            ),
                            method_version="geo-diagnostic-sampling-v1",
                        ),
                        synthesis_usage=GeoSynthesisUsage.diagnostic_sampling(),
                    ),
                )
                return GeoExecutionOutcome(succeeded=True, java_task_status=java_status)

            assert isinstance(context.synthesis_context, GeoSynthesisContext)
            if not successful:
                code = "GEO_ZERO_SUCCESSFUL_OBSERVATIONS"
                lease_monitor.raise_if_lost()
                await control_plane.report_failure(
                    message.task_id,
                    execution_no=context.execution_no,
                    execution_token=execution_token,
                    code=code,
                    submission_state=SubmissionState.CONFIRMED,
                    traceparent=message.traceparent,
                )
                return GeoExecutionOutcome(succeeded=False, stable_error_code=code)

            # Read the same Java lexical evidence used by report metrics after every
            # expected unit is persisted. Do not reconstruct flags from model excerpts.
            visibility_evidence = await control_plane.get_geo_visibility_evidence(
                message.task_id,
                execution_no=context.execution_no,
                execution_token=execution_token,
                traceparent=message.traceparent,
            )
            lease_monitor.raise_if_lost()
            try:
                validate_visibility_evidence(
                    persisted, context.synthesis_context, visibility_evidence
                )
            except ValueError as exc:
                raise ControlPlaneRejected("JAVA_GEO_VISIBILITY_EVIDENCE_INVALID") from exc

            stage[0] = "GEO_SYNTHESIZING"
            await self._announce_stage(
                control_plane,
                message,
                context,
                execution_token,
                stage[0],
            )
            with start_span(
                "geo.synthesis",
                attributes={
                    "geo.observationCount": len(persisted),
                    "model.name": self._synthesizer.identity.model,
                },
            ) as span:
                remaining_calls = 0
                remaining_seconds = 0.0
                try:
                    remaining_calls = context.max_provider_calls - sum(
                        item.provider_call_count for item in persisted
                    )
                    remaining_seconds = (context.deadline_at - datetime.now(UTC)).total_seconds()
                    if remaining_calls <= 0:
                        raise GeoSynthesisError("GEO_SYNTHESIS_CALL_BUDGET_EXCEEDED")
                    if remaining_seconds <= 0:
                        raise GeoSynthesisError("GEO_SYNTHESIS_DEADLINE_EXCEEDED")
                    synthesis_deadline = asyncio.timeout(remaining_seconds)
                    try:
                        with bind_model_call_boundary(
                            max_calls=remaining_calls, deadline_at=context.deadline_at
                        ):
                            async with synthesis_deadline:
                                synthesis, usage = await lease_monitor.run(
                                    lambda: self._synthesizer.synthesize_with_usage(
                                        persisted,
                                        authorized_context=context.synthesis_context,
                                        visibility_evidence=visibility_evidence,
                                    ),
                                    name=f"geo-synthesis-{message.task_id}",
                                )
                    except TimeoutError:
                        if not synthesis_deadline.expired():
                            raise
                        raise GeoSynthesisError("GEO_SYNTHESIS_DEADLINE_EXCEEDED") from None
                    synthesis_usage = GeoSynthesisUsage.from_gateway(
                        self._synthesizer.identity.model,
                        usage,
                    )
                    self._validate_synthesis(persisted, synthesis)
                except (GeoSynthesisError, ModelGatewayError, HarnessViolation) as exc:
                    code = getattr(exc, "code", "GEO_SYNTHESIS_FAILED")
                    span.fail(code if isinstance(code, str) else "GEO_SYNTHESIS_FAILED")
                    synthesis = self._deterministic_synthesis(
                        context, persisted, visibility_evidence
                    )
                    synthesis_usage = GeoSynthesisUsage.deterministic()
                    _logger.warning(
                        "geo_synthesis_fell_back_to_deterministic_report",
                        extra={
                            "traceId": trace.trace_id,
                            "taskRef": message.task_id[-8:],
                            "stableErrorCode": (
                                code if isinstance(code, str) else "GEO_SYNTHESIS_FAILED"
                            ),
                            "failureStage": getattr(exc, "failure_stage", None),
                            "remainingProviderCalls": remaining_calls,
                            "remainingDeadlineMs": max(0, round(remaining_seconds * 1000)),
                            "observationCount": len(persisted),
                            "successfulObservationCount": len(successful),
                        },
                    )

            lease_monitor.raise_if_lost()
            java_status = await self._handoff(
                message,
                context,
                control_plane,
                execution_token,
                GeoCompletion(synthesis=synthesis, synthesis_usage=synthesis_usage),
            )
            return GeoExecutionOutcome(succeeded=True, java_task_status=java_status)
        finally:
            if lease_monitor is not None:
                await lease_monitor.close()
            reset_current_trace_id(trace_token)

    @staticmethod
    async def _handoff(
        message: TaskExecutionMessage,
        context: GeoExecutionContext,
        control_plane: GeoWorkerControlPlane,
        execution_token: str,
        completion: GeoCompletion,
    ) -> str:
        with start_span(
            "java.result.handoff",
            attributes={
                "task.ref": safe_ref(message.task_id),
                "executionNo": context.execution_no,
                "handoff.kind": "geo-completion",
            },
        ) as span:
            try:
                java_status = await control_plane.report_geo_completion(
                    message.task_id,
                    execution_no=context.execution_no,
                    execution_token=execution_token,
                    completion=completion,
                    traceparent=message.traceparent,
                )
            except (ControlPlaneUnavailable, ControlPlaneRejected) as exc:
                span.fail(exc.code)
                raise
            span.set("java.taskStatus", java_status)
            return java_status

    async def _execute_remaining(
        self,
        message: TaskExecutionMessage,
        context: GeoExecutionContext,
        *,
        execution_token: str,
        control_plane: GeoWorkerControlPlane,
        units: tuple[tuple[str, GeoPlatformCode, int], ...],
        max_provider_calls: int,
        stage: list[str],
    ) -> None:
        task_slots = asyncio.Semaphore(context.max_concurrency)
        report_slots = asyncio.Semaphore(context.max_concurrency)
        pending: dict[tuple[str, GeoPlatformCode, int], GeoObservation] = {}
        used_calls = 0

        async def sample(unit: tuple[str, GeoPlatformCode, int]) -> GeoObservation:
            nonlocal used_calls
            question_id, platform, sample_no = unit
            with start_span(
                "geo.sample",
                attributes={
                    "geo.platform": platform.value,
                    "geo.questionRef": safe_ref(question_id),
                    "geo.sampleNo": sample_no,
                },
            ) as span:
                observation = await self._executor.execute(
                    platform_code=platform,
                    question_id=question_id,
                    sample_no=sample_no,
                    question=context.question_text(question_id),
                    max_provider_calls=max_provider_calls,
                    deadline_at=context.deadline_at,
                    traceparent=message.traceparent,
                    bind_provider_boundary=False,
                    task_slots=task_slots,
                )
                span.set("provider.callCount", observation.provider_call_count)
                span.set("geo.status", observation.status.value)
                if observation.stable_error_code is not None:
                    span.fail(observation.stable_error_code)
            used_calls += observation.provider_call_count
            return observation

        async def persist(observation: GeoObservation) -> None:
            async with report_slots:
                disposition = await control_plane.report_geo_observation(
                    message.task_id,
                    execution_no=context.execution_no,
                    execution_token=execution_token,
                    observation=observation,
                    traceparent=message.traceparent,
                )
                if disposition not in {
                    GeoObservationDisposition.CREATED,
                    GeoObservationDisposition.DUPLICATE,
                }:
                    raise ControlPlaneRejected("JAVA_GEO_OBSERVATION_NOT_ACCEPTED")

        async def first_round(unit: tuple[str, GeoPlatformCode, int]) -> None:
            observation = await sample(unit)
            if (
                context.execution_no == 1
                and observation.status is GeoObservationStatus.FAILED
                and observation.stable_error_code in _TRANSIENT_SAMPLE_ERRORS
            ):
                # The Java observation is immutable. Defer only transient failures
                # until their final outcome; never replace or resample a saved unit.
                pending[unit] = observation
            else:
                await persist(observation)

        def retry_wait(observation: GeoObservation) -> float | None:
            now = datetime.now(UTC)
            wait = (
                max(0.0, (observation.retry_not_before - now).total_seconds())
                if observation.retry_not_before is not None
                else 0.0
            )
            if (
                used_calls >= max_provider_calls
                or wait > observation.retry_wait_limit_seconds
                or wait >= (context.deadline_at - now).total_seconds()
            ):
                return None
            return wait

        async def retry_unit(unit: tuple[str, GeoPlatformCode, int]) -> None:
            previous = pending[unit]
            wait = retry_wait(previous)
            if wait is None:
                await persist(previous)
                return
            if wait > 0:
                # No execution slot is held while waiting. The enclosing lease
                # monitor cancels this coroutine if authority is lost.
                await asyncio.sleep(wait)
            if retry_wait(previous) is None:
                await persist(previous)
                return
            current = await sample(unit)
            if current.provider_call_count == 0:
                # A competing unit can consume the last shared call after the
                # local check. Keep the actual last upstream error in that case.
                await persist(previous)
                return
            combined = current.model_dump()
            combined.update(
                started_at=previous.started_at,
                latency_ms=previous.latency_ms + current.latency_ms,
                provider_call_count=previous.provider_call_count + current.provider_call_count,
                # Failed HTTP attempts need not return token usage. Add only
                # usage actually observed, never invent the unknown earlier cost.
                usage=(previous.usage + current.usage).model_dump(),
            )
            await persist(GeoObservation.model_validate(combined))

        async def gather_units(tasks: tuple[asyncio.Task[None], ...]) -> None:
            try:
                await asyncio.gather(*tasks)
            except BaseException:
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
                raise

        # The context caps the entire matrix at 10 * 7 * 3. Waiting coroutines
        # are not execution slots; HTTP backoff releases admission in providers.
        # Seed round-robin by sample round so a platform's three DEEP samples
        # cannot monopolize task admission before other platforms get a turn.
        tasks = tuple(
            asyncio.create_task(first_round(unit), name=f"geo-unit-worker-{index + 1}")
            for index, unit in enumerate(sorted(units, key=lambda item: item[2]))
        )
        await gather_units(tasks)
        if not pending:
            return
        # All first-round units get a turn before the single compensation round.
        # It shares the original provider boundary/deadline and raises no budget.
        if any(retry_wait(item) is not None for item in pending.values()):
            stage[0] = "GEO_RETRYING"
            await self._announce_stage(control_plane, message, context, execution_token, stage[0])
        await gather_units(
            tuple(
                asyncio.create_task(retry_unit(unit), name=f"geo-unit-retry-{index + 1}")
                for index, unit in enumerate(pending)
            )
        )

    @staticmethod
    def _validate_persisted_observations(
        context: GeoExecutionContext,
        observations: tuple[GeoObservation, ...],
        *,
        require_complete: bool = False,
    ) -> None:
        keys = [_unit_key(item) for item in observations]
        if len(keys) != len(set(keys)) or not set(keys).issubset(context.unit_keys):
            raise ControlPlaneRejected("JAVA_GEO_OBSERVATIONS_INVALID")
        expected_platform = {code.value for code in context.platform_codes}
        if any(
            item.provider != item.platform_code.value
            or item.platform_code.value not in expected_platform
            for item in observations
        ):
            raise ControlPlaneRejected("JAVA_GEO_OBSERVATIONS_INVALID")
        if require_complete and frozenset(keys) != context.unit_keys:
            raise ControlPlaneRejected("JAVA_GEO_OBSERVATIONS_INCOMPLETE")

    @staticmethod
    def _ordered_observations(
        context: GeoExecutionContext,
        observations: tuple[GeoObservation, ...],
    ) -> tuple[GeoObservation, ...]:
        by_key = {_unit_key(item): item for item in observations}
        return tuple(by_key[_unit_key_parts(*unit)] for unit in context.units())

    @staticmethod
    def _validate_synthesis(
        observations: tuple[GeoObservation, ...],
        synthesis: object,
    ) -> None:
        if not isinstance(synthesis, GeoSynthesis):
            raise GeoSynthesisError("GEO_SYNTHESIS_RESPONSE_INVALID")
        ordered_missing = tuple(
            _unit_key(item) for item in observations if item.status is GeoObservationStatus.FAILED
        )
        expected_missing = set(ordered_missing[:100])
        if (
            synthesis.method_version not in {"geo-synthesis-v1", "geo-deterministic-v1"}
            or set(synthesis.missing_samples) != expected_missing
        ):
            raise GeoSynthesisError("GEO_SYNTHESIS_RESPONSE_INVALID")

    @staticmethod
    def _deterministic_synthesis(
        context: GeoExecutionContext,
        observations: tuple[GeoObservation, ...],
        visibility_evidence: GeoVisibilityEvidence | None = None,
    ) -> GeoSynthesis:
        assert isinstance(context.synthesis_context, GeoSynthesisContext)
        successful = tuple(
            item
            for item in observations
            if item.status is GeoObservationStatus.SUCCEEDED and (item.answer_text or "").strip()
        )
        failed = tuple(item for item in observations if item.status is GeoObservationStatus.FAILED)
        citation_count = sum(len(item.citations) for item in successful)
        cited_samples = sum(bool(item.citations) for item in successful)

        failure_by_platform = Counter(item.platform_code.value for item in failed)
        domains = Counter(
            citation.domain or "未识别域名" for item in successful for citation in item.citations
        )
        coverage_gaps = [
            f"{platform} 有 {count} 个监测点未取得有效回答。"
            for platform, count in sorted(failure_by_platform.items())
        ]
        if successful and cited_samples < len(successful):
            coverage_gaps.append(
                f"{len(successful) - cited_samples} 个有效回答没有结构化引用来源。"
            )

        citation_structure = [
            f"{domain}: {count} 次引用" for domain, count in domains.most_common(8)
        ]
        # Java projects name-mention metrics from the immutable catalog snapshot
        # and original answers. Do not create a second denominator/matching rule
        # here, especially not from topic.name (a user-defined report label).
        findings = [
            (
                f"共完成 {len(observations)} 个监测点，其中 {len(successful)} 个取得有效回答，"
                f"{len(failed)} 个失败。"
            ),
            f"有效回答共包含 {citation_count} 条结构化引用。",
        ]
        visibility_lines: list[str] = []
        if visibility_evidence is not None:
            facts = canonical_synthesis_facts(
                observations, context.synthesis_context, visibility_evidence
            )
            if facts.target_status == "AVAILABLE":
                for platform in facts.platforms:
                    if platform.mentioned is not None:
                        visibility_lines.append(
                            f"{PLATFORM_NAMES[platform.code]} 的 {platform.valid} 个有效回答中，"
                            f"{platform.mentioned} 个明确提及监测对象，"
                            f"名称提及可见度为 {platform.rate:g}%。"
                        )
                findings.extend(visibility_lines)
        return GeoSynthesis(
            summary=(
                f"本次计划监测 {len(observations)} 个点，成功获得 {len(successful)} 个有效回答，"
                f"失败 {len(failed)} 个。智能总结暂时不可用，"
                "当前报告由系统依据已保存的监测证据确定性生成；"
                "原始回答与引用均已保留。"
                + ("\n\n" + "\n".join(visibility_lines) if visibility_lines else "")
            ),
            coverage_gaps=tuple(coverage_gaps[:12]),
            citation_structure=tuple(citation_structure[:12]),
            competitor_signals=(),
            findings=tuple(findings),
            # Provider timeout is a collection limitation, not proof that the
            # merchant lacks content. No business advice is invented on fallback.
            recommendations=(),
            content_blueprint=(),
            missing_samples=tuple(_unit_key(item) for item in failed[:100]),
            method_version="geo-deterministic-v1",
        )

    @staticmethod
    async def _announce_stage(
        control_plane: GeoWorkerControlPlane,
        message: TaskExecutionMessage,
        context: GeoExecutionContext,
        execution_token: str,
        stage: str,
    ) -> None:
        await control_plane.heartbeat(
            message.task_id,
            execution_no=context.execution_no,
            execution_token=execution_token,
            stage_code=stage,
            traceparent=message.traceparent,
        )
        await control_plane.report_progress(
            message.task_id,
            execution_no=context.execution_no,
            execution_token=execution_token,
            stage_code=stage,
            traceparent=message.traceparent,
        )

    @staticmethod
    async def _renew_lease(
        control_plane: GeoWorkerControlPlane,
        message: TaskExecutionMessage,
        context: GeoExecutionContext,
        execution_token: str,
        stage: list[str],
    ) -> None:
        await control_plane.heartbeat(
            message.task_id,
            execution_no=context.execution_no,
            execution_token=execution_token,
            stage_code=stage[0],
            traceparent=message.traceparent,
        )

    async def aclose(self) -> None:
        await self._registry.aclose()
        await self._synthesizer.aclose()


def _unit_key(observation: GeoObservation) -> str:
    return _unit_key_parts(
        observation.question_id,
        observation.platform_code,
        observation.sample_no,
    )


def _unit_key_parts(question_id: str, platform: GeoPlatformCode, sample_no: int) -> str:
    return f"{question_id}:{platform.value}:{sample_no}"


def build_geo_worker_backend(settings: AppSettings) -> GeoWorkerBackend:
    from app.common.enums import TaskType
    from app.prompts.runtime import load_commerce_prompt_set

    prompts = load_commerce_prompt_set(settings.bundle_path)
    synthesizer = GeoSynthesizer(
        build_model_gateway(settings),
        prompts.for_execution(TaskType.GEO),
    )
    registry = GeoProviderRegistry(build_geo_adapters(settings))
    return GeoWorkerBackend(
        registry,
        GeoSampleExecutor(registry, doubao_concurrency=settings.geo_doubao_policy.max_concurrency),
        synthesizer,
    )


__all__ = ["GeoWorkerBackend", "GeoWorkerControlPlane", "build_geo_worker_backend"]

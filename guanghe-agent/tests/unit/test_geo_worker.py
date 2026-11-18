from __future__ import annotations

import asyncio
import json
import logging
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from typing import cast

import pytest
from pydantic import BaseModel, ValidationError

from app.common.enums import ProviderStatus, TaskType
from app.common.runtime import initialize_runtime
from app.common.settings import AppSettings
from app.geo.execution import record_geo_provider_call
from app.geo.models import (
    GeoCitation,
    GeoObservation,
    GeoObservationStatus,
    GeoPlatformCode,
    GeoProviderUsage,
    GeoVisibilityEvidence,
    GeoVisibilityEvidenceUnit,
)
from app.geo.providers import GeoProviderError, GeoProviderRequest, GeoProviderResult
from app.geo.runtime import GeoProviderRegistry, GeoSampleExecutor, GeoSynthesizer
from app.geo.worker import GeoWorkerBackend
from app.geo.worker_models import (
    GeoCompetitorSnapshot,
    GeoCompletion,
    GeoExecutionContext,
    GeoMonitoringTarget,
    GeoObservationDisposition,
    GeoQuestionSnapshot,
    GeoSampleMode,
    GeoSynthesisContext,
    GeoTopicSnapshot,
)
from app.harness.runtime import record_model_call, record_provider_call
from app.model_gateway.gateway import (
    GatewayUsage,
    ModelGateway,
    ModelGatewayError,
    ModelIdentity,
    ModelMessages,
    TextResult,
)
from app.observability.logging import JsonFormatter
from app.providers.models import SubmissionState
from app.workers.control_plane import (
    ControlPlaneRejected,
    ControlPlaneUnavailable,
    LocalWorkerControlPlane,
    WorkerControlPlane,
)
from app.workers.models import TaskExecutionMessage, WorkerTaskStatus
from app.workers.runtime import WorkerRuntime
from app.workers.transport import LocalMessageBroker, RawDelivery

TASK_ID = "910001234567890001"
TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"


class _Adapter:
    online_search_supported = True

    def __init__(
        self,
        code: GeoPlatformCode,
        *,
        succeed: bool = True,
        configured: bool = True,
        calls_per_sample: int = 1,
        wait: asyncio.Event | None = None,
    ) -> None:
        self.platform_code = code
        self.provider = code.value
        self.model = f"{code.value.casefold()}-test"
        self.endpoint_host = "provider.invalid"
        self.configured = configured
        self.succeed = succeed
        self.calls_per_sample = calls_per_sample
        self.wait = wait
        self.calls = 0
        self.active = 0
        self.max_active = 0

    async def observe(self, request: GeoProviderRequest) -> GeoProviderResult:
        self.active += 1
        self.max_active = max(self.max_active, self.active)
        try:
            for _ in range(self.calls_per_sample):
                record_geo_provider_call()
                self.calls += 1
            if self.wait is not None:
                await self.wait.wait()
            if not self.succeed:
                raise GeoProviderError(
                    "GEO_PROVIDER_MAINTENANCE", request_count=self.calls_per_sample
                )
            return GeoProviderResult(
                answer_text=f"untrusted answer {request.sample_no}",
                citations=(),
                provider=self.provider,
                model=self.model,
                usage=GeoProviderUsage(input_tokens=3, output_tokens=2),
                provider_call_count=self.calls_per_sample,
                latency_ms=2,
            )
        finally:
            self.active -= 1

    async def aclose(self) -> None:
        return None


class _Gateway:
    identity = ModelIdentity(provider="DEEPSEEK", model="deepseek-v4-flash", version="1")
    is_mock = True
    status = ProviderStatus.AVAILABLE
    last_error_code: str | None = None

    def __init__(
        self,
        *,
        fail: bool = False,
        model: str = "deepseek-v4-flash",
        wait: asyncio.Event | None = None,
        requests: int = 1,
    ) -> None:
        self.identity = ModelIdentity(provider="DEEPSEEK", model=model, version="1")
        self.fail = fail
        self.calls = 0
        self.messages: ModelMessages | None = None
        self.wait = wait
        self.requests = requests

    async def probe(self) -> bool:
        return True

    async def answer_general(
        self,
        messages: ModelMessages,
        *,
        on_delta: object | None = None,
    ) -> TextResult:
        del messages, on_delta
        raise AssertionError("structured GEO synthesis is required")

    async def complete_structured(
        self,
        messages: ModelMessages,
        response_model: type[BaseModel],
        *,
        operation: str,
        max_tokens: int,
        error_code: str,
        repair_instruction: str,
    ) -> tuple[BaseModel, GatewayUsage]:
        del max_tokens, error_code, repair_instruction
        assert operation == "geo_synthesis"
        for _ in range(self.requests):
            record_model_call()
            self.calls += 1
        self.messages = messages
        if self.wait is not None:
            await self.wait.wait()
        if self.fail:
            raise ModelGatewayError("DEEPSEEK_GEO_SYNTHESIS_UNAVAILABLE")
        return response_model.model_validate(
            {
                "summary": "bounded summary",
                "coverageGaps": [],
                "citationStructure": [],
                "competitorSignals": [],
                "competitorCandidates": [],
                "findings": ["one finding"],
                "recommendations": [],
                "contentBlueprint": [],
            }
        ), GatewayUsage(input_tokens=20, output_tokens=10, latency_ms=5, request_count=1)

    async def aclose(self) -> None:
        return None


class _ControlPlane:
    def __init__(
        self,
        *,
        observations: tuple[GeoObservation, ...] = (),
        fail_observation_at: int | None = None,
        visibility_evidence: GeoVisibilityEvidence | None = None,
    ) -> None:
        self.observations = {_unit_key(item): item for item in observations}
        self.fail_observation_at = fail_observation_at
        self.observation_reports = 0
        self.stage_codes: list[str] = []
        self.failures: list[str] = []
        self.completions: list[GeoCompletion] = []
        self.visibility_evidence = visibility_evidence
        self.visibility_reads = 0

    async def heartbeat(self, task_id: str, **kwargs: object) -> None:
        del task_id, kwargs

    async def report_progress(self, task_id: str, **kwargs: object) -> None:
        del task_id
        self.stage_codes.append(cast(str, kwargs["stage_code"]))

    async def list_geo_observations(
        self, task_id: str, **kwargs: object
    ) -> tuple[GeoObservation, ...]:
        del task_id, kwargs
        return tuple(self.observations.values())

    async def get_geo_visibility_evidence(
        self, task_id: str, **kwargs: object
    ) -> GeoVisibilityEvidence:
        del task_id, kwargs
        self.visibility_reads += 1
        return self.visibility_evidence or GeoVisibilityEvidence(
            target_status="TARGET_UNRESOLVED",
            matching_version="explicit-name-mention-v1",
            units=tuple(
                GeoVisibilityEvidenceUnit(
                    question_id=item.question_id,
                    platform_code=item.platform_code.value,
                    sample_no=item.sample_no,
                    target_mentioned=None,
                )
                for item in self.observations.values()
            ),
        )

    async def report_geo_observation(
        self,
        task_id: str,
        *,
        observation: GeoObservation,
        **kwargs: object,
    ) -> GeoObservationDisposition:
        del task_id, kwargs
        self.observation_reports += 1
        if self.fail_observation_at == self.observation_reports:
            raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_TIMEOUT")
        key = _unit_key(observation)
        if key in self.observations:
            return GeoObservationDisposition.DUPLICATE
        self.observations[key] = observation
        return GeoObservationDisposition.CREATED

    async def report_geo_completion(
        self,
        task_id: str,
        *,
        completion: GeoCompletion,
        **kwargs: object,
    ) -> str:
        del task_id, kwargs
        self.completions.append(completion)
        return "SUCCEEDED"

    async def report_failure(
        self,
        task_id: str,
        *,
        code: str,
        submission_state: SubmissionState,
        **kwargs: object,
    ) -> None:
        del task_id, kwargs
        assert submission_state is SubmissionState.CONFIRMED
        self.failures.append(code)


def _context(
    *,
    sample_mode: GeoSampleMode = GeoSampleMode.STANDARD,
    platforms: tuple[GeoPlatformCode, ...] = (GeoPlatformCode.DOUBAO,),
    completed: frozenset[str] = frozenset(),
    max_provider_calls: int = 20,
    max_concurrency: int = 2,
    deadline_at: datetime | None = None,
) -> GeoExecutionContext:
    questions = (
        GeoQuestionSnapshot(
            id="910001234567890101",
            category="BRAND_DISCOVERY",
            text="untrusted question one",
        ),
        GeoQuestionSnapshot(
            id="910001234567890102",
            category="BRAND_DISCOVERY",
            text="untrusted question two",
        ),
        GeoQuestionSnapshot(
            id="910001234567890103",
            category="BRAND_DISCOVERY",
            text="untrusted question three",
        ),
    )
    return GeoExecutionContext(
        task_id=TASK_ID,
        execution_no=1,
        topic_id="910001234567890201",
        question_ids=tuple(item.id for item in questions),
        platform_codes=platforms,
        sample_mode=sample_mode,
        region="全国",
        synthesis_context=GeoSynthesisContext(
            topic=GeoTopicSnapshot(
                id="910001234567890201",
                space_id="910001234567890301",
                product_id=None,
                name="测试主题",
                region="全国",
                version=1,
            ),
            questions=questions,
            competitor_set_version=1,
            competitors=(
                GeoCompetitorSnapshot(
                    id="910001234567890401",
                    name="测试竞品",
                    aliases=(),
                    competitor_type="DIRECT",
                ),
            ),
        ),
        completed_unit_keys=completed,
        max_provider_calls=max_provider_calls,
        max_geo_questions=10,
        max_geo_platforms=7,
        samples_per_combination=sample_mode.samples_per_combination,
        max_concurrency=max_concurrency,
        deadline_at=deadline_at or datetime.now(UTC) + timedelta(minutes=2),
    )


def _message() -> TaskExecutionMessage:
    return TaskExecutionMessage(
        schema_version=1,
        event_id="910001234567890501",
        event_type="TASK_EXECUTION_REQUESTED",
        task_id=TASK_ID,
        task_type=TaskType.GEO,
        user_id="910001234567890601",
        space_id="910001234567890301",
        attempt=1,
        traceparent=TRACEPARENT,
        created_at=datetime.now(UTC),
    )


def _frozen_visibility(
    context: GeoExecutionContext, *, target_mentioned: bool | None = False
) -> GeoVisibilityEvidence:
    return GeoVisibilityEvidence(
        target_status="AVAILABLE" if target_mentioned is not None else "TARGET_UNRESOLVED",
        matching_version="explicit-name-mention-v1",
        units=tuple(
            GeoVisibilityEvidenceUnit(
                question_id=question_id,
                platform_code=platform.value,
                sample_no=sample_no,
                target_mentioned=target_mentioned,
            )
            for question_id, platform, sample_no in context.units()
        ),
    )


def _backend(
    adapters: tuple[_Adapter, ...], *, gateway: _Gateway | None = None
) -> GeoWorkerBackend:
    registry = GeoProviderRegistry(adapters)
    return GeoWorkerBackend(
        registry,
        GeoSampleExecutor(registry),
        GeoSynthesizer(cast(ModelGateway, gateway or _Gateway())),
    )


def test_geo_worker_accepts_the_official_account_flash_alias() -> None:
    adapters = tuple(_Adapter(code) for code in GeoPlatformCode)
    backend = _backend(adapters, gateway=_Gateway(model="deepseek-flash"))

    assert asyncio.run(backend.probe()) is True


async def _execute(
    backend: GeoWorkerBackend,
    context: GeoExecutionContext,
    control: _ControlPlane,
):
    try:
        return await backend.execute(
            _message(),
            context,
            execution_token="execution-token",
            control_plane=control,
            heartbeat_interval_seconds=60,
        )
    finally:
        await backend.aclose()


def test_standard_and_deep_create_the_exact_authorized_unit_matrix() -> None:
    async def scenario() -> tuple[int, int]:
        standard_control = _ControlPlane()
        deep_control = _ControlPlane()
        await _execute(
            _backend((_Adapter(GeoPlatformCode.DOUBAO),)),
            _context(),
            standard_control,
        )
        await _execute(
            _backend((_Adapter(GeoPlatformCode.DOUBAO),)),
            _context(sample_mode=GeoSampleMode.DEEP),
            deep_control,
        )
        return len(standard_control.observations), len(deep_control.observations)

    assert asyncio.run(scenario()) == (3, 9)


def test_platform_failure_is_persisted_without_blocking_other_platforms() -> None:
    async def scenario() -> tuple[GeoObservation, ...]:
        control = _ControlPlane()
        backend = _backend(
            (
                _Adapter(GeoPlatformCode.DOUBAO),
                _Adapter(GeoPlatformCode.WENXIN, succeed=False),
            )
        )
        outcome = await _execute(
            backend,
            _context(platforms=(GeoPlatformCode.DOUBAO, GeoPlatformCode.WENXIN)),
            control,
        )
        assert outcome.succeeded is True
        return tuple(control.observations.values())

    observations = asyncio.run(scenario())
    assert sum(item.status is GeoObservationStatus.SUCCEEDED for item in observations) == 3
    assert sum(item.status is GeoObservationStatus.FAILED for item in observations) == 3


class _RetryAdapter(_Adapter):
    def __init__(
        self,
        *,
        code: str = "GEO_PROVIDER_MAINTENANCE",
        failures: int = 1,
        first_succeeds: bool = False,
        delay: float = 0,
        wait_limit: float = 120,
    ) -> None:
        super().__init__(GeoPlatformCode.DOUBAO)
        self.code = code
        self.failures = failures
        self.first_succeeds = first_succeeds
        self.delay = delay
        self.wait_limit = wait_limit
        self.attempts: dict[str, int] = {}
        self.events: list[tuple[str, int, datetime]] = []

    async def observe(self, request: GeoProviderRequest) -> GeoProviderResult:
        record_geo_provider_call()
        self.calls += 1
        attempt = self.attempts.get(request.question_id, 0) + 1
        self.attempts[request.question_id] = attempt
        self.events.append((request.question_id, attempt, datetime.now(UTC)))
        await asyncio.sleep(0)
        if attempt <= self.failures and not (
            self.first_succeeds and request.question_id.endswith("101")
        ):
            raise GeoProviderError(
                self.code,
                request_count=1,
                retry_not_before=datetime.now(UTC) + timedelta(seconds=self.delay),
                retry_wait_limit_seconds=self.wait_limit,
            )
        return GeoProviderResult(
            answer_text="authorized platform answer",
            citations=(),
            provider=self.provider,
            model=self.model,
            usage=GeoProviderUsage(input_tokens=3, output_tokens=2),
            provider_call_count=1,
            latency_ms=2,
        )


def test_compensation_runs_after_all_first_samples_and_keeps_successes() -> None:
    async def scenario() -> None:
        adapter = _RetryAdapter(first_succeeds=True)
        control = _ControlPlane()
        await _execute(_backend((adapter,)), _context(), control)
        assert [event[1] for event in adapter.events] == [1, 1, 1, 2, 2]
        assert adapter.attempts["910001234567890101"] == 1
        assert control.observation_reports == len(control.observations) == 3
        assert all(
            item.status is GeoObservationStatus.SUCCEEDED for item in control.observations.values()
        )
        assert sorted(item.provider_call_count for item in control.observations.values()) == [
            1,
            2,
            2,
        ]
        # Earlier failed attempts did not return tokens; never invent their usage.
        assert all(item.usage.input_tokens == 3 for item in control.observations.values())
        assert control.stage_codes == ["GEO_SAMPLING", "GEO_RETRYING", "GEO_SYNTHESIZING"]

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "code",
    (
        "GEO_PROVIDER_TIMEOUT",
        "GEO_PROVIDER_NETWORK_UNAVAILABLE",
        "GEO_PROVIDER_RATE_LIMITED",
        "GEO_PROVIDER_MAINTENANCE",
    ),
)
def test_compensation_is_bounded_to_one_extra_round(code: str) -> None:
    async def scenario() -> None:
        adapter = _RetryAdapter(code=code, failures=99)
        control = _ControlPlane()
        await _execute(_backend((adapter,)), _context(), control)
        assert adapter.calls == 6
        assert set(adapter.attempts.values()) == {2}
        assert control.observation_reports == 3
        assert all(item.provider_call_count == 2 for item in control.observations.values())
        assert all(item.answer_text is None for item in control.observations.values())
        assert control.failures == ["GEO_ZERO_SUCCESSFUL_OBSERVATIONS"]

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "code",
    (
        "GEO_PROVIDER_AUTH_FAILED",
        "GEO_PROVIDER_REQUEST_REJECTED",
        "GEO_PROVIDER_SEARCH_NOT_OBSERVED",
        "GEO_PROVIDER_RESPONSE_INVALID",
        "GEO_DEEPSEEK_MODEL_UNAVAILABLE",
    ),
)
def test_compensation_does_not_repeat_permanent_or_unknown_errors(code: str) -> None:
    async def scenario() -> None:
        adapter = _RetryAdapter(code=code)
        control = _ControlPlane()
        await _execute(_backend((adapter,)), _context(), control)
        assert adapter.calls == 3
        assert "GEO_RETRYING" not in control.stage_codes
        assert control.observation_reports == 3

    asyncio.run(scenario())


@pytest.mark.parametrize("budget", (3, 4))
def test_compensation_uses_only_the_original_remaining_budget(budget: int) -> None:
    async def scenario() -> None:
        adapter = _RetryAdapter()
        control = _ControlPlane()
        await _execute(_backend((adapter,)), _context(max_provider_calls=budget), control)
        assert adapter.calls == budget
        assert sum(item.provider_call_count for item in control.observations.values()) == budget
        assert (
            sum(
                item.status is GeoObservationStatus.SUCCEEDED
                for item in control.observations.values()
            )
            == budget - 3
        )
        assert control.observation_reports == 3

    asyncio.run(scenario())


@pytest.mark.parametrize("deadline_seconds,delay,limit", ((1, 2, 120), (60, 2, 1)))
def test_compensation_refuses_wait_beyond_deadline_or_provider_limit(
    deadline_seconds: int,
    delay: int,
    limit: int,
) -> None:
    async def scenario() -> None:
        adapter = _RetryAdapter(delay=delay, wait_limit=limit)
        control = _ControlPlane()
        context = _context(deadline_at=datetime.now(UTC) + timedelta(seconds=deadline_seconds))
        await _execute(_backend((adapter,)), context, control)
        assert adapter.calls == 3
        assert "GEO_RETRYING" not in control.stage_codes
        assert control.observation_reports == 3

    asyncio.run(scenario())


def test_compensation_respects_retry_after_without_holding_admission() -> None:
    async def scenario() -> None:
        adapter = _RetryAdapter(delay=0.02)
        control = _ControlPlane()
        await _execute(_backend((adapter,)), _context(max_concurrency=1), control)
        for question_id in adapter.attempts:
            times = [at for question, _, at in adapter.events if question == question_id]
            assert (times[1] - times[0]).total_seconds() >= 0.02
        assert adapter.calls == 6

    asyncio.run(scenario())


def test_recovered_execution_does_not_add_another_compensation_round() -> None:
    async def scenario() -> None:
        adapter = _RetryAdapter()
        control = _ControlPlane()
        await _execute(_backend((adapter,)), replace(_context(), execution_no=2), control)
        assert adapter.calls == 3
        assert "GEO_RETRYING" not in control.stage_codes
        assert control.observation_reports == 3

    asyncio.run(scenario())


def test_compensation_wait_is_cancelled_on_lease_loss() -> None:
    class LostRetryLease(_ControlPlane):
        async def heartbeat(self, task_id: str, **kwargs: object) -> None:
            if "GEO_RETRYING" in self.stage_codes:
                raise ControlPlaneUnavailable("JAVA_EXECUTION_LEASE_LOST")

    async def scenario() -> None:
        adapter = _RetryAdapter(delay=10)
        control = LostRetryLease()
        backend = _backend((adapter,))
        try:
            with pytest.raises(ControlPlaneUnavailable, match="JAVA_EXECUTION_LEASE_LOST"):
                await backend.execute(
                    _message(),
                    _context(),
                    execution_token="execution-token",
                    control_plane=control,
                    heartbeat_interval_seconds=0.01,
                )
        finally:
            await backend.aclose()
        assert adapter.calls == 3
        assert not control.completions
        assert not control.observations

    asyncio.run(scenario())


def test_recovery_skips_both_successful_and_failed_persisted_units() -> None:
    now = datetime.now(UTC)
    completed = GeoObservation(
        platform_code=GeoPlatformCode.DOUBAO,
        question_id="910001234567890101",
        sample_no=1,
        status=GeoObservationStatus.FAILED,
        provider="DOUBAO",
        model="doubao-test",
        started_at=now,
        finished_at=now,
        latency_ms=1,
        stable_error_code="GEO_PROVIDER_MAINTENANCE",
        provider_call_count=1,
    )

    async def scenario() -> tuple[int, int]:
        adapter = _Adapter(GeoPlatformCode.DOUBAO)
        control = _ControlPlane(observations=(completed,))
        context = _context(completed=frozenset({_unit_key(completed)}))
        await _execute(_backend((adapter,)), context, control)
        return adapter.calls, control.observation_reports

    assert asyncio.run(scenario()) == (2, 2)


def test_provider_call_budget_is_shared_across_concurrent_units() -> None:
    async def scenario() -> tuple[int, tuple[GeoObservation, ...]]:
        adapter = _Adapter(GeoPlatformCode.DOUBAO)
        control = _ControlPlane()
        await _execute(
            _backend((adapter,)),
            _context(max_provider_calls=1, max_concurrency=2),
            control,
        )
        return adapter.calls, tuple(control.observations.values())

    calls, observations = asyncio.run(scenario())
    assert calls == 1
    assert len(observations) == 3
    assert {item.stable_error_code for item in observations} == {
        None,
        "PROVIDER_CALL_BUDGET_EXCEEDED",
    }


def test_recovery_counts_persisted_provider_calls_against_the_task_budget() -> None:
    now = datetime.now(UTC)
    completed = GeoObservation(
        platform_code=GeoPlatformCode.DOUBAO,
        question_id="910001234567890101",
        sample_no=1,
        status=GeoObservationStatus.SUCCEEDED,
        answer_text="persisted answer",
        provider="DOUBAO",
        model="doubao-test",
        started_at=now,
        finished_at=now,
        latency_ms=1,
        provider_call_count=1,
    )

    async def scenario() -> tuple[int, GeoObservation]:
        adapter = _Adapter(GeoPlatformCode.DOUBAO)
        control = _ControlPlane(observations=(completed,))
        await _execute(
            _backend((adapter,)),
            _context(
                completed=frozenset({_unit_key(completed)}),
                max_provider_calls=1,
            ),
            control,
        )
        missing = control.observations["910001234567890102:DOUBAO:1"]
        return adapter.calls, missing

    calls, missing = asyncio.run(scenario())
    assert calls == 0
    assert missing.status is GeoObservationStatus.FAILED
    assert missing.stable_error_code == "PROVIDER_CALL_BUDGET_EXCEEDED"


def test_expired_deadline_never_calls_provider_and_reports_zero_success() -> None:
    async def scenario() -> tuple[int, list[str]]:
        adapter = _Adapter(GeoPlatformCode.DOUBAO)
        control = _ControlPlane()
        await _execute(
            _backend((adapter,)),
            _context(deadline_at=datetime.now(UTC) - timedelta(seconds=1)),
            control,
        )
        return adapter.calls, control.failures

    assert asyncio.run(scenario()) == (0, ["GEO_ZERO_SUCCESSFUL_OBSERVATIONS"])


def test_observation_persistence_failure_stops_before_synthesis() -> None:
    gateway = _Gateway()

    async def scenario() -> None:
        control = _ControlPlane(fail_observation_at=1)
        with pytest.raises(ControlPlaneUnavailable):
            await _execute(
                _backend((_Adapter(GeoPlatformCode.DOUBAO),), gateway=gateway),
                _context(max_concurrency=1),
                control,
            )

    asyncio.run(scenario())
    assert gateway.calls == 0


def test_authorized_concurrency_bounds_parallel_platform_calls() -> None:
    async def scenario() -> int:
        release = asyncio.Event()
        adapter = _Adapter(GeoPlatformCode.DOUBAO, wait=release)
        task = asyncio.create_task(
            _execute(
                _backend((adapter,)),
                _context(max_concurrency=2),
                _ControlPlane(),
            )
        )
        for _ in range(100):
            if adapter.max_active == 2:
                break
            await asyncio.sleep(0)
        release.set()
        await task
        return adapter.max_active

    assert asyncio.run(scenario()) == 2


def test_geo_worker_cancels_sampling_when_java_lease_is_lost() -> None:
    class LostLeaseControl(_ControlPlane):
        def __init__(self) -> None:
            super().__init__()
            self.heartbeat_calls = 0

        async def heartbeat(self, task_id: str, **kwargs: object) -> None:
            del task_id, kwargs
            self.heartbeat_calls += 1
            if self.heartbeat_calls > 1:
                raise ControlPlaneUnavailable("JAVA_EXECUTION_LEASE_LOST")

    async def scenario() -> tuple[int, int, int, int]:
        adapter = _Adapter(GeoPlatformCode.DOUBAO, wait=asyncio.Event())
        control = LostLeaseControl()
        backend = _backend((adapter,))
        try:
            with pytest.raises(ControlPlaneUnavailable, match="JAVA_EXECUTION_LEASE_LOST"):
                await backend.execute(
                    _message(),
                    _context(max_concurrency=1),
                    execution_token="execution-token",
                    control_plane=control,
                    heartbeat_interval_seconds=0.001,
                )
        finally:
            await backend.aclose()
        return (
            adapter.calls,
            adapter.active,
            len(control.observations),
            len(control.completions),
        )

    provider_calls, active, observations, completions = asyncio.run(scenario())
    assert provider_calls == 1
    assert active == 0
    assert observations == 0
    assert completions == 0


def test_deep_slow_doubao_does_not_starve_any_fast_platform_round() -> None:
    async def scenario() -> None:
        release = asyncio.Event()
        fast_finished = asyncio.Event()
        slow = _Adapter(GeoPlatformCode.DOUBAO, wait=release)
        fast = _Adapter(GeoPlatformCode.QWEN)

        class ProgressControl(_ControlPlane):
            async def report_geo_observation(
                self, task_id: str, *, observation: GeoObservation, **kwargs: object
            ) -> GeoObservationDisposition:
                disposition = await super().report_geo_observation(
                    task_id, observation=observation, **kwargs
                )
                if (
                    sum(
                        item.platform_code is GeoPlatformCode.QWEN
                        for item in self.observations.values()
                    )
                    == 9
                ):
                    fast_finished.set()
                return disposition

        control = ProgressControl()
        task = asyncio.create_task(
            _execute(
                _backend((slow, fast)),
                _context(
                    sample_mode=GeoSampleMode.DEEP,
                    platforms=(GeoPlatformCode.DOUBAO, GeoPlatformCode.QWEN),
                    max_concurrency=3,
                    max_provider_calls=40,
                ),
                control,
            )
        )
        try:
            async with asyncio.timeout(2):
                await fast_finished.wait()
            assert slow.active == 2
            assert slow.max_active == 2
            assert len(control.observations) == 9
        finally:
            release.set()
            await task
        assert len(control.observations) == 18
        assert slow.calls == fast.calls == 9
        assert len(control.completions) == 1

    asyncio.run(scenario())


@pytest.mark.parametrize("budget,requests,expected_calls", ((3, 1, 0), (4, 2, 1)))
def test_synthesis_and_repair_cannot_exceed_remaining_task_calls(
    budget: int, requests: int, expected_calls: int
) -> None:
    async def scenario() -> None:
        gateway = _Gateway(requests=requests)
        control = _ControlPlane()
        await _execute(
            _backend((_Adapter(GeoPlatformCode.DOUBAO),), gateway=gateway),
            _context(max_provider_calls=budget),
            control,
        )
        assert gateway.calls == expected_calls
        assert len(control.observations) == 3
        assert control.completions[0].synthesis.method_version == "geo-deterministic-v1"

    asyncio.run(scenario())


def test_synthesis_hang_is_bounded_and_keeps_successful_samples() -> None:
    async def scenario() -> None:
        gateway = _Gateway(wait=asyncio.Event())
        control = _ControlPlane()
        async with asyncio.timeout(2):
            outcome = await _execute(
                _backend((_Adapter(GeoPlatformCode.DOUBAO),), gateway=gateway),
                _context(deadline_at=datetime.now(UTC) + timedelta(seconds=0.15)),
                control,
            )
        assert gateway.calls == 1
        assert outcome.succeeded
        assert all(
            item.status is GeoObservationStatus.SUCCEEDED for item in control.observations.values()
        )
        assert len(control.observations) == 3
        assert control.completions[0].synthesis.method_version == "geo-deterministic-v1"

    asyncio.run(scenario())


def test_lease_loss_during_synthesis_never_delivers_even_a_fallback() -> None:
    async def scenario() -> None:
        gateway = _Gateway(wait=asyncio.Event())

        class LostLeaseControl(_ControlPlane):
            async def heartbeat(self, task_id: str, **kwargs: object) -> None:
                if gateway.calls:
                    raise ControlPlaneUnavailable("JAVA_EXECUTION_LEASE_LOST")

        control = LostLeaseControl()
        backend = _backend((_Adapter(GeoPlatformCode.DOUBAO),), gateway=gateway)
        try:
            async with asyncio.timeout(2):
                with pytest.raises(ControlPlaneUnavailable, match="JAVA_EXECUTION_LEASE_LOST"):
                    await backend.execute(
                        _message(),
                        _context(),
                        execution_token="execution-token",
                        control_plane=control,
                        heartbeat_interval_seconds=0.03,
                    )
        finally:
            await backend.aclose()
        assert gateway.calls == 1
        assert len(control.observations) == 3
        assert control.completions == []

    asyncio.run(scenario())


def test_synthesis_failure_completes_with_deterministic_report() -> None:
    async def scenario() -> tuple[list[str], int, list[GeoCompletion]]:
        gateway = _Gateway(fail=True)
        control = _ControlPlane()
        outcome = await _execute(
            _backend((_Adapter(GeoPlatformCode.DOUBAO),), gateway=gateway),
            _context(),
            control,
        )
        assert outcome.succeeded is True
        return control.failures, len(control.observations), control.completions

    failures, observations, completions = asyncio.run(scenario())
    assert failures == []
    assert observations == 3
    assert len(completions) == 1
    assert completions[0].synthesis.method_version == "geo-deterministic-v1"
    assert completions[0].synthesis.competitor_candidates == ()
    assert completions[0].synthesis.competitor_discovery_version is None
    assert completions[0].synthesis_usage.provider == "SYSTEM"
    assert completions[0].synthesis_usage.provider_call_count == 0


def test_synthesis_receives_authorized_context_as_untrusted_user_data() -> None:
    gateway = _Gateway()

    async def scenario() -> None:
        context = replace(
            _context(),
            synthesis_context=_context().synthesis_context.model_copy(
                update={
                    "monitoring_target": GeoMonitoringTarget(
                        kind="PRODUCT", name="光禾恒温杯 T1", aliases=("GH T1",)
                    ),
                }
            ),
        )
        await _execute(
            _backend((_Adapter(GeoPlatformCode.DOUBAO),), gateway=gateway),
            context,
            _ControlPlane(visibility_evidence=_frozen_visibility(context)),
        )

    asyncio.run(scenario())
    assert gateway.messages is not None
    assert "untrusted data" in gateway.messages[0].content
    assert "untrusted question one" not in gateway.messages[0].content
    assert "untrusted question one" in gateway.messages[1].content
    payload = json.loads(gateway.messages[1].content)
    assert payload["authorizedContext"]["monitoringTarget"] == {
        "kind": "PRODUCT",
        "name": "光禾恒温杯 T1",
        "aliases": ["GH T1"],
    }
    assert "光禾恒温杯 T1" not in gateway.messages[0].content


@pytest.mark.parametrize("mentioned", [False, True])
def test_first_synthesis_reads_full_matrix_flags_once_and_never_rematches_excerpt(
    mentioned: bool,
) -> None:
    context = _context(platforms=(GeoPlatformCode.DEEPSEEK,))
    context = replace(
        context,
        synthesis_context=context.synthesis_context.model_copy(
            update={
                "monitoring_target": GeoMonitoringTarget(
                    kind="BRAND", name="合成公开咖啡店", aliases=()
                ),
            }
        ),
    )
    gateway = _Gateway()
    control = _ControlPlane(
        visibility_evidence=_frozen_visibility(context, target_mentioned=mentioned)
    )
    outcome = asyncio.run(
        _execute(
            _backend((_Adapter(GeoPlatformCode.DEEPSEEK),), gateway=gateway),
            context,
            control,
        )
    )
    assert outcome.succeeded and control.visibility_reads == 1 and gateway.calls == 1
    assert gateway.messages is not None
    packet = json.loads(gateway.messages[1].content)
    assert control.visibility_evidence is not None
    assert packet["visibilityEvidence"] == control.visibility_evidence.model_dump(
        mode="json", by_alias=True
    )
    platform = packet["canonicalFacts"]["platforms"][0]
    assert platform["platformName"] == "DeepSeek" and platform["validSamples"] == 3
    assert platform["mentionedSamples"] == (3 if mentioned else 0)
    assert platform["mentionRate"] == (100.0 if mentioned else 0.0)
    assert all("合成公开咖啡店" not in item["answerText"] for item in packet["observations"])
    if not mentioned:
        assert "3 个可核验的有效回答" in control.completions[0].synthesis.summary


@pytest.mark.parametrize("fault", ["partial", "duplicate", "foreign", "unresolved_target"])
def test_first_synthesis_rejects_incomplete_or_contradictory_java_flags_before_model(
    fault: str,
) -> None:
    context = _context(platforms=(GeoPlatformCode.DEEPSEEK,))
    context = replace(
        context,
        synthesis_context=context.synthesis_context.model_copy(
            update={
                "monitoring_target": GeoMonitoringTarget(
                    kind="BRAND", name="合成公开咖啡店", aliases=()
                ),
            }
        ),
    )
    flags = _frozen_visibility(context)
    if fault == "partial":
        flags = flags.model_copy(update={"units": flags.units[:1]})
    elif fault == "duplicate":
        flags = flags.model_copy(update={"units": (*flags.units, flags.units[0])})
    elif fault == "foreign":
        flags = flags.model_copy(
            update={
                "units": (
                    flags.units[0].model_copy(update={"question_id": "999"}),
                    *flags.units[1:],
                )
            }
        )
    else:
        flags = _frozen_visibility(context, target_mentioned=None)
    gateway = _Gateway()
    control = _ControlPlane(visibility_evidence=flags)
    with pytest.raises(ControlPlaneRejected) as error:
        asyncio.run(
            _execute(
                _backend((_Adapter(GeoPlatformCode.DEEPSEEK),), gateway=gateway), context, control
            )
        )
    assert error.value.code == "JAVA_GEO_VISIBILITY_EVIDENCE_INVALID"
    assert len(control.observations) == 3 and control.visibility_reads == 1
    assert gateway.calls == 0 and not control.completions


def test_failed_intelligent_synthesis_retains_authoritative_zero_in_deterministic_report() -> None:
    context = _context(platforms=(GeoPlatformCode.DEEPSEEK,))
    context = replace(
        context,
        synthesis_context=context.synthesis_context.model_copy(
            update={
                "monitoring_target": GeoMonitoringTarget(
                    kind="BRAND", name="合成公开咖啡店", aliases=()
                ),
            }
        ),
    )
    control = _ControlPlane(visibility_evidence=_frozen_visibility(context))
    asyncio.run(
        _execute(
            _backend((_Adapter(GeoPlatformCode.DEEPSEEK),), gateway=_Gateway(fail=True)),
            context,
            control,
        )
    )
    completion = control.completions[0]
    assert completion.synthesis.method_version == "geo-deterministic-v1"
    assert "DeepSeek 的 3 个有效回答" in completion.synthesis.summary
    assert (
        "0 个明确提及" in completion.synthesis.summary
        and "可见度为 0%" in completion.synthesis.summary
    )
    assert "豆包" not in completion.synthesis.summary
    assert "智能总结暂时不可用" in completion.synthesis.summary
    assert completion.synthesis.recommendations == completion.synthesis.content_blueprint == ()


def test_missing_monitoring_target_is_legacy_compatible_but_strict_when_present() -> None:
    context = _context().synthesis_context
    assert context.monitoring_target is None
    legacy_payload = context.model_dump(mode="json", by_alias=True)
    legacy_payload.pop("monitoringTarget")
    assert "monitoringTarget" not in legacy_payload
    assert GeoSynthesisContext.model_validate_json(json.dumps(legacy_payload)) == context
    with pytest.raises(ValidationError):
        GeoMonitoringTarget.model_validate_json(
            '{"kind":"PRODUCT","name":"商品","aliases":[],"userId":"1"}'
        )
    with pytest.raises(ValidationError):
        GeoMonitoringTarget(kind="PRODUCT", name="商品", aliases=tuple("x" for _ in range(51)))


def test_fallback_never_infers_merchant_identity_or_content_gaps_from_timeouts() -> None:
    async def scenario() -> GeoCompletion:
        control = _ControlPlane()
        await _execute(
            _backend(
                (
                    _Adapter(GeoPlatformCode.DOUBAO, succeed=False),
                    _Adapter(GeoPlatformCode.DEEPSEEK),
                ),
                gateway=_Gateway(fail=True),
            ),
            _context(platforms=(GeoPlatformCode.DOUBAO, GeoPlatformCode.DEEPSEEK)),
            control,
        )
        return control.completions[0]

    completion = asyncio.run(scenario())
    synthesis = completion.synthesis
    assert "成功获得 3 个有效回答" in synthesis.summary
    assert "失败 3 个" in synthesis.summary
    assert "智能总结暂时不可用" in synthesis.summary
    assert synthesis.recommendations == ()
    assert synthesis.content_blueprint == ()
    assert synthesis.competitor_signals == ()
    assert "测试主题" not in "".join(synthesis.findings)
    assert "提及" not in "".join(synthesis.findings)
    assert len(synthesis.missing_samples) == 3
    assert synthesis.coverage_gaps[0] == "DOUBAO 有 3 个监测点未取得有效回答。"


def test_geo_logs_omit_answers_citation_queries_and_full_question_ids(
    caplog: pytest.LogCaptureFixture,
) -> None:
    secret_answer = "never-log-provider-answer"
    signed_url = "https://example.com/source?signature=never-log-citation"

    class SensitiveAdapter(_Adapter):
        async def observe(self, request: GeoProviderRequest) -> GeoProviderResult:
            record_provider_call()
            self.calls += 1
            now = datetime.now(UTC)
            return GeoProviderResult(
                answer_text=secret_answer,
                citations=(
                    GeoCitation(
                        title="source",
                        url=signed_url,
                        observed_at=now,
                    ),
                ),
                provider=self.provider,
                model=self.model,
                usage=GeoProviderUsage(search_requests=1),
                provider_call_count=1,
                latency_ms=1,
            )

    async def scenario() -> None:
        with caplog.at_level(logging.INFO, logger="app.geo.runtime"):
            await _execute(
                _backend((SensitiveAdapter(GeoPlatformCode.DOUBAO),)),
                _context(),
                _ControlPlane(),
            )

    asyncio.run(scenario())
    rendered = "\n".join(JsonFormatter().format(record) for record in caplog.records)
    assert secret_answer not in rendered
    assert signed_url not in rendered
    assert "never-log-citation" not in rendered
    assert "910001234567890101" not in rendered


def test_context_rejects_unknown_completed_units_and_mode_sample_mismatch() -> None:
    with pytest.raises(ValueError, match="completedUnitKeys"):
        _context(completed=frozenset({"910001234567890101:QWEN:1"}))
    with pytest.raises(ValueError, match="samplesPerCombination"):
        replace(_context(), samples_per_combination=3)


def test_existing_worker_runtime_handles_geo_without_media_provider_regression() -> None:
    context = _context()

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

    async def scenario() -> tuple[str, WorkerTaskStatus, str, int]:
        adapters = tuple(_Adapter(code) for code in GeoPlatformCode)
        control = RuntimeControl()
        worker = WorkerRuntime(
            initialize_runtime(
                AppSettings(environment="test", worker_task_type="GEO", worker_provider="REAL")
            ),
            provider=None,
            geo_backend=_backend(adapters),
            control_plane=cast(WorkerControlPlane, control),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        delivery = RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(_message().event_id)
        assert snapshot is not None
        status = worker.status_snapshot()
        await worker.close()
        return delivery.state, snapshot.status, status.provider, status.processed

    assert asyncio.run(scenario()) == (
        "ACKED",
        WorkerTaskStatus.RESULT_READY,
        "GEO_MULTI_PLATFORM",
        1,
    )


def _unit_key(observation: GeoObservation) -> str:
    return f"{observation.question_id}:{observation.platform_code.value}:{observation.sample_no}"

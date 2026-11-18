"""ARTICLE Worker integration tests; no network or real model calls."""

from __future__ import annotations

import asyncio
import json
import logging
from datetime import UTC, datetime, timedelta
from typing import cast

import httpx
import pytest
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from pydantic import BaseModel, SecretStr

from app.article.models import (
    ArticleExecutionBudget,
    ArticleExecutionRequest,
    ArticleStage,
    ArticleTargetLength,
)
from app.article.runtime import ArticleExecutionKernel
from app.article.worker import ArticleWorkerBackend
from app.article.worker_models import ArticleCompletion, ArticleExecutionContext
from app.common.enums import ProviderStatus, TaskType
from app.common.runtime import initialize_runtime
from app.common.settings import AppSettings, ProviderSettings, default_bundle_path
from app.harness.runtime import record_model_call
from app.integration.java_internal import JavaInternalClient
from app.model_gateway.deepseek import DeepSeekAdapter
from app.model_gateway.gateway import GatewayUsage, ModelIdentity, ModelMessages
from app.observability.tracing import configure_tracing, shutdown_tracing
from app.prompts.assembly import CommercePromptSet
from app.prompts.loader import load_bundle
from app.providers.models import SubmissionState
from app.workers.control_plane import (
    ClaimDisposition,
    ClaimResult,
    ControlPlaneUnavailable,
    JavaTaskStatus,
)
from app.workers.java_control_plane import JavaWorkerControlPlane
from app.workers.models import TaskExecutionMessage
from app.workers.runtime import WorkerRuntime
from app.workers.transport import LocalMessageBroker, RawDelivery
from tests.internal_security import internal_settings

TASK_ID = "1900012345678901700"
EVENT_ID = "1900012345678906000"
TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"


class _ArticleGateway:
    identity = ModelIdentity(provider="DEEPSEEK", model="deepseek-test", version="chat-v1")
    is_mock = True
    status = ProviderStatus.AVAILABLE
    last_error_code: str | None = None

    def __init__(self, payload: dict[str, object]) -> None:
        self.payload = payload
        self.model_calls = 0
        self.probe_calls = 0
        self.closed = False

    async def probe(self) -> bool:
        self.probe_calls += 1
        return True

    async def complete_structured(
        self,
        messages: ModelMessages,
        response_model: type[BaseModel],
        *,
        operation: str,
        max_tokens: int,
        error_code: str,
        repair_instruction: str,
        repair_attempts: int = 1,
    ) -> tuple[BaseModel, GatewayUsage]:
        del operation, max_tokens, error_code, repair_instruction, repair_attempts
        self.model_calls += 1
        record_model_call(input_messages=messages)
        return response_model.model_validate(self.payload), GatewayUsage(
            input_tokens=20,
            output_tokens=10,
            latency_ms=5,
            request_count=1,
        )

    async def aclose(self) -> None:
        self.closed = True


def _backend(gateway: _ArticleGateway) -> ArticleWorkerBackend:
    loaded = load_bundle(default_bundle_path())
    prompts = CommercePromptSet.from_bundle(loaded.bundle, loaded.bundle.profiles[0])
    return ArticleWorkerBackend(ArticleExecutionKernel(cast(object, gateway), prompts))  # type: ignore[arg-type]


def _context() -> ArticleExecutionContext:
    return ArticleExecutionContext(
        task_id=TASK_ID,
        execution_no=1,
        article_version=1,
        request=ArticleExecutionRequest(
            mode=ArticleStage.TITLE,
            article_id="1900012345678903001",
            brief="准备克制、可核验的商品选购内容",
            target_length=ArticleTargetLength.SHORT,
            tone="PROFESSIONAL",
            space_summary='{"name":"测试经营空间"}',
            brand_summary='{"name":"测试品牌"}',
            budget=ArticleExecutionBudget(
                deadline_at=datetime.now(UTC) + timedelta(seconds=30),
                max_model_calls=2,
                max_repair_retries=1,
                max_input_tokens=16_000,
                max_output_tokens=4_096,
            ),
        ),
        authorized_outline=(),
    )


def _message() -> TaskExecutionMessage:
    return TaskExecutionMessage(
        schema_version=1,
        event_id=EVENT_ID,
        event_type="TASK_EXECUTION_REQUESTED",
        task_id=TASK_ID,
        task_type=TaskType.ARTICLE,
        user_id="1900012345678901001",
        space_id="1900012345678901100",
        attempt=1,
        traceparent=TRACEPARENT,
        created_at=datetime.now(UTC),
    )


class _ArticleControlPlane:
    def __init__(self, context: ArticleExecutionContext, disposition: ClaimDisposition) -> None:
        self.context = context
        self.disposition = disposition
        self.calls: list[str] = []
        self.completion: ArticleCompletion | None = None

    async def probe(self) -> bool:
        self.calls.append("probe")
        return True

    async def claim(self, message: TaskExecutionMessage, **kwargs: object) -> ClaimResult:
        del message, kwargs
        self.calls.append("claim")
        if self.disposition is ClaimDisposition.CLAIMED:
            return ClaimResult(
                disposition=self.disposition,
                execution_no=1,
                execution_token="synthetic-token",
            )
        return ClaimResult(disposition=self.disposition)

    async def get_execution_context(
        self, task_id: str, **kwargs: object
    ) -> ArticleExecutionContext:
        del task_id, kwargs
        self.calls.append("context")
        return self.context

    async def heartbeat(self, task_id: str, **kwargs: object) -> None:
        del task_id, kwargs
        self.calls.append("heartbeat")

    async def report_progress(self, task_id: str, **kwargs: object) -> None:
        del task_id, kwargs
        self.calls.append("progress")

    async def report_article_preview(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        attempt: int,
        sequence: int,
        markdown: str,
        traceparent: str,
    ) -> None:
        del task_id, execution_no, execution_token, attempt, sequence, markdown, traceparent
        self.calls.append("preview")

    async def report_article_completion(
        self,
        task_id: str,
        *,
        completion: ArticleCompletion,
        **kwargs: object,
    ) -> JavaTaskStatus:
        del task_id, kwargs
        self.calls.append("result")
        self.completion = completion
        return "SUCCEEDED"

    async def report_failure(
        self,
        task_id: str,
        *,
        code: str,
        submission_state: SubmissionState,
        **kwargs: object,
    ) -> None:
        del task_id, code, submission_state, kwargs
        self.calls.append("failure")

    async def aclose(self) -> None:
        self.calls.append("close")


def test_article_worker_runs_one_authorized_stage_and_hands_result_to_java() -> None:
    gateway = _ArticleGateway(
        {
            "candidates": [
                {"text": "标题一", "rationale": "突出场景"},
                {"text": "标题二", "rationale": "说明方法"},
                {"text": "标题三", "rationale": "保持克制"},
                {"text": "标题四", "rationale": "提供清单"},
                {"text": "标题五", "rationale": "强调核验"},
            ],
            "warnings": [],
        }
    )
    backend = _backend(gateway)
    control = _ArticleControlPlane(_context(), ClaimDisposition.CLAIMED)

    outcome = asyncio.run(
        backend.execute(
            _message(),
            control.context,
            execution_token="synthetic-token",
            control_plane=control,
            heartbeat_interval_seconds=60,
        )
    )

    assert outcome.succeeded is True
    assert gateway.model_calls == 1
    assert control.calls == ["heartbeat", "progress", "result"]
    assert control.completion is not None
    assert control.completion.result.mode is ArticleStage.TITLE


def test_article_worker_cancels_model_when_java_lease_is_lost() -> None:
    class BlockingGateway(_ArticleGateway):
        def __init__(self) -> None:
            super().__init__({})
            self.cancelled = False

        async def complete_structured(
            self,
            messages: ModelMessages,
            response_model: type[BaseModel],
            *,
            operation: str,
            max_tokens: int,
            error_code: str,
            repair_instruction: str,
            repair_attempts: int = 1,
        ) -> tuple[BaseModel, GatewayUsage]:
            del (
                messages,
                response_model,
                operation,
                max_tokens,
                error_code,
                repair_instruction,
                repair_attempts,
            )
            self.model_calls += 1
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                self.cancelled = True
                raise
            raise AssertionError("unreachable")

    class LostLeaseControl(_ArticleControlPlane):
        def __init__(self) -> None:
            super().__init__(_context(), ClaimDisposition.CLAIMED)
            self.heartbeat_calls = 0

        async def heartbeat(self, task_id: str, **kwargs: object) -> None:
            del task_id, kwargs
            self.calls.append("heartbeat")
            self.heartbeat_calls += 1
            if self.heartbeat_calls > 1:
                raise ControlPlaneUnavailable("JAVA_EXECUTION_LEASE_LOST")

    async def scenario() -> tuple[bool, list[str]]:
        gateway = BlockingGateway()
        control = LostLeaseControl()
        backend = _backend(gateway)
        try:
            with pytest.raises(ControlPlaneUnavailable, match="JAVA_EXECUTION_LEASE_LOST"):
                await backend.execute(
                    _message(),
                    control.context,
                    execution_token="synthetic-token",
                    control_plane=control,
                    heartbeat_interval_seconds=0.001,
                )
        finally:
            await backend.aclose()
        return gateway.cancelled, control.calls

    cancelled, calls = asyncio.run(scenario())
    assert cancelled is True
    assert calls[:3] == ["heartbeat", "progress", "heartbeat"]
    assert "result" not in calls and "failure" not in calls


def test_article_duplicate_delivery_is_acked_without_model_call() -> None:
    async def scenario() -> tuple[str, int, list[str]]:
        gateway = _ArticleGateway({})
        backend = _backend(gateway)
        control = _ArticleControlPlane(_context(), ClaimDisposition.DUPLICATE)
        worker = WorkerRuntime(
            initialize_runtime(AppSettings(environment="test", worker_task_type="ARTICLE")),
            provider=None,
            article_backend=backend,
            control_plane=cast(object, control),  # type: ignore[arg-type]
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        delivery = RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        result = delivery.state, gateway.model_calls, list(control.calls)
        await worker.close()
        return result

    delivery_state, model_calls, calls = asyncio.run(scenario())
    assert delivery_state == "ACKED"
    assert model_calls == 0
    assert calls[:2] == ["probe", "claim"]
    assert "context" not in calls and "result" not in calls


def test_article_worker_counts_processed_only_after_java_accepts_result() -> None:
    async def scenario() -> tuple[str, str, int, list[str]]:
        gateway = _ArticleGateway(
            {
                "candidates": [
                    {"text": "标题一", "rationale": "突出场景"},
                    {"text": "标题二", "rationale": "说明方法"},
                    {"text": "标题三", "rationale": "保持克制"},
                    {"text": "标题四", "rationale": "提供清单"},
                    {"text": "标题五", "rationale": "强调核验"},
                ],
                "warnings": [],
            }
        )
        backend = _backend(gateway)
        control = _ArticleControlPlane(_context(), ClaimDisposition.CLAIMED)
        worker = WorkerRuntime(
            initialize_runtime(AppSettings(environment="test", worker_task_type="ARTICLE")),
            provider=None,
            article_backend=backend,
            control_plane=cast(object, control),  # type: ignore[arg-type]
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        delivery = RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        status = worker.status_snapshot()
        result = delivery.state, snapshot.status.value, status.processed, list(control.calls)
        await worker.close()
        return result

    delivery_state, task_status, processed, calls = asyncio.run(scenario())
    assert delivery_state == "ACKED"
    assert task_status == "RESULT_READY"
    assert processed == 1
    assert calls[:6] == ["probe", "claim", "context", "heartbeat", "progress", "result"]


@pytest.mark.parametrize("blocked_request", (1, 2), ids=("first-in-flight", "repair-in-flight"))
def test_article_worker_overall_deadline_reports_unknown_without_resubmitting(
    blocked_request: int,
) -> None:
    class FailureControl(_ArticleControlPlane):
        def __init__(self, java_control: JavaWorkerControlPlane) -> None:
            super().__init__(_context(), ClaimDisposition.CLAIMED)
            self.failure: tuple[str, SubmissionState, dict[str, object]] | None = None
            self.java_control = java_control

        async def report_failure(
            self,
            task_id: str,
            *,
            code: str,
            submission_state: SubmissionState,
            **kwargs: object,
        ) -> None:
            self.calls.append("failure")
            self.failure = code, submission_state, kwargs
            await self.java_control.report_failure(
                task_id,
                execution_no=cast(int, kwargs["execution_no"]),
                execution_token=cast(str, kwargs["execution_token"]),
                traceparent=cast(str, kwargs["traceparent"]),
                code=code,
                submission_state=submission_state,
                provider=cast(str | None, kwargs.get("provider")),
                model=cast(str | None, kwargs.get("model")),
            )
            # Java's existing no-receipt UNKNOWN path terminates instead of
            # authorizing another generation when the result cannot be queried.
            self.disposition = ClaimDisposition.DUPLICATE

    async def scenario() -> None:
        requests = 0
        cancelled = False
        failure_events: list[dict[str, object]] = []

        def java_handler(request: httpx.Request) -> httpx.Response:
            assert request.method == "POST"
            assert request.url.path == f"/internal/v1/tasks/{TASK_ID}/execution-events"
            failure_events.append(json.loads(request.content))
            return httpx.Response(200, json={"accepted": True, "taskStatus": "SYSTEM_TERMINATED"})

        async def handler(request: httpx.Request) -> httpx.Response:
            nonlocal requests, cancelled
            if request.method == "GET" and request.url.path == "/models":
                return httpx.Response(200, json={"data": [{"id": "deepseek-test"}]})
            assert request.method == "POST" and request.url.path == "/chat/completions"
            requests += 1
            if requests < blocked_request:
                return httpx.Response(
                    200,
                    json={
                        "choices": [{"message": {"content": "invalid JSON"}}],
                        "usage": {"prompt_tokens": 17, "completion_tokens": 4},
                    },
                )
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                cancelled = True
                raise
            raise AssertionError("The model must be cancelled at the overall stage deadline")

        async with (
            httpx.AsyncClient(
                base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
            ) as client,
            httpx.AsyncClient(
                base_url="http://127.0.0.1:8080", transport=httpx.MockTransport(java_handler)
            ) as java_http,
        ):
            gateway = DeepSeekAdapter(
                ProviderSettings(
                    credential=SecretStr("unit-test-article-secret"),
                    model="deepseek-test",
                    endpoint="https://deepseek.test",
                    max_retries=2,
                ),
                client=client,
            )
            loaded = load_bundle(default_bundle_path())
            prompts = CommercePromptSet.from_bundle(loaded.bundle, loaded.bundle.profiles[0])
            control = FailureControl(
                JavaWorkerControlPlane(
                    JavaInternalClient(
                        internal_settings(java_internal_base_url="http://127.0.0.1:8080"), java_http
                    )
                )
            )
            worker = WorkerRuntime(
                initialize_runtime(AppSettings(environment="test", worker_task_type="ARTICLE")),
                provider=None,
                article_backend=ArticleWorkerBackend(ArticleExecutionKernel(gateway, prompts)),
                control_plane=cast(object, control),  # type: ignore[arg-type]
                broker=LocalMessageBroker(),
            )
            try:
                assert await worker.start()
                context = control.context
                control.context = ArticleExecutionContext(
                    task_id=context.task_id,
                    execution_no=context.execution_no,
                    article_version=context.article_version,
                    authorized_outline=context.authorized_outline,
                    request=context.request.model_copy(
                        update={
                            "budget": context.request.budget.model_copy(
                                update={"deadline_at": datetime.now(UTC) + timedelta(seconds=0.25)}
                            )
                        }
                    ),
                )
                message = _message().model_dump_json(by_alias=True).encode()
                delivery = RawDelivery(body=message)
                await worker.process_delivery(delivery)
                assert delivery.state == "ACKED"
                assert control.failure is not None
                code, submission, details = control.failure
                assert code == "ARTICLE_DEADLINE_EXCEEDED"
                assert submission is SubmissionState.UNKNOWN
                assert details["provider"] == "DEEPSEEK"
                assert details["model"] == "deepseek-test"
                assert "providerRequestId" not in details
                assert "providerTaskId" not in details
                assert len(failure_events) == 1
                assert failure_events[0]["stageCode"] == "PROVIDER_RESULT_UNKNOWN"
                assert failure_events[0]["details"] == {
                    "code": "ARTICLE_DEADLINE_EXCEEDED",
                    "submissionState": "UNKNOWN",
                    "provider": "DEEPSEEK",
                    "model": "deepseek-test",
                    "providerRequestId": None,
                    "providerTaskId": None,
                }
                assert control.calls.count("failure") == 1
                assert "result" not in control.calls
                assert requests == blocked_request and cancelled is True
                # Redelivery still goes through Java claim and cannot call the
                # model after Java has fenced this unknown-result task.
                redelivery = RawDelivery(body=message)
                await worker.process_delivery(redelivery)
                assert redelivery.state == "ACKED"
                assert requests == blocked_request
                snapshot = worker.get_task(EVENT_ID)
                assert snapshot is not None and snapshot.status.value == "DUPLICATE"
            finally:
                await worker.close()

    exporter = InMemorySpanExporter()
    trace_runtime = configure_tracing(
        AppSettings(environment="test", otel_enabled=True, otel_sample_ratio=1),
        process_role="worker",
        span_exporter=exporter,
        synchronous_export=True,
    )
    try:
        asyncio.run(scenario())
        spans = [
            span for span in exporter.get_finished_spans() if span.name == "article.stage.title"
        ]
        assert len(spans) == 1
        attributes = spans[0].attributes
        assert attributes is not None
        assert attributes["usage.requestCount"] == blocked_request
        assert attributes["usage.inputTokens"] == (17 if blocked_request == 2 else 0)
        assert attributes["usage.outputTokens"] == (4 if blocked_request == 2 else 0)
        assert attributes["usage.isLowerBound"] is True
        assert attributes["provider.submissionState"] == "UNKNOWN"
        assert attributes["stableErrorCode"] == "ARTICLE_DEADLINE_EXCEEDED"
        assert _context().request.brief not in repr(attributes)
        assert "synthetic-token" not in repr(attributes)
    finally:
        shutdown_tracing(trace_runtime)


def test_article_worker_failed_response_without_usage_remains_observational_lower_bound() -> None:
    async def scenario() -> None:
        requests = 0

        def handler(request: httpx.Request) -> httpx.Response:
            nonlocal requests
            assert request.method == "POST" and request.url.path == "/chat/completions"
            requests += 1
            return httpx.Response(200, json={"choices": [{"message": {"content": "invalid JSON"}}]})

        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            gateway = DeepSeekAdapter(
                ProviderSettings(
                    credential=SecretStr("unit-test-article-secret"),
                    model="deepseek-test",
                    endpoint="https://deepseek.test",
                ),
                client=client,
            )
            loaded = load_bundle(default_bundle_path())
            prompts = CommercePromptSet.from_bundle(loaded.bundle, loaded.bundle.profiles[0])
            backend = ArticleWorkerBackend(ArticleExecutionKernel(gateway, prompts))
            control = _ArticleControlPlane(_context(), ClaimDisposition.CLAIMED)
            try:
                outcome = await backend.execute(
                    _message(),
                    control.context,
                    execution_token="synthetic-token",
                    control_plane=control,
                    heartbeat_interval_seconds=60,
                )
                assert outcome.succeeded is False
                assert outcome.stable_error_code == "DEEPSEEK_ARTICLE_TITLE_RESPONSE_INVALID"
                assert requests == 2
                assert control.calls.count("failure") == 1
                assert "result" not in control.calls
            finally:
                await backend.aclose()

    exporter = InMemorySpanExporter()
    trace_runtime = configure_tracing(
        AppSettings(environment="test", otel_enabled=True, otel_sample_ratio=1),
        process_role="worker",
        span_exporter=exporter,
        synchronous_export=True,
    )
    try:
        asyncio.run(scenario())
        spans = [
            span for span in exporter.get_finished_spans() if span.name == "article.stage.title"
        ]
        assert len(spans) == 1
        attributes = spans[0].attributes
        assert attributes is not None
        assert attributes["usage.requestCount"] == 2
        assert attributes["usage.inputTokens"] == 0
        assert attributes["usage.outputTokens"] == 0
        assert attributes["usage.isLowerBound"] is True
        assert attributes["provider.submissionState"] == "CONFIRMED"
        assert attributes["stableErrorCode"] == "DEEPSEEK_ARTICLE_TITLE_RESPONSE_INVALID"
        assert _context().request.brief not in repr(attributes)
        assert "synthetic-token" not in repr(attributes)
    finally:
        shutdown_tracing(trace_runtime)


def test_article_worker_control_plane_probe_failure_prevents_model_probe_and_consumption() -> None:
    class _UnavailableControl(_ArticleControlPlane):
        async def probe(self) -> bool:
            self.calls.append("probe")
            raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_CONNECTION_FAILED")

    async def scenario() -> tuple[bool, int, list[str]]:
        gateway = _ArticleGateway({})
        backend = _backend(gateway)
        control = _UnavailableControl(_context(), ClaimDisposition.CLAIMED)
        worker = WorkerRuntime(
            initialize_runtime(AppSettings(environment="test", worker_task_type="ARTICLE")),
            provider=None,
            article_backend=backend,
            control_plane=cast(object, control),  # type: ignore[arg-type]
            broker=LocalMessageBroker(),
        )
        started = await worker.start()
        calls = list(control.calls)
        await worker.close()
        return started, gateway.probe_calls, calls

    started, model_probe_calls, calls = asyncio.run(scenario())
    assert started is False
    assert model_probe_calls == 0
    assert calls == ["probe"]


def test_article_worker_logs_only_safe_metadata(
    caplog: pytest.LogCaptureFixture,
) -> None:
    secret_body = "synthetic-private-article-body"
    gateway = _ArticleGateway(
        {
            "candidates": [
                {"text": "标题一", "rationale": "突出场景"},
                {"text": "标题二", "rationale": "说明方法"},
                {"text": "标题三", "rationale": "保持克制"},
                {"text": "标题四", "rationale": "提供清单"},
                {"text": "标题五", "rationale": "强调核验"},
            ],
            "warnings": ["保持事实边界"],
        }
    )
    context = _context()
    context = ArticleExecutionContext(
        task_id=context.task_id,
        execution_no=context.execution_no,
        article_version=context.article_version,
        request=context.request.model_copy(update={"brief": secret_body}),
        authorized_outline=context.authorized_outline,
    )
    control = _ArticleControlPlane(context, ClaimDisposition.CLAIMED)

    with caplog.at_level(logging.INFO):
        asyncio.run(
            _backend(gateway).execute(
                _message(),
                context,
                execution_token="synthetic-token",
                control_plane=control,
                heartbeat_interval_seconds=60,
            )
        )

    assert secret_body not in caplog.text
    assert "synthetic-token" not in caplog.text

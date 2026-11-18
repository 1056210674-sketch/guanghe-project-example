"""Independent Worker lifecycle, ACK ordering, schema, and local API tests."""

import asyncio
import json
import logging
from dataclasses import replace
from datetime import UTC, datetime
from time import monotonic, sleep
from typing import cast
from uuid import UUID

import pytest
from fastapi.testclient import TestClient
from pydantic import JsonValue, SecretStr

from app.article.worker import ArticleWorkerBackend
from app.common.enums import TaskType
from app.common.runtime import initialize_runtime
from app.common.settings import AppSettings, ProviderMockSettings, ProviderSettings
from app.creative.models import CreativeReferenceAsset, ProductCreativeBrief
from app.geo.worker import GeoWorkerBackend
from app.integration.java_internal import JavaInternalClient, JavaReverseCallResult
from app.observability.logging import JsonFormatter
from app.providers.mock import LocalTaskProviderMock
from app.providers.models import (
    ProviderArtifact,
    ProviderErrorCategory,
    ProviderUsage,
    QwenImageProviderContinuation,
    SubmissionState,
    TaskProviderError,
    TaskProviderRequest,
    TaskProviderResult,
)
from app.workers.application import create_worker_app
from app.workers.control_plane import (
    ControlPlaneRejected,
    ControlPlaneUnavailable,
    ExecutionContext,
    JavaTaskStatus,
    LocalWorkerControlPlane,
)
from app.workers.models import (
    TaskExecutionMessage,
    WorkerStatusSnapshot,
    WorkerTaskSnapshot,
    WorkerTaskStatus,
)
from app.workers.runtime import WorkerRuntime
from app.workers.transport import LocalMessageBroker, MessageBrokerUnavailable, RawDelivery
from tests.internal_security import FakeInternalNonceStore, internal_settings, signed_headers

EVENT_ID = "1900012345678906000"
TASK_ID = "1900012345678901700"
TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"


class _ObservedDelivery(RawDelivery):
    """Expose manual Rabbit-style settlement in a shared lifecycle timeline."""

    def __init__(self, body: bytes, timeline: list[str]) -> None:
        super().__init__(body=body)
        self.timeline = timeline

    async def ack(self) -> None:
        self.timeline.append("ack")
        await super().ack()

    async def nack(self, *, requeue: bool) -> None:
        self.timeline.append("nack-requeue" if requeue else "nack")
        await super().nack(requeue=requeue)

    async def reject(self) -> None:
        self.timeline.append("reject-dlq")
        await super().reject()


def _message(*, event_id: str = EVENT_ID, task_type: TaskType = TaskType.IMAGE):
    return TaskExecutionMessage(
        schema_version=1,
        event_id=event_id,
        event_type="TASK_EXECUTION_REQUESTED",
        task_id=TASK_ID,
        task_type=task_type,
        user_id="1900012345678901001",
        space_id="1900012345678901100",
        attempt=1,
        traceparent=TRACEPARENT,
        created_at=datetime.now(UTC),
    )


def _await_terminal(client: TestClient, status_url: str) -> dict[str, object]:
    deadline = monotonic() + 2
    while monotonic() < deadline:
        payload = cast(dict[str, object], client.get(status_url).json())
        if payload["status"] in {
            "RESULT_READY",
            "HANDOFF_ACCEPTED",
            "FAILED",
            "SUBMISSION_UNKNOWN",
            "DUPLICATE",
            "REJECTED",
        }:
            return payload
        sleep(0.005)
    raise AssertionError("Worker task did not reach a terminal local status")


def test_worker_process_has_independent_health_console_and_all_domain_mocks() -> None:
    settings = internal_settings(
        provider_mock=ProviderMockSettings(latency_ms=0),
    )
    store = FakeInternalNonceStore()
    with TestClient(
        create_worker_app(settings, internal_nonce_store=store),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        assert client.get("/health").json()["service"] == "guanghe-agent-worker"
        assert client.get("/internal/v1/health/ready").json()["status"] == "READY"
        assert client.get("/dev/worker").status_code == 200
        for task_type in ("IMAGE", "VIDEO", "GEO", "ARTICLE"):
            accepted = client.post(
                "/dev/v1/tasks",
                json={"taskType": task_type, "parameters": {"fixture": "synthetic"}},
            )
            assert accepted.status_code == 202
            terminal = _await_terminal(client, accepted.json()["statusUrl"])
            assert terminal["status"] == "RESULT_READY"
            assert terminal["deliveryState"] == "ACKED"
            assert terminal["provider"] == "LOCAL_TASK_PROVIDER"
            assert terminal["artifactCount"] == 1
        worker_status = client.get(
            "/internal/v1/worker/status",
            headers=signed_headers("GET", "/internal/v1/worker/status"),
        ).json()

    assert worker_status["processed"] == 4
    assert worker_status["mockMode"] is True


def test_production_worker_never_falls_back_to_local_stub_or_mock() -> None:
    settings = internal_settings(environment="production")
    with TestClient(
        create_worker_app(settings, internal_nonce_store=FakeInternalNonceStore()),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")
        worker_status = client.get(
            "/internal/v1/worker/status",
            headers=signed_headers("GET", "/internal/v1/worker/status"),
        )
        console = client.get("/dev/worker")

    assert readiness.status_code == 503
    assert readiness.json()["checks"][0]["reasonCode"] == (
        "LOCAL_WORKER_COMPONENTS_FORBIDDEN_IN_PRODUCTION"
    )
    assert worker_status.json()["accepting"] is False
    assert console.status_code == 404


def test_java_control_plane_requires_complete_shared_client_configuration() -> None:
    cases = (
        (
            internal_settings(worker_control_plane="JAVA", worker_task_type="IMAGE"),
            "JAVA_CONTROL_PLANE_CONFIGURATION_UNAVAILABLE",
        ),
        (
            internal_settings(worker_broker="RABBITMQ"),
            "RABBITMQ_REQUIRES_JAVA_CONTROL_PLANE",
        ),
    )

    for settings, expected_code in cases:
        with TestClient(
            create_worker_app(settings, internal_nonce_store=FakeInternalNonceStore()),
            base_url="http://127.0.0.1",
            client=("127.0.0.1", 50000),
        ) as client:
            readiness = client.get("/internal/v1/health/ready")
            worker_status = client.get(
                "/internal/v1/worker/status",
                headers=signed_headers("GET", "/internal/v1/worker/status"),
            )

        assert readiness.status_code == 503
        assert readiness.json()["checks"][0]["reasonCode"] == expected_code
        assert worker_status.json()["reasonCode"] == expected_code


def test_default_java_control_plane_assembles_and_probes_before_readiness(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    probe_calls: list[str | None] = []

    async def reverse_call(
        _client: JavaInternalClient, traceparent: str | None = None
    ) -> JavaReverseCallResult:
        probe_calls.append(traceparent)
        return JavaReverseCallResult(
            accepted=True,
            caller_key_id="test-key",
            trace_id="4bf92f3577b34da6a3ce929d0e0e4736",
        )

    monkeypatch.setattr(JavaInternalClient, "reverse_call", reverse_call)
    settings = internal_settings(
        worker_control_plane="JAVA",
        worker_task_type="IMAGE",
        java_internal_base_url="http://127.0.0.1:8080",
        provider_mock=ProviderMockSettings(latency_ms=0),
    )

    with TestClient(
        create_worker_app(settings, internal_nonce_store=FakeInternalNonceStore()),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")
        worker_status = client.get(
            "/internal/v1/worker/status",
            headers=signed_headers("GET", "/internal/v1/worker/status"),
        )

    assert readiness.status_code == 200
    assert worker_status.json()["status"] == "READY"
    assert worker_status.json()["controlPlane"] == "JAVA"
    assert probe_calls == [None]


@pytest.mark.parametrize("task_type", (TaskType.IMAGE, TaskType.VIDEO))
def test_media_worker_control_plane_probe_failure_prevents_provider_and_broker_start(
    task_type: TaskType,
) -> None:
    timeline: list[str] = []

    class UnavailableControlPlane(LocalWorkerControlPlane):
        async def probe(self) -> bool:
            timeline.append("control-plane-probe")
            raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_CONNECTION_FAILED")

    class UnexpectedProvider(LocalTaskProviderMock):
        async def probe(self) -> bool:
            timeline.append("provider-probe")
            return True

    class UnexpectedBroker(LocalMessageBroker):
        async def start(self) -> None:
            timeline.append("broker-start")

    async def scenario() -> None:
        runtime = initialize_runtime(
            AppSettings(
                environment="test",
                worker_task_type="IMAGE" if task_type is TaskType.IMAGE else "VIDEO",
            )
        )
        worker = WorkerRuntime(
            runtime,
            provider=UnexpectedProvider(ProviderMockSettings(latency_ms=0)),
            control_plane=UnavailableControlPlane(),
            broker=UnexpectedBroker(),
        )

        assert await worker.start() is False
        snapshot = worker.status_snapshot()
        assert snapshot.status == "NOT_READY"
        assert snapshot.reason_code == "JAVA_CONTROL_PLANE_CONNECTION_FAILED"
        assert timeline == ["control-plane-probe"]
        await worker.close()

    asyncio.run(scenario())


def test_explicit_factories_assemble_test_java_and_rabbit_worker() -> None:
    settings = internal_settings(
        worker_control_plane="JAVA",
        worker_broker="RABBITMQ",
        worker_task_type="IMAGE",
        provider_mock=ProviderMockSettings(latency_ms=0),
    )
    control_plane = LocalWorkerControlPlane()
    broker = LocalMessageBroker()
    factory_calls: list[tuple[str, AppSettings]] = []

    def control_plane_factory(resolved: AppSettings) -> LocalWorkerControlPlane:
        factory_calls.append(("control-plane", resolved))
        return control_plane

    def broker_factory(resolved: AppSettings) -> LocalMessageBroker:
        factory_calls.append(("broker", resolved))
        return broker

    with TestClient(
        create_worker_app(
            settings,
            control_plane_factory=control_plane_factory,
            broker_factory=broker_factory,
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")
        worker_status = client.get(
            "/internal/v1/worker/status",
            headers=signed_headers("GET", "/internal/v1/worker/status"),
        ).json()

    assert readiness.status_code == 200
    assert worker_status["status"] == "READY"
    assert worker_status["controlPlane"] == "JAVA"
    assert factory_calls == [("control-plane", settings), ("broker", settings)]


def test_rabbitmq_never_consumes_with_local_control_plane() -> None:
    settings = internal_settings(worker_broker="RABBITMQ", worker_control_plane="LOCAL")
    broker_factory_calls = 0

    def broker_factory(resolved: AppSettings) -> LocalMessageBroker:
        nonlocal broker_factory_calls
        del resolved
        broker_factory_calls += 1
        return LocalMessageBroker()

    with TestClient(
        create_worker_app(
            settings,
            broker_factory=broker_factory,
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")

    assert readiness.status_code == 503
    assert readiness.json()["checks"][0]["reasonCode"] == ("RABBITMQ_REQUIRES_JAVA_CONTROL_PLANE")
    assert broker_factory_calls == 0


def test_java_real_image_worker_requires_qwen_image_3_model() -> None:
    settings = internal_settings(
        worker_control_plane="JAVA",
        worker_task_type="IMAGE",
        worker_provider="REAL",
    )
    provider_factory_calls = 0

    def provider_factory(resolved: AppSettings) -> LocalTaskProviderMock:
        nonlocal provider_factory_calls
        provider_factory_calls += 1
        return LocalTaskProviderMock(resolved.provider_mock)

    with TestClient(
        create_worker_app(
            settings,
            provider_factory=provider_factory,
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")

    assert readiness.status_code == 503
    assert readiness.json()["checks"][0]["reasonCode"] == (
        "JAVA_REAL_IMAGE_REQUIRES_QWEN_IMAGE_3_0"
    )
    assert provider_factory_calls == 0


def test_java_real_image_worker_can_start_with_exact_qwen_provider() -> None:
    class _ReadyRealImageProvider(LocalTaskProviderMock):
        code = "QWEN_IMAGE"
        model = "qwen-image-3.0"
        is_mock = False

    settings = internal_settings(
        worker_control_plane="JAVA",
        worker_task_type="IMAGE",
        worker_provider="REAL",
        qwen_image=ProviderSettings(
            credential=SecretStr("unit-test-qwen-key"),
            model="qwen-image-3.0",
        ),
    )
    provider_factory_calls = 0

    def provider_factory(resolved: AppSettings) -> _ReadyRealImageProvider:
        nonlocal provider_factory_calls
        provider_factory_calls += 1
        return _ReadyRealImageProvider(resolved.provider_mock)

    with TestClient(
        create_worker_app(
            settings,
            provider_factory=provider_factory,
            control_plane_factory=lambda _settings: LocalWorkerControlPlane(),
            broker_factory=lambda _settings: LocalMessageBroker(),
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")
        worker_status = client.get(
            "/internal/v1/worker/status",
            headers=signed_headers("GET", "/internal/v1/worker/status"),
        ).json()

    assert readiness.status_code == 200
    assert provider_factory_calls == 1
    assert worker_status["provider"] == "QWEN_IMAGE"
    assert worker_status["providerModel"] == "qwen-image-3.0"
    assert worker_status["mockMode"] is False


def test_java_real_video_worker_requires_exact_wan_provider_and_video_queue() -> None:
    provider_factory_calls = 0

    def provider_factory(resolved: AppSettings) -> LocalTaskProviderMock:
        nonlocal provider_factory_calls
        provider_factory_calls += 1
        return LocalTaskProviderMock(resolved.provider_mock)

    settings = internal_settings(
        worker_control_plane="JAVA",
        worker_broker="RABBITMQ",
        worker_task_type="VIDEO",
        worker_provider="REAL",
        rabbitmq_queue_name="zhixiaohe.agent.video",
    )
    with TestClient(
        create_worker_app(
            settings,
            provider_factory=provider_factory,
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")

    assert readiness.status_code == 503
    assert readiness.json()["checks"][0]["reasonCode"] == ("JAVA_REAL_VIDEO_REQUIRES_WAN_3_0_VIDEO")
    assert provider_factory_calls == 0


def test_java_real_video_worker_can_start_only_on_the_video_queue() -> None:
    class _ReadyRealVideoProvider(LocalTaskProviderMock):
        code = "WAN_VIDEO"
        model = "wan3.0-video"
        is_mock = False

    settings = internal_settings(
        worker_control_plane="JAVA",
        worker_broker="RABBITMQ",
        worker_task_type="VIDEO",
        worker_provider="REAL",
        rabbitmq_queue_name="zhixiaohe.agent.video",
        wan_video=ProviderSettings(
            credential=SecretStr("unit-test-wan-key"),
            model="wan3.0-video",
        ),
    )

    with TestClient(
        create_worker_app(
            settings,
            provider_factory=lambda resolved: _ReadyRealVideoProvider(resolved.provider_mock),
            control_plane_factory=lambda _settings: LocalWorkerControlPlane(),
            broker_factory=lambda _settings: LocalMessageBroker(),
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")
        worker_status = client.get(
            "/internal/v1/worker/status",
            headers=signed_headers("GET", "/internal/v1/worker/status"),
        ).json()

    assert readiness.status_code == 200
    assert worker_status["taskType"] == "VIDEO"
    assert worker_status["provider"] == "WAN_VIDEO"
    assert worker_status["providerModel"] == "wan3.0-video"
    assert worker_status["mockMode"] is False


def test_rabbitmq_worker_rejects_cross_domain_queue_configuration() -> None:
    with TestClient(
        create_worker_app(
            internal_settings(
                worker_control_plane="JAVA",
                worker_broker="RABBITMQ",
                worker_task_type="VIDEO",
                rabbitmq_queue_name="zhixiaohe.agent.image",
            ),
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")

    assert readiness.status_code == 503
    assert readiness.json()["checks"][0]["reasonCode"] == ("RABBITMQ_QUEUE_TASK_TYPE_MISMATCH")


def test_java_real_geo_worker_uses_frozen_queue_route_and_existing_runtime() -> None:
    class ReadyGeoBackend:
        code = "GEO_MULTI_PLATFORM"
        model = "geo-synthesis-v1"
        is_mock = False

        async def probe(self) -> bool:
            return True

        async def aclose(self) -> None:
            return None

    settings = internal_settings(
        worker_control_plane="JAVA",
        worker_broker="RABBITMQ",
        worker_task_type="GEO",
        worker_provider="REAL",
        rabbitmq_queue_name="zhixiaohe.agent.geo",
        rabbitmq_routing_key="agent.geo",
        worker_bind_port=8012,
    )
    with TestClient(
        create_worker_app(
            settings,
            geo_backend_factory=lambda _settings: cast(GeoWorkerBackend, ReadyGeoBackend()),
            control_plane_factory=lambda _settings: LocalWorkerControlPlane(),
            broker_factory=lambda _settings: LocalMessageBroker(),
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")
        worker_status = client.get(
            "/internal/v1/worker/status",
            headers=signed_headers("GET", "/internal/v1/worker/status"),
        ).json()

    assert readiness.status_code == 200
    assert worker_status["taskType"] == "GEO"
    assert worker_status["provider"] == "GEO_MULTI_PLATFORM"
    assert worker_status["mockMode"] is False


def test_java_article_worker_uses_frozen_queue_route_and_deepseek_backend() -> None:
    class ReadyArticleBackend:
        code = "DEEPSEEK"
        model = "deepseek-v4-flash"
        is_mock = False

        async def probe(self) -> bool:
            return True

        async def aclose(self) -> None:
            return None

    settings = internal_settings(
        model_gateway="DEEPSEEK",
        worker_control_plane="JAVA",
        worker_broker="RABBITMQ",
        worker_task_type="ARTICLE",
        worker_provider="REAL",
        rabbitmq_queue_name="zhixiaohe.agent.article",
        rabbitmq_routing_key="agent.article",
        worker_bind_port=8013,
    )
    with TestClient(
        create_worker_app(
            settings,
            article_backend_factory=lambda _settings: cast(
                ArticleWorkerBackend, ReadyArticleBackend()
            ),
            control_plane_factory=lambda _settings: LocalWorkerControlPlane(),
            broker_factory=lambda _settings: LocalMessageBroker(),
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")
        worker_status = client.get(
            "/internal/v1/worker/status",
            headers=signed_headers("GET", "/internal/v1/worker/status"),
        ).json()

    assert readiness.status_code == 200
    assert worker_status["taskType"] == "ARTICLE"
    assert worker_status["provider"] == "DEEPSEEK"
    assert worker_status["providerModel"] == "deepseek-v4-flash"
    assert worker_status["mockMode"] is False


@pytest.mark.parametrize("routing_key", [None, "agent.geo"])
def test_article_worker_requires_exact_routing_key(routing_key: str | None) -> None:
    with TestClient(
        create_worker_app(
            internal_settings(
                model_gateway="DEEPSEEK",
                worker_control_plane="JAVA",
                worker_broker="RABBITMQ",
                worker_task_type="ARTICLE",
                worker_provider="REAL",
                rabbitmq_queue_name="zhixiaohe.agent.article",
                rabbitmq_routing_key=routing_key,
            ),
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")

    assert readiness.status_code == 503
    assert readiness.json()["checks"][0]["reasonCode"] == (
        "RABBITMQ_ROUTING_KEY_TASK_TYPE_MISMATCH"
    )


@pytest.mark.parametrize("routing_key", [None, "agent.video"])
def test_geo_worker_requires_exact_routing_key_before_opening_rabbit(
    routing_key: str | None,
) -> None:
    with TestClient(
        create_worker_app(
            internal_settings(
                worker_control_plane="JAVA",
                worker_broker="RABBITMQ",
                worker_task_type="GEO",
                worker_provider="REAL",
                rabbitmq_queue_name="zhixiaohe.agent.geo",
                rabbitmq_routing_key=routing_key,
            ),
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")

    assert readiness.status_code == 503
    assert readiness.json()["checks"][0]["reasonCode"] == (
        "RABBITMQ_ROUTING_KEY_TASK_TYPE_MISMATCH"
    )


def test_java_image_control_plane_requires_exact_image_task_scope() -> None:
    provider_factory_calls = 0

    def provider_factory(resolved: AppSettings) -> LocalTaskProviderMock:
        nonlocal provider_factory_calls
        provider_factory_calls += 1
        return LocalTaskProviderMock(resolved.provider_mock)

    with TestClient(
        create_worker_app(
            internal_settings(worker_control_plane="JAVA"),
            provider_factory=provider_factory,
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")

    assert readiness.status_code == 503
    assert readiness.json()["checks"][0]["reasonCode"] == (
        "JAVA_CONTROL_PLANE_REQUIRES_SINGLE_MEDIA_TASK_TYPE"
    )
    assert provider_factory_calls == 0


def test_local_real_provider_mode_can_start_only_with_an_explicit_adapter() -> None:
    class ExplicitRealFixtureProvider(LocalTaskProviderMock):
        code = "REAL_FIXTURE_PROVIDER"
        model = "real-fixture-v1"
        is_mock = False

    settings = internal_settings(
        worker_provider="REAL",
        provider_mock=ProviderMockSettings(latency_ms=0),
    )
    with TestClient(
        create_worker_app(
            settings,
            provider_factory=lambda resolved: ExplicitRealFixtureProvider(resolved.provider_mock),
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")
        worker_status = client.get(
            "/internal/v1/worker/status",
            headers=signed_headers("GET", "/internal/v1/worker/status"),
        ).json()

    assert readiness.status_code == 200
    assert worker_status["provider"] == "REAL_FIXTURE_PROVIDER"
    assert worker_status["mockMode"] is False


def test_worker_internal_status_requires_hmac() -> None:
    settings = internal_settings(provider_mock=ProviderMockSettings(latency_ms=0))
    with TestClient(
        create_worker_app(settings, internal_nonce_store=FakeInternalNonceStore())
    ) as client:
        response = client.get("/internal/v1/worker/status")
    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_SIGNATURE_INVALID"


def test_worker_readiness_rejects_invalid_build_commit() -> None:
    settings = internal_settings(
        git_commit="invalid-build-commit",
        provider_mock=ProviderMockSettings(latency_ms=0),
    )
    with TestClient(
        create_worker_app(settings, internal_nonce_store=FakeInternalNonceStore()),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        response = client.get("/internal/v1/health/ready")
    checks = {item["name"]: item for item in response.json()["checks"]}
    assert response.status_code == 503
    assert response.json()["gitCommit"] == "UNAVAILABLE"
    assert checks["buildCommit"]["reasonCode"] == "BUILD_COMMIT_NOT_READY"


def test_unknown_schema_is_rejected_to_dlq_path() -> None:
    async def scenario() -> tuple[str, WorkerTaskStatus, str | None, list[str], bool]:
        runtime = initialize_runtime(AppSettings(environment="test"))
        worker = WorkerRuntime(
            runtime,
            provider=LocalTaskProviderMock(ProviderMockSettings(latency_ms=0)),
            control_plane=LocalWorkerControlPlane(),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        timeline: list[str] = []
        delivery = _ObservedDelivery(
            body=b'{"schemaVersion":2,"eventType":"TASK_EXECUTION_REQUESTED"}',
            timeline=timeline,
        )
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(delivery.delivery_id)
        assert snapshot is not None
        await worker.close()
        return (
            delivery.state,
            snapshot.status,
            snapshot.error_code,
            timeline,
            delivery.requeue,
        )

    delivery_state, task_status, error_code, timeline, requeue = asyncio.run(scenario())
    assert delivery_state == "REJECTED"
    assert task_status is WorkerTaskStatus.REJECTED
    assert error_code == "RABBIT_MESSAGE_SCHEMA_INVALID"
    assert timeline == ["reject-dlq"]
    assert requeue is False


def test_image_mock_worker_closes_the_authorized_manual_ack_lifecycle() -> None:
    timeline: list[str] = []
    parameters: dict[str, JsonValue] = {
        "prompt": "白色背景上的简约陶瓷马克杯",
        "aspectRatio": "1:1",
        "count": 1,
    }

    class ObservedControlPlane(LocalWorkerControlPlane):
        def __init__(self, delivery: RawDelivery) -> None:
            super().__init__()
            self.delivery = delivery
            self.consumer_name: str | None = None
            self.traceparents: list[str] = []
            self.stage_codes: list[str] = []
            self.reported_result: TaskProviderResult | None = None

        async def claim(
            self,
            message: TaskExecutionMessage,
            *,
            consumer_name: str,
            worker_id: str,
            bundle_version: str,
        ):
            timeline.append("claim")
            assert self.delivery.state == "PENDING"
            assert message.traceparent == TRACEPARENT
            assert worker_id
            assert bundle_version
            self.consumer_name = consumer_name
            return await super().claim(
                message,
                consumer_name=consumer_name,
                worker_id=worker_id,
                bundle_version=bundle_version,
            )

        async def get_execution_context(
            self,
            task_id: str,
            *,
            execution_no: int,
            execution_token: str,
            traceparent: str,
        ):
            timeline.append("context")
            assert self.delivery.state == "ACKED"
            self.traceparents.append(traceparent)
            return await super().get_execution_context(
                task_id,
                execution_no=execution_no,
                execution_token=execution_token,
                traceparent=traceparent,
            )

        async def heartbeat(
            self,
            task_id: str,
            *,
            execution_no: int,
            execution_token: str,
            stage_code: str,
            traceparent: str,
        ) -> None:
            timeline.append("heartbeat")
            self.traceparents.append(traceparent)
            self.stage_codes.append(stage_code)
            await super().heartbeat(
                task_id,
                execution_no=execution_no,
                execution_token=execution_token,
                stage_code=stage_code,
                traceparent=traceparent,
            )

        async def report_progress(
            self,
            task_id: str,
            *,
            execution_no: int,
            execution_token: str,
            stage_code: str,
            traceparent: str,
        ) -> None:
            timeline.append("progress")
            self.traceparents.append(traceparent)
            self.stage_codes.append(stage_code)
            await super().report_progress(
                task_id,
                execution_no=execution_no,
                execution_token=execution_token,
                stage_code=stage_code,
                traceparent=traceparent,
            )

        async def report_result(
            self,
            task_id: str,
            *,
            execution_no: int,
            execution_token: str,
            result: TaskProviderResult,
            traceparent: str,
        ) -> JavaTaskStatus:
            timeline.append("result")
            self.traceparents.append(traceparent)
            self.reported_result = result
            return await super().report_result(
                task_id,
                execution_no=execution_no,
                execution_token=execution_token,
                result=result,
                traceparent=traceparent,
            )

    class ObservedImageMock(LocalTaskProviderMock):
        def __init__(self) -> None:
            super().__init__(ProviderMockSettings(latency_ms=0))
            self.requests: list[TaskProviderRequest] = []

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            timeline.append("provider")
            self.requests.append(request)
            return await super().execute(request)

    async def scenario() -> None:
        runtime = initialize_runtime(
            AppSettings(
                environment="test",
                worker_consumer_name="zhixiaohe-image-mock-test",
            )
        )
        delivery = _ObservedDelivery(
            body=_message().model_dump_json(by_alias=True).encode(),
            timeline=timeline,
        )
        control = ObservedControlPlane(delivery)
        control.register_context(TASK_ID, TaskType.IMAGE, parameters)
        provider = ObservedImageMock()
        worker = WorkerRuntime(
            runtime,
            provider=provider,
            control_plane=control,
            broker=LocalMessageBroker(),
        )

        assert await worker.start()
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None

        assert timeline == [
            "claim",
            "ack",
            "context",
            "heartbeat",
            "progress",
            "provider",
            "result",
        ]
        assert delivery.audit == ["ack"]
        assert delivery.state == "ACKED"
        assert control.consumer_name == "zhixiaohe-image-mock-test"
        assert control.traceparents == [TRACEPARENT] * 4
        assert control.stage_codes == ["PROVIDER_GENERATING"] * 2
        assert len(provider.requests) == 1
        request = provider.requests[0]
        assert request.task_id == TASK_ID
        assert request.task_type is TaskType.IMAGE
        assert request.execution_no == 1
        assert request.parameters == parameters

        result = control.reported_result
        assert result is not None
        assert result.provider == "LOCAL_TASK_PROVIDER"
        assert result.model == "deterministic-provider-mock-v1"
        assert result.usage.images == 1
        assert result.usage.provider_cost == "0.0000"
        assert len(result.artifacts) == 1
        artifact = result.artifacts[0]
        assert artifact.artifact_type == "IMAGE"
        assert artifact.content_type == "image/png"
        assert artifact.metadata == {"width": 1024, "height": 1024, "mock": True}
        assert artifact.provider_url == (
            f"https://mock-provider.invalid/image/{request.request_id}.png"
        )
        assert snapshot.status is WorkerTaskStatus.RESULT_READY
        assert snapshot.artifact_count == 1
        assert snapshot.artifacts == result.artifacts
        await worker.close()

    asyncio.run(scenario())


def test_video_worker_treats_java_running_result_as_accepted_handoff() -> None:
    provider_finished = False
    parameters: dict[str, JsonValue] = {
        "scene": "陶瓷杯缓慢旋转",
        "durationSeconds": 2,
        "aspectRatio": "16:9",
        "resolution": "480P",
        "audio": False,
        "seed": -1,
        "promptExtend": True,
        "watermark": False,
    }

    class VideoControlPlane(LocalWorkerControlPlane):
        def __init__(self) -> None:
            super().__init__()
            self.heartbeat_calls = 0
            self.heartbeats_after_provider = 0

        async def heartbeat(
            self,
            task_id: str,
            *,
            execution_no: int,
            execution_token: str,
            stage_code: str,
            traceparent: str,
        ) -> None:
            self.heartbeat_calls += 1
            if provider_finished:
                self.heartbeats_after_provider += 1
            await super().heartbeat(
                task_id,
                execution_no=execution_no,
                execution_token=execution_token,
                stage_code=stage_code,
                traceparent=traceparent,
            )

        async def get_execution_context(
            self,
            task_id: str,
            *,
            execution_no: int,
            execution_token: str,
            traceparent: str,
        ) -> ExecutionContext:
            del execution_token, traceparent
            reference_id = "1900012345678901300"
            return ExecutionContext(
                task_id=task_id,
                task_type=TaskType.VIDEO,
                execution_no=execution_no,
                parameters=parameters,
                creative_brief=ProductCreativeBrief.model_validate(
                    {
                        "sourceMode": "CURRENT_ATTACHMENTS",
                        "productId": None,
                        "referenceAssetIds": [reference_id],
                        "productFacts": None,
                        "creativeDirection": {"requestText": "陶瓷杯缓慢旋转"},
                        "prohibitedClaims": [],
                        "provenance": {"conversationId": None, "messageId": None},
                    }
                ),
                reference_assets=(
                    CreativeReferenceAsset(
                        asset_id=reference_id,
                        content_type="image/png",
                        size_bytes=1,
                        sha256="0" * 64,
                        width=1_024,
                        height=1_024,
                        read_url="https://asset.example/first-frame.png?signature=test",
                        expires_at=datetime(2030, 1, 1, tzinfo=UTC),
                    ),
                ),
                max_provider_calls=3,
                max_videos=1,
                max_video_seconds=2,
            )

        async def report_result(
            self,
            task_id: str,
            *,
            execution_no: int,
            execution_token: str,
            result: TaskProviderResult,
            traceparent: str,
        ) -> JavaTaskStatus:
            del task_id, execution_no, execution_token, result, traceparent
            await asyncio.sleep(0.03)
            return "RUNNING"

    class VideoProvider(LocalTaskProviderMock):
        code = "WAN_VIDEO"
        model = "wan3.0-video"
        is_mock = False

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            nonlocal provider_finished
            await asyncio.sleep(0.03)
            provider_finished = True
            return TaskProviderResult(
                provider=self.code,
                model=self.model,
                request_id=request.request_id,
                provider_task_id="wan-task-safe",
                artifacts=(
                    ProviderArtifact(
                        artifact_type="VIDEO",
                        provider_url="https://provider.invalid/video.mp4",
                        content_type="video/mp4",
                        metadata={
                            "width": 832,
                            "height": 480,
                            "durationSeconds": 2,
                            "mock": False,
                        },
                    ),
                ),
                usage=ProviderUsage(video_seconds=2),
                latency_ms=1,
            )

    async def scenario() -> tuple[WorkerTaskStatus, int, int, int, int]:
        runtime = initialize_runtime(AppSettings(environment="test", worker_task_type="VIDEO"))
        control = VideoControlPlane()
        provider = VideoProvider(ProviderMockSettings(latency_ms=0))
        worker = WorkerRuntime(
            runtime,
            provider=provider,
            control_plane=control,
            broker=LocalMessageBroker(),
            heartbeat_interval_seconds=0.001,
        )
        assert await worker.start()
        delivery = RawDelivery(
            body=_message(task_type=TaskType.VIDEO).model_dump_json(by_alias=True).encode()
        )
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        status = worker.status_snapshot()
        await worker.close()
        return (
            snapshot.status,
            status.processed,
            status.failed,
            control.heartbeat_calls,
            control.heartbeats_after_provider,
        )

    task_status, processed, failed, heartbeat_calls, heartbeats_after_provider = asyncio.run(
        scenario()
    )
    assert task_status is WorkerTaskStatus.HANDOFF_ACCEPTED
    assert processed == 1
    assert failed == 0
    assert heartbeat_calls >= 2
    assert heartbeats_after_provider >= 1


def test_video_retry_without_authorized_continuation_never_calls_provider() -> None:
    class RetryControlPlane(LocalWorkerControlPlane):
        async def get_execution_context(
            self,
            task_id: str,
            *,
            execution_no: int,
            execution_token: str,
            traceparent: str,
        ) -> ExecutionContext:
            del execution_token, traceparent
            return ExecutionContext(
                task_id=task_id,
                task_type=TaskType.VIDEO,
                execution_no=execution_no,
                parameters={"scene": "商品展示", "durationSeconds": 2},
                max_provider_calls=3,
                max_videos=1,
                max_video_seconds=2,
                resume_only=False,
            )

    class UnexpectedVideoProvider(LocalTaskProviderMock):
        code = "WAN_VIDEO"
        model = "wan3.0-video"
        is_mock = False

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            raise AssertionError(f"retry without continuation submitted {request.task_id}")

    async def scenario() -> tuple[WorkerTaskStatus, str | None, list[str]]:
        runtime = initialize_runtime(AppSettings(environment="test", worker_task_type="VIDEO"))
        control = RetryControlPlane()
        control.register_context(TASK_ID, TaskType.VIDEO, {})
        worker = WorkerRuntime(
            runtime,
            provider=UnexpectedVideoProvider(ProviderMockSettings(latency_ms=0)),
            control_plane=control,
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        retry_message = _message(task_type=TaskType.VIDEO).model_copy(update={"attempt": 2})
        delivery = RawDelivery(body=retry_message.model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        await worker.close()
        return snapshot.status, snapshot.error_code, control.audit

    status, error_code, audit = asyncio.run(scenario())
    assert status is WorkerTaskStatus.FAILED
    assert error_code == "VIDEO_RETRY_CONTINUATION_REQUIRED"
    assert f"failure:{TASK_ID}" in audit


def test_claim_unavailable_nacks_for_requeue_without_calling_provider() -> None:
    timeline: list[str] = []

    class UnavailableControlPlane(LocalWorkerControlPlane):
        async def claim(
            self,
            message: TaskExecutionMessage,
            *,
            consumer_name: str,
            worker_id: str,
            bundle_version: str,
        ):
            del message, consumer_name, worker_id, bundle_version
            timeline.append("claim")
            raise ControlPlaneUnavailable("synthetic unavailable control plane")

    class UnexpectedProvider(LocalTaskProviderMock):
        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            raise AssertionError(f"Provider must not run before Claim: {request.task_id}")

    async def scenario() -> None:
        runtime = initialize_runtime(AppSettings(environment="test"))
        worker = WorkerRuntime(
            runtime,
            provider=UnexpectedProvider(ProviderMockSettings(latency_ms=0)),
            control_plane=UnavailableControlPlane(),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        delivery = _ObservedDelivery(
            body=_message().model_dump_json(by_alias=True).encode(),
            timeline=timeline,
        )
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None

        assert timeline == ["claim", "nack-requeue"]
        assert delivery.audit == ["nack-requeue"]
        assert delivery.state == "NACKED"
        assert delivery.requeue is True
        assert snapshot.status is WorkerTaskStatus.FAILED
        assert snapshot.delivery_state == "NACKED"
        assert snapshot.error_code == "JAVA_CONTROL_PLANE_UNAVAILABLE"
        assert worker.status_snapshot().status == "NOT_READY"
        assert worker.status_snapshot().accepting is False
        await worker.close()

    asyncio.run(scenario())


def test_worker_recovers_control_plane_and_restarts_consumption_after_backoff() -> None:
    class RecoveringControlPlane(LocalWorkerControlPlane):
        def __init__(self) -> None:
            super().__init__()
            self.probe_calls = 0

        async def probe(self) -> bool:
            self.probe_calls += 1
            return self.probe_calls != 2

        async def claim(
            self,
            message: TaskExecutionMessage,
            *,
            consumer_name: str,
            worker_id: str,
            bundle_version: str,
        ):
            del message, consumer_name, worker_id, bundle_version
            raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_CONNECTION_FAILED")

    async def scenario() -> tuple[str, bool, int]:
        runtime = initialize_runtime(AppSettings(environment="test"))
        control = RecoveringControlPlane()
        worker = WorkerRuntime(
            runtime,
            provider=LocalTaskProviderMock(ProviderMockSettings(latency_ms=0)),
            control_plane=control,
            broker=LocalMessageBroker(),
            dependency_recovery_base_seconds=0.001,
            dependency_recovery_max_seconds=0.002,
        )
        assert await worker.start()
        delivery = RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        assert worker.status_snapshot().status == "NOT_READY"

        for _ in range(100):
            if worker.is_ready:
                break
            await asyncio.sleep(0.001)

        snapshot = worker.status_snapshot()
        result = snapshot.status, snapshot.accepting, control.probe_calls
        await worker.close()
        return result

    status, accepting, probe_calls = asyncio.run(scenario())
    assert status == "READY"
    assert accepting is True
    assert probe_calls == 3


def test_concurrent_dependency_failure_cannot_strand_a_completed_recovery() -> None:
    class UnavailableClaimControlPlane(LocalWorkerControlPlane):
        async def claim(
            self,
            message: TaskExecutionMessage,
            *,
            consumer_name: str,
            worker_id: str,
            bundle_version: str,
        ):
            del message, consumer_name, worker_id, bundle_version
            raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_CONNECTION_FAILED")

    class CoordinatedBroker(LocalMessageBroker):
        def __init__(self) -> None:
            super().__init__()
            self.restart_calls = 0
            self.first_restart_started = asyncio.Event()
            self.release_first_restart = asyncio.Event()

        async def restart(self) -> None:
            self.restart_calls += 1
            if self.restart_calls == 1:
                self.first_restart_started.set()
                await self.release_first_restart.wait()
            await super().restart()

    async def scenario() -> tuple[str, bool, int]:
        runtime = initialize_runtime(AppSettings(environment="test"))
        broker = CoordinatedBroker()
        worker = WorkerRuntime(
            runtime,
            provider=LocalTaskProviderMock(ProviderMockSettings(latency_ms=0)),
            control_plane=UnavailableClaimControlPlane(),
            broker=broker,
            dependency_recovery_base_seconds=0.001,
            dependency_recovery_max_seconds=0.001,
        )
        assert await worker.start()
        first = RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        await worker.process_delivery(first)
        await asyncio.wait_for(broker.first_restart_started.wait(), timeout=0.1)

        second = RawDelivery(
            body=_message(event_id="1900012345678906001").model_dump_json(by_alias=True).encode()
        )
        second_failure = asyncio.create_task(worker.process_delivery(second))
        await asyncio.sleep(0)
        broker.release_first_restart.set()
        await second_failure

        for _ in range(100):
            if worker.is_ready and broker.restart_calls == 2:
                break
            await asyncio.sleep(0.001)
        snapshot = worker.status_snapshot()
        result = snapshot.status, snapshot.accepting, broker.restart_calls
        await worker.close()
        return result

    status, accepting, restart_calls = asyncio.run(scenario())
    assert status == "READY"
    assert accepting is True
    assert restart_calls == 2


@pytest.mark.parametrize("task_type", (TaskType.IMAGE, TaskType.VIDEO))
def test_media_worker_cancels_provider_when_java_lease_is_lost(task_type: TaskType) -> None:
    class LostLeaseControlPlane(LocalWorkerControlPlane):
        def __init__(self) -> None:
            super().__init__()
            self.heartbeat_calls = 0

        async def heartbeat(self, task_id: str, **kwargs: object) -> None:
            del task_id, kwargs
            self.heartbeat_calls += 1
            if self.heartbeat_calls > 1:
                raise ControlPlaneUnavailable("JAVA_EXECUTION_LEASE_LOST")

    class BlockingProvider(LocalTaskProviderMock):
        def __init__(self) -> None:
            super().__init__(ProviderMockSettings(latency_ms=0))
            self.cancelled = False

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            del request
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                self.cancelled = True
                raise
            raise AssertionError("unreachable")

    async def scenario() -> tuple[bool, WorkerTaskStatus, str | None, str, bool]:
        runtime = initialize_runtime(
            AppSettings(environment="test", worker_task_type="IMAGE_VIDEO")
        )
        control = LostLeaseControlPlane()
        control.register_context(TASK_ID, task_type, {})
        provider = BlockingProvider()
        worker = WorkerRuntime(
            runtime,
            provider=provider,
            control_plane=control,
            broker=LocalMessageBroker(),
            heartbeat_interval_seconds=0.001,
        )
        assert await worker.start()
        message = _message(task_type=task_type)
        delivery = RawDelivery(body=message.model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        result = (
            provider.cancelled,
            snapshot.status,
            snapshot.error_code,
            delivery.state,
            worker.status_snapshot().accepting,
        )
        await worker.close()
        return result

    cancelled, status, error_code, delivery_state, accepting = asyncio.run(scenario())
    assert cancelled is True
    assert status is WorkerTaskStatus.FAILED
    assert error_code == "JAVA_EXECUTION_LEASE_LOST"
    assert delivery_state == "ACKED"
    assert accepting is False


def test_reference_url_must_cover_new_submission_but_not_get_only_resume() -> None:
    runtime = initialize_runtime(AppSettings(environment="test"))
    worker = WorkerRuntime(
        runtime,
        provider=LocalTaskProviderMock(),
        control_plane=LocalWorkerControlPlane(),
        broker=LocalMessageBroker(),
    )
    reference_id = "1900012345678901300"
    brief = ProductCreativeBrief.model_validate(
        {
            "sourceMode": "CURRENT_ATTACHMENTS",
            "productId": None,
            "referenceAssetIds": [reference_id],
            "productFacts": None,
            "creativeDirection": {"requestText": "陶瓷杯缓慢旋转"},
            "prohibitedClaims": [],
            "provenance": {"conversationId": None, "messageId": None},
        }
    )
    reference = CreativeReferenceAsset(
        asset_id=reference_id,
        content_type="image/png",
        size_bytes=1,
        sha256="0" * 64,
        width=1_024,
        height=1_024,
        read_url="https://asset.example/first-frame.png?signature=test",
        expires_at=datetime.now(UTC),
    )
    context = ExecutionContext(
        task_id=TASK_ID,
        task_type=TaskType.VIDEO,
        execution_no=1,
        parameters={},
        creative_brief=brief,
        reference_assets=(reference,),
    )

    with pytest.raises(TaskProviderError, match="REFERENCE_ASSET_NOT_READY"):
        worker._enforce_creative_mode(  # pyright: ignore[reportPrivateUsage]
            TaskType.VIDEO, context
        )

    worker._enforce_creative_mode(  # pyright: ignore[reportPrivateUsage]
        TaskType.VIDEO, replace(context, resume_only=True)
    )


@pytest.mark.parametrize(
    "code",
    (
        "JAVA_CONTROL_PLANE_NOT_FOUND",
        "JAVA_CONTROL_PLANE_CONFLICT",
        "JAVA_CONTROL_PLANE_REQUEST_REJECTED",
    ),
)
def test_permanent_claim_rejection_goes_to_dlq_without_calling_provider(code: str) -> None:
    class RejectedControlPlane(LocalWorkerControlPlane):
        async def claim(
            self,
            message: TaskExecutionMessage,
            *,
            consumer_name: str,
            worker_id: str,
            bundle_version: str,
        ):
            del message, consumer_name, worker_id, bundle_version
            raise ControlPlaneRejected(code)

    class UnexpectedProvider(LocalTaskProviderMock):
        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            raise AssertionError(f"Provider must not run after rejected Claim: {request.task_id}")

    async def scenario() -> None:
        runtime = initialize_runtime(AppSettings(environment="test"))
        worker = WorkerRuntime(
            runtime,
            provider=UnexpectedProvider(ProviderMockSettings(latency_ms=0)),
            control_plane=RejectedControlPlane(),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        delivery = RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        assert delivery.state == "REJECTED"
        assert delivery.requeue is False
        assert snapshot.status is WorkerTaskStatus.REJECTED
        assert snapshot.error_code == code
        assert worker.status_snapshot().status == "READY"
        await worker.close()

    asyncio.run(scenario())


def test_unclassified_claim_failure_requeues_once_then_rejects_without_stopping() -> None:
    class BrokenControlPlane(LocalWorkerControlPlane):
        async def claim(
            self,
            message: TaskExecutionMessage,
            *,
            consumer_name: str,
            worker_id: str,
            bundle_version: str,
        ):
            del message, consumer_name, worker_id, bundle_version
            raise ValueError("synthetic upstream body must stay hidden")

    class ObservedProvider(LocalTaskProviderMock):
        def __init__(self) -> None:
            super().__init__(ProviderMockSettings(latency_ms=0))
            self.calls = 0

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            self.calls += 1
            return await super().execute(request)

    async def settled(delivery: RawDelivery) -> None:
        for _ in range(100):
            if delivery.state != "PENDING":
                return
            await asyncio.sleep(0)
        raise AssertionError("Local delivery was not settled")

    async def scenario() -> tuple[
        str,
        bool,
        str,
        bool,
        WorkerStatusSnapshot,
        WorkerTaskStatus,
        str | None,
        int,
    ]:
        runtime = initialize_runtime(AppSettings(environment="test"))
        broker = LocalMessageBroker()
        provider = ObservedProvider()
        worker = WorkerRuntime(
            runtime,
            provider=provider,
            control_plane=BrokenControlPlane(),
            broker=broker,
        )
        assert await worker.start()
        body = _message().model_dump_json(by_alias=True).encode()
        first = await broker.publish(body)
        await settled(first)
        second = await broker.publish(body, redelivered=True)
        await settled(second)
        status = worker.status_snapshot()
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        await worker.close()
        return (
            first.state,
            first.requeue,
            second.state,
            second.requeue,
            status,
            snapshot.status,
            snapshot.error_code,
            provider.calls,
        )

    (
        first_state,
        first_requeue,
        second_state,
        second_requeue,
        status,
        task_status,
        error_code,
        provider_calls,
    ) = asyncio.run(scenario())
    assert first_state == "NACKED"
    assert first_requeue is True
    assert second_state == "REJECTED"
    assert second_requeue is False
    assert status.status == "READY"
    assert status.accepting is True
    assert status.reason_code is None
    assert status.failed == 2
    assert task_status is WorkerTaskStatus.REJECTED
    assert error_code == "WORKER_DELIVERY_POISONED"
    assert provider_calls == 0


@pytest.mark.parametrize(
    ("current_generation", "expected_status", "expected_accepting"),
    ((1, "NOT_READY", False), (2, "READY", True)),
)
def test_settlement_failure_only_invalidates_its_own_broker_generation(
    current_generation: int, expected_status: str, expected_accepting: bool
) -> None:
    class BrokenControlPlane(LocalWorkerControlPlane):
        async def claim(
            self,
            message: TaskExecutionMessage,
            *,
            consumer_name: str,
            worker_id: str,
            bundle_version: str,
        ):
            del message, consumer_name, worker_id, bundle_version
            raise ValueError("synthetic unclassified claim failure")

    class FailingNackDelivery(RawDelivery):
        async def nack(self, *, requeue: bool) -> None:
            del requeue
            raise MessageBrokerUnavailable("synthetic NACK failure", generation=1)

    class GenerationBroker(LocalMessageBroker):
        def __init__(self) -> None:
            super().__init__()
            self.invalidations: list[int | None] = []
            self.invalidated = asyncio.Event()

        async def publish(self, body: bytes, *, redelivered: bool = False) -> RawDelivery:
            if not self.accepting:
                raise RuntimeError("Worker broker is draining")
            delivery = FailingNackDelivery(body=body, redelivered=redelivered)
            await self._queue.put(delivery)
            return delivery

        async def invalidate(self, generation: int | None) -> bool:
            self.invalidations.append(generation)
            self.invalidated.set()
            return generation == current_generation

    async def scenario() -> tuple[WorkerStatusSnapshot, list[int | None], str]:
        runtime = initialize_runtime(AppSettings(environment="test"))
        broker = GenerationBroker()
        worker = WorkerRuntime(
            runtime,
            provider=LocalTaskProviderMock(ProviderMockSettings(latency_ms=0)),
            control_plane=BrokenControlPlane(),
            broker=broker,
            dependency_recovery_base_seconds=10,
            dependency_recovery_max_seconds=10,
        )
        assert await worker.start()
        delivery = await broker.publish(_message().model_dump_json(by_alias=True).encode())
        await asyncio.wait_for(broker.invalidated.wait(), timeout=1)
        status = worker.status_snapshot()
        await worker.close()
        return status, broker.invalidations, delivery.state

    status, invalidations, delivery_state = asyncio.run(scenario())
    assert invalidations == [1]
    assert delivery_state == "PENDING"
    assert status.status == expected_status
    assert status.accepting is expected_accepting


def test_authorized_execution_context_mismatch_fails_after_ack_without_provider_call() -> None:
    class MismatchedControlPlane(LocalWorkerControlPlane):
        async def get_execution_context(
            self,
            task_id: str,
            *,
            execution_no: int,
            execution_token: str,
            traceparent: str,
        ) -> ExecutionContext:
            del execution_token, traceparent
            return ExecutionContext(
                task_id=f"{task_id}9",
                task_type=TaskType.IMAGE,
                execution_no=execution_no,
                parameters={},
            )

    class UnexpectedProvider(LocalTaskProviderMock):
        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            raise AssertionError(f"Provider received mismatched context: {request.task_id}")

    async def scenario() -> tuple[str, WorkerTaskStatus, str | None, list[str]]:
        runtime = initialize_runtime(AppSettings(environment="test"))
        control = MismatchedControlPlane()
        worker = WorkerRuntime(
            runtime,
            provider=UnexpectedProvider(ProviderMockSettings(latency_ms=0)),
            control_plane=control,
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        delivery = RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        await worker.close()
        return delivery.state, snapshot.status, snapshot.error_code, control.audit

    delivery_state, task_status, error_code, audit = asyncio.run(scenario())
    assert delivery_state == "ACKED"
    assert task_status is WorkerTaskStatus.FAILED
    assert error_code == "EXECUTION_CONTEXT_TASK_ID_MISMATCH"
    assert audit == [f"claim:{EVENT_ID}", f"failure:{TASK_ID}"]


@pytest.mark.parametrize(
    ("failure_type", "error_code", "expect_report", "expect_ready"),
    (
        (ControlPlaneUnavailable, "JAVA_CONTROL_PLANE_RESPONSE_INVALID", True, True),
        (ControlPlaneUnavailable, "JAVA_CONTROL_PLANE_CONNECTION_FAILED", False, False),
        (ControlPlaneRejected, "JAVA_CONTROL_PLANE_CONFLICT", False, True),
    ),
)
def test_post_claim_context_failure_logs_safe_identity_and_phase(
    caplog: pytest.LogCaptureFixture,
    failure_type: type[ControlPlaneUnavailable] | type[ControlPlaneRejected],
    error_code: str,
    expect_report: bool,
    expect_ready: bool,
) -> None:
    class FailingContextControlPlane(LocalWorkerControlPlane):
        async def get_execution_context(
            self,
            task_id: str,
            *,
            execution_no: int,
            execution_token: str,
            traceparent: str,
        ) -> ExecutionContext:
            del task_id, execution_no, execution_token, traceparent
            raise failure_type(error_code)

    async def scenario() -> tuple[str, WorkerTaskStatus, str | None, bool, list[str]]:
        runtime = initialize_runtime(AppSettings(environment="test"))
        control = FailingContextControlPlane()
        worker = WorkerRuntime(
            runtime,
            provider=LocalTaskProviderMock(ProviderMockSettings(latency_ms=0)),
            control_plane=control,
            broker=LocalMessageBroker(),
            dependency_recovery_base_seconds=10,
            dependency_recovery_max_seconds=10,
        )
        assert await worker.start()
        delivery = RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        result = (
            delivery.state,
            snapshot.status,
            snapshot.error_code,
            worker.is_ready,
            control.audit,
        )
        await worker.close()
        return result

    with caplog.at_level(logging.ERROR, logger="app.workers.runtime"):
        delivery_state, status, actual_error_code, ready, audit = asyncio.run(scenario())

    assert delivery_state == "ACKED"
    assert status is WorkerTaskStatus.FAILED
    assert actual_error_code == error_code
    assert ready is expect_ready
    assert (f"failure:{TASK_ID}" in audit) is expect_report
    records = [record for record in caplog.records if record.msg == "worker_control_plane_failed"]
    assert len(records) == 1
    logged = json.loads(JsonFormatter().format(records[0]))
    assert logged["taskId"] == TASK_ID
    assert logged["traceId"] == "4bf92f3577b34da6a3ce929d0e0e4736"
    assert logged["attempt"] == 1
    assert logged["failureStage"] == "CONTEXT_LOAD"
    assert logged["stableErrorCode"] == error_code
    assert "local-only-" not in json.dumps(logged)


def test_worker_fails_readiness_when_rabbit_start_is_unavailable() -> None:
    class UnavailableBroker(LocalMessageBroker):
        async def start(self) -> None:
            raise MessageBrokerUnavailable("synthetic broker outage")

    async def scenario() -> tuple[bool, str | None]:
        runtime = initialize_runtime(AppSettings(environment="test"))
        worker = WorkerRuntime(
            runtime,
            provider=LocalTaskProviderMock(ProviderMockSettings(latency_ms=0)),
            control_plane=LocalWorkerControlPlane(),
            broker=UnavailableBroker(),
        )
        started = await worker.start()
        reason_code = worker.status_snapshot().reason_code
        await worker.close()
        return started, reason_code

    started, reason_code = asyncio.run(scenario())
    assert started is False
    assert reason_code == "RABBITMQ_UNAVAILABLE"


def test_worker_recovers_when_broker_was_unavailable_before_first_consumer_start() -> None:
    class RecoveringStartupBroker(LocalMessageBroker):
        def __init__(self) -> None:
            super().__init__()
            self.start_calls = 0

        async def start(self) -> None:
            self.start_calls += 1
            if self.start_calls == 1:
                raise MessageBrokerUnavailable("synthetic broker outage")

    async def scenario() -> tuple[str, bool, int]:
        runtime = initialize_runtime(AppSettings(environment="test"))
        broker = RecoveringStartupBroker()
        worker = WorkerRuntime(
            runtime,
            provider=LocalTaskProviderMock(ProviderMockSettings(latency_ms=0)),
            control_plane=LocalWorkerControlPlane(),
            broker=broker,
            dependency_recovery_base_seconds=0.001,
            dependency_recovery_max_seconds=0.001,
        )
        assert await worker.start() is False
        for _ in range(100):
            if worker.is_ready:
                break
            await asyncio.sleep(0.001)
        snapshot = worker.status_snapshot()
        result = snapshot.status, snapshot.accepting, broker.start_calls
        await worker.close()
        return result

    status, accepting, start_calls = asyncio.run(scenario())
    assert status == "READY"
    assert accepting is True
    assert start_calls == 2


def test_worker_start_is_idempotent_and_creates_one_consumer() -> None:
    class CountingBroker(LocalMessageBroker):
        def __init__(self) -> None:
            super().__init__()
            self.start_calls = 0

        async def start(self) -> None:
            self.start_calls += 1

    async def scenario() -> tuple[bool, bool, int]:
        runtime = initialize_runtime(AppSettings(environment="test"))
        broker = CountingBroker()
        worker = WorkerRuntime(
            runtime,
            provider=LocalTaskProviderMock(ProviderMockSettings(latency_ms=0)),
            control_plane=LocalWorkerControlPlane(),
            broker=broker,
        )
        first = await worker.start()
        second = await worker.start()
        await worker.close()
        return first, second, broker.start_calls

    first, second, start_calls = asyncio.run(scenario())
    assert first is True
    assert second is True
    assert start_calls == 1


def test_claim_happens_before_ack_and_duplicate_does_not_call_provider_twice() -> None:
    class ObservedControlPlane(LocalWorkerControlPlane):
        def __init__(self, deliveries: list[RawDelivery]) -> None:
            super().__init__()
            self.deliveries = deliveries

        async def claim(
            self,
            message: TaskExecutionMessage,
            *,
            consumer_name: str,
            worker_id: str,
            bundle_version: str,
        ):
            assert self.deliveries[-1].state == "PENDING"
            return await super().claim(
                message,
                consumer_name=consumer_name,
                worker_id=worker_id,
                bundle_version=bundle_version,
            )

    class CountingProvider(LocalTaskProviderMock):
        def __init__(self) -> None:
            super().__init__(ProviderMockSettings(latency_ms=0))
            self.calls = 0

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            self.calls += 1
            return await super().execute(request)

    async def scenario() -> tuple[int, str, str, list[str]]:
        runtime = initialize_runtime(AppSettings(environment="test"))
        deliveries: list[RawDelivery] = []
        control = ObservedControlPlane(deliveries)
        control.register_context(TASK_ID, TaskType.IMAGE, dict[str, JsonValue]())
        provider = CountingProvider()
        worker = WorkerRuntime(
            runtime,
            provider=provider,
            control_plane=control,
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        body = _message().model_dump_json(by_alias=True).encode()
        first = RawDelivery(body=body)
        deliveries.append(first)
        await worker.process_delivery(first)
        second = RawDelivery(body=body)
        deliveries.append(second)
        await worker.process_delivery(second)
        await worker.close()
        return provider.calls, first.state, second.state, control.audit

    calls, first_state, second_state, audit = asyncio.run(scenario())
    assert calls == 1
    assert first_state == "ACKED"
    assert second_state == "ACKED"
    assert audit[0] == f"claim:{EVENT_ID}"
    assert audit.count(f"claim:{EVENT_ID}") == 2


def test_unknown_provider_submit_is_not_retried_or_reported_as_success() -> None:
    settings = AppSettings(
        environment="test",
        provider_mock=ProviderMockSettings(latency_ms=0, failure_mode="UNKNOWN_SUBMIT"),
    )
    with TestClient(create_worker_app(settings)) as client:
        accepted = client.post(
            "/dev/v1/tasks",
            json={"taskType": "IMAGE", "parameters": {}},
        )
        terminal = _await_terminal(client, accepted.json()["statusUrl"])

    assert terminal["status"] == "SUBMISSION_UNKNOWN"
    assert terminal["deliveryState"] == "ACKED"
    assert terminal["errorCode"] == "PROVIDER_SUBMISSION_UNKNOWN"


def _text_only_creative_context(
    *,
    execution_no: int,
    continuation: QwenImageProviderContinuation | None = None,
) -> ExecutionContext:
    return ExecutionContext(
        task_id=TASK_ID,
        task_type=TaskType.IMAGE,
        execution_no=execution_no,
        parameters={"scene": "雨后城市街道", "count": 1},
        creative_brief=ProductCreativeBrief.model_validate(
            {
                "sourceMode": "TEXT_ONLY",
                "productId": None,
                "referenceAssetIds": [],
                "productFacts": None,
                "creativeDirection": {"requestText": "生成雨后城市街道图片"},
                "prohibitedClaims": [],
                "provenance": {"conversationId": None, "messageId": None},
            }
        ),
        provider="QWEN_IMAGE",
        model="qwen-image-3.0",
        max_provider_calls=1,
        max_images=1,
        resume_only=continuation is not None,
        provider_continuation=continuation,
    )


def test_unexpected_initial_provider_failure_without_receipt_fails_closed() -> None:
    class RecordingControlPlane(LocalWorkerControlPlane):
        failure: dict[str, object] | None = None

        async def get_execution_context(self, task_id: str, **kwargs: object) -> ExecutionContext:
            assert task_id == TASK_ID
            return _text_only_creative_context(
                execution_no=cast(int, kwargs["execution_no"]),
            )

        async def report_failure(self, task_id: str, **kwargs: object) -> None:
            assert task_id == TASK_ID
            self.failure = dict(kwargs)

    class ProviderWithoutReceipt(LocalTaskProviderMock):
        code = "QWEN_IMAGE"
        model = "qwen-image-3.0"
        is_mock = False

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            del request
            raise RuntimeError("unexpected adapter failure without a receipt")

    async def scenario() -> tuple[WorkerTaskSnapshot, dict[str, object]]:
        runtime = initialize_runtime(AppSettings(environment="test", worker_task_type="IMAGE"))
        control = RecordingControlPlane()
        worker = WorkerRuntime(
            runtime,
            provider=ProviderWithoutReceipt(ProviderMockSettings(latency_ms=0)),
            control_plane=control,
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        await worker.process_delivery(
            RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        )
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        assert control.failure is not None
        await worker.close()
        return snapshot, control.failure

    snapshot, failure = asyncio.run(scenario())
    assert snapshot.status is WorkerTaskStatus.SUBMISSION_UNKNOWN
    assert snapshot.error_code == "PROVIDER_SUBMISSION_EVIDENCE_UNAVAILABLE"
    assert failure["submission_state"] is SubmissionState.UNKNOWN
    assert failure["provider"] == "QWEN_IMAGE"
    assert failure["model"] == "qwen-image-3.0"
    assert failure["provider_request_id"] is None
    assert failure["provider_task_id"] is None


def test_unexpected_resume_failure_preserves_authorized_receipt_and_never_posts() -> None:
    continuation = QwenImageProviderContinuation(
        provider="QWEN_IMAGE",
        model="qwen-image-3.0",
        provider_request_id=UUID("123e4567-e89b-42d3-a456-426614174111"),
        provider_task_id="qwen-task-existing",
    )

    class RecordingControlPlane(LocalWorkerControlPlane):
        failure: dict[str, object] | None = None

        async def get_execution_context(self, task_id: str, **kwargs: object) -> ExecutionContext:
            assert task_id == TASK_ID
            return _text_only_creative_context(
                execution_no=cast(int, kwargs["execution_no"]),
                continuation=continuation,
            )

        async def report_failure(self, task_id: str, **kwargs: object) -> None:
            assert task_id == TASK_ID
            self.failure = dict(kwargs)

    class GetOnlyProvider(LocalTaskProviderMock):
        code = "QWEN_IMAGE"
        model = "qwen-image-3.0"
        is_mock = False
        execute_calls = 0
        resume_calls = 0

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            del request
            self.execute_calls += 1
            raise AssertionError("recovery must never submit a new task")

        async def resume(
            self,
            request: TaskProviderRequest,
            authorized: QwenImageProviderContinuation,
        ) -> TaskProviderResult:
            assert request.request_id == continuation.provider_request_id
            assert authorized == continuation
            self.resume_calls += 1
            raise RuntimeError("unexpected GET recovery failure")

    async def scenario() -> tuple[WorkerTaskSnapshot, dict[str, object], int, int]:
        runtime = initialize_runtime(AppSettings(environment="test", worker_task_type="IMAGE"))
        control = RecordingControlPlane()
        provider = GetOnlyProvider(ProviderMockSettings(latency_ms=0))
        worker = WorkerRuntime(
            runtime,
            provider=provider,
            control_plane=control,
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        retry = _message().model_copy(update={"attempt": 2})
        await worker.process_delivery(
            RawDelivery(body=retry.model_dump_json(by_alias=True).encode())
        )
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        assert control.failure is not None
        await worker.close()
        return snapshot, control.failure, provider.execute_calls, provider.resume_calls

    snapshot, failure, execute_calls, resume_calls = asyncio.run(scenario())
    assert execute_calls == 0
    assert resume_calls == 1
    assert snapshot.status is WorkerTaskStatus.SUBMISSION_UNKNOWN
    assert snapshot.provider_task_id == continuation.provider_task_id
    assert failure["submission_state"] is SubmissionState.UNKNOWN
    assert failure["provider_request_id"] == str(continuation.provider_request_id)
    assert failure["provider_task_id"] == continuation.provider_task_id


def test_unexpected_pre_provider_failure_is_reported_not_submitted() -> None:
    class RecordingControlPlane(LocalWorkerControlPlane):
        failure: dict[str, object] | None = None

        async def get_execution_context(self, task_id: str, **kwargs: object) -> ExecutionContext:
            assert task_id == TASK_ID
            return _text_only_creative_context(
                execution_no=cast(int, kwargs["execution_no"]),
            )

        async def report_progress(self, task_id: str, **kwargs: object) -> None:
            del task_id, kwargs
            raise RuntimeError("unexpected progress failure")

        async def report_failure(self, task_id: str, **kwargs: object) -> None:
            assert task_id == TASK_ID
            self.failure = dict(kwargs)

    class ProviderMustNotRun(LocalTaskProviderMock):
        code = "QWEN_IMAGE"
        model = "qwen-image-3.0"
        is_mock = False

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            raise AssertionError(f"provider ran before progress setup: {request.task_id}")

    async def scenario() -> tuple[WorkerTaskSnapshot, dict[str, object]]:
        runtime = initialize_runtime(AppSettings(environment="test", worker_task_type="IMAGE"))
        control = RecordingControlPlane()
        worker = WorkerRuntime(
            runtime,
            provider=ProviderMustNotRun(ProviderMockSettings(latency_ms=0)),
            control_plane=control,
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        await worker.process_delivery(
            RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        )
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        assert control.failure is not None
        await worker.close()
        return snapshot, control.failure

    snapshot, failure = asyncio.run(scenario())
    assert snapshot.status is WorkerTaskStatus.FAILED
    assert failure["submission_state"] is SubmissionState.NOT_SUBMITTED
    assert failure["provider"] is None
    assert failure["provider_request_id"] is None
    assert failure["provider_task_id"] is None


def test_unexpected_result_handoff_failure_preserves_provider_receipt() -> None:
    provider_request_id = UUID("123e4567-e89b-42d3-a456-426614174112")
    provider_task_id = "qwen-task-finished"

    class RecordingControlPlane(LocalWorkerControlPlane):
        failure: dict[str, object] | None = None

        async def get_execution_context(self, task_id: str, **kwargs: object) -> ExecutionContext:
            assert task_id == TASK_ID
            return _text_only_creative_context(
                execution_no=cast(int, kwargs["execution_no"]),
            )

        async def report_result(self, task_id: str, **kwargs: object) -> JavaTaskStatus:
            del task_id, kwargs
            raise RuntimeError("unexpected result handoff failure")

        async def report_failure(self, task_id: str, **kwargs: object) -> None:
            assert task_id == TASK_ID
            self.failure = dict(kwargs)

    class CompletedProvider(LocalTaskProviderMock):
        code = "QWEN_IMAGE"
        model = "qwen-image-3.0"
        is_mock = False

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            return TaskProviderResult(
                provider=self.code,
                model=self.model,
                request_id=provider_request_id,
                provider_task_id=provider_task_id,
                artifacts=(
                    ProviderArtifact(
                        artifact_type="IMAGE",
                        provider_url="https://provider.invalid/image.png",
                        metadata={"width": 1024, "height": 1024, "mock": False},
                    ),
                ),
                usage=ProviderUsage(images=1),
                latency_ms=1,
            )

    async def scenario() -> tuple[WorkerTaskSnapshot, dict[str, object]]:
        runtime = initialize_runtime(AppSettings(environment="test", worker_task_type="IMAGE"))
        control = RecordingControlPlane()
        worker = WorkerRuntime(
            runtime,
            provider=CompletedProvider(ProviderMockSettings(latency_ms=0)),
            control_plane=control,
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        await worker.process_delivery(
            RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        )
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        assert control.failure is not None
        await worker.close()
        return snapshot, control.failure

    snapshot, failure = asyncio.run(scenario())
    assert snapshot.status is WorkerTaskStatus.SUBMISSION_UNKNOWN
    assert snapshot.provider_task_id == provider_task_id
    assert failure["submission_state"] is SubmissionState.UNKNOWN
    assert failure["provider_request_id"] == str(provider_request_id)
    assert failure["provider_task_id"] == provider_task_id


def test_worker_retries_only_known_not_submitted_failures_within_budget() -> None:
    class FlakyProvider(LocalTaskProviderMock):
        def __init__(self) -> None:
            super().__init__(ProviderMockSettings(latency_ms=0))
            self.calls = 0

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            self.calls += 1
            if self.calls < 3:
                raise TaskProviderError(
                    "PROVIDER_UPSTREAM_ERROR",
                    category=ProviderErrorCategory.UPSTREAM,
                    retryable=True,
                    submission_state=SubmissionState.NOT_SUBMITTED,
                )
            return await super().execute(request)

    async def scenario() -> tuple[int, WorkerTaskStatus]:
        runtime = initialize_runtime(
            AppSettings(
                environment="test",
                worker_provider_retry_base_seconds=0,
                worker_provider_retry_max_seconds=0,
                worker_provider_retry_budget_seconds=1,
            )
        )
        control = LocalWorkerControlPlane()
        control.register_context(TASK_ID, TaskType.IMAGE, dict[str, JsonValue]())
        provider = FlakyProvider()
        worker = WorkerRuntime(
            runtime,
            provider=provider,
            control_plane=control,
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        delivery = RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        await worker.close()
        return provider.calls, snapshot.status

    calls, task_status = asyncio.run(scenario())
    assert calls == 3
    assert task_status is WorkerTaskStatus.RESULT_READY


def test_shutdown_drains_an_already_queued_local_task() -> None:
    settings = AppSettings(
        environment="test",
        provider_mock=ProviderMockSettings(latency_ms=20),
    )
    application = create_worker_app(settings)
    with TestClient(application) as client:
        accepted = client.post(
            "/dev/v1/tasks",
            json={"taskType": "IMAGE", "parameters": {}},
        )
        event_id = accepted.json()["eventId"]
        worker: WorkerRuntime = application.state.worker_runtime

    snapshot = worker.get_task(event_id)
    assert snapshot is not None
    assert snapshot.status is WorkerTaskStatus.RESULT_READY
    assert worker.status_snapshot().status == "STOPPED"

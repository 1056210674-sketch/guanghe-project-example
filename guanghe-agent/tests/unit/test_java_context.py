"""Authorized Run context source and production fail-closed tests."""

import asyncio
import json
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from typing import cast
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.agent.models import ChatMessage, ChatRunRequest, RunBudget, TrustedState
from app.api.application import create_app
from app.common.settings import AppSettings
from app.integration.java_internal import JavaInternalResponse, JavaReverseCallResult
from app.model_gateway.gateway import LocalModelGateway
from app.providers.java_context import (
    JavaRunContextProvider,
    LocalRunContextProvider,
    ProtocolFeatures,
    RunContextErrorCode,
    RunContextUnavailableError,
    UnavailableJavaRunContextProvider,
    build_run_context_provider,
)
from tests.internal_security import (
    TEST_KEY_ID,
    FakeInternalNonceStore,
    internal_settings,
    signed_headers,
)

_TRACE_ID = "0123456789abcdef0123456789abcdef"
_JAVA_BASE_URL = "http://127.0.0.1:8080"
_SPACE_ID = "1900012345678901100"
_PRODUCT_ID = "1900012345678901200"
_MESSAGE_ID = "1900012345678901300"


@pytest.mark.parametrize("invalid", ({"businessOutcome": "true"}, {"unknown": True}))
def test_protocol_features_reject_coerced_authorization_and_unknown_flags(
    invalid: dict[str, object],
) -> None:
    with pytest.raises(ValidationError):
        ProtocolFeatures.model_validate(invalid)


def test_authorized_protocol_features_are_not_passed_to_the_model() -> None:
    def payload_factory(run_id: UUID) -> dict[str, object]:
        return {
            **_empty_authorized_payload(run_id),
            "protocolFeatures": {"businessOutcome": True, "resourceLookup": False},
        }

    context = asyncio.run(
        JavaRunContextProvider(_RecordingJavaClient(payload_factory)).load(
            _request().model_copy(update={"trusted_state": TrustedState()})
        )
    )
    assert context.protocol_features is not None
    assert context.protocol_features.business_outcome is True
    assert "protocolFeatures" not in context.model_context_json()
    assert context.current_attachment_only().protocol_features == context.protocol_features


def _request() -> ChatRunRequest:
    return ChatRunRequest(
        run_id=uuid4(),
        conversation_id="123456789012345678",
        user_context_ref="opaque-context-ref-must-survive",
        message=ChatMessage(
            message_id=_MESSAGE_ID,
            content="生成商品主图",
            attachment_asset_ids=(),
        ),
        trusted_state=TrustedState(
            selected_space_id=_SPACE_ID,
            selected_product_id=_PRODUCT_ID,
        ),
        budget=RunBudget(deadline_at=datetime.now(UTC) + timedelta(minutes=5)),
    )


def _empty_authorized_payload(run_id: UUID) -> dict[str, object]:
    return {
        "runId": str(run_id),
        "conversationId": "123456789012345678",
        "contextVersion": 1,
        "space": None,
        "product": None,
        "competitors": [],
        "attachmentAssetIds": [],
        "conversationMemory": {
            "messages": [
                {
                    "messageId": _MESSAGE_ID,
                    "sequence": 1,
                    "role": "USER",
                    "content": {"text": "生成商品主图"},
                    "createdAt": "2026-09-04T08:00:00Z",
                    "replyToMessageId": None,
                }
            ],
            "summary": None,
            "lastMessageSequence": 1,
            "summaryThroughSequence": 0,
            "contextSource": "POSTGRESQL",
        },
        "taskWorkspace": {
            "currentGoal": "生成商品主图",
            "currentIntent": "IMAGE",
            "activeScope": {"spaceId": None, "productId": None, "competitorIds": []},
            "confirmedConstraints": {},
            "unresolvedQuestions": [],
            "pendingInteraction": None,
            "activeDraftId": None,
            "activeTaskId": None,
            "referenceAssetIds": [],
            "runningTasks": [],
            "contextVersion": 1,
        },
        "loadedToolGroups": ["TASK_PREPARATION"],
        "contextBudget": {
            "targetInputTokens": 32_000,
            "reservedOutputTokens": 0,
            "reservedToolTokens": 0,
            "reservedSafetyTokens": 0,
            "availableHistoryTokens": 32_000,
            "estimatedHistoryTokens": 1,
            "truncated": False,
            "selectionStrategy": "TOKEN_BUDGET_REPLY_CHAIN_THEN_RECENCY",
        },
        "taskPreparationConstraints": {
            "geo": {
                "enabled": True,
                "minQuestions": 3,
                "maxQuestions": 5,
                "platformCodes": ["DOUBAO", "QWEN", "WENXIN", "KIMI", "YUANBAO", "ZHIPU"],
                "maxProviderCalls": 30,
            }
        },
    }


class _RecordingJavaClient:
    def __init__(
        self,
        payload_factory: Callable[[UUID], dict[str, object]] = _empty_authorized_payload,
    ) -> None:
        self._payload_factory = payload_factory
        self.context_calls: list[tuple[UUID, str, str | None]] = []
        self.closed = False

    async def get_run_context(
        self,
        run_id: UUID,
        user_context_ref: str,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        self.context_calls.append((run_id, user_context_ref, traceparent))
        return JavaInternalResponse(
            status_code=200,
            payload=self._payload_factory(run_id),
            trace_id=_TRACE_ID,
        )

    async def reverse_call(self, traceparent: str | None = None) -> JavaReverseCallResult:
        del traceparent
        return JavaReverseCallResult(
            accepted=True,
            caller_key_id=TEST_KEY_ID,
            trace_id=_TRACE_ID,
        )

    async def aclose(self) -> None:
        self.closed = True


def _sse_events(raw: str) -> tuple[dict[str, object], ...]:
    return tuple(
        cast(dict[str, object], json.loads(line.removeprefix("data: ")))
        for line in raw.splitlines()
        if line.startswith("data: ")
    )


def test_local_context_keeps_explicit_stub_and_never_calls_java() -> None:
    request = _request()
    java_client = _RecordingJavaClient()

    for environment in ("local", "test"):
        provider = build_run_context_provider(
            AppSettings(environment=environment),
            java_client,
        )
        assert isinstance(provider, LocalRunContextProvider)
        context = asyncio.run(provider.load(request))
        assert context.source == "LOCAL_TRUSTED_STATE"
        assert context.user_context_ref == "opaque-context-ref-must-survive"
        assert context.selected_space_id == _SPACE_ID
        assert context.selected_product_id == _PRODUCT_ID
        assert context.attachment_asset_ids == ()

    assert java_client.context_calls == []


def test_test_environment_can_explicitly_select_java_context() -> None:
    settings = internal_settings(
        environment="test",
        run_context_provider="JAVA",
        java_internal_base_url=_JAVA_BASE_URL,
    )
    java_client = _RecordingJavaClient()

    provider = build_run_context_provider(settings, java_client)

    assert isinstance(provider, JavaRunContextProvider)
    assert provider.is_ready is True
    assert java_client.context_calls == []


def test_local_environment_cannot_explicitly_select_java_context() -> None:
    java_client = _RecordingJavaClient()

    provider = build_run_context_provider(
        internal_settings(
            environment="local",
            run_context_provider="JAVA",
            java_internal_base_url=_JAVA_BASE_URL,
        ),
        java_client,
    )

    assert isinstance(provider, UnavailableJavaRunContextProvider)
    assert provider.is_ready is False
    assert provider.reason_code == RunContextErrorCode.CONFIGURATION_UNAVAILABLE.value
    assert java_client.context_calls == []


def test_explicit_java_context_without_hmac_configuration_fails_closed() -> None:
    java_client = _RecordingJavaClient()

    provider = build_run_context_provider(
        AppSettings(environment="test", run_context_provider="JAVA"),
        java_client,
    )

    assert isinstance(provider, UnavailableJavaRunContextProvider)
    assert provider.is_ready is False
    assert provider.reason_code == RunContextErrorCode.CONFIGURATION_UNAVAILABLE.value
    assert java_client.context_calls == []


def test_production_cannot_explicitly_select_local_context() -> None:
    java_client = _RecordingJavaClient()
    settings = internal_settings(
        environment="production",
        run_context_provider="LOCAL",
        java_internal_base_url=_JAVA_BASE_URL,
    )
    provider = build_run_context_provider(settings, java_client)

    assert isinstance(provider, UnavailableJavaRunContextProvider)
    assert provider.is_ready is False
    assert provider.reason_code == RunContextErrorCode.CONFIGURATION_UNAVAILABLE.value
    assert java_client.context_calls == []

    application = create_app(
        settings,
        gateway_factory=lambda _settings: LocalModelGateway(),
        internal_nonce_store=FakeInternalNonceStore(),
        java_client_factory=lambda _settings: java_client,
    )
    with TestClient(
        application,
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        response = client.get("/internal/v1/health/ready")

    assert response.status_code == 503
    checks = {item["name"]: item for item in response.json()["checks"]}
    assert checks["authorizedContext"] == {
        "name": "authorizedContext",
        "status": "NOT_READY",
        "reasonCode": RunContextErrorCode.CONFIGURATION_UNAVAILABLE.value,
    }
    assert java_client.context_calls == []


@pytest.mark.parametrize(
    "settings",
    (
        internal_settings(environment="production"),
        AppSettings(
            environment="production",
            java_internal_base_url=_JAVA_BASE_URL,
        ),
    ),
)
def test_production_missing_java_or_hmac_configuration_fails_closed(
    settings: AppSettings,
) -> None:
    java_client = _RecordingJavaClient()
    provider = build_run_context_provider(settings, java_client)

    assert isinstance(provider, UnavailableJavaRunContextProvider)
    assert provider.is_ready is False
    assert provider.reason_code == RunContextErrorCode.CONFIGURATION_UNAVAILABLE.value
    with pytest.raises(RunContextUnavailableError) as unavailable:
        asyncio.run(provider.load(_request()))
    assert unavailable.value.code == RunContextErrorCode.CONFIGURATION_UNAVAILABLE.value
    assert java_client.context_calls == []


def test_complete_production_configuration_enables_java_context_readiness_without_probe() -> None:
    settings = internal_settings(
        environment="production",
        java_internal_base_url=_JAVA_BASE_URL,
    )
    java_client = _RecordingJavaClient()
    application = create_app(
        settings,
        gateway_factory=lambda _settings: LocalModelGateway(),
        internal_nonce_store=FakeInternalNonceStore(),
        java_client_factory=lambda _settings: java_client,
    )

    with TestClient(
        application,
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        first = client.get("/internal/v1/health/ready")
        second = client.get("/internal/v1/health/ready")

        assert isinstance(application.state.run_context_provider, JavaRunContextProvider)

    for response in (first, second):
        assert response.status_code == 200
        checks = {item["name"]: item for item in response.json()["checks"]}
        assert checks["authorizedContext"] == {
            "name": "authorizedContext",
            "status": "READY",
            "reasonCode": None,
        }
    assert java_client.context_calls == []
    assert java_client.closed is True


def test_test_environment_can_pair_java_context_with_local_model_gateway() -> None:
    settings = internal_settings(
        environment="test",
        model_gateway="LOCAL",
        run_context_provider="JAVA",
        java_internal_base_url=_JAVA_BASE_URL,
    )
    java_client = _RecordingJavaClient()
    application = create_app(
        settings,
        gateway_factory=lambda _settings: LocalModelGateway(),
        internal_nonce_store=FakeInternalNonceStore(),
        java_client_factory=lambda _settings: java_client,
    )

    with TestClient(
        application,
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        response = client.get("/internal/v1/health/ready")

        assert isinstance(application.state.run_context_provider, JavaRunContextProvider)

    assert response.status_code == 200
    checks = {item["name"]: item for item in response.json()["checks"]}
    assert checks["modelGateway"]["status"] == "READY"
    assert checks["authorizedContext"] == {
        "name": "authorizedContext",
        "status": "READY",
        "reasonCode": None,
    }
    assert java_client.context_calls == []
    assert java_client.closed is True


@pytest.mark.parametrize("environment", ("production", "test"))
def test_java_context_chat_run_does_not_fall_back_to_trusted_state(
    environment: str,
) -> None:
    request = _request()
    settings = internal_settings(
        environment=environment,
        run_context_provider="JAVA",
        java_internal_base_url=_JAVA_BASE_URL,
        mock_event_delay_ms=0,
    )
    java_client = _RecordingJavaClient()
    payload = request.model_dump(mode="json", by_alias=True)
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
    start_path = "/internal/v1/chat-runs"
    events_path = f"/internal/v1/chat-runs/{request.run_id}/events"

    with TestClient(
        create_app(
            settings,
            gateway_factory=lambda _settings: LocalModelGateway(),
            internal_nonce_store=FakeInternalNonceStore(),
            java_client_factory=lambda _settings: java_client,
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        started = client.post(
            start_path,
            content=body,
            headers={
                **signed_headers("POST", start_path, body=body),
                "Content-Type": "application/json",
            },
        )
        events_response = client.get(
            events_path,
            headers=signed_headers("GET", events_path),
        )

    assert started.status_code == 202
    events = _sse_events(events_response.text)
    event_types = [event["eventType"] for event in events]
    assert "task-draft.proposed" not in event_types
    run_started = next(event for event in events if event["eventType"] == "run.started")
    run_started_payload = cast(dict[str, object], run_started["payload"])
    assert "activeScope" not in run_started_payload
    failed = next(event for event in events if event["eventType"] == "run.failed")
    failed_payload = cast(dict[str, object], failed["payload"])
    assert failed_payload["code"] == RunContextErrorCode.AUTHORIZATION_MISMATCH.value
    assert java_client.context_calls == [
        (request.run_id, request.user_context_ref, None),
    ]

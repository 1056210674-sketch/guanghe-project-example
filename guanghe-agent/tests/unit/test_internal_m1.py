"""Python M1 HMAC, nonce, trace, and Java client gates without real sockets."""

import asyncio
import json
import time
import tomllib
from pathlib import Path
from typing import cast
from uuid import UUID

import httpx
import pytest
from fastapi.testclient import TestClient
from redis.asyncio import Redis

from app.api.application import create_app
from app.common.settings import AppSettings
from app.integration.java_internal import (
    JavaInternalClient,
    JavaInternalClientError,
    JavaInternalErrorCode,
    JavaInternalResponse,
    JavaReverseCallResult,
)
from app.security.internal_hmac import EMPTY_BODY_SHA256, sign_content
from app.security.nonce import RedisInternalNonceStore, build_internal_nonce_store
from tests.internal_security import (
    TEST_KEY_ID,
    TEST_SECRET_TEXT,
    TEST_TRACEPARENT,
    FakeInternalNonceStore,
    internal_settings,
    signed_headers,
)

ROOT = Path(__file__).resolve().parents[2]


def test_python_signer_matches_shared_java_vector() -> None:
    vector = json.loads(
        (ROOT / "contracts" / "hmac" / "hmac-v1-test-vector.json").read_text(encoding="utf-8")
    )
    signed = sign_content(
        secret=vector["secret"].encode("utf-8"),
        timestamp=vector["timestamp"],
        nonce=vector["nonce"],
        method=vector["method"],
        raw_path_with_query=vector["rawPathWithQuery"],
        body=vector["bodyUtf8"].encode("utf-8"),
    )
    assert signed.body_sha256 == vector["bodySha256"]
    assert signed.signature_base64 == vector["signatureBase64"]
    assert EMPTY_BODY_SHA256 == ("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")


def test_correct_signature_succeeds_and_raw_body_reaches_fastapi() -> None:
    path = "/internal/v1/chat-runs"
    payload: dict[str, object] = {
        "runId": "00000000-0000-4000-8000-000000000101",
        "conversationId": "conversation-1",
        "userContextRef": "opaque-context-1",
        "message": {
            "messageId": "message-1",
            "content": "你好",
            "replyToMessageId": None,
            "attachmentAssetIds": [],
        },
        "trustedState": {
            "selectedSpaceId": None,
            "selectedProductId": None,
            "pendingInteractionId": None,
            "draftId": None,
            "taskId": None,
        },
        "budget": {
            "maxModelCalls": 4,
            "maxToolCalls": 6,
            "maxSteps": 12,
            "maxInputTokens": 32000,
            "deadlineAt": "2099-01-01T00:00:00+08:00",
        },
    }
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    store = FakeInternalNonceStore()
    with TestClient(
        create_app(
            internal_settings(mock_event_delay_ms=0),
            internal_nonce_store=store,
        )
    ) as client:
        response = client.post(
            path,
            content=body,
            headers={
                **signed_headers("POST", path, body=body),
                "Content-Type": "application/json",
            },
        )
    assert response.status_code == 202
    assert response.json()["runId"] == payload["runId"]
    assert response.headers["X-Trace-Id"] == "0123456789abcdef0123456789abcdef"
    assert store.claim_calls[0][0] == TEST_KEY_ID
    assert store.claim_calls[0][2] == 120


def test_capabilities_without_signature_is_rejected() -> None:
    with TestClient(create_app(internal_settings())) as client:
        response = client.get("/internal/v1/capabilities")
    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_SIGNATURE_INVALID"


def test_modified_body_is_rejected_before_nonce_claim() -> None:
    path = "/internal/v1/chat-runs"
    signed_body = b'{"signed":true}'
    store = FakeInternalNonceStore()
    with TestClient(create_app(internal_settings(), internal_nonce_store=store)) as client:
        response = client.post(
            path,
            content=b'{"signed":false}',
            headers={
                **signed_headers("POST", path, body=signed_body),
                "Content-Type": "application/json",
            },
        )
    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_SIGNATURE_INVALID"
    assert store.claim_calls == []


def test_modified_query_is_rejected_before_nonce_claim() -> None:
    signed_path = "/internal/v1/capabilities?mode=a%20b"
    sent_path = "/internal/v1/capabilities?mode=a%20c"
    store = FakeInternalNonceStore()
    with TestClient(create_app(internal_settings(), internal_nonce_store=store)) as client:
        response = client.get(sent_path, headers=signed_headers("GET", signed_path))
    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_SIGNATURE_INVALID"
    assert store.claim_calls == []


def test_percent_encoded_query_is_verified_without_normalization() -> None:
    path = "/internal/v1/capabilities?mode=a%20b"
    with TestClient(
        create_app(
            internal_settings(),
            internal_nonce_store=FakeInternalNonceStore(),
        )
    ) as client:
        response = client.get(path, headers=signed_headers("GET", path))
    assert response.status_code == 200


def test_query_order_change_is_rejected_before_nonce_claim() -> None:
    signed_path = "/internal/v1/capabilities?b=2&a=1"
    sent_path = "/internal/v1/capabilities?a=1&b=2"
    store = FakeInternalNonceStore()
    with TestClient(create_app(internal_settings(), internal_nonce_store=store)) as client:
        response = client.get(sent_path, headers=signed_headers("GET", signed_path))
    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_SIGNATURE_INVALID"
    assert store.claim_calls == []


def test_duplicate_nonce_is_rejected() -> None:
    path = "/internal/v1/capabilities"
    nonce = "00000000-0000-4000-8000-000000000201"
    headers = signed_headers("GET", path, nonce=nonce)
    with TestClient(
        create_app(
            internal_settings(),
            internal_nonce_store=FakeInternalNonceStore(),
        )
    ) as client:
        accepted = client.get(path, headers=headers)
        replay = client.get(path, headers=headers)
    assert accepted.status_code == 200
    assert replay.status_code == 401
    assert replay.json()["code"] == "INTERNAL_REPLAY_DETECTED"


def test_canonical_lowercase_nonce_is_accepted_and_claimed() -> None:
    path = "/internal/v1/capabilities"
    nonce = "abcdefab-cdef-4abc-8def-abcdefabcdef"
    store = FakeInternalNonceStore()
    with TestClient(create_app(internal_settings(), internal_nonce_store=store)) as client:
        response = client.get(path, headers=signed_headers("GET", path, nonce=nonce))
    assert response.status_code == 200
    assert store.claim_calls == [(TEST_KEY_ID, nonce, 120)]


def test_uppercase_nonce_with_matching_signature_is_rejected_before_claim() -> None:
    path = "/internal/v1/capabilities"
    nonce = "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF"
    store = FakeInternalNonceStore()
    with TestClient(create_app(internal_settings(), internal_nonce_store=store)) as client:
        response = client.get(path, headers=signed_headers("GET", path, nonce=nonce))
    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_SIGNATURE_INVALID"
    assert store.claim_calls == []


def test_noncanonical_nonce_is_rejected_before_claim() -> None:
    path = "/internal/v1/capabilities"
    nonce = "abcdefabcdef4abc8defabcdefabcdef"
    store = FakeInternalNonceStore()
    with TestClient(create_app(internal_settings(), internal_nonce_store=store)) as client:
        response = client.get(path, headers=signed_headers("GET", path, nonce=nonce))
    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_SIGNATURE_INVALID"
    assert store.claim_calls == []


def test_duplicate_nonce_header_is_rejected_before_claim() -> None:
    path = "/internal/v1/capabilities"
    nonce = "abcdefab-cdef-4abc-8def-abcdefabcdef"
    headers = list(signed_headers("GET", path, nonce=nonce).items())
    headers.append(("X-Internal-Nonce", nonce))
    store = FakeInternalNonceStore()
    with TestClient(create_app(internal_settings(), internal_nonce_store=store)) as client:
        response = client.get(path, headers=headers)
    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_SIGNATURE_INVALID"
    assert store.claim_calls == []


def test_expired_timestamp_is_rejected_before_nonce_claim() -> None:
    path = "/internal/v1/capabilities"
    store = FakeInternalNonceStore()
    headers = signed_headers("GET", path, timestamp=int(time.time()) - 61)
    with TestClient(create_app(internal_settings(), internal_nonce_store=store)) as client:
        response = client.get(path, headers=headers)
    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_SIGNATURE_INVALID"
    assert store.claim_calls == []


def test_non_java_timestamp_syntax_is_rejected_before_nonce_claim() -> None:
    path = "/internal/v1/capabilities"
    store = FakeInternalNonceStore()
    timestamp = f"{int(time.time()):_}"
    headers = signed_headers("GET", path, timestamp=timestamp)
    with TestClient(create_app(internal_settings(), internal_nonce_store=store)) as client:
        response = client.get(path, headers=headers)
    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_SIGNATURE_INVALID"
    assert store.claim_calls == []


def test_non_ascii_signature_is_rejected_instead_of_raising() -> None:
    path = "/internal/v1/capabilities"
    headers: list[tuple[bytes, bytes]] = [
        (name.encode("ascii"), value.encode("ascii"))
        for name, value in signed_headers("GET", path).items()
        if name != "X-Internal-Signature"
    ]
    headers.append((b"X-Internal-Signature", b"\xe9" * 44))
    with TestClient(
        create_app(
            internal_settings(),
            internal_nonce_store=FakeInternalNonceStore(),
        )
    ) as client:
        response = client.get(path, headers=headers)
    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_SIGNATURE_INVALID"


def test_wrong_key_id_and_duplicate_security_header_are_rejected() -> None:
    path = "/internal/v1/capabilities"
    wrong_key_headers = signed_headers("GET", path, key_id="wrong-internal-key")
    duplicated_headers = list(signed_headers("GET", path).items())
    duplicated_headers.append(("X-Internal-Signature", "synthetic-duplicate"))
    store = FakeInternalNonceStore()
    with TestClient(create_app(internal_settings(), internal_nonce_store=store)) as client:
        wrong_key = client.get(path, headers=wrong_key_headers)
        duplicate = client.get(path, headers=duplicated_headers)
    assert wrong_key.status_code == 401
    assert duplicate.status_code == 401
    assert wrong_key.json()["code"] == "INTERNAL_SIGNATURE_INVALID"
    assert duplicate.json()["code"] == "INTERNAL_SIGNATURE_INVALID"
    assert store.claim_calls == []


def test_oversized_body_is_rejected_before_nonce_claim() -> None:
    path = "/internal/v1/chat-runs"
    body = b"123456789"
    store = FakeInternalNonceStore()
    settings = internal_settings(internal_hmac_max_body_bytes=8)
    with TestClient(create_app(settings, internal_nonce_store=store)) as client:
        response = client.post(
            path,
            content=body,
            headers=signed_headers("POST", path, body=body),
        )
    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_SIGNATURE_INVALID"
    assert store.claim_calls == []


def test_readiness_is_the_unsigned_internal_exception() -> None:
    with TestClient(
        create_app(
            internal_settings(),
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        response = client.get("/internal/v1/health/ready")
    assert response.status_code == 200
    assert response.json()["status"] == "READY"
    assert len(response.headers["X-Trace-Id"]) == 32


def test_unsigned_readiness_rejects_non_loopback_callers() -> None:
    application = create_app(AppSettings())
    with TestClient(
        application,
        base_url="http://127.0.0.1",
        client=("203.0.113.10", 50000),
    ) as client:
        response = client.get("/internal/v1/health/ready")
    assert response.status_code == 403
    assert response.json()["code"] == "FORBIDDEN"


def test_unsigned_readiness_rejects_public_host_and_proxy_headers() -> None:
    application = create_app(AppSettings())
    with TestClient(
        application,
        base_url="http://public.example",
        client=("127.0.0.1", 50000),
    ) as client:
        public_host = client.get("/internal/v1/health/ready")
    with TestClient(
        application,
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        forwarded = client.get(
            "/internal/v1/health/ready",
            headers={"X-Forwarded-For": "203.0.113.10"},
        )
    assert public_host.status_code == 403
    assert forwarded.status_code == 403


def test_missing_hmac_configuration_fails_closed() -> None:
    with TestClient(create_app(AppSettings())) as client:
        response = client.get("/internal/v1/capabilities")
    assert response.status_code == 503
    assert response.json()["code"] == "INTERNAL_AUTHENTICATION_UNAVAILABLE"


def test_unconfigured_nonce_store_rejects_an_authentic_request() -> None:
    path = "/internal/v1/capabilities"
    with TestClient(create_app(internal_settings())) as client:
        response = client.get(path, headers=signed_headers("GET", path))
    assert response.status_code == 503
    assert response.json()["code"] == "INTERNAL_AUTHENTICATION_UNAVAILABLE"


def test_redis_unavailable_rejects_an_authentic_request() -> None:
    path = "/internal/v1/capabilities"
    with TestClient(
        create_app(
            internal_settings(),
            internal_nonce_store=FakeInternalNonceStore(unavailable=True),
        )
    ) as client:
        response = client.get(path, headers=signed_headers("GET", path))
    assert response.status_code == 503
    assert response.json()["code"] == "INTERNAL_AUTHENTICATION_UNAVAILABLE"


def test_nonce_replay_and_store_failure_have_distinct_http_statuses() -> None:
    path = "/internal/v1/capabilities"
    nonce = "00000000-0000-4000-8000-000000000202"
    headers = signed_headers("GET", path, nonce=nonce)
    with TestClient(
        create_app(
            internal_settings(),
            internal_nonce_store=FakeInternalNonceStore(),
        )
    ) as client:
        assert client.get(path, headers=headers).status_code == 200
        replay = client.get(path, headers=headers)
    with TestClient(
        create_app(
            internal_settings(),
            internal_nonce_store=FakeInternalNonceStore(unavailable=True),
        )
    ) as client:
        unavailable = client.get(path, headers=signed_headers("GET", path))

    assert replay.status_code == 401
    assert replay.json()["code"] == "INTERNAL_REPLAY_DETECTED"
    assert unavailable.status_code == 503
    assert unavailable.json()["code"] == "INTERNAL_AUTHENTICATION_UNAVAILABLE"


def test_redis_nonce_key_hashes_identity_and_uses_nx_ttl() -> None:
    class RecordingRedis:
        def __init__(self) -> None:
            self.name: str | None = None
            self.ex: int | None = None
            self.nx: bool | None = None

        async def set(
            self,
            name: str,
            value: bytes,
            *,
            ex: int,
            nx: bool,
        ) -> bool:
            assert value == b"1"
            self.name = name
            self.ex = ex
            self.nx = nx
            return True

        async def aclose(self) -> None:
            return None

        async def ping(self) -> bool:
            return True

    redis = RecordingRedis()
    store = RedisInternalNonceStore(cast(Redis, redis), "zhixiaohe:test:")
    claimed = asyncio.run(store.claim(TEST_KEY_ID, "nonce-for-key-test", 120))
    assert claimed is True
    assert redis.name is not None
    assert redis.name.startswith("zhixiaohe:test:internal:hmac:nonce:")
    assert TEST_KEY_ID not in redis.name
    assert "nonce-for-key-test" not in redis.name
    assert redis.ex == 120
    assert redis.nx is True


def test_redis_client_uses_resp2_for_acl_authentication(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    class RecordingRedis:
        def __init__(self, **kwargs: object) -> None:
            captured.update(kwargs)

        async def aclose(self) -> None:
            return None

    monkeypatch.setattr("app.security.nonce.Redis", RecordingRedis)
    store = build_internal_nonce_store(
        internal_settings(
            redis_host="redis.unit.test",
            redis_port=6380,
            redis_username="synthetic-user",
            redis_password="synthetic-password",
            redis_ssl_enabled=True,
            redis_ca_cert_path="C:/synthetic/test-ca.pem",
            redis_key_prefix="zhixiaohe:test:unit:",
        )
    )
    assert isinstance(store, RedisInternalNonceStore)
    assert captured["protocol"] == 2
    assert captured["ssl"] is True
    assert Path(cast(str, captured["ssl_ca_certs"])) == Path("C:/synthetic/test-ca.pem")
    assert captured["ssl_check_hostname"] is True
    asyncio.run(store.aclose())


def test_python_hmac_client_calls_java_reverse_probe_with_query_json_and_child_trace() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        timestamp = request.headers["X-Internal-Timestamp"]
        nonce = request.headers["X-Internal-Nonce"]
        raw_path = request.url.raw_path.decode("ascii")
        expected = sign_content(
            secret=TEST_SECRET_TEXT.encode("utf-8"),
            timestamp=timestamp,
            nonce=nonce,
            method=request.method,
            raw_path_with_query=raw_path,
            body=request.content,
        )
        assert raw_path == "/internal/v1/test/reverse-call?mode=python-m1"
        assert request.content == b'{"probe":"python-m1"}'
        assert request.headers["X-Content-SHA256"] == expected.body_sha256
        assert request.headers["X-Internal-Signature"] == expected.signature_base64
        assert request.headers["traceparent"].startswith("00-0123456789abcdef0123456789abcdef-")
        assert request.headers["traceparent"] != TEST_TRACEPARENT
        return httpx.Response(
            200,
            json={
                "accepted": True,
                "callerKeyId": TEST_KEY_ID,
                "traceId": "0123456789abcdef0123456789abcdef",
            },
        )

    async def scenario() -> JavaReverseCallResult:
        http_client = httpx.AsyncClient(
            base_url="http://127.0.0.1:8080",
            transport=httpx.MockTransport(handler),
        )
        java_client = JavaInternalClient(
            internal_settings(java_internal_base_url="http://127.0.0.1:8080"),
            http_client,
            clock=lambda: 1_787_625_000,
            nonce_factory=lambda: UUID("00000000-0000-4000-8000-000000000301"),
        )
        try:
            return await java_client.reverse_call(TEST_TRACEPARENT)
        finally:
            await java_client.aclose()

    result = asyncio.run(scenario())
    assert len(captured) == 1
    assert result.accepted is True
    assert result.caller_key_id == TEST_KEY_ID
    assert result.trace_id == "0123456789abcdef0123456789abcdef"


def test_java_client_get_preserves_frozen_query_order_and_encoding() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        timestamp = request.headers["X-Internal-Timestamp"]
        nonce = request.headers["X-Internal-Nonce"]
        raw_path = request.url.raw_path.decode("ascii")
        expected = sign_content(
            secret=TEST_SECRET_TEXT.encode("utf-8"),
            timestamp=timestamp,
            nonce=nonce,
            method="GET",
            raw_path_with_query=raw_path,
            body=b"",
        )
        assert raw_path == "/internal/v1/test/reverse-call?mode=a%20b&tag=x%2Fy&mode=second"
        assert request.headers["X-Internal-Signature"] == expected.signature_base64
        return httpx.Response(
            200,
            headers={"X-Trace-Id": "0123456789abcdef0123456789abcdef"},
            json={"accepted": True},
        )

    async def scenario() -> None:
        http_client = httpx.AsyncClient(
            base_url="http://127.0.0.1:8080",
            transport=httpx.MockTransport(handler),
        )
        java_client = JavaInternalClient(
            internal_settings(java_internal_base_url="http://127.0.0.1:8080"),
            http_client,
        )
        try:
            response = await java_client.get(
                "/internal/v1/test/reverse-call",
                query=(("mode", "a b"), ("tag", "x/y"), ("mode", "second")),
                traceparent=TEST_TRACEPARENT,
            )
            assert response.payload == {"accepted": True}
        finally:
            await java_client.aclose()

    asyncio.run(scenario())
    assert len(captured) == 1


def test_java_client_posts_workflow_plan_with_context_ref_hmac_and_trace() -> None:
    run_id = UUID("11111111-1111-4111-8111-111111111111")
    context_ref = "opaque-workflow-context"
    payload: dict[str, object] = {
        "proposalId": "22222222-2222-4222-8222-222222222222",
        "conversationId": "9001",
        "goalSummary": "组合计划",
        "budget": {
            "maxSteps": 3,
            "maxReplans": 1,
            "maxModelCalls": 4,
            "maxToolCalls": 3,
            "maxProviderCalls": 2,
            "maxPointCost": 100,
            "deadlineAt": "2026-09-11T00:00:00Z",
        },
        "steps": [],
    }

    def handler(request: httpx.Request) -> httpx.Response:
        raw_path = request.url.raw_path.decode("ascii")
        assert request.method == "POST"
        assert raw_path == f"/internal/v1/runs/{run_id}/workflow-plans"
        assert request.headers["X-User-Context-Ref"] == context_ref
        expected = sign_content(
            secret=TEST_SECRET_TEXT.encode("utf-8"),
            timestamp=request.headers["X-Internal-Timestamp"],
            nonce=request.headers["X-Internal-Nonce"],
            method="POST",
            raw_path_with_query=raw_path,
            body=request.content,
        )
        assert request.headers["X-Internal-Signature"] == expected.signature_base64
        assert request.headers["traceparent"].startswith("00-0123456789abcdef0123456789abcdef-")
        return httpx.Response(201, json={"id": "9002"})

    async def scenario() -> JavaInternalResponse:
        client = JavaInternalClient(
            internal_settings(java_internal_base_url="http://127.0.0.1:8080"),
            httpx.AsyncClient(
                base_url="http://127.0.0.1:8080",
                transport=httpx.MockTransport(handler),
            ),
        )
        try:
            return await client.propose_workflow_plan(
                run_id,
                context_ref,
                payload,
                TEST_TRACEPARENT,
            )
        finally:
            await client.aclose()

    response = asyncio.run(scenario())
    assert response.status_code == 201
    assert response.payload == {"id": "9002"}


def test_java_client_signs_caller_json_bytes_without_reserializing() -> None:
    raw_body = b'{"probe": "spacing-is-part-of-signature"}\n'

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.content == raw_body
        expected = sign_content(
            secret=TEST_SECRET_TEXT.encode("utf-8"),
            timestamp=request.headers["X-Internal-Timestamp"],
            nonce=request.headers["X-Internal-Nonce"],
            method="POST",
            raw_path_with_query=request.url.raw_path.decode("ascii"),
            body=raw_body,
        )
        assert request.headers["X-Content-SHA256"] == expected.body_sha256
        assert request.headers["X-Internal-Signature"] == expected.signature_base64
        return httpx.Response(200, json={"accepted": True})

    async def scenario() -> None:
        http_client = httpx.AsyncClient(
            base_url="http://127.0.0.1:8080",
            transport=httpx.MockTransport(handler),
        )
        java_client = JavaInternalClient(
            internal_settings(java_internal_base_url="http://127.0.0.1:8080"),
            http_client,
        )
        try:
            await java_client.post_json_bytes(
                "/internal/v1/test/reverse-call",
                raw_body,
                traceparent=TEST_TRACEPARENT,
            )
        finally:
            await java_client.aclose()

    asyncio.run(scenario())


def test_traceparent_continues_from_python_request_to_java_client() -> None:
    class RecordingJavaClient:
        def __init__(self) -> None:
            self.traceparent: str | None = None

        async def get_run_context(
            self,
            run_id: UUID,
            user_context_ref: str,
            traceparent: str | None = None,
        ) -> JavaInternalResponse:
            del run_id, user_context_ref, traceparent
            raise AssertionError("Run Context is not used by the reverse-call probe")

        async def reverse_call(self, traceparent: str | None = None) -> JavaReverseCallResult:
            self.traceparent = traceparent
            return JavaReverseCallResult(
                accepted=True,
                caller_key_id=TEST_KEY_ID,
                trace_id="0123456789abcdef0123456789abcdef",
            )

        async def aclose(self) -> None:
            return None

    path = "/internal/v1/test/java-reverse-call"
    java_client = RecordingJavaClient()
    with TestClient(
        create_app(
            internal_settings(),
            internal_nonce_store=FakeInternalNonceStore(),
            java_client_factory=lambda _settings: java_client,
        )
    ) as client:
        response = client.post(path, headers=signed_headers("POST", path))
    assert response.status_code == 200
    assert java_client.traceparent == TEST_TRACEPARENT
    assert response.json()["traceId"] == "0123456789abcdef0123456789abcdef"
    assert response.headers["X-Trace-Id"] == "0123456789abcdef0123456789abcdef"


def test_java_client_classifies_connection_timeout_401_and_5xx() -> None:
    async def invoke(handler: httpx.MockTransport) -> JavaInternalErrorCode:
        http_client = httpx.AsyncClient(
            base_url="http://127.0.0.1:8080",
            transport=handler,
        )
        java_client = JavaInternalClient(
            internal_settings(java_internal_base_url="http://127.0.0.1:8080"),
            http_client,
        )
        try:
            await java_client.reverse_call(TEST_TRACEPARENT)
        except JavaInternalClientError as exc:
            return exc.code
        finally:
            await java_client.aclose()
        raise AssertionError("Expected JavaInternalClientError")

    def connection_handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("synthetic", request=request)

    def timeout_handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("synthetic", request=request)

    def unauthorized_handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(401)

    def upstream_handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(503)

    assert asyncio.run(invoke(httpx.MockTransport(connection_handler))) is (
        JavaInternalErrorCode.CONNECTION_FAILED
    )
    assert asyncio.run(invoke(httpx.MockTransport(timeout_handler))) is (
        JavaInternalErrorCode.TIMEOUT
    )
    assert asyncio.run(invoke(httpx.MockTransport(unauthorized_handler))) is (
        JavaInternalErrorCode.AUTHENTICATION_REJECTED
    )
    assert asyncio.run(invoke(httpx.MockTransport(upstream_handler))) is (
        JavaInternalErrorCode.UPSTREAM_5XX
    )


def test_python_project_has_no_postgresql_dependency_or_setting() -> None:
    project = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))
    dependencies = " ".join(project["project"]["dependencies"]).casefold()
    lockfile = (ROOT / "uv.lock").read_text(encoding="utf-8").casefold()
    for forbidden in ("postgres", "psycopg", "asyncpg", "sqlalchemy", "alembic"):
        assert forbidden not in dependencies
        assert forbidden not in lockfile
    setting_names = {name.casefold() for name in AppSettings.model_fields}
    assert not any("postgres" in name or "database" in name for name in setting_names)

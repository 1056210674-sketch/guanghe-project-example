"""Java-compatible internal HMAC signing and ASGI verification."""

import base64
import hashlib
import hmac
import ipaddress
import json
import logging
import re
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import cast
from urllib.parse import urlsplit
from uuid import UUID

from starlette.datastructures import Headers
from starlette.responses import Response
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.common.settings import AppSettings
from app.observability.trace import (
    TraceContext,
    reset_current_trace_id,
    resolve_trace_context,
    set_current_trace_id,
)
from app.security.nonce import InternalNonceStore, InternalNonceStoreUnavailableError

KEY_ID_HEADER = "X-Internal-Key-Id"
TIMESTAMP_HEADER = "X-Internal-Timestamp"
NONCE_HEADER = "X-Internal-Nonce"
CONTENT_SHA256_HEADER = "X-Content-SHA256"
SIGNATURE_HEADER = "X-Internal-Signature"
TRACEPARENT_HEADER = "traceparent"
TRACE_ID_HEADER = "X-Trace-Id"
READINESS_PATH = "/internal/v1/health/ready"
INTERNAL_PATH_PREFIX = "/internal/v1/"
EMPTY_BODY_SHA256 = hashlib.sha256(b"").hexdigest()

_LOWERCASE_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_BASE64_SHA256 = re.compile(r"^[A-Za-z0-9+/]{43}=$")
_JAVA_LONG_TEXT = re.compile(r"^[+-]?[0-9]+$")
_JAVA_LONG_MIN = -(1 << 63)
_JAVA_LONG_MAX = (1 << 63) - 1
_PROXY_ORIGIN_HEADERS = (
    "forwarded",
    "x-forwarded-for",
    "x-forwarded-host",
    "x-forwarded-proto",
    "x-real-ip",
)
_logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class SignedContent:
    body_sha256: str
    signature_base64: str


def sign_content(
    *,
    secret: bytes,
    timestamp: str,
    nonce: str,
    method: str,
    raw_path_with_query: str,
    body: bytes,
) -> SignedContent:
    """Produce exactly the canonical content used by Java InternalHmacSigner."""
    if not secret:
        raise ValueError("HMAC secret must not be empty")
    body_sha256 = hashlib.sha256(body).hexdigest()
    canonical = "\n".join(
        (timestamp, nonce, method.upper(), raw_path_with_query, body_sha256)
    ).encode("utf-8")
    digest = hmac.new(secret, canonical, hashlib.sha256).digest()
    return SignedContent(
        body_sha256=body_sha256,
        signature_base64=base64.b64encode(digest).decode("ascii"),
    )


class InternalHmacMiddleware:
    """Authenticate every Internal route except the loopback readiness probe."""

    def __init__(
        self,
        app: ASGIApp,
        *,
        settings: AppSettings,
        nonce_store: InternalNonceStore,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._app = app
        self._settings = settings
        self._nonce_store = nonce_store
        self._clock = clock

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self._app(scope, receive, send)
            return

        headers = Headers(scope=scope)
        trace = resolve_trace_context(self._single_header(headers, TRACEPARENT_HEADER))
        state = cast(dict[str, object], scope.setdefault("state", {}))
        state["traceparent"] = trace.traceparent
        state["trace_id"] = trace.trace_id
        state["traceId"] = trace.trace_id
        token = set_current_trace_id(trace.trace_id)
        traced_send = self._traced_send(send, trace.trace_id)
        try:
            path = cast(str, scope["path"])
            if not path.startswith(INTERNAL_PATH_PREFIX):
                await self._app(scope, receive, traced_send)
                return
            if path == READINESS_PATH:
                if self._is_direct_loopback(scope, headers):
                    await self._app(scope, receive, traced_send)
                else:
                    await self._reject(
                        scope,
                        receive,
                        traced_send,
                        trace,
                        status_code=403,
                        code="FORBIDDEN",
                        message="Access is forbidden",
                    )
                return

            body, body_too_large, disconnected = await self._read_body(
                receive,
                self._settings.internal_hmac_max_body_bytes,
            )
            if body_too_large or disconnected:
                await self._reject(
                    scope,
                    receive,
                    traced_send,
                    trace,
                    status_code=401,
                    code="INTERNAL_SIGNATURE_INVALID",
                    message="Internal request signature is invalid",
                )
                return

            rejection = self._verify_headers_and_signature(scope, headers, body)
            if rejection is not None:
                if rejection == "configuration":
                    await self._reject(
                        scope,
                        receive,
                        traced_send,
                        trace,
                        status_code=503,
                        code="INTERNAL_AUTHENTICATION_UNAVAILABLE",
                        message="Internal authentication is temporarily unavailable",
                    )
                    return
                await self._reject(
                    scope,
                    receive,
                    traced_send,
                    trace,
                    status_code=401,
                    code="INTERNAL_SIGNATURE_INVALID",
                    message="Internal request signature is invalid",
                )
                return

            key_id = self._single_header(headers, KEY_ID_HEADER)
            nonce = self._single_header(headers, NONCE_HEADER)
            if key_id is None or nonce is None:
                await self._reject(
                    scope,
                    receive,
                    traced_send,
                    trace,
                    status_code=401,
                    code="INTERNAL_SIGNATURE_INVALID",
                    message="Internal request signature is invalid",
                )
                return
            try:
                claimed = await self._nonce_store.claim(
                    key_id,
                    nonce,
                    self._settings.internal_hmac_nonce_ttl_seconds,
                )
            except InternalNonceStoreUnavailableError:
                await self._reject(
                    scope,
                    receive,
                    traced_send,
                    trace,
                    status_code=503,
                    code="INTERNAL_AUTHENTICATION_UNAVAILABLE",
                    message="Internal authentication is temporarily unavailable",
                )
                return
            if not claimed:
                await self._reject(
                    scope,
                    receive,
                    traced_send,
                    trace,
                    status_code=401,
                    code="INTERNAL_REPLAY_DETECTED",
                    message="Internal request replay was detected",
                )
                return

            _logger.info(
                "internal_request_authenticated",
                extra={"traceId": trace.trace_id},
            )
            await self._app(scope, self._replay_body(receive, body), traced_send)
        finally:
            reset_current_trace_id(token)

    def _verify_headers_and_signature(
        self,
        scope: Scope,
        headers: Headers,
        body: bytes,
    ) -> str | None:
        supplied_key_id = self._single_header(headers, KEY_ID_HEADER)
        timestamp = self._single_header(headers, TIMESTAMP_HEADER)
        nonce = self._single_header(headers, NONCE_HEADER)
        supplied_body_hash = self._single_header(headers, CONTENT_SHA256_HEADER)
        supplied_signature = self._single_header(headers, SIGNATURE_HEADER)
        configured_key_id = self._settings.internal_hmac_key_id
        configured_secret = self._settings.internal_hmac_secret
        if configured_key_id is None or configured_secret is None:
            return "configuration"
        secret = configured_secret.get_secret_value().encode("utf-8")
        if len(secret) < 32:
            return "configuration"
        if (
            supplied_key_id is None
            or not self._constant_time_ascii_equals(configured_key_id, supplied_key_id)
            or timestamp is None
            or not self._valid_timestamp(timestamp)
            or nonce is None
            or not self._valid_nonce(nonce)
            or supplied_body_hash is None
            or _LOWERCASE_SHA256.fullmatch(supplied_body_hash) is None
            or supplied_signature is None
            or _BASE64_SHA256.fullmatch(supplied_signature) is None
        ):
            return "headers"
        try:
            raw_path_with_query = self._raw_path_with_query(scope)
        except UnicodeDecodeError:
            return "path"
        expected = sign_content(
            secret=secret,
            timestamp=timestamp,
            nonce=nonce,
            method=cast(str, scope["method"]),
            raw_path_with_query=raw_path_with_query,
            body=body,
        )
        if not self._constant_time_ascii_equals(expected.body_sha256, supplied_body_hash):
            return "body"
        if not self._constant_time_ascii_equals(expected.signature_base64, supplied_signature):
            return "signature"
        return None

    def _valid_timestamp(self, timestamp: str) -> bool:
        if len(timestamp) > 20 or _JAVA_LONG_TEXT.fullmatch(timestamp) is None:
            return False
        try:
            supplied = int(timestamp)
        except ValueError:
            return False
        if not _JAVA_LONG_MIN <= supplied <= _JAVA_LONG_MAX:
            return False
        now = int(self._clock())
        skew = self._settings.internal_hmac_allowed_clock_skew_seconds
        return now - skew <= supplied <= now + skew

    @staticmethod
    def _valid_nonce(nonce: str) -> bool:
        if len(nonce) > 36:
            return False
        try:
            return str(UUID(nonce)) == nonce
        except ValueError:
            return False

    @classmethod
    def _is_direct_loopback(cls, scope: Scope, headers: Headers) -> bool:
        if any(headers.getlist(name) for name in _PROXY_ORIGIN_HEADERS):
            return False
        client = scope.get("client")
        if client is None:
            return False
        try:
            client_is_loopback = ipaddress.ip_address(client[0]).is_loopback
        except ValueError:
            return False
        host_header = cls._single_header(headers, "host")
        if host_header is None:
            return False
        try:
            hostname = urlsplit(f"//{host_header}").hostname
        except ValueError:
            return False
        if hostname is None:
            return False
        if hostname.lower() == "localhost":
            host_is_loopback = True
        else:
            try:
                host_is_loopback = ipaddress.ip_address(hostname).is_loopback
            except ValueError:
                return False
        return client_is_loopback and host_is_loopback

    @staticmethod
    def _raw_path_with_query(scope: Scope) -> str:
        raw_path = cast(bytes, scope.get("raw_path") or cast(str, scope["path"]).encode("utf-8"))
        query = cast(bytes, scope.get("query_string", b""))
        combined = raw_path + (b"?" + query if query else b"")
        return combined.decode("ascii")

    @staticmethod
    async def _read_body(
        receive: Receive,
        max_body_bytes: int,
    ) -> tuple[bytes, bool, bool]:
        chunks: list[bytes] = []
        total = 0
        too_large = False
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return b"".join(chunks), too_large, True
            chunk = cast(bytes, message.get("body", b""))
            total += len(chunk)
            if total > max_body_bytes:
                return b"".join(chunks), True, False
            chunks.append(chunk)
            if not message.get("more_body", False):
                return b"".join(chunks), too_large, False

    @staticmethod
    def _single_header(headers: Headers, name: str) -> str | None:
        values = headers.getlist(name)
        return values[0] if len(values) == 1 else None

    @staticmethod
    def _constant_time_ascii_equals(expected: str, supplied: str) -> bool:
        try:
            expected_bytes = expected.encode("ascii")
            supplied_bytes = supplied.encode("ascii")
        except UnicodeEncodeError:
            return False
        return hmac.compare_digest(expected_bytes, supplied_bytes)

    @staticmethod
    def _replay_body(receive: Receive, body: bytes) -> Receive:
        delivered = False

        async def replay() -> Message:
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": body, "more_body": False}
            return await receive()

        return replay

    @staticmethod
    def _traced_send(send: Send, trace_id: str) -> Send:
        async def traced(message: Message) -> None:
            if message["type"] == "http.response.start":
                raw_headers = list(message.get("headers", []))
                if not any(name.lower() == b"x-trace-id" for name, _ in raw_headers):
                    raw_headers.append((b"x-trace-id", trace_id.encode("ascii")))
                    message = {**message, "headers": raw_headers}
            await send(message)

        return traced

    @staticmethod
    async def _reject(
        scope: Scope,
        receive: Receive,
        send: Send,
        trace: TraceContext,
        *,
        status_code: int,
        code: str,
        message: str,
    ) -> None:
        _logger.warning("internal_request_rejected code=%s", code)
        payload = json.dumps(
            {"code": code, "message": message, "details": {}, "traceId": trace.trace_id},
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        response = Response(payload, status_code=status_code, media_type="application/json")
        await response(scope, receive, send)

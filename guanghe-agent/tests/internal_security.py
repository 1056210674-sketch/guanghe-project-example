"""Process-local HMAC helpers; tests never connect to a real Redis or service."""

import time
from uuid import uuid4

from pydantic import SecretStr

from app.common.settings import AppSettings
from app.security.internal_hmac import (
    CONTENT_SHA256_HEADER,
    KEY_ID_HEADER,
    NONCE_HEADER,
    SIGNATURE_HEADER,
    TIMESTAMP_HEADER,
    TRACEPARENT_HEADER,
    sign_content,
)
from app.security.nonce import InternalNonceStoreUnavailableError

TEST_KEY_ID = "agent-service-unit-test-v1"
TEST_SECRET_TEXT = "0123456789abcdef0123456789abcdef"
TEST_TRACEPARENT = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"


class FakeInternalNonceStore:
    def __init__(self, *, unavailable: bool = False) -> None:
        self.unavailable = unavailable
        self.claimed: set[tuple[str, str]] = set()
        self.claim_calls: list[tuple[str, str, int]] = []
        self.closed = False

    async def claim(self, key_id: str, nonce: str, ttl_seconds: int) -> bool:
        if self.unavailable:
            raise InternalNonceStoreUnavailableError
        self.claim_calls.append((key_id, nonce, ttl_seconds))
        key = (key_id, nonce)
        if key in self.claimed:
            return False
        self.claimed.add(key)
        return True

    async def is_available(self) -> bool:
        return not self.unavailable

    async def aclose(self) -> None:
        self.closed = True


def internal_settings(**overrides: object) -> AppSettings:
    values: dict[str, object] = {
        "environment": "test",
        "internal_hmac_key_id": TEST_KEY_ID,
        "internal_hmac_secret": SecretStr(TEST_SECRET_TEXT),
        "internal_hmac_allowed_clock_skew_seconds": 60,
        "internal_hmac_nonce_ttl_seconds": 120,
    }
    values.update(overrides)
    return AppSettings.model_validate(values)


def signed_headers(
    method: str,
    raw_path_with_query: str,
    *,
    body: bytes = b"",
    timestamp: int | str | None = None,
    nonce: str | None = None,
    traceparent: str = TEST_TRACEPARENT,
    secret: str = TEST_SECRET_TEXT,
    key_id: str = TEST_KEY_ID,
) -> dict[str, str]:
    resolved_timestamp = str(int(time.time())) if timestamp is None else str(timestamp)
    resolved_nonce = nonce or str(uuid4())
    signed = sign_content(
        secret=secret.encode("utf-8"),
        timestamp=resolved_timestamp,
        nonce=resolved_nonce,
        method=method,
        raw_path_with_query=raw_path_with_query,
        body=body,
    )
    return {
        KEY_ID_HEADER: key_id,
        TIMESTAMP_HEADER: resolved_timestamp,
        NONCE_HEADER: resolved_nonce,
        CONTENT_SHA256_HEADER: signed.body_sha256,
        SIGNATURE_HEADER: signed.signature_base64,
        TRACEPARENT_HEADER: traceparent,
    }

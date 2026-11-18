"""Fail-closed Redis nonce claims for internal HMAC authentication."""

import hashlib
from typing import Protocol, cast

from redis.asyncio import Redis

from app.common.settings import AppSettings


class InternalNonceStoreUnavailableError(RuntimeError):
    """Raised when replay protection cannot safely claim a nonce."""


class InternalNonceStore(Protocol):
    async def claim(self, key_id: str, nonce: str, ttl_seconds: int) -> bool: ...

    async def is_available(self) -> bool: ...

    async def aclose(self) -> None: ...


class _RedisClient(Protocol):
    async def set(
        self,
        name: str,
        value: bytes,
        *,
        ex: int,
        nx: bool,
    ) -> object: ...

    async def ping(self) -> bool: ...

    async def aclose(self) -> None: ...


class UnavailableInternalNonceStore:
    """Fail closed when required Redis configuration was not supplied."""

    async def claim(self, key_id: str, nonce: str, ttl_seconds: int) -> bool:
        del key_id, nonce, ttl_seconds
        raise InternalNonceStoreUnavailableError

    async def is_available(self) -> bool:
        return False

    async def aclose(self) -> None:
        return None


class RedisInternalNonceStore:
    """Use one atomic SET NX EX operation per authenticated request."""

    def __init__(self, client: Redis | _RedisClient, key_prefix: str) -> None:
        self._client = cast(_RedisClient, client)
        self._key_prefix = key_prefix if key_prefix.endswith(":") else f"{key_prefix}:"

    async def claim(self, key_id: str, nonce: str, ttl_seconds: int) -> bool:
        if ttl_seconds < 120:
            raise ValueError("Nonce TTL must be at least 120 seconds")
        nonce_hash = hashlib.sha256(f"{key_id}:{nonce}".encode()).hexdigest()
        key = f"{self._key_prefix}internal:hmac:nonce:{nonce_hash}"
        try:
            claimed = await self._client.set(key, b"1", ex=ttl_seconds, nx=True)
        except Exception as exc:
            raise InternalNonceStoreUnavailableError from exc
        return bool(claimed)

    async def is_available(self) -> bool:
        try:
            return bool(await self._client.ping())
        except Exception:
            return False

    async def aclose(self) -> None:
        await self._client.aclose()


def build_internal_nonce_store(settings: AppSettings) -> InternalNonceStore:
    """Build a lazy Redis client; construction itself opens no network connection."""
    if settings.redis_host is None or settings.redis_key_prefix is None:
        return UnavailableInternalNonceStore()
    password = (
        settings.redis_password.get_secret_value() if settings.redis_password is not None else None
    )
    ssl_ca_certs = (
        str(settings.redis_ca_cert_path) if settings.redis_ca_cert_path is not None else None
    )
    client = Redis(
        host=settings.redis_host,
        port=settings.redis_port,
        username=settings.redis_username,
        password=password,
        ssl=settings.redis_ssl_enabled,
        ssl_ca_certs=ssl_ca_certs,
        ssl_cert_reqs="required" if settings.redis_ssl_enabled else None,
        ssl_check_hostname=settings.redis_ssl_enabled,
        protocol=2,
        decode_responses=False,
        socket_connect_timeout=1.0,
        socket_timeout=1.0,
    )
    return RedisInternalNonceStore(client, settings.redis_key_prefix)

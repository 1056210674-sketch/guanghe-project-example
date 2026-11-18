"""Minimal W3C trace parsing, continuation, and request-local correlation."""

import re
import secrets
from contextvars import ContextVar, Token
from dataclasses import dataclass

_TRACEPARENT = re.compile(
    r"^(?P<version>[0-9a-f]{2})-"
    r"(?P<trace_id>[0-9a-f]{32})-"
    r"(?P<span_id>[0-9a-f]{16})-"
    r"(?P<flags>[0-9a-f]{2})$"
)
_ZERO_TRACE_ID = "0" * 32
_ZERO_SPAN_ID = "0" * 16
_current_trace_id: ContextVar[str | None] = ContextVar("current_trace_id", default=None)


@dataclass(frozen=True, slots=True)
class TraceContext:
    traceparent: str
    trace_id: str


def parse_traceparent(value: str | None) -> TraceContext | None:
    """Accept the fixed-width W3C traceparent form used by Java and Python."""
    if value is None:
        return None
    normalized = value.strip().lower()
    matched = _TRACEPARENT.fullmatch(normalized)
    if matched is None or matched.group("version") == "ff":
        return None
    trace_id = matched.group("trace_id")
    span_id = matched.group("span_id")
    if trace_id == _ZERO_TRACE_ID or span_id == _ZERO_SPAN_ID:
        return None
    return TraceContext(traceparent=normalized, trace_id=trace_id)


def new_trace_context() -> TraceContext:
    """Create a non-zero trace and span without initializing an OTel platform."""
    trace_id = _random_nonzero_hex(16)
    span_id = _random_nonzero_hex(8)
    return TraceContext(traceparent=f"00-{trace_id}-{span_id}-01", trace_id=trace_id)


def resolve_trace_context(value: str | None) -> TraceContext:
    """Continue a valid inbound context verbatim, otherwise create a safe one."""
    return parse_traceparent(value) or new_trace_context()


def child_trace_context(value: str | None = None) -> TraceContext:
    """Create a new child span while preserving an inbound or request-local trace ID."""
    from app.observability.tracing import current_otel_traceparent

    active = parse_traceparent(current_otel_traceparent())
    if active is not None:
        return active
    parent = parse_traceparent(value)
    trace_id = parent.trace_id if parent is not None else current_trace_id()
    if trace_id is None:
        trace_id = new_trace_context().trace_id
    return TraceContext(
        traceparent=f"00-{trace_id}-{_random_nonzero_hex(8)}-01",
        trace_id=trace_id,
    )


def current_trace_id() -> str | None:
    from app.observability.tracing import current_otel_trace_id

    return current_otel_trace_id() or _current_trace_id.get()


def current_span_id() -> str | None:
    from app.observability.tracing import current_otel_span_id

    return current_otel_span_id()


def set_current_trace_id(trace_id: str) -> Token[str | None]:
    return _current_trace_id.set(trace_id)


def reset_current_trace_id(token: Token[str | None]) -> None:
    _current_trace_id.reset(token)


def _random_nonzero_hex(byte_count: int) -> str:
    while True:
        value = secrets.token_hex(byte_count)
        if any(character != "0" for character in value):
            return value

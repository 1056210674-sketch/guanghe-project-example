"""Test-only M1 bridge that exercises the Python-to-Java HMAC client."""

import logging

from fastapi import APIRouter, Request, status
from fastapi.responses import JSONResponse

from app.integration.java_internal import (
    JavaInternalClientError,
    JavaInternalClientProtocol,
    JavaInternalErrorCode,
)

router = APIRouter(tags=["internal-m1-probe"])
_logger = logging.getLogger(__name__)


@router.post(
    "/internal/v1/test/java-reverse-call",
    include_in_schema=False,
    response_model=None,
)
async def java_reverse_call_probe(request: Request) -> dict[str, object] | JSONResponse:
    """Continue the inbound trace through Python to Java's existing reverse probe."""
    if request.app.state.runtime.settings.environment == "production":
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content={"detail": "Not found"})
    client: JavaInternalClientProtocol = request.app.state.java_internal_client
    traceparent = getattr(request.state, "traceparent", None)
    trace_id = getattr(request.state, "trace_id", "unavailable")
    try:
        result = await client.reverse_call(traceparent)
    except JavaInternalClientError as exc:
        _logger.warning("java_reverse_call_probe_failed code=%s", exc.code.value)
        response_status = _error_status(exc.code)
        return JSONResponse(
            status_code=response_status,
            content={
                "code": exc.code.value,
                "message": "Java internal reverse call failed",
                "details": {},
                "traceId": trace_id,
            },
        )
    return {
        "accepted": result.accepted,
        "callerKeyId": result.caller_key_id,
        "traceId": result.trace_id,
    }


def _error_status(code: JavaInternalErrorCode) -> int:
    if code is JavaInternalErrorCode.TIMEOUT:
        return status.HTTP_504_GATEWAY_TIMEOUT
    if code in {
        JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE,
        JavaInternalErrorCode.CONNECTION_FAILED,
    }:
        return status.HTTP_503_SERVICE_UNAVAILABLE
    return status.HTTP_502_BAD_GATEWAY

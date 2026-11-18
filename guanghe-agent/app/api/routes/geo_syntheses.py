"""Signed recovery of GEO interpretation from existing evidence, with no sampling."""

import asyncio
from collections.abc import Callable, Coroutine
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from fastapi.routing import APIRoute

from app.api.models import ApiModel, InternalErrorResponse
from app.geo.recovery import (
    GeoSynthesisRecoveryRequest,
    GeoSynthesisRecoveryResponse,
    regenerate_synthesis,
)
from app.geo.runtime import GeoSynthesisError
from app.harness.runtime import HarnessViolation
from app.model_gateway.gateway import ModelGateway, ModelGatewayError


class GeoSynthesisRecoveryErrorResponse(ApiModel):
    detail: InternalErrorResponse


class _SafeEvidenceRoute(APIRoute):
    def get_route_handler(self) -> Callable[[Request], Coroutine[Any, Any, Response]]:
        handler = super().get_route_handler()

        async def safe_handler(request: Request) -> Response:
            try:
                return await handler(request)
            except RequestValidationError:
                # Never echo answer text, citation URLs, or whole evidence on a 422.
                return JSONResponse(
                    status_code=422,
                    content={
                        "detail": {
                            "code": "GEO_SYNTHESIS_EVIDENCE_INVALID",
                            "message": "Invalid saved evidence",
                        }
                    },
                )

        return safe_handler


router = APIRouter(prefix="/internal/v1/geo", tags=["geo"], route_class=_SafeEvidenceRoute)


@router.post(
    "/syntheses",
    response_model=GeoSynthesisRecoveryResponse,
    operation_id="regenerateGeoSynthesis",
    responses={
        401: {
            "description": "Internal HMAC authentication failed or nonce replayed.",
            "model": InternalErrorResponse,
        },
        422: {
            "description": "Invalid or empty authorized saved evidence.",
            "model": GeoSynthesisRecoveryErrorResponse,
        },
        429: {
            "description": "Bounded analysis capacity full.",
            "model": GeoSynthesisRecoveryErrorResponse,
        },
        503: {
            "description": "Model unavailable or structured synthesis failed; no fallback success.",
            "model": GeoSynthesisRecoveryErrorResponse | InternalErrorResponse,
        },
        504: {
            "description": "The at-most-60-second analysis deadline expired.",
            "model": GeoSynthesisRecoveryErrorResponse,
        },
    },
)
async def regenerate(
    payload: GeoSynthesisRecoveryRequest, request: Request
) -> GeoSynthesisRecoveryResponse:
    received_at = datetime.now(UTC)
    gateway: ModelGateway | None = request.app.state.geo_visual_gateway
    if gateway is None:
        raise HTTPException(
            503,
            detail={"code": "GEO_SYNTHESIS_MODEL_UNAVAILABLE", "message": "Analysis unavailable"},
        )
    # Share the existing bounded read-only GEO model capacity, not a second client or queue.
    semaphore: asyncio.Semaphore = request.app.state.geo_visual_semaphore
    try:
        await asyncio.wait_for(semaphore.acquire(), timeout=0.5)
    except TimeoutError as exc:
        raise HTTPException(
            429, detail={"code": "GEO_SYNTHESIS_CAPACITY_FULL", "message": "Analysis busy"}
        ) from exc
    try:
        return await regenerate_synthesis(
            payload,
            gateway,
            received_at=received_at,
            execution_prompt=request.app.state.geo_synthesis_prompt,
        )
    except (GeoSynthesisError, ModelGatewayError, HarnessViolation) as exc:
        code = exc.code
        status_code = 504 if "DEADLINE" in code else 503
        raise HTTPException(
            status_code, detail={"code": code, "message": "Analysis unavailable"}
        ) from exc
    finally:
        semaphore.release()

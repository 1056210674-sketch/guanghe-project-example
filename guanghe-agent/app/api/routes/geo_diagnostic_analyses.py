"""Signed read-only analysis boundary; sampling and PDF delivery remain in Java."""

import asyncio
from datetime import UTC, datetime
from typing import ClassVar

from fastapi import APIRouter, HTTPException, Request

from app.api.models import InternalErrorResponse
from app.api.routes.geo_diagnostic_preparations import (
    DiagnosticPreparationErrorResponse,
    SafeDiagnosticRoute,
)
from app.geo.diagnostic_analysis import (
    DiagnosticAnalysisError,
    DiagnosticAnalysisRequest,
    DiagnosticAnalysisResponse,
    analyze_diagnostic,
)
from app.model_gateway.gateway import ModelGateway


class _SafeAnalysisRoute(SafeDiagnosticRoute):
    validation_error_code: ClassVar[str] = "GEO_DIAGNOSTIC_ANALYSIS_INPUT_INVALID"


router = APIRouter(prefix="/internal/v1/geo", tags=["geo"], route_class=_SafeAnalysisRoute)


@router.post(
    "/diagnostic-analyses",
    response_model=DiagnosticAnalysisResponse,
    operation_id="analyzeGeoDiagnostic",
    responses={
        401: {"description": "Internal HMAC invalid or replayed.", "model": InternalErrorResponse},
        422: {
            "description": "Invalid or over-budget frozen evidence; private text never echoed.",
            "model": DiagnosticPreparationErrorResponse,
        },
        429: {
            "description": "Existing bounded GEO analysis capacity is full.",
            "model": DiagnosticPreparationErrorResponse,
        },
        503: {
            "description": "Model or evidence validation failed; no fake report.",
            "model": DiagnosticPreparationErrorResponse | InternalErrorResponse,
        },
        504: {
            "description": "At-most-120-second analysis expired; no sampling or automatic replay.",
            "model": DiagnosticPreparationErrorResponse,
        },
    },
)
async def analyze(
    payload: DiagnosticAnalysisRequest, request: Request
) -> DiagnosticAnalysisResponse:
    received_at = datetime.now(UTC)
    gateway: ModelGateway | None = request.app.state.geo_visual_gateway
    if gateway is None:
        raise HTTPException(
            503,
            detail={
                "code": "GEO_DIAGNOSTIC_ANALYSIS_MODEL_UNAVAILABLE",
                "message": "Analysis unavailable",
            },
        )
    semaphore: asyncio.Semaphore = request.app.state.geo_visual_semaphore
    try:
        await asyncio.wait_for(semaphore.acquire(), timeout=0.5)
    except TimeoutError as exc:
        raise HTTPException(
            429,
            detail={
                "code": "GEO_DIAGNOSTIC_ANALYSIS_CAPACITY_FULL",
                "message": "Analysis busy",
            },
        ) from exc
    try:
        return await analyze_diagnostic(payload, gateway, received_at=received_at)
    except DiagnosticAnalysisError as exc:
        status = 422 if "INPUT_BUDGET" in exc.code else 504 if "DEADLINE" in exc.code else 503
        raise HTTPException(
            status, detail={"code": exc.code, "message": "Analysis unavailable"}
        ) from exc
    finally:
        semaphore.release()

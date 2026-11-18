"""Signed, bounded period-report narrative endpoint; no tasks or database access."""

import asyncio
from collections.abc import Callable, Coroutine
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from fastapi.routing import APIRoute

from app.api.models import ApiModel, InternalErrorResponse
from app.geo.period_report_summary import (
    GeoPeriodReportSummaryError,
    GeoPeriodReportSummaryRequest,
    GeoPeriodReportSummaryResponse,
    summarize_period_report,
)
from app.model_gateway.gateway import ModelGateway


class GeoPeriodReportSummaryErrorResponse(ApiModel):
    detail: InternalErrorResponse


class _SafePeriodEvidenceRoute(APIRoute):
    def get_route_handler(self) -> Callable[[Request], Coroutine[Any, Any, Response]]:
        handler = super().get_route_handler()

        async def safe_handler(request: Request) -> Response:
            try:
                return await handler(request)
            except RequestValidationError:
                return JSONResponse(
                    status_code=422,
                    content={
                        "detail": {
                            "code": "GEO_PERIOD_REPORT_EVIDENCE_INVALID",
                            "message": "Invalid period report evidence",
                        }
                    },
                )

        return safe_handler


router = APIRouter(prefix="/internal/v1/geo", tags=["geo"], route_class=_SafePeriodEvidenceRoute)


@router.post(
    "/period-report-summaries",
    response_model=GeoPeriodReportSummaryResponse,
    operation_id="summarizeGeoPeriodReport",
    responses={
        401: {"description": "Internal HMAC invalid or replayed.", "model": InternalErrorResponse},
        422: {
            "description": "Invalid bounded Java-frozen evidence; source contents never echoed.",
            "model": GeoPeriodReportSummaryErrorResponse,
        },
        429: {
            "description": "Existing bounded GEO analysis capacity is full.",
            "model": GeoPeriodReportSummaryErrorResponse,
        },
        503: {
            "description": "DeepSeek unavailable or narrative invalid; no fallback success.",
            "model": GeoPeriodReportSummaryErrorResponse | InternalErrorResponse,
        },
        504: {
            "description": "At-most-60-second deadline expired; never retried by this route.",
            "model": GeoPeriodReportSummaryErrorResponse,
        },
    },
)
async def summarize(
    payload: GeoPeriodReportSummaryRequest, request: Request
) -> GeoPeriodReportSummaryResponse:
    received_at = datetime.now(UTC)
    gateway: ModelGateway | None = request.app.state.geo_visual_gateway
    if gateway is None:
        raise HTTPException(
            503,
            detail={
                "code": "GEO_PERIOD_REPORT_MODEL_UNAVAILABLE",
                "message": "Summary unavailable",
            },
        )
    semaphore: asyncio.Semaphore = request.app.state.geo_visual_semaphore
    try:
        await asyncio.wait_for(semaphore.acquire(), timeout=0.5)
    except TimeoutError as exc:
        raise HTTPException(
            429, detail={"code": "GEO_PERIOD_REPORT_CAPACITY_FULL", "message": "Summary busy"}
        ) from exc
    try:
        return await summarize_period_report(payload, gateway, received_at=received_at)
    except GeoPeriodReportSummaryError as exc:
        raise HTTPException(
            504 if "DEADLINE" in exc.code else 503,
            detail={"code": exc.code, "message": "Summary unavailable"},
        ) from exc
    finally:
        semaphore.release()

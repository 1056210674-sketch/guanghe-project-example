"""Signed card preparation boundary; Java owns drafts, versions and jobs."""

import asyncio
from collections.abc import Callable, Coroutine
from datetime import UTC, datetime
from typing import Any, ClassVar

from fastapi import APIRouter, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from fastapi.routing import APIRoute

from app.api.models import ApiModel, InternalErrorResponse
from app.geo.diagnostic_preparation import (
    DiagnosticPreparationError,
    DiagnosticPreparationRequest,
    DiagnosticPreparationResponse,
    prepare_diagnostic,
)
from app.geo.providers import BochaSearchAdapter
from app.model_gateway.gateway import ModelGateway


class DiagnosticPreparationErrorResponse(ApiModel):
    detail: InternalErrorResponse


class SafeDiagnosticRoute(APIRoute):
    validation_error_code: ClassVar[str] = "GEO_DIAGNOSTIC_INPUT_INVALID"

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
                            "code": self.validation_error_code,
                            "message": "Invalid bounded diagnostic input",
                        }
                    },
                )

        return safe_handler


router = APIRouter(prefix="/internal/v1/geo", tags=["geo"], route_class=SafeDiagnosticRoute)


@router.post(
    "/diagnostic-preparations",
    response_model=DiagnosticPreparationResponse,
    operation_id="prepareGeoDiagnostic",
    responses={
        401: {"description": "Internal HMAC invalid or replayed.", "model": InternalErrorResponse},
        422: {
            "description": "Invalid frozen diagnostic input; source text never echoed.",
            "model": DiagnosticPreparationErrorResponse,
        },
        429: {
            "description": "Existing bounded GEO analysis capacity is full.",
            "model": DiagnosticPreparationErrorResponse,
        },
        503: {
            "description": "Search/model unavailable or grounding invalid; no fake success.",
            "model": DiagnosticPreparationErrorResponse | InternalErrorResponse,
        },
        504: {
            "description": "At-most-60-second deadline expired; no automatic job replay.",
            "model": DiagnosticPreparationErrorResponse,
        },
    },
)
async def prepare(
    payload: DiagnosticPreparationRequest, request: Request
) -> DiagnosticPreparationResponse:
    received_at = datetime.now(UTC)
    gateway: ModelGateway | None = request.app.state.geo_visual_gateway
    search: BochaSearchAdapter | None = request.app.state.geo_diagnostic_search
    if gateway is None:
        raise HTTPException(
            503,
            detail={
                "code": "GEO_DIAGNOSTIC_MODEL_UNAVAILABLE",
                "message": "Preparation unavailable",
            },
        )
    semaphore: asyncio.Semaphore = request.app.state.geo_visual_semaphore
    try:
        await asyncio.wait_for(semaphore.acquire(), timeout=0.5)
    except TimeoutError as exc:
        raise HTTPException(
            429,
            detail={"code": "GEO_DIAGNOSTIC_CAPACITY_FULL", "message": "Preparation busy"},
        ) from exc
    try:
        return await prepare_diagnostic(
            payload,
            gateway,
            search,
            received_at=received_at,
            traceparent=request.headers.get("traceparent"),
        )
    except DiagnosticPreparationError as exc:
        input_budget_exceeded = exc.code == "GEO_DIAGNOSTIC_INPUT_BUDGET_EXCEEDED"
        raise HTTPException(
            422 if input_budget_exceeded else 504 if "DEADLINE" in exc.code else 503,
            detail={
                "code": exc.code,
                "message": "品牌资料过长，请缩短资料后重试。"
                if input_budget_exceeded
                else "Preparation unavailable",
            },
        ) from exc
    finally:
        semaphore.release()

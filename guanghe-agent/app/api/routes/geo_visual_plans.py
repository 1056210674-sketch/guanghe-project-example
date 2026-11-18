"""Signed Java-to-Agent GEO visual planning boundary."""

import asyncio

from fastapi import APIRouter, HTTPException, Request, status

from app.creative.geo_visual_plan import (
    GeoVisualPlan,
    GeoVisualPlanRequest,
    GeoVisualPlanUnavailable,
    create_geo_visual_plan,
)
from app.model_gateway.gateway import ModelGateway

router = APIRouter(prefix="/internal/v1/creative", tags=["creative"])


@router.post(
    "/geo-plans",
    response_model=GeoVisualPlan,
    operation_id="createGeoVisualPlan",
    responses={
        status.HTTP_401_UNAUTHORIZED: {
            "description": "Internal HMAC authentication failed or the nonce was replayed."
        },
        status.HTTP_429_TOO_MANY_REQUESTS: {
            "description": "The bounded GEO planning capacity is full."
        },
        status.HTTP_503_SERVICE_UNAVAILABLE: {
            "description": (
                "The structured DeepSeek planner is unavailable or returned an invalid plan."
            )
        },
    },
)
async def create_plan(payload: GeoVisualPlanRequest, request: Request) -> GeoVisualPlan:
    gateway: ModelGateway | None = request.app.state.geo_visual_gateway
    if gateway is None:
        raise HTTPException(
            status_code=503,
            detail={
                "code": "GEO_VISUAL_PLAN_MODEL_UNAVAILABLE",
                "message": "Creative planning is unavailable",
            },
        )
    semaphore: asyncio.Semaphore = request.app.state.geo_visual_semaphore
    try:
        await asyncio.wait_for(semaphore.acquire(), timeout=0.5)
    except TimeoutError as exc:
        raise HTTPException(
            status_code=429,
            detail={
                "code": "GEO_VISUAL_PLAN_CAPACITY_FULL",
                "message": "Creative planning is busy",
            },
        ) from exc
    try:
        return await create_geo_visual_plan(payload, gateway)
    except GeoVisualPlanUnavailable as exc:
        raise HTTPException(
            status_code=503,
            detail={"code": str(exc), "message": "Creative planning is unavailable"},
        ) from exc
    finally:
        semaphore.release()

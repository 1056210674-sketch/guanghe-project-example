"""Internal Chat Run HTTP and SSE routes."""

import json
from collections.abc import AsyncIterator
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Header, HTTPException, Path, Request, status
from fastapi.responses import StreamingResponse

from app.agent.models import (
    ChatRunAccepted,
    ChatRunRequest,
    ChatRunSnapshot,
    StopRunResponse,
)
from app.agent.run_manager import (
    AuthorizedContextUnavailableError,
    ChatRunManager,
    DuplicateRunError,
    GatewayUnavailableError,
    RunCapacityError,
    RunNotFoundError,
)

router = APIRouter(prefix="/internal/v1/chat-runs", tags=["chat-runs"])


def _manager(request: Request) -> ChatRunManager:
    manager: ChatRunManager | None = request.app.state.run_manager
    if manager is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "AGENT_BUNDLE_NOT_READY", "message": "Agent runtime is not ready"},
        )
    return manager


def _not_found() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_404_NOT_FOUND,
        detail={"code": "RUN_NOT_FOUND", "message": "Chat Run was not found"},
    )


@router.post(
    "",
    response_model=ChatRunAccepted,
    status_code=status.HTTP_202_ACCEPTED,
    operation_id="startChatRun",
    responses={
        status.HTTP_401_UNAUTHORIZED: {
            "description": "Internal authentication failed or the nonce was replayed."
        },
        status.HTTP_409_CONFLICT: {
            "description": "A Chat Run with the requested runId already exists."
        },
        status.HTTP_503_SERVICE_UNAVAILABLE: {
            "description": (
                "Internal authentication infrastructure, the Agent runtime, "
                "or a required dependency is unavailable."
            )
        },
    },
)
async def start_chat_run(payload: ChatRunRequest, request: Request) -> ChatRunAccepted:
    try:
        snapshot = await _manager(request).start(
            payload,
            traceparent=getattr(request.state, "traceparent", None),
        )
    except DuplicateRunError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"code": "RUN_ALREADY_EXISTS", "message": str(exc)},
        ) from exc
    except RunCapacityError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "RUN_CAPACITY_FULL", "message": str(exc)},
        ) from exc
    except GatewayUnavailableError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": exc.code, "message": "Model gateway is unavailable"},
        ) from exc
    except AuthorizedContextUnavailableError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": exc.code, "message": "Authorized Run context is unavailable"},
        ) from exc
    return ChatRunAccepted(
        run_id=snapshot.run_id,
        status=snapshot.status,
        events_url=f"/internal/v1/chat-runs/{snapshot.run_id}/events",
    )


@router.get(
    "/{runId}",
    response_model=ChatRunSnapshot,
    operation_id="getChatRunSnapshot",
    responses={
        status.HTTP_401_UNAUTHORIZED: {
            "description": "Internal authentication failed or the nonce was replayed."
        },
        status.HTTP_404_NOT_FOUND: {"description": "The Chat Run was not found."},
        status.HTTP_503_SERVICE_UNAVAILABLE: {
            "description": (
                "Internal authentication infrastructure or the Agent runtime is unavailable."
            )
        },
    },
)
async def get_chat_run(
    run_id: Annotated[UUID, Path(alias="runId")], request: Request
) -> ChatRunSnapshot:
    try:
        return _manager(request).get(run_id)
    except RunNotFoundError as exc:
        raise _not_found() from exc


@router.get(
    "/{runId}/events",
    response_class=StreamingResponse,
    operation_id="streamChatRunEvents",
    responses={
        status.HTTP_200_OK: {
            "description": "Server-sent Chat Run events.",
            "content": {"text/event-stream": {"schema": {"type": "string"}}},
        },
        status.HTTP_400_BAD_REQUEST: {
            "description": "Last-Event-ID is not a valid numeric sequence."
        },
        status.HTTP_401_UNAUTHORIZED: {
            "description": "Internal authentication failed or the nonce was replayed."
        },
        status.HTTP_404_NOT_FOUND: {"description": "The Chat Run was not found."},
        status.HTTP_503_SERVICE_UNAVAILABLE: {
            "description": (
                "Internal authentication infrastructure or the Agent runtime is unavailable."
            )
        },
    },
)
async def stream_chat_run_events(
    run_id: Annotated[UUID, Path(alias="runId")],
    request: Request,
    last_event_id: Annotated[str | None, Header(alias="Last-Event-ID")] = None,
) -> StreamingResponse:
    manager = _manager(request)
    try:
        manager.get(run_id)
    except RunNotFoundError as exc:
        raise _not_found() from exc
    after_sequence = 0
    if last_event_id:
        try:
            after_sequence = max(0, int(last_event_id))
        except ValueError as exc:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail={
                    "code": "INVALID_LAST_EVENT_ID",
                    "message": "Last-Event-ID must be numeric",
                },
            ) from exc

    try:
        bound_events = manager.events(run_id, after_sequence)
    except RunNotFoundError as exc:
        raise _not_found() from exc

    async def event_stream() -> AsyncIterator[str]:
        async for event in bound_events:
            encoded = event.model_dump(mode="json", by_alias=True)
            yield (
                f"id: {event.sequence}\n"
                f"event: {event.event_type}\n"
                f"data: {json.dumps(encoded, ensure_ascii=False, separators=(',', ':'))}\n\n"
            )

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


@router.post(
    "/{runId}/stop",
    response_model=StopRunResponse,
    status_code=202,
    operation_id="stopChatRun",
    responses={
        status.HTTP_401_UNAUTHORIZED: {
            "description": "Internal authentication failed or the nonce was replayed."
        },
        status.HTTP_404_NOT_FOUND: {"description": "The Chat Run was not found."},
        status.HTTP_503_SERVICE_UNAVAILABLE: {
            "description": (
                "Internal authentication infrastructure or the Agent runtime is unavailable."
            )
        },
    },
)
async def stop_chat_run(
    run_id: Annotated[UUID, Path(alias="runId")], request: Request
) -> StopRunResponse:
    try:
        accepted, run_status = await _manager(request).stop(run_id)
    except RunNotFoundError as exc:
        raise _not_found() from exc
    return StopRunResponse(run_id=run_id, accepted=accepted, status=run_status)

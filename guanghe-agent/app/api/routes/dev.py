"""Local-only conveniences for exercising the Agent without Java."""

from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

from fastapi import APIRouter, HTTPException, Request, status
from fastapi.responses import FileResponse
from pydantic import Field

from app.agent.models import (
    AgentModel,
    ChatMessage,
    ChatRunAccepted,
    ChatRunRequest,
    RunBudget,
    TrustedState,
)
from app.agent.run_manager import ChatRunManager, GatewayUnavailableError, RunCapacityError

router = APIRouter(tags=["local-development"])


class DevRunRequest(AgentModel):
    content: str = Field(min_length=1, max_length=16_000)
    selected_space_id: str | None = Field(default=None, max_length=128)
    selected_product_id: str | None = Field(default=None, max_length=128)


def _ensure_enabled(request: Request) -> None:
    if not request.app.state.runtime.settings.dev_console_enabled:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")


def _manager(request: Request) -> ChatRunManager:
    manager: ChatRunManager | None = request.app.state.run_manager
    if manager is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "AGENT_BUNDLE_NOT_READY", "message": "Agent runtime is not ready"},
        )
    return manager


@router.get("/dev/agent", include_in_schema=False)
async def agent_console(request: Request) -> FileResponse:
    _ensure_enabled(request)
    return FileResponse(request.app.state.dev_console_path, media_type="text/html")


@router.post(
    "/dev/v1/runs",
    response_model=ChatRunAccepted,
    status_code=202,
    include_in_schema=False,
)
async def start_dev_run(payload: DevRunRequest, request: Request) -> ChatRunAccepted:
    _ensure_enabled(request)
    run_id = uuid4()
    conversation_id = f"local-{uuid4()}"
    chat_request = ChatRunRequest(
        run_id=run_id,
        conversation_id=conversation_id,
        user_context_ref=f"local-only-{uuid4()}",
        message=ChatMessage(message_id=str(uuid4()), content=payload.content),
        trusted_state=TrustedState(
            selected_space_id=payload.selected_space_id,
            selected_product_id=payload.selected_product_id,
        ),
        budget=RunBudget(deadline_at=datetime.now(UTC) + timedelta(minutes=5)),
    )
    try:
        snapshot = await _manager(request).start(chat_request)
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
    return ChatRunAccepted(
        run_id=UUID(str(snapshot.run_id)),
        status=snapshot.status,
        events_url=f"/internal/v1/chat-runs/{snapshot.run_id}/events",
    )

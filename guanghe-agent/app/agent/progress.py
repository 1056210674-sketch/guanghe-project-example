"""Safe, code-owned progress notifications for one Chat Run."""

from collections.abc import Awaitable, Callable

from app.agent.models import RunProgressStage, RunProgressStatus

RunProgressHandler = Callable[
    [RunProgressStage, RunProgressStatus, int | None, int | None],
    Awaitable[None],
]


async def notify_progress(
    handler: RunProgressHandler | None,
    stage: RunProgressStage,
    status: RunProgressStatus,
    *,
    current: int | None = None,
    total: int | None = None,
) -> None:
    """Notify the bounded runtime without accepting model-authored labels or text."""

    if handler is not None:
        await handler(stage, status, current, total)


__all__ = ["RunProgressHandler", "notify_progress"]

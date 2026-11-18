"""Command-line entry point for the independent Worker process."""

import uvicorn

from app.common.settings import get_settings


def run_worker() -> None:
    """Run only the Worker ASGI app; Uvicorn owns SIGTERM and lifespan draining."""
    settings = get_settings()
    uvicorn.run(
        "app.workers.api:app",
        host=settings.worker_bind_host,
        port=settings.worker_bind_port,
        log_config=None,
    )

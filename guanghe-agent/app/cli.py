"""Local command-line entry points."""

import uvicorn

from app.common.settings import get_settings


def run_api() -> None:
    """Run the internal Agent API on the configured loopback address."""
    settings = get_settings()
    uvicorn.run(
        "app.api.main:app",
        host=settings.bind_host,
        port=settings.bind_port,
        log_config=None,
    )

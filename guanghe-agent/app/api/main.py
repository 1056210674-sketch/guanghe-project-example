"""Primary ASGI entry point for the Agent API process."""

from app.api.application import create_app

app = create_app()

__all__ = ["app"]

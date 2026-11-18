"""Backward-compatible ASGI entry point."""

from app.api.main import app

__all__ = ["app"]

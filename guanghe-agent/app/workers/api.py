"""ASGI application import target for the independent Worker process."""

from app.workers.application import create_worker_app

app = create_worker_app()

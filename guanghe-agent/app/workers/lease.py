"""Shared fail-closed execution lease guard for long-running Workers."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable, Coroutine
from typing import Any, TypeVar

from app.workers.control_plane import ControlPlaneRejected, ControlPlaneUnavailable

_T = TypeVar("_T")
_LeaseError = ControlPlaneRejected | ControlPlaneUnavailable


class ExecutionLeaseMonitor:
    """Renew a Java lease and cancel local work as soon as authority is lost."""

    def __init__(
        self,
        heartbeat: Callable[[], Awaitable[None]],
        *,
        interval_seconds: float,
    ) -> None:
        if interval_seconds <= 0:
            raise ValueError("Execution lease heartbeat interval must be positive")
        self._heartbeat = heartbeat
        self._interval_seconds = interval_seconds
        self._failure: asyncio.Future[_LeaseError] | None = None
        self._task: asyncio.Task[None] | None = None

    def start(self, *, name: str) -> None:
        if self._task is not None:
            raise RuntimeError("Execution lease monitor already started")
        self._failure = asyncio.get_running_loop().create_future()
        self._task = asyncio.create_task(self._run(), name=name)

    async def run(
        self,
        operation_factory: Callable[[], Coroutine[Any, Any, _T]],
        *,
        name: str,
    ) -> _T:
        """Run one expensive operation while the Java lease remains valid."""

        failure = self._failure
        if self._task is None or failure is None:
            raise RuntimeError("Execution lease monitor is not started")
        self.raise_if_lost()
        operation_task = asyncio.create_task(operation_factory(), name=name)
        try:
            done, _ = await asyncio.wait(
                (operation_task, failure),
                return_when=asyncio.FIRST_COMPLETED,
            )
            if failure in done:
                raise failure.result()
            result = await operation_task
            # Prefer fencing when the operation and a failed heartbeat race to finish.
            self.raise_if_lost()
            return result
        except BaseException:
            if not operation_task.done():
                operation_task.cancel()
            await asyncio.gather(operation_task, return_exceptions=True)
            raise

    def raise_if_lost(self) -> None:
        failure = self._failure
        if failure is not None and failure.done() and not failure.cancelled():
            raise failure.result()

    async def close(self) -> None:
        if self._task is not None:
            self._task.cancel()
            await asyncio.gather(self._task, return_exceptions=True)
            self._task = None
        if self._failure is not None and not self._failure.done():
            self._failure.cancel()

    async def _run(self) -> None:
        while True:
            await asyncio.sleep(self._interval_seconds)
            try:
                await self._heartbeat()
            except (ControlPlaneUnavailable, ControlPlaneRejected) as exc:
                failure = self._failure
                if failure is not None and not failure.done():
                    failure.set_result(exc)
                return
            except Exception:
                # A malformed/unexpected heartbeat response must never silently
                # disable fencing while paid work continues.
                failure = self._failure
                if failure is not None and not failure.done():
                    failure.set_result(ControlPlaneUnavailable())
                return


__all__ = ["ExecutionLeaseMonitor"]

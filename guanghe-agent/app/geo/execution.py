"""Request admission for the existing GEO worker, not a durable task scheduler.

At most 210 authorized unit coroutines wait for slots. Platform admission happens
before task admission; backoff releases both. Nothing here replaces Java budgets,
leases or persisted observations. Limits are per process, not shared-account QPS.
"""

from __future__ import annotations

import asyncio
from collections.abc import Generator
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from datetime import UTC, datetime
from time import monotonic

from app.harness.runtime import record_provider_call


@dataclass(slots=True)
class GeoExecutionSlot:
    deadline_at: datetime
    task_slots: asyncio.Semaphore | None = None
    platform_slots: asyncio.Semaphore | None = None
    calls: int = 0
    admission_wait_ms: int = 0
    _task_acquired: bool = False
    _platform_acquired: bool = False

    async def acquire(self) -> None:
        started = monotonic()
        try:
            if self.platform_slots is not None:
                await self.platform_slots.acquire()
                self._platform_acquired = True
            if self.task_slots is not None:
                await self.task_slots.acquire()
                self._task_acquired = True
        except BaseException:
            self.release()
            raise
        finally:
            self.admission_wait_ms += round((monotonic() - started) * 1000)

    def release(self) -> None:
        if self._task_acquired and self.task_slots is not None:
            self._task_acquired = False
            self.task_slots.release()
        if self._platform_acquired and self.platform_slots is not None:
            self._platform_acquired = False
            self.platform_slots.release()


_CURRENT_SLOT: ContextVar[GeoExecutionSlot | None] = ContextVar("geo_execution_slot", default=None)


@contextmanager
def bind_geo_slot(slot: GeoExecutionSlot) -> Generator[None]:
    token = _CURRENT_SLOT.set(slot)
    try:
        yield
    finally:
        slot.release()
        _CURRENT_SLOT.reset(token)


def record_geo_provider_call() -> None:
    # Count only after the existing authoritative call boundary admits it.
    record_provider_call()
    slot = _CURRENT_SLOT.get()
    if slot is not None:
        slot.calls += 1


def geo_remaining_seconds() -> float | None:
    slot = _CURRENT_SLOT.get()
    if slot is None:
        return None
    return (slot.deadline_at - datetime.now(UTC)).total_seconds()


async def wait_geo_retry(delay: float) -> None:
    slot = _CURRENT_SLOT.get()
    if slot is not None:
        slot.release()
    # Cancellation while sleeping must not reacquire a slot or send a retry.
    await asyncio.sleep(delay)
    if slot is not None:
        await slot.acquire()

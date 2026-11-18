"""Execution lease fencing tests; no network or Provider calls."""

from __future__ import annotations

import asyncio

import pytest

from app.workers.control_plane import ControlPlaneRejected, ControlPlaneUnavailable
from app.workers.lease import ExecutionLeaseMonitor


def test_lease_loss_cancels_in_flight_operation_and_preserves_error() -> None:
    async def scenario() -> tuple[bool, int]:
        heartbeat_calls = 0
        cancelled = False

        async def heartbeat() -> None:
            nonlocal heartbeat_calls
            heartbeat_calls += 1
            raise ControlPlaneRejected("JAVA_EXECUTION_LEASE_LOST")

        async def operation() -> None:
            nonlocal cancelled
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                cancelled = True
                raise

        monitor = ExecutionLeaseMonitor(heartbeat, interval_seconds=0.001)
        monitor.start(name="test-lease-heartbeat")
        try:
            with pytest.raises(ControlPlaneRejected, match="JAVA_EXECUTION_LEASE_LOST"):
                await monitor.run(operation, name="test-lease-operation")
        finally:
            await monitor.close()
        return cancelled, heartbeat_calls

    cancelled, heartbeat_calls = asyncio.run(scenario())
    assert cancelled is True
    assert heartbeat_calls == 1


def test_operation_factory_is_not_called_when_lease_was_already_lost() -> None:
    async def scenario() -> bool:
        operation_called = False

        async def heartbeat() -> None:
            raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_TIMEOUT")

        async def operation() -> None:
            nonlocal operation_called
            operation_called = True

        monitor = ExecutionLeaseMonitor(heartbeat, interval_seconds=0.001)
        monitor.start(name="test-lost-before-operation")
        try:
            for _ in range(100):
                await asyncio.sleep(0.001)
                try:
                    monitor.raise_if_lost()
                except ControlPlaneUnavailable:
                    break
            else:  # pragma: no cover - bounded scheduling guard
                raise AssertionError("lease monitor did not observe the failed heartbeat")

            with pytest.raises(ControlPlaneUnavailable, match="JAVA_CONTROL_PLANE_TIMEOUT"):
                await monitor.run(operation, name="must-not-start")
        finally:
            await monitor.close()
        return operation_called

    assert asyncio.run(scenario()) is False


def test_unexpected_heartbeat_error_fails_closed_with_stable_code() -> None:
    async def scenario() -> bool:
        cancelled = False

        async def heartbeat() -> None:
            raise RuntimeError("untrusted internal detail")

        async def operation() -> None:
            nonlocal cancelled
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                cancelled = True
                raise

        monitor = ExecutionLeaseMonitor(heartbeat, interval_seconds=0.001)
        monitor.start(name="test-unexpected-heartbeat-error")
        try:
            with pytest.raises(ControlPlaneUnavailable, match="JAVA_CONTROL_PLANE_UNAVAILABLE"):
                await monitor.run(operation, name="test-unexpected-error-operation")
        finally:
            await monitor.close()
        return cancelled

    assert asyncio.run(scenario()) is True


def test_successful_operation_closes_without_an_extra_heartbeat() -> None:
    async def scenario() -> tuple[str, int]:
        heartbeat_calls = 0

        async def heartbeat() -> None:
            nonlocal heartbeat_calls
            heartbeat_calls += 1

        async def operation() -> str:
            return "done"

        monitor = ExecutionLeaseMonitor(heartbeat, interval_seconds=60)
        monitor.start(name="test-success-heartbeat")
        try:
            result = await monitor.run(operation, name="test-success-operation")
        finally:
            await monitor.close()
        return result, heartbeat_calls

    assert asyncio.run(scenario()) == ("done", 0)


def test_cancelling_guarded_run_does_not_leave_operation_running() -> None:
    async def scenario() -> bool:
        started = asyncio.Event()
        cancelled = False

        async def heartbeat() -> None:
            return None

        async def operation() -> None:
            nonlocal cancelled
            started.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                cancelled = True
                raise

        monitor = ExecutionLeaseMonitor(heartbeat, interval_seconds=60)
        monitor.start(name="test-cancel-heartbeat")
        guarded = asyncio.create_task(monitor.run(operation, name="test-cancel-operation"))
        await started.wait()
        guarded.cancel()
        try:
            with pytest.raises(asyncio.CancelledError):
                await guarded
        finally:
            await monitor.close()
        return cancelled

    assert asyncio.run(scenario()) is True

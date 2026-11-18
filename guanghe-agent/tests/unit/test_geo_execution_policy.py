"""Zero-provider-cost admission, transport deadlines and retry counterexamples."""

from __future__ import annotations

import asyncio
import json
import logging
from contextlib import suppress
from datetime import UTC, datetime, timedelta
from email.utils import format_datetime

import httpx
import pytest
from pydantic import SecretStr, ValidationError

from app.common.settings import AppSettings, GeoDoubaoPolicySettings, ProviderSettings
from app.geo.models import GeoObservation, GeoObservationStatus, GeoPlatformCode
from app.geo.providers import (
    DoubaoGeoAdapter,
    QwenGeoAdapter,
)
from app.geo.runtime import GeoProviderRegistry, GeoSampleExecutor
from app.observability.logging import JsonFormatter


def _response() -> dict[str, object]:
    return {
        "output": [
            {"type": "web_search_call"},
            {"type": "message", "content": [{"type": "output_text", "text": "回答"}]},
        ],
        "usage": {"input_tokens": 1, "output_tokens": 1},
    }


def _settings(*, retries: int = 0, read: float = 0.5) -> ProviderSettings:
    return ProviderSettings(
        credential=SecretStr("private-unit-test-token"),
        model="test-model",
        max_retries=retries,
        timeout_seconds=read,
    )


def _policy(*, total: float = 1, delay: float = 0) -> GeoDoubaoPolicySettings:
    return GeoDoubaoPolicySettings(
        request_timeout_seconds=total,
        retry_base_seconds=delay,
        retry_max_seconds=delay,
    )


async def _sample(
    executor: GeoSampleExecutor,
    *,
    question: str = "private question",
    platform: GeoPlatformCode = GeoPlatformCode.DOUBAO,
    slots: asyncio.Semaphore | None = None,
    deadline: datetime | None = None,
):
    return await executor.execute(
        platform_code=platform,
        question_id="q-policy",
        sample_no=1,
        question=question,
        max_provider_calls=2,
        deadline_at=deadline or datetime.now(UTC) + timedelta(seconds=5),
        task_slots=slots,
    )


def test_doubao_defaults_survive_nested_environment_and_preserve_explicit_override(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("GUANGHE_AGENT_GEO_DOUBAO__MODEL", "unit-test-model")
    monkeypatch.delenv("GUANGHE_AGENT_GEO_DOUBAO__TIMEOUT_SECONDS", raising=False)
    assert AppSettings().geo_doubao.timeout_seconds == 120
    monkeypatch.setenv("GUANGHE_AGENT_GEO_DOUBAO__TIMEOUT_SECONDS", "45")
    assert AppSettings().geo_doubao.timeout_seconds == 45
    assert ProviderSettings().timeout_seconds == 30
    assert GeoDoubaoPolicySettings().request_timeout_seconds == 150
    with pytest.raises(ValidationError):
        GeoDoubaoPolicySettings(retry_base_seconds=40, retry_max_seconds=30)


@pytest.mark.parametrize("status", (400, 401, 403, 404, 422))
def test_permanent_error_never_retries(status: int) -> None:
    async def scenario() -> None:
        requests = 0

        async def handler(request: httpx.Request) -> httpx.Response:
            nonlocal requests
            requests += 1
            return httpx.Response(status, text="private upstream body")

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = DoubaoGeoAdapter(_settings(retries=1), client=client, policy=_policy())
            result = await _sample(GeoSampleExecutor(GeoProviderRegistry([adapter])))
        assert result.status is GeoObservationStatus.FAILED
        assert requests == result.provider_call_count == 1

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "kind,phase",
    (
        (httpx.ConnectTimeout, "connect"),
        (httpx.ReadTimeout, "read"),
        (httpx.WriteTimeout, "write"),
        (httpx.PoolTimeout, "pool"),
    ),
)
def test_phase_timeout_is_counted_and_logged_without_payload(
    kind: type[httpx.TimeoutException],
    phase: str,
    caplog: pytest.LogCaptureFixture,
) -> None:
    async def scenario() -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            raise kind("private exception body", request=request)

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = DoubaoGeoAdapter(_settings(retries=1), client=client, policy=_policy())
            result = await _sample(GeoSampleExecutor(GeoProviderRegistry([adapter])))
        assert result.stable_error_code == "GEO_PROVIDER_TIMEOUT"
        assert result.provider_call_count == 2

    with caplog.at_level(logging.INFO, logger="app.geo.providers"):
        asyncio.run(scenario())
    records = [record for record in caplog.records if record.msg == "geo_provider_http_attempt"]
    assert [record.__dict__["timeoutPhase"] for record in records] == [phase, phase]
    # Test the production formatter too: LogRecord extras alone may be dropped.
    rendered = [json.loads(JsonFormatter().format(record)) for record in records]
    assert all(record["timeoutPhase"] == phase for record in rendered)
    assert all(record["platformCode"] == "DOUBAO" for record in rendered)
    assert all(record["upstreamOutcomeUnknown"] is True for record in rendered)
    assert "private" not in " ".join(str(record.__dict__) for record in caplog.records)


def test_retry_after_honored_or_refused_without_early_retry(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    waits: list[float] = []

    async def capture_wait(delay: float) -> None:
        waits.append(delay)

    monkeypatch.setattr("app.geo.providers.wait_geo_retry", capture_wait)

    async def scenario(header: str, short_deadline: bool = False) -> tuple[int, str | None]:
        calls = 0

        async def handler(request: httpx.Request) -> httpx.Response:
            nonlocal calls
            calls += 1
            return httpx.Response(429, headers={"Retry-After": header})

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = DoubaoGeoAdapter(_settings(retries=1), client=client, policy=_policy())
            result = await _sample(
                GeoSampleExecutor(GeoProviderRegistry([adapter])),
                deadline=datetime.now(UTC) + timedelta(seconds=1 if short_deadline else 300),
            )
        return calls, result.stable_error_code

    assert asyncio.run(scenario("2"))[0] == 2
    assert waits == [2]
    waits.clear()
    assert asyncio.run(scenario("2", True)) == (1, "PROVIDER_DEADLINE_EXCEEDED")
    assert asyncio.run(scenario("180")) == (1, "GEO_PROVIDER_RATE_LIMITED")
    assert waits == []
    future = format_datetime(datetime.now(UTC) + timedelta(seconds=60), usegmt=True)
    assert asyncio.run(scenario(future))[0] == 2
    assert len(waits) == 1 and 58 <= waits[0] <= 60
    for invalid in ("-1", "nan", "inf", "not a date"):
        waits.clear()
        assert asyncio.run(scenario(invalid))[0] == 2
        assert waits == [0]


def test_request_absolute_timeout_is_unit_failure_with_exact_call_count() -> None:
    async def scenario() -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            await asyncio.Event().wait()
            raise AssertionError("unreachable")

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = DoubaoGeoAdapter(_settings(), client=client, policy=_policy(total=0.03))
            result = await _sample(GeoSampleExecutor(GeoProviderRegistry([adapter])))
        assert result.stable_error_code == "GEO_PROVIDER_TIMEOUT"
        assert result.provider_call_count == 1

    asyncio.run(scenario())


@pytest.mark.parametrize("status", (429, 503))
@pytest.mark.parametrize("retry_after", ("2", "180", "9" * 400))
def test_final_transport_failure_retains_private_retry_window(
    status: int, retry_after: str
) -> None:
    async def scenario() -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(status, headers={"Retry-After": retry_after})

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = DoubaoGeoAdapter(_settings(), client=client, policy=_policy())
            result = await _sample(GeoSampleExecutor(GeoProviderRegistry([adapter])))
        assert result.provider_call_count == 1
        assert result.retry_not_before is not None
        assert (result.retry_not_before - result.finished_at).total_seconds() > 1.9
        assert result.retry_wait_limit_seconds == 120
        # This state cannot change the signed observation schema or be accepted
        # from the control plane as a persisted retry instruction.
        assert "retry" not in result.model_dump_json(by_alias=True).casefold()
        assert "retry" not in json.dumps(GeoObservation.model_json_schema()).casefold()
        restored = GeoObservation.model_validate_json(result.model_dump_json())
        assert restored.retry_not_before is None

    asyncio.run(scenario())


def test_task_deadline_cancels_hanging_request_but_not_fast_sibling() -> None:
    async def scenario() -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            if json.loads(request.content)["input"] == "slow":
                await asyncio.Event().wait()
            return httpx.Response(200, json=_response())

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = DoubaoGeoAdapter(_settings(), client=client, policy=_policy(total=1))
            executor = GeoSampleExecutor(GeoProviderRegistry([adapter]))
            deadline = datetime.now(UTC) + timedelta(seconds=0.1)
            slow, fast = await asyncio.gather(
                _sample(executor, question="slow", deadline=deadline),
                _sample(executor, question="fast", deadline=deadline),
            )
        assert slow.stable_error_code == "PROVIDER_DEADLINE_EXCEEDED"
        assert slow.provider_call_count == 1
        assert fast.status is GeoObservationStatus.SUCCEEDED

    asyncio.run(scenario())


def test_platform_waiter_does_not_take_task_slot() -> None:
    async def scenario() -> None:
        entered = asyncio.Event()
        release = asyncio.Event()
        active = 0

        async def handler(request: httpx.Request) -> httpx.Response:
            nonlocal active
            if request.url.host == "ark.cn-beijing.volces.com":
                active += 1
                if active == 2:
                    entered.set()
                await release.wait()
                active -= 1
                return httpx.Response(200, json=_response())
            return httpx.Response(200, json={"choices": [{"message": {"content": "快平台"}}]})

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapters = [
                DoubaoGeoAdapter(_settings(), client=client, policy=_policy(total=3)),
                QwenGeoAdapter(_settings(), client=client),
            ]
            executor = GeoSampleExecutor(GeoProviderRegistry(adapters))
            slots = asyncio.Semaphore(3)
            tasks = [asyncio.create_task(_sample(executor, slots=slots)) for _ in range(3)]
            try:
                async with asyncio.timeout(2):
                    await entered.wait()
                    fast = await _sample(executor, platform=GeoPlatformCode.QWEN, slots=slots)
                assert fast.status is GeoObservationStatus.SUCCEEDED
                assert active == 2
            finally:
                release.set()
                await asyncio.gather(*tasks)

    asyncio.run(scenario())


def test_backoff_releases_the_only_task_slot() -> None:
    async def scenario() -> None:
        rejected = asyncio.Event()
        seen: list[str] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            name = json.loads(request.content)["input"]
            seen.append(name)
            if seen == ["slow"]:
                rejected.set()
                return httpx.Response(429)
            return httpx.Response(200, json=_response())

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = DoubaoGeoAdapter(
                _settings(retries=1), client=client, policy=_policy(delay=0.15)
            )
            executor = GeoSampleExecutor(GeoProviderRegistry([adapter]))
            slots = asyncio.Semaphore(1)
            slow = asyncio.create_task(_sample(executor, question="slow", slots=slots))
            await rejected.wait()
            fast = await _sample(executor, question="fast", slots=slots)
            result = await slow
        assert fast.status is result.status is GeoObservationStatus.SUCCEEDED
        assert seen == ["slow", "fast", "slow"]
        assert result.provider_call_count == 2

    asyncio.run(scenario())


def test_external_cancellation_does_not_retry_and_releases_admission() -> None:
    async def scenario() -> None:
        entered = asyncio.Event()
        calls = 0

        async def handler(request: httpx.Request) -> httpx.Response:
            nonlocal calls
            calls += 1
            if calls == 1:
                entered.set()
                await asyncio.Event().wait()
            return httpx.Response(200, json=_response())

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = DoubaoGeoAdapter(_settings(retries=1), client=client, policy=_policy())
            executor = GeoSampleExecutor(GeoProviderRegistry([adapter]), doubao_concurrency=1)
            slots = asyncio.Semaphore(1)
            first = asyncio.create_task(_sample(executor, slots=slots))
            await entered.wait()
            first.cancel()
            with pytest.raises(asyncio.CancelledError):
                await first
            assert calls == 1
            second = await _sample(executor, slots=slots)
            assert second.status is GeoObservationStatus.SUCCEEDED
            assert calls == 2

    asyncio.run(scenario())


def test_deadline_while_waiting_for_task_slot_releases_platform_without_a_call() -> None:
    async def scenario() -> None:
        calls = 0

        async def handler(request: httpx.Request) -> httpx.Response:
            nonlocal calls
            calls += 1
            return httpx.Response(200, json=_response())

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = DoubaoGeoAdapter(_settings(), client=client, policy=_policy())
            executor = GeoSampleExecutor(GeoProviderRegistry([adapter]), doubao_concurrency=1)
            waiting = await _sample(
                executor,
                slots=asyncio.Semaphore(0),
                deadline=datetime.now(UTC) + timedelta(seconds=0.06),
            )
            assert waiting.stable_error_code == "PROVIDER_DEADLINE_EXCEEDED"
            assert calls == waiting.provider_call_count == 0
            # Another task can now use that same process-wide platform slot.
            async with asyncio.timeout(1):
                next_task = await _sample(executor, slots=asyncio.Semaphore(1))
            assert next_task.status is GeoObservationStatus.SUCCEEDED
            assert calls == 1

    asyncio.run(scenario())


@pytest.mark.parametrize("mode", ("delayed_success", "read_timeout", "drip_total_timeout"))
def test_real_loopback_transport_deadlines(mode: str) -> None:
    """Exercise real sockets; MockTransport cannot enforce HTTPX read timeout."""

    async def scenario() -> None:
        stopped = asyncio.Event()
        sent_bytes = 0

        async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
            nonlocal sent_bytes
            try:
                await reader.readuntil(b"\r\n\r\n")
                body = json.dumps(_response()).encode()
                headers = (
                    f"HTTP/1.1 200 OK\r\nContent-Length: {len(body)}\r\n"
                    "Content-Type: application/json\r\nConnection: close\r\n\r\n"
                ).encode()
                if mode == "drip_total_timeout":
                    writer.write(headers)
                    for byte in body:
                        writer.write(bytes([byte]))
                        sent_bytes += 1
                        await writer.drain()
                        # Above Windows' ~15.6 ms event-loop clock resolution;
                        # shorter waits may all run early under continuous I/O.
                        await asyncio.sleep(0.04)
                else:
                    await asyncio.sleep(0.08 if mode == "delayed_success" else 0.2)
                    writer.write(headers + body)
                    await writer.drain()
            except (ConnectionError, asyncio.IncompleteReadError):
                pass
            finally:
                writer.close()
                with suppress(ConnectionError):
                    await writer.wait_closed()
                stopped.set()

        server = await asyncio.start_server(handle, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]

        class LoopbackTransport(httpx.AsyncBaseTransport):
            def __init__(self) -> None:
                self.network = httpx.AsyncHTTPTransport(trust_env=False)

            async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
                # Only this test transport redirects the already validated fake
                # provider request to loopback, preserving timeout extensions.
                request.url = httpx.URL(f"http://127.0.0.1:{port}/")
                return await self.network.handle_async_request(request)

            async def aclose(self) -> None:
                await self.network.aclose()

        try:
            async with httpx.AsyncClient(transport=LoopbackTransport()) as client:
                read = 0.03 if mode == "read_timeout" else 0.5
                total = 0.25 if mode == "drip_total_timeout" else 1.0
                adapter = DoubaoGeoAdapter(
                    _settings(read=read), client=client, policy=_policy(total=total)
                )
                result = await _sample(GeoSampleExecutor(GeoProviderRegistry([adapter])))
            if mode == "delayed_success":
                assert result.status is GeoObservationStatus.SUCCEEDED
            else:
                assert result.stable_error_code == "GEO_PROVIDER_TIMEOUT", (mode, sent_bytes)
            assert result.provider_call_count == 1
            async with asyncio.timeout(2):
                await stopped.wait()
        finally:
            server.close()
            await server.wait_closed()

    asyncio.run(scenario())

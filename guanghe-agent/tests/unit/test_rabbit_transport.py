"""Rabbit transport tests use injected aio-pika-shaped fakes, never a live broker."""

import asyncio
from typing import cast

import pytest
from pydantic import SecretStr

from app.common.settings import AppSettings
from app.workers.transport import (
    LocalMessageBroker,
    MessageBrokerConfigurationError,
    MessageBrokerUnavailable,
    RabbitChannel,
    RabbitConnection,
    RabbitIncomingMessage,
    RabbitMessageBroker,
    RabbitQueue,
    RabbitQueueIterator,
    aio_pika_connector,
    build_message_broker,
)


class FakeIncomingMessage:
    def __init__(
        self,
        body: bytes,
        *,
        message_id: str | None = None,
        delivery_tag: int | None = None,
        redelivered: bool = False,
    ) -> None:
        self.body = body
        self.message_id = message_id
        self.delivery_tag = delivery_tag
        self.redelivered = redelivered
        self.settlements: list[tuple[str, bool]] = []

    async def ack(self, *, multiple: bool = False) -> None:
        self.settlements.append(("ack", multiple))

    async def nack(self, *, multiple: bool = False, requeue: bool = True) -> None:
        self.settlements.append(("nack", requeue or multiple))

    async def reject(self, *, requeue: bool = False) -> None:
        self.settlements.append(("reject", requeue))


class FakeQueueIterator:
    def __init__(self, messages: list[FakeIncomingMessage], audit: list[str]) -> None:
        self._messages: asyncio.Queue[FakeIncomingMessage | None] = asyncio.Queue()
        for message in messages:
            self._messages.put_nowait(message)
        self.audit = audit

    async def __aenter__(self) -> "FakeQueueIterator":
        self.audit.append("iterator-enter")
        return self

    async def __anext__(self) -> RabbitIncomingMessage:
        message = await self._messages.get()
        if message is None:
            raise StopAsyncIteration
        return cast(RabbitIncomingMessage, message)

    async def close(self) -> None:
        self.audit.append("iterator-close")
        self._messages.put_nowait(None)


class FakeQueue:
    def __init__(self, iterator: FakeQueueIterator, audit: list[str]) -> None:
        self._iterator = iterator
        self.audit = audit
        self.no_ack: bool | None = None

    def iterator(self, *, no_ack: bool = False) -> RabbitQueueIterator:
        self.no_ack = no_ack
        self.audit.append("queue-iterator")
        return cast(RabbitQueueIterator, self._iterator)


class FakeChannel:
    def __init__(self, queue: FakeQueue, audit: list[str]) -> None:
        self._queue = queue
        self.audit = audit
        self.prefetch_count: int | None = None
        self.declared: tuple[str, bool] | None = None

    async def set_qos(self, *, prefetch_count: int) -> None:
        self.prefetch_count = prefetch_count
        self.audit.append("channel-qos")

    async def declare_queue(self, name: str, *, passive: bool) -> RabbitQueue:
        self.declared = (name, passive)
        self.audit.append("queue-declare")
        return cast(RabbitQueue, self._queue)

    async def close(self) -> None:
        self.audit.append("channel-close")


class FakeConnection:
    def __init__(self, channel: FakeChannel, audit: list[str]) -> None:
        self._channel = channel
        self.audit = audit

    async def channel(self) -> RabbitChannel:
        self.audit.append("connection-channel")
        return cast(RabbitChannel, self._channel)

    async def close(self) -> None:
        self.audit.append("connection-close")


def _fixture(
    messages: list[FakeIncomingMessage],
) -> tuple[
    RabbitMessageBroker,
    FakeQueue,
    FakeChannel,
    list[str],
]:
    audit: list[str] = []
    iterator = FakeQueueIterator(messages, audit)
    queue = FakeQueue(iterator, audit)
    channel = FakeChannel(queue, audit)
    connection = FakeConnection(channel, audit)

    async def connector() -> RabbitConnection:
        audit.append("connect")
        return cast(RabbitConnection, connection)

    broker = RabbitMessageBroker(
        queue_name="zhixiaohe.agent.image",
        prefetch_count=3,
        connector=connector,
        close_timeout_seconds=0.05,
    )
    return broker, queue, channel, audit


def test_rabbit_consumer_is_passive_manual_ack_and_prefetch_bounded() -> None:
    async def scenario() -> tuple[object, FakeQueue, FakeChannel, list[str]]:
        incoming = FakeIncomingMessage(
            b'{"schemaVersion":1}',
            message_id="event-1",
            delivery_tag=7,
            redelivered=True,
        )
        broker, queue, channel, audit = _fixture([incoming])
        await broker.start()
        await broker.start()
        delivery = await broker.get()
        assert delivery is not None
        assert delivery.body == incoming.body
        assert delivery.delivery_id == "event-1"
        assert delivery.redelivered is True
        await delivery.ack()
        with pytest.raises(RuntimeError, match="already been settled"):
            await delivery.reject()
        await broker.stop_accepting()
        await broker.aclose()
        return incoming, queue, channel, audit

    incoming, queue, channel, audit = asyncio.run(scenario())
    assert cast(FakeIncomingMessage, incoming).settlements == [("ack", False)]
    assert queue.no_ack is False
    assert channel.prefetch_count == 3
    assert channel.declared == ("zhixiaohe.agent.image", True)
    assert audit.count("connect") == 1
    assert audit[-3:] == ["iterator-close", "channel-close", "connection-close"]


def test_rabbit_delivery_forwards_nack_and_reject_without_auto_ack() -> None:
    async def scenario() -> tuple[FakeIncomingMessage, FakeIncomingMessage]:
        first = FakeIncomingMessage(b"first", delivery_tag=11)
        second = FakeIncomingMessage(b"second", delivery_tag=12)
        broker, _, _, _ = _fixture([first, second])
        first_delivery = await broker.get()
        second_delivery = await broker.get()
        assert first_delivery is not None
        assert second_delivery is not None
        assert first_delivery.delivery_id == "11"
        await first_delivery.nack(requeue=True)
        await second_delivery.reject()
        await broker.aclose()
        return first, second

    first, second = asyncio.run(scenario())
    assert first.settlements == [("nack", True)]
    assert second.settlements == [("reject", False)]


def test_rabbit_settlement_failure_is_sanitized_and_remains_unsettled() -> None:
    class FailingAckMessage(FakeIncomingMessage):
        async def ack(self, *, multiple: bool = False) -> None:
            del multiple
            raise ConnectionError("synthetic upstream detail must stay hidden")

    async def scenario() -> tuple[str, str]:
        broker, _, _, _ = _fixture([FailingAckMessage(b"payload")])
        delivery = await broker.get()
        assert delivery is not None
        try:
            await delivery.ack()
        except MessageBrokerUnavailable as exc:
            error = str(exc)
        else:
            raise AssertionError("Settlement failure was not mapped")
        await broker.aclose()
        return error, delivery.state

    error, delivery_state = asyncio.run(scenario())
    assert error == "RabbitMQ ACK failed"
    assert "upstream detail" not in error
    assert delivery_state == "PENDING"


@pytest.mark.parametrize(
    ("settlement", "expected_error"),
    (
        ("ACK", "RabbitMQ ACK failed"),
        ("NACK", "RabbitMQ NACK failed"),
        ("REJECT", "RabbitMQ reject failed"),
    ),
)
def test_settlement_failure_discards_generation_before_reconnect(
    settlement: str, expected_error: str
) -> None:
    class FailingSettlementMessage(FakeIncomingMessage):
        async def ack(self, *, multiple: bool = False) -> None:
            if settlement == "ACK":
                raise ConnectionError("synthetic ACK failure")
            await super().ack(multiple=multiple)

        async def nack(self, *, multiple: bool = False, requeue: bool = True) -> None:
            if settlement == "NACK":
                raise ConnectionError("synthetic NACK failure")
            await super().nack(multiple=multiple, requeue=requeue)

        async def reject(self, *, requeue: bool = False) -> None:
            if settlement == "REJECT":
                raise ConnectionError("synthetic reject failure")
            await super().reject(requeue=requeue)

    async def scenario() -> tuple[list[str], int | None, bool, bytes]:
        audit: list[str] = []
        connections = 0

        async def connector() -> RabbitConnection:
            nonlocal connections
            connections += 1
            incoming: FakeIncomingMessage = (
                FailingSettlementMessage(b"first", delivery_tag=1)
                if connections == 1
                else FakeIncomingMessage(b"recovered", delivery_tag=2, redelivered=True)
            )
            iterator = FakeQueueIterator([incoming], audit)
            queue = FakeQueue(iterator, audit)
            channel = FakeChannel(queue, audit)
            audit.append(f"connect-{connections}")
            return cast(RabbitConnection, FakeConnection(channel, audit))

        broker = RabbitMessageBroker(
            queue_name="zhixiaohe.agent.image",
            prefetch_count=1,
            connector=connector,
        )
        delivery = await broker.get()
        assert delivery is not None
        with pytest.raises(MessageBrokerUnavailable, match=expected_error) as raised:
            if settlement == "ACK":
                await delivery.ack()
            elif settlement == "NACK":
                await delivery.nack(requeue=True)
            else:
                await delivery.reject()
        assert delivery.state == "PENDING"
        error = raised.value
        invalidated = await broker.invalidate(error.generation)
        assert invalidated is True
        assert broker.accepting is False
        assert audit.index("channel-close") < audit.index("connection-close")

        await broker.stop_accepting()
        await broker.restart()
        assert audit.index("connection-close") < audit.index("connect-2")
        assert await broker.invalidate(error.generation) is False
        assert broker.accepting is True

        recovered = await broker.get()
        assert recovered is not None
        await recovered.ack()
        result = audit.copy(), error.generation, invalidated, recovered.body
        await broker.aclose()
        return result

    audit, generation, invalidated, recovered_body = asyncio.run(scenario())
    assert generation == 1
    assert invalidated is True
    assert recovered_body == b"recovered"
    assert audit.count("connect-1") == 1
    assert audit.count("connect-2") == 1


def test_delivery_failure_discards_generation_before_reconnect() -> None:
    class FailingGetIterator(FakeQueueIterator):
        async def __anext__(self) -> RabbitIncomingMessage:
            raise ConnectionError("synthetic delivery failure")

    async def scenario() -> tuple[list[str], int | None, bytes]:
        audit: list[str] = []
        connections = 0

        async def connector() -> RabbitConnection:
            nonlocal connections
            connections += 1
            if connections == 1:
                iterator: FakeQueueIterator = FailingGetIterator([], audit)
            else:
                iterator = FakeQueueIterator(
                    [FakeIncomingMessage(b"recovered", delivery_tag=2, redelivered=True)],
                    audit,
                )
            queue = FakeQueue(iterator, audit)
            channel = FakeChannel(queue, audit)
            audit.append(f"connect-{connections}")
            return cast(RabbitConnection, FakeConnection(channel, audit))

        broker = RabbitMessageBroker(
            queue_name="zhixiaohe.agent.image",
            prefetch_count=1,
            connector=connector,
        )
        with pytest.raises(MessageBrokerUnavailable, match="RabbitMQ delivery failed") as raised:
            await broker.get()
        error = raised.value
        assert await broker.invalidate(error.generation) is True
        await broker.stop_accepting()
        await broker.restart()
        recovered = await broker.get()
        assert recovered is not None
        await recovered.ack()
        result = audit.copy(), error.generation, recovered.body
        await broker.aclose()
        return result

    audit, generation, recovered_body = asyncio.run(scenario())
    assert generation == 1
    assert recovered_body == b"recovered"
    assert audit.index("channel-close") < audit.index("connection-close")
    assert audit.index("connection-close") < audit.index("connect-2")


def test_rabbit_shutdown_is_bounded_and_closes_remaining_resources() -> None:
    class HangingIterator(FakeQueueIterator):
        async def close(self) -> None:
            self.audit.append("iterator-close-start")
            await asyncio.Event().wait()

    async def scenario() -> tuple[float, list[str]]:
        audit: list[str] = []
        iterator = HangingIterator([], audit)
        queue = FakeQueue(iterator, audit)
        channel = FakeChannel(queue, audit)
        connection = FakeConnection(channel, audit)

        async def connector() -> RabbitConnection:
            return cast(RabbitConnection, connection)

        broker = RabbitMessageBroker(
            queue_name="zhixiaohe.agent.image",
            prefetch_count=1,
            connector=connector,
            close_timeout_seconds=0.01,
        )
        await broker.start()
        started = asyncio.get_running_loop().time()
        await broker.aclose()
        return asyncio.get_running_loop().time() - started, audit

    elapsed, audit = asyncio.run(scenario())
    assert elapsed < 0.1
    assert "iterator-close-start" in audit
    assert "channel-close" in audit
    assert "connection-close" in audit


def test_rabbit_shutdown_drains_delivery_before_channel_close() -> None:
    audit: list[str] = []

    class ObservedMessage(FakeIncomingMessage):
        async def ack(self, *, multiple: bool = False) -> None:
            audit.append("message-ack")
            await super().ack(multiple=multiple)

    async def scenario() -> list[str]:
        iterator = FakeQueueIterator([ObservedMessage(b"payload")], audit)
        queue = FakeQueue(iterator, audit)
        channel = FakeChannel(queue, audit)
        connection = FakeConnection(channel, audit)

        async def connector() -> RabbitConnection:
            return cast(RabbitConnection, connection)

        broker = RabbitMessageBroker(
            queue_name="zhixiaohe.agent.image",
            prefetch_count=1,
            connector=connector,
        )
        delivery = await broker.get()
        assert delivery is not None
        await broker.stop_accepting()
        await delivery.ack()
        await broker.aclose()
        return audit

    lifecycle = asyncio.run(scenario())
    assert lifecycle.index("iterator-close") < lifecycle.index("message-ack")
    assert lifecycle.index("message-ack") < lifecycle.index("channel-close")
    assert lifecycle.index("channel-close") < lifecycle.index("connection-close")


def test_rabbit_restart_replaces_paused_channel_before_resuming_consumption() -> None:
    async def scenario() -> tuple[bytes, list[str]]:
        audit: list[str] = []
        connection_count = 0

        async def connector() -> RabbitConnection:
            nonlocal connection_count
            connection_count += 1
            iterator = FakeQueueIterator(
                [FakeIncomingMessage(b"payload", redelivered=connection_count == 2)],
                audit,
            )
            queue = FakeQueue(iterator, audit)
            channel = FakeChannel(queue, audit)
            audit.append(f"connect-{connection_count}")
            return cast(RabbitConnection, FakeConnection(channel, audit))

        broker = RabbitMessageBroker(
            queue_name="zhixiaohe.agent.image",
            prefetch_count=1,
            connector=connector,
        )
        await broker.start()
        first = await broker.get()
        assert first is not None
        await first.ack()
        await broker.stop_accepting()
        assert broker.accepting is False

        await broker.restart()
        second = await broker.get()
        assert second is not None
        assert second.redelivered is True
        await second.ack()
        await broker.aclose()
        return second.body, audit

    body, audit = asyncio.run(scenario())
    assert body == b"payload"
    assert audit.count("connect-1") == 1
    assert audit.count("connect-2") == 1
    assert audit.count("iterator-enter") == 2
    assert audit.index("channel-close") < audit.index("connect-2")


def test_failed_iterator_cancel_cannot_leave_an_orphan_consumer_after_restart() -> None:
    async def scenario() -> tuple[list[str], int]:
        audit: list[str] = []
        connections = 0
        old_channel_closed = False

        class StuckIterator(FakeQueueIterator):
            async def close(self) -> None:
                self.audit.append("old-iterator-close-start")
                await asyncio.Event().wait()

        class FirstChannel(FakeChannel):
            async def close(self) -> None:
                nonlocal old_channel_closed
                old_channel_closed = True
                self.audit.append("old-channel-close")

        async def connector() -> RabbitConnection:
            nonlocal connections
            connections += 1
            if connections == 1:
                iterator: FakeQueueIterator = StuckIterator([], audit)
                channel: FakeChannel = FirstChannel(FakeQueue(iterator, audit), audit)
            else:
                assert old_channel_closed
                iterator = FakeQueueIterator(
                    [FakeIncomingMessage(b"retry", redelivered=True)], audit
                )
                channel = FakeChannel(FakeQueue(iterator, audit), audit)
            audit.append(f"connect-{connections}")
            return cast(RabbitConnection, FakeConnection(channel, audit))

        broker = RabbitMessageBroker(
            queue_name="zhixiaohe.agent.article",
            prefetch_count=1,
            connector=connector,
            close_timeout_seconds=0.01,
        )
        await broker.start()
        await broker.stop_accepting()
        await broker.restart()
        recovered = await broker.get()
        assert recovered is not None
        assert recovered.body == b"retry"
        assert recovered.redelivered is True
        await recovered.ack()
        await broker.aclose()
        return audit, connections

    audit, connections = asyncio.run(scenario())
    assert connections == 2
    assert audit.index("old-channel-close") < audit.index("connect-2")


def test_restart_stays_unready_if_old_channel_and_connection_cannot_close() -> None:
    class StuckChannel(FakeChannel):
        async def close(self) -> None:
            await asyncio.Event().wait()

    class StuckConnection(FakeConnection):
        async def close(self) -> None:
            await asyncio.Event().wait()

    async def scenario() -> tuple[bool, int]:
        audit: list[str] = []
        connections = 0

        async def connector() -> RabbitConnection:
            nonlocal connections
            connections += 1
            iterator = FakeQueueIterator([], audit)
            channel = StuckChannel(FakeQueue(iterator, audit), audit)
            return cast(RabbitConnection, StuckConnection(channel, audit))

        broker = RabbitMessageBroker(
            queue_name="zhixiaohe.agent.article",
            prefetch_count=1,
            connector=connector,
            close_timeout_seconds=0.01,
        )
        await broker.start()
        await broker.stop_accepting()
        with pytest.raises(MessageBrokerUnavailable):
            await broker.restart()
        result = broker.accepting, connections
        await broker.aclose()
        return result

    accepting, connections = asyncio.run(scenario())
    assert accepting is False
    assert connections == 1


@pytest.mark.parametrize("missing_resource", ("channel", "connection"))
def test_restart_stays_unready_if_only_remaining_resource_cannot_close(
    missing_resource: str,
) -> None:
    class StuckChannel(FakeChannel):
        async def close(self) -> None:
            await asyncio.Event().wait()

    class StuckConnection(FakeConnection):
        async def close(self) -> None:
            await asyncio.Event().wait()

    async def scenario() -> tuple[bool, int]:
        audit: list[str] = []
        connections = 0

        async def connector() -> RabbitConnection:
            nonlocal connections
            connections += 1
            channel = StuckChannel(FakeQueue(FakeQueueIterator([], audit), audit), audit)
            return cast(RabbitConnection, StuckConnection(channel, audit))

        broker = RabbitMessageBroker(
            queue_name="zhixiaohe.agent.article",
            prefetch_count=1,
            connector=connector,
            close_timeout_seconds=0.01,
        )
        await broker.start()
        await broker.stop_accepting()
        if missing_resource == "channel":
            broker._channel = None  # pyright: ignore[reportPrivateUsage]
        else:
            broker._connection = None  # pyright: ignore[reportPrivateUsage]
        with pytest.raises(MessageBrokerUnavailable):
            await broker.restart()
        return broker.accepting, connections

    accepting, connections = asyncio.run(scenario())
    assert accepting is False
    assert connections == 1


def test_local_broker_restart_removes_stop_marker_without_losing_pending_delivery() -> None:
    async def scenario() -> bytes:
        broker = LocalMessageBroker()
        pending = await broker.publish(b"pending")
        await broker.stop_accepting()
        await broker.restart()
        recovered = await broker.get()
        assert recovered is not None
        assert recovered is pending
        await broker.aclose()
        return recovered.body

    assert asyncio.run(scenario()) == b"pending"


def test_rabbit_full_startup_sequence_has_a_timeout() -> None:
    class HangingChannel(FakeChannel):
        async def set_qos(self, *, prefetch_count: int) -> None:
            del prefetch_count
            self.audit.append("channel-qos-start")
            await asyncio.Event().wait()

    async def scenario() -> tuple[float, list[str]]:
        audit: list[str] = []
        iterator = FakeQueueIterator([], audit)
        queue = FakeQueue(iterator, audit)
        channel = HangingChannel(queue, audit)
        connection = FakeConnection(channel, audit)

        async def connector() -> RabbitConnection:
            return cast(RabbitConnection, connection)

        broker = RabbitMessageBroker(
            queue_name="zhixiaohe.agent.image",
            prefetch_count=1,
            connector=connector,
            startup_timeout_seconds=0.01,
            close_timeout_seconds=0.01,
        )
        started = asyncio.get_running_loop().time()
        with pytest.raises(MessageBrokerUnavailable):
            await broker.start()
        return asyncio.get_running_loop().time() - started, audit

    elapsed, audit = asyncio.run(scenario())
    assert elapsed < 0.1
    assert "channel-qos-start" in audit
    assert "channel-close" in audit
    assert "connection-close" in audit


def test_rabbit_connector_failure_fails_closed_without_retry_loop() -> None:
    async def connector() -> RabbitConnection:
        raise ConnectionError("synthetic")

    async def scenario() -> tuple[bool, bool]:
        broker = RabbitMessageBroker(
            queue_name="zhixiaohe.agent.image",
            prefetch_count=1,
            connector=connector,
        )
        with pytest.raises(MessageBrokerUnavailable, match="RabbitMQ consumer is unavailable"):
            await broker.start()
        return broker.accepting, (await broker.get()) is None

    accepting, drained = asyncio.run(scenario())
    assert accepting is False
    assert drained is True


def test_aio_pika_connector_defers_connection_and_passes_bounded_timeout(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    audit: list[tuple[str, float]] = []

    async def connect_robust(url: str, **options: float) -> object:
        audit.append((url, options["timeout"]))
        return object()

    monkeypatch.setattr("app.workers.transport.aio_pika.connect_robust", connect_robust)
    connector = aio_pika_connector(
        "amqps://synthetic-user:synthetic-password@rabbit.unit.test/vhost",
        timeout_seconds=4.0,
    )
    assert audit == []

    async def scenario() -> RabbitConnection:
        return await connector()

    asyncio.run(scenario())
    assert audit == [
        (
            "amqps://synthetic-user:synthetic-password@rabbit.unit.test/vhost",
            4.0,
        )
    ]


def test_broker_builder_never_falls_back_from_explicit_rabbitmq() -> None:
    local = build_message_broker(AppSettings(worker_broker="LOCAL"))
    assert isinstance(local, LocalMessageBroker)

    with pytest.raises(MessageBrokerConfigurationError):
        build_message_broker(AppSettings(worker_broker="RABBITMQ"))

    rabbit = build_message_broker(
        AppSettings(
            worker_broker="RABBITMQ",
            rabbitmq_url=SecretStr("amqp://rabbit.unit.test/vhost"),
            rabbitmq_queue_name="zhixiaohe.agent.image",
        )
    )
    assert isinstance(rabbit, RabbitMessageBroker)


@pytest.mark.parametrize(
    ("queue_name", "prefetch_count", "startup_timeout", "close_timeout"),
    [
        ("", 1, 1.0, 1.0),
        ("zhixiaohe.agent.image", 0, 1.0, 1.0),
        ("zhixiaohe.agent.image", 33, 1.0, 1.0),
        ("zhixiaohe.agent.image", 1, 0.0, 1.0),
        ("zhixiaohe.agent.image", 1, 1.0, 0.0),
    ],
)
def test_rabbit_transport_rejects_unbounded_or_invalid_configuration(
    queue_name: str,
    prefetch_count: int,
    startup_timeout: float,
    close_timeout: float,
) -> None:
    async def connector() -> RabbitConnection:
        return cast(RabbitConnection, object())

    with pytest.raises(ValueError):
        RabbitMessageBroker(
            queue_name=queue_name,
            prefetch_count=prefetch_count,
            connector=connector,
            startup_timeout_seconds=startup_timeout,
            close_timeout_seconds=close_timeout,
        )

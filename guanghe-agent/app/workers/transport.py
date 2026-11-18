"""Message-delivery abstractions for local and aio-pika Rabbit consumers."""

import asyncio
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Literal, Protocol, cast
from uuid import uuid4

import aio_pika

if TYPE_CHECKING:
    from app.common.settings import AppSettings

DeliveryState = Literal["PENDING", "ACKED", "NACKED", "REJECTED"]
PoisonDeliveryDisposition = Literal["REQUEUED", "REJECTED", "ALREADY_SETTLED"]


class RabbitIncomingMessage(Protocol):
    """Small structural subset of :mod:`aio_pika`'s incoming message API."""

    body: bytes
    message_id: str | None
    delivery_tag: int | None
    redelivered: bool

    async def ack(self, *, multiple: bool = False) -> None: ...

    async def nack(self, *, multiple: bool = False, requeue: bool = True) -> None: ...

    async def reject(self, *, requeue: bool = False) -> None: ...


class RabbitQueueIterator(Protocol):
    async def __aenter__(self) -> "RabbitQueueIterator": ...

    async def __anext__(self) -> RabbitIncomingMessage: ...

    async def close(self) -> None: ...


class RabbitQueue(Protocol):
    def iterator(self, *, no_ack: bool = False) -> RabbitQueueIterator: ...


class RabbitChannel(Protocol):
    async def set_qos(self, *, prefetch_count: int) -> None: ...

    async def declare_queue(self, name: str, *, passive: bool) -> RabbitQueue: ...

    async def close(self) -> None: ...


class RabbitConnection(Protocol):
    async def channel(self) -> RabbitChannel: ...

    async def close(self) -> None: ...


RabbitConnector = Callable[[], Awaitable[RabbitConnection]]


class MessageBroker(Protocol):
    """Minimal pull-consumer boundary used by the bounded Worker runtime."""

    @property
    def accepting(self) -> bool: ...

    @property
    def depth(self) -> int: ...

    async def start(self) -> None: ...

    async def restart(self) -> None: ...

    async def invalidate(self, generation: int | None) -> bool: ...

    async def get(self) -> "RawDelivery | None": ...

    async def stop_accepting(self) -> None: ...

    async def aclose(self) -> None: ...


class MessageBrokerUnavailable(RuntimeError):
    """Sanitized broker failure which is safe to expose through readiness."""

    code = "RABBITMQ_UNAVAILABLE"

    def __init__(self, message: str, *, generation: int | None = None) -> None:
        super().__init__(message)
        self.generation = generation


class MessageBrokerConfigurationError(RuntimeError):
    """Broker mode was selected without the required environment configuration."""

    code = "RABBITMQ_CONFIGURATION_UNAVAILABLE"


@dataclass(slots=True)
class RawDelivery:
    body: bytes
    delivery_id: str = field(default_factory=lambda: str(uuid4()))
    redelivered: bool = False
    state: DeliveryState = "PENDING"
    requeue: bool = False
    audit: list[str] = field(default_factory=lambda: list[str]())
    _rabbit_message: RabbitIncomingMessage | None = field(default=None, repr=False)
    _broker_generation: int | None = field(default=None, repr=False)

    async def ack(self) -> None:
        self._ensure_pending()
        if self._rabbit_message is not None:
            try:
                await self._rabbit_message.ack(multiple=False)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                raise MessageBrokerUnavailable(
                    "RabbitMQ ACK failed", generation=self._broker_generation
                ) from exc
        self.state = "ACKED"
        self.audit.append("ack")

    async def nack(self, *, requeue: bool) -> None:
        self._ensure_pending()
        if self._rabbit_message is not None:
            try:
                await self._rabbit_message.nack(multiple=False, requeue=requeue)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                raise MessageBrokerUnavailable(
                    "RabbitMQ NACK failed", generation=self._broker_generation
                ) from exc
        self.state = "NACKED"
        self.requeue = requeue
        self.audit.append("nack-requeue" if requeue else "nack")

    async def reject(self) -> None:
        self._ensure_pending()
        if self._rabbit_message is not None:
            try:
                await self._rabbit_message.reject(requeue=False)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                raise MessageBrokerUnavailable(
                    "RabbitMQ reject failed", generation=self._broker_generation
                ) from exc
        self.state = "REJECTED"
        self.audit.append("reject-dlq")

    def _ensure_pending(self) -> None:
        if self.state != "PENDING":
            raise RuntimeError("Delivery has already been settled")


async def settle_poison_delivery(delivery: RawDelivery) -> PoisonDeliveryDisposition:
    """Bound an unclassified delivery failure without stopping the consumer.

    RabbitMQ marks a requeued delivery as ``redelivered``.  One retry allows a
    transient process-local failure to clear, while rejecting the redelivery
    prevents a deterministic poison message from hot-looping forever.  A
    delivery which was already ACKed belongs to execution failure handling and
    cannot safely be settled a second time.
    """

    if delivery.state != "PENDING":
        return "ALREADY_SETTLED"
    if delivery.redelivered:
        await delivery.reject()
        return "REJECTED"
    await delivery.nack(requeue=True)
    return "REQUEUED"


class LocalMessageBroker:
    """Process-local broker for Worker development; production never falls back to it."""

    def __init__(self) -> None:
        self._queue: asyncio.Queue[RawDelivery | None] = asyncio.Queue()
        self._accepting = True

    @property
    def accepting(self) -> bool:
        return self._accepting

    @property
    def depth(self) -> int:
        return self._queue.qsize()

    async def start(self) -> None:
        return None

    async def restart(self) -> None:
        pending: list[RawDelivery] = []
        while True:
            try:
                item = self._queue.get_nowait()
            except asyncio.QueueEmpty:
                break
            if item is not None:
                pending.append(item)
        self._queue = asyncio.Queue()
        for item in pending:
            self._queue.put_nowait(item)
        self._accepting = True
        await self.start()

    async def invalidate(self, generation: int | None) -> bool:
        del generation
        return True

    async def publish(self, body: bytes, *, redelivered: bool = False) -> RawDelivery:
        if not self._accepting:
            raise RuntimeError("Worker broker is draining")
        delivery = RawDelivery(body=body, redelivered=redelivered)
        await self._queue.put(delivery)
        return delivery

    async def get(self) -> RawDelivery | None:
        return await self._queue.get()

    async def stop_accepting(self) -> None:
        if self._accepting:
            self._accepting = False
            await self._queue.put(None)

    async def aclose(self) -> None:
        await self.stop_accepting()


class RabbitMessageBroker:
    """Consume one predeclared Rabbit queue with manual settlement and bounded close.

    Queue topology remains owned by Java.  The connector is injectable so tests stay
    offline and the application can keep the credential-bearing URL inside SecretStr.
    """

    def __init__(
        self,
        *,
        queue_name: str,
        prefetch_count: int,
        connector: RabbitConnector,
        startup_timeout_seconds: float = 5.0,
        close_timeout_seconds: float = 5.0,
    ) -> None:
        if not queue_name or len(queue_name) > 255 or any(ord(char) < 0x21 for char in queue_name):
            raise ValueError("Rabbit queue name is invalid")
        if not 1 <= prefetch_count <= 32:
            raise ValueError("Rabbit prefetch count must be between 1 and 32")
        if not 0 < startup_timeout_seconds <= 30:
            raise ValueError("Rabbit startup timeout must be between 0 and 30 seconds")
        if not 0 < close_timeout_seconds <= 30:
            raise ValueError("Rabbit close timeout must be between 0 and 30 seconds")
        self._queue_name = queue_name
        self._prefetch_count = prefetch_count
        self._connector = connector
        self._startup_timeout_seconds = startup_timeout_seconds
        self._close_timeout_seconds = close_timeout_seconds
        self._accepting = True
        self._start_lock = asyncio.Lock()
        self._connection: RabbitConnection | None = None
        self._channel: RabbitChannel | None = None
        self._iterator: RabbitQueueIterator | None = None
        self._generation = 0

    @property
    def accepting(self) -> bool:
        return self._accepting and self._iterator is not None

    @property
    def depth(self) -> int:
        # Rabbit owns the queue and QoS is the capacity boundary; no local buffer exists.
        return 0

    async def start(self) -> None:
        """Open the channel and begin a manual-ack iterator exactly once."""
        async with self._start_lock:
            if self._iterator is not None:
                return
            if not self._accepting:
                raise RuntimeError("Worker broker is draining")
            try:
                await asyncio.wait_for(
                    self._open_resources(),
                    timeout=self._startup_timeout_seconds,
                )
            except asyncio.CancelledError:
                self._accepting = False
                await self._close_all_resources()
                raise
            except Exception as exc:
                self._accepting = False
                await self._close_all_resources()
                raise MessageBrokerUnavailable("RabbitMQ consumer is unavailable") from exc

    async def restart(self) -> None:
        """Reopen a paused consumer on a fresh channel generation.

        Cancelling an iterator can time out after its consumer has already been
        registered with RabbitMQ. Reusing that channel would leave the old
        consumer able to hold unacked messages while the new iterator appears
        healthy. Closing the channel requeues those deliveries before we
        advertise a recovered consumer.
        """
        async with self._start_lock:
            self._accepting = False
            try:
                if not await self._close_all_resources():
                    raise MessageBrokerUnavailable("RabbitMQ previous consumer is still active")
                await asyncio.wait_for(
                    self._open_resources(),
                    timeout=self._startup_timeout_seconds,
                )
            except asyncio.CancelledError:
                self._accepting = False
                await self._close_all_resources()
                raise
            except Exception as exc:
                self._accepting = False
                await self._close_all_resources()
                raise MessageBrokerUnavailable("RabbitMQ consumer is unavailable") from exc
            self._accepting = True

    async def invalidate(self, generation: int | None) -> bool:
        """Discard one failed Rabbit channel generation before recovery.

        Control-plane pauses deliberately keep the channel alive so in-flight
        deliveries can settle.  Broker I/O failures carry their channel
        generation here; only the matching generation is closed.  A late
        failure from an older delivery therefore cannot tear down a healthy
        replacement channel.
        """

        async with self._start_lock:
            if generation is not None and generation != self._generation:
                return False
            self._accepting = False
            await self._close_all_resources()
            return True

    async def _open_resources(self) -> None:
        self._connection = await self._connector()
        self._channel = await self._connection.channel()
        self._generation += 1
        await self._channel.set_qos(prefetch_count=self._prefetch_count)
        await self._open_iterator()

    async def _open_iterator(self) -> None:
        channel = self._channel
        if channel is None:
            raise MessageBrokerUnavailable("RabbitMQ channel is unavailable")
        queue = await channel.declare_queue(self._queue_name, passive=True)
        iterator = queue.iterator(no_ack=False)
        self._iterator = iterator
        self._iterator = await iterator.__aenter__()

    async def get(self) -> RawDelivery | None:
        if not self._accepting:
            return None
        await self.start()
        iterator = self._iterator
        if iterator is None or not self._accepting:
            return None
        generation = self._generation
        try:
            message = await iterator.__anext__()
        except StopAsyncIteration:
            if not self._accepting:
                return None
            self._accepting = False
            raise MessageBrokerUnavailable(
                "RabbitMQ consumer stopped unexpectedly", generation=generation
            ) from None
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            if not self._accepting:
                return None
            self._accepting = False
            raise MessageBrokerUnavailable(
                "RabbitMQ delivery failed", generation=generation
            ) from exc
        return RawDelivery(
            body=bytes(message.body),
            delivery_id=_rabbit_delivery_id(message),
            redelivered=message.redelivered,
            _rabbit_message=message,
            _broker_generation=generation,
        )

    async def stop_accepting(self) -> None:
        """Cancel the consumer but keep its channel alive for in-flight settlements."""
        self._accepting = False
        async with self._start_lock:
            iterator = self._iterator
            self._iterator = None
            await self._close_resource(iterator)

    async def aclose(self) -> None:
        """Close the channel only after WorkerRuntime has drained in-flight deliveries."""
        await self.stop_accepting()
        async with self._start_lock:
            await self._close_all_resources()

    async def _close_all_resources(self) -> bool:
        iterator, channel, connection = self._iterator, self._channel, self._connection
        self._iterator = None
        self._channel = None
        self._connection = None
        await self._close_resource(iterator)
        channel_closed = await self._close_resource(channel)
        connection_closed = await self._close_resource(connection)
        if (channel is not None or connection is not None) and not (
            (channel is not None and channel_closed)
            or (connection is not None and connection_closed)
        ):
            # A timed-out cancellation must not be followed by a second
            # consumer while the first connection can still hold deliveries.
            self._channel = channel
            self._connection = connection
            return False
        return True

    async def _close_resource(
        self,
        resource: RabbitQueueIterator | RabbitChannel | RabbitConnection | None,
    ) -> bool:
        if resource is None:
            return True
        try:
            await asyncio.wait_for(resource.close(), timeout=self._close_timeout_seconds)
        except Exception:
            # Shutdown remains bounded and best-effort; Rabbit requeues unacked deliveries.
            return False
        return True


def _rabbit_delivery_id(message: RabbitIncomingMessage) -> str:
    if message.message_id:
        return message.message_id
    if message.delivery_tag is not None:
        return str(message.delivery_tag)
    return str(uuid4())


def aio_pika_connector(url: str, *, timeout_seconds: float) -> RabbitConnector:
    """Build the production connector without opening a connection or logging its URL."""

    if not url:
        raise ValueError("RabbitMQ URL is required")
    if not 0 < timeout_seconds <= 30:
        raise ValueError("RabbitMQ connection timeout must be between 0 and 30 seconds")

    async def connect() -> RabbitConnection:
        connection = await aio_pika.connect_robust(url, timeout=timeout_seconds)
        return cast(RabbitConnection, connection)

    return connect


def build_message_broker(settings: "AppSettings") -> MessageBroker:
    """Assemble only the explicitly selected broker; never fall back from RabbitMQ."""

    if settings.worker_broker == "LOCAL":
        return LocalMessageBroker()
    url = settings.rabbitmq_url
    queue_name = settings.rabbitmq_queue_name
    if url is None:
        raise MessageBrokerConfigurationError("RabbitMQ URL must be configured")
    return RabbitMessageBroker(
        queue_name=queue_name,
        prefetch_count=settings.rabbitmq_prefetch_count,
        connector=aio_pika_connector(
            url.get_secret_value(),
            timeout_seconds=settings.rabbitmq_connection_timeout_seconds,
        ),
        startup_timeout_seconds=settings.rabbitmq_connection_timeout_seconds,
    )

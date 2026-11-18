"""Qwen OpenAI-compatible adapter with bounded retries and safe failures."""

import asyncio
import json
from dataclasses import dataclass
from time import monotonic

import httpx
from pydantic import BaseModel, ConfigDict, Field, JsonValue, ValidationError

from app.agent.models import Intent
from app.common.enums import ProviderStatus
from app.common.settings import ProviderSettings
from app.harness.runtime import record_model_call
from app.model_gateway.gateway import (
    TASK_PARAMETER_KEYS,
    DeltaHandler,
    GatewayUsage,
    IntentResult,
    IntentRoute,
    ModelGatewayError,
    ModelIdentity,
    ModelMessage,
    ModelMessages,
    StructuredResponseDiagnostics,
    TaskParametersResult,
    TextResult,
    filter_task_parameters,
    serialize_messages,
)
from app.observability.tracing import SafeSpan, current_otel_traceparent, start_span

DEFAULT_QWEN_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1"


class _ProviderModel(BaseModel):
    model_config = ConfigDict(extra="ignore")


class _Usage(_ProviderModel):
    prompt_tokens: int = Field(default=0, ge=0)
    completion_tokens: int = Field(default=0, ge=0)


class _ModelEntry(_ProviderModel):
    id: str


class _ModelList(_ProviderModel):
    data: list[_ModelEntry]


class _Message(_ProviderModel):
    content: str | None = None


class _Choice(_ProviderModel):
    message: _Message
    finish_reason: str | None = None


class _Completion(_ProviderModel):
    id: str | None = Field(default=None, max_length=256)
    choices: list[_Choice] = Field(min_length=1)
    usage: _Usage | None = None


class _Delta(_ProviderModel):
    content: str | None = None


class _StreamChoice(_ProviderModel):
    delta: _Delta


class _StreamChunk(_ProviderModel):
    choices: list[_StreamChoice]
    usage: _Usage | None = None


class _TaskParametersPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    summary: str = Field(min_length=1, max_length=500)
    parameters: dict[str, JsonValue] = Field(default_factory=dict, max_length=16)


@dataclass(frozen=True, slots=True)
class _CompletionResult:
    completion: _Completion
    usage: GatewayUsage


class QwenAdapter:
    """Translate the Agent's bounded text needs to DashScope's compatible API."""

    is_mock = False

    def __init__(
        self,
        settings: ProviderSettings,
        *,
        client: httpx.AsyncClient | None = None,
        identity_provider: str = "QWEN",
    ) -> None:
        if settings.credential is None:
            raise ModelGatewayError("QWEN_CREDENTIAL_NOT_CONFIGURED")
        if settings.model is None:
            raise ModelGatewayError("QWEN_MODEL_NOT_CONFIGURED")
        endpoint = (settings.endpoint or DEFAULT_QWEN_ENDPOINT).rstrip("/")
        self._settings = settings
        self._identity = ModelIdentity(
            provider=identity_provider,
            model=settings.model,
            version="dashscope-openai-compatible-v1",
        )
        self._status = ProviderStatus.CONFIGURED
        self._last_error_code: str | None = None
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(
            base_url=endpoint,
            headers={
                "Authorization": f"Bearer {settings.credential.get_secret_value()}",
                "Content-Type": "application/json",
            },
            timeout=httpx.Timeout(settings.timeout_seconds),
            trust_env=settings.trust_env,
        )

    @property
    def identity(self) -> ModelIdentity:
        return self._identity

    @property
    def status(self) -> ProviderStatus:
        return self._status

    @property
    def last_error_code(self) -> str | None:
        return self._last_error_code

    async def probe(self) -> bool:
        try:
            response = await self._client.get(
                "/models",
                timeout=self._settings.probe_timeout_seconds,
            )
            if not response.is_success:
                raise self._http_error(response.status_code)
            if not self._model_is_listed(response):
                raise ModelGatewayError("QWEN_MODEL_UNAVAILABLE")
        except httpx.TimeoutException:
            self._mark_unavailable("QWEN_TIMEOUT")
            return False
        except httpx.RequestError:
            self._mark_unavailable("QWEN_CONNECTION_FAILED")
            return False
        except ModelGatewayError as exc:
            self._mark_unavailable(exc.code)
            return False
        self._mark_available()
        return True

    async def classify_intent(self, messages: ModelMessages) -> IntentResult:
        total_usage = GatewayUsage()
        active_messages = messages
        last_stage = "unknown"
        with start_span(
            "model.qwen.intent-router",
            attributes={
                "model.provider": self._identity.provider,
                "model.name": self._identity.model,
                "model.operation": "intent",
                "message.count": len(messages),
            },
        ) as span:
            for structured_attempt in range(2):
                payload: dict[str, object] = {
                    "model": self._identity.model,
                    "messages": serialize_messages(active_messages),
                    "response_format": {"type": "json_object"},
                    "enable_thinking": False,
                    "preserve_thinking": False,
                    "temperature": 0,
                    "max_tokens": 384,
                }
                try:
                    result = await self._post_completion(payload)
                except ModelGatewayError as exc:
                    total_usage += exc.usage
                    self._record_span_usage(span, total_usage)
                    span.fail(exc.code)
                    raise ModelGatewayError(
                        exc.code,
                        retryable=exc.retryable,
                        usage=total_usage,
                        failure_stage=exc.failure_stage,
                        attempt_count=structured_attempt + 1,
                    ) from exc
                total_usage += result.usage
                completion = result.completion
                choice = completion.choices[0]
                raw_content = choice.message.content
                if choice.finish_reason not in {None, "stop"}:
                    last_stage = f"finish_reason_{choice.finish_reason}"
                elif not raw_content or not raw_content.strip():
                    last_stage = "empty_content"
                else:
                    try:
                        decoded = json.loads(self._strip_json_fence(raw_content))
                        parsed = IntentRoute.model_validate(decoded)
                    except (json.JSONDecodeError, ValidationError, TypeError):
                        last_stage = "invalid_schema"
                    else:
                        self._record_span_usage(span, total_usage)
                        span.set("attemptCount", structured_attempt + 1)
                        return IntentResult(
                            route=parsed,
                            usage=total_usage,
                            diagnostics=StructuredResponseDiagnostics(
                                mode="JSON_OBJECT",
                                attempt_count=structured_attempt + 1,
                                first_attempt_succeeded=structured_attempt == 0,
                                recovered_by_retry=structured_attempt == 1,
                            ),
                        )
                if structured_attempt == 0:
                    active_messages = (
                        ModelMessage(
                            role="system",
                            content=(
                                messages[0].content
                                + "\nReturn exactly one valid intent-router-v2.2 JSON object; "
                                "use only declared enums and no extra keys."
                            ),
                        ),
                        *messages[1:],
                    )
            self._record_span_usage(span, total_usage)
            span.set("attemptCount", 2)
            span.fail("QWEN_INTENT_RESPONSE_INVALID")
        raise ModelGatewayError(
            "QWEN_INTENT_RESPONSE_INVALID",
            usage=total_usage,
            structured_response_failure=True,
            failure_stage=last_stage,
            attempt_count=2,
        )

    async def answer_general(
        self,
        messages: ModelMessages,
        *,
        on_delta: DeltaHandler | None = None,
    ) -> TextResult:
        started = monotonic()
        payload: dict[str, object] = {
            "model": self._identity.model,
            "messages": serialize_messages(messages),
            "stream": True,
            "stream_options": {"include_usage": True},
            "enable_thinking": False,
            "preserve_thinking": False,
            "temperature": 0.2,
            "max_tokens": 1024,
        }
        text_parts: list[str] = []
        final_usage: _Usage | None = None
        try:
            record_model_call()
            async with self._client.stream("POST", "/chat/completions", json=payload) as response:
                if not response.is_success:
                    raise self._http_error(response.status_code)
                async for line in response.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    data = line.removeprefix("data:").strip()
                    if not data or data == "[DONE]":
                        continue
                    try:
                        chunk = _StreamChunk.model_validate_json(data)
                    except ValidationError as exc:
                        raise self._invalid_response("QWEN_STREAM_RESPONSE_INVALID") from exc
                    if chunk.usage is not None:
                        final_usage = chunk.usage
                    for choice in chunk.choices:
                        delta = choice.delta.content
                        if not delta:
                            continue
                        text_parts.append(delta)
                        if on_delta is not None:
                            await on_delta(delta)
        except httpx.TimeoutException as exc:
            self._mark_unavailable("QWEN_TIMEOUT")
            raise ModelGatewayError("QWEN_TIMEOUT", retryable=True) from exc
        except httpx.RequestError as exc:
            self._mark_unavailable("QWEN_CONNECTION_FAILED")
            raise ModelGatewayError("QWEN_CONNECTION_FAILED", retryable=True) from exc
        except ModelGatewayError as exc:
            self._mark_unavailable(exc.code)
            raise

        text = "".join(text_parts).strip()
        if not text:
            raise self._invalid_response("QWEN_EMPTY_RESPONSE")
        self._mark_available()
        return TextResult(text=text, usage=self._usage(final_usage, started))

    async def suggest_task_parameters(
        self,
        intent: Intent,
        messages: ModelMessages,
    ) -> TaskParametersResult:
        allowed_keys = TASK_PARAMETER_KEYS.get(intent)
        if allowed_keys is None:
            raise ModelGatewayError("QWEN_TASK_INTENT_UNSUPPORTED")
        payload: dict[str, object] = {
            "model": self._identity.model,
            "messages": serialize_messages(messages),
            "response_format": {"type": "json_object"},
            "enable_thinking": False,
            "preserve_thinking": False,
            "temperature": 0,
            "max_tokens": 512,
        }
        result = await self._post_completion(payload)
        completion = result.completion
        raw_content = completion.choices[0].message.content
        if not raw_content:
            raise self._invalid_response("QWEN_TASK_RESPONSE_INVALID")
        try:
            decoded = json.loads(self._strip_json_fence(raw_content))
            parsed = _TaskParametersPayload.model_validate(decoded)
        except (json.JSONDecodeError, ValidationError, TypeError) as exc:
            raise self._invalid_response("QWEN_TASK_RESPONSE_INVALID") from exc
        parameters, dropped = filter_task_parameters(intent, parsed.parameters)
        warnings = ("有些不适用的内容没有加入草稿，请在确认前核对。",) if dropped else ()
        return TaskParametersResult(
            summary=parsed.summary,
            parameters=parameters,
            warnings=warnings,
            usage=result.usage,
        )

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def _post_completion(self, payload: dict[str, object]) -> _CompletionResult:
        last_error: ModelGatewayError | None = None
        total_usage = GatewayUsage()
        for attempt in range(self._settings.max_retries + 1):
            started = monotonic()
            try:
                record_model_call()
                traceparent = current_otel_traceparent()
                headers = {"traceparent": traceparent} if traceparent is not None else None
                response = await self._client.post(
                    "/chat/completions",
                    json=payload,
                    headers=headers,
                )
                if response.is_success:
                    try:
                        completion = _Completion.model_validate_json(response.content)
                    except ValidationError as exc:
                        total_usage += GatewayUsage(
                            latency_ms=self._latency_since(started),
                            request_count=1,
                        )
                        self._mark_unavailable("QWEN_COMPLETION_RESPONSE_INVALID")
                        raise ModelGatewayError(
                            "QWEN_COMPLETION_RESPONSE_INVALID",
                            usage=total_usage,
                            structured_response_failure=True,
                            failure_stage="completion_envelope",
                        ) from exc
                    total_usage += self._usage(completion.usage, started)
                    self._mark_available()
                    return _CompletionResult(completion=completion, usage=total_usage)
                total_usage += GatewayUsage(
                    latency_ms=self._latency_since(started),
                    request_count=1,
                )
                last_error = self._http_error(response.status_code)
            except httpx.TimeoutException as exc:
                total_usage += GatewayUsage(
                    latency_ms=self._latency_since(started),
                    request_count=1,
                )
                last_error = ModelGatewayError(
                    "QWEN_TIMEOUT",
                    retryable=True,
                    usage=total_usage,
                )
                last_error.__cause__ = exc
            except httpx.RequestError as exc:
                total_usage += GatewayUsage(
                    latency_ms=self._latency_since(started),
                    request_count=1,
                )
                last_error = ModelGatewayError(
                    "QWEN_CONNECTION_FAILED",
                    retryable=True,
                    usage=total_usage,
                )
                last_error.__cause__ = exc
            if not last_error.retryable or attempt >= self._settings.max_retries:
                self._mark_unavailable(last_error.code)
                raise ModelGatewayError(
                    last_error.code,
                    retryable=last_error.retryable,
                    usage=total_usage,
                    failure_stage=last_error.failure_stage,
                ) from last_error
            await asyncio.sleep(0.2 * (2**attempt))
        raise RuntimeError("unreachable")

    @staticmethod
    def _http_error(status_code: int) -> ModelGatewayError:
        if status_code in {401, 403}:
            return ModelGatewayError("QWEN_AUTH_FAILED")
        if status_code == 429:
            return ModelGatewayError("QWEN_RATE_LIMITED", retryable=True)
        if status_code >= 500:
            return ModelGatewayError("QWEN_PROVIDER_UNAVAILABLE", retryable=True)
        return ModelGatewayError("QWEN_REQUEST_REJECTED")

    def _model_is_listed(self, response: httpx.Response) -> bool:
        try:
            payload = _ModelList.model_validate_json(response.content)
        except ValidationError:
            return False
        return any(item.id == self._identity.model for item in payload.data)

    def _invalid_response(self, code: str) -> ModelGatewayError:
        self._mark_unavailable(code)
        return ModelGatewayError(code, retryable=True)

    def _mark_available(self) -> None:
        self._status = ProviderStatus.AVAILABLE
        self._last_error_code = None

    def _mark_unavailable(self, code: str) -> None:
        self._status = ProviderStatus.UNAVAILABLE
        self._last_error_code = code

    @staticmethod
    def _usage(usage: _Usage | None, started: float) -> GatewayUsage:
        return GatewayUsage(
            input_tokens=usage.prompt_tokens if usage else 0,
            output_tokens=usage.completion_tokens if usage else 0,
            latency_ms=max(0, round((monotonic() - started) * 1_000)),
            request_count=1,
        )

    @staticmethod
    def _latency_since(started: float) -> int:
        return max(0, round((monotonic() - started) * 1_000))

    @staticmethod
    def _record_span_usage(span: SafeSpan, usage: GatewayUsage) -> None:
        span.set("usage.inputTokens", usage.input_tokens)
        span.set("usage.outputTokens", usage.output_tokens)
        span.set("usage.requestCount", usage.request_count)
        span.set("provider.latencyMs", usage.latency_ms)

    @staticmethod
    def _strip_json_fence(value: str) -> str:
        stripped = value.strip()
        if stripped.startswith("```") and stripped.endswith("```"):
            lines = stripped.splitlines()
            return "\n".join(lines[1:-1]).strip()
        return stripped

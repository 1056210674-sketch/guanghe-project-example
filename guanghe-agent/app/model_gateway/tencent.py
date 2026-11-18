"""TokenHub text completion using the existing bounded compatible transport."""

import httpx

from app.common.settings import ProviderSettings
from app.model_gateway.gateway import (
    DeltaHandler,
    ModelGatewayError,
    ModelIdentity,
    ModelMessages,
    TextResult,
    serialize_messages,
)
from app.model_gateway.qwen import QwenAdapter
from app.observability.tracing import start_span


class TencentHy3Adapter(QwenAdapter):
    """Search is external; this gateway never claims or enables native web search."""

    def __init__(self, settings: ProviderSettings, *, client: httpx.AsyncClient | None = None):
        endpoint = settings.endpoint or "https://tokenhub.tencentmaas.com/v1/chat/completions"
        if endpoint != "https://tokenhub.tencentmaas.com/v1/chat/completions":
            raise ValueError("GEO_YUANBAO_ENDPOINT_INVALID")
        super().__init__(
            settings.model_copy(
                update={"endpoint": endpoint.removesuffix("/chat/completions"), "max_retries": 0}
            ),
            client=client,
            identity_provider="YUANBAO",
        )
        self._identity = ModelIdentity(
            provider="YUANBAO", model=settings.model or "UNCONFIGURED", version="tokenhub-chat-v1"
        )

    async def answer_general(
        self, messages: ModelMessages, *, on_delta: DeltaHandler | None = None
    ) -> TextResult:
        with start_span(
            "model.tencent.response",
            attributes={"model.provider": "YUANBAO", "model.name": self.identity.model},
        ) as span:
            try:
                result = await self._post_completion(
                    {
                        "model": self.identity.model,
                        "messages": serialize_messages(messages),
                        "thinking": {"type": "disabled"},
                        "stream": False,
                        "temperature": 0.2,
                        "max_tokens": 2048,
                    }
                )
                self._record_span_usage(span, result.usage)
                choice = result.completion.choices[0]
                text = (choice.message.content or "").strip()
                if not text or choice.finish_reason != "stop":
                    raise ModelGatewayError("GEO_YUANBAO_ANSWER_INVALID", usage=result.usage)
                if on_delta is not None:
                    await on_delta(text)
                return TextResult(text=text, usage=result.usage)
            except ModelGatewayError as exc:
                code = exc.code.replace("QWEN_", "GEO_YUANBAO_", 1)
                self._record_span_usage(span, exc.usage)
                span.fail(code)
                raise ModelGatewayError(code, usage=exc.usage, retryable=exc.retryable) from exc

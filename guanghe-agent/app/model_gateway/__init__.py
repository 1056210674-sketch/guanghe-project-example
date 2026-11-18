"""Central model gateway package; Provider calls must pass through here."""

from app.common.settings import AppSettings
from app.model_gateway.deepseek import DeepSeekAdapter
from app.model_gateway.gateway import LocalModelGateway, ModelGateway, ModelGatewayError


def build_model_gateway(settings: AppSettings) -> ModelGateway:
    """Resolve the DeepSeek-first conversation gateway without runtime fallback."""
    selected = settings.model_gateway
    if selected == "AUTO":
        deepseek_requested = (
            settings.environment == "production"
            or settings.deepseek.credential is not None
            or settings.deepseek.model is not None
        )
        selected = "DEEPSEEK" if deepseek_requested else "LOCAL"
    if selected == "QWEN":
        raise ModelGatewayError("QWEN_CONVERSATION_GATEWAY_DISABLED")
    if selected == "DEEPSEEK":
        if not settings.deepseek.enabled:
            raise ModelGatewayError("DEEPSEEK_DISABLED")
        return DeepSeekAdapter(settings.deepseek)
    if settings.environment == "production":
        raise ModelGatewayError("LOCAL_MODEL_GATEWAY_FORBIDDEN_IN_PRODUCTION")
    return LocalModelGateway()


__all__ = ["build_model_gateway"]

"""Provider-neutral model gateway boundary and selection tests."""

import asyncio

import pytest
from pydantic import SecretStr

from app.agent.models import Intent
from app.common.settings import AppSettings, ProviderSettings
from app.model_gateway import build_model_gateway
from app.model_gateway.gateway import ModelGatewayError, filter_task_parameters


def test_filter_task_parameters_omits_blank_optional_values() -> None:
    filtered, dropped_unknown = filter_task_parameters(
        Intent.IMAGE,
        {
            "scene": "  厨房台面  ",
            "negativePrompt": "   ",
            "count": 0,
            "unknown": "must-not-cross-boundary",
        },
    )

    assert filtered == {"scene": "厨房台面", "count": 0}
    assert dropped_unknown is True


def test_filter_task_parameters_keeps_only_domain_specific_media_fields() -> None:
    image, image_dropped = filter_task_parameters(
        Intent.IMAGE,
        {"scene": "厨房台面", "resolution": "1K"},
    )
    video, video_dropped = filter_task_parameters(
        Intent.VIDEO,
        {
            "durationSeconds": 5,
            "resolution": "720P",
            "audio": False,
            "negativePrompt": "not supported for Wan V1",
        },
    )

    assert image == {"scene": "厨房台面"}
    assert image_dropped is True
    assert video == {"durationSeconds": 5, "resolution": "720P", "audio": False}
    assert video_dropped is True


def test_explicit_deepseek_never_selects_qwen_or_local() -> None:
    settings = AppSettings(
        environment="test",
        model_gateway="DEEPSEEK",
        deepseek=ProviderSettings(
            credential=SecretStr("deepseek-unit-test-secret"),
            model="deepseek-v4-flash",
            endpoint="https://deepseek.test",
        ),
        qwen=ProviderSettings(
            credential=SecretStr("qwen-unit-test-secret"),
            model="qwen-test-model",
            endpoint="https://qwen.test",
        ),
    )

    gateway = build_model_gateway(settings)

    assert gateway.identity.provider == "DEEPSEEK"
    assert gateway.identity.model == "deepseek-v4-flash"
    assert gateway.is_mock is False
    asyncio.run(gateway.aclose())


@pytest.mark.parametrize(
    ("deepseek", "expected_code"),
    (
        (ProviderSettings(model="deepseek-v4-flash"), "DEEPSEEK_CREDENTIAL_NOT_CONFIGURED"),
        (
            ProviderSettings(credential=SecretStr("deepseek-unit-test-secret")),
            "DEEPSEEK_MODEL_NOT_CONFIGURED",
        ),
        (
            ProviderSettings(
                enabled=False,
                credential=SecretStr("deepseek-unit-test-secret"),
                model="deepseek-v4-flash",
            ),
            "DEEPSEEK_DISABLED",
        ),
    ),
)
def test_explicit_deepseek_configuration_errors_do_not_fallback(
    deepseek: ProviderSettings,
    expected_code: str,
) -> None:
    settings = AppSettings(
        environment="test",
        model_gateway="DEEPSEEK",
        deepseek=deepseek,
        qwen=ProviderSettings(
            credential=SecretStr("qwen-unit-test-secret"),
            model="qwen-test-model",
        ),
    )

    with pytest.raises(ModelGatewayError, match=expected_code) as captured:
        build_model_gateway(settings)

    assert captured.value.code == expected_code


def test_production_still_forbids_explicit_local_gateway() -> None:
    with pytest.raises(
        ModelGatewayError,
        match="LOCAL_MODEL_GATEWAY_FORBIDDEN_IN_PRODUCTION",
    ) as captured:
        build_model_gateway(AppSettings(environment="production", model_gateway="LOCAL"))

    assert captured.value.code == "LOCAL_MODEL_GATEWAY_FORBIDDEN_IN_PRODUCTION"

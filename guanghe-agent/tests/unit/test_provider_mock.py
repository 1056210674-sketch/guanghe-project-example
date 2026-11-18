"""Provider-neutral Mock success and failure classification tests."""

import asyncio
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.common.enums import TaskType
from app.common.settings import ProviderMockSettings
from app.providers.mock import LocalTaskProviderMock
from app.providers.models import (
    ProviderErrorCategory,
    SubmissionState,
    TaskProviderError,
    TaskProviderRequest,
)


def _request(task_type: TaskType) -> TaskProviderRequest:
    return TaskProviderRequest(
        request_id=uuid4(),
        task_id=f"task-{task_type.value.casefold()}",
        task_type=task_type,
        execution_no=1,
        parameters={},
    )


@pytest.mark.parametrize(
    ("task_type", "artifact_type"),
    (
        (TaskType.IMAGE, "IMAGE"),
        (TaskType.VIDEO, "VIDEO"),
        (TaskType.GEO, "GEO_PLATFORM_SAMPLE"),
        (TaskType.ARTICLE, "ARTICLE_CONTENT"),
    ),
)
def test_mock_returns_explicitly_synthetic_domain_result(
    task_type: TaskType,
    artifact_type: str,
) -> None:
    result = asyncio.run(
        LocalTaskProviderMock(ProviderMockSettings(latency_ms=0)).execute(_request(task_type))
    )

    assert result.provider == "LOCAL_TASK_PROVIDER"
    assert result.submission_state is SubmissionState.CONFIRMED
    assert result.artifacts[0].artifact_type == artifact_type
    assert result.artifacts[0].metadata["mock"] is True


@pytest.mark.parametrize(
    ("failure_mode", "code", "category", "submission_state", "retryable"),
    (
        ("AUTH", "PROVIDER_AUTH_FAILED", ProviderErrorCategory.AUTH, "NOT_SUBMITTED", False),
        (
            "RATE_LIMIT",
            "PROVIDER_RATE_LIMITED",
            ProviderErrorCategory.RATE_LIMIT,
            "NOT_SUBMITTED",
            True,
        ),
        (
            "UPSTREAM_5XX",
            "PROVIDER_UPSTREAM_ERROR",
            ProviderErrorCategory.UPSTREAM,
            "NOT_SUBMITTED",
            True,
        ),
        (
            "TIMEOUT",
            "PROVIDER_TIMEOUT",
            ProviderErrorCategory.TIMEOUT,
            "NOT_SUBMITTED",
            True,
        ),
        (
            "INVALID_RESPONSE",
            "PROVIDER_INVALID_RESPONSE",
            ProviderErrorCategory.INVALID_RESPONSE,
            "CONFIRMED",
            False,
        ),
        (
            "SAFETY_REJECTED",
            "PROVIDER_SAFETY_REJECTED",
            ProviderErrorCategory.SAFETY_REJECTED,
            "CONFIRMED",
            False,
        ),
        (
            "UNKNOWN_SUBMIT",
            "PROVIDER_SUBMISSION_UNKNOWN",
            ProviderErrorCategory.TIMEOUT,
            "UNKNOWN",
            False,
        ),
    ),
)
def test_mock_classifies_provider_failure_without_raw_payload(
    failure_mode: str,
    code: str,
    category: ProviderErrorCategory,
    submission_state: str,
    retryable: bool,
) -> None:
    settings = ProviderMockSettings.model_validate({"latency_ms": 0, "failure_mode": failure_mode})

    with pytest.raises(TaskProviderError) as captured:
        asyncio.run(LocalTaskProviderMock(settings).execute(_request(TaskType.IMAGE)))

    assert captured.value.code == code
    assert captured.value.category is category
    assert captured.value.submission_state.value == submission_state
    assert captured.value.retryable is retryable


def test_provider_contract_rejects_java_owned_media() -> None:
    with pytest.raises(ValidationError, match="MEDIA is owned by Java"):
        _request(TaskType.MEDIA)

"""Strict shared Rabbit schema validation at the Worker ingress boundary."""

import json
from copy import deepcopy
from datetime import UTC, datetime
from typing import Any

import pytest

from app.workers.models import TaskExecutionMessage
from app.workers.schema_validation import (
    RabbitMessageSchemaInvalid,
    parse_task_execution_message,
)

VALID_MESSAGE: dict[str, Any] = {
    "schemaVersion": 1,
    "eventId": "1900012345678906000",
    "eventType": "TASK_EXECUTION_REQUESTED",
    "taskId": "1900012345678901700",
    "taskType": "IMAGE",
    "userId": "1900012345678901001",
    "spaceId": "1900012345678901100",
    "attempt": 1,
    "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
    "createdAt": "2026-08-31T10:00:00+08:00",
}


def _encoded(message: object) -> bytes:
    return json.dumps(message, separators=(",", ":")).encode()


def test_valid_shared_message_is_parsed_after_json_schema_validation() -> None:
    parsed = parse_task_execution_message(_encoded(VALID_MESSAGE))

    assert isinstance(parsed, TaskExecutionMessage)
    assert parsed.event_id == VALID_MESSAGE["eventId"]
    assert parsed.traceparent == VALID_MESSAGE["traceparent"]


@pytest.mark.parametrize(
    ("field", "invalid_value"),
    [
        ("eventId", "event-1"),
        ("taskId", "0"),
        ("userId", "01"),
        ("spaceId", -1),
        ("attempt", 0),
        ("attempt", "1"),
        ("taskType", "MEDIA"),
        ("traceparent", None),
        ("traceparent", "00-invalid"),
        ("createdAt", "2026-08-31T10:00:00"),
    ],
)
def test_contract_invalid_fields_are_rejected_before_consumption(
    field: str, invalid_value: object
) -> None:
    message = deepcopy(VALID_MESSAGE)
    message[field] = invalid_value

    with pytest.raises(RabbitMessageSchemaInvalid):
        parse_task_execution_message(_encoded(message))


def test_missing_traceparent_and_unknown_properties_are_rejected() -> None:
    missing_trace = deepcopy(VALID_MESSAGE)
    missing_trace.pop("traceparent")
    extra_property = {**VALID_MESSAGE, "prompt": "must never cross Rabbit"}

    with pytest.raises(RabbitMessageSchemaInvalid):
        parse_task_execution_message(_encoded(missing_trace))
    with pytest.raises(RabbitMessageSchemaInvalid):
        parse_task_execution_message(_encoded(extra_property))


@pytest.mark.parametrize(
    "traceparent",
    [
        "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
    ],
)
def test_structurally_matching_but_w3c_forbidden_traceparent_is_rejected(
    traceparent: str,
) -> None:
    message = deepcopy(VALID_MESSAGE)
    message["traceparent"] = traceparent

    with pytest.raises(RabbitMessageSchemaInvalid):
        parse_task_execution_message(_encoded(message))


def test_attempt_has_no_private_python_maximum_beyond_the_shared_schema() -> None:
    parsed = TaskExecutionMessage.model_validate(
        {
            **VALID_MESSAGE,
            "attempt": 11,
            "createdAt": datetime.now(UTC),
        }
    )

    assert parsed.attempt == 11

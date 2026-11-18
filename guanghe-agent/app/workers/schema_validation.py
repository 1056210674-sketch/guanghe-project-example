"""Strict Rabbit JSON Schema validation before DTO parsing."""

import json
from collections.abc import Callable
from functools import lru_cache
from pathlib import Path
from typing import Any, cast

from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import SchemaError
from jsonschema.exceptions import ValidationError as JsonSchemaValidationError
from pydantic import ValidationError as PydanticValidationError

from app.workers.models import TaskExecutionMessage

RABBIT_SCHEMA_PATH = (
    Path(__file__).resolve().parents[2]
    / "contracts"
    / "rabbit"
    / "task-execution-requested-v1.schema.json"
)


class RabbitMessageSchemaInvalid(ValueError):
    """Publicly safe marker for a malformed or contract-invalid Rabbit message."""


@lru_cache(maxsize=1)
def _task_execution_validator() -> Draft202012Validator:
    try:
        document = json.loads(RABBIT_SCHEMA_PATH.read_text(encoding="utf-8"))
        schema = cast(dict[str, Any], document)
        Draft202012Validator.check_schema(schema)
    except (OSError, json.JSONDecodeError, SchemaError) as exc:
        raise RuntimeError("Rabbit contract schema is unavailable or invalid") from exc
    return Draft202012Validator(schema, format_checker=FormatChecker())


def ensure_task_execution_schema_ready() -> None:
    """Load and self-check the shared contract before accepting deliveries."""
    _task_execution_validator()


def parse_task_execution_message(body: bytes) -> TaskExecutionMessage:
    """Validate raw UTF-8 JSON against the shared schema, then create the typed DTO."""
    try:
        document: object = json.loads(body.decode("utf-8"))
        validate = cast(
            Callable[[object], None],
            getattr(_task_execution_validator(), "validate"),  # noqa: B009 - untyped library
        )
        validate(document)
        return TaskExecutionMessage.model_validate(document)
    except (
        UnicodeDecodeError,
        json.JSONDecodeError,
        JsonSchemaValidationError,
        PydanticValidationError,
        ValueError,
    ) as exc:
        raise RabbitMessageSchemaInvalid("Rabbit message does not match schema v1") from exc

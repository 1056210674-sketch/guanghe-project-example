"""Runtime OpenAPI augmentation for the frozen internal HMAC contract."""

from typing import Any, cast

from fastapi import FastAPI
from pydantic import BaseModel

from app.agent.models import (
    BusinessOutcome,
    MessageDeltaPayload,
    RunProgressPayload,
    TaskDraftProposal,
    Usage,
)
from app.article.models import ArticleExecutionRequest, ArticleExecutionResult

INTERNAL_PREFIX = "/internal/v1/"
READINESS_PATH = "/internal/v1/health/ready"
INTERNAL_HMAC_SCHEME = "InternalHmac"
INTERNAL_PARAMETER_NAMES = (
    "InternalKeyId",
    "InternalTimestamp",
    "InternalNonce",
    "ContentSha256",
    "InternalSignature",
    "Traceparent",
)
HTTP_METHODS = frozenset({"get", "put", "post", "delete", "options", "head", "patch", "trace"})


def configure_internal_openapi(application: FastAPI) -> None:
    """Add the security boundary to the generated schema and cache that exact document."""
    schema = application.openapi()
    components = cast(dict[str, Any], schema.setdefault("components", {}))
    schemas = cast(dict[str, Any], components.setdefault("schemas", {}))
    _add_model_schema(schemas, "Usage", Usage)
    _add_model_schema(schemas, "MessageDeltaPayload", MessageDeltaPayload)
    _add_model_schema(schemas, "BusinessOutcome", BusinessOutcome)
    # Internal optional fields use None; the public wire omits them instead of sending null.
    outcome_properties = cast(dict[str, Any], schemas["BusinessOutcome"]["properties"])
    for field_name in ("reasonCode", "failureStage"):
        nullable_property = cast(dict[str, Any], outcome_properties[field_name])
        variants = cast(list[dict[str, Any]], nullable_property["anyOf"])
        outcome_properties[field_name] = {
            **next(variant for variant in variants if variant.get("type") != "null"),
            **{
                key: value
                for key, value in nullable_property.items()
                if key not in {"anyOf", "default"}
            },
        }
    _add_model_schema(schemas, "TaskDraftProposal", TaskDraftProposal)
    _add_model_schema(schemas, "ArticleExecutionRequest", ArticleExecutionRequest)
    _add_model_schema(schemas, "ArticleExecutionResult", ArticleExecutionResult)
    progress_schema = RunProgressPayload.model_json_schema(
        ref_template="#/components/schemas/{model}"
    )
    progress_definitions = cast(dict[str, Any], progress_schema.pop("$defs", {}))
    schemas.update(progress_definitions)
    schemas["RunProgressPayload"] = progress_schema
    chat_run_event = cast(dict[str, Any], schemas.get("ChatRunEvent", {}))
    event_constraints = cast(list[dict[str, Any]], chat_run_event.setdefault("allOf", []))
    event_constraints.append(
        {
            "if": {
                "properties": {"eventType": {"const": "message.delta"}},
                "required": ["eventType"],
            },
            "then": {
                "properties": {"payload": {"$ref": "#/components/schemas/MessageDeltaPayload"}},
            },
        }
    )
    geo_context_constraint = {
        "if": {
            "properties": {"eventType": {"const": "run.completed"}},
            "required": ["eventType"],
        },
        "then": {
            "properties": {
                "payload": {
                    "properties": {
                        "businessOutcome": {
                            "$ref": "#/components/schemas/BusinessOutcome",
                            "description": (
                                "Runtime-owned business result. Sent only when both per-Run "
                                "authorization and Python output configuration enable it. "
                                "Java verifies actual drafts before DRAFT_READY. "
                                "Omitted on STOPPED."
                            ),
                        },
                        "geoContextUsed": {
                            "type": "boolean",
                            "description": (
                                "True only when this Run used the GEO readonly renderer. "
                                "Java derives action metadata solely from this Run's frozen "
                                "authorized context, never from model-generated IDs. "
                                "Omitted for ordinary replies, drafts, or stopped Runs."
                            ),
                        },
                    }
                }
            }
        },
    }
    if geo_context_constraint not in event_constraints:
        event_constraints.append(geo_context_constraint)
    usage_constraint = {
        "if": {
            "properties": {"eventType": {"const": "usage.reported"}},
            "required": ["eventType"],
        },
        "then": {
            "properties": {"payload": {"$ref": "#/components/schemas/Usage"}},
        },
    }
    if usage_constraint not in event_constraints:
        event_constraints.append(usage_constraint)
    progress_constraint = {
        "if": {
            "properties": {"eventType": {"const": "run.progress"}},
            "required": ["eventType"],
        },
        "then": {
            "properties": {"payload": {"$ref": "#/components/schemas/RunProgressPayload"}},
        },
    }
    if progress_constraint not in event_constraints:
        event_constraints.append(progress_constraint)
    draft_constraint = {
        "if": {
            "properties": {"eventType": {"const": "task-draft.proposed"}},
            "required": ["eventType"],
        },
        "then": {"properties": {"payload": {"$ref": "#/components/schemas/TaskDraftProposal"}}},
    }
    if draft_constraint not in event_constraints:
        event_constraints.append(draft_constraint)
    security_schemes = cast(dict[str, Any], components.setdefault("securitySchemes", {}))
    security_schemes[INTERNAL_HMAC_SCHEME] = {
        "type": "apiKey",
        "in": "header",
        "name": "X-Internal-Signature",
        "description": (
            "HMAC-SHA256 Base64 signature. The companion key ID, epoch-second timestamp, "
            "UUID nonce, and lowercase body SHA-256 headers are mandatory."
        ),
    }
    parameters = cast(dict[str, Any], components.setdefault("parameters", {}))
    parameters.update(_internal_parameters())

    paths = cast(dict[str, Any], schema.get("paths", {}))
    for path, raw_path_item in paths.items():
        if not path.startswith(INTERNAL_PREFIX):
            continue
        path_item = cast(dict[str, Any], raw_path_item)
        for method, raw_operation in path_item.items():
            if method not in HTTP_METHODS:
                continue
            operation = cast(dict[str, Any], raw_operation)
            responses = cast(dict[str, Any], operation.setdefault("responses", {}))
            if path == READINESS_PATH:
                operation["security"] = []
                responses.setdefault(
                    "403",
                    {"description": "Readiness is accessible only by a direct loopback caller."},
                )
                continue
            operation["security"] = [{INTERNAL_HMAC_SCHEME: []}]
            existing_parameters = cast(list[dict[str, Any]], operation.setdefault("parameters", []))
            existing_references = {parameter.get("$ref") for parameter in existing_parameters}
            required_parameters = [
                {"$ref": f"#/components/parameters/{name}"}
                for name in INTERNAL_PARAMETER_NAMES
                if f"#/components/parameters/{name}" not in existing_references
            ]
            operation["parameters"] = [*required_parameters, *existing_parameters]
            responses.setdefault(
                "401",
                {"description": "Internal authentication failed or the nonce was replayed."},
            )
            responses.setdefault(
                "503",
                {
                    "description": (
                        "Internal authentication is unavailable because its configuration "
                        "or NonceStore dependency is unavailable."
                    ),
                    "content": {
                        "application/json": {
                            "schema": {"$ref": "#/components/schemas/InternalErrorResponse"}
                        }
                    },
                },
            )
    application.openapi_schema = schema


def _add_model_schema(
    schemas: dict[str, Any],
    name: str,
    model: type[BaseModel],
) -> None:
    model_schema = model.model_json_schema(ref_template="#/components/schemas/{model}")
    definitions = cast(dict[str, Any], model_schema.pop("$defs", {}))
    schemas.update(definitions)
    schemas[name] = model_schema


def _internal_parameters() -> dict[str, dict[str, Any]]:
    return {
        "InternalKeyId": _header_parameter("X-Internal-Key-Id", {"type": "string"}),
        "InternalTimestamp": _header_parameter(
            "X-Internal-Timestamp",
            {"type": "string", "pattern": r"^-?[0-9]{1,20}$"},
        ),
        "InternalNonce": _header_parameter(
            "X-Internal-Nonce",
            {
                "type": "string",
                "format": "uuid",
                "pattern": r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            },
            description=(
                "A canonical lowercase UUID. Uppercase or non-canonical forms are rejected "
                "before the Nonce claim."
            ),
        ),
        "ContentSha256": _header_parameter(
            "X-Content-SHA256",
            {"type": "string", "pattern": r"^[0-9a-f]{64}$"},
        ),
        "InternalSignature": _header_parameter(
            "X-Internal-Signature",
            {"type": "string", "maxLength": 128},
        ),
        "Traceparent": _header_parameter(
            "traceparent",
            {
                "type": "string",
                "pattern": r"^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$",
            },
            required=False,
            description="A valid W3C context is continued; otherwise Python creates a new one.",
        ),
    }


def _header_parameter(
    name: str,
    schema: dict[str, Any],
    *,
    required: bool = True,
    description: str | None = None,
) -> dict[str, Any]:
    parameter: dict[str, Any] = {
        "in": "header",
        "name": name,
        "required": required,
        "schema": schema,
    }
    if description is not None:
        parameter["description"] = description
    return parameter

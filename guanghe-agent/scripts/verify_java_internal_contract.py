"""Verify the Java task execution-context contract against strict Worker consumers."""

from __future__ import annotations

import argparse
import json
import sys
from copy import deepcopy
from pathlib import Path
from typing import Any, Protocol, cast
from uuid import UUID

import yaml
from jsonschema import Draft202012Validator
from pydantic import BaseModel, ValidationError

REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from app.agent.creative_action_review import CreativeContinuationCandidate  # noqa: E402
from app.geo.worker_models import GeoDiagnosticTaskParameters  # noqa: E402
from app.providers.java_context import (  # noqa: E402
    AgentIdentity,
    GeoInsightContext,
    MonitorActionContext,
)
from app.providers.resources import (  # noqa: E402
    FrozenResourceSelection,
    ResourceCandidate,
    ResourceReadRequest,
    ResourceReadResponse,
    ResourceReportAttempt,
    ResourceSearchRequest,
    ResourceSearchResponse,
)
from app.workers.java_control_plane import (  # noqa: E402
    _ArticleContentResult,
    _ArticleTaskExecutionContextResponse,
    _GeoDiagnosticAuthorizedContext,
    _GeoDiagnosticCompletionAccepted,
    _GeoTaskExecutionContextResponse,
    _ImageTaskExecutionContextResponse,
    _TaskExecutionEventRequest,
    _VideoTaskExecutionContextResponse,
)

TASK_CONSUMERS: dict[str, type[BaseModel]] = {
    "IMAGE": _ImageTaskExecutionContextResponse,
    "VIDEO": _VideoTaskExecutionContextResponse,
    "GEO": _GeoTaskExecutionContextResponse,
    "ARTICLE": _ArticleTaskExecutionContextResponse,
}


class _SchemaFieldValidator(Protocol):
    def is_valid(self, instance: object) -> bool: ...


def _forbidden_fields(branch: dict[str, Any]) -> set[str]:
    negative = branch.get("not", {})
    direct = {field for field in negative.get("required", []) if isinstance(field, str)}
    conditions = negative.get("anyOf", [])
    return direct | {
        field
        for condition in conditions
        for field in condition.get("required", [])
        if isinstance(field, str)
    }


def verify_contract(path: Path) -> list[str]:
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    context = document["components"]["schemas"]["TaskExecutionContext"]
    producer_properties = set(context.get("properties", {}))
    producer_required = set(context.get("required", []))
    branches: dict[str, list[dict[str, Any]]] = {}
    for branch in context.get("oneOf", []):
        branches.setdefault(branch["properties"]["taskType"]["const"], []).append(branch)
    errors: list[str] = []

    if set(branches) != set(TASK_CONSUMERS):
        errors.append(
            "TaskExecutionContext branches differ: "
            f"java={sorted(branches)} python={sorted(TASK_CONSUMERS)}"
        )
        return errors

    for task_type, consumer in TASK_CONSUMERS.items():
        variants = branches[task_type]
        # A consumer can validate multiple provider variants. Its declared shape
        # is their union; required fields are their intersection. Pair-specific
        # requirements remain enforced by concrete consumer validation tests.
        allowed = set.union(*(producer_properties - _forbidden_fields(b) for b in variants))
        branch_required = set.intersection(
            *(producer_required | set(b.get("required", [])) for b in variants)
        )
        consumer_schema = consumer.model_json_schema(by_alias=True)
        consumer_properties = set(consumer_schema.get("properties", {}))
        consumer_required = set(consumer_schema.get("required", []))

        if consumer_schema.get("additionalProperties") is not False:
            errors.append(f"{task_type}: Python consumer must forbid unknown fields")
        if consumer_properties != allowed:
            errors.append(
                f"{task_type}: top-level fields differ: "
                f"java={sorted(allowed)} python={sorted(consumer_properties)}"
            )
        if consumer_required != branch_required:
            errors.append(
                f"{task_type}: required fields differ: "
                f"java={sorted(branch_required)} python={sorted(consumer_required)}"
            )

        consumer_branch_properties = consumer_schema.get("properties", {})
        for field in ("taskType", "provider", "model"):
            producer_fields = [b.get("properties", {}).get(field) for b in variants]
            consumer_field = consumer_branch_properties.get(field)
            if any(p is None for p in producer_fields) or consumer_field is None:
                continue
            producer_values = {
                value
                for p in producer_fields
                if p is not None
                for value in ([p["const"]] if "const" in p else p.get("enum", []))
            }
            consumer_values = set(
                [consumer_field["const"]]
                if "const" in consumer_field
                else consumer_field.get("enum", [])
            )
            if producer_values and consumer_values != producer_values:
                errors.append(
                    f"{task_type}.{field}: allowed values differ: "
                    f"java={sorted(producer_values)!r} python={sorted(consumer_values)!r}"
                )

    errors.extend(_verify_creative_continuation(document))
    errors.extend(_verify_article_v2(document, branches["ARTICLE"][0]))
    errors.extend(_verify_image_candidates(document))
    errors.extend(_verify_chat_geo_context(document))
    errors.extend(_verify_read_resources(document))
    errors.extend(_verify_agent_identity(document))
    errors.extend(_verify_monitor_action_context(document))
    errors.extend(_verify_article_unknown_failure(document))
    errors.extend(_verify_geo_diagnostic(document, branches["GEO"]))

    return errors


def _verify_creative_continuation(document: dict[str, Any]) -> list[str]:
    """Exercise frozen current selections and bounded history against the real Harness parser."""
    errors: list[str] = []
    schemas = document["components"]["schemas"]
    name = "RunCreativeContinuationEvidence"
    schema = schemas.get(name, {})
    consumer = CreativeContinuationCandidate.model_json_schema(by_alias=True)
    _check_closed_required_shape(errors, name, schema, set(consumer.get("properties", {})))
    candidate: dict[str, Any] = {
        "authorized": False,
        "reviewRequired": True,
        "currentMessageId": "101",
        "conversationId": "102",
        "projectId": None,
        "historyFingerprint": "a" * 64,
        "history": [{"messageId": "100", "role": "USER", "text": "生成咖啡海报"}],
        "historyIncomplete": False,
        "sourceImageAssetId": None,
        "currentAttachmentAssetIds": [],
    }
    probes: list[tuple[str, dict[str, Any], bool]] = [("valid", candidate, True)]
    for identifiers in (["103"], [str(index + 1) for index in range(8)]):
        probes.append(
            (
                "currentAttachmentAssetIds.currentOnly",
                {**candidate, "history": [], "currentAttachmentAssetIds": identifiers},
                True,
            )
        )
    probes.append(
        (
            "currentAttachmentAssetIds.withHistory",
            {**candidate, "currentAttachmentAssetIds": ["103"]},
            True,
        )
    )
    for field, value in (
        ("authorized", True),
        ("reviewRequired", False),
        ("projectId", "0"),
        ("sourceImageAssetId", "0"),
        ("historyFingerprint", "A" * 64),
        ("history", []),
        ("history", [{"messageId": str(i + 1), "role": "USER", "text": "x"} for i in range(7)]),
        ("currentAttachmentAssetIds", [str(index + 1) for index in range(9)]),
        ("currentAttachmentAssetIds", ["103", "103"]),
        ("currentAttachmentAssetIds", ["0"]),
        ("currentAttachmentAssetIds", ["01"]),
        ("currentAttachmentAssetIds", ["1" * 20]),
        ("currentAttachmentAssetIds", [103]),
        ("currentAttachmentAssetIds", [True]),
        ("currentAttachmentAssetIds", [None]),
        ("currentAttachmentAssetIds", None),
        ("unknown", "x"),
    ):
        probes.append((field, {**candidate, field: value}, False))
    for field, value in (("role", "SYSTEM"), ("text", "x" * 16001), ("unknown", "x")):
        probes.append(
            (
                "history." + field,
                {
                    **candidate,
                    "history": [{**candidate["history"][0], field: value}],
                },
                False,
            )
        )
    probes.append(("sourceImageAssetId", {**candidate, "sourceImageAssetId": "103"}, True))
    validator = Draft202012Validator({"$ref": f"#/components/schemas/{name}", **document})
    for label, payload, expected in probes:
        producer_valid = validator.is_valid(payload)
        try:
            CreativeContinuationCandidate.model_validate(payload)
            consumer_valid = True
        except ValidationError:
            consumer_valid = False
        if producer_valid != expected or consumer_valid != expected:
            errors.append(f"CREATIVE {name}.{label} differs from bounded Harness parser")
    review = schemas.get("RunCreativeContinuationReview", {})
    required = {"currentMessageId", "historyFingerprint", "taskType", "decision", "sourceMode"}
    if (
        review.get("additionalProperties") is not False
        or set(review.get("properties", {})) != required | {"sourceImageAssetId"}
        or set(review.get("required", [])) != required
    ):
        errors.append("CREATIVE review fields differ from Harness-owned admission metadata")
    props = review.get("properties", {})
    if props.get("decision", {}).get("const") != "CREATE_NEW":
        errors.append("CREATIVE review decision must be CREATE_NEW")
    if set(props.get("taskType", {}).get("enum", [])) != {"IMAGE", "VIDEO"}:
        errors.append("CREATIVE review taskType must be IMAGE or VIDEO")
    if set(props.get("sourceMode", {}).get("enum", [])) != {
        "TEXT_ONLY",
        "PREVIOUS_IMAGE_RESULT",
        "CURRENT_ATTACHMENTS",
    }:
        errors.append(
            "CREATIVE review sourceMode must bind text, historical image or current attachments"
        )
    for field in ("currentMessageId", "sourceImageAssetId"):
        if props.get(field, {}).get("pattern") != "^[1-9][0-9]{0,18}$":
            errors.append(f"CREATIVE review {field} must be a server-issued ID")
    if not _allows_null(props.get("sourceImageAssetId", {})):
        errors.append("CREATIVE review sourceImageAssetId must remain nullable and optional")
    review_payload: dict[str, Any] = {
        "currentMessageId": "101",
        "historyFingerprint": "a" * 64,
        "taskType": "IMAGE",
        "decision": "CREATE_NEW",
        "sourceMode": "CURRENT_ATTACHMENTS",
    }
    review_validator = Draft202012Validator(
        {"$ref": "#/components/schemas/RunCreativeContinuationReview", **document}
    )
    for label, payload, expected in (
        ("currentAttachments", review_payload, True),
        ("currentAttachments.nullSource", {**review_payload, "sourceImageAssetId": None}, True),
        ("textOnly", {**review_payload, "sourceMode": "TEXT_ONLY"}, True),
        (
            "historicalImage",
            {**review_payload, "sourceMode": "PREVIOUS_IMAGE_RESULT", "sourceImageAssetId": "103"},
            True,
        ),
        (
            "currentAttachments.historicalSource",
            {**review_payload, "sourceImageAssetId": "103"},
            False,
        ),
        (
            "textOnly.historicalSource",
            {**review_payload, "sourceMode": "TEXT_ONLY", "sourceImageAssetId": "103"},
            False,
        ),
        (
            "historicalImage.missingSource",
            {**review_payload, "sourceMode": "PREVIOUS_IMAGE_RESULT"},
            False,
        ),
        (
            "historicalImage.nullSource",
            {**review_payload, "sourceMode": "PREVIOUS_IMAGE_RESULT", "sourceImageAssetId": None},
            False,
        ),
        ("sourceMode", {**review_payload, "sourceMode": "PRODUCT_MAIN_IMAGE"}, False),
        ("unknown", {**review_payload, "unknown": True}, False),
    ):
        if review_validator.is_valid(payload) != expected:
            errors.append(f"CREATIVE review {label} source binding differs from Harness metadata")
    for task in ("Image", "Video"):
        arguments = schemas.get(f"Run{task}GenerateArguments", {})
        if not _contains_ref(
            arguments.get("properties", {}).get("continuationReview", {}),
            "RunCreativeContinuationReview",
        ):
            errors.append(f"CREATIVE {task} continuationReview reference missing")
    data = schemas.get("RunActionResponse", {}).get("properties", {}).get("data", {})
    if not _contains_ref(data.get("properties", {}).get("creativeContinuation", {}), name):
        errors.append("CREATIVE context.read evidence reference missing")
    return errors


def _verify_geo_diagnostic(document: dict[str, Any], branches: list[dict[str, Any]]) -> list[str]:
    """Check the actual new Worker parser, without weakening the ordinary branch."""
    errors: list[str] = []
    schemas = document["components"]["schemas"]
    for name, model in {
        "GeoDiagnosticTaskParameters": GeoDiagnosticTaskParameters,
        "GeoDiagnosticAuthorizedContext": _GeoDiagnosticAuthorizedContext,
        "GeoDiagnosticCompletionAccepted": _GeoDiagnosticCompletionAccepted,
    }.items():
        python = model.model_json_schema(by_alias=True)
        _diagnostic_shape(
            errors, name, schemas.get(name, {}), python, schemas, python.get("$defs", {})
        )
    diagnostic = [b for b in branches if _contains_ref(b, "GeoDiagnosticTaskParameters")]
    if len(diagnostic) != 1:
        errors.append("DIAGNOSTIC_RUN must have exactly one dedicated Worker branch")
        return errors
    budget = diagnostic[0].get("properties", {}).get("budget", {}).get("properties", {})
    if budget.get("maxProviderCalls", {}).get("maximum") != 1000:
        errors.append("DIAGNOSTIC_RUN must retain the signed 1..1000 provider HTTP bounds")
    if budget.get("samplesPerCombination", {}).get("const") != 1:
        errors.append("DIAGNOSTIC_RUN must sample each matrix unit exactly once")
    accepted = schemas.get("GeoCompletionAccepted", {})
    if not all(
        _contains_ref(accepted, name)
        for name in (
            "GeoMonitorCompletionAccepted",
            "GeoDiagnosticCompletionAccepted",
        )
    ):
        errors.append("GEO completion must preserve separate monitor and diagnostic states")
    consumer_fixture = _diagnostic_worker_fixture()
    producer = Draft202012Validator(
        {"$ref": "#/components/schemas/TaskExecutionContext", "components": document["components"]}
    )
    try:
        _GeoTaskExecutionContextResponse.model_validate(consumer_fixture)
    except ValidationError:
        errors.append("DIAGNOSTIC_RUN Python rejects the canonical authorized fixture")
    if not producer.is_valid(consumer_fixture):
        errors.append("DIAGNOSTIC_RUN Java rejects the canonical authorized fixture")
    mutations = {
        "sampleMode": ("inputSnapshot.parameters.sampleMode", "DEEP"),
        "budget": ("budget.maxProviderCalls", 1001),
        "category": ("authorizedContext.questions.0.category", "OTHER"),
        "product": ("authorizedContext.topic.productId", "51"),
        "version": ("authorizedContext.competitorSetVersion", 1),
        "name": ("authorizedContext.monitoringTarget.name", "x" * 201),
        "stableId": ("authorizedContext.competitors.0.id", "x" * 129),
        "unknown": ("authorizedContext.diagnostic.secret", "test-only"),
        "sourceCount": (
            "authorizedContext.diagnostic.sources",
            [
                {"id": str(i), "title": "source", "url": f"https://example.com/{i}", "snippet": ""}
                for i in range(33)
            ],
        ),
        "warningCount": ("authorizedContext.diagnostic.warnings", ["warning"] * 21),
    }
    for name, (path, value) in mutations.items():
        payload = deepcopy(consumer_fixture)
        node: Any = payload
        parts = path.split(".")
        for part in parts[:-1]:
            node = node[int(part)] if isinstance(node, list) else node[part]
        node[parts[-1]] = value
        try:
            _GeoTaskExecutionContextResponse.model_validate(payload)
        except ValidationError:
            pass
        else:
            errors.append(f"DIAGNOSTIC_RUN Python accepts forbidden {name}")
        if producer.is_valid(payload):
            errors.append(f"DIAGNOSTIC_RUN Java accepts forbidden {name}")
    return errors


def _diagnostic_shape(
    errors: list[str],
    path: str,
    producer: dict[str, Any],
    consumer: dict[str, Any],
    schemas: dict[str, Any],
    definitions: dict[str, Any],
) -> None:
    if "$ref" in producer:
        producer = schemas.get(producer["$ref"].split("/")[-1], {})
    if "$ref" in consumer:
        consumer = definitions.get(consumer["$ref"].split("/")[-1], {})
    if _allows_null(producer) != _allows_null(consumer):
        errors.append(f"{path} DIAG nullability differs from Python")
    producer, consumer = _nonnull_field(producer), _nonnull_field(consumer)
    for bound in ("minItems", "maxItems", "minLength", "maxLength", "minimum", "maximum"):
        if path.endswith(".url") and bound == "minLength":
            # The actual URL validator additionally rejects credentials/private hosts.
            continue
        default = 0 if bound in {"minItems", "minLength"} else None
        if producer.get(bound, default) != consumer.get(bound, default):
            errors.append(f"{path} DIAG {bound} differs from Python")
    java_values = producer.get("enum", [producer["const"]] if "const" in producer else None)
    python_values = consumer.get("enum", [consumer["const"]] if "const" in consumer else None)
    if java_values is not None and set(java_values) != set(python_values or []):
        errors.append(f"{path} DIAG enum differs from Python")
    if producer.get("type") == "object" or consumer.get("type") == "object":
        _check_closed_required_shape(errors, path, producer, set(consumer.get("properties", {})))
        if set(producer.get("required", [])) != set(consumer.get("required", [])):
            errors.append(f"{path} DIAG required fields differ from Python")
        for key, field in consumer.get("properties", {}).items():
            _diagnostic_shape(
                errors,
                f"{path}.{key}",
                producer.get("properties", {}).get(key, {}),
                field,
                schemas,
                definitions,
            )
    elif "items" in consumer:
        _diagnostic_shape(
            errors,
            f"{path}.item",
            producer.get("items", {}),
            consumer["items"],
            schemas,
            definitions,
        )


def _diagnostic_worker_fixture() -> dict[str, Any]:
    questions = [
        {
            "id": str(111 + i),
            "category": "BRAND_DISCOVERY" if i < 2 else "BRAND_REPUTATION",
            "text": "自然服务问题" if i < 2 else "品牌评价问题",
        }
        for i in range(3)
    ]
    return {
        "taskId": "151",
        "taskType": "GEO",
        "executionNo": 1,
        "snapshotSchemaVersion": 1,
        "inputSnapshot": {
            "parameters": {
                "mode": "DIAGNOSTIC_RUN",
                "topicId": "121",
                "questionIds": [q["id"] for q in questions],
                "platformCodes": ["DOUBAO"],
                "sampleMode": "STANDARD",
                "region": "全国",
            }
        },
        "authorizedContext": {
            "topic": {
                "id": "121",
                "spaceId": "131",
                "productId": None,
                "name": "诊断",
                "region": "全国",
                "version": 1,
            },
            "questions": questions,
            "competitorSetVersion": None,
            "competitors": [
                {"id": "stable-c1", "name": "竞品", "aliases": [], "competitorType": "DIRECT"}
            ],
            "monitoringTarget": {"kind": "BRAND", "name": "品牌", "aliases": []},
            "completedUnitKeys": [],
            "diagnostic": {
                "diagnosticId": "141",
                "profile": {
                    "brandName": "品牌",
                    "website": None,
                    "introduction": "业务",
                    "brandTerms": ["品牌"],
                    "region": "全国",
                },
                "competitors": [
                    {
                        "id": "stable-c1",
                        "name": "竞品",
                        "brandTerms": ["竞品"],
                        "reason": "用户保留",
                        "sourceIds": [],
                        "origin": "USER",
                        "edited": True,
                    }
                ],
                "sources": [],
                "warnings": [],
            },
        },
        "referenceAssets": [],
        "resumeOnly": False,
        "providerContinuation": None,
        "pricing": {"pricingVersionId": "161", "quotedPoints": 3},
        "budget": {
            "maxProviderCalls": 12,
            "maxGeoQuestions": 10,
            "maxGeoPlatforms": 7,
            "samplesPerCombination": 1,
            "maxConcurrency": 2,
            "deadlineAt": "2030-01-01T00:00:00Z",
        },
    }


def _verify_article_unknown_failure(document: dict[str, Any]) -> list[str]:
    """Keep ARTICLE uncertainty terminal and distinct from media continuation."""
    errors: list[str] = []
    schemas = document["components"]["schemas"]
    event = schemas.get("TaskUnknownFailureEventRequest", {})
    details = schemas.get("ArticleUnknownFailureDetails", {})
    expected_refs = {
        "#/components/schemas/QwenImageUnknownFailureDetails",
        "#/components/schemas/WanImageEditUnknownFailureDetails",
        "#/components/schemas/WanVideoUnknownFailureDetails",
        "#/components/schemas/ArticleUnknownFailureDetails",
    }
    branches = event.get("properties", {}).get("details", {}).get("oneOf", [])
    if len(branches) != 4 or {branch.get("$ref") for branch in branches} != expected_refs:
        errors.append("ARTICLE UNKNOWN event must retain exactly the four Provider branches")
    _check_closed_required_shape(
        errors,
        "ARTICLE UNKNOWN event",
        event,
        set(_TaskExecutionEventRequest.model_json_schema(by_alias=True)["properties"]),
    )
    event_properties = event.get("properties", {})
    if (
        event_properties.get("eventType", {}).get("const") != "TASK_FAILED"
        or event_properties.get("stageCode", {}).get("const") != "PROVIDER_RESULT_UNKNOWN"
    ):
        errors.append("ARTICLE UNKNOWN event must pin failure type and unknown stage")
    _check_closed_required_shape(
        errors,
        "ARTICLE UNKNOWN details",
        details,
        {"code", "provider", "model", "providerRequestId", "providerTaskId", "submissionState"},
    )
    properties = details.get("properties", {})
    if set(properties.get("code", {}).get("enum", [])) != {
        "ARTICLE_DEADLINE_EXCEEDED",
        "DEEPSEEK_SUBMISSION_UNKNOWN",
    }:
        errors.append("ARTICLE UNKNOWN codes must remain the exact two uncertainty codes")
    if (
        properties.get("provider", {}).get("const") != "DEEPSEEK"
        or properties.get("submissionState", {}).get("const") != "UNKNOWN"
    ):
        errors.append("ARTICLE UNKNOWN identity and submission state must remain frozen")
    model = properties.get("model", {})
    expected_pattern = _ArticleContentResult.model_json_schema(by_alias=True)["properties"][
        "model"
    ]["pattern"]
    if model.get("type") != "string" or model.get("pattern") != expected_pattern:
        errors.append("ARTICLE UNKNOWN model pattern must match the ARTICLE result boundary")
    if any(
        not _allows_only_null(properties.get(field, {}))
        for field in ("providerRequestId", "providerTaskId")
    ):
        errors.append("ARTICLE UNKNOWN receipts must be null, never authorize continuation")
    # Exercise the actual event producer validator, not a second lookalike DTO.
    # The schema assertion alone cannot catch a widened Python after-validator.
    details_validator = cast(_SchemaFieldValidator, Draft202012Validator(details))
    for code in ("ARTICLE_DEADLINE_EXCEEDED", "DEEPSEEK_SUBMISSION_UNKNOWN"):
        payload: dict[str, Any] = {
            "eventId": UUID("56251d09-57ed-4fde-8cab-5e7640d145ed"),
            "executionNo": 1,
            "executionToken": "unit-test-article-execution-token",
            "eventType": "TASK_FAILED",
            "stageCode": "PROVIDER_RESULT_UNKNOWN",
            "details": {
                "code": code,
                "provider": "DEEPSEEK",
                "model": "deepseek-test",
                "providerRequestId": None,
                "providerTaskId": None,
                "submissionState": "UNKNOWN",
            },
        }
        try:
            _TaskExecutionEventRequest.model_validate(payload)
        except ValidationError:
            errors.append(f"ARTICLE UNKNOWN producer rejects the valid {code} event")
        if not details_validator.is_valid(payload["details"]):
            errors.append(f"ARTICLE UNKNOWN Java details reject the valid {code} event")
        for mutation in (
            {"code": "ARBITRARY_FAILURE"},
            {"provider": "OPENAI"},
            {"model": "qwen-image-3.0"},
            {"model": "deepseek-"},
            {"model": "deepseek-" + "a" * 113},
            {"providerRequestId": "56251d09-57ed-4fde-8cab-5e7640d145ed"},
            {"providerTaskId": "unit-test-provider-task"},
            {"submissionState": "CONFIRMED"},
            {"unexpected": True},
        ):
            invalid = payload | {"details": payload["details"] | mutation}
            try:
                _TaskExecutionEventRequest.model_validate(invalid)
            except ValidationError:
                pass
            else:
                errors.append("ARTICLE UNKNOWN producer accepts forbidden details")
            if details_validator.is_valid(invalid["details"]):
                errors.append("ARTICLE UNKNOWN Java details accept forbidden details")
        invalid_events = (
            payload | {"stageCode": "FAILED"},
            payload
            | {
                "details": {
                    key: value
                    for key, value in payload["details"].items()
                    if key != "providerTaskId"
                }
            },
        )
        for invalid in invalid_events:
            try:
                _TaskExecutionEventRequest.model_validate(invalid)
            except ValidationError:
                pass
            else:
                errors.append("ARTICLE UNKNOWN producer accepts wrong stage or incomplete identity")
    return errors


def _verify_monitor_action_context(document: dict[str, Any]) -> list[str]:
    """Pin the non-model current-Run monitor selection and unchanged Worker matrix."""
    errors: list[str] = []
    schemas = document["components"]["schemas"]
    context = schemas.get("AuthorizedRunContext", {})
    field = context.get("properties", {}).get("monitorActionContext", {})
    if (
        "monitorActionContext" in context.get("required", [])
        or not _allows_null(field)
        or not _contains_ref(field, "MonitorActionContext")
    ):
        errors.append("AuthorizedRunContext.monitorActionContext must remain optional and nullable")
    java = schemas.get("MonitorActionContext", {})
    python = MonitorActionContext.model_json_schema(by_alias=True)
    _check_closed_required_shape(errors, "MonitorActionContext", java, set(python["properties"]))
    properties = java.get("properties", {})
    if properties.get("action", {}).get("const") != "MONITOR_RUN":
        errors.append("MonitorActionContext.action must be MONITOR_RUN")
    if not _contains_ref(properties.get("spaceId", {}), "SnowflakeId"):
        errors.append("MonitorActionContext.spaceId must reference SnowflakeId")
    if _allows_null(properties.get("spaceId", {})) or not _allows_null(
        properties.get("productId", {})
    ):
        errors.append("MonitorActionContext scope nullability differs from Python")
    if not _contains_ref(properties.get("parameters", {}), "GeoTaskParameters") or _allows_null(
        properties.get("parameters", {})
    ):
        errors.append("MonitorActionContext.parameters must reference non-null GeoTaskParameters")
    questions = properties.get("questions", {})
    if questions.get("minItems") != 3 or questions.get("maxItems") != 10:
        errors.append("MonitorActionContext.questions bounds must be 3..10")
    question = questions.get("items", {})
    if "$ref" in question:
        question = schemas.get(question["$ref"].split("/")[-1], {})
    _check_closed_required_shape(
        errors, "MonitorActionContext.questions.item", question, {"id", "category", "text"}
    )
    if not _contains_ref(question.get("properties", {}).get("id", {}), "SnowflakeId"):
        errors.append("MonitorActionContext question id must reference SnowflakeId")
    if question.get("properties", {}).get("category", {}).get("const") != "BRAND_DISCOVERY":
        errors.append("MonitorActionContext question category differs from Python")
    _check_string_bounds(errors, "MonitorActionContext.questions.item", question, "text", 1, 1000)
    parameters = schemas.get("GeoTaskParameters", {})
    expected = python["$defs"]["GeoTaskParameters"]["properties"]
    _check_closed_required_shape(errors, "GeoTaskParameters", parameters, set(expected))
    actual = parameters.get("properties", {})
    if actual.get("mode", {}).get("const") != "MONITOR_RUN":
        errors.append("GeoTaskParameters.mode must remain MONITOR_RUN")
    if not _contains_ref(actual.get("topicId", {}), "SnowflakeId"):
        errors.append("GeoTaskParameters.topicId must reference SnowflakeId")
    if set(actual.get("sampleMode", {}).get("enum", [])) != {"STANDARD", "DEEP"}:
        errors.append("GeoTaskParameters.sampleMode differs from Python")
    _check_string_bounds(errors, "GeoTaskParameters", parameters, "region", 1, 100)
    for name in ("questionIds", "platformCodes"):
        matrix = actual.get(name, {})
        if any(matrix.get(bound) != expected[name][bound] for bound in ("minItems", "maxItems")):
            errors.append(f"GeoTaskParameters.{name} bounds differ from Python")
        if matrix.get("uniqueItems") is not True:
            errors.append(f"GeoTaskParameters.{name} must remain unique")
    if not _contains_ref(actual.get("questionIds", {}).get("items", {}), "SnowflakeId"):
        errors.append("GeoTaskParameters.questionIds must reference SnowflakeId")
    if set(actual.get("platformCodes", {}).get("items", {}).get("enum", [])) != set(
        expected["platformCodes"]["items"]["enum"]
    ):
        errors.append("GeoTaskParameters.platformCodes enum differs from Python")
    return errors


def _verify_agent_identity(document: dict[str, Any]) -> list[str]:
    """Keep the Java-authorized execution identity closed and optional for legacy snapshots."""
    errors: list[str] = []
    schemas = document["components"]["schemas"]
    context = schemas.get("AuthorizedRunContext", {})
    projection = context.get("properties", {}).get("agentIdentity", {})
    if "agentIdentity" in context.get("required", []) or not _contains_ref(
        projection, "AuthorizedAgentIdentity"
    ):
        errors.append("AuthorizedRunContext.agentIdentity must remain an optional identity")
    java = schemas.get("AuthorizedAgentIdentity", {})
    python = AgentIdentity.model_json_schema(by_alias=True)
    required_identity_fields = {
        "installationId",
        "agentKey",
        "agentVersion",
        "name",
        "projectId",
        "capabilities",
    }
    identity_fields = required_identity_fields | {"executionRelease", "catalogHash"}
    # Only the two release fields are optional for pre-release legacy snapshots. Keep
    # the original six fields required in both producer and consumer contracts.
    for side, schema in (("Java", java), ("Python", python)):
        if schema.get("additionalProperties") is not False:
            errors.append(f"AuthorizedAgentIdentity {side} must reject unknown fields")
        if (
            set(schema.get("properties", {})) != identity_fields
            or set(schema.get("required", [])) != required_identity_fields
        ):
            errors.append(f"AuthorizedAgentIdentity {side} fields differ from the frozen identity")
    for key, consumer_field in python.get("properties", {}).items():
        producer_field = java.get("properties", {}).get(key, {})
        if _allows_null(producer_field) != _allows_null(consumer_field):
            errors.append(f"AuthorizedAgentIdentity.{key} nullability differs from Python")
        producer, consumer = _nonnull_field(producer_field), _nonnull_field(consumer_field)
        # IDs reference the shared Snowflake schema in Java and are strict strings in Python.
        if "$ref" in producer:
            continue
        for rule in ("pattern", "minLength", "maxLength", "maxItems", "uniqueItems"):
            if producer.get(rule) != consumer.get(rule):
                errors.append(f"AuthorizedAgentIdentity.{key} {rule} differs from Python")
        if producer.get("items", {}).get("pattern") != consumer.get("items", {}).get("pattern"):
            errors.append(f"AuthorizedAgentIdentity.{key} item pattern differs from Python")
    groups = context.get("properties", {}).get("loadedToolGroups", {})
    if groups.get("minItems") != 0 or groups.get("maxItems") != 1:
        errors.append("AuthorizedRunContext.loadedToolGroups must allow zero or one group")
    return errors


def _verify_read_resources(document: dict[str, Any]) -> list[str]:
    """Check the full new DTO envelope and capability bounds, not a partial copied contract."""
    errors: list[str] = []
    schemas = document["components"]["schemas"]
    models: dict[str, type[BaseModel]] = {
        "RunResourceSearchRequest": ResourceSearchRequest,
        "RunResourceSearchResponse": ResourceSearchResponse,
        "RunResourceCandidate": ResourceCandidate,
        "RunResourceSelection": FrozenResourceSelection,
        "RunResourceReadRequest": ResourceReadRequest,
        "RunResourceReadResponse": ResourceReadResponse,
        "RunResourceReportAttempt": ResourceReportAttempt,
    }
    for name, model in models.items():
        java = schemas.get(name, {})
        python = model.model_json_schema(by_alias=True)
        if java.get("additionalProperties") is not False:
            errors.append(f"{name} must forbid unknown fields")
        if set(java.get("properties", {})) != set(python.get("properties", {})):
            errors.append(f"{name} fields differ from Python")
        if set(java.get("required", [])) != set(python.get("required", [])):
            errors.append(f"{name} required fields differ from Python")
        for key, py_field in python.get("properties", {}).items():
            java_field = java.get("properties", {}).get(key, {})
            if _allows_null(java_field) != _allows_null(py_field):
                errors.append(f"{name}.{key} nullability differs from Python")
            producer, consumer = _nonnull_field(java_field), _nonnull_field(py_field)
            if "$ref" in producer or "$ref" in consumer:
                continue
            for bound in ("maxItems", "maxLength", "minLength", "minimum", "maximum"):
                if producer.get(bound) != consumer.get(bound):
                    errors.append(f"{name}.{key} {bound} differs from Python")
            if producer.get("enum") is not None and set(producer["enum"]) != set(
                consumer.get("enum", [])
            ):
                errors.append(f"{name}.{key} enum differs from Python")
    authorized = schemas.get("AuthorizedRunContext", {})
    expected_refs = {
        "resourceSelection": "RunResourceSelection",
        "lastReadContext": "RunResourceReadResponse",
        "pendingResourceContext": "RunResourceSearchResponse",
        "lastReportReadContext": "RunResourceReadResponse",
    }
    for field, target in expected_refs.items():
        projection = authorized.get("properties", {}).get(field, {})
        if field in authorized.get("required", []) or not _contains_ref(projection, target):
            errors.append(f"AuthorizedRunContext.{field} must remain an optional {target}")
    return errors


def _verify_chat_geo_context(document: dict[str, Any]) -> list[str]:
    """Compare every bounded GEO projection node, without vendoring a partial contract."""
    errors: list[str] = []
    schemas = document["components"]["schemas"]
    context = schemas.get("AuthorizedRunContext", {})
    field = context.get("properties", {}).get("geoInsightContext", {})
    if "geoInsightContext" in context.get("required", []):
        errors.append("GEO chat context must remain optional for old Run snapshots")
    if not _allows_null(field) or not _contains_ref(field, "GeoInsightContext"):
        errors.append("GEO chat context must reference nullable GeoInsightContext")
    consumer = GeoInsightContext.model_json_schema(by_alias=True)
    models = {**consumer.get("$defs", {}), "GeoInsightContext": consumer}
    names = {
        "GeoInsightContext": "GeoInsightContext",
        "GeoTopicChoice": "GeoChatTopicChoice",
        "GeoInsightTarget": "GeoChatTarget",
        "GeoInsightReport": "GeoChatReport",
        "GeoInsightPlatform": "GeoChatVisibility",
        "GeoInsightTrend": "GeoChatTrend",
        "GeoInsightTrendPlatform": "GeoChatTrendPlatform",
        "GeoInsightCompetitors": "GeoChatCompetitors",
        "GeoInsightCompetitorPlatform": "GeoChatCompetitivePlatform",
        "GeoInsightCompetitorEntity": "GeoChatEntity",
        "GeoInsightCandidate": "GeoChatCandidate",
        "GeoInsightCompetitorEvidence": "GeoChatEvidence",
        "GeoInsightLinks": "GeoChatLinks",
    }
    for python_name, java_name in names.items():
        java = schemas.get(java_name, {})
        python = models[python_name]
        if java.get("additionalProperties") is not False:
            errors.append(f"{java_name} must forbid unknown fields")
        if set(java.get("properties", {})) != set(python.get("properties", {})):
            errors.append(f"{java_name} fields differ from Python")
        if set(java.get("required", [])) != set(python.get("required", [])):
            errors.append(f"{java_name} required fields differ from Python")
        for key, python_field in python.get("properties", {}).items():
            java_field = java.get("properties", {}).get(key, {})
            if _allows_null(java_field) != _allows_null(python_field):
                errors.append(f"{java_name}.{key} nullability differs from Python")
            java_value = _nonnull_field(java_field)
            python_value = _nonnull_field(python_field)
            for bound in ("maxItems", "maxLength", "minimum", "maximum", "pattern", "const"):
                # Snowflake/date constraints are resolved by their shared schema elsewhere.
                if "$ref" in java_value or "$ref" in python_value:
                    continue
                if java_value.get(bound) != python_value.get(bound):
                    errors.append(f"{java_name}.{key} {bound} differs from Python")
            java_enum = java_value.get("enum")
            if java_enum is not None and set(java_enum) != set(python_value.get("enum", [])):
                errors.append(f"{java_name}.{key} enum differs from Python")
    return errors


def _nonnull_field(field: dict[str, Any]) -> dict[str, Any]:
    for branch in field.get("oneOf", field.get("anyOf", [])):
        if branch.get("type") != "null":
            return branch
    return field


def _verify_image_candidates(document: dict[str, Any]) -> list[str]:
    """Keep the paid image authorization and result boundary at one to four candidates."""
    errors: list[str] = []
    schemas = document["components"]["schemas"]
    budget = schemas["TaskExecutionContext"]["properties"]["budget"]["properties"]
    bounded_fields = {"IMAGE.budget.maxImages": budget.get("maxImages", {})}
    for name in ("ImageMockResultRequest", "QwenImageResultRequest", "WanImageEditResultRequest"):
        properties = schemas.get(name, {}).get("properties", {})
        artifacts = properties.get("artifacts", {})
        if artifacts.get("minItems") != 1 or artifacts.get("maxItems") != 4:
            errors.append(f"{name}.artifacts bounds must be 1..4")
        bounded_fields[f"{name}.usage.images"] = (
            properties.get("usage", {}).get("properties", {}).get("images", {})
        )
        if name in ("QwenImageResultRequest", "WanImageEditResultRequest"):
            artifact_properties = artifacts.get("items", {}).get("properties", {})
            bounded_fields[f"{name}.metadata.index"] = (
                artifact_properties.get("metadata", {}).get("properties", {}).get("index", {})
            )
            unit_key = cast(
                _SchemaFieldValidator, Draft202012Validator(artifact_properties.get("unitKey", {}))
            )
            if any(not unit_key.is_valid(f"image:{index}") for index in range(1, 5)) or any(
                unit_key.is_valid(value) for value in ("image:0", "image:5", "video:1", 1)
            ):
                errors.append(f"{name}.unitKey must allow only image:1..4 or null")
    for name, field in bounded_fields.items():
        validator = cast(_SchemaFieldValidator, Draft202012Validator(field))
        if any(not validator.is_valid(value) for value in range(1, 5)) or any(
            validator.is_valid(value) for value in (0, 5, -1, True, "1", 1.5)
        ):
            errors.append(f"{name} integer bounds must be 1..4")
    return errors


def _verify_article_v2(
    document: dict[str, Any],
    article_branch: dict[str, Any] | None,
) -> list[str]:
    errors: list[str] = []
    schemas = document.get("components", {}).get("schemas", {})
    if not isinstance(schemas, dict) or article_branch is None:
        return ["ARTICLE v2 schemas are missing"]

    required_schema_names = {
        "ArticleOutlineItem",
        "ArticlePlan",
        "ArticlePlanSection",
        "ArticleTaskParameters",
        "ArticleAuthorizedContext",
        "ArticleContentResultRequest",
        "ArticleContentResult",
    }
    missing = required_schema_names - schemas.keys()
    if missing:
        return [f"ARTICLE v2 schemas are missing: {sorted(missing)}"]

    snapshot = article_branch.get("properties", {}).get("snapshotSchemaVersion")
    if not isinstance(snapshot, dict):
        snapshot = (
            schemas.get("TaskExecutionContext", {})
            .get("properties", {})
            .get("snapshotSchemaVersion")
        )
    if not isinstance(snapshot, dict) or snapshot.get("const") != 2:
        errors.append("ARTICLE.snapshotSchemaVersion must be const 2")

    outline = schemas["ArticleOutlineItem"]
    _check_closed_required_shape(
        errors,
        "ArticleOutlineItem",
        outline,
        {"key", "sequence", "level", "heading", "brief"},
    )
    brief = outline.get("properties", {}).get("brief", {})
    if brief.get("maxLength") != 1_000 or not _allows_null(brief):
        errors.append("ArticleOutlineItem.brief must preserve the nullable legacy bound")

    plan_section = schemas["ArticlePlanSection"]
    _check_closed_required_shape(
        errors,
        "ArticlePlanSection",
        plan_section,
        {"key", "sequence", "level", "heading", "brief"},
    )
    plan_section_brief = plan_section.get("properties", {}).get("brief", {})
    if plan_section_brief.get("minLength") != 1 or plan_section_brief.get("maxLength") != 1_000:
        errors.append("ArticlePlanSection.brief bounds must be 1..1000")

    plan = schemas["ArticlePlan"]
    plan_fields = {
        "summary",
        "coreMessage",
        "targetAudience",
        "sections",
        "tone",
        "targetLength",
        "targetPlatform",
        "riskNotes",
    }
    _check_closed_required_shape(errors, "ArticlePlan", plan, plan_fields)
    sections = plan.get("properties", {}).get("sections", {})
    if sections.get("minItems") != 2 or sections.get("maxItems") != 12:
        errors.append("ArticlePlan.sections bounds must be 2..12")
    if not _contains_ref(sections, "ArticlePlanSection"):
        errors.append("ArticlePlan.sections must reference ArticlePlanSection")
    _check_string_bounds(errors, "ArticlePlan", plan, "summary", 1, 1_000)
    _check_string_bounds(errors, "ArticlePlan", plan, "coreMessage", 1, 2_000)
    _check_string_bounds(errors, "ArticlePlan", plan, "targetAudience", 1, 1_000)
    target_platform = plan.get("properties", {}).get("targetPlatform", {})
    if target_platform.get("maxLength") != 100 or not _allows_null(target_platform):
        errors.append("ArticlePlan.targetPlatform must be nullable with maxLength 100")
    risk_notes = plan.get("properties", {}).get("riskNotes", {})
    if risk_notes.get("maxItems") != 20 or risk_notes.get("uniqueItems") is not True:
        errors.append("ArticlePlan.riskNotes must be unique with maxItems 20")
    _check_string_bounds(
        errors,
        "ArticlePlan.riskNotes",
        {"properties": {"item": risk_notes.get("items", {})}},
        "item",
        1,
        500,
    )

    parameters = schemas["ArticleTaskParameters"]
    parameter_properties = parameters.get("properties", {})
    for field in ("confirmedPlan", "targetPlatform"):
        if field not in parameter_properties or field not in parameters.get("required", []):
            errors.append(f"ArticleTaskParameters.{field} must be required and nullable")
    if not _contains_ref(parameter_properties.get("confirmedPlan", {}), "ArticlePlan"):
        errors.append("ArticleTaskParameters.confirmedPlan must reference ArticlePlan")

    authorized = schemas["ArticleAuthorizedContext"]
    serialized_authorized = json.dumps(authorized, sort_keys=True)
    if '"confirmedPlan"' not in serialized_authorized or "ArticlePlan" not in serialized_authorized:
        errors.append("ArticleAuthorizedContext article must carry nullable confirmedPlan")

    content = schemas["ArticleContentResult"]
    if "plan" not in content.get("properties", {}) or "plan" not in content.get("required", []):
        errors.append("ArticleContentResult.plan must be required and nullable")
    title_candidates = content.get("properties", {}).get("titleCandidates", {})
    if title_candidates.get("maxItems") != 5:
        errors.append("ArticleContentResult.titleCandidates maxItems must be 5")
    supported_prompt_versions = content.get("properties", {}).get("promptVersion", {}).get("enum")
    if not isinstance(supported_prompt_versions, list) or not {
        "agent-bundle-v1.11.0",
        "agent-bundle-v1.12.0",
        "agent-bundle-v1.16.0",
    }.issubset(supported_prompt_versions):
        errors.append(
            "ArticleContentResult.promptVersion must preserve old versions and allow v1.16.0"
        )
    python_prompt_versions = _ArticleContentResult.model_json_schema(by_alias=True)["properties"][
        "promptVersion"
    ]["enum"]
    if not isinstance(supported_prompt_versions, list) or set(supported_prompt_versions) != set(
        python_prompt_versions
    ):
        errors.append("ArticleContentResult.promptVersion differs from Python result consumer")
    stage_branches = {
        branch.get("properties", {}).get("mode", {}).get("const"): branch
        for branch in content.get("oneOf", [])
    }
    title_shape = stage_branches.get("TITLE", {}).get("properties", {})
    title_items = title_shape.get("titleCandidates", {})
    if title_items.get("minItems") != 5 or title_items.get("maxItems") != 5:
        errors.append("ARTICLE TITLE result must require exactly 5 candidates")
    outline_shape = stage_branches.get("OUTLINE", {}).get("properties", {})
    if not _contains_ref(outline_shape.get("plan", {}), "ArticlePlan"):
        errors.append("ARTICLE OUTLINE result must require ArticlePlan")
    for mode in ("TITLE", "BODY", "REWRITE"):
        plan_shape = stage_branches.get(mode, {}).get("properties", {}).get("plan", {})
        if not _allows_only_null(plan_shape):
            errors.append(f"ARTICLE {mode} result plan must be null")

    result_request = schemas["ArticleContentResultRequest"]
    artifacts = result_request.get("properties", {}).get("artifacts", {}).get("items", {})
    artifact_version = artifacts.get("properties", {}).get("schemaVersion", {})
    if artifact_version.get("const") != 2:
        errors.append("ARTICLE_CONTENT artifact schemaVersion must be const 2")
    return errors


def _check_closed_required_shape(
    errors: list[str],
    label: str,
    schema: dict[str, Any],
    expected_fields: set[str],
) -> None:
    if schema.get("additionalProperties") is not False:
        errors.append(f"{label} must forbid unknown fields")
    properties = set(schema.get("properties", {}))
    required = set(schema.get("required", []))
    if properties != expected_fields or required != expected_fields:
        errors.append(
            f"{label} fields differ: properties={sorted(properties)} required={sorted(required)}"
        )


def _contains_ref(schema: object, name: str) -> bool:
    return f"#/components/schemas/{name}" in json.dumps(schema, sort_keys=True)


def _check_string_bounds(
    errors: list[str],
    label: str,
    schema: dict[str, Any],
    field: str,
    minimum: int,
    maximum: int,
) -> None:
    value = schema.get("properties", {}).get(field, {})
    if value.get("minLength") != minimum or value.get("maxLength") != maximum:
        errors.append(f"{label}.{field} bounds must be {minimum}..{maximum}")


def _allows_null(schema: object) -> bool:
    if not isinstance(schema, dict):
        return False
    schema_type = schema.get("type")
    if isinstance(schema_type, list) and "null" in schema_type:
        return True
    return any(
        isinstance(branch, dict) and branch.get("type") == "null"
        for key in ("oneOf", "anyOf")
        for branch in schema.get(key, [])
    )


def _allows_only_null(schema: object) -> bool:
    return isinstance(schema, dict) and schema.get("type") == "null"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Fail when Java task execution contexts and Python strict DTOs drift."
    )
    parser.add_argument("openapi", type=Path, help="Java java-internal-openapi.yaml path")
    arguments = parser.parse_args()
    errors = verify_contract(arguments.openapi.resolve())
    if errors:
        for error in errors:
            print(f"CONTRACT_ERROR {error}")
        return 1
    print("JAVA_PYTHON_TASK_CONTEXT_CONTRACT=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

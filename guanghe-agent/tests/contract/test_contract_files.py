"""Basic parse and boundary tests for shared and Python-owned contracts."""

import base64
import hashlib
import hmac
import json
import os
import re
from pathlib import Path
from typing import Any, Protocol, cast

import pytest
import yaml
from jsonschema import Draft202012Validator
from jsonschema import ValidationError as JsonSchemaValidationError

from app.agent.models import DraftOperation, TaskDraftProposal
from app.api.application import create_app
from app.common.enums import TaskType
from app.common.settings import AppSettings
from app.providers.resources import ResourceReadResponse

ROOT = Path(__file__).resolve().parents[2]
JAVA_ROOT = Path(os.environ.get("GUANGHE_BACKEND_REPO", ROOT.parent / "guanghe-backend"))
HTTP_METHODS = frozenset({"get", "put", "post", "delete", "options", "head", "patch", "trace"})


class _JsonSchemaValidator(Protocol):
    def validate(self, instance: object) -> None: ...


def _agent_openapi() -> dict[str, Any]:
    raw: Any = yaml.safe_load((ROOT / "contracts" / "agent-openapi.yaml").read_bytes())
    return cast(dict[str, Any], raw)


def _canonical_json_digest(document: object) -> str:
    encoded = json.dumps(
        document,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode()
    return hashlib.sha256(encoded).hexdigest()


def test_agent_openapi_contains_gate_zero_paths() -> None:
    contract = _agent_openapi()
    paths = cast(dict[str, Any], contract["paths"])
    assert {
        "/health",
        "/internal/v1/health/ready",
        "/internal/v1/capabilities",
        "/internal/v1/chat-runs",
        "/internal/v1/chat-runs/{runId}",
        "/internal/v1/chat-runs/{runId}/events",
        "/internal/v1/chat-runs/{runId}/stop",
        "/internal/v1/creative/geo-plans",
        "/internal/v1/geo/syntheses",
        "/internal/v1/geo/period-report-summaries",
        "/internal/v1/geo/diagnostic-preparations",
        "/internal/v1/geo/diagnostic-analyses",
    } == paths.keys()
    assert not any("{run_id}" in path for path in paths)
    assert paths["/internal/v1/chat-runs/{runId}"]["get"]["operationId"] == ("getChatRunSnapshot")
    planner = paths["/internal/v1/creative/geo-plans"]["post"]
    assert planner["operationId"] == "createGeoVisualPlan"
    assert {"401", "429", "503"} <= planner["responses"].keys()


def test_diagnostic_preparation_contract_declares_signed_strict_candidate_and_usage_shapes() -> (
    None
):
    contract = _agent_openapi()
    operation = contract["paths"]["/internal/v1/geo/diagnostic-preparations"]["post"]
    assert operation["operationId"] == "prepareGeoDiagnostic"
    assert operation["security"] == [{"InternalHmac": []}]
    assert {"401", "422", "429", "503", "504"} <= operation["responses"].keys()
    schemas = contract["components"]["schemas"]
    request = schemas["DiagnosticPreparationRequest"]
    assert request["additionalProperties"] is False
    assert set(request["required"]) == {"jobId", "inputHash", "phase", "deadlineAt", "input"}
    assert request["properties"]["phase"]["enum"] == [
        "BRAND_RESEARCH",
        "COMPETITORS",
        "DISCOVERY_QUESTIONS",
        "REPUTATION_QUESTIONS",
    ]
    response = schemas["DiagnosticPreparationResponse"]
    assert response["properties"]["promptVersion"]["const"] == "GEO_DIAGNOSTIC_PREPARATION_V5"
    assert response["properties"]["searchRequestCount"]["maximum"] == 6
    assert "synthesisUsage" in response["required"]
    assert schemas["DiagnosticSource"]["properties"]["title"]["minLength"] == 1
    assert schemas["DiagnosticSource"]["properties"]["snippet"]["type"] == "string"
    assert schemas["DiagnosticPreparationResult"]["properties"]["sources"]["maxItems"] == 32


def test_diagnostic_analysis_contract_has_strict_matrix_metrics_and_grounded_report() -> None:
    contract = _agent_openapi()
    operation = contract["paths"]["/internal/v1/geo/diagnostic-analyses"]["post"]
    assert operation["operationId"] == "analyzeGeoDiagnostic"
    assert operation["security"] == [{"InternalHmac": []}]
    assert {"401", "422", "429", "503", "504"} <= operation["responses"].keys()
    schemas = contract["components"]["schemas"]
    request = schemas["DiagnosticAnalysisRequest"]
    assert set(request["required"]) == {"jobId", "inputHash", "deadlineAt", "title", "evidence"}
    assert request["additionalProperties"] is False
    assert "262144" in request["description"]
    evidence = schemas["DiagnosticAnalysisEvidence"]["properties"]
    assert evidence["samples"]["maxItems"] == 70
    assert evidence["sources"]["maxItems"] == 100
    assert schemas["DiagnosticAnalysisSource"]["properties"]["snippet"]["maxLength"] == 500
    sample = schemas["DiagnosticSample"]["properties"]
    assert "questionText" not in sample
    assert sample["status"]["enum"] == ["SUCCEEDED", "FAILED", "MISSING"]
    assert sample["answerExcerpt"]["anyOf"][0]["maxLength"] == 1600
    assert sample["sourceIds"]["maxItems"] == 50
    assert sample["matchedBrandIds"]["anyOf"][0]["maxItems"] == 6
    assert sample["matchedBrandIds"]["anyOf"][1]["type"] == "null"
    assert "matchedBrandIds" not in schemas["DiagnosticSample"]["required"]
    assert "legacy V1" in sample["matchedBrandIds"]["description"]
    assert sample["category"]["enum"] == ["BRAND_DISCOVERY", "BRAND_REPUTATION"]
    assert schemas["DiagnosticMention"]["properties"]["mentionRate"]["anyOf"][1]["type"] == "null"
    assert schemas["DiagnosticReport"]["additionalProperties"] is False
    assert schemas["DiagnosticReport"]["properties"]["findings"]["minItems"] == 1
    assert schemas["DiagnosticReport"]["properties"]["recommendations"]["maxItems"] == 15
    assert schemas["DiagnosticReport"]["properties"]["contentTopics"]["minItems"] == 1
    assert schemas["DiagnosticReport"]["properties"]["contentTopics"]["maxItems"] == 10
    topic = schemas["DiagnosticContentTopic"]
    assert topic["additionalProperties"] is False
    assert set(topic["required"]) == {
        "title",
        "audienceQuestion",
        "angle",
        "coveredBrandTerms",
        "basisEvidence",
    }
    assert schemas["DiagnosticReportEvidence"]["properties"]["quote"]["maxLength"] == 1000
    assert schemas["DiagnosticAnalysisResponse"]["properties"]["promptVersion"]["const"] == (
        "GEO_DIAGNOSTIC_ANALYSIS_V11"
    )


def test_geo_recovery_contract_is_signed_bounded_and_saved_evidence_only() -> None:
    contract = _agent_openapi()
    observation = contract["components"]["schemas"]["GeoObservation"]
    method = observation["properties"]["collectionMethod"]
    assert "collectionMethod" not in observation["required"]
    assert method["anyOf"] == [
        {"type": "string", "maxLength": 128, "pattern": "^[a-z][a-z0-9-]{0,127}$"},
        {"type": "null"},
    ]
    operation = contract["paths"]["/internal/v1/geo/syntheses"]["post"]
    assert operation["operationId"] == "regenerateGeoSynthesis"
    assert operation["security"] == [{"InternalHmac": []}]
    assert {"401", "422", "429", "503", "504"} <= operation["responses"].keys()
    schemas = contract["components"]["schemas"]
    request = schemas["GeoSynthesisRecoveryRequest"]
    assert request["additionalProperties"] is False
    assert set(request["required"]) == {
        "jobId",
        "attempt",
        "inputHash",
        "deadlineAt",
        "maxProviderCalls",
        "authorizedContext",
        "observations",
        "evidenceProjection",
        "visibilityEvidence",
    }
    assert request["properties"]["maxProviderCalls"]["maximum"] == 2
    assert request["properties"]["observations"]["maxItems"] == 210
    assert (
        schemas["GeoVisibilityEvidence"]["properties"]["matchingVersion"]["const"]
        == "explicit-name-mention-v1"
    )
    assert operation["responses"]["422"]["content"]["application/json"]["schema"] == {
        "$ref": "#/components/schemas/GeoSynthesisRecoveryErrorResponse"
    }


def test_static_agent_openapi_is_exactly_the_normalized_runtime_schema() -> None:
    static_contract = _agent_openapi()
    runtime_contract = create_app(AppSettings()).openapi()

    assert static_contract == runtime_contract


def test_runtime_openapi_preserves_chat_run_stream_and_error_responses() -> None:
    runtime_contract = create_app(AppSettings()).openapi()
    paths = cast(dict[str, Any], runtime_contract["paths"])

    start_responses = paths["/internal/v1/chat-runs"]["post"]["responses"]
    snapshot_responses = paths["/internal/v1/chat-runs/{runId}"]["get"]["responses"]
    event_responses = paths["/internal/v1/chat-runs/{runId}/events"]["get"]["responses"]
    stop_responses = paths["/internal/v1/chat-runs/{runId}/stop"]["post"]["responses"]

    event_content = event_responses["200"]["content"]
    assert set(event_content) == {"text/event-stream"}
    assert "application/json" not in event_content
    assert {"400", "401", "404", "503"} <= event_responses.keys()
    assert {"401", "409", "503"} <= start_responses.keys()
    assert {"401", "404", "503"} <= snapshot_responses.keys()
    assert {"401", "404", "503"} <= stop_responses.keys()


def test_usage_reported_openapi_exposes_split_and_compatible_counters() -> None:
    contract = _agent_openapi()
    schemas = cast(dict[str, Any], contract["components"]["schemas"])
    usage = cast(dict[str, Any], schemas["Usage"])
    properties = cast(dict[str, Any], usage["properties"])
    split_fields = {
        "workflowStepCount",
        "modelCallCount",
        "contextLoadCount",
        "toolCallCount",
        "providerCallCount",
    }
    legacy_fields = {"steps", "modelCalls", "toolCalls"}
    required_fields = split_fields | legacy_fields | {"estimatedInputTokens"}
    optional_fields = {
        "providerInputTokens",
        "providerOutputTokens",
        "providerLatencyMs",
        "provider",
        "model",
    }

    assert set(properties) == required_fields | optional_fields
    assert set(usage["required"]) == required_fields
    for field in split_fields | legacy_fields:
        assert properties[field]["minimum"] == 0

    event_schema = cast(dict[str, Any], schemas["ChatRunEvent"])
    assert {
        "if": {
            "properties": {"eventType": {"const": "usage.reported"}},
            "required": ["eventType"],
        },
        "then": {"properties": {"payload": {"$ref": "#/components/schemas/Usage"}}},
    } in event_schema["allOf"]


def test_run_progress_openapi_is_strict_and_contains_only_safe_control_fields() -> None:
    contract = _agent_openapi()
    schemas = cast(dict[str, Any], contract["components"]["schemas"])
    event_schema = cast(dict[str, Any], schemas["ChatRunEvent"])
    progress = cast(dict[str, Any], schemas["RunProgressPayload"])
    properties = cast(dict[str, Any], progress["properties"])

    assert set(properties) == {
        "stageCode",
        "status",
        "ordinal",
        "elapsedMs",
        "current",
        "total",
    }
    assert progress["additionalProperties"] is False
    assert properties["ordinal"]["minimum"] == 1
    assert properties["ordinal"]["maximum"] == 32
    assert properties["elapsedMs"]["minimum"] == 0
    assert schemas["RunProgressStage"]["enum"] == [
        "UNDERSTANDING_REQUEST",
        "LOADING_BUSINESS_CONTEXT",
        "LOADING_PRODUCT_CONTEXT",
        "LOADING_REFERENCE_ASSETS",
        "ROUTING_INTENT",
        "PLANNING_WORKFLOW",
        "RUNNING_SAFE_TOOL",
        "SEARCHING_WEB",
        "PREPARING_TASK_DRAFT",
        "COMPOSING_RESPONSE",
    ]
    assert schemas["RunProgressStatus"]["enum"] == ["RUNNING", "SUCCEEDED", "FAILED"]
    assert {
        "if": {
            "properties": {"eventType": {"const": "run.progress"}},
            "required": ["eventType"],
        },
        "then": {"properties": {"payload": {"$ref": "#/components/schemas/RunProgressPayload"}}},
    } in event_schema["allOf"]


def test_run_progress_event_json_schema_rejects_free_text_and_unknown_stages() -> None:
    schema = json.loads(
        (ROOT / "contracts" / "events" / "chat-run-event-v1.schema.json").read_text()
    )
    validator = cast(_JsonSchemaValidator, Draft202012Validator(schema))
    base_event = {
        "schemaVersion": 1,
        "eventId": "00000000-0000-4000-8000-000000000001",
        "eventType": "run.progress",
        "runId": "00000000-0000-4000-8000-000000000002",
        "occurredAt": "2026-09-14T10:00:00Z",
        "sequence": 2,
        "payload": {
            "stageCode": "ROUTING_INTENT",
            "status": "RUNNING",
            "ordinal": 2,
            "elapsedMs": 20,
            "current": None,
            "total": None,
        },
    }

    validator.validate(base_event)
    with_text = json.loads(json.dumps(base_event))
    with_text["payload"]["displayText"] = "模型正在思考内部提示词"
    with pytest.raises(JsonSchemaValidationError):
        validator.validate(with_text)
    unknown_stage = json.loads(json.dumps(base_event))
    unknown_stage["payload"]["stageCode"] = "MODEL_CHAIN_OF_THOUGHT"
    with pytest.raises(JsonSchemaValidationError):
        validator.validate(unknown_stage)


@pytest.mark.parametrize("contract_type", ("EVENT", "OPENAPI"))
def test_business_outcome_wire_is_optional_closed_and_non_null(contract_type: str) -> None:
    if contract_type == "EVENT":
        document = json.loads(
            (ROOT / "contracts" / "events" / "chat-run-event-v1.schema.json").read_text()
        )
        schema = document["$defs"]["businessOutcome"]
    else:
        schemas = _agent_openapi()["components"]["schemas"]
        schema = {
            **schemas["BusinessOutcome"],
            "properties": {
                **schemas["BusinessOutcome"]["properties"],
                "status": schemas["BusinessOutcomeStatus"],
            },
        }
    validator = cast(_JsonSchemaValidator, Draft202012Validator(schema))
    validator.validate({"schemaVersion": 1, "status": "ANSWERED"})
    validator.validate(
        {
            "schemaVersion": 1,
            "status": "PARTIAL",
            "reasonCode": "READONLY_SYNTHESIS_UNAVAILABLE",
            "failureStage": "SYNTHESIS",
        }
    )
    for invalid in (
        {"reasonCode": None},
        {"failureStage": None},
        {"status": "SUCCESS"},
        {"reasonCode": "RAW_PROVIDER_ERROR"},
        {"failureStage": "INTERNAL_PROMPT"},
        {"modelExplanation": "model-controlled"},
    ):
        with pytest.raises(JsonSchemaValidationError):
            validator.validate({"schemaVersion": 1, "status": "FAILED", **invalid})


def test_capabilities_exposes_safe_intent_router_state() -> None:
    schemas = cast(dict[str, Any], _agent_openapi()["components"]["schemas"])
    capability = cast(dict[str, Any], schemas["IntentRouterCapability"])
    properties = cast(dict[str, Any], capability["properties"])

    assert set(properties) == {
        "routerProvider",
        "routerModel",
        "routerMode",
        "available",
        "schemaVersion",
        "status",
        "reasonCode",
    }
    assert properties["routerMode"]["enum"] == [
        "NATIVE_LOOP",
        "DEEPSEEK_PRIMARY",
        "QWEN_SHADOW",
        "QWEN_PRIMARY",
    ]
    assert "endpoint" not in json.dumps(capability).lower()
    assert "credential" not in json.dumps(capability).lower()


def test_capabilities_exposes_safe_langfuse_collector_wiring_state() -> None:
    schemas = cast(dict[str, Any], _agent_openapi()["components"]["schemas"])
    capability = cast(dict[str, Any], schemas["CapabilitiesResponse"])
    langfuse = cast(dict[str, Any], schemas["LangfuseExportCapability"])
    status = cast(dict[str, Any], schemas["LangfuseExportStatus"])
    serialized = json.dumps(langfuse).lower()

    assert "langfuseExport" in capability["required"]
    assert langfuse["properties"]["status"] == {"$ref": "#/components/schemas/LangfuseExportStatus"}
    assert status["enum"] == [
        "DISABLED",
        "NOT_CONFIGURED",
        "READY",
        "DEGRADED",
    ]
    for forbidden in ("endpoint", "authorization", "credential", "secret", "publickey"):
        assert forbidden not in serialized


def test_chat_run_budget_matches_java_multi_turn_runtime_limits() -> None:
    schemas = cast(dict[str, Any], _agent_openapi()["components"]["schemas"])
    budget = cast(dict[str, Any], schemas["RunBudget"])
    properties = cast(dict[str, Any], budget["properties"])

    assert properties["maxContextLoads"]["default"] == 1
    assert properties["maxContextLoads"]["minimum"] == 1
    assert properties["maxContextLoads"]["maximum"] == 1
    assert properties["maxProviderCalls"]["default"] == 0
    assert properties["maxProviderCalls"]["minimum"] == 0
    assert properties["maxProviderCalls"]["maximum"] == 6


def test_agent_openapi_exposes_versioned_article_plan_execution_schemas() -> None:
    schemas = cast(dict[str, Any], _agent_openapi()["components"]["schemas"])
    request = cast(dict[str, Any], schemas["ArticleExecutionRequest"])
    result = cast(dict[str, Any], schemas["ArticleExecutionResult"])
    plan = cast(dict[str, Any], schemas["ArticlePlan"])
    section = cast(dict[str, Any], schemas["ArticleOutlineItem"])

    assert request["additionalProperties"] is False
    assert result["additionalProperties"] is False
    assert request["properties"]["confirmedPlan"] == {
        "anyOf": [
            {"$ref": "#/components/schemas/ArticlePlan"},
            {"type": "null"},
        ],
        "default": None,
    }
    assert result["properties"]["titleCandidates"]["maxItems"] == 5
    assert set(plan["properties"]) == {
        "summary",
        "coreMessage",
        "targetAudience",
        "sections",
        "tone",
        "targetLength",
        "targetPlatform",
        "riskNotes",
    }
    assert set(plan["required"]) == set(plan["properties"])
    assert plan["properties"]["sections"]["minItems"] == 2
    assert plan["properties"]["sections"]["maxItems"] == 12
    assert plan["properties"]["summary"]["maxLength"] == 1_000
    assert plan["properties"]["coreMessage"]["maxLength"] == 2_000
    assert plan["properties"]["targetAudience"]["maxLength"] == 1_000
    assert plan["properties"]["targetPlatform"]["anyOf"][0]["maxLength"] == 100
    assert plan["properties"]["riskNotes"]["uniqueItems"] is True
    assert set(section["properties"]) == {
        "key",
        "sequence",
        "level",
        "heading",
        "brief",
    }
    assert section["properties"]["sequence"]["maximum"] == 12
    assert section["properties"]["brief"]["minLength"] == 1


def test_internal_nonce_requires_canonical_lowercase_uuid() -> None:
    contract = _agent_openapi()
    nonce = cast(dict[str, Any], contract["components"]["parameters"]["InternalNonce"])
    schema = cast(dict[str, Any], nonce["schema"])
    pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"

    assert schema == {"type": "string", "format": "uuid", "pattern": pattern}
    assert "canonical lowercase UUID" in nonce["description"]
    assert "Uppercase or non-canonical forms" in nonce["description"]
    assert "before the Nonce claim" in nonce["description"]
    assert re.fullmatch(pattern, "abcdefab-cdef-4abc-8def-abcdefabcdef") is not None
    assert re.fullmatch(pattern, "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF") is None


def test_agent_openapi_marks_only_readiness_as_unsigned_internal_api() -> None:
    contract = _agent_openapi()
    paths = cast(dict[str, Any], contract["paths"])
    assert paths["/internal/v1/health/ready"]["get"]["security"] == []
    expected_headers = {
        "X-Internal-Key-Id",
        "X-Internal-Timestamp",
        "X-Internal-Nonce",
        "X-Content-SHA256",
        "X-Internal-Signature",
    }
    parameters = cast(dict[str, Any], contract["components"]["parameters"])
    for path, raw_path_item in paths.items():
        if not path.startswith("/internal/v1/") or path == "/internal/v1/health/ready":
            continue
        path_item = cast(dict[str, Any], raw_path_item)
        for method, operation in path_item.items():
            if method not in HTTP_METHODS:
                continue
            assert operation["security"] == [{"InternalHmac": []}]
            assert {"401", "503"} <= operation["responses"].keys()
            references = {
                item["$ref"].removeprefix("#/components/parameters/")
                for item in operation["parameters"]
                if "$ref" in item
            }
            header_names = {parameters[name]["name"] for name in references}
            assert expected_headers <= header_names


def test_chat_event_schema_does_not_allow_business_secrets() -> None:
    schema = json.loads(
        (ROOT / "contracts" / "events" / "chat-run-event-v1.schema.json").read_text()
    )
    serialized = json.dumps(schema).lower()
    for forbidden in ("jwt", "password", "apikey", "base64", "prompt"):
        assert forbidden not in serialized


def test_task_draft_wire_contract_distinguishes_new_and_authorized_revision() -> None:
    schema = json.loads(
        (ROOT / "contracts" / "events" / "chat-run-event-v1.schema.json").read_text()
    )
    validator = cast(_JsonSchemaValidator, Draft202012Validator(schema))
    proposal = TaskDraftProposal(
        task_type=TaskType.IMAGE, space_id="123", product_id="456", parameters={"count": 1}
    )
    event: dict[str, Any] = {
        "schemaVersion": 1,
        "eventId": "00000000-0000-4000-8000-000000000001",
        "eventType": "task-draft.proposed",
        "runId": "00000000-0000-4000-8000-000000000002",
        "occurredAt": "2026-09-24T10:00:00Z",
        "sequence": 3,
        "payload": proposal.model_dump(mode="json", by_alias=True),
    }
    validator.validate(event)
    assert event["payload"]["draftOperation"] == "NEW"
    assert event["payload"]["previousDraftId"] is None
    revised = TaskDraftProposal(
        task_type=TaskType.IMAGE,
        space_id="123",
        product_id="456",
        draft_operation=DraftOperation.REVISE,
        previous_draft_id="789",
        parameters={"count": 1},
    )
    validator.validate({**event, "payload": revised.model_dump(mode="json", by_alias=True)})
    for bad in (
        {"draftOperation": "REVISE", "previousDraftId": None},
        {"draftOperation": "REVISE"},
        {"draftOperation": "NEW", "previousDraftId": "789"},
    ):
        with pytest.raises(JsonSchemaValidationError):
            validator.validate({**event, "payload": bad})


def test_rabbit_execution_request_schema_is_strict_and_contains_no_payload_body() -> None:
    contract_path = ROOT / "contracts" / "rabbit" / "task-execution-requested-v1.schema.json"
    raw_contract = contract_path.read_bytes().replace(b"\r\n", b"\n")
    schema = json.loads(raw_contract)
    assert hashlib.sha256(raw_contract).hexdigest() == (
        "e13ea99e873c34a9ccad011148b1c53d6d606109b206b27b875aa20f150e2928"
    )
    assert schema["additionalProperties"] is False
    assert schema["properties"]["schemaVersion"] == {"const": 1}
    assert schema["properties"]["eventType"] == {"const": "TASK_EXECUTION_REQUESTED"}
    assert "traceparent" in schema["required"]
    assert schema["properties"]["traceparent"] == {
        "type": "string",
        "pattern": "^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$",
    }
    assert schema["$defs"]["id"] == {
        "type": "string",
        "pattern": "^[1-9][0-9]*$",
    }
    assert schema["properties"]["attempt"] == {"type": "integer", "minimum": 1}
    assert schema["properties"]["taskType"] == {"enum": ["GEO", "IMAGE", "VIDEO", "ARTICLE"]}
    Draft202012Validator.check_schema(schema)
    properties = set(schema["properties"])
    assert not properties & {"prompt", "body", "base64", "jwt", "credential", "providerKey"}


def test_hmac_vector_is_internally_consistent() -> None:
    vector = json.loads(
        (ROOT / "contracts" / "hmac" / "hmac-v1-test-vector.json").read_text(encoding="utf-8")
    )
    body = vector["bodyUtf8"].encode()
    assert hashlib.sha256(body).hexdigest() == vector["bodySha256"]
    canonical = "\n".join(
        (
            vector["timestamp"],
            vector["nonce"],
            vector["method"].upper(),
            vector["rawPathWithQuery"],
            vector["bodySha256"],
        )
    )
    signature = hmac.new(
        vector["secret"].encode(),
        canonical.encode(),
        hashlib.sha256,
    ).digest()
    assert base64.b64encode(signature).decode() == vector["signatureBase64"]


@pytest.mark.parametrize(
    "relative_path",
    [
        Path("hmac/hmac-v1-test-vector.json"),
        Path("rabbit/task-execution-requested-v1.schema.json"),
    ],
)
def test_java_and_python_shared_contract_copies_are_normalized_equal(
    relative_path: Path,
) -> None:
    python_path = ROOT / "contracts" / relative_path
    java_path = JAVA_ROOT / "src" / "main" / "resources" / "contracts" / relative_path
    if not java_path.is_file():
        pytest.skip(f"Java repository contract is unavailable: {relative_path.as_posix()}")

    python_document = json.loads(python_path.read_text(encoding="utf-8"))
    java_document = json.loads(java_path.read_text(encoding="utf-8"))

    assert _canonical_json_digest(python_document) == _canonical_json_digest(java_document)


def test_java_conversation_resource_event_preserves_diagnostic_read_purpose() -> None:
    """Conversation SSE is Java-owned, distinct from the internal Agent Run event."""
    path = JAVA_ROOT / "src/main/resources/contracts/sse/conversation-event-v1.schema.json"
    if not path.is_file():
        pytest.skip("Java conversation SSE authority is unavailable")
    document = json.loads(path.read_text(encoding="utf-8"))
    resource = document["$defs"]["resourcePayload"]
    purposes = ResourceReadResponse.model_json_schema(by_alias=True)["properties"]["purpose"][
        "enum"
    ]
    assert set(resource["properties"]["purpose"]["enum"]) == set(purposes)
    validator = cast(
        _JsonSchemaValidator,
        Draft202012Validator({"$ref": "#/$defs/resourcePayload", "$defs": document["$defs"]}),
    )
    event = {
        "kind": "READ",
        "success": True,
        "runId": "cf8589e0-856c-496a-8857-795986ddc72d",
        "purpose": "GEO_DIAGNOSTIC_REPORT",
        "observationId": "101",
        "resourceId": "102",
        "spaceId": "102",
    }
    validator.validate(event)
    for mutation in ({"purpose": "UNAUTHORIZED_REPORT"}, {"privateEvidence": "must not leak"}):
        with pytest.raises(JsonSchemaValidationError):
            validator.validate({**event, **mutation})

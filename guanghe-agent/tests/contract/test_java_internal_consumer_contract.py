import os
from pathlib import Path

import pytest
import yaml
from pydantic import ValidationError

from scripts.verify_java_internal_contract import verify_contract

JAVA_OPENAPI = Path(
    os.environ.get(
        "GUANGHE_AGENT_JAVA_CONTRACT_PATH",
        str(
            Path(__file__).resolve().parents[3]
            / "guanghe-backend"
            / "src"
            / "main"
            / "resources"
            / "contracts"
            / "openapi"
            / "java-internal-openapi.yaml"
        ),
    )
)


@pytest.mark.parametrize("field", ("budget", "artifacts", "images", "index", "unitKey"))
def test_gate_detects_image_candidate_boundary_drift(tmp_path: Path, field: str) -> None:
    if not JAVA_OPENAPI.is_file():
        pytest.skip("Java checkout is required for the cross-repository contract gate")
    document = yaml.safe_load(JAVA_OPENAPI.read_text(encoding="utf-8"))
    schemas = document["components"]["schemas"]
    result = schemas["QwenImageResultRequest"]["properties"]
    if field == "budget":
        schemas["TaskExecutionContext"]["properties"]["budget"]["properties"]["maxImages"] = {
            "type": ["integer", "null"],
            "minimum": 1,
            "maximum": 5,
        }
    elif field == "artifacts":
        result["artifacts"]["maxItems"] = 1
    elif field == "images":
        result["usage"]["properties"]["images"] = {"type": "integer", "const": 1}
    elif field == "index":
        result["artifacts"]["items"]["properties"]["metadata"]["properties"]["index"] = {
            "type": "integer",
            "minimum": 0,
            "maximum": 4,
        }
    else:
        result["artifacts"]["items"]["properties"]["unitKey"] = {"type": ["string", "null"]}
    drifted = tmp_path / "java-internal-openapi.yaml"
    drifted.write_text(yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
    errors = verify_contract(drifted)
    assert any(field in error for error in errors)


def test_checked_out_java_task_context_matches_strict_python_consumers() -> None:
    if not JAVA_OPENAPI.is_file():
        pytest.skip("adjacent Java checkout is required for the cross-repository contract gate")

    assert verify_contract(JAVA_OPENAPI) == []


@pytest.mark.parametrize(
    "mutation", ["mode", "category", "name", "sources", "warnings", "budget", "unknown", "handoff"]
)
def test_gate_detects_diagnostic_nested_context_and_sampling_handoff_drift(
    tmp_path: Path,
    mutation: str,
) -> None:
    if not JAVA_OPENAPI.is_file():
        pytest.skip("Java checkout is required for the diagnostic worker gate")
    document = yaml.safe_load(JAVA_OPENAPI.read_text(encoding="utf-8"))
    schemas = document["components"]["schemas"]
    authorized = schemas["GeoDiagnosticAuthorizedContext"]
    if mutation == "mode":
        schemas["GeoDiagnosticTaskParameters"]["properties"]["sampleMode"]["const"] = "DEEP"
    elif mutation == "category":
        authorized["properties"]["questions"]["items"]["properties"]["category"]["enum"].append(
            "OTHER"
        )
    elif mutation == "name":
        authorized["properties"]["monitoringTarget"]["properties"]["name"]["maxLength"] = 201
    elif mutation in {"sources", "warnings"}:
        authorized["properties"]["diagnostic"]["properties"][mutation]["maxItems"] += 1
    elif mutation == "budget":
        branch = next(
            b
            for b in schemas["TaskExecutionContext"]["oneOf"]
            if "GeoDiagnosticTaskParameters" in str(b)
        )
        branch["properties"]["budget"]["properties"]["maxProviderCalls"]["maximum"] = 1001
    elif mutation == "unknown":
        authorized["additionalProperties"] = True
    else:
        schemas["GeoDiagnosticCompletionAccepted"]["properties"]["taskStatus"]["enum"] = ["READY"]
    target = tmp_path / "java-internal-openapi.yaml"
    target.write_text(yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
    assert any("DIAG" in error or "GeoDiagnostic" in error for error in verify_contract(target))


def test_gate_detects_required_provider_identity_drift(tmp_path: Path) -> None:
    if not JAVA_OPENAPI.is_file():
        pytest.skip("adjacent Java checkout is required for the cross-repository contract gate")
    document = yaml.safe_load(JAVA_OPENAPI.read_text(encoding="utf-8"))
    image_branch = document["components"]["schemas"]["TaskExecutionContext"]["oneOf"][0]
    image_branch["required"].remove("model")
    drifted = tmp_path / "java-internal-openapi.yaml"
    drifted.write_text(yaml.safe_dump(document, sort_keys=False), encoding="utf-8")

    errors = verify_contract(drifted)

    assert any("IMAGE: required fields differ" in error for error in errors)


def test_gate_detects_article_plan_and_schema_version_drift(tmp_path: Path) -> None:
    if not JAVA_OPENAPI.is_file():
        pytest.skip("adjacent Java checkout is required for the cross-repository contract gate")
    document = yaml.safe_load(JAVA_OPENAPI.read_text(encoding="utf-8"))
    schemas = document["components"]["schemas"]
    title_branch = next(
        branch
        for branch in schemas["ArticleContentResult"]["oneOf"]
        if branch["properties"]["mode"].get("const") == "TITLE"
    )
    title_branch["properties"]["titleCandidates"]["maxItems"] = 4
    artifacts = schemas["ArticleContentResultRequest"]["properties"]["artifacts"]["items"]
    artifacts["properties"]["schemaVersion"]["const"] = 1
    drifted = tmp_path / "java-internal-openapi.yaml"
    drifted.write_text(yaml.safe_dump(document, sort_keys=False), encoding="utf-8")

    errors = verify_contract(drifted)

    assert "ARTICLE TITLE result must require exactly 5 candidates" in errors
    assert "ARTICLE_CONTENT artifact schemaVersion must be const 2" in errors


def test_gate_detects_article_result_prompt_version_drift(tmp_path: Path) -> None:
    if not JAVA_OPENAPI.is_file():
        pytest.skip("Java checkout is required for the cross-repository contract gate")
    document = yaml.safe_load(JAVA_OPENAPI.read_text(encoding="utf-8"))
    versions = document["components"]["schemas"]["ArticleContentResult"]["properties"][
        "promptVersion"
    ]["enum"]
    versions.remove("agent-bundle-v1.29.0")
    drifted = tmp_path / "java-internal-openapi.yaml"
    drifted.write_text(yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
    assert (
        "ArticleContentResult.promptVersion differs from Python result consumer"
        in verify_contract(drifted)
    )


@pytest.mark.parametrize(
    "mutation", ("branch", "receipt", "codes", "model", "provider", "unknown", "required", "stage")
)
def test_gate_detects_article_unknown_failure_boundary_drift(tmp_path: Path, mutation: str) -> None:
    if not JAVA_OPENAPI.is_file():
        pytest.skip("adjacent Java checkout is required for the cross-repository contract gate")
    document = yaml.safe_load(JAVA_OPENAPI.read_text(encoding="utf-8"))
    schemas = document["components"]["schemas"]
    details = schemas["ArticleUnknownFailureDetails"]
    event = schemas["TaskUnknownFailureEventRequest"]
    if mutation == "branch":
        event["properties"]["details"]["oneOf"].pop()
    elif mutation == "receipt":
        details["properties"]["providerTaskId"] = {"type": ["string", "null"]}
    elif mutation == "codes":
        details["properties"]["code"]["enum"].append("ARBITRARY_FAILURE")
    elif mutation == "model":
        details["properties"]["model"]["pattern"] = ".*"
    elif mutation == "provider":
        details["properties"]["provider"]["const"] = "OPENAI"
    elif mutation == "unknown":
        details["additionalProperties"] = True
    elif mutation == "required":
        details["required"].remove("providerTaskId")
    else:
        event["properties"]["stageCode"]["const"] = "FAILED"
    drifted = tmp_path / "java-internal-openapi.yaml"
    drifted.write_text(yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
    assert any("ARTICLE UNKNOWN" in error for error in verify_contract(drifted))


@pytest.mark.parametrize("mutation", ("widen", "narrow"))
def test_gate_exercises_article_unknown_production_event_validator(
    monkeypatch: pytest.MonkeyPatch, mutation: str
) -> None:
    if not JAVA_OPENAPI.is_file():
        pytest.skip("adjacent Java checkout is required for the cross-repository contract gate")

    def changed_validation(payload: object) -> object:
        if mutation == "widen":
            return payload
        raise ValidationError.from_exception_data(
            "ChangedEventBoundary",
            [
                {
                    "type": "value_error",
                    "loc": (),
                    "input": None,
                    "ctx": {"error": ValueError("reject all events")},
                }
            ],
        )

    monkeypatch.setattr(
        "scripts.verify_java_internal_contract._TaskExecutionEventRequest.model_validate",
        changed_validation,
    )
    errors = verify_contract(JAVA_OPENAPI)
    assert any(
        "ARTICLE UNKNOWN producer accepts" in error
        if mutation == "widen"
        else "ARTICLE UNKNOWN producer rejects" in error
        for error in errors
    )


@pytest.mark.parametrize("mutation", ("required", "unknown", "bound", "nullability"))
def test_gate_detects_chat_geo_projection_drift(tmp_path: Path, mutation: str) -> None:
    if not JAVA_OPENAPI.is_file():
        pytest.skip("Java checkout is required for the cross-repository contract gate")
    document = yaml.safe_load(JAVA_OPENAPI.read_text(encoding="utf-8"))
    schemas = document["components"]["schemas"]
    if mutation == "required":
        schemas["GeoChatReport"]["required"].remove("interpretationScope")
    elif mutation == "unknown":
        schemas["GeoChatCandidate"]["additionalProperties"] = True
    elif mutation == "bound":
        schemas["GeoChatCompetitors"]["properties"]["candidates"]["maxItems"] = 30
    else:
        schemas["GeoChatEntity"]["properties"]["recognitionStatus"] = {"type": "string"}
    drifted = tmp_path / "java-internal-openapi.yaml"
    drifted.write_text(yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
    assert any("GeoChat" in error for error in verify_contract(drifted))


@pytest.mark.parametrize(
    "mutation",
    (
        "required",
        "unknown",
        "pattern",
        "unique",
        "groups",
        "release-required",
        "original-optional",
        "release-nullable",
        "hash-pattern",
    ),
)
def test_gate_detects_agent_identity_drift(tmp_path: Path, mutation: str) -> None:
    if not JAVA_OPENAPI.is_file():
        pytest.skip("Java checkout is required for the cross-repository contract gate")
    document = yaml.safe_load(JAVA_OPENAPI.read_text(encoding="utf-8"))
    schemas = document["components"]["schemas"]
    identity = schemas["AuthorizedAgentIdentity"]
    if mutation == "required":
        schemas["AuthorizedRunContext"]["required"].append("agentIdentity")
    elif mutation == "unknown":
        identity["additionalProperties"] = True
    elif mutation == "pattern":
        identity["properties"]["agentVersion"]["pattern"] = ".*"
    elif mutation == "unique":
        identity["properties"]["capabilities"]["uniqueItems"] = False
    elif mutation == "release-required":
        identity["required"].append("executionRelease")
    elif mutation == "original-optional":
        identity["required"].remove("projectId")
    elif mutation == "release-nullable":
        identity["properties"]["executionRelease"]["type"] = ["string", "null"]
    elif mutation == "hash-pattern":
        identity["properties"]["catalogHash"]["pattern"] = ".*"
    else:
        schemas["AuthorizedRunContext"]["properties"]["loadedToolGroups"]["minItems"] = 1
    drifted = tmp_path / "java-internal-openapi.yaml"
    drifted.write_text(yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
    assert any(
        "AgentIdentity" in error or "agentIdentity" in error or "loadedToolGroups" in error
        for error in verify_contract(drifted)
    )


@pytest.mark.parametrize(
    "mutation", ("required", "nullability", "unknown", "mode", "range", "questions", "platforms")
)
def test_gate_detects_monitor_action_scope_drift(tmp_path: Path, mutation: str) -> None:
    if not JAVA_OPENAPI.is_file():
        pytest.skip("Java checkout is required for the cross-repository contract gate")
    document = yaml.safe_load(JAVA_OPENAPI.read_text(encoding="utf-8"))
    schemas = document["components"]["schemas"]
    if mutation == "required":
        schemas["AuthorizedRunContext"]["required"].append("monitorActionContext")
    elif mutation == "nullability":
        schemas["AuthorizedRunContext"]["properties"]["monitorActionContext"] = {
            "$ref": "#/components/schemas/MonitorActionContext"
        }
    elif mutation == "unknown":
        schemas["MonitorActionContext"]["additionalProperties"] = True
    elif mutation == "mode":
        schemas["GeoTaskParameters"]["properties"]["mode"]["const"] = "QUESTION_DISCOVERY"
    elif mutation == "range":
        schemas["GeoTaskParameters"]["properties"]["questionIds"]["uniqueItems"] = False
    elif mutation == "questions":
        schemas["MonitorActionContext"]["properties"]["questions"]["maxItems"] = 11
    else:
        schemas["GeoTaskParameters"]["properties"]["platformCodes"]["items"]["enum"].append("ANY")
    drifted = tmp_path / "java-internal-openapi.yaml"
    drifted.write_text(yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
    assert any(
        "monitorActionContext" in error
        or "MonitorActionContext" in error
        or "GeoTaskParameters" in error
        for error in verify_contract(drifted)
    )


@pytest.mark.parametrize(
    "mutation",
    (
        "unknown",
        "source",
        "required",
        "bound",
        "review",
        "mode",
        "reference",
        "current_required",
        "current_bound",
        "current_unique",
        "current_id",
        "current_type",
        "current_only",
        "both_empty",
        "history_empty",
        "review_source",
        "current_mode",
    ),
)
def test_gate_detects_creative_history_and_bound_source_drift(
    tmp_path: Path, mutation: str
) -> None:
    if not JAVA_OPENAPI.is_file():
        pytest.skip("Java checkout is required for the creative continuation contract gate")
    document = yaml.safe_load(JAVA_OPENAPI.read_text(encoding="utf-8"))
    schemas = document["components"]["schemas"]
    evidence = schemas["RunCreativeContinuationEvidence"]
    if mutation == "unknown":
        evidence["additionalProperties"] = True
    elif mutation == "source":
        evidence["properties"]["sourceImageAssetId"]["pattern"] = ".*"
    elif mutation == "required":
        evidence["required"].remove("sourceImageAssetId")
    elif mutation == "bound":
        evidence["properties"]["history"]["maxItems"] = 7
    elif mutation == "review":
        schemas["RunCreativeContinuationReview"]["properties"]["decision"]["const"] = "RETRY"
    elif mutation == "mode":
        schemas["RunCreativeContinuationReview"]["properties"]["sourceMode"]["enum"].append(
            "PRODUCT_MAIN_IMAGE"
        )
    elif mutation == "reference":
        schemas["RunImageGenerateArguments"]["properties"].pop("continuationReview")
    elif mutation == "current_required":
        evidence["required"].remove("currentAttachmentAssetIds")
    elif mutation == "current_bound":
        evidence["properties"]["currentAttachmentAssetIds"]["maxItems"] = 9
    elif mutation == "current_unique":
        evidence["properties"]["currentAttachmentAssetIds"]["uniqueItems"] = False
    elif mutation == "current_id":
        evidence["properties"]["currentAttachmentAssetIds"]["items"]["pattern"] = ".*"
    elif mutation == "current_type":
        evidence["properties"]["currentAttachmentAssetIds"]["items"]["type"] = ["string", "integer"]
    elif mutation == "current_only":
        evidence["anyOf"] = [{"properties": {"history": {"minItems": 1}}}]
    elif mutation == "both_empty":
        evidence.pop("anyOf")
    elif mutation == "history_empty":
        evidence["properties"]["history"]["minItems"] = 1
    elif mutation == "review_source":
        schemas["RunCreativeContinuationReview"].pop("allOf")
    else:
        schemas["RunCreativeContinuationReview"]["properties"]["sourceMode"]["enum"].remove(
            "CURRENT_ATTACHMENTS"
        )
    target = tmp_path / "java-internal-openapi.yaml"
    target.write_text(yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
    assert any(
        "CREATIVE" in error or "RunCreativeContinuation" in error
        for error in verify_contract(target)
    )

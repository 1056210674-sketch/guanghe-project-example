from scripts.check_runtime_readiness import evaluate_readiness

COMMIT = "a" * 40


def test_ready_component_passes_with_matching_build_and_optional_check() -> None:
    result = evaluate_readiness(
        "image",
        200,
        {
            "status": "READY",
            "gitCommit": COMMIT,
            "checks": [
                {"name": "workerRuntime", "status": "READY", "reasonCode": None},
                {
                    "name": "langfuseExport",
                    "status": "NOT_CONFIGURED",
                    "reasonCode": "LANGFUSE_EXPORT_NOT_CONFIGURED",
                },
            ],
        },
        COMMIT,
    )

    assert result.ready is True
    assert result.safe_payload()["reasonCodes"] == []


def test_provider_failure_blocks_release_without_leaking_details() -> None:
    result = evaluate_readiness(
        "image",
        503,
        {
            "status": "NOT_READY",
            "gitCommit": COMMIT,
            "checks": [
                {
                    "name": "workerRuntime",
                    "status": "NOT_READY",
                    "reasonCode": "TASK_PROVIDER_UNAVAILABLE",
                    "details": "credential=must-not-leak",
                }
            ],
        },
        COMMIT,
    )

    assert result.ready is False
    assert result.reason_codes == ("TASK_PROVIDER_UNAVAILABLE",)
    assert "must-not-leak" not in str(result.safe_payload())


def test_build_mismatch_blocks_feature_enablement() -> None:
    result = evaluate_readiness(
        "video",
        200,
        {"status": "READY", "gitCommit": "b" * 40, "checks": []},
        COMMIT,
    )

    assert result.ready is False
    assert result.reason_codes == ("BUILD_COMMIT_MISMATCH",)

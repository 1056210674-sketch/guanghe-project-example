"""Single stable-error taxonomy shared by tracing and metrics."""

from __future__ import annotations


def classify_failure(stable_error_code: str) -> tuple[str, str]:
    """Return a bounded category and retry strategy without error detail."""
    normalized = stable_error_code.upper()
    if normalized.endswith("BUDGET_EXCEEDED") or "OUT_OF_MEMORY" in normalized:
        return "BUDGET", "NEVER"
    if "DEADLINE" in normalized:
        return "DEADLINE", "NEVER"
    if "STRUCTURED_REPAIR" in normalized or "REPAIR_EXHAUSTED" in normalized:
        return "MODEL_OUTPUT", "BOUNDED_REPAIR"
    if "REPLAN" in normalized:
        return (
            "WORKFLOW_REPLAN",
            "NEVER" if "EXHAUSTED" in normalized else "BOUNDED_RETRY",
        )
    if "WORKFLOW_WAIT" in normalized:
        return "WORKFLOW_WAIT", "SAFE_RETRY"
    if "UNKNOWN" in normalized and any(
        marker in normalized for marker in ("PROVIDER", "SUBMISSION", "POLL", "RESULT", "TASK")
    ):
        return "PROVIDER_UNKNOWN", "RECOVERY_ONLY"
    if normalized.startswith(("PROVIDER_", "QWEN_IMAGE_", "WAN_VIDEO_", "GEO_PROVIDER_")):
        if any(
            marker in normalized
            for marker in ("AUTH", "BALANCE", "QUOTA", "CONFIG", "NOT_CONFIGURED")
        ):
            return "PROVIDER_CONFIGURATION", "NEVER"
        if "RATE_LIMIT" in normalized:
            return "PROVIDER_RATE_LIMIT", "SAFE_RETRY"
        return "PROVIDER", "BOUNDED_RETRY"
    if any(
        marker in normalized
        for marker in (
            "AUTHENTICATION",
            "AUTH_FAILED",
            "BALANCE",
            "QUOTA",
            "MODEL_NOT_FOUND",
            "MODEL_RETIRED",
            "REGION_MISMATCH",
            "CONFIGURATION",
            "NOT_CONFIGURED",
        )
    ) and any(marker in normalized for marker in ("MODEL", "DEEPSEEK", "QWEN", "WAN")):
        return "MODEL_CONFIGURATION", "NEVER"
    if "RATE_LIMIT" in normalized and any(
        marker in normalized for marker in ("MODEL", "DEEPSEEK", "QWEN", "WAN")
    ):
        return "MODEL_RATE_LIMIT", "SAFE_RETRY"
    if any(
        marker in normalized for marker in ("TIMEOUT", "CONNECTION", "DNS", "TLS", "UPSTREAM")
    ) and any(marker in normalized for marker in ("MODEL", "DEEPSEEK", "QWEN", "WAN")):
        return "MODEL_TRANSPORT", "SAFE_RETRY"
    if any(marker in normalized for marker in ("MODEL_", "DEEPSEEK_", "QWEN_")) or any(
        marker in normalized for marker in ("RESPONSE_INVALID", "EMPTY_RESPONSE")
    ):
        return "MODEL_OUTPUT", "BOUNDED_REPAIR"
    if "CONTEXT" in normalized or normalized in {
        "PROCESS_MEMORY_PRESSURE",
        "CONTEXT_WINDOW_EXCEEDED",
    }:
        return "CONTEXT", "SAFE_RETRY"
    if "FENCING" in normalized or "LEASE_" in normalized or "STALE_EXECUTION" in normalized:
        return "WORKER_FENCING", "RECOVERY_ONLY"
    if "CLAIM" in normalized and any(
        marker in normalized for marker in ("CONFLICT", "REJECTED", "UNAVAILABLE")
    ):
        return "WORKER_CONTROL", "SAFE_RETRY"
    if normalized.startswith("TOOL_") and any(
        marker in normalized
        for marker in (
            "POLICY",
            "FORBIDDEN",
            "NOT_ALLOWED",
            "NOT_AUTHORIZED",
            "NOT_REGISTERED",
            "GROUP_",
            "INPUT_INVALID",
            "ARGUMENT",
            "CONFIRMATION_REQUIRED",
        )
    ):
        return "TOOL_POLICY", "NEVER"
    if normalized == "COMMIT_TOOL_FORBIDDEN":
        return "TOOL_POLICY", "NEVER"
    if normalized.startswith("TOOL_"):
        return "TOOL_EXECUTION", "BOUNDED_RETRY"
    if "RESULT" in normalized and any(
        marker in normalized for marker in ("JAVA", "HANDOFF", "REPORT")
    ):
        return "RESULT_HANDOFF", "SAFE_RETRY"
    if normalized.startswith(("RABBIT_", "WORKER_", "CLAIM_", "JAVA_", "WORKFLOW_JAVA_")):
        return "WORKER_CONTROL", "SAFE_RETRY"
    return "INTERNAL", "NEVER"

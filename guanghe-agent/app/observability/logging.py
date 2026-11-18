"""Minimal JSON logging without configuration or secret expansion."""

import json
import logging
from datetime import UTC, datetime
from hashlib import sha256
from logging.config import dictConfig
from typing import Any

from app.observability.trace import current_span_id, current_trace_id


class JsonFormatter(logging.Formatter):
    """Emit a small allowlisted JSON log envelope."""

    def format(self, record: logging.LogRecord) -> str:
        is_raw_network_log = record.name == "httpx" or record.name.startswith("httpcore")
        payload: dict[str, Any] = {
            "timestamp": datetime.now(UTC).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": "external_http_client_log_suppressed"
            if is_raw_network_log
            else record.getMessage(),
        }
        for field in (
            "traceId",
            "spanId",
            "taskId",
            "agentRunId",
            "agentKey",
            "agentVersion",
            "modelCallCount",
            "contextLoadCount",
            "providerInputTokens",
            "providerOutputTokens",
            "userIdHash",
            "toolCallId",
            "toolName",
            "toolVersion",
            "riskLevel",
            "inputHash",
            "status",
            "attempt",
            "latencyMs",
            "stableErrorCode",
            "outputRef",
            "provider",
            "artifactHost",
            "operation",
            "failureStage",
            "attemptCount",
            "fallbackType",
            "classifiedIntent",
            "draftOperation",
            "clarificationReason",
            "clarificationTarget",
            "revisionTargetPresent",
            "revisionTargetFromAuthorizedContext",
            "providerRequestRef",
            "javaTaskStatus",
            "messageCount",
            "estimatedInputTokens",
            "finishReason",
            "contentLength",
            "contentNonWhitespaceLength",
            "outputMode",
            "toolCallCount",
            "expectedToolName",
            "validationErrors",
            "sourceMode",
            "referenceAssetCount",
            "contextSource",
            "reasoningContentLength",
            "platformCode",
            "httpStatus",
            "timeoutPhase",
            "retryDelayMs",
            "admissionWaitMs",
            "upstreamOutcomeUnknown",
            "providerCallCount",
            "remainingProviderCalls",
            "remainingDeadlineMs",
            "observationCount",
            "successfulObservationCount",
        ):
            value = getattr(record, field, None)
            if field == "traceId" and value is None:
                value = current_trace_id()
            if field == "spanId" and value is None:
                value = current_span_id()
            if value is not None:
                if field == "providerRequestRef":
                    value = f"sha256:{sha256(str(value).encode()).hexdigest()[:16]}"
                payload[field] = value
        if record.exc_info is not None:
            exception_type = record.exc_info[0]
            if exception_type is not None:
                payload["exceptionType"] = exception_type.__name__
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def configure_logging(level: str) -> None:
    """Configure stdout JSON logs using an allowlisted record shape."""
    dictConfig(
        {
            "version": 1,
            "disable_existing_loggers": False,
            "formatters": {"json": {"()": JsonFormatter}},
            "handlers": {
                "console": {
                    "class": "logging.StreamHandler",
                    "formatter": "json",
                    "stream": "ext://sys.stdout",
                }
            },
            "root": {"handlers": ["console"], "level": level},
            "loggers": {
                "httpx": {"handlers": [], "level": "CRITICAL", "propagate": False},
                "httpcore": {"handlers": [], "level": "CRITICAL", "propagate": False},
                "opentelemetry.exporter": {
                    "handlers": [],
                    "level": "CRITICAL",
                    "propagate": False,
                },
            },
        }
    )

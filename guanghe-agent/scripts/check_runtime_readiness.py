"""Fail closed before enabling Java features that depend on Python workers."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from typing import Any, cast
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

DEFAULT_ENDPOINTS = {
    "image": "http://127.0.0.1:8010/internal/v1/health/ready",
    "video": "http://127.0.0.1:8011/internal/v1/health/ready",
    "geo": "http://127.0.0.1:8012/internal/v1/health/ready",
    "article": "http://127.0.0.1:8013/internal/v1/health/ready",
    "workflow": "http://127.0.0.1:8014/internal/v1/health/ready",
}
REASON_CODE = re.compile(r"^[A-Z][A-Z0-9_]{0,127}$")
GIT_COMMIT = re.compile(r"^[0-9a-f]{40}$")


@dataclass(frozen=True)
class ReadinessResult:
    component: str
    http_status: int | None
    status: str
    git_commit: str | None
    reason_codes: tuple[str, ...]

    @property
    def ready(self) -> bool:
        return self.http_status == 200 and self.status == "READY" and not self.reason_codes

    def safe_payload(self) -> dict[str, object]:
        return {
            "component": self.component,
            "httpStatus": self.http_status,
            "status": self.status,
            "gitCommit": self.git_commit,
            "reasonCodes": list(self.reason_codes),
        }


def evaluate_readiness(
    component: str,
    http_status: int | None,
    payload: object,
    expected_commit: str | None,
) -> ReadinessResult:
    if not isinstance(payload, dict):
        return ReadinessResult(component, http_status, "NOT_READY", None, ("INVALID_RESPONSE",))
    typed_payload = cast(dict[str, Any], payload)
    status = typed_payload.get("status")
    safe_status = status if status in {"READY", "NOT_READY"} else "NOT_READY"
    commit = typed_payload.get("gitCommit")
    safe_commit = commit if isinstance(commit, str) and GIT_COMMIT.fullmatch(commit) else None
    raw_checks = typed_payload.get("checks")
    checks = cast(list[object], raw_checks) if isinstance(raw_checks, list) else []
    reasons: set[str] = set()
    for raw_check in checks:
        if not isinstance(raw_check, dict):
            continue
        check = cast(dict[str, Any], raw_check)
        reason = check.get("reasonCode")
        if (
            check.get("status") == "NOT_READY"
            and isinstance(reason, str)
            and REASON_CODE.fullmatch(reason)
        ):
            reasons.add(reason)
    if http_status != 200 and not reasons:
        reasons.add("READINESS_HTTP_FAILED")
    if safe_status != "READY" and not reasons:
        reasons.add("READINESS_NOT_READY")
    if expected_commit is not None and safe_commit != expected_commit:
        reasons.add("BUILD_COMMIT_MISMATCH")
    return ReadinessResult(component, http_status, safe_status, safe_commit, tuple(sorted(reasons)))


def fetch_readiness(
    component: str, endpoint: str, timeout_seconds: float, expected_commit: str | None
) -> ReadinessResult:
    request = Request(endpoint, headers={"Accept": "application/json"})
    status: int | None = None
    try:
        with urlopen(request, timeout=timeout_seconds) as response:
            status = response.status
            payload = json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        status = error.code
        try:
            payload = json.loads(error.read().decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            payload = None
    except (URLError, TimeoutError, OSError):
        return ReadinessResult(component, None, "NOT_READY", None, ("READINESS_UNREACHABLE",))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return ReadinessResult(component, status, "NOT_READY", None, ("INVALID_RESPONSE",))
    return evaluate_readiness(component, status, payload, expected_commit)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Check loopback Python readiness before enabling dependent Java features."
    )
    parser.add_argument(
        "--component",
        action="append",
        choices=sorted(DEFAULT_ENDPOINTS),
        required=True,
        help="Required runtime component; repeat for multiple components.",
    )
    parser.add_argument("--expected-commit")
    parser.add_argument("--timeout-seconds", type=float, default=3.0)
    arguments = parser.parse_args()
    if arguments.expected_commit is not None and not GIT_COMMIT.fullmatch(
        arguments.expected_commit
    ):
        parser.error("--expected-commit must be a lowercase 40-character Git SHA")
    if not 0.1 <= arguments.timeout_seconds <= 30:
        parser.error("--timeout-seconds must be between 0.1 and 30")

    components = tuple(dict.fromkeys(arguments.component))
    results = [
        fetch_readiness(
            component,
            DEFAULT_ENDPOINTS[component],
            arguments.timeout_seconds,
            arguments.expected_commit,
        )
        for component in components
    ]
    for result in results:
        print(json.dumps(result.safe_payload(), separators=(",", ":"), sort_keys=True))
    print(
        "PYTHON_RUNTIME_RELEASE_GATE="
        + ("PASS" if all(result.ready for result in results) else "FAIL")
    )
    return 0 if all(result.ready for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())

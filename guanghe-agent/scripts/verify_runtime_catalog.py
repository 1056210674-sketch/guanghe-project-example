"""Read-only cross-repository Agent release check; never changes a database or calls a model."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

import yaml

REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from app.agents.catalog import CatalogError, parse_catalog  # noqa: E402
from app.common.settings import default_bundle_path  # noqa: E402
from app.prompts.loader import BundleLoadError, load_bundle  # noqa: E402


def _git(repository: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repository), *arguments],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return result.stdout.strip()


def verify(java_repository: Path, web_repository: Path, *, require_clean: bool) -> int:
    canonical = REPOSITORY_ROOT / "app/agents/runtime-catalog.json"
    mirror = java_repository / "src/main/resources/contracts/agents/runtime-catalog.json"
    openapi = java_repository / "src/main/resources/contracts/openapi/java-internal-openapi.yaml"
    errors: list[str] = []
    try:
        raw = canonical.read_bytes().replace(b"\r\n", b"\n")
        catalog = parse_catalog(raw)
        catalog.validate_bundle(load_bundle(default_bundle_path()))
        if raw != mirror.read_bytes().replace(b"\r\n", b"\n"):
            errors.append("JAVA_PYTHON_CATALOG_MISMATCH")
        document = yaml.safe_load(openapi.read_text(encoding="utf-8"))
        accepted = json.loads(raw)["acceptedArticlePromptVersions"]
        contract_versions = document["components"]["schemas"]["ArticleContentResult"]["properties"][
            "promptVersion"
        ]["enum"]
        if (
            len(accepted) != len(set(accepted))
            or set(accepted) != set(contract_versions)
            or catalog.bundle_version not in accepted
        ):
            errors.append("ARTICLE_PROMPT_VERSION_CONTRACT_MISMATCH")
        print(f"RUNTIME_CATALOG_SHA256={catalog.catalog_hash}")
        print(f"RUNTIME_BUNDLE_VERSION={catalog.bundle_version}")
        print(f"RUNTIME_BUNDLE_SHA256={catalog.bundle_sha256}")
        print(f"REGISTERED_AGENT_COUNT={len(catalog.agents)}")
    except (OSError, ValueError, KeyError, TypeError, yaml.YAMLError, BundleLoadError) as exc:
        # Stable codes only; never dump configuration, secrets, prompts or user content.
        reason = (
            exc.code if isinstance(exc, (CatalogError, BundleLoadError)) else type(exc).__name__
        )
        errors.append(f"RUNTIME_CATALOG_INVALID:{reason}")

    dirty = False
    for name, repository in (
        ("JAVA", java_repository),
        ("PYTHON", REPOSITORY_ROOT),
        ("WEB", web_repository),
    ):
        try:
            commit = _git(repository, "rev-parse", "HEAD")
            status = _git(repository, "status", "--porcelain", "--untracked-files=all")
            source_state = "DIRTY" if status else "CLEAN"
            dirty = dirty or bool(status)
            print(f"{name}_SOURCE_COMMIT={commit}")
            print(f"{name}_SOURCE_STATE={source_state}")
            if status and require_clean:
                errors.append(f"{name}_SOURCE_NOT_REPRODUCIBLE")
        except (OSError, subprocess.CalledProcessError, UnicodeError):
            errors.append(f"{name}_SOURCE_REVISION_UNAVAILABLE")

    for error in errors:
        print(f"CATALOG_GATE_ERROR={error}")
    if errors:
        print("AGENT_RUNTIME_CATALOG_GATE=FAIL")
        return 1
    print("AGENT_RUNTIME_CATALOG_GATE=PASS")
    print(f"SOURCE_RELEASE_READINESS={'NOT_READY_DIRTY_SOURCE' if dirty else 'CLEAN_SOURCE'}")
    if not require_clean:
        print("MODE=DEVELOPMENT_CHECK_NOT_DEPLOYMENT_APPROVAL")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--java-repository", type=Path, default=REPOSITORY_ROOT.parent / "guanghe-backend"
    )
    parser.add_argument(
        "--web-repository", type=Path, default=REPOSITORY_ROOT.parent / "guanghe-web"
    )
    parser.add_argument(
        "--require-clean", action="store_true", help="Reject dirty/untracked source before release."
    )
    args = parser.parse_args()
    return verify(
        args.java_repository.resolve(),
        args.web_repository.resolve(),
        require_clean=args.require_clean,
    )


if __name__ == "__main__":
    raise SystemExit(main())

"""Validated, read-only process runtime state."""

from dataclasses import dataclass

from app.common.build_info import resolve_runtime_git_commit
from app.common.settings import AppSettings
from app.prompts.loader import BundleLoadError, LoadedBundle, load_bundle
from app.tools.registry import DEFAULT_TOOL_REGISTRY, ToolRegistryError


@dataclass(frozen=True, slots=True)
class RuntimeState:
    settings: AppSettings
    git_commit: str | None
    bundle: LoadedBundle | None
    bundle_error_code: str | None


def initialize_runtime(settings: AppSettings) -> RuntimeState:
    """Load the small configuration Bundle without opening external connections."""
    git_commit = resolve_runtime_git_commit(settings.git_commit)
    try:
        bundle = load_bundle(settings.bundle_path)
        DEFAULT_TOOL_REGISTRY.validate_bundle(bundle.bundle)
    except BundleLoadError as exc:
        return RuntimeState(
            settings=settings,
            git_commit=git_commit,
            bundle=None,
            bundle_error_code=exc.code,
        )
    except ToolRegistryError:
        return RuntimeState(
            settings=settings,
            git_commit=git_commit,
            bundle=None,
            bundle_error_code="BUNDLE_TOOL_REGISTRY_MISMATCH",
        )
    return RuntimeState(
        settings=settings,
        git_commit=git_commit,
        bundle=bundle,
        bundle_error_code=None,
    )

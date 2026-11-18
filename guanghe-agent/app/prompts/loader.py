"""Strict, bounded YAML Bundle loader."""

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml
from pydantic import ValidationError

from app.prompts.models import AgentBundle

MAX_BUNDLE_BYTES = 1_048_576


class BundleLoadError(Exception):
    """Safe Bundle failure carrying a public reason code only."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


@dataclass(frozen=True, slots=True)
class LoadedBundle:
    bundle: AgentBundle
    bundle_hash: str
    prompt_hash: str
    normalized_bundle_hash: str | None = None


def _prompt_hash(bundle: AgentBundle) -> str:
    prompt_payload = [
        {"code": prompt.code, "version": prompt.version, "system": prompt.system}
        for prompt in bundle.prompts
    ]
    canonical = json.dumps(
        prompt_payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode()
    return hashlib.sha256(canonical).hexdigest()


def load_bundle(path: Path) -> LoadedBundle:
    """Read and fully validate a small YAML Bundle before publishing it in memory."""
    try:
        size = path.stat().st_size
    except FileNotFoundError as exc:
        raise BundleLoadError("BUNDLE_FILE_NOT_FOUND") from exc
    except OSError as exc:
        raise BundleLoadError("BUNDLE_FILE_UNREADABLE") from exc

    if size > MAX_BUNDLE_BYTES:
        raise BundleLoadError("BUNDLE_FILE_TOO_LARGE")

    try:
        raw = path.read_bytes()
        untrusted: Any = yaml.safe_load(raw)
        bundle = AgentBundle.model_validate(untrusted)
    except (OSError, UnicodeError, yaml.YAMLError, ValidationError, TypeError) as exc:
        raise BundleLoadError("BUNDLE_SCHEMA_INVALID") from exc

    return LoadedBundle(
        bundle=bundle,
        bundle_hash=hashlib.sha256(raw).hexdigest(),
        prompt_hash=_prompt_hash(bundle),
        normalized_bundle_hash=hashlib.sha256(raw.replace(b"\r\n", b"\n")).hexdigest(),
    )

"""Strict code-owned runtime catalogue, never loaded from a user/URL/environment path."""

import hashlib
import json
from collections.abc import Mapping
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from types import MappingProxyType
from typing import Annotated, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator
from pydantic.alias_generators import to_camel

from app.prompts.loader import LoadedBundle

_LEGACY_IDENTITIES = frozenset({("geo", "1.0.0"), ("creative", "1.0.0"), ("summary", "1.0.0")})
ReleaseName = Annotated[str, Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")]
CapabilityName = Annotated[str, Field(pattern=r"^[a-z][a-z0-9_.-]{0,63}$")]
AgentKey = Annotated[str, Field(pattern=r"^[a-z][a-z0-9-]{0,63}$")]
PromptCode = Annotated[str, Field(pattern=r"^[A-Z][A-Z0-9_]{0,63}$")]
LegacyGrants = Annotated[tuple[CapabilityName, ...], Field(max_length=128)]


class CatalogError(ValueError):
    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


class _Model(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, extra="forbid", frozen=True)


class _Tool(_Model):
    required_capabilities: tuple[CapabilityName, ...] = Field(min_length=1, max_length=32)

    @model_validator(mode="after")
    def unique_capabilities(self) -> Self:
        if len(self.required_capabilities) != len(set(self.required_capabilities)):
            raise ValueError("Duplicate tool capabilities")
        return self


class _Agent(_Model):
    agent_key: AgentKey
    agent_version: str = Field(pattern=r"^[0-9]+\.[0-9]+\.[0-9]+$")
    catalogue_adapter_key: AgentKey
    runtime_kind: Literal["native", "summary"]
    prompt_code: PromptCode
    execution_release: ReleaseName
    intent: Literal["GEO", "GENERAL"]
    context_policy: Literal["geo-brand", "plain"]
    continuation_policy: Literal["geo-article", "none"]
    explicit_actions: tuple[Literal["MONITOR_RUN", "ARTICLE_FULL_V1"], ...]
    required_capabilities: tuple[CapabilityName, ...] = Field(min_length=1, max_length=32)
    capabilities: tuple[CapabilityName, ...] = Field(min_length=1, max_length=32)
    legacy_capability_grants: dict[CapabilityName, LegacyGrants]
    allowed_tools: tuple[CapabilityName, ...] = Field(max_length=64)

    @model_validator(mode="after")
    def validate_policy(self) -> Self:
        ceiling = set(self.capabilities)
        if (
            "chat" not in self.required_capabilities
            or not set(self.required_capabilities) <= ceiling
        ):
            raise ValueError("Invalid required capabilities")
        for values in (
            self.capabilities,
            self.required_capabilities,
            self.allowed_tools,
            self.explicit_actions,
            *self.legacy_capability_grants.values(),
        ):
            if len(values) != len(set(values)):
                raise ValueError("Duplicate catalogue values")
        if (
            self.legacy_capability_grants
            and (self.agent_key, self.agent_version) not in _LEGACY_IDENTITIES
        ):
            raise ValueError("Only existing built-ins may use legacy capability grants")
        if any(not set(grants) <= ceiling for grants in self.legacy_capability_grants.values()):
            raise ValueError("Legacy grant exceeds registered ceiling")
        if self.runtime_kind == "summary" and (
            self.allowed_tools
            or self.explicit_actions
            or self.context_policy != "plain"
            or self.continuation_policy != "none"
            or "text.summarize" not in self.required_capabilities
        ):
            raise ValueError("Summary runtime must remain text-only")
        return self


class _Catalog(_Model):
    schema_version: Literal[1]
    bundle_version: ReleaseName
    bundle_sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    worker_profile_code: PromptCode
    accepted_article_prompt_versions: tuple[ReleaseName, ...] = Field(min_length=1, max_length=128)
    tools: dict[CapabilityName, _Tool] = Field(min_length=1)
    agents: tuple[_Agent, ...] = Field(min_length=1, max_length=256)

    @model_validator(mode="after")
    def validate_published_versions(self) -> Self:
        versions = self.accepted_article_prompt_versions
        if len(versions) != len(set(versions)) or self.bundle_version not in versions:
            raise ValueError("Published article versions must be unique and include this bundle")
        return self


@dataclass(frozen=True, slots=True)
class AgentRegistration:
    agent_key: str
    agent_version: str
    runtime_kind: str
    prompt_code: str
    execution_release: str
    intent: str
    context_policy: str
    continuation_policy: str
    explicit_actions: frozenset[str]
    required_capabilities: frozenset[str]
    capabilities: frozenset[str]
    legacy_capability_grants: Mapping[str, frozenset[str]]
    allowed_tools: frozenset[str]

    @property
    def legacy_identity_allowed(self) -> bool:
        return (self.agent_key, self.agent_version) in _LEGACY_IDENTITIES


@dataclass(frozen=True, slots=True)
class RuntimeCatalog:
    catalog_hash: str
    bundle_version: str
    bundle_sha256: str
    worker_profile_code: str
    agents: Mapping[tuple[str, str], AgentRegistration]
    tool_capabilities: Mapping[str, frozenset[str]]

    def validate_bundle(self, bundle: LoadedBundle) -> None:
        if (
            bundle.bundle.bundle_version != self.bundle_version
            or (bundle.normalized_bundle_hash or bundle.bundle_hash) != self.bundle_sha256
        ):
            raise CatalogError("AGENT_CATALOG_BUNDLE_MISMATCH")
        prompts = {prompt.code for prompt in bundle.bundle.prompts}
        if any(agent.prompt_code not in prompts for agent in self.agents.values()):
            raise CatalogError("AGENT_CATALOG_PROMPT_MISSING")
        if self.worker_profile_code not in {profile.code for profile in bundle.bundle.profiles}:
            raise CatalogError("AGENT_CATALOG_WORKER_PROFILE_MISSING")


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON field")
        result[key] = value
    return result


def parse_catalog(raw: bytes) -> RuntimeCatalog:
    """Parse release-owned bytes; also permits deterministic in-memory functional fixtures."""
    if len(raw) > 262_144:
        raise CatalogError("AGENT_CATALOG_TOO_LARGE")
    normalized = raw.replace(b"\r\n", b"\n")
    try:
        model = _Catalog.model_validate(json.loads(normalized, object_pairs_hook=_unique_object))
        agents: dict[tuple[str, str], AgentRegistration] = {}
        for item in model.agents:
            key = (item.agent_key, item.agent_version)
            if key in agents or not set(item.allowed_tools) <= model.tools.keys():
                raise ValueError("Duplicate identity or unregistered tool")
            agents[key] = AgentRegistration(
                item.agent_key,
                item.agent_version,
                item.runtime_kind,
                item.prompt_code,
                item.execution_release,
                item.intent,
                item.context_policy,
                item.continuation_policy,
                frozenset(item.explicit_actions),
                frozenset(item.required_capabilities),
                frozenset(item.capabilities),
                MappingProxyType(
                    {key: frozenset(value) for key, value in item.legacy_capability_grants.items()}
                ),
                frozenset(item.allowed_tools),
            )
    except (ValidationError, ValueError, UnicodeError, TypeError) as exc:
        raise CatalogError("AGENT_CATALOG_INVALID") from exc
    return RuntimeCatalog(
        hashlib.sha256(normalized).hexdigest(),
        model.bundle_version,
        model.bundle_sha256,
        model.worker_profile_code,
        MappingProxyType(agents),
        MappingProxyType(
            {name: frozenset(tool.required_capabilities) for name, tool in model.tools.items()}
        ),
    )


@lru_cache(maxsize=1)
def load_catalog() -> RuntimeCatalog:
    try:
        raw = Path(__file__).with_name("runtime-catalog.json").read_bytes()
    except OSError as exc:
        raise CatalogError("AGENT_CATALOG_UNAVAILABLE") from exc
    return parse_catalog(raw)

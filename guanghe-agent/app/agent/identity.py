"""Code-owned execution registrations and per-Run capability intersection.

Only Java's authorized context selects an identity. This is not a plugin loader,
model router, permission store or a second tool registry.
"""

from collections.abc import Generator
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from types import MappingProxyType

from app.agents.catalog import AgentRegistration, RuntimeCatalog, load_catalog


class AgentIdentityError(RuntimeError):
    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


@dataclass(frozen=True, slots=True)
class AgentExecution:
    agent_key: str
    agent_version: str
    capabilities: frozenset[str]
    registration: AgentRegistration
    catalog: RuntimeCatalog


# Compatibility exports are projections of the one published catalogue, not registrations.
GEO_CAPABILITIES = load_catalog().agents[("geo", "1.0.0")].capabilities
SUMMARY_CAPABILITIES = load_catalog().agents[("summary", "1.0.0")].capabilities
CREATIVE_CAPABILITIES = load_catalog().agents[("creative", "1.0.0")].capabilities
REGISTERED_AGENTS = MappingProxyType(
    {key: registration.capabilities for key, registration in load_catalog().agents.items()}
)
_CURRENT_EXECUTION: ContextVar[AgentExecution | None] = ContextVar(
    "agent_execution_identity", default=None
)


def resolve_execution(
    agent_key: str,
    agent_version: str,
    capabilities: tuple[str, ...],
    *,
    catalog: RuntimeCatalog | None = None,
    execution_release: str | None = None,
    catalog_hash: str | None = None,
) -> AgentExecution:
    catalog = catalog or load_catalog()
    registered = catalog.agents.get((agent_key, agent_version))
    if registered is None:
        code = (
            "AGENT_VERSION_NOT_REGISTERED"
            if any(key == agent_key for key, _ in catalog.agents)
            else "AGENT_NOT_REGISTERED"
        )
        raise AgentIdentityError(code)
    if execution_release is None and catalog_hash is None:
        if not registered.legacy_identity_allowed:
            raise AgentIdentityError("AGENT_EXECUTION_RELEASE_REQUIRED")
    elif execution_release != registered.execution_release or catalog_hash != catalog.catalog_hash:
        raise AgentIdentityError("AGENT_EXECUTION_RELEASE_MISMATCH")
    granted = set(capabilities)
    if execution_release is None and catalog_hash is None:
        for source, additions in registered.legacy_capability_grants.items():
            if source in capabilities:
                granted.update(additions)
    effective = registered.capabilities.intersection(granted)
    if not registered.required_capabilities <= effective:
        raise AgentIdentityError("AGENT_CAPABILITY_DENIED")
    return AgentExecution(agent_key, agent_version, effective, registered, catalog)


@contextmanager
def bind_agent_execution(execution: AgentExecution) -> Generator[None]:
    token = _CURRENT_EXECUTION.set(execution)
    try:
        yield
    finally:
        _CURRENT_EXECUTION.reset(token)


def current_agent_execution() -> AgentExecution | None:
    """Read the immutable Run binding; domain policies must not infer an identity."""
    return _CURRENT_EXECUTION.get()


def capability_available(capability: str) -> bool:
    execution = _CURRENT_EXECUTION.get()
    # Existing direct domain/worker callers retain their separately authorized boundary.
    return execution is None or capability in execution.capabilities


def require_capability(capability: str) -> None:
    if not capability_available(capability):
        raise AgentIdentityError("AGENT_CAPABILITY_DENIED")


def tool_available(name: str) -> bool:
    execution = _CURRENT_EXECUTION.get()
    if execution is None:
        return True
    required = execution.catalog.tool_capabilities.get(name)
    return (
        name in execution.registration.allowed_tools
        and required is not None
        and required <= execution.capabilities
    )


def require_preparation_capability(intent: str) -> None:
    required = {
        "GEO": "geo.monitor",
        "IMAGE": "image.generate",
        "VIDEO": "video.generate",
        "ARTICLE": "article.generate",
    }.get(intent)
    if required is None:
        raise AgentIdentityError("AGENT_CAPABILITY_DENIED")
    require_capability(required)

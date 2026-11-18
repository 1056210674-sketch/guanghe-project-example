"""Model-only view of the existing project brand identity and scoped memory.

This is not another store, intake workflow or task authorization gate. Missing
recorded fields can already be present in the current USER or selected material.
"""

from typing import Any

from app.providers.java_context import AuthorizedRunContext
from app.providers.knowledge_context import ProjectMemoryContext


def require_project_memory_scope(
    context: AuthorizedRunContext,
    memory: ProjectMemoryContext | None,
    current_brand_identity: dict[str, Any] | None = None,
) -> None:
    """Fence every refreshed memory against this Run, including first brand binding.

    Installed creative agents also have project memory, so use their authorized
    identity directly rather than GEO report-selection eligibility predicates.
    """
    expected = None if context.agent_identity is None else context.agent_identity.project_id
    if expected is not None and context.selected_space_id not in (None, expected):
        raise ValueError("MEMORY_PROJECT_MISMATCH")
    expected = expected or context.selected_space_id
    if current_brand_identity is not None:
        bound = current_brand_identity.get("projectId")
        if not isinstance(bound, str) or (expected is not None and bound != expected):
            raise ValueError("MEMORY_PROJECT_MISMATCH")
        expected = bound
    if memory is not None and memory.project_id != expected:
        raise ValueError("MEMORY_PROJECT_MISMATCH")


def project_brand_brief(
    context: AuthorizedRunContext,
    memory: ProjectMemoryContext | None,
    current_brand_identity: dict[str, Any] | None = None,
) -> dict[str, Any]:
    require_project_memory_scope(context, memory, current_brand_identity)
    identity = current_brand_identity
    if identity is None and context.space is not None:
        # space.name is the customer's project label, NOT their public brand.
        identity = {
            "projectId": context.space.id,
            "name": context.space.brand.name,
            "aliases": list(context.space.brand.aliases),
        }
    fields: dict[str, Any] = {
        "brandIdentity": {
            "label": "对外品牌名称",
            "status": "RECORDED" if identity else "NOT_RECORDED",
            "value": identity,
            "source": "CURRENT_BRAND_IDENTITY" if identity else None,
        }
    }
    for field, label, key in (
        ("offerings", "主营业务", "brand.categories"),
        ("serviceArea", "实际经营与服务范围", "brand.service_area"),
        ("targetRegions", "长期默认目标曝光地域", "geo.target_regions"),
        ("priorityOffering", "长期优先推广业务(可选)", "geo.priority_offering"),
        ("targetQueries", "目标关键词或需求方向(可选)", "geo.target_queries"),
    ):
        entry = (
            next(
                (item for item in memory.entries if item.scope == "PROJECT" and item.key == key),
                None,
            )
            if memory
            else None
        )
        fields[field] = {
            "label": label,
            "key": key,
            "status": "NOT_LOADED"
            if memory is None
            else "NOT_RECORDED"
            if entry is None
            else "DELETED"
            if entry.deleted
            else "RECORDED",
            "value": None if entry is None or entry.deleted else entry.value,
            "version": None if entry is None else entry.version,
        }
    required = ("brandIdentity", "offerings", "serviceArea", "targetRegions")
    return {
        "schemaVersion": 1,
        "fields": fields,
        "missingRecordedFields": [
            name for name in required if fields[name]["status"] != "RECORDED"
        ],
        "scope": "CURRENT_PROJECT_ONLY",
        "meaning": "RECORDED_FIELDS_NOT_EXECUTION_PERMISSION_OR_GLOBAL_PREREQUISITES",
    }

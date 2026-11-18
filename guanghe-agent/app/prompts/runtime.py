"""Small helpers for loading the published commerce Prompt Bundle in Workers."""

from pathlib import Path

from app.agents.catalog import load_catalog
from app.common.enums import TaskType
from app.prompts.assembly import CommercePromptSet
from app.prompts.loader import load_bundle


def load_commerce_prompt_set(path: Path) -> CommercePromptSet:
    loaded = load_bundle(path)
    catalog = load_catalog()
    catalog.validate_bundle(loaded)
    profile = next(
        item for item in loaded.bundle.profiles if item.code == catalog.worker_profile_code
    )
    return CommercePromptSet.from_bundle(loaded.bundle, profile)


def load_execution_prompt(path: Path, task_type: TaskType) -> str:
    return load_commerce_prompt_set(path).for_execution(task_type)


__all__ = ["load_commerce_prompt_set", "load_execution_prompt"]

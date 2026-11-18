"""Cross-service enums defined by the v1 contracts."""

from enum import StrEnum


class TaskType(StrEnum):
    GEO = "GEO"
    IMAGE = "IMAGE"
    VIDEO = "VIDEO"
    ARTICLE = "ARTICLE"
    MEDIA = "MEDIA"


class ProviderStatus(StrEnum):
    AVAILABLE = "AVAILABLE"
    CONFIGURED = "CONFIGURED"
    UNAVAILABLE = "UNAVAILABLE"
    NOT_CONFIGURED = "NOT_CONFIGURED"
    MODEL_NOT_CONFIGURED = "MODEL_NOT_CONFIGURED"
    DISABLED = "DISABLED"

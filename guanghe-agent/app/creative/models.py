"""Strict Java-owned creative brief and ephemeral reference-asset boundaries."""

from datetime import datetime
from enum import StrEnum
from typing import Annotated, Self, cast
from urllib.parse import urlsplit

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    JsonValue,
    StrictInt,
    StrictStr,
    StringConstraints,
    field_validator,
    model_validator,
)
from pydantic.alias_generators import to_camel

SnowflakeId = Annotated[StrictStr, StringConstraints(pattern=r"^[1-9][0-9]{0,18}$")]
Text150 = Annotated[StrictStr, StringConstraints(min_length=1, max_length=150)]
Text100Optional = Annotated[StrictStr, StringConstraints(max_length=100)]
Text150Optional = Annotated[StrictStr, StringConstraints(max_length=150)]
Text200 = Annotated[StrictStr, StringConstraints(max_length=200)]
Text500 = Annotated[StrictStr, StringConstraints(max_length=500)]
Sha256 = Annotated[StrictStr, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
ImageContentType = Annotated[
    StrictStr,
    StringConstraints(pattern=r"^image/[a-z0-9.+-]+$"),
]


class _CreativeModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        strict=True,
        hide_input_in_errors=True,
    )


class CreativeSourceMode(StrEnum):
    CATALOG_PRODUCT = "CATALOG_PRODUCT"
    CURRENT_ATTACHMENTS = "CURRENT_ATTACHMENTS"
    PREVIOUS_USER_ATTACHMENT = "PREVIOUS_USER_ATTACHMENT"
    PREVIOUS_IMAGE_RESULT = "PREVIOUS_IMAGE_RESULT"
    TEXT_ONLY = "TEXT_ONLY"
    SELECTED_ASSETS = "SELECTED_ASSETS"
    PRODUCT_MATERIALS = "PRODUCT_MATERIALS"
    GEO_REPORT = "GEO_REPORT"
    REGION_EDIT = "REGION_EDIT"


class CreativeReferenceAsset(_CreativeModel):
    """A Java-authorized short-lived provider input; its URL is never logged or persisted."""

    asset_id: SnowflakeId
    content_type: ImageContentType
    size_bytes: StrictInt = Field(ge=1)
    sha256: Sha256
    width: StrictInt = Field(ge=8, le=8192)
    height: StrictInt = Field(ge=8, le=8192)
    read_url: StrictStr = Field(min_length=1, repr=False)
    expires_at: AwareDatetime

    @field_validator("expires_at", mode="before")
    @classmethod
    def parse_java_expiry(cls, value: object) -> object:
        if isinstance(value, str):
            return datetime.fromisoformat(value.replace("Z", "+00:00"))
        return value

    @field_validator("read_url")
    @classmethod
    def read_url_must_be_ephemeral_https(cls, value: str) -> str:
        parsed = urlsplit(value)
        if (
            parsed.scheme != "https"
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
            or parsed.fragment
        ):
            raise ValueError("reference readUrl must be an HTTPS URL without embedded userinfo")
        return value


class ProductFacts(_CreativeModel):
    """Exact fact fields frozen by Java; an absent field remains unknown."""

    name: Text150 | None = None
    category: Text100Optional | None = None
    model_spec: Text150Optional | None = None
    selling_points: tuple[Text500, ...] = Field(default=(), max_length=50)
    parameters: dict[str, JsonValue] = Field(default_factory=dict, max_length=100)
    audience: tuple[Text200, ...] = Field(default=(), max_length=50)
    usage_scenarios: tuple[Text200, ...] = Field(default=(), max_length=50)

    @field_validator("selling_points", "audience", "usage_scenarios", mode="before")
    @classmethod
    def arrays_must_be_frozen_after_json_decoding(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class CreativeProvenance(_CreativeModel):
    conversation_id: SnowflakeId | None
    message_id: SnowflakeId | None
    reference_message_id: SnowflakeId | None = None


class ProductCreativeBrief(_CreativeModel):
    """Fact-preserving brief supplied by Java and treated as immutable by Python."""

    source_mode: CreativeSourceMode
    product_id: SnowflakeId | None
    reference_asset_ids: tuple[SnowflakeId, ...] = Field(max_length=3)
    product_facts: ProductFacts | None
    creative_direction: dict[str, JsonValue] = Field(max_length=32)
    prohibited_claims: tuple[Text500, ...] = Field(max_length=100)
    provenance: CreativeProvenance

    @field_validator("source_mode", mode="before")
    @classmethod
    def parse_wire_source_mode(cls, value: object) -> object:
        # Java payloads arrive as decoded JSON dictionaries. Preserve strict
        # enum semantics while accepting the exact wire value.
        if isinstance(value, str):
            return CreativeSourceMode(value)
        return value

    @field_validator("reference_asset_ids", "prohibited_claims", mode="before")
    @classmethod
    def arrays_must_be_frozen_after_json_decoding(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @field_validator("reference_asset_ids")
    @classmethod
    def reference_asset_ids_must_be_unique(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        if len(value) != len(set(value)):
            raise ValueError("referenceAssetIds must be unique")
        return value

    @model_validator(mode="after")
    def source_mode_must_control_product_authority(self) -> Self:
        if self.source_mode in {
            CreativeSourceMode.CATALOG_PRODUCT,
            CreativeSourceMode.PRODUCT_MATERIALS,
            CreativeSourceMode.GEO_REPORT,
        }:
            if self.product_id is None or self.product_facts is None:
                raise ValueError("catalog source requires product identity and facts")
        elif self.product_id is not None or self.product_facts is not None:
            raise ValueError("non-catalog sources cannot carry product identity or facts")
        if self.source_mode is CreativeSourceMode.TEXT_ONLY:
            if self.reference_asset_ids:
                raise ValueError("text-only mode cannot carry reference assets")
        elif not self.reference_asset_ids:
            raise ValueError("reference mode requires reference assets")
        if (
            self.source_mode
            in {
                CreativeSourceMode.PREVIOUS_USER_ATTACHMENT,
                CreativeSourceMode.PREVIOUS_IMAGE_RESULT,
            }
            and len(self.reference_asset_ids) != 1
        ):
            raise ValueError("previous image source requires exactly one reference asset")
        if self.source_mode is CreativeSourceMode.PREVIOUS_USER_ATTACHMENT:
            if self.provenance.reference_message_id is None:
                raise ValueError("previous user attachment requires its source message")
        elif self.provenance.reference_message_id is not None:
            raise ValueError("reference message is valid only for previous user attachment")
        if self.source_mode is CreativeSourceMode.REGION_EDIT:
            if len(self.reference_asset_ids) != 1:
                raise ValueError("region edit requires exactly one base image")
            mask_id = self.creative_direction.get("maskAssetId")
            if (
                not isinstance(mask_id, str)
                or not mask_id.isascii()
                or not mask_id.isdecimal()
                or not 1 <= len(mask_id) <= 19
                or mask_id.startswith("0")
            ):
                raise ValueError("region edit requires an authorized maskAssetId")
            if mask_id in self.reference_asset_ids:
                raise ValueError("mask must be distinct from the base image")
        if self.source_mode is CreativeSourceMode.GEO_REPORT:
            if "geoInsight" in self.creative_direction:
                raise ValueError("GEO report text must not be frozen into a creative brief")
            from app.creative.geo_visual_plan import FrozenGeoVisualPlan

            FrozenGeoVisualPlan.model_validate(self.creative_direction.get("creativePlan"))
        return self

    def validate_reference_assets(
        self,
        references: tuple[CreativeReferenceAsset, ...],
    ) -> None:
        if tuple(item.asset_id for item in references) != self.reference_asset_ids:
            raise ValueError("reference assets must match the frozen brief order")

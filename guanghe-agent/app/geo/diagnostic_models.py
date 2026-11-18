"""Immutable diagnostic facts reused by card preparation and delivery evidence."""

import ipaddress
from typing import Annotated, Literal, cast
from urllib.parse import urlsplit

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    StrictStr,
    StringConstraints,
    field_validator,
    model_validator,
)
from pydantic.alias_generators import to_camel

Name = Annotated[StrictStr, StringConstraints(strip_whitespace=True, min_length=1, max_length=200)]
SourceId = Annotated[StrictStr, StringConstraints(min_length=1, max_length=128)]


class DiagnosticWire(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        strict=True,
        hide_input_in_errors=True,
    )


def _public_link(value: str, *, source: bool) -> str:
    """Validate links only; diagnostic execution never fetches supplied URLs."""
    parsed = urlsplit(value)
    hostname = parsed.hostname
    if (
        parsed.scheme not in ({"http", "https"} if source else {"https"})
        or not hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.port not in ({None, 80, 443} if source else {None, 443})
        or any(ord(char) < 33 for char in value)
        or hostname.lower().endswith((".localhost", ".local", ".internal"))
        or hostname.lower() == "localhost"
        or "." not in hostname
    ):
        raise ValueError("Diagnostic URLs require public web links")
    try:
        address = ipaddress.ip_address(hostname)
    except ValueError:
        pass
    else:
        if not address.is_global:
            raise ValueError("Diagnostic URLs require public web links")
    return value


def public_https(value: str) -> str:
    return _public_link(value, source=False)


def public_source_url(value: str) -> str:
    return _public_link(value, source=True)


class DiagnosticProfile(DiagnosticWire):
    brand_name: Name
    website: str | None = Field(max_length=2048)
    introduction: str = Field(max_length=6000)
    brand_terms: tuple[Name, ...] = Field(max_length=50)
    region: str = Field(max_length=100)

    @field_validator("brand_terms", mode="before")
    @classmethod
    def freeze_terms(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @field_validator("website")
    @classmethod
    def website_is_link(cls, value: str | None) -> str | None:
        return public_https(value) if value else None


class DiagnosticSource(DiagnosticWire):
    id: SourceId
    title: str = Field(min_length=1, max_length=500)
    url: str = Field(min_length=8, max_length=2048)
    snippet: str = Field(max_length=2000)

    @field_validator("url")
    @classmethod
    def source_is_link(cls, value: str) -> str:
        return public_source_url(value)


class DiagnosticConfirmedProfile(DiagnosticProfile):
    """Java's submitted brand-level snapshot, not an incomplete preparation card."""

    introduction: str = Field(min_length=1, max_length=6000)
    brand_terms: tuple[Name, ...] = Field(min_length=1, max_length=50)
    region: str = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def confirmed_business(self) -> "DiagnosticConfirmedProfile":
        if (
            not self.introduction.strip()
            or not self.region.strip()
            or self.brand_name not in self.brand_terms
        ):
            raise ValueError(
                "Submitted diagnostic profile requires confirmed business and primary name"
            )
        return self


class DiagnosticCompetitor(DiagnosticWire):
    id: SourceId
    name: Name
    brand_terms: tuple[Name, ...] = Field(max_length=50)
    reason: str = Field(max_length=1000)
    source_ids: tuple[SourceId, ...] = Field(max_length=8)
    origin: Literal["AI", "USER"]
    edited: StrictBool

    @field_validator("brand_terms", "source_ids", mode="before")
    @classmethod
    def freeze_lists(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

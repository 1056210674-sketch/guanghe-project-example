"""Bounded search excerpts, never authoritative brand facts or page contents."""

from typing import Annotated, Literal
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator
from pydantic.alias_generators import to_camel


class WebResearchSource(BaseModel):
    model_config = ConfigDict(
        extra="forbid", frozen=True, populate_by_name=True, alias_generator=to_camel
    )
    title: Annotated[str, StringConstraints(max_length=500)] | None = None
    url: Annotated[str, StringConstraints(min_length=8, max_length=2048)]
    snippet: Annotated[str, StringConstraints(max_length=600)] | None = None
    observed_at: Annotated[str, StringConstraints(min_length=1, max_length=64)]

    @field_validator("url")
    @classmethod
    def public_link_only(cls, value: str) -> str:
        parsed = urlsplit(value)
        if (
            parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
            or any(ord(char) < 33 for char in value)
        ):
            raise ValueError("Research sources require credential-free HTTP(S) links")
        return value

    @field_validator("observed_at")
    @classmethod
    def timestamp_with_offset(cls, value: str) -> str:
        from datetime import datetime

        if datetime.fromisoformat(value.replace("Z", "+00:00")).tzinfo is None:
            raise ValueError("Research observation needs a timestamp offset")
        return value


class WebResearchEvidence(BaseModel):
    model_config = ConfigDict(
        extra="forbid", frozen=True, populate_by_name=True, alias_generator=to_camel
    )
    source_type: Literal["WEB_SEARCH_EXCERPTS"]
    full_pages_read: Literal[False]
    sources: tuple[WebResearchSource, ...] = Field(min_length=1, max_length=8)

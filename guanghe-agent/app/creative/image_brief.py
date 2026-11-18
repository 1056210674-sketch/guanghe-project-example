"""Bounded visual direction shared by chat preparation and image execution."""

import re
from typing import Annotated, Literal, cast

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictStr,
    StringConstraints,
    field_validator,
    model_validator,
)
from pydantic.alias_generators import to_camel

CopyLine = Annotated[StrictStr, StringConstraints(min_length=1, max_length=40)]


class ImageVisualBrief(BaseModel):
    """Creative choices only: no identity, price, or invented product facts."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        strict=True,
        hide_input_in_errors=True,
    )

    version: Literal["commerce-image-v1"]
    platform: Literal["GENERAL", "PINDUODUO", "TAOBAO", "JD", "DOUYIN", "XIAOHONGSHU"]
    placement: Literal["MAIN_IMAGE", "DETAIL_IMAGE", "SOCIAL_COVER", "POSTER"]
    visual_focus: StrictStr = Field(min_length=1, max_length=160)
    composition: StrictStr = Field(min_length=1, max_length=240)
    background: StrictStr = Field(min_length=1, max_length=180)
    lighting: StrictStr = Field(min_length=1, max_length=160)
    text_policy: Literal["NONE", "USER_SUPPLIED"]
    copy_lines: tuple[CopyLine, ...] = Field(max_length=3)

    @field_validator("copy_lines", mode="before")
    @classmethod
    def freeze_wire_list(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @field_validator("visual_focus", "composition", "background", "lighting")
    @classmethod
    def require_nonblank_direction(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("visual direction must not be blank")
        return value.strip()

    @model_validator(mode="after")
    def require_consistent_copy_policy(self) -> "ImageVisualBrief":
        if (self.text_policy == "NONE" and self.copy_lines) or (
            self.text_policy == "USER_SUPPLIED" and not self.copy_lines
        ):
            raise ValueError("image copy does not match text policy")
        if any(not line.strip() for line in self.copy_lines):
            raise ValueError("image copy must not be blank")
        return self

    def validate_copy_source(self, current_request: str, previous: object = None) -> None:
        """A mention is not consent: require a positive copy instruction or frozen copy."""
        prior_lines: tuple[str, ...] = ()
        if isinstance(previous, dict):
            prior_lines = ImageVisualBrief.model_validate(previous).copy_lines
        for line in self.copy_lines:
            clauses = re.split(r"[，,。；;\n]", current_request)
            mentions = [clause for clause in clauses if line in clause]
            # Inspect instructions around the literal, not inside copy such as “别熬夜”.
            if any(
                re.search(
                    r"(?:不要|别|禁止|不能|不)(?:添加|新增|写上|写|加上|加|印上|标注|使用)?"
                    r"[\s\"'“\u2018:\uff1a]*$|(?:don't|do not)\s+(?:add|write)\s*[\"']?$",
                    clause[: clause.index(line)],
                    re.IGNORECASE,
                )
                or re.match(
                    r"[\s\"'”\u2019]*(?:这几个字)?(?:去掉|删除|移除|不要写)",
                    clause[clause.index(line) + len(line) :],
                )
                for clause in mentions
            ):
                raise ValueError("image copy must be explicitly supplied by the user")
            if line in prior_lines:
                continue
            if not any(
                re.search(
                    r"加字|配文|写|文案|标语|标注|印上|文字|标题|add|write|caption|text",
                    clause[: clause.index(line)],
                    re.IGNORECASE,
                )
                for clause in mentions
            ):
                raise ValueError("image copy must be explicitly supplied by the user")

    @staticmethod
    def request_excludes_text(current_request: str) -> bool:
        """Conservative literal exclusion guard, not semantic intent routing."""
        instruction = re.sub(
            r'“[^”]*”|\u2018[^\u2019]*\u2019|"[^"]*"|\'[^\']*\'', "", current_request
        )
        return (
            re.search(
                r"不要(?:添加|新增|任何|有)?(?:文字|文案|字)|不(?:加|要|写)字|无字|"
                r"(?:去掉|删除|移除)(?:所有|全部)?(?:文字|文案)|"
                r"\b(?:no|without)\s+(?:text|caption|lettering)\b",
                instruction,
                re.IGNORECASE,
            )
            is not None
        )


__all__ = ["ImageVisualBrief"]

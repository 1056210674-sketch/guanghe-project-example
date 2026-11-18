"""High-value tests for deterministic creative source and fact boundaries."""

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.agent.models import ChatMessage, ChatRunRequest, RunBudget, TrustedState
from app.common.enums import TaskType
from app.creative.brief import (
    CreativeBriefError,
    creative_grounding_instruction,
    select_creative_source,
)
from app.creative.models import CreativeReferenceAsset, ProductCreativeBrief
from app.providers.java_context import AuthorizedRunContext, ProductSnapshot

SPACE_ID = "1900012345678901001"
PRODUCT_ID = "1900012345678901002"
PRIMARY_ASSET_ID = "1900012345678901003"
SECONDARY_ASSET_ID = "1900012345678901006"
TERTIARY_ASSET_ID = "1900012345678901007"
MESSAGE_ID = "1900012345678901004"
ATTACHMENT_ID = "1900012345678901005"


def _request(*, attachments: tuple[str, ...] = ()) -> ChatRunRequest:
    return ChatRunRequest(
        run_id=uuid4(),
        conversation_id="1900012345678901000",
        user_context_ref="opaque-context",
        message=ChatMessage(
            message_id=MESSAGE_ID,
            content="参考当前图片生成一张爆款商品图",
            attachment_asset_ids=attachments,
        ),
        trusted_state=TrustedState(
            selected_space_id=SPACE_ID,
            selected_product_id=PRODUCT_ID,
        ),
        budget=RunBudget(deadline_at=datetime.now(UTC) + timedelta(minutes=1)),
    )


def _context(*, attachments: tuple[str, ...] = ()) -> AuthorizedRunContext:
    product = ProductSnapshot.model_validate(
        {
            "id": PRODUCT_ID,
            "spaceId": SPACE_ID,
            "name": "权威商品名",
            "aliases": [],
            "category": None,
            "modelSpec": None,
            "sellingPoints": [],
            "parameters": {},
            "audience": [],
            "usageScenarios": [],
            "prohibitedClaims": [],
            "primaryAssetId": PRIMARY_ASSET_ID,
            "identityAssetIds": [PRIMARY_ASSET_ID, SECONDARY_ASSET_ID, TERTIARY_ASSET_ID],
        }
    )
    return AuthorizedRunContext(
        source="JAVA_AUTHORIZED_CONTEXT",
        user_context_ref="opaque-context",
        selected_space_id=SPACE_ID,
        selected_product_id=PRODUCT_ID,
        attachment_asset_ids=attachments,
        product=product,
    )


def test_current_attachment_deterministically_wins_and_catalog_is_redacted() -> None:
    request = _request(attachments=(ATTACHMENT_ID,))
    context = _context(attachments=(ATTACHMENT_ID,))

    mode, reference_ids = select_creative_source(TaskType.IMAGE, context, request)

    assert mode.value == "CURRENT_ATTACHMENTS"
    assert reference_ids == (ATTACHMENT_ID,)
    redacted = context.current_attachment_only()
    assert redacted.selected_space_id is None
    assert redacted.selected_product_id is None
    assert redacted.product is None
    assert redacted.conversation_memory is None
    assert redacted.task_workspace is None
    assert redacted.attachment_asset_ids == (ATTACHMENT_ID,)


def test_catalog_mode_uses_first_identity_asset_for_video() -> None:
    mode, reference_ids = select_creative_source(TaskType.VIDEO, _context(), _request())

    assert mode.value == "CATALOG_PRODUCT"
    assert reference_ids == (PRIMARY_ASSET_ID,)


def test_catalog_mode_preserves_up_to_three_authorized_identity_assets_for_image() -> None:
    mode, reference_ids = select_creative_source(TaskType.IMAGE, _context(), _request())

    assert mode.value == "CATALOG_PRODUCT"
    assert reference_ids == (PRIMARY_ASSET_ID, SECONDARY_ASSET_ID, TERTIARY_ASSET_ID)


def test_current_attachment_must_match_the_current_java_authority() -> None:
    with pytest.raises(CreativeBriefError, match="REFERENCE_ASSET_NOT_OWNED"):
        select_creative_source(
            TaskType.IMAGE,
            _context(attachments=(PRIMARY_ASSET_ID,)),
            _request(attachments=(ATTACHMENT_ID,)),
        )


def test_attachment_brief_rejects_catalog_facts_and_signed_url_is_not_repr() -> None:
    base = {
        "sourceMode": "CURRENT_ATTACHMENTS",
        "productId": None,
        "referenceAssetIds": [ATTACHMENT_ID],
        "productFacts": None,
        "creativeDirection": {"requestText": "生成爆款商品图"},
        "prohibitedClaims": ["不得生成未经核验的销量宣传"],
        "provenance": {
            "conversationId": "1900012345678901000",
            "messageId": MESSAGE_ID,
        },
    }
    brief = ProductCreativeBrief.model_validate(base)
    instruction = creative_grounding_instruction(
        brief,
        "保持商品身份并服从创作用途；不得生成销量第一等未经核验宣传。",
    )
    assert "仅使用本次消息参考图片" in instruction
    assert "销量第一" in instruction
    assert "不得生成未经核验的销量宣传" in instruction
    with pytest.raises(ValidationError):
        ProductCreativeBrief.model_validate(
            {
                **base,
                "productId": PRODUCT_ID,
                "productFacts": {"name": "不应混入的商品"},
            }
        )

    reference = CreativeReferenceAsset.model_validate(
        {
            "assetId": ATTACHMENT_ID,
            "contentType": "image/png",
            "sizeBytes": 1,
            "sha256": "a" * 64,
            "width": 1024,
            "height": 1024,
            "readUrl": "https://asset.example/image.png?signature=must-not-log",
            "expiresAt": "2030-09-14T10:00:00+08:00",
        }
    )
    assert "must-not-log" not in repr(reference)
    mismatched = reference.model_copy(update={"asset_id": PRIMARY_ASSET_ID})
    with pytest.raises(ValueError, match="frozen brief order"):
        brief.validate_reference_assets((mismatched,))


def test_previous_image_result_is_one_non_catalog_reference_and_is_grounded() -> None:
    base = {
        "sourceMode": "PREVIOUS_IMAGE_RESULT",
        "productId": None,
        "referenceAssetIds": [ATTACHMENT_ID],
        "productFacts": None,
        "creativeDirection": {"requestText": "让上一张图里的主体挥手"},
        "prohibitedClaims": [],
        "provenance": {
            "conversationId": "1900012345678901000",
            "messageId": MESSAGE_ID,
        },
    }
    brief = ProductCreativeBrief.model_validate(base)
    instruction = creative_grounding_instruction(brief, "让主体自然挥手")
    assert "PREVIOUS_IMAGE_RESULT" in instruction
    assert "上一张生成图片" in instruction

    with pytest.raises(ValidationError, match="exactly one reference asset"):
        ProductCreativeBrief.model_validate(
            {
                **base,
                "referenceAssetIds": [ATTACHMENT_ID, PRIMARY_ASSET_ID],
            }
        )


def test_previous_user_attachment_is_one_non_catalog_reference_with_source_message() -> None:
    base = {
        "sourceMode": "PREVIOUS_USER_ATTACHMENT",
        "productId": None,
        "referenceAssetIds": [ATTACHMENT_ID],
        "productFacts": None,
        "creativeDirection": {"requestText": "按这张图作参考生成一版带红痕的"},
        "prohibitedClaims": [],
        "provenance": {
            "conversationId": "1900012345678901000",
            "messageId": MESSAGE_ID,
            "referenceMessageId": "1900012345678901008",
        },
    }
    brief = ProductCreativeBrief.model_validate(base)
    instruction = creative_grounding_instruction(brief, "参考原图生成带红痕版本")
    assert "PREVIOUS_USER_ATTACHMENT" in instruction
    assert "用户上一轮上传" in instruction

    with pytest.raises(ValidationError, match="source message"):
        ProductCreativeBrief.model_validate(
            {
                **base,
                "provenance": {
                    "conversationId": "1900012345678901000",
                    "messageId": MESSAGE_ID,
                },
            }
        )
    with pytest.raises(ValidationError, match="exactly one reference asset"):
        ProductCreativeBrief.model_validate(
            {
                **base,
                "referenceAssetIds": [ATTACHMENT_ID, PRIMARY_ASSET_ID],
            }
        )

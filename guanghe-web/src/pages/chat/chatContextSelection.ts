import type { SendConversationMessageRequest } from '../../api/conversationApi'

export interface ConversationContextSelectionOverride {
  businessSpaceId?: string
  productId?: string
}

interface ForcedConversationContext {
  spaceId: string
  productId: string | null
}

type ConversationContextRequest = Pick<
  SendConversationMessageRequest,
  'contextSelectionMode' | 'selectedSpaceId' | 'selectedProductId'
>

/**
 * Converts browser selection state into the explicit server-side context
 * command. Undefined means the user did not edit the sidebar for this
 * conversation; an empty override means they deliberately cleared it.
 */
export function toConversationContextRequest(
  override: ConversationContextSelectionOverride | undefined,
  forcedContext?: ForcedConversationContext,
): ConversationContextRequest {
  if (forcedContext) {
    return {
      contextSelectionMode: 'REPLACE',
      selectedSpaceId: forcedContext.spaceId,
      ...(forcedContext.productId
        ? { selectedProductId: forcedContext.productId }
        : {}),
    }
  }

  if (!override) {
    return { contextSelectionMode: 'INHERIT' }
  }

  if (!override.businessSpaceId) {
    return { contextSelectionMode: 'CLEAR' }
  }

  return {
    contextSelectionMode: 'REPLACE',
    selectedSpaceId: override.businessSpaceId,
    ...(override.productId
      ? { selectedProductId: override.productId }
      : {}),
  }
}

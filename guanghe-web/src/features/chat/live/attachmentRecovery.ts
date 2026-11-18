const RECOVERY_PREFIX = 'zhixiaohe.chat.current-attachments.v1'
const SCHEMA_VERSION = 1
const MAX_CURRENT_ATTACHMENTS = 8

interface AttachmentRecoveryPayload {
  schemaVersion: typeof SCHEMA_VERSION
  assetIds: string[]
}

function recoveryKey(ownerId: string, conversationKey: string) {
  return `${RECOVERY_PREFIX}.${ownerId}.${conversationKey}`
}

function normalizedAssetIds(assetIds: readonly string[]) {
  return Array.from(
    new Set(
      assetIds
        .filter((assetId) => typeof assetId === 'string' && assetId.trim())
        .map((assetId) => assetId.trim()),
    ),
  ).slice(0, MAX_CURRENT_ATTACHMENTS)
}

export function readCurrentAttachmentIds(
  ownerId: string,
  conversationKey: string,
  storage: Storage = window.localStorage,
): string[] {
  try {
    const raw = storage.getItem(recoveryKey(ownerId, conversationKey))
    if (!raw) return []
    const payload = JSON.parse(raw) as Partial<AttachmentRecoveryPayload>
    if (
      payload.schemaVersion !== SCHEMA_VERSION ||
      !Array.isArray(payload.assetIds)
    ) {
      storage.removeItem(recoveryKey(ownerId, conversationKey))
      return []
    }
    return normalizedAssetIds(payload.assetIds)
  } catch {
    storage.removeItem(recoveryKey(ownerId, conversationKey))
    return []
  }
}

export function storeCurrentAttachmentIds(
  ownerId: string,
  conversationKey: string,
  assetIds: readonly string[],
  storage: Storage = window.localStorage,
) {
  const key = recoveryKey(ownerId, conversationKey)
  const normalized = normalizedAssetIds(assetIds)
  if (normalized.length === 0) {
    storage.removeItem(key)
    return
  }
  const payload: AttachmentRecoveryPayload = {
    schemaVersion: SCHEMA_VERSION,
    assetIds: normalized,
  }
  storage.setItem(key, JSON.stringify(payload))
}

export function moveCurrentAttachmentIds(
  ownerId: string,
  fromConversationKey: string,
  toConversationKey: string,
  storage: Storage = window.localStorage,
) {
  const assetIds = readCurrentAttachmentIds(
    ownerId,
    fromConversationKey,
    storage,
  )
  storeCurrentAttachmentIds(ownerId, toConversationKey, assetIds, storage)
  storage.removeItem(recoveryKey(ownerId, fromConversationKey))
  return assetIds
}

export function clearCurrentAttachmentIds(
  ownerId: string,
  conversationKey: string,
  storage: Storage = window.localStorage,
) {
  storage.removeItem(recoveryKey(ownerId, conversationKey))
}

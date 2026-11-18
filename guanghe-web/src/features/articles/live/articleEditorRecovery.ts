const STORAGE_PREFIX = 'zhixiaohe.article-editor-draft.v1:'
const MAX_TITLE_LENGTH = 300
const MAX_BODY_LENGTH = 50_000
const MAX_OUTLINE_ITEMS = 100
const MAX_OUTLINE_KEY_LENGTH = 200
const MAX_OUTLINE_HEADING_LENGTH = 300
const MAX_ASSET_IDS = 10
const MAX_ASSET_ID_LENGTH = 200
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000

export interface ArticleEditorRecoveryOutlineItem {
  key: string
  level: number
  heading: string
}

export interface ArticleEditorRecoveryDraft {
  baseVersion: number
  title: string
  bodyText: string
  /** Optional for backwards compatibility with v1 drafts created before the full editor state was persisted. */
  outline?: ArticleEditorRecoveryOutlineItem[]
  /** Optional for backwards compatibility with v1 drafts created before the full editor state was persisted. */
  assetIds?: string[]
  savedAt: number
}

function storage(): Storage | null {
  try {
    return typeof globalThis.localStorage === 'undefined'
      ? null
      : globalThis.localStorage
  } catch {
    return null
  }
}

function removeStorageKey(target: Storage, key: string): void {
  try {
    target.removeItem(key)
  } catch {
    // A failing browser store must never prevent the editor from opening.
  }
}

function validOutline(value: unknown): value is ArticleEditorRecoveryOutlineItem[] {
  if (!Array.isArray(value) || value.length > MAX_OUTLINE_ITEMS) return false
  const keys = new Set<string>()
  return value.every((item) => {
    if (!item || typeof item !== 'object') return false
    const candidate = item as Partial<ArticleEditorRecoveryOutlineItem>
    if (
      typeof candidate.key !== 'string' ||
      candidate.key.trim().length === 0 ||
      candidate.key.length > MAX_OUTLINE_KEY_LENGTH ||
      keys.has(candidate.key) ||
      typeof candidate.level !== 'number' ||
      !Number.isInteger(candidate.level) ||
      candidate.level < 1 ||
      candidate.level > 6 ||
      typeof candidate.heading !== 'string' ||
      candidate.heading.length > MAX_OUTLINE_HEADING_LENGTH
    ) {
      return false
    }
    keys.add(candidate.key)
    return true
  })
}

function validAssetIds(value: unknown): value is string[] {
  return (
    Array.isArray(value) &&
    value.length <= MAX_ASSET_IDS &&
    new Set(value).size === value.length &&
    value.every(
      (assetId) =>
        typeof assetId === 'string' &&
        assetId.trim().length > 0 &&
        assetId.length <= MAX_ASSET_ID_LENGTH,
    )
  )
}

export function articleEditorRecoveryKey(articleId: string): string {
  return `${STORAGE_PREFIX}${articleId}`
}

function validDraft(
  value: unknown,
  articleId: string,
  version: number,
  now: number,
): value is ArticleEditorRecoveryDraft {
  if (!value || typeof value !== 'object') return false
  const draft = value as Partial<ArticleEditorRecoveryDraft>
  return (
    draft.baseVersion === version &&
    typeof draft.title === 'string' &&
    draft.title.length <= MAX_TITLE_LENGTH &&
    typeof draft.bodyText === 'string' &&
    draft.bodyText.length <= MAX_BODY_LENGTH &&
    (draft.outline === undefined || validOutline(draft.outline)) &&
    (draft.assetIds === undefined || validAssetIds(draft.assetIds)) &&
    typeof draft.savedAt === 'number' &&
    Number.isFinite(draft.savedAt) &&
    draft.savedAt > 0 &&
    draft.savedAt <= now &&
    now - draft.savedAt <= MAX_AGE_MS &&
    // The id is encoded in the key. Keep this argument in the validator so
    // callers cannot accidentally reuse a draft parser for another resource.
    articleId.trim().length > 0
  )
}

export function readArticleEditorRecovery(
  articleId: string,
  version: number,
  now = Date.now(),
): ArticleEditorRecoveryDraft | undefined {
  const target = storage()
  if (!target || !articleId || !Number.isInteger(version) || version < 1) {
    return undefined
  }
  const key = articleEditorRecoveryKey(articleId)
  let raw: string | null
  try {
    raw = target.getItem(key)
  } catch {
    return undefined
  }
  if (!raw) return undefined
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!validDraft(parsed, articleId, version, now)) {
      removeStorageKey(target, key)
      return undefined
    }
    return parsed
  } catch {
    removeStorageKey(target, key)
    return undefined
  }
}

export function saveArticleEditorRecovery(
  articleId: string,
  draft: Omit<ArticleEditorRecoveryDraft, 'savedAt'>,
  now = Date.now(),
): void {
  const target = storage()
  if (!target || !articleId) return
  if (
    !Number.isInteger(draft.baseVersion) ||
    draft.baseVersion < 1 ||
    typeof draft.title !== 'string' ||
    draft.title.length > MAX_TITLE_LENGTH ||
    typeof draft.bodyText !== 'string' ||
    draft.bodyText.length > MAX_BODY_LENGTH ||
    (draft.outline !== undefined && !validOutline(draft.outline)) ||
    (draft.assetIds !== undefined && !validAssetIds(draft.assetIds))
  ) {
    return
  }
  try {
    target.setItem(
      articleEditorRecoveryKey(articleId),
      JSON.stringify({ ...draft, savedAt: now }),
    )
  } catch {
    // Browser storage is an optional recovery hint. The server remains the
    // authoritative source when storage is unavailable or full.
  }
}

export function clearArticleEditorRecovery(articleId: string): void {
  const target = storage()
  if (!target || !articleId) return
  removeStorageKey(target, articleEditorRecoveryKey(articleId))
}

import { isApiError } from '../../api/apiError'
import {
  createGeoArticleWorkflow,
  type GeoArticleWorkflow,
} from '../../api/geoApi'
import { abandonTaskDraft, getTaskDraft, type TaskDraft } from '../../api/conversationApi'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import {
  readArticleTaskRecovery,
  storeArticleTaskRecovery,
} from '../articles/live/articleRecovery'
import { reuseOrCreateInputAttempt } from '../task-draft'

export interface PreparedGeoArticleWorkflow {
  workflowId: string
  reportId: string
  suggestionId?: string | null
  articleId: string
  draftId: string
  conversationId: string
  reused: boolean
  draft: GeoArticleWorkflow['draft']
}

export class GeoArticleWorkflowConflictError extends Error {
  readonly existing?: Omit<PreparedGeoArticleWorkflow, 'draft'>

  constructor(
    message: string,
    existing?: Omit<PreparedGeoArticleWorkflow, 'draft'>,
  ) {
    super(message)
    this.name = 'GeoArticleWorkflowConflictError'
    this.existing = existing
  }
}

function detailId(
  details: Record<string, unknown>,
  field: string,
): string | undefined {
  const value = details[field]
  return typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value)
    ? value
    : undefined
}

function workflowFingerprint(
  reportId: string,
  suggestionId?: string,
  conversationId?: string,
  observationId?: string,
  direction?: string,
) {
  if (observationId || direction) return JSON.stringify(['geo-article-workflow-v2', reportId, suggestionId ?? '', conversationId ?? '', observationId ?? '', direction ?? ''])
  return [
    'geo-article-workflow-v1',
    reportId,
    suggestionId ?? '',
    conversationId ?? '',
  ].join(':')
}

export async function prepareGeoArticleWorkflow({
  ownerId,
  reportId,
  suggestionId,
  conversationId,
  observationId,
  direction,
  isCurrent,
}: {
  ownerId: string
  reportId: string
  suggestionId?: string
  conversationId?: string
  observationId?: string
  direction?: string
  isCurrent?: () => boolean
}): Promise<PreparedGeoArticleWorkflow> {
  const session = captureAuthSession()
  const active = () => isCurrentAuthSession(session) && (isCurrent?.() ?? true)
  if ((direction?.trim().length ?? 0) > 800) throw new Error('文章方向请控制在 800 字以内。')
  const fingerprint = workflowFingerprint(
    reportId,
    suggestionId,
    conversationId,
    observationId,
    direction?.trim() || undefined,
  )
  const recovery = readArticleTaskRecovery(ownerId)
  let previousAttempt =
    recovery.draftAttempt?.fingerprint === fingerprint
      ? recovery.draftAttempt
      : undefined
  if (previousAttempt?.draftId) {
    try {
      const draft = await getTaskDraft(previousAttempt.draftId)
      const reusable =
        draft.status === 'CONFIRMED' ||
        (draft.status === 'WAITING_CONFIRMATION' &&
          Date.parse(draft.expiresAt) > Date.now())
      if (!reusable) previousAttempt = undefined
    } catch (error) {
      if (!isApiError(error) || error.status !== 404) throw error
      previousAttempt = undefined
    }
  }
  if (!active()) throw new Error('本次资料或会话已切换，未继续准备文章。')
  const attempt = reuseOrCreateInputAttempt(
    previousAttempt,
    fingerprint,
  )
  storeArticleTaskRecovery(ownerId, {
    conversationId:
      recovery.draftAttempt?.fingerprint === fingerprint
        ? recovery.conversationId
        : undefined,
    draftAttempt: attempt,
  })
  const ownsRecovery = () => active()
    && readArticleTaskRecovery(ownerId).draftAttempt?.idempotencyKey === attempt.idempotencyKey

  try {
    const workflow = await createGeoArticleWorkflow(
      reportId,
      {
        suggestionId: suggestionId ?? null,
        conversationId: conversationId ?? null,
        ...(observationId ? { observationId } : {}),
        ...(direction?.trim() ? { direction: direction.trim() } : {}),
      },
      attempt.idempotencyKey,
    )
    if (ownsRecovery()) storeArticleTaskRecovery(ownerId, {
      conversationId: workflow.conversationId,
      draftAttempt: {
        ...attempt,
        draftId: workflow.draftId,
      },
    })
    return workflow
  } catch (error) {
    if (isApiError(error) && error.status === 409) {
      const workflowId = detailId(error.details, 'workflowId')
      const existingReportId = detailId(error.details, 'reportId')
      const existingConversationId = detailId(error.details, 'conversationId')
      const articleId = detailId(error.details, 'articleId')
      const draftId = detailId(error.details, 'draftId')
      if (
        workflowId &&
        existingConversationId &&
        articleId &&
        draftId
      ) {
        if (ownsRecovery()) storeArticleTaskRecovery(ownerId, {
          conversationId: existingConversationId,
          draftAttempt: {
            ...attempt,
            draftId,
          },
        })
        throw new GeoArticleWorkflowConflictError(error.message, {
          workflowId,
          reportId: existingReportId ?? reportId,
          suggestionId: null,
          conversationId: existingConversationId,
          articleId,
          draftId,
          reused: true,
        })
      }
    }
    throw error
  }
}

/** Abandon is idempotent. An unknown acknowledgement requires a fresh GET,
 * never an optimistic return to an old confirmation or creation of a new one. */
export async function abandonGeoArticleSourceDraft(draftId: string, isCurrent: () => boolean): Promise<TaskDraft> {
  if (!isCurrent()) throw new Error('本次资料或会话已切换，未继续准备文章。')
  let draft: TaskDraft
  try {
    draft = await abandonTaskDraft(draftId)
  } catch (error) {
    if (!isCurrent()) throw error
    draft = await getTaskDraft(draftId)
  }
  if (!isCurrent()) throw new Error('本次资料或会话已切换，未继续准备文章。')
  if (draft.id !== draftId || !['ABANDONED', 'EXPIRED'].includes(draft.status)) {
    throw new Error('尚未确认原方案已失效，请先核对原文章方案，未创建新方案。')
  }
  return draft
}

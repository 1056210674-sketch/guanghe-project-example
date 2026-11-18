import {
  createFormTaskDraftRecoveryStore,
} from '../../task-draft'
import type { FormTaskDraftRecoveryState } from '../../task-draft'

export type ArticleTaskRecoveryState = FormTaskDraftRecoveryState

const recoveryStore = createFormTaskDraftRecoveryStore({
  flow: 'article-creation',
})

export const readArticleTaskRecovery = recoveryStore.read
export const storeArticleTaskRecovery = recoveryStore.write
export const getArticleTaskRecoveryStorageKey = recoveryStore.keyForOwner

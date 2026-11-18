import { createFormTaskDraftRecoveryStore } from '../../task-draft'
import type { FormTaskDraftRecoveryState } from '../../task-draft'

export type MediaTaskRecoveryState = FormTaskDraftRecoveryState

const recoveryStore = createFormTaskDraftRecoveryStore({
  flow: 'media-submission',
})

export const readMediaTaskRecovery = recoveryStore.read
export const storeMediaTaskRecovery = recoveryStore.write
export const getMediaTaskRecoveryStorageKey = recoveryStore.keyForOwner

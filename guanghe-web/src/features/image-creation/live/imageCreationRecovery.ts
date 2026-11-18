import {
  createFormTaskDraftRecoveryStore,
} from '../../task-draft'
import type { FormTaskDraftRecoveryState } from '../../task-draft'

export type ImageCreationRecoveryState = FormTaskDraftRecoveryState

const recoveryStore = createFormTaskDraftRecoveryStore({
  flow: 'image-creation',
})

export const readImageCreationRecovery = recoveryStore.read
export const storeImageCreationRecovery = recoveryStore.write
export const getImageCreationRecoveryStorageKey = recoveryStore.keyForOwner

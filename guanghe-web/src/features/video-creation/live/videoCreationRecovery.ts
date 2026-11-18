import {
  createFormTaskDraftRecoveryStore,
} from '../../task-draft'
import type { FormTaskDraftRecoveryState } from '../../task-draft'

export type VideoCreationRecoveryState = FormTaskDraftRecoveryState

const recoveryStore = createFormTaskDraftRecoveryStore({
  flow: 'video-creation',
  legacyStoragePrefixes: ['zhixiaohe.video-creation.recovery.'],
})

export const readVideoCreationRecovery = recoveryStore.read
export const storeVideoCreationRecovery = recoveryStore.write
export const getVideoCreationRecoveryStorageKey = recoveryStore.keyForOwner

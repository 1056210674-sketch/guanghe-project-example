import { createFormTaskDraftRecoveryStore } from '../task-draft'
import type { FormTaskDraftRecoveryState } from '../task-draft'

export type GeoManualRecoveryState = FormTaskDraftRecoveryState

const manualRecoveryStore = createFormTaskDraftRecoveryStore({
  flow: 'geo-manual',
  legacyStoragePrefixes: ['zhixiaohe.geo-manual.recovery.'],
})

const quickRecoveryStore = createFormTaskDraftRecoveryStore({
  flow: 'geo-quick',
})

export const readGeoManualRecovery = manualRecoveryStore.read
export const storeGeoManualRecovery = manualRecoveryStore.write
export const getGeoManualRecoveryStorageKey = manualRecoveryStore.keyForOwner

export const readGeoQuickRecovery = quickRecoveryStore.read
export const storeGeoQuickRecovery = quickRecoveryStore.write
export const getGeoQuickRecoveryStorageKey = quickRecoveryStore.keyForOwner

import type {
  BusinessDataPermissions,
  BusinessViewError,
} from '../../features/business-data'
import { isCatalogPartialCreateError } from '../../features/business-data/catalogQueries'
import { toBusinessViewError } from './viewAdapters'

export const BUSINESS_DATA_PERMISSIONS: BusinessDataPermissions = {
  canCreate: true,
  canEdit: true,
  canDelete: true,
}

export interface CatalogCreateRecoveryState {
  partialCreateError: BusinessViewError
  pendingAliases: string[]
}

export interface CatalogCreateRecoveryResult {
  createdResourceId: string
  state: CatalogCreateRecoveryState
}

export function toCatalogCreateRecoveryState(
  error: unknown,
  entityLabel: '商品' | '竞品',
  pendingAliases: readonly string[],
): CatalogCreateRecoveryResult | null {
  if (!isCatalogPartialCreateError(error)) {
    return null
  }

  const operationError = toBusinessViewError(error.operationError)

  return {
    createdResourceId: error.createdResource.id,
    state: {
      pendingAliases: [...pendingAliases],
      partialCreateError: {
        message: `${entityLabel}已创建，但别名保存失败。请在此重试保存。${
          operationError ? ` ${operationError.message}` : ''
        }`,
        traceId: operationError?.traceId,
      },
    },
  }
}

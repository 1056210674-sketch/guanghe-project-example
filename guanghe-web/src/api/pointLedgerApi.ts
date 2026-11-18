import type { components, operations } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiRequest } from './client'

export type PointLedgerEntryDto = components['schemas']['PointLedgerEntry']
export type PointLedgerPageDto = components['schemas']['PointLedgerPage']
export type PointLedgerListQuery = NonNullable<operations['listPointLedger']['parameters']['query']>

type PointLedgerPageApiResponse = components['schemas']['PointLedgerPageApiResponse']

function listPath(query: PointLedgerListQuery): string {
  const search = new URLSearchParams()
  if (query.entryType) search.set('entryType', query.entryType)
  if (query.businessType) search.set('businessType', query.businessType)
  if (query.ledgerId) search.set('ledgerId', query.ledgerId)
  if (query.page !== undefined) search.set('page', String(query.page))
  if (query.size !== undefined) search.set('size', String(query.size))
  const suffix = search.toString()
  return suffix ? `/api/v1/billing/point-ledger?${suffix}` : '/api/v1/billing/point-ledger'
}

export async function listPointLedger(query: PointLedgerListQuery = {}): Promise<PointLedgerPageDto> {
  const response = await apiRequest<PointLedgerPageApiResponse>(listPath(query), {
    method: 'GET',
    auth: 'required',
  })
  if (!response.data) {
    throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '积分明细返回了无法识别的数据。' })
  }
  return response.data
}

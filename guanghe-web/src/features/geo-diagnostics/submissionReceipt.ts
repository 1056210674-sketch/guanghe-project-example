import type { GeoDiagnostic, GeoDiagnosticSubmit } from '../../api/geoDiagnosticApi'

/** Only request identity and the displayed quote are stored; no token or customer text. */
export interface DiagnosticSubmissionReceipt {
  diagnosticId: string; projectId: string; conversationId: string; request: GeoDiagnosticSubmit
}
const key = (owner: string, record: Pick<GeoDiagnostic, 'id' | 'projectId' | 'conversationId'>) => `zxh.diagnostic-submit.${owner}.${record.projectId}.${record.conversationId}.${record.id}`
export function readDiagnosticSubmission(owner: string, record: GeoDiagnostic): DiagnosticSubmissionReceipt | undefined {
  try {
    const receipt = JSON.parse(sessionStorage.getItem(key(owner, record)) ?? 'null') as Partial<DiagnosticSubmissionReceipt> | null
    if (receipt?.diagnosticId === record.id && receipt.projectId === record.projectId && receipt.conversationId === record.conversationId
      && typeof receipt.request?.version === 'number' && Number.isSafeInteger(receipt.request.version)
      && /^[0-9a-f]{64}$/i.test(receipt.request.inputHash) && /^[0-9a-f-]{36}$/i.test(receipt.request.requestId)
      && typeof receipt.request.quoteId === 'string') return receipt as DiagnosticSubmissionReceipt
  } catch { /* A missing receipt never manufactures a submission. */ }
  return undefined
}
export function saveDiagnosticSubmission(owner: string, record: GeoDiagnostic, request: GeoDiagnosticSubmit) {
  const receipt: DiagnosticSubmissionReceipt = { diagnosticId: record.id, projectId: record.projectId, conversationId: record.conversationId, request }
  try { sessionStorage.setItem(key(owner, record), JSON.stringify(receipt)) } catch { /* Caller retains the receipt in memory. */ }
  return receipt
}
export function clearDiagnosticSubmission(owner: string, record: GeoDiagnostic) {
  try { sessionStorage.removeItem(key(owner, record)) } catch { /* A reconciled Java task remains the authority. */ }
}

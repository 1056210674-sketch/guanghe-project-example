import type { MonitorExecutionIntent } from '../../api/monitorExecutionApi'
import { readMessageAttempt } from '../chat/live/messageAttempt'

interface Recovery { conversationId: string; intent: MonitorExecutionIntent; authRevision: string | null }
const prefix = 'zhixiaohe.monitor.intent.v1.'
const memory = new Map<string, Recovery>()
const failedWrites = new Set<string>()
const id = (value: unknown): value is string => typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value)
const positive = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0 && Number(value) <= 2147483647
const object = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)

// Only selection IDs, hash/version/price and region are retained. Never retain chat text,
// attachments, credentials or identity grants. Java revalidates the stored intent on retry.
function parse(value: unknown): Recovery | undefined {
  if (!object(value) || !id(value.conversationId) || !object(value.intent)) return
  const i = value.intent; const r = i.range
  if (i.action !== 'MONITOR_RUN' || !id(i.grantId) || !positive(i.grantVersion) || !id(i.pricingVersionId) || !positive(i.quotedPoints) || !object(r)) return
  const versions = r.inputVersions
  if (!id(r.topicId) || !positive(r.topicVersion) || !Array.isArray(r.questionIds) || r.questionIds.length < 3 || r.questionIds.length > 10 || !r.questionIds.every(id)
    || new Set(r.questionIds).size !== r.questionIds.length
    || !Array.isArray(r.platformCodes) || r.platformCodes.length < 1 || r.platformCodes.length > 7 || new Set(r.platformCodes).size !== r.platformCodes.length || !r.platformCodes.every((code) => ['DOUBAO', 'DEEPSEEK', 'QWEN', 'WENXIN', 'KIMI', 'YUANBAO', 'ZHIPU'].includes(String(code)))
    || !['STANDARD', 'DEEP'].includes(String(r.sampleMode)) || typeof r.region !== 'string' || !r.region.trim() || r.region.length > 100
    || typeof r.inputHash !== 'string' || !/^[a-f0-9]{64}$/.test(r.inputHash) || !object(versions) || !positive(versions.spaceVersion)
    || !(versions.productVersion === null || positive(versions.productVersion)) || !(versions.competitorSetVersion === null || positive(versions.competitorSetVersion))
    || !Array.isArray(versions.competitorVersions) || versions.competitorVersions.length > 20
    || !versions.competitorVersions.every((item) => object(item) && id(item.competitorId) && positive(item.version))) return
  // Whitelist construction also strips any locally injected identity/capability fields.
  return { conversationId: value.conversationId, authRevision: typeof value.authRevision === 'string' ? value.authRevision : null, intent: {
    action: 'MONITOR_RUN', grantId: i.grantId, grantVersion: i.grantVersion, pricingVersionId: i.pricingVersionId, quotedPoints: i.quotedPoints,
    range: { topicId: r.topicId, topicVersion: r.topicVersion, region: r.region, inputHash: r.inputHash,
      questionIds: r.questionIds, platformCodes: r.platformCodes as MonitorExecutionIntent['range']['platformCodes'], sampleMode: r.sampleMode as 'STANDARD' | 'DEEP',
      inputVersions: { spaceVersion: versions.spaceVersion, productVersion: versions.productVersion, competitorSetVersion: versions.competitorSetVersion,
        competitorVersions: versions.competitorVersions.map((item) => ({ competitorId: String(item.competitorId), version: Number(item.version) })) } },
  } }
}
export function readMonitorIntent(owner: string, scope: string, authRevision: string | null): Recovery | undefined {
  const key = `${prefix}${owner}.${scope}`
  let value = memory.get(key)
  if (!failedWrites.has(key)) try {
    const raw = sessionStorage.getItem(key)
    value = raw && raw.length < 16384 ? parse(JSON.parse(raw)) : undefined
  } catch { /* Same-page fallback only. */ }
  return value && (!id(scope) || value.conversationId === scope) && authRevision !== null
    && value.authRevision === authRevision && readMessageAttempt(owner, value.conversationId) ? value : undefined
}
export function storeMonitorIntent(owner: string, scope: string, input: Recovery): Recovery {
  const value = parse(input)
  if (!value) throw new Error('本次监测参数无效，请重新核对。')
  const key = `${prefix}${owner}.${scope}`
  memory.set(key, value)
  try { sessionStorage.setItem(key, JSON.stringify(value)); failedWrites.delete(key) } catch { failedWrites.add(key) }
  return value
}
export function clearMonitorIntent(owner: string, scope: string) {
  const key = `${prefix}${owner}.${scope}`
  memory.delete(key); failedWrites.delete(key)
  try { sessionStorage.removeItem(key) } catch { /* No authority is retained in this cache. */ }
}

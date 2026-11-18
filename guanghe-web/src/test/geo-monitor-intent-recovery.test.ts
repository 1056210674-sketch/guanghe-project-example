import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MonitorExecutionIntent } from '../api/monitorExecutionApi'
import { clearMonitorIntent, readMonitorIntent, storeMonitorIntent } from '../features/geo/monitorIntentRecovery'
import { clearMessageAttempt, messageFingerprint, readMessageAttempt, storeMessageAttempt } from '../features/chat/live/messageAttempt'

const intent: MonitorExecutionIntent = { action: 'MONITOR_RUN', grantId: '501', grantVersion: 1,
  range: { topicId: '101', topicVersion: 2, questionIds: ['301', '302', '303'], platformCodes: ['DOUBAO'], sampleMode: 'STANDARD', region: '广州', inputHash: 'a'.repeat(64), inputVersions: { spaceVersion: 1, productVersion: null, competitorSetVersion: null, competitorVersions: [] } }, pricingVersionId: '701', quotedPoints: 3 }
const request = (value: MonitorExecutionIntent) => ({ content: '执行本次已选专题的单次监测。', executionIntent: value, contextSelectionMode: 'INHERIT' as const, attachments: [] })
const attempt = { clientMessageId: 'original-message', idempotencyKey: 'original-key', fingerprint: 'original-fingerprint' }
const key = 'zhixiaohe.monitor.intent.v1.1.801'
beforeEach(() => { sessionStorage.clear(); vi.stubGlobal('crypto', webcrypto) })
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); clearMonitorIntent('1', '801'); clearMessageAttempt('1', '801'); sessionStorage.clear() })

describe('显式监测意图恢复不授予权限', () => {
  it('刷新恢复 typed intent 原序列化指纹，沿用同一原消息 UUID/key，不含正文或令牌', async () => {
    const normalized = storeMonitorIntent('1', '801', { conversationId: '801', intent, authRevision: 'epoch-a' })
    const fingerprint = await messageFingerprint(request(normalized.intent))
    storeMessageAttempt('1', '801', { ...attempt, fingerprint })
    const restored = readMonitorIntent('1', '801', 'epoch-a')!
    expect(await messageFingerprint(request(restored.intent))).toBe(fingerprint)
    expect(readMessageAttempt('1', '801')).toEqual({ ...attempt, fingerprint })
    const stored = sessionStorage.getItem(key)!
    expect(stored).not.toContain('执行本次'); expect(stored).not.toContain('accessToken'); expect(stored).not.toContain('installationId')
  })
  it('没有原消息 attempt 不提供待重试动作，不自动发送任何请求', () => {
    storeMonitorIntent('1', '801', { conversationId: '801', intent, authRevision: 'epoch-a' })
    expect(readMonitorIntent('1', '801', 'epoch-a')).toBeUndefined()
  })
  it('owner/conversation/登录 revision 任一个改变都不带走旧授权意图', () => {
    storeMessageAttempt('1', '801', attempt)
    storeMonitorIntent('1', '801', { conversationId: '801', intent, authRevision: 'epoch-a' })
    expect(readMonitorIntent('2', '801', 'epoch-a')).toBeUndefined()
    expect(readMonitorIntent('1', '802', 'epoch-a')).toBeUndefined()
    expect(readMonitorIntent('1', '801', 'epoch-b')).toBeUndefined()
    expect(readMonitorIntent('1', '801', null)).toBeUndefined()
  })
  it('原 USER 动作完成清除后不再恢复，普通新消息拥有不同指纹', async () => {
    storeMessageAttempt('1', '801', attempt)
    const value = storeMonitorIntent('1', '801', { conversationId: '801', intent, authRevision: 'epoch-a' })
    expect(await messageFingerprint(request(value.intent))).not.toBe(await messageFingerprint({ content: '换个话题，帮我翻译', contextSelectionMode: 'INHERIT', attachments: [] }))
    clearMessageAttempt('1', '801')
    expect(readMonitorIntent('1', '801', 'epoch-a')).toBeUndefined()
  })
  it('存储中注入安装身份/权限字段不会进入恢复 payload，Java仍决定是否准入', () => {
    storeMessageAttempt('1', '801', attempt)
    sessionStorage.setItem(key, JSON.stringify({ conversationId: '801', authRevision: 'epoch-a', intent: { ...intent, userId: '2', capabilities: ['everything'], range: { ...intent.range, installationId: '999' } } }))
    const restored = readMonitorIntent('1', '801', 'epoch-a')!
    expect(restored.intent).toEqual(intent)
    expect(JSON.stringify(restored)).not.toContain('capabilities'); expect(JSON.stringify(restored)).not.toContain('installationId')
  })
  it.each(['{broken', 'x'.repeat(16385), JSON.stringify({ conversationId: '801', intent: { ...intent, quotedPoints: -1 } })])('畸形或超限缓存 fail closed', (raw) => {
    storeMessageAttempt('1', '801', attempt); sessionStorage.setItem(key, raw)
    expect(readMonitorIntent('1', '801', 'epoch-a')).toBeUndefined()
  })
  it.each([
    { ...intent.range, region: '城'.repeat(101) },
    { ...intent.range, questionIds: ['301', '301', '303'] },
    { ...intent.range, platformCodes: ['DOUBAO', 'DOUBAO'] },
    { ...intent.range, inputVersions: { ...intent.range.inputVersions, competitorVersions: Array.from({ length: 21 }, (_, index) => ({ competitorId: String(index + 1), version: 1 })) } },
  ])('恢复参数按 canonical 上限和唯一性校验', (range) => {
    storeMessageAttempt('1', '801', attempt)
    sessionStorage.setItem(key, JSON.stringify({ conversationId: '801', authRevision: 'epoch-a', intent: { ...intent, range } }))
    expect(readMonitorIntent('1', '801', 'epoch-a')).toBeUndefined()
  })
  it('存储拒绝写入时仅同页可恢复，依然要求原消息 attempt 和同 auth revision', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('Full', 'QuotaExceededError') })
    storeMessageAttempt('1', '801', attempt)
    storeMonitorIntent('1', '801', { conversationId: '801', intent, authRevision: 'epoch-a' })
    expect(readMonitorIntent('1', '801', 'epoch-a')?.intent).toEqual(intent)
    expect(readMonitorIntent('1', '801', 'epoch-b')).toBeUndefined()
  })
})

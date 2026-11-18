import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ComponentProps } from 'react'
import type { MonitorExecutionIntent } from '../api/monitorExecutionApi'
import { GeoMonitorAction } from '../pages/chat/GeoMonitorAction'
import { clearMessageAttempt, messageFingerprint, readMessageAttempt, storeMessageAttempt } from '../features/chat/live/messageAttempt'
import { clearMonitorIntent, readMonitorIntent, storeMonitorIntent } from '../features/geo/monitorIntentRecovery'

const api = vi.hoisted(() => ({ budget: vi.fn(), put: vi.fn(), grants: vi.fn(), preview: vi.fn(), create: vi.fn(), update: vi.fn(), revoke: vi.fn(), admission: vi.fn(), topics: vi.fn(), topic: vi.fn(), platforms: vi.fn(), target: vi.fn(), current: true }))
vi.mock('../api/agentPlatformApi', () => ({ getProjectGeoTarget: api.target }))
vi.mock('../api/monitorExecutionApi', () => ({ getExecutionBudget: api.budget, putExecutionBudget: api.put, listMonitorAuthorizations: api.grants, previewMonitorAction: api.preview, createMonitorAuthorization: api.create, updateMonitorAuthorization: api.update, revokeMonitorAuthorization: api.revoke, getMonitorAdmission: api.admission }))
vi.mock('../api/geoApi', () => ({ listGeoTopics: api.topics, getGeoTopic: api.topic, listGeoPlatforms: api.platforms }))
vi.mock('../auth/tokenStorage', () => ({ captureAuthSession: () => ({ generation: 1, revision: 'auth-1' }), isCurrentAuthSession: () => api.current }))

const runId = '5c3fe539-f733-460f-a275-f41238464cf7'
const intent: MonitorExecutionIntent = { action: 'MONITOR_RUN', grantId: '501', grantVersion: 1, pricingVersionId: '701', quotedPoints: 3,
  range: { topicId: '101', topicVersion: 1, questionIds: ['301', '302', '303'], platformCodes: ['DOUBAO'], sampleMode: 'STANDARD', region: '广州', inputHash: 'a'.repeat(64), inputVersions: { spaceVersion: 1, productVersion: null, competitorSetVersion: null, competitorVersions: [] } } }

beforeEach(() => {
  vi.clearAllMocks(); sessionStorage.clear(); api.current = true
  clearMessageAttempt('1', '801'); clearMonitorIntent('1', '801')
  api.admission.mockResolvedValue({ runId, status: 'ADMITTED', reasonCode: null, draftId: '601', taskId: '1001', executionSource: 'EXECUTION_PREAUTH', quotedPoints: 3, admittedAt: '2026-10-14T10:00:00Z' })
})

function setup(overrides: Partial<ComponentProps<typeof GeoMonitorAction>> = {}) {
  const props = { ownerId: '1', recoveryScope: '801', conversationId: '801', resultOnly: true, disabled: false,
    onEnsureConversation: vi.fn().mockResolvedValue('801'), onSend: vi.fn().mockResolvedValue({ runId, messageId: '901', status: 'QUEUED' }), onViewTask: vi.fn(), ...overrides }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  render(<QueryClientProvider client={client}><GeoMonitorAction {...props} /></QueryClientProvider>)
  return props
}

function expectNoNewMonitorPreparation() {
  for (const action of [api.budget, api.put, api.grants, api.preview, api.create, api.update, api.revoke, api.topics, api.topic, api.platforms, api.target]) {
    expect(action).not.toHaveBeenCalled()
  }
}

describe('监测后台恢复组件 resultOnly 模式', () => {
  it('默认兼容旧调用方，显式 resultOnly 不渲染新建监测入口，也不读取设置', () => {
    const props = setup()
    expect(screen.queryByRole('button', { name: '监测一次' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重试同一次请求' })).not.toBeInTheDocument()
    expectNoNewMonitorPreparation()
    expect(api.admission).not.toHaveBeenCalled()
    expect(props.onSend).not.toHaveBeenCalled()
    expect(props.onEnsureConversation).not.toHaveBeenCalled()
  })

  it('未传 resultOnly 的旧界面仍有监测一次入口', () => {
    setup({ resultOnly: undefined })
    expect(screen.getByRole('button', { name: '监测一次' })).toBeEnabled()
    expectNoNewMonitorPreparation()
  })

  it('已受理的后台任务继续读取和手动刷新状态，不创建授权或发送新请求', async () => {
    const onAdmission = vi.fn()
    const props = setup({ restoredRunId: runId, onAdmission })
    await screen.findByText('监测已受理')
    await waitFor(() => expect(onAdmission).toHaveBeenCalledWith('801', expect.objectContaining({ runId, taskId: '1001' })))
    expect(screen.queryByRole('button', { name: '监测一次' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /刷新受理状态/ }))
    await waitFor(() => expect(api.admission).toHaveBeenCalledTimes(2))
    expect(api.admission).toHaveBeenCalledWith('801', runId, { generation: 1, revision: 'auth-1' })
    expectNoNewMonitorPreparation()
    expect(props.onSend).not.toHaveBeenCalled()
  })

  it('保留待确认原请求的显式恢复入口，双击只重放一次相同意图，不另开额度或授权', async () => {
    const persisted = storeMonitorIntent('1', '801', { conversationId: '801', authRevision: 'auth-1', intent }).intent
    const fingerprint = await messageFingerprint({ content: '执行本次已选专题的单次监测。', executionIntent: persisted, contextSelectionMode: 'INHERIT', attachments: [] })
    const attempt = { clientMessageId: 'original-message', idempotencyKey: 'original-key', fingerprint }
    storeMessageAttempt('1', '801', attempt)
    const props = setup()
    expect(props.onSend).not.toHaveBeenCalled()
    const retry = screen.getByRole('button', { name: '重试同一次请求' })
    fireEvent.click(retry); fireEvent.click(retry)
    await waitFor(() => expect(props.onSend).toHaveBeenCalledTimes(1))
    expect(props.onSend).toHaveBeenCalledWith(intent, '801')
    await waitFor(() => expect(screen.queryByRole('button', { name: '重试同一次请求' })).not.toBeInTheDocument())
    expect(readMessageAttempt('1', '801')).toEqual(attempt)
    expect(readMonitorIntent('1', '801', 'auth-1')).toBeUndefined()
    expectNoNewMonitorPreparation()
    expect(props.onEnsureConversation).not.toHaveBeenCalled()
  })
})

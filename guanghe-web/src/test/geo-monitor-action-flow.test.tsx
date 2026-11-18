import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ComponentProps } from 'react'
import type { GeoMonitorActionPanel } from '../features/geo/components/GeoMonitorActionPanel'
import type { BudgetPolicy, FrozenMonitorRange, MonitorAdmission, MonitorAuthorization } from '../api/monitorExecutionApi'
import { GeoMonitorAction } from '../pages/chat/GeoMonitorAction'
import { ApiError } from '../api/apiError'
import { monitorAdmissionView } from '../features/geo/monitorAdmissionView'
import { monitorAdmissionInterval } from '../features/geo/monitorAdmissionQuery'
import { clearMessageAttempt, messageFingerprint, storeMessageAttempt } from '../features/chat/live/messageAttempt'
import type { MonitorExecutionIntent } from '../api/monitorExecutionApi'

const api = vi.hoisted(() => ({ budget: vi.fn(), put: vi.fn(), grants: vi.fn(), preview: vi.fn(), create: vi.fn(), update: vi.fn(), revoke: vi.fn(), admission: vi.fn(), topics: vi.fn(), topic: vi.fn(), platforms: vi.fn(), target: vi.fn(), current: true }))
vi.mock('../api/agentPlatformApi', () => ({ getProjectGeoTarget: api.target }))
vi.mock('../api/monitorExecutionApi', () => ({ getExecutionBudget: api.budget, putExecutionBudget: api.put, listMonitorAuthorizations: api.grants, previewMonitorAction: api.preview, createMonitorAuthorization: api.create, updateMonitorAuthorization: api.update, revokeMonitorAuthorization: api.revoke, getMonitorAdmission: api.admission }))
vi.mock('../api/geoApi', () => ({ listGeoTopics: api.topics, getGeoTopic: api.topic, listGeoPlatforms: api.platforms }))
vi.mock('../auth/tokenStorage', () => ({ captureAuthSession: () => ({ generation: 1, revision: 'auth-1' }), isCurrentAuthSession: () => api.current }))
vi.mock('../features/geo/components/GeoMonitorActionPanel', () => ({ GeoMonitorActionPanel: (p: ComponentProps<typeof GeoMonitorActionPanel>) => p.open ? <section>
  {p.topic ? <p>已加载专题地区：{p.topic.region}</p> : null}
  <button onClick={() => p.onChange({ topicId: '101', questionIds: ['301', '302', '303'], platformCodes: ['DOUBAO'], sampleMode: 'STANDARD', actionPoints: '10', dayPoints: '30', expiresLocal: '2099-10-08T18:00' })}>填写范围和额度</button>
  <button onClick={() => p.onChange({ ...p.editor, platformCodes: ['DEEPSEEK'] })}>更改范围</button>
  <button disabled={p.busy} onClick={p.onPreview}>核对费用</button>
  <button disabled={p.busy || !p.canStart} onClick={p.onStart}>确认授权开始</button>
  <button disabled={p.busy || !p.canRevoke} onClick={p.onRevoke}>撤销</button>
  {p.onRetryPending ? <button disabled={p.busy} onClick={p.onRetryPending}>重试同一次请求</button> : null}
  {p.error ? <p>{p.error}</p> : null}{p.notice ? <p>{p.notice}</p> : null}
  {p.admission ? <p>{p.admission.description}</p> : null}
</section> : null }))

const range: FrozenMonitorRange = { topicId: '101', topicVersion: 1, questionIds: ['301', '302', '303'], platformCodes: ['DOUBAO'], sampleMode: 'STANDARD', region: '广州', inputHash: 'a'.repeat(64), inputVersions: { spaceVersion: 1, productVersion: null, competitorSetVersion: null, competitorVersions: [] } }
const policy: BudgetPolicy = { configured: false, enabled: false, version: 0, maxPointsPerAction: null, maxPointsPerDay: null, timezone: 'Asia/Shanghai', earliestTrustedShanghaiDate: '2026-10-07', effectiveShanghaiDate: null }
const grant: MonitorAuthorization = { id: '501', action: 'MONITOR_RUN', version: 1, status: 'ACTIVE', range, expiresAt: '2099-10-08T10:00:00Z', revokedAt: null, createdAt: '2026-10-07T01:00:00Z' }
const runId = '5c3fe539-f733-460f-a275-f41238464cf7'
const pending: MonitorAdmission = { runId, status: 'PENDING', reasonCode: null, draftId: '601', taskId: null, executionSource: null, quotedPoints: 3, admittedAt: null }

beforeEach(() => {
  vi.clearAllMocks(); sessionStorage.clear(); api.current = true
  api.budget.mockResolvedValue(policy); api.put.mockResolvedValue({ ...policy, configured: true, enabled: true, version: 1, maxPointsPerAction: 10, maxPointsPerDay: 30, effectiveShanghaiDate: '2026-10-07' })
  api.grants.mockResolvedValue([]); api.create.mockResolvedValue(grant); api.update.mockResolvedValue(grant); api.revoke.mockResolvedValue({ ...grant, status: 'REVOKED' })
  api.preview.mockResolvedValue({ action: 'MONITOR_RUN', status: 'READY', reasonCode: null, range, pricingVersionId: '701', quotedPoints: 3, unitPoints: 1, expectedUnits: 3 })
  api.admission.mockResolvedValue(pending)
  api.topics.mockResolvedValue({ items: [{ id: '101', spaceId: '201', name: '品牌发现' }], total: 1, page: 1, size: 20, hasNext: false })
  api.topic.mockResolvedValue({ id: '101', region: '广州', questions: [] })
  api.platforms.mockResolvedValue([{ code: 'DOUBAO', selectable: true }])
  api.target.mockResolvedValue({ projectId: '201', version: 1, name: '小行星咖啡店', aliases: [], configured: true, updatedAt: '2026-10-07T01:00:00Z' })
})

function setup(overrides: Partial<ComponentProps<typeof GeoMonitorAction>> = {}) {
  const props = { ownerId: '1', recoveryScope: '801', conversationId: '801', disabled: false, onEnsureConversation: vi.fn().mockResolvedValue('801'), onSend: vi.fn().mockResolvedValue({ runId, messageId: '901', status: 'QUEUED' }), onViewTask: vi.fn(), ...overrides }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  const view = render(<QueryClientProvider client={client}><GeoMonitorAction {...props} /></QueryClientProvider>)
  return { ...view, props, client }
}
async function prepare() {
  fireEvent.click(screen.getByRole('button', { name: '监测一次' }))
  await waitFor(() => expect(api.budget).toHaveBeenCalledTimes(1))
  fireEvent.click(screen.getByRole('button', { name: '填写范围和额度' }))
  await screen.findByText('已加载专题地区：广州')
  await waitFor(() => expect(screen.getByRole('button', { name: '核对费用' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '核对费用' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '确认授权开始' })).toBeEnabled())
}

describe('单次监测真实接口编排（stub 只证明协议，不证明线上执行）', () => {
  it('未展开不读取设置；范围/报价不授权，明确开始才写限额与授权，并只发送 canonical intent', async () => {
    const { props } = setup({ projectId: '201' })
    expect(api.budget).not.toHaveBeenCalled(); expect(api.topics).not.toHaveBeenCalled()
    await prepare()
    expect(api.put).not.toHaveBeenCalled(); expect(api.create).not.toHaveBeenCalled(); expect(props.onSend).not.toHaveBeenCalled()
    expect(api.topics).toHaveBeenCalledWith({ spaceId: '201', page: 1, size: 20, keyword: undefined })
    expect(api.target).toHaveBeenCalledWith('201', expect.anything())
    fireEvent.click(screen.getByRole('button', { name: '确认授权开始' }))
    await waitFor(() => expect(props.onSend).toHaveBeenCalledTimes(1))
    expect(api.put).toHaveBeenCalledWith({ version: 0, enabled: true, maxPointsPerAction: 10, maxPointsPerDay: 30 }, expect.anything())
    expect(api.create).toHaveBeenCalledWith('801', { expectedVersion: 0, range, expiresAt: '2099-10-08T10:00:00.000Z' }, expect.anything())
    expect(props.onSend).toHaveBeenCalledWith({ action: 'MONITOR_RUN', grantId: '501', grantVersion: 1, range, pricingVersionId: '701', quotedPoints: 3 }, '801')
    await screen.findByText('正在准备本次监测')
    expect(screen.queryByText('监测已受理')).not.toBeInTheDocument(); expect(screen.queryByText('监测已完成')).not.toBeInTheDocument()
  })
  it('新会话只有核对费用才创建，准备阶段不发送聊天或虚构项目', async () => {
    const { props } = setup({ conversationId: undefined, recoveryScope: 'new:20:' })
    await prepare()
    expect(props.onEnsureConversation).toHaveBeenCalledTimes(1)
    expect(props.onSend).not.toHaveBeenCalled(); expect(api.preview).toHaveBeenCalledWith('801', { topicId: '101', questionIds: ['301', '302', '303'], platformCodes: ['DOUBAO'], sampleMode: 'STANDARD' }, expect.anything())
  })
  it('已有商品名称的专题无需补品牌监测名称，不改变旧商品范围或添加配置前置', async () => {
    api.topic.mockResolvedValue({ id: '101', region: '广州', productId: '202', questions: [] })
    api.target.mockResolvedValue({ projectId: '201', version: 1, name: null, aliases: [], configured: false, updatedAt: '2026-10-07T01:00:00Z' })
    const onConfigureTarget = vi.fn()
    setup({ projectId: '201', onConfigureTarget })
    await prepare()
    expect(api.target).not.toHaveBeenCalled(); expect(onConfigureTarget).not.toHaveBeenCalled()
    expect(api.preview).toHaveBeenCalledTimes(1)
  })
  it('项目没有公开监测对象时，在创建会话或报价前引导项目资料，不授权、不付费、不猜项目名', async () => {
    api.target.mockResolvedValue({ projectId: '201', version: 1, name: null, aliases: [], configured: false, updatedAt: '2026-10-07T01:00:00Z' })
    const onConfigureTarget = vi.fn()
    const { props } = setup({ projectId: '201', conversationId: undefined, onConfigureTarget })
    fireEvent.click(screen.getByRole('button', { name: '监测一次' }))
    await screen.findByRole('button', { name: '填写范围和额度' })
    fireEvent.click(screen.getByRole('button', { name: '填写范围和额度' }))
    await screen.findByText('已加载专题地区：广州')
    fireEvent.click(screen.getByRole('button', { name: '核对费用' }))
    await waitFor(() => expect(onConfigureTarget).toHaveBeenCalledTimes(1))
    expect(screen.getByText(/请先在项目资料中设置公开监测名称/)).toBeInTheDocument()
    expect(props.onEnsureConversation).not.toHaveBeenCalled()
    expect(api.preview).not.toHaveBeenCalled(); expect(api.put).not.toHaveBeenCalled(); expect(api.create).not.toHaveBeenCalled(); expect(props.onSend).not.toHaveBeenCalled()
  })
  it('监测对象读取失败不继续报价或偷偷生成名称，重试由用户决定', async () => {
    api.target.mockRejectedValue(new ApiError({ status: 503, code: 'UNAVAILABLE', message: '监测对象暂不可读取' }))
    const { props } = setup({ projectId: '201', conversationId: undefined })
    fireEvent.click(screen.getByRole('button', { name: '监测一次' }))
    await screen.findByRole('button', { name: '填写范围和额度' })
    fireEvent.click(screen.getByRole('button', { name: '填写范围和额度' }))
    await screen.findByText('已加载专题地区：广州')
    fireEvent.click(screen.getByRole('button', { name: '核对费用' }))
    await screen.findByText('监测对象暂不可读取')
    expect(props.onEnsureConversation).not.toHaveBeenCalled(); expect(api.preview).not.toHaveBeenCalled(); expect(props.onSend).not.toHaveBeenCalled()
  })
  it('改变范围使旧报价失效，不能沿用旧授权开始', async () => {
    setup(); await prepare()
    fireEvent.click(screen.getByRole('button', { name: '更改范围' }))
    expect(screen.getByRole('button', { name: '确认授权开始' })).toBeDisabled()
    expect(api.create).not.toHaveBeenCalled()
  })
  it('额度被另一个窗口更新时不覆盖，也不创建授权/任务', async () => {
    const { props } = setup(); await prepare(); api.budget.mockResolvedValue({ ...policy, version: 2 })
    fireEvent.click(screen.getByRole('button', { name: '确认授权开始' }))
    await screen.findByText('执行额度已在别处更新，请重新加载后核对。')
    expect(api.put).not.toHaveBeenCalled(); expect(api.create).not.toHaveBeenCalled(); expect(props.onSend).not.toHaveBeenCalled()
  })
  it.each([
    { earliestTrustedShanghaiDate: '2099-10-08', expected: '自动监测将在 2099-10-08 开放，可先核对范围和费用。' },
    { earliestTrustedShanghaiDate: null, expected: '自动监测尚未开放，可先核对范围和费用。' },
  ])('可信结算日未到/未配置时能核对费用，但不能保存授权或发送 Run', async ({ earliestTrustedShanghaiDate, expected }) => {
    api.budget.mockResolvedValue({ ...policy, earliestTrustedShanghaiDate })
    const { props } = setup()
    fireEvent.click(screen.getByRole('button', { name: '监测一次' })); await screen.findByText(expected)
    fireEvent.click(screen.getByRole('button', { name: '填写范围和额度' })); await screen.findByText('已加载专题地区：广州')
    fireEvent.click(screen.getByRole('button', { name: '核对费用' })); await waitFor(() => expect(api.preview).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('button', { name: '确认授权开始' })).toBeDisabled()
    expect(api.put).not.toHaveBeenCalled(); expect(api.create).not.toHaveBeenCalled(); expect(props.onSend).not.toHaveBeenCalled()
  })
  it('过期 ACTIVE 授权按原版本更新，不能另建绕过唯一范围约束', async () => {
    api.grants.mockResolvedValue([{ ...grant, expiresAt: '2000-01-01T01:00:00Z' }])
    const { props } = setup(); await prepare()
    fireEvent.click(screen.getByRole('button', { name: '确认授权开始' }))
    await waitFor(() => expect(props.onSend).toHaveBeenCalledTimes(1))
    expect(api.create).not.toHaveBeenCalled()
    expect(api.update).toHaveBeenCalledWith('801', '501', { version: 1, range, expiresAt: '2099-10-08T10:00:00.000Z' }, expect.anything())
  })
  it('预览后授权被撤销时不修改额度，不偷偷重新授权', async () => {
    api.grants.mockResolvedValue([grant]); const { props } = setup(); await prepare()
    api.grants.mockResolvedValue([])
    fireEvent.click(screen.getByRole('button', { name: '确认授权开始' }))
    await screen.findByText('本次授权已在别处更新，请重新核对。')
    expect(api.put).not.toHaveBeenCalled(); expect(api.create).not.toHaveBeenCalled(); expect(props.onSend).not.toHaveBeenCalled()
  })
  it('显式撤销只调用原授权版本，不停止或取消已受理任务', async () => {
    api.grants.mockResolvedValue([grant]); const { props } = setup(); await prepare()
    fireEvent.click(screen.getByRole('button', { name: '撤销' }))
    await screen.findByText('授权已撤销。已经受理的任务不受影响。')
    expect(api.revoke).toHaveBeenCalledWith('801', '501', { version: 1 }, expect.anything())
    expect(props.onSend).not.toHaveBeenCalled(); expect(api.put).not.toHaveBeenCalled()
  })
  it('服务器价格或输入变化返回 WAIT_INPUT，不把 HTTP 成功当可执行', async () => {
    api.preview.mockResolvedValue({ action: 'MONITOR_RUN', status: 'WAIT_INPUT', reasonCode: 'PRICE_CHANGED', range: null, pricingVersionId: null, quotedPoints: null })
    setup(); fireEvent.click(screen.getByRole('button', { name: '监测一次' })); await waitFor(() => expect(api.budget).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: '填写范围和额度' })); await screen.findByText('已加载专题地区：广州')
    fireEvent.click(screen.getByRole('button', { name: '核对费用' })); await screen.findByText('价格已更新，请重新核对本次费用。')
    expect(screen.getByRole('button', { name: '确认授权开始' })).toBeDisabled()
  })
  it('未知发送错误只允许显式重试同一 intent，不自动重写授权或发送', async () => {
    const onSend = vi.fn().mockImplementationOnce(async (intent: MonitorExecutionIntent) => {
      const fingerprint = await messageFingerprint({ content: '执行本次已选专题的单次监测。', executionIntent: intent, contextSelectionMode: 'INHERIT', attachments: [] })
      storeMessageAttempt('1', '801', { clientMessageId: 'original-message', idempotencyKey: 'original-key', fingerprint })
      throw new Error('网络中断')
    }).mockResolvedValueOnce({ runId, messageId: '901', status: 'QUEUED' })
    setup({ onSend }); await prepare(); fireEvent.click(screen.getByRole('button', { name: '确认授权开始' }))
    await screen.findByText('网络中断'); expect(onSend).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: '重试同一次请求' }))
    await waitFor(() => expect(onSend).toHaveBeenCalledTimes(2))
    expect(onSend.mock.calls[0]).toEqual(onSend.mock.calls[1]); expect(api.create).toHaveBeenCalledTimes(1); expect(api.put).toHaveBeenCalledTimes(1)
  })
  it.each(['missing', 'changed'])('原 attempt 已被普通聊天恢复/替换（%s）时不以旧 intent 创建新 Run', async (mode) => {
    const onSend = vi.fn().mockRejectedValue(new Error('网络中断'))
    setup({ onSend }); await prepare(); fireEvent.click(screen.getByRole('button', { name: '确认授权开始' }))
    await screen.findByText('网络中断')
    if (mode === 'missing') clearMessageAttempt('1', '801')
    else storeMessageAttempt('1', '801', { clientMessageId: 'other-message', idempotencyKey: 'other-key', fingerprint: 'different-payload' })
    fireEvent.click(screen.getByRole('button', { name: '重试同一次请求' }))
    await screen.findByText('上一条请求记录已更新，请查看已有受理状态。未重新发送监测请求。')
    expect(onSend).toHaveBeenCalledTimes(1)
    expect(api.create).toHaveBeenCalledTimes(1); expect(api.put).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: '重试同一次请求' })).not.toBeInTheDocument()
  })
  it('明确被拒绝的发送解除重试锁，重新报价前不能再提交', async () => {
    setup({ onSend: vi.fn().mockRejectedValue(new ApiError({ status: 409, code: 'GRANT_CHANGED', message: '授权改变' })) })
    await prepare(); fireEvent.click(screen.getByRole('button', { name: '确认授权开始' })); await screen.findByText('授权改变')
    expect(screen.queryByRole('button', { name: '重试同一次请求' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '确认授权开始' })).toBeDisabled()
  })
  it('账户切换后迟到额度响应不得继续授权或发消息', async () => {
    const { props } = setup(); await prepare()
    let resolve!: (value: BudgetPolicy) => void
    api.budget.mockReturnValue(new Promise<BudgetPolicy>((done) => { resolve = done }))
    fireEvent.click(screen.getByRole('button', { name: '确认授权开始' }))
    api.current = false
    await act(async () => { resolve(policy); await Promise.resolve() })
    expect(api.put).not.toHaveBeenCalled(); expect(api.create).not.toHaveBeenCalled(); expect(props.onSend).not.toHaveBeenCalled()
  })
  it('会话离开后迟到响应不得继续创建授权', async () => {
    const view = setup(); await prepare()
    let resolve!: (value: BudgetPolicy) => void
    api.budget.mockReturnValue(new Promise<BudgetPolicy>((done) => { resolve = done }))
    fireEvent.click(screen.getByRole('button', { name: '确认授权开始' })); view.unmount()
    await act(async () => { resolve(policy); await Promise.resolve() })
    expect(api.put).not.toHaveBeenCalled(); expect(api.create).not.toHaveBeenCalled()
  })
  it('只凭已有 USER explicit run 恢复读状态，ADMITTED 有真实 taskId 才显示受理', async () => {
    api.admission.mockResolvedValue({ ...pending, status: 'ADMITTED', taskId: '1001', executionSource: 'EXECUTION_PREAUTH' })
    const { props } = setup({ restoredRunId: runId })
    await screen.findByText('监测已受理')
    expect(api.admission).toHaveBeenCalledWith('801', runId, expect.anything())
    expect(props.onSend).not.toHaveBeenCalled(); expect(api.put).not.toHaveBeenCalled()
  })
  it.each(['RUN_FAILED', 'RUN_STOPPED', 'GRANT_REVOKED', 'DAY_LIMIT'] as const)('正常拒绝 %s 稳定展示，不重新发消息', async (reasonCode) => {
    api.admission.mockResolvedValue({ ...pending, status: 'WAIT_INPUT', reasonCode })
    const { props } = setup({ restoredRunId: runId }); await screen.findByText('本次未自动受理')
    expect(props.onSend).not.toHaveBeenCalled(); expect(api.create).not.toHaveBeenCalled()
  })
  it('ADMITTED 缺任务 ID 仍保持未知，NOT_REQUESTED 不假装完成', () => {
    expect(monitorAdmissionView({ ...pending, status: 'ADMITTED' }).tone).toBe('warning')
    expect(monitorAdmissionView({ ...pending, status: 'NOT_REQUESTED' }).tone).toBe('warning')
  })
  it('读取轮询仅等待态且有界；失败/缺证/终态均停止，不无限轮询', () => {
    expect(monitorAdmissionInterval(pending, null, 1)).toBe(3000)
    expect(monitorAdmissionInterval(pending, null, 30)).toBe(false)
    expect(monitorAdmissionInterval(pending, new Error('offline'), 1)).toBe(false)
    expect(monitorAdmissionInterval(undefined, null, 0)).toBe(false)
    for (const status of ['ADMITTED', 'WAIT_INPUT', 'NOT_REQUESTED'] as const) expect(monitorAdmissionInterval({ ...pending, status }, null, 1)).toBe(false)
  })
})

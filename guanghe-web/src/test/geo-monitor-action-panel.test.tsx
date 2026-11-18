import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { GeoPlatform, GeoTopic } from '../api/geoApi'
import { GeoMonitorActionPanel } from '../features/geo/components/GeoMonitorActionPanel'
import { monitorExpiryIso, positiveMonitorPoints } from '../features/geo/monitorActionEditor'
import type { GeoMonitorEditor } from '../features/geo/monitorActionEditor'

const topic: GeoTopic = {
  id: '101', spaceId: '201', productId: null, name: '小行星品牌发现', region: '广州',
  questions: ['哪些咖啡店值得去？', '周末哪里可以喝咖啡？', '有什么咖啡店适合朋友见面？'].map((text, index) => ({
    id: String(301 + index), sequence: index + 1, category: 'BRAND_DISCOVERY', text, source: 'MANUAL', enabled: true,
    createdAt: '2026-10-07T01:00:00Z', updatedAt: '2026-10-07T01:00:00Z',
  })),
  keywords: [], competitorIds: [], competitorSetVersion: null, version: 1,
  createdAt: '2026-10-07T01:00:00Z', updatedAt: '2026-10-07T01:00:00Z',
}
const platforms: GeoPlatform[] = [
  { code: 'DOUBAO', name: '豆包', configured: true, selectable: true, liveStatus: null },
  { code: 'DEEPSEEK', name: 'DeepSeek', configured: false, selectable: false, liveStatus: null },
]
const editor: GeoMonitorEditor = {
  topicId: topic.id, questionIds: topic.questions.map((question) => question.id), platformCodes: ['DOUBAO'],
  sampleMode: 'STANDARD', actionPoints: '10', dayPoints: '20', expiresLocal: '2099-10-08T18:00',
}

function setup(overrides: Partial<React.ComponentProps<typeof GeoMonitorActionPanel>> = {}) {
  const onStart = vi.fn()
  const onPreview = vi.fn()
  const onChange = vi.fn()
  const props = {
    open: true, loading: false, busy: false, editor, topic, topics: [topic], topicPage: 1, topicTotal: 1,
    topicSearch: '', platforms, quote: { points: 3, sampleCount: 3 }, canRevoke: false, canStart: true,
    startLabel: '授权并开始本次监测', onClose: vi.fn(), onChange, onSearch: vi.fn(), onPageChange: vi.fn(),
    onPreview, onStart, onRevoke: vi.fn(), onRetry: vi.fn(), onViewTask: vi.fn(), ...overrides,
  }
  return { ...render(<GeoMonitorActionPanel {...props} />), props, onStart, onPreview, onChange }
}

describe('一次监测范围与预算展示', () => {
  it('mount 和编辑不授权，只有显式点击才提交，并保持未完成含义', async () => {
    const { onStart, onPreview, onChange } = setup()
    expect(onStart).not.toHaveBeenCalled()
    expect(onPreview).not.toHaveBeenCalled()
    expect(screen.getByText(/本次预计 3 积分/)).toBeInTheDocument()
    expect(screen.getByText(/不创建定时计划/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: topic.questions[0].text }))
    expect(onChange).toHaveBeenCalledWith({ ...editor, questionIds: ['302', '303'] })
    expect(onStart).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: '授权并开始本次监测' }))
    expect(onStart).toHaveBeenCalledTimes(1)
    expect(screen.queryByText('监测已完成')).not.toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'DeepSeek' })).toBeDisabled()
  })

  it.each([
    { actionPoints: '', dayPoints: '' },
    { actionPoints: '1.5' },
    { actionPoints: '1e3' },
    { actionPoints: '21', dayPoints: '20' },
    { actionPoints: '2147483648' },
    { expiresLocal: '2000-01-01T00:00' },
    { questionIds: ['301', '302'] },
    { platformCodes: ['DEEPSEEK'] as GeoPlatform['code'][] },
  ])('无有效额度、有效期或范围时不允许开始：%j', (change) => {
    setup({ editor: { ...editor, ...change } })
    expect(screen.getByRole('button', { name: '授权并开始本次监测' })).toBeDisabled()
  })

  it('没有服务端真实报价，即使用户填了额度也不能开始', () => {
    setup({ quote: undefined })
    expect(screen.getByText('尚未取得有效报价，不会开始监测。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '授权并开始本次监测' })).toBeDisabled()
  })

  it('发送结果未知时在面板内重试原请求，不用关闭抽屉也不允许改范围', async () => {
    const onRetryPending = vi.fn()
    setup({ onRetryPending })
    expect(screen.getByRole('button', { name: '授权并开始本次监测' })).toBeDisabled()
    expect(screen.getByRole('checkbox', { name: topic.questions[0].text })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '重试同一次请求' }))
    expect(onRetryPending).toHaveBeenCalledTimes(1)
  })

  it('地区缺失不默认全国，也不核对费用', () => {
    setup({ topic: { ...topic, region: null } })
    expect(screen.getByText(/尚未设置，请先完善专题地区/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '核对本次费用' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '授权并开始本次监测' })).toBeDisabled()
  })

  it('没有后端准入能力或正在操作时不显示假成功', () => {
    setup({ canStart: false, notice: '当前暂未开放自动监测。' })
    expect(screen.getByRole('button', { name: '授权并开始本次监测' })).toBeDisabled()
    expect(screen.getByText('当前暂未开放自动监测。')).toBeInTheDocument()
    expect(screen.queryByText('监测已受理')).not.toBeInTheDocument()
  })
})

describe('监测编辑值不替代后端授权校验', () => {
  it.each(['0', '-1', '1.1', '1e3', 'Infinity', 'NaN', '2147483648', ''])('拒绝非法积分 %s', (value) => {
    expect(positiveMonitorPoints(value)).toBeUndefined()
  })
  it('只接受 int32 正整数且不转金额单位', () => {
    expect(positiveMonitorPoints('500')).toBe(500)
    expect(positiveMonitorPoints('2147483647')).toBe(2147483647)
  })
  it('截止时间按显式北京时间转换，拒绝无效日期及过去', () => {
    const now = Date.parse('2026-10-07T00:00:00Z')
    expect(monitorExpiryIso('2026-10-07T09:00', now)).toBe('2026-10-07T01:00:00.000Z')
    expect(monitorExpiryIso('2026-10-07T08:00', now)).toBeUndefined()
    expect(monitorExpiryIso('2027-02-30T08:00', now)).toBeUndefined()
    expect(monitorExpiryIso('2027-13-01T08:00', now)).toBeUndefined()
  })
})

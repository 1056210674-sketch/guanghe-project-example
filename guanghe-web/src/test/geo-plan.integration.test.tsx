import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  GeoPlanAuthorizationCard,
  GeoPlanManager,
} from '../features/geo'
import type {
  GeoPlanAuthorizationView,
  GeoPlanDraftView,
  GeoPlanView,
} from '../features/geo'
import { geoPlanRecoveryStore } from '../features/geo/geoPlanRecovery'
import {
  fingerprintJsonInput,
  reuseOrCreateInputAttempt,
} from '../features/task-draft/formTaskDraftLifecycle'

const plan: GeoPlanView = {
  id: 'plan-1', topicId: 'topic-1', name: '每日品牌监测', topicName: '品牌口碑专题',
  questionIds: ['q1', 'q2', 'q3'], platformCodes: ['DOUBAO'], mode: 'STANDARD',
  schedule: { frequency: 'DAILY', localTime: '09:00', timeZone: 'Asia/Shanghai' },
  status: 'ACTIVE', nextRunAt: '2026-09-10T01:00:00Z',
  estimatedPointsPerRun: 21, maxPointsPerRun: 30, pricingVersionId: 'pricing-1',
  version: 1, createdAt: '2026-09-09T01:00:00Z', updatedAt: '2026-09-09T01:00:00Z',
}

const draft: GeoPlanDraftView = {
  name: plan.name, topicId: plan.topicId, questionIds: plan.questionIds,
  platformCodes: plan.platformCodes, mode: plan.mode, schedule: plan.schedule,
  maxPointsPerRun: plan.maxPointsPerRun,
}

const authorization: GeoPlanAuthorizationView = {
  draftId: 'draft-1', title: plan.name, topicName: plan.topicName,
  questionLabels: ['品牌认知如何？', '用户评价如何？', '核心场景是什么？'],
  platformNames: ['豆包'], mode: plan.mode, schedule: plan.schedule,
  estimatedPointsPerRun: 21, maxPointsPerRun: 30,
  pricingVersionId: 'pricing-1', expiresAt: '2026/9/10 09:00:00', confirmEnabled: true,
}

function renderManager(overrides: Partial<React.ComponentProps<typeof GeoPlanManager>> = {}) {
  return render(<GeoPlanManager
    loading={false} error={null} plans={[plan]}
    platforms={[{ code: 'DOUBAO', name: '豆包', availability: 'AVAILABLE' }]}
    topics={[{ id: 'topic-1', name: plan.topicName, keywords: [], platformCodes: [] }]}
    questions={[
      { id: 'q1', text: '品牌认知如何？', typeLabel: '品牌发现', source: 'MANUAL', relatedKeywords: [], status: 'ENABLED', enabled: true },
      { id: 'q2', text: '用户评价如何？', typeLabel: '品牌发现', source: 'MANUAL', relatedKeywords: [], status: 'ENABLED', enabled: true },
      { id: 'q3', text: '核心场景是什么？', typeLabel: '品牌发现', source: 'MANUAL', relatedKeywords: [], status: 'ENABLED', enabled: true },
    ]}
    pagination={{ current: 1, pageSize: 20, total: 1 }} editorOpen={false}
    {...overrides}
  />)
}

describe('GEO 自动监测计划展示与授权边界', () => {
  it('每小时计划恢复保持原分钟锚点，不回退成每天，也不自动写稿发布', () => {
    const hourly: GeoPlanDraftView = { ...draft, schedule: { frequency: 'HOURLY', localTime: '00:35', timeZone: 'Asia/Shanghai' } }
    geoPlanRecoveryStore.write('hourly-test-user', { formDraft: hourly })
    expect(geoPlanRecoveryStore.read('hourly-test-user').formDraft?.schedule).toEqual(hourly.schedule)
    renderManager({ editorOpen: true, draft: hourly })
    expect(screen.getByText('每小时的执行分钟')).toBeInTheDocument()
    expect(screen.getByText(/只监测，不自动写稿或发布/)).toBeInTheDocument()
    geoPlanRecoveryStore.clear('hourly-test-user')
  })
  it('列表展示真实计划字段，暂停/恢复只回传意图且没有取消任务或伪跳过记录', () => {
    const onPause = vi.fn()
    renderManager({ onPausePlanIntent: onPause })
    expect(screen.getByText('每日品牌监测')).toBeInTheDocument()
    expect(screen.getByText('21 积分')).toBeInTheDocument()
    expect(screen.getByText('30 积分')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /暂停/ }))
    expect(onPause).toHaveBeenCalledWith('plan-1')
    expect(screen.queryByText('取消任务')).not.toBeInTheDocument()
    expect(screen.queryByText('跳过记录')).not.toBeInTheDocument()
  })

  it('编辑器使用正式问题、固定周期和IANA时区，不提供Cron或问题组', () => {
    const onChange = vi.fn()
    renderManager({ editorOpen: true, draft, onDraftChange: onChange, onSavePlanIntent: vi.fn() })
    const drawer = screen.getByRole('dialog')
    expect(within(drawer).getByText('问题（3～10 个）')).toBeInTheDocument()
    expect(within(drawer).getByText('IANA 时区')).toBeInTheDocument()
    expect(within(drawer).getByText('生成授权草稿')).toBeInTheDocument()
    expect(within(drawer).queryByText('Cron')).not.toBeInTheDocument()
    expect(within(drawer).queryByText('问题组')).not.toBeInTheDocument()
  })

  it('授权卡明确只创建计划，确认和返回操作均由外部处理', () => {
    const onConfirm = vi.fn()
    const onBack = vi.fn()
    render(<GeoPlanAuthorizationCard authorization={authorization} confirming={false} onConfirm={onConfirm} onBack={onBack} />)
    expect(screen.getByText(/只创建计划，不立即执行监测/)).toBeInTheDocument()
    expect(screen.getByText(/不立即冻结或扣除积分/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '确认并创建计划' }))
    fireEvent.click(screen.getByRole('button', { name: '返回修改' }))
    expect(onConfirm).toHaveBeenCalledWith('draft-1')
    expect(onBack).toHaveBeenCalledOnce()
    expect(screen.queryByText('确认并执行')).not.toBeInTheDocument()
  })

  it('confirming和无令牌语义会锁定确认，防止重复提交', () => {
    const onConfirm = vi.fn()
    const { rerender } = render(<GeoPlanAuthorizationCard authorization={authorization} confirming onConfirm={onConfirm} onBack={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: /确认并创建计划/ }))
    expect(onConfirm).not.toHaveBeenCalled()
    rerender(<GeoPlanAuthorizationCard authorization={{ ...authorization, confirmEnabled: false }} confirming={false} onConfirm={onConfirm} onBack={vi.fn()} />)
    expect(screen.getByRole('button', { name: /确认并创建计划/ })).toBeDisabled()
  })
})

describe('GEO 计划幂等恢复', () => {
  it('同一输入复用幂等键，变化输入创建新尝试', async () => {
    const fingerprint = await fingerprintJsonInput({ topicId: 'topic-1', questionIds: ['q1', 'q2', 'q3'] })
    const first = reuseOrCreateInputAttempt(undefined, fingerprint)
    const retry = reuseOrCreateInputAttempt(first, fingerprint)
    const changed = reuseOrCreateInputAttempt(first, `${fingerprint}-changed`)
    expect(retry).toBe(first)
    expect(changed.idempotencyKey).not.toBe(first.idempotencyKey)
  })

  it('持久化只保留指纹和幂等恢复信息，不保存确认令牌并按用户隔离', () => {
    window.localStorage.clear()
    geoPlanRecoveryStore.write('user-1', {
      editorTarget: 'new',
      formDraft: draft,
      draftAttempt: { fingerprint: 'fingerprint', idempotencyKey: 'draft-key' },
      confirmationAttempt: {
        draftId: 'draft-1', inputHash: 'input-hash', pricingVersionId: 'pricing-1',
        idempotencyKey: 'confirm-key',
        ...({ confirmationToken: 'must-not-persist' } as Record<string, string>),
      },
    })
    const persisted = window.localStorage.getItem(geoPlanRecoveryStore.keyForOwner('user-1'))
    expect(persisted).not.toContain('confirmationToken')
    expect(persisted).not.toContain('must-not-persist')
    expect(geoPlanRecoveryStore.read('user-1').confirmationAttempt?.idempotencyKey).toBe('confirm-key')
    expect(geoPlanRecoveryStore.read('user-1').formDraft).toEqual(draft)
    expect(geoPlanRecoveryStore.read('user-2')).toEqual({})
  })
})

import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  PointsBalanceOverview,
  PointsLedgerPanel,
  pointLedgerType,
  toPointsLedgerEntry,
  type PointsLedgerEntry,
} from '../features/points-billing'
import { APP_PATHS } from '../routes/paths'
import { DEV_PREVIEW_PATHS } from '../routes/devPreviewPaths'
import { useAuthStore } from '../stores/authStore'
import { userFixture } from './fixtures/auth'
import { renderApp } from './renderApp'

/**
 * Balance and ledger only.
 *
 * The recharge, invoice and feedback surfaces were moved to `src/features/billing` and are driven by
 * the real commercial API; their behaviour is covered by `billing-commercial.integration.test.tsx`.
 * What is asserted here is what the point account and the point ledger can prove on their own.
 */

const ledgerEntries: PointsLedgerEntry[] = [
  {
    id: 'ledger-recharge',
    type: 'RECHARGE',
    title: '微信充值到账',
    description: '基础套餐',
    pointsChange: 5000,
    balanceAfter: 9800,
    occurredAt: '2026-09-03 09:00',
    status: 'COMPLETED',
    referenceNo: 'RC202609030001',
  },
  {
    id: 'ledger-consumption',
    type: 'CONSUMPTION',
    title: '图片创作任务结算',
    description: '任务 IMG-1001',
    pointsChange: -320,
    balanceAfter: 9480,
    occurredAt: '2026-09-03 10:00',
    status: 'COMPLETED',
    referenceNo: 'IMG-1001',
  },
  {
    id: 'ledger-freeze',
    type: 'FREEZE',
    title: '视频任务积分冻结',
    description: '任务 VID-1001',
    pointsChange: -1200,
    balanceAfter: 8280,
    occurredAt: '2026-09-03 11:00',
    status: 'PENDING',
    referenceNo: 'VID-1001',
  },
]

const fullSummary = {
  availablePoints: 9480,
  frozenPoints: 1200,
  totalEarnedPoints: 18000,
  totalConsumedPoints: 7320,
  expiringPoints: 600,
  expiresAt: '2026-12-31',
}

async function chooseSelectOption(
  user: ReturnType<typeof userEvent.setup>,
  label: string,
  option: string,
) {
  await user.click(screen.getByLabelText(label))
  const matches = await screen.findAllByText(option)
  await user.click(matches[matches.length - 1])
}

beforeEach(() => {
  vi.clearAllMocks()
  useAuthStore.setState({ status: 'anonymous', user: null, error: null })
})

describe('积分与账单页面', () => {
  it('按真实账务语义区分冻结、退回、结算和余额不变的审计节点', () => {
    expect(pointLedgerType('TASK_FREEZE')).toBe('FREEZE')
    expect(pointLedgerType('WORKFLOW_TASK_RELEASE')).toBe('UNFREEZE')
    expect(pointLedgerType('WORKFLOW_RELEASE')).toBe('UNFREEZE')
    expect(pointLedgerType('WORKFLOW_TASK_SETTLE')).toBe('CONSUMPTION')
    expect(pointLedgerType('WORKFLOW_ALLOCATE')).toBe('ADJUSTMENT')
    expect(pointLedgerType('RECHARGE')).toBe('RECHARGE')
    expect(pointLedgerType('ADMIN_ADJUSTMENT')).toBe('ADJUSTMENT')

    const released = toPointsLedgerEntry({
      id: 'ledger-release',
      entryType: 'TASK_RELEASE',
      title: '任务退回积分',
      businessType: 'TASK',
      businessId: 'task-1',
      amountPoints: 20,
      availableDelta: 20,
      frozenDelta: -20,
      availableAfter: 100,
      frozenAfter: 0,
      createdAt: '2026-09-17T10:00:00Z',
    })
    const allocation = toPointsLedgerEntry({
      id: 'ledger-allocation',
      entryType: 'WORKFLOW_ALLOCATE',
      title: '计划分配任务额度',
      businessType: 'WORKFLOW',
      businessId: 'workflow-1',
      amountPoints: 0,
      availableDelta: 0,
      frozenDelta: 0,
      availableAfter: 100,
      frozenAfter: 20,
      createdAt: '2026-09-17T10:01:00Z',
    })
    const mirroredSettle = toPointsLedgerEntry({
      id: 'ledger-mirrored-settle',
      entryType: 'WORKFLOW_TASK_SETTLE',
      title: '组合计划任务结算镜像',
      businessType: 'TASK',
      businessId: 'task-2',
      amountPoints: 20,
      availableDelta: 0,
      frozenDelta: 0,
      availableAfter: 100,
      frozenAfter: 0,
      createdAt: '2026-09-17T10:02:00Z',
    })

    expect(released).toMatchObject({ type: 'UNFREEZE', pointsChange: 20 })
    expect(allocation).toMatchObject({ type: 'ADJUSTMENT', pointsChange: 0 })
    expect(mirroredSettle).toMatchObject({ type: 'ADJUSTMENT', pointsChange: 0 })
    expect(Object.is(mirroredSettle.pointsChange, -0)).toBe(false)

    for (const entryType of ['RECHARGE', 'ADMIN_ADJUSTMENT'] as const) {
      expect(toPointsLedgerEntry({
        id: '100', entryType, businessType: entryType, businessId: '200',
        title: '积分入账', amountPoints: 50, availableDelta: 50, frozenDelta: 0,
        availableAfter: 150, frozenAfter: 0, createdAt: '2026-09-22T10:00:00Z',
      })).toMatchObject({ pointsChange: 50, description: entryType === 'RECHARGE' ? '充值积分入账' : '人工积分调整' })
    }
  })

  it('没有明细时展示真实空态并禁用筛选控件', () => {
    render(<PointsLedgerPanel entries={[]} />)

    expect(screen.getByText('暂无积分明细')).toBeInTheDocument()
    expect(screen.getByLabelText('搜索积分明细')).toBeDisabled()
    expect(screen.getByLabelText('积分类型').closest('.ant-select')).toHaveClass(
      'ant-select-disabled',
    )
  })

  it('余额摘要只展示有效非负安全整数', () => {
    render(<PointsBalanceOverview summary={fullSummary} />)

    expect(screen.getAllByText('9,480')).toHaveLength(1)
    expect(screen.getByText('1,200')).toBeInTheDocument()
    expect(screen.getByText('18,000')).toBeInTheDocument()
    expect(screen.getByText('7,320')).toBeInTheDocument()
    expect(screen.getByText('600')).toBeInTheDocument()
    expect(screen.getByText('2026-12-31 到期')).toBeInTheDocument()
  })

  it('非法余额数值显示占位符且不泄露 NaN 或 Infinity', () => {
    render(
      <PointsBalanceOverview
        summary={{
          availablePoints: Number.NaN,
          frozenPoints: -1,
          totalEarnedPoints: Number.POSITIVE_INFINITY,
        }}
      />,
    )

    expect(screen.queryByText(/NaN|Infinity/)).not.toBeInTheDocument()
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(3)
  })

  it('搜索词和动态积分类型筛选同时生效', async () => {
    const user = userEvent.setup()
    render(<PointsLedgerPanel entries={ledgerEntries} />)

    await chooseSelectOption(user, '积分类型', '消费')
    fireEvent.change(screen.getByLabelText('搜索积分明细'), {
      target: { value: '图片' },
    })

    const table = screen.getByRole('table', { name: '积分明细列表' })
    expect(within(table).getByText('图片创作任务结算')).toBeInTheDocument()
    expect(within(table).queryByText('微信充值到账')).not.toBeInTheDocument()
    expect(within(table).queryByText('视频任务积分冻结')).not.toBeInTheDocument()
  })

  it('加载失败时展示服务端信息并可重试', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    render(
      <PointsLedgerPanel
        entries={[]}
        error="服务暂时不可用（traceId：0123456789abcdef0123456789abcdef）"
        onRetry={onRetry}
      />,
    )

    expect(screen.getByText(/traceId：0123456789abcdef/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重新加载' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('USER 可通过正式路由访问并看到激活导航', async () => {
    useAuthStore.setState({
      status: 'authenticated',
      user: userFixture,
      error: null,
    })
    renderApp([APP_PATHS.pointsBilling])

    expect(await screen.findByRole('heading', { name: '积分与账单' }, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '积分与账单' })).toHaveAttribute(
      'aria-current',
      'page',
    )
  })

  it('开发环境提供免登录预览路由', async () => {
    renderApp([DEV_PREVIEW_PATHS.pointsBilling])

    expect(await screen.findByRole('heading', { name: '积分与账单' }, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '积分与账单' })).toHaveAttribute(
      'aria-current',
      'page',
    )
  })
})

import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import {
  ChatMessageStream,
  TaskExecutionOutcomeGroup,
  type ChatMessageStreamProps,
  type TaskExecutionOutcomeGroupView,
  type TaskExecutionOutcomeView,
} from '../features/chat'
import {
  articleDraftPreviewFixture,
  generatedImageResultGroupFixture,
  paidTaskConfirmationFixture,
  parallelTaskGroupFixture,
  taskCreatedReceiptFixture,
  taskExecutionOutcomeGroupFixture,
  taskExecutionOutcomeMessageFixture,
} from './fixtures/chat'
import './task-center.integration.test.cases'

function outcomeGroup(
  overrides: Partial<TaskExecutionOutcomeGroupView> = {},
): TaskExecutionOutcomeGroupView {
  return { ...taskExecutionOutcomeGroupFixture, ...overrides }
}

function outcome(
  overrides: Partial<TaskExecutionOutcomeView> = {},
): TaskExecutionOutcomeView {
  return { ...taskExecutionOutcomeGroupFixture.outcomes[0], ...overrides }
}

function messageCallbacks(): Omit<ChatMessageStreamProps, 'messages'> {
  return {
    onSelectClarificationOption: vi.fn(),
    onEditPaidTaskConfirmation: vi.fn(),
    onConfirmPaidTaskConfirmation: vi.fn(),
    onOpenRecharge: vi.fn(),
    onPrepareReestimate: vi.fn(),
    onOpenPaidTaskReferenceAsset: vi.fn(),
    onAddPaidTaskReferenceAsset: vi.fn(),
    onViewTask: vi.fn(),
    onPreviewArticle: vi.fn(),
    onContinueEditingArticle: vi.fn(),
    onSaveArticleToAssets: vi.fn(),
    onPreviewGeneratedImage: vi.fn(),
    onDownloadGeneratedImage: vi.fn(),
    onSaveGeneratedImageToAssets: vi.fn(),
    onSelectGeneratedImage: vi.fn(),
    onTaskOutcomeAction: vi.fn(),
  }
}

describe('任务执行结果与积分结算展示', () => {
  it('outcomes 和 resultItems 均严格按 Props 顺序展示', () => {
    render(
      <TaskExecutionOutcomeGroup
        group={taskExecutionOutcomeGroupFixture}
        onAction={vi.fn()}
      />,
    )

    const group = screen.getByLabelText('任务执行结果与积分结算')
    const outcomeCards = within(group).getAllByLabelText(/任务执行结果：/)
    expect(outcomeCards.map((card) => card.getAttribute('aria-label'))).toEqual(
      taskExecutionOutcomeGroupFixture.outcomes.map(
        (item) => `任务执行结果：${item.taskType}`,
      ),
    )

    const firstOutcome = taskExecutionOutcomeGroupFixture.outcomes[0]
    const firstCard = within(outcomeCards[0])
    expect(firstCard.getByText(firstOutcome.statusLabel)).toBeInTheDocument()
    expect(outcomeCards[0]).toHaveTextContent(firstOutcome.completedAt ?? '')
    expect(outcomeCards[0]).toHaveTextContent(firstOutcome.summary ?? '')
    expect(outcomeCards[0]).toHaveTextContent(firstOutcome.failureReason ?? '')

    const resultList = firstCard.getByLabelText(
      `${firstOutcome.taskType}结果明细`,
    )
    expect(
      within(resultList)
        .getAllByRole('listitem')
        .map((item) => within(item).getByText(/测试结果项/).textContent),
    ).toEqual(firstOutcome.resultItems?.map((item) => item.label))
    expect(group).not.toHaveTextContent('350 积分')
  })

  it('任务与结果项的状态颜色只由 statusTone 决定', () => {
    render(
      <TaskExecutionOutcomeGroup
        group={outcomeGroup({
          outcomes: [
            outcome({
              taskId: 'task-status-tone-test-001',
              statusLabel: '文案写着成功但仅用于测试',
              statusTone: 'danger',
              resultItems: [
                {
                  id: 'result-status-tone-test-001',
                  label: '测试状态项',
                  statusLabel: '文案写着失败但仅用于测试',
                  statusTone: 'success',
                },
              ],
            }),
          ],
        })}
        onAction={vi.fn()}
      />,
    )

    expect(screen.getByText('文案写着成功但仅用于测试')).toHaveClass(
      'task-execution-outcome__status--danger',
    )
    expect(screen.getByText('文案写着失败但仅用于测试')).toHaveClass(
      'task-outcome-item__status--success',
    )
  })

  it('积分只接受非负安全整数，非法值显示单独的占位符', () => {
    render(
      <TaskExecutionOutcomeGroup
        group={outcomeGroup({
          outcomes: [
            outcome({
              taskId: 'settlement-valid-test-001',
              settlement: { settledPoints: 0, returnedPoints: 8930 },
            }),
            outcome({
              taskId: 'settlement-invalid-test-001',
              taskType: '测试非法结算一',
              settlement: { settledPoints: -1, returnedPoints: 1.5 },
            }),
            outcome({
              taskId: 'settlement-invalid-test-002',
              taskType: '测试非法结算二',
              settlement: {
                settledPoints: Number.NaN,
                returnedPoints: Number.POSITIVE_INFINITY,
              },
            }),
            outcome({
              taskId: 'settlement-invalid-test-003',
              taskType: '测试非法结算三',
              settlement: {
                settledPoints: Number.MAX_SAFE_INTEGER + 1,
                returnedPoints: Number.NEGATIVE_INFINITY,
              },
            }),
          ],
        })}
        onAction={vi.fn()}
      />,
    )

    expect(screen.getByText('0 积分')).toBeInTheDocument()
    expect(screen.getByText('8,930 积分')).toBeInTheDocument()
    expect(screen.getAllByText('—')).toHaveLength(6)
    expect(screen.queryByText('— 积分')).not.toBeInTheDocument()
  })

  it('可选字段缺失时不补造内容，且不提供操作或计算结果', () => {
    render(
      <TaskExecutionOutcomeGroup
        group={outcomeGroup({
          outcomes: [
            outcome({
              taskId: 'minimal-outcome-test-001',
              taskType: '测试最小任务结果',
              completedAt: undefined,
              summary: undefined,
              failureReason: undefined,
              resultItems: undefined,
              settlement: {},
              actions: undefined,
            }),
          ],
        })}
        onAction={vi.fn()}
      />,
    )

    const card = screen.getByLabelText('任务执行结果：测试最小任务结果')
    expect(card).not.toHaveTextContent('完成时间')
    expect(card).not.toHaveTextContent('失败原因')
    expect(card).not.toHaveTextContent('结果明细')
    expect(within(card).getAllByText('暂不可查')).toHaveLength(2)
    expect(within(card).queryByRole('button')).not.toBeInTheDocument()
    expect(card).not.toHaveTextContent(
      /合计|成功比例|应扣|应退|现金退款|支付退款/,
    )
    expect(card).toHaveTextContent('已退回积分')
  })

  it('恢复操作严格保序并为五种类型使用固定中文文案', () => {
    render(
      <TaskExecutionOutcomeGroup
        group={taskExecutionOutcomeGroupFixture}
        onAction={vi.fn()}
      />,
    )

    expect(
      screen
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual([
      '仅重试失败项',
      '查看已完成报告',
      '调整内容重新生成',
      '联系支持',
      '稍后重试',
    ])
    expect(screen.queryByRole('button', { name: /取消/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('点击只回传 taskId 和 actionType，disabled 操作不触发', async () => {
    const user = userEvent.setup()
    const onAction = vi.fn()
    render(
      <TaskExecutionOutcomeGroup
        group={taskExecutionOutcomeGroupFixture}
        onAction={onAction}
      />,
    )

    const retryFailedItems = screen.getByRole('button', {
      name: '仅重试失败项',
    })
    const viewCompletedReport = screen.getByRole('button', {
      name: '查看已完成报告',
    })
    const adjustAndRetry = screen.getByRole('button', {
      name: '调整内容重新生成',
    })
    const contactSupport = screen.getByRole('button', { name: '联系支持' })
    const prepareRetry = screen.getByRole('button', { name: '稍后重试' })

    expect(adjustAndRetry).toBeDisabled()
    expect(contactSupport).toBeDisabled()
    await user.click(retryFailedItems)
    await user.click(viewCompletedReport)
    await user.click(adjustAndRetry)
    await user.click(contactSupport)
    await user.click(prepareRetry)

    expect(onAction.mock.calls).toEqual([
      [
        taskExecutionOutcomeGroupFixture.outcomes[0].taskId,
        'PREPARE_RETRY_FAILED_ITEMS',
      ],
      [
        taskExecutionOutcomeGroupFixture.outcomes[0].taskId,
        'VIEW_COMPLETED_REPORT',
      ],
      [
        taskExecutionOutcomeGroupFixture.outcomes[1].taskId,
        'PREPARE_RETRY',
      ],
    ])
    expect(
      screen.getByLabelText('任务执行结果与积分结算'),
    ).toBeInTheDocument()
    expect(retryFailedItems).toBeEnabled()
  })

  it('actions 缺失或为空时不渲染操作区', () => {
    const minimalOutcome = outcome({
      taskId: 'outcome-without-actions-test-001',
      taskType: '测试无恢复操作任务',
      actions: undefined,
    })
    const { rerender } = render(
      <TaskExecutionOutcomeGroup
        group={outcomeGroup({ outcomes: [minimalOutcome] })}
        onAction={vi.fn()}
      />,
    )

    expect(
      screen.queryByLabelText('测试无恢复操作任务恢复操作'),
    ).not.toBeInTheDocument()
    expect(
      screen.getByLabelText('测试无恢复操作任务失败原因'),
    ).toBeInTheDocument()

    rerender(
      <TaskExecutionOutcomeGroup
        group={outcomeGroup({
          outcomes: [{ ...minimalOutcome, actions: [] }],
        })}
        onAction={vi.fn()}
      />,
    )
    expect(
      screen.queryByLabelText('测试无恢复操作任务恢复操作'),
    ).not.toBeInTheDocument()
  })

  it('组件不包含请求、跳转、内部执行或状态修改实现', () => {
    const componentSources = import.meta.glob(
      '../features/chat/components/TaskExecutionOutcomeGroup.tsx',
      {
        eager: true,
        import: 'default',
        query: '?raw',
      },
    ) as Record<string, string>
    const source = Object.values(componentSources).join('\n')

    expect(source).not.toMatch(
      /fetch\s*\(|EventSource|XMLHttpRequest|WebSocket|useQuery|useMutation|setTimeout|setInterval/,
    )
    expect(source).not.toMatch(
      /window\.open|(?:window\.)?location\.|useNavigate|navigate\s*\(|mailto:|tel:|href\s*=|<a\b|<Link\b/,
    )
    expect(source).not.toMatch(
      /executeTask|retryTask|submitTask|cancelTask|confirmTask|deductPoints|refundPoints/,
    )
    expect(source).not.toMatch(
      /useState|useReducer|useEffect|dispatch\s*\(|setState\s*\(/,
    )
    expect(source).not.toMatch(/取消任务|确认并执行|直接执行|自动重试/)
  })

  it('空 outcomes 不渲染组件，也不改变消息宽度语义', () => {
    const { container, rerender } = render(
      <TaskExecutionOutcomeGroup
        group={outcomeGroup({ outcomes: [] })}
        onAction={vi.fn()}
      />,
    )
    expect(container).toBeEmptyDOMElement()

    rerender(
      <ChatMessageStream
        messages={[
          {
            ...taskExecutionOutcomeMessageFixture,
            taskExecutionOutcomeGroup: outcomeGroup({ outcomes: [] }),
          },
        ]}
        {...messageCallbacks()}
      />,
    )
    expect(
      screen.queryByLabelText('任务执行结果与积分结算'),
    ).not.toBeInTheDocument()
    expect(screen.getByLabelText('光禾AI智能体框架消息')).not.toHaveClass(
      'chat-message--task-outcomes',
    )
  })

  it('仅助手消息展示，并与既有卡片共存且保持回执优先规则', () => {
    const callbacks = messageCallbacks()
    const combinedMessage = {
      ...taskExecutionOutcomeMessageFixture,
      articleDraftPreview: articleDraftPreviewFixture,
      generatedImageResultGroup: generatedImageResultGroupFixture,
      paidTaskConfirmation: paidTaskConfirmationFixture,
      parallelTaskGroup: parallelTaskGroupFixture,
    }
    const { rerender } = render(
      <ChatMessageStream messages={[combinedMessage]} {...callbacks} />,
    )

    expect(
      screen.getByLabelText('任务执行结果与积分结算'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText(/图片生成结果/)).toBeInTheDocument()
    expect(screen.getByLabelText(/文章草稿预览/)).toBeInTheDocument()
    expect(screen.getByLabelText(/并行任务组/)).toBeInTheDocument()
    expect(screen.getByLabelText(/付费任务确认卡/)).toBeInTheDocument()

    rerender(
      <ChatMessageStream
        messages={[
          {
            ...combinedMessage,
            taskCreatedReceipt: taskCreatedReceiptFixture,
          },
        ]}
        {...callbacks}
      />,
    )
    expect(
      screen.getByLabelText('任务执行结果与积分结算'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText(/任务创建成功回执/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/付费任务确认卡/)).not.toBeInTheDocument()

    rerender(
      <ChatMessageStream
        messages={[{ ...combinedMessage, role: 'USER' }]}
        {...callbacks}
      />,
    )
    expect(
      screen.queryByLabelText('任务执行结果与积分结算'),
    ).not.toBeInTheDocument()
  })
})

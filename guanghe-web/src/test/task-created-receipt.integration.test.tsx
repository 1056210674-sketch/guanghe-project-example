import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import {
  ChatMessageStream,
  TaskCreatedReceiptCard,
  type ChatMessageView,
  type TaskCreatedReceiptView,
} from '../features/chat'
import {
  paidTaskConfirmationFixture,
  taskCreatedReceiptFixture,
  taskCreatedReceiptMessageFixture,
  videoStoryboardScenesFixture,
} from './fixtures/chat'

const SINGLE_TASK_MESSAGE =
  '该任务已经提交，无需再次确认。'

function receipt(
  overrides: Partial<TaskCreatedReceiptView> = {},
): TaskCreatedReceiptView {
  return { ...taskCreatedReceiptFixture, ...overrides }
}

function renderMessageStream(
  messages: readonly ChatMessageView[],
  onViewTask = vi.fn(),
) {
  render(
    <ChatMessageStream
      messages={messages}
      onSelectClarificationOption={vi.fn()}
      onEditPaidTaskConfirmation={vi.fn()}
      onConfirmPaidTaskConfirmation={vi.fn()}
      onOpenRecharge={vi.fn()}
      onPrepareReestimate={vi.fn()}
      onOpenPaidTaskReferenceAsset={vi.fn()}
      onAddPaidTaskReferenceAsset={vi.fn()}
      onViewTask={onViewTask}
      onPreviewArticle={vi.fn()}
      onContinueEditingArticle={vi.fn()}
      onSaveArticleToAssets={vi.fn()}
      onPreviewGeneratedImage={vi.fn()}
      onDownloadGeneratedImage={vi.fn()}
      onSaveGeneratedImageToAssets={vi.fn()}
      onSelectGeneratedImage={vi.fn()}
      onTaskOutcomeAction={vi.fn()}
    />,
  )
}

describe('任务提交成功回执卡', () => {
  it('完全由 Props 渲染任务、状态、商品、版本、积分和创建信息', () => {
    render(
      <TaskCreatedReceiptCard
        receipt={receipt()}
        onViewTask={vi.fn()}
      />,
    )

    expect(
      screen.getByLabelText(`任务创建成功回执：${taskCreatedReceiptFixture.title}`),
    ).toBeInTheDocument()
    expect(screen.getByText(taskCreatedReceiptFixture.title)).toBeInTheDocument()
    expect(screen.getByText(taskCreatedReceiptFixture.statusLabel)).toHaveClass(
      'task-created-receipt__status--info',
    )
    expect(screen.getByText(taskCreatedReceiptFixture.taskNumber)).toBeInTheDocument()
    expect(screen.getByText(taskCreatedReceiptFixture.taskType)).toBeInTheDocument()
    expect(screen.getByText(taskCreatedReceiptFixture.productName ?? '')).toBeInTheDocument()
    expect(screen.getByText(taskCreatedReceiptFixture.version ?? '')).toBeInTheDocument()
    expect(screen.getByText('240 积分')).toBeInTheDocument()
    expect(screen.getByText('当前余额 760 积分')).toBeInTheDocument()
    expect(screen.getByText(taskCreatedReceiptFixture.createdAt ?? '')).toBeInTheDocument()
    expect(screen.getByText(taskCreatedReceiptFixture.message ?? '')).toBeInTheDocument()
    expect(screen.getByText(SINGLE_TASK_MESSAGE)).toBeInTheDocument()
  })

  it('只提供查看详情操作并回传正确 taskId', async () => {
    const user = userEvent.setup()
    const onViewTask = vi.fn()
    render(
      <TaskCreatedReceiptCard
        receipt={receipt()}
        onViewTask={onViewTask}
      />,
    )

    await user.click(screen.getByRole('button', { name: '查看任务详情' }))
    expect(onViewTask).toHaveBeenCalledOnce()
    expect(onViewTask).toHaveBeenCalledWith(taskCreatedReceiptFixture.taskId)
    expect(screen.queryByRole('button', { name: /确认并执行/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /返回修改/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /取消任务/ })).not.toBeInTheDocument()
  })

  it('detailEnabled=false 时禁用详情操作且不能触发回调', async () => {
    const user = userEvent.setup()
    const onViewTask = vi.fn()
    render(
      <TaskCreatedReceiptCard
        receipt={receipt({ detailEnabled: false })}
        onViewTask={onViewTask}
      />,
    )

    const button = screen.getByRole('button', { name: '查看任务详情' })
    expect(button).toBeDisabled()
    await user.click(button)
    expect(onViewTask).not.toHaveBeenCalled()
  })

  it('非法积分显示为破折号且不暴露非法数值', () => {
    const unsafePoints = Number.MAX_SAFE_INTEGER + 1
    render(
      <TaskCreatedReceiptCard
        receipt={receipt({ frozenPoints: unsafePoints, pointBalance: -1 })}
        onViewTask={vi.fn()}
      />,
    )

    expect(screen.getByText('— 积分')).toBeInTheDocument()
    expect(screen.getByText('当前余额 — 积分')).toBeInTheDocument()
    expect(document.body).not.toHaveTextContent(String(unsafePoints))
    expect(document.body).not.toHaveTextContent('-1')
  })

  it('已有金额标签但历史资金缺失时展示暂不可查，真实零值仍展示零', () => {
    const { rerender } = render(
      <TaskCreatedReceiptCard
        receipt={receipt({ pointsLabel: '实际扣除积分', pointsAmount: undefined, frozenPoints: undefined })}
        onViewTask={vi.fn()}
      />,
    )
    expect(screen.getByText('实际扣除积分')).toBeInTheDocument()
    expect(screen.getByText('暂不可查')).toBeInTheDocument()
    expect(screen.queryByText('0 积分')).not.toBeInTheDocument()
    rerender(
      <TaskCreatedReceiptCard
        receipt={receipt({ pointsLabel: '实际扣除积分', pointsAmount: 0, frozenPoints: undefined })}
        onViewTask={vi.fn()}
      />,
    )
    expect(screen.getByText('0 积分')).toBeInTheDocument()
    expect(screen.queryByText('暂不可查')).not.toBeInTheDocument()
  })

  it('商品图片缺失或加载失败时展示中性占位', () => {
    const { rerender } = render(
      <TaskCreatedReceiptCard
        receipt={receipt({ productImageUrl: undefined })}
        onViewTask={vi.fn()}
      />,
    )
    expect(screen.getByRole('img', { name: /暂无商品图片/ })).toBeInTheDocument()

    rerender(
      <TaskCreatedReceiptCard
        receipt={receipt({ productImageUrl: '/test-receipt-image.png' })}
        onViewTask={vi.fn()}
      />,
    )
    fireEvent.error(screen.getByRole('img', { name: /商品图片$/ }))
    expect(screen.getByRole('img', { name: /暂无商品图片/ })).toBeInTheDocument()
  })
})

describe('任务提交成功回执消息流接入', () => {
  it('助手消息展示回执卡并把详情动作交给外部回调', async () => {
    const user = userEvent.setup()
    const onViewTask = vi.fn()
    renderMessageStream([taskCreatedReceiptMessageFixture], onViewTask)

    expect(
      screen.getByText(taskCreatedReceiptMessageFixture.content),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '查看任务详情' }))
    expect(onViewTask).toHaveBeenCalledWith(taskCreatedReceiptFixture.taskId)
  })

  it('用户消息携带同名字段时不展示回执卡', () => {
    renderMessageStream([
      { ...taskCreatedReceiptMessageFixture, role: 'USER' },
    ])

    expect(screen.queryByLabelText(/任务创建成功回执/)).not.toBeInTheDocument()
  })

  it('同一助手消息同时携带确认卡和回执卡时仅展示回执卡', () => {
    renderMessageStream([
      {
        ...taskCreatedReceiptMessageFixture,
        paidTaskConfirmation: {
          ...paidTaskConfirmationFixture,
          videoStoryboardScenes: videoStoryboardScenesFixture,
        },
      },
    ])

    expect(screen.getByLabelText(/任务创建成功回执/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/付费任务确认卡/)).not.toBeInTheDocument()
    expect(screen.queryByLabelText('视频分镜草稿')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /确认并执行/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /返回修改/ })).not.toBeInTheDocument()
  })
})

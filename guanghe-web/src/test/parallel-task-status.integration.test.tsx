import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import {
  ChatMessageStream,
  ChatWorkspace,
  ParallelTaskGroup,
  type ChatWorkspaceProps,
  type ParallelTaskGroupView,
  type ParallelTaskItemView,
} from '../features/chat'
import {
  articleDraftPreviewFixture,
  paidTaskConfirmationFixture,
  parallelTaskGroupFixture,
  parallelTaskMessageFixture,
  taskCreatedReceiptFixture,
} from './fixtures/chat'

function group(
  overrides: Partial<ParallelTaskGroupView> = {},
): ParallelTaskGroupView {
  return { ...parallelTaskGroupFixture, ...overrides }
}

function task(
  overrides: Partial<ParallelTaskItemView> = {},
): ParallelTaskItemView {
  return { ...parallelTaskGroupFixture.tasks[0], ...overrides }
}

function messageCallbacks() {
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

function workspaceProps(
  overrides: Partial<ChatWorkspaceProps> = {},
): ChatWorkspaceProps {
  return {
    title: '测试并行任务会话',
    sessions: [],
    messages: [parallelTaskMessageFixture],
    recommendations: [],
    taskFocusOptions: [],
    taskStatusSummaries: [],
    sessionSearchValue: '',
    draft: '测试继续对话内容',
    isGenerating: false,
    sendEnabled: true,
    onNewConversation: vi.fn(),
    onSessionSearchChange: vi.fn(),
    onSelectSession: vi.fn(),
    onSelectTaskFocus: vi.fn(),
    onRecommendationSelect: vi.fn(),
    onDraftChange: vi.fn(),
    onSend: vi.fn(),
    onStop: vi.fn(),
    ...messageCallbacks(),
    onToggleHistoricalContext: vi.fn(),
    onOpenReferenceAsset: vi.fn(),
    ...overrides,
  }
}

describe('对话内多任务状态卡片', () => {
  it('完全由 Props 按传入顺序展示任务字段', () => {
    render(
      <ParallelTaskGroup group={parallelTaskGroupFixture} onViewTask={vi.fn()} />,
    )

    const taskGroup = screen.getByLabelText(
      `并行任务组：${parallelTaskGroupFixture.title}`,
    )
    const taskCards = within(taskGroup).getAllByRole('listitem')
    expect(taskCards.map((card) => card.getAttribute('aria-label'))).toEqual(
      parallelTaskGroupFixture.tasks.map(
        (item) => `任务：${item.taskNumber}`,
      ),
    )
    expect(taskGroup).toHaveTextContent('测试监测任务')
    expect(taskGroup).toHaveTextContent('TEST-PARALLEL-003')
    expect(taskGroup).toHaveTextContent('测试执行中')
    expect(taskGroup).toHaveTextContent('测试并行任务商品甲')
    expect(taskGroup).toHaveTextContent('测试进度 18/35')
    expect(taskGroup).toHaveTextContent('测试预计剩余时间')
    expect(
      within(taskGroup).queryByRole('button', {
        name: /停止|取消|重新执行/,
      }),
    ).not.toBeInTheDocument()
  })

  it('只有 0 到 100 的有限数值才展示进度条', () => {
    const progressValues = [
      undefined,
      -1,
      0,
      50,
      100,
      101,
      Number.NaN,
      Number.POSITIVE_INFINITY,
    ]
    render(
      <ParallelTaskGroup
        group={group({
          tasks: progressValues.map((progressPercent, index) =>
            task({
              taskId: `progress-task-test-${index}`,
              taskNumber: `TEST-PROGRESS-${index}`,
              progressPercent,
            }),
          ),
        })}
        onViewTask={vi.fn()}
      />,
    )

    expect(
      screen
        .getAllByRole('progressbar')
        .map((progress) => progress.getAttribute('aria-valuenow')),
    ).toEqual(['0', '50', '100'])
  })

  it('状态颜色只使用 statusTone，不从状态文案推断进度或语义', () => {
    render(
      <ParallelTaskGroup
        group={group({
          tasks: [
            task({
              taskId: 'status-tone-task-test-001',
              taskNumber: 'TEST-STATUS-TONE-001',
              statusLabel: '文案写着已完成但仅用于测试',
              statusTone: 'danger',
              progressPercent: undefined,
            }),
          ],
        })}
        onViewTask={vi.fn()}
      />,
    )

    expect(screen.getByText('文案写着已完成但仅用于测试')).toHaveClass(
      'parallel-task-card__status--danger',
    )
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  })

  it('商品图片缺失或加载失败时展示中性占位', () => {
    const { rerender } = render(
      <ParallelTaskGroup
        group={group({
          tasks: [
            task({
              taskId: 'missing-image-task-test-001',
              taskNumber: 'TEST-MISSING-IMAGE-001',
              productImageUrl: undefined,
            }),
          ],
        })}
        onViewTask={vi.fn()}
      />,
    )
    expect(
      screen.getByRole('img', { name: /暂无商品图片/ }),
    ).toBeInTheDocument()

    rerender(
      <ParallelTaskGroup
        group={group({
          tasks: [
            task({
              taskId: 'missing-image-task-test-001',
              taskNumber: 'TEST-MISSING-IMAGE-001',
              productImageUrl: '/test-parallel-task-product.png',
            }),
          ],
        })}
        onViewTask={vi.fn()}
      />,
    )
    fireEvent.error(screen.getByRole('img', { name: /商品图片$/ }))
    expect(
      screen.getByRole('img', { name: /暂无商品图片/ }),
    ).toBeInTheDocument()
  })

  it('查看任务回传 taskId，detailEnabled=false 时不触发', async () => {
    const user = userEvent.setup()
    const onViewTask = vi.fn()
    render(
      <ParallelTaskGroup
        group={parallelTaskGroupFixture}
        onViewTask={onViewTask}
      />,
    )

    const buttons = screen.getAllByRole('button', { name: '查看任务' })
    expect(buttons).toHaveLength(3)
    await user.click(buttons[0])
    expect(onViewTask).toHaveBeenCalledWith(
      parallelTaskGroupFixture.tasks[0].taskId,
    )
    expect(buttons[2]).toBeDisabled()
    await user.click(buttons[2])
    expect(onViewTask).toHaveBeenCalledOnce()
  })

  it('空任务数组不渲染任务组', () => {
    const { container } = render(
      <ParallelTaskGroup
        group={group({ tasks: [] })}
        onViewTask={vi.fn()}
      />,
    )

    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByLabelText(/并行任务组/)).not.toBeInTheDocument()
  })

  it('只允许助手消息展示，并与文章卡、确认卡和任务回执独立共存', () => {
    const { rerender } = render(
      <ChatMessageStream
        messages={[
          {
            ...parallelTaskMessageFixture,
            articleDraftPreview: articleDraftPreviewFixture,
            paidTaskConfirmation: paidTaskConfirmationFixture,
          },
        ]}
        {...messageCallbacks()}
      />,
    )

    expect(screen.getByLabelText(/并行任务组/)).toBeInTheDocument()
    expect(screen.getByLabelText(/文章草稿预览/)).toBeInTheDocument()
    expect(screen.getByLabelText(/付费任务确认卡/)).toBeInTheDocument()

    rerender(
      <ChatMessageStream
        messages={[
          {
            ...parallelTaskMessageFixture,
            articleDraftPreview: articleDraftPreviewFixture,
            paidTaskConfirmation: paidTaskConfirmationFixture,
            taskCreatedReceipt: taskCreatedReceiptFixture,
          },
        ]}
        {...messageCallbacks()}
      />,
    )
    expect(screen.getByLabelText(/并行任务组/)).toBeInTheDocument()
    expect(screen.getByLabelText(/文章草稿预览/)).toBeInTheDocument()
    expect(screen.getByLabelText(/任务创建成功回执/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/付费任务确认卡/)).not.toBeInTheDocument()

    rerender(
      <ChatMessageStream
        messages={[{ ...parallelTaskMessageFixture, role: 'USER' }]}
        {...messageCallbacks()}
      />,
    )
    expect(screen.queryByLabelText(/并行任务组/)).not.toBeInTheDocument()
  })

  it('展示并行任务时不锁定输入框和继续对话能力', async () => {
    const user = userEvent.setup()
    const onSend = vi.fn()
    render(<ChatWorkspace {...workspaceProps({ onSend })} />)

    const input = screen.getByRole('textbox', { name: '消息输入' })
    const sendButton = screen.getByRole('button', { name: '发送' })
    expect(input).toHaveValue('测试继续对话内容')
    expect(sendButton).toBeEnabled()
    await user.click(sendButton)
    expect(onSend).toHaveBeenCalledWith('测试继续对话内容')
  })
})

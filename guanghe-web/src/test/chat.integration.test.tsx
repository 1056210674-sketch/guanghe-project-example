import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ChatCurrentTaskContext,
  ChatHistoricalTaskContext,
  ChatStageTransitionBanner,
  ChatWorkspace,
  type ChatWorkspaceProps,
} from '../features/chat'
import { APP_PATHS } from '../routes/paths'
import { useAuthStore } from '../stores/authStore'
import { storeAuthToken } from '../auth/tokenStorage'
import { adminFixture, userFixture, testAccessToken, testExpiresAt, testTraceId } from './fixtures/auth'
import {
  chatAssistantMessageFixture,
  articleDraftMessageFixture,
  chatClarificationMessageFixture,
  chatClarificationOptionsFixture,
  chatCurrentReferenceAssetFixture,
  chatCurrentTaskContextFixture,
  chatHistoricalReferenceAssetFixture,
  chatHistoricalTaskContextFixture,
  chatMessageFixture,
  chatProductFixture,
  chatRecommendationFixture,
  chatSessionFixture,
  chatStageTransitionFixture,
  chatTaskFocusOptionsFixture,
  chatTaskStatusSummariesFixture,
  taskExecutionOutcomeGroupFixture,
  taskExecutionOutcomeMessageFixture,
} from './fixtures/chat'
import { renderApp } from './renderApp'

function workspaceProps(
  overrides: Partial<ChatWorkspaceProps> = {},
): ChatWorkspaceProps {
  return {
    title: '新对话',
    sessions: [],
    messages: [],
    recommendations: [chatRecommendationFixture],
    taskFocusOptions: [],
    taskStatusSummaries: [],
    sessionSearchValue: '',
    draft: '',
    isGenerating: false,
    sendEnabled: false,
    onNewConversation: vi.fn(),
    onSessionSearchChange: vi.fn(),
    onSelectSession: vi.fn(),
    onSelectTaskFocus: vi.fn(),
    onRecommendationSelect: vi.fn(),
    onDraftChange: vi.fn(),
    onSend: vi.fn(),
    onStop: vi.fn(),
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
    onToggleHistoricalContext: vi.fn(),
    onOpenReferenceAsset: vi.fn(),
    ...overrides,
  }
}

function ControlledWorkspace({
  onSend,
  onStop = vi.fn(),
  isGenerating = false,
  sendEnabled,
}: {
  onSend: (message: string) => void
  onStop?: () => void
  isGenerating?: boolean
  sendEnabled: boolean
}) {
  const [draft, setDraft] = useState('')

  return (
    <ChatWorkspace
      {...workspaceProps()}
      draft={draft}
      isGenerating={isGenerating}
      sendEnabled={sendEnabled}
      onDraftChange={setDraft}
      onRecommendationSelect={setDraft}
      onSend={onSend}
      onStop={onStop}
    />
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  useAuthStore.setState({ status: 'anonymous', user: null, error: null })
})

describe('AI 对话基础壳与空白态', () => {
  it('生产页面展示真实空会话，无任务时不强制显示上下文侧栏', async () => {
    const user = userEvent.setup()
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input) => {
      const path = String(input)
      const data = path === '/api/v1/projects' ? [] : path === '/api/v1/notifications/unread-count' ? { unreadCount: 0 } : path === '/api/v1/billing/point-account' ? { userId: userFixture.id, availablePoints: 0, frozenPoints: 0, version: 1 } : { items: [], page: 1, size: 100, total: 0, hasNext: false }
      return new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), { headers: { 'Content-Type': 'application/json' } })
    }))
    useAuthStore.setState({
      status: 'authenticated',
      user: userFixture,
      error: null,
    })
    renderApp([APP_PATHS.chat])

    expect(
      await screen.findByRole('heading', { name: '新对话', level: 1 }, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '最近聊天' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '任务进度' })).not.toBeInTheDocument()
    expect(screen.queryByRole('complementary', { name: '本次聊天任务' })).not.toBeInTheDocument()
    expect(await screen.findByText('暂无聊天')).toBeInTheDocument()
    expect(screen.getByText('你好，我是光禾AI智能体框架。')).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: '选择经营空间' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '选择商品' })).not.toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue('')
    const input = screen.getByRole('textbox', { name: '消息输入' })
    const sendButton = screen.getByRole('button', { name: '发送' })
    expect(document.body).not.toHaveTextContent(
      /消息未发送|接口未接入|后续开发|晨光旗舰店|便携咖啡机|8,680|AI 回复示例/,
    )

    await user.type(input, '帮我制定品牌优化方案')
    expect(input).toHaveValue('帮我制定品牌优化方案')
    expect(sendButton).toBeEnabled()
    expect(document.body).not.toHaveTextContent('消息未发送')
    expect(screen.queryByLabelText('消息列表')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('group', { name: '补问选项' }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByLabelText(/付费任务确认卡/),
    ).not.toBeInTheDocument()
    expect(screen.queryByLabelText('参考素材')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('视频分镜草稿')).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/文章草稿预览/)).not.toBeInTheDocument()
    expect(
      screen.queryByLabelText(/文章媒体投稿明细/),
    ).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/并行任务组/)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/图片生成结果/)).not.toBeInTheDocument()
    expect(
      screen.queryByLabelText('任务执行结果与积分结算'),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', {
        name: /查看已完成报告|仅重试失败项|调整内容重新生成|稍后重试|联系支持/,
      }),
    ).not.toBeInTheDocument()
    expect(screen.queryByLabelText('当前焦点')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('任务状态总览')).not.toBeInTheDocument()
    expect(
      screen.queryByLabelText(/任务创建成功回执/),
    ).not.toBeInTheDocument()
    expect(screen.queryByLabelText('对话阶段切换')).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/当前任务参数/)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/历史任务参数/)).not.toBeInTheDocument()
  })

  it('会话搜索无匹配时与真实空列表使用不同文案', () => {
    render(
      <ChatWorkspace
        {...workspaceProps({
          sessions: [],
          sessionSearchValue: '不存在的会话',
        })}
      />,
    )

    expect(screen.getByText('没有匹配的会话')).toBeInTheDocument()
    expect(screen.queryByText('暂无会话')).not.toBeInTheDocument()
  })

  it('进行中文章入口只定位工作流卡片，不代替用户确认', async () => {
    const user = userEvent.setup()
    const scrollIntoView = vi.fn()
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: scrollIntoView,
    })
    render(
      <ChatWorkspace
        {...workspaceProps({
          activeArticleWorkflow: {
            articleId: articleDraftMessageFixture.articleDraftPreview!.id,
            title: articleDraftMessageFixture.articleDraftPreview!.title,
            stageLabel: '待确认创作方案',
            nextActionLabel: '去确认',
          },
          messages: [articleDraftMessageFixture],
        })}
      />,
    )

    await user.click(
      screen.getByRole('button', {
        name: `返回进行中的文章：${articleDraftMessageFixture.articleDraftPreview!.title}`,
      }),
    )

    expect(scrollIntoView).toHaveBeenCalledOnce()
  })

  it('sendEnabled=false 时保留草稿且不触发 onSend', async () => {
    const user = userEvent.setup()
    const onSend = vi.fn()
    render(
      <ControlledWorkspace onSend={onSend} sendEnabled={false} />,
    )

    const input = screen.getByRole('textbox', { name: '消息输入' })
    const sendButton = screen.getByRole('button', { name: '发送' })
    await user.type(input, '保留草稿内容')

    expect(sendButton).toBeDisabled()
    await user.click(sendButton)
    expect(onSend).not.toHaveBeenCalled()
    expect(input).toHaveValue('保留草稿内容')
  })

  it('sendEnabled=true 时有效输入通过 onSend 回调提交', async () => {
    const user = userEvent.setup()
    const onSend = vi.fn()
    render(<ControlledWorkspace onSend={onSend} sendEnabled />)

    const input = screen.getByRole('textbox', { name: '消息输入' })
    const sendButton = screen.getByRole('button', { name: '发送' })

    expect(sendButton).toBeDisabled()
    await user.click(screen.getByRole('button', { name: /测试推荐入口/ }))
    expect(input).toHaveValue(chatRecommendationFixture.prompt)
    await user.clear(input)
    await user.type(input, '   ')
    expect(sendButton).toBeDisabled()
    expect(onSend).not.toHaveBeenCalled()

    await user.clear(input)
    await user.type(input, '  请帮我整理创作目标  ')
    expect(sendButton).toBeEnabled()
    await user.click(sendButton)

    expect(onSend).toHaveBeenCalledOnce()
    expect(onSend).toHaveBeenCalledWith('请帮我整理创作目标')
  })

  it('生成中可以展示停止按钮并通过 onStop 回调表达动作', async () => {
    const user = userEvent.setup()
    const onStop = vi.fn()
    render(
      <ControlledWorkspace
        onSend={vi.fn()}
        onStop={onStop}
        isGenerating
        sendEnabled
      />,
    )

    expect(screen.queryByRole('button', { name: '发送' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '停止当前回复' }))
    expect(onStop).toHaveBeenCalledOnce()
  })

  it('测试 Fixture 只通过 props 渲染并把操作交给外部回调', async () => {
    const user = userEvent.setup()
    const onNewConversation = vi.fn()
    const onSelectSession = vi.fn()
    const onRecommendationSelect = vi.fn()
    render(
      <ChatWorkspace
        {...workspaceProps({
          sessions: [chatSessionFixture],
          messages: [chatMessageFixture],
          selectedSessionId: chatSessionFixture.id,
          selectedProductId: chatProductFixture.id,
          onNewConversation,
          onSelectSession,
          onRecommendationSelect,
        })}
      />,
    )

    expect(screen.getByText(chatMessageFixture.content)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '选择商品' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /测试会话/ }))
    await user.click(screen.getByRole('button', { name: '新建对话' }))

    expect(onSelectSession).toHaveBeenCalledWith(chatSessionFixture.id)
    expect(onNewConversation).toHaveBeenCalledOnce()
    expect(onRecommendationSelect).not.toHaveBeenCalled()
  })

  it('任务恢复操作通过 Workspace 只回传操作意图', async () => {
    const user = userEvent.setup()
    const onTaskOutcomeAction = vi.fn()
    const onConfirmPaidTaskConfirmation = vi.fn()
    const onViewTask = vi.fn()
    const onSend = vi.fn()
    const taskId = taskExecutionOutcomeGroupFixture.outcomes[0].taskId
    const messages = [
      {
        ...taskExecutionOutcomeMessageFixture,
        taskExecutionOutcomeGroup: {
          ...taskExecutionOutcomeGroupFixture,
          outcomes: [
            {
              ...taskExecutionOutcomeGroupFixture.outcomes[0],
              actions: [
                { type: 'PREPARE_RETRY_FAILED_ITEMS' as const, enabled: true },
                { type: 'PREPARE_ADJUST_AND_RETRY' as const, enabled: true },
                { type: 'PREPARE_RETRY' as const, enabled: true },
              ],
            },
          ],
        },
      },
    ]
    render(
      <ChatWorkspace
        {...workspaceProps({
          messages,
          onTaskOutcomeAction,
          onConfirmPaidTaskConfirmation,
          onViewTask,
          onSend,
        })}
      />,
    )

    await user.click(screen.getByRole('button', { name: '仅重试失败项' }))
    await user.click(
      screen.getByRole('button', { name: '调整内容重新生成' }),
    )
    await user.click(screen.getByRole('button', { name: '稍后重试' }))
    expect(onTaskOutcomeAction.mock.calls).toEqual([
      [taskId, 'PREPARE_RETRY_FAILED_ITEMS'],
      [taskId, 'PREPARE_ADJUST_AND_RETRY'],
      [taskId, 'PREPARE_RETRY'],
    ])
    expect(onConfirmPaidTaskConfirmation).not.toHaveBeenCalled()
    expect(onViewTask).not.toHaveBeenCalled()
    expect(onSend).not.toHaveBeenCalled()
    expect(
      screen.getByRole('button', { name: '仅重试失败项' }),
    ).toBeEnabled()
    expect(
      screen.getByLabelText('任务执行结果与积分结算'),
    ).toBeInTheDocument()
    expect(screen.getAllByLabelText('光禾AI智能体框架消息')).toHaveLength(1)
  })

  it('用户和光禾AI智能体框架普通消息使用不同展示语义且不生成补问区域', () => {
    render(
      <ChatWorkspace
        {...workspaceProps({
          messages: [chatMessageFixture, chatAssistantMessageFixture],
        })}
      />,
    )

    const userMessage = screen.getByLabelText('用户消息')
    const assistantMessage = screen.getByLabelText('光禾AI智能体框架消息')

    expect(userMessage).toHaveClass('chat-message--user')
    expect(assistantMessage).toHaveClass('chat-message--assistant')
    expect(userMessage).not.toHaveClass('chat-message--clarification')
    expect(assistantMessage).not.toHaveClass('chat-message--clarification')
    expect(screen.getByText(chatMessageFixture.content)).toBeInTheDocument()
    expect(
      screen.getByText(chatAssistantMessageFixture.content),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('group', { name: '补问选项' }),
    ).not.toBeInTheDocument()
  })

  it('补问内容、选项和说明由 props 渲染并回传稳定 ID', async () => {
    const user = userEvent.setup()
    const onSelectClarificationOption = vi.fn()
    const onSend = vi.fn()
    const onDraftChange = vi.fn()
    render(
      <ChatWorkspace
        {...workspaceProps({
          messages: [chatClarificationMessageFixture],
          onSelectClarificationOption,
          onSend,
          onDraftChange,
        })}
      />,
    )

    expect(
      screen.getByText(chatClarificationMessageFixture.content),
    ).toBeInTheDocument()
    expect(
      screen.getByText(chatClarificationMessageFixture.guidance ?? ''),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('group', { name: '补问选项' }),
    ).toBeInTheDocument()

    const enabledOption = chatClarificationOptionsFixture[1]
    const disabledOption = chatClarificationOptionsFixture[3]
    const enabledButton = screen.getByRole('button', {
      name: new RegExp(enabledOption.title),
    })
    const disabledButton = screen.getByRole('button', {
      name: new RegExp(disabledOption.title),
    })

    expect(enabledButton).toBeEnabled()
    expect(disabledButton).toBeDisabled()
    await user.click(enabledButton)
    await user.click(disabledButton)

    expect(onSelectClarificationOption).toHaveBeenCalledOnce()
    expect(onSelectClarificationOption).toHaveBeenCalledWith(
      chatClarificationMessageFixture.id,
      enabledOption.id,
    )
    expect(onSend).not.toHaveBeenCalled()
    expect(onDraftChange).not.toHaveBeenCalled()
    expect(screen.getAllByLabelText('光禾AI智能体框架消息')).toHaveLength(1)
  })
})

describe('AI 对话路由与角色隔离', () => {
  it('USER 可直接打开兼容 AI 对话路由，不再经过更多功能', async () => {
    useAuthStore.setState({
      status: 'authenticated',
      user: userFixture,
      error: null,
    })
    renderApp([APP_PATHS.chat])
    expect(
      await screen.findByRole('heading', { name: '新对话', level: 1 }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '选择智能体开始新聊天' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '更多功能' })).not.toBeInTheDocument()
  })

  it('ADMIN 访问 AI 对话路由会进入 403', async () => {
    useAuthStore.setState({
      status: 'authenticated',
      user: adminFixture,
      error: null,
    })
    renderApp([APP_PATHS.chat])

    expect(
      await screen.findByRole('heading', { name: '403 拒绝访问' }),
    ).toBeInTheDocument()
  })

  it('带尾斜杠访问时仍保持 Chat 用户文案', async () => {
    useAuthStore.setState({
      status: 'authenticated',
      user: userFixture,
      error: null,
    })
    renderApp([`${APP_PATHS.chat}/`])

    expect(
      await screen.findByRole('heading', { name: '新对话', level: 1 }),
    ).toBeInTheDocument()
    expect(document.body).not.toHaveTextContent(/接口尚未接入|数据接口待接入/)
  })
})

describe('M2B-1 生产源码边界', () => {
  const featureSources = import.meta.glob('../features/chat/**/*.{ts,tsx}', {
    eager: true,
    import: 'default',
    query: '?raw',
  }) as Record<string, string>
  const pageSources = import.meta.glob('../pages/chat/**/*.{ts,tsx}', {
    eager: true,
    import: 'default',
    query: '?raw',
  }) as Record<string, string>
  const productionSources = { ...featureSources, ...pageSources }

  it('只通过 Browser API 接入 Java，不调用 Python、内部接口或运行时 Mock', () => {
    expect(Object.keys(productionSources).length).toBeGreaterThan(0)
    for (const [path, source] of Object.entries(productionSources)) {
      expect(source, path).not.toMatch(/\/api\/v1|\/internal\/v1|:8000/)
      expect(source, path).not.toMatch(/EventSource|XMLHttpRequest|WebSocket/)
      expect(source, path).not.toMatch(
        /\b(?:refetchInterval|pollInterval)\s*:|\b(?:vi|jest)\.(?:mock|spyOn)\s*\(|\bmsw\b/,
      )
      expect(source, path).not.toMatch(
        /showOpenFilePicker|URL\.(?:createObjectURL|revokeObjectURL)|new\s+(?:FileReader|Blob)|mailto:|tel:/,
      )
      expect(source, path).not.toMatch(
        /(?:from|import\s*)[\s(']*['"][^'"]*(?:\/test\/|fixtures?)/,
      )
    }
  })

  it('不在生产源码硬编码示例会话、消息或 Figma 业务演示数据', () => {
    for (const [path, source] of Object.entries(productionSources)) {
      expect(source, path).not.toMatch(
        /(?:ChatSessionView|ChatMessageView)\s*\[\]\s*=\s*\[\s*{/s,
      )
      expect(source, path).not.toMatch(
        /chat-session-test-001|chat-message-test-001|测试会话|测试用户消息/,
      )
      expect(source, path).not.toMatch(
        /晨光旗舰店|便携咖啡机|CM-01-BLK|8,680|120\s*积分/,
      )
      expect(source, path).not.toMatch(
        /paid-task-confirmation-test-001|测试付费任务确认|测试平台甲/,
      )
      expect(source, path).not.toMatch(
        /task-created-receipt-test-001|TEST-TASK-0001|测试回执商品/,
      )
      expect(source, path).not.toMatch(
        /chat-stage-previous-test-001|测试当前意图|测试历史参数值|测试历史素材/,
      )
      expect(source, path).not.toMatch(
        /paid-task-reference-asset-test-001|测试确认卡参考素材/,
      )
      expect(source, path).not.toMatch(
        /video-storyboard-scene-test-001|测试开场画面|测试过程画面|测试收尾画面/,
      )
      expect(source, path).not.toMatch(
        /article-draft-preview-test-001|测试文章草稿标题|测试文章草稿已经生成/,
      )
      expect(source, path).not.toMatch(
        /article-submission-snapshot-test-001|media-submission-target-test-001|测试投稿文章标题|测试媒体甲/,
      )
      expect(source, path).not.toMatch(
        /parallel-task-group-test-001|parallel-task-id-test-001|TEST-PARALLEL-001|测试多任务并行中/,
      )
      expect(source, path).not.toMatch(
        /chat-task-focus-test-001|测试文章草稿|测试 GEO 监测|测试图片创作/,
      )
      expect(source, path).not.toMatch(
        /chat-task-status-test-001|测试执行中 18\/35 项|测试已完成 4\/4 张|测试排队中/,
      )
      expect(source, path).not.toMatch(
        /generated-image-result-group-test-001|generated-image-asset-test-001|测试图片创作结果|测试生成图一/,
      )
      expect(source, path).not.toMatch(
        /task-execution-outcome-group-test-001|task-outcome-item-test-001|测试 GEO 监测结果|测试结果项甲/,
      )
      expect(source, path).not.toMatch(/现金退款|支付退款/)
      expect(source, path).not.toMatch(/店铺|旗舰店|\bSKU\b/)
    }
  })

  it('商品上下文不包含废弃字段、规则或演示图片', () => {
    for (const [path, source] of Object.entries(productionSources)) {
      expect(source, path).not.toMatch(
        /店铺|SKU|商品价格|售价|最多选择|最多可选|多选/,
      )
      expect(source, path).not.toMatch(/data:image|https?:\/\//)
    }
  })

  it('付费确认展示组件和 View Model 不包含内部凭据或取消入口', () => {
    for (const [path, source] of Object.entries(productionSources)) {
      if (path.includes('/live/') || path.includes('/pages/chat/')) continue
      expect(source, path).not.toMatch(
        /confirmationToken|confirmToken|idempotencyKey|pricingVersionId|userId|JWT/,
      )
      expect(source, path).not.toMatch(/取消任务/)
    }
  })
})

describe('跨工具阶段切换展示', () => {
  it('阶段名称、状态和方向关系完全由 Props 渲染', () => {
    render(
      <ChatStageTransitionBanner transition={chatStageTransitionFixture} />,
    )

    expect(screen.getByLabelText('对话阶段切换')).toBeInTheDocument()
    expect(screen.getByText('上一阶段')).toBeInTheDocument()
    expect(screen.getByText('当前阶段')).toBeInTheDocument()
    expect(
      screen.getByLabelText(
        `${chatStageTransitionFixture.previousStage.label}：${chatStageTransitionFixture.previousStage.statusLabel}`,
      ),
    ).toBeInTheDocument()
    expect(
      screen.getByText(chatStageTransitionFixture.currentStage.statusLabel),
    ).toHaveClass('chat-stage-transition__status--warning')
    expect(screen.getByLabelText('切换至')).toBeInTheDocument()
  })

  it('阶段切换条位于对话标题之后且没有 Props 时不生成空区域', () => {
    const { rerender } = render(
      <ChatWorkspace
        {...workspaceProps({
          title: '测试对话标题',
          stageTransition: chatStageTransitionFixture,
        })}
      />,
    )
    const heading = screen.getByRole('heading', {
      name: '测试对话标题',
      level: 1,
    })
    const transition = screen.getByLabelText('对话阶段切换')
    expect(
      heading.compareDocumentPosition(transition) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()

    rerender(<ChatWorkspace {...workspaceProps()} />)
    expect(screen.queryByLabelText('对话阶段切换')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '任务进度' })).not.toBeInTheDocument()
    expect(screen.queryByRole('complementary', { name: '本次聊天任务' })).not.toBeInTheDocument()
  })
})

describe('当前与历史任务上下文隔离', () => {
  it('按需打开任务进度后可切换焦点并保留当前与历史参数', async () => {
    const user = userEvent.setup()
    const onSelectTaskFocus = vi.fn()
    render(
      <ChatWorkspace
        {...workspaceProps({
          selectedProductId: chatProductFixture.id,
          taskFocusOptions: chatTaskFocusOptionsFixture,
          taskStatusSummaries: chatTaskStatusSummariesFixture,
          selectedTaskId: chatTaskFocusOptionsFixture[1].taskId,
          currentTaskContext: chatCurrentTaskContextFixture,
          historicalTaskContext: chatHistoricalTaskContextFixture,
          onSelectTaskFocus,
        })}
      />,
    )

    expect(screen.queryByLabelText('当前焦点')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '任务进度' }))
    expect(await screen.findByRole('dialog', { name: '任务进度' })).toBeInTheDocument()
    expect(screen.getByLabelText('当前焦点')).toBeInTheDocument()
    expect(screen.getByLabelText('任务状态总览')).toBeInTheDocument()
    expect(
      within(screen.getByLabelText('当前焦点')).getByRole('button', {
        name: new RegExp(chatTaskFocusOptionsFixture[1].label),
      }),
    ).toHaveAttribute('aria-pressed', 'true')
    expect(
      within(screen.getByLabelText('任务状态总览')).getByRole('button', {
        name: new RegExp(chatTaskStatusSummariesFixture[0].label),
      }),
    ).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByRole('button', { name: '选择商品' })).not.toBeInTheDocument()
    expect(
      screen.getByLabelText(
        `当前任务参数：${chatCurrentTaskContextFixture.title}`,
      ),
    ).toBeInTheDocument()
    expect(
      screen.getByLabelText(
        `历史任务参数：${chatHistoricalTaskContextFixture.title}`,
      ),
    ).toBeInTheDocument()

    await user.click(
      within(screen.getByLabelText('任务状态总览')).getByRole('button', {
        name: new RegExp(chatTaskStatusSummariesFixture[2].label),
      }),
    )
    expect(onSelectTaskFocus).toHaveBeenCalledOnce()
    expect(onSelectTaskFocus).toHaveBeenCalledWith(
      chatTaskStatusSummariesFixture[2].taskId,
    )
    expect(
      screen.getByLabelText(
        `当前任务参数：${chatCurrentTaskContextFixture.title}`,
      ),
    ).toHaveTextContent('测试当前参数值')
  })

  it.each([
    { taskFocusOptions: chatTaskFocusOptionsFixture },
    { taskStatusSummaries: chatTaskStatusSummariesFixture },
    { currentTaskContext: chatCurrentTaskContextFixture },
    { historicalTaskContext: chatHistoricalTaskContextFixture },
  ])('有任务时显示入口，打开后展示任务详情；无任务时入口消失：%j', async (context) => {
    const user = userEvent.setup()
    const { rerender } = render(<ChatWorkspace {...workspaceProps(context)} />)
    expect(screen.queryByRole('complementary', { name: '本次聊天任务' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '任务进度' }))
    const drawer = await screen.findByRole('dialog', { name: '任务进度' })
    expect(within(drawer).getByRole('complementary', { name: '本次聊天任务' })).toBeInTheDocument()
    expect(within(drawer).queryByRole('combobox', { name: '选择经营空间' })).not.toBeInTheDocument()
    await user.click(within(drawer).getByRole('button', { name: 'Close' }))
    rerender(<ChatWorkspace {...workspaceProps()} />)
    expect(screen.queryByRole('button', { name: '任务进度' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '新对话', level: 1 })).toBeInTheDocument()
  })

  it('当前参数和历史参数使用独立模型字段与独立展示区域', async () => {
    render(
      <ChatWorkspace
        {...workspaceProps({
          selectedProductId: chatProductFixture.id,
          currentTaskContext: chatCurrentTaskContextFixture,
          historicalTaskContext: chatHistoricalTaskContextFixture,
          historicalContextExpanded: true,
        })}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: '任务进度' }))
    const currentRegion = screen.getByLabelText(
      `当前任务参数：${chatCurrentTaskContextFixture.title}`,
    )
    const historicalRegion = screen.getByLabelText(
      `历史任务参数：${chatHistoricalTaskContextFixture.title}`,
    )
    expect(currentRegion).not.toBe(historicalRegion)
    expect(chatCurrentTaskContextFixture.detailRows).not.toBe(
      chatHistoricalTaskContextFixture.summaryRows,
    )
    expect(currentRegion).toHaveTextContent('测试当前参数值')
    expect(currentRegion).toHaveTextContent('参数来源')
    expect(currentRegion).toHaveTextContent('测试参数来源')
    expect(currentRegion).not.toHaveTextContent('测试历史参数值')
    expect(historicalRegion).toHaveTextContent('测试历史参数值')
    expect(historicalRegion).not.toHaveTextContent('测试当前参数值')
    expect(screen.getByText('历史任务参数不参与本次任务')).toBeInTheDocument()
  })

  it('当前任务素材只触发查看回调', async () => {
    const user = userEvent.setup()
    const onOpenReferenceAsset = vi.fn()
    render(
      <ChatCurrentTaskContext
        context={chatCurrentTaskContextFixture}
        onOpenReferenceAsset={onOpenReferenceAsset}
      />,
    )

    await user.click(
      screen.getByRole('button', {
        name: `查看素材：${chatCurrentReferenceAssetFixture.name}`,
      }),
    )
    expect(onOpenReferenceAsset).toHaveBeenCalledOnce()
    expect(onOpenReferenceAsset).toHaveBeenCalledWith(
      chatCurrentReferenceAssetFixture.id,
    )
  })

  it('历史区域默认收起，点击只回传下一展开状态', async () => {
    const user = userEvent.setup()
    const onToggle = vi.fn()
    render(
      <ChatHistoricalTaskContext
        context={chatHistoricalTaskContextFixture}
        onToggle={onToggle}
        onOpenReferenceAsset={vi.fn()}
      />,
    )

    const toggle = screen.getByRole('button', {
      name: new RegExp(chatHistoricalTaskContextFixture.title),
    })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('测试历史参数值')).not.toBeInTheDocument()
    await user.click(toggle)
    expect(onToggle).toHaveBeenCalledWith(
      chatHistoricalTaskContextFixture.id,
      true,
    )
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('测试历史参数值')).not.toBeInTheDocument()
  })

  it('外部展开后历史素材只触发查看，不改变当前参数', async () => {
    const user = userEvent.setup()
    const onToggleHistoricalContext = vi.fn()
    const onOpenReferenceAsset = vi.fn()
    render(
      <ChatWorkspace
        {...workspaceProps({
          currentTaskContext: chatCurrentTaskContextFixture,
          historicalTaskContext: chatHistoricalTaskContextFixture,
          historicalContextExpanded: true,
          onToggleHistoricalContext,
          onOpenReferenceAsset,
        })}
      />,
    )

    await user.click(screen.getByRole('button', { name: '任务进度' }))
    await user.click(
      screen.getByRole('button', {
        name: `查看素材：${chatHistoricalReferenceAssetFixture.name}`,
      }),
    )
    expect(onOpenReferenceAsset).toHaveBeenCalledOnce()
    expect(onOpenReferenceAsset).toHaveBeenCalledWith(
      chatHistoricalReferenceAssetFixture.id,
    )
    expect(onToggleHistoricalContext).not.toHaveBeenCalled()
    expect(screen.getByText('测试当前参数值')).toBeInTheDocument()
    expect(
      screen.getByRole('button', {
        name: `查看素材：${chatCurrentReferenceAssetFixture.name}`,
      }),
    ).toBeInTheDocument()
  })

  it('上下文区域不存在自动沿用或复制历史参数的操作', async () => {
    render(
      <ChatWorkspace
        {...workspaceProps({
          currentTaskContext: chatCurrentTaskContextFixture,
          historicalTaskContext: chatHistoricalTaskContextFixture,
          historicalContextExpanded: true,
        })}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: '任务进度' }))
    expect(
      screen.queryByRole('button', { name: /沿用|复制|自动继承|重新执行/ }),
    ).not.toBeInTheDocument()
    expect(screen.getByText('历史任务参数不参与本次任务')).toBeInTheDocument()
  })
})

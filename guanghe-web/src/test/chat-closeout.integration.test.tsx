import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import {
  ChatWorkspace,
  PaidTaskConfirmationCard,
  type ChatMessageView,
  type ChatWorkspaceProps,
  type PaidTaskConfirmationView,
} from '../features/chat'
import {
  chatProductFixture,
  paidTaskConfirmationFixture,
  videoStoryboardScenesFixture,
} from './fixtures/chat'
import {
  closeoutArticleConfirmationId,
  closeoutArticleMessagesFixture,
  closeoutGeoConfirmationId,
  closeoutGeoMessagesFixture,
  closeoutGeoTaskId,
  closeoutHistoricalAssetFixture,
  closeoutImageConfirmationId,
  closeoutImageConfirmationMessagesFixture,
  closeoutImageCurrentContextFixture,
  closeoutImageHistoricalContextFixture,
  closeoutImageSelectionPendingMessagesFixture,
  closeoutImageStageTransitionFixture,
  closeoutImageToVideoMessagesFixture,
  closeoutNewUserConfirmationId,
  closeoutNewUserMessagesFixture,
  closeoutPointsInsufficientFixture,
  closeoutPointsInsufficientMessageFixture,
  closeoutPriorityMessageFixture,
  closeoutSelectedGeneratedAssetId,
  closeoutSelectedImageAssetFixture,
  closeoutVideoConfirmationId,
} from './fixtures/chatCloseout'

function createStoryCallbacks() {
  return {
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
  }
}

function workspaceProps(
  messages: readonly ChatMessageView[],
  callbacks = createStoryCallbacks(),
  overrides: Partial<ChatWorkspaceProps> = {},
): ChatWorkspaceProps {
  return {
    title: '测试完整场景对话',
    sessions: [],
    messages,
    recommendations: [],
    selectedProductId: chatProductFixture.id,
    taskFocusOptions: [],
    taskStatusSummaries: [],
    sessionSearchValue: '',
    draft: '测试继续对话草稿',
    isGenerating: false,
    sendEnabled: true,
    ...callbacks,
    ...overrides,
  }
}

function insufficientConfirmation(
  overrides: Partial<PaidTaskConfirmationView> = {},
): PaidTaskConfirmationView {
  return {
    ...paidTaskConfirmationFixture,
    pointsCost: 900,
    pointBalance: 320,
    pointsInsufficient: closeoutPointsInsufficientFixture,
    confirmEnabled: true,
    ...overrides,
  }
}

function createPaidCardCallbacks() {
  return {
    onEdit: vi.fn(),
    onConfirm: vi.fn(),
    onOpenRecharge: vi.fn(),
    onPrepareReestimate: vi.fn(),
    onOpenPaidTaskReferenceAsset: vi.fn(),
    onAddPaidTaskReferenceAsset: vi.fn(),
  }
}

describe('积分不足阻断状态', () => {
  it('直接展示三项 Props 数值和未创建说明，并强制禁止确认', async () => {
    const user = userEvent.setup()
    const callbacks = createPaidCardCallbacks()
    render(
      <PaidTaskConfirmationCard
        confirmation={insufficientConfirmation()}
        {...callbacks}
      />,
    )

    const alert = screen.getByRole('alert', { name: '积分不足' })
    expect(alert).toHaveTextContent(
      '积分不足，任务尚未创建，也未扣除积分',
    )
    expect(within(alert).getByText('预计消耗积分')).toBeInTheDocument()
    expect(within(alert).getByText('900 积分')).toBeInTheDocument()
    expect(within(alert).getByText('当前可用积分')).toBeInTheDocument()
    expect(within(alert).getByText('320 积分')).toBeInTheDocument()
    expect(within(alert).getByText('尚缺积分')).toBeInTheDocument()
    expect(within(alert).getByText('581 积分')).toBeInTheDocument()
    expect(
      within(alert).getByText('积分不足，暂不可用'),
    ).toBeInTheDocument()
    expect(
      within(alert).getByRole('button', { name: '去充值' }),
    ).toBeInTheDocument()
    expect(
      within(alert).getByRole('button', { name: '调整方案重新估价' }),
    ).toBeInTheDocument()

    const confirmButton = screen.getByRole('button', {
      name: /确认开始 · .* 积分/,
    })
    expect(confirmButton).toBeDisabled()
    await user.click(confirmButton)
    expect(callbacks.onConfirm).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: /取消任务/ })).toBeNull()
  })

  it('非法或缺失积分统一显示破折号且不能确认', () => {
    render(
      <PaidTaskConfirmationCard
        confirmation={insufficientConfirmation({
          pointsCost: Number.NaN,
          pointBalance: undefined,
          pointsInsufficient: {
            ...closeoutPointsInsufficientFixture,
            shortfallPoints: undefined,
          },
        })}
        {...createPaidCardCallbacks()}
      />,
    )

    const alert = screen.getByRole('alert', { name: '积分不足' })
    expect(within(alert).getAllByText('—')).toHaveLength(3)
    expect(alert).not.toHaveTextContent(/NaN|undefined/)
    expect(
      screen.getByRole('button', { name: /确认开始 · .* 积分/ }),
    ).toBeDisabled()
  })

  it('充值和重新估价只回传 cardId，并分别遵守 enabled', async () => {
    const user = userEvent.setup()
    const callbacks = createPaidCardCallbacks()
    const { rerender } = render(
      <PaidTaskConfirmationCard
        confirmation={insufficientConfirmation()}
        {...callbacks}
      />,
    )

    await user.click(screen.getByRole('button', { name: '去充值' }))
    await user.click(
      screen.getByRole('button', { name: '调整方案重新估价' }),
    )
    expect(callbacks.onOpenRecharge).toHaveBeenCalledWith(
      paidTaskConfirmationFixture.id,
    )
    expect(callbacks.onPrepareReestimate).toHaveBeenCalledWith(
      paidTaskConfirmationFixture.id,
    )
    expect(callbacks.onConfirm).not.toHaveBeenCalled()
    expect(callbacks.onEdit).not.toHaveBeenCalled()

    rerender(
      <PaidTaskConfirmationCard
        confirmation={insufficientConfirmation({
          pointsInsufficient: {
            shortfallPoints: 581,
            rechargeEnabled: false,
            reestimateEnabled: false,
          },
        })}
        {...callbacks}
      />,
    )
    const rechargeButton = screen.getByRole('button', { name: '去充值' })
    const reestimateButton = screen.getByRole('button', {
      name: '调整方案重新估价',
    })
    expect(rechargeButton).toBeDisabled()
    expect(reestimateButton).toBeDisabled()
    await user.click(rechargeButton)
    await user.click(reestimateButton)
    expect(callbacks.onOpenRecharge).toHaveBeenCalledOnce()
    expect(callbacks.onPrepareReestimate).toHaveBeenCalledOnce()
  })

  it('两种积分恢复意图经消息流和工作区完整透传', async () => {
    const user = userEvent.setup()
    const callbacks = createStoryCallbacks()
    render(
      <ChatWorkspace
        {...workspaceProps(
          [closeoutPointsInsufficientMessageFixture],
          callbacks,
        )}
      />,
    )

    await user.click(screen.getByRole('button', { name: '去充值' }))
    await user.click(
      screen.getByRole('button', { name: '调整方案重新估价' }),
    )
    expect(callbacks.onOpenRecharge).toHaveBeenCalledWith(
      'closeout-points-insufficient-card-test-001',
    )
    expect(callbacks.onPrepareReestimate).toHaveBeenCalledWith(
      'closeout-points-insufficient-card-test-001',
    )
    expect(callbacks.onConfirmPaidTaskConfirmation).not.toHaveBeenCalled()
    expect(screen.queryByLabelText(/任务创建成功回执/)).toBeNull()
  })
})

describe('对话工作台四条故事组合闭环', () => {
  it('新用户场景直接补问并展示未确认任务方案，不要求经营空间和商品选择', async () => {
    const user = userEvent.setup()
    const callbacks = createStoryCallbacks()
    const { rerender } = render(
      <ChatWorkspace
        {...workspaceProps([], callbacks, {
          selectedProductId: undefined,
        })}
      />,
    )

    expect(screen.queryByRole('button', { name: '任务进度' })).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: '选择经营空间' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '选择商品' })).not.toBeInTheDocument()

    rerender(
      <ChatWorkspace
        {...workspaceProps(
          closeoutNewUserMessagesFixture.slice(0, 2),
          callbacks,
        )}
      />,
    )
    expect(screen.queryByRole('complementary', { name: '本次聊天任务' })).not.toBeInTheDocument()

    await user.click(
      screen.getByRole('button', { name: /测试图片创作方向/ }),
    )
    expect(callbacks.onSelectClarificationOption).toHaveBeenCalledWith(
      'closeout-new-user-clarification-test-001',
      'closeout-new-user-option-test-001',
    )
    expect(screen.queryByLabelText(/付费任务确认卡/)).toBeNull()

    rerender(
      <ChatWorkspace
        {...workspaceProps(closeoutNewUserMessagesFixture, callbacks)}
      />,
    )
    expect(
      screen.getByLabelText('付费任务确认卡：测试首次付费任务确认'),
    ).toBeInTheDocument()
    expect(screen.queryByLabelText(/任务创建成功回执/)).toBeNull()
    expect(callbacks.onConfirmPaidTaskConfirmation).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '返回修改' }))
    expect(callbacks.onEditPaidTaskConfirmation).toHaveBeenCalledWith(
      closeoutNewUserConfirmationId,
    )
    expect(callbacks.onConfirmPaidTaskConfirmation).not.toHaveBeenCalled()
  })

  it('GEO 场景由外部推进确认、回执、并行状态和失败恢复意图', async () => {
    const user = userEvent.setup()
    const callbacks = createStoryCallbacks()
    const { rerender } = render(
      <ChatWorkspace
        {...workspaceProps(closeoutGeoMessagesFixture.slice(0, 1), callbacks)}
      />,
    )

    await user.click(
      screen.getByRole('button', { name: /测试监测问题范围/ }),
    )
    await user.click(
      screen.getByRole('button', { name: /测试监测平台范围/ }),
    )
    expect(callbacks.onSelectClarificationOption.mock.calls).toEqual([
      [
        'closeout-geo-clarification-test-001',
        'closeout-geo-scope-option-test-002',
      ],
      [
        'closeout-geo-clarification-test-001',
        'closeout-geo-platform-option-test-001',
      ],
    ])
    expect(screen.queryByLabelText(/付费任务确认卡/)).toBeNull()

    rerender(
      <ChatWorkspace
        {...workspaceProps(closeoutGeoMessagesFixture.slice(0, 2), callbacks)}
      />,
    )

    const confirmationCard = screen.getByLabelText(
      '付费任务确认卡：测试 GEO 付费确认',
    )
    expect(
      Array.from(
        confirmationCard.querySelectorAll(
          '.paid-task-confirmation__platforms .ant-tag',
        ),
      ).map((tag) => tag.textContent),
    ).toEqual(['测试平台丙', '测试平台甲', '测试平台乙'])
    await user.click(
      within(confirmationCard).getByRole('button', {
        name: /确认开始 · .* 积分/,
      }),
    )
    expect(callbacks.onConfirmPaidTaskConfirmation).toHaveBeenCalledWith(
      closeoutGeoConfirmationId,
    )
    expect(screen.queryByLabelText(/任务创建成功回执/)).toBeNull()

    rerender(
      <ChatWorkspace
        {...workspaceProps(closeoutGeoMessagesFixture, callbacks)}
      />,
    )
    expect(screen.getByLabelText(/任务创建成功回执/)).toBeInTheDocument()
    const parallelGroup = screen.getByLabelText(/并行任务组/)
    const parallelTasks = within(parallelGroup).getAllByRole('listitem')
    expect(parallelTasks).toHaveLength(2)
    expect(parallelTasks[0]).toHaveTextContent('测试平台丙监测')
    expect(parallelTasks[0]).toHaveTextContent('测试排队中')
    expect(parallelTasks[1]).toHaveTextContent('测试平台甲监测')
    expect(parallelTasks[1]).toHaveTextContent('测试执行中')

    const outcome = screen.getByLabelText(
      '任务执行结果：测试 GEO 部分完成结果',
    )
    const outcomeItems = within(
      within(outcome).getByRole('list', {
        name: '测试 GEO 部分完成结果结果明细',
      }),
    ).getAllByRole('listitem')
    expect(outcomeItems).toHaveLength(3)
    expect(outcomeItems[0]).toHaveTextContent('测试结果项丙')
    expect(outcomeItems[0]).toHaveTextContent('测试成功')
    expect(outcomeItems[1]).toHaveTextContent('测试结果项甲')
    expect(outcomeItems[1]).toHaveTextContent('测试失败')
    await user.click(
      screen.getByRole('button', { name: '仅重试失败项' }),
    )
    expect(callbacks.onTaskOutcomeAction).toHaveBeenCalledWith(
      closeoutGeoTaskId,
      'PREPARE_RETRY_FAILED_ITEMS',
    )
    expect(screen.getAllByLabelText('光禾AI智能体框架消息')).toHaveLength(5)
  })

  it('图片到视频场景只在外部选择后共享当前素材并分别确认任务', async () => {
    const user = userEvent.setup()
    const callbacks = createStoryCallbacks()
    const { rerender } = render(
      <ChatWorkspace
        {...workspaceProps(
          closeoutImageConfirmationMessagesFixture,
          callbacks,
        )}
      />,
    )

    const imageConfirmationCard = screen.getByLabelText(
      '付费任务确认卡：测试图片任务确认',
    )
    await user.click(
      within(imageConfirmationCard).getByRole('button', {
        name: /确认开始 · .* 积分/,
      }),
    )
    expect(callbacks.onConfirmPaidTaskConfirmation).toHaveBeenCalledWith(
      closeoutImageConfirmationId,
    )
    expect(screen.queryByLabelText(/图片生成结果/)).toBeNull()

    rerender(
      <ChatWorkspace
        {...workspaceProps(
          closeoutImageSelectionPendingMessagesFixture,
          callbacks,
        )}
      />,
    )

    const pendingGallery = screen.getByLabelText(/图片生成结果/)
    const pendingCards = within(pendingGallery).getAllByRole('listitem')
    expect(
      within(pendingGallery).queryByLabelText('已选择'),
    ).not.toBeInTheDocument()
    await user.click(
      within(pendingCards[0]).getByRole('button', { name: /用这张继续创作|已加入下一条素材/ }),
    )
    expect(callbacks.onSelectGeneratedImage).toHaveBeenCalledWith(
      closeoutSelectedGeneratedAssetId,
    )
    expect(screen.queryByLabelText(/当前任务参数/)).toBeNull()

    rerender(
      <ChatWorkspace
        {...workspaceProps(closeoutImageToVideoMessagesFixture, callbacks, {
          stageTransition: closeoutImageStageTransitionFixture,
          currentTaskContext: closeoutImageCurrentContextFixture,
          historicalTaskContext: closeoutImageHistoricalContextFixture,
          historicalContextExpanded: true,
        })}
      />,
    )
    expect(screen.getByLabelText('已选择')).toBeInTheDocument()
    const transition = screen.getByLabelText('对话阶段切换')
    expect(
      within(transition).getByLabelText('测试图片创作：测试已完成'),
    ).toBeInTheDocument()
    expect(
      within(transition).getByLabelText('测试视频创作：测试待确认'),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '任务进度' }))
    const currentContext = screen.getByLabelText(
      `当前任务参数：${closeoutImageCurrentContextFixture.title}`,
    )
    const historicalContext = screen.getByLabelText(
      `历史任务参数：${closeoutImageHistoricalContextFixture.title}`,
    )
    expect(
      within(currentContext).getByText(closeoutSelectedImageAssetFixture.name),
    ).toBeInTheDocument()
    expect(
      within(currentContext).queryByText(closeoutHistoricalAssetFixture.name),
    ).toBeNull()
    expect(
      within(historicalContext).getByText(closeoutHistoricalAssetFixture.name),
    ).toBeInTheDocument()

    await user.click(within(screen.getByRole('dialog', { name: '任务进度' })).getByRole('button', { name: 'Close' }))
    const videoCard = screen.getByLabelText(
      '付费任务确认卡：测试视频任务确认',
    )
    const storyboard = within(videoCard).getByLabelText('视频分镜草稿')
    expect(
      within(storyboard)
        .getAllByRole('listitem')
        .map((scene) => scene.textContent),
    ).toEqual(
      videoStoryboardScenesFixture.map(
        (scene) =>
          `${scene.timeRangeLabel}｜${scene.title}${scene.description}`,
      ),
    )
    expect(
      within(videoCard).getByText(
        closeoutSelectedImageAssetFixture.name,
      ),
    ).toBeInTheDocument()
    expect(
      within(videoCard).queryByText(
        closeoutHistoricalAssetFixture.name,
      ),
    ).toBeNull()
    expect(screen.queryByText(/沿用历史素材|自动继承历史素材/)).toBeNull()

    await user.click(
      within(videoCard).getByRole('button', {
        name: /确认开始 · .* 积分/,
      }),
    )
    expect(callbacks.onConfirmPaidTaskConfirmation.mock.calls).toEqual([
      [closeoutImageConfirmationId],
      [closeoutVideoConfirmationId],
    ])
  })

  it('文章投稿场景在不可逆确认前展示文章、媒体、对外价格和积分明细', async () => {
    const user = userEvent.setup()
    const callbacks = createStoryCallbacks()
    const { rerender } = render(
      <ChatWorkspace
        {...workspaceProps(closeoutArticleMessagesFixture.slice(0, 2), callbacks)}
      />,
    )

    expect(screen.getByLabelText(/文章草稿预览/)).toBeInTheDocument()
    const confirmationCard = screen.getByLabelText(
      '付费任务确认卡：测试投稿任务确认',
    )
    const mediaList = within(confirmationCard).getByRole('list', {
      name: '已选媒体',
    })
    expect(
      within(mediaList)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual([
      '测试媒体丙预计时效：测试预计时效三',
      '测试媒体甲预计时效：测试预计时效一',
      '测试媒体乙',
    ])
    expect(confirmationCard).toHaveTextContent('对外价格')
    expect(confirmationCard).toHaveTextContent('测试用户可见报价')
    expect(confirmationCard).toHaveTextContent('570 积分')
    expect(confirmationCard).toHaveTextContent(
      '确认提交后任务不可取消，最终按实际成功结果结算',
    )
    expect(confirmationCard).not.toHaveTextContent(
      /采购原价|采购价|成本价|供应商报价|35%利润|毛利/,
    )

    await user.click(
      within(confirmationCard).getByRole('button', {
        name: /确认开始 · .* 积分/,
      }),
    )
    expect(callbacks.onConfirmPaidTaskConfirmation).toHaveBeenCalledWith(
      closeoutArticleConfirmationId,
    )
    expect(screen.queryByLabelText(/任务创建成功回执/)).toBeNull()

    rerender(
      <ChatWorkspace
        {...workspaceProps(closeoutArticleMessagesFixture, callbacks)}
      />,
    )
    expect(screen.getByLabelText(/任务创建成功回执/)).toBeInTheDocument()
    expect(
      screen.getByLabelText('任务执行结果与积分结算'),
    ).toBeInTheDocument()
  })
})

describe('CLOSEOUT 优先级与生产边界', () => {
  it('同一助手消息中回执压制确认卡，但执行结果仍然展示', () => {
    render(
      <ChatWorkspace
        {...workspaceProps([closeoutPriorityMessageFixture])}
      />,
    )

    expect(screen.getByLabelText(/任务创建成功回执/)).toBeInTheDocument()
    expect(
      screen.getByLabelText('任务执行结果与积分结算'),
    ).toBeInTheDocument()
    expect(screen.queryByLabelText(/付费任务确认卡/)).toBeNull()
    expect(screen.queryByRole('alert', { name: '积分不足' })).toBeNull()
    expect(screen.queryByRole('button', { name: /确认开始 · .* 积分/ })).toBeNull()
  })

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
  const presentationSources = Object.fromEntries(
    Object.entries(featureSources).filter(
      ([path]) =>
        !path.includes('/live/') &&
        // The timeline owns a presentation-only elapsed-time ticker. It cannot
        // initiate recovery, navigation, task, or points side effects.
        !path.endsWith('/ChatExecutionTimeline.tsx'),
    ),
  )

  it('展示组件中的恢复意图不直接请求、导航、执行任务或操作积分', () => {
    for (const [path, source] of Object.entries(presentationSources)) {
      expect(source, path).not.toMatch(
        /\b(?:fetch|EventSource|XMLHttpRequest|WebSocket|setTimeout|setInterval)\s*\(/,
      )
      expect(source, path).not.toMatch(
        /window\.open|(?:window\.)?location\.(?:assign|replace)|(?:window\.)?location\.href\s*=|useNavigate|\bnavigate\s*\(|href\s*=|mailto:|tel:/,
      )
      expect(source, path).not.toMatch(
        /\b(?:createTask|executeTask|retryTask|submitTask|deductPoints|refundPoints)\s*\(/,
      )
      expect(source, path).not.toMatch(
        /(?:pointsCost|pointBalance|shortfallPoints)\s*[-+*/]/,
      )
      expect(source, path).not.toMatch(
        /(?:from|import\s*)[\s(']*['"][^'"]*(?:\/test\/|fixtures?)/,
      )
      expect(source, path).not.toMatch(/closeout-[a-z-]+-test-/)
    }
  })

  it('生产 ChatPage 使用真实查询结果，不注入演示消息和上下文', () => {
    const chatPageSource = pageSources['../pages/chat/ChatPage.tsx']

    expect(chatPageSource).toContain('useConversationMessages')
    expect(chatPageSource).toContain('useProjects')
    expect(chatPageSource).toContain('messages={messages}')
    expect(chatPageSource).not.toContain('businessSpaces={spaces}')
    expect(chatPageSource).not.toContain('products={products}')
    expect(chatPageSource).not.toMatch(
      /(?:from|import\s*)[\s(']*['"][^'"]*(?:\/test\/|fixtures?)/,
    )
    expect(chatPageSource).not.toMatch(/closeout-[a-z-]+-test-/)
    expect(Object.keys(productionSources).length).toBeGreaterThan(0)
  })
})

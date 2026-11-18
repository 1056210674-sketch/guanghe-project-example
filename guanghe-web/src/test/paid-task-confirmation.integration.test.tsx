import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import {
  ArticleDraftPreviewCard,
  ChatHistoricalTaskContext,
  ChatMessageStream,
  PaidTaskConfirmationCard,
  VideoStoryboardDraft,
  type ArticleDraftPreviewView,
  type PaidTaskConfirmationView,
} from '../features/chat'
import {
  articleDraftMessageFixture,
  articleDraftPreviewFixture,
  chatHistoricalReferenceAssetFixture,
  chatHistoricalTaskContextFixture,
  paidTaskConfirmationFixture,
  paidTaskMessageFixture,
  paidTaskReferenceAssetFixture,
  taskCreatedReceiptFixture,
  videoStoryboardScenesFixture,
} from './fixtures/chat'

const REQUIRED_SETTLEMENT_WARNING =
  '确认提交后任务不可取消，最终按实际成功结果结算'

function confirmation(
  overrides: Partial<PaidTaskConfirmationView> = {},
): PaidTaskConfirmationView {
  return { ...paidTaskConfirmationFixture, ...overrides }
}

function referenceAssetCallbacks() {
  return {
    onOpenRecharge: vi.fn(),
    onPrepareReestimate: vi.fn(),
    onOpenPaidTaskReferenceAsset: vi.fn(),
    onAddPaidTaskReferenceAsset: vi.fn(),
  }
}

function article(
  overrides: Partial<ArticleDraftPreviewView> = {},
): ArticleDraftPreviewView {
  return { ...articleDraftPreviewFixture, ...overrides }
}

function articleCallbacks() {
  return {
    onPreviewArticle: vi.fn(),
    onContinueEditingArticle: vi.fn(),
    onSaveArticleToAssets: vi.fn(),
  }
}

function messageResultCallbacks() {
  return {
    onOpenRecharge: vi.fn(),
    onPrepareReestimate: vi.fn(),
    onPreviewGeneratedImage: vi.fn(),
    onDownloadGeneratedImage: vi.fn(),
    onSaveGeneratedImageToAssets: vi.fn(),
    onSelectGeneratedImage: vi.fn(),
    onTaskOutcomeAction: vi.fn(),
  }
}

describe('文章草稿预览卡', () => {
  it('完全由 Props 展示封面、标题、版本、摘要和元数据', () => {
    render(
      <ArticleDraftPreviewCard
        article={article()}
        {...articleCallbacks()}
      />,
    )

    expect(
      screen.getByLabelText(`文章草稿预览：${articleDraftPreviewFixture.title}`),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('img', { name: /测试文章草稿标题文章封面/ }),
    ).toBeInTheDocument()
    expect(screen.getByText(articleDraftPreviewFixture.title)).toBeInTheDocument()
    expect(
      screen.getByText(articleDraftPreviewFixture.versionLabel),
    ).toBeInTheDocument()
    expect(screen.getByText(articleDraftPreviewFixture.excerpt ?? '')).toBeInTheDocument()
    expect(screen.getByText('1,286 字')).toBeInTheDocument()
    expect(screen.getByText('4 分钟')).toBeInTheDocument()
    expect(screen.getByText(articleDraftPreviewFixture.generatedAt ?? '')).toBeInTheDocument()
  })

  it('可选字段缺失时不伪造内容且不显示空封面区域', () => {
    render(
      <ArticleDraftPreviewCard
        article={article({
          coverImageUrl: undefined,
          excerpt: undefined,
          wordCount: undefined,
          estimatedReadingMinutes: undefined,
          generatedAt: undefined,
        })}
        {...articleCallbacks()}
      />,
    )

    expect(screen.queryByRole('img', { name: /文章封面/ })).not.toBeInTheDocument()
    expect(
      document.querySelector('.article-draft-preview__preview--without-cover'),
    ).toBeInTheDocument()
    expect(screen.queryByText('字数')).not.toBeInTheDocument()
    expect(screen.queryByText('预计阅读时间')).not.toBeInTheDocument()
    expect(screen.queryByText('生成时间')).not.toBeInTheDocument()
    expect(document.querySelector('.article-draft-preview__excerpt')).toBeNull()
  })

  it('封面加载失败时隐藏损坏的封面区域', () => {
    render(
      <ArticleDraftPreviewCard
        article={article({ coverImageUrl: '/test-article-cover.png' })}
        {...articleCallbacks()}
      />,
    )

    fireEvent.error(screen.getByRole('img', { name: /文章封面$/ }))
    expect(screen.queryByRole('img', { name: /文章封面/ })).not.toBeInTheDocument()
    expect(
      document.querySelector('.article-draft-preview__preview--without-cover'),
    ).toBeInTheDocument()
  })

  it('三个操作只回传 articleId，actionsEnabled=false 时全部禁用', async () => {
    const user = userEvent.setup()
    const onPreviewArticle = vi.fn()
    const onContinueEditingArticle = vi.fn()
    const onSaveArticleToAssets = vi.fn()
    const callbacks = {
      onPreviewArticle,
      onContinueEditingArticle,
      onSaveArticleToAssets,
    }
    const { rerender } = render(
      <ArticleDraftPreviewCard article={article()} {...callbacks} />,
    )

    await user.click(screen.getByRole('button', { name: '预览文章' }))
    await user.click(screen.getByRole('button', { name: '继续编辑' }))
    await user.click(screen.getByRole('button', { name: '保存到资产' }))
    expect(onPreviewArticle).toHaveBeenCalledWith(articleDraftPreviewFixture.id)
    expect(onContinueEditingArticle).toHaveBeenCalledWith(
      articleDraftPreviewFixture.id,
    )
    expect(onSaveArticleToAssets).toHaveBeenCalledWith(
      articleDraftPreviewFixture.id,
    )

    rerender(
      <ArticleDraftPreviewCard
        article={article({ actionsEnabled: false })}
        {...callbacks}
      />,
    )
    const disabledActions = screen.getAllByRole('button')
    expect(disabledActions).toHaveLength(3)
    disabledActions.forEach((button) => expect(button).toBeDisabled())
    await user.click(disabledActions[0])
    await user.click(disabledActions[1])
    await user.click(disabledActions[2])
    expect(onPreviewArticle).toHaveBeenCalledOnce()
    expect(onContinueEditingArticle).toHaveBeenCalledOnce()
    expect(onSaveArticleToAssets).toHaveBeenCalledOnce()
  })

  it('助手消息渲染文章卡并透传操作，用户消息不渲染', () => {
    const onPreviewArticle = vi.fn()
    const onContinueEditingArticle = vi.fn()
    const onSaveArticleToAssets = vi.fn()
    const { rerender } = render(
      <ChatMessageStream
        messages={[articleDraftMessageFixture]}
        onSelectClarificationOption={vi.fn()}
        onEditPaidTaskConfirmation={vi.fn()}
        onConfirmPaidTaskConfirmation={vi.fn()}
        onOpenPaidTaskReferenceAsset={vi.fn()}
        onAddPaidTaskReferenceAsset={vi.fn()}
        onViewTask={vi.fn()}
        onPreviewArticle={onPreviewArticle}
        onContinueEditingArticle={onContinueEditingArticle}
        onSaveArticleToAssets={onSaveArticleToAssets}
        {...messageResultCallbacks()}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: '预览文章' }))
    fireEvent.click(screen.getByRole('button', { name: '继续编辑' }))
    fireEvent.click(screen.getByRole('button', { name: '保存到资产' }))
    expect(onPreviewArticle).toHaveBeenCalledWith(articleDraftPreviewFixture.id)
    expect(onContinueEditingArticle).toHaveBeenCalledWith(
      articleDraftPreviewFixture.id,
    )
    expect(onSaveArticleToAssets).toHaveBeenCalledWith(
      articleDraftPreviewFixture.id,
    )

    rerender(
      <ChatMessageStream
        messages={[{ ...articleDraftMessageFixture, role: 'USER' }]}
        onSelectClarificationOption={vi.fn()}
        onEditPaidTaskConfirmation={vi.fn()}
        onConfirmPaidTaskConfirmation={vi.fn()}
        onOpenPaidTaskReferenceAsset={vi.fn()}
        onAddPaidTaskReferenceAsset={vi.fn()}
        onViewTask={vi.fn()}
        onPreviewArticle={vi.fn()}
        onContinueEditingArticle={vi.fn()}
        onSaveArticleToAssets={vi.fn()}
        {...messageResultCallbacks()}
      />,
    )
    expect(screen.queryByLabelText(/文章草稿预览/)).not.toBeInTheDocument()
  })

  it('文章卡与付费确认卡可并列展示', () => {
    render(
      <ChatMessageStream
        messages={[
          {
            ...articleDraftMessageFixture,
            paidTaskConfirmation: paidTaskConfirmationFixture,
          },
        ]}
        onSelectClarificationOption={vi.fn()}
        onEditPaidTaskConfirmation={vi.fn()}
        onConfirmPaidTaskConfirmation={vi.fn()}
        onOpenPaidTaskReferenceAsset={vi.fn()}
        onAddPaidTaskReferenceAsset={vi.fn()}
        onViewTask={vi.fn()}
        {...articleCallbacks()}
        {...messageResultCallbacks()}
      />,
    )

    expect(screen.getByLabelText(/文章草稿预览/)).toBeInTheDocument()
    expect(screen.getByLabelText(/付费任务确认卡/)).toBeInTheDocument()
  })

  it('文章卡不改变任务回执优先于确认卡和分镜的规则', () => {
    render(
      <ChatMessageStream
        messages={[
          {
            ...articleDraftMessageFixture,
            paidTaskConfirmation: {
              ...paidTaskConfirmationFixture,
              videoStoryboardScenes: videoStoryboardScenesFixture,
            },
            taskCreatedReceipt: taskCreatedReceiptFixture,
          },
        ]}
        onSelectClarificationOption={vi.fn()}
        onEditPaidTaskConfirmation={vi.fn()}
        onConfirmPaidTaskConfirmation={vi.fn()}
        onOpenPaidTaskReferenceAsset={vi.fn()}
        onAddPaidTaskReferenceAsset={vi.fn()}
        onViewTask={vi.fn()}
        {...articleCallbacks()}
        {...messageResultCallbacks()}
      />,
    )

    expect(screen.getByLabelText(/文章草稿预览/)).toBeInTheDocument()
    expect(screen.getByLabelText(/任务创建成功回执/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/付费任务确认卡/)).not.toBeInTheDocument()
    expect(screen.queryByLabelText('视频分镜草稿')).not.toBeInTheDocument()
  })
})

describe('视频分镜草稿展示组件', () => {
  it('严格按照外部传入顺序展示多个分镜', () => {
    render(<VideoStoryboardDraft scenes={videoStoryboardScenesFixture} />)

    const storyboard = screen.getByLabelText('视频分镜草稿')
    const scenes = within(storyboard).getAllByRole('listitem')
    expect(scenes).toHaveLength(videoStoryboardScenesFixture.length)
    expect(scenes.map((scene) => scene.textContent)).toEqual(
      videoStoryboardScenesFixture.map(
        (scene) =>
          `${scene.timeRangeLabel}｜${scene.title}${scene.description}`,
      ),
    )
    expect(within(storyboard).queryByRole('button')).not.toBeInTheDocument()
  })

  it('没有分镜时不渲染空容器', () => {
    const { container } = render(<VideoStoryboardDraft scenes={[]} />)

    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByLabelText('视频分镜草稿')).not.toBeInTheDocument()
  })

  it('预览图缺失或加载失败时展示占位状态', () => {
    render(
      <VideoStoryboardDraft
        scenes={[
          {
            ...videoStoryboardScenesFixture[0],
            id: 'video-storyboard-missing-image-test',
            previewImageUrl: undefined,
          },
          {
            ...videoStoryboardScenesFixture[1],
            id: 'video-storyboard-failed-image-test',
            previewImageUrl: '/test-storyboard-preview.png',
          },
        ]}
      />,
    )

    expect(
      screen.getByRole('img', { name: /测试收尾画面暂无分镜预览图/ }),
    ).toBeInTheDocument()
    fireEvent.error(
      screen.getByRole('img', { name: /测试开场画面分镜预览/ }),
    )
    expect(
      screen.getByRole('img', { name: /测试开场画面暂无分镜预览图/ }),
    ).toBeInTheDocument()
  })
})

describe('付费任务确认卡展示组件', () => {
  it('完全由 Props 渲染方案、商品、参数、候选问题、平台、积分和风险提示', () => {
    render(
      <PaidTaskConfirmationCard
        confirmation={confirmation()}
        onEdit={vi.fn()}
        onConfirm={vi.fn()}
        {...referenceAssetCallbacks()}
      />,
    )

    expect(
      screen.getByLabelText(`付费任务确认卡：${paidTaskConfirmationFixture.title}`),
    ).toBeInTheDocument()
    expect(screen.getByText(paidTaskConfirmationFixture.taskType)).toBeInTheDocument()
    expect(screen.getByText(paidTaskConfirmationFixture.productName ?? '')).toBeInTheDocument()
    expect(screen.getByText('测试监测专题')).toBeInTheDocument()
    expect(screen.getByText('测试执行方式')).toBeInTheDocument()
    expect(screen.getByText('测试候选问题一')).toBeInTheDocument()
    expect(screen.getByText('测试平台甲')).toBeInTheDocument()
    expect(screen.getByText('350 积分')).toBeInTheDocument()
    expect(screen.getByText('8,680 积分')).toBeInTheDocument()
    expect(screen.getByText('测试风险提示')).toBeInTheDocument()
    expect(screen.getByText(REQUIRED_SETTLEMENT_WARNING)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /取消任务/ })).not.toBeInTheDocument()
  })

  it('返回修改和确认执行分别回传正确 cardId', async () => {
    const user = userEvent.setup()
    const onEdit = vi.fn()
    const onConfirm = vi.fn()
    render(
      <PaidTaskConfirmationCard
        confirmation={confirmation()}
        onEdit={onEdit}
        onConfirm={onConfirm}
        {...referenceAssetCallbacks()}
      />,
    )

    await user.click(screen.getByRole('button', { name: '返回修改' }))
    await user.click(screen.getByRole('button', { name: /确认开始 · .* 积分/ }))

    expect(onEdit).toHaveBeenCalledOnce()
    expect(onEdit).toHaveBeenCalledWith(paidTaskConfirmationFixture.id)
    expect(onConfirm).toHaveBeenCalledOnce()
    expect(onConfirm).toHaveBeenCalledWith(paidTaskConfirmationFixture.id)
  })

  it('confirming 时禁用两个操作且不重复触发回调', async () => {
    const user = userEvent.setup()
    const onEdit = vi.fn()
    const onConfirm = vi.fn()
    render(
      <PaidTaskConfirmationCard
        confirmation={confirmation({ confirming: true })}
        onEdit={onEdit}
        onConfirm={onConfirm}
        {...referenceAssetCallbacks()}
      />,
    )

    const editButton = screen.getByRole('button', { name: '返回修改' })
    const confirmButton = screen.getByRole('button', {
      name: '正在提交',
    })
    expect(
      screen.getByLabelText(`付费任务确认卡：${paidTaskConfirmationFixture.title}`),
    ).toHaveAttribute('aria-busy', 'true')
    expect(editButton).toBeDisabled()
    expect(confirmButton).toBeDisabled()
    await user.click(editButton)
    await user.click(confirmButton)
    expect(onEdit).not.toHaveBeenCalled()
    expect(onConfirm).not.toHaveBeenCalled()
  })

  it('confirmEnabled=false 时禁止确认但仍允许返回修改', async () => {
    const user = userEvent.setup()
    const onEdit = vi.fn()
    const onConfirm = vi.fn()
    render(
      <PaidTaskConfirmationCard
        confirmation={confirmation({ confirmEnabled: false })}
        onEdit={onEdit}
        onConfirm={onConfirm}
        {...referenceAssetCallbacks()}
      />,
    )

    await user.click(screen.getByRole('button', { name: '返回修改' }))
    expect(screen.getByRole('button', { name: /确认开始 · .* 积分/ })).toBeDisabled()
    expect(onEdit).toHaveBeenCalledWith(paidTaskConfirmationFixture.id)
    expect(onConfirm).not.toHaveBeenCalled()
  })

  it('非整数积分不作为有效积分展示且不能确认', () => {
    render(
      <PaidTaskConfirmationCard
        confirmation={confirmation({ pointsCost: 12.5, pointBalance: 20.2 })}
        onEdit={vi.fn()}
        onConfirm={vi.fn()}
        {...referenceAssetCallbacks()}
      />,
    )

    expect(screen.getAllByText('— 积分')).toHaveLength(2)
    expect(screen.getByRole('button', { name: /确认开始 · .* 积分/ })).toBeDisabled()
    expect(document.body).not.toHaveTextContent(/12\.5|20\.2/)
  })

  it('商品图片缺失或加载失败时展示中性占位', () => {
    const { rerender } = render(
      <PaidTaskConfirmationCard
        confirmation={confirmation({ productImageUrl: undefined })}
        onEdit={vi.fn()}
        onConfirm={vi.fn()}
        {...referenceAssetCallbacks()}
      />,
    )
    expect(screen.getByRole('img', { name: /暂无商品图片/ })).toBeInTheDocument()

    rerender(
      <PaidTaskConfirmationCard
        confirmation={confirmation({ productImageUrl: '/test-image.png' })}
        onEdit={vi.fn()}
        onConfirm={vi.fn()}
        {...referenceAssetCallbacks()}
      />,
    )
    fireEvent.error(screen.getByRole('img', { name: /商品图片$/ }))
    expect(screen.getByRole('img', { name: /暂无商品图片/ })).toBeInTheDocument()
  })

  it('参考素材完全由 Props 渲染并回传正确 cardId 和 assetId', async () => {
    const user = userEvent.setup()
    const onOpenPaidTaskReferenceAsset = vi.fn()
    const onAddPaidTaskReferenceAsset = vi.fn()
    render(
      <PaidTaskConfirmationCard
        confirmation={confirmation()}
        onEdit={vi.fn()}
        onConfirm={vi.fn()}
        onOpenRecharge={vi.fn()}
        onPrepareReestimate={vi.fn()}
        onOpenPaidTaskReferenceAsset={onOpenPaidTaskReferenceAsset}
        onAddPaidTaskReferenceAsset={onAddPaidTaskReferenceAsset}
      />,
    )

    const card = screen.getByLabelText(
      `付费任务确认卡：${paidTaskConfirmationFixture.title}`,
    )
    expect(
      within(card).getByText(paidTaskReferenceAssetFixture.name),
    ).toBeInTheDocument()

    await user.click(
      within(card).getByRole('button', {
        name: `查看素材：${paidTaskReferenceAssetFixture.name}`,
      }),
    )
    expect(onOpenPaidTaskReferenceAsset).toHaveBeenCalledWith(
      paidTaskConfirmationFixture.id,
      paidTaskReferenceAssetFixture.id,
    )
    expect(onAddPaidTaskReferenceAsset).not.toHaveBeenCalled()

    await user.click(within(card).getByRole('button', { name: '添加参考素材' }))

    expect(onOpenPaidTaskReferenceAsset).toHaveBeenCalledOnce()
    expect(onAddPaidTaskReferenceAsset).toHaveBeenCalledWith(
      paidTaskConfirmationFixture.id,
    )
  })

  it('没有素材时显示中性空状态且 editingEnabled=false 时没有添加入口', () => {
    render(
      <PaidTaskConfirmationCard
        confirmation={confirmation({
          referenceAssets: [],
          referenceAssetEditingEnabled: false,
        })}
        onEdit={vi.fn()}
        onConfirm={vi.fn()}
        {...referenceAssetCallbacks()}
      />,
    )

    expect(screen.getByRole('status')).toHaveTextContent('暂无参考素材')
    expect(
      screen.queryByRole('button', { name: '添加参考素材' }),
    ).not.toBeInTheDocument()
  })

  it('confirming=true 时不能添加素材但仍可查看已有素材', async () => {
    const user = userEvent.setup()
    const onOpenPaidTaskReferenceAsset = vi.fn()
    const onAddPaidTaskReferenceAsset = vi.fn()
    render(
      <PaidTaskConfirmationCard
        confirmation={confirmation({ confirming: true })}
        onEdit={vi.fn()}
        onConfirm={vi.fn()}
        onOpenRecharge={vi.fn()}
        onPrepareReestimate={vi.fn()}
        onOpenPaidTaskReferenceAsset={onOpenPaidTaskReferenceAsset}
        onAddPaidTaskReferenceAsset={onAddPaidTaskReferenceAsset}
      />,
    )

    const addButton = screen.getByRole('button', { name: '添加参考素材' })
    expect(addButton).toBeDisabled()
    await user.click(addButton)
    await user.click(
      screen.getByRole('button', {
        name: `查看素材：${paidTaskReferenceAssetFixture.name}`,
      }),
    )

    expect(onAddPaidTaskReferenceAsset).not.toHaveBeenCalled()
    expect(onOpenPaidTaskReferenceAsset).toHaveBeenCalledWith(
      paidTaskConfirmationFixture.id,
      paidTaskReferenceAssetFixture.id,
    )
  })

  it('参考素材图片加载失败时展示中性占位', () => {
    render(
      <PaidTaskConfirmationCard
        confirmation={confirmation({
          referenceAssets: [
            {
              ...paidTaskReferenceAssetFixture,
              thumbnailUrl: '/test-reference-asset.png',
            },
          ],
        })}
        onEdit={vi.fn()}
        onConfirm={vi.fn()}
        {...referenceAssetCallbacks()}
      />,
    )

    const referenceSection = screen.getByLabelText('参考素材')
    const image = referenceSection.querySelector('img')
    expect(image).not.toBeNull()
    fireEvent.error(image as HTMLImageElement)
    expect(
      referenceSection.querySelector('.chat-reference-asset__placeholder'),
    ).toBeInTheDocument()
  })

  it('确认卡素材与历史任务素材保持独立且不存在沿用入口', () => {
    render(
      <>
        <PaidTaskConfirmationCard
          confirmation={confirmation()}
          onEdit={vi.fn()}
          onConfirm={vi.fn()}
          {...referenceAssetCallbacks()}
        />
        <ChatHistoricalTaskContext
          context={chatHistoricalTaskContextFixture}
          expanded
          onToggle={vi.fn()}
          onOpenReferenceAsset={vi.fn()}
        />
      </>,
    )

    const card = screen.getByLabelText(
      `付费任务确认卡：${paidTaskConfirmationFixture.title}`,
    )
    expect(
      within(card).getByText(paidTaskReferenceAssetFixture.name),
    ).toBeInTheDocument()
    expect(
      within(card).queryByText(chatHistoricalReferenceAssetFixture.name),
    ).not.toBeInTheDocument()
    expect(
      screen.getByText(chatHistoricalReferenceAssetFixture.name),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: /沿用历史素材/ }),
    ).not.toBeInTheDocument()
  })

  it('视频确认卡按参考素材、分镜草稿、任务参数和积分的顺序展示', () => {
    render(
      <PaidTaskConfirmationCard
        confirmation={confirmation({
          taskType: '测试视频任务',
          videoStoryboardScenes: videoStoryboardScenesFixture,
        })}
        onEdit={vi.fn()}
        onConfirm={vi.fn()}
        {...referenceAssetCallbacks()}
      />,
    )

    const card = screen.getByLabelText(
      `付费任务确认卡：${paidTaskConfirmationFixture.title}`,
    )
    const referenceAssets = within(card).getByLabelText('参考素材')
    const storyboard = within(card).getByLabelText('视频分镜草稿')
    const taskParameters = card.querySelector('.paid-task-confirmation__details')
    const points = card.querySelector('.paid-task-confirmation__points')
    const follows = Node.DOCUMENT_POSITION_FOLLOWING

    expect(taskParameters).not.toBeNull()
    expect(points).not.toBeNull()
    expect(referenceAssets.compareDocumentPosition(storyboard) & follows).toBe(
      follows,
    )
    expect(storyboard.compareDocumentPosition(taskParameters as Element) & follows).toBe(
      follows,
    )
    expect((taskParameters as Element).compareDocumentPosition(points as Element) & follows).toBe(
      follows,
    )
  })

  it('确认卡未提供或传入空分镜时均不渲染分镜区域', () => {
    const { rerender } = render(
      <PaidTaskConfirmationCard
        confirmation={confirmation({ videoStoryboardScenes: undefined })}
        onEdit={vi.fn()}
        onConfirm={vi.fn()}
        {...referenceAssetCallbacks()}
      />,
    )
    expect(screen.queryByLabelText('视频分镜草稿')).not.toBeInTheDocument()

    rerender(
      <PaidTaskConfirmationCard
        confirmation={confirmation({ videoStoryboardScenes: [] })}
        onEdit={vi.fn()}
        onConfirm={vi.fn()}
        {...referenceAssetCallbacks()}
      />,
    )
    expect(screen.queryByLabelText('视频分镜草稿')).not.toBeInTheDocument()
  })
})

describe('付费任务确认卡消息流接入', () => {
  it('只在光禾AI智能体框架消息中按 Props 渲染并把操作交给外部回调', async () => {
    const user = userEvent.setup()
    const onEdit = vi.fn()
    const onConfirm = vi.fn()
    const onOpenPaidTaskReferenceAsset = vi.fn()
    const onAddPaidTaskReferenceAsset = vi.fn()
    render(
      <ChatMessageStream
        messages={[paidTaskMessageFixture]}
        onSelectClarificationOption={vi.fn()}
        onEditPaidTaskConfirmation={onEdit}
        onConfirmPaidTaskConfirmation={onConfirm}
        onOpenPaidTaskReferenceAsset={onOpenPaidTaskReferenceAsset}
        onAddPaidTaskReferenceAsset={onAddPaidTaskReferenceAsset}
        onViewTask={vi.fn()}
        {...articleCallbacks()}
        {...messageResultCallbacks()}
      />,
    )

    expect(screen.getByText(paidTaskMessageFixture.content)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '返回修改' }))
    await user.click(screen.getByRole('button', { name: /确认开始 · .* 积分/ }))
    expect(onEdit).toHaveBeenCalledWith(paidTaskConfirmationFixture.id)
    expect(onConfirm).toHaveBeenCalledWith(paidTaskConfirmationFixture.id)
    fireEvent.click(
      screen.getByRole('button', {
        name: `查看素材：${paidTaskReferenceAssetFixture.name}`,
      }),
    )
    fireEvent.click(screen.getByRole('button', { name: '添加参考素材' }))
    expect(onOpenPaidTaskReferenceAsset).toHaveBeenCalledWith(
      paidTaskConfirmationFixture.id,
      paidTaskReferenceAssetFixture.id,
    )
    expect(onAddPaidTaskReferenceAsset).toHaveBeenCalledWith(
      paidTaskConfirmationFixture.id,
    )
  })

  it('用户消息携带同名可选字段时不渲染付费确认卡', () => {
    render(
      <ChatMessageStream
        messages={[
          {
            ...paidTaskMessageFixture,
            role: 'USER',
            paidTaskConfirmation: {
              ...paidTaskConfirmationFixture,
              videoStoryboardScenes: videoStoryboardScenesFixture,
            },
          },
        ]}
        onSelectClarificationOption={vi.fn()}
        onEditPaidTaskConfirmation={vi.fn()}
        onConfirmPaidTaskConfirmation={vi.fn()}
        onOpenPaidTaskReferenceAsset={vi.fn()}
        onAddPaidTaskReferenceAsset={vi.fn()}
        onViewTask={vi.fn()}
        {...articleCallbacks()}
        {...messageResultCallbacks()}
      />,
    )

    expect(screen.queryByLabelText(/付费任务确认卡/)).not.toBeInTheDocument()
    expect(screen.queryByLabelText('视频分镜草稿')).not.toBeInTheDocument()
  })
})

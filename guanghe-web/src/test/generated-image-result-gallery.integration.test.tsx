import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import {
  ChatMessageStream,
  ChatWorkspace,
  GeneratedImageResultGallery,
  type ChatMessageStreamProps,
  type ChatWorkspaceProps,
  type GeneratedImageResultGroupView,
  type GeneratedImageResultView,
} from '../features/chat'
import {
  articleDraftPreviewFixture,
  generatedImageResultGroupFixture,
  generatedImageResultMessageFixture,
  paidTaskConfirmationFixture,
  parallelTaskGroupFixture,
  taskCreatedReceiptFixture,
} from './fixtures/chat'

function resultGroup(
  overrides: Partial<GeneratedImageResultGroupView> = {},
): GeneratedImageResultGroupView {
  return { ...generatedImageResultGroupFixture, ...overrides }
}

function resultImage(
  overrides: Partial<GeneratedImageResultView> = {},
): GeneratedImageResultView {
  return { ...generatedImageResultGroupFixture.images[0], ...overrides }
}

function generatedImageCallbacks() {
  return {
    onPreviewGeneratedImage: vi.fn(),
    onDownloadGeneratedImage: vi.fn(),
    onSaveGeneratedImageToAssets: vi.fn(),
    onSelectGeneratedImage: vi.fn(),
  }
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
    ...generatedImageCallbacks(),
    onTaskOutcomeAction: vi.fn(),
  }
}

function workspaceProps(
  overrides: Partial<ChatWorkspaceProps> = {},
): ChatWorkspaceProps {
  return {
    title: '测试图片结果会话',
    sessions: [],
    messages: [generatedImageResultMessageFixture],
    recommendations: [],
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
    ...messageCallbacks(),
    onToggleHistoricalContext: vi.fn(),
    onOpenReferenceAsset: vi.fn(),
    ...overrides,
  }
}

describe('图片生成结果画廊与显式选择', () => {
  it('点击缩略图只预览该资产，禁用预览时不能触发下载或继续创作', async () => {
    const user = userEvent.setup()
    const callbacks = generatedImageCallbacks()
    render(<GeneratedImageResultGallery group={generatedImageResultGroupFixture} {...callbacks} />)
    await user.click(screen.getByRole('button', { name: `预览${generatedImageResultGroupFixture.images[0].name}` }))
    expect(callbacks.onPreviewGeneratedImage).toHaveBeenCalledExactlyOnceWith(generatedImageResultGroupFixture.images[0].assetId)
    const disabled = screen.getByRole('button', { name: `预览${generatedImageResultGroupFixture.images[1].name}` })
    expect(disabled).toBeDisabled()
    fireEvent.click(disabled)
    expect(callbacks.onPreviewGeneratedImage).toHaveBeenCalledOnce()
    expect(callbacks.onDownloadGeneratedImage).not.toHaveBeenCalled()
    expect(callbacks.onSelectGeneratedImage).not.toHaveBeenCalled()
  })

  it('完全由 Props 按传入顺序展示名称、可选元数据和外部选中项', () => {
    render(
      <GeneratedImageResultGallery
        group={generatedImageResultGroupFixture}
        {...generatedImageCallbacks()}
      />,
    )

    const gallery = screen.getByLabelText(
      `图片生成结果：${generatedImageResultGroupFixture.title}`,
    )
    const cards = within(gallery).getAllByRole('listitem')
    expect(
      cards.map((card) =>
        within(card).getByText(/测试生成图/).textContent,
      ),
    ).toEqual(
      generatedImageResultGroupFixture.images.map((image) => image.name),
    )

    expect(cards[0]).toHaveTextContent('测试 WEBP')
    expect(cards[0]).toHaveTextContent('测试 1024 × 1280')
    expect(cards[1]).toHaveTextContent('测试 PNG')
    expect(cards[1]).not.toHaveTextContent('测试 1280 × 1024')
    expect(cards[2]).not.toHaveTextContent('测试 WEBP')
    expect(cards[2]).toHaveTextContent('测试 1280 × 1024')
    expect(gallery).not.toHaveTextContent('—')

    const selectButtons = within(gallery).getAllByRole('button', {
      name: /^(用这张继续创作|已加入下一条素材)$/,
    })
    expect(selectButtons.map((button) => button.getAttribute('aria-pressed'))).toEqual(
      ['false', 'true', 'false'],
    )
    expect(cards[1]).toHaveClass('generated-image-result--selected')
    expect(within(cards[1]).getByLabelText('已选择')).toBeInTheDocument()
  })

  it('选择操作只回传 assetId，选中态仅在外部 Props 更新后变化', async () => {
    const user = userEvent.setup()
    const callbacks = generatedImageCallbacks()
    const groupWithoutSelection = resultGroup({ selectedAssetId: undefined })
    const { rerender } = render(
      <GeneratedImageResultGallery
        group={groupWithoutSelection}
        {...callbacks}
      />,
    )

    const firstSelectButton = screen.getAllByRole('button', {
      name: '用这张继续创作',
    })[0]
    expect(firstSelectButton).toHaveAttribute('aria-pressed', 'false')
    await user.click(firstSelectButton)
    expect(callbacks.onSelectGeneratedImage).toHaveBeenCalledOnce()
    expect(callbacks.onSelectGeneratedImage).toHaveBeenCalledWith(
      groupWithoutSelection.images[0].assetId,
    )
    expect(firstSelectButton).toHaveAttribute('aria-pressed', 'false')

    const externallySelectedImage = groupWithoutSelection.images[2]
    rerender(
      <GeneratedImageResultGallery
        group={resultGroup({
          selectedAssetId: externallySelectedImage.assetId,
        })}
        {...callbacks}
      />,
    )
    const selectedButton = screen.getByRole('button', {
      name: '已加入下一条素材',
    })
    expect(selectedButton).toHaveAttribute('aria-pressed', 'true')
    expect(selectedButton).toBeDisabled()
    expect(screen.queryByText(/沿用全部历史结果/)).not.toBeInTheDocument()
  })

  it('四个操作分别受 enabled 字段控制并由 Workspace 透传 assetId', () => {
    const callbacks = generatedImageCallbacks()
    render(<ChatWorkspace {...workspaceProps(callbacks)} />)

    const gallery = screen.getByLabelText(
      `图片生成结果：${generatedImageResultGroupFixture.title}`,
    )
    const cards = gallery.querySelectorAll<HTMLLIElement>('.generated-image-result')
    expect(cards).toHaveLength(3)
    const firstActions = cards[0].querySelectorAll<HTMLButtonElement>('.generated-image-result__actions button')
    expect(firstActions).toHaveLength(4)
    expect(Array.from(firstActions, (button) => button.getAttribute('aria-label'))).toEqual([
      '预览',
      '打开原图',
      '保存到资产',
      '用这张继续创作',
    ])
    firstActions.forEach((button) => fireEvent.click(button))

    const firstAssetId = generatedImageResultGroupFixture.images[0].assetId
    expect(callbacks.onPreviewGeneratedImage).toHaveBeenCalledWith(firstAssetId)
    expect(callbacks.onDownloadGeneratedImage).toHaveBeenCalledWith(firstAssetId)
    expect(callbacks.onSaveGeneratedImageToAssets).toHaveBeenCalledWith(
      firstAssetId,
    )
    expect(callbacks.onSelectGeneratedImage).toHaveBeenCalledWith(firstAssetId)

    const secondActions = cards[1].querySelectorAll<HTMLButtonElement>('.generated-image-result__actions button')
    expect(secondActions).toHaveLength(4)
    expect(secondActions[0]).toBeDisabled()
    expect(secondActions[2]).toBeDisabled()
    fireEvent.click(secondActions[1])
    fireEvent.click(secondActions[3])

    const secondAssetId = generatedImageResultGroupFixture.images[1].assetId
    expect(callbacks.onDownloadGeneratedImage).toHaveBeenLastCalledWith(
      secondAssetId,
    )
    expect(callbacks.onSelectGeneratedImage).toHaveBeenLastCalledWith(
      secondAssetId,
    )

    const thirdActions = cards[2].querySelectorAll<HTMLButtonElement>('.generated-image-result__actions button')
    expect(thirdActions).toHaveLength(4)
    expect(thirdActions[1]).toBeDisabled()
    expect(thirdActions[3]).toBeDisabled()
    fireEvent.click(thirdActions[0])
    fireEvent.click(thirdActions[2])

    const thirdAssetId = generatedImageResultGroupFixture.images[2].assetId
    expect(callbacks.onPreviewGeneratedImage).toHaveBeenLastCalledWith(
      thirdAssetId,
    )
    expect(callbacks.onSaveGeneratedImageToAssets).toHaveBeenLastCalledWith(
      thirdAssetId,
    )
  })

  it('图片缺失、加载失败时展示占位，URL 变化后可重新加载', () => {
    const missingImage = resultImage({
      assetId: 'generated-image-missing-test-001',
      name: '测试缺失图片',
      imageUrl: undefined,
    })
    const brokenImage = resultImage({
      assetId: 'generated-image-broken-test-001',
      name: '测试失败图片',
      imageUrl: '/test-generated-image-broken.png',
    })
    const callbacks = generatedImageCallbacks()
    const { rerender } = render(
      <GeneratedImageResultGallery
        group={resultGroup({ images: [missingImage, brokenImage] })}
        {...callbacks}
      />,
    )

    expect(
      screen.getByRole('img', { name: '测试缺失图片预览暂不可用' }),
    ).toBeInTheDocument()
    fireEvent.error(
      screen.getByRole('img', { name: '测试失败图片生成图片' }),
    )
    expect(
      screen.getByRole('img', { name: '测试失败图片预览暂不可用' }),
    ).toBeInTheDocument()

    rerender(
      <GeneratedImageResultGallery
        group={resultGroup({
          images: [
            {
              ...brokenImage,
              imageUrl: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=',
            },
          ],
        })}
        {...callbacks}
      />,
    )
    expect(
      screen.getByRole('img', { name: '测试失败图片生成图片' }),
    ).toBeInTheDocument()
  })

  it('空 images 不渲染画廊，也不改变消息宽度语义', () => {
    const callbacks = generatedImageCallbacks()
    const { container, rerender } = render(
      <GeneratedImageResultGallery
        group={resultGroup({ images: [] })}
        {...callbacks}
      />,
    )
    expect(container).toBeEmptyDOMElement()

    rerender(
      <ChatMessageStream
        messages={[
          {
            ...generatedImageResultMessageFixture,
            generatedImageResultGroup: resultGroup({ images: [] }),
          },
        ]}
        {...messageCallbacks()}
      />,
    )
    expect(screen.queryByLabelText(/图片生成结果/)).not.toBeInTheDocument()
    expect(screen.getByLabelText('光禾AI智能体框架消息')).not.toHaveClass(
      'chat-message--generated-images',
    )
  })

  it('仅助手消息展示，并与文章、任务卡共存且保持回执优先规则', () => {
    const callbacks = messageCallbacks()
    const combinedMessage = {
      ...generatedImageResultMessageFixture,
      articleDraftPreview: articleDraftPreviewFixture,
      paidTaskConfirmation: paidTaskConfirmationFixture,
      parallelTaskGroup: parallelTaskGroupFixture,
    }
    const { rerender } = render(
      <ChatMessageStream messages={[combinedMessage]} {...callbacks} />,
    )

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
    expect(screen.getByLabelText(/图片生成结果/)).toBeInTheDocument()
    expect(screen.getByLabelText(/文章草稿预览/)).toBeInTheDocument()
    expect(screen.getByLabelText(/并行任务组/)).toBeInTheDocument()
    expect(screen.getByLabelText(/任务创建成功回执/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/付费任务确认卡/)).not.toBeInTheDocument()

    rerender(
      <ChatMessageStream
        messages={[{ ...combinedMessage, role: 'USER' }]}
        {...callbacks}
      />,
    )
    expect(screen.queryByLabelText(/图片生成结果/)).not.toBeInTheDocument()
  })
})

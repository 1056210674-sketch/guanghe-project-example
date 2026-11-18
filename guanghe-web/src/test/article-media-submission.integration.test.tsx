import { render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  ArticleMediaSubmissionDetails,
  ChatMessageStream,
  PaidTaskConfirmationCard,
  type ArticleMediaSubmissionDetailsView,
} from '../features/chat'
import {
  articleMediaSubmissionDetailsFixture,
  paidTaskConfirmationFixture,
  paidTaskMessageFixture,
  taskCreatedReceiptFixture,
} from './fixtures/chat'

function details(
  overrides: Partial<ArticleMediaSubmissionDetailsView> = {},
): ArticleMediaSubmissionDetailsView {
  return { ...articleMediaSubmissionDetailsFixture, ...overrides }
}

function paidCardCallbacks() {
  return {
    onEdit: vi.fn(),
    onConfirm: vi.fn(),
    onOpenRecharge: vi.fn(),
    onPrepareReestimate: vi.fn(),
    onOpenPaidTaskReferenceAsset: vi.fn(),
    onAddPaidTaskReferenceAsset: vi.fn(),
  }
}

function messageStreamCallbacks() {
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

describe('文章媒体投稿确认明细', () => {
  it('完全由 Props 展示文章版本快照和媒体预计时效', () => {
    render(
      <ArticleMediaSubmissionDetails
        details={articleMediaSubmissionDetailsFixture}
      />,
    )

    const submissionDetails = screen.getByLabelText(
      `文章媒体投稿明细：${articleMediaSubmissionDetailsFixture.articleSnapshot.title}`,
    )
    expect(within(submissionDetails).getByLabelText('文章版本快照')).toHaveTextContent(
      articleMediaSubmissionDetailsFixture.articleSnapshot.title,
    )
    expect(submissionDetails).toHaveTextContent(
      articleMediaSubmissionDetailsFixture.articleSnapshot.versionLabel,
    )
    expect(submissionDetails).toHaveTextContent(
      articleMediaSubmissionDetailsFixture.articleSnapshot.generatedAt ?? '',
    )
    expect(submissionDetails).toHaveTextContent('测试预计时效三')
    expect(submissionDetails).toHaveTextContent('测试预计时效一')
    expect(within(submissionDetails).queryByRole('button')).not.toBeInTheDocument()
    expect(within(submissionDetails).queryByRole('img')).not.toBeInTheDocument()
  })

  it('严格按照外部传入顺序展示媒体', () => {
    render(
      <ArticleMediaSubmissionDetails
        details={articleMediaSubmissionDetailsFixture}
      />,
    )

    const mediaList = screen.getByRole('list', { name: '已选媒体' })
    const mediaItems = within(mediaList).getAllByRole('listitem')
    expect(mediaItems.map((item) => item.textContent)).toEqual(
      articleMediaSubmissionDetailsFixture.targets.map(
        (target) =>
          `${target.name}${
            target.estimatedTimeLabel
              ? `预计时效：${target.estimatedTimeLabel}`
              : ''
          }`,
      ),
    )
  })

  it('没有媒体时显示中性空状态', () => {
    render(
      <ArticleMediaSubmissionDetails details={details({ targets: [] })} />,
    )

    expect(screen.getByRole('status')).toHaveTextContent('暂无已选媒体')
    expect(screen.queryByRole('list', { name: '已选媒体' })).not.toBeInTheDocument()
  })

  it('可选时间字段缺失时不补造内容', () => {
    render(
      <ArticleMediaSubmissionDetails
        details={details({
          articleSnapshot: {
            ...articleMediaSubmissionDetailsFixture.articleSnapshot,
            generatedAt: undefined,
          },
          targets: [
            {
              id: 'media-submission-target-without-time-test-001',
              name: '测试无时效媒体',
            },
          ],
        })}
      />,
    )

    const submissionDetails = screen.getByLabelText(/文章媒体投稿明细/)
    expect(submissionDetails).not.toHaveTextContent('测试文章生成时间')
    expect(screen.getByRole('listitem')).toHaveTextContent('测试无时效媒体')
    expect(screen.getByRole('listitem')).not.toHaveTextContent('—')
  })

  it('确认卡按参考素材、投稿明细、通用参数和积分顺序展示且不重复平台', () => {
    render(
      <PaidTaskConfirmationCard
        confirmation={{
          ...paidTaskConfirmationFixture,
          mediaSubmissionDetails: articleMediaSubmissionDetailsFixture,
        }}
        {...paidCardCallbacks()}
      />,
    )

    const card = screen.getByLabelText(/付费任务确认卡/)
    const referenceAssets = within(card).getByLabelText('参考素材')
    const submissionDetails = within(card).getByLabelText(/文章媒体投稿明细/)
    const commonDetails = card.querySelector('.paid-task-confirmation__details')
    const points = card.querySelector('.paid-task-confirmation__points')
    expect(commonDetails).not.toBeNull()
    expect(points).not.toBeNull()
    const follows = Node.DOCUMENT_POSITION_FOLLOWING
    expect(
      referenceAssets.compareDocumentPosition(submissionDetails) & follows,
    ).toBeTruthy()
    expect(
      submissionDetails.compareDocumentPosition(commonDetails as Element) &
        follows,
    ).toBeTruthy()
    expect(
      (commonDetails as Element).compareDocumentPosition(points as Element) &
        follows,
    ).toBeTruthy()
    expect(within(card).queryByText('目标平台')).not.toBeInTheDocument()
    expect(within(card).queryByText('测试平台甲')).not.toBeInTheDocument()
    expect(within(card).queryByText('测试平台乙')).not.toBeInTheDocument()
    expect(within(card).getByText('350 积分')).toBeInTheDocument()
    expect(card).not.toHaveTextContent(/采购价|利润/)
  })

  it('空媒体明细仍不回退到 platformNames，缺少明细时保持原平台展示', () => {
    const { rerender } = render(
      <PaidTaskConfirmationCard
        confirmation={{
          ...paidTaskConfirmationFixture,
          mediaSubmissionDetails: details({ targets: [] }),
        }}
        {...paidCardCallbacks()}
      />,
    )

    expect(screen.getByText('暂无已选媒体')).toBeInTheDocument()
    expect(screen.queryByText('目标平台')).not.toBeInTheDocument()
    expect(screen.queryByText('测试平台甲')).not.toBeInTheDocument()

    rerender(
      <PaidTaskConfirmationCard
        confirmation={paidTaskConfirmationFixture}
        {...paidCardCallbacks()}
      />,
    )
    expect(screen.getByText('目标平台')).toBeInTheDocument()
    expect(screen.getByText('测试平台甲')).toBeInTheDocument()
    expect(screen.getByText('测试平台乙')).toBeInTheDocument()
  })

  it('任务回执继续优先于带投稿明细的付费确认卡', () => {
    render(
      <ChatMessageStream
        messages={[
          {
            ...paidTaskMessageFixture,
            paidTaskConfirmation: {
              ...paidTaskConfirmationFixture,
              mediaSubmissionDetails: articleMediaSubmissionDetailsFixture,
            },
            taskCreatedReceipt: taskCreatedReceiptFixture,
          },
        ]}
        {...messageStreamCallbacks()}
      />,
    )

    expect(screen.getByLabelText(/任务创建成功回执/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/付费任务确认卡/)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/文章媒体投稿明细/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '确认并执行' })).not.toBeInTheDocument()
  })
})

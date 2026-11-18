import {
  CheckCircleFilled,
  EditOutlined,
  EyeOutlined,
  FolderAddOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import { Button, Input, Tag, Typography } from 'antd'
import { useState } from 'react'
import { ArticleMediaRecommendationCard } from '../../articles/components/ArticleMediaRecommendationCard'
import { ArticleBodyConfirmationCard } from '../../articles/components/ArticleBodyConfirmationCard'
import type {
  ArticleBodyConfirmationInput,
  ArticleRecommendedSubmissionSelection,
} from '../../articles/types'
import type { ArticleDraftPreviewView } from '../types'

export interface ArticleDraftPreviewCardProps {
  article: ArticleDraftPreviewView
  onPreviewArticle: (articleId: string) => void
  onContinueEditingArticle: (articleId: string) => void
  onSaveArticleToAssets: (articleId: string) => void
  onConfirmArticleTitle?: (
    articleId: string,
    input: {
      title: string
      source: 'SELECTED' | 'EDITED' | 'CUSTOM'
      candidateIndex?: number
    },
  ) => void
  onRegenerateArticleTitles?: (articleId: string) => void
  onReviseArticleCreativePlan?: (
    articleId: string,
    instruction: string,
  ) => void
  onConfirmArticleCreativePlan?: (articleId: string) => void
  onRegenerateArticleCreativePlan?: (articleId: string) => void
  onConfirmArticleBody?: (
    articleId: string,
    input: ArticleBodyConfirmationInput,
  ) => void
  recommendedSubmissionBusy?: boolean
  recommendedSubmissionBlocked?: boolean
  onConfirmRecommendedSubmission?: (
    selection: ArticleRecommendedSubmissionSelection,
  ) => void
  onRefreshMediaRecommendations?: (articleId: string) => void
}

function formatWordCount(value?: number) {
  return value !== undefined && Number.isSafeInteger(value) && value >= 0
    ? value.toLocaleString('zh-CN')
    : undefined
}

function formatReadingMinutes(value?: number) {
  return value !== undefined && Number.isFinite(value) && value >= 0
    ? value.toLocaleString('zh-CN')
    : undefined
}

export function ArticleDraftPreviewCard({
  article,
  onPreviewArticle,
  onContinueEditingArticle,
  onSaveArticleToAssets,
  onConfirmArticleTitle,
  onRegenerateArticleTitles,
  onReviseArticleCreativePlan,
  onConfirmArticleCreativePlan,
  onRegenerateArticleCreativePlan,
  onConfirmArticleBody,
  recommendedSubmissionBusy,
  recommendedSubmissionBlocked,
  onConfirmRecommendedSubmission,
  onRefreshMediaRecommendations,
}: ArticleDraftPreviewCardProps) {
  const {
    id,
    title,
    versionLabel,
    taskStage,
    workflowStage,
    titleCandidates = [],
    creativePlan,
    coverImageUrl,
    excerpt,
    wordCount,
    estimatedReadingMinutes,
    generatedAt,
    mediaRecommendations,
    mediaRecommendationError,
    imageUnsupported,
    imageCount,
    hasExternalLinks,
    actionsEnabled,
    saveToAssetsEnabled = actionsEnabled,
  } = article
  const [failedCoverUrl, setFailedCoverUrl] = useState<string>()
  const [selectedTitle, setSelectedTitle] = useState('')
  const [selectedCandidateIndex, setSelectedCandidateIndex] = useState<number>()
  const [planInstruction, setPlanInstruction] = useState('')
  const hasCover = Boolean(
    coverImageUrl && coverImageUrl !== failedCoverUrl,
  )
  const formattedWordCount = formatWordCount(wordCount)
  const formattedReadingMinutes = formatReadingMinutes(
    estimatedReadingMinutes,
  )
  const hasMetadata = Boolean(
    formattedWordCount || formattedReadingMinutes || generatedAt,
  )
  const titleSelectionPending =
    taskStage === 'TITLE' && titleCandidates.length > 0
  const planConfirmationPending = taskStage === 'OUTLINE'

  return (
    <section
      className="article-draft-preview"
      aria-label={
        titleSelectionPending
          ? '标题候选待人工确认'
          : planConfirmationPending
            ? `文章创作方案待人工确认：${title}`
            : `文章草稿预览：${title}`
      }
    >
      <header className="article-draft-preview__header">
        <Typography.Text strong>
          {titleSelectionPending
            ? '标题候选（待人工确认）'
            : planConfirmationPending
              ? '文章创作方案（待人工确认）'
              : '文章草稿（可预览）'}
        </Typography.Text>
        <Tag icon={<CheckCircleFilled aria-hidden="true" />}>
          {versionLabel}
        </Tag>
      </header>

      {titleSelectionPending ? (
        <div className="article-draft-preview__title-candidates">
          <Typography.Title level={3}>{title}</Typography.Title>
          <Typography.Paragraph>
            请选择一个标题作为正式标题，确认后再生成文章创作方案。
          </Typography.Paragraph>
          <ol>
            {titleCandidates.map((candidate, index) => (
              <li key={`${index}:${candidate}`}>
                <button
                  type="button"
                  aria-pressed={selectedTitle === candidate}
                  className={selectedTitle === candidate ? 'is-selected' : undefined}
                  disabled={!actionsEnabled}
                  onClick={() => {
                    setSelectedCandidateIndex(index)
                    setSelectedTitle(candidate)
                  }}
                >
                  <span>{index + 1}</span>
                  {candidate}
                </button>
              </li>
            ))}
          </ol>
          <Input
            aria-label="确认或自定义文章标题"
            value={selectedTitle}
            maxLength={300}
            showCount
            placeholder="选择候选后可修改，或直接输入自定义标题"
            disabled={!actionsEnabled}
            onChange={(event) => setSelectedTitle(event.target.value)}
          />
        </div>
      ) : planConfirmationPending ? (
        <div className="article-draft-preview__plan-ready">
          <Typography.Title level={3}>{title}</Typography.Title>
          {creativePlan ? (
            <>
              <dl>
                <div>
                  <dt>一句话摘要</dt>
                  <dd>{creativePlan.summary}</dd>
                </div>
                <div>
                  <dt>核心观点</dt>
                  <dd>{creativePlan.coreMessage}</dd>
                </div>
                <div>
                  <dt>目标读者</dt>
                  <dd>{creativePlan.targetAudience}</dd>
                </div>
                <div>
                  <dt>风格与篇幅</dt>
                  <dd>{creativePlan.tone} · {creativePlan.targetLength}</dd>
                </div>
                {creativePlan.targetPlatform ? (
                  <div>
                    <dt>发布平台</dt>
                    <dd>{creativePlan.targetPlatform}</dd>
                  </div>
                ) : null}
              </dl>
              <Typography.Text strong>段落结构</Typography.Text>
              <ol>
                {creativePlan.sections.map((section) => (
                  <li key={section.key}>
                    <strong>{section.heading}</strong>
                    <span>{section.brief}</span>
                  </li>
                ))}
              </ol>
              {creativePlan.riskNotes.length ? (
                <Typography.Text type="secondary">
                  风险提示：{creativePlan.riskNotes.join('；')}
                </Typography.Text>
              ) : null}
            </>
          ) : (
            <Typography.Paragraph>
              创作方案已经生成。打开文章工作台后可核对摘要、核心观点、目标读者、段落结构、风格和篇幅。
            </Typography.Paragraph>
          )}
          {creativePlan && onReviseArticleCreativePlan ? (
            <div className="article-draft-preview__plan-instruction">
              <Input.TextArea
                aria-label="告诉光禾AI智能体框架如何修改创作方案"
                value={planInstruction}
                maxLength={1000}
                autoSize={{ minRows: 2, maxRows: 4 }}
                placeholder="例如：第二部分不要谈功效，语气年轻一点"
                disabled={!actionsEnabled}
                onChange={(event) => setPlanInstruction(event.target.value)}
              />
              <Button
                disabled={!actionsEnabled || !planInstruction.trim()}
                onClick={() =>
                  onReviseArticleCreativePlan(id, planInstruction.trim())
                }
              >
                提交修改要求
              </Button>
            </div>
          ) : null}
        </div>
      ) : (
        <div
          className={`article-draft-preview__preview${
            hasCover ? '' : ' article-draft-preview__preview--without-cover'
          }`}
        >
          {hasCover ? (
            <img
              className="article-draft-preview__cover"
              src={coverImageUrl}
              alt={`${title}文章封面`}
              width={360}
              height={196}
              loading="lazy"
              onError={() => setFailedCoverUrl(coverImageUrl)}
            />
          ) : null}

          <div className="article-draft-preview__content">
            <Typography.Title level={3}>{title}</Typography.Title>
            {excerpt ? (
              <Typography.Paragraph className="article-draft-preview__excerpt">
                {excerpt}
              </Typography.Paragraph>
            ) : null}
          </div>
        </div>
      )}

      {(mediaRecommendations || mediaRecommendationError) &&
      onConfirmRecommendedSubmission &&
      onRefreshMediaRecommendations ? (
        <ArticleMediaRecommendationCard
          recommendations={mediaRecommendations}
          submitting={recommendedSubmissionBusy}
          submissionBlocked={recommendedSubmissionBlocked}
          imageUnsupported={imageUnsupported}
          imageCount={imageCount}
          hasExternalLinks={hasExternalLinks}
          error={mediaRecommendationError}
          onConfirmSubmission={onConfirmRecommendedSubmission}
          onRefresh={() => onRefreshMediaRecommendations(id)}
          onOpenFullSelection={() => onContinueEditingArticle(id)}
        />
      ) : null}

      {(workflowStage === 'BODY_READY' || workflowStage === 'EDITING') &&
      onConfirmArticleBody ? (
        <ArticleBodyConfirmationCard
          onConfirm={(input) => onConfirmArticleBody(id, input)}
        />
      ) : null}

      {workflowStage === 'READY_TO_SUBMIT' && onConfirmArticleBody ? (
        <details className="article-submission-preferences">
          <summary>调整投稿目标</summary>
          <ArticleBodyConfirmationCard
            key={`${id}:submission-preferences:${mediaRecommendations?.generatedAt ?? 'loading'}`}
            title="重新确认投稿目标"
            initialValue={mediaRecommendations?.strategy}
            onConfirm={(input) => onConfirmArticleBody(id, input)}
          />
        </details>
      ) : null}

      {hasMetadata ? (
        <dl className="article-draft-preview__metadata">
          {formattedWordCount ? (
            <div>
              <dt>字数</dt>
              <dd>{formattedWordCount} 字</dd>
            </div>
          ) : null}
          {formattedReadingMinutes ? (
            <div>
              <dt>预计阅读时间</dt>
              <dd>{formattedReadingMinutes} 分钟</dd>
            </div>
          ) : null}
          {generatedAt ? (
            <div>
              <dt>生成时间</dt>
              <dd>{generatedAt}</dd>
            </div>
          ) : null}
        </dl>
      ) : null}

      {titleSelectionPending ? (
        <div className="article-draft-preview__actions article-draft-preview__actions--title">
          <Button
            icon={<ReloadOutlined aria-hidden="true" />}
            disabled={!actionsEnabled}
            onClick={() => {
              if (onRegenerateArticleTitles) {
                onRegenerateArticleTitles(id)
              } else {
                onContinueEditingArticle(id)
              }
            }}
          >
            重新生成
          </Button>
          <Button
            type="primary"
            disabled={!actionsEnabled || !selectedTitle.trim()}
            onClick={() => {
              if (actionsEnabled && selectedTitle.trim()) {
                if (onConfirmArticleTitle) {
                  const selectedCandidate =
                    selectedCandidateIndex === undefined
                      ? undefined
                      : titleCandidates[selectedCandidateIndex]
                  onConfirmArticleTitle(id, {
                    title: selectedTitle.trim(),
                    source: selectedCandidate
                      ? selectedCandidate === selectedTitle.trim()
                        ? 'SELECTED'
                        : 'EDITED'
                      : 'CUSTOM',
                    candidateIndex:
                      selectedCandidateIndex === undefined
                        ? undefined
                        : selectedCandidateIndex + 1,
                  })
                } else {
                  onContinueEditingArticle(id)
                }
              }
            }}
          >
            确认标题并继续
          </Button>
          <Button
            className="article-draft-preview__open-workspace"
            disabled={!actionsEnabled}
            onClick={() => onContinueEditingArticle(id)}
          >
            打开文章工作台
          </Button>
        </div>
      ) : planConfirmationPending ? (
        <div className="article-draft-preview__actions article-draft-preview__actions--plan">
          {onRegenerateArticleCreativePlan ? (
            <Button
              disabled={!actionsEnabled}
              onClick={() => onRegenerateArticleCreativePlan(id)}
            >
              重新生成方案
            </Button>
          ) : null}
          {onConfirmArticleCreativePlan && creativePlan ? (
            <Button
              type="primary"
              disabled={!actionsEnabled}
              onClick={() => onConfirmArticleCreativePlan(id)}
            >
              确认方案并继续
            </Button>
          ) : null}
          <Button
            className="article-draft-preview__open-workspace"
            disabled={!actionsEnabled}
            onClick={() => onContinueEditingArticle(id)}
          >
            打开文章工作台
          </Button>
        </div>
      ) : (
        <div className="article-draft-preview__actions">
          <Button
            icon={<EyeOutlined aria-hidden="true" />}
            disabled={!actionsEnabled}
            onClick={() => {
              if (actionsEnabled) {
                onPreviewArticle(id)
              }
            }}
          >
            预览文章
          </Button>
          <Button
            icon={<EditOutlined aria-hidden="true" />}
            disabled={!actionsEnabled}
            onClick={() => {
              if (actionsEnabled) {
                onContinueEditingArticle(id)
              }
            }}
          >
            继续编辑
          </Button>
          <Button
            className="article-draft-preview__save"
            icon={<FolderAddOutlined aria-hidden="true" />}
            disabled={!actionsEnabled || !saveToAssetsEnabled}
            onClick={() => {
              if (actionsEnabled && saveToAssetsEnabled) {
                onSaveArticleToAssets(id)
              }
            }}
          >
            保存到资产
          </Button>
        </div>
      )}
    </section>
  )
}

import {
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  FileTextOutlined,
  PlusOutlined,
  SaveOutlined,
  UpOutlined,
} from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Empty,
  Input,
  Modal,
  Pagination,
  Select,
  Tag,
  Typography,
} from 'antd'
import { useEffect, useMemo, useState } from 'react'
import { ErrorState, PageLoading } from '../../../components/GlobalState'
import { PaidTaskConfirmationCard } from '../../chat/components/PaidTaskConfirmationCard'
import { ParallelTaskGroup } from '../../chat/components/ParallelTaskGroup'
import { TaskCreatedReceiptCard } from '../../chat/components/TaskCreatedReceiptCard'
import { TaskExecutionOutcomeGroup } from '../../chat/components/TaskExecutionOutcomeGroup'
import type {
  PaidTaskConfirmationView,
  ParallelTaskGroupView,
  TaskCreatedReceiptView,
  TaskExecutionOutcomeGroupView,
  TaskOutcomeActionType,
} from '../../chat'
import type {
  ArticleAssetOptionView,
  ArticleBodyConfirmationInput,
  ArticleCreativePlanDraft,
  ArticleCreativePlanView,
  ArticleListItem,
  ArticleMediaRecommendationSetView,
  ArticleOption,
  ArticleOutlineDraftItem,
  ArticleStage,
  ArticleTaskFormDraft,
  ArticleTitleCandidateBatchView,
  ArticleTitleSource,
  ArticleRecommendedSubmissionSelection,
  ArticleViewError,
  ArticleWorkflowStage,
  ArticleWorkspaceRecordView,
  SubmissionSnapshotView,
} from '../types'
import {
  ArticleCreativePlanCard,
  ArticleTitleConfirmationCard,
  ArticleWorkflowProgress,
} from './ArticleCreationWorkflow'
import { ArticleMediaRecommendationCard } from './ArticleMediaRecommendationCard'
import { ArticleBodyConfirmationCard } from './ArticleBodyConfirmationCard'
import { ArticleMediaSubmissionPanel } from './ArticleMediaSubmissionPanel'
import type { ArticleMediaSubmissionPanelProps } from './ArticleMediaSubmissionPanel'
import { SafeArticleContent } from './SafeArticleContent'
import { mediaArticleHasImages, mediaArticleHasLinks } from '../live/mediaContentPolicy'
import { articleBlockIds } from '../live/articleAdapters'
import {
  clearArticleEditorRecovery,
  readArticleEditorRecovery,
  saveArticleEditorRecovery,
} from '../live/articleEditorRecovery'
import type { ArticleEditorRecoveryOutlineItem } from '../live/articleEditorRecovery'

const STAGES: readonly { label: string; value: ArticleStage }[] = [
  { label: '生成标题', value: 'TITLE' },
  { label: '生成创作方案', value: 'OUTLINE' },
  { label: '生成正文', value: 'BODY' },
  { label: '局部改写', value: 'REWRITE' },
]

const TONES = [
  { label: '专业可信', value: 'PROFESSIONAL' },
  { label: '轻松自然', value: 'NATURAL' },
  { label: '亲切活泼', value: 'FRIENDLY' },
  { label: '简洁有力', value: 'CONCISE' },
]

const TARGETS = [800, 1200, 1600].map((value) => ({
  label: `约 ${value} 字`,
  value,
}))

const EMPTY_TASK_DRAFT: ArticleTaskFormDraft = {
  mode: 'TITLE',
  brief: '',
  tone: 'PROFESSIONAL',
  targetWordCount: 1200,
  rewriteInstruction: '',
  rewriteBlockIds: [],
  sourceAssetIds: [],
}

const PREPARE_LABELS: Record<ArticleStage, string> = {
  TITLE: '生成标题确认方案',
  OUTLINE: '生成创作方案确认方案',
  BODY: '生成完整正文确认方案',
  REWRITE: '局部改写确认方案',
}

function wordCountForTargetLength(
  targetLength: ArticleCreativePlanDraft['targetLength'],
) {
  return targetLength === 'SHORT' ? 800 : targetLength === 'LONG' ? 1600 : 1200
}

export interface ArticleLiveWorkspaceProps {
  businessSpaces: readonly ArticleOption[]
  products: readonly ArticleOption[]
  articles: readonly ArticleListItem[]
  article?: ArticleWorkspaceRecordView
  selectedArticleId?: string
  newArticleDraft?: { spaceId?: string; productId?: string; title: string }
  onNewArticleDraftChange?: (draft: { spaceId?: string; productId?: string; title: string }) => void
  assets: readonly ArticleAssetOptionView[]
  snapshot?: SubmissionSnapshotView
  articleFilter: {
    keyword: string
    status?: string
    spaceId?: string
  }
  articlePagination: {
    current: number
    pageSize: number
    total: number
  }
  loading?: boolean
  busy?: boolean
  error?: ArticleViewError | null
  paidTaskConfirmation?: PaidTaskConfirmationView
  taskReceipt?: TaskCreatedReceiptView
  taskProgress?: ParallelTaskGroupView
  taskOutcome?: TaskExecutionOutcomeGroupView
  workflowStage?: ArticleWorkflowStage
  titleCandidateBatch?: ArticleTitleCandidateBatchView
  creativePlan?: ArticleCreativePlanView
  titleCandidates?: readonly string[]
  generatedOutline?: readonly ArticleOutlineDraftItem[]
  mediaRecommendations?: ArticleMediaRecommendationSetView
  loadingMediaRecommendations?: boolean
  submittingRecommendedMedia?: boolean
  recommendedSubmissionBlocked?: boolean
  mediaRecommendationError?: ArticleViewError | null
  mediaSubmission?: Omit<
    ArticleMediaSubmissionPanelProps,
    'article' | 'snapshot'
  >
  onSelectArticle: (articleId?: string) => void
  onBusinessSpaceChange: (spaceId?: string) => void
  onArticleFilterChange: (filter: ArticleLiveWorkspaceProps['articleFilter']) => void
  onArticlePageChange: (page: number, pageSize: number) => void
  onCreateArticle: (input: {
    spaceId: string
    productId?: string
    title?: string
  }) => void
  onSaveArticle: (input: { title: string; bodyHtml: string }) => void
  onSaveOutline: (outline: readonly ArticleOutlineDraftItem[]) => void
  onSaveAssets: (assetIds: readonly string[]) => void
  onDeleteArticle: (articleId: string) => void
  onPrepareTask: (draft: ArticleTaskFormDraft) => void
  onConfirmTask: (draftId: string) => void
  onEditTask: () => void
  onUseTitleCandidate: (title: string) => void
  onUseGeneratedOutline: (outline: readonly ArticleOutlineDraftItem[]) => void
  onConfirmTitle?: (input: {
    title: string
    source: ArticleTitleSource
    candidateIndex?: number
  }, nextTask: ArticleTaskFormDraft) => void
  onRegenerateTitle?: (draft: ArticleTaskFormDraft) => void
  onSaveCreativePlan?: (plan: ArticleCreativePlanDraft) => void
  onConfirmCreativePlan?: (
    plan: ArticleCreativePlanDraft,
    nextTask: ArticleTaskFormDraft,
  ) => void
  onRegenerateCreativePlan?: (draft: ArticleTaskFormDraft) => void
  onApplyCreativePlanInstruction?: (instruction: string) => void
  onConfirmBody?: (input: ArticleBodyConfirmationInput) => void
  onConfirmRecommendedSubmission?: (
    selection: ArticleRecommendedSubmissionSelection,
  ) => void
  onRefreshMediaRecommendations?: () => void
  onCreateSnapshot: () => void
  onReloadArticle: () => void
  onViewTask: (taskId: string) => void
  onTaskAction: (taskId: string, type: TaskOutcomeActionType) => void
  onOpenRecharge: (draftId: string) => void
}

function plainTextToHtml(value: string): string {
  const escape = (part: string) =>
    part.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
  return value
    .split(/\n{2,}/)
    .map((paragraph) => `<p>${escape(paragraph).replaceAll('\n', '<br>')}</p>`)
    .join('')
}

function stageEnabled(stage: ArticleStage, article: ArticleWorkspaceRecordView) {
  if (stage === 'TITLE') return true
  if (stage === 'OUTLINE') return Boolean(article.title.trim())
  if (stage === 'BODY') {
    return Boolean(
      article.title.trim() &&
        (article.workflowStage
          ? article.creativePlan?.confirmed
          : article.outline.length),
    )
  }
  return Boolean(article.bodyText.trim())
}

function sameStringList(
  left: readonly string[],
  right: readonly string[],
): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

function sameOutline(
  left: readonly ArticleEditorRecoveryOutlineItem[],
  right: readonly ArticleEditorRecoveryOutlineItem[],
): boolean {
  return (
    left.length === right.length &&
    left.every(
      (item, index) =>
        item.key === right[index]?.key &&
        item.level === right[index]?.level &&
        item.heading === right[index]?.heading,
    )
  )
}

function ArticleEditor({
  article,
  assets,
  busy,
  onSaveArticle,
  onSaveOutline,
  onSaveAssets,
}: Pick<
  ArticleLiveWorkspaceProps,
  | 'article'
  | 'assets'
  | 'busy'
  | 'onSaveArticle'
  | 'onSaveOutline'
  | 'onSaveAssets'
> & { article: ArticleWorkspaceRecordView }) {
  const [title, setTitle] = useState(article.title)
  const [bodyText, setBodyText] = useState(article.bodyText)
  const [outline, setOutline] = useState<ArticleOutlineDraftItem[]>([
    ...article.outline,
  ])
  const [assetIds, setAssetIds] = useState<string[]>(
    article.assets.map((item) => item.assetId),
  )
  const [recoveryDraft, setRecoveryDraft] = useState(() =>
    readArticleEditorRecovery(article.id, article.version),
  )
  const [recoveryDismissed, setRecoveryDismissed] = useState(false)
  const serverAssetIds = useMemo(
    () => article.assets.map((item) => item.assetId),
    [article.assets],
  )
  const titleDirty = title !== article.title
  const bodyDirty = bodyText !== article.bodyText
  const outlineDirty = !sameOutline(outline, article.outline)
  const assetIdsDirty = !sameStringList(assetIds, serverAssetIds)
  const articleDirty = titleDirty || bodyDirty
  const editorDirty = articleDirty || outlineDirty || assetIdsDirty
  const recoveredOutline = recoveryDraft?.outline ?? article.outline
  const recoveredAssetIds = recoveryDraft?.assetIds ?? serverAssetIds
  const recoveryAreas = [
    recoveryDraft?.title !== article.title ? '标题' : undefined,
    recoveryDraft?.bodyText !== article.bodyText ? '正文' : undefined,
    !sameOutline(recoveredOutline, article.outline) ? '创作方案' : undefined,
    !sameStringList(recoveredAssetIds, serverAssetIds) ? '配图' : undefined,
  ].filter((value): value is string => Boolean(value))
  const hasMeaningfulRecovery = Boolean(recoveryDraft && recoveryAreas.length > 0)
  const recoveryTitle =
    recoveryAreas.length === 2 &&
    recoveryAreas[0] === '标题' &&
    recoveryAreas[1] === '正文'
      ? '发现未保存的标题和正文'
      : `发现未保存的${recoveryAreas.join('、')}`

  useEffect(() => {
    if (!editorDirty) {
      if (
        recoveryDraft &&
        recoveryDraft.title === article.title &&
        recoveryDraft.bodyText === article.bodyText &&
        sameOutline(recoveredOutline, article.outline) &&
        sameStringList(recoveredAssetIds, serverAssetIds)
      ) {
        clearArticleEditorRecovery(article.id)
      }
      return
    }
    const timer = window.setTimeout(() => {
      saveArticleEditorRecovery(article.id, {
        baseVersion: article.version,
        title,
        bodyText,
        outline: outline.map(({ key, level, heading }) => ({ key, level, heading })),
        assetIds: [...assetIds],
      })
    }, 350)
    return () => window.clearTimeout(timer)
  }, [
    article.bodyText,
    article.id,
    article.outline,
    article.title,
    article.version,
    bodyText,
    editorDirty,
    recoveryDraft,
    outline,
    assetIds,
    recoveredAssetIds,
    recoveredOutline,
    serverAssetIds,
    title,
  ])

  const recoverDraft = () => {
    if (!recoveryDraft) return
    setTitle(recoveryDraft.title)
    setBodyText(recoveryDraft.bodyText)
    setOutline(
      recoveryDraft.outline
        ? [...recoveryDraft.outline]
        : [...article.outline],
    )
    setAssetIds(
      recoveryDraft.assetIds
        ? [...recoveryDraft.assetIds]
        : [...serverAssetIds],
    )
    setRecoveryDismissed(true)
  }

  const discardRecoveryDraft = () => {
    clearArticleEditorRecovery(article.id)
    setRecoveryDraft(undefined)
    setRecoveryDismissed(true)
  }

  const startManualEdit = () => {
    if (recoveryDraft) {
      clearArticleEditorRecovery(article.id)
      setRecoveryDraft(undefined)
    }
    setRecoveryDismissed(true)
  }

  const previewHtml = bodyDirty ? plainTextToHtml(bodyText) : article.bodyHtml
  return (
    <>
      {hasMeaningfulRecovery && !recoveryDismissed ? (
        <Alert
          className="article-editor-recovery"
          type="info"
          showIcon
          title={recoveryTitle}
          description="这是当前文章同一服务端版本的浏览器恢复副本，不会覆盖服务端内容。"
          action={
            <div className="article-editor-recovery__actions">
              <Button size="small" onClick={discardRecoveryDraft}>
                丢弃
              </Button>
              <Button size="small" type="primary" onClick={recoverDraft}>
                恢复编辑
              </Button>
            </div>
          }
        />
      ) : null}
      <Card
        className="article-editor-card"
        title="文章编辑与预览"
        extra={<Tag>{article.statusLabel} · V{article.version}</Tag>}
      >
        <Input
          className="article-title-input"
          aria-label="文章标题"
          value={title}
          maxLength={300}
          showCount
          onChange={(event) => {
            setTitle(event.target.value)
            startManualEdit()
          }}
        />
        <Input.TextArea
          className="article-body-input"
          aria-label="文章正文"
          value={bodyText}
          maxLength={50000}
          placeholder="正文任务完成后会自动恢复到这里。"
          onChange={(event) => {
            setBodyText(event.target.value)
            startManualEdit()
          }}
        />
        <footer className="article-editor-footer">
          <span>服务端版本 V{article.version} · {article.updatedAt}</span>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={busy}
            disabled={!articleDirty || busy}
            onClick={() => {
              onSaveArticle({
                title: title.trim(),
                bodyHtml: bodyDirty ? plainTextToHtml(bodyText) : article.bodyHtml,
              })
            }}
          >
            保存当前版本
          </Button>
        </footer>
        <SafeArticleContent html={previewHtml} fallback="正文尚未生成" />
      </Card>

      <div className="article-creation-rail">
        <Card className="article-outline-card" title="文章创作方案">
          <div className="article-live-outline">
            {outline.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无创作方案" />
            ) : (
              outline.map((item, index) => (
                <div key={item.key} className="article-live-outline__row">
                  <Select
                    aria-label={`创作方案 ${index + 1} 层级`}
                    value={item.level}
                    options={[1, 2, 3, 4, 5, 6].map((level) => ({
                      label: `H${level}`,
                      value: level,
                    }))}
                    onChange={(level) => {
                      const next = [...outline]
                      next[index] = { ...item, level }
                      setOutline(next)
                      startManualEdit()
                    }}
                  />
                  <Input
                    aria-label={`创作方案 ${index + 1} 标题`}
                    value={item.heading}
                    onChange={(event) => {
                      const next = [...outline]
                      next[index] = { ...item, heading: event.target.value }
                      setOutline(next)
                      startManualEdit()
                    }}
                  />
                  <Button
                    danger
                    aria-label={`删除创作方案 ${index + 1}`}
                    onClick={() => {
                      setOutline(outline.filter((_, itemIndex) => itemIndex !== index))
                      startManualEdit()
                    }}
                  >
                    删除
                  </Button>
                </div>
              ))
            )}
            <div className="article-live-outline__actions">
              <Button
                icon={<PlusOutlined />}
                onClick={() => {
                  setOutline([
                    ...outline,
                    {
                      key: crypto.randomUUID(),
                      level: 2,
                      heading: '',
                    },
                  ])
                  startManualEdit()
                }}
              >
                添加小节
              </Button>
              <Button
                type="primary"
                disabled={busy || outline.some((item) => !item.heading.trim())}
                onClick={() => onSaveOutline(outline)}
              >
                整体保存创作方案
              </Button>
            </div>
          </div>
        </Card>

        <Card title="文章配图" className="article-live-assets">
          {assets.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前经营空间暂无 READY 图片资产" />
          ) : (
            <Checkbox.Group
              aria-label="文章配图资产"
              value={assetIds}
              options={assets.map((asset) => ({
                label: asset.name,
                value: asset.id,
              }))}
              onChange={(values) => {
                const selected = values.map(String)
                const selectedSet = new Set(selected)
                setAssetIds([
                  ...assetIds.filter((assetId) => selectedSet.has(assetId)),
                  ...selected.filter((assetId) => !assetIds.includes(assetId)),
                ].slice(0, 10))
                startManualEdit()
              }}
            />
          )}
          {assetIds.length ? (
            <ol className="article-live-assets__order" aria-label="文章配图顺序">
              {assetIds.map((assetId, index) => {
                const asset = assets.find((item) => item.id === assetId)
                return (
                  <li key={assetId}>
                    <span>{asset?.name ?? assetId}</span>
                    <Button
                      size="small"
                      icon={<UpOutlined />}
                      aria-label={`上移配图 ${asset?.name ?? assetId}`}
                      disabled={index === 0 || busy}
                      onClick={() => {
                        const next = [...assetIds]
                        ;[next[index - 1], next[index]] = [next[index], next[index - 1]]
                        setAssetIds(next)
                        startManualEdit()
                      }}
                    />
                    <Button
                      size="small"
                      icon={<DownOutlined />}
                      aria-label={`下移配图 ${asset?.name ?? assetId}`}
                      disabled={index === assetIds.length - 1 || busy}
                      onClick={() => {
                        const next = [...assetIds]
                        ;[next[index], next[index + 1]] = [next[index + 1], next[index]]
                        setAssetIds(next)
                        startManualEdit()
                      }}
                    />
                  </li>
                )
              })}
            </ol>
          ) : null}
          <Button
            type="primary"
            disabled={busy}
            onClick={() => onSaveAssets(assetIds)}
          >
            保存配图顺序
          </Button>
        </Card>
      </div>
    </>
  )
}

export function ArticleLiveWorkspace(props: ArticleLiveWorkspaceProps) {
  const {
    businessSpaces,
    products,
    articles,
    article,
    assets,
    snapshot,
    loading,
    busy,
    error,
  } = props
  const [view, setView] = useState<'CREATION' | 'SUBMISSION' | 'ARTICLES'>('CREATION')
  const [localNewArticleDraft, setLocalNewArticleDraft] = useState<{
    spaceId?: string; productId?: string; title: string
  }>({ spaceId: article?.spaceId, productId: article?.productId, title: '' })
  const newArticleDraft = props.newArticleDraft ?? localNewArticleDraft
  const updateNewArticleDraft = (draft: typeof newArticleDraft) => {
    if (props.onNewArticleDraftChange) props.onNewArticleDraftChange(draft)
    else setLocalNewArticleDraft(draft)
  }
  const { spaceId, productId, title: newTitle } = newArticleDraft
  const [taskDraftState, setTaskDraftState] = useState<{
    articleId?: string
    draft: ArticleTaskFormDraft
  }>({ articleId: article?.id, draft: EMPTY_TASK_DRAFT })
  const taskDraft =
    taskDraftState.articleId === article?.id
      ? taskDraftState.draft
      : EMPTY_TASK_DRAFT
  const setTaskDraft = (draft: ArticleTaskFormDraft) =>
    setTaskDraftState({ articleId: article?.id, draft })
  const [deleteTarget, setDeleteTarget] = useState<string>()

  const guidedMode: ArticleStage | undefined = props.workflowStage
    ? props.workflowStage.startsWith('TITLE')
      ? 'TITLE'
      : props.workflowStage.startsWith('PLAN')
        ? 'OUTLINE'
        : ['BODY_READY_TO_GENERATE', 'BODY_GENERATING'].includes(
              props.workflowStage,
            )
          ? 'BODY'
          : 'REWRITE'
    : undefined
  const visibleTaskDraft = guidedMode
    ? { ...taskDraft, mode: guidedMode }
    : taskDraft
  const generationInProgress = Boolean(
    props.workflowStage &&
      ['TITLE_GENERATING', 'PLAN_GENERATING', 'BODY_GENERATING'].includes(
        props.workflowStage,
      ),
  )
  const bodyEditable = Boolean(
    !props.workflowStage ||
      ['BODY_READY', 'EDITING', 'READY_TO_SUBMIT'].includes(
        props.workflowStage,
      ),
  )
  const titleCandidateBatch =
    props.titleCandidateBatch ??
    (props.titleCandidates?.length
      ? {
          version: article?.version ?? 1,
          candidates: props.titleCandidates,
        }
      : undefined)

  const readyAssets = useMemo(
    () => assets.filter((asset) => asset.status === 'READY' && asset.contentType.startsWith('image/')),
    [assets],
  )
  const rewriteBlockIds = useMemo(
    () => (article ? articleBlockIds(article.bodyHtml) : []),
    [article],
  )

  const taskCanPrepare = Boolean(
    article &&
      visibleTaskDraft.brief.trim() &&
      !generationInProgress &&
      stageEnabled(visibleTaskDraft.mode, article) &&
      (visibleTaskDraft.mode !== 'REWRITE' ||
        (visibleTaskDraft.rewriteInstruction.trim() &&
          visibleTaskDraft.rewriteBlockIds.length > 0 &&
          visibleTaskDraft.rewriteBlockIds.every((blockId) =>
            rewriteBlockIds.includes(blockId),
          ))),
  )

  return (
    <section className="article-workspace" aria-labelledby="article-workspace-title">
      <header className="article-workspace-heading">
        <div>
          <Typography.Title id="article-workspace-title" level={1}>文章与投稿</Typography.Title>
          <Typography.Text type="secondary">文章版本、生成任务和投稿快照均以页面最新数据为准</Typography.Text>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => {
          updateNewArticleDraft({ spaceId: article?.spaceId ?? spaceId, title: '' })
          props.onSelectArticle(undefined)
          setView('CREATION')
        }}>
          新建文章
        </Button>
      </header>

      <nav className="article-workspace-tabs" aria-label="文章与投稿模块">
        {[
          ['CREATION', '文章创作'],
          ['SUBMISSION', '媒体投稿'],
          ['ARTICLES', '我的文章'],
        ].map(([value, label]) => (
          <button
            type="button"
            key={value}
            className={view === value ? 'is-active' : undefined}
            aria-current={view === value ? 'page' : undefined}
            onClick={() => setView(value as typeof view)}
          >
            {label}
          </button>
        ))}
      </nav>

      {loading ? <PageLoading label="正在加载文章工作区…" /> : null}

      {error ? (
        <>
          <ErrorState message={error.message} traceId={error.traceId} onRetry={props.onReloadArticle} />
          {error.conflict ? (
            <Alert
              type="warning"
              showIcon
              title="服务端文章版本已更新"
              description="当前输入仍保留在页面中。重新加载服务端版本后，请核对再提交。"
              action={<Button onClick={props.onReloadArticle}>重新加载服务端版本</Button>}
            />
          ) : null}
        </>
      ) : null}

      {view === 'CREATION' ? (
        <>
          {!article && !props.selectedArticleId ? <div className="article-live-create-bar">
            <Select
              aria-label="经营空间"
              value={spaceId}
              options={[...businessSpaces]}
              placeholder="选择经营空间"
              onChange={(value) => {
                if (value !== spaceId) updateNewArticleDraft({ ...newArticleDraft, spaceId: value, productId: undefined })
                props.onBusinessSpaceChange(value)
              }}
            />
            <Select
              allowClear
              aria-label="商品"
              value={productId}
              options={[...products]}
              placeholder="选择商品（选填）"
              disabled={!spaceId}
              onChange={(productId) => updateNewArticleDraft({ ...newArticleDraft, productId })}
            />
            <Input
              aria-label="新文章标题"
              value={newTitle}
              maxLength={300}
              placeholder="新文章标题（可稍后生成）"
              onChange={(event) => updateNewArticleDraft({ ...newArticleDraft, title: event.target.value })}
            />
            <Button
              type="primary"
              disabled={!spaceId || busy}
              onClick={() =>
                spaceId && props.onCreateArticle({
                  spaceId,
                  productId,
                  title: newTitle.trim() || undefined,
                })
              }
            >
              创建空文章
            </Button>
          </div> : null}

          {article ? (
            <>
              {props.workflowStage ? (
                <ArticleWorkflowProgress stage={props.workflowStage} />
              ) : null}
              <div className="article-live-task-grid">
                <Card title="生成阶段" className="article-brief-card">
                  <Select
                    aria-label="文章生成阶段"
                    value={visibleTaskDraft.mode}
                    options={[...STAGES]}
                    disabled={Boolean(guidedMode)}
                    onChange={(mode) => setTaskDraft({ ...taskDraft, mode })}
                  />
                  <Input.TextArea
                    aria-label="文章创作要求"
                    value={visibleTaskDraft.brief}
                    maxLength={4000}
                    showCount
                    placeholder="说明文章目标、受众和重点"
                    onChange={(event) => setTaskDraft({ ...taskDraft, brief: event.target.value })}
                  />
                  <Select
                    aria-label="表达语气"
                    value={visibleTaskDraft.tone}
                    options={TONES}
                    onChange={(tone) => setTaskDraft({ ...taskDraft, tone })}
                  />
                  <Select
                    aria-label="目标字数"
                    value={visibleTaskDraft.targetWordCount}
                    options={TARGETS}
                    onChange={(targetWordCount) => setTaskDraft({ ...taskDraft, targetWordCount })}
                  />
                  {visibleTaskDraft.mode === 'REWRITE' ? (
                    <>
                      <Input.TextArea
                        aria-label="改写要求"
                        value={visibleTaskDraft.rewriteInstruction}
                        maxLength={4000}
                        placeholder="说明需要如何调整正文"
                        onChange={(event) => setTaskDraft({ ...taskDraft, rewriteInstruction: event.target.value })}
                      />
                      {rewriteBlockIds.length ? (
                        <Checkbox.Group
                          aria-label="选择改写内容块"
                          value={[...visibleTaskDraft.rewriteBlockIds]}
                          options={rewriteBlockIds.map((blockId) => ({
                            label: blockId,
                            value: blockId,
                          }))}
                          onChange={(values) =>
                            setTaskDraft({
                              ...taskDraft,
                              rewriteBlockIds: values.map(String),
                            })
                          }
                        />
                      ) : (
                        <Typography.Text type="secondary">
                          当前正文没有可授权的改写内容块。
                        </Typography.Text>
                      )}
                    </>
                  ) : null}
                  <Button
                    type="primary"
                    icon={<EditOutlined />}
                    disabled={!taskCanPrepare || busy}
                    loading={busy}
                    onClick={() =>
                      props.onPrepareTask({
                        ...visibleTaskDraft,
                        sourceAssetIds: article.assets.map((item) => item.assetId),
                      })
                    }
                  >
                    {PREPARE_LABELS[visibleTaskDraft.mode]}
                  </Button>
                  {!stageEnabled(visibleTaskDraft.mode, article) ? (
                    <Typography.Text type="secondary">请先完成上一阶段并保存当前版本。</Typography.Text>
                  ) : null}
                </Card>
                <div className="article-live-task-state">
                  {props.paidTaskConfirmation ? (
                    <PaidTaskConfirmationCard
                      confirmation={props.paidTaskConfirmation}
                      onEdit={props.onEditTask}
                      onConfirm={props.onConfirmTask}
                      onOpenRecharge={props.onOpenRecharge}
                      onPrepareReestimate={props.onEditTask}
                      onOpenPaidTaskReferenceAsset={() => undefined}
                      onAddPaidTaskReferenceAsset={() => undefined}
                    />
                  ) : null}
                  {props.taskReceipt ? <TaskCreatedReceiptCard receipt={props.taskReceipt} onViewTask={props.onViewTask} /> : null}
                  {props.taskProgress ? <ParallelTaskGroup group={props.taskProgress} onViewTask={props.onViewTask} /> : null}
                  {props.taskOutcome ? <TaskExecutionOutcomeGroup group={props.taskOutcome} onAction={props.onTaskAction} /> : null}
                  {titleCandidateBatch &&
                  (!props.workflowStage ||
                    props.workflowStage === 'TITLE_AWAITING_CONFIRMATION') ? (
                    <ArticleTitleConfirmationCard
                      key={`${article.id}:titles:${titleCandidateBatch.version}`}
                      batch={titleCandidateBatch}
                      currentTitle={article.title}
                      busy={busy}
                      onConfirm={(input) =>
                        props.onConfirmTitle
                          ? props.onConfirmTitle(input, {
                              ...visibleTaskDraft,
                              mode: 'OUTLINE',
                              sourceAssetIds: article.assets.map(
                                (item) => item.assetId,
                              ),
                            })
                          : props.onUseTitleCandidate(input.title)
                      }
                      onRegenerate={() => {
                        if (props.onRegenerateTitle) {
                          props.onRegenerateTitle({
                            ...visibleTaskDraft,
                            mode: 'TITLE',
                            sourceAssetIds: article.assets.map(
                              (item) => item.assetId,
                            ),
                          })
                        } else if (taskCanPrepare) {
                          props.onPrepareTask({
                            ...visibleTaskDraft,
                            mode: 'TITLE',
                            sourceAssetIds: article.assets.map(
                              (item) => item.assetId,
                            ),
                          })
                        }
                      }}
                    />
                  ) : null}
                  {props.creativePlan &&
                  !props.creativePlan.confirmed &&
                  (!props.workflowStage ||
                    props.workflowStage === 'PLAN_AWAITING_CONFIRMATION') &&
                  props.onSaveCreativePlan &&
                  props.onConfirmCreativePlan &&
                  props.onRegenerateCreativePlan ? (
                    <ArticleCreativePlanCard
                      key={`${article.id}:plan:${props.creativePlan.version}`}
                      plan={props.creativePlan}
                      busy={busy}
                      onSave={props.onSaveCreativePlan}
                      onConfirm={(plan) =>
                        props.onConfirmCreativePlan?.(plan, {
                          ...visibleTaskDraft,
                          mode: 'BODY',
                          tone: plan.tone,
                          targetWordCount: wordCountForTargetLength(
                            plan.targetLength,
                          ),
                          sourceAssetIds: article.assets.map(
                            (item) => item.assetId,
                          ),
                        })
                      }
                      onRegenerate={() =>
                        props.onRegenerateCreativePlan?.({
                          ...visibleTaskDraft,
                          mode: 'OUTLINE',
                          sourceAssetIds: article.assets.map(
                            (item) => item.assetId,
                          ),
                        })
                      }
                      onApplyNaturalLanguageRevision={
                        props.onApplyCreativePlanInstruction
                      }
                    />
                  ) : null}
                  {props.generatedOutline?.length ? (
                    <Card title="任务生成的段落结构">
                      <ol>{props.generatedOutline.map((item) => <li key={item.key}>{item.heading}</li>)}</ol>
                      <Button type="primary" onClick={() => props.onUseGeneratedOutline(props.generatedOutline ?? [])}>保存候选段落结构</Button>
                    </Card>
                  ) : null}
                </div>
              </div>
              {bodyEditable ? (
                <>
                  <div className="article-creation-layout article-creation-layout--live">
                    <ArticleEditor
                      key={`${article.id}:${article.version}`}
                      article={article}
                      assets={readyAssets}
                      busy={busy}
                      onSaveArticle={props.onSaveArticle}
                      onSaveOutline={props.onSaveOutline}
                      onSaveAssets={props.onSaveAssets}
                    />
                  </div>
                  {props.workflowStage &&
                  ['BODY_READY', 'EDITING'].includes(props.workflowStage) &&
                  article.bodyText.trim() &&
                  props.onConfirmBody ? (
                    <ArticleBodyConfirmationCard
                      key={`${article.id}:${article.version}:body-confirmation`}
                      busy={busy}
                      onConfirm={props.onConfirmBody}
                    />
                  ) : null}
                  {props.workflowStage &&
                  props.workflowStage === 'READY_TO_SUBMIT' &&
                  article.bodyText.trim() &&
                  props.onConfirmBody ? (
                    <details className="article-submission-preferences">
                      <summary>调整投稿目标</summary>
                      <ArticleBodyConfirmationCard
                        key={`${article.id}:${article.version}:submission-preferences:${props.mediaRecommendations?.generatedAt ?? 'loading'}`}
                        title="重新确认投稿目标"
                        busy={busy}
                        initialValue={props.mediaRecommendations?.strategy}
                        onConfirm={props.onConfirmBody}
                      />
                    </details>
                  ) : null}
                  {props.workflowStage &&
                  props.workflowStage === 'READY_TO_SUBMIT' &&
                  article.bodyText.trim() &&
                  props.onConfirmRecommendedSubmission &&
                  props.onRefreshMediaRecommendations ? (
                    <ArticleMediaRecommendationCard
                      recommendations={props.mediaRecommendations}
                      imageCount={article.assets.length}
                      imageUnsupported={mediaArticleHasImages({
                        bodyHtml: article.bodyHtml,
                        assetCount: article.assets.length,
                      })}
                      hasExternalLinks={mediaArticleHasLinks(article.bodyHtml)}
                      loading={props.loadingMediaRecommendations}
                      submitting={props.submittingRecommendedMedia}
                      submissionBlocked={props.recommendedSubmissionBlocked}
                      error={props.mediaRecommendationError}
                      onConfirmSubmission={
                        props.onConfirmRecommendedSubmission
                      }
                      onRefresh={props.onRefreshMediaRecommendations}
                      onOpenFullSelection={() => setView('SUBMISSION')}
                    />
                  ) : null}
                </>
              ) : (
                <Card className="article-body-locked">
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={
                      props.workflowStage === 'BODY_GENERATING'
                        ? '完整正文正在生成，完成后可在这里预览和编辑'
                        : '确认标题和文章创作方案后，才会生成完整正文'
                    }
                  />
                </Card>
              )}
            </>
          ) : (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="请选择已有文章，或先创建一个空文章"
            />
          )}
        </>
      ) : null}

      {view === 'SUBMISSION' ? (
        props.mediaSubmission ? (
          <ArticleMediaSubmissionPanel
            {...props.mediaSubmission}
            article={article}
            snapshot={snapshot}
          />
        ) : (
          <Card className="submission-confirm-card" title="投稿快照">
            <Alert
              type="info"
              showIcon
              title="当前只开放不可变文章快照"
            />
            {article ? (
              <Button type="primary" disabled={busy} onClick={props.onCreateSnapshot}>
                创建不可变投稿快照
              </Button>
            ) : <Empty description="请先选择文章" />}
            {snapshot ? (
              <Card size="small" title="最近创建的快照">
                <p>{snapshot.title} · {snapshot.versionLabel}</p>
                <time>{snapshot.createdAt}</time>
                <SafeArticleContent html={snapshot.bodyHtml} fallback={snapshot.bodyText || '暂无正文'} />
              </Card>
            ) : null}
          </Card>
        )
      ) : null}

      {view === 'ARTICLES' ? (
        <Card className="article-list-card" title="我的文章">
          <div className="article-live-list-filter">
            <Select
              allowClear
              aria-label="按经营空间筛选文章"
              value={props.articleFilter.spaceId}
              options={[...businessSpaces]}
              placeholder="全部经营空间"
              onChange={(spaceId) =>
                props.onArticleFilterChange({
                  ...props.articleFilter,
                  spaceId,
                })
              }
            />
            <Select
              allowClear
              aria-label="按状态筛选文章"
              value={props.articleFilter.status}
              placeholder="全部状态"
              options={[
                ['DRAFT', '草稿'],
                ['OUTLINING', '创作方案生成中'],
                ['BODY_QUEUED', '正文排队'],
                ['BODY_GENERATING', '正文生成中'],
                ['COMPLETED', '已完成'],
                ['FAILED', '失败'],
                ['ARCHIVED', '已归档'],
              ].map(([value, label]) => ({ value, label }))}
              onChange={(status) =>
                props.onArticleFilterChange({
                  ...props.articleFilter,
                  status,
                })
              }
            />
            <Input.Search
              aria-label="搜索文章"
              value={props.articleFilter.keyword}
              allowClear
              placeholder="搜索标题"
              onChange={(event) =>
                props.onArticleFilterChange({
                  ...props.articleFilter,
                  keyword: event.target.value,
                })
              }
            />
          </div>
          {articles.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无文章" />
          ) : (
            <ul>
              {articles.map((item) => (
                <li key={item.id}>
                  <div className="article-list-card__icon"><FileTextOutlined /></div>
                  <div className="article-list-card__copy"><strong>{item.title}</strong></div>
                  <Tag>{item.statusLabel ?? item.status}</Tag>
                  <span>{item.versionLabel}</span>
                  <span>—</span>
                  <time>{item.updatedAt}</time>
                  <div className="article-live-list-actions">
                    <Button size="small" onClick={() => { props.onSelectArticle(item.id); setView('CREATION') }}>继续编辑</Button>
                    <Button size="small" danger icon={<DeleteOutlined />} onClick={() => setDeleteTarget(item.id)} aria-label={`删除文章 ${item.title}`} />
                  </div>
                </li>
              ))}
            </ul>
          )}
          <Pagination
            current={props.articlePagination.current}
            pageSize={props.articlePagination.pageSize}
            total={props.articlePagination.total}
            showSizeChanger
            onChange={props.onArticlePageChange}
          />
        </Card>
      ) : null}

      <Modal
        open={Boolean(deleteTarget)}
        title="确认删除文章？"
        okText="删除"
        cancelText="取消"
        okButtonProps={{ danger: true }}
        onCancel={() => setDeleteTarget(undefined)}
        onOk={() => {
          if (deleteTarget) props.onDeleteArticle(deleteTarget)
          setDeleteTarget(undefined)
        }}
      >
        只能删除未被任务、资产或投稿快照引用的普通草稿。
      </Modal>
    </section>
  )
}

import {
  ArrowRightOutlined,
  BarsOutlined,
  BoldOutlined,
  CheckCircleFilled,
  ClockCircleOutlined,
  EditOutlined,
  FileDoneOutlined,
  FileTextOutlined,
  FontSizeOutlined,
  GlobalOutlined,
  LinkOutlined,
  OrderedListOutlined,
  PlusOutlined,
  SearchOutlined,
  StarFilled,
  UnorderedListOutlined,
} from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  Modal,
  Select,
  Tag,
  Tooltip,
  Typography,
} from 'antd'
import { useState } from 'react'
import type {
  ArticleCreationDraft,
  ArticleListItem,
  ArticleOption,
  ArticleStatus,
  ArticleSubmissionDraft,
  ArticleWorkspaceView,
  MediaResourceItem,
  SubmissionListItem,
  SubmissionStatus,
} from '../types'
import { ArticleLiveWorkspace } from './ArticleLiveWorkspace'
import type { ArticleLiveWorkspaceProps } from './ArticleLiveWorkspace'

const PURPOSE_OPTIONS: ArticleOption[] = [
  { label: '品牌种草', value: 'BRAND_SEEDING' },
  { label: '商品推广', value: 'PRODUCT_PROMOTION' },
  { label: '知识科普', value: 'KNOWLEDGE' },
  { label: '活动宣传', value: 'CAMPAIGN' },
]

const CHANNEL_OPTIONS: ArticleOption[] = [
  { label: '微信公众号', value: 'WECHAT' },
  { label: '小红书', value: 'XIAOHONGSHU' },
  { label: '知乎', value: 'ZHIHU' },
  { label: '品牌官网', value: 'OFFICIAL_SITE' },
]

const TONE_OPTIONS: ArticleOption[] = [
  { label: '专业可信', value: 'PROFESSIONAL' },
  { label: '轻松自然', value: 'NATURAL' },
  { label: '亲切活泼', value: 'FRIENDLY' },
  { label: '简洁有力', value: 'CONCISE' },
]

const WORD_COUNT_OPTIONS = [800, 1200, 1600, 2000].map((value) => ({
  label: `约 ${value} 字`,
  value,
}))

const ARTICLE_STATUS: Record<ArticleStatus, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: 'default' },
  OUTLINING: { label: '创作方案生成中', color: 'processing' },
  QUEUED: { label: '正文排队', color: 'blue' },
  GENERATING: { label: '正文生成中', color: 'processing' },
  BODY_QUEUED: { label: '正文排队', color: 'blue' },
  BODY_GENERATING: { label: '正文生成中', color: 'processing' },
  COMPLETED: { label: '已完成', color: 'success' },
  FAILED: { label: '失败', color: 'error' },
  ARCHIVED: { label: '已归档', color: 'default' },
}

const SUBMISSION_STATUS: Record<
  SubmissionStatus,
  { label: string; color: string }
> = {
  QUEUED: { label: '待提交', color: 'default' },
  SUBMITTED: { label: '已提交', color: 'blue' },
  PROCESSING: { label: '处理中', color: 'processing' },
  PUBLISHED: { label: '已发布', color: 'success' },
  REJECTED: { label: '退稿', color: 'error' },
  RECONCILING: { label: '异常待核对', color: 'warning' },
}

const WORKSPACE_VIEWS: readonly {
  value: ArticleWorkspaceView
  label: string
}[] = [
  { value: 'CREATION', label: '文章创作' },
  { value: 'SUBMISSION', label: '媒体投稿' },
  { value: 'ARTICLES', label: '我的文章' },
]

export interface ArticleSubmissionWorkspaceProps {
  businessSpaces: readonly ArticleOption[]
  products: readonly ArticleOption[]
  articles: readonly ArticleListItem[]
  mediaResources: readonly MediaResourceItem[]
  submissions: readonly SubmissionListItem[]
  estimatedCreationCredits?: number
  creationEnabled?: boolean
  submissionEnabled?: boolean
  onCreateArticle?: (draft: ArticleCreationDraft) => void
  onConfirmSubmission?: (draft: ArticleSubmissionDraft) => void
  live?: ArticleLiveWorkspaceProps
}

interface ArticleSubmissionFormState {
  articleId?: string
  mediaIds: string[]
  note: string
  searchValue: string
  mediaType: string
}

interface ArticleSubmissionConfirmation extends ArticleSubmissionDraft {
  articleTitle: string
  mediaCount: number
  totalPoints: number
  noteSummary: string
}

const createInitialArticleDraft = (): ArticleCreationDraft => ({
  purpose: PURPOSE_OPTIONS[0].value,
  channel: CHANNEL_OPTIONS[0].value,
  tone: TONE_OPTIONS[0].value,
  targetWordCount: 1200,
  keywords: [],
  title: '',
  bodyText: '',
})

const createInitialSubmissionForm = (): ArticleSubmissionFormState => ({
  mediaIds: [],
  note: '',
  searchValue: '',
  mediaType: 'ALL',
})

function hasUnsavedArticleDraft(
  draft: ArticleCreationDraft,
  keywordInput: string,
): boolean {
  const initial = createInitialArticleDraft()
  return Boolean(
    draft.businessSpaceId ||
      draft.productId ||
      draft.purpose !== initial.purpose ||
      draft.channel !== initial.channel ||
      draft.tone !== initial.tone ||
      draft.targetWordCount !== initial.targetWordCount ||
      draft.keywords.length > 0 ||
      draft.title.trim() ||
      draft.bodyText.trim() ||
      keywordInput.trim(),
  )
}

function safePublishedUrl(value?: string): string | undefined {
  if (!value?.trim()) return undefined
  try {
    const parsed = new URL(value)
    return parsed.protocol === 'https:' ? parsed.href : undefined
  } catch {
    return undefined
  }
}

function ArticleWorkflow() {
  const steps = [
    { label: '创作设置', description: '填写目标与渠道' },
    { label: '标题与创作方案', description: '选择标题并确认内容方向与结构' },
    { label: '正文编辑', description: '完善内容与配图' },
    { label: '媒体投稿', description: '核价后确认提交' },
  ]

  return (
    <ol className="article-workflow" aria-label="文章创作流程">
      {steps.map((step, index) => (
        <li key={step.label} className={index === 0 ? 'is-current' : undefined}>
          <span>{index + 1}</span>
          <div>
            <strong>{step.label}</strong>
            <small>{step.description}</small>
          </div>
        </li>
      ))}
    </ol>
  )
}

function EditorToolbar() {
  const tools = [
    { label: '标题', icon: <FontSizeOutlined /> },
    { label: '加粗', icon: <BoldOutlined /> },
    { label: '无序列表', icon: <UnorderedListOutlined /> },
    { label: '有序列表', icon: <OrderedListOutlined /> },
    { label: '插入链接', icon: <LinkOutlined /> },
  ]

  return (
    <div className="article-editor-toolbar" aria-label="富文本工具栏">
      {tools.map((tool) => (
        <Tooltip key={tool.label} title="正文编辑能力将在文章接口接入后开放">
          <Button
            type="text"
            size="small"
            icon={tool.icon}
            disabled
            aria-label={tool.label}
          />
        </Tooltip>
      ))}
    </div>
  )
}

function ArticleChecks({
  title,
  bodyText,
  keywords,
  targetWordCount,
}: {
  title: string
  bodyText: string
  keywords: readonly string[]
  targetWordCount: number
}) {
  const bodyLength = bodyText.trim().length

  return (
    <Card className="article-check-card" title="文章检查">
      <dl>
        <div>
          <dt>标题</dt>
          <dd className={title.trim() ? 'is-ready' : undefined}>
            {title.trim() ? `${title.trim().length} 字` : '待填写'}
          </dd>
        </div>
        <div>
          <dt>正文</dt>
          <dd className={bodyLength ? 'is-ready' : undefined}>
            {bodyLength ? `${bodyLength} / ${targetWordCount} 字` : '待生成'}
          </dd>
        </div>
        <div>
          <dt>关键词</dt>
          <dd className={keywords.length ? 'is-ready' : undefined}>
            {keywords.length ? `${keywords.length} 个` : '未添加'}
          </dd>
        </div>
        <div>
          <dt>配图</dt>
          <dd>0 / 10 张</dd>
        </div>
      </dl>
      <Alert
        type="info"
        showIcon
        title="内容安全检查待接入"
        description="正式保存和投稿前将校验正文、图片与外链。"
      />
    </Card>
  )
}

interface CreationPanelProps
  extends Pick<
    ArticleSubmissionWorkspaceProps,
    | 'businessSpaces'
    | 'products'
    | 'estimatedCreationCredits'
    | 'creationEnabled'
    | 'onCreateArticle'
  > {
  draft: ArticleCreationDraft
  keywordInput: string
  generationPrepared: boolean
  onDraftChange: (patch: Partial<ArticleCreationDraft>) => void
  onKeywordInputChange: (value: string) => void
  onGenerationPrepared: () => void
}

function CreationPanel({
  businessSpaces,
  products,
  estimatedCreationCredits,
  creationEnabled,
  onCreateArticle,
  draft,
  keywordInput,
  generationPrepared,
  onDraftChange,
  onKeywordInputChange,
  onGenerationPrepared,
}: CreationPanelProps) {
  const addKeyword = () => {
    const value = keywordInput.trim()
    if (
      !value ||
      draft.keywords.includes(value) ||
      draft.keywords.length >= 8
    ) {
      return
    }
    onDraftChange({ keywords: [...draft.keywords, value] })
    onKeywordInputChange('')
  }

  return (
    <>
      <ArticleWorkflow />
      <div className="article-creation-layout">
        <Card className="article-brief-card" title="创作设置">
          <div className="article-form-field article-form-field--double">
            <label>经营资料</label>
            <div>
              <Select
                aria-label="经营空间"
                value={draft.businessSpaceId}
                options={[...businessSpaces]}
                placeholder="选择经营空间（选填）"
                disabled={businessSpaces.length === 0}
                onChange={(value) => {
                  onDraftChange({
                    businessSpaceId: value,
                    productId: undefined,
                  })
                }}
              />
              <Select
                aria-label="商品"
                value={draft.productId}
                options={[...products]}
                placeholder="选择商品（选填）"
                disabled={products.length === 0}
                onChange={(value) => onDraftChange({ productId: value })}
              />
            </div>
            <small>可不绑定；选择后将使用已授权的结构化经营资料。</small>
          </div>

          <div className="article-parameter-grid">
            <div className="article-form-field">
              <label htmlFor="article-purpose">创作目的</label>
              <Select
                id="article-purpose"
                aria-label="创作目的"
                value={draft.purpose}
                options={PURPOSE_OPTIONS}
                onChange={(value) => onDraftChange({ purpose: value })}
              />
            </div>
            <div className="article-form-field">
              <label htmlFor="article-channel">发布渠道</label>
              <Select
                id="article-channel"
                aria-label="发布渠道"
                value={draft.channel}
                options={CHANNEL_OPTIONS}
                onChange={(value) => onDraftChange({ channel: value })}
              />
            </div>
            <div className="article-form-field">
              <label htmlFor="article-tone">表达语气</label>
              <Select
                id="article-tone"
                aria-label="表达语气"
                value={draft.tone}
                options={TONE_OPTIONS}
                onChange={(value) => onDraftChange({ tone: value })}
              />
            </div>
            <div className="article-form-field">
              <label htmlFor="article-word-count">目标字数</label>
              <Select
                id="article-word-count"
                aria-label="目标字数"
                value={draft.targetWordCount}
                options={WORD_COUNT_OPTIONS}
                onChange={(value) =>
                  onDraftChange({ targetWordCount: value })
                }
              />
            </div>
          </div>

          <div className="article-form-field article-form-field--stacked">
            <label htmlFor="article-keyword">核心关键词（选填，最多 8 个）</label>
            <Input.Search
              id="article-keyword"
              aria-label="核心关键词"
              value={keywordInput}
              maxLength={20}
              placeholder="输入关键词后添加"
              enterButton="添加关键词"
              onChange={(event) => onKeywordInputChange(event.target.value)}
              onSearch={addKeyword}
            />
            <div className="article-keyword-list" aria-label="已添加关键词">
              {draft.keywords.length === 0 ? (
                <Typography.Text type="secondary">暂未添加关键词</Typography.Text>
              ) : (
                draft.keywords.map((keyword) => (
                  <Tag
                    key={keyword}
                    closable
                    onClose={() => {
                      onDraftChange({
                        keywords: draft.keywords.filter(
                          (item) => item !== keyword,
                        ),
                      })
                    }}
                  >
                    {keyword}
                  </Tag>
                ))
              )}
            </div>
          </div>

          <div className="article-generation-action">
            <span>
              预计消耗
              <strong>
                <StarFilled /> {estimatedCreationCredits ?? '—'} 积分
              </strong>
            </span>
            <Button
              type="primary"
              icon={<EditOutlined />}
              aria-label="生成标题与创作方案"
              onClick={onGenerationPrepared}
            >
              生成标题与创作方案
            </Button>
          </div>
          {generationPrepared ? (
            <Alert
              type="warning"
              showIcon
              title="文章生成接口尚未接入"
              description="当前只保留创作设置，不会调用模型、创建任务或扣减积分。"
              action={
                <Button
                  type="primary"
                  size="small"
                  disabled={!creationEnabled || !onCreateArticle}
                  onClick={() =>
                    onCreateArticle?.({
                      ...draft,
                      title: draft.title.trim(),
                      bodyText: draft.bodyText.trim(),
                    })
                  }
                >
                  确认生成
                </Button>
              }
            />
          ) : null}
        </Card>

        <Card
          className="article-editor-card"
          title="文章编辑与预览"
          extra={<Tag color="default">本地草稿</Tag>}
        >
          <Input
            className="article-title-input"
            aria-label="文章标题"
            value={draft.title}
            maxLength={60}
            showCount
            placeholder="输入文章标题，或等待标题候选生成"
            onChange={(event) => onDraftChange({ title: event.target.value })}
          />
          <EditorToolbar />
          <Input.TextArea
            className="article-body-input"
            aria-label="文章正文"
            value={draft.bodyText}
            maxLength={10000}
            placeholder="正文生成完成后将在这里显示。你也可以先录入本地草稿；刷新页面前内容仅保存在当前页面状态中。"
            onChange={(event) =>
              onDraftChange({ bodyText: event.target.value })
            }
          />
          <footer className="article-editor-footer">
            <span>
              <ClockCircleOutlined /> 尚未保存到服务器
            </span>
            <div>
              <Tooltip title="文章资产接口尚未接入">
                <Button disabled>保存到资产</Button>
              </Tooltip>
              <Tooltip title="请先保存真实文章版本">
                <Button type="primary" disabled>
                  进入媒体投稿 <ArrowRightOutlined />
                </Button>
              </Tooltip>
            </div>
          </footer>
        </Card>

        <div className="article-creation-rail">
          <ArticleChecks
            title={draft.title}
            bodyText={draft.bodyText}
            keywords={draft.keywords}
            targetWordCount={draft.targetWordCount}
          />
          <Card className="article-outline-card" title="文章创作方案">
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="确认生成后展示创作方案"
            />
          </Card>
        </div>
      </div>
    </>
  )
}

interface SubmissionPanelProps
  extends Pick<
    ArticleSubmissionWorkspaceProps,
    | 'articles'
    | 'mediaResources'
    | 'submissions'
    | 'submissionEnabled'
    | 'onConfirmSubmission'
  > {
  form: ArticleSubmissionFormState
  confirmation?: ArticleSubmissionConfirmation
  onSubmissionInputChange: (
    patch: Partial<ArticleSubmissionFormState>,
  ) => void
  onFilterChange: (patch: Partial<ArticleSubmissionFormState>) => void
  onPrepareConfirmation: (
    confirmation: ArticleSubmissionConfirmation,
  ) => void
  onClearConfirmation: () => void
}

function SubmissionPanel({
  articles,
  mediaResources,
  submissions,
  submissionEnabled,
  onConfirmSubmission,
  form,
  confirmation,
  onSubmissionInputChange,
  onFilterChange,
  onPrepareConfirmation,
  onClearConfirmation,
}: SubmissionPanelProps) {
  const articleOptions = articles
    .filter((article) => article.status === 'COMPLETED')
    .map((article) => ({ label: article.title, value: article.id }))
  const mediaTypes = Array.from(
    new Set(mediaResources.map((media) => media.type)),
  )
  const mediaTypeOptions = [
    { label: '全部类型', value: 'ALL' },
    ...mediaTypes.map((type) => ({ label: type, value: type })),
  ]
  const normalizedSearch = form.searchValue
    .trim()
    .toLocaleLowerCase('zh-CN')
  const visibleMedia = mediaResources.filter(
    (media) =>
      (form.mediaType === 'ALL' || media.type === form.mediaType) &&
      media.name
        .toLocaleLowerCase('zh-CN')
        .includes(normalizedSearch),
  )
  const selectedMedia = mediaResources.filter((media) =>
    form.mediaIds.includes(media.id),
  )
  const totalPoints = selectedMedia.reduce(
    (sum, media) => sum + media.chargedPoints,
    0,
  )

  const toggleMedia = (media: MediaResourceItem) => {
    if (media.status !== 'ACTIVE') return
    onSubmissionInputChange({
      mediaIds: form.mediaIds.includes(media.id)
        ? form.mediaIds.filter((id) => id !== media.id)
        : [...form.mediaIds, media.id].slice(0, 5),
    })
  }

  const prepareConfirmation = () => {
    if (!form.articleId || selectedMedia.length === 0) return
    const selectedArticle = articles.find(
      (article) => article.id === form.articleId,
    )
    if (!selectedArticle) return
    const note = form.note.trim()
    onPrepareConfirmation({
      articleId: form.articleId,
      mediaIds: [...form.mediaIds],
      note,
      articleTitle: `${selectedArticle.title} · ${selectedArticle.versionLabel}`,
      mediaCount: selectedMedia.length,
      totalPoints,
      noteSummary: note || '无备注',
    })
  }

  return (
    <div className="article-submission-layout">
      <Card className="media-resource-card" title="选择投稿媒体">
        <div className="media-resource-toolbar">
          <Select
            aria-label="投稿文章"
            value={form.articleId}
            options={articleOptions}
            placeholder="选择已完成文章"
            disabled={articleOptions.length === 0}
            onChange={(value) =>
              onSubmissionInputChange({ articleId: value })
            }
          />
          <Input
            aria-label="搜索媒体"
            prefix={<SearchOutlined />}
            value={form.searchValue}
            placeholder="搜索媒体名称"
            disabled={mediaResources.length === 0}
            onChange={(event) =>
              onFilterChange({ searchValue: event.target.value })
            }
          />
          <Select
            aria-label="媒体类型"
            value={form.mediaType}
            options={mediaTypeOptions}
            disabled={mediaResources.length === 0}
            onChange={(value) => onFilterChange({ mediaType: value })}
          />
          <span>已选 {form.mediaIds.length} / 5</span>
        </div>

        {visibleMedia.length === 0 ? (
          <Empty
            className="media-resource-empty"
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              <span>
                暂无可用媒体资源
                <small>媒体列表、实时报价和发布要求将在接口接入后显示</small>
              </span>
            }
          />
        ) : (
          <div className="media-resource-table-scroll">
            <table className="media-resource-table" aria-label="媒体资源">
              <thead>
                <tr>
                  <th scope="col">媒体名称</th>
                  <th scope="col">类型</th>
                  <th scope="col">发布说明</th>
                  <th scope="col">预计时效</th>
                  <th scope="col">积分价格</th>
                  <th scope="col">状态</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                {visibleMedia.map((media) => {
                  const selected = form.mediaIds.includes(media.id)
                  const paused = media.status === 'PAUSED'
                  return (
                    <tr className={selected ? 'is-selected' : undefined} key={media.id}>
                      <th scope="row"><GlobalOutlined /> {media.name}</th>
                      <td><Tag>{media.type}</Tag></td>
                      <td>{media.publicationNote}</td>
                      <td>{media.estimatedTimeLabel}</td>
                      <td><strong><StarFilled /> {media.chargedPoints}</strong></td>
                      <td>
                        <Tag color={paused ? 'default' : 'success'}>
                          {paused ? '已停用' : '可投稿'}
                        </Tag>
                      </td>
                      <td>
                        <Button
                          size="small"
                          type={selected ? 'primary' : 'default'}
                          disabled={paused}
                          aria-label={`${selected ? '取消选择' : '选择'}媒体 ${media.name}`}
                          aria-pressed={selected}
                          onClick={() => toggleMedia(media)}
                        >
                          {selected ? <><CheckCircleFilled /> 已选</> : '选择'}
                        </Button>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card className="submission-confirm-card" title="投稿确认">
        <Alert
          type="warning"
          showIcon
          title="投稿属于对外付费操作"
          description="正式提交前将重新核验媒体价格，并生成不可变的文章投稿快照。"
        />
        <dl aria-label={confirmation ? '投稿确认快照' : '投稿信息'}>
          <div>
            <dt>文章版本</dt>
            <dd>{confirmation?.articleTitle ?? (form.articleId ? '已选择' : '未选择')}</dd>
          </div>
          <div>
            <dt>投稿媒体</dt>
            <dd>{confirmation?.mediaCount ?? form.mediaIds.length} 家</dd>
          </div>
          <div>
            <dt>预计积分</dt>
            <dd>
              <StarFilled />{' '}
              {confirmation?.totalPoints ?? (selectedMedia.length ? totalPoints : '待报价')}
            </dd>
          </div>
          {confirmation ? (
            <div>
              <dt>备注摘要</dt>
              <dd title={confirmation.noteSummary}>{confirmation.noteSummary}</dd>
            </div>
          ) : null}
        </dl>
        <Input.TextArea
          aria-label="投稿备注和排版要求"
          value={form.note}
          placeholder="投稿备注和排版要求（选填）"
          maxLength={300}
          showCount
          disabled={!form.articleId}
          onChange={(event) =>
            onSubmissionInputChange({ note: event.target.value })
          }
        />
        {confirmation ? (
          <Alert
            type="info"
            showIcon
            title="投稿接口尚未接入"
            description="不会创建采购订单、冻结积分或向外部媒体提交内容。"
          />
        ) : null}
        <div className="submission-confirm-card__actions">
          <Button onClick={onClearConfirmation}>返回修改</Button>
          <Button
            type="primary"
            disabled={!form.articleId || form.mediaIds.length === 0}
            onClick={prepareConfirmation}
          >
            生成投稿确认
          </Button>
          <Button
            danger
            disabled={
              !confirmation || !submissionEnabled || !onConfirmSubmission
            }
            onClick={() =>
              confirmation &&
              onConfirmSubmission?.({
                articleId: confirmation.articleId,
                mediaIds: [...confirmation.mediaIds],
                note: confirmation.note,
              })
            }
          >
            确认投稿
          </Button>
        </div>
      </Card>

      <Card className="submission-history-card" title="最近投稿">
        {submissions.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="暂无投稿记录"
          />
        ) : (
          <ul>
            {submissions.map((submission) => {
              const status = SUBMISSION_STATUS[submission.status]
              const publishedUrl = safePublishedUrl(submission.publishedUrl)
              return (
                <li key={submission.id}>
                  <div>
                    <FileDoneOutlined />
                    <span>
                      <strong>{submission.articleTitle}</strong>
                      <small>{submission.mediaNames.join('、')}</small>
                    </span>
                  </div>
                  <Tag color={status.color}>{status.label}</Tag>
                  <span><StarFilled /> {submission.chargedPoints}</span>
                  <time>{submission.submittedAt}</time>
                  {publishedUrl ? (
                    <a href={publishedUrl} target="_blank" rel="noopener noreferrer">
                      查看回链
                    </a>
                  ) : (
                    <Button size="small" disabled>查看详情</Button>
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </Card>
    </div>
  )
}

function ArticleListPanel({ articles }: { articles: readonly ArticleListItem[] }) {
  return (
    <Card className="article-list-card" title="我的文章">
      {articles.length === 0 ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={
            <span>
              暂无文章
              <small>生成或保存后的真实文章会显示在这里</small>
            </span>
          }
        />
      ) : (
        <ul>
          {articles.map((article) => {
            const status = ARTICLE_STATUS[article.status]
            return (
              <li key={article.id}>
                <div className="article-list-card__icon"><FileTextOutlined /></div>
                <div className="article-list-card__copy">
                  <Typography.Text strong ellipsis>{article.title}</Typography.Text>
                  <Typography.Text type="secondary" ellipsis>
                    {article.excerpt ?? '暂无摘要'}
                  </Typography.Text>
                </div>
                <Tag color={status.color}>{status.label}</Tag>
                <span>{article.versionLabel}</span>
                <span>{article.wordCount === undefined ? '—' : `${article.wordCount} 字`}</span>
                <time>{article.updatedAt}</time>
                <Button size="small" disabled>继续编辑</Button>
              </li>
            )
          })}
        </ul>
      )}
    </Card>
  )
}

function LegacyArticleSubmissionWorkspace({
  businessSpaces,
  products,
  articles,
  mediaResources,
  submissions,
  estimatedCreationCredits,
  creationEnabled = false,
  submissionEnabled = false,
  onCreateArticle,
  onConfirmSubmission,
}: ArticleSubmissionWorkspaceProps) {
  const [view, setView] = useState<ArticleWorkspaceView>('CREATION')
  const [articleDraft, setArticleDraft] = useState<ArticleCreationDraft>(
    createInitialArticleDraft,
  )
  const [keywordInput, setKeywordInput] = useState('')
  const [generationPrepared, setGenerationPrepared] = useState(false)
  const [discardConfirmationOpen, setDiscardConfirmationOpen] = useState(false)
  const [submissionForm, setSubmissionForm] =
    useState<ArticleSubmissionFormState>(createInitialSubmissionForm)
  const [submissionConfirmation, setSubmissionConfirmation] =
    useState<ArticleSubmissionConfirmation>()

  const updateArticleDraft = (patch: Partial<ArticleCreationDraft>) => {
    setArticleDraft((current) => ({ ...current, ...patch }))
    setGenerationPrepared(false)
  }

  const updateSubmissionInput = (
    patch: Partial<ArticleSubmissionFormState>,
  ) => {
    setSubmissionForm((current) => ({ ...current, ...patch }))
    setSubmissionConfirmation(undefined)
  }

  const resetArticleDraft = () => {
    setArticleDraft(createInitialArticleDraft())
    setKeywordInput('')
    setGenerationPrepared(false)
    setDiscardConfirmationOpen(false)
    setView('CREATION')
  }

  const startNewArticle = () => {
    if (hasUnsavedArticleDraft(articleDraft, keywordInput)) {
      setDiscardConfirmationOpen(true)
      return
    }
    setView('CREATION')
  }

  return (
    <section className="article-workspace" aria-labelledby="article-workspace-title">
      <header className="article-workspace-heading">
        <div>
          <Typography.Title id="article-workspace-title" level={1}>
            文章与投稿
          </Typography.Title>
          <Typography.Text type="secondary">
            从内容策划、文章创作到媒体发布，一站式完成
          </Typography.Text>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={startNewArticle}>
          新建文章
        </Button>
      </header>

      <nav className="article-workspace-tabs" aria-label="文章与投稿模块">
        {WORKSPACE_VIEWS.map((item) => (
          <button
            type="button"
            key={item.value}
            className={view === item.value ? 'is-active' : undefined}
            aria-label={item.label}
            aria-current={view === item.value ? 'page' : undefined}
            onClick={() => setView(item.value)}
          >
            {item.value === 'CREATION' ? <EditOutlined /> : null}
            {item.value === 'SUBMISSION' ? <GlobalOutlined /> : null}
            {item.value === 'ARTICLES' ? <BarsOutlined /> : null}
            {item.label}
          </button>
        ))}
      </nav>

      {view === 'CREATION' ? (
        <CreationPanel
          businessSpaces={businessSpaces}
          products={products}
          estimatedCreationCredits={estimatedCreationCredits}
          creationEnabled={creationEnabled}
          onCreateArticle={onCreateArticle}
          draft={articleDraft}
          keywordInput={keywordInput}
          generationPrepared={generationPrepared}
          onDraftChange={updateArticleDraft}
          onKeywordInputChange={(value) => {
            setKeywordInput(value)
            setGenerationPrepared(false)
          }}
          onGenerationPrepared={() => setGenerationPrepared(true)}
        />
      ) : null}
      {view === 'SUBMISSION' ? (
        <SubmissionPanel
          articles={articles}
          mediaResources={mediaResources}
          submissions={submissions}
          submissionEnabled={submissionEnabled}
          onConfirmSubmission={onConfirmSubmission}
          form={submissionForm}
          confirmation={submissionConfirmation}
          onSubmissionInputChange={updateSubmissionInput}
          onFilterChange={(patch) =>
            setSubmissionForm((current) => ({ ...current, ...patch }))
          }
          onPrepareConfirmation={setSubmissionConfirmation}
          onClearConfirmation={() => setSubmissionConfirmation(undefined)}
        />
      ) : null}
      {view === 'ARTICLES' ? <ArticleListPanel articles={articles} /> : null}

      <Modal
        title={<span id="article-discard-title">放弃当前草稿？</span>}
        aria-labelledby="article-discard-title"
        open={discardConfirmationOpen}
        okText="放弃并新建"
        cancelText="继续编辑"
        okButtonProps={{ danger: true }}
        onOk={resetArticleDraft}
        onCancel={() => setDiscardConfirmationOpen(false)}
      >
        <Typography.Paragraph>
          当前文章包含未保存内容。放弃后，标题、正文、关键词和创作设置将被清空，且无法恢复。
        </Typography.Paragraph>
      </Modal>
    </section>
  )
}

export function ArticleSubmissionWorkspace(
  props: ArticleSubmissionWorkspaceProps,
) {
  return props.live ? (
    <ArticleLiveWorkspace {...props.live} />
  ) : (
    <LegacyArticleSubmissionWorkspace {...props} />
  )
}

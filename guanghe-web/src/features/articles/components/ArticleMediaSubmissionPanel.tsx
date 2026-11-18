import {
  FileTextOutlined,
  GlobalOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Input,
  InputNumber,
  Pagination,
  Select,
  Tag,
  Timeline,
  Typography,
} from 'antd'
import { useMemo, useState } from 'react'
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
  ArticleViewError,
  ArticleWorkspaceRecordView,
  LiveMediaResourceStatus,
  LiveMediaResourceView,
  LiveMediaSubmissionDetailView,
  LiveMediaSubmissionListItemView,
  MediaQuoteSelectionView,
  MediaResourceFilterView,
  SubmissionSnapshotView,
} from '../types'
import { SafeArticleContent } from './SafeArticleContent'
import { PublishedMediaLinks } from './PublishedMediaLinks'
import { mediaArticleHasImages, mediaArticleHasLinks, mediaImageFeeModeLabel } from '../live/mediaContentPolicy'

const STATUS_OPTIONS: readonly { label: string; value: LiveMediaResourceStatus }[] = [
  { label: '可用', value: 'ACTIVE' },
  { label: '已停用', value: 'INACTIVE' },
]

const SUBMISSION_STATUS_OPTIONS = [
  ['QUEUED', '排队中'],
  ['SUBMITTING', '提交中'],
  ['SUBMIT_UNKNOWN', '正在核对'],
  ['ACCEPTED', '已受理'],
  ['PUBLISHED', '已发布'],
  ['REJECTED', '已退稿'],
  ['RECONCILING', '结算核对中'],
  ['FAILED', '投稿失败'],
] as const

export interface ArticleMediaSubmissionPanelProps {
  article?: ArticleWorkspaceRecordView
  snapshot?: SubmissionSnapshotView
  resources: readonly LiveMediaResourceView[]
  selectedResource?: LiveMediaResourceView
  resourceDetailOpen?: boolean
  resourceModels: readonly { label: string; value: string }[]
  resourceTypes: readonly { label: string; value: string }[]
  resourceFilter: MediaResourceFilterView
  resourcePagination: { current: number; pageSize: number; total: number }
  submissions: readonly LiveMediaSubmissionListItemView[]
  submissionStatus?: LiveMediaSubmissionListItemView['status']
  submissionPagination: { current: number; pageSize: number; total: number }
  selectedSubmission?: LiveMediaSubmissionDetailView
  submissionDetailOpen?: boolean
  quotedSelection?: MediaQuoteSelectionView
  loadingResources?: boolean
  loadingResourceDetail?: boolean
  loadingSubmissions?: boolean
  refreshingSubmissions?: boolean
  loadingSubmissionDetail?: boolean
  refreshingSubmission?: boolean
  submissionPollingPaused?: boolean
  preparing?: boolean
  busy?: boolean
  error?: ArticleViewError | null
  paidTaskConfirmation?: PaidTaskConfirmationView
  taskReceipt?: TaskCreatedReceiptView
  taskProgress?: ParallelTaskGroupView
  taskOutcome?: TaskExecutionOutcomeGroupView
  onCreateSnapshot: () => void
  onResourceFilterChange: (filter: MediaResourceFilterView) => void
  onResourcePageChange: (page: number, pageSize: number) => void
  onOpenResource: (resourceId?: string) => void
  onPrepareQuote: (selection: MediaQuoteSelectionView) => void
  onEditQuote: (selection: MediaQuoteSelectionView) => void
  onConfirmQuote: (draftId: string) => void
  onOpenRecharge: (draftId: string) => void
  onSubmissionStatusChange: (
    status?: LiveMediaSubmissionListItemView['status'],
  ) => void
  onSubmissionPageChange: (page: number, pageSize: number) => void
  onRefreshSubmissions: () => void
  onOpenSubmission: (submissionId?: string) => void
  onRefreshSubmission: (submissionId: string) => void
  onViewTask: (taskId: string) => void
  onTaskAction: (taskId: string, type: TaskOutcomeActionType) => void
  onRetry: () => void
}

function toneColor(tone: LiveMediaSubmissionListItemView['statusTone']) {
  return {
    neutral: 'default',
    info: 'blue',
    success: 'success',
    warning: 'warning',
    danger: 'error',
  }[tone]
}

function ArticleSnapshot({ snapshot }: { snapshot: SubmissionSnapshotView }) {
  return (
    <Card size="small" title="不可变投稿快照" className="media-live-snapshot">
      <div className="media-live-snapshot__heading">
        <FileTextOutlined aria-hidden="true" />
        <div>
          <strong>{snapshot.title || '未命名文章'}</strong>
          <span>{snapshot.versionLabel} · {snapshot.createdAt}</span>
        </div>
      </div>
      <Typography.Text type="secondary">
        创作方案 {snapshot.outlineCount} 个结构节点 · {snapshot.assetCount} 张配图
      </Typography.Text>
      <SafeArticleContent
        html={snapshot.bodyHtml}
        fallback={snapshot.bodyText || '暂无正文'}
      />
    </Card>
  )
}

export function ArticleMediaSubmissionPanel(
  props: ArticleMediaSubmissionPanelProps,
) {
  const { article, snapshot, quotedSelection } = props
  const initialSelection = useMemo(
    () => ({
      snapshotId: snapshot?.id,
      mediaResourceIds: [...(quotedSelection?.mediaResourceIds ?? [])],
      remark: quotedSelection?.remark ?? '',
    }),
    [quotedSelection, snapshot?.id],
  )
  const [selectionState, setSelectionState] = useState(initialSelection)
  const selection =
    selectionState.snapshotId === snapshot?.id
      ? selectionState
      : initialSelection
  const quoted = Boolean(props.paidTaskConfirmation)
  const hasImages = Boolean(snapshot && mediaArticleHasImages({
    bodyHtml: snapshot.bodyHtml,
    assetCount: snapshot.assetCount,
  }))
  const supportsArticleImages = (resource: LiveMediaResourceView) => resource.supportsImages === true &&
    (resource.maxImageCount ?? 0) >= Math.max(snapshot?.assetCount ?? 1, 1)
  const imageUnsupported = hasImages && (selection.mediaResourceIds.length === 0 || selection.mediaResourceIds.some((id) => {
    const resource = props.resources.find((item) => item.id === id)
    return !resource || !supportsArticleImages(resource)
  }))
  const hasExternalLinks = Boolean(snapshot && mediaArticleHasLinks(snapshot.bodyHtml))
  const incompatibleSelected = hasExternalLinks && props.resources.some(
    (resource) => selection.mediaResourceIds.includes(resource.id) && !resource.supportsLinks,
  )

  const toggleResource = (resource: LiveMediaResourceView) => {
    const selected = selection.mediaResourceIds.includes(resource.id)
    if ((!selected && (!resource.orderable || (hasExternalLinks && !resource.supportsLinks) || (hasImages && !supportsArticleImages(resource)))) ||
      quoted || props.busy) return
    if (!selected && selection.mediaResourceIds.length >= 5) return
    setSelectionState({
      ...selection,
      snapshotId: snapshot?.id,
      mediaResourceIds: selected
        ? selection.mediaResourceIds.filter((id) => id !== resource.id)
        : [...selection.mediaResourceIds, resource.id],
    })
  }

  return (
    <section className="media-live-workspace" aria-label="文章媒体投稿">
      {props.error ? (
        <ErrorState
          message={props.error.message}
          traceId={props.error.traceId}
          onRetry={props.onRetry}
        />
      ) : null}

      <div className="media-live-grid">
        <Card className="media-live-article" title="投稿文章与快照">
          {article ? (
            <>
              <dl>
                <div><dt>文章</dt><dd>{article.title || '未命名文章'}</dd></div>
                <div><dt>当前版本</dt><dd>V{article.version}</dd></div>
                <div><dt>正文</dt><dd>{article.bodyText.length} 字</dd></div>
              </dl>
              <Alert
                type="info"
                showIcon
                title="投稿只使用不可变快照"
                description="后续修改文章不会静默改变已报价或已提交的投稿内容。"
              />
              <Button
                type="primary"
                disabled={props.busy}
                onClick={props.onCreateSnapshot}
              >
                {snapshot ? '为当前版本创建新快照' : '创建不可变投稿快照'}
              </Button>
            </>
          ) : (
            <Empty description="请先选择文章" />
          )}
          {snapshot ? <ArticleSnapshot snapshot={snapshot} /> : null}
          {article && snapshot && snapshot.versionLabel !== `V${article.version}` ? (
            <Alert
              type="warning"
              showIcon
              title="当前文章版本与投稿快照不同"
              description={`本次报价仍以 ${snapshot.versionLabel} 的不可变快照为准。`}
            />
          ) : null}
        </Card>

        <Card className="media-live-resource-card" title="选择投稿媒体">
          <div className="media-live-resource-filter">
            <Input.Search
              allowClear
              aria-label="搜索媒体资源"
              value={props.resourceFilter.search}
              placeholder="搜索媒体名称"
              onChange={(event) =>
                props.onResourceFilterChange({
                  ...props.resourceFilter,
                  search: event.target.value,
                })
              }
            />
            <Select
              allowClear
              aria-label="媒体模型"
              value={props.resourceFilter.modelId}
              options={[...props.resourceModels]}
              placeholder="全部模型"
              onChange={(modelId) =>
                props.onResourceFilterChange({
                  ...props.resourceFilter,
                  modelId,
                })
              }
            />
            <Select
              allowClear
              aria-label="媒体类型"
              value={props.resourceFilter.type}
              options={[...props.resourceTypes]}
              placeholder="全部类型"
              onChange={(type) =>
                props.onResourceFilterChange({ ...props.resourceFilter, type })
              }
            />
            <Select
              allowClear
              aria-label="媒体状态"
              value={props.resourceFilter.status}
              options={[...STATUS_OPTIONS]}
              placeholder="全部状态"
              onChange={(status) =>
                props.onResourceFilterChange({
                  ...props.resourceFilter,
                  status,
                })
              }
            />
            <InputNumber
              aria-label="最低销售价（分）"
              min={0}
              precision={0}
              value={props.resourceFilter.minSaleAmountCents}
              placeholder="最低价（分）"
              onChange={(value) =>
                props.onResourceFilterChange({
                  ...props.resourceFilter,
                  minSaleAmountCents: value ?? undefined,
                })
              }
            />
            <InputNumber
              aria-label="最高销售价（分）"
              min={0}
              precision={0}
              value={props.resourceFilter.maxSaleAmountCents}
              placeholder="最高价（分）"
              onChange={(value) =>
                props.onResourceFilterChange({
                  ...props.resourceFilter,
                  maxSaleAmountCents: value ?? undefined,
                })
              }
            />
          </div>

          {props.loadingResources ? (
            <PageLoading label="正在加载媒体资源…" />
          ) : props.resources.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="暂无符合条件的媒体资源"
            />
          ) : (
            <div className="media-live-resource-table-wrap">
              <table className="media-live-resource-table">
                <thead>
                  <tr>
                    <th scope="col">媒体名称</th>
                    <th scope="col">类型</th>
                    <th scope="col">区域</th>
                    <th scope="col">预计时效</th>
                    <th scope="col">基础销售价</th>
                    <th scope="col">基础积分</th>
                    <th scope="col">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {props.resources.map((resource) => {
                    const selected = selection.mediaResourceIds.includes(resource.id)
                    return (
                      <tr key={resource.id} className={selected ? 'is-selected' : undefined}>
                        <th scope="row">
                          <GlobalOutlined aria-hidden="true" /> {resource.name}
                          <Tag color={resource.orderable ? 'success' : 'default'}>
                            {resource.orderable ? '可投稿' : '当前不可用'}
                          </Tag>
                          <Tag color={resource.supportsLinks ? 'success' : 'default'}>
                            {resource.supportsLinks ? '可带外链' : '不支持外链'}
                          </Tag>
                          {hasImages ? <Tag color={supportsArticleImages(resource) ? 'success' : 'default'}>
                            {supportsArticleImages(resource) ? `${mediaImageFeeModeLabel(resource.imageFeeMode)} · 最多 ${resource.maxImageCount} 张` : '图文规则 / 费用待核验'}
                          </Tag> : null}
                          {hasImages && resource.imagePricingDescription ? <Typography.Paragraph type="secondary">{resource.imagePricingDescription}</Typography.Paragraph> : null}
                        </th>
                        <td>{resource.resourceType ?? '—'}</td>
                        <td>{resource.region ?? '—'}</td>
                        <td>{resource.estimatedTimeLabel ?? '—'}</td>
                        <td>{resource.saleAmountLabel ?? '—'}</td>
                        <td>{resource.chargedPoints ?? '—'}</td>
                        <td>
                          <Button size="small" onClick={() => props.onOpenResource(resource.id)}>
                            详情
                          </Button>
                          <Button
                            size="small"
                            type={selected ? 'primary' : 'default'}
                            disabled={
                              (!selected && (!resource.orderable || (hasExternalLinks && !resource.supportsLinks) || (hasImages && !supportsArticleImages(resource)))) ||
                              quoted ||
                              props.busy ||
                              (!selected && selection.mediaResourceIds.length >= 5)
                            }
                            onClick={() => toggleResource(resource)}
                          >
                            {selected ? '已选择' : '选择'}
                          </Button>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
          <Pagination
            current={props.resourcePagination.current}
            pageSize={props.resourcePagination.pageSize}
            total={props.resourcePagination.total}
            showSizeChanger
            onChange={props.onResourcePageChange}
          />
        </Card>

        <Card className="media-live-quote" title="投稿报价与确认">
          {hasExternalLinks ? (
            <Alert
              type={incompatibleSelected ? 'warning' : 'info'}
              showIcon
              title="当前快照含外链"
              description={incompatibleSelected
                ? '已选媒体中有不支持外链的资源，请取消选择并改选“可带外链”媒体后报价。'
                : '当前只能选择标记为“可带外链”的媒体；也可移除正文外链后重新创建快照。'}
            />
          ) : null}
          {hasImages ? <Alert
            type={imageUnsupported ? 'warning' : 'info'}
            showIcon
            title={imageUnsupported ? '请选择已核验的图文媒体' : `本稿 ${snapshot?.assetCount ?? 0} 张配图`}
            description={imageUnsupported
              ? '未核验图片抓取或费用规则的渠道不可选，未知费用不当作免费。稿件和图片会保留，不会提交或扣分。'
              : '上方目录展示基础价格；获取本稿报价后，会列出各媒体的图片附加费和含图总价。只在你最终确认投稿后下单。'}
          /> : null}
          <Input.TextArea
            aria-label="投稿备注"
            maxLength={1000}
            showCount
            value={selection.remark}
            disabled={quoted || props.busy}
            placeholder="投稿备注（选填）"
            onChange={(event) =>
              setSelectionState({
                ...selection,
                snapshotId: snapshot?.id,
                remark: event.target.value,
              })
            }
          />
          <Typography.Text type="secondary">
            已选择 {selection.mediaResourceIds.length} / 5 家媒体。报价和积分以提交前的最新确认信息为准。
          </Typography.Text>
          {!props.paidTaskConfirmation &&
          (!props.taskReceipt || props.taskOutcome) ? (
            <Button
              type="primary"
              loading={props.preparing}
              disabled={
                !snapshot ||
                imageUnsupported ||
                incompatibleSelected ||
                selection.mediaResourceIds.length === 0 ||
                props.busy ||
                props.preparing
              }
              onClick={() =>
                props.onPrepareQuote({
                  mediaResourceIds: selection.mediaResourceIds,
                  remark: selection.remark,
                })
              }
            >
              获取最新报价
            </Button>
          ) : null}
          {props.paidTaskConfirmation ? (
            <PaidTaskConfirmationCard
              confirmation={props.paidTaskConfirmation}
              onEdit={() => {
                setSelectionState({ ...selection, snapshotId: snapshot?.id })
                props.onEditQuote({
                  mediaResourceIds: selection.mediaResourceIds,
                  remark: selection.remark,
                })
              }}
              onConfirm={props.onConfirmQuote}
              onOpenRecharge={props.onOpenRecharge}
              onPrepareReestimate={() => {
                setSelectionState({ ...selection, snapshotId: snapshot?.id })
                props.onEditQuote({
                  mediaResourceIds: selection.mediaResourceIds,
                  remark: selection.remark,
                })
              }}
              onOpenPaidTaskReferenceAsset={() => undefined}
              onAddPaidTaskReferenceAsset={() => undefined}
            />
          ) : null}
          {props.taskReceipt ? (
            <TaskCreatedReceiptCard receipt={props.taskReceipt} onViewTask={props.onViewTask} />
          ) : null}
          {props.taskProgress ? (
            <ParallelTaskGroup group={props.taskProgress} onViewTask={props.onViewTask} />
          ) : null}
          {props.taskOutcome ? (
            <TaskExecutionOutcomeGroup group={props.taskOutcome} onAction={props.onTaskAction} />
          ) : null}
        </Card>

        <Card
          className="media-live-submissions"
          title="投稿记录"
          extra={
            <Button
              icon={<ReloadOutlined aria-hidden="true" />}
              loading={props.refreshingSubmissions}
              disabled={props.refreshingSubmissions}
              onClick={props.onRefreshSubmissions}
            >
              刷新投稿状态
            </Button>
          }
        >
          <Select
            allowClear
            aria-label="投稿状态"
            value={props.submissionStatus}
            placeholder="全部投稿状态"
            options={SUBMISSION_STATUS_OPTIONS.map(([value, label]) => ({ value, label }))}
            onChange={props.onSubmissionStatusChange}
          />
          {props.loadingSubmissions ? (
            <PageLoading label="正在加载投稿记录…" />
          ) : props.submissions.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无投稿记录" />
          ) : (
            <ul className="media-live-submission-list">
              {props.submissions.map((submission) => (
                <li key={submission.id}>
                  <div>
                    <strong>{submission.mediaName}</strong>
                    <span>{submission.createdAt}</span>
                  </div>
                  <Tag color={toneColor(submission.statusTone)}>{submission.statusLabel}</Tag>
                  <span>{submission.saleAmountLabel}</span>
                  <span>{submission.chargedPoints === undefined ? '—' : `${submission.chargedPoints} 积分`}</span>
                  <Button size="small" onClick={() => props.onOpenSubmission(submission.id)}>
                    查看详情
                  </Button>
                </li>
              ))}
            </ul>
          )}
          <Pagination
            current={props.submissionPagination.current}
            pageSize={props.submissionPagination.pageSize}
            total={props.submissionPagination.total}
            showSizeChanger
            onChange={props.onSubmissionPageChange}
          />
        </Card>
      </div>

      <Drawer
        size="large"
        open={props.resourceDetailOpen ?? Boolean(props.selectedResource)}
        title="媒体资源详情"
        onClose={() => props.onOpenResource(undefined)}
      >
        {props.loadingResourceDetail ? (
          <PageLoading label="正在加载媒体资源…" />
        ) : props.selectedResource ? (
          <div className="media-live-resource-detail">
            <dl>
              <div><dt>媒体名称</dt><dd>{props.selectedResource.name}</dd></div>
              <div><dt>资源模型</dt><dd>{props.selectedResource.modelId}</dd></div>
              <div><dt>资源类型</dt><dd>{props.selectedResource.resourceType ?? '—'}</dd></div>
              <div><dt>外链能力</dt><dd>{props.selectedResource.supportsLinks ? '可带外链' : '不支持外链'}</dd></div>
              <div><dt>渠道分类</dt><dd>{props.selectedResource.channelCategory ?? '—'}</dd></div>
              <div><dt>区域</dt><dd>{props.selectedResource.region ?? '—'}</dd></div>
              <div><dt>预计时效</dt><dd>{props.selectedResource.estimatedTimeLabel ?? '—'}</dd></div>
              <div><dt>基础销售价</dt><dd>{props.selectedResource.saleAmountLabel ?? '—'}</dd></div>
              <div><dt>基础积分</dt><dd>{props.selectedResource.chargedPoints ?? '—'}</dd></div>
              <div><dt>图片费用规则</dt><dd>{props.selectedResource.supportsImages ? mediaImageFeeModeLabel(props.selectedResource.imageFeeMode) : '未核验，不支持图文投稿'}</dd></div>
              {props.selectedResource.imagePricingDescription ? <div><dt>图片费用说明</dt><dd>{props.selectedResource.imagePricingDescription}</dd></div> : null}
              <div><dt>报价有效期</dt><dd>{props.selectedResource.quoteExpiresAt}</dd></div>
              <div><dt>同步时间</dt><dd>{props.selectedResource.syncedAt}</dd></div>
            </dl>
            {!props.selectedResource.orderable ? (
              <Alert type="warning" showIcon title="该媒体当前不可下单" />
            ) : null}
          </div>
        ) : (
          <Empty description="请选择媒体资源" />
        )}
      </Drawer>

      <Drawer
        size="large"
        open={props.submissionDetailOpen ?? Boolean(props.selectedSubmission)}
        title="投稿详情"
        onClose={() => props.onOpenSubmission(undefined)}
      >
        {props.loadingSubmissionDetail ? (
          <PageLoading label="正在加载投稿详情…" />
        ) : props.selectedSubmission ? (
          <div className="media-live-submission-detail">
            <dl>
              <div><dt>媒体</dt><dd>{props.selectedSubmission.mediaName}</dd></div>
              <div><dt>状态</dt><dd><Tag color={toneColor(props.selectedSubmission.statusTone)}>{props.selectedSubmission.statusLabel}</Tag></dd></div>
              <div><dt>销售价</dt><dd>{props.selectedSubmission.saleAmountLabel}</dd></div>
              <div><dt>消耗积分</dt><dd>{props.selectedSubmission.chargedPoints ?? '—'}</dd></div>
              <div><dt>已结算积分</dt><dd>{props.selectedSubmission.settledPoints ?? '—'}</dd></div>
              <div><dt>退回积分</dt><dd>{props.selectedSubmission.returnedPoints ?? '—'}</dd></div>
              {props.selectedSubmission.remark ? <div><dt>投稿备注</dt><dd>{props.selectedSubmission.remark}</dd></div> : null}
              {props.selectedSubmission.errorCode ? <div><dt>处理说明</dt><dd>{props.selectedSubmission.errorCode}</dd></div> : null}
            </dl>
            {props.selectedSubmission.status === 'SUBMIT_UNKNOWN' ? (
              <Alert
                type="warning"
                showIcon
                title="正在核对，系统不会重复下单"
                description="投稿结果尚未明确，系统将依据服务端记录继续核对。"
              />
            ) : null}
            {props.submissionPollingPaused ? (
              <Alert
                type="info"
                showIcon
                title="自动刷新已暂停，投稿仍待处理"
                description="系统可能仍在核对上游结果。可手动刷新这一条投稿的最新状态，刷新不会再次下单。"
                action={
                  <Button
                    size="small"
                    icon={<ReloadOutlined aria-hidden="true" />}
                    aria-label="刷新此投稿状态"
                    loading={props.refreshingSubmission}
                    disabled={props.refreshingSubmission}
                    onClick={() => props.onRefreshSubmission(props.selectedSubmission!.id)}
                  >
                    刷新此投稿状态
                  </Button>
                }
              />
            ) : null}
            <PublishedMediaLinks
              urls={props.selectedSubmission.publishedUrls}
              published={props.selectedSubmission.status === 'PUBLISHED'}
            />
            <Card size="small" title="状态记录">
              {props.selectedSubmission.events.length ? (
                <Timeline items={props.selectedSubmission.events.map((event) => ({
                  content: <><strong>{event.statusLabel}</strong><p>{event.label} · {event.occurredAt}</p>{event.errorCode ? <p>{event.errorCode}</p> : null}</>,
                }))} />
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无状态记录" />
              )}
            </Card>
          </div>
        ) : (
          <Empty description="请选择投稿记录" />
        )}
      </Drawer>

      {props.error?.conflict ? (
        <Alert
          type="warning"
          showIcon
          title={props.error.code === 'MEDIA_QUOTE_CHANGED' ? '媒体报价已变化' : '投稿状态冲突'}
          description="已保留媒体选择和投稿备注，请重新获取报价后再确认。"
          action={<Button icon={<ReloadOutlined />} onClick={props.onRetry}>重新加载</Button>}
        />
      ) : null}
    </section>
  )
}

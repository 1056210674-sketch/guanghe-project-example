import { Alert, Button, Descriptions, Drawer, Empty, Input, InputNumber, Pagination, Select, Space, Table, Tabs, Tag, Typography } from 'antd'
import { ErrorState, PageLoading } from '../../components/GlobalState'
import { PublishedMediaLinks } from '../articles/components/PublishedMediaLinks'
import { MEDIA_SUBMISSION_STATUS_OPTIONS } from '../articles/live/mediaAdapters'
import type { ArticleViewError, LiveMediaResourceView, LiveMediaSubmissionDetailView, LiveMediaSubmissionListItemView, LiveMediaSubmissionStatus } from '../articles/types'

export interface DirectoryFilters {
  search: string
  status: 'ACTIVE' | 'INACTIVE'
  minYuan: number | null
  maxYuan: number | null
}

interface PageState { current: number; pageSize: number; total: number }

interface Props {
  tab: string
  onTabChange: (tab: string) => void
  filters: DirectoryFilters
  onFiltersChange: (filters: DirectoryFilters) => void
  onSearch: () => void
  filterError?: string
  resources: readonly LiveMediaResourceView[]
  resourcesLoading: boolean
  resourcesError: ArticleViewError | null
  resourcePage: PageState
  onResourcePageChange: (page: number, size: number) => void
  onRefreshResources: () => void
  submissions: readonly LiveMediaSubmissionListItemView[]
  submissionsLoading: boolean
  submissionsRefreshing: boolean
  submissionsError: ArticleViewError | null
  status?: LiveMediaSubmissionStatus
  onStatusChange: (status?: LiveMediaSubmissionStatus) => void
  submissionPage: PageState
  onSubmissionPageChange: (page: number, size: number) => void
  onRefreshSubmissions: () => void
  onOpenSubmission: (id?: string) => void
  detailOpen: boolean
  detail?: LiveMediaSubmissionDetailView
  detailLoading: boolean
  detailRefreshing: boolean
  detailError: ArticleViewError | null
  onRefreshDetail: () => void
  onOpenArticle: (id: string) => void
  onGoSubmit: () => void
}

export function SubmissionCenterWorkspace(props: Props) {
  const pagination = (page: PageState, onChange: (page: number, size: number) => void) => (
    <Pagination current={page.current} pageSize={page.pageSize} total={page.total}
      showSizeChanger pageSizeOptions={[20, 50, 100]} showTotal={(total) => `共 ${total} 条`}
      onChange={onChange} />
  )
  return <section className="submission-center" aria-labelledby="submission-center-title">
    <header className="platform-page-heading submission-center-heading">
      <div><Typography.Title level={1} id="submission-center-title">投稿</Typography.Title>
        <Typography.Paragraph type="secondary">查看媒体目录与价格，跟进每一笔投稿和发布回链。</Typography.Paragraph></div>
      <Button onClick={props.onGoSubmit}>选择文章投稿</Button>
    </header>
    <Tabs activeKey={props.tab} onChange={props.onTabChange} destroyOnHidden items={[
      { key: 'resources', label: '媒体目录', children: <Space orientation="vertical" size="middle" className="submission-center-section">
        <Typography.Paragraph type="secondary">价格来自最近同步目录，正式投稿前会再次核验价格与媒体状态。</Typography.Paragraph>
        <form className="submission-center-filters" onSubmit={(event) => { event.preventDefault(); props.onSearch() }}>
          <Input aria-label="搜索媒体" placeholder="搜索媒体名称" value={props.filters.search} maxLength={200}
            onChange={(event) => props.onFiltersChange({ ...props.filters, search: event.target.value })} allowClear />
          <Select aria-label="媒体目录状态" value={props.filters.status}
            options={[{ value: 'ACTIVE', label: '目录可选' }, { value: 'INACTIVE', label: '目录暂不可选' }]}
            onChange={(status) => props.onFiltersChange({ ...props.filters, status })} />
          <InputNumber aria-label="最低参考价（元）" placeholder="最低价（元）" min={0} max={21_474_836.47} precision={2}
            value={props.filters.minYuan} onChange={(minYuan) => props.onFiltersChange({ ...props.filters, minYuan })} />
          <InputNumber aria-label="最高参考价（元）" placeholder="最高价（元）" min={0} max={21_474_836.47} precision={2}
            value={props.filters.maxYuan} onChange={(maxYuan) => props.onFiltersChange({ ...props.filters, maxYuan })} />
          <Button htmlType="submit">查询</Button><Button onClick={props.onRefreshResources}>刷新目录</Button>
        </form>
        {props.filterError ? <Alert type="warning" title={props.filterError} /> : null}
        {props.resourcesError ? <ErrorState {...props.resourcesError} onRetry={props.onRefreshResources} /> : <>
          <Table<LiveMediaResourceView> rowKey="id" dataSource={[...props.resources]} loading={props.resourcesLoading}
            pagination={false} scroll={{ x: 1000 }} locale={{ emptyText: <Empty description="暂无符合条件的媒体资源" /> }}
            columns={[
              { title: '媒体', dataIndex: 'name', key: 'name' },
              { title: '类别 / 地区', key: 'category', render: (_, row) => [row.channelCategory ?? row.resourceType, row.region].filter(Boolean).join(' / ') || '—' },
              { title: '目录状态', key: 'status', render: (_, row) => <Tag>{row.orderable ? '目录可选' : '目录暂不可选'}</Tag> },
              { title: '参考金额（元）', dataIndex: 'saleAmountLabel', key: 'price' },
              { title: '预计积分', key: 'points', render: (_, row) => row.chargedPoints === undefined ? '—' : `${row.chargedPoints} 积分` },
              { title: '预计时效', key: 'days', render: (_, row) => row.estimatedTimeLabel ?? '—' },
              { title: '快照同步时间', dataIndex: 'syncedAt', key: 'syncedAt' },
              { title: '参考价有效至', dataIndex: 'quoteExpiresAt', key: 'expiresAt' },
            ]} />
          {pagination(props.resourcePage, props.onResourcePageChange)}
        </>}
      </Space> },
      { key: 'submissions', label: '投稿记录', children: <Space orientation="vertical" size="middle" className="submission-center-section">
        <Typography.Paragraph type="secondary">每条记录对应一家媒体，金额与积分为下单快照。实际消耗及退回请查看积分账单。</Typography.Paragraph>
        <Space wrap><Select aria-label="投稿状态" allowClear placeholder="全部投稿状态" value={props.status}
          options={MEDIA_SUBMISSION_STATUS_OPTIONS} onChange={props.onStatusChange} className="submission-center-status" />
          <Button aria-label="刷新投稿记录" onClick={props.onRefreshSubmissions} loading={props.submissionsRefreshing}>刷新投稿记录</Button></Space>
        {props.submissionsError ? <ErrorState {...props.submissionsError} onRetry={props.onRefreshSubmissions} /> : <>
          {props.submissionsLoading ? <PageLoading label="正在加载投稿记录…" /> : props.submissions.length === 0 ? <Empty description="暂无投稿记录" /> :
            <ul className="submission-center-orders">{props.submissions.map((row) => <li key={row.id}>
              <div className="submission-center-order-heading"><Space wrap><Typography.Text strong>{row.mediaName}</Typography.Text><Tag>{row.statusLabel}</Tag></Space>
                <Space wrap><Button onClick={() => props.onOpenSubmission(row.id)} aria-label={`查看投稿 ${row.id}`}>查看详情</Button><Button type="link" onClick={() => props.onOpenArticle(row.articleId)} aria-label={`查看投稿 ${row.id} 的原文章`}>查看原文章</Button></Space></div>
              <dl className="submission-center-order-facts">
                <div><dt>投稿订单</dt><dd>{row.id}</dd></div>
                <div><dt>下单金额（元）</dt><dd>{row.saleAmountLabel}</dd></div>
                <div><dt>下单积分</dt><dd>{row.chargedPoints === undefined ? '—' : `${row.chargedPoints} 积分`}</dd></div>
                <div><dt>投稿时间</dt><dd>{row.createdAt}</dd></div>
              </dl>
              <PublishedMediaLinks urls={row.publishedUrls} published={row.status === 'PUBLISHED'} />
            </li>)}</ul>}
          {pagination(props.submissionPage, props.onSubmissionPageChange)}
        </>}
      </Space> },
    ]} />
    <Drawer open={props.detailOpen} title="投稿详情" aria-label="投稿详情" onClose={() => props.onOpenSubmission(undefined)} size="large">
      {props.detailError ? <ErrorState {...props.detailError} onRetry={props.onRefreshDetail} /> : props.detailLoading ? <PageLoading label="正在加载投稿详情…" /> : props.detail ? <Space orientation="vertical" size="middle" className="submission-center-section">
        <Descriptions column={1} items={[
          { key: 'id', label: '投稿订单', children: props.detail.id },
          { key: 'media', label: '媒体', children: props.detail.mediaName },
          { key: 'status', label: '状态', children: props.detail.statusLabel },
          { key: 'amount', label: '下单金额', children: props.detail.saleAmountLabel },
          { key: 'points', label: '下单积分', children: props.detail.chargedPoints === undefined ? '—' : `${props.detail.chargedPoints} 积分` },
          { key: 'time', label: '状态更新时间', children: props.detail.updatedAt },
          { key: 'remark', label: '备注', children: props.detail.remark ?? '—' },
        ]} />
        <PublishedMediaLinks urls={props.detail.publishedUrls} published={props.detail.status === 'PUBLISHED'} />
        {props.detail.errorCode ? <Alert type="warning" title={props.detail.errorCode} /> : null}
        {props.detail.status === 'SUBMIT_UNKNOWN' || props.detail.status === 'RECONCILING' ? <Alert type="warning" title="投稿结果仍在核对，请勿重复下单。" /> : null}
        <Space wrap><Button aria-label="刷新当前记录" onClick={props.onRefreshDetail} loading={props.detailRefreshing}>刷新当前记录</Button>
          <Button onClick={() => { if (props.detail) props.onOpenArticle(props.detail.articleId) }}>查看原文章</Button></Space>
        <Typography.Title level={5}>状态记录</Typography.Title>
        {props.detail.events.length === 0 ? <Typography.Text type="secondary">暂无状态记录</Typography.Text> :
          <ul>{props.detail.events.map((event) => <li key={event.id}>{event.occurredAt} · {event.statusLabel}</li>)}</ul>}
      </Space> : null}
    </Drawer>
  </section>
}

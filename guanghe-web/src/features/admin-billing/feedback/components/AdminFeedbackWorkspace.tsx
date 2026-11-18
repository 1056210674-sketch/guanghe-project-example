import {
  MessageOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Input,
  Pagination,
  Select,
  Skeleton,
  Tag,
  Typography,
} from 'antd'
import { useState } from 'react'
import type { AdminErrorView } from '../../format'
import { formatDateTime, maskUserId } from '../../format'
import {
  FEEDBACK_CATEGORY_LABELS,
  FEEDBACK_STATUS_FILTER_OPTIONS,
  FEEDBACK_STATUS_META,
  feedbackForwardStatuses,
  feedbackStatusIsTerminal,
  feedbackTransitionNote,
  type AdminFeedbackFiltersView,
  type AdminFeedbackHandleDraft,
  type AdminFeedbackHandleResultView,
  type AdminFeedbackRowView,
  type FeedbackHandleStatus,
} from '../types'

function ErrorNotice({ error }: { error?: AdminErrorView | null }) {
  if (!error) return null
  return (
    <Alert
      type="error"
      showIcon
      title={error.message}
      description={error.traceId ? `traceId：${error.traceId}` : undefined}
    />
  )
}

function text(value: string | undefined): string {
  return value && value.trim().length > 0 ? value : '—'
}

/** An author is never invented: the published row may simply not carry one. */
function authorText(authorId: string | undefined): string {
  return authorId ? maskUserId(authorId) : '—'
}

function replyText(reply: string | undefined): string {
  return reply ? reply : '未回复'
}

interface FeedbackDetailProps {
  item: AdminFeedbackRowView
  pending: boolean
  actionError?: AdminErrorView | null
  actionResult?: AdminFeedbackHandleResultView | null
  onHandle: (draft: AdminFeedbackHandleDraft) => void
}

function FeedbackDetail({ item, pending, actionError, actionResult, onHandle }: FeedbackDetailProps) {
  const forwardStatuses = feedbackForwardStatuses(item.status)
  const [nextStatus, setNextStatus] = useState<FeedbackHandleStatus | undefined>(forwardStatuses[0])
  const [reply, setReply] = useState('')

  // The row can be refetched under an open drawer; a choice the current status no longer allows is
  // dropped rather than submitted as a transition the server would refuse.
  if (nextStatus !== undefined && !forwardStatuses.includes(nextStatus)) {
    setNextStatus(forwardStatuses[0])
  }

  const replyMissing = reply.trim().length === 0
  const terminal = feedbackStatusIsTerminal(item.status)
  const canSubmit = !terminal && nextStatus !== undefined && !replyMissing && !pending
  const submit = () => {
    if (!canSubmit || nextStatus === undefined) return
    onHandle({ feedbackId: item.id, status: nextStatus, reply: reply.trim() })
  }

  return (
    <div className="admin-detail-stack admin-observability-detail">
      <section>
        <Typography.Title level={3}>反馈内容</Typography.Title>
        <dl className="admin-detail-list">
          <div>
            <dt>分类</dt>
            <dd>{FEEDBACK_CATEGORY_LABELS[item.category]}</dd>
          </div>
          <div>
            <dt>当前状态</dt>
            <dd>
              <Tag color={FEEDBACK_STATUS_META[item.status].color}>
                {FEEDBACK_STATUS_META[item.status].label}
              </Tag>
            </dd>
          </div>
          <div>
            <dt>用户</dt>
            <dd>{authorText(item.authorId)}</dd>
          </div>
          <div>
            <dt>联系方式</dt>
            <dd>{text(item.contact)}</dd>
          </div>
          <div>
            <dt>提交时间</dt>
            <dd>{formatDateTime(item.createdAt)}</dd>
          </div>
          <div>
            <dt>处理时间</dt>
            <dd>{item.handledAt ? formatDateTime(item.handledAt) : '未处理'}</dd>
          </div>
        </dl>
        {/* User-written text: rendered as text, never as HTML and never as a link. */}
        <p className="admin-observability-text">{item.content}</p>
      </section>

      <section>
        <Typography.Title level={3}>已记录回复</Typography.Title>
        {item.reply ? (
          <p className="admin-observability-text">{item.reply}</p>
        ) : (
          <Typography.Text type="secondary">尚未回复，提交人还看不到任何答复。</Typography.Text>
        )}
      </section>

      <section>
        <Typography.Title level={3}>处理</Typography.Title>
        {terminal ? (
          <Alert
            type="info"
            showIcon
            title="已关闭的反馈不可再变更"
            description="关闭是终态：这里不提供状态选择，也不提供补写回复的入口。若仍需答复用户，请通过其他已开通的渠道联系。"
          />
        ) : (
          <div className="admin-modal-form">
            <Alert type="info" showIcon title={feedbackTransitionNote(item.status)} />
            <label>
              <span>目标状态</span>
              <Select
                aria-label="目标状态"
                value={nextStatus}
                options={forwardStatuses.map((status) => ({
                  value: status,
                  label: FEEDBACK_STATUS_META[status].label,
                }))}
                onChange={(value: FeedbackHandleStatus) => setNextStatus(value)}
              />
            </label>
            <label>
              <span>回复（提交人可见，必填）</span>
              <Input.TextArea
                aria-label="反馈回复"
                value={reply}
                maxLength={2000}
                placeholder="请输入将展示给提交人的回复内容"
                onChange={(event) => setReply(event.target.value)}
              />
            </label>
            <Alert
              type="warning"
              showIcon
              title="回复是提交人唯一能看到的内容，因此为必填"
              description="服务端会拒绝空回复；填写前不能提交，也不存在仅内部可见的备注通道。"
            />
            <Button type="primary" disabled={!canSubmit} onClick={submit}>
              {pending ? '提交中…' : '提交处理'}
            </Button>
            {replyMissing ? (
              <Typography.Text type="secondary">
                提交处理前必须填写非空回复，当前回复为空，因此按钮不可用。
              </Typography.Text>
            ) : null}
          </div>
        )}
        {actionError ? <ErrorNotice error={actionError} /> : null}
        {actionResult && actionResult.feedbackId === item.id ? (
          <Alert
            type="success"
            showIcon
            title={`服务端已记录处理结果：${FEEDBACK_STATUS_META[actionResult.status].label}`}
          />
        ) : null}
      </section>
    </div>
  )
}

export interface AdminFeedbackWorkspaceProps {
  items: readonly AdminFeedbackRowView[]
  total: number
  page: number
  pageSize: number
  loading?: boolean
  error?: AdminErrorView | null
  filters: AdminFeedbackFiltersView
  pendingFeedbackId?: string
  actionError?: AdminErrorView | null
  actionResult?: AdminFeedbackHandleResultView | null
  onFiltersChange: (filters: AdminFeedbackFiltersView) => void
  onPageChange: (page: number) => void
  onHandle: (draft: AdminFeedbackHandleDraft) => void
  onRetry?: () => void
}

export function AdminFeedbackWorkspace({
  items,
  total,
  page,
  pageSize,
  loading = false,
  error,
  filters,
  pendingFeedbackId,
  actionError,
  actionResult,
  onFiltersChange,
  onPageChange,
  onHandle,
  onRetry,
}: AdminFeedbackWorkspaceProps) {
  const [draft, setDraft] = useState<AdminFeedbackFiltersView>(filters)
  const [selectedId, setSelectedId] = useState<string>()
  const selected = items.find((item) => item.id === selectedId)
  const filtered = Boolean(filters.userId?.trim()) || Boolean(filters.status && filters.status !== 'ALL')

  return (
    <section className="admin-workspace" aria-labelledby="admin-feedback-title">
      <header className="admin-workspace__heading">
        <div>
          <Typography.Title id="admin-feedback-title" level={1}>
            用户反馈
          </Typography.Title>
          <Typography.Text type="secondary">
            查询用户提交的反馈并给出答复；状态只能向前推进，回复即提交人看到的答复。
          </Typography.Text>
        </div>
        <Tag icon={<SafetyCertificateOutlined />} color="red">
          管理员专用
        </Tag>
      </header>

      <Card className="admin-data-card" variant="borderless">
        <form
          className="admin-filter-bar"
          onSubmit={(event) => {
            event.preventDefault()
            onFiltersChange(draft)
          }}
        >
          <Input
            allowClear
            prefix={<SearchOutlined />}
            aria-label="用户 ID"
            placeholder="用户 ID（精确匹配）"
            value={draft.userId ?? ''}
            onChange={(event) => setDraft({ ...draft, userId: event.target.value })}
          />
          <Select
            aria-label="反馈状态"
            value={draft.status ?? 'ALL'}
            options={[
              { value: 'ALL', label: '全部状态' },
              ...FEEDBACK_STATUS_FILTER_OPTIONS.map((status) => ({
                value: status,
                label: FEEDBACK_STATUS_META[status].label,
              })),
            ]}
            onChange={(status) => setDraft({ ...draft, status })}
          />
          <Button type="primary" htmlType="submit">
            查询
          </Button>
          <Button
            disabled={!draft.userId && (!draft.status || draft.status === 'ALL')}
            onClick={() => {
              setDraft({})
              onFiltersChange({})
            }}
          >
            重置
          </Button>
        </form>
      </Card>

      <ErrorNotice error={error} />
      <ErrorNotice error={actionError} />
      {actionResult ? (
        <Alert
          type="success"
          showIcon
          title={`已提交处理：反馈 ${actionResult.feedbackId} → ${FEEDBACK_STATUS_META[actionResult.status].label}`}
        />
      ) : null}

      <Card className="admin-data-card" variant="borderless">
        {loading ? (
          <Skeleton active paragraph={{ rows: 6 }} />
        ) : items.length === 0 ? (
          <Empty
            image={<MessageOutlined />}
            description={
              filtered ? '没有符合筛选条件的用户反馈' : '暂无用户反馈记录'
            }
          />
        ) : (
          <div className="admin-table-scroll">
            <table className="admin-data-table" aria-label="用户反馈列表">
              <thead>
                <tr>
                  <th scope="col">分类</th>
                  <th scope="col">内容</th>
                  <th scope="col">用户</th>
                  <th scope="col">联系方式</th>
                  <th scope="col">状态</th>
                  <th scope="col">提交时间</th>
                  <th scope="col">处理时间</th>
                  <th scope="col">回复</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr key={item.id}>
                    <td>{FEEDBACK_CATEGORY_LABELS[item.category]}</td>
                    <td className="admin-observability-text">{item.content}</td>
                    <td>{authorText(item.authorId)}</td>
                    <td>{text(item.contact)}</td>
                    <td>
                      <Tag color={FEEDBACK_STATUS_META[item.status].color}>
                        {FEEDBACK_STATUS_META[item.status].label}
                      </Tag>
                    </td>
                    <td>{formatDateTime(item.createdAt)}</td>
                    <td>{item.handledAt ? formatDateTime(item.handledAt) : '未处理'}</td>
                    <td className="admin-observability-text">{replyText(item.reply)}</td>
                    <td>
                      <Button
                        type="link"
                        onClick={() => {
                          setSelectedId(item.id)
                        }}
                      >
                        {feedbackStatusIsTerminal(item.status) ? '查看' : '处理'}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {!loading && !error && total > 0 ? (
          <div className="admin-observability-footer">
            <span>共 {total} 条</span>
            <Pagination
              current={page}
              pageSize={pageSize}
              total={total}
              showSizeChanger={false}
              onChange={onPageChange}
            />
          </div>
        ) : null}
        {error && onRetry ? <Button onClick={onRetry}>重新加载</Button> : null}
        <p className="admin-observability-note">
          反馈内容为提交人填写的原文，按纯文本展示，不解析 HTML、不跳转链接；用户标识按脱敏规则展示，列表未返回提交用户时显示「—」，筛选仍按完整 userId 精确匹配。
        </p>
      </Card>

      <Drawer
        title={selected ? `反馈 ${selected.id}` : '反馈详情'}
        size="min(720px, 94vw)"
        open={Boolean(selected)}
        onClose={() => setSelectedId(undefined)}
      >
        {selected ? (
          <FeedbackDetail
            key={selected.id}
            item={selected}
            pending={pendingFeedbackId === selected.id}
            actionError={actionError}
            actionResult={actionResult}
            onHandle={onHandle}
          />
        ) : null}
      </Drawer>

      <Typography.Text type="secondary">
        状态推进与回复由服务端写入并留痕；此处不提供删除、撤回或状态回退入口。
      </Typography.Text>
    </section>
  )
}

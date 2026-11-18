import {
  DatabaseOutlined,
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
import { formatDateTime } from '../../format'
import {
  AUDIT_ACTOR_TYPE_LABELS,
  AUDIT_FILTER_ALL,
  AUDIT_OUTCOME_META,
  auditDetailsText,
  auditFilterOptions,
  type AdminAuditEventRowView,
  type AdminAuditFiltersView,
  type AuditActorType,
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

/** A non-user actor is identified by its reference; a user actor by its id. Neither is invented. */
function actorText(item: AdminAuditEventRowView): string {
  if (item.actorType === 'USER' || item.actorType === 'ADMIN') return text(item.actorUserId)
  return text(item.actorRef)
}

function targetText(item: AdminAuditEventRowView): string {
  const targetId = item.targetId ? ` · ${item.targetId}` : ''
  return `${item.targetType}${targetId}`
}

export interface AdminAuditWorkspaceProps {
  items: readonly AdminAuditEventRowView[]
  total: number
  page: number
  pageSize: number
  loading?: boolean
  error?: AdminErrorView | null
  filters: AdminAuditFiltersView
  onFiltersChange: (filters: AdminAuditFiltersView) => void
  onPageChange: (page: number) => void
  onRetry?: () => void
}

/**
 * Read-only in the strongest sense: there is no edit, no delete and no export, because the API
 * exposes none. The empty state never falls back to sample rows.
 */
export function AdminAuditWorkspace({
  items,
  total,
  page,
  pageSize,
  loading = false,
  error,
  filters,
  onFiltersChange,
  onPageChange,
  onRetry,
}: AdminAuditWorkspaceProps) {
  const [draft, setDraft] = useState<AdminAuditFiltersView>(filters)
  const [selectedId, setSelectedId] = useState<string>()
  const selected = items.find((item) => item.id === selectedId)
  const hasFilter = (value: AdminAuditFiltersView) =>
    Boolean(
      (value.actorType && value.actorType !== AUDIT_FILTER_ALL) ||
        (value.targetType && value.targetType !== AUDIT_FILTER_ALL) ||
        value.targetId?.trim() ||
        (value.eventType && value.eventType !== AUDIT_FILTER_ALL),
    )
  const filtered = hasFilter(filters)
  const actorTypes = auditFilterOptions(items, 'actorType')
  const targetTypes = auditFilterOptions(items, 'targetType')
  const eventTypes = auditFilterOptions(items, 'eventType')

  return (
    <section className="admin-workspace admin-observability" aria-labelledby="admin-audit-title">
      <header className="admin-workspace__heading">
        <div>
          <Typography.Title id="admin-audit-title" level={1}>
            操作日志
          </Typography.Title>
          <Typography.Text type="secondary">
            记录平台关键操作与外部回调的只追加审计事件，只读、不可编辑、不可删除。
          </Typography.Text>
        </div>
        <Tag icon={<SafetyCertificateOutlined />} color="red">
          管理员只读
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
          <Select
            aria-label="操作者类型"
            value={draft.actorType ?? AUDIT_FILTER_ALL}
            options={[
              { value: AUDIT_FILTER_ALL, label: '全部操作者类型' },
              ...actorTypes.map((value) => ({
                value,
                label: `${value}（${AUDIT_ACTOR_TYPE_LABELS[value as AuditActorType] ?? value}）`,
              })),
            ]}
            onChange={(actorType: string) =>
              setDraft({ ...draft, actorType: actorType as AdminAuditFiltersView['actorType'] })
            }
          />
          <Select
            aria-label="事件类型"
            value={draft.eventType ?? AUDIT_FILTER_ALL}
            options={[
              { value: AUDIT_FILTER_ALL, label: '全部事件类型' },
              ...eventTypes.map((value) => ({ value, label: value })),
            ]}
            onChange={(eventType: string) => setDraft({ ...draft, eventType })}
          />
          <Select
            aria-label="对象类型"
            value={draft.targetType ?? AUDIT_FILTER_ALL}
            options={[
              { value: AUDIT_FILTER_ALL, label: '全部对象类型' },
              ...targetTypes.map((value) => ({ value, label: value })),
            ]}
            onChange={(targetType: string) => setDraft({ ...draft, targetType })}
          />
          <Input
            allowClear
            prefix={<SearchOutlined />}
            aria-label="对象编号"
            placeholder="对象编号（精确匹配）"
            value={draft.targetId ?? ''}
            onChange={(event) => setDraft({ ...draft, targetId: event.target.value })}
          />
          <Button type="primary" htmlType="submit">
            查询
          </Button>
          <Button
            disabled={!hasFilter(draft) && !filtered}
            onClick={() => {
              setDraft({})
              onFiltersChange({})
            }}
          >
            重置
          </Button>
        </form>
        <p className="admin-observability-note">
          事件类型、操作者类型与对象类型下拉项来自当前响应中真实出现的取值，不预先虚构未出现的枚举值。
        </p>
      </Card>

      <ErrorNotice error={error} />

      <Card className="admin-data-card" variant="borderless">
        {loading ? (
          <Skeleton active paragraph={{ rows: 8 }} />
        ) : items.length === 0 ? (
          <Empty
            image={<DatabaseOutlined />}
            description={
              filtered ? '操作日志中没有匹配的记录' : '操作日志暂无记录'
            }
          />
        ) : (
          <div className="admin-table-scroll">
            <table className="admin-data-table admin-observability-records" aria-label="操作日志列表">
              <thead>
                <tr>
                  <th scope="col">事件号</th>
                  <th scope="col">actorType</th>
                  <th scope="col">操作人</th>
                  <th scope="col">对象</th>
                  <th scope="col">eventType</th>
                  <th scope="col">outcome</th>
                  <th scope="col">traceId</th>
                  <th scope="col">发生时间</th>
                  <th scope="col">详情</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr key={item.id}>
                    <td>
                      <strong>{item.id}</strong>
                    </td>
                    <td>{item.actorType}</td>
                    <td className="admin-observability-text">{actorText(item)}</td>
                    <td className="admin-observability-text">{targetText(item)}</td>
                    <td>{item.eventType}</td>
                    <td>
                      <Tag color={AUDIT_OUTCOME_META[item.outcome].color}>
                        {AUDIT_OUTCOME_META[item.outcome].label}
                      </Tag>
                    </td>
                    <td>
                      <code>{item.traceId}</code>
                    </td>
                    <td>{formatDateTime(item.createdAt)}</td>
                    <td>
                      <Button type="link" onClick={() => setSelectedId(item.id)}>
                        查看详情
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
      </Card>

      <Drawer
        title={selected ? `审计事件 ${selected.id}` : '审计事件详情'}
        size="min(820px, 94vw)"
        open={Boolean(selected)}
        onClose={() => setSelectedId(undefined)}
      >
        {selected ? (
          <div className="admin-observability-detail">
            <dl className="admin-detail-list">
              <div>
                <dt>事件号</dt>
                <dd>{selected.id}</dd>
              </div>
              <div>
                <dt>actorType</dt>
                <dd>{selected.actorType}</dd>
              </div>
              <div>
                <dt>操作人</dt>
                <dd>{actorText(selected)}</dd>
              </div>
              <div>
                <dt>操作人 user</dt>
                <dd>{text(selected.actorUserId)}</dd>
              </div>
              <div>
                <dt>操作人 ref</dt>
                <dd>{text(selected.actorRef)}</dd>
              </div>
              <div>
                <dt>对象</dt>
                <dd>{targetText(selected)}</dd>
              </div>
              <div>
                <dt>eventType</dt>
                <dd>{selected.eventType}</dd>
              </div>
              <div>
                <dt>outcome</dt>
                <dd>
                  <Tag color={AUDIT_OUTCOME_META[selected.outcome].color}>
                    {AUDIT_OUTCOME_META[selected.outcome].label}
                  </Tag>
                </dd>
              </div>
              <div>
                <dt>traceId</dt>
                <dd>
                  <code>{selected.traceId}</code>
                </dd>
              </div>
              <div>
                <dt>发生时间</dt>
                <dd>{formatDateTime(selected.createdAt)}</dd>
              </div>
            </dl>
            <section>
              <Typography.Title level={3}>details（服务端脱敏摘要）</Typography.Title>
              {selected.details.trim().length > 0 ? (
                // Arbitrary server text: rendered as text, never parsed as markup.
                <pre aria-label="审计事件 details">{auditDetailsText(selected.details)}</pre>
              ) : (
                <Typography.Text type="secondary">该事件没有记录 details 摘要。</Typography.Text>
              )}
            </section>
            <Alert
              type="info"
              showIcon
              title="日志为只追加记录，只读，不可编辑或删除"
              description="details 是服务端写入并已脱敏的受限摘要（数据库上限 16 KiB）：不记录密码、令牌、密钥与完整回调报文，此处也不会把其中的文本当作 HTML 渲染。"
            />
          </div>
        ) : null}
      </Drawer>
    </section>
  )
}

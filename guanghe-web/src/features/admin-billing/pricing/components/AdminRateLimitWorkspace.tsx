import { EditOutlined, ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Empty,
  InputNumber,
  Modal,
  Space,
  Switch,
  Tag,
  Typography,
} from 'antd'
import { useState } from 'react'
import { ErrorState, PageLoading } from '../../../../components/GlobalState'
import { formatDateTime, maskUserId } from '../../format'
import type { AdminErrorView } from '../../format'
import {
  RATE_LIMIT_MAX_ATTEMPTS_MAX,
  RATE_LIMIT_MAX_ATTEMPTS_MIN,
  RATE_LIMIT_NOT_CONFIGURED_COPY,
  RATE_LIMIT_SERVER_ENFORCED_NOTE,
  RATE_LIMIT_WINDOW_SECONDS_MAX,
  RATE_LIMIT_WINDOW_SECONDS_MIN,
  rateLimitActionLabel,
  toRateLimitRuleDraft,
  validateRateLimitForm,
} from '../types'
import type {
  RateLimitAction,
  RateLimitRuleDraft,
  RateLimitRuleFormState,
  RateLimitRuleView,
} from '../types'

export interface AdminRateLimitWorkspaceProps {
  rules: readonly RateLimitRuleView[]
  loading: boolean
  /** Why the list itself could not be read. */
  error: AdminErrorView | null
  /** What happened to the list after a rejected save, for example that it was refetched. */
  notice: string | null
  /** The server's own refusal of the save, shown inside the form that was refused. */
  formError: AdminErrorView | null
  /** The rule whose save is in flight, so only that row reports itself as busy. */
  pendingAction?: RateLimitAction
  onSave: (action: RateLimitAction, draft: RateLimitRuleDraft) => Promise<boolean>
  onRetry: () => void
}

const LIMIT_BOUNDS_COPY = `服务端强制范围：时间窗 ${RATE_LIMIT_WINDOW_SECONDS_MIN}–${RATE_LIMIT_WINDOW_SECONDS_MAX} 秒；最大次数 ${RATE_LIMIT_MAX_ATTEMPTS_MIN}–${RATE_LIMIT_MAX_ATTEMPTS_MAX} 次。`

const LIMIT_BOUNDS_FORM_COPY = `允许区间：时间窗 ${RATE_LIMIT_WINDOW_SECONDS_MIN}–${RATE_LIMIT_WINDOW_SECONDS_MAX} 秒，最大次数 ${RATE_LIMIT_MAX_ATTEMPTS_MIN}–${RATE_LIMIT_MAX_ATTEMPTS_MAX} 次；越界或版本号过期的保存会被服务端拒绝。`

interface RateLimitFormProps {
  rule: RateLimitRuleView
  pending: boolean
  error: AdminErrorView | null
  onClose: () => void
  onSave: (action: RateLimitAction, draft: RateLimitRuleDraft) => Promise<boolean>
}

function RateLimitFormModal({ rule, pending, error, onClose, onSave }: RateLimitFormProps) {
  const [draft, setDraft] = useState<RateLimitRuleFormState>({
    windowSeconds: rule.windowSeconds,
    maxAttempts: rule.maxAttempts,
    enabled: rule.enabled,
  })
  const validationMessage = validateRateLimitForm(draft)

  const submit = async () => {
    if (validationMessage || pending) return
    const succeeded = await onSave(rule.action, toRateLimitRuleDraft(draft))
    if (succeeded) onClose()
  }

  return (
    <Modal
      open
      title={`编辑频率限制 · ${rateLimitActionLabel(rule.action)}`}
      okText="保存"
      cancelText="取消"
      confirmLoading={pending}
      okButtonProps={{ disabled: Boolean(validationMessage) }}
      onOk={() => void submit()}
      onCancel={onClose}
    >
      <div className="admin-modal-form">
        {rule.configured ? (
          <Alert
            type="info"
            showIcon
            title={`当前已配置，服务端版本号 ${rule.version}`}
            description="保存时会带上该版本号作为 expectedVersion；若期间有人改过，服务端会拒绝本次保存并给出原因。"
          />
        ) : (
          <Alert
            type="warning"
            showIcon
            title="该动作尚未配置，服务端当前使用内置默认值"
            description="保存后会首次写入规则，expectedVersion 为 0。"
          />
        )}
        {error ? (
          <Alert
            type="error"
            showIcon
            title={error.message}
            description={error.traceId ? `traceId：${error.traceId}` : undefined}
          />
        ) : null}
        {validationMessage ? <Alert type="warning" showIcon title={validationMessage} /> : null}
        <label>
          <span>时间窗（秒）</span>
          <InputNumber
            precision={0}
            disabled={pending}
            aria-label="时间窗（秒）"
            value={draft.windowSeconds}
            onChange={(value) => setDraft({ ...draft, windowSeconds: value })}
          />
        </label>
        <label>
          <span>最大次数</span>
          <InputNumber
            precision={0}
            disabled={pending}
            aria-label="最大次数"
            value={draft.maxAttempts}
            onChange={(value) => setDraft({ ...draft, maxAttempts: value })}
          />
        </label>
        <label>
          <span>是否启用</span>
          <Switch
            disabled={pending}
            aria-label="是否启用"
            checked={draft.enabled}
            checkedChildren="启用"
            unCheckedChildren="停用"
            onChange={(enabled) => setDraft({ ...draft, enabled })}
          />
        </label>
        <Alert type="info" showIcon title={LIMIT_BOUNDS_FORM_COPY} />
      </div>
    </Modal>
  )
}

export function AdminRateLimitWorkspace(props: AdminRateLimitWorkspaceProps) {
  const [editing, setEditing] = useState<RateLimitRuleView | null>(null)

  return (
    <section className="admin-workspace" aria-labelledby="admin-rate-limit-title">
      <header className="admin-workspace__heading">
        <div>
          <Typography.Title id="admin-rate-limit-title" level={2}>
            频率限制
          </Typography.Title>
          <Typography.Text type="secondary">
            三类用户动作的时间窗与最大次数；未配置的动作由服务端默认值兜底
          </Typography.Text>
        </div>
        <Space>
          <Tag icon={<SafetyCertificateOutlined />} color="red">
            管理员专用
          </Tag>
          <Button icon={<ReloadOutlined />} onClick={props.onRetry}>
            重新加载
          </Button>
        </Space>
      </header>

      <Alert type="info" showIcon title={LIMIT_BOUNDS_COPY} description={RATE_LIMIT_SERVER_ENFORCED_NOTE} />

      {props.notice ? <Alert type="warning" showIcon title={props.notice} /> : null}

      <Card className="admin-data-card" variant="borderless">
        {props.loading ? (
          <PageLoading label="正在加载频率限制…" />
        ) : props.error ? (
          <ErrorState
            message={props.error.message}
            traceId={props.error.traceId}
            onRetry={props.onRetry}
          />
        ) : !props.rules.length ? (
          <Empty description="暂无频率限制规则" />
        ) : (
          <div className="admin-table-scroll">
            <table className="admin-data-table" aria-label="频率限制规则列表">
              <thead>
                <tr>
                  <th scope="col">动作</th>
                  <th scope="col">时间窗（秒）</th>
                  <th scope="col">最大次数</th>
                  <th scope="col">是否启用</th>
                  <th scope="col">配置状态</th>
                  <th scope="col">版本</th>
                  <th scope="col">修改人/时间</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                {props.rules.map((rule) => (
                  <tr key={rule.action}>
                    <td>
                      <strong>{rateLimitActionLabel(rule.action)}</strong>
                      <div>
                        <Typography.Text type="secondary">{rule.action}</Typography.Text>
                      </div>
                    </td>
                    <td title={rule.configured ? undefined : '服务端内置默认值'}>
                      {rule.windowSeconds.toLocaleString('zh-CN')}
                    </td>
                    <td title={rule.configured ? undefined : '服务端内置默认值'}>
                      {rule.maxAttempts.toLocaleString('zh-CN')}
                    </td>
                    <td>
                      <Tag color={rule.enabled ? 'success' : 'default'}>
                        {rule.enabled ? '启用' : '停用'}
                      </Tag>
                    </td>
                    <td>
                      {rule.configured ? (
                        <Tag color="blue">已配置</Tag>
                      ) : (
                        <Tag>{RATE_LIMIT_NOT_CONFIGURED_COPY}</Tag>
                      )}
                    </td>
                    <td>{rule.configured ? rule.version.toLocaleString('zh-CN') : '0（内置默认）'}</td>
                    <td>
                      <div>{rule.updatedBy ? maskUserId(rule.updatedBy) : '—'}</div>
                      <div>{formatDateTime(rule.updatedAt)}</div>
                    </td>
                    <td>
                      <Button
                        size="small"
                        icon={<EditOutlined />}
                        loading={props.pendingAction === rule.action}
                        onClick={() => setEditing(rule)}
                      >
                        编辑
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {editing ? (
        <RateLimitFormModal
          rule={editing}
          pending={props.pendingAction === editing.action}
          error={props.formError}
          onClose={() => setEditing(null)}
          onSave={props.onSave}
        />
      ) : null}
    </section>
  )
}

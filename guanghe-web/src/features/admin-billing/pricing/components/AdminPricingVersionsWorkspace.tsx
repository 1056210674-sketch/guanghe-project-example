import { DeleteOutlined, PlusOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  InputNumber,
  Modal,
  Pagination,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd'
import { useState } from 'react'
import { ErrorState, PageLoading } from '../../../../components/GlobalState'
import { formatCents, formatDateTime, maskUserId } from '../../format'
import type { AdminErrorView } from '../../format'
import {
  PRICING_ACTION_REASONS,
  PRICING_VERSION_STATUSES,
  PUBLISH_CONFIRM_NOTE,
  createPricingTierDraftRow,
  deriveBasePoints,
  deriveTotalPoints,
  formatRechargeRange,
  pricingStatusLabel,
  pricingStatusTone,
  toPricingTierDraftRow,
  toPricingVersionRequest,
  validatePricingVersionForm,
  yuanToCents,
} from '../types'
import type {
  PricingTierDraftRow,
  PricingVersionFormDraft,
  PricingVersionStatus,
  PricingVersionView,
} from '../types'
import type { CreatePricingVersionRequest } from '../../../../api/adminBillingApi'

export type PricingWorkspaceMode = 'versions' | 'gift-tiers'

export type PricingVersionFilter = 'ALL' | PricingVersionStatus

export type PricingMutationKind = 'create' | 'update' | 'publish' | 'retire'

export interface PricingPendingAction {
  kind: PricingMutationKind
  versionId?: string
}

export interface AdminPricingVersionsWorkspaceProps {
  mode: PricingWorkspaceMode
  versions: readonly PricingVersionView[]
  total: number
  page: number
  pageSize: number
  statusFilter: PricingVersionFilter
  listLoading: boolean
  listError: AdminErrorView | null
  /** Versions the 赠送积分 tab may inspect: the server list page, minus already retired versions. */
  giftVersions: readonly PricingVersionView[]
  giftVersionsTruncated: boolean
  giftVersionsLoading: boolean
  giftVersionsError: AdminErrorView | null
  selectedGiftVersionId?: string
  giftVersion?: PricingVersionView
  actionError: AdminErrorView | null
  formError: AdminErrorView | null
  pendingAction?: PricingPendingAction
  onStatusFilterChange: (filter: PricingVersionFilter) => void
  onPageChange: (page: number) => void
  onSelectGiftVersion: (versionId: string) => void
  onCreate: (request: CreatePricingVersionRequest) => Promise<boolean>
  onUpdate: (versionId: string, request: CreatePricingVersionRequest) => Promise<boolean>
  onPublish: (versionId: string) => void
  onRetire: (versionId: string) => void
  onRetryList: () => void
  onRetryGiftVersions: () => void
}

type FormTarget = { kind: 'create' } | { kind: 'edit'; version: PricingVersionView }

interface ConfirmTarget {
  kind: 'publish' | 'retire'
  version: PricingVersionView
}

function disabledActionNotes(version: PricingVersionView): string[] {
  const notes: string[] = []
  if (version.status !== 'DRAFT') {
    notes.push(PRICING_ACTION_REASONS.edit, PRICING_ACTION_REASONS.publish)
  }
  if (version.status === 'RETIRED') {
    notes.push(PRICING_ACTION_REASONS.retire)
  }
  return notes
}

function emptyFormDraft(): PricingVersionFormDraft {
  return {
    pointsPerYuan: null,
    minYuan: null,
    maxYuan: null,
    note: '',
    tiers: [createPricingTierDraftRow('tier-1')],
  }
}

function formDraftFrom(version: PricingVersionView): PricingVersionFormDraft {
  return {
    pointsPerYuan: version.pointsPerYuan,
    minYuan: version.minRechargeAmountCents / 100,
    maxYuan: version.maxRechargeAmountCents / 100,
    note: version.note ?? '',
    tiers: version.tiers.length
      ? version.tiers.map(toPricingTierDraftRow)
      : [createPricingTierDraftRow('tier-1')],
  }
}

interface TierEditorProps {
  rows: readonly PricingTierDraftRow[]
  pointsPerYuan: number | null
  disabled: boolean
  onChange: (rows: PricingTierDraftRow[]) => void
}

function TierEditor({ rows, pointsPerYuan, disabled, onChange }: TierEditorProps) {
  const update = (rowId: string, patch: Partial<PricingTierDraftRow>) => {
    onChange(rows.map((row) => (row.rowId === rowId ? { ...row, ...patch } : row)))
  }

  return (
    <div className="admin-detail-stack">
      <Typography.Title level={3}>充值档位</Typography.Title>
      <Alert
        type="info"
        showIcon
        title="档位只提交金额、赠送积分、标签和排序"
        description="档位的基础积分由服务端按积分/元推导，因此这里的基础积分与到账合计只是按当前输入预计，最终以服务端返回为准。"
      />
      {rows.map((row, index) => {
        const basePoints = deriveBasePoints(yuanToCents(row.amountYuan), pointsPerYuan)
        const totalPoints = deriveTotalPoints(basePoints, row.giftPoints)
        return (
          <div key={row.rowId} className="admin-detail-stack">
            <strong>档位 {index + 1}</strong>
            <label>
              <span>充值金额（元）</span>
              <InputNumber
                min={0}
                precision={0}
                disabled={disabled}
                aria-label={`档位 ${index + 1} 充值金额（元）`}
                value={row.amountYuan}
                onChange={(value) => update(row.rowId, { amountYuan: value })}
              />
            </label>
            <label>
              <span>赠送积分</span>
              <InputNumber
                min={0}
                precision={0}
                disabled={disabled}
                aria-label={`档位 ${index + 1} 赠送积分`}
                value={row.giftPoints}
                onChange={(value) => update(row.rowId, { giftPoints: value })}
              />
            </label>
            <label>
              <span>标签</span>
              <Input
                disabled={disabled}
                aria-label={`档位 ${index + 1} 标签`}
                value={row.label}
                placeholder="例如：标准档"
                onChange={(event) => update(row.rowId, { label: event.target.value })}
              />
            </label>
            <label>
              <span>排序</span>
              <InputNumber
                precision={0}
                disabled={disabled}
                aria-label={`档位 ${index + 1} 排序`}
                value={row.sortOrder}
                placeholder="留空按填写顺序"
                onChange={(value) => update(row.rowId, { sortOrder: value })}
              />
            </label>
            <Typography.Text type="secondary">
              预计基础积分：{basePoints === undefined ? '—（请填写整元金额与积分/元）' : basePoints}
              ；预计到账合计：
              {totalPoints === undefined ? '—（请填写整元金额与积分/元）' : totalPoints}
            </Typography.Text>
            <Button
              danger
              icon={<DeleteOutlined />}
              disabled={disabled || rows.length <= 1}
              aria-label={`删除档位 ${index + 1}`}
              onClick={() => onChange(rows.filter((item) => item.rowId !== row.rowId))}
            >
              删除该档位
            </Button>
            {rows.length <= 1 ? (
              <Typography.Text type="secondary">
                至少保留一个档位，服务端拒绝没有档位的版本
              </Typography.Text>
            ) : null}
          </div>
        )
      })}
      <Button
        icon={<PlusOutlined />}
        disabled={disabled}
        onClick={() =>
          onChange([...rows, createPricingTierDraftRow(`tier-${Date.now()}-${rows.length + 1}`)])
        }
      >
        新增档位
      </Button>
    </div>
  )
}

interface PricingVersionFormModalProps {
  target: FormTarget
  pending: boolean
  error: AdminErrorView | null
  onClose: () => void
  onSubmit: (request: CreatePricingVersionRequest) => Promise<boolean>
}

function PricingVersionFormModal({
  target,
  pending,
  error,
  onClose,
  onSubmit,
}: PricingVersionFormModalProps) {
  const initial = target.kind === 'edit' ? target.version : undefined
  const [draft, setDraft] = useState<PricingVersionFormDraft>(() =>
    initial ? formDraftFrom(initial) : emptyFormDraft(),
  )
  const validationMessage = validatePricingVersionForm(draft)

  const submit = async () => {
    if (validationMessage || pending) return
    const succeeded = await onSubmit(toPricingVersionRequest(draft))
    if (succeeded) onClose()
  }

  return (
    <Modal
      open
      title={initial ? `编辑草稿 v${initial.versionNo}` : '新建价格版本'}
      okText={initial ? '保存草稿' : '创建草稿'}
      cancelText="取消"
      confirmLoading={pending}
      okButtonProps={{ disabled: Boolean(validationMessage) }}
      onOk={() => void submit()}
      onCancel={onClose}
    >
      <div className="admin-modal-form">
        <Alert
          type="info"
          showIcon
          title="新建的版本是草稿"
          description="草稿不会影响任何付费页面；只有发布后才会生效，并自动停用被它替换的版本。"
        />
        {error ? (
          <Alert
            type="error"
            showIcon
            title={error.message}
            description={error.traceId ? `traceId：${error.traceId}` : undefined}
          />
        ) : null}
        {validationMessage ? (
          <Alert type="warning" showIcon title={validationMessage} />
        ) : null}
        <label>
          <span>积分/元（正整数）</span>
          <InputNumber
            min={1}
            precision={0}
            disabled={pending}
            aria-label="积分/元"
            value={draft.pointsPerYuan}
            placeholder="例如：100 表示 1 元兑换 100 积分"
            onChange={(value) => setDraft({ ...draft, pointsPerYuan: value })}
          />
        </label>
        <label>
          <span>最低充值金额（元）</span>
          <InputNumber
            min={0}
            precision={0}
            disabled={pending}
            aria-label="最低充值金额（元）"
            value={draft.minYuan}
            onChange={(value) => setDraft({ ...draft, minYuan: value })}
          />
        </label>
        <label>
          <span>最高充值金额（元）</span>
          <InputNumber
            min={0}
            precision={0}
            disabled={pending}
            aria-label="最高充值金额（元）"
            value={draft.maxYuan}
            onChange={(value) => setDraft({ ...draft, maxYuan: value })}
          />
        </label>
        <label>
          <span>备注（选填）</span>
          <Input.TextArea
            disabled={pending}
            aria-label="备注"
            value={draft.note}
            placeholder="记录本次定价的变更原因"
            onChange={(event) => setDraft({ ...draft, note: event.target.value })}
          />
        </label>
        <TierEditor
          rows={draft.tiers}
          pointsPerYuan={draft.pointsPerYuan}
          disabled={pending}
          onChange={(tiers) => setDraft({ ...draft, tiers })}
        />
        {initial ? (
          <Typography.Text type="secondary">
            提交时会带上读取到的版本号 v{initial.versionNo} 作为 expectedVersion，服务端会拒绝过期编辑。
          </Typography.Text>
        ) : null}
      </div>
    </Modal>
  )
}

function VersionRow({
  version,
  pendingAction,
  onEdit,
  onAsk,
}: {
  version: PricingVersionView
  pendingAction?: PricingPendingAction
  onEdit: (version: PricingVersionView) => void
  onAsk: (target: ConfirmTarget) => void
}) {
  const notes = disabledActionNotes(version)
  return (
    <tr>
      <td>
        <strong>v{version.versionNo}</strong>
      </td>
      <td>
        <Tag color={pricingStatusTone(version.status)}>{pricingStatusLabel(version.status)}</Tag>
      </td>
      <td>{version.pointsPerYuan.toLocaleString('zh-CN')}</td>
      <td>{formatRechargeRange(version.minRechargeAmountCents, version.maxRechargeAmountCents)}</td>
      <td>{version.note?.trim() || '—'}</td>
      <td>{maskUserId(version.createdBy)}</td>
      <td>
        <div>{version.publishedBy ? maskUserId(version.publishedBy) : '—'}</div>
        <div>{formatDateTime(version.publishedAt)}</div>
      </td>
      <td>
        <div>{formatDateTime(version.createdAt)}</div>
        <div>{formatDateTime(version.updatedAt)}</div>
      </td>
      <td>{version.tierCount.toLocaleString('zh-CN')}</td>
      <td>
        <Space size="small" wrap>
          <Button
            size="small"
            title={version.status === 'DRAFT' ? undefined : PRICING_ACTION_REASONS.edit}
            disabled={version.status !== 'DRAFT'}
            loading={pendingAction?.kind === 'update' && pendingAction.versionId === version.id}
            onClick={() => onEdit(version)}
          >
            编辑
          </Button>
          <Button
            size="small"
            title={version.status === 'DRAFT' ? undefined : PRICING_ACTION_REASONS.publish}
            disabled={version.status !== 'DRAFT'}
            loading={pendingAction?.kind === 'publish' && pendingAction.versionId === version.id}
            onClick={() => onAsk({ kind: 'publish', version })}
          >
            发布
          </Button>
          <Button
            size="small"
            danger
            title={version.status === 'RETIRED' ? PRICING_ACTION_REASONS.retire : undefined}
            disabled={version.status === 'RETIRED'}
            loading={pendingAction?.kind === 'retire' && pendingAction.versionId === version.id}
            onClick={() => onAsk({ kind: 'retire', version })}
          >
            停用
          </Button>
        </Space>
        {notes.length ? (
          <Typography.Text type="secondary">{notes.join('；')}</Typography.Text>
        ) : null}
      </td>
    </tr>
  )
}

function TierTable({ version }: { version: PricingVersionView }) {
  if (!version.tiers.length) {
    return <Empty description="该版本暂无充值档位" />
  }
  return (
    <div className="admin-table-scroll">
      <table className="admin-data-table" aria-label="充值档位列表">
        <thead>
          <tr>
            <th scope="col">充值金额</th>
            <th scope="col">基础积分</th>
            <th scope="col">赠送积分</th>
            <th scope="col">到账合计</th>
            <th scope="col">标签</th>
            <th scope="col">排序</th>
          </tr>
        </thead>
        <tbody>
          {version.tiers.map((tier) => (
            <tr key={tier.tierId}>
              <td>{formatCents(tier.amountCents)}</td>
              <td>{tier.basePoints.toLocaleString('zh-CN')}</td>
              <td>{tier.giftPoints.toLocaleString('zh-CN')}</td>
              <td>{tier.points.toLocaleString('zh-CN')}</td>
              <td>{tier.label}</td>
              <td>{tier.sortOrder}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function AdminPricingVersionsWorkspace(props: AdminPricingVersionsWorkspaceProps) {
  const [formTarget, setFormTarget] = useState<FormTarget | null>(null)
  const [confirmTarget, setConfirmTarget] = useState<ConfirmTarget | null>(null)
  const [retireReason, setRetireReason] = useState('')

  const formPending =
    formTarget?.kind === 'create'
      ? props.pendingAction?.kind === 'create'
      : props.pendingAction?.kind === 'update' &&
        props.pendingAction.versionId === formTarget?.version.id

  const submitForm = async (request: CreatePricingVersionRequest): Promise<boolean> => {
    if (!formTarget) return false
    if (formTarget.kind === 'create') return props.onCreate(request)
    return props.onUpdate(formTarget.version.id, request)
  }

  const closeForm = () => {
    setFormTarget(null)
  }

  const confirmAction = () => {
    if (!confirmTarget) return
    if (confirmTarget.kind === 'publish') props.onPublish(confirmTarget.version.id)
    else props.onRetire(confirmTarget.version.id)
    setConfirmTarget(null)
    setRetireReason('')
  }

  const giftVersion = props.giftVersion
  const giftEditable = giftVersion?.status === 'DRAFT'

  return (
    <section className="admin-workspace" aria-labelledby="admin-pricing-versions-title">
      <header className="admin-workspace__heading">
        <div>
          <Typography.Title id="admin-pricing-versions-title" level={2}>
            {props.mode === 'versions' ? '定价版本' : '赠送积分'}
          </Typography.Title>
          <Typography.Text type="secondary">
            {props.mode === 'versions'
              ? '草稿、生效与已停用的价格版本；发布后生效版本会被自动替换'
              : '按版本查看和调整充值档位的赠送积分'}
          </Typography.Text>
        </div>
        <Space>
          <Tag icon={<SafetyCertificateOutlined />} color="red">
            管理员专用
          </Tag>
          {props.mode === 'versions' ? (
            <Button
              type="primary"
              icon={<PlusOutlined />}
              loading={props.pendingAction?.kind === 'create'}
              disabled={props.pendingAction?.kind === 'create'}
              onClick={() => setFormTarget({ kind: 'create' })}
            >
              新建版本
            </Button>
          ) : null}
        </Space>
      </header>

      {props.actionError ? (
        <Alert
          type="error"
          showIcon
          title={props.actionError.message}
          description={props.actionError.traceId ? `traceId：${props.actionError.traceId}` : undefined}
        />
      ) : null}

      {props.mode === 'versions' ? (
        <Card className="admin-data-card" variant="borderless">
          <div className="admin-filter-bar">
            <Select<PricingVersionFilter>
              aria-label="版本状态筛选"
              value={props.statusFilter}
              options={[
                { value: 'ALL', label: '全部状态' },
                ...PRICING_VERSION_STATUSES.map((status) => ({
                  value: status,
                  label: pricingStatusLabel(status),
                })),
              ]}
              onChange={props.onStatusFilterChange}
            />
          </div>
          {props.listLoading ? (
            <PageLoading label="正在加载价格版本…" />
          ) : props.listError ? (
            <ErrorState
              message={props.listError.message}
              traceId={props.listError.traceId}
              onRetry={props.onRetryList}
            />
          ) : !props.versions.length ? (
            <Empty
              description={
                props.statusFilter === 'ALL'
                  ? '暂无价格版本'
                  : `${pricingStatusLabel(props.statusFilter)}状态下暂无价格版本`
              }
            />
          ) : (
            <>
              <div className="admin-table-scroll">
                <table className="admin-data-table" aria-label="价格版本列表">
                  <thead>
                    <tr>
                      <th scope="col">版本号</th>
                      <th scope="col">状态</th>
                      <th scope="col">积分/元</th>
                      <th scope="col">充值区间</th>
                      <th scope="col">备注</th>
                      <th scope="col">创建人</th>
                      <th scope="col">发布人/发布时间</th>
                      <th scope="col">创建/更新时间</th>
                      <th scope="col">档位数</th>
                      <th scope="col">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {props.versions.map((version) => (
                      <VersionRow
                        key={version.id}
                        version={version}
                        pendingAction={props.pendingAction}
                        onEdit={(target) => setFormTarget({ kind: 'edit', version: target })}
                        onAsk={setConfirmTarget}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
              <Pagination
                current={props.page}
                pageSize={props.pageSize}
                total={props.total}
                showSizeChanger={false}
                onChange={props.onPageChange}
              />
            </>
          )}
        </Card>
      ) : (
        <Card className="admin-data-card" variant="borderless">
          {props.giftVersionsLoading ? (
            <PageLoading label="正在加载价格版本…" />
          ) : props.giftVersionsError ? (
            <ErrorState
              message={props.giftVersionsError.message}
              traceId={props.giftVersionsError.traceId}
              onRetry={props.onRetryGiftVersions}
            />
          ) : !props.giftVersions.length ? (
            <Empty description="暂无价格版本，充值功能尚未配置" />
          ) : (
            <>
              <div className="admin-filter-bar">
                <label>
                  <span>版本</span>
                  <Select<string>
                    aria-label="选择价格版本"
                    value={props.selectedGiftVersionId}
                    options={props.giftVersions.map((version) => ({
                      value: version.id,
                      label: `v${version.versionNo} · ${pricingStatusLabel(version.status)}`,
                    }))}
                    onChange={props.onSelectGiftVersion}
                  />
                </label>
                <Button
                  disabled={!giftEditable}
                  title={giftEditable ? undefined : PRICING_ACTION_REASONS.giftEdit}
                  onClick={() => {
                    if (giftVersion) setFormTarget({ kind: 'edit', version: giftVersion })
                  }}
                >
                  编辑该版本档位
                </Button>
              </div>
              {props.giftVersionsTruncated ? (
                <Alert
                  type="info"
                  showIcon
                  title="仅列出最近一个版本批次"
                  description="该列表只包含服务端返回的第一页价格版本；更早的版本请在上方“定价版本”中按状态筛选查看。"
                />
              ) : null}
              {!giftEditable ? (
                <Alert
                  type="warning"
                  showIcon
                  title={PRICING_ACTION_REASONS.giftEdit}
                  description="生效中的版本被订单引用，不能就地修改；请新建一个草稿版本，发布后替换掉当前生效版本。"
                />
              ) : null}
              {giftVersion ? <TierTable version={giftVersion} /> : null}
            </>
          )}
        </Card>
      )}

      {formTarget ? (
        <PricingVersionFormModal
          target={formTarget}
          pending={Boolean(formPending)}
          error={props.formError}
          onClose={closeForm}
          onSubmit={submitForm}
        />
      ) : null}

      <Modal
        open={Boolean(confirmTarget)}
        title={confirmTarget?.kind === 'publish' ? '确认发布价格版本' : '确认停用价格版本'}
        okText={confirmTarget?.kind === 'publish' ? '确认发布' : '确认停用'}
        cancelText="取消"
        okButtonProps={{
          danger: confirmTarget?.kind === 'retire',
          disabled:
            confirmTarget?.kind === 'retire' ? retireReason.trim().length < 2 : false,
        }}
        confirmLoading={Boolean(
          confirmTarget &&
            props.pendingAction &&
            props.pendingAction.versionId === confirmTarget.version.id &&
            (props.pendingAction.kind === 'publish' || props.pendingAction.kind === 'retire'),
        )}
        onOk={confirmAction}
        onCancel={() => setConfirmTarget(null)}
      >
        <div className="admin-modal-form">
          {confirmTarget?.kind === 'publish' ? (
            <Alert type="warning" showIcon title={PUBLISH_CONFIRM_NOTE} />
          ) : (
            <>
              <Alert
                type="warning"
                showIcon
                title="停用后该版本不能再被新订单引用"
                description="已经按该版本结算的订单不会被改写；如果需要继续提供充值，请改发布新的版本。"
              />
              <label>
                <span>停用原因</span>
                <Input.TextArea
                  aria-label="停用原因"
                  value={retireReason}
                  placeholder="请输入至少 2 个字符的停用原因"
                  onChange={(event) => setRetireReason(event.target.value)}
                />
              </label>
            </>
          )}
        </div>
      </Modal>
    </section>
  )
}

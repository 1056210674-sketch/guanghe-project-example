import {
  Alert,
  Button,
  Card,
  Descriptions,
  Input,
  InputNumber,
  Radio,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useRef, useState } from 'react'
import { errorText } from '../live/commercialError'
import {
  useCancelInvoiceApplication,
  useCreateInvoiceApplication,
  useInvoiceApplications,
  useInvoiceCapability,
  useInvoiceApplication,
} from '../live/commercialQueries'
import {
  toInvoiceApplicationView,
  toInvoiceCapabilityView,
  type InvoiceApplicationView,
} from '../types'

const TAX_NUMBER_PATTERN = /^[0-9A-Z]{15,20}$/
const EMAIL_PATTERN = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

interface InvoiceDraft {
  invoiceType: 'VAT_NORMAL' | 'VAT_SPECIAL'
  titleType: 'PERSONAL' | 'COMPANY'
  title: string
  taxNumber: string
  amountYuan: number | null
  deliveryEmail: string
  remark: string
}

const EMPTY_DRAFT: InvoiceDraft = {
  invoiceType: 'VAT_NORMAL',
  titleType: 'PERSONAL',
  title: '',
  taxNumber: '',
  amountYuan: null,
  deliveryEmail: '',
  remark: '',
}

/**
 * The 发票申请 tab.
 *
 * The rules the form enforces are the server's own: a company title needs a tax number, a personal
 * one must not carry one, the tax number is 15–20 uppercase alphanumerics. Catching them here means
 * the user is not sent a request that was guaranteed to be refused — and the reason the submit button
 * is unavailable is written out instead of left to be guessed.
 *
 * An application that has been approved is labelled 审核通过（未开具）: no PDF is produced and no
 * invoice is issued in this build, so nothing here claims an invoice exists.
 */
export function InvoiceConsole({ targetId }: { targetId?: string }) {
  const capabilityQuery = useInvoiceCapability()
  const targetQuery = useInvoiceApplication(targetId)
  const target = targetQuery.data ? toInvoiceApplicationView(targetQuery.data) : undefined
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const applicationsQuery = useInvoiceApplications({ page, size: pageSize })
  const createApplication = useCreateInvoiceApplication()
  const cancelApplication = useCancelInvoiceApplication()

  const [draft, setDraft] = useState<InvoiceDraft>(EMPTY_DRAFT)
  const [cancelTarget, setCancelTarget] = useState<string>()
  const [cancelNotice, setCancelNotice] = useState<string>()
  const draftKeyRef = useRef<{ fingerprint: string; key: string } | undefined>(undefined)

  const capability = capabilityQuery.data
    ? toInvoiceCapabilityView(capabilityQuery.data)
    : undefined
  const applications = (applicationsQuery.data?.items ?? []).map(toInvoiceApplicationView)
  const problems = draftProblems(draft)
  const applicationsOpen = capability?.available === true
  const submitting = createApplication.isPending
  const submitDisabled = !applicationsOpen || problems.length > 0 || submitting

  function idempotencyKeyFor(current: InvoiceDraft): string {
    const fingerprint = JSON.stringify(current)
    if (draftKeyRef.current?.fingerprint === fingerprint) return draftKeyRef.current.key
    const key = crypto.randomUUID()
    draftKeyRef.current = { fingerprint, key }
    return key
  }

  const columns: ColumnsType<InvoiceApplicationView> = [
    { title: '申请单号', dataIndex: 'applicationNo', key: 'applicationNo' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (_value, application) => <Tag color="default">{application.statusLabel}</Tag>,
    },
    { title: '类型', dataIndex: 'invoiceTypeLabel', key: 'invoiceTypeLabel' },
    { title: '抬头', dataIndex: 'title', key: 'title' },
    { title: '金额', dataIndex: 'amountText', key: 'amountText' },
    { title: '收件邮箱', dataIndex: 'deliveryEmail', key: 'deliveryEmail' },
    { title: '提交时间', dataIndex: 'submittedAtText', key: 'submittedAtText' },
    {
      title: '操作',
      key: 'actions',
      render: (_value, application) =>
        application.canCancel ? (
          <Button type="link" onClick={() => setCancelTarget(application.id)}>
            撤销申请
          </Button>
        ) : (
          <Typography.Text type="secondary">不可撤销</Typography.Text>
        ),
    },
  ]

  const cancelView = applications.find((application) => application.id === cancelTarget)

  return (
    <Space orientation="vertical" size="middle" style={{ display: 'flex' }}>
      {targetId ? <Card title="通知关联的发票申请" variant="borderless">
        {targetQuery.isError ? <Alert type="error" showIcon title="关联发票申请无法加载" description={errorText(targetQuery.error)} action={<Button onClick={() => void targetQuery.refetch()}>重新加载</Button>} /> : target ? <Descriptions column={2}>
          <Descriptions.Item label="申请单号">{target.applicationNo}</Descriptions.Item>
          <Descriptions.Item label="状态">{target.statusLabel}</Descriptions.Item>
          <Descriptions.Item label="抬头">{target.title}</Descriptions.Item>
          <Descriptions.Item label="金额">{target.amountText}</Descriptions.Item>
          <Descriptions.Item label="状态说明" span={2}>{target.statusHint}</Descriptions.Item>
          <Descriptions.Item label="驳回原因" span={2}>{target.rejectReason ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="收件邮箱">{target.deliveryEmail}</Descriptions.Item>
          <Descriptions.Item label="飞书通知">{target.webhookStatusLabel}</Descriptions.Item>
          <Descriptions.Item label="管理员确认发件时间">{target.deliveredAtText}</Descriptions.Item>
        </Descriptions> : <Typography.Text>正在加载关联发票申请…</Typography.Text>}
      </Card> : null}
      <Card title="发票能力" variant="borderless">
        {capabilityQuery.isError ? (
          <Alert
            type="error"
            showIcon
            title="发票能力查询失败"
            description={errorText(capabilityQuery.error)}
            action={
              <Button type="link" onClick={() => void capabilityQuery.refetch()}>
                重新加载
              </Button>
            }
          />
        ) : capability === undefined ? (
          <Typography.Text type="secondary">正在查询发票能力…</Typography.Text>
        ) : (
          <Space orientation="vertical" size="small" style={{ display: 'flex' }}>
            <Space wrap>
              <Tag color={capability.available ? 'success' : 'warning'}>
                {capability.statusLabel}
              </Tag>
              <Typography.Text code>{capability.statusToken}</Typography.Text>
              <Tag color={capability.deliverable ? 'success' : 'warning'}>
                {capability.deliveryStatusLabel}
              </Tag>
              <Typography.Text code>{capability.deliveryStatusToken}</Typography.Text>
            </Space>
            <Typography.Text type="secondary">
              交付方式：管理员线下开票并人工发送至申请邮箱；{capability.webhookStatusLabel}。
            </Typography.Text>
            {capability.deliveryMissing.length > 0 ? (
              <Typography.Text type="secondary">
                缺少的配置项：{capability.deliveryMissing.join('、')}（仅名称，不含任何密钥值）
              </Typography.Text>
            ) : null}
            {capability.webhookConfigured ? null : (
              <Alert
                type="warning"
                showIcon
                title="飞书通知尚未配置"
                description="申请仍可提交并保留待推送记录；在飞书通知成功前，不能认为管理员已从飞书收到开票信息。发票仍须由管理员线下开具并人工发至申请邮箱。"
              />
            )}
          </Space>
        )}
      </Card>

      <Card title="提交发票申请" variant="borderless">
        {applicationsOpen ? null : (
          <Alert
            type="warning"
            showIcon
            title={`当前不可提交发票申请（${capability?.statusToken ?? '能力未知'}）`}
            style={{ marginBottom: 12 }}
          />
        )}
        <Space orientation="vertical" size="small" style={{ display: 'flex' }}>
          <Space wrap>
            <Typography.Text>发票类型</Typography.Text>
            <Radio.Group
              aria-label="发票类型"
              value={draft.invoiceType}
              onChange={(event) =>
                setDraft({ ...draft, invoiceType: event.target.value as InvoiceDraft['invoiceType'] })
              }
            >
              <Radio value="VAT_NORMAL">增值税普通发票</Radio>
              <Radio value="VAT_SPECIAL">增值税专用发票</Radio>
            </Radio.Group>
          </Space>
          <Space wrap>
            <Typography.Text>抬头类型</Typography.Text>
            <Radio.Group
              aria-label="抬头类型"
              value={draft.titleType}
              onChange={(event) =>
                setDraft({
                  ...draft,
                  titleType: event.target.value as InvoiceDraft['titleType'],
                  taxNumber: '',
                })
              }
            >
              <Radio value="PERSONAL">个人</Radio>
              <Radio value="COMPANY">单位</Radio>
            </Radio.Group>
          </Space>
          <Space wrap>
            <Typography.Text>发票抬头</Typography.Text>
            <Input
              aria-label="发票抬头"
              maxLength={200}
              style={{ width: 260 }}
              value={draft.title}
              onChange={(event) => setDraft({ ...draft, title: event.target.value })}
            />
          </Space>
          {draft.titleType === 'COMPANY' ? (
            <Space wrap>
              <Typography.Text>纳税人识别号</Typography.Text>
              <Input
                aria-label="纳税人识别号"
                maxLength={64}
                style={{ width: 260 }}
                value={draft.taxNumber}
                onChange={(event) =>
                  setDraft({ ...draft, taxNumber: event.target.value.toUpperCase() })
                }
              />
              <Typography.Text type="secondary">15–20 位数字或大写字母</Typography.Text>
            </Space>
          ) : (
            <Typography.Text type="secondary">
              个人抬头不需要纳税人识别号；提交时会显式传 null，服务端会拒绝为个人抬头携带税号。
            </Typography.Text>
          )}
          <Space wrap>
            <Typography.Text>开票金额（元）</Typography.Text>
            <InputNumber
              aria-label="开票金额（元）"
              min={0}
              step={1}
              precision={2}
              value={draft.amountYuan}
              onChange={(value) =>
                setDraft({ ...draft, amountYuan: typeof value === 'number' ? value : null })
              }
            />
            <Typography.Text type="secondary">
              申请单不关联充值订单，金额由申请人填写，服务端按提交值记录。
            </Typography.Text>
          </Space>
          <Space wrap>
            <Typography.Text>接收邮箱</Typography.Text>
            <Input
              aria-label="接收邮箱"
              maxLength={255}
              style={{ width: 260 }}
              value={draft.deliveryEmail}
              onChange={(event) => setDraft({ ...draft, deliveryEmail: event.target.value })}
            />
          </Space>
          <Space wrap>
            <Typography.Text>备注</Typography.Text>
            <Input
              aria-label="备注"
              maxLength={500}
              style={{ width: 360 }}
              value={draft.remark}
              onChange={(event) => setDraft({ ...draft, remark: event.target.value })}
            />
          </Space>

          <Button
            type="primary"
            loading={submitting}
            disabled={submitDisabled}
            onClick={() => {
              const amountCents = draft.amountYuan === null ? undefined : Math.round(draft.amountYuan * 100)
              if (amountCents === undefined) return
              createApplication.mutate({
                body: {
                  invoiceType: draft.invoiceType,
                  titleType: draft.titleType,
                  title: draft.title.trim(),
                  taxNumber: draft.titleType === 'COMPANY' ? draft.taxNumber.trim() : null,
                  amountCents,
                  deliveryEmail: draft.deliveryEmail.trim(),
                  remark: draft.remark.trim() ? draft.remark.trim() : null,
                },
                idempotencyKey: idempotencyKeyFor(draft),
              })
            }}
          >
            提交发票申请
          </Button>

          {submitDisabled && !submitting ? (
            <Typography.Text type="secondary">
              按钮不可用的原因：
              {!applicationsOpen
                ? `发票申请未开放（${capability?.statusToken ?? '能力未知'}）`
                : problems.join('；')}
            </Typography.Text>
          ) : null}
        </Space>
      </Card>

      {createApplication.isError ? (
        <Alert
          type="error"
          showIcon
          title="提交发票申请失败"
          description={errorText(createApplication.error)}
        />
      ) : null}
      {createApplication.isSuccess ? (
        <Alert
          type="success"
          showIcon
          title={`已提交，服务端状态：${toInvoiceApplicationView(createApplication.data).statusLabel}`}
          description={`申请已记录，${toInvoiceApplicationView(createApplication.data).webhookStatusLabel}。管理员将线下开票并人工发送至申请邮箱；提交和飞书推送均不表示发票已发出。`}
        />
      ) : null}

      <Card title="我的发票申请" variant="borderless">
        {applicationsQuery.isError ? (
          <Alert
            type="error"
            showIcon
            title="发票申请加载失败"
            description={errorText(applicationsQuery.error)}
            action={
              <Button type="link" onClick={() => void applicationsQuery.refetch()}>
                重新加载
              </Button>
            }
          />
        ) : (
          <Table<InvoiceApplicationView>
            rowKey="id"
            size="small"
            columns={columns}
            dataSource={applications}
            loading={applicationsQuery.isLoading}
            locale={{ emptyText: '暂无发票申请：当前账户还没有提交过开票申请。' }}
            expandable={{
              expandedRowRender: (application) => (
                <Descriptions column={1} size="small">
                  <Descriptions.Item label="状态说明">{application.statusHint}</Descriptions.Item>
                  <Descriptions.Item label="飞书通知">{application.webhookStatusLabel}</Descriptions.Item>
                  <Descriptions.Item label="纳税人识别号">
                    {application.taxNumber ?? '—（个人抬头）'}
                  </Descriptions.Item>
                  <Descriptions.Item label="驳回原因">
                    {application.rejectReason ?? '—'}
                  </Descriptions.Item>
                  <Descriptions.Item label="人工发件渠道">
                    {application.deliveryProvider ?? '—（管理员尚未确认发件）'}
                  </Descriptions.Item>
                  <Descriptions.Item label="发票号">{application.invoiceNumber ?? '—'}</Descriptions.Item>
                  <Descriptions.Item label="审核时间">
                    {application.reviewedAtText}
                  </Descriptions.Item>
                  <Descriptions.Item label="审核通过时间">
                    {application.approvedAtText}（仅代表审核通过，不代表已开具）
                  </Descriptions.Item>
                  <Descriptions.Item label="管理员确认发件时间">{application.deliveredAtText}</Descriptions.Item>
                  <Descriptions.Item label="驳回时间">
                    {application.rejectedAtText}
                  </Descriptions.Item>
                  <Descriptions.Item label="撤销时间">
                    {application.cancelledAtText}
                  </Descriptions.Item>
                  <Descriptions.Item label="备注">{application.remark ?? '—'}</Descriptions.Item>
                </Descriptions>
              ),
            }}
            pagination={{
              current: applicationsQuery.data?.page ?? page,
              pageSize: applicationsQuery.data?.size ?? pageSize,
              total: applicationsQuery.data?.total ?? 0,
              showSizeChanger: true,
              onChange: (nextPage, nextPageSize) => {
                setPage(nextPageSize === pageSize ? nextPage : 1)
                setPageSize(nextPageSize)
              },
            }}
          />
        )}
      </Card>

      {cancelView ? (
        <Card title={`撤销申请 ${cancelView.applicationNo}`} variant="borderless">
          <Typography.Paragraph>
            撤销后申请进入「已撤销」，服务端不再受理审核。当前状态：{cancelView.statusLabel}。
          </Typography.Paragraph>
          <Space>
            <Button
              danger
              loading={cancelApplication.isPending}
              onClick={() =>
                cancelApplication.mutate(cancelView.id, {
                  onSuccess: (application) => {
                    setCancelNotice(
                      `服务端返回的状态：${toInvoiceApplicationView(application).statusLabel}`,
                    )
                    setCancelTarget(undefined)
                  },
                })
              }
            >
              确认撤销
            </Button>
            <Button onClick={() => setCancelTarget(undefined)}>返回</Button>
          </Space>
        </Card>
      ) : null}

      {cancelNotice ? (
        <Alert type="info" showIcon title="撤销结果" description={cancelNotice} />
      ) : null}
      {cancelApplication.isError ? (
        <Alert
          type="error"
          showIcon
          title="撤销失败"
          description={errorText(cancelApplication.error)}
        />
      ) : null}
    </Space>
  )
}

/** The same rules the server applies, so the button is disabled for a reason it can name. */
function draftProblems(draft: InvoiceDraft): string[] {
  const problems: string[] = []
  if (!draft.title.trim()) problems.push('发票抬头不能为空')
  if (draft.title.trim().length > 200) problems.push('发票抬头不能超过 200 个字符')
  if (draft.titleType === 'COMPANY') {
    const taxNumber = draft.taxNumber.trim()
    if (!taxNumber) problems.push('单位抬头必须填写纳税人识别号')
    else if (!TAX_NUMBER_PATTERN.test(taxNumber)) {
      problems.push('纳税人识别号必须是 15–20 位数字或大写字母')
    }
  } else if (draft.taxNumber.trim()) {
    problems.push('个人抬头不能填写纳税人识别号')
  }
  if (draft.amountYuan === null) problems.push('请填写开票金额')
  else if (!(Math.round(draft.amountYuan * 100) >= 1)) problems.push('开票金额必须大于 0 元')
  if (!draft.deliveryEmail.trim()) problems.push('请填写接收邮箱')
  else if (!EMAIL_PATTERN.test(draft.deliveryEmail.trim())) problems.push('接收邮箱格式不正确')
  if (draft.remark.length > 500) problems.push('备注不能超过 500 个字符')
  return problems
}

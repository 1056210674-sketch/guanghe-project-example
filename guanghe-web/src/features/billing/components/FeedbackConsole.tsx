import { Alert, Button, Card, Descriptions, Input, Select, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useRef, useState } from 'react'
import { errorText } from '../live/commercialError'
import { useCreateFeedback, useMyFeedback, useFeedbackDetail } from '../live/commercialQueries'
import {
  FEEDBACK_CATEGORY_LABELS,
  toFeedbackView,
  type FeedbackCategory,
  type FeedbackView,
} from '../types'

interface FeedbackDraft {
  category: FeedbackCategory
  content: string
  contact: string
}

const EMPTY_DRAFT: FeedbackDraft = { category: 'PRODUCT', content: '', contact: '' }

/**
 * The 意见反馈 tab.
 *
 * The list is the author's own submissions with the operator's answer shown as plain text: no HTML
 * from a reply is ever rendered as markup, and the status shown is the one the server returned.
 */
export function FeedbackConsole({ targetId }: { targetId?: string }) {
  const targetQuery = useFeedbackDetail(targetId)
  const target = targetQuery.data ? toFeedbackView(targetQuery.data) : undefined
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const feedbackQuery = useMyFeedback({ page, size: pageSize })
  const createFeedback = useCreateFeedback()

  const [draft, setDraft] = useState<FeedbackDraft>(EMPTY_DRAFT)
  const draftKeyRef = useRef<{ fingerprint: string; key: string } | undefined>(undefined)

  const items = (feedbackQuery.data?.items ?? []).map(toFeedbackView)
  const content = draft.content.trim()
  const contact = draft.contact.trim()
  const problems: string[] = []
  if (!content) problems.push('反馈内容不能为空')
  if (content.length > 2000) problems.push('反馈内容不能超过 2000 个字符')
  if (contact.length > 128) problems.push('联系方式不能超过 128 个字符')
  const submitting = createFeedback.isPending
  const submitDisabled = problems.length > 0 || submitting

  function idempotencyKeyFor(current: FeedbackDraft): string {
    const fingerprint = JSON.stringify(current)
    if (draftKeyRef.current?.fingerprint === fingerprint) return draftKeyRef.current.key
    const key = crypto.randomUUID()
    draftKeyRef.current = { fingerprint, key }
    return key
  }

  const columns: ColumnsType<FeedbackView> = [
    {
      title: '类型',
      dataIndex: 'categoryLabel',
      key: 'categoryLabel',
      render: (_value, feedback) => <Tag>{feedback.categoryLabel}</Tag>,
    },
    {
      title: '内容',
      dataIndex: 'content',
      key: 'content',
      render: (_value, feedback) => (
        <Typography.Text ellipsis style={{ maxWidth: 320 }}>
          {feedback.content}
        </Typography.Text>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (_value, feedback) => <Tag color="default">{feedback.statusLabel}</Tag>,
    },
    { title: '提交时间', dataIndex: 'createdAtText', key: 'createdAtText' },
    { title: '处理时间', dataIndex: 'handledAtText', key: 'handledAtText' },
    {
      title: '回复',
      key: 'reply',
      render: (_value, feedback) =>
        feedback.reply ? (
          <Typography.Text>{feedback.reply}</Typography.Text>
        ) : (
          <Typography.Text type="secondary">尚未回复</Typography.Text>
        ),
    },
  ]

  return (
    <Space orientation="vertical" size="middle" style={{ display: 'flex' }}>
      {targetId ? <Card title="通知关联的反馈" variant="borderless">
        {targetQuery.isError ? <Alert type="error" showIcon title="关联反馈无法加载" description={errorText(targetQuery.error)} action={<Button onClick={() => void targetQuery.refetch()}>重新加载</Button>} /> : target ? <Descriptions column={1}>
          <Descriptions.Item label="状态">{target.statusLabel}</Descriptions.Item>
          <Descriptions.Item label="完整内容">{target.content}</Descriptions.Item>
          <Descriptions.Item label="运营回复">{target.reply ?? '尚未回复'}</Descriptions.Item>
          <Descriptions.Item label="处理时间">{target.handledAtText}</Descriptions.Item>
        </Descriptions> : <Typography.Text>正在加载关联反馈…</Typography.Text>}
      </Card> : null}
      <Card title="提交反馈" variant="borderless">
        <Space orientation="vertical" size="small" style={{ display: 'flex' }}>
          <Space wrap>
            <Typography.Text>反馈类型</Typography.Text>
            <Select<FeedbackCategory>
              aria-label="反馈类型"
              style={{ minWidth: 180 }}
              value={draft.category}
              onChange={(category) => setDraft({ ...draft, category })}
              options={Object.entries(FEEDBACK_CATEGORY_LABELS).map(([value, label]) => ({
                value: value as FeedbackCategory,
                label,
              }))}
            />
          </Space>
          <Space wrap align="start">
            <Typography.Text>反馈内容</Typography.Text>
            <Input.TextArea
              aria-label="反馈内容"
              rows={4}
              maxLength={2000}
              showCount
              style={{ width: 460 }}
              value={draft.content}
              onChange={(event) => setDraft({ ...draft, content: event.target.value })}
            />
          </Space>
          <Space wrap>
            <Typography.Text>联系方式（选填）</Typography.Text>
            <Input
              aria-label="联系方式"
              maxLength={128}
              style={{ width: 260 }}
              value={draft.contact}
              onChange={(event) => setDraft({ ...draft, contact: event.target.value })}
            />
          </Space>
          <Button
            type="primary"
            loading={submitting}
            disabled={submitDisabled}
            onClick={() =>
              createFeedback.mutate({
                body: {
                  category: draft.category,
                  content,
                  contact: contact ? contact : null,
                },
                idempotencyKey: idempotencyKeyFor(draft),
              })
            }
          >
            提交反馈
          </Button>
          {submitDisabled && !submitting ? (
            <Typography.Text type="secondary">
              按钮不可用的原因：{problems.join('；')}
            </Typography.Text>
          ) : null}
        </Space>
      </Card>

      {createFeedback.isError ? (
        <Alert type="error" showIcon title="提交反馈失败" description={errorText(createFeedback.error)} />
      ) : null}
      {createFeedback.isSuccess ? (
        <Alert
          type="success"
          showIcon
          title={`已提交，服务端状态：${toFeedbackView(createFeedback.data).statusLabel}`}
          description="运营处理后会在这里显示回复，并可能同时产生一条通知。"
        />
      ) : null}

      <Card title="我的反馈" variant="borderless">
        {feedbackQuery.isError ? (
          <Alert
            type="error"
            showIcon
            title="反馈记录加载失败"
            description={errorText(feedbackQuery.error)}
            action={
              <Button type="link" onClick={() => void feedbackQuery.refetch()}>
                重新加载
              </Button>
            }
          />
        ) : (
          <Table<FeedbackView>
            rowKey="id"
            size="small"
            columns={columns}
            dataSource={items}
            loading={feedbackQuery.isLoading}
            locale={{ emptyText: '暂无反馈：当前账户还没有提交过意见反馈。' }}
            expandable={{
              expandedRowRender: (feedback) => (
                <Descriptions column={1} size="small">
                  <Descriptions.Item label="完整内容">
                    <Typography.Text>{feedback.content}</Typography.Text>
                  </Descriptions.Item>
                  <Descriptions.Item label="联系方式">
                    {feedback.contact ?? '—（未填写）'}
                  </Descriptions.Item>
                  <Descriptions.Item label="运营回复">
                    {feedback.reply ?? '尚未回复'}
                  </Descriptions.Item>
                </Descriptions>
              ),
            }}
            pagination={{
              current: feedbackQuery.data?.page ?? page,
              pageSize: feedbackQuery.data?.size ?? pageSize,
              total: feedbackQuery.data?.total ?? 0,
              showSizeChanger: true,
              onChange: (nextPage, nextPageSize) => {
                setPage(nextPageSize === pageSize ? nextPage : 1)
                setPageSize(nextPageSize)
              },
            }}
          />
        )}
      </Card>
    </Space>
  )
}

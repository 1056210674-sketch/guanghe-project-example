import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  SaveOutlined,
} from '@ant-design/icons'
import { diagnosticTraceDescription } from '../../../shared/tracePresentation'
import {
  Alert,
  Button,
  Card,
  Input,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd'
import { useMemo, useRef, useState } from 'react'
import type {
  GeoAsyncViewProps,
  GeoKeywordDraftView,
  GeoKeywordKind,
  GeoQuestionDraftView,
  GeoSelectOption,
  GeoViewError,
} from '../types'
import { GeoStateFrame } from './GeoStateFrame'

export interface GeoQuestionLibraryProps extends GeoAsyncViewProps {
  topics: readonly GeoSelectOption[]
  selectedTopicId?: string
  topicName?: string
  keywords: readonly GeoKeywordDraftView[]
  questions: readonly GeoQuestionDraftView[]
  keywordsSaving?: boolean
  questionsSaving?: boolean
  keywordsError?: GeoViewError | null
  questionsError?: GeoViewError | null
  onTopicChange?: (topicId: string) => void
  onSaveKeywords?: (keywords: readonly GeoKeywordDraftView[]) => void
  onSaveQuestions?: (questions: readonly GeoQuestionDraftView[]) => void
}

const KEYWORD_LABELS: Record<GeoKeywordKind, string> = {
  CORE: '核心词',
  DISTILLED: '蒸馏词',
  BRAND: '品牌词',
  SCENE: '场景词',
}

function moveItem<T>(items: readonly T[], index: number, offset: -1 | 1): T[] {
  const nextIndex = index + offset
  if (nextIndex < 0 || nextIndex >= items.length) return [...items]
  const next = [...items]
  const current = next[index]
  const target = next[nextIndex]
  if (current === undefined || target === undefined) return next
  next[index] = target
  next[nextIndex] = current
  return next
}

function DraftError({ error }: { error?: GeoViewError | null }) {
  if (!error) return null
  return (
    <Alert
      type="error"
      showIcon
      title={error.message}
      description={diagnosticTraceDescription(error.traceId)}
    />
  )
}

function KeywordDraftEditor({
  initial,
  saving,
  error,
  onSave,
}: {
  initial: readonly GeoKeywordDraftView[]
  saving: boolean
  error?: GeoViewError | null
  onSave?: (items: readonly GeoKeywordDraftView[]) => void
}) {
  const [items, setItems] = useState<GeoKeywordDraftView[]>(() => [...initial])
  const [type, setType] = useState<GeoKeywordKind>('CORE')
  const [text, setText] = useState('')
  const [editingId, setEditingId] = useState<string>()
  const [validation, setValidation] = useState<string>()
  const counter = useRef(0)
  const initialSignature = JSON.stringify(initial.map(({ type: kind, text: value }) => [kind, value]))
  const currentSignature = JSON.stringify(items.map(({ type: kind, text: value }) => [kind, value]))
  const dirty = initialSignature !== currentSignature

  const commitInput = () => {
    const normalized = text.trim()
    if (!normalized) {
      setValidation('请输入关键词')
      return
    }
    if (normalized.length > 200) {
      setValidation('关键词不能超过200个字符')
      return
    }
    if (items.some((item) => item.id !== editingId && item.text.trim() === normalized)) {
      setValidation('关键词不能重复')
      return
    }

    if (editingId) {
      setItems((current) =>
        current.map((item) =>
          item.id === editingId ? { ...item, type, text: normalized } : item,
        ),
      )
    } else {
      counter.current += 1
      setItems((current) => [
        ...current,
        { id: `keyword-draft-${counter.current}`, type, text: normalized },
      ])
    }
    setText('')
    setEditingId(undefined)
    setValidation(undefined)
  }

  return (
    <Card title="关键词管理" className="geo-panel-card geo-foundation-editor-card">
      <Typography.Paragraph type="secondary">
        支持核心词、蒸馏词、品牌词和场景词；列表顺序会作为正式顺序整体保存。
      </Typography.Paragraph>
      <div className="geo-foundation-editor-input">
        <Select
          value={type}
          options={Object.entries(KEYWORD_LABELS).map(([value, label]) => ({ value, label }))}
          onChange={(value) => setType(value as GeoKeywordKind)}
        />
        <Input
          value={text}
          maxLength={200}
          placeholder="输入关键词"
          onChange={(event) => setText(event.target.value)}
          onPressEnter={commitInput}
        />
        <Button
          aria-label={editingId ? '更新关键词草稿' : '加入关键词草稿'}
          icon={<PlusOutlined />}
          onClick={commitInput}
        >
          {editingId ? '更新草稿' : '加入草稿'}
        </Button>
      </div>
      {validation ? <Alert type="warning" showIcon title={validation} /> : null}
      {dirty ? (
        <Alert type="warning" showIcon title="当前列表为本地编辑草稿，保存成功后才会替换正式关键词。" />
      ) : null}
      <DraftError error={error} />
      {items.length === 0 ? (
        <div className="geo-inline-empty">
          <Typography.Text type="secondary">暂无正式关键词</Typography.Text>
        </div>
      ) : (
        <Table<GeoKeywordDraftView>
          rowKey="id"
          dataSource={items}
          pagination={false}
          columns={[
            { title: '顺序', width: 70, render: (_, __, index) => index + 1 },
            {
              title: '类型',
              dataIndex: 'type',
              width: 100,
              render: (value: GeoKeywordKind) => <Tag>{KEYWORD_LABELS[value]}</Tag>,
            },
            { title: '关键词', dataIndex: 'text' },
            {
              title: '操作',
              width: 190,
              render: (_, record, index) => (
                <Space size={2}>
                  <Button
                    type="text"
                    aria-label={`上移关键词：${record.text}`}
                    icon={<ArrowUpOutlined />}
                    disabled={index === 0}
                    onClick={() => setItems((current) => moveItem(current, index, -1))}
                  />
                  <Button
                    type="text"
                    aria-label={`下移关键词：${record.text}`}
                    icon={<ArrowDownOutlined />}
                    disabled={index === items.length - 1}
                    onClick={() => setItems((current) => moveItem(current, index, 1))}
                  />
                  <Button
                    type="text"
                    aria-label={`编辑关键词：${record.text}`}
                    icon={<EditOutlined />}
                    onClick={() => {
                      setEditingId(record.id)
                      setType(record.type)
                      setText(record.text)
                    }}
                  />
                  <Button
                    type="text"
                    danger
                    aria-label={`删除关键词：${record.text}`}
                    icon={<DeleteOutlined />}
                    onClick={() => setItems((current) => current.filter((item) => item.id !== record.id))}
                  />
                </Space>
              ),
            },
          ]}
        />
      )}
      <Button
        aria-label="整体保存关键词"
        type="primary"
        icon={<SaveOutlined />}
        loading={saving}
        disabled={!dirty || !onSave}
        onClick={() => onSave?.(items)}
      >
        整体保存关键词
      </Button>
    </Card>
  )
}

function QuestionDraftEditor({
  initial,
  saving,
  error,
  onSave,
}: {
  initial: readonly GeoQuestionDraftView[]
  saving: boolean
  error?: GeoViewError | null
  onSave?: (items: readonly GeoQuestionDraftView[]) => void
}) {
  const [items, setItems] = useState<GeoQuestionDraftView[]>(() => [...initial])
  const [text, setText] = useState('')
  const [enabled, setEnabled] = useState(true)
  const [editingId, setEditingId] = useState<string>()
  const [validation, setValidation] = useState<string>()
  const counter = useRef(0)
  const initialSignature = JSON.stringify(initial.map(({ text: value, enabled: active }) => [value, active]))
  const currentSignature = JSON.stringify(items.map(({ text: value, enabled: active }) => [value, active]))
  const dirty = initialSignature !== currentSignature

  const commitInput = () => {
    const normalized = text.trim()
    if (!normalized) {
      setValidation('请输入问题内容')
      return
    }
    if (normalized.length > 1000) {
      setValidation('问题内容不能超过1000个字符')
      return
    }

    if (editingId) {
      setItems((current) =>
        current.map((item) =>
          item.id === editingId ? { ...item, text: normalized, enabled } : item,
        ),
      )
    } else {
      counter.current += 1
      setItems((current) => [
        ...current,
        { id: `question-draft-${counter.current}`, text: normalized, enabled },
      ])
    }
    setText('')
    setEnabled(true)
    setEditingId(undefined)
    setValidation(undefined)
  }

  return (
    <Card title="正式问题" className="geo-panel-card geo-foundation-editor-card">
      <Typography.Paragraph type="secondary">
        首版问题类型固定为“品牌发现”，来源固定为“手工新增”。启停和排序随整体保存生效。
      </Typography.Paragraph>
      <div className="geo-foundation-question-input">
        <Input.TextArea
          value={text}
          maxLength={1000}
          autoSize={{ minRows: 2, maxRows: 5 }}
          placeholder="输入需要监测的问题"
          onChange={(event) => setText(event.target.value)}
        />
        <Space>
          <Switch checked={enabled} onChange={setEnabled} />
          <Typography.Text>{enabled ? '启用' : '停用'}</Typography.Text>
          <Button
            aria-label={editingId ? '更新问题草稿' : '加入问题草稿'}
            icon={<PlusOutlined />}
            onClick={commitInput}
          >
            {editingId ? '更新草稿' : '加入草稿'}
          </Button>
        </Space>
      </div>
      {validation ? <Alert type="warning" showIcon title={validation} /> : null}
      {dirty ? (
        <Alert type="warning" showIcon title="当前列表为本地编辑草稿，保存成功后才会替换正式问题。" />
      ) : null}
      <DraftError error={error} />
      {items.length === 0 ? (
        <div className="geo-inline-empty">
          <Typography.Text type="secondary">暂无正式问题</Typography.Text>
        </div>
      ) : (
        <Table<GeoQuestionDraftView>
          rowKey="id"
          dataSource={items}
          pagination={false}
          scroll={{ x: 760 }}
          columns={[
            { title: '顺序', width: 70, render: (_, __, index) => index + 1 },
            { title: '问题内容', dataIndex: 'text', width: 300 },
            { title: '类型', width: 100, render: () => '品牌发现' },
            { title: '来源', width: 100, render: () => '手工新增' },
            {
              title: '状态',
              dataIndex: 'enabled',
              width: 90,
              render: (value: boolean) => (
                <Tag color={value ? 'success' : 'default'}>{value ? '已启用' : '已停用'}</Tag>
              ),
            },
            {
              title: '操作',
              width: 210,
              render: (_, record, index) => (
                <Space size={2}>
                  <Button
                    type="text"
                    aria-label={`上移问题：${record.text}`}
                    icon={<ArrowUpOutlined />}
                    disabled={index === 0}
                    onClick={() => setItems((current) => moveItem(current, index, -1))}
                  />
                  <Button
                    type="text"
                    aria-label={`下移问题：${record.text}`}
                    icon={<ArrowDownOutlined />}
                    disabled={index === items.length - 1}
                    onClick={() => setItems((current) => moveItem(current, index, 1))}
                  />
                  <Button
                    type="text"
                    aria-label={`编辑问题：${record.text}`}
                    icon={<EditOutlined />}
                    onClick={() => {
                      setEditingId(record.id)
                      setText(record.text)
                      setEnabled(record.enabled)
                    }}
                  />
                  <Button
                    type="text"
                    aria-label={`${record.enabled ? '停用' : '启用'}问题：${record.text}`}
                    onClick={() => setItems((current) => current.map((item) => item.id === record.id ? { ...item, enabled: !item.enabled } : item))}
                  >
                    {record.enabled ? '停用' : '启用'}
                  </Button>
                  <Button
                    type="text"
                    danger
                    aria-label={`删除问题：${record.text}`}
                    icon={<DeleteOutlined />}
                    onClick={() => setItems((current) => current.filter((item) => item.id !== record.id))}
                  />
                </Space>
              ),
            },
          ]}
        />
      )}
      <Button
        aria-label="整体保存问题"
        type="primary"
        icon={<SaveOutlined />}
        loading={saving}
        disabled={!dirty || !onSave}
        onClick={() => onSave?.(items)}
      >
        整体保存问题
      </Button>
    </Card>
  )
}

export function GeoQuestionLibrary({
  loading,
  error,
  onRetry,
  topics,
  selectedTopicId,
  topicName,
  keywords,
  questions,
  keywordsSaving = false,
  questionsSaving = false,
  keywordsError,
  questionsError,
  onTopicChange,
  onSaveKeywords,
  onSaveQuestions,
}: GeoQuestionLibraryProps) {
  const keywordKey = useMemo(
    () => `${selectedTopicId ?? 'none'}:${keywords.map((item) => `${item.id}:${item.type}:${item.text}`).join('|')}`,
    [keywords, selectedTopicId],
  )
  const questionKey = useMemo(
    () => `${selectedTopicId ?? 'none'}:${questions.map((item) => `${item.id}:${item.enabled}:${item.text}`).join('|')}`,
    [questions, selectedTopicId],
  )

  return (
    <GeoStateFrame
      label="GEO 问题库"
      loading={loading}
      error={error}
      onRetry={onRetry}
      empty={topics.length === 0}
      emptyTitle="还没有 GEO 专题"
      emptyDescription="请先创建专题，再维护正式关键词和问题。"
    >
      <section className="geo-questions" aria-labelledby="geo-questions-title">
        <header className="geo-page-heading">
          <div>
            <Typography.Title id="geo-questions-title" level={1}>关键词与问题库</Typography.Title>
            <Typography.Paragraph type="secondary">
              {topicName ? `专题：${topicName}` : '选择专题后维护正式基础资料。'}
            </Typography.Paragraph>
          </div>
          <Select
            className="geo-topic-selector"
            showSearch
            optionFilterProp="label"
            placeholder="选择专题"
            value={selectedTopicId}
            options={topics.map((topic) => ({ value: topic.id, label: topic.label }))}
            onChange={onTopicChange}
          />
        </header>

        {!selectedTopicId ? (
          <div className="geo-inline-empty geo-inline-empty--large">
            <Typography.Text type="secondary">请选择一个专题</Typography.Text>
          </div>
        ) : (
          <div className="geo-foundation-editor-grid">
            <KeywordDraftEditor
              key={`keywords:${keywordKey}`}
              initial={keywords}
              saving={keywordsSaving}
              error={keywordsError}
              onSave={onSaveKeywords}
            />
            <QuestionDraftEditor
              key={`questions:${questionKey}`}
              initial={questions}
              saving={questionsSaving}
              error={questionsError}
              onSave={onSaveQuestions}
            />
          </div>
        )}
      </section>
    </GeoStateFrame>
  )
}

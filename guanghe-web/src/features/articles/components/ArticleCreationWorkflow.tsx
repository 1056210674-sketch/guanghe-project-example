import {
  CheckCircleFilled,
  DeleteOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import { Alert, Button, Card, Input, Select, Tag, Typography } from 'antd'
import { useState } from 'react'
import type {
  ArticleCreativePlanDraft,
  ArticleCreativePlanSectionView,
  ArticleCreativePlanView,
  ArticleTitleCandidateBatchView,
  ArticleTitleSource,
  ArticleWorkflowStage,
} from '../types'

const WORKFLOW_STEPS = [
  { id: 'TITLE', label: '确定标题' },
  { id: 'PLAN', label: '确认创作方案' },
  { id: 'BODY', label: '生成完整正文' },
] as const

const TONE_OPTIONS = [
  { label: '专业可信', value: 'PROFESSIONAL' },
  { label: '轻松自然', value: 'NATURAL' },
  { label: '亲切活泼', value: 'FRIENDLY' },
  { label: '简洁有力', value: 'CONCISE' },
]

const LENGTH_OPTIONS = [
  { label: '短篇（约 800 字）', value: 'SHORT' },
  { label: '中篇（约 1200 字）', value: 'MEDIUM' },
  { label: '长篇（约 1600 字）', value: 'LONG' },
]

function workflowStep(stage: ArticleWorkflowStage) {
  if (stage.startsWith('TITLE')) return 0
  if (stage.startsWith('PLAN')) return 1
  return 2
}

function stageStatus(stage: ArticleWorkflowStage) {
  switch (stage) {
    case 'TITLE_INPUT':
      return '等待填写创作需求'
    case 'TITLE_GENERATING':
      return '正在生成标题'
    case 'TITLE_AWAITING_CONFIRMATION':
      return '标题待确认'
    case 'PLAN_READY':
      return '标题已确认，可生成创作方案'
    case 'PLAN_GENERATING':
      return '正在生成创作方案'
    case 'PLAN_AWAITING_CONFIRMATION':
      return '创作方案待确认'
    case 'BODY_READY_TO_GENERATE':
      return '创作方案已确认，可生成完整正文'
    case 'BODY_GENERATING':
      return '正在生成完整正文'
    case 'BODY_READY':
      return '正文已生成'
    case 'EDITING':
      return '正在编辑正文'
    case 'READY_TO_SUBMIT':
      return '可以准备投稿'
  }
}

export function ArticleWorkflowProgress({
  stage,
}: {
  stage: ArticleWorkflowStage
}) {
  const currentStep = workflowStep(stage)
  return (
    <Card className="article-workflow-progress" size="small">
      <div className="article-workflow-progress__heading">
        <Typography.Text strong>文章创作进度</Typography.Text>
        <Tag color="processing">{stageStatus(stage)}</Tag>
      </div>
      <ol aria-label="文章交互式创作流程">
        {WORKFLOW_STEPS.map((step, index) => (
          <li
            key={step.id}
            className={
              index < currentStep
                ? 'is-complete'
                : index === currentStep
                  ? 'is-current'
                  : undefined
            }
            aria-current={index === currentStep ? 'step' : undefined}
          >
            <span aria-hidden="true">
              {index < currentStep ? <CheckCircleFilled /> : index + 1}
            </span>
            <strong>{step.label}</strong>
          </li>
        ))}
      </ol>
    </Card>
  )
}

export interface ArticleTitleConfirmationCardProps {
  batch: ArticleTitleCandidateBatchView
  currentTitle?: string
  busy?: boolean
  onConfirm: (input: {
    title: string
    source: ArticleTitleSource
    candidateIndex?: number
  }) => void
  onRegenerate: () => void
}

export function ArticleTitleConfirmationCard({
  batch,
  currentTitle = '',
  busy,
  onConfirm,
  onRegenerate,
}: ArticleTitleConfirmationCardProps) {
  const initialCandidateIndex = batch.candidates.findIndex(
    (candidate) => candidate === currentTitle,
  )
  const [selectedIndex, setSelectedIndex] = useState<number | undefined>(
    initialCandidateIndex >= 0 ? initialCandidateIndex : undefined,
  )
  const [title, setTitle] = useState(currentTitle)
  const normalizedTitle = title.trim()
  const selectedCandidate =
    selectedIndex === undefined ? undefined : batch.candidates[selectedIndex]
  const source: ArticleTitleSource = selectedCandidate
    ? selectedCandidate === normalizedTitle
      ? 'SELECTED'
      : 'EDITED'
    : 'CUSTOM'

  return (
    <Card
      className="article-workflow-card article-title-confirmation"
      title="标题候选（待确认）"
      extra={<Tag>第 {batch.version} 批</Tag>}
    >
      <Alert
        type="info"
        showIcon
        title={`光禾AI智能体框架生成了 ${batch.candidates.length} 个标题候选`}
        description="选择后可以直接修改，也可以自己填写标题。确认前不会生成创作方案或正文。"
      />
      <div
        className="article-title-confirmation__candidates"
        role="radiogroup"
        aria-label="标题候选"
      >
        {batch.candidates.map((candidate, index) => (
          <button
            key={`${index}:${candidate}`}
            type="button"
            role="radio"
            aria-checked={selectedIndex === index}
            className={selectedIndex === index ? 'is-selected' : undefined}
            disabled={busy}
            onClick={() => {
              setSelectedIndex(index)
              setTitle(candidate)
            }}
          >
            <span>{index + 1}</span>
            <strong>{candidate}</strong>
          </button>
        ))}
      </div>
      <label className="article-workflow-field">
        <span>直接修改或自定义标题</span>
        <Input
          aria-label="确认使用的文章标题"
          value={title}
          maxLength={300}
          showCount
          placeholder="选择上方候选，或在这里输入自己的标题"
          disabled={busy}
          onChange={(event) => setTitle(event.target.value)}
        />
      </label>
      <div className="article-workflow-card__actions">
        <Button
          aria-label="重新生成标题"
          icon={<ReloadOutlined />}
          disabled={busy}
          onClick={onRegenerate}
        >
          重新生成标题
        </Button>
        <Button
          type="primary"
          loading={busy}
          disabled={!normalizedTitle || busy}
          onClick={() =>
            onConfirm({
              title: normalizedTitle,
              source,
              candidateIndex:
                source === 'CUSTOM' || selectedIndex === undefined
                  ? undefined
                  : selectedIndex + 1,
            })
          }
        >
          确认标题并继续
        </Button>
      </div>
      {batch.generatedAt ? (
        <Typography.Text type="secondary">
          本批生成于 {batch.generatedAt}
        </Typography.Text>
      ) : null}
    </Card>
  )
}

function copyPlan(plan: ArticleCreativePlanView): ArticleCreativePlanDraft {
  return {
    summary: plan.summary,
    coreMessage: plan.coreMessage,
    targetAudience: plan.targetAudience,
    sections: plan.sections.map((section) => ({ ...section })),
    tone: plan.tone,
    targetLength: plan.targetLength,
    targetPlatform: plan.targetPlatform,
    riskNotes: [...plan.riskNotes],
  }
}

export interface ArticleCreativePlanCardProps {
  plan: ArticleCreativePlanView
  busy?: boolean
  onSave: (plan: ArticleCreativePlanDraft) => void
  onConfirm: (plan: ArticleCreativePlanDraft) => void
  onRegenerate: () => void
  onApplyNaturalLanguageRevision?: (instruction: string) => void
}

export function ArticleCreativePlanCard({
  plan,
  busy,
  onSave,
  onConfirm,
  onRegenerate,
  onApplyNaturalLanguageRevision,
}: ArticleCreativePlanCardProps) {
  const [draft, setDraft] = useState<ArticleCreativePlanDraft>(() =>
    copyPlan(plan),
  )
  const [instruction, setInstruction] = useState('')
  const updateSection = (
    index: number,
    update: Partial<ArticleCreativePlanSectionView>,
  ) => {
    const sections = draft.sections.map((section, itemIndex) =>
      itemIndex === index ? { ...section, ...update } : section,
    )
    setDraft({ ...draft, sections })
  }
  const valid = Boolean(
    draft.summary.trim() &&
      draft.coreMessage.trim() &&
      draft.targetAudience.trim() &&
      draft.sections.length >= 2 &&
      draft.sections.length <= 12 &&
      draft.sections.every(
        (section) => section.heading.trim() && section.brief?.trim(),
      ),
  )

  return (
    <Card
      className="article-workflow-card article-creative-plan"
      title="文章创作方案（待确认）"
      extra={<Tag>V{plan.version}</Tag>}
    >
      <Alert
        type="info"
        showIcon
        title="请先核对内容方向"
        description="你可以直接修改每一项，也可以告诉光禾AI智能体框架调整要求。确认方案后才会开放完整正文生成。"
      />
      {onApplyNaturalLanguageRevision ? (
        <div className="article-creative-plan__instruction">
          <Input.TextArea
            aria-label="用自然语言修改创作方案"
            value={instruction}
            maxLength={1000}
            autoSize={{ minRows: 2, maxRows: 4 }}
            placeholder="例如：第二部分不要谈功效，整体语气年轻一点"
            disabled={busy}
            onChange={(event) => setInstruction(event.target.value)}
          />
          <Button
            disabled={!instruction.trim() || busy}
            onClick={() => onApplyNaturalLanguageRevision(instruction.trim())}
          >
            应用修改要求
          </Button>
        </div>
      ) : null}
      <div className="article-creative-plan__fields">
        <label className="article-workflow-field article-workflow-field--wide">
          <span>一句话摘要</span>
          <Input.TextArea
            aria-label="一句话摘要"
            value={draft.summary}
            maxLength={1000}
            autoSize={{ minRows: 2, maxRows: 4 }}
            disabled={busy}
            onChange={(event) =>
              setDraft({ ...draft, summary: event.target.value })
            }
          />
        </label>
        <label className="article-workflow-field article-workflow-field--wide">
          <span>核心观点</span>
          <Input.TextArea
            aria-label="核心观点"
            value={draft.coreMessage}
            maxLength={2000}
            autoSize={{ minRows: 2, maxRows: 4 }}
            disabled={busy}
            onChange={(event) =>
              setDraft({ ...draft, coreMessage: event.target.value })
            }
          />
        </label>
        <label className="article-workflow-field">
          <span>目标读者</span>
          <Input
            aria-label="目标读者"
            value={draft.targetAudience}
            maxLength={1000}
            disabled={busy}
            onChange={(event) =>
              setDraft({ ...draft, targetAudience: event.target.value })
            }
          />
        </label>
        <label className="article-workflow-field">
          <span>发布平台</span>
          <Input
            aria-label="发布平台"
            value={draft.targetPlatform ?? ''}
            maxLength={100}
            placeholder="选填，例如微信公众号"
            disabled={busy}
            onChange={(event) =>
              setDraft({
                ...draft,
                targetPlatform: event.target.value || undefined,
              })
            }
          />
        </label>
        <label className="article-workflow-field">
          <span>内容风格</span>
          <Select
            aria-label="创作方案内容风格"
            value={draft.tone}
            options={TONE_OPTIONS}
            disabled={busy}
            onChange={(tone) => setDraft({ ...draft, tone })}
          />
        </label>
        <label className="article-workflow-field">
          <span>目标篇幅</span>
          <Select
            aria-label="创作方案目标篇幅"
            value={draft.targetLength}
            options={LENGTH_OPTIONS}
            disabled={busy}
            onChange={(targetLength) =>
              setDraft({ ...draft, targetLength })
            }
          />
        </label>
      </div>
      <section className="article-creative-plan__sections">
        <div className="article-creative-plan__section-heading">
          <Typography.Text strong>段落结构</Typography.Text>
          <Button
            size="small"
            icon={<PlusOutlined />}
            disabled={busy || draft.sections.length >= 12}
            onClick={() =>
              setDraft({
                ...draft,
                sections: [
                  ...draft.sections,
                  {
                    key: crypto.randomUUID(),
                    sequence: draft.sections.length + 1,
                    level: 2,
                    heading: '',
                    brief: '',
                  },
                ],
              })
            }
          >
            添加段落
          </Button>
        </div>
        {draft.sections.map((section, index) => (
          <div key={section.key} className="article-creative-plan__section-row">
            <span>{index + 1}</span>
            <Select
              aria-label={`段落 ${index + 1} 层级`}
              value={section.level}
              options={[1, 2, 3, 4, 5, 6].map((level) => ({
                label: `H${level}`,
                value: level,
              }))}
              disabled={busy}
              onChange={(level) => updateSection(index, { level })}
            />
            <Input
              aria-label={`段落 ${index + 1} 标题`}
              value={section.heading}
              maxLength={300}
              placeholder="段落标题"
              disabled={busy}
              onChange={(event) =>
                updateSection(index, { heading: event.target.value })
              }
            />
            <Input
              aria-label={`段落 ${index + 1} 写作要点`}
              value={section.brief ?? ''}
              maxLength={1000}
              placeholder="本段写作要点"
              disabled={busy}
              onChange={(event) =>
                updateSection(index, { brief: event.target.value })
              }
            />
            <Button
              danger
              aria-label={`删除段落 ${index + 1}`}
              icon={<DeleteOutlined />}
              disabled={busy || draft.sections.length <= 2}
              onClick={() =>
                setDraft({
                  ...draft,
                  sections: draft.sections
                    .filter((_, itemIndex) => itemIndex !== index)
                    .map((item, itemIndex) => ({
                      ...item,
                      sequence: itemIndex + 1,
                    })),
                })
              }
            />
          </div>
        ))}
      </section>
      <label className="article-workflow-field">
        <span>风险提示（每行一条）</span>
        <Input.TextArea
          aria-label="风险提示"
          value={draft.riskNotes.join('\n')}
          maxLength={10000}
          autoSize={{ minRows: 2, maxRows: 5 }}
          placeholder="例如：避免医疗功效和绝对化表述"
          disabled={busy}
          onChange={(event) =>
            setDraft({
              ...draft,
              riskNotes: event.target.value
                .split('\n')
                .map((item) => item.trim())
                .filter(Boolean),
            })
          }
        />
      </label>
      <div className="article-workflow-card__actions article-creative-plan__actions">
        <Button
          aria-label="重新生成方案"
          icon={<ReloadOutlined />}
          disabled={busy}
          onClick={onRegenerate}
        >
          重新生成方案
        </Button>
        <Button disabled={!valid || busy} onClick={() => onSave(draft)}>
          保存修改
        </Button>
        <Button
          type="primary"
          loading={busy}
          disabled={!valid || busy}
          onClick={() => onConfirm(draft)}
        >
          确认方案并继续
        </Button>
      </div>
      {plan.updatedAt ? (
        <Typography.Text type="secondary">
          最近更新：{plan.updatedAt}
        </Typography.Text>
      ) : null}
    </Card>
  )
}

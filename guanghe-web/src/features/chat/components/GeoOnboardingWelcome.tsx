import { FileTextOutlined, FormOutlined, UploadOutlined } from '@ant-design/icons'
import { Alert, Button, Collapse, Form, Input, Modal, Spin } from 'antd'
import { useId, useState } from 'react'
import { GeoMessageAvatar } from '../mascot/GeoMascot'
import '../../../styles/geo-onboarding.css'

export interface GeoOnboardingWelcomeProps {
  projectName?: string
  knowledgeState: 'loading' | 'error' | 'empty' | 'existing' | 'unscoped'
  knownBrandName?: string
  disabled?: boolean
  onUpload: () => void
  onOpenKnowledge: () => void
  onRetry: () => void
  /** Return false when the container cannot safely preserve the existing composer draft. */
  onPrefill: (text: string) => boolean
}

const BASIC_FIELDS = [
  { key: 'brand', label: '对外品牌名称', placeholder: '例如：小行星咖啡；可以与公司全称不同', limit: 200 },
  { key: 'offering', label: '主营产品／服务', placeholder: '卖什么、提供什么服务？可以写多个品类', limit: 600 },
  { key: 'customers', label: '主要客户', placeholder: '例如：附近上班族、学生，或某类企业', limit: 500 },
  { key: 'serviceArea', label: '实际服务范围', placeholder: '实际门店所在地、配送范围，或可远程服务的地区', limit: 500 },
  { key: 'targetRegions', label: '目标曝光地域', placeholder: '希望哪些地区的人搜索时找到你？例如：广州天河', limit: 500 },
  { key: 'queries', label: '关键词／顾客问题', placeholder: '例如：广州适合办公的咖啡店；不会写可以先留空', limit: 600 },
] as const

const EXTRA_FIELDS = [
  { key: 'company', label: '公司全称', placeholder: '如与品牌名称不同，可在这里补充', limit: 300 },
  { key: 'strengths', label: '真实优势', placeholder: '有哪些具体的产品、服务或经验优势？不必写广告口号', limit: 800 },
  { key: 'website', label: '官网／官方主页', placeholder: '填写你确认的官网或官方主页链接，没有可以留空', limit: 500 },
  { key: 'evidence', label: '可公开案例／资质及证据', placeholder: '真实案例、资质或数据，尽量附可核对的链接、来源或资料名称', limit: 1500 },
  { key: 'other', label: '其他资料／不可公开内容', placeholder: '可以补充背景和写作偏好；不能对外使用的内容请明确标注“不可公开”', limit: 1500 },
] as const

type Field = (typeof BASIC_FIELDS)[number] | (typeof EXTRA_FIELDS)[number]
type Values = Partial<Record<Field['key'], string>>

function composeProfile(values: Values): string {
  const lines = [...BASIC_FIELDS, ...EXTRA_FIELDS].flatMap((field) => {
    const value = values[field.key]?.trim()
    return value ? [`${field.label}：${value}`] : []
  })
  if (!lines.length) return ''
  return [
    '我来补充这个项目的公司与品牌资料，以下是我提供的信息：',
    ...lines,
    '',
    ...(values.brand?.trim() ? ['请将我填写的对外品牌名称保存到当前项目的品牌档案，其他明确填写的资料也请保存到本项目。'] : ['请将我明确填写的资料保存到本项目；没有填写的品牌名称请先问我。']),
    '请根据我提供的资料梳理本项目的品牌档案，先给出 GEO 优化建议，不启动监测或投稿。未填写的内容不要自行补造，需要时再逐步问我。标为“不可公开”的内容只作为内部约束，不得写入对外文章或搜索词。',
  ].join('\n')
}

export function GeoOnboardingWelcome({
  projectName, knowledgeState, knownBrandName, disabled = false,
  onUpload, onOpenKnowledge, onRetry, onPrefill,
}: GeoOnboardingWelcomeProps) {
  const formId = useId()
  const [open, setOpen] = useState(false)
  const [values, setValues] = useState<Values>({})
  const [prefillFailed, setPrefillFailed] = useState(false)
  const profile = composeProfile(values)
  const unscoped = knowledgeState === 'unscoped'
  const existing = knowledgeState === 'existing'

  const renderField = (field: Field) => <Form.Item key={field.key} label={field.label} htmlFor={`${formId}-${field.key}`}>
    <Input.TextArea id={`${formId}-${field.key}`} value={values[field.key] ?? ''}
      placeholder={field.placeholder} maxLength={field.limit} autoSize={{ minRows: 2, maxRows: 5 }}
      showCount onChange={(event) => {
        setValues((current) => ({ ...current, [field.key]: event.target.value }))
        setPrefillFailed(false)
      }} />
  </Form.Item>

  const prefill = () => {
    if (disabled || !profile) return
    if (!onPrefill(profile)) {
      setPrefillFailed(true)
      return
    }
    setOpen(false)
    setValues({})
    setPrefillFailed(false)
  }

  return <section className="geo-onboarding" aria-label="GEO 智能体欢迎引导">
    <div className="geo-onboarding__greeting">
      <GeoMessageAvatar />
      <div className="geo-onboarding__intro">
        <p className="geo-onboarding__eyebrow">{projectName ? `一起为「${projectName}」做好 GEO` : '先认识你，再一起做好 GEO'}</p>
        <h2>你好，我是 GEO 智能体</h2>
        <p>{existing
          ? '本项目已有资料，可以直接告诉我想完成什么，也可以补充新的公司信息。'
          : knowledgeState === 'loading'
            ? '我可以帮你梳理品牌信息、制定优化方案和创作文章。'
            : knowledgeState === 'error'
              ? '我可以帮你梳理品牌信息、制定优化方案和创作文章。也可以先直接聊聊你的需求。'
              : '初次见面，可以上传公司介绍、产品手册或案例，也可以直接告诉我你的公司信息。'}</p>
      </div>
    </div>

    {knowledgeState === 'loading' ? <div className="geo-onboarding__status" role="status"><Spin size="small" />正在查看本项目是否已有资料…</div> : null}
    {knowledgeState === 'error' ? <Alert type="warning" showIcon title="暂时无法确认本项目的资料，不代表资料为空。"
      action={<Button size="small" disabled={disabled} onClick={onRetry}>重新读取</Button>} /> : null}
    {existing && knownBrandName ? <p className="geo-onboarding__brand">当前品牌：<strong>{knownBrandName}</strong></p> : null}

    <div className="geo-onboarding__actions">
      <Button type="primary" icon={<UploadOutlined aria-hidden />} disabled={disabled || unscoped} onClick={onUpload}>上传文件</Button>
      <Button icon={<FormOutlined aria-hidden />} disabled={disabled} onClick={() => setOpen(true)}>填写公司信息</Button>
      {existing ? <Button type="text" icon={<FileTextOutlined aria-hidden />} disabled={disabled} onClick={onOpenKnowledge}>查看品牌知识库</Button> : null}
    </div>
    <p className="geo-onboarding__hint">{unscoped
      ? '先选择项目才能上传并留存资料；你仍然可以填写信息或直接聊天。'
      : '支持 TXT、Markdown、文字型 PDF，单个文件不超过 20 MB。资料只用于当前项目。'}</p>
    <p className="geo-onboarding__footnote">不必一次填完，我会根据你的目标逐步补充了解。现在只聊方案，不会自动监测或投稿。</p>

    <Modal className="geo-onboarding-modal" title="让我了解你的公司与品牌" open={open} width={720}
      onCancel={() => setOpen(false)} footer={<div className="geo-onboarding-modal__footer">
        <span>关闭会保留本次填写；切换项目后不保留。</span>
        <div><Button onClick={() => setOpen(false)}>暂时收起</Button>
          <Button type="primary" htmlType="submit" form={formId} disabled={disabled || !profile}>放入聊天输入框</Button></div>
      </div>}>
      <p className="geo-onboarding-modal__description">想到什么就填什么，所有字段都可以暂时留空。填写后会先放入聊天输入框，由你检查并发送，不会立即保存或执行任务。</p>
      {prefillFailed ? <Alert type="warning" showIcon title="暂时无法放入输入框，请先检查当前聊天输入。这里的填写已保留。" /> : null}
      <Form id={formId} layout="vertical" requiredMark={false} disabled={disabled} onFinish={prefill}>
        <div className="geo-onboarding-modal__fields">{BASIC_FIELDS.map(renderField)}</div>
        <Collapse ghost items={[{ key: 'more', label: '补充真实优势、官网与案例（选填）',
          children: <div className="geo-onboarding-modal__extras">{EXTRA_FIELDS.map(renderField)}</div> }]} />
      </Form>
    </Modal>
  </section>
}

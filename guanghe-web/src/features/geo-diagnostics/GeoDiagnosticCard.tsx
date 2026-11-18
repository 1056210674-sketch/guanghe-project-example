import { DeleteOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { Alert, Button, Checkbox, Form, Input, Space, Spin, Tag, Typography } from 'antd'
import { useEffect, useState } from 'react'
import type { GeoDiagnosticCompetitor, GeoDiagnosticQuestion, GeoDiagnosticQuote, GeoDiagnosticSnapshot } from '../../api/geoDiagnosticApi'
import type { GeoPlatform } from '../../api/geoApi'
import '../../styles/geo-diagnostics.css'

export type DiagnosticEditor = Pick<GeoDiagnosticSnapshot, 'profile' | 'competitors' | 'discoveryQuestions' | 'reputationQuestions' | 'platformCodes'>

interface Props {
  step: number
  paged?: boolean
  snapshot: GeoDiagnosticSnapshot
  editable: boolean
  frozen?: boolean
  submissionPending?: boolean
  busy: boolean
  generating: boolean
  generationLabel?: string
  generationFailed?: boolean
  error?: string
  platforms: readonly GeoPlatform[]
  platformsLoading: boolean
  platformsError?: string
  quote?: GeoDiagnosticQuote | null
  submissionEnabled?: boolean
  submitting?: boolean
  onSubmit?: () => void
  onSave: (values: DiagnosticEditor, advance: boolean) => void
  onRegenerate: (values: DiagnosticEditor) => void
  onEdit: () => void
  onQuote: (platformCodes: string[]) => void
  onRefresh: () => void
  onRefreshPlatforms: () => void
  onNew: () => void
}

const titles = ['开始品牌诊断', '确认品牌资料', '确认同类品牌', '确认 AI 可见度问题', '确认品牌口碑问题', '选择平台与核对费用']
const budgetReason: Record<string, string> = { SINGLE_ACTION_LIMIT: '预计积分超过单次预算上限。', INSUFFICIENT_POINTS: '当前可用积分不足。', DAY_LIMIT: '本次诊断会超过今日预算。', BUDGET_DISABLED: '账户预算当前已关闭。', BUDGET_NOT_EFFECTIVE: '账户预算设置尚未生效。', BUDGET_UNAVAILABLE: '暂时无法核对账户预算。' }
const normalized = (value: string) => value.normalize('NFKC').toLocaleLowerCase().trim()
const mentioned = (name: string, text: string) => {
  const term = normalized(name)
  if (!term) return false
  const characters = [...term]
  const latinOrNumber = (character: string) => /[\p{Script=Latin}\p{Number}]/u.test(character)
  const left = latinOrNumber(characters[0]) ? '(?<![\\p{Script=Latin}\\p{Number}_])' : ''
  const right = latinOrNumber(characters.at(-1)!) ? '(?![\\p{Script=Latin}\\p{Number}_])' : ''
  const escaped = term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`${left}${escaped}${right}`, 'u').test(normalized(text))
}
const terms = (value: string) => [...new Set(value.split(/[\n,，;；]/).map((item) => item.trim()).filter(Boolean))]
const publicUrl = (value: string) => {
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) ? url.href : undefined } catch { return undefined }
}
const questionError = (rows: readonly GeoDiagnosticQuestion[], brandTerms: readonly string[], reputation: boolean) => {
  const values = rows.map((row) => row.text.trim())
  if (!values.length || values.some((text) => !text)) return '请至少保留一个问题，并填写空白问题。'
  if (values.some((text) => text.length > 1000)) return '单个问题最多填写 1000 字。'
  if (new Set(values.map(normalized)).size !== values.length) return '问题有重复，请修改或删除重复项。'
  const mentionsBrand = (text: string) => brandTerms.some((term) => mentioned(term, text))
  if (reputation ? values.some((text) => !mentionsBrand(text)) : values.some(mentionsBrand)) {
    return reputation ? '口碑问题需要直接写出你的品牌或品牌词。' : '可见度问题请模拟顾客寻找同类品牌时的问法，不直接写出目标品牌或已选同类品牌的名称与品牌词。'
  }
  return undefined
}

/** Pure, editable card; all persistence and AI requests belong to its container. */
export function GeoDiagnosticCard(props: Props) {
  const { step, snapshot, editable, busy, generating } = props
  const [checkedAt, setCheckedAt] = useState(Date.now)
  useEffect(() => {
    if (!props.quote) return
    const delay = Date.parse(props.quote.expiresAt) - Date.now()
    const timer = window.setTimeout(() => setCheckedAt(Date.now()), Number.isFinite(delay) ? Math.max(0, Math.min(delay + 1, 2_147_483_647)) : 0)
    return () => window.clearTimeout(timer)
  }, [props.quote])
  const [values, setValues] = useState<DiagnosticEditor>(() => ({
    profile: { ...snapshot.profile, brandTerms: [...snapshot.profile.brandTerms], region: snapshot.profile.region || '全国' },
    competitors: snapshot.competitors.map((row) => ({ ...row, brandTerms: [...row.brandTerms], sourceIds: [...row.sourceIds] })),
    discoveryQuestions: snapshot.discoveryQuestions.map((row) => ({ ...row })),
    reputationQuestions: snapshot.reputationQuestions.map((row) => ({ ...row })),
    platformCodes: [...snapshot.platformCodes],
  }))
  const locked = busy || generating || !editable || Boolean(props.frozen)
  const updateProfile = (patch: Partial<DiagnosticEditor['profile']>) => setValues((current) => ({ ...current, profile: { ...current.profile, ...patch } }))
  const updateCompetitor = (id: string, patch: Partial<GeoDiagnosticCompetitor>) => setValues((current) => ({ ...current, competitors: current.competitors.map((row) => row.id === id ? { ...row, ...patch, edited: true } : row) }))
  const questionField = step === 4 ? 'discoveryQuestions' : 'reputationQuestions'
  const questionRows = values[questionField]
  const updateQuestion = (id: string, text: string) => setValues((current) => ({ ...current, [questionField]: current[questionField].map((row) => row.id === id ? { ...row, text, edited: true } : row) }))
  const totalQuestions = values.discoveryQuestions.length + values.reputationQuestions.length
  const brandTerms = [...new Set([values.profile.brandName, ...values.profile.brandTerms].filter(Boolean))]
  const discoveryExcludedTerms = [...brandTerms, ...values.competitors.flatMap((row) => [row.name, ...row.brandTerms])]
  const profileError = !values.profile.brandName.trim() ? '请填写品牌名称。'
    : step === 2 && !values.profile.introduction.trim() ? '请补充品牌介绍，至少说明主营产品或服务。'
      : values.profile.brandTerms.length > 50 || values.profile.brandTerms.some((term) => term.length > 200) ? '品牌词最多 50 个，每个最多 200 字。' : undefined
  const competitorError = values.competitors.some((row) => !row.name.trim()) ? '请填写同类品牌名称，或删除空白项。'
    : values.competitors.some((row) => row.brandTerms.length > 50 || row.brandTerms.some((term) => term.length > 200)) ? '每个同类品牌最多设置 50 个品牌词，每词最多 200 字。'
      : new Set(values.competitors.map((row) => normalized(row.name))).size !== values.competitors.length ? '同类品牌有重复，请修改或删除重复项。' : undefined
  const validation = step < 3 ? profileError : step === 3 ? competitorError : step < 6
    ? totalQuestions > 10 ? '两类问题合计最多 10 个，请先删除部分问题。'
      : step === 5 && (totalQuestions < 3 || values.discoveryQuestions.length < 1 || values.reputationQuestions.length < 1) ? '两类问题合计至少 3 个，且各至少保留 1 个。'
        : questionError(questionRows, step === 4 ? discoveryExcludedTerms : brandTerms, step === 5) : undefined
  const quoteMatches = props.quote && props.quote.version === snapshot.version
    && JSON.stringify([...values.platformCodes].sort()) === JSON.stringify([...snapshot.platformCodes].sort())
    && Date.parse(props.quote.expiresAt) > checkedAt
  const shownSources = snapshot.sources.filter((source) => publicUrl(source.url))
  const quoteRangeValid = totalQuestions >= 3 && totalQuestions <= 10 && values.discoveryQuestions.length >= 1 && values.reputationQuestions.length >= 1

  function save(advance: boolean) {
    if (locked || validation) return
    props.onSave({ ...values, profile: { ...values.profile, brandName: values.profile.brandName.trim(), brandTerms, region: values.profile.region.trim() || '全国' } }, advance)
  }

  return <section className={`geo-diagnostic-card${editable ? ' geo-diagnostic-card--current' : ''}`} aria-label={`诊断报告 · ${titles[step - 1]}`}>
    <header className="geo-diagnostic-card__header"><div>{!props.paged ? <Typography.Text type="secondary">诊断报告 · 第 {step} / 6 步</Typography.Text> : null}<Typography.Title level={4}>{titles[step - 1]}</Typography.Title></div>
      <Tag color={editable ? 'processing' : 'default'}>{editable ? '当前步骤' : '已保存记录'}</Tag></header>
    {props.error ? <Alert type="error" showIcon title={props.error} action={<Button disabled={busy} onClick={props.onRefresh}>刷新诊断记录</Button>} /> : null}
    {generating ? <div className="geo-diagnostic-card__generation" role="status"><Spin size="small" /><span>{props.generationLabel ?? '正在准备下一步…'}可以继续在输入框和我聊天。</span></div> : null}
    {props.generationFailed ? <Alert type="warning" showIcon title="这一步暂时没有生成完成，已保留填写的资料。" description="可以修改后重新生成，也可以自行补充结果。" /> : null}
    {!editable || props.frozen ? <>
      {step < 3 ? <dl className="geo-diagnostic-card__facts"><div><dt>品牌</dt><dd>{snapshot.profile.brandName || '尚未填写'}</dd></div>{step === 2 ? <><div><dt>官网</dt><dd>{snapshot.profile.website || '未填写'}</dd></div><div><dt>品牌介绍</dt><dd>{snapshot.profile.introduction || '未填写'}</dd></div><div><dt>品牌词</dt><dd>{snapshot.profile.brandTerms.join('、') || '未填写'}</dd></div><div><dt>目标地域</dt><dd>{snapshot.profile.region || '全国'}</dd></div></> : null}</dl>
        : step === 3 ? <ul className="geo-diagnostic-card__summary-list">{snapshot.competitors.map((row) => <li key={row.id}><strong>{row.name}</strong>{row.brandTerms.length ? ` · ${row.brandTerms.join('、')}` : ''}</li>)}{!snapshot.competitors.length ? <li>未选择同类品牌</li> : null}</ul>
          : step < 6 ? <ol className="geo-diagnostic-card__summary-list">{(step === 4 ? snapshot.discoveryQuestions : snapshot.reputationQuestions).map((row) => <li key={row.id}>{row.text}</li>)}</ol>
            : <p>{snapshot.platformCodes.join('、') || '尚未选择平台'}</p>}
      {!props.frozen ? <><Button size="small" disabled={busy || generating} onClick={props.onEdit}>返回修改这一步</Button>
      <Typography.Text type="secondary">修改这一步后，后面的内容会按新资料重新准备。</Typography.Text></> : <Typography.Text type="secondary">{props.submissionPending ? '正在核对提交回执，先保留配置；尚不能认定提交成功。' : '诊断已提交，以上为本次配置记录。'}</Typography.Text>}
    </> : <Form component="div" layout="vertical" className="geo-diagnostic-card__form">
      {step === 1 ? <><Typography.Paragraph>告诉我你要诊断哪个品牌，我会先查找公开资料，再请你核对。</Typography.Paragraph>
        <Form.Item label="品牌名称" required><Input aria-label="诊断品牌名称" maxLength={200} value={values.profile.brandName} disabled={locked} placeholder="例如：小行星咖啡店" onChange={(event) => updateProfile({ brandName: event.target.value, brandTerms: [event.target.value] })} /></Form.Item></> : null}
      {step === 2 ? <><Typography.Paragraph type="secondary">填写品牌资料和对外名称，目标地域用于生成顾客问法。</Typography.Paragraph>
        <Form.Item label="品牌名称" required><Input aria-label="诊断资料品牌名称" maxLength={200} value={values.profile.brandName} disabled={locked} onChange={(event) => updateProfile({ brandName: event.target.value })} /></Form.Item>
        <Form.Item label="官网地址（可选）"><Input aria-label="诊断官网地址" value={values.profile.website ?? ''} maxLength={2000} placeholder="https://example.com，也可以只填写域名" disabled={locked} onChange={(event) => updateProfile({ website: event.target.value || null })} /></Form.Item>
        <Form.Item label="品牌介绍" required><Input.TextArea aria-label="诊断品牌介绍" value={values.profile.introduction} maxLength={6000} autoSize={{ minRows: 3, maxRows: 8 }} disabled={locked} placeholder="介绍主营产品或服务、主要客户、真实优势等" onChange={(event) => updateProfile({ introduction: event.target.value })} /></Form.Item>
        <Form.Item label="品牌词设置"><Input.TextArea aria-label="诊断品牌词" defaultValue={values.profile.brandTerms.join('\n')} autoSize={{ minRows: 2, maxRows: 5 }} disabled={locked} placeholder="每行一个，例如公司全称、品牌简称、其他对外名称" onChange={(event) => updateProfile({ brandTerms: terms(event.target.value) })} /></Form.Item>
        <Form.Item label="目标地域"><Input aria-label="诊断目标地域" value={values.profile.region} maxLength={100} disabled={locked} placeholder="例如：广州、广东、全国" onChange={(event) => updateProfile({ region: event.target.value })} /></Form.Item>
      </> : null}
      {step === 3 ? <><Typography.Paragraph type="secondary">保留与你的品牌同类、能被顾客替代选择的品牌，最多 5 个。没有合适的候选时，也可以暂不添加。</Typography.Paragraph>
        <div className="geo-diagnostic-card__rows">{values.competitors.map((row, index) => <div className="geo-diagnostic-card__row" key={row.id}>
          <div className="geo-diagnostic-card__row-heading"><Typography.Text strong>同类品牌 {index + 1}</Typography.Text><Tag>{row.origin === 'USER' ? '手动添加' : row.edited ? '已修改' : 'AI 建议'}</Tag><Button size="small" type="text" danger disabled={locked} icon={<DeleteOutlined aria-hidden="true" />} aria-label={`删除同类品牌 ${index + 1}`} onClick={() => setValues((current) => ({ ...current, competitors: current.competitors.filter((item) => item.id !== row.id) }))} /></div>
          <Input aria-label={`同类品牌 ${index + 1} 名称`} maxLength={200} disabled={locked} value={row.name} onChange={(event) => updateCompetitor(row.id, { name: event.target.value })} placeholder="品牌名称" />
          <Input.TextArea aria-label={`同类品牌 ${index + 1} 品牌词`} autoSize={{ minRows: 1, maxRows: 3 }} disabled={locked} defaultValue={row.brandTerms.join('\n')} onChange={(event) => updateCompetitor(row.id, { brandTerms: terms(event.target.value) })} placeholder="每行一个品牌词" />
          {row.reason ? <Typography.Paragraph type="secondary">{row.reason}</Typography.Paragraph> : null}
          {row.sourceIds.length ? <div className="geo-diagnostic-card__row-sources">{row.sourceIds.flatMap((id) => { const source = shownSources.find((item) => item.id === id); return source ? [<a key={id} href={publicUrl(source.url)} target="_blank" rel="noopener noreferrer">{source.title}</a>] : [] })}</div> : null}
        </div>)}</div>
        <Button icon={<PlusOutlined aria-hidden="true" />} disabled={locked || values.competitors.length >= 5} onClick={() => setValues((current) => ({ ...current, competitors: [...current.competitors, { id: crypto.randomUUID(), name: '', brandTerms: [], reason: '', sourceIds: [], origin: 'USER', edited: true }] }))}>添加同类品牌</Button>
      </> : null}
      {step === 4 || step === 5 ? <><Typography.Paragraph type="secondary">{step === 4 ? '模拟顾客寻找产品、门店或服务商时会问的问题，不直接写目标品牌或已选同类品牌的名称与品牌词。' : '直接询问你的品牌，例如“体验如何”“是否值得考虑”，与自然被发现的可见度分开分析。'}两类问题各至少 1 个，合计 3～10 个。</Typography.Paragraph>
        <div className="geo-diagnostic-card__rows">{questionRows.map((row, index) => <div className="geo-diagnostic-card__question" key={row.id}><span>{index + 1}</span>
          <Input.TextArea aria-label={`${step === 4 ? '可见度' : '口碑'}问题 ${index + 1}`} value={row.text} maxLength={1000} autoSize={{ minRows: 2, maxRows: 4 }} disabled={locked} onChange={(event) => updateQuestion(row.id, event.target.value)} />
          <Button size="small" type="text" danger icon={<DeleteOutlined aria-hidden="true" />} disabled={locked || questionRows.length <= 1} aria-label={`删除${step === 4 ? '可见度' : '口碑'}问题 ${index + 1}`} onClick={() => setValues((current) => ({ ...current, [questionField]: current[questionField].filter((item) => item.id !== row.id) }))} />
        </div>)}</div>
        <Button icon={<PlusOutlined aria-hidden="true" />} disabled={locked || totalQuestions >= 10} onClick={() => setValues((current) => ({ ...current, [questionField]: [...current[questionField], { id: crypto.randomUUID(), text: '', origin: 'USER', edited: true }] }))}>添加{step === 4 ? '可见度' : '口碑'}问题</Button>
        <Typography.Text type="secondary">可见度 {values.discoveryQuestions.length} 个 · 口碑 {values.reputationQuestions.length} 个</Typography.Text>
      </> : null}
      {step === 6 ? <><Typography.Paragraph>{values.profile.brandName} · {values.discoveryQuestions.length} 个可见度问题 + {values.reputationQuestions.length} 个口碑问题；每个平台独立采样 1 次。</Typography.Paragraph>
        {props.platformsError ? <Alert type="error" title={props.platformsError} action={<Button onClick={props.onRefreshPlatforms}>重新加载平台</Button>} /> : null}
        <Form.Item label="监测平台" required><Checkbox.Group aria-label="诊断监测平台" value={values.platformCodes} disabled={locked || props.platformsLoading || Boolean(props.platformsError)} options={props.platforms.map((platform) => ({ value: platform.code, label: platform.name, disabled: !platform.configured || !platform.selectable }))} onChange={(codes) => setValues((current) => ({ ...current, platformCodes: props.platforms.filter((platform) => platform.configured && platform.selectable && codes.includes(platform.code)).map((platform) => platform.code) }))} /></Form.Item>
        {props.platformsLoading ? <div role="status"><Spin size="small" /> 正在读取可用平台…</div> : null}
        {!props.platformsLoading && !props.platformsError && !props.platforms.some((platform) => platform.configured && platform.selectable) ? <Typography.Text type="secondary">暂无可选平台，请稍后重新加载。</Typography.Text> : null}
        {!quoteRangeValid ? <Typography.Text type="warning">请返回问题卡片，保留至少 3 个问题，且两类问题各至少 1 个。</Typography.Text> : null}
        <Button loading={busy} disabled={locked || !values.platformCodes.length || Boolean(props.platformsError) || !quoteRangeValid} onClick={() => props.onQuote(values.platformCodes)}>核对本次费用</Button>
        {quoteMatches ? <div className="geo-diagnostic-card__quote"><strong>预计 {props.quote!.points} 积分</strong><span>{props.quote!.expectedSamples} 个独立采样 · 报价有效至 {new Date(props.quote!.expiresAt).toLocaleTimeString('zh-CN', { timeZone: 'Asia/Shanghai' })}（北京时间）</span></div> : <Typography.Text type="secondary">选择平台后核对实际费用，修改配置会使旧报价失效。</Typography.Text>}
        {quoteMatches && props.quote!.blockedReason ? <Alert type="warning" showIcon title="当前预算不允许提交本次诊断" description={`${budgetReason[props.quote!.blockedReason!] ?? '预算核对尚未通过。'}预计 ${props.quote!.points} 积分${props.quote!.maxPointsPerAction !== null ? `，单次上限 ${props.quote!.maxPointsPerAction} 积分` : ''}${props.quote!.maxPointsPerDay !== null ? `，每日上限 ${props.quote!.maxPointsPerDay} 积分` : ''}${props.quote!.availablePoints !== null ? `，可用余额 ${props.quote!.availablePoints} 积分` : ''}。可自行调整平台或问题，或在预算设置中明确修改上限；系统不会替你提高限额。`} /> : null}
        {props.submissionEnabled ? <Typography.Text type="secondary">提交后可继续聊天，完成后可查看报告或下载 PDF。</Typography.Text> : <Alert type="info" showIcon title="诊断暂不可提交" description="后台监测及完整 PDF 交付尚未开放，当前不会提交监测。" />}
        <Button type="primary" loading={props.submitting} disabled={locked || !props.submissionEnabled || !quoteMatches || Boolean(props.quote?.blockedReason) || !props.onSubmit} onClick={props.onSubmit}>{props.submissionEnabled ? '提交诊断并生成 PDF' : '提交诊断并生成 PDF（尚未开放）'}</Button>
      </> : null}
      {validation && !generating ? <Typography.Text type="secondary" role="status">{validation}</Typography.Text> : null}
      {step < 6 ? <Space wrap className="geo-diagnostic-card__actions">
        <Button type="primary" disabled={locked || Boolean(validation)} loading={busy} onClick={() => save(true)}>{step === 1 ? '开始诊断' : step === 5 ? '确认问题，选择平台' : '确认并继续'}</Button>
        {step > 1 ? <Button disabled={locked || Boolean(validation)} onClick={() => save(false)}>保存当前修改</Button> : null}
        {step > 2 && step < 6 ? <Button icon={<ReloadOutlined aria-hidden="true" />} disabled={locked} onClick={() => props.onRegenerate(values)}>重新生成{step === 3 ? '同类品牌' : step === 4 ? '可见度问题' : '口碑问题'}</Button> : null}
      </Space> : null}
    </Form>}
    {shownSources.length && (step === 2 || step === 3) ? <details className="geo-diagnostic-card__sources"><summary>查看公开资料来源（{shownSources.length}）</summary><ul>{shownSources.map((source) => <li key={source.id}><a href={publicUrl(source.url)} target="_blank" rel="noopener noreferrer">{source.title || source.url}</a>{source.snippet ? <p>{source.snippet}</p> : null}</li>)}</ul></details> : null}
    {editable ? <Button type="link" className="geo-diagnostic-card__new" disabled={busy || generating} onClick={props.onNew}>新建另一份诊断</Button> : null}
  </section>
}

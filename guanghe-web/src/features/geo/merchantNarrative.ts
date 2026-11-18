import type { GeoReport, GeoRun } from '../../api/geoApi'

const platformNames: Record<string, string> = { DOUBAO: '豆包', DEEPSEEK: 'DeepSeek', QWEN: '通义千问', WENXIN: '文心一言', KIMI: 'Kimi', YUANBAO: '腾讯元宝', ZHIPU: '智谱' }
const escapePattern = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** Presentation only: never persist this projection or apply it to saved answers/citations. */
export function geoMerchantNarrative(report: GeoReport, context?: Pick<GeoRun, 'competitors'>) {
  const target = report.visibility?.target
  const protectedNames = [target?.name, ...(target?.aliases ?? []), report.topic.name,
    ...(context?.competitors ?? []).flatMap((competitor) => [competitor.name, ...competitor.aliases]),
    ...report.observations.flatMap((observation) => (observation.citations ?? []).flatMap((citation) => [citation.url, citation.domain]))]
    .filter((value): value is string => Boolean(value)).sort((a, b) => b.length - a.length)
  const humanize = (input: string): string => {
    const protectedText: string[] = []
    const protect = (value: string) => { protectedText.push(value); return `\uE000${protectedText.length - 1}\uE001` }
    let value = protectedNames.length ? input.replace(new RegExp(protectedNames.map(escapePattern).join('|'), 'g'), protect) : input
    value = value.replace(/https?:\/\/[^\s<>\u3000-\u303f\uff00-\uffef]+/g, protect)
    // Preserve prices and numeric model names, even when their digits match an internal id.
    value = value.replace(/(?:[¥￥$]\s*\d[\d,.]*|\d[\d,.]*\s*(?:元|万元|美元))/g, protect)
    for (const observation of report.observations) {
      value = value.replaceAll(observation.unitKey, () => protect(`“${observation.questionText}”在${platformNames[observation.platformCode] ?? '该平台'}的第 ${observation.sampleNo} 轮回答`))
    }
    const ids: Array<[string | null | undefined, string]> = [
      [report.id, '本报告'], [report.runId, '本次监测'], [report.taskId, '本次监测'],
      [report.topicId, '本专题'], [report.topic.spaceId, '经营空间'], [report.topic.productId, target?.name ?? '监测商品'],
      ...(report.questions ?? []).map((question): [string, string] => [question.id, `“${question.text}”`]),
      ...(context?.competitors ?? []).map((competitor): [string, string] => [competitor.id, competitor.name]),
    ]
    for (const [id, label] of ids) {
      if (!id) continue
      // Contextual ids only: an ordinary amount or model suffix must never be scrubbed.
      value = value.replace(new RegExp(`(?:questionId|topicId|productId|spaceId|reportId|runId|taskId|competitorId|问题(?:编号)?|专题(?:编号)?|商品编号|报告编号|任务编号)\\s*[:：=]?\\s*["']?${escapePattern(id)}["']?(?![\\dA-Za-z_-])`, 'g'), () => protect(label))
    }
    // A known question id may be the second item in “问题 id 与 id”; match only
    // frozen question identities, with model-name/number boundaries preserved.
    for (const [id, label] of ids) if (id && id.length >= 12) value = value.replace(new RegExp(`(?<![\\dA-Za-z_-])${escapePattern(id)}(?![\\dA-Za-z_-])`, 'g'), () => protect(label))
    value = value
      .replace(/\btargetMentioned\s*(?:[=:：]|为|均为|均是)?\s*null\b/gi, '尚不能评估是否提及')
      .replace(/\btargetMentioned\s*(?:[=:：]|为|均为|均是)?\s*false\b/gi, '未提及监测对象')
      .replace(/\btargetMentioned\s*(?:[=:：]|为|均为|均是)?\s*true\b/gi, '提及监测对象')
      .replace(/\btargetMentioned\b/g, '监测对象提及结果')
      .replace(/\bGEO_PROVIDER_TIMEOUT\b/g, '平台回答超时')
      .replace(/\bGEO_[A-Z][A-Z0-9_]+\b/g, '采样服务暂未完成')
      .replace(/\b(?:PROVIDER|DEEPSEEK|MODEL)_[A-Z][A-Z0-9_]+\b/g, '本次未取得完整结果')
      .replace(/\bJava\b/gi, '系统统计')
      .replace(/geo-(?:deterministic|synthesis)-v\d+/g, '本次分析方法')
      .replace(/explicit-name-mention-v\d+/g, '明确名称提及统计')
      .replace(/\bomittedCitationCount\s*[=:：]\s*(\d+)/g, '另有 $1 条引用未纳入这段解读')
      .replace(/\bcompetitorSetVersion\b/g, '竞品集合版本').replace(/\bcompetitors\b/g, '竞品资料')
      .replace(/\brecommendations\b/g, '内容建议').replace(/\bcontentBlueprint\b/g, '内容蓝图')
      .replace(/\bPRODUCT\b/g, '商品').replace(/\bBRAND\b/g, '品牌')
      .replace(/\bvisibilityEvidence\b/g, '完整回答的名称核对结果')
      .replace(/\bauthorizedContext(?:\.monitoringTarget)?\b/g, '本次监测资料')
      .replace(/\bmonitoringTarget\b/g, '监测对象').replace(/\bobservations\b/g, '本次回答')
      .replace(/\bmethodVersion\b/g, '分析方法').replace(/\bmissingSamples\b/g, '未完成回答')
      .replace(/\bTARGET_UNRESOLVED\b/g, '监测对象尚未明确').replace(/\bAVAILABLE\b/g, '可核验')
      .replace(/\bSUCCEEDED\b/g, '已取得回答').replace(/\bFAILED\b/g, '未取得回答')
      .replace(/\b(?:unitKey|questionId|topicId|productId|spaceId|reportId|runId|taskId|stableErrorCode)\b/g, '监测记录')
      .replace(/\bfalse\b/g, '未提及').replace(/\btrue\b/g, '已提及').replace(/\bnull\b/g, '未提供')
    for (const [code, name] of Object.entries(platformNames)) value = value.replace(new RegExp(`\\b${code}\\b`, 'g'), name)
    return value.replace(/\uE000(\d+)\uE001/g, (_, index: string) => protectedText[Number(index)]).trim()
  }
  const assessed = report.visibility?.assessmentStatus === 'AVAILABLE'
  const metrics = report.visibility?.platforms ?? []
  const valid = metrics.reduce((sum, metric) => sum + metric.validSamples, 0)
  const mentioned = metrics.reduce((sum, metric) => sum + (metric.mentionedSamples ?? 0), 0)
  const factualSummary = report.succeededUnits === 0 || report.visibility?.assessmentStatus === 'NO_VALID_SAMPLES' || (assessed && valid === 0)
    ? '本次未取得有效回答，暂时无法判断商品是否被提及。采集失败不代表未被提及。'
    : !assessed ? '历史记录不足以可靠评估名称提及情况；请结合已保存的原始回答查看。'
    : mentioned === 0
      ? `本次 ${valid} 条有效回答中，尚未观察到“${target?.name}”的明确名称提及。失败与缺失回答不计作未提及。`
      : `本次 ${valid} 条有效回答中，${mentioned} 条明确提及“${target?.name}”。这是本轮采样观察，不代表推荐、排名或长期表现。`
  const analyzed = report.methodVersion === 'geo-synthesis-v1'
  const list = (items: readonly string[] = []) => analyzed ? items.map(humanize).filter(Boolean) : []
  const diagnostic = /核对.*(?:名称|别名|资料)|完善.*(?:名称|资料)|(?:补齐|补充|新增|扩充).*问题|(?:重新|补充|调整|配置).*监测|纳入.*监测|检查.*(?:失败|采样)/
  const recommendations = analyzed ? (report.synthesis.recommendations ?? [])
    .map((item) => ({ ...item, title: humanize(item.title), rationale: humanize(item.rationale) }))
    .filter((item) => !diagnostic.test(`${item.title} ${item.rationale}`)) : []
  return {
    factualSummary,
    summary: analyzed ? humanize(report.synthesis.summary || report.summary) : '尚未形成智能解读；已保存的回答与提及结果仍可查看。',
    findings: list(report.synthesis.findings), coverageGaps: list(report.synthesis.coverageGaps),
    competitorSignals: list(report.synthesis.competitorSignals), contentBlueprint: list(report.synthesis.contentBlueprint),
    citationStructure: (report.synthesis.citationStructure ?? []).map(humanize), recommendations,
  }
}

import type { GeoDiagnosticReport } from '../../api/geoDiagnosticApi'
import { diagnosticRate } from './reportPresentation'

type Sample = GeoDiagnosticReport['samples'][number]

export function diagnosticBatchBaselines(report: GeoDiagnosticReport) {
  const discoveryValid = report.metrics.discovery.reduce((total, row) => total + row.validSamples, 0)
  const discoveryHits = report.metrics.discovery.reduce((total, row) => total + (row.mentions.find((brand) => brand.brandId === 'OWN')?.mentionedSamples ?? 0), 0)
  const reputationValid = report.metrics.reputation.reduce((total, row) => total + row.validSamples, 0)
  const reputationHits = report.metrics.reputation.reduce((total, row) => total + row.recognizedSamples, 0)
  const competitorMentions = report.metrics.discovery.reduce((total, row) => total + row.mentions.filter((brand) => brand.brandId !== 'OWN').reduce((sum, brand) => sum + brand.mentionedSamples, 0), 0)
  const competitorIds = new Set([...(report.competitors ?? []).map((brand) => brand.id), ...report.metrics.discovery.flatMap((row) => row.mentions.filter((brand) => brand.brandId !== 'OWN').map((brand) => brand.brandId))])
  // Use the same saved-URL identity as Java/PDF, without URL normalization.
  const sourceCount = new Set(report.sources.map((source) => source.url.trim()).filter(Boolean)).size
  const discoveryRate = discoveryValid ? discoveryHits / discoveryValid : null
  const reputationRate = reputationValid ? reputationHits / reputationValid : null
  const coverageRate = report.metrics.expectedSamples ? report.metrics.validSamples / report.metrics.expectedSamples : null
  return { discoveryValid, discoveryHits, reputationValid, reputationHits, competitorMentions, competitorCount: competitorIds.size, sourceCount, discoveryRate, reputationRate, coverageRate,
    rows: [
      { label: '自然提及率', value: diagnosticRate(discoveryRate), detail: `${discoveryHits} / ${discoveryValid} 条有效发现型回答`, next: '沿用本次问题与平台复测，比较自然提及变化。', method: '目标品牌在有效发现型回答中的提及比例，口碑组不计入。' },
      { label: '自然命中样本', value: `${discoveryHits} 条`, detail: `有效自然样本 ${discoveryValid} 条`, next: '逐条核对新命中的问题及回答依据。', method: '提及目标品牌的自然发现回答数，不是去重问题数。' },
      { label: '点名再次提及率', value: diagnosticRate(reputationRate), detail: `${reputationHits} / ${reputationValid} 条有效口碑回答`, next: '结合原文检查描述是否准确，不只看品牌名字。', method: '显式点名品牌后的再次提及，不等于正确识别或正面评价。' },
      { label: '有效覆盖率', value: report.metrics.expectedSamples ? `${((coverageRate ?? 0) * 100).toFixed(1)}%` : '无计划样本', detail: `${report.metrics.validSamples} / ${report.metrics.expectedSamples} 条计划样本`, next: '复测时核对有效覆盖，区分平台缺口与品牌表现。', method: '有效回答 / 全部计划样本。失败、缺失不当作品牌未提及。' },
      { label: '竞品合计提及次数', value: `${competitorMentions} 次`, detail: `${competitorIds.size} 个选定竞品 · 单位：品牌×回答`, next: '对照同批问题，检查哪些回答选择了同类品牌。', method: '选定竞品的提及次数合计；一条回答可提及多个品牌。' },
      { label: '本批可追溯来源', value: `${sourceCount} 项`, detail: '报告信源 · 按 URL 去重', next: '保留可访问来源，核对其中的品牌信息与表达。', method: '包含准备阶段来源，不等同于采样引用总量或采用量。' },
    ],
  }
}

/** Successful unknowns, failures and missing answers are never converted into checked negatives. */
export function diagnosticSampleOutcome(samples: Sample[], brandId = 'OWN') {
  if (!samples.length) return { label: '未采集', state: 'missing' }
  const valid = samples.filter((sample) => sample.status === 'SUCCEEDED')
  if (!valid.length) return { label: samples.some((sample) => sample.status === 'FAILED') ? '采样失败' : '未采集', state: 'missing' }
  const unknown = valid.filter((sample) => sample.matchedBrandIds == null).length
  const hits = valid.filter((sample) => sample.matchedBrandIds?.includes(brandId)).length
  if (unknown) return { label: valid.length === 1 ? '未记录命中' : `${hits} 条已提及 · ${unknown} 条未记录`, state: 'unknown' }
  if (valid.length === 1) return { label: hits ? '已提及' : '未提及', state: hits ? 'hit' : 'miss' }
  return { label: `${hits}/${valid.length} 条提及`, state: hits ? 'hit' : 'miss' }
}

export function diagnosticLayerMeasure(samples: Sample[]) {
  const valid = samples.filter((sample) => sample.status === 'SUCCEEDED')
  const unknown = valid.filter((sample) => sample.matchedBrandIds == null).length
  const hits = valid.filter((sample) => sample.matchedBrandIds?.includes('OWN')).length
  return { valid: valid.length, unknown, hits, rate: valid.length && !unknown ? hits / valid.length : null }
}

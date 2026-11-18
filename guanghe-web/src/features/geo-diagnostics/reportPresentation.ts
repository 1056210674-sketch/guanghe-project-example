/** Presentation-only chart rows: no request or resource identifiers. */
export interface DiagnosticChartRow {
  label: string
  values: { name: string; rate: number | null; count: number; target: boolean }[]
  valid: number
  expected: number
  failed: number
  missing: number
}

export const diagnosticPlatformName = (code: string) => ({ DEEPSEEK: 'DeepSeek', DOUBAO: '豆包', KIMI: 'Kimi', QWEN: '通义千问', WENXIN: '文心', YUANBAO: '元宝', ZHIPU: '智谱' })[code] ?? code
export const diagnosticIntentLabel = (layer: string) => ({ BRAND: '品牌认知', CATEGORY: '赛道需求', SCENARIO: '使用场景', QUESTION: '知识问答', DECISION: '选购决策' })[layer] ?? layer
export const diagnosticRate = (rate: number | null) => rate === null ? '无有效样本' : `${(rate * 100).toFixed(1)}%`
export const diagnosticPublicUrl = (value?: string | null) => {
  try { const parsed = new URL(value ?? ''); return ['http:', 'https:'].includes(parsed.protocol) && !parsed.username && !parsed.password ? parsed.href : undefined } catch { return undefined }
}

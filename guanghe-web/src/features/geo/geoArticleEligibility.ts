import type { GeoReport } from '../../api/geoApi'

/** Presentation guard only; article-workflows rechecks the current report on every request. */
export function geoArticleUnavailableReason(report: GeoReport): string | undefined {
  if (report.archivedAt) return '这份报告已归档，不能创建文章草稿。'
  if (!['SUCCEEDED', 'PARTIAL'].includes(report.status)) return '监测尚未完成，请稍后查看报告。'
  if (!Number.isFinite(report.sampleSize) || report.sampleSize <= 0) return '这份报告没有有效样本，暂不能据此写文章。'
  if (report.methodVersion !== 'geo-synthesis-v1') return '这份报告尚未完成智能解读，请先在报告中查看解读状态。'
  return undefined
}

import type { GeoCreativePlan, GeoCreativePlanResponse, GeoReport } from '../../api/geoApi'

/** Rehydrate a quoted plan from its frozen fields and the owned report; no model call or new quote. */
export function restoredGeoVisualPlan(plan: GeoCreativePlan, report?: GeoReport): GeoCreativePlanResponse {
  const evidence = (report?.observations ?? [])
    .filter((item) => item.status === 'SUCCEEDED' && plan.evidenceKeys.includes(item.unitKey))
    .map((item) => ({
      key: item.unitKey,
      questionText: item.questionText,
      platformCode: item.platformCode,
      answerExcerpt: (item.answerText ?? '').trim().slice(0, 360),
    }))
  return { reportId: plan.reportId, recommendationId: plan.recommendationId, plan, evidence }
}

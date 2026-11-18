import type { PaidTask } from '../../api/conversationApi'
import type { GeoReport, GeoRun } from '../../api/geoApi'
import { geoReportDto, geoRunDto } from './geo'

const baseReport = geoReportDto('geo-deterministic-v1', {
  summary: '', findings: [], coverageGaps: [], citationStructure: [],
  competitorSignals: [], recommendations: [], contentBlueprint: [], missingSamples: [],
})
const questions = Array.from({ length: 10 }, (_, index) => ({
  id: String(6101 + index), category: 'BRAND_DISCOVERY' as const, text: `并发监测问题 ${index + 1}`,
}))

/** Ten completed sampling units: two saved answers and eight upstream refusals. */
export const partialRateLimitedReport: GeoReport = {
  ...baseReport, expectedUnits: 10, succeededUnits: 2, failedUnits: 8, missingUnits: 0,
  sampleSize: 10, settledPoints: 2, releasedPoints: 8, questions, platformCodes: ['ZHIPU'],
  generatedAt: geoRunDto.finishedAt!,
  visibility: {
    ...baseReport.visibility!,
    platforms: [{ platformCode: 'ZHIPU', expectedSamples: 10, validSamples: 2,
      mentionedSamples: null, failedSamples: 8, missingSamples: 0, mentionRate: null }],
  },
  observations: questions.map((question, index) => ({
    ...baseReport.observations[0], unitKey: `${question.id}:ZHIPU:1`,
    questionId: question.id, questionText: question.text, platformCode: 'ZHIPU', model: 'glm-4.7',
    status: index < 2 ? 'SUCCEEDED' : 'FAILED',
    answerText: index < 2 ? `第 ${index + 1} 条已保存的成功回答。` : null,
    stableErrorCode: index < 2 ? null : 'GEO_PROVIDER_RATE_LIMITED',
    usage: index < 2 ? baseReport.observations[0].usage : {
      inputTokens: 0, outputTokens: 0, searchRequests: 0, monetaryCost: null, currency: null,
    },
  })),
}

export const partialRateLimitedRun: GeoRun = {
  ...geoRunDto, questions, platformCodes: ['ZHIPU'],
  expectedUnits: 10, succeededUnits: 2, failedUnits: 8, missingUnits: 0,
}

export const partialRateLimitedTask: PaidTask = {
  id: '7001', taskType: 'GEO', status: 'PARTIAL', stageCode: 'PARTIAL_COMPLETED', stageLabel: '部分完成',
  spaceId: '4001', productId: null, quotedPoints: 10, frozenPoints: 0,
  settledPoints: 2, releasedPoints: 8,
  progress: { percent: 100, completedUnits: 10, totalUnits: 10 },
  result: { runId: '8001', reportId: '9001', status: 'PARTIAL',
    expectedUnits: 10, succeededUnits: 2, failedUnits: 8, missingUnits: 0,
    settledPoints: 2, releasedPoints: 8 },
  errorCode: null, createdAt: geoRunDto.createdAt, updatedAt: geoRunDto.finishedAt!,
}

export const completedGeoTask: PaidTask = {
  ...partialRateLimitedTask, status: 'SUCCEEDED', stageCode: 'COMPLETED', stageLabel: '已完成',
  settledPoints: 10, releasedPoints: 0,
  result: { runId: partialRateLimitedRun.id, reportId: partialRateLimitedReport.id, status: 'SUCCEEDED',
    expectedUnits: 10, succeededUnits: 10, failedUnits: 0, missingUnits: 0,
    settledPoints: 10, releasedPoints: 0 },
}

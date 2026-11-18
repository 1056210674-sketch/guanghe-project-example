import type { GeoReport, GeoRun } from '../../api/geoApi'
import type {
  GeoPlanView,
  GeoPlatformPerformanceView,
  GeoPlatformView,
  GeoQuestionView,
  GeoReportDetailView,
  GeoTopicView,
} from '../../features/geo'

export const geoPlatformFixtures = [
  { code: 'DOUBAO', name: '豆包', availability: 'AVAILABLE' },
  { code: 'DEEPSEEK', name: 'DeepSeek', availability: 'AVAILABLE' },
  { code: 'QWEN', name: '通义千问', availability: 'AVAILABLE' },
  { code: 'WENXIN', name: '文心一言', availability: 'AVAILABLE' },
  { code: 'KIMI', name: 'Kimi', availability: 'AVAILABLE' },
  { code: 'YUANBAO', name: '腾讯元宝', availability: 'AVAILABLE' },
  { code: 'ZHIPU', name: '智谱', availability: 'AVAILABLE' },
] as const satisfies readonly GeoPlatformView[]

export const geoPlatformPerformanceFixture = geoPlatformFixtures.map(
  (platform, index) => ({
    platform,
    primaryValue: `${70 - index}%`,
    primaryLabel: '测试可见度',
    statusLabel: index === 6 ? '测试平台失败' : '测试平台完成',
  }),
) satisfies readonly GeoPlatformPerformanceView[]

export const geoTopicFixture = {
  id: 'geo-topic-fixture',
  objectId: 'geo-object-fixture',
  name: '测试监测专题',
  keywords: ['测试关键词'],
  platformCodes: geoPlatformFixtures.map((platform) => platform.code),
  questionCount: 1,
  reportCount: 1,
  planCount: 2,
} satisfies GeoTopicView

export const geoQuestionFixture = {
  id: 'geo-question-fixture',
  text: '测试问题内容',
  typeLabel: '测试问题类型',
  source: 'MANUAL',
  relatedKeywords: ['测试关键词'],
  status: 'ENABLED',
} satisfies GeoQuestionView

export const geoPlanFixtures = [
  {
    id: 'geo-plan-active-fixture',
    topicId: geoTopicFixture.id,
    name: '测试运行计划',
    topicName: geoTopicFixture.name,
    questionIds: ['geo-question-1', 'geo-question-2', 'geo-question-3'],
    platformCodes: ['DOUBAO'],
    mode: 'STANDARD',
    schedule: {
      frequency: 'DAILY',
      localTime: '09:00',
      timeZone: 'Asia/Shanghai',
    },
    status: 'ACTIVE',
    nextRunAt: '测试下次执行时间',
    estimatedPointsPerRun: 21,
    maxPointsPerRun: 30,
    pricingVersionId: 'pricing-version-fixture',
    version: 1,
    createdAt: '测试创建时间',
    updatedAt: '测试更新时间',
  },
  {
    id: 'geo-plan-paused-fixture',
    topicId: geoTopicFixture.id,
    name: '测试暂停计划',
    topicName: geoTopicFixture.name,
    questionIds: ['geo-question-1', 'geo-question-2', 'geo-question-3'],
    platformCodes: ['DEEPSEEK'],
    mode: 'DEEP',
    schedule: {
      frequency: 'INTERVAL_DAYS',
      intervalDays: 3,
      localTime: '10:00',
      timeZone: 'Asia/Shanghai',
    },
    status: 'PAUSED',
    estimatedPointsPerRun: 42,
    maxPointsPerRun: 50,
    pricingVersionId: 'pricing-version-fixture',
    version: 2,
    createdAt: '测试创建时间',
    updatedAt: '测试更新时间',
  },
] as const satisfies readonly GeoPlanView[]

export const geoPartialReportFixture = {
  id: 'geo-report-fixture',
  name: '测试部分完成报告',
  objectName: '测试监测对象',
  monitoredAt: '测试监测时间',
  sampleCount: 2,
  sampleRounds: 1,
  platformCount: 7,
  status: 'PARTIAL',
  metrics: [
    {
      key: 'visibility',
      label: '测试品牌可见度',
      value: '68%',
      tone: 'WARNING',
    },
  ],
  trend: [{ label: '测试周期', value: 68 }],
  platformPerformance: geoPlatformPerformanceFixture,
  suggestions: [],
  samples: [
    {
      id: 'geo-sample-success-fixture',
      questionId: geoQuestionFixture.id,
      question: '测试成功问题',
      platform: geoPlatformFixtures[0],
      executionStatus: 'SUCCEEDED',
      analysisStatus: 'MENTIONED',
      originalAnswer: '测试平台回答',
      citations: [],
    },
    {
      id: 'geo-sample-failed-fixture',
      questionId: geoQuestionFixture.id,
      question: '测试失败问题',
      platform: geoPlatformFixtures[6],
      executionStatus: 'FAILED',
      analysisStatus: 'PENDING',
      citations: [],
      failureReason: '测试平台未返回有效回答',
    },
  ],
  summary: '测试报告摘要',
} satisfies GeoReportDetailView

const geoTopicSnapshotFixture = {
  id: '5001',
  spaceId: '4001',
  productId: null,
  name: '测试监测对象',
  region: null,
  version: 3,
}

const geoQuestionSnapshotsFixture = [
  { id: '6001', category: 'BRAND_DISCOVERY' as const, text: '测试问题' },
]

/**
 * A GEO report exactly as Java returns it.
 *
 * `methodVersion` is a parameter rather than an overridable field, because the whole point of the
 * report surface is that this one value — and nothing about the content around it — decides whether
 * a conclusion exists to show.
 */
export function geoReportDto(
  methodVersion: GeoReport['methodVersion'],
  synthesis: Partial<GeoReport['synthesis']> = {},
): GeoReport {
  return {
    id: '9001',
    runId: '8001',
    taskId: '7001',
    topicId: geoTopicSnapshotFixture.id,
    status: 'PARTIAL',
    summary: '确定性摘要：品牌在测试平台被提及。',
    sampleSize: 3,
    settledPoints: 12,
    releasedPoints: 3,
    generatedAt: '2026-09-20T10:30:00Z',
    topic: { ...geoTopicSnapshotFixture },
    questions: geoQuestionSnapshotsFixture,
    platformCodes: ['DOUBAO'],
    expectedUnits: 4,
    succeededUnits: 2,
    failedUnits: 1,
    missingUnits: 1,
    visibility: {
      methodVersion: 'explicit-name-mention-v1', assessmentStatus: 'UNAVAILABLE_TARGET',
      unavailableReason: 'MISSING_TARGET_SNAPSHOT', target: null,
      platforms: [{ platformCode: 'DOUBAO', expectedSamples: 4, validSamples: 2, mentionedSamples: null, failedSamples: 1, missingSamples: 1, mentionRate: null }],
      samples: [],
    },
    observations: [
      {
        unitKey: 'unit-1',
        questionId: '6001',
        questionText: '测试成功问题',
        platformCode: 'DOUBAO',
        sampleNo: 1,
        status: 'SUCCEEDED',
        answerText: '测试平台回答，包含品牌事实。',
        stableErrorCode: null,
        provider: 'test-provider',
        model: 'test-model-v1',
        startedAt: '2026-09-20T10:30:00Z',
        finishedAt: '2026-09-20T10:30:05Z',
        latencyMs: 1200,
        usage: {
          inputTokens: 100,
          outputTokens: 200,
          searchRequests: 1,
          monetaryCost: null,
          currency: null,
        },
        providerCallCount: 1,
        citations: [],
      },
    ],
    synthesis: {
      summary: 'AI 综合摘要',
      coverageGaps: ['缺少场景词覆盖'],
      citationStructure: ['引用集中在官网'],
      competitorSignals: ['竞品在测试平台被提及'],
      findings: ['品牌在测试平台被稳定提及'],
      recommendations: [
        {
          id: '9001:recommendation:1',
          priority: 'P0',
          title: '补充场景内容',
          rationale: '围绕通勤场景发布可核验内容。',
        },
        {
          id: '9001:recommendation:2',
          priority: 'P2',
          title: '补齐长尾问题',
          rationale: '把长尾问题纳入下一轮监测。',
        },
      ],
      contentBlueprint: ['内容蓝图条目'],
      missingSamples: ['测试缺失样本'],
      methodVersion,
      ...synthesis,
    },
    methodVersion,
    interpretation: {
      status: methodVersion === 'geo-synthesis-v1' ? 'SUCCEEDED' : 'NOT_STARTED',
      canRetry: methodVersion === 'geo-deterministic-v1',
      attempts: 0,
      maxAttempts: 2,
      requestedAt: null,
      finishedAt: null,
      failureCode: null,
      retryAfter: null,
      unavailableReason: null,
    },
  }
}

export const geoRunDto: GeoRun = {
  id: '8001',
  taskId: '7001',
  topicId: geoTopicSnapshotFixture.id,
  status: 'PARTIAL',
  sampleMode: 'STANDARD',
  expectedUnits: 4,
  succeededUnits: 2,
  failedUnits: 1,
  missingUnits: 1,
  createdAt: '2026-09-20T10:00:00Z',
  finishedAt: '2026-09-20T10:31:00Z',
  spaceId: geoTopicSnapshotFixture.spaceId,
  productId: null,
  topic: { ...geoTopicSnapshotFixture },
  questions: geoQuestionSnapshotsFixture,
  platformCodes: ['DOUBAO'],
  competitors: [
    { id: '3001', name: '测试竞品', aliases: [], competitorType: 'DIRECT' },
  ],
  competitorSetVersion: 2,
  metricVersion: 'geo-observation-v1',
  methodVersion: 'geo-synthesis-v1',
  startedAt: '2026-09-20T10:00:00Z',
}

/** The synthesis Java never formed: every list empty, so nothing here can be read as a conclusion. */
export const emptyGeoSynthesisFixture: Partial<GeoReport['synthesis']> = {
  summary: '',
  coverageGaps: [],
  citationStructure: [],
  competitorSignals: [],
  findings: [],
  recommendations: [],
  contentBlueprint: [],
  missingSamples: [],
}

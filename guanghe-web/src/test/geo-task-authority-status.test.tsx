import { describe, expect, it } from 'vitest'
import type { PaidTask } from '../api/conversationApi'
import { toParallelTaskItem, toTaskExecutionOutcome } from '../features/chat'
import { geoReportDto } from './fixtures/geo'

/**
 * Every status Java can put on a paid task, with the label the GEO surfaces must show for it.
 *
 * Written out in full rather than sampled: the risk this guards against is a surface quietly
 * renaming one state into something the backend never said.
 */
const PAID_TASK_STATUS_LABELS: Record<PaidTask['status'], string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  RETRY_WAIT: '等待重试',
  SUCCEEDED: '已完成',
  PARTIAL: '部分完成',
  FAILED: '失败',
  SKIPPED: '已跳过',
  SYSTEM_TERMINATED: '系统终止',
}

/** Labels that belong to the chat Run machine. None of them may ever label a GEO task. */
const CHAT_RUN_ONLY_LABELS = [
  '等待中',
  '停止请求',
  '已停止',
  '已取消',
  '生成中',
  '思考中',
]

function geoTask(overrides: Partial<PaidTask> = {}): PaidTask {
  return {
    id: '7001',
    taskType: 'GEO',
    status: 'RUNNING',
    stageCode: 'GEO_SAMPLING',
    stageLabel: '正在采集平台回答',
    spaceId: '4001',
    productId: '4002',
    quotedPoints: 30,
    settledPoints: 0,
    frozenPoints: 30,
    progress: { percent: 50, completedUnits: 5, totalUnits: 10 },
    result: {
      runId: '8001',
      reportId: '9001',
      status: 'PARTIAL',
      expectedUnits: 4,
      succeededUnits: 2,
      failedUnits: 1,
      missingUnits: 1,
      settledPoints: 12,
      releasedPoints: 3,
    },
    errorCode: null,
    createdAt: '2026-09-20T10:00:00Z',
    updatedAt: '2026-09-20T10:05:00Z',
    ...overrides,
  }
}

describe('GEO 长任务的阶段、进度与更新时间', () => {
  it('阶段与进度直接取自 Java 的 stageLabel 与 progress', () => {
    const item = toParallelTaskItem(geoTask(), '测试产品')

    expect(item.statusLabel).toBe('执行中')
    expect(item.progressLabel).toBe('正在采集平台回答 · 已处理 5/10 个监测点')
    expect(item.progressPercent).toBe(50)
    expect(item.productName).toBe('测试产品')
    expect(item.detailEnabled).toBe(true)
  })

  it('没有进度时不编造分母，只显示阶段', () => {
    const item = toParallelTaskItem(geoTask({ progress: null }))

    expect(item.progressLabel).toBe('正在采集平台回答')
    expect(item.progressPercent).toBeUndefined()
  })

  it('更新时间来自 updatedAt，而不是创建时间或本地时钟', () => {
    const base = toParallelTaskItem(geoTask())
    const refreshed = toParallelTaskItem(
      geoTask({ updatedAt: '2026-09-21T18:45:00Z' }),
    )

    expect(base.timingLabel).toBeTruthy()
    expect(refreshed.timingLabel).toBeTruthy()
    expect(refreshed.timingLabel).not.toBe(base.timingLabel)

    // 同一份 updatedAt 必须给出同一份展示时间，刷新（重新适配）不得改变它。
    expect(toParallelTaskItem(geoTask()).timingLabel).toBe(base.timingLabel)
  })

  it('八个 PaidTask 状态逐一映射，且没有一个被换成聊天 Run 状态', () => {
    for (const [status, label] of Object.entries(PAID_TASK_STATUS_LABELS)) {
      const item = toParallelTaskItem(
        geoTask({ status: status as PaidTask['status'] }),
      )
      const outcome = toTaskExecutionOutcome(
        geoTask({ status: status as PaidTask['status'] }),
      )

      expect(item.statusLabel).toBe(label)
      // 同一条任务在两个界面上必须是同一个状态，不允许各说各话。
      expect(outcome.statusLabel).toBe(label)
      expect(CHAT_RUN_ONLY_LABELS).not.toContain(item.statusLabel)
      expect(CHAT_RUN_ONLY_LABELS).not.toContain(outcome.statusLabel)
    }
  })
})

describe('GEO 长任务失败与终态', () => {
  it('失败任务展示服务端原因，不显示空白结果区', () => {
    const outcome = toTaskExecutionOutcome(
      geoTask({ status: 'FAILED', errorCode: 'PROVIDER_TIMEOUT' }),
    )

    expect(outcome.statusLabel).toBe('失败')
    expect(outcome.failureReason).toBe(
      '执行服务暂时异常，本次任务未完成，请稍后重新发起。',
    )
  })

  it('系统终止任务展示系统保护原因', () => {
    const outcome = toTaskExecutionOutcome(
      geoTask({ status: 'SYSTEM_TERMINATED', errorCode: 'SYSTEM_TERMINATED' }),
    )

    expect(outcome.statusLabel).toBe('系统终止')
    expect(outcome.failureReason).toBe(
      '任务因系统保护机制中止，请重新发起；如重复发生，请联系支持。',
    )
  })

  it('终态仍保留本次已采集样本的结算口径', () => {
    const outcome = toTaskExecutionOutcome(
      geoTask({ status: 'PARTIAL', errorCode: null }),
    )

    expect(outcome.resultItems?.map((item) => item.label)).toEqual([
      '成功 2 个',
      '失败 1 个',
      '缺失 1 个',
    ])
    expect(outcome.resultItems?.[1].statusLabel).toBe('存在失败')
    expect(outcome.resultItems?.[2].statusLabel).toBe('存在缺失')
    // GEO 任务的摘要只说监测口径，不得出现媒体投稿或文章阶段的说法。
    expect(outcome.summary).toBe('计划 4 个采样单元，成功 2 个。')
  })
})

describe('GEO 诊断以服务端报告为准', () => {
  it('只有实际报告与任务报告一致时，才提供专题趋势和本次竞品入口', () => {
    const report = geoReportDto('geo-synthesis-v1')
    expect(toTaskExecutionOutcome(geoTask(), report).actions).toEqual(expect.arrayContaining([
      { type: 'VIEW_GEO_TREND', enabled: true },
      { type: 'VIEW_GEO_COMPETITORS', enabled: true },
    ]))
    for (const source of [undefined, { ...report, id: '9002' }]) {
      expect(toTaskExecutionOutcome(geoTask(), source).actions?.map(({ type }) => type))
        .not.toEqual(expect.arrayContaining(['VIEW_GEO_TREND', 'VIEW_GEO_COMPETITORS']))
    }
  })

  it('报告为 PARTIAL 或存在缺失样本时给出核验提示', () => {
    const outcome = toTaskExecutionOutcome(
      geoTask({ status: 'PARTIAL' }),
      geoReportDto('geo-synthesis-v1'),
    )

    expect(outcome.geoDiagnosis?.caveat).toBe(
      '以上判断仅基于本次已完成样本；存在缺失样本的部分需要继续核验。',
    )
    expect(outcome.geoDiagnosis?.recommendations).toEqual([
      { title: '补充场景内容', rationale: '围绕通勤场景发布可核验内容。' },
    ])
  })

  it('报告完整且无缺失样本时说明不代表市场份额', () => {
    const completeReport = {
      ...geoReportDto('geo-synthesis-v1', { missingSamples: [] }),
      status: 'SUCCEEDED' as const,
    }
    const outcome = toTaskExecutionOutcome(
      geoTask({ status: 'SUCCEEDED' }),
      completeReport,
    )

    expect(outcome.geoDiagnosis?.caveat).toBe(
      '以上判断基于本次 GEO 监测样本，不代表全网市场份额或平台保证。',
    )
    // 有覆盖缺口时诊断先给缺口，没有缺口才退回已验证事实——两者都只来自服务端报告。
    expect(outcome.geoDiagnosis?.findings).toEqual(['缺少场景词覆盖'])

    const withoutGaps = toTaskExecutionOutcome(
      geoTask({ status: 'SUCCEEDED' }),
      {
        ...geoReportDto('geo-synthesis-v1', { coverageGaps: [], missingSamples: [] }),
        status: 'SUCCEEDED' as const,
      },
    )
    expect(withoutGaps.geoDiagnosis?.findings).toEqual([
      '品牌在测试平台被稳定提及',
    ])
  })

  it('没有报告时不给诊断，而不是凭任务结果推断', () => {
    const outcome = toTaskExecutionOutcome(geoTask({ status: 'PARTIAL' }))

    expect(outcome.geoDiagnosis).toBeUndefined()
  })
})

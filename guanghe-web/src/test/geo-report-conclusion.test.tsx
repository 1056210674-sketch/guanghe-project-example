import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/apiError'
import type { GeoReport } from '../api/geoApi'
import { GeoReportDetail } from '../features/geo'
import {
  GEO_REPORT_UNAVAILABLE_MESSAGE,
  type GeoReportDetailView,
} from '../features/geo'
import {
  toGeoReportDetailView,
  toGeoReportLoadError,
  toGeoCollectionTrend,
} from '../pages/geo/viewAdapters'
import {
  emptyGeoSynthesisFixture,
  geoReportDto,
  geoRunDto,
} from './fixtures/geo'

const REPORT_ID = '9001'
const TRACE_ID = 'trace-report-conclusion'

function viewOf(
  methodVersion: GeoReport['methodVersion'],
  synthesis?: Partial<GeoReport['synthesis']>,
): GeoReportDetailView {
  return toGeoReportDetailView(
    geoReportDto(methodVersion, synthesis),
    geoRunDto,
    [],
  )
}

function renderReport(report: GeoReportDetailView, extra: Record<string, unknown> = {}) {
  return render(
    <GeoReportDetail loading={false} error={null} report={report} {...extra} />,
  )
}

describe('GEO 报告商家阅读结构', () => {
  it('智能总结、真实证据与建议分开呈现', () => {
    renderReport(viewOf('geo-synthesis-v1'))
    expect(screen.getByRole('region', { name: '监测结论' })).toBeInTheDocument()
    expect(screen.getByText('AI 综合摘要')).toBeInTheDocument()
    expect(screen.getByText('回答中的观察')).toBeInTheDocument()
    expect(screen.getByText('建议优先动作')).toBeInTheDocument()
  })

  it('无智能总结仅提醒一次，不将有效回答误报为证据不足', () => {
    renderReport(viewOf('geo-deterministic-v1'))
    expect(screen.getAllByText('已有回答可用于生成智能解读')).toHaveLength(1)
    expect(screen.queryByText('当前证据不足，暂不形成 AI 结论')).not.toBeInTheDocument()
    expect(screen.queryByText('AI 综合摘要')).not.toBeInTheDocument()
    expect(screen.queryByText('建议优先动作')).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '下一步建议' })).not.toBeInTheDocument()
    expect(screen.getByText('平台问题回答与引用证据')).toBeInTheDocument()
    expect(screen.getByText('计划回答：4')).toBeInTheDocument()
    expect(screen.getByText('有效回答：2')).toBeInTheDocument()
    expect(screen.getByText('采集失败：1')).toBeInTheDocument()
  })

  it('PARTIAL 不阻止展示已经形成的总结；未知缺失标识不会泄漏给商家', () => {
    const view = viewOf('geo-synthesis-v1')
    expect(view.status).toBe('PARTIAL')
    expect(view.missingSamples).toEqual(['有回答未取得，可在下方逐条查看平台结果。', '另有 1 条计划回答未返回，暂时无法判断是否提及。'])
    renderReport(view)
    expect(screen.getByText('AI 综合摘要')).toBeInTheDocument()
    expect(screen.queryByText('测试缺失样本')).not.toBeInTheDocument()
  })

  it('缺少 methodVersion 不自行推断证据不足', () => {
    renderReport({ ...viewOf('geo-synthesis-v1'), methodVersion: undefined })
    expect(screen.getByText('AI 综合摘要')).toBeInTheDocument()
    expect(screen.queryByText('当前证据不足，暂不形成 AI 结论')).not.toBeInTheDocument()
  })
})

describe('GEO 报告视图适配器', () => {
  it('采集趋势使用真实终态分母，保留失败任务并排除尚未完成任务', () => {
    const trend = toGeoCollectionTrend([
      { ...geoRunDto, id: 'failed', status: 'FAILED', expectedUnits: 10, succeededUnits: 0, finishedAt: '2026-09-24T02:00:00Z' },
      { ...geoRunDto, id: 'partial', status: 'PARTIAL', expectedUnits: 10, succeededUnits: 3, finishedAt: '2026-09-24T03:00:00Z' },
      { ...geoRunDto, id: 'running', status: 'RUNNING', finishedAt: null },
    ])
    expect(trend.map((point) => point.value)).toEqual([0, 30])
  })
  it('methodVersion 与样本范围取自服务端字段，不从文字或数组长度推断', () => {
    const richButDeterministic = viewOf('geo-deterministic-v1')
    expect(richButDeterministic.methodVersion).toBe('geo-deterministic-v1')
    expect(richButDeterministic.findings).toHaveLength(0)
    expect(richButDeterministic.suggestions).toHaveLength(0)
    expect(richButDeterministic.status).toBe('PARTIAL')

    const emptyButSynthesised = viewOf('geo-synthesis-v1', emptyGeoSynthesisFixture)
    expect(emptyButSynthesised.methodVersion).toBe('geo-synthesis-v1')
    expect(emptyButSynthesised.findings).toHaveLength(0)
    expect(emptyButSynthesised.suggestions).toHaveLength(0)

    expect(richButDeterministic.sampleScope).toEqual({
      planned: 4,
      succeeded: 2,
      failed: 1,
      missing: 1,
    })
  })

  it('建议项使用后端稳定 recommendation id，并原样交给调用方', () => {
    const view = viewOf('geo-synthesis-v1', { recommendations: [
      { id: `${REPORT_ID}:recommendation:1`, priority: 'P0', title: '补充场景内容', rationale: '围绕通勤场景发布可核验内容。' },
      { id: `${REPORT_ID}:recommendation:2`, priority: 'P2', title: '完善选购说明', rationale: '按已确认的商品事实整理选购说明。' },
    ] })
    expect(view.suggestions.map((item) => item.id)).toEqual([
      `${REPORT_ID}:recommendation:1`,
      `${REPORT_ID}:recommendation:2`,
    ])
    for (const suggestion of view.suggestions) {
      expect(suggestion.id).toMatch(
        /^[1-9][0-9]{0,18}:recommendation:[1-9][0-9]{0,2}$/,
      )
    }
    expect(view.suggestions.map((item) => item.priority)).toEqual([
      'HIGH',
      'LOW',
    ])

    const onPrepareArticleDraft = vi.fn()
    renderReport(view, { onPrepareArticleDraft })

    const buttons = screen.getAllByRole('button', { name: /准备文章草稿/ })
    expect(buttons).toHaveLength(2)
    fireEvent.click(buttons[1])

    expect(onPrepareArticleDraft).toHaveBeenCalledWith(
      REPORT_ID,
      `${REPORT_ID}:recommendation:2`,
    )
  })
})

describe('GEO 报告不可用统一口径', () => {
  it('403 与 404 收敛为同一句文案，不区分资源是否存在', () => {
    const forbidden = toGeoReportLoadError(
      new ApiError({
        status: 403,
        code: 'FORBIDDEN',
        message: '无权访问该监测报告',
        traceId: TRACE_ID,
      }),
    )
    const missing = toGeoReportLoadError(
      new ApiError({
        status: 404,
        code: 'NOT_FOUND',
        message: '监测报告不存在',
        traceId: TRACE_ID,
      }),
    )

    expect(forbidden).toEqual({
      message: GEO_REPORT_UNAVAILABLE_MESSAGE,
      traceId: TRACE_ID,
    })
    expect(missing).toEqual(forbidden)
  })

  it('其他失败保留服务端原因，不被折叠成不可用', () => {
    expect(
      toGeoReportLoadError(
        new ApiError({
          status: 500,
          code: 'INTERNAL_ERROR',
          message: '服务暂时不可用，请稍后重试。',
        }),
      )?.message,
    ).toBe('服务暂时不可用，请稍后重试。')
    expect(
      toGeoReportLoadError(new ApiError({ status: 409, code: 'CONFLICT', message: '状态已变化' }))
        ?.message,
    ).toBe('状态已变化')
    expect(toGeoReportLoadError(undefined)).toBeNull()
    expect(toGeoReportLoadError(new Error('private detail'))).toEqual({
      message: '请求失败，请稍后重试。',
    })
  })

  it('报告不可用与报告为空显示同一句文案并保留返回入口', () => {
    const onBack = vi.fn()
    const unavailable = toGeoReportLoadError(
      new ApiError({
        status: 404,
        code: 'NOT_FOUND',
        message: '监测报告不存在',
      }),
    )

    const { unmount } = render(
      <GeoReportDetail loading={false} error={unavailable} onBack={onBack} />,
    )
    expect(screen.getByText(GEO_REPORT_UNAVAILABLE_MESSAGE)).toBeInTheDocument()
    expect(screen.queryByText('监测报告不存在')).not.toBeInTheDocument()
    unmount()

    render(
      <GeoReportDetail loading={false} error={null} report={undefined} onBack={onBack} />,
    )
    expect(screen.getByText(GEO_REPORT_UNAVAILABLE_MESSAGE)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /返回报告库/ }))
    expect(onBack).toHaveBeenCalled()
  })
})

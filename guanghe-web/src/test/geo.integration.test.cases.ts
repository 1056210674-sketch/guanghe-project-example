import { describe, expect, it } from 'vitest'
import { formatGeoSchedule } from '../features/geo/presentation'
import * as geoPageDefaults from '../pages/geo/pageDefaults'
import { APP_PATHS, APP_ROUTE_PATTERNS, appLinks } from '../routes/paths'
import {
  geoPartialReportFixture,
  geoPlanFixtures,
  geoPlatformFixtures,
} from './fixtures/geo'

const geoProductionModules = import.meta.glob(
  '../{features,pages}/geo/**/*.{ts,tsx}',
  { eager: true, import: 'default', query: '?raw' },
) as Record<string, string>
const geoStyleModules = import.meta.glob('../styles/geo.css', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>
const routeModules = import.meta.glob('../routes/AppRoutes.tsx', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

const geoSource = Object.values(geoProductionModules).join('\n')
const geoStyles = Object.values(geoStyleModules).join('\n')
const routeSource = Object.values(routeModules).join('\n')

describe('GEO 展示契约', () => {
  it('GEO 运行与报告使用真实查询并保留 Loading、Empty、Error', () => {
    expect(
      Object.values(geoPageDefaults).every(
        (value) => Array.isArray(value) && value.length === 0,
      ),
    ).toBe(true)
    expect(geoSource).toContain('loading')
    expect(geoSource).toContain('error')
    expect(geoSource).toContain('还没有监测结果')
    expect(geoSource).toContain('useGeoRuns')
    expect(geoSource).toContain('useGeoReports')
    expect(geoSource).toContain('useGeoReport')
    expect(geoSource).toContain('useGeoPlatforms')
    expect(geoSource).not.toContain('测试部分完成报告')
  })

  it('GEO Foundation 页面复用真实Query且不暴露AI生成入口', () => {
    expect(geoSource).toContain('useGeoTopics')
    expect(geoSource).toContain('useCreateGeoTopic')
    expect(geoSource).toContain('useReplaceGeoKeywords')
    expect(geoSource).toContain('useReplaceGeoQuestions')
    expect(geoSource).toContain('useReplaceGeoCompetitors')
    // Explanatory text such as “不会自动生成问题” is not a generation control.
    expect(geoSource).not.toMatch(/(?:title|label|aria-label)=["'](?:AI 推荐|全网挖掘|生成问题)["']|>\s*(?:AI 推荐|全网挖掘|生成问题)\s*</)
    expect(geoSource).not.toMatch(/\b(?:onGenerateQuestions|useGenerateGeoQuestions|useMineGeoQuestions)\b/)
  })

  it('七个平台严格保留 Fixture 传入顺序且生产组件使用平台标识', () => {
    expect(geoPlatformFixtures.map((platform) => platform.name)).toEqual([
      '豆包',
      'DeepSeek',
      '通义千问',
      '文心一言',
      'Kimi',
      '腾讯元宝',
      '智谱',
    ])
    expect(geoSource).toContain('GeoPlatformBadge')
    expect(geoSource).toContain('platformLogos')
    expect(geoSource).not.toMatch(/platformPerformance\.(?:sort|toSorted)\s*\(/)
  })

  it('部分完成报告显式保留失败范围、失败原因与引用证据空态', () => {
    expect(geoPartialReportFixture.status).toBe('PARTIAL')
    expect(
      geoPartialReportFixture.samples.map((sample) => sample.executionStatus),
    ).toEqual(['SUCCEEDED', 'FAILED'])
    expect(geoPartialReportFixture.samples[1]?.failureReason).toBeTruthy()
    expect(geoPartialReportFixture.samples[0]?.citations).toEqual([])
    expect(geoSource).toContain('<GeoStatusTag status={report.status} />')
    expect(geoSource).toContain('此回答未附可核验链接')
    expect(geoSource).toContain('onPrepareRetrySample?.(selectedSample.id)')
  })

  it('自动计划仅允许正式频率，暂停和恢复只表达上层意图', () => {
    expect(formatGeoSchedule(geoPlanFixtures[0].schedule)).toBe('每天 09:00')
    expect(formatGeoSchedule(geoPlanFixtures[1].schedule)).toBe('每 3 天 10:00')
    expect(geoSource).toContain("'DAILY'")
    expect(geoSource).toContain("'WEEKLY'")
    expect(geoSource).toContain("'INTERVAL_DAYS'")
    expect(geoSource).toContain('onPausePlanIntent?.(plan.id)')
    expect(geoSource).toContain('onResumePlanIntent?.(plan.id)')
    expect(geoSource).not.toMatch(/\b(?:MONTHLY|CRON)\b/)
  })

  it('手动与快速监测只准备付费确认，不伪造已创建任务', () => {
    expect(geoSource).toContain('onPrepareConfirmation')
    expect(geoSource).toContain('准备付费确认')
    expect(geoSource).not.toMatch(/已扣除积分|唯一任务已创建/)
    expect(geoSource).not.toMatch(/settledPoints\s*[+\-*/]|returnedPoints\s*[+\-*/]/)
  })

  it('经营专题、问题库、竞品与报告区均为独立展示组件', () => {
    for (const componentName of [
      'GeoOverview',
      'GeoTopicManager',
      'GeoQuestionLibrary',
      'GeoManualMonitoring',
      'GeoPlanManager',
      'GeoCompetitorManager',
      'GeoReportLibrary',
      'GeoReportDetail',
    ]) {
      expect(geoSource).toContain(componentName)
    }
    expect(geoSource).toContain('competitorType')
    expect(geoSource).not.toMatch(/\bentityType\b/)
  })
})

describe('GEO 路由和生产边界', () => {
  it('GEO 路由集中定义并全部位于 USER 守卫内', () => {
    expect(APP_PATHS.geo).toBe('/app/geo')
    expect(APP_PATHS.geoTopics).toBe('/app/geo/topics')
    expect(APP_PATHS.geoQuestions).toBe('/app/geo/questions')
    expect(APP_PATHS.geoManualMonitoring).toBe('/app/geo/manual-monitoring')
    expect(APP_PATHS.geoDiagnostics).toBe('/app/geo/diagnostics')
    expect(APP_PATHS.geoPlans).toBe('/app/geo/plans')
    expect(APP_PATHS.geoCompetitors).toBe('/app/geo/competitors')
    expect(APP_PATHS.geoReports).toBe('/app/geo/reports')
    expect(APP_ROUTE_PATTERNS.geoReportDetail).toBe('/app/geo/reports/:reportId')
    expect(appLinks.geoReportDetail('report id')).toBe(
      '/app/geo/reports/report%20id',
    )

    const userRouteSection = routeSource
      .split('<Route element={<ProtectedRoute role="USER" />}>')[1]
      ?.split('<Route element={<ProtectedRoute role="ADMIN" />}>')[0]
    expect(userRouteSection).toBeDefined()
    for (const routeToken of [
      'APP_PATHS.geo',
      'APP_PATHS.geoTopics',
      'APP_PATHS.geoQuestions',
      'APP_PATHS.geoManualMonitoring',
      'APP_PATHS.geoDiagnostics',
      'APP_PATHS.geoPlans',
      'APP_PATHS.geoCompetitors',
      'APP_PATHS.geoReports',
      'APP_ROUTE_PATTERNS.geoReportDetail',
    ]) {
      expect(userRouteSection).toContain(`path={${routeToken}}`)
    }
  })

  it('生产代码不导入 Fixture、不直连网络，冷却计时仅允许单次只读刷新', () => {
    expect(Object.keys(geoProductionModules).length).toBeGreaterThan(0)
    for (const [path, source] of Object.entries(geoProductionModules)) {
      expect(source, path).not.toMatch(
        /(?:from|import\s*)[\s(']*['"][^'"]*(?:\/test\/|fixtures?)/,
      )
      expect(source, path).not.toMatch(
        /\b(?:fetch|axios|EventSource|WebSocket)\b/,
      )
      if (path.endsWith('/geoQueries.ts')) {
        // Report-capacity cooldown is server supplied. It rereads eligibility once;
        // it does not start/retry a paid task or perform an automatic POST.
        expect(source.match(/setTimeout\s*\(/g), path).toHaveLength(1)
        expect(source, path).not.toMatch(/setInterval\s*\(/)
        const cooldownCallback = source.split('const timer = window.setTimeout(() => {')[1]?.split('},')[0]
        expect(cooldownCallback).toBeDefined()
        expect(cooldownCallback).toContain('refreshedCooldown.current = cooldownKey')
        expect(cooldownCallback).toContain('void refetch()')
        expect(cooldownCallback).not.toMatch(/mutate|POST|reinterpretGeoReport|confirmTask|createTask/)
        expect(source).toContain('return () => window.clearTimeout(timer)')
      } else {
        expect(source, path).not.toMatch(/set(?:Timeout|Interval)\s*\(/)
      }
      expect(source, path).not.toMatch(/\/api\/v1\/|\/internal\/v1\/|:8000/)
      expect(source, path).not.toMatch(/取消任务|终止任务|撤销任务/)
      const nonTimerSource = path.endsWith('/geoQueries.ts')
        ? source.replace(/window\.(?:setTimeout|clearTimeout)/g, 'controlledTimer')
        : source
      expect(nonTimerSource, path).not.toMatch(/window\.|location\.|URL\.createObjectURL/)
      expect(source, path).not.toMatch(
        /<a\s|\bhref=|\bdownload=|navigator\.clipboard/,
      )
      expect(source, path).not.toMatch(/采购原价|采购成本|利润|35%/)
    }
  })

  it('1280px 与 1440px 布局有收缩规则且关键区域允许换列', () => {
    expect(geoStyles).toContain('min-width: 0')
    expect(geoStyles).toMatch(/@media\s*\(max-width:\s*1500px\)/)
    expect(geoStyles).toMatch(/@media\s*\(max-width:\s*1320px\)/)
    expect(geoStyles).toMatch(/grid-template-columns/)
  })
})

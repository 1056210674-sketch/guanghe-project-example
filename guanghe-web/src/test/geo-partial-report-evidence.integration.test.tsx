import { fireEvent, render } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, describe, expect, it } from 'vitest'
import { GeoReportDetail } from '../features/geo/components/GeoReportDetail'
import { toGeoReportDetailView } from '../pages/geo/viewAdapters'
import { geoPlatformFixtures } from './fixtures/geo'
import { partialRateLimitedReport, partialRateLimitedRun } from './fixtures/geoPartialRateLimited'

beforeEach(async () => { await import('../features/chat/components/MarkdownMessageContent') })

describe('部分 GEO 报告的成功回答与限流条目（真实适配器和报告组件）', () => {
  it('十条中两条成功八条限流，两条成功回答可逐条查看，切换失败条目后仍能返回成功回答', () => {
    const view = toGeoReportDetailView(partialRateLimitedReport, partialRateLimitedRun, geoPlatformFixtures)
    function Evidence() {
      const [sampleId, setSampleId] = useState<string>()
      return <GeoReportDetail loading={false} error={null} report={view}
        selectedSampleId={sampleId} onSelectSample={setSampleId} />
    }
    const { container } = render(<Evidence />)
    const questions = Array.from(container.querySelectorAll<HTMLButtonElement>('.geo-question-link'))
    expect(questions).toHaveLength(10)
    expect(questions.map((button) => button.textContent)).toEqual(
      Array.from({ length: 10 }, (_, index) => `并发监测问题 ${index + 1}`),
    )
    expect(document.querySelector('.geo-answer-card')).toHaveTextContent('第 1 条已保存的成功回答。')
    fireEvent.click(questions[1])
    expect(document.querySelector('.geo-answer-card')).toHaveTextContent('第 2 条已保存的成功回答。')
    fireEvent.click(questions[2])
    expect(container.querySelector('.geo-sample-detail')).toHaveTextContent('上游接口触发限流，本条未取得回答。')
    expect(document.querySelector('.geo-answer-card')).toBeNull()
    fireEvent.click(questions[0])
    expect(document.querySelector('.geo-answer-card')).toHaveTextContent('第 1 条已保存的成功回答。')
  })
})

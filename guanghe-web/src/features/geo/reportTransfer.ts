import type {
  GeoAnswerSampleView,
  GeoReportDetailView,
  GeoSampleAnalysisStatus,
  GeoSampleExecutionStatus,
  GeoSuggestionPriority,
} from './types'

const reportStatusLabels: Record<GeoReportDetailView['status'], string> = {
  SUCCEEDED: '已完成',
  PARTIAL: '部分完成',
  FAILED: '失败',
}

const executionStatusLabels: Record<GeoSampleExecutionStatus, string> = {
  PENDING: '等待采集',
  RUNNING: '采集中',
  RETRY_WAIT: '等待重试',
  SUCCEEDED: '采集成功',
  FAILED: '采集失败',
}

const analysisStatusLabels: Record<GeoSampleAnalysisStatus, string> = {
  PENDING: '等待分析',
  MENTIONED: '已提及',
  NOT_MENTIONED: '未提及',
}

function analysisStatusLabel(status: GeoSampleAnalysisStatus | undefined): string {
  return status
    ? analysisStatusLabels[status]
    : '未评估（当前回答没有可核验的目标提及结果）'
}

const priorityLabels: Record<GeoSuggestionPriority, string> = {
  HIGH: '高优先级',
  MEDIUM: '中优先级',
  LOW: '低优先级',
}

function present(value: string | number | undefined): string {
  return value === undefined || value === '' ? '—' : String(value)
}

function singleLine(value: string | number | undefined): string {
  return present(value).replace(/\s+/g, ' ').trim()
}

function tableCell(value: string | number | undefined): string {
  return singleLine(value).replace(/\\/g, '\\\\').replace(/\|/g, '\\|')
}

function listSection(
  title: string,
  items: readonly string[] | undefined,
  emptyText = '暂无内容。',
): string[] {
  return [
    `## ${title}`,
    '',
    ...(items?.length ? items.map((item) => `- ${singleLine(item)}`) : [emptyText]),
    '',
  ]
}

function paragraphBlock(value: string | undefined, emptyText: string): string[] {
  const content = value?.trim()
  if (!content) return [emptyText]
  return content.split(/\r?\n/).map((line) => `> ${line || ' '}`)
}

function sampleMarkdown(sample: GeoAnswerSampleView, index: number): string[] {
  const lines = [
    `### ${index + 1}. ${singleLine(sample.question)}`,
    '',
    `- 平台：${singleLine(sample.platform.name)}`,
    `- 采集状态：${executionStatusLabels[sample.executionStatus]}`,
    `- 目标提及：${sample.executionStatus === 'SUCCEEDED' ? analysisStatusLabel(sample.analysisStatus) : '未取得回答，不能判断是否提及'}`,
    `- 采样轮次：${present(sample.sampleRound)}`,
    `- 开始时间：${present(sample.startedAt)}`,
    `- 生成时间：${present(sample.sampledAt)}`,
    `- 执行提供方：${present(sample.provider)}`,
    `- 模型：${present(sample.model)}`,
    `- 执行耗时：${sample.latencyMs === undefined ? '—' : `${sample.latencyMs} ms`}`,
    `- 上游调用次数：${present(sample.providerCallCount)}`,
    `- 推荐位置：${present(sample.recommendationPosition)}`,
    `- 情感：${present(sample.sentimentLabel)}`,
  ]

  if (sample.usage) {
    const cost = sample.usage.monetaryCost
      ? `${sample.usage.monetaryCost}${sample.usage.currency ? ` ${sample.usage.currency}` : ''}`
      : '—'
    lines.push(
      `- 用量：输入 ${sample.usage.inputTokens} tokens / 输出 ${sample.usage.outputTokens} tokens / 搜索 ${sample.usage.searchRequests} 次`,
      `- 上游成本：${cost}`,
    )
  }

  if (sample.failureReason) {
    lines.push(`- 失败原因：${singleLine(sample.failureReason)}`)
  }

  lines.push('', '#### 原始回答', '')
  lines.push(
    ...paragraphBlock(sample.originalAnswer, '本样本没有可展示的原始回答。'),
  )
  lines.push('', `#### 引用来源（${sample.citations.length}）`, '')

  if (sample.citations.length === 0) {
    lines.push('本样本没有返回可核验的引用来源。', '')
    return lines
  }

  sample.citations.forEach((citation, citationIndex) => {
    lines.push(
      `${citationIndex + 1}. ${singleLine(citation.title)}`,
      `   - URL：${singleLine(citation.url)}`,
      `   - 站点：${present(citation.site)}`,
      `   - 抓取时间：${present(citation.capturedAt)}`,
    )
    if (citation.excerpt) {
      lines.push(`   - 摘录：${singleLine(citation.excerpt)}`)
    }
  })
  lines.push('')
  return lines
}

export function buildGeoReportMarkdown(report: GeoReportDetailView): string {
  const incompleteAnalysis = report.methodVersion === 'geo-deterministic-v1'
  const interpretationStatus = report.interpretation?.status
  const interpretationLabel = interpretationStatus === 'QUEUED' ? '智能解读已排队'
    : interpretationStatus === 'RUNNING' ? '智能解读生成中'
      : interpretationStatus === 'FAILED' ? '智能解读未完成，可在报告页面查看恢复状态'
        : '尚未形成智能解读，可在报告页面生成解读'
  // Exports must enforce the same evidence-only boundary as the report UI,
  // even when called without the page adapter (for example from cached data).
  if (incompleteAnalysis) {
    report = { ...report, suggestions: [], findings: [], coverageGaps: [],
      competitorSignals: [], contentBlueprint: [], summary: `${interpretationLabel}；已取得的回答与目标提及结果仍可查看。` }
  }
  const lines = [
    `# ${singleLine(report.name)}`,
    '',
    '> 本文件由光禾AI智能体框架根据该报告已保存的监测样本生成；指标与结论仅代表报告标注的监测时间、平台范围和样本范围。',
    '',
    '## 基本信息',
    '',
    `- 报告 ID：${report.id}`,
    `- 监测对象：${singleLine(report.objectName)}`,
    `- 报告状态：${reportStatusLabels[report.status]}`,
    `- 分析状态：${incompleteAnalysis ? interpretationLabel : '已完成 AI 分析'}`,
    `- 监测时间：${present(report.monitoredAt)}`,
    `- 样本量：${present(report.sampleCount)}`,
    `- 采样轮次：${present(report.sampleRounds)}`,
    `- 平台范围：${present(report.platformCount)}`,
    `- 监测模式：${present(report.modeLabel)}`,
    `- 竞品集合：${present(report.competitorSetVersion)}`,
    '',
    '## 商品 AI 可见度',
    '',
    report.factualSummary ?? '',
    '',
    report.visibilityNotice ?? '当前报告没有提供可核验的目标提及指标，暂不评估可见度。',
    '',
    '名称提及率以有效回答为分母；失败和缺失回答单独保留，不作为未提及。字面提及不等于正面推荐。',
    '',
  ]

  if (report.metrics.length === 0) {
    lines.push('暂无核心指标。', '')
  } else {
    lines.push('| 指标 | 数值 | 对比 | 说明 |', '| --- | --- | --- | --- |')
    report.metrics.forEach((metric) => {
      lines.push(
        `| ${tableCell(metric.label)} | ${tableCell(metric.value)} | ${tableCell(metric.comparison)} | ${tableCell(metric.description)} |`,
      )
    })
    lines.push('')
  }

  if (report.trend.length > 0) {
    lines.push('## 可见度趋势', '')
    lines.push('| 周期 | 当前值 | 对照值 |', '| --- | ---: | ---: |')
    report.trend.forEach((point) => {
      lines.push(
        `| ${tableCell(point.label)} | ${point.value} | ${present(point.comparisonValue)} |`,
      )
    })
    lines.push('')
  }

  lines.push('## 平台表现', '')
  if (report.platformPerformance.length === 0) {
    lines.push('暂无平台汇总。', '')
  } else {
    lines.push(
      '| 平台 | AI 可见度 | 提及 / 有效回答 | 状态 |',
      '| --- | --- | --- | --- |',
    )
    report.platformPerformance.forEach((item) => {
      lines.push(
        `| ${tableCell(item.platform.name)} | ${tableCell(item.primaryValue)} | ${tableCell(item.secondaryValue)} | ${tableCell(item.statusLabel ?? item.change)} |`,
      )
    })
    lines.push('')
  }

  if (!incompleteAnalysis) {
    lines.push('## 可执行优化建议', '')
    if (report.suggestions.length === 0) {
      lines.push('尚无证据充分的内容创作建议。可先核对公开商品名称与别名、完善商品事实，再确认后续问题与监测报价；历史报告保持不变。', '')
    } else {
      report.suggestions.forEach((suggestion, index) => {
        lines.push(
          `### ${index + 1}. [${priorityLabels[suggestion.priority]}] ${singleLine(suggestion.title)}`,
          '',
          suggestion.description?.trim() || '暂无补充说明。',
          '',
        )
      })
    }

    lines.push(
      ...listSection('回答中的观察', report.findings),
      ...listSection('采样与证据限制', report.coverageGaps, '本次没有额外的采样或证据限制说明。'),
      ...listSection('竞品提及信号（非市场份额）', report.competitorSignals, '本次未识别到竞品提及信号。'),
      ...listSection('内容优化蓝图', report.contentBlueprint),
    )
  }
  lines.push(
    ...listSection('引用结构', report.citationStructure),
    ...listSection('未取得的回答', report.missingSamples, '没有未取得的回答。'),
    '## 平台问题回答与引用证据',
    '',
  )

  if (report.samples.length === 0) {
    lines.push('暂无回答样本。', '')
  } else {
    report.samples.forEach((sample, index) => {
      lines.push(...sampleMarkdown(sample, index))
    })
  }

  lines.push('## 报告摘要', '')
  lines.push(...paragraphBlock(report.summary, '暂无报告摘要。'), '')
  lines.push('## 方法说明', '')
  lines.push(
    ...paragraphBlock(
      report.methodologyNotice,
      '名称提及率以有效回答为分母。失败和缺失回答单独保留，不计作未提及；字面提及不代表推荐或排名。',
    ),
    '',
  )

  return `${lines.join('\n').trim()}\n`
}

export function buildGeoEvidenceSummary(
  report: GeoReportDetailView,
  sample: GeoAnswerSampleView,
): string {
  const lines = [
    'GEO 证据摘要',
    `报告：${singleLine(report.name)}`,
    `监测对象：${singleLine(report.objectName)}`,
    `监测时间：${present(report.monitoredAt)}`,
    `问题：${singleLine(sample.question)}`,
    `平台：${singleLine(sample.platform.name)}`,
    `执行提供方：${present(sample.provider)}`,
    `模型：${present(sample.model)}`,
    `生成时间：${present(sample.sampledAt)}`,
    `采集状态：${executionStatusLabels[sample.executionStatus]}`,
    `目标提及：${sample.executionStatus === 'SUCCEEDED' ? analysisStatusLabel(sample.analysisStatus) : '未取得回答，不能判断是否提及'}`,
  ]

  if (sample.failureReason) {
    lines.push(`失败原因：${singleLine(sample.failureReason)}`)
  }

  lines.push('', '原始回答：', sample.originalAnswer?.trim() || '暂无可展示的原始回答。')
  lines.push('', `引用来源（${sample.citations.length}）：`)

  if (sample.citations.length === 0) {
    lines.push('暂无可核验的引用来源。')
  } else {
    sample.citations.forEach((citation, index) => {
      lines.push(
        `${index + 1}. ${singleLine(citation.title)}`,
        `   URL：${singleLine(citation.url)}`,
        `   站点：${present(citation.site)}`,
        `   抓取时间：${present(citation.capturedAt)}`,
      )
      if (citation.excerpt) {
        lines.push(`   摘录：${singleLine(citation.excerpt)}`)
      }
    })
  }

  lines.push('', '说明：以上内容仅代表本报告的监测时间、平台范围和样本范围。')
  return `${lines.join('\n').trim()}\n`
}

export function geoReportDownloadFilename(report: GeoReportDetailView): string {
  const raw = `${report.objectName}-GEO报告-${report.monitoredAt ?? report.id}`
  const printable = [...raw]
    .map((character) => (character.charCodeAt(0) < 32 ? '-' : character))
    .join('')
  const safe = printable
    .replace(/[<>:"/\\|?*]/g, '-')
    .replace(/\s+/g, ' ')
    .replace(/[. ]+$/g, '')
    .trim()
    .slice(0, 120)
  return `${safe || 'GEO报告'}.md`
}

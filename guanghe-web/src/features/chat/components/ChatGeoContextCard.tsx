import { Button, Input, Space, Typography } from 'antd'
import type { ConversationMessageGeoContext } from '../../../api/conversationApi'
import { formatChatDateTime } from '../live/chatAdapters'

export interface ChatGeoContextCardProps {
  context: ConversationMessageGeoContext
  preparingArticle?: boolean
  direction?: string
  onDirectionChange?: (value: string) => void
  onViewReport: () => void
  onPrepareArticle: () => void
  onSelectTopic: (topicId: string) => void
  onOpenTopics: () => void
}

const unavailableLabels = {
  REPORT_NOT_TERMINAL: '监测尚未完成，暂不能据此写文章。',
  REPORT_ARCHIVED: '这份报告已归档，不能创建文章草稿。',
  REPORT_ANALYSIS_UNAVAILABLE: '这份报告尚无可用于创作的有效解读，请先查看报告。',
} as const

export function ChatGeoContextCard({ context, preparingArticle, direction = '', onDirectionChange, onViewReport, onPrepareArticle, onSelectTopic, onOpenTopics }: ChatGeoContextCardProps) {
  const report = context.status === 'READY' ? context.report : null
  const sourceUnavailable = Boolean(report?.observationId && report.available !== true)
  const cannotPrepare = !report?.canCreateArticle || Boolean(report.articleUnavailableReason) || sourceUnavailable
  return <section className="chat-geo-context" aria-label="本条回复的 GEO 依据与后续操作">
    {report ? <>
      <Typography.Text strong>本条回答依据：{report.title}</Typography.Text>
      {report.generatedAt ? <Typography.Text type="secondary">报告时间：{formatChatDateTime(report.generatedAt)}</Typography.Text> : null}
      <Typography.Paragraph type="secondary">可补充想写给谁、重点讲什么。引用报告后发送写作需求，系统会准备完整文章，投稿前由你确认。</Typography.Paragraph>
      <label className="chat-geo-context__direction">文章方向（选填）
        <Input.TextArea aria-label="文章方向" value={direction} maxLength={800} rows={3} showCount
          placeholder="例如：写给第一次购买的顾客，重点介绍适用场景和选购方法"
          disabled={cannotPrepare || preparingArticle} onChange={(event) => onDirectionChange?.(event.target.value)} />
      </label>
      <Space wrap>
        <Button onClick={onViewReport}>查看报告</Button>
        <Button loading={preparingArticle} disabled={cannotPrepare || preparingArticle} onClick={(event) => { if (event.detail <= 1) onPrepareArticle() }}>引用报告写文章</Button>
      </Space>
      {cannotPrepare ? <Typography.Paragraph type="secondary">{sourceUnavailable ? '这份报告的创作来源暂不可用，请重新读取报告后继续。' : report.articleUnavailableReason ? unavailableLabels[report.articleUnavailableReason] : '这份报告暂不具备文章创作条件，请先查看报告。'}</Typography.Paragraph> : null}
    </> : <>
      <Typography.Text strong>{context.status === 'NEEDS_TOPIC' ? '先选择要分析的专题' : context.status === 'NEEDS_SCOPE' ? '请先选择本次商品资料' : context.status === 'NO_REPORT' ? '当前范围尚无可用报告' : '报告暂时不可读取'}</Typography.Text>
      <Typography.Paragraph type="secondary">{context.status === 'NEEDS_TOPIC'
        ? '打开专题后选择报告，再点击“带这份报告问 AI”；不会自动为你选择或监测。'
        : context.status === 'NO_REPORT'
          ? context.topicChoices.length > 0
            ? '已有专题尚无报告，打开该专题配置监测；不会重复新建专题。问题、平台和报价需由你核对并确认后才执行。'
            : '可以请 AI 先拟监测问题并推荐可用平台。方案与报价可修改，只有点击“确认开始”才执行。'
          : context.status === 'NEEDS_SCOPE'
            ? '在“本次资料与任务”选择经营空间和商品后，再提出分析需求。'
            : '这不代表没有报告。请先到 GEO 页面查看状态，不会因此自动启动新监测。'}</Typography.Paragraph>
      <Space wrap>
        {context.topicChoices.map((topic) => <Button key={topic.topicId} onClick={() => onSelectTopic(topic.topicId)}>{context.status === 'NO_REPORT' ? '配置监测：' : '打开专题：'}{topic.name}</Button>)}
        <Button onClick={onOpenTopics}>{context.topicsTruncated ? '查看全部专题' : '前往 GEO 查看'}</Button>
      </Space>
    </>}
  </section>
}

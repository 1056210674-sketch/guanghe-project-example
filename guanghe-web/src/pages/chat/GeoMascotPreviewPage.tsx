import { Button, Space, Tag } from 'antd'
import { useState } from 'react'
import { ChatWorkspace } from '../../features/chat/components/ChatWorkspace'
import { GeoMascot } from '../../features/chat/mascot/GeoMascot'
import type { ChatMessageView } from '../../features/chat/types'
import '../../styles/agent-platform.css'

const noop = () => undefined
// DEV-only interaction controls. No fixture, fake progress, or automatic timer in production.
export function GeoMascotPreviewPage() {
  const [messages, setMessages] = useState<ChatMessageView[]>([])
  const [busy, setBusy] = useState(false)
  const [scope, setScope] = useState(0)
  const [geo, setGeo] = useState(true)
  const [welcome, setWelcome] = useState(true)
  const [split, setSplit] = useState(false)
  const [draft, setDraft] = useState('')
  const [run, setRun] = useState(0)
  const reply = (status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'STOPPED', text = '', streaming = false): ChatMessageView => ({
    id: `reply-${run}`, role: 'ASSISTANT', content: text, outputStreaming: streaming,
    executionTimeline: { id: `timeline-${run}`, messageId: `reply-${run}`, runId: `run-${run}`,
      startedAt: new Date(Date.now() - 4000).toISOString(), updatedAt: new Date().toISOString(), elapsedMs: 4000, status, steps: [],
      ...(status === 'COMPLETED' || status === 'FAILED' ? { businessOutcome: { schemaVersion: 1 as const, status: status === 'FAILED' ? 'FAILED' as const : 'ANSWERED' as const } } : {}) },
  })
  function start() { setWelcome(false); setBusy(true); setMessages([reply('RUNNING')]) }
  function finish(status: 'COMPLETED' | 'FAILED' | 'STOPPED') { setBusy(false); setMessages([reply(status, status === 'COMPLETED' ? '先明确品牌的目标地区和客户需求，再制定优化方案。本次没有启动监测或投稿。' : '已保留内容，请查看任务状态。')]) }
  function article(status: 'running' | 'succeeded' | 'failed' | 'loading') {
    setWelcome(false); setBusy(false)
    setMessages([{ id: 'article-message', role: 'ASSISTANT', content: '', articlePreparation: {
      id: 'preview-article', status, observedAt: Date.now(), loading: status === 'running' || status === 'loading',
      tone: status === 'succeeded' ? 'success' : status === 'failed' ? 'danger' : 'info',
      statusLabel: status === 'succeeded' ? '完整文章已准备' : '文章准备', description: '开发预览，仅演示真实状态映射。', stages: [], openLabel: '查看文章',
    } }])
  }
  return <div className="platform-shell" style={{ padding: 16, height: '100dvh', display: 'flex', flexDirection: 'column', gap: 12 }}>
    <Space wrap><Tag>仅本地交互验收 · 不发业务请求</Tag>
      <Button onClick={start}>处理请求</Button>
      <Button onClick={() => { setWelcome(false); setBusy(true); setMessages([{ id: 'user-waiting', role: 'USER', content: '帮我想一想怎么做品牌优化。' }]) }}>等待受理</Button>
      <Button onClick={() => { setBusy(true); setMessages([reply('RUNNING', '正在生成一段真实状态示例…', true)]) }}>正文输出</Button>
      <Button onClick={() => finish('COMPLETED')}>回复完成</Button>
      <Button onClick={() => finish('FAILED')}>回复失败</Button>
      <Button onClick={() => finish('STOPPED')}>停止</Button>
      <Button onClick={() => article('running')}>文章生成中</Button>
      <Button onClick={() => article('succeeded')}>文章成功</Button>
      <Button onClick={() => { setScope((value) => value + 1); setBusy(false); setWelcome(false); setMessages([reply('COMPLETED', '历史回复，不应该重新播放完成动画。')]) }}>历史聊天</Button>
      <Button onClick={() => { setWelcome(true); setBusy(false); setMessages([]); setScope((value) => value + 1); setRun((value) => value + 1) }}>新聊天</Button>
      <Button onClick={() => setGeo(!geo)}>切换智能体</Button>
      <Button onClick={() => { setSplit(!split); setWelcome(false) }}>右侧文章画布</Button>
    </Space>
    <div className={`chat-page${split ? ' chat-page--with-article' : ''}`} style={{ flex: 1, minHeight: 0, height: 'auto', display: 'flex', gap: 16 }}>
      <div style={{ minWidth: 0, flex: 1 }}><GeoMascot key={scope} messages={messages} busy={busy} enabled={geo}><ChatWorkspace title={welcome ? '今天想完成什么？' : '小行星咖啡店的 GEO 优化'} navigationInShell platformHome={welcome}
        identityControls={<div className="platform-task-identity"><Tag>{geo ? 'GEO 智能体' : '创作智能体'}</Tag><span>项目：小行星咖啡店</span></div>}
        sessions={[]} messages={messages} recommendations={[]} taskFocusOptions={[]} taskStatusSummaries={[]}
        sessionSearchValue="" draft={draft} isGenerating={busy} sendEnabled={!busy}
        onNewConversation={noop} onSessionSearchChange={noop} onSelectSession={noop} onSelectTaskFocus={noop}
        onRecommendationSelect={noop} onDraftChange={setDraft} onSend={start} onStop={() => finish('STOPPED')}
        onSelectClarificationOption={noop} onEditPaidTaskConfirmation={noop} onConfirmPaidTaskConfirmation={noop}
        onOpenRecharge={noop} onPrepareReestimate={noop} onOpenPaidTaskReferenceAsset={noop} onAddPaidTaskReferenceAsset={noop}
        onViewTask={noop} onPreviewArticle={noop} onContinueEditingArticle={noop} onSaveArticleToAssets={noop}
        onPreviewGeneratedImage={noop} onDownloadGeneratedImage={noop} onSaveGeneratedImageToAssets={noop}
        onSelectGeneratedImage={noop} onTaskOutcomeAction={noop} onToggleHistoricalContext={noop} onOpenReferenceAsset={noop}
      /></GeoMascot></div>
      {split ? <article style={{ flex: 1, padding: 32, border: '1px solid #eee', borderRadius: 16, overflow: 'auto' }}><h1>文章画布</h1><h2>小行星咖啡店</h2><p>形象留在聊天栏内，不遮挡正文、编辑或投稿入口。</p></article> : null}
    </div>
  </div>
}

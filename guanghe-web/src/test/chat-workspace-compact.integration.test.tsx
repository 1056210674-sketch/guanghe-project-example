import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ChatWorkspace, type ChatWorkspaceProps } from '../features/chat/components/ChatWorkspace'
import { chatMessageFixture, chatTaskStatusSummariesFixture } from './fixtures/chat'

function props(overrides: Partial<ChatWorkspaceProps> = {}): ChatWorkspaceProps {
  return {
    title: '客户项目的长聊天标题', navigationInShell: true, selectedSessionId: 'chat-1',
    sessions: [], messages: [chatMessageFixture], recommendations: [], taskFocusOptions: [], taskStatusSummaries: [],
    sessionSearchValue: '', draft: '', isGenerating: false, sendEnabled: true,
    onNewConversation: vi.fn(), onSessionSearchChange: vi.fn(), onSelectSession: vi.fn(),
    onSelectTaskFocus: vi.fn(), onRecommendationSelect: vi.fn(), onDraftChange: vi.fn(), onSend: vi.fn(), onStop: vi.fn(),
    onSelectClarificationOption: vi.fn(), onEditPaidTaskConfirmation: vi.fn(), onConfirmPaidTaskConfirmation: vi.fn(),
    onOpenRecharge: vi.fn(), onPrepareReestimate: vi.fn(), onOpenPaidTaskReferenceAsset: vi.fn(), onAddPaidTaskReferenceAsset: vi.fn(),
    onViewTask: vi.fn(), onPreviewArticle: vi.fn(), onContinueEditingArticle: vi.fn(), onSaveArticleToAssets: vi.fn(),
    onPreviewGeneratedImage: vi.fn(), onDownloadGeneratedImage: vi.fn(), onSaveGeneratedImageToAssets: vi.fn(), onSelectGeneratedImage: vi.fn(),
    onTaskOutcomeAction: vi.fn(), onToggleHistoricalContext: vi.fn(), onOpenReferenceAsset: vi.fn(),
    ...overrides,
  }
}
const view = (value: ChatWorkspaceProps) => <ConfigProvider theme={{ token: { motion: false } }}><ChatWorkspace {...value} /></ConfigProvider>
afterEach(() => vi.unstubAllGlobals())

describe('紧凑聊天布局', () => {
  it('已有聊天去掉整块顶部栏，消息、输入和工作区名称保留', () => {
    const { container } = render(view(props({ identityControls: <span>GEO 智能体 · 项目状态说明</span> })))
    expect(container.querySelector('.chat-conversation__header')).toBeNull()
    expect(screen.queryByText('GEO 智能体 · 项目状态说明')).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: '客户项目的长聊天标题' })).toBeInTheDocument()
    expect(screen.getByText(chatMessageFixture.content!)).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '消息输入' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '监测一次' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '写文章' })).not.toBeInTheDocument()
  })

  it('新聊天仍可选择项目和智能体，不会误删首次配置入口', () => {
    render(view(props({ platformHome: true, selectedSessionId: undefined, messages: [], identityControls: <button>选择智能体</button> })))
    expect(screen.getByRole('heading', { name: '客户项目的长聊天标题' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '选择智能体' })).toBeInTheDocument()
  })

  it('离开底部只显示无文字下箭头，点击回到底部，切换聊天自动复位', () => {
    const initial = props()
    const { container, rerender } = render(view(initial))
    const scroll = container.querySelector('.chat-conversation__content') as HTMLDivElement
    Object.defineProperties(scroll, { scrollHeight: { configurable: true, value: 1600 }, clientHeight: { configurable: true, value: 500 } })
    scroll.scrollTop = 100
    fireEvent.scroll(scroll)
    const arrow = screen.getByRole('button', { name: '回到最新消息' })
    expect(arrow.textContent).toBe('')
    expect(arrow.closest('.chat-conversation__viewport')).not.toBeNull()
    fireEvent.click(arrow)
    expect(scroll.scrollTop).toBe(1600)
    expect(screen.queryByRole('button', { name: '回到最新消息' })).not.toBeInTheDocument()
    scroll.scrollTop = 100
    fireEvent.scroll(scroll)
    expect(screen.getByRole('button', { name: '回到最新消息' })).toBeInTheDocument()
    rerender(view({ ...initial, selectedSessionId: 'chat-2' }))
    expect(screen.queryByRole('button', { name: '回到最新消息' })).not.toBeInTheDocument()
    expect(scroll.scrollTop).toBe(1600)
  })

  it('任务进度从加号进入，恢复操作保留在面板且不会自动执行', async () => {
    const retry = vi.fn()
    render(view(props({ taskStatusSummaries: chatTaskStatusSummariesFixture, taskActions: <button onClick={retry}>恢复已有任务</button> })))
    expect(screen.queryByRole('button', { name: '任务进度' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '恢复已有任务' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '添加资料' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: '任务进度' }))
    const drawer = await screen.findByRole('dialog', { name: '任务进度' })
    expect(within(drawer).getByText('测试 GEO 监测')).toBeInTheDocument()
    expect(retry).not.toHaveBeenCalled()
    await userEvent.click(within(drawer).getByRole('button', { name: '恢复已有任务' }))
    expect(retry).toHaveBeenCalledOnce()
  })

  it('输入框增高时仅在原本跟随底部的情况下调整消息滚动位置', () => {
    const resize = new Map<Element, () => void>()
    class Observer implements ResizeObserver {
      constructor(private callback: ResizeObserverCallback) {}
      observe(target: Element) { resize.set(target, () => this.callback([], this)) }
      unobserve(target: Element) { resize.delete(target) }
      disconnect() {}
    }
    vi.stubGlobal('ResizeObserver', Observer)
    const { container } = render(view(props()))
    const scroll = container.querySelector('.chat-conversation__content') as HTMLDivElement
    Object.defineProperties(scroll, { scrollHeight: { configurable: true, value: 1600 }, clientHeight: { configurable: true, value: 500 } })
    expect(resize.has(scroll)).toBe(true)
    resize.get(scroll)!()
    expect(scroll.scrollTop).toBe(1600)
    scroll.scrollTop = 100
    fireEvent.scroll(scroll)
    resize.get(scroll)!()
    expect(scroll.scrollTop).toBe(100)
  })

  it('首页只有未知请求恢复操作时仍可通过加号进入任务进度', async () => {
    const retry = vi.fn()
    render(view(props({ platformHome: true, selectedSessionId: undefined, messages: [], taskActions: <button onClick={retry}>恢复未知请求</button> })))
    await userEvent.click(screen.getByRole('button', { name: '添加资料' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: '任务进度' }))
    const drawer = await screen.findByRole('dialog', { name: '任务进度' })
    expect(within(drawer).getByRole('button', { name: '恢复未知请求' })).toBeVisible()
    expect(retry).not.toHaveBeenCalled()
  })

  it('未确认消息的恢复按钮与下箭头不共用悬浮定位，也不主动重发', () => {
    const restore = vi.fn()
    render(view(props({ onRestorePendingMessage: restore })))
    const button = screen.getByRole('button', { name: '恢复上次未确认消息' })
    expect(button).toHaveClass('chat-conversation__restore')
    expect(button).not.toHaveClass('chat-conversation__latest')
    expect(restore).not.toHaveBeenCalled()
    fireEvent.click(button)
    expect(restore).toHaveBeenCalledOnce()
  })
})

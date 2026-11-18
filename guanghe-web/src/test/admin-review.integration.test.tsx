import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AdminFeedbackWorkspace } from '../features/admin-review/AdminFeedbackWorkspace'
import { displayCount, displayPercent, type FeedbackView } from '../features/admin-review/types'
import { useAuthStore } from '../stores/authStore'
import { adminFixture, userFixture } from './fixtures/auth'
import { renderApp } from './renderApp'

const ticket: FeedbackView = { id: 'feedback-1', account: '用**甲', business: '生图', description: '画面细节反馈', taskId: 'TASK-A', createdAt: '2026-09-07 11:00:00', status: 'NEW', problemType: '效果反馈', runId: 'run-1', profileHash: 'hash-1', attachments: [{ id: 'file-1', name: '截图.png' }], events: [{ id: 'event-1', actor: '客服甲', time: '2026-09-07', summary: '已记录问题' }] }
beforeEach(() => useAuthStore.setState({ status: 'anonymous', user: null, error: null }))
async function choose(user: ReturnType<typeof userEvent.setup>, name: string, option: string) {
  await user.click(screen.getByRole('combobox', { name }))
  await user.click(screen.getByText(option, { selector: '.ant-select-item-option-content' }))
}

describe('用户反馈', () => {
  it('反馈和聚合页签真实空态及导出刷新禁用', async () => {
    const user = userEvent.setup(); render(<AdminFeedbackWorkspace />)
    expect(screen.getAllByText('—')).toHaveLength(4); expect(screen.getByRole('button', { name: '导出反馈' })).toBeDisabled()
    for (const [tab, empty] of [['聚合分析', '暂无反馈聚合数据'], ['反馈工单', '暂无反馈工单']]) {
      await user.click(screen.getByRole('tab', { name: tab })); expect(screen.getByText(empty)).toBeInTheDocument()
    }
  })
  it('搜索、业务、状态、问题、日期可组合且重置恢复', async () => {
    const user = userEvent.setup(); render(<AdminFeedbackWorkspace tickets={[ticket, { ...ticket, id: 'feedback-2', taskId: 'TASK-B', business: '生视频', problemType: '任务异常', status: 'PROCESSING' }]} />)
    fireEvent.change(screen.getByLabelText('搜索记录'), { target: { value: ' TASK-A ' } })
    await choose(user, '业务类型', '生图'); await choose(user, '处理状态', '新增反馈'); await choose(user, '问题类型', '效果反馈')
    fireEvent.change(screen.getByLabelText('开始日期'), { target: { value: '2026-09-07' } }); fireEvent.change(screen.getByLabelText('结束日期'), { target: { value: '2026-09-07' } })
    expect(screen.getByText('feedback-1')).toBeInTheDocument(); expect(screen.queryByText('feedback-2')).not.toBeInTheDocument()
    await choose(user, '问题类型', '任务异常'); expect(screen.getByText('没有符合条件的反馈')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重置筛选' })); expect(screen.getByText('feedback-2')).toBeInTheDocument()
  })
  it('详情展示关联 Run、哈希、附件元数据、沟通记录，处理和评测禁用无请求', async () => {
    const user = userEvent.setup(); const request = vi.spyOn(globalThis, 'fetch'); render(<AdminFeedbackWorkspace tickets={[ticket]} />)
    await user.click(screen.getByRole('button', { name: '查看反馈' })); const drawer = within(screen.getByRole('dialog', { name: '反馈详情' }))
    for (const text of ['run-1', 'hash-1', '截图.png', '已记录问题']) expect(drawer.getByText(text)).toBeInTheDocument()
    for (const name of ['标记处理中', '保存回复记录', '标记解决', '预览未接入']) { expect(drawer.getByRole('button', { name })).toBeDisabled(); await user.click(drawer.getByRole('button', { name })) }
    expect(request).not.toHaveBeenCalled(); expect(document.querySelector('img[src],iframe')).toBeNull()
  })
  it('恶意用户描述作为纯文本展示，不解释 HTML 或创建链接', async () => {
    const user = userEvent.setup(); const malicious = '<img src=x onerror=alert(1)><a href="javascript:alert(1)">点击</a>'
    render(<AdminFeedbackWorkspace tickets={[{ ...ticket, description: malicious }]} />)
    await user.click(screen.getByRole('button', { name: '查看反馈' }))
    expect(within(screen.getByRole('dialog', { name: '反馈详情' })).getByText(malicious)).toBeInTheDocument()
    expect(document.querySelector('img[src="x"],a[href^="javascript:"]')).toBeNull()
  })
  it.each([undefined, { status: 'RESOLVED' as const }, { id: 'feedback-new' }, { account: '新账号' }, { taskId: 'TASK-NEW' }, { description: '新摘要' }])('记录变化清除本地回复且不恢复：%j', async change => {
    const user = userEvent.setup(); const { rerender } = render(<AdminFeedbackWorkspace tickets={[ticket]} />)
    await user.click(screen.getByRole('button', { name: '查看反馈' })); fireEvent.change(screen.getByLabelText('回复记录备注'), { target: { value: '旧回复' } })
    await user.click(within(screen.getByRole('dialog', { name: '反馈详情' })).getByRole('button', { name: 'Close' }))
    rerender(<AdminFeedbackWorkspace tickets={change ? [{ ...ticket, ...change }] : []} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(screen.queryByDisplayValue('旧回复')).not.toBeInTheDocument()
    rerender(<AdminFeedbackWorkspace tickets={[ticket]} />); expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '查看反馈' })); expect(screen.getByLabelText('回复记录备注')).toHaveValue('')
  })
  it('等价 tickets 保留填写；确认放弃后清空；已解决只读', async () => {
    const user = userEvent.setup(); const { rerender } = render(<AdminFeedbackWorkspace tickets={[ticket]} />)
    await user.click(screen.getByRole('button', { name: '查看反馈' })); fireEvent.change(screen.getByLabelText('回复记录备注'), { target: { value: '保留回复' } })
    rerender(<AdminFeedbackWorkspace tickets={[{ ...ticket, attachments: ticket.attachments.map(file => ({ ...file })), events: ticket.events.map(event => ({ ...event })) }]} />)
    expect(screen.getByLabelText('回复记录备注')).toHaveValue('保留回复')
    await user.click(within(screen.getByRole('dialog', { name: '反馈详情' })).getByRole('button', { name: 'Close' })); await user.click(screen.getByRole('button', { name: '确认放弃' }))
    rerender(<AdminFeedbackWorkspace tickets={[{ ...ticket, status: 'RESOLVED' }]} />)
    await user.click(screen.getByRole('button', { name: '查看反馈' })); expect(screen.getByLabelText('回复记录备注')).toHaveValue(''); expect(screen.getByLabelText('回复记录备注')).toBeDisabled()
  })
  it('聚合展示 Props 数据，不产生自动结论', async () => {
    const user = userEvent.setup(); render(<AdminFeedbackWorkspace aggregates={[{ id: 'a1', business: '生图', problemType: '效果反馈', count: 9, resolved: 2 }]} />)
    await user.click(screen.getByRole('tab', { name: '聚合分析' })); expect(screen.getByRole('table', { name: '反馈聚合分析' })).toHaveTextContent('效果反馈')
  })
  it.each([NaN, Infinity, -Infinity, -1, 101])('非法满意率 %s 显示占位', value => {
    render(<AdminFeedbackWorkspace summary={{ satisfaction7d: value }} />); expect(screen.getAllByText('—')).toHaveLength(4); expect(displayPercent(value)).toBe('—')
  })
  it('合法满意率边界和小数、非负安全整数统计格式正确', () => {
    for (const value of [0, 100, 98.5]) expect(displayPercent(value)).toBe(`${value}%`)
    expect(displayCount(1000)).toBe('1,000')
    for (const value of [undefined, NaN, Infinity, -1, 1.5, Number.MAX_SAFE_INTEGER + 1]) expect(displayCount(value)).toBe('—')
  })
})

describe('用户反馈与已移除审核路由', () => {
  it('用户反馈正式路由渲染真实控制台，不暴露预览样例', async () => {
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null }); renderApp(['/admin/feedback'])
    expect(await screen.findByRole('heading', { name: '用户反馈' })).toBeInTheDocument()
    expect(screen.queryByText('404 页面未找到')).not.toBeInTheDocument()
    expect(screen.queryByText('PREVIEW-FEEDBACK-001')).not.toBeInTheDocument()
  })
  it('普通用户不可访问用户反馈', async () => {
    useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null }); renderApp(['/admin/feedback']); expect(await screen.findByText('403 拒绝访问')).toBeInTheDocument()
  })
  it('用户反馈开发预览路由保留并高亮导航', async () => {
    renderApp(['/preview/admin/feedback']); expect(await screen.findByRole('heading', { name: '用户反馈' })).toBeInTheDocument()
    expect(within(screen.getByRole('navigation', { name: '管理员主导航' })).getByRole('button', { name: '用户反馈' })).toHaveAttribute('aria-current', 'page')
  })
  it.each(['/admin/content-safety', '/preview/admin/content-safety'])('人工内容审核路由 %s 已移除', async (path) => {
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
    renderApp([path])
    expect(await screen.findByText('404 页面未找到')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '内容安全' })).not.toBeInTheDocument()
  })
})

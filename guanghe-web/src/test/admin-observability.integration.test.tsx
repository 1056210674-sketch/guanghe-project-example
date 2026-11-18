import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AdminAuditWorkspace } from '../features/admin-observability/AdminAuditWorkspace'
import { redactedChangesJson, type AuditLogView } from '../features/admin-observability/types'
import { useAuthStore } from '../stores/authStore'
import { adminFixture, userFixture } from './fixtures/auth'
import { renderApp } from './renderApp'

const log: AuditLogView = { id: 'LOG-1', administrator: '管理员甲', action: '发布版本', objectType: '版本', objectId: 'v1', summary: '脱敏变更摘要', result: 'SUCCESS', ipMasked: '192.***.0.1', occurredAt: '2026-09-07 10:00:00', traceId: 'TRACE-ABC', reason: '更新原因', changes: [{ field: '版本', before: 'v0', after: 'v1' }], confirmation: { administrator: '管理员乙', time: '2026-09-07 09:59:00', result: '确认通过' }, taskId: 'TASK-1', events: [{ id: 'e1', time: '2026-09-07 10:00:00', summary: '操作完成' }] }
beforeEach(() => useAuthStore.setState({ status: 'anonymous', user: null, error: null }))
async function choose(user: ReturnType<typeof userEvent.setup>, name: string, value: string) {
  await user.click(screen.getByRole('combobox', { name }))
  await user.click(screen.getByText(value, { selector: '.ant-select-item-option-content' }))
}

describe('操作日志', () => {
  it('真实空态、禁用查询导出，不请求接口', async () => {
    const request = vi.spyOn(globalThis, 'fetch'); const user = userEvent.setup(); render(<AdminAuditWorkspace />)
    expect(screen.getByText('暂无操作日志')).toBeInTheDocument(); expect(screen.getByLabelText('搜索 traceId')).toBeDisabled()
    for (const name of ['查询日志', '导出日志']) { expect(screen.getByRole('button', { name })).toBeDisabled(); await user.click(screen.getByRole('button', { name })) }
    expect(request).not.toHaveBeenCalled()
  })
  it('管理员、操作、对象、结果、日期和 trace 组合查询，忽略 trace 大小写空格', async () => {
    const user = userEvent.setup(); render(<AdminAuditWorkspace logs={[log, { ...log, id: 'LOG-2', administrator: '管理员乙', traceId: 'TRACE-OTHER', result: 'FAILED' }]} />)
    await choose(user, '管理员', '管理员甲'); await choose(user, '操作类型', '发布版本'); await choose(user, '业务对象', '版本'); await choose(user, '操作结果', '成功')
    fireEvent.change(screen.getByLabelText('搜索 traceId'), { target: { value: '  trace-abc ' } })
    for (const label of ['日志开始日期', '日志结束日期']) fireEvent.change(screen.getByLabelText(label), { target: { value: '2026-09-07' } })
    expect(screen.getByText('LOG-2')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '查询日志' })); expect(screen.getByText('LOG-1')).toBeInTheDocument(); expect(screen.queryByText('LOG-2')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重置日志筛选' })); expect(screen.getByText('LOG-2')).toBeInTheDocument()
  })
  it('无结果空态及错误日期禁止查询', async () => {
    const user = userEvent.setup(); render(<AdminAuditWorkspace logs={[log]} />)
    fireEvent.change(screen.getByLabelText('搜索 traceId'), { target: { value: 'missing' } }); await user.click(screen.getByRole('button', { name: '查询日志' }))
    expect(screen.getByText('没有符合条件的操作日志')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('日志开始日期'), { target: { value: '2026-09-08' } }); fireEvent.change(screen.getByLabelText('日志结束日期'), { target: { value: '2026-09-07' } })
    expect(screen.getByRole('button', { name: '查询日志' })).toBeDisabled(); expect(screen.getByText('开始日期不能晚于结束日期')).toBeInTheDocument()
  })
  it('详情含前后变更、原因、二次确认、关联标识和时间线，仅展开脱敏 JSON', async () => {
    const user = userEvent.setup(); render(<AdminAuditWorkspace logs={[log]} />)
    await user.click(screen.getByRole('button', { name: '查看日志' })); const detail = within(screen.getByRole('dialog', { name: '日志详情' }))
    for (const text of ['更新原因', '确认通过', 'TASK-1', '操作完成']) expect(detail.getByText(text)).toBeInTheDocument()
    expect(detail.getByRole('table', { name: '操作前后变更' })).toHaveTextContent('v0')
    await user.click(detail.getByRole('button', { name: '查看摘要 JSON' })); expect(detail.getByLabelText('脱敏变更 JSON')).toHaveTextContent('"before": "v0"')
    await user.click(detail.getByRole('button', { name: '收起摘要 JSON' })); expect(detail.queryByLabelText('脱敏变更 JSON')).not.toBeInTheDocument()
    expect(detail.queryByRole('button', { name: /编辑|删除/ })).not.toBeInTheDocument()
  })
  it('不泄漏额外原始字段，恶意文本不执行', async () => {
    const user = userEvent.setup(); const raw = { ...log, token: 'DO-NOT-EXPOSE', changes: [{ ...log.changes[0], rawPrompt: 'DO-NOT-EXPOSE' }] }
    expect(redactedChangesJson(raw)).not.toContain('DO-NOT-EXPOSE')
    render(<AdminAuditWorkspace logs={[{ ...raw, reason: '<img src=x onerror=alert(1)>' }]} />)
    await user.click(screen.getByRole('button', { name: '查看日志' })); expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument(); expect(document.querySelector('img[src="x"]')).toBeNull()
  })
  it('删除记录立即关闭详情，重新出现不恢复旧详情', async () => {
    const user = userEvent.setup(); const { rerender } = render(<AdminAuditWorkspace logs={[log]} />)
    await user.click(screen.getByRole('button', { name: '查看日志' })); rerender(<AdminAuditWorkspace logs={[]} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); rerender(<AdminAuditWorkspace logs={[log]} />); expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
  it('同 ID Props 刷新使用当前变更，旧 JSON 不残留', async () => {
    const user = userEvent.setup(); const { rerender } = render(<AdminAuditWorkspace logs={[log]} />)
    await user.click(screen.getByRole('button', { name: '查看日志' })); await user.click(screen.getByRole('button', { name: '查看摘要 JSON' }))
    rerender(<AdminAuditWorkspace logs={[{ ...log, changes: [{ field: '版本', before: 'v2', after: 'v3' }], reason: '新原因' }]} />)
    expect(screen.queryByLabelText('脱敏变更 JSON')).not.toBeInTheDocument(); expect(screen.getByText('新原因')).toBeInTheDocument(); expect(screen.queryByText('v0')).not.toBeInTheDocument()
  })
  it('日志本地分页只显示当前页，数据缩减回到有效页', async () => {
    const user = userEvent.setup(); const logs = Array.from({ length: 11 }, (_, index) => ({ ...log, id: `page-log-${index}` }))
    const { rerender } = render(<AdminAuditWorkspace logs={logs} />)
    expect(screen.getAllByRole('button', { name: '查看日志' })).toHaveLength(10)
    await user.click(screen.getByTitle('2')); expect(screen.getByText('page-log-10')).toBeInTheDocument()
    rerender(<AdminAuditWorkspace logs={[logs[0]]} />); expect(screen.getByText('page-log-0')).toBeInTheDocument()
  })
})
describe('操作日志与版本路由', () => {
  it('正式路由 /admin/audit-logs 渲染真实控制台，不暴露预览样例', async () => {
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null }); renderApp(['/admin/audit-logs'])
    expect(await screen.findByRole('heading', { name: '操作日志' })).toBeInTheDocument()
    expect(screen.queryByText('404 页面未找到')).not.toBeInTheDocument(); expect(screen.queryByText('PREVIEW-AUDIT-001')).not.toBeInTheDocument()
  })
  it('正式路由 /admin/agent-versions 渲染只读版本观测台', async () => {
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null }); renderApp(['/admin/agent-versions'])
    expect(await screen.findByRole('heading', { name: 'Prompt / Agent 版本' })).toBeInTheDocument(); expect(screen.queryByText('404 页面未找到')).not.toBeInTheDocument()
  })
  it.each(['/admin/audit-logs', '/admin/agent-versions'])('普通用户禁止访问 %s', async path => {
    useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null }); renderApp([path]); expect(await screen.findByText('403 拒绝访问')).toBeInTheDocument()
  })
  it.each([['/preview/admin/audit-logs', '操作日志', '操作日志'], ['/preview/admin/agent-versions', 'Prompt / Agent 版本', 'Agent 版本']])('开发预览 %s 正常且导航高亮', async (path, heading, nav) => {
    renderApp([path]); expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument()
    expect(within(screen.getByRole('navigation', { name: '管理员主导航' })).getByRole('button', { name: nav })).toHaveAttribute('aria-current', 'page')
  })
})

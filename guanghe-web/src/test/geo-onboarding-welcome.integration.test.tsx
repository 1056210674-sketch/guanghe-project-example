import { fireEvent, render as renderComponent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import type { ReactElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { GeoOnboardingWelcome, type GeoOnboardingWelcomeProps } from '../features/chat/components/GeoOnboardingWelcome'
import { GeoMascot } from '../features/chat/mascot/GeoMascot'

// JSDOM does not complete CSS animations; keep real form/events and disable only motion.
const render = (element: ReactElement) => renderComponent(element, {
  wrapper: ({ children }) => <ConfigProvider theme={{ token: { motion: false } }}>{children}</ConfigProvider>,
})

function props(overrides: Partial<GeoOnboardingWelcomeProps> = {}): GeoOnboardingWelcomeProps {
  return {
    projectName: '小行星咖啡', knowledgeState: 'empty',
    onUpload: vi.fn(), onOpenKnowledge: vi.fn(), onRetry: vi.fn(), onPrefill: vi.fn(() => true),
    ...overrides,
  }
}

describe('GEO 首次聊天资料引导', () => {
  it('主动问候并提供上传入口，不自动读取全文、发送消息或启动任务', async () => {
    const user = userEvent.setup()
    const callbacks = props()
    render(<GeoMascot messages={[]} busy={false}><GeoOnboardingWelcome {...callbacks} /></GeoMascot>)
    expect(screen.getByRole('heading', { name: '你好，我是 GEO 智能体' })).toBeVisible()
    expect(screen.getByRole('button', { name: /光禾AI智能体框架陪你一起完成任务/ })).toBeVisible()
    expect(screen.getByText(/现在只聊方案，不会自动监测或投稿/)).toBeVisible()
    expect(callbacks.onUpload).not.toHaveBeenCalled()
    expect(callbacks.onPrefill).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '上传文件' }))
    expect(callbacks.onUpload).toHaveBeenCalledOnce()
    expect(callbacks.onPrefill).not.toHaveBeenCalled()
  })

  it('只填写一项也能放入输入框，不拼造空字段或将已有品牌默认为用户确认', async () => {
    const user = userEvent.setup()
    const callbacks = props({ knowledgeState: 'existing', knownBrandName: '已有品牌' })
    render(<GeoOnboardingWelcome {...callbacks} />)
    expect(screen.getByText('已有品牌')).toBeVisible()
    await user.click(screen.getByRole('button', { name: '填写公司信息' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByLabelText('对外品牌名称')).toHaveValue('')
    expect(within(dialog).getByRole('button', { name: '放入聊天输入框' })).toBeDisabled()
    await user.type(within(dialog).getByLabelText('主营产品／服务'), '现磨咖啡与茶咖')
    expect(callbacks.onPrefill).not.toHaveBeenCalled()
    await user.click(within(dialog).getByRole('button', { name: '放入聊天输入框' }))
    expect(callbacks.onPrefill).toHaveBeenCalledOnce()
    const text = vi.mocked(callbacks.onPrefill).mock.calls[0][0]
    expect(text).toContain('主营产品／服务：现磨咖啡与茶咖')
    expect(text).not.toContain('对外品牌名称：')
    expect(text).not.toContain('已有品牌')
    expect(text).not.toContain('目标曝光地域：')
    expect(text).toContain('没有填写的品牌名称请先问我')
    expect(text).not.toContain('请将我填写的对外品牌名称保存')
    expect(text).toContain('不启动监测或投稿')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument(), { timeout: 3000 })
  })

  it('基础与补充信息一起交给输入框，不丢失地区区别、证据和保密约束', async () => {
    const user = userEvent.setup()
    const callbacks = props()
    render(<GeoOnboardingWelcome {...callbacks} />)
    await user.click(screen.getByRole('button', { name: '填写公司信息' }))
    const dialog = screen.getByRole('dialog')
    await user.type(within(dialog).getByLabelText('对外品牌名称'), '小行星咖啡')
    await user.type(within(dialog).getByLabelText('实际服务范围'), '广州天河，仅门店自取')
    await user.type(within(dialog).getByLabelText('目标曝光地域'), '广东')
    await user.click(within(dialog).getByText('补充真实优势、官网与案例（选填）'))
    await user.type(within(dialog).getByLabelText('可公开案例／资质及证据'), '暂无可公开案例')
    await user.type(within(dialog).getByLabelText('其他资料／不可公开内容'), '不可公开：店主私人电话')
    await user.click(within(dialog).getByRole('button', { name: '放入聊天输入框' }))
    const text = vi.mocked(callbacks.onPrefill).mock.calls[0][0]
    expect(text).toContain('实际服务范围：广州天河，仅门店自取\n目标曝光地域：广东')
    expect(text).toContain('可公开案例／资质及证据：暂无可公开案例')
    expect(text).toContain('其他资料／不可公开内容：不可公开：店主私人电话')
    expect(text).toContain('请将我填写的对外品牌名称保存到当前项目的品牌档案')
    expect(text).toContain('不得写入对外文章或搜索词')
    expect(text).toContain('未填写的内容不要自行补造')
    expect(text.length).toBeLessThan(10_000)
  })

  it('拒绝放入、收起后重开都保留输入，禁用状态不能触发提交', async () => {
    const user = userEvent.setup()
    const callbacks = props({ onPrefill: vi.fn(() => false) })
    const view = render(<GeoOnboardingWelcome {...callbacks} />)
    await user.click(screen.getByRole('button', { name: '填写公司信息' }))
    await user.type(screen.getByLabelText('对外品牌名称'), '小行星咖啡')
    await user.click(screen.getByRole('button', { name: '放入聊天输入框' }))
    expect(screen.getByRole('dialog')).toBeVisible()
    expect(screen.getByText(/这里的填写已保留/)).toBeVisible()
    await user.click(screen.getByRole('button', { name: '暂时收起' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument(), { timeout: 3000 })
    await user.click(screen.getByRole('button', { name: '填写公司信息' }))
    expect(screen.getByLabelText('对外品牌名称')).toHaveValue('小行星咖啡')
    view.rerender(<GeoOnboardingWelcome {...callbacks} disabled />)
    expect(screen.getByRole('button', { name: '放入聊天输入框' })).toBeDisabled()
    const input = screen.getByLabelText('对外品牌名称')
    fireEvent.submit(input.closest('form')!)
    expect(callbacks.onPrefill).toHaveBeenCalledOnce()
  })

  it('无项目不能上传，但仍可提供公司信息；读取失败不呈现没有资料', async () => {
    const user = userEvent.setup()
    const callbacks = props({ knowledgeState: 'unscoped', projectName: undefined })
    const view = render(<GeoOnboardingWelcome {...callbacks} />)
    expect(screen.getByRole('button', { name: '上传文件' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '填写公司信息' })).toBeEnabled()
    expect(screen.getByText(/先选择项目才能上传并留存资料/)).toBeVisible()
    view.rerender(<GeoOnboardingWelcome {...callbacks} knowledgeState="error" projectName="小行星咖啡" />)
    expect(screen.getByText('暂时无法确认本项目的资料，不代表资料为空。')).toBeVisible()
    expect(screen.queryByText(/初次见面/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重新读取' }))
    expect(callbacks.onRetry).toHaveBeenCalledOnce()
  })

  it('已有资料只展示状态，查看知识库不会主动上传或填入表单', async () => {
    const user = userEvent.setup()
    const callbacks = props({ knowledgeState: 'existing' })
    render(<GeoOnboardingWelcome {...callbacks} />)
    expect(screen.getByText(/本项目已有资料，可以直接告诉我想完成什么/)).toBeVisible()
    expect(screen.queryByText(/已读完|已读取全文/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '查看品牌知识库' }))
    expect(callbacks.onOpenKnowledge).toHaveBeenCalledOnce()
    expect(callbacks.onUpload).not.toHaveBeenCalled()
    expect(callbacks.onPrefill).not.toHaveBeenCalled()
  })
})

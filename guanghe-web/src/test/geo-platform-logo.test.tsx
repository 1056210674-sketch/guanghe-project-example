import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Checkbox } from 'antd'
import { describe, expect, it, vi } from 'vitest'
import deepseekLogo from '../assets/geo-platforms/deepseek.svg'
import doubaoLogo from '../assets/geo-platforms/doubao.png'
import kimiLogo from '../assets/geo-platforms/kimi.png'
import qwenLogo from '../assets/geo-platforms/qwen.png'
import wenxinLogo from '../assets/geo-platforms/wenxin-app.png'
import yuanbaoLogo from '../assets/geo-platforms/yuanbao.png'
import zhipuLogo from '../assets/geo-platforms/zhipu.ico'
import { GeoPlatformBadge } from '../features/geo/components/GeoPlatformBadge'
import type { GeoPlatformView } from '../features/geo/types'

const platformMarks = [
  ['DOUBAO', '豆包', doubaoLogo],
  ['DEEPSEEK', 'DeepSeek', deepseekLogo],
  ['QWEN', '通义千问', qwenLogo],
  ['WENXIN', '文心一言', wenxinLogo],
  ['KIMI', 'Kimi', kimiLogo],
  ['YUANBAO', '腾讯元宝', yuanbaoLogo],
  ['ZHIPU', '智谱', zhipuLogo],
  ['GLM', '智谱', zhipuLogo],
] as const

describe('GEO 平台标识', () => {
  it.each(platformMarks)('%s 使用对应平台图标并保留可读名称', (code, name, logo) => {
    const platform: GeoPlatformView = { code, name, availability: 'AVAILABLE' }
    const { container } = render(<GeoPlatformBadge platform={platform} />)

    expect(screen.getByText(name)).toBeInTheDocument()
    expect(container.querySelector('.geo-platform-badge__mark img')).toHaveAttribute('src', logo)
  })

  it('未知平台仍显示文字标识', () => {
    const platform: GeoPlatformView = {
      code: 'FUTURE_PROVIDER',
      name: '新平台',
      availability: 'AVAILABLE',
    }
    const { container } = render(<GeoPlatformBadge platform={platform} />)

    expect(container.querySelector('.geo-platform-badge__mark img')).toBeNull()
    expect(container.querySelector('.geo-platform-badge__mark')).toHaveTextContent('新平')
    expect(screen.getByText('新平台')).toBeInTheDocument()
  })

  it('点击图标或用键盘可切换整张平台卡片的勾选', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const platform: GeoPlatformView = {
      code: 'DEEPSEEK', name: 'DeepSeek', availability: 'AVAILABLE',
    }
    const { container } = render(
      <Checkbox.Group className="geo-platform-check-grid" onChange={onChange}>
        <Checkbox value="DEEPSEEK"><GeoPlatformBadge platform={platform} compact /></Checkbox>
      </Checkbox.Group>,
    )

    const logo = container.querySelector('[data-platform-code="DEEPSEEK"] img')
    expect(logo).not.toBeNull()
    await user.click(logo as Element)
    const checkbox = screen.getByRole('checkbox', { name: 'DeepSeek' })
    expect(checkbox).toBeChecked()
    expect(onChange).toHaveBeenCalledWith(['DEEPSEEK'])

    await user.click(checkbox.closest('label') as Element)
    expect(checkbox).not.toBeChecked()

    checkbox.focus()
    await user.keyboard(' ')
    expect(checkbox).toBeChecked()
  })
})

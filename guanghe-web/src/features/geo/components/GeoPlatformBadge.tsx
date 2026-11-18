import { Tag, Typography } from 'antd'
import deepseekLogo from '../../../assets/geo-platforms/deepseek.svg'
import doubaoLogo from '../../../assets/geo-platforms/doubao.png'
import kimiLogo from '../../../assets/geo-platforms/kimi.png'
import qwenLogo from '../../../assets/geo-platforms/qwen.png'
import wenxinLogo from '../../../assets/geo-platforms/wenxin-app.png'
import yuanbaoLogo from '../../../assets/geo-platforms/yuanbao.png'
import zhipuLogo from '../../../assets/geo-platforms/zhipu.ico'
import type { GeoPlatformView } from '../types'

export interface GeoPlatformBadgeProps {
  platform: GeoPlatformView
  compact?: boolean
  showAvailability?: boolean
}

// Use the provider's own published mark. Unknown platform codes retain a text
// fallback so a newly enabled backend platform never renders as a broken image.
const platformLogos: Record<string, string> = {
  DOUBAO: doubaoLogo,
  DEEPSEEK: deepseekLogo,
  QWEN: qwenLogo,
  WENXIN: wenxinLogo,
  KIMI: kimiLogo,
  YUANBAO: yuanbaoLogo,
  ZHIPU: zhipuLogo,
  GLM: zhipuLogo,
}

function platformInitial(platform: GeoPlatformView) {
  const explicit = platform.shortLabel?.trim()
  if (explicit) {
    return explicit.slice(0, 2)
  }

  return platform.name.trim().slice(0, 2).toUpperCase() || 'AI'
}

function availabilityText(platform: GeoPlatformView) {
  if (platform.availabilityLabel) {
    return platform.availabilityLabel
  }

  switch (platform.availability) {
    case 'AVAILABLE':
      return '可用'
    case 'MAINTENANCE':
      return '维护中'
    case 'DISABLED':
      return '暂不可用'
  }
}

export function GeoPlatformBadge({
  platform,
  compact = false,
  showAvailability = false,
}: GeoPlatformBadgeProps) {
  const logo = platformLogos[platform.code.trim().toUpperCase()]

  return (
    <span
      className={`geo-platform-badge${compact ? ' geo-platform-badge--compact' : ''}`}
      data-platform-code={platform.code}
    >
      <span className="geo-platform-badge__mark" aria-hidden="true">
        {logo ? <img src={logo} alt="" width="30" height="30" /> : platformInitial(platform)}
      </span>
      <span className="geo-platform-badge__copy">
        <Typography.Text>{platform.name}</Typography.Text>
        {showAvailability ? (
          <Tag className="geo-platform-badge__availability">
            {availabilityText(platform)}
          </Tag>
        ) : null}
      </span>
    </span>
  )
}

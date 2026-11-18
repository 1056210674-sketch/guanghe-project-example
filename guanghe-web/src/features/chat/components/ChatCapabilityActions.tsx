import { FileImageOutlined, FileTextOutlined, PlaySquareOutlined } from '@ant-design/icons'
import { Button } from 'antd'
import type { ReactNode } from 'react'
import { hasAgentCapability } from '../../agent-platform/capabilities'
import { creationPrefill, type ComposerCreationCapability, type ComposerPrefill } from '../composerCapabilities'

const CREATION_ACTIONS: readonly { capability: ComposerCreationCapability; label: string; icon: ReactNode }[] = [
  { capability: 'article.generate', label: '写文章', icon: <FileTextOutlined /> },
  { capability: 'image.generate', label: '生成图片', icon: <FileImageOutlined /> },
  { capability: 'video.generate', label: '生成视频', icon: <PlaySquareOutlined /> },
]

/** Shared by any installed agent that has the corresponding server-projected capability. */
export function ChatCapabilityActions({ capabilities, draft, hasAttachments, hasReport, disabled, articleBusy, onPrefill }: {
  capabilities: readonly string[] | undefined
  draft: string
  hasAttachments: boolean
  hasReport: boolean
  disabled: boolean
  articleBusy: boolean
  onPrefill: (prefill: ComposerPrefill) => void
}) {
  return <>{CREATION_ACTIONS.filter(({ capability }) => hasAgentCapability(capabilities, capability)).map((action) => (
    <Button key={action.capability} size="small" icon={action.icon} aria-label={action.label}
      disabled={disabled || action.capability === 'article.generate' && articleBusy}
      onClick={() => onPrefill(creationPrefill(action.capability, {
        draft, hasAttachments, hasReport, canRecommendMedia: hasAgentCapability(capabilities, 'media.recommend'),
      }))}>{action.label}</Button>
  ))}</>
}

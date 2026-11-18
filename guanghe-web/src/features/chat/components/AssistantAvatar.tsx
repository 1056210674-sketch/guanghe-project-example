import companyLogoUrl from '../../../assets/brand/company-logo-ai-hd-white.png'
import { CREATIVE_MASCOT_DATA_URI } from '../../../assets/creative-mascot/creativeMascotAsset'

export type AssistantAvatarVariant = 'default' | 'creative'

export interface AssistantAvatarProps {
  placement?: 'message' | 'welcome'
  variant?: AssistantAvatarVariant
}

/** Static identity for non-GEO agents. GEO keeps its stateful animated mascot. */
export function AssistantAvatar({
  placement = 'message',
  variant = 'default',
}: AssistantAvatarProps) {
  const className = placement === 'welcome'
    ? `chat-empty-welcome__logo chat-empty-welcome__logo--${variant}`
    : `chat-message__avatar chat-message__avatar--${variant}`

  return (
    <span
      className={className}
      data-agent-avatar={variant}
      aria-hidden="true"
    >
      <img
        src={variant === 'creative' ? CREATIVE_MASCOT_DATA_URI : companyLogoUrl}
        alt=""
      />
    </span>
  )
}

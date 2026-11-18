import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AssistantAvatar } from '../features/chat/components/AssistantAvatar'
import {
  ChatMessageStream,
  type ChatMessageStreamProps,
} from '../features/chat/components/ChatMessageStream'

const callbacks: Omit<ChatMessageStreamProps, 'messages' | 'assistantAvatarVariant'> = {
  onSelectClarificationOption: vi.fn(),
  onEditPaidTaskConfirmation: vi.fn(),
  onConfirmPaidTaskConfirmation: vi.fn(),
  onOpenRecharge: vi.fn(),
  onPrepareReestimate: vi.fn(),
  onOpenPaidTaskReferenceAsset: vi.fn(),
  onAddPaidTaskReferenceAsset: vi.fn(),
  onViewTask: vi.fn(),
  onPreviewArticle: vi.fn(),
  onContinueEditingArticle: vi.fn(),
  onSaveArticleToAssets: vi.fn(),
  onPreviewGeneratedImage: vi.fn(),
  onDownloadGeneratedImage: vi.fn(),
  onSaveGeneratedImageToAssets: vi.fn(),
  onSelectGeneratedImage: vi.fn(),
  onTaskOutcomeAction: vi.fn(),
}

describe('创作智能体独立 IP 身份', () => {
  it('空态与消息位复用同一份已审粉色光禾AI智能体框架素材，普通智能体仍使用默认品牌标识', () => {
    const mounted = render(
      <>
        <AssistantAvatar placement="welcome" variant="creative" />
        <AssistantAvatar variant="creative" />
        <AssistantAvatar />
      </>,
    )

    const creative = mounted.container.querySelectorAll<HTMLElement>(
      '[data-agent-avatar="creative"]',
    )
    expect(creative).toHaveLength(2)
    expect(creative[0]).toHaveClass('chat-empty-welcome__logo--creative')
    expect(creative[1]).toHaveClass('chat-message__avatar--creative')
    for (const avatar of creative) {
      expect(avatar.querySelector('img')?.src).toMatch(/^data:image\/png;base64,/)
    }
    expect(
      mounted.container.querySelector('[data-agent-avatar="default"] img'),
    ).toHaveAttribute('src', expect.not.stringMatching(/^data:image\/png;base64,/))
  })

  it('创作对话只替换 assistant 左侧身份，用户头像保持原样', () => {
    const mounted = render(
      <MemoryRouter>
        <ChatMessageStream
          {...callbacks}
          assistantAvatarVariant="creative"
          messages={[
            { id: 'u1', role: 'USER', content: '生成一张春日咖啡海报' },
            { id: 'a1', role: 'ASSISTANT', content: '已准备生成参数。' },
            {
              id: 'a2',
              role: 'ASSISTANT',
              content: '',
              backgroundTaskNotification: {
                taskId: 'image-task-1',
                label: '后台任务',
                title: '图片正在生成',
                description: '可以继续聊天，完成后会在这里显示。',
              },
            },
          ]}
        />
      </MemoryRouter>,
    )

    expect(
      mounted.container.querySelectorAll('[data-agent-avatar="creative"]'),
    ).toHaveLength(2)
    expect(
      mounted.container.querySelector('.chat-message--user [data-agent-avatar]'),
    ).not.toBeInTheDocument()
  })
})

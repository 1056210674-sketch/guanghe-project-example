import { fireEvent, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { ChatMessageStream, type ChatMessageStreamProps } from '../features/chat/components/ChatMessageStream'
import { geoDiagnosticMessageKey, placeGeoDiagnosticMessages } from '../features/chat/live/diagnosticMessagePlacement'
import type { ChatMessageView } from '../features/chat/types'

const callbacks: Omit<ChatMessageStreamProps, 'messages'> = {
  onSelectClarificationOption: vi.fn(), onEditPaidTaskConfirmation: vi.fn(), onConfirmPaidTaskConfirmation: vi.fn(),
  onOpenRecharge: vi.fn(), onPrepareReestimate: vi.fn(), onOpenPaidTaskReferenceAsset: vi.fn(), onAddPaidTaskReferenceAsset: vi.fn(),
  onViewTask: vi.fn(), onPreviewArticle: vi.fn(), onContinueEditingArticle: vi.fn(), onSaveArticleToAssets: vi.fn(),
  onPreviewGeneratedImage: vi.fn(), onDownloadGeneratedImage: vi.fn(), onSaveGeneratedImageToAssets: vi.fn(),
  onSelectGeneratedImage: vi.fn(), onTaskOutcomeAction: vi.fn(),
}
const diagnostic = (messageId: string, step = 1, diagnosticId = '901', conversationId = '501'): ChatMessageView => ({
  id: messageId, role: 'ASSISTANT', content: '', createdAt: '14:30',
  geoDiagnostic: { diagnosticId, conversationId, step },
})

describe('诊断卡片共享一个消息位置', () => {
  it('六步及同一步重新生成只保留首条稳定引用，不改动服务端消息或快照', () => {
    const messages = Object.freeze([
      diagnostic('9901'), diagnostic('9902', 2), diagnostic('9903', 3), diagnostic('9904', 3),
      diagnostic('9905', 4), diagnostic('9906', 5), diagnostic('9907', 6),
    ].map((message) => Object.freeze(message)))
    const placed = placeGeoDiagnosticMessages(messages)
    expect(placed).toEqual([messages[0]])
    expect(placed[0]).toBe(messages[0])
    expect(messages).toHaveLength(7)
    expect(placed[0].geoDiagnostic).toEqual({ diagnosticId: '901', conversationId: '501', step: 1 })
  })

  it('同一聊天的不同诊断、不同聊天的同号诊断不合并，普通消息顺序不变', () => {
    const user: ChatMessageView = { id: 'u1', role: 'USER', content: '诊断期间继续讨论选题' }
    const reply: ChatMessageView = { id: 'a1', role: 'ASSISTANT', content: '可以继续聊天。' }
    const messages = [diagnostic('9901'), user, diagnostic('9902', 2), reply,
      diagnostic('9911', 1, '902'), diagnostic('9921', 1, '901', '502'), diagnostic('9912', 2, '902')]
    expect(placeGeoDiagnosticMessages(messages)).toEqual([messages[0], user, reply, messages[4], messages[5]])
    expect(geoDiagnosticMessageKey(messages[0])).not.toBe(geoDiagnosticMessageKey(messages[5]))
  })

  it('正文、其他业务组件和未知新载荷不因携带诊断引用而被吞掉', () => {
    const anchor = diagnostic('9901')
    const mixed = [
      { ...diagnostic('9902', 2), content: '已经核对品牌资料。' },
      { ...diagnostic('9903', 3), guidance: '请确认这些同类品牌。' },
      { ...diagnostic('9904', 4), businessOutcome: { schemaVersion: 1 as const, status: 'ANSWERED' as const } },
      { ...diagnostic('9905', 5), outputStreaming: true },
      { ...diagnostic('9906', 6), futurePayload: { content: '保留未来组件' } },
      { ...diagnostic('u2', 1), role: 'USER' as const },
    ]
    expect(placeGeoDiagnosticMessages([anchor, ...mixed, diagnostic('9907', 6)])).toEqual([anchor, ...mixed])
    expect(mixed.every((message) => geoDiagnosticMessageKey(message) === undefined)).toBe(true)
  })

  it('适配器生成的 undefined 字段和非流式标记不妨碍折叠', () => {
    const first = { ...diagnostic('9901'), outputStreaming: false, articlePreparation: undefined, guidance: undefined }
    const second = { ...diagnostic('9902', 2), outputStreaming: false }
    expect(placeGeoDiagnosticMessages([first, second])).toEqual([first])
    expect(placeGeoDiagnosticMessages([])).toEqual([])
  })

  it('真实消息列表只渲染一张诊断卡片，并保留夹在步骤间的聊天和不同诊断', () => {
    const renderCard = vi.fn((reference: NonNullable<ChatMessageView['geoDiagnostic']>, id: string) => (
      <section aria-label={`诊断 ${reference.diagnosticId}`}>首条引用 {id}</section>
    ))
    const messages = [diagnostic('9901'), { id: 'u1', role: 'USER' as const, content: '我还想讨论选题' },
      diagnostic('9902', 2), { id: 'a1', role: 'ASSISTANT' as const, content: '我们可以继续讨论。' },
      diagnostic('9903', 6), diagnostic('9911', 1, '902')]
    render(<MemoryRouter><ChatMessageStream {...callbacks} renderGeoDiagnosticCard={renderCard} messages={messages} /></MemoryRouter>)
    expect(screen.getAllByRole('region', { name: '诊断 901' })).toHaveLength(1)
    expect(screen.getAllByRole('region', { name: '诊断 902' })).toHaveLength(1)
    expect(within(screen.getByLabelText('消息列表')).getAllByRole('article').map((item) => item.textContent)).toEqual([
      '首条引用 990114:30', '我还想讨论选题', '我们可以继续讨论。', '首条引用 991114:30',
    ])
    expect(renderCard).toHaveBeenCalledTimes(2)
    expect(renderCard).toHaveBeenNthCalledWith(1, messages[0].geoDiagnostic, '9901')
  })

  it('追加后台步骤不重挂首条诊断实例，保留其本地输入', () => {
    function DraftCard({ anchor }: { anchor: string }) {
      const [draft, setDraft] = useState('')
      return <><span>引用 {anchor}</span><input aria-label="尚未保存的诊断输入" value={draft} onChange={(event) => setDraft(event.target.value)} /></>
    }
    const renderCard = (_reference: NonNullable<ChatMessageView['geoDiagnostic']>, id: string) => <DraftCard anchor={id} />
    const first = diagnostic('9901')
    const tree = (messages: readonly ChatMessageView[]) => <MemoryRouter><ChatMessageStream {...callbacks} renderGeoDiagnosticCard={renderCard} messages={messages} /></MemoryRouter>
    const mounted = render(tree([first]))
    fireEvent.change(screen.getByLabelText('尚未保存的诊断输入'), { target: { value: '用户还没保存的资料' } })
    mounted.rerender(tree([first, diagnostic('9902', 2), diagnostic('9903', 2)]))
    expect(screen.getByLabelText('尚未保存的诊断输入')).toHaveValue('用户还没保存的资料')
    expect(screen.getByText('引用 9901')).toBeVisible()
    expect(screen.queryByText('引用 9903')).not.toBeInTheDocument()
  })
})

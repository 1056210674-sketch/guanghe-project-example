import { useEffect, useRef, useState } from 'react'
import { LeftOutlined, RightOutlined } from '@ant-design/icons'
import { Alert, Button, Spin } from 'antd'
import { useIsMutating } from '@tanstack/react-query'
import { useAuthStore } from '../../stores/authStore'
import type { ChatGeoDiagnosticReference } from '../../features/chat/types'
import { diagnosticKeys, isScopedGeoDiagnostic, useGeoDiagnostic } from '../../features/geo-diagnostics/queries'
import { GeoDiagnosticCardContainer } from './GeoDiagnosticCardContainer'
import '../../styles/geo-diagnostics.css'

/** One stable conversation item; pagination is local browsing, never a business action. */
export function GeoDiagnosticPagerContainer({ reference, messageId }: { reference: ChatGeoDiagnosticReference; messageId: string }) {
  const ownerId = useAuthStore((state) => state.user?.id)
  const query = useGeoDiagnostic(ownerId, reference.diagnosticId, reference.conversationId, reference.step, messageId, 'deck')
  const busy = useIsMutating({ mutationKey: diagnosticKeys.action(ownerId, reference.diagnosticId) }) > 0
  // null follows server progress. Looking back must not be interrupted by an AI result.
  const [selectedStep, setSelectedStep] = useState<number | null>(null)
  const currentPage = useRef<HTMLDivElement>(null)
  const diagnostic = !query.isError && isScopedGeoDiagnostic(query.data, reference) ? query.data : undefined
  const currentStep = diagnostic?.step
  useEffect(() => { if (currentPage.current) currentPage.current.scrollTop = 0 }, [currentStep])

  if (query.isError) return <Alert type="error" showIcon title="暂时无法读取这份诊断，请重新加载。" action={<Button onClick={() => void query.refetch()}>重新加载诊断</Button>} />
  if (!diagnostic) return <div role="status"><Spin size="small" /> 正在读取诊断卡片…</div>
  const cards = new Map(diagnostic.cards.filter((card) => card.step <= diagnostic.step).map((card) => [card.step, card]))
  const currentCard = cards.get(diagnostic.step)
  const viewedStep = selectedStep !== null && selectedStep < diagnostic.step && cards.has(selectedStep) ? selectedStep : diagnostic.step
  const viewedCard = cards.get(viewedStep)
  if (!currentCard || !viewedCard) return <Alert type="warning" title="诊断卡片暂时不可用，请刷新记录。" action={<Button onClick={() => void query.refetch()}>刷新</Button>} />
  const steps = [...cards.keys()].sort((left, right) => left - right)
  const position = steps.indexOf(viewedStep)
  const navigate = (step: number) => setSelectedStep(step === diagnostic.step ? null : step)
  const cardReference = (step: number): ChatGeoDiagnosticReference => ({ ...reference, step })

  return <div className="geo-diagnostic-deck">
    <nav className="geo-diagnostic-deck__navigation" aria-label="诊断步骤翻页">
      <span>诊断报告</span>
      <div className="geo-diagnostic-deck__pager">
        <Button type="text" aria-label="上一步" title="查看上一步" icon={<LeftOutlined />} disabled={busy || position <= 0} onClick={() => navigate(steps[position - 1])} />
        <span className="geo-diagnostic-deck__position" aria-live="polite" aria-atomic="true">{viewedStep}/6</span>
        <Button type="text" aria-label="下一步" title="查看下一步已保存内容" icon={<RightOutlined />} disabled={busy || position >= steps.length - 1} onClick={() => navigate(steps[position + 1])} />
      </div>
    </nav>
    {/* Keep the live editor mounted while browsing history: unsaved input stays local. */}
    <div ref={currentPage} className="geo-diagnostic-deck__page" tabIndex={0} hidden={viewedStep !== diagnostic.step}>
      <GeoDiagnosticCardContainer reference={cardReference(diagnostic.step)} messageId={currentCard.messageId} paged onResume={() => setSelectedStep(null)} />
    </div>
    {viewedStep !== diagnostic.step ? <div className="geo-diagnostic-deck__page" tabIndex={0} key={viewedStep}>
      <GeoDiagnosticCardContainer reference={cardReference(viewedStep)} messageId={viewedCard.messageId} paged onResume={() => setSelectedStep(null)} />
    </div> : null}
  </div>
}

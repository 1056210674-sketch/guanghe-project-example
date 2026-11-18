import { ArrowRightOutlined } from '@ant-design/icons'
import type { ChatResourceSelectionView } from '../types'

export interface ChatResourceSelectionCardProps {
  selection: ChatResourceSelectionView
  busy?: boolean
  onSelect?: (candidateSetId: string, choiceId: string) => void
}

/** Read-only resource choice. IO and conversation scope stay in the container. */
export function ChatResourceSelectionCard({ selection, busy, onSelect }: ChatResourceSelectionCardProps) {
  return <section className="chat-resource-selection" aria-label="选择查询对象">
    <div className="chat-resource-selection__choices">
      {selection.candidates.map((candidate) => <button
        key={candidate.choiceId}
        type="button"
        aria-label={`${candidate.name} ${[candidate.kindLabel, candidate.spaceName, candidate.reportLabel].filter(Boolean).join(' · ')}`}
        disabled={!selection.available || busy || !onSelect}
        onClick={() => onSelect?.(selection.candidateSetId, candidate.choiceId)}
      >
        <strong>{candidate.name}</strong>
        <span>{[candidate.kindLabel, candidate.spaceName, candidate.reportLabel].filter(Boolean).join(' · ')}</span>
        <ArrowRightOutlined aria-hidden="true" />
      </button>)}
    </div>
    {!selection.available ? <p>这次选择已失效，请重新说明要查询的对象。</p> : null}
  </section>
}

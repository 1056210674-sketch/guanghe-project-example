import { useId, type ReactNode } from 'react'
import { Drawer } from 'antd'

export function ReadOnlyDetail({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const titleId = useId()
  return <Drawer aria-labelledby={titleId} title={<span id={titleId}>{title}</span>} open size={500} onClose={onClose}>
    <div className="admin-detail-stack admin-observability-detail">{children}</div>
  </Drawer>
}
export function Metadata({ rows }: { rows: readonly (readonly [string, string | undefined])[] }) {
  return <dl className="admin-detail-list">{rows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value || '—'}</dd></div>)}</dl>
}

import { useState } from 'react'

export function useReviewTarget<T extends { id: string }>(records: readonly T[], fingerprintOf: (record: T) => string) {
  const [target, setTarget] = useState<{ id: string; fingerprint: string }>()
  const [conflict, setConflict] = useState(false)
  const record = records.find(item => item.id === target?.id)
  const fingerprint = record ? fingerprintOf(record) : undefined
  // Compare business content, not object identity. Unmounting the detail also
  // discards local notes and any discard dialog, even if the record reappears.
  if (target && target.fingerprint !== fingerprint) {
    setTarget(undefined)
    setConflict(true)
  }
  return {
    record: target && target.fingerprint === fingerprint ? record : undefined,
    fingerprint, conflict,
    open: (item: T) => { setTarget({ id: item.id, fingerprint: fingerprintOf(item) }); setConflict(false) },
    close: () => setTarget(undefined),
  }
}

export interface ReviewFilters { search: string; type: string; status: string; problem: string; start: string; end: string }
export const emptyReviewFilters: ReviewFilters = { search: '', type: 'ALL', status: 'ALL', problem: 'ALL', start: '', end: '' }
export function matchesReviewFilters(item: { id: string; account: string; taskId: string; createdAt: string; status: string }, filters: ReviewFilters) {
  const search = filters.search.trim().toLowerCase()
  return [item.id, item.account, item.taskId].some(value => value.toLowerCase().includes(search)) &&
    (filters.status === 'ALL' || item.status === filters.status) &&
    (!filters.start || item.createdAt.slice(0, 10) >= filters.start) && (!filters.end || item.createdAt.slice(0, 10) <= filters.end)
}

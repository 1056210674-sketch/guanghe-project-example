import type { CanvasBatch, CanvasDocument, CanvasGeneration, CanvasNode, CanvasReference } from '../../api/canvasApi'
import { generatedImageAssetReferences, generatedVideoAssetReference } from '../chat/live/chatAdapters'

export const roleLabels = { PRODUCT: '商品', PERSON: '人物', BACKGROUND: '背景', STYLE: '风格', LOGO: 'Logo', REFERENCE: '参考' } as const
export const terminalStatuses = new Set(['SUCCEEDED', 'PARTIAL', 'FAILED', 'SKIPPED', 'SYSTEM_TERMINATED'])
export function newNode(kind: CanvasNode['kind'], index: number, assetId?: string, title?: string): CanvasNode {
  return { id: crypto.randomUUID(), kind, title: title ?? (kind === 'TEXT' ? '文字便笺' : kind === 'PLAN' ? '创作方案' : kind === 'GROUP' ? '素材组' : '素材'), position: { x: (index % 4) * 300, y: Math.floor(index / 4) * 340 }, text: '', assetIds: assetId ? [assetId] : [], activeAssetId: assetId, role: 'REFERENCE', starred: false }
}
export function selectedReferences(nodes: CanvasNode[], selected: readonly string[]): CanvasReference[] {
  return nodes.filter(n => selected.includes(n.id) && n.activeAssetId).map(n => ({ nodeId: n.id, assetId: n.activeAssetId!, role: n.role ?? 'REFERENCE' }))
}
export function canvasReferenceScope(document: CanvasDocument): Set<string> {
  const ids = new Set(document.referenceNodeIds ?? [])
  const pending = [...ids]
  while (pending.length) {
    const target = pending.pop()!
    const sources = [...document.edges.filter(e => e.target === target).map(e => e.source), ...document.nodes.filter(n => n.parentId === target).map(n => n.id)]
    for (const source of sources) if (!ids.has(source)) { ids.add(source); pending.push(source) }
  }
  return ids
}
export function activeCanvasReferences(document: CanvasDocument): CanvasReference[] {
  return selectedReferences(document.nodes, [...canvasReferenceScope(document)])
}
/** Explicit pairing; never silently interpret different products as views of one product. */
export function expandGenerations(base: Omit<CanvasGeneration, 'key' | 'references'>, references: CanvasReference[], pairing: 'TOGETHER' | 'EACH_PRODUCT', variants: string[]): CanvasGeneration[] {
  const products = references.filter(r => r.role === 'PRODUCT')
  const shared = references.filter(r => r.role !== 'PRODUCT')
  const pairs = pairing === 'EACH_PRODUCT' && products.length ? products.map(r => [r, ...shared]) : [references]
  const directions = variants.map(v => v.trim()).filter(Boolean)
  return pairs.flatMap((refs, i) => (directions.length ? directions : ['']).map((variant, j) => ({ ...base, key: `${i + 1}-${j + 1}`, references: refs, prompt: base.prompt + (variant ? `\n场景：${variant}` : '') })))
}
export function removeNodes(document: CanvasDocument, removed: string[]): CanvasDocument {
  const removedSet = new Set(removed)
  return { ...document, referenceNodeIds: document.referenceNodeIds?.filter(id => !removedSet.has(id)), nodes: document.nodes.filter(n => !removedSet.has(n.id)).map(n => {
    const parent = n.parentId && removedSet.has(n.parentId) ? document.nodes.find(p => p.id === n.parentId) : undefined
    return parent ? { ...n, parentId: null, position: { x: n.position.x + parent.position.x, y: n.position.y + parent.position.y } } : n
  }), edges: document.edges.filter(e => !removedSet.has(e.source) && !removedSet.has(e.target)) }
}
export interface CanvasResult { assetId: string; taskId: string; taskType: string; batchId: string; input: CanvasGeneration }
export function batchResults(batches: CanvasBatch[]): CanvasResult[] {
  const result: CanvasResult[] = []
  for (const batch of batches) for (const item of batch.items) {
    const task = item.task
    if (!task || !terminalStatuses.has(task.status)) continue
    // Use only persisted business assets; supplier URLs never become canvas identities.
    const video = generatedVideoAssetReference(task)
    const candidates = task.taskType === 'IMAGE' ? generatedImageAssetReferences(task) : video ? [video] : []
    for (const candidate of candidates) {
      if (!result.some(r => r.assetId === candidate.assetId))
        result.push({ assetId: candidate.assetId, taskId: task.id, taskType: task.taskType, batchId: batch.id, input: item.input })
    }
  }
  return result
}

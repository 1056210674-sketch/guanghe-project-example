import { Background, Controls, Handle, MiniMap, Position, ReactFlow, type Node as FlowNode, type NodeProps, type ReactFlowInstance, type NodeChange } from '@xyflow/react'
import { useMemo, useRef, useState } from 'react'
import { StarFilled } from '@ant-design/icons'
import type { CanvasDocument, CanvasNode } from '../../api/canvasApi'
import { CanvasMedia } from './CanvasMedia'
import { roleLabels } from './canvasModel'
import '@xyflow/react/dist/style.css'

type CreativeNode = FlowNode<{ value: CanvasNode }, 'creative'>
function MediaNode({ data, selected }: NodeProps<CreativeNode>) {
  const node = data.value
  return <article className={`canvas-node canvas-node--${node.kind.toLowerCase()}${selected ? ' canvas-node--selected' : ''}`}>
    <Handle type="target" position={Position.Left} />
    <header><span>{node.title}</span>{node.starred ? <StarFilled /> : <small>{node.role ? roleLabels[node.role] : ''}</small>}</header>
    {node.kind === 'MEDIA' && node.activeAssetId ? <CanvasMedia assetId={node.activeAssetId} name={node.title} /> : node.kind !== 'GROUP' ? <p>{node.text || (node.kind === 'PLAN' ? '选中后编辑创作方案，再连接参考素材。' : '选中后编辑文字')}</p> : null}
    {(node.assetIds?.length ?? 0) > 1 ? <footer>{node.assetIds!.length} 个版本</footer> : null}
    <Handle type="source" position={Position.Right} />
  </article>
}
const nodeTypes = { creative: MediaNode }
export function CanvasBoard({ document, selected, onSelect, onChange, onRemove }: {
  document: CanvasDocument; selected: string[]; onSelect: (ids: string[]) => void
  onChange: (document: CanvasDocument) => void; onRemove: (ids: string[]) => void
}) {
  const flow = useRef<ReactFlowInstance<CreativeNode> | null>(null)
  const [dragPositions, setDragPositions] = useState<Record<string, { x: number; y: number }>>({})
  const nodes = useMemo<CreativeNode[]>(() => [...document.nodes].sort((a, b) => Number(Boolean(a.parentId)) - Number(Boolean(b.parentId))).map(n => ({
    id: n.id, type: 'creative', position: dragPositions[n.id] ?? n.position, data: { value: n }, selected: selected.includes(n.id),
    ...(n.parentId ? { parentId: n.parentId } : {}), ...(n.kind === 'GROUP' ? { style: { width: 620, height: 420 }, zIndex: -1 } : { style: { width: 260 } }),
  })), [document.nodes, selected, dragPositions])
  const changes = (events: NodeChange<CreativeNode>[]) => {
    const positions = events.filter(e => e.type === 'position' && e.position)
    if (positions.length) setDragPositions(current => { const next = { ...current }; for (const e of positions) if (e.type === 'position' && e.position) next[e.id] = e.position; return next })
    const removed = events.filter(e => e.type === 'remove').map(e => e.id)
    if (removed.length) onRemove(removed)
  }
  return <ReactFlow<CreativeNode> nodes={nodes} edges={document.edges.map(e => ({ ...e, type: 'smoothstep' }))} nodeTypes={nodeTypes}
    defaultViewport={document.viewport} minZoom={0.1} maxZoom={4} onInit={instance => { flow.current = instance }}
    onNodesChange={changes} onSelectionChange={selection => onSelect(selection.nodes.map(n => n.id))}
    onNodeDragStop={(_event, _node, moved) => { const positions = new Map(moved.map(n => [n.id, n.position])); onChange({ ...document, nodes: document.nodes.map(n => positions.has(n.id) ? { ...n, position: positions.get(n.id)! } : n) }); setDragPositions({}) }}
    onMoveEnd={(_event, viewport) => onChange({ ...document, viewport })}
    onConnect={connection => { if (connection.source !== connection.target && !document.edges.some(e => e.source === connection.source && e.target === connection.target)) onChange({ ...document, edges: [...document.edges, { id: crypto.randomUUID(), source: connection.source, target: connection.target }] }) }}
    onEdgesDelete={removed => onChange({ ...document, edges: document.edges.filter(e => !removed.some(r => r.id === e.id)) })}
    deleteKeyCode={['Backspace', 'Delete']} multiSelectionKeyCode={['Control', 'Meta']} selectionOnDrag panOnScroll panOnDrag={[1, 2]} zoomOnDoubleClick={false} onlyRenderVisibleElements>
    <Background color="#d8d9e0" gap={24} size={1} /><Controls showInteractive={false} /><MiniMap pannable zoomable nodeColor="#dcdaef" />
  </ReactFlow>
}

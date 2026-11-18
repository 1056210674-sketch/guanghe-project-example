import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Alert, App, Button, Checkbox, Drawer, Empty, Input, InputNumber, Modal, Segmented, Select, Spin, Switch, Tag, Tooltip } from 'antd'
import { ArrowLeftOutlined, BorderOuterOutlined, CopyOutlined, DeleteOutlined, DownloadOutlined, FileTextOutlined, GroupOutlined, PictureOutlined, PlusOutlined, RedoOutlined, StarFilled, StarOutlined, UndoOutlined, UploadOutlined } from '@ant-design/icons'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { createAssetDownloadUrl, getAsset, listAssets, uploadImageAsset } from '../../api/assetApi'
import { confirmCanvasBatch, getCanvas, listCanvasBatches, optimizeCanvasPrompt, quoteCanvasBatch, type CanvasBatch, type CanvasDocument, type CanvasGeneration, type CanvasNode, type CanvasView } from '../../api/canvasApi'
import { CanvasBoard } from '../../features/canvas/CanvasBoard'
import { CanvasMedia } from '../../features/canvas/CanvasMedia'
import { activeCanvasReferences, batchResults, canvasReferenceScope, expandGenerations, newNode, removeNodes, roleLabels, terminalStatuses } from '../../features/canvas/canvasModel'
import { canvasError, useCanvasDocument } from '../../features/canvas/useCanvasDocument'
import { RegionMaskPainter } from '../../features/creative-modes/RegionMaskPainter'
import { useAssetDetailsState, useAssetDownloadUrls } from '../../features/assets/live/assetQueries'
import { openExternalHttps } from '../../features/shared/openExternalHttps'
import { UserShell } from '../../layouts/UserShell'
import { APP_PATHS } from '../../routes/paths'
import { useAuthStore } from '../../stores/authStore'
import { getCanvasCapabilities } from '../../api/canvasApi'
import { downloadCanvasAssets } from '../../api/canvasApi'
import '../../features/canvas/canvas.css'

const defaults = { taskType: 'IMAGE', count: 1, durationSeconds: 5, aspectRatio: '1:1', resolution: '720P', pairing: 'TOGETHER', variants: '', originalPrompt: '', autoOptimize: true } as const

export function CanvasWorkspacePage() {
  const { canvasId = '' } = useParams()
  const owner = useAuthStore(s => s.user?.id)
  const canvas = useQuery({ queryKey: ['canvas', owner, canvasId], queryFn: () => getCanvas(canvasId), enabled: Boolean(canvasId), refetchOnWindowFocus: false })
  return <UserShell>{canvas.isPending ? <Spin className="canvas-page-loading" /> : canvas.isError ? <div className="canvas-page-loading"><p>{canvasError(canvas.error)}</p><Button onClick={() => void canvas.refetch()}>重新加载</Button></div> : <CanvasWorkspace key={`${owner}:${canvasId}`} initial={canvas.data} ownerId={owner!} />}</UserShell>
}
export function CanvasWorkspace({ initial, ownerId }: { initial: CanvasView; ownerId: string }) {
  const edit = useCanvasDocument(initial, ownerId)
  const navigate = useNavigate()
  const { message } = App.useApp()
  const selected = edit.document.referenceNodeIds ?? []
  const setSelected = (next: string[] | ((ids: string[]) => string[])) => {
    const current = edit.read(); const previous = current.document.referenceNodeIds ?? []
    const ids = typeof next === 'function' ? next(previous) : next
    if (ids.length === previous.length && ids.every(id => previous.includes(id))) return
    edit.update({ ...current, document: { ...current.document, referenceNodeIds: ids } }, false)
  }
  const [view, setView] = useState<'画布' | '作品'>('画布')
  const [filter, setFilter] = useState<'全部' | '入选'>('全部')
  const [busy, setBusy] = useState(false)
  const [optimizing, setOptimizing] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string>()
  const [quote, setQuote] = useState<CanvasBatch>()
  const [picker, setPicker] = useState(false)
  const [assetPage, setAssetPage] = useState(1)
  const [assetType, setAssetType] = useState<'IMAGE' | 'VIDEO'>('IMAGE')
  const [masking, setMasking] = useState(false)
  const [maskFile, setMaskFile] = useState<File>()
  const [historyOpen, setHistoryOpen] = useState(false)
  const actionLock = useRef(false)
  const capabilities = useQuery({ queryKey: ['canvas-capabilities', ownerId], queryFn: getCanvasCapabilities })
  const upload = useRef<HTMLInputElement>(null)
  const quoteAttempt = useRef<{ signature: string; key: string } | null>(null)
  const batches = useInfiniteQuery({ queryKey: ['canvas-batches', ownerId, initial.id], initialPageParam: 1, queryFn: ({ pageParam }) => listCanvasBatches(initial.id, pageParam), getNextPageParam: (page, _pages, previous) => page.length === 20 ? previous + 1 : undefined, refetchInterval: query => query.state.data?.pages.flat().some(b => b.items.some(i => i.task && !terminalStatuses.has(i.task.status))) ? 5000 : false })
  const batchList = batches.data?.pages.flat() ?? []
  const results = useMemo(() => batchResults(batches.data?.pages.flat() ?? []), [batches.data])
  const selectedNode = selected.length === 1 ? edit.document.nodes.find(n => n.id === selected[0]) : undefined
  const selectedAsset = useAssetDetailsState(selectedNode?.activeAssetId ? [selectedNode.activeAssetId] : [])
  const selectedIsImage = selectedNode?.activeAssetId && selectedAsset.data[selectedNode.activeAssetId]?.assetType === 'IMAGE'
  const settings = edit.document.settings ?? defaults
  const mask = 'maskAssetId' in settings && settings.maskAssetId && settings.maskBaseAssetId ? { base: settings.maskBaseAssetId, id: settings.maskAssetId } : undefined
  const prompt = edit.document.prompt ?? ''
  const refs = useMemo(() => activeCanvasReferences(edit.document), [edit.document])
  const removeReference = (nodeId: string) => {
    const current = edit.read(); const scope = canvasReferenceScope(current.document)
    edit.update({ ...current, document: { ...current.document,
      referenceNodeIds: activeCanvasReferences(current.document).filter(r => r.nodeId !== nodeId).map(r => r.nodeId),
      edges: current.document.edges.filter(e => !(e.source === nodeId && scope.has(e.target))) } })
  }
  const selectedUrls = useAssetDownloadUrls(refs.map(r => r.assetId))
  const assetList = useQuery({ queryKey: ['canvas-picker', ownerId, initial.spaceId, assetType, assetPage], queryFn: () => listAssets({ spaceId: initial.spaceId, type: assetType, page: assetPage, size: 12 }), enabled: picker })
  const active = batchList.flatMap(b => b.items).filter(i => i.task && !terminalStatuses.has(i.task.status)).length
  const visibleQuote = quote
  const quoteExpired = visibleQuote?.items.some(i => i.draft && Date.parse(i.draft.expiresAt) <= Date.now()) ?? false
  const mutate = useCallback((document: CanvasDocument) => edit.update({ ...edit.read(), document }), [edit])
  const patchNode = (id: string, patch: Partial<CanvasNode>) => { const current = edit.read(); edit.update({ ...current, document: { ...current.document, nodes: current.document.nodes.map(n => n.id === id ? { ...n, ...patch } : n) } }) }
  const patchSettings = (patch: Partial<NonNullable<CanvasDocument['settings']>>) => { const current = edit.read(); edit.update({ ...current, document: { ...current.document, settings: { ...(current.document.settings ?? defaults), ...patch } } }) }
  const setPrompt = (text: string) => { const current = edit.read(); edit.update({ ...current, document: { ...current.document, prompt: text, settings: { ...(current.document.settings ?? defaults), originalPrompt: '' } } }) }
  const add = (nodes: CanvasNode[]) => { const current = edit.read(); if (current.document.nodes.length + nodes.length > 200) throw new Error('本画布已达到 200 个节点，请新建画布继续。'); edit.update({ ...current, document: { ...current.document, nodes: [...current.document.nodes, ...nodes] } }); setSelected(nodes.map(n => n.id)) }
  const remove = (ids: string[]) => { const current = edit.read(); edit.update({ ...current, document: removeNodes(current.document, ids) }); setSelected([]) }
  useEffect(() => {
    const current = edit.read()
    const placed = current.document.placedResultAssetIds ?? []
    const fresh = results.filter(r => !placed.includes(r.assetId))
    if (!fresh.length) return
    const available = 200 - current.document.nodes.length
    const nodes = fresh.slice(0, available).map((r, i) => newNode('MEDIA', current.document.nodes.length + i, r.assetId, r.taskType === 'VIDEO' ? '生成视频' : '生成图片'))
    edit.update({ ...current, document: { ...current.document, nodes: [...current.document.nodes, ...nodes], placedResultAssetIds: [...placed, ...fresh.map(r => r.assetId)].slice(-2000) } }, false)
  }, [results, edit])
  const addAssets = async (ids: string[]) => {
    try {
      const assets = await Promise.all(ids.map(getAsset))
      add(assets.map((asset, i) => newNode('MEDIA', edit.read().document.nodes.length + i, asset.id, asset.name)))
      setPicker(false)
    } catch (failure) { setError(canvasError(failure)) }
  }
  const uploadFiles = async (files: File[]) => {
    if (uploading) return
    setUploading(true); setError(undefined)
    try {
      if (files.length > 20) throw new Error('每次最多上传 20 张图片。')
      for (const file of files) {
        const asset = await uploadImageAsset({ spaceId: initial.spaceId, file, idempotencyKey: crypto.randomUUID() })
        add([newNode('MEDIA', edit.read().document.nodes.length, asset.id, file.name)])
      }
    } catch (failure) { setError(canvasError(failure)) } finally { setUploading(false); if (upload.current) upload.current.value = '' }
  }
  const signature = () => JSON.stringify({ prompt: edit.read().document.prompt, settings: edit.read().document.settings, refs: activeCanvasReferences(edit.read().document) })
  const optimize = async () => {
    if (actionLock.current || optimizing || !prompt.trim()) return
    actionLock.current = true
    setOptimizing(true); setError(undefined)
    const before = signature()
    try {
      if (refs.length > 3) throw new Error('先选择最多 3 张参考图片。')
      if (prompt.length > 600) throw new Error('描述超过 600 字，可直接使用当前描述生成，或缩短后优化。')
      await edit.flush()
      const result = await optimizeCanvasPrompt(initial.id, { taskType: settings.taskType!, originalPrompt: prompt, references: refs })
      if (signature() !== before) return
      const current = edit.read(); edit.update({ ...current, document: { ...current.document, prompt: result.prompt, settings: { ...(current.document.settings ?? defaults), originalPrompt: result.originalPrompt } } })
    } catch (failure) { setError(canvasError(failure)) } finally { actionLock.current = false; setOptimizing(false) }
  }
  const prepare = async (retryItems?: CanvasGeneration[]) => {
    if (actionLock.current) return
    actionLock.current = true; setBusy(true); setError(undefined)
    const before = signature()
    try {
      let items = retryItems
      if (!capabilities.data) throw new Error('创作能力尚未加载，请稍后重试。')
      if (items) {
        const current = edit.read(); const nodes = [...current.document.nodes]
        items = items.map(item => ({ ...item, references: item.references.map(reference => {
          const existing = nodes.find(n => n.id === reference.nodeId && n.assetIds?.includes(reference.assetId))
          if (existing) return reference
          const restored = { ...newNode('MEDIA', nodes.length, reference.assetId, '原制作素材'), role: reference.role }
          nodes.push(restored); return { ...reference, nodeId: restored.id }
        }) }))
        if (nodes.length > 200) throw new Error('画布已满，请移除部分节点后恢复原制作素材。')
        if (nodes.length !== current.document.nodes.length) edit.update({ ...current, document: { ...current.document, nodes } })
      }
      if (!items) {
        if (!prompt.trim()) throw new Error('先写一句你想制作的内容。')
        const original = settings.originalPrompt || prompt
        if (original.length > 600) throw new Error('请把原始描述控制在 600 字内。')
        let finalPrompt = prompt
        await edit.flush()
        if (settings.autoOptimize && !settings.originalPrompt) finalPrompt = (await optimizeCanvasPrompt(initial.id, { taskType: settings.taskType!, originalPrompt: prompt, references: refs.length <= 3 ? refs : refs.filter(r => r.role !== 'PRODUCT').slice(0, 3) })).prompt
        if (before !== signature()) throw new Error('制作要求已修改，请按新内容重新预览。')
        items = expandGenerations({ taskType: settings.taskType!, originalPrompt: original, prompt: finalPrompt, aspectRatio: settings.aspectRatio!, count: settings.taskType === 'VIDEO' ? 1 : settings.count, durationSeconds: settings.durationSeconds, resolution: settings.resolution, maskAssetId: mask && refs.length === 1 && refs[0].assetId === mask.base ? mask.id : undefined }, refs, settings.pairing!, (settings.variants ?? '').split('\n'))
      }
      if (items.length > 20) throw new Error('每批最多 20 项制作，可拆成多批提交。')
      if (items.some(i => i.references.length > 3)) throw new Error('每项最多 3 张参考图；多商品请使用“每件商品分别制作”。')
      if (items.some(i => i.taskType === 'IMAGE' ? !capabilities.data.image || i.references.length > 0 && !capabilities.data.referenceImages : !capabilities.data.video || i.references.length > 0 && !capabilities.data.imageToVideo)) throw new Error('当前设置未开放这项创作能力，请更换制作类型或素材。')
      if (items.some(i => i.taskType === 'VIDEO' && i.references.length > 1)) throw new Error('当前视频支持一张首帧，请选一张图片生成视频。')
      if (items.some(i => i.prompt.length > 800)) throw new Error('优化描述和场景合计超过 800 字，请缩短后重试。')
      const current = await edit.flush()
      const payload = { version: current!.version, items }
      const inputSignature = JSON.stringify(payload)
      if (quoteAttempt.current?.signature !== inputSignature) quoteAttempt.current = { signature: inputSignature, key: crypto.randomUUID() }
      const result = await quoteCanvasBatch(initial.id, payload, quoteAttempt.current!.key)
      setQuote(result); void batches.refetch()
    } catch (failure) { setError(canvasError(failure)) } finally { actionLock.current = false; setBusy(false) }
  }
  const confirm = async () => {
    if (!visibleQuote || actionLock.current) return
    actionLock.current = true; setConfirming(true); setError(undefined)
    try { await confirmCanvasBatch(initial.id, visibleQuote.id, visibleQuote.quotedPoints); setQuote(undefined); quoteAttempt.current = null; await batches.refetch(); void message.success('已开始制作，可以继续编辑画布。') }
    catch (failure) { setError(canvasError(failure)); await batches.refetch() }
    finally { actionLock.current = false; setConfirming(false) }
  }
  const group = () => {
    const current = edit.read(); const chosen = current.document.nodes.filter(n => selected.includes(n.id) && !n.parentId && n.kind !== 'GROUP')
    if (!chosen.length) return
    const x = Math.min(...chosen.map(n => n.position.x)) - 30, y = Math.min(...chosen.map(n => n.position.y)) - 65
    const node = { ...newNode('GROUP', 0), position: { x, y } }
    edit.update({ ...current, document: { ...current.document, nodes: [node, ...current.document.nodes.map(n => chosen.some(c => c.id === n.id) ? { ...n, parentId: node.id, position: { x: n.position.x - x, y: n.position.y - y } } : n)] } })
    setSelected([node.id])
  }
  const mergeVersions = async () => {
    const current = edit.read(); const chosen = current.document.nodes.filter(n => selected.includes(n.id) && n.kind === 'MEDIA')
    const ids = [...new Set(chosen.flatMap(n => n.assetIds ?? []))]
    if (chosen.length < 2 || !ids.length) return
    try {
      if (ids.length > 20) throw new Error('一个作品最多收纳 20 个版本。')
      const types = await Promise.all(ids.map(getAsset))
      if (new Set(types.map(a => a.assetType)).size > 1) throw new Error('图片和视频请分别收纳版本。')
      // Editing while metadata is loading must not be overwritten by this older selection.
      if (current !== edit.read()) throw new Error('画布已修改，请重新选择作品后收纳。')
      const document = removeNodes(current.document, chosen.slice(1).map(n => n.id))
      edit.update({ ...current, document: { ...document, nodes: document.nodes.map(n => n.id === chosen[0].id ? { ...n, assetIds: ids } : n) } })
      setSelected([chosen[0].id])
    } catch (failure) { setError(canvasError(failure)) }
  }
  const exportDraft = () => { const blob = new Blob([JSON.stringify(edit.read(), null, 2)], { type: 'application/json' }); const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = `${edit.name}.json`; a.click(); URL.revokeObjectURL(url) }
  const download = async (ids: string[]) => {
    try {
      if (ids.length === 1) { openExternalHttps((await createAssetDownloadUrl(ids[0])).url); return }
      const blob = await downloadCanvasAssets(initial.id, ids)
      const url = URL.createObjectURL(blob); const anchor = document.createElement('a')
      anchor.href = url; anchor.download = edit.name + '.zip'; anchor.click()
      setTimeout(() => URL.revokeObjectURL(url), 30_000)
    } catch (failure) { setError(canvasError(failure)) }
  }
  return <main className="canvas-workspace" onDragOver={e => { if (e.dataTransfer.types.includes('Files')) e.preventDefault() }} onDrop={e => { if (e.dataTransfer.files.length) { e.preventDefault(); void uploadFiles([...e.dataTransfer.files]) } }} onPaste={e => { if (e.clipboardData.files.length) { e.preventDefault(); void uploadFiles([...e.clipboardData.files]) } }}>
    <header className="canvas-topbar"><Button type="text" icon={<ArrowLeftOutlined />} aria-label="返回画布列表" onClick={() => void edit.flush().then(() => navigate(APP_PATHS.canvases)).catch(f => setError(canvasError(f)))} /><Input aria-label="画布名称" value={edit.name} maxLength={100} onChange={e => edit.update({ ...edit.read(), name: e.target.value })} variant="borderless" /><span className="canvas-save-status">{edit.status}</span>{selected.length > 1 ? <Button onClick={() => void mergeVersions()}>收为版本</Button> : null}<Segmented options={['画布', '作品']} value={view} onChange={v => setView(v as typeof view)} /><Button onClick={() => setHistoryOpen(true)}>{active ? `${active} 项制作中` : '制作记录'}</Button><Tooltip title="撤销编辑"><Button icon={<UndoOutlined />} aria-label="撤销" onClick={edit.undo} /></Tooltip><Tooltip title="重做编辑"><Button icon={<RedoOutlined />} aria-label="重做" onClick={edit.redo} /></Tooltip></header>
    {(error || edit.error || batches.isError) ? <Alert closable title={error ?? edit.error ?? '制作记录暂时无法获取'} type="error" onClose={() => setError(undefined)} action={edit.conflict ? <Button onClick={exportDraft}>下载当前草稿</Button> : <Button onClick={() => { void edit.flush().catch(() => {}); void batches.refetch() }}>重试读取/保存</Button>} /> : null}
    {edit.recovery ? <div className="canvas-recovery"><Button onClick={edit.recover}>恢复上次未保存的编辑</Button></div> : null}
    <div className="canvas-stage">
      <aside className="canvas-tools" aria-label="画布工具"><Tooltip title="上传图片" placement="right"><Button icon={<UploadOutlined />} loading={uploading} onClick={() => upload.current?.click()} aria-label="上传图片" /></Tooltip><Tooltip title="从资产中心添加" placement="right"><Button icon={<PictureOutlined />} onClick={() => setPicker(true)} aria-label="添加资产" /></Tooltip><Tooltip title="文字便笺" placement="right"><Button icon={<FileTextOutlined />} onClick={() => add([newNode('TEXT', edit.document.nodes.length)])} aria-label="添加文字" /></Tooltip><Tooltip title="创作方案" placement="right"><Button icon={<PlusOutlined />} onClick={() => add([newNode('PLAN', edit.document.nodes.length)])} aria-label="添加方案" /></Tooltip><Tooltip title="分组" placement="right"><Button icon={<GroupOutlined />} disabled={!selected.length} onClick={group} aria-label="素材分组" /></Tooltip><Tooltip title="自动排列" placement="right"><Button icon={<BorderOuterOutlined />} onClick={() => mutate({ ...edit.document, nodes: edit.document.nodes.map((n, i) => ({ ...n, parentId: null, position: { x: i % 4 * 300, y: Math.floor(i / 4) * 340 } })) })} aria-label="自动排列" /></Tooltip><Button icon={<DeleteOutlined />} aria-label="移除所选节点" disabled={!selected.length} onClick={() => remove(selected)} /></aside>
      <input ref={upload} hidden type="file" multiple accept="image/jpeg,image/png" onChange={e => void uploadFiles([...e.target.files ?? []])} />
      {view === '画布' ? <CanvasBoard document={edit.document} selected={selected} onSelect={setSelected} onChange={mutate} onRemove={remove} /> : <section className="canvas-result-grid"><header><h2>作品与素材</h2><Segmented value={filter} options={['全部', '入选']} onChange={v => setFilter(v as typeof filter)} /></header>{edit.document.nodes.filter(n => n.kind === 'MEDIA' && n.activeAssetId && (filter === '全部' || n.starred)).map(n => <article key={n.id} className={selected.includes(n.id) ? 'is-selected' : ''}><Checkbox checked={selected.includes(n.id)} onChange={e => setSelected(ids => e.target.checked ? [...ids, n.id] : ids.filter(id => id !== n.id))}>{n.title}</Checkbox><CanvasMedia assetId={n.activeAssetId!} name={n.title} download /><Button type="text" icon={n.starred ? <StarFilled /> : <StarOutlined />} onClick={() => patchNode(n.id, { starred: !n.starred })}>{n.starred ? '已入选' : '入选'}</Button></article>)}{!edit.document.nodes.some(n => n.activeAssetId) ? <Empty description="上传素材，或生成第一份作品" /> : null}</section>}
      {selectedNode ? <aside className="canvas-inspector"><Input aria-label="节点名称" value={selectedNode.title} maxLength={100} onChange={e => patchNode(selectedNode.id, { title: e.target.value })} />{selectedNode.kind !== 'MEDIA' && selectedNode.kind !== 'GROUP' ? <><Input.TextArea aria-label="节点内容" value={selectedNode.text ?? ''} autoSize={{ minRows: 5, maxRows: 14 }} maxLength={6000} onChange={e => patchNode(selectedNode.id, { text: e.target.value })} /><Button onClick={() => setPrompt((selectedNode.text ?? '').slice(0, 600))}>用于创作描述</Button></> : null}
        {selectedNode.kind === 'MEDIA' ? <><label>素材用途<Select value={selectedNode.role ?? 'REFERENCE'} options={Object.entries(roleLabels).map(([value, label]) => ({ value, label }))} onChange={role => patchNode(selectedNode.id, { role })} /></label><label>当前版本<Select value={selectedNode.activeAssetId ?? undefined} options={selectedNode.assetIds?.map((value, i) => ({ value, label: `版本 ${i + 1}` }))} onChange={activeAssetId => patchNode(selectedNode.id, { activeAssetId })} /></label><Button onClick={() => patchNode(selectedNode.id, { starred: !selectedNode.starred })} icon={selectedNode.starred ? <StarFilled /> : <StarOutlined />}>{selectedNode.starred ? '已入选' : '标记入选'}</Button><Button icon={<DownloadOutlined />} onClick={() => void download([selectedNode.activeAssetId!])}>下载原文件</Button><Button disabled={!capabilities.data?.imageToVideo || !selectedIsImage} onClick={() => { patchSettings({ taskType: 'VIDEO' }); setPrompt('让这张图动起来，保留主体外观，使用自然的轻微运动和缓慢运镜。') }}>由这张图生成视频</Button>{capabilities.data?.regionEdit && selectedIsImage ? <Button onClick={() => { setMaskFile(undefined); setMasking(true) }}>局部修改</Button> : null}</> : null}
        {selectedNode.parentId ? <Button onClick={() => { const parent = edit.document.nodes.find(n => n.id === selectedNode.parentId); patchNode(selectedNode.id, { parentId: null, position: { x: selectedNode.position.x + (parent?.position.x ?? 0), y: selectedNode.position.y + (parent?.position.y ?? 0) } }) }}>移出分组</Button> : null}
        {selectedNode.activeAssetId && (selectedNode.assetIds?.length ?? 0) > 1 ? <Button onClick={() => add([newNode('MEDIA', edit.document.nodes.length, selectedNode.activeAssetId!, selectedNode.title + ' · 版本对比')])}>展开当前版本</Button> : null}<Button icon={<CopyOutlined />} onClick={() => add([{ ...selectedNode, id: crypto.randomUUID(), parentId: null, position: { x: selectedNode.position.x + 290, y: selectedNode.position.y + 40 } }])}>复制节点</Button>
      </aside> : null}
      {!edit.document.nodes.length ? <div className="canvas-empty"><span>YOUR NEXT CREATION</span><h1>从一个想法，展开无限可能</h1><p>拖入图片，放入文字，把素材连接成你的创作方案。</p><Button type="primary" icon={<UploadOutlined />} onClick={() => upload.current?.click()}>添加第一张素材</Button></div> : null}
      <section className="canvas-composer">
        <div className="canvas-reference-strip">{refs.map((r, i) => <Tag key={r.assetId} closable onClose={() => removeReference(r.nodeId)}>{i + 1} · {roleLabels[r.role]}</Tag>)}{refs.length ? <small>已引用 {refs.length} 张</small> : <small>选中素材作为参考，或直接描述画面</small>}</div>
        <Input.TextArea value={prompt} onChange={e => setPrompt(e.target.value)} placeholder="例如：让模特戴上这顶帽子，拍一组高级感商品图" autoSize={{ minRows: 2, maxRows: 5 }} maxLength={800} aria-label="创作描述" />
        <div className="canvas-composer-actions"><Select aria-label="制作类型" value={settings.taskType} options={[{ value: 'IMAGE', label: '图片', disabled: !capabilities.data?.image }, { value: 'VIDEO', label: '视频', disabled: !capabilities.data?.video }]} onChange={taskType => patchSettings({ taskType })} /><Select aria-label="画面比例" value={settings.aspectRatio} options={['1:1', '3:4', '4:3', '9:16', '16:9'].map(value => ({ value }))} onChange={aspectRatio => patchSettings({ aspectRatio })} />{settings.taskType === 'IMAGE' ? <InputNumber aria-label="每项图片数" min={1} max={4} value={settings.count} onChange={v => patchSettings({ count: v ?? 1 })} suffix="张" /> : <><Select aria-label="视频时长" value={settings.durationSeconds} options={[5, 10, 15].map(value => ({ value, label: `${value}秒` }))} onChange={durationSeconds => patchSettings({ durationSeconds })} /><Select aria-label="视频分辨率" value={settings.resolution} options={['480P', '720P', '1080P'].map(value => ({ value }))} onChange={resolution => patchSettings({ resolution })} /></>}<Button type="text" loading={optimizing} disabled={busy || !prompt.trim()} onClick={() => void optimize()}>优化描述</Button>{settings.originalPrompt ? <Button type="text" onClick={() => setPrompt(settings.originalPrompt!)}>恢复原话</Button> : null}<Button type="primary" loading={busy} disabled={optimizing || edit.conflict || !capabilities.data} onClick={() => void prepare()}>预览制作</Button></div>
        <details><summary>批量与更多设置</summary><div className="canvas-batch-settings"><Select aria-label="素材配对" value={settings.pairing} options={[{ value: 'TOGETHER', label: '一起用于同一画面' }, { value: 'EACH_PRODUCT', label: '每件商品分别制作' }]} onChange={pairing => patchSettings({ pairing })} /><label>场景变体<Input.TextArea aria-label="场景变体" value={settings.variants ?? ''} maxLength={200} autoSize={{ minRows: 1, maxRows: 3 }} placeholder="每行一个，例如：简洁棚拍 / 户外街景" onChange={e => patchSettings({ variants: e.target.value })} /></label><label><Switch size="small" checked={settings.autoOptimize} onChange={autoOptimize => patchSettings({ autoOptimize })} /> 生成前优化描述</label>{mask ? <Tag closable onClose={() => patchSettings({ maskAssetId: null, maskBaseAssetId: null })}>已选局部修改蒙版</Tag> : null}<Button disabled={!refs.length} onClick={() => void download(refs.map(r => r.assetId))}>下载所选</Button></div></details>
      </section>
    </div>
    <Drawer destroyOnHidden title="从资产中心添加" open={picker} onClose={() => setPicker(false)} size={440}><Segmented value={assetType} options={[{ label: '图片', value: 'IMAGE' }, { label: '视频', value: 'VIDEO' }]} onChange={v => { setAssetType(v as typeof assetType); setAssetPage(1) }} /><div className="canvas-picker-grid">{assetList.isLoading ? <Spin /> : assetList.isError ? <Button onClick={() => void assetList.refetch()}>重新加载</Button> : assetList.data?.items.filter(a => a.status === 'READY').map(a => <article key={a.id}><CanvasMedia assetId={a.id} name={a.name} /><Button onClick={() => void addAssets([a.id])}>{a.name} · 添加</Button></article>)}</div><div className="canvas-pagination"><Button disabled={assetPage === 1} onClick={() => setAssetPage(p => p - 1)}>上一页</Button><Button disabled={!assetList.data?.hasNext} onClick={() => setAssetPage(p => p + 1)}>下一页</Button></div></Drawer>
    <Drawer destroyOnHidden title="制作记录" open={historyOpen} onClose={() => setHistoryOpen(false)} size={480}>{batchList.map(b => <section className="canvas-batch-record" key={b.id}><header>{new Date(b.createdAt).toLocaleString('zh-CN')} · {b.quotedPoints} 积分 {b.status === 'QUOTED' ? <Button size="small" onClick={() => setQuote(b)}>查看报价</Button> : null}</header>{b.items.map(i => <article key={i.input.key}><strong>{i.input.taskType === 'IMAGE' ? '图片' : '视频'} · {i.input.key}</strong><p>{i.input.originalPrompt}</p><small>{i.task ? `${i.task.stageLabel} ${i.task.progress?.percent ?? 0}%` : '尚未提交'}</small>{i.task?.errorCode ? <p className="canvas-error">{i.task.errorCode}</p> : null}{i.task && ['FAILED', 'SKIPPED', 'PARTIAL'].includes(i.task.status) && !i.task.errorCode?.includes('UNKNOWN') && !i.task.errorCode?.includes('CONFIRMATION_FAILED') ? <Button size="small" loading={busy} onClick={() => void prepare([{ ...i.input, key: crypto.randomUUID(), count: i.task && 'failedCount' in i.task.result ? Number(i.task.result.failedCount) || 1 : i.input.count }])}>重试此项</Button> : null}<div className="canvas-reference-strip">{i.input.references.map(r => <Tag key={r.assetId}>{roleLabels[r.role]} #{r.assetId}</Tag>)}</div></article>)}</section>)}{results.filter(r => !edit.document.nodes.some(n => n.assetIds?.includes(r.assetId))).map(r => <Button key={r.assetId} onClick={() => add([newNode('MEDIA', edit.document.nodes.length, r.assetId, r.taskType === 'VIDEO' ? '生成视频' : '生成图片')])}>将结果放回画布 #{r.assetId}</Button>)}{batches.hasNextPage ? <Button onClick={() => void batches.fetchNextPage()}>更早记录</Button> : null}</Drawer>
    {visibleQuote ? <Modal destroyOnHidden title="确认制作" open={Boolean(visibleQuote)} onCancel={() => setQuote(undefined)} onOk={() => void confirm()} confirmLoading={confirming} okText={`开始制作 · ${visibleQuote?.quotedPoints ?? 0} 积分`} okButtonProps={{ disabled: visibleQuote?.status === 'SUBMITTED' || quoteExpired }}><div className="canvas-quote-list">{visibleQuote?.items.map(i => <article key={i.input.key}><strong>{i.input.key} · {i.input.taskType === 'IMAGE' ? `${i.input.count} 张图片` : `${i.input.durationSeconds} 秒视频`}</strong><p>{i.input.prompt}</p><span>{i.input.references.map(r => roleLabels[r.role]).join(' + ') || '文字创作'} · {i.input.aspectRatio} · {i.draft?.quotedPoints ?? i.task?.quotedPoints} 积分</span></article>)}</div>{quoteExpired ? <p>报价已过期。<Button type="link" loading={busy} onClick={() => void prepare(visibleQuote.items.map(i => i.input))}>重新报价</Button></p> : null}{error ? <p role="alert" className="canvas-error">{error}</p> : null}</Modal> : null}
    <Modal destroyOnHidden title="局部修改" open={masking} onCancel={() => setMasking(false)} okText="使用蒙版" okButtonProps={{ disabled: !maskFile || uploading }} confirmLoading={uploading} onOk={() => { if (!maskFile || !selectedNode?.activeAssetId) return; setUploading(true); const base = selectedNode.activeAssetId; void uploadImageAsset({ spaceId: initial.spaceId, file: maskFile, idempotencyKey: crypto.randomUUID(), name: '画布局部修改蒙版' }).then(asset => { patchSettings({ taskType: 'IMAGE', maskAssetId: asset.id, maskBaseAssetId: base }); setMasking(false) }).catch(f => setError(canvasError(f))).finally(() => setUploading(false)) }}>{refs.length === 1 && selectedUrls[refs[0].assetId] ? <RegionMaskPainter imageUrl={selectedUrls[refs[0].assetId]!.url} onChange={setMaskFile} /> : <p>请先选择一张图片底图。</p>}</Modal>
  </main>
}

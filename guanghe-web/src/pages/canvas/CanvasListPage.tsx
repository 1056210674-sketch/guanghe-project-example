import { useQuery } from '@tanstack/react-query'
import { App, Button, Empty, Input, Modal, Select, Spin } from 'antd'
import { ArrowRightOutlined, CopyOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { createCanvas, deleteCanvas, duplicateCanvas, listCanvases } from '../../api/canvasApi'
import { listProjects } from '../../api/agentPlatformApi'
import { useAuthStore } from '../../stores/authStore'
import { UserShell } from '../../layouts/UserShell'
import { APP_PATHS } from '../../routes/paths'
import { canvasError } from '../../features/canvas/useCanvasDocument'
import '../../features/canvas/canvas.css'

export function CanvasListPage() {
  const owner = useAuthStore(s => s.user?.id)
  const navigate = useNavigate()
  const { modal } = App.useApp()
  const [page, setPage] = useState(1)
  const [search, setSearch] = useState('')
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('未命名画布')
  const [projectId, setProjectId] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const list = useQuery({ queryKey: ['canvases', owner, page], queryFn: () => listCanvases(page) })
  const projects = useQuery({ queryKey: ['canvas-projects', owner], queryFn: listProjects })
  const create = async () => {
    if (busy || !name.trim()) return
    setBusy(true); setError(undefined)
    try { const result = await createCanvas({ name: name.trim(), projectId }); navigate(`${APP_PATHS.canvases}/${result.id}`) }
    catch (failure) { setError(canvasError(failure)) } finally { setBusy(false) }
  }
  const copy = async (id: string) => {
    setError(undefined)
    try { const result = await duplicateCanvas(id); navigate(`${APP_PATHS.canvases}/${result.id}`) } catch (failure) { setError(canvasError(failure)) }
  }
  return <UserShell><main className="canvas-library">
    <header><div><span className="canvas-eyebrow">CREATIVE STUDIO</span><h1>创作画布</h1><p>把素材放在一起，让想法成为一组作品。</p></div><Button type="primary" icon={<PlusOutlined />} size="large" onClick={() => setOpen(true)}>新建画布</Button></header>
    <Input.Search value={search} onChange={e => setSearch(e.target.value)} placeholder="查找本页画布" allowClear style={{ maxWidth: 300 }} />
    {error ? <p role="alert" className="canvas-error">{error}</p> : null}
    {list.isPending ? <Spin /> : list.isError ? <Button onClick={() => void list.refetch()}>重新加载画布</Button> : <div className="canvas-library-grid">
      {list.data.filter(item => item.name.toLowerCase().includes(search.trim().toLowerCase())).map((item, index) => <article key={item.id} className="canvas-cover">
        <button className={`canvas-cover-art canvas-cover-art--${index % 3}`} onClick={() => navigate(`${APP_PATHS.canvases}/${item.id}`)} aria-label={`打开 ${item.name}`}><span className="canvas-cover-tile" /><span className="canvas-cover-tile" /><ArrowRightOutlined /></button>
        <div><h2><button onClick={() => navigate(`${APP_PATHS.canvases}/${item.id}`)}>{item.name}</button></h2><p>{item.projectId ? projects.data?.find(p => p.id === item.projectId)?.name ?? '客户项目' : '个人创作'} · {new Date(item.updatedAt).toLocaleDateString('zh-CN')}</p><Button type="text" icon={<CopyOutlined />} aria-label={`复制 ${item.name}`} onClick={() => void copy(item.id)} /><Button type="text" icon={<DeleteOutlined />} aria-label={`删除 ${item.name}`} onClick={() => modal.confirm({ title: `删除画布“${item.name}”？`, content: '生成文件仍保留在资产中心。', onOk: async () => { try { await deleteCanvas(item.id); await list.refetch() } catch (failure) { setError(canvasError(failure)); throw failure } } })} /></div>
      </article>)}
      {!list.data.length ? <Empty description="从一张商品图、一个想法开始" /> : null}
    </div>}
    <div className="canvas-pagination"><Button disabled={page === 1} onClick={() => setPage(p => p - 1)}>上一页</Button><span>{page}</span><Button disabled={(list.data?.length ?? 0) < 24} onClick={() => setPage(p => p + 1)}>下一页</Button></div>
    <Modal title="新建创作画布" open={open} onCancel={() => setOpen(false)} onOk={() => void create()} confirmLoading={busy} okText="开始创作">
      <label>画布名称<Input value={name} maxLength={100} onChange={e => setName(e.target.value)} onPressEnter={() => void create()} /></label>
      <label className="canvas-field">关联项目<Select allowClear placeholder="个人创作" value={projectId} options={projects.data?.filter(p => p.status === 'ACTIVE').map(p => ({ label: p.name, value: p.id }))} onChange={setProjectId} style={{ width: '100%' }} /></label>
      {error ? <p role="alert" className="canvas-error">{error}</p> : null}
    </Modal>
  </main></UserShell>
}

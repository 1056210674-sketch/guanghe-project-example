import { BoldOutlined, EyeOutlined, ItalicOutlined, LinkOutlined, OrderedListOutlined, PictureOutlined, RedoOutlined, SendOutlined, StrikethroughOutlined, UnderlineOutlined, UndoOutlined, UnorderedListOutlined } from '@ant-design/icons'
import { Alert, App, Button, Dropdown, Input, Modal, Select, Space, Tooltip, Typography } from 'antd'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { EditorContent, useEditor, useEditorState } from '@tiptap/react'
import { Extension } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Plugin, type SelectionBookmark } from '@tiptap/pm/state'
import { replaceArticleAssets, updateArticle, type Article } from '../../api/articleApi'
import { uploadImageAsset } from '../../api/assetApi'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import { ImageAssetPickerDialog } from '../../features/assets/components/ImageAssetPickerDialog'
import { useAssetDownloadUrls } from '../../features/assets/live/assetQueries'
import { SafeArticleContent } from '../../features/articles/components/SafeArticleContent'
import { imageUploadValidationError } from '../../features/shared/imageUploadPolicy'
import { useAuthStore } from '../../stores/authStore'
import { ArticleAssetNode, articleEditorImages, articleTableExtensions, cleanPastedArticleHtml, imageDocumentIsValid, initialArticleHtml, safeArticleLink } from './articleRichText'

interface EditorDraft { title: string; html: string; version: number; assets: Article['assets']; updatedAt: number }
function readDraft(key: string): EditorDraft | undefined {
  try {
    const value: unknown = JSON.parse(sessionStorage.getItem(key) ?? 'null')
    if (!value || typeof value !== 'object') return
    const draft = value as Partial<EditorDraft>
    if (typeof draft.title === 'string' && typeof draft.html === 'string' && Number.isInteger(draft.version) && Array.isArray(draft.assets) && typeof draft.updatedAt === 'number' && Date.now() - draft.updatedAt < 24 * 60 * 60_000) return draft as EditorDraft
  } catch { /* Storage is optional; the unload warning still protects active edits. */ }
}

export function ArticleRichTextEditor({ article, onSaved, onPersisted, onCancel }: {
  article: Article
  onSaved: (article: Article, intent?: 'publish') => void
  onPersisted?: (article: Article) => void
  onCancel: () => void
}) {
  const { modal } = App.useApp()
  const ownerId = useAuthStore((state) => state.user?.id)
  const [session] = useState(captureAuthSession)
  const [draftKey] = useState(() => `zhixiaohe.article-editor.${ownerId}.${article.id}`)
  const [draft] = useState(() => readDraft(draftKey))
  const [initial] = useState(() => draft?.html ?? initialArticleHtml(article))
  const [title, setTitle] = useState(draft?.title ?? article.title)
  const [html, setHtml] = useState(initial)
  const [version, setVersion] = useState(draft?.version ?? article.version)
  const [assets, setAssets] = useState(draft?.assets ?? article.assets)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const [storageFailed, setStorageFailed] = useState(false)
  const [pickerOpen, setPickerOpen] = useState(false)
  const [previewOpen, setPreviewOpen] = useState(false)
  const [linkOpen, setLinkOpen] = useState(false)
  const [linkValue, setLinkValue] = useState('')
  const [linkError, setLinkError] = useState<string>()
  const upload = useRef<HTMLInputElement>(null)
  const bookmark = useRef<SelectionBookmark | null>(null)
  const alive = useRef(true)
  const lock = useRef(false)
  const saved = useRef(false)
  const [baseline, setBaseline] = useState<string>()
  const current = () => alive.current && isCurrentAuthSession(session)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])

  const editor = useEditor({
    extensions: [StarterKit.configure({ link: { openOnClick: false, autolink: false, linkOnPaste: false, isAllowedUri: safeArticleLink, HTMLAttributes: { target: null, rel: 'noopener noreferrer' } }, trailingNode: false }), ArticleAssetNode, ...articleTableExtensions,
      Extension.create({ name: 'articleImageLimit', addProseMirrorPlugins() { return [new Plugin({ filterTransaction: (transaction) => !transaction.docChanged || imageDocumentIsValid(transaction.doc.toJSON()) })] } }),
    ],
    content: initial,
    editorProps: {
      attributes: { role: 'textbox', 'aria-label': '文章正文', 'aria-multiline': 'true', class: 'article-rich-editor__body' },
      transformPastedHTML: cleanPastedArticleHtml,
      handlePaste: (_view, event) => { if (event.clipboardData?.files.length) { setError('请通过“图片”按钮上传本地图片或选择资产图片。'); return true } return false },
      handleDrop: (_view, event) => { if (event.dataTransfer?.files.length) { setError('请通过“图片”按钮上传本地图片。'); return true } return false },
    },
    onCreate: ({ editor: active }) => { setBaseline(active.getHTML()) },
    onUpdate: ({ editor: active }) => setHtml(active.getHTML()),
    onTransaction: ({ transaction }) => { if (bookmark.current && transaction.docChanged) bookmark.current = bookmark.current.map(transaction.mapping) },
  })
  const toolbar = useEditorState({ editor, selector: ({ editor: active }) => active ? {
    bold: active.isActive('bold'), italic: active.isActive('italic'), underline: active.isActive('underline'), strike: active.isActive('strike'),
    bulletList: active.isActive('bulletList'), orderedList: active.isActive('orderedList'), blockquote: active.isActive('blockquote'), link: active.isActive('link'),
    heading: active.isActive('heading') ? String(active.getAttributes('heading').level) : 'paragraph',
    undo: active.can().undo(), redo: active.can().redo(),
  } : null })
  const images = editor ? articleEditorImages(editor) : []
  const imageIds = images.map((image) => image.assetId)
  const downloads = useAssetDownloadUrls(imageIds)
  const urls = Object.fromEntries(Object.entries(downloads).map(([id, value]) => [id, value?.url]))
  const dirty = Boolean(draft) || title !== article.title || html !== initial && html !== baseline

  useEffect(() => { editor?.setEditable(!busy) }, [editor, busy])
  useEffect(() => {
    if (!dirty || saved.current || !isCurrentAuthSession(session)) return
    try { sessionStorage.setItem(draftKey, JSON.stringify({ title, html, version, assets, updatedAt: Date.now() })) }
    catch { queueMicrotask(() => { if (alive.current) setStorageFailed(true) }) }
  }, [assets, dirty, draftKey, html, session, title, version])
  useEffect(() => {
    if (!dirty && !busy) return
    const warn = (event: BeforeUnloadEvent) => { if (!saved.current && isCurrentAuthSession(session)) { event.preventDefault(); event.returnValue = '' } }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [busy, dirty, session])

  function rememberPosition() { if (editor) bookmark.current = editor.state.selection.getBookmark() }
  function restorePosition() {
    if (!editor || editor.isDestroyed) return false
    if (bookmark.current) editor.view.dispatch(editor.state.tr.setSelection(bookmark.current.resolve(editor.state.doc)))
    editor.commands.focus()
    return true
  }
  function insertImages(ids: readonly string[]) {
    if (!editor || !current()) return
    const existing = articleEditorImages(editor).map((image) => image.assetId)
    const unique = [...new Set(ids)].filter((id) => !existing.includes(id))
    if (!unique.length) { setError('所选图片已在文章中，不会重复插入。'); return }
    if (unique.length + existing.length > 10) { setError('每篇文章最多插入 10 张不同图片。'); return }
    if (!restorePosition()) return
    // Insert at the caret (collapse a range); inserting a picture must not delete selected prose.
    const position = editor.state.selection.from
    const inserted = editor.commands.insertContentAt(position, unique.map((assetId) => ({ type: 'articleAsset', attrs: { assetId, caption: '' } })))
    bookmark.current = null
    setError(inserted ? undefined : '当前位置无法插入图片，请将光标放在正文段落中。')
  }
  async function uploadImage(file?: File) {
    if (!file || lock.current || !current() || !editor) return
    const invalid = imageUploadValidationError(file)
    if (invalid || articleEditorImages(editor).length >= 10) { setError(invalid ?? '每篇文章最多插入 10 张图片。'); return }
    lock.current = true; setBusy(true); setError(undefined)
    try {
      const result = await uploadImageAsset({ spaceId: article.spaceId, file, idempotencyKey: crypto.randomUUID() })
      if (current()) insertImages([result.id])
    } catch (problem) { if (current()) setError(problem instanceof Error ? problem.message : '图片上传失败。') }
    finally { lock.current = false; if (current()) setBusy(false) }
  }
  function clearDraft() { saved.current = true; try { sessionStorage.removeItem(draftKey) } catch { /* No draft can be read if storage is unavailable. */ } }
  async function save(intent?: 'publish') {
    if (lock.current || !current() || !editor) return
    if (!title.trim()) { setError('请填写文章标题。'); return }
    if (!editor.getText().trim()) { setError('请保留文章正文。'); return }
    const bodyHtml = editor.getHTML()
    if (new TextEncoder().encode(bodyHtml).length > 524288) { setError('文章内容过长，请缩短后保存。'); return }
    lock.current = true; setBusy(true); setError(undefined)
    let associationsSaved = false
    let bodySaved = false
    try {
      let nextVersion = version
      const requestedAssets = articleEditorImages(editor).map(({ assetId, caption }, index) => ({ assetId, positionKey: `body-${index + 1}`, sortOrder: index + 1, altText: caption.trim() || null }))
      const existingAssets = assets.map((asset) => ({ assetId: asset.assetId, positionKey: asset.positionKey, sortOrder: asset.sortOrder, altText: asset.altText?.trim() || null }))
      const unionIds = [...new Set([...existingAssets.map((asset) => asset.assetId), ...requestedAssets.map((asset) => asset.assetId)])]
      if (unionIds.length > 10) throw new Error('本次替换涉及超过 10 张图片。请先删除不需要的旧图并保存，再插入新图；当前修改尚未提交。')
      // Keep old body references valid throughout every individually committed request.
      // Add new relations first, update body second, only then remove unused relations.
      if (requestedAssets.some((asset) => !existingAssets.some((old) => old.assetId === asset.assetId))) {
        const union = unionIds.map((assetId, index) => ({ assetId, positionKey: `body-${index + 1}`, sortOrder: index + 1, altText: requestedAssets.find((asset) => asset.assetId === assetId)?.altText ?? existingAssets.find((asset) => asset.assetId === assetId)?.altText ?? null }))
        const associated = await replaceArticleAssets(article.id, { version: nextVersion, items: union })
        if (!current()) return
        nextVersion = associated.version; setVersion(nextVersion); setAssets(associated.assets); associationsSaved = true; onPersisted?.(associated)
      }
      let result = await updateArticle(article.id, { version: nextVersion, title: title.trim(), bodyHtml })
      if (!current()) return
      bodySaved = true; setVersion(result.version); setAssets(result.assets); onPersisted?.(result)
      const resultingAssets = result.assets.map((asset) => ({ assetId: asset.assetId, positionKey: asset.positionKey, sortOrder: asset.sortOrder, altText: asset.altText?.trim() || null }))
      if (JSON.stringify(requestedAssets) !== JSON.stringify(resultingAssets)) {
        result = await replaceArticleAssets(article.id, { version: result.version, items: requestedAssets })
        if (!current()) return
        setVersion(result.version); setAssets(result.assets); onPersisted?.(result)
      }
      clearDraft(); onSaved(result, intent)
    } catch (problem) {
      if (current()) setError(`${bodySaved ? '正文已保存，但配图关系整理尚未完成。' : associationsSaved ? '新增配图关联已保存，但正文尚未保存。' : ''}${problem instanceof Error ? problem.message : '保存失败。'} 未保存的修改仍保留。如果请求超时或版本冲突，请先复制保留修改，再退出编辑核对最新文章；不会自动覆盖其他修改。`)
    } finally { lock.current = false; if (current()) setBusy(false) }
  }
  function applyLink(remove = false) {
    const href = linkValue.trim()
    if (!remove && !safeArticleLink(href)) { setLinkError('请输入完整的 http:// 或 https:// 链接。'); return }
    if (!restorePosition() || !editor) return
    if (remove) editor.chain().extendMarkRange('link').unsetLink().run()
    else if (editor.state.selection.empty && !editor.isActive('link')) editor.commands.insertContent({ type: 'text', text: href, marks: [{ type: 'link', attrs: { href } }] })
    else editor.chain().extendMarkRange('link').setLink({ href }).run()
    bookmark.current = null; setLinkOpen(false)
  }
  const tool = (label: string, icon: ReactNode, action: () => void, active?: boolean, disabled = false) => <Tooltip title={label}><Button type="text" aria-label={label} aria-pressed={active} className={active ? 'is-active' : undefined} icon={icon} disabled={busy || disabled || !editor} onMouseDown={(event) => event.preventDefault()} onClick={action} /></Tooltip>
  return <section className="article-rich-editor" data-article-editor-dirty={dirty || busy ? 'true' : 'false'} aria-label="整篇文章编辑器">
    {draft ? <Alert type="warning" showIcon title={draft.version === article.version ? '已恢复本标签页未保存的修改。' : '已恢复未保存的修改，但文章另有新版本。保存可能冲突，请先复制保留修改再核对最新文章。'} /> : null}
    {storageFailed ? <Alert type="warning" showIcon title="浏览器无法暂存修改，请在离开前保存。" /> : null}
    <div className="article-rich-editor__toolbar" role="toolbar" aria-label="文章排版">
      {tool('撤销', <UndoOutlined />, () => { editor?.chain().focus().undo().run() }, false, !toolbar?.undo)}
      {tool('重做', <RedoOutlined />, () => { editor?.chain().focus().redo().run() }, false, !toolbar?.redo)}
      <Select aria-label="段落样式" value={toolbar?.heading ?? 'paragraph'} disabled={busy || !editor} options={[{ value: 'paragraph', label: '正文' }, ...[1, 2, 3, 4, 5, 6].map((level) => ({ value: String(level), label: `标题 ${level}` }))]} onChange={(value) => { if (value === 'paragraph') editor?.chain().focus().setParagraph().run(); else editor?.chain().focus().setHeading({ level: Number(value) as 1 | 2 | 3 | 4 | 5 | 6 }).run() }} />
      {tool('粗体', <BoldOutlined />, () => { editor?.chain().focus().toggleBold().run() }, toolbar?.bold)}
      {tool('斜体', <ItalicOutlined />, () => { editor?.chain().focus().toggleItalic().run() }, toolbar?.italic)}
      {tool('下划线', <UnderlineOutlined />, () => { editor?.chain().focus().toggleUnderline().run() }, toolbar?.underline)}
      {tool('删除线', <StrikethroughOutlined />, () => { editor?.chain().focus().toggleStrike().run() }, toolbar?.strike)}
      {tool('无序列表', <UnorderedListOutlined />, () => { editor?.chain().focus().toggleBulletList().run() }, toolbar?.bulletList)}
      {tool('有序列表', <OrderedListOutlined />, () => { editor?.chain().focus().toggleOrderedList().run() }, toolbar?.orderedList)}
      {tool('引用', <span aria-hidden>❝</span>, () => { editor?.chain().focus().toggleBlockquote().run() }, toolbar?.blockquote)}
      <Tooltip title="链接"><Button type="text" aria-label="链接" aria-pressed={toolbar?.link} className={toolbar?.link ? 'is-active' : undefined} icon={<LinkOutlined />} disabled={busy || !editor} onMouseDown={(event) => event.preventDefault()} onClick={() => { rememberPosition(); setLinkValue(String(editor?.getAttributes('link').href ?? '')); setLinkError(undefined); setLinkOpen(true) }} /></Tooltip>
      <Dropdown trigger={['click']} menu={{ items: [{ key: 'upload', label: '本地文件上传' }, { key: 'assets', label: '从资产中心选择' }], onClick: ({ key }) => { if (key === 'upload') upload.current?.click(); else setPickerOpen(true) } }}><Button aria-label="插入图片" icon={<PictureOutlined />} disabled={busy || !editor || imageIds.length >= 10} onMouseDown={(event) => { event.preventDefault(); rememberPosition() }} onClick={rememberPosition}>图片</Button></Dropdown>
    </div>
    <div className="article-rich-editor__paper"><Input className="article-rich-editor__title" value={title} aria-label="文章标题" placeholder="文章标题" maxLength={255} disabled={busy} onChange={(event) => setTitle(event.target.value)} /><EditorContent editor={editor} /></div>
    {error ? <Alert type="error" showIcon title={error} /> : null}
    <footer className="article-rich-editor__footer"><Typography.Text type="secondary">{editor?.getText().replace(/\s/g, '').length ?? 0} 字 · {imageIds.length}/10 张图片{dirty ? ' · 未保存' : ''}</Typography.Text><Space wrap><Button disabled={busy} onClick={() => { const cancel = () => { clearDraft(); onCancel() }; if (dirty) modal.confirm({ title: '放弃未保存的修改？', okText: '放弃修改', cancelText: '继续编辑', onOk: cancel }); else cancel() }}>取消</Button><Button icon={<EyeOutlined />} disabled={!editor} onClick={() => setPreviewOpen(true)}>预览</Button><Button loading={busy} disabled={!editor || busy} onClick={() => void save()}>保存修改</Button><Button type="primary" icon={<SendOutlined />} disabled={!editor || busy} onClick={() => void save('publish')}>保存并投稿</Button></Space></footer>
    <input ref={upload} type="file" aria-label="本地文章图片" accept="image/png,image/jpeg" hidden onChange={(event) => { void uploadImage(event.target.files?.[0]); event.target.value = '' }} />
    {pickerOpen ? <ImageAssetPickerDialog open title="插入资产中心的图片" businessSpaceId={article.spaceId} selectedAssetIds={[]} maxSelection={10 - imageIds.length} onCancel={() => { setPickerOpen(false); bookmark.current = null }} onConfirm={(ids) => { insertImages(ids); setPickerOpen(false) }} /> : null}
    <Modal title="文章预览" open={previewOpen} width={860} footer={<Button onClick={() => setPreviewOpen(false)}>返回编辑</Button>} onCancel={() => setPreviewOpen(false)}><div className="article-document article-rich-editor__preview"><Typography.Title level={2}>{title}</Typography.Title><SafeArticleContent html={editor?.getHTML() ?? html} imageUrls={urls} /></div></Modal>
    <Modal title="设置链接" open={linkOpen} okText="应用链接" cancelText="取消" onOk={() => applyLink()} onCancel={() => { setLinkOpen(false); bookmark.current = null }}><Input aria-label="链接地址" value={linkValue} placeholder="https://" onChange={(event) => { setLinkValue(event.target.value); setLinkError(undefined) }} onPressEnter={() => applyLink()} />{linkError ? <Alert type="error" title={linkError} /> : null}<Button type="link" onClick={() => applyLink(true)}>移除链接</Button></Modal>
  </section>
}

import { DeleteOutlined } from '@ant-design/icons'
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react'
import { Button, Input } from 'antd'
import { useSyncExternalStore } from 'react'
import { useAssetDownloadUrls } from '../../features/assets/live/assetQueries'

export function ArticleAssetNodeView({ node, updateAttributes, deleteNode, editor }: NodeViewProps) {
  // NodeViews can be created while an upload/save temporarily disables editing.
  // setEditable emits update without changing the document, so subscribe explicitly.
  const editable = useSyncExternalStore((changed) => {
    editor.on('update', changed)
    editor.on('transaction', changed)
    return () => { editor.off('update', changed); editor.off('transaction', changed) }
  }, () => editor.isEditable, () => false)
  const assetId = String(node.attrs.assetId ?? '')
  const downloads = useAssetDownloadUrls(/^[1-9]\d*$/.test(assetId) ? [assetId] : [])
  const url = downloads[assetId]?.url
  const caption = String(node.attrs.caption ?? '')
  return <NodeViewWrapper className="article-rich-editor__image" data-asset-id={assetId} contentEditable={false}>
    {url && /^https:\/\//i.test(url) ? <img src={url} alt={caption || '文章配图'} draggable={false} /> : <div className="article-document__image-unavailable">配图暂不可用</div>}
    <div className="article-rich-editor__image-caption"><Input aria-label="图片说明" placeholder="添加图片说明（可选）" value={caption} maxLength={500} disabled={!editable} onChange={(event) => updateAttributes({ caption: event.target.value })} /><Button aria-label="删除图片" title="删除图片" type="text" disabled={!editable} icon={<DeleteOutlined />} onClick={deleteNode} /></div>
  </NodeViewWrapper>
}

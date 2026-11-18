import { Node as TiptapNode, type Editor, type JSONContent } from '@tiptap/core'
import { ReactNodeViewRenderer } from '@tiptap/react'
import { Table, TableCell, TableHeader, TableRow } from '@tiptap/extension-table'
import type { Article } from '../../api/articleApi'
import { ArticleAssetNodeView } from './ArticleAssetNodeView'

const ASSET_ID = /^[1-9]\d*$/
export const safeArticleLink = (value: string) => {
  try { return /^https?:$/.test(new URL(value).protocol) } catch { return false }
}

// The saved document contains only an asset ID and caption, never a signed URL or arbitrary img/src.
export const ArticleAssetNode = TiptapNode.create({
  name: 'articleAsset', group: 'block', atom: true, selectable: true,
  addAttributes() { return { assetId: { default: null }, caption: { default: '' } } },
  parseHTML() { return [{ tag: 'figure[data-asset-id]', getAttrs: (element) => {
    const assetId = element.getAttribute('data-asset-id') ?? ''
    return ASSET_ID.test(assetId) ? { assetId, caption: element.querySelector('figcaption')?.textContent ?? '' } : false
  } }] },
  renderHTML({ node }) { return ['figure', { 'data-asset-id': node.attrs.assetId }, ['figcaption', String(node.attrs.caption ?? '')]] },
  addNodeView() { return ReactNodeViewRenderer(ArticleAssetNodeView) },
})

// Keep existing tables editable without offering widths/alignment the server cannot preserve.
export const articleTableExtensions = [
  Table.extend({ renderHTML() { return ['table', ['tbody', 0]] } }).configure({ resizable: false }),
  TableRow,
  TableCell.extend({ renderHTML({ node }) { return ['td', { colspan: node.attrs.colspan, rowspan: node.attrs.rowspan }, 0] } }),
  TableHeader.extend({ renderHTML({ node }) { return ['th', { colspan: node.attrs.colspan, rowspan: node.attrs.rowspan }, 0] } }),
]

function assetFigure(document: Document, assetId: string, caption: string) {
  const figure = document.createElement('figure')
  figure.setAttribute('data-asset-id', assetId)
  const label = document.createElement('figcaption')
  label.textContent = caption
  figure.append(label)
  return figure
}

/** Normalize legacy inline asset references into blocks without dropping surrounding marked text. */
export function initialArticleHtml(article: Article): string {
  const document = new DOMParser().parseFromString(article.bodyHtml, 'text/html')
  const seen = new Set<string>()
  // Splitting a paragraph clones its remaining children. Re-query so later inline images
  // in that same paragraph are normalized too, rather than visiting detached old nodes.
  let element: Element | null
  while ((element = document.body.querySelector('[data-asset-id]:not([data-rich-normalized])'))) {
    const id = element.getAttribute('data-asset-id') ?? ''
    if (!ASSET_ID.test(id)) { element.removeAttribute('data-asset-id'); continue }
    const caption = element.querySelector('figcaption')?.textContent ?? element.getAttribute('data-alt') ?? article.assets.find((asset) => asset.assetId === id)?.altText ?? ''
    // A duplicated legacy reference is retained as its caption; relations are unique server-side.
    const replacement = seen.has(id) ? document.createTextNode(caption) : assetFigure(document, id, caption)
    if (replacement instanceof Element) replacement.setAttribute('data-rich-normalized', 'true')
    seen.add(id)
    const block = element.parentElement?.closest('p,h1,h2,h3,h4,h5,h6,pre')
    if (block && block.contains(element)) {
      const before = document.createRange(); before.selectNodeContents(block); before.setEndBefore(element)
      const after = document.createRange(); after.selectNodeContents(block); after.setStartAfter(element)
      const left = block.cloneNode(false) as Element; left.append(before.cloneContents())
      const right = block.cloneNode(false) as Element; right.append(after.cloneContents())
      block.replaceWith(...[left, replacement, right].filter((node) => node === replacement || node.textContent?.trim() || node instanceof Element && node.querySelector('[data-asset-id],br')))
    } else element.replaceWith(replacement)
  }
  document.body.querySelectorAll('[data-rich-normalized]').forEach((node) => node.removeAttribute('data-rich-normalized'))
  for (const asset of article.assets) if (!seen.has(asset.assetId)) {
    document.body.append(assetFigure(document, asset.assetId, asset.altText ?? ''))
    seen.add(asset.assetId)
  }
  return document.body.innerHTML
}

/** Schema parsing supplies the final allowlist. Remove active content and uncontrolled images first. */
export function cleanPastedArticleHtml(html: string): string {
  const document = new DOMParser().parseFromString(html, 'text/html')
  document.querySelectorAll('script,style,iframe,object,embed,img,svg,math,video,audio').forEach((element) => element.remove())
  document.querySelectorAll('[data-asset-id]').forEach((element) => element.replaceWith(document.createTextNode(element.querySelector('figcaption')?.textContent ?? '')))
  document.querySelectorAll('*').forEach((element) => {
    for (const attribute of Array.from(element.attributes)) {
      if (!['href', 'title', 'colspan', 'rowspan'].includes(attribute.name) || attribute.name === 'href' && !safeArticleLink(attribute.value)) element.removeAttribute(attribute.name)
    }
  })
  return document.body.innerHTML
}

export function articleEditorImages(editor: Editor): { assetId: string; caption: string }[] {
  const result: { assetId: string; caption: string }[] = []
  editor.state.doc.descendants((node) => {
    if (node.type.name === 'articleAsset') result.push({ assetId: String(node.attrs.assetId), caption: String(node.attrs.caption ?? '') })
  })
  return result
}

export function imageDocumentIsValid(document: JSONContent): boolean {
  const ids = new Set<string>()
  let valid = true
  const visit = (node: JSONContent) => {
    if (node.type === 'articleAsset') {
      const id = String(node.attrs?.assetId ?? '')
      if (!ASSET_ID.test(id) || ids.has(id) || String(node.attrs?.caption ?? '').length > 500) valid = false
      ids.add(id)
    }
    node.content?.forEach(visit)
  }
  visit(document)
  return valid && ids.size <= 10
}

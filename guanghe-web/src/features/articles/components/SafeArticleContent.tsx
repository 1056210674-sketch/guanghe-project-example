import { createElement, Fragment, type ReactNode } from 'react'

const ALLOWED_TAGS = new Set([
  'p',
  'h1',
  'h2',
  'h3',
  'h4',
  'strong', 'b', 'i', 'u', 's',
  'em',
  'blockquote',
  'ul',
  'ol',
  'li',
  'br',
  'table',
  'thead',
  'tbody',
  'tr',
  'th',
  'td',
  'hr', 'h5', 'h6', 'pre', 'code', 'del', 'caption', 'figcaption',
])
const BLOCKED_TAGS = new Set(['script', 'style', 'img', 'iframe', 'object', 'embed'])

function renderNode(node: ChildNode, key: string, images: Readonly<Record<string, string | undefined>>): ReactNode {
  if (node.nodeType === Node.TEXT_NODE) return node.textContent
  if (node.nodeType !== Node.ELEMENT_NODE) return null
  const element = node as Element
  const tag = element.tagName.toLocaleLowerCase('en-US')
  if (BLOCKED_TAGS.has(tag)) return null
  const assetId = element.getAttribute('data-asset-id')
  if (assetId && /^[1-9]\d*$/.test(assetId)) {
    // Never use HTML img/src supplied by the model. URLs come only from the bound asset API.
    const url = images[assetId]
    const alt = element.getAttribute('data-alt') ?? element.querySelector('figcaption')?.textContent ?? '文章配图'
    return <figure key={key}>{url && /^https:\/\//i.test(url) ? <img className="article-document__image" src={url} alt={alt} /> : <span className="article-document__image-unavailable">配图暂不可用</span>}{alt ? <figcaption>{alt}</figcaption> : null}</figure>
  }
  const children = Array.from(element.childNodes).map((child, index) =>
    renderNode(child, `${key}-${index}`, images),
  )
  if (tag === 'a') {
    const href = element.getAttribute('href') ?? ''
    if (/^https?:\/\//i.test(href)) return <a key={key} href={href} target="_blank" rel="noopener noreferrer">{children}</a>
  }
  if (!ALLOWED_TAGS.has(tag)) {
    return <Fragment key={key}>{children}</Fragment>
  }
  if (tag === 'br' || tag === 'hr') return createElement(tag, { key })
  const spans = tag === 'td' || tag === 'th' ? { colSpan: Math.max(1, Math.min(100, Number(element.getAttribute('colspan')) || 1)), rowSpan: Math.max(1, Math.min(100, Number(element.getAttribute('rowspan')) || 1)) } : {}
  return createElement(tag, { key, ...spans }, children)
}

export function SafeArticleContent({
  html,
  fallback,
  imageUrls = {},
}: {
  html: string
  fallback?: string
  imageUrls?: Readonly<Record<string, string | undefined>>
}) {
  if (!html.trim()) {
    return <p className="article-live-preview__empty">{fallback ?? '暂无正文'}</p>
  }
  const document = new DOMParser().parseFromString(html, 'text/html')
  return (
    <div className="article-live-preview" aria-label="文章安全预览">
      {Array.from(document.body.childNodes).map((node, index) =>
        renderNode(node, `article-node-${index}`, imageUrls),
      )}
    </div>
  )
}

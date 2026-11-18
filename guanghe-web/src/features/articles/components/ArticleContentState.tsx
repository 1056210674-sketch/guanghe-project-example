import { Spin } from 'antd'
import type { ReactNode } from 'react'

/** Async states must occupy the same space as the document, including in row-flex panels. */
export function ArticleContentState({ layout = 'page', loading, children }: {
  layout?: 'page' | 'panel'
  loading?: string
  children?: ReactNode
}) {
  return <div className={`article-content-state article-content-state--${layout}`}>
    {loading ? <Spin description={loading} /> : children}
  </div>
}

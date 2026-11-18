import { FileTextOutlined } from '@ant-design/icons'
import { Button, Spin, Tag, Typography } from 'antd'
import { Link } from 'react-router-dom'
import { appLinks } from '../../../routes/paths'
import type { ChatArticlePreparationView } from '../types'

export function ChatArticlePreparationCard({ preparation, onOpen }: { preparation: ChatArticlePreparationView; onOpen?: (id: string) => void }) {
  return <section className="chat-article-preparation" aria-label="完整文章准备记录">
    <header><Typography.Text strong>{preparation.statusLabel}</Typography.Text><Tag color={preparation.tone === 'success' ? 'success' : preparation.tone === 'danger' ? 'error' : preparation.tone === 'warning' ? 'warning' : 'default'}>完整文章</Tag></header>
    <Typography.Paragraph type="secondary">{preparation.description}</Typography.Paragraph>
    {preparation.loading ? <Spin size="small" /> : null}
    {preparation.stages.length ? <Typography.Text type="secondary">{preparation.stages.join(' · ')}</Typography.Text> : null}
    {onOpen ? <Button type="link" className="chat-article-preparation__link" icon={<FileTextOutlined />} onClick={() => onOpen(preparation.id)}>{preparation.openLabel}</Button> : <Link className="chat-article-preparation__link" to={appLinks.articlePreparation(preparation.id)}><FileTextOutlined aria-hidden="true" /> {preparation.openLabel}</Link>}
  </section>
}

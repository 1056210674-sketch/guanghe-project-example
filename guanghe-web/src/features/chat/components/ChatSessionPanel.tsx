import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import { Alert, Button, Empty, Input, Spin, Typography } from 'antd'
import type { ChatSessionView } from '../types'

interface ChatSessionPanelProps {
  sessions: readonly ChatSessionView[]
  selectedSessionId?: string
  searchValue: string
  onNewConversation: () => void
  onSearchChange: (value: string) => void
  onSelectSession: (id: string) => void
  loading?: boolean
  error?: string
  onRenameSession?: (id: string) => void
  onDeleteSession?: (id: string) => void
}

export function ChatSessionPanel({
  sessions,
  selectedSessionId,
  searchValue,
  onNewConversation,
  onSearchChange,
  onSelectSession,
  loading = false,
  error,
  onRenameSession,
  onDeleteSession,
}: ChatSessionPanelProps) {
  return (
    <aside className="chat-session-panel" aria-label="会话列表">
      <div className="chat-session-panel__actions">
        <Button
          type="primary"
          block
          icon={<PlusOutlined />}
          aria-label="新建对话"
          onClick={onNewConversation}
        >
          新建对话
        </Button>
        <Input
          allowClear
          prefix={<SearchOutlined />}
          placeholder="搜索会话"
          aria-label="搜索会话"
          value={searchValue}
          onChange={(event) => onSearchChange(event.target.value)}
        />
      </div>

      <div className="chat-session-panel__list">
        {error ? <Alert type="error" showIcon title={error} /> : null}
        {loading ? <Spin description="正在加载会话…" /> : null}
        {sessions.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              <div className="chat-session-panel__empty-copy">
                <Typography.Text strong>
                  {searchValue.trim() ? '没有匹配的会话' : '暂无会话'}
                </Typography.Text>
                <Typography.Text type="secondary">
                  {searchValue.trim()
                    ? '没有找到匹配的会话，请尝试其他关键词。'
                    : '新建对话后，会话将显示在这里。'}
                </Typography.Text>
              </div>
            }
          />
        ) : (
          sessions.map((session) => {
            const selected = session.id === selectedSessionId

            return (
              <div
                key={session.id}
                className={`chat-session-item-wrap ${selected ? 'chat-session-item-wrap--selected' : ''}`}
              >
                <button
                  type="button"
                  className="chat-session-item"
                  aria-current={selected ? 'true' : undefined}
                  onClick={() => onSelectSession(session.id)}
                >
                  <span className="chat-session-item__heading">
                    <strong>{session.title}</strong>
                    {session.updatedAt ? <time>{session.updatedAt}</time> : null}
                  </span>
                  {session.preview ? (
                    <span className="chat-session-item__preview">
                      {session.preview}
                    </span>
                  ) : null}
                </button>
                {onRenameSession || onDeleteSession ? (
                  <span className="chat-session-item__actions">
                    {onRenameSession ? (
                      <Button
                        type="text"
                        size="small"
                        icon={<EditOutlined aria-hidden="true" />}
                        aria-label={`重命名会话：${session.title}`}
                        onClick={() => onRenameSession(session.id)}
                      />
                    ) : null}
                    {onDeleteSession ? (
                      <Button
                        type="text"
                        size="small"
                        danger
                        icon={<DeleteOutlined aria-hidden="true" />}
                        aria-label={`删除会话：${session.title}`}
                        onClick={() => onDeleteSession(session.id)}
                      />
                    ) : null}
                  </span>
                ) : null}
              </div>
            )
          })
        )}
      </div>
    </aside>
  )
}

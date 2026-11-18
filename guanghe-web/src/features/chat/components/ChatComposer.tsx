import {
  DeleteOutlined,
  FileTextOutlined,
  LinkOutlined,
  PaperClipOutlined,
  PictureOutlined,
  PlusOutlined,
  SendOutlined,
  StopOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons'
import { Button, Dropdown, Input, Progress, Tag, Typography } from 'antd'
import { useRef } from 'react'
import type { ChangeEvent, FormEvent, ReactNode } from 'react'
import type { ChatComposerAttachmentView } from '../creativeGrounding'
import '../../../styles/chat-composer-compact.css'

interface ChatComposerProps {
  reportReference?: ReactNode
  onChooseGeoReport?: () => void
  onCreatePeriodReport?: () => void
  onCreateDiagnosticReport?: () => void
  onViewTaskProgress?: () => void
  value: string
  isGenerating: boolean
  sendEnabled: boolean
  sendFeedback?: string
  attachments?: readonly ChatComposerAttachmentView[]
  hasSelectedProduct?: boolean
  attachmentUploadEnabled?: boolean
  attachmentUploadDisabledReason?: string
  onChange: (value: string) => void
  onSend: (message: string) => void
  onStop: () => void
  onStopAndSend?: (message: string) => void
  stopPending?: boolean
  onSelectAttachmentFiles?: (files: readonly File[]) => void
  onChooseAttachmentAssets?: () => void
  onRemoveAttachment?: (assetId: string) => void
  onAddProjectDocument?: () => void
  onAddProjectExcerpt?: () => void
  onOpenProjectKnowledge?: () => void
}

const ATTACHMENT_STATUS_LABELS = {
  UPLOADING: '上传中',
  PROCESSING: '处理中',
  READY: '可使用',
  FAILED: '上传失败',
} as const

export function ChatComposer({
  reportReference,
  onChooseGeoReport,
  onCreatePeriodReport,
  onCreateDiagnosticReport,
  onViewTaskProgress,
  value,
  isGenerating,
  sendEnabled,
  sendFeedback,
  attachments = [],
  hasSelectedProduct = false,
  attachmentUploadEnabled = false,
  attachmentUploadDisabledReason,
  onChange,
  onSend,
  onStop,
  onStopAndSend,
  stopPending = false,
  onSelectAttachmentFiles,
  onChooseAttachmentAssets,
  onRemoveAttachment,
  onAddProjectDocument,
  onAddProjectExcerpt,
  onOpenProjectKnowledge,
}: ChatComposerProps) {
  const attachmentInputRef = useRef<HTMLInputElement>(null)
  const composingRef = useRef(false)
  const normalizedValue = value.trim()
  const attachmentsReady = attachments.every(
    (attachment) => attachment.status === 'READY',
  )
  const canSend =
    sendEnabled &&
    normalizedValue.length > 0 &&
    !isGenerating &&
    attachmentsReady
  const footerFeedback = sendFeedback ?? (
    !attachmentsReady
      ? attachments.some((item) => item.status === 'FAILED')
        ? '有图片不可用，请移除或重新选择；已输入的文字会保留。'
        : '图片处理完成后才能发送'
      : normalizedValue ? `${value.length} 字` : undefined
  )
  const useAddMenu = Boolean(onChooseGeoReport || onCreatePeriodReport || onCreateDiagnosticReport || onViewTaskProgress || onAddProjectDocument || onAddProjectExcerpt || onOpenProjectKnowledge)
  const addItems = [
    ...(onCreateDiagnosticReport ? [{ key: 'diagnostic-report', label: '诊断报告', icon: <FileTextOutlined />, onClick: onCreateDiagnosticReport }] : []),
    ...(onViewTaskProgress ? [{ key: 'task-progress', label: '任务进度', icon: <UnorderedListOutlined aria-hidden="true" />, onClick: onViewTaskProgress }] : []),
    ...(onChooseGeoReport ? [{ key: 'geo-report', label: '引用监测报告', icon: <FileTextOutlined />, onClick: onChooseGeoReport }] : []),
    ...(onCreatePeriodReport ? [{ key: 'period-report', label: '导出周报 / 阶段报告', icon: <FileTextOutlined />, onClick: onCreatePeriodReport }] : []),
    ...(onSelectAttachmentFiles ? [{ key: 'image', label: '上传本次图片', icon: <PaperClipOutlined />, disabled: !attachmentUploadEnabled || attachments.length >= 8, onClick: () => attachmentInputRef.current?.click() }] : []),
    ...(onChooseAttachmentAssets ? [{ key: 'assets', label: '从资产中心选择图片', icon: <PictureOutlined />, disabled: attachments.length >= 8, onClick: onChooseAttachmentAssets }] : []),
    ...(onAddProjectDocument ? [{ key: 'document', label: '添加项目资料', icon: <FileTextOutlined />, onClick: onAddProjectDocument }] : []),
    ...(onAddProjectExcerpt ? [{ key: 'excerpt', label: '添加网页摘录', icon: <LinkOutlined />, onClick: onAddProjectExcerpt }] : []),
    ...(onOpenProjectKnowledge ? [{ key: 'knowledge', label: '查看已有项目资料', icon: <FileTextOutlined />, onClick: onOpenProjectKnowledge }] : []),
  ]

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (canSend) {
      onSend(normalizedValue)
    }
  }

  const handleAttachmentChange = (event: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? [])
    event.target.value = ''
    if (files.length > 0 && attachmentUploadEnabled) {
      onSelectAttachmentFiles?.(files)
    }
  }

  return (
    <form className="chat-composer chat-composer--compact" onSubmit={handleSubmit}>
      {reportReference}
      <Input.TextArea
        aria-label="消息输入"
        placeholder="给光禾AI智能体框架发消息…"
        value={value}
        rows={1}
        autoSize={{ minRows: 1, maxRows: 8 }}
        onChange={(event) => onChange(event.target.value)}
        onCompositionStart={() => { composingRef.current = true }}
        onCompositionEnd={() => { composingRef.current = false }}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.shiftKey
            && !composingRef.current && !event.nativeEvent.isComposing && event.keyCode !== 229) {
            event.preventDefault()
            if (canSend && !event.repeat) onSend(normalizedValue)
          }
        }}
      />
      <input
        ref={attachmentInputRef}
        className="chat-composer__attachment-input"
        type="file"
        accept="image/jpeg,image/png"
        multiple
        tabIndex={-1}
        aria-hidden="true"
        onChange={handleAttachmentChange}
      />
      {attachments.length > 0 ? (
        <ul className="chat-composer__attachments" aria-label="本次图片附件">
          {attachments.map((attachment) => (
            <li key={attachment.assetId}>
              <span className="chat-composer__attachment-preview">
                {attachment.thumbnailUrl ? (
                  <img
                    src={attachment.thumbnailUrl}
                    alt={`${attachment.name}缩略图`}
                    width={48}
                    height={48}
                  />
                ) : (
                  <PictureOutlined aria-hidden="true" />
                )}
              </span>
              <span className="chat-composer__attachment-copy">
                <Typography.Text ellipsis>{attachment.name}</Typography.Text>
                <Tag
                  color={
                    attachment.status === 'READY'
                      ? 'success'
                      : attachment.status === 'FAILED'
                        ? 'error'
                        : 'processing'
                  }
                >
                  {ATTACHMENT_STATUS_LABELS[attachment.status]}
                </Tag>
                {attachment.status === 'UPLOADING' ? (
                  <Progress
                    percent={attachment.progressPercent ?? 0}
                    size="small"
                    showInfo={false}
                  />
                ) : null}
                {attachment.errorMessage ? (
                  <Typography.Text type="danger">
                    {attachment.errorMessage}
                  </Typography.Text>
                ) : null}
              </span>
              <Button
                type="text"
                danger
                htmlType="button"
                icon={<DeleteOutlined />}
                aria-label={`删除本次附件 ${attachment.name}`}
                disabled={
                  !onRemoveAttachment ||
                  attachment.status === 'UPLOADING'
                }
                onClick={() => onRemoveAttachment?.(attachment.assetId)}
              />
            </li>
          ))}
        </ul>
      ) : null}
      {attachments.length > 0 && hasSelectedProduct ? (
        <Typography.Text className="chat-composer__source-notice" type="warning">
          本次将以附件为准，不使用右侧商品资料。
        </Typography.Text>
      ) : null}
      {attachments.length > 0 ? (
        <Typography.Text type="secondary" className="chat-composer__source-notice">
          已选 {attachments.length} 张，{attachments.filter((item) => item.status === 'READY').length} 张可使用。仅用于下一条消息，不修改商品资料。
        </Typography.Text>
      ) : null}
      {isGenerating ? (
        <Typography.Text className="chat-composer__pending-notice" role="status">
          {normalizedValue ? '这段内容尚未发送。' : '正在回复，可先编辑下一条消息。'}停止回复不会取消已确认的后台任务。
        </Typography.Text>
      ) : null}
      <div className="chat-composer__footer">
        <div className="chat-composer__footer-copy">
          {useAddMenu ? <Dropdown trigger={['click']} menu={{ items: addItems }}>
            <Button type="text" htmlType="button" icon={<PlusOutlined />} aria-label="添加资料" />
          </Dropdown> : <>
          <Button
            type="text"
            htmlType="button"
            icon={<PaperClipOutlined />}
            disabled={
              !attachmentUploadEnabled ||
              !onSelectAttachmentFiles ||
              attachments.length >= 8
            }
            onClick={() => attachmentInputRef.current?.click()}
          >
            上传本次图片
          </Button>
          <Button
            type="text"
            htmlType="button"
            icon={<PictureOutlined />}
            disabled={
              !onChooseAttachmentAssets ||
              attachments.length >= 8
            }
            onClick={onChooseAttachmentAssets}
          >
            从资产中心选择
          </Button>
          </>}
          {footerFeedback ? (
            <Typography.Text
              type={sendFeedback ? 'danger' : 'secondary'}
              role="status"
              aria-live="polite"
            >
              {footerFeedback}
            </Typography.Text>
          ) : null}
          {attachmentUploadDisabledReason ? <Typography.Text type="secondary">{attachmentUploadDisabledReason}</Typography.Text> : null}
        </div>
        {isGenerating ? (
          <div className="chat-composer__generation-actions">
          <Button
            danger
            htmlType="button"
            icon={<StopOutlined />}
            aria-label="停止当前回复"
            loading={stopPending}
            onClick={onStop}
          >
            停止当前回复
          </Button>
          {normalizedValue && onStopAndSend ? (
            <Button htmlType="button" type="primary" disabled={!attachmentsReady || stopPending}
              onClick={() => onStopAndSend(normalizedValue)}>
              停止当前回复并发送
            </Button>
          ) : null}
          </div>
        ) : (
          <Button
            type="primary"
            htmlType="submit"
            icon={<SendOutlined />}
            aria-label="发送"
            disabled={!canSend}
          >
            发送
          </Button>
        )}
      </div>
    </form>
  )
}

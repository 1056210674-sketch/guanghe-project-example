import { FileTextOutlined } from '@ant-design/icons'
import { Alert, Button, InputNumber, Modal, Select, Space, Typography } from 'antd'
import { useState } from 'react'

export interface ArticleFullMode { spaceId: string; productId?: string; maxPoints: number }
interface Props { projectId?: string; productId?: string; spaces: readonly { id: string; name: string }[]; value?: ArticleFullMode; disabled: boolean; initiallyOpen?: boolean; onChange: (value?: ArticleFullMode) => void }

/** Choosing a mode is not execution. Sending the next user message invokes exactly one article. */
export function ArticleFullModeControl(props: Props) {
  const [open, setOpen] = useState(Boolean(props.initiallyOpen))
  const [spaceId, setSpaceId] = useState(props.value?.spaceId ?? props.projectId)
  const [points, setPoints] = useState<number | null>(props.value?.maxPoints ?? null)
  return <>
    <Space size={4}><Button size="small" icon={<FileTextOutlined />} disabled={props.disabled} onClick={() => setOpen(true)}>{props.value ? `完整文章 · ≤${props.value.maxPoints}积分` : '完整文章'}</Button>
      {props.value ? <Button size="small" type="link" disabled={props.disabled} onClick={() => props.onChange(undefined)}>切回聊天</Button> : null}</Space>
    <Modal title="完整文章模式" open={open} onCancel={() => setOpen(false)} okText="使用全文模式" cancelText="取消" okButtonProps={{ disabled: props.disabled || !spaceId || !points || !Number.isSafeInteger(points) }} onOk={() => {
      if (!spaceId || !points || !Number.isSafeInteger(points) || points < 1 || points > 100000) return
      props.onChange({ spaceId, productId: props.productId, maxPoints: points }); setOpen(false)
    }}>
      {props.projectId ? <Typography.Paragraph>资料项目：{props.spaces.find((item) => item.id === props.projectId)?.name ?? '当前项目'}</Typography.Paragraph> : <Select aria-label="全文所属项目" placeholder="选择文章所属项目" value={spaceId} options={props.spaces.map((item) => ({ value: item.id, label: item.name }))} onChange={setSpaceId} style={{ width: '100%', marginBottom: 16 }} />}
      <Typography.Paragraph>每篇创作积分上限</Typography.Paragraph><InputNumber aria-label="全文创作积分上限" min={1} max={100000} precision={0} value={points} onChange={setPoints} />
      <Alert style={{ marginTop: 16 }} type="info" title="下一次发送即开始准备一篇完整文章" description="先完成必要的资料核对，再连续生成标题、结构和正文。超过本次上限不执行；文章不会自动发布。切回聊天即可聊其他内容。" />
    </Modal>
  </>
}

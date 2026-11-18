import { CopyOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { Alert, Button, Modal, Typography } from 'antd'
import { useState } from 'react'
import { flushSync } from 'react-dom'
import type { OneTimePasswordView } from '../types'

export function OneTimePasswordModal({
  credential,
  onClose,
}: {
  credential?: OneTimePasswordView
  onClose: () => void
}) {
  const [copied, setCopied] = useState(false)
  if (!credential) return null

  const copy = async () => {
    await navigator.clipboard.writeText(credential.temporaryPassword)
    setCopied(true)
  }
  const close = () => {
    flushSync(() => {
      setCopied(false)
      onClose()
    })
  }
  return (
    <Modal
      title="一次性临时密码"
      open
      footer={<Button type="primary" onClick={close}>我已安全保存并关闭</Button>}
      closable={false}
      mask={{ closable: false }}
      keyboard={false}
      destroyOnHidden
    >
      <div className="admin-one-time-password">
        <Alert
          type="warning"
          showIcon
          icon={<SafetyCertificateOutlined />}
          title="请立即安全转交给用户，关闭后无法再次查看"
        />
        <Typography.Text type="secondary">账号：{credential.label}</Typography.Text>
        <div className="admin-one-time-password__value" aria-label="一次性临时密码">
          <code>{credential.temporaryPassword}</code>
          <Button icon={<CopyOutlined />} onClick={() => void copy()}>{copied ? '已复制' : '复制临时密码'}</Button>
        </div>
      </div>
    </Modal>
  )
}

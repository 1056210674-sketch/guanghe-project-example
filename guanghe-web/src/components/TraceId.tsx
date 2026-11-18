import { CopyOutlined } from '@ant-design/icons'
import { Button, Space, Typography } from 'antd'
import { useState } from 'react'
import { canViewDiagnosticTrace, useAuthStore } from '../stores/authStore'

interface TraceIdProps {
  traceId?: string
}

export function TraceId({ traceId }: TraceIdProps) {
  const [copied, setCopied] = useState(false)
  const visibleToAdmin = useAuthStore(canViewDiagnosticTrace)

  if (!traceId || !visibleToAdmin) {
    return null
  }

  const copyTraceId = async () => {
    try {
      await navigator.clipboard.writeText(traceId)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  return (
    <Space className="trace-id" size={4} wrap>
      <Typography.Text type="secondary">traceId：{traceId}</Typography.Text>
      <Button
        type="link"
        size="small"
        icon={<CopyOutlined />}
        onClick={() => void copyTraceId()}
      >
        {copied ? '已复制' : '复制'}
      </Button>
    </Space>
  )
}

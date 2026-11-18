import { Alert, Modal, Typography } from 'antd'
import { visibleDiagnosticTraceId } from '../../../shared/tracePresentation'

export interface DeleteConfirmationProps {
  open: boolean
  entityLabel: string
  entityName?: string
  impact: string
  confirming?: boolean
  error?: {
    message: string
    traceId?: string
  } | null
  onCancel: () => void
  onConfirm: () => void
}

export function DeleteConfirmation({
  open,
  entityLabel,
  entityName,
  impact,
  confirming = false,
  error,
  onCancel,
  onConfirm,
}: DeleteConfirmationProps) {
  return (
    <Modal
      title={`确认删除${entityLabel}`}
      open={open}
      okText="确认删除"
      cancelText="取消"
      okButtonProps={{ danger: true, loading: confirming }}
      cancelButtonProps={{ disabled: confirming }}
      closable={!confirming}
      mask={{ closable: !confirming }}
      onCancel={() => {
        if (!confirming) {
          onCancel()
        }
      }}
      onOk={onConfirm}
      destroyOnHidden
    >
      <div className="business-delete-confirmation">
        <Typography.Paragraph>
          {entityName ? (
            <>
              即将永久删除{entityLabel}“
              <Typography.Text strong>{entityName}</Typography.Text>”。
            </>
          ) : (
            `即将永久删除该${entityLabel}。`
          )}
        </Typography.Paragraph>
        <Alert
          type="warning"
          showIcon
          message="此操作不可撤销"
          description={impact}
        />
        {error ? (
          <Alert
            type="error"
            showIcon
            message="删除失败"
            description={
              <div>
                <div>{error.message}</div>
                {visibleDiagnosticTraceId(error.traceId) ? (
                  <Typography.Text type="secondary">
                    traceId：{error.traceId}
                  </Typography.Text>
                ) : null}
              </div>
            }
          />
        ) : null}
      </div>
    </Modal>
  )
}

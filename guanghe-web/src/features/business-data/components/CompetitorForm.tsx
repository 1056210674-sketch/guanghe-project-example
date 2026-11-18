import { Alert, Button, Card, Form, Input, Select, Space, Typography } from 'antd'
import { visibleDiagnosticTraceId } from '../../../shared/tracePresentation'
import type {
  BusinessSpaceOption,
  BusinessViewError,
  CompetitorFormValues,
} from '../types'

export interface CompetitorFormProps {
  mode: 'create' | 'edit'
  businessSpaceOptions: BusinessSpaceOption[]
  initialValues?: Partial<CompetitorFormValues>
  submitting?: boolean
  disabled?: boolean
  error?: BusinessViewError | null
  onSubmit: (values: CompetitorFormValues) => void
  onCancel: () => void
}

export function CompetitorForm({
  mode,
  businessSpaceOptions,
  initialValues,
  submitting = false,
  disabled = false,
  error,
  onSubmit,
  onCancel,
}: CompetitorFormProps) {
  return (
    <Form<CompetitorFormValues>
      className="business-form"
      layout="vertical"
      requiredMark="optional"
      initialValues={{ aliases: [], ...initialValues }}
      onFinish={(values) => {
        if (!disabled) {
          onSubmit(values)
        }
      }}
      disabled={disabled}
    >
      {error ? (
        <Alert
          className="business-form__error"
          type="error"
          showIcon
          message="提交失败"
          description={
            <div>
              <div>{error.message}</div>
              {visibleDiagnosticTraceId(error.traceId) ? (
                <Typography.Text type="secondary">traceId：{error.traceId}</Typography.Text>
              ) : null}
            </div>
          }
        />
      ) : null}

      <Card className="business-form-section" title="竞品信息">
        <Form.Item
          label="经营空间"
          name="businessSpaceId"
          rules={[{ required: true, message: '请选择经营空间' }]}
        >
          <Select
            showSearch
            disabled={mode === 'edit'}
            optionFilterProp="label"
            placeholder="请选择经营空间"
            options={businessSpaceOptions.map((option) => ({
              label: option.name,
              value: option.id,
            }))}
          />
        </Form.Item>

        <Form.Item
          label="竞品名称"
          name="name"
          rules={[{ required: true, message: '请输入竞品名称' }]}
        >
          <Input maxLength={120} placeholder="请输入竞品名称" showCount />
        </Form.Item>

        <Form.Item
          label="竞品别名"
          name="aliases"
          extra="输入别名后按回车，可添加多个。"
        >
          <Select mode="tags" tokenSeparators={[',', '，']} placeholder="添加竞品别名" />
        </Form.Item>

        <Form.Item
          label="竞品关系"
          name="competitorType"
          rules={[{ required: true, message: '请选择竞品关系' }]}
        >
          <Select placeholder="请选择竞品关系">
            <Select.Option value="DIRECT">直接竞品</Select.Option>
            <Select.Option value="INDIRECT">间接竞品</Select.Option>
            <Select.Option value="SUBSTITUTE">替代品</Select.Option>
          </Select>
        </Form.Item>

        <Form.Item label="竞品说明" name="description">
          <Input.TextArea
            maxLength={1000}
            autoSize={{ minRows: 4, maxRows: 8 }}
            placeholder="请输入竞品说明"
            showCount
          />
        </Form.Item>
      </Card>

      <div className="business-form-actions">
        <Space>
          <Button onClick={onCancel} disabled={submitting}>
            取消
          </Button>
          <Button
            type="primary"
            htmlType="submit"
            loading={submitting}
            disabled={disabled}
          >
            {mode === 'create' ? '创建竞品' : '保存修改'}
          </Button>
        </Space>
      </div>
    </Form>
  )
}

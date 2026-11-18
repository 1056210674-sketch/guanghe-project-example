import { Alert, Form, Input, Modal, Select, Typography } from 'antd'
import { visibleDiagnosticTraceId } from '../../../shared/tracePresentation'
import type {
  GeoSelectOption,
  GeoTopicFormValues,
  GeoViewError,
} from '../types'

export interface GeoTopicFormDialogProps {
  open: boolean
  mode: 'create' | 'edit'
  spaces: readonly GeoSelectOption[]
  products: readonly GeoSelectOption[]
  productsLoading?: boolean
  initialValues?: GeoTopicFormValues
  submitting?: boolean
  error?: GeoViewError | null
  onSpaceChange: (spaceId: string) => void
  onCancel: () => void
  onSubmit: (values: GeoTopicFormValues) => void
}

export function GeoTopicFormDialog({
  open,
  mode,
  spaces,
  products,
  productsLoading = false,
  initialValues,
  submitting = false,
  error,
  onSpaceChange,
  onCancel,
  onSubmit,
}: GeoTopicFormDialogProps) {
  const [form] = Form.useForm<GeoTopicFormValues>()
  const selectedSpaceId = Form.useWatch('spaceId', form)

  return (
    <Modal
      title={mode === 'create' ? '新建 GEO 专题' : '编辑 GEO 专题'}
      open={open}
      okText={mode === 'create' ? '创建专题' : '保存修改'}
      cancelText="取消"
      okButtonProps={{ loading: submitting }}
      cancelButtonProps={{ disabled: submitting }}
      closable={!submitting}
      mask={{ closable: !submitting }}
      destroyOnHidden
      onCancel={() => {
        if (!submitting) onCancel()
      }}
      onOk={() => form.submit()}
    >
      <Form<GeoTopicFormValues>
        form={form}
        layout="vertical"
        initialValues={initialValues}
        onFinish={onSubmit}
      >
        {error ? (
          <Alert
            className="geo-form-alert"
            type="error"
            showIcon
            title={error.message}
            description={
              visibleDiagnosticTraceId(error.traceId) ? (
                <Typography.Text type="secondary">
                  traceId：{error.traceId}
                </Typography.Text>
              ) : undefined
            }
          />
        ) : null}
        <Form.Item
          name="spaceId"
          label="经营空间"
          rules={[{ required: true, message: '请选择经营空间' }]}
        >
          <Select
            placeholder="选择经营空间"
            disabled={mode === 'edit'}
            options={spaces.map((space) => ({ value: space.id, label: space.label }))}
            onChange={(spaceId: string) => {
              form.setFieldValue('productId', undefined)
              onSpaceChange(spaceId)
            }}
          />
        </Form.Item>
        <Form.Item name="productId" label="关联商品（可选）">
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            loading={productsLoading}
            disabled={!selectedSpaceId}
            placeholder="仅显示当前经营空间的商品"
            options={products.map((product) => ({ value: product.id, label: product.label }))}
          />
        </Form.Item>
        <Form.Item
          name="name"
          label="专题名称"
          rules={[
            { required: true, whitespace: true, message: '请输入专题名称' },
            { max: 100, message: '专题名称不能超过100个字符' },
          ]}
        >
          <Input maxLength={100} showCount placeholder="例如：便携咖啡机品牌发现" />
        </Form.Item>
        <Form.Item
          name="region"
          label="区域（可选）"
          rules={[{ max: 100, message: '区域不能超过100个字符' }]}
        >
          <Input maxLength={100} showCount placeholder="例如：中国大陆" />
        </Form.Item>
      </Form>
    </Modal>
  )
}

import {
  DeleteOutlined,
  PlusOutlined,
} from '@ant-design/icons'
import { Alert, Button, Card, Form, Input, Select, Space, Typography } from 'antd'
import { visibleDiagnosticTraceId } from '../../../shared/tracePresentation'
import type {
  BusinessSpaceOption,
  BusinessViewError,
  ProductFormValues,
  ProductIdentityAssetRole,
  ProductIdentityAssetView,
} from '../types'
import { ProductIdentityAssetManager } from './ProductIdentityAssetManager'

export interface ProductFormProps {
  mode: 'create' | 'edit'
  businessSpaceOptions: BusinessSpaceOption[]
  initialValues?: Partial<ProductFormValues>
  submitting?: boolean
  disabled?: boolean
  error?: BusinessViewError | null
  onSubmit: (values: ProductFormValues) => void
  onCancel: () => void
  identityAssets?: readonly ProductIdentityAssetView[]
  identityAssetsComplete?: boolean
  identityAssetManagementEnabled?: boolean
  identityAssetUploadEnabled?: boolean
  identityAssetDisabledReason?: string
  identityAssetUploadDisabledReason?: string
  identityAssetError?: BusinessViewError | null
  identityAssetUploads?: readonly {
    id: string
    name: string
    progressPercent: number
    errorMessage?: string
  }[]
  onUploadIdentityAssets?: (files: readonly File[]) => void
  onChooseAssets?: () => void
  onIdentityAssetRoleChange?: (
    assetId: string,
    role: ProductIdentityAssetRole,
  ) => void
  onMoveIdentityAsset?: (assetId: string, direction: 'UP' | 'DOWN') => void
  onUnbindIdentityAsset?: (assetId: string) => void
}

export function ProductForm({
  mode,
  businessSpaceOptions,
  initialValues,
  submitting = false,
  disabled = false,
  error,
  onSubmit,
  onCancel,
  identityAssets = [],
  identityAssetsComplete = false,
  identityAssetManagementEnabled = false,
  identityAssetUploadEnabled = false,
  identityAssetDisabledReason,
  identityAssetUploadDisabledReason,
  identityAssetError,
  identityAssetUploads,
  onUploadIdentityAssets,
  onChooseAssets,
  onIdentityAssetRoleChange,
  onMoveIdentityAsset,
  onUnbindIdentityAsset,
}: ProductFormProps) {
  return (
    <Form<ProductFormValues>
      className="business-form"
      layout="vertical"
      requiredMark="optional"
      initialValues={{ aliases: [], parameters: [], ...initialValues }}
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

      <Card className="business-form-section" title="归属与基本信息">
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
        <div className="business-form-grid business-form-grid--two">
          <Form.Item
            label="商品名称"
            name="name"
            rules={[{ required: true, message: '请输入商品名称' }]}
          >
            <Input maxLength={120} placeholder="请输入商品名称" showCount />
          </Form.Item>
          <Form.Item label="品类" name="category">
            <Input maxLength={80} placeholder="请输入商品品类" showCount />
          </Form.Item>
        </div>
        <Form.Item
          label="商品别名"
          name="aliases"
          extra="输入别名后按回车，可添加多个。"
        >
          <Select mode="tags" tokenSeparators={[',', '，']} placeholder="添加商品别名" />
        </Form.Item>
        <Form.Item label="型号或规格" name="modelOrSpecification">
          <Input maxLength={120} placeholder="请输入型号或规格" showCount />
        </Form.Item>
      </Card>

      <Card className="business-form-section" title="参数与卖点">
        <Form.List name="parameters">
          {(fields, { add, remove }) => (
            <div className="business-parameter-list">
              {fields.map((field) => (
                <div className="business-parameter-row" key={field.key}>
                  <Form.Item
                    {...field}
                    label="参数名称"
                    name={[field.name, 'name']}
                    rules={[{ required: true, message: '请输入参数名称' }]}
                  >
                    <Input maxLength={80} placeholder="例如：材质" />
                  </Form.Item>
                  <Form.Item
                    {...field}
                    label="参数值"
                    name={[field.name, 'value']}
                    rules={[{ required: true, message: '请输入参数值' }]}
                  >
                    <Input maxLength={160} placeholder="请输入参数值" />
                  </Form.Item>
                  <Button
                    className="business-parameter-row__remove"
                    type="text"
                    danger
                    icon={<DeleteOutlined />}
                    aria-label="删除该参数"
                    onClick={() => remove(field.name)}
                  />
                </div>
              ))}
              <Button type="dashed" icon={<PlusOutlined />} onClick={() => add()}>
                添加参数
              </Button>
            </div>
          )}
        </Form.List>
        <Form.Item label="核心卖点" name="sellingPoints">
          <Input.TextArea
            maxLength={1200}
            autoSize={{ minRows: 4, maxRows: 8 }}
            placeholder="描述商品核心卖点"
            showCount
          />
        </Form.Item>
      </Card>

      <Card className="business-form-section" title="使用上下文">
        <Form.Item label="目标人群" name="targetAudience">
          <Input.TextArea
            maxLength={600}
            autoSize={{ minRows: 3, maxRows: 6 }}
            placeholder="描述商品适用人群"
            showCount
          />
        </Form.Item>
        <Form.Item label="使用场景" name="usageScenarios">
          <Input.TextArea
            maxLength={800}
            autoSize={{ minRows: 3, maxRows: 6 }}
            placeholder="描述商品常见使用场景"
            showCount
          />
        </Form.Item>
        <Form.Item label="注意事项" name="precautions">
          <Input.TextArea
            maxLength={800}
            autoSize={{ minRows: 3, maxRows: 6 }}
            placeholder="填写创作和使用时需要注意的事项"
            showCount
          />
        </Form.Item>
      </Card>

      <Card className="business-form-section" title="商品素材">
        <ProductIdentityAssetManager
          assets={identityAssets}
          complete={identityAssetsComplete}
          managementEnabled={identityAssetManagementEnabled}
          uploadEnabled={identityAssetUploadEnabled}
          uploadDisabledReason={identityAssetUploadDisabledReason}
          disabledReason={
            identityAssetDisabledReason ??
            (mode === 'create'
              ? '请先保存商品基础资料，保存后即可添加商品图片。'
              : '商品图片接口正在对齐，暂时不能调整。')
          }
          error={identityAssetError}
          uploads={identityAssetUploads}
          onUploadFiles={onUploadIdentityAssets}
          onSelectAssets={onChooseAssets}
          onRoleChange={onIdentityAssetRoleChange}
          onMove={onMoveIdentityAsset}
          onUnbind={onUnbindIdentityAsset}
        />
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
            {mode === 'create' ? '创建商品' : '保存修改'}
          </Button>
        </Space>
      </div>
    </Form>
  )
}

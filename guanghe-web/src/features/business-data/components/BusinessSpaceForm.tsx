import { FileImageOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Form, Input, Select, Space, Typography } from 'antd'
import { visibleDiagnosticTraceId } from '../../../shared/tracePresentation'
import type { BusinessSpaceFormValues, BusinessViewError } from '../types'

export interface BusinessSpaceFormProps {
  mode: 'create' | 'edit'
  initialValues?: Partial<BusinessSpaceFormValues>
  submitting?: boolean
  disabled?: boolean
  error?: BusinessViewError | null
  onSubmit: (values: BusinessSpaceFormValues) => void
  onCancel: () => void
  onChooseLogoAsset?: () => void
}

export function BusinessSpaceForm({
  mode,
  initialValues,
  submitting = false,
  disabled = false,
  error,
  onSubmit,
  onCancel,
  onChooseLogoAsset,
}: BusinessSpaceFormProps) {
  return (
    <Form<BusinessSpaceFormValues>
      className="business-form"
      layout="vertical"
      requiredMark="optional"
      initialValues={{
        brandAliases: [],
        forbiddenExpressions: [],
        ...initialValues,
      }}
      onFinish={onSubmit}
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

      <Card className="business-form-section" title="基本信息">
        <div className="business-form-grid business-form-grid--two">
          <Form.Item
            label="经营空间名称"
            name="name"
            rules={[{ required: true, message: '请输入经营空间名称' }]}
          >
            <Input maxLength={80} placeholder="请输入经营空间名称" showCount />
          </Form.Item>
          <Form.Item
            label="品牌名称"
            name="brandName"
            rules={[{ required: true, message: '请输入品牌名称' }]}
          >
            <Input maxLength={80} placeholder="请输入品牌名称" showCount />
          </Form.Item>
        </div>

        <Form.Item
          label="品牌别名"
          name="brandAliases"
          extra="输入别名后按回车，可添加多个。"
        >
          <Select mode="tags" tokenSeparators={[',', '，']} placeholder="添加品牌别名" />
        </Form.Item>

        <Form.Item name="logoAssetReference" hidden>
          <Input />
        </Form.Item>
        <div className="business-asset-reference">
          <div className="business-asset-reference__icon" aria-hidden="true">
            <FileImageOutlined />
          </div>
          <div className="business-asset-reference__copy">
            <Typography.Text strong>品牌 Logo</Typography.Text>
            <Typography.Text type="secondary">
              品牌 Logo 由资产中心统一管理。
            </Typography.Text>
          </div>
          <Button onClick={onChooseLogoAsset} disabled={!onChooseLogoAsset}>
            从资产中心选择
          </Button>
        </div>
      </Card>

      <Card className="business-form-section" title="品牌经营背景">
        <Form.Item label="品牌简介" name="brandIntroduction">
          <Input.TextArea
            maxLength={1000}
            autoSize={{ minRows: 4, maxRows: 8 }}
            placeholder="请输入品牌定位、背景与核心信息"
            showCount
          />
        </Form.Item>
        <Form.Item label="目标人群" name="targetAudience">
          <Input.TextArea
            maxLength={600}
            autoSize={{ minRows: 3, maxRows: 6 }}
            placeholder="描述品牌主要服务的人群"
            showCount
          />
        </Form.Item>
        <Form.Item label="核心卖点" name="coreSellingPoints">
          <Input.TextArea
            maxLength={1000}
            autoSize={{ minRows: 4, maxRows: 8 }}
            placeholder="描述品牌核心价值与差异化卖点"
            showCount
          />
        </Form.Item>
      </Card>

      <Card className="business-form-section" title="表达约束">
        <Form.Item
          label="禁用表达"
          name="forbiddenExpressions"
          extra="输入表达后按回车，可添加多个。"
        >
          <Select
            mode="tags"
            tokenSeparators={[',', '，']}
            placeholder="添加不应在创作内容中出现的表达"
          />
        </Form.Item>
      </Card>

      <div className="business-form-actions">
        <Space>
          <Button onClick={onCancel} disabled={submitting}>
            取消
          </Button>
          <Button type="primary" htmlType="submit" loading={submitting}>
            {mode === 'create' ? '创建经营空间' : '保存修改'}
          </Button>
        </Space>
      </div>
    </Form>
  )
}

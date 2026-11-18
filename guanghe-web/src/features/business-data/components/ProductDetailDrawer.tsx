import { EditOutlined, PictureOutlined } from '@ant-design/icons'
import { Alert, Button, Descriptions, Divider, Drawer, Select, Space, Tag, Typography } from 'antd'
import { useState } from 'react'
import { ErrorState, PageLoading } from '../../../components/GlobalState'
import { visibleDiagnosticTraceId } from '../../../shared/tracePresentation'
import type { BusinessViewError, ProductDetailView } from '../types'

export interface ProductDetailDrawerProps {
  open: boolean
  product: ProductDetailView | null
  loading?: boolean
  error?: BusinessViewError | null
  actionError?: BusinessViewError | null
  onClose: () => void
  onRetry?: () => void
  onEdit?: (id: string) => void
  onSaveAliases?: (id: string, aliases: string[]) => void
  savingAliases?: boolean
}

function ProductImage({ url, alt }: { url?: string; alt: string }) {
  const [failed, setFailed] = useState(false)

  if (!url || failed) {
    return (
      <div className="product-detail-image product-detail-image--empty">
        <PictureOutlined aria-hidden="true" />
        <Typography.Text type="secondary">暂无图片</Typography.Text>
      </div>
    )
  }

  return (
    <div className="product-detail-image">
      <img
        src={url}
        alt={alt}
        width={152}
        height={152}
        loading="lazy"
        onError={() => setFailed(true)}
      />
    </div>
  )
}

function ProductAliasEditor({
  product,
  saving,
  onSave,
}: {
  product: ProductDetailView
  saving: boolean
  onSave?: (id: string, aliases: string[]) => void
}) {
  const [aliases, setAliases] = useState(product.aliases)

  return (
    <div className="product-alias-editor">
      <div className="product-detail-section-heading">
        <Typography.Text strong>商品别名</Typography.Text>
        {onSave ? (
          <Button
            type="link"
            size="small"
            loading={saving}
            onClick={() => onSave(product.id, aliases)}
          >
            保存别名
          </Button>
        ) : null}
      </div>
      {onSave ? (
        <Select
          mode="tags"
          value={aliases}
          tokenSeparators={[',', '，']}
          placeholder="添加商品别名"
          onChange={setAliases}
        />
      ) : aliases.length > 0 ? (
        <Space size={[4, 4]} wrap>
          {aliases.map((alias) => (
            <Tag key={alias}>{alias}</Tag>
          ))}
        </Space>
      ) : (
        <Typography.Text type="secondary">暂无别名</Typography.Text>
      )}
    </div>
  )
}

export function ProductDetailDrawer({
  open,
  product,
  loading = false,
  error,
  actionError,
  onClose,
  onRetry,
  onEdit,
  onSaveAliases,
  savingAliases = false,
}: ProductDetailDrawerProps) {
  return (
    <Drawer
      title="商品详情"
      size={600}
      open={open}
      onClose={onClose}
      extra={
        product && onEdit ? (
          <Button icon={<EditOutlined />} onClick={() => onEdit(product.id)}>
            编辑商品
          </Button>
        ) : null
      }
    >
      {loading ? (
        <PageLoading label="正在加载商品详情…" />
      ) : error ? (
        <ErrorState
          message={error.message}
          traceId={error.traceId}
          onRetry={onRetry}
        />
      ) : product ? (
        <div className="product-detail">
          {actionError ? (
            <Alert
              className="business-form__error"
              type="error"
              showIcon
              message="保存别名失败"
              description={
                <div>
                  <div>{actionError.message}</div>
                  {visibleDiagnosticTraceId(actionError.traceId) ? (
                    <Typography.Text type="secondary">
                      traceId：{actionError.traceId}
                    </Typography.Text>
                  ) : null}
                </div>
              }
            />
          ) : null}
          <div className="product-detail__hero">
            <ProductImage
              key={product.mainAssetUrl || 'empty-main'}
              url={product.mainAssetUrl}
              alt={`${product.name}主图`}
            />
            <div>
              <Typography.Title level={3}>{product.name}</Typography.Title>
              <Typography.Text type="secondary">
                {product.modelOrSpecification || '暂无型号或规格'}
              </Typography.Text>
              <Typography.Paragraph type="secondary">
                所属经营空间：{product.businessSpaceName}
              </Typography.Paragraph>
            </div>
          </div>

          <ProductAliasEditor
            key={product.id}
            product={product}
            saving={savingAliases}
            onSave={onSaveAliases}
          />

          <Divider />
          <Descriptions column={1} size="small" title="商品资料">
            <Descriptions.Item label="品类">{product.category || '—'}</Descriptions.Item>
            <Descriptions.Item label="核心卖点">
              {product.sellingPoints || '—'}
            </Descriptions.Item>
            <Descriptions.Item label="目标人群">
              {product.targetAudience || '—'}
            </Descriptions.Item>
            <Descriptions.Item label="使用场景">
              {product.usageScenarios || '—'}
            </Descriptions.Item>
            <Descriptions.Item label="注意事项">
              {product.precautions || '—'}
            </Descriptions.Item>
            <Descriptions.Item label="最近更新">
              {product.updatedAt || '—'}
            </Descriptions.Item>
          </Descriptions>

          <Divider />
          <div className="product-detail-section-heading">
            <Typography.Text strong>商品参数</Typography.Text>
          </div>
          {product.parameters.length > 0 ? (
            <Descriptions column={1} size="small" bordered>
              {product.parameters.map((parameter) => (
                <Descriptions.Item key={parameter.name} label={parameter.name}>
                  {parameter.value}
                </Descriptions.Item>
              ))}
            </Descriptions>
          ) : (
            <Typography.Text type="secondary">暂无参数</Typography.Text>
          )}

          <Divider />
          <div className="product-detail-section-heading">
            <Typography.Text strong>参考素材</Typography.Text>
          </div>
          {product.referenceAssetUrls.length > 0 ? (
            <div className="product-detail-assets">
              {product.referenceAssetUrls.map((url, index) => (
                <ProductImage key={url} url={url} alt={`${product.name}参考素材 ${index + 1}`} />
              ))}
            </div>
          ) : (
            <div className="business-asset-reference business-asset-reference--compact">
              <div className="business-asset-reference__icon" aria-hidden="true">
                <PictureOutlined />
              </div>
              <div className="business-asset-reference__copy">
                <Typography.Text strong>暂无素材引用</Typography.Text>
                <Typography.Text type="secondary">
                  当前商品暂无可用的素材引用。
                </Typography.Text>
              </div>
            </div>
          )}
        </div>
      ) : (
        <div className="product-detail__empty">
          <PictureOutlined aria-hidden="true" />
          <Typography.Text type="secondary">请选择商品查看详情</Typography.Text>
        </div>
      )}
    </Drawer>
  )
}

import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  DeleteOutlined,
  PictureOutlined,
  PlusOutlined,
  UploadOutlined,
} from '@ant-design/icons'
import { Alert, Button, Progress, Select, Tag, Typography } from 'antd'
import { useRef, useState } from 'react'
import { diagnosticTraceDescription } from '../../../shared/tracePresentation'
import type { ChangeEvent } from 'react'
import type {
  BusinessViewError,
  ProductIdentityAssetRole,
  ProductIdentityAssetView,
} from '../types'
import {
  imageUploadSelectionError,
  imageUploadValidationError,
} from '../../shared/imageUploadPolicy'

const MAX_IDENTITY_ASSETS = 20

const ROLE_LABELS: Record<ProductIdentityAssetRole, string> = {
  PRIMARY: '商品主图',
  WHITE_BACKGROUND: '白底商品图',
  DETAIL: '商品细节图',
  PACKAGE: '包装图',
}

const STATUS_LABELS: Record<ProductIdentityAssetView['status'], string> = {
  UPLOADING: '上传中',
  SCANNING: '处理中',
  READY: '可使用',
  UNAVAILABLE: '不可用',
}

export interface ProductIdentityAssetManagerProps {
  assets: readonly ProductIdentityAssetView[]
  complete: boolean
  managementEnabled: boolean
  uploadEnabled?: boolean
  uploadDisabledReason?: string
  disabledReason?: string
  error?: BusinessViewError | null
  uploads?: readonly {
    id: string
    name: string
    progressPercent: number
    errorMessage?: string
  }[]
  onUploadFiles?: (files: readonly File[]) => void
  onSelectAssets?: () => void
  onRoleChange?: (
    assetId: string,
    role: ProductIdentityAssetRole,
  ) => void
  onMove?: (assetId: string, direction: 'UP' | 'DOWN') => void
  onUnbind?: (assetId: string) => void
}

export function ProductIdentityAssetManager({
  assets,
  complete,
  managementEnabled,
  uploadEnabled = false,
  uploadDisabledReason,
  disabledReason,
  error,
  uploads = [],
  onUploadFiles,
  onSelectAssets,
  onRoleChange,
  onMove,
  onUnbind,
}: ProductIdentityAssetManagerProps) {
  const uploadInputRef = useRef<HTMLInputElement>(null)
  const [uploadSelectionError, setUploadSelectionError] = useState<string>()
  const orderedAssets = [...assets].sort((left, right) => left.order - right.order)

  const handleUpload = (event: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? [])
    event.target.value = ''
    setUploadSelectionError(undefined)
    if (files.length === 0 || !managementEnabled || !uploadEnabled) return

    const invalidFile = files
      .map((file) => ({ file, error: imageUploadValidationError(file) }))
      .find(({ error }) => error)
    if (invalidFile) {
      setUploadSelectionError(
        `${invalidFile.file.name}：${invalidFile.error}`,
      )
      return
    }

    const availableSlots = Math.max(0, MAX_IDENTITY_ASSETS - assets.length)
    if (availableSlots === 0) {
      setUploadSelectionError('商品最多关联 20 张图片，请先解绑不需要的图片。')
      return
    }

    const selectionError = imageUploadSelectionError(files, availableSlots)
    if (selectionError) setUploadSelectionError(selectionError)
    onUploadFiles?.(files.slice(0, availableSlots))
  }

  return (
    <section className="product-identity-assets" aria-label="商品身份图片">
      <header>
        <div>
          <Typography.Text strong>商品身份图片</Typography.Text>
          <Typography.Paragraph type="secondary">
            至少准备一张可用的商品主图或白底商品图，才能基于商品资料生成图片或视频。
          </Typography.Paragraph>
        </div>
        <Tag color={complete ? 'success' : 'warning'}>
          {complete ? '创作资料完整' : '缺少主图或白底图'}
        </Tag>
      </header>

      {!managementEnabled && disabledReason ? (
        <Alert type="info" showIcon title={disabledReason} />
      ) : null}
      {error ? (
        <Alert
          type="error"
          showIcon
          title={error.message}
          description={diagnosticTraceDescription(error.traceId)}
        />
      ) : null}
      {uploadSelectionError ? (
        <Alert type="warning" showIcon title={uploadSelectionError} />
      ) : null}

      {orderedAssets.length > 0 ? (
        <ol className="product-identity-assets__list">
          {orderedAssets.map((asset, index) => (
            <li key={asset.assetId}>
              <span className="product-identity-assets__thumbnail">
                {asset.thumbnailUrl ? (
                  <img
                    src={asset.thumbnailUrl}
                    alt={`${asset.name}缩略图`}
                    width={72}
                    height={72}
                  />
                ) : (
                  <PictureOutlined aria-hidden="true" />
                )}
              </span>
              <span className="product-identity-assets__copy">
                <Typography.Text strong ellipsis>
                  {asset.name}
                </Typography.Text>
                <Tag color={asset.status === 'READY' ? 'success' : 'default'}>
                  {STATUS_LABELS[asset.status]}
                </Tag>
              </span>
              <Select<ProductIdentityAssetRole>
                aria-label={`${asset.name}图片用途`}
                value={asset.role}
                disabled={!managementEnabled || !onRoleChange}
                options={Object.entries(ROLE_LABELS).map(([value, label]) => ({
                  value: value as ProductIdentityAssetRole,
                  label,
                }))}
                onChange={(role) => onRoleChange?.(asset.assetId, role)}
              />
              <span className="product-identity-assets__order-actions">
                <Button
                  type="text"
                  icon={<ArrowUpOutlined />}
                  aria-label={`上移 ${asset.name}`}
                  disabled={!managementEnabled || !onMove || index === 0}
                  onClick={() => onMove?.(asset.assetId, 'UP')}
                />
                <Button
                  type="text"
                  icon={<ArrowDownOutlined />}
                  aria-label={`下移 ${asset.name}`}
                  disabled={
                    !managementEnabled ||
                    !onMove ||
                    index === orderedAssets.length - 1
                  }
                  onClick={() => onMove?.(asset.assetId, 'DOWN')}
                />
                <Button
                  type="text"
                  danger
                  icon={<DeleteOutlined />}
                  aria-label={`解绑 ${asset.name}`}
                  disabled={!managementEnabled || !onUnbind}
                  onClick={() => onUnbind?.(asset.assetId)}
                />
              </span>
            </li>
          ))}
        </ol>
      ) : (
        <div className="product-identity-assets__empty">
          <PictureOutlined aria-hidden="true" />
          <Typography.Text type="secondary">还没有商品身份图片</Typography.Text>
        </div>
      )}

      {uploads.length > 0 ? (
        <ul className="product-identity-assets__uploads" aria-label="图片上传进度">
          {uploads.map((upload) => (
            <li key={upload.id}>
              <Typography.Text ellipsis>{upload.name}</Typography.Text>
              <Progress
                percent={upload.progressPercent}
                size="small"
                status={upload.errorMessage ? 'exception' : 'active'}
              />
              {upload.errorMessage ? (
                <Typography.Text type="danger">{upload.errorMessage}</Typography.Text>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}

      <div className="product-identity-assets__actions">
        <input
          ref={uploadInputRef}
          type="file"
          accept="image/jpeg,image/png"
          multiple
          tabIndex={-1}
          aria-hidden="true"
          onChange={handleUpload}
        />
        <Button
          icon={<UploadOutlined />}
          disabled={!managementEnabled || !uploadEnabled || !onUploadFiles}
          onClick={() => uploadInputRef.current?.click()}
        >
          上传图片
        </Button>
        <Button
          icon={<PlusOutlined />}
          disabled={!managementEnabled || !onSelectAssets}
          onClick={onSelectAssets}
        >
          从资产中心选择
        </Button>
        {!uploadEnabled && uploadDisabledReason ? (
          <Typography.Text type="secondary">
            {uploadDisabledReason}
          </Typography.Text>
        ) : null}
      </div>
    </section>
  )
}

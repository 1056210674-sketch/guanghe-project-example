import { CheckCircleFilled, PictureOutlined } from '@ant-design/icons'
import { Alert, Empty, Modal, Pagination, Spin, Typography } from 'antd'
import { useEffect, useEffectEvent, useMemo, useState } from 'react'
import { isApiError } from '../../../api/apiError'
import { errorTextWithDiagnosticTrace } from '../../../shared/tracePresentation'
import { useAssetDownloadUrls, useAssets } from '../live/assetQueries'

export interface ImageAssetPickerDialogProps {
  open: boolean
  title: string
  selectedAssetIds: readonly string[]
  businessSpaceId?: string
  maxSelection?: number
  onCancel: () => void
  onConfirm: (assetIds: readonly string[]) => void
}

function assetErrorMessage(error: unknown) {
  if (isApiError(error)) {
    return errorTextWithDiagnosticTrace(error.message, error.traceId)
  }
  return '图片资产加载失败，请稍后重试。'
}

function ImageAssetPickerDialogContent({
  open,
  title,
  selectedAssetIds,
  businessSpaceId,
  maxSelection = 20,
  onCancel,
  onConfirm,
}: ImageAssetPickerDialogProps) {
  const [page, setPage] = useState(1)
  const pageSize = 20
  const assetsQuery = useAssets({
    page,
    size: pageSize,
    type: 'IMAGE',
    spaceId: businessSpaceId,
  })
  const readyAssets = useMemo(
    () =>
      (assetsQuery.data?.items ?? []).filter(
        ({ status }) => status === 'READY',
      ),
    [assetsQuery.data?.items],
  )
  const downloads = useAssetDownloadUrls(readyAssets.map(({ id }) => id))
  const [selection, setSelection] = useState(() => [...selectedAssetIds])
  const selectedAssetIdsKey = selectedAssetIds.join('\u0000')
  const resetPickerState = useEffectEvent(() => {
    setSelection((current) =>
      current.length === selectedAssetIds.length &&
      current.every((assetId, index) => assetId === selectedAssetIds[index])
        ? current
        : [...selectedAssetIds],
    )
    setPage(1)
  })

  useEffect(() => {
    if (!open) return
    let cancelled = false
    queueMicrotask(() => {
      if (cancelled) return
      resetPickerState()
    })
    return () => {
      cancelled = true
    }
  }, [businessSpaceId, open, selectedAssetIdsKey])

  const toggle = (assetId: string) => {
    setSelection((current) => {
      if (current.includes(assetId)) {
        return current.filter((id) => id !== assetId)
      }
      if (current.length >= maxSelection) return current
      return [...current, assetId]
    })
  }

  return (
    <Modal
      open={open}
      title={title}
      okText={`确认选择（${selection.length}/${maxSelection}）`}
      cancelText="取消"
      okButtonProps={{ disabled: selection.length === 0 }}
      width={760}
      destroyOnHidden
      onCancel={onCancel}
      onOk={() => onConfirm(selection)}
    >
      {assetsQuery.error ? (
        <Alert
          type="error"
          showIcon
          title={assetErrorMessage(assetsQuery.error)}
        />
      ) : assetsQuery.isPending ? (
        <Spin description="正在加载图片资产…" />
      ) : readyAssets.length === 0 ? (
        <Empty description={assetsQuery.data?.total ? '此页暂无可使用的图片，请查看其他页' : '资产中心暂无可使用的图片'} />
      ) : (
        <ul className="image-asset-picker" aria-label="可选择的图片资产">
          {readyAssets.map((asset) => {
            const selected = selection.includes(asset.id)
            return (
              <li key={asset.id}>
                <button
                  type="button"
                  className={selected ? 'is-selected' : undefined}
                  aria-pressed={selected}
                  onClick={() => toggle(asset.id)}
                >
                  <span className="image-asset-picker__preview">
                    {downloads[asset.id]?.url ? (
                      <img
                        src={downloads[asset.id]?.url}
                        alt={`${asset.name}缩略图`}
                        width={112}
                        height={112}
                      />
                    ) : (
                      <PictureOutlined aria-hidden="true" />
                    )}
                    {selected ? <CheckCircleFilled aria-hidden="true" /> : null}
                  </span>
                  <Typography.Text ellipsis>{asset.name}</Typography.Text>
                </button>
              </li>
            )
          })}
        </ul>
      )}
      {(assetsQuery.data?.total ?? 0) > pageSize ? (
        <Pagination
          current={page}
          pageSize={pageSize}
          total={assetsQuery.data?.total ?? 0}
          showSizeChanger={false}
          onChange={setPage}
        />
      ) : null}
      <Typography.Paragraph type="secondary">
        按页显示当前账号中已处理完成的图片；翻页不会丢失已选内容，临时预览链接不会被保存。
      </Typography.Paragraph>
    </Modal>
  )
}

export const ImageAssetPickerDialog = ImageAssetPickerDialogContent

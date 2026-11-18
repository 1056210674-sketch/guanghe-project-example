import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import {
  ChatComposer,
  GeneratedImageResultGallery,
  GeneratedVideoResultPlayer,
  PaidTaskConfirmationCard,
} from '../features/chat'
import { ProductIdentityAssetManager } from '../features/business-data'
import { paidTaskConfirmationFixture } from './fixtures/chat'

describe('创作依据交互', () => {
  it('本次附件覆盖右侧商品，处理中禁止发送且删除只回传资产 ID', async () => {
    const user = userEvent.setup()
    const onRemoveAttachment = vi.fn()
    const onChooseAttachmentAssets = vi.fn()
    const { rerender } = render(
      <ChatComposer
        value="生成商品图"
        isGenerating={false}
        sendEnabled
        hasSelectedProduct
        attachments={[
          { assetId: 'asset-1', name: '本次正面图', status: 'PROCESSING' },
        ]}
        onChange={vi.fn()}
        onSend={vi.fn()}
        onStop={vi.fn()}
        attachmentUploadEnabled={false}
        attachmentUploadDisabledReason="直接上传接口尚未开放"
        onChooseAttachmentAssets={onChooseAttachmentAssets}
        onRemoveAttachment={onRemoveAttachment}
      />,
    )

    expect(screen.getByText('本次将以附件为准，不使用右侧商品资料。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled()
    expect(screen.getByRole('button', { name: /上传本次图片/ })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: /从资产中心选择/ }))
    expect(onChooseAttachmentAssets).toHaveBeenCalledOnce()

    rerender(
      <ChatComposer
        value="生成商品图"
        isGenerating={false}
        sendEnabled
        hasSelectedProduct
        attachments={[
          { assetId: 'asset-1', name: '本次正面图', status: 'READY' },
        ]}
        onChange={vi.fn()}
        onSend={vi.fn()}
        onStop={vi.fn()}
        attachmentUploadEnabled={false}
        attachmentUploadDisabledReason="直接上传接口尚未开放"
        onChooseAttachmentAssets={onChooseAttachmentAssets}
        onRemoveAttachment={onRemoveAttachment}
      />,
    )
    expect(screen.getByRole('button', { name: '发送' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: /删除本次附件/ }))
    expect(onRemoveAttachment).toHaveBeenCalledWith('asset-1')
  })

  it('确认卡展示冻结的创作依据和真实参考图', () => {
    render(
      <PaidTaskConfirmationCard
        confirmation={{
          ...paidTaskConfirmationFixture,
          creativeSourceMode: 'CURRENT_ATTACHMENTS',
          productName: undefined,
          rightSideProductOverridden: true,
        }}
        onEdit={vi.fn()}
        onConfirm={vi.fn()}
        onOpenRecharge={vi.fn()}
        onPrepareReestimate={vi.fn()}
        onOpenPaidTaskReferenceAsset={vi.fn()}
        onAddPaidTaskReferenceAsset={vi.fn()}
      />,
    )

    expect(screen.getByText('创作依据：本次附件')).toBeInTheDocument()
    expect(screen.getByText(/本次将以附件为准/)).toBeInTheDocument()
    expect(
      screen.getByText('测试确认卡参考素材'),
    ).toBeInTheDocument()
  })

  it('商品身份图展示完整状态、角色、排序和显式禁用的上传入口', async () => {
    const user = userEvent.setup()
    const onMove = vi.fn()
    const onUnbind = vi.fn()
    render(
      <ProductIdentityAssetManager
        assets={[
          {
            assetId: 'asset-1',
            name: '商品正面图',
            role: 'PRIMARY',
            order: 1,
            status: 'READY',
          },
          {
            assetId: 'asset-2',
            name: '商品包装图',
            role: 'PACKAGE',
            order: 2,
            status: 'READY',
          },
        ]}
        complete
        managementEnabled
        uploadEnabled={false}
        uploadDisabledReason="直接上传接口尚未开放"
        onSelectAssets={vi.fn()}
        onMove={onMove}
        onUnbind={onUnbind}
      />,
    )

    expect(screen.getByText('创作资料完整')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /上传图片/ })).toBeDisabled()
    expect(screen.getByText('直接上传接口尚未开放')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '上移 商品包装图' }))
    await user.click(screen.getByRole('button', { name: '解绑 商品包装图' }))
    expect(onMove).toHaveBeenCalledWith('asset-2', 'UP')
    expect(onUnbind).toHaveBeenCalledWith('asset-2')
  })

  it('商品身份图上传先在浏览器侧校验格式和大小，并提示 20 张上限', () => {
    const onUploadFiles = vi.fn()
    const { container, rerender } = render(
      <ProductIdentityAssetManager
        assets={[]}
        complete={false}
        managementEnabled
        uploadEnabled
        onUploadFiles={onUploadFiles}
      />,
    )

    const input = container.querySelector('input[type="file"]') as HTMLInputElement
    const invalidFile = new File(['not-an-image'], 'notes.txt', { type: 'text/plain' })
    fireEvent.change(input, { target: { files: [invalidFile] } })
    expect(screen.getByText(/notes\.txt：仅支持 PNG 或 JPEG 图片/)).toBeInTheDocument()
    expect(onUploadFiles).not.toHaveBeenCalled()

    const oversizedFile = new File(
      [new Uint8Array(20 * 1024 * 1024 + 1)],
      'oversized.png',
      { type: 'image/png' },
    )
    fireEvent.change(input, { target: { files: [oversizedFile] } })
    expect(screen.getByText(/oversized\.png：单张图片不能超过 20 MiB/)).toBeInTheDocument()
    expect(onUploadFiles).not.toHaveBeenCalled()

    const validFile = new File(['image'], 'coffee.png', { type: 'image/png' })
    fireEvent.change(input, { target: { files: [validFile] } })
    expect(onUploadFiles).toHaveBeenCalledWith([validFile])

    rerender(
      <ProductIdentityAssetManager
        assets={Array.from({ length: 20 }, (_, index) => ({
          assetId: `asset-${index}`,
          name: `图片 ${index + 1}`,
          role: 'DETAIL' as const,
          order: index + 1,
          status: 'READY' as const,
        }))}
        complete
        managementEnabled
        uploadEnabled
        onUploadFiles={onUploadFiles}
      />,
    )
    const fullInput = container.querySelector('input[type="file"]') as HTMLInputElement
    fireEvent.change(fullInput, { target: { files: [validFile] } })
    expect(screen.getByText('商品最多关联 20 张图片，请先解绑不需要的图片。')).toBeInTheDocument()
    expect(onUploadFiles).toHaveBeenCalledTimes(1)
  })

  it('本次附件生成结果提供显式关联入口，但接口未开放时不会伪造成功', () => {
    const { rerender } = render(
      <GeneratedImageResultGallery
        group={{
          id: 'images-1',
          title: '图片生成结果',
          creativeSourceMode: 'CURRENT_ATTACHMENTS',
          productAssociationEnabled: false,
          productAssociationDisabledReason: '待关联接口开放后可用',
          images: [
            {
              assetId: 'asset-1',
              name: '生成图',
              previewEnabled: true,
              downloadEnabled: true,
              saveToAssetsEnabled: true,
              selectEnabled: true,
            },
          ],
        }}
        onPreviewGeneratedImage={vi.fn()}
        onDownloadGeneratedImage={vi.fn()}
        onSaveGeneratedImageToAssets={vi.fn()}
        onSelectGeneratedImage={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: /关联到商品/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /关联到商品/ })).toHaveAttribute(
      'title',
      '待关联接口开放后可用',
    )

    rerender(
      <GeneratedVideoResultPlayer
        video={{
          assetId: 'video-1',
          name: '生成视频',
          persistedAsset: true,
          previewEnabled: true,
          creativeSourceMode: 'CURRENT_ATTACHMENTS',
          productAssociationEnabled: false,
          productAssociationDisabledReason: '待关联接口开放后可用',
        }}
        onOpenOriginal={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: /关联到商品/ })).toBeDisabled()
  })
})

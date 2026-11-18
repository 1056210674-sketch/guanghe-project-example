import { describe, expect, it } from 'vitest'
import {
  creativeGroundingErrorMessage,
  creativeSourceLabel,
  decideCreativeSource,
} from '../features/chat'

describe('创作依据选择', () => {
  it('当前消息有图片时固定优先使用本次附件且不混入右侧商品', () => {
    expect(
      decideCreativeSource({
        selectedProductId: 'product-1',
        attachments: [
          { assetId: 'asset-1', name: '正面图', status: 'READY' },
          { assetId: 'asset-2', name: '侧面图', status: 'READY' },
        ],
      }),
    ).toEqual({
      sourceMode: 'CURRENT_ATTACHMENTS',
      selectedProductId: undefined,
      referenceAssetIds: ['asset-1', 'asset-2'],
      ready: true,
      guidance: '本次将以附件为准，不使用右侧商品资料。',
    })
  })

  it('没有附件时使用已选商品，两者都没有时给出用户引导', () => {
    expect(
      decideCreativeSource({ attachments: [], selectedProductId: 'product-1' }),
    ).toMatchObject({
      sourceMode: 'CATALOG_PRODUCT',
      selectedProductId: 'product-1',
      ready: true,
    })
    expect(
      decideCreativeSource({ attachments: [], selectedProductId: undefined }),
    ).toEqual({
      referenceAssetIds: [],
      ready: false,
      guidance: '请选择商品，或上传本次要参考的图片。',
    })
  })

  it('附件未完成时不把部分资产交给任务，并翻译标准创作错误', () => {
    const decision = decideCreativeSource({
      selectedProductId: 'product-1',
      attachments: [
        { assetId: 'asset-1', name: '正面图', status: 'READY' },
        { assetId: 'asset-2', name: '侧面图', status: 'PROCESSING' },
      ],
    })
    expect(decision.ready).toBe(false)
    expect(decision.referenceAssetIds).toEqual([])
    expect(
      creativeGroundingErrorMessage(
        'PRODUCT_IDENTITY_ASSET_REQUIRED',
        'fallback',
      ),
    ).toContain('主图或白底图')
  })

  it('能区分上一轮上传图与上一张生成图', () => {
    expect(creativeSourceLabel('PREVIOUS_USER_ATTACHMENT')).toBe(
      '上一轮上传图',
    )
    expect(creativeSourceLabel('PREVIOUS_IMAGE_RESULT')).toBe('上一张生成图')
  })
})

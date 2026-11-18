import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/apiError'
import {
  toBusinessSpaceFormValues,
  toBusinessSpaceListItem,
  toBusinessSpaceSummaryView,
  toBusinessViewError,
  toCompetitorFormValues,
  toCompetitorListItem,
  toCreateCompetitorRequest,
  toCreateProductRequest,
  toProductDetailView,
  toProductListItem,
  toUpdateBusinessSpaceRequest,
  toUpdateCompetitorRequest,
  toUpdateProductRequest,
} from '../pages/business-data/viewAdapters'
import {
  catalogBusinessSpace,
  catalogBusinessSpacePage,
  catalogCompetitor,
  catalogCompetitorPage,
  catalogProduct,
  catalogProductPage,
  catalogTraceId,
} from './fixtures/catalogApi'

describe('Catalog 生成 DTO 到展示模型的适配', () => {
  it('经营空间列表只展示契约真实字段，完整度缺失时不编造', () => {
    const item = toBusinessSpaceListItem(catalogBusinessSpacePage.items[0])
    const summary = toBusinessSpaceSummaryView(catalogBusinessSpacePage)

    expect(item).toEqual({
      id: catalogBusinessSpace.id,
      name: catalogBusinessSpace.name,
      brandName: catalogBusinessSpace.brand.name,
      linkedProductCount: catalogBusinessSpace.productCount,
      updatedAt: catalogBusinessSpace.updatedAt,
    })
    expect(item.completeness).toBeUndefined()
    expect(summary).toEqual({ totalSpaces: catalogBusinessSpacePage.total })
    expect(summary.averageCompleteness).toBeUndefined()
    expect(summary.incompleteSpaces).toBeUndefined()
  })

  it('经营空间更新提交完整 brand 并保留表单未编辑的 tone 与当前 version', () => {
    const values = toBusinessSpaceFormValues(catalogBusinessSpace)
    const request = toUpdateBusinessSpaceRequest(
      { ...values, name: ' 更新后空间 ', brandName: ' 更新后品牌 ' },
      catalogBusinessSpace,
    )

    expect(request.version).toBe(catalogBusinessSpace.version)
    expect(request.name).toBe('更新后空间')
    expect(request.brand).toEqual({
      name: '更新后品牌',
      aliases: catalogBusinessSpace.brand.aliases,
      description: catalogBusinessSpace.brand.description,
      tone: catalogBusinessSpace.brand.tone,
      audience: catalogBusinessSpace.brand.audience,
      sellingPoints: catalogBusinessSpace.brand.sellingPoints,
      prohibitedClaims: catalogBusinessSpace.brand.prohibitedClaims,
    })
  })

  it('商品摘要不伪造别名或图片，详情映射 modelSpec、数组和参数', () => {
    const summary = toProductListItem(
      catalogProductPage.items[0],
      catalogBusinessSpace.name,
    )
    const detail = toProductDetailView(catalogProduct, catalogBusinessSpace.name)

    expect(summary.aliases).toBeUndefined()
    expect(summary.aliasCount).toBe(catalogProduct.aliases.length)
    expect(summary.mainAssetUrl).toBeUndefined()
    expect(summary.modelOrSpecification).toBe(catalogProduct.modelSpec)
    expect(detail.aliases).toEqual(catalogProduct.aliases)
    expect(detail.parameters).toEqual([{ name: '功率', value: '100W' }])
    expect(detail.referenceAssetUrls).toEqual([])
  })

  it('商品表单映射真实数组、parameters、modelSpec 和 current version', () => {
    const values = {
      businessSpaceId: catalogProduct.spaceId,
      name: ' 更新商品 ',
      aliases: ['别名'],
      category: '',
      modelOrSpecification: ' MODEL-02 ',
      parameters: [{ name: ' 功率 ', value: ' 120W ' }],
      sellingPoints: '卖点一\n卖点二',
      targetAudience: '人群一\n人群二',
      usageScenarios: '场景一',
      precautions: '禁用一',
    }

    expect(toCreateProductRequest(values)).toEqual({
      name: '更新商品',
      category: null,
      modelSpec: 'MODEL-02',
      sellingPoints: ['卖点一', '卖点二'],
      parameters: { 功率: '120W' },
      audience: ['人群一', '人群二'],
      usageScenarios: ['场景一'],
      prohibitedClaims: ['禁用一'],
    })
    expect(toUpdateProductRequest(values, catalogProduct)).toEqual({
      version: catalogProduct.version,
      ...toCreateProductRequest(values),
    })
  })

  it('竞品在 View competitorType 与 API type 之间显式映射', () => {
    const summary = toCompetitorListItem(
      catalogCompetitorPage.items[0],
      catalogBusinessSpace.name,
    )
    const values = toCompetitorFormValues(catalogCompetitor)

    expect(summary.competitorType).toBe('DIRECT')
    expect(summary.aliases).toBeUndefined()
    expect(summary.aliasCount).toBe(1)
    expect(toCreateCompetitorRequest(values)).toEqual({
      name: catalogCompetitor.name,
      type: 'DIRECT',
      description: catalogCompetitor.description,
      status: 'ACTIVE',
    })
    expect(toUpdateCompetitorRequest(values, catalogCompetitor)).toEqual({
      version: catalogCompetitor.version,
      name: catalogCompetitor.name,
      type: 'DIRECT',
      description: catalogCompetitor.description,
    })
  })

  it('错误适配保留 traceId，未知错误不伪造 traceId', () => {
    expect(
      toBusinessViewError(
        new ApiError({
          status: 409,
          code: 'RESOURCE_VERSION_CONFLICT',
          message: '资源版本已变化，请刷新后重试',
          traceId: catalogTraceId,
        }),
      ),
    ).toEqual({
      message: '资源版本已变化，请刷新后重试',
      traceId: catalogTraceId,
    })
    expect(toBusinessViewError(new Error('private detail'))).toEqual({
      message: '请求失败，请稍后重试。',
    })
  })
})

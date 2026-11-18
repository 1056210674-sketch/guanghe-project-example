import type {
  BusinessDataPermissions,
  BusinessListPagination,
  BusinessSpaceFormValues,
  BusinessSpaceListItemView,
  BusinessSpaceOption,
  CompetitorFormValues,
  CompetitorListItemView,
  ProductDetailView,
  ProductFormValues,
  ProductListItemView,
} from '../../features/business-data'

export const businessPagination: BusinessListPagination = {
  current: 1,
  pageSize: 10,
  total: 21,
}

export const fullBusinessPermissions: BusinessDataPermissions = {
  canCreate: true,
  canEdit: true,
  canDelete: true,
}

export const readOnlyBusinessPermissions: BusinessDataPermissions = {
  canCreate: false,
  canEdit: false,
  canDelete: false,
}

export const businessSpaceOption: BusinessSpaceOption = {
  id: 'space-test-001',
  name: '测试经营空间',
}

export const businessSpaceFixture: BusinessSpaceListItemView = {
  id: businessSpaceOption.id,
  name: businessSpaceOption.name,
  brandName: '测试品牌',
  brandAliases: ['测试品牌别名'],
  completeness: 75,
  linkedProductCount: 1,
  updatedAt: '2099-01-01 12:00',
}

export const businessSpaceFormFixture: BusinessSpaceFormValues = {
  name: businessSpaceOption.name,
  brandName: '测试品牌',
  brandAliases: ['测试品牌别名'],
  logoAssetReference: 'asset-reference-test-001',
  brandIntroduction: '用于组件测试的品牌简介。',
  targetAudience: '测试目标人群',
  coreSellingPoints: '测试核心卖点',
  forbiddenExpressions: ['测试禁用表达'],
}

export const productFixture: ProductListItemView = {
  id: 'product-test-001',
  businessSpaceId: businessSpaceOption.id,
  businessSpaceName: businessSpaceOption.name,
  name: '测试商品',
  aliases: ['测试商品别名'],
  category: '测试品类',
  modelOrSpecification: 'TEST-01',
  updatedAt: '2099-01-01 12:00',
}

export const productDetailFixture: ProductDetailView = {
  ...productFixture,
  aliases: [...(productFixture.aliases ?? [])],
  parameters: [{ name: '测试参数', value: '测试参数值' }],
  sellingPoints: '测试商品卖点',
  targetAudience: '测试商品人群',
  usageScenarios: '测试使用场景',
  precautions: '测试注意事项',
  referenceAssetUrls: [],
}

export const productFormFixture: ProductFormValues = {
  businessSpaceId: businessSpaceOption.id,
  name: productFixture.name,
  aliases: [...(productFixture.aliases ?? [])],
  category: productFixture.category,
  modelOrSpecification: productFixture.modelOrSpecification,
  parameters: [{ name: '测试参数', value: '测试参数值' }],
  sellingPoints: '测试商品卖点',
  targetAudience: '测试商品人群',
  usageScenarios: '测试使用场景',
  precautions: '测试注意事项',
}

export const competitorFixture: CompetitorListItemView = {
  id: 'competitor-test-001',
  businessSpaceId: businessSpaceOption.id,
  businessSpaceName: businessSpaceOption.name,
  name: '测试竞品',
  aliases: ['测试竞品别名'],
  competitorType: 'DIRECT',
  description: '测试竞品说明',
  status: '测试状态',
  updatedAt: '2099-01-01 12:00',
}

export const competitorFormFixture: CompetitorFormValues = {
  businessSpaceId: businessSpaceOption.id,
  name: competitorFixture.name,
  aliases: [...(competitorFixture.aliases ?? [])],
  competitorType: competitorFixture.competitorType,
  description: competitorFixture.description,
}

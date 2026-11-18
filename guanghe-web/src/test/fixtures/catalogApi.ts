import type {
  BusinessSpace,
  BusinessSpacePage,
  Competitor,
  CompetitorPage,
  Product,
  ProductPage,
} from '../../api/catalogApi'

export const catalogTraceId = '1234567890abcdef1234567890abcdef'
export const catalogSpaceId = '1900012345678901100'
export const catalogProductId = '1900012345678901200'
export const catalogCompetitorId = '1900012345678901300'

export const catalogBusinessSpace: BusinessSpace = {
  id: catalogSpaceId,
  name: '测试经营空间',
  brand: {
    name: '测试品牌',
    aliases: ['品牌别名'],
    description: '品牌说明',
    tone: ['专业'],
    audience: ['目标人群'],
    sellingPoints: ['核心卖点'],
    prohibitedClaims: ['禁用表达'],
  },
  logoAssetId: null,
  productCount: 1,
  competitorCount: 1,
  version: 7,
  createdAt: '2099-01-01T00:00:00Z',
  updatedAt: '2099-01-02T00:00:00Z',
}

export const catalogBusinessSpacePage: BusinessSpacePage = {
  items: [
    {
      id: catalogSpaceId,
      name: catalogBusinessSpace.name,
      brandName: catalogBusinessSpace.brand.name,
      productCount: catalogBusinessSpace.productCount,
      competitorCount: catalogBusinessSpace.competitorCount,
      version: catalogBusinessSpace.version,
      createdAt: catalogBusinessSpace.createdAt,
      updatedAt: catalogBusinessSpace.updatedAt,
    },
  ],
  page: 2,
  size: 50,
  total: 51,
  hasNext: false,
}

export const catalogProduct: Product = {
  id: catalogProductId,
  spaceId: catalogSpaceId,
  name: '测试商品',
  aliases: ['商品别名'],
  category: '测试品类',
  modelSpec: 'MODEL-01',
  sellingPoints: ['商品卖点'],
  parameters: { 功率: '100W' },
  audience: ['商品人群'],
  usageScenarios: ['使用场景'],
  prohibitedClaims: ['商品禁用表达'],
  primaryAssetId: null,
  version: 11,
  createdAt: '2099-01-01T00:00:00Z',
  updatedAt: '2099-01-02T00:00:00Z',
}

export const catalogProductPage: ProductPage = {
  items: [
    {
      id: catalogProductId,
      spaceId: catalogSpaceId,
      name: catalogProduct.name,
      category: catalogProduct.category,
      modelSpec: catalogProduct.modelSpec,
      aliasCount: catalogProduct.aliases.length,
      version: catalogProduct.version,
      createdAt: catalogProduct.createdAt,
      updatedAt: catalogProduct.updatedAt,
    },
  ],
  page: 2,
  size: 50,
  total: 51,
  hasNext: false,
}

export const catalogCompetitor: Competitor = {
  id: catalogCompetitorId,
  spaceId: catalogSpaceId,
  name: '测试竞品',
  aliases: ['竞品别名'],
  type: 'DIRECT',
  description: '竞品说明',
  status: 'ACTIVE',
  version: 13,
  createdAt: '2099-01-01T00:00:00Z',
  updatedAt: '2099-01-02T00:00:00Z',
}

export const catalogCompetitorPage: CompetitorPage = {
  items: [
    {
      id: catalogCompetitorId,
      spaceId: catalogSpaceId,
      name: catalogCompetitor.name,
      type: catalogCompetitor.type,
      status: catalogCompetitor.status,
      aliasCount: catalogCompetitor.aliases.length,
      version: catalogCompetitor.version,
      createdAt: catalogCompetitor.createdAt,
      updatedAt: catalogCompetitor.updatedAt,
    },
  ],
  page: 2,
  size: 50,
  total: 51,
  hasNext: false,
}

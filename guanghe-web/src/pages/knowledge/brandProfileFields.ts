import type { MemoryEntry } from '../../api/knowledgeApi'

// These keys are shared with the GEO agent's project-scoped intake memory.
export const BRAND_PROFILE_FIELDS = [
  { key: 'brand.categories', kind: 'FACT', label: '主营业务', required: true, hint: '卖什么、提供什么服务；可以同时填写多个品类。', example: '咖啡、曲奇和蛋糕' },
  { key: 'brand.service_area', kind: 'FACT', label: '实际经营／服务范围', required: true, hint: '真实门店位置、到店服务或配送范围，不等同于希望曝光的地域。', example: '广州天河有门店；蛋糕配送限周边 3 公里，曲奇可全国快递' },
  { key: 'geo.target_regions', kind: 'FACT', label: '目标曝光地域', required: true, hint: '长期希望覆盖的地域相关搜索需求；单次活动的临时地域不用改这里。', example: '广州，优先天河区；也希望吸引来广州的游客' },
  { key: 'geo.priority_offering', kind: 'PREFERENCE', label: '推广重点', required: false, hint: '选填。没有明确偏好时，智能体可以结合业务提出建议。', example: '优先推广咖啡和蛋糕组成的下午茶' },
  { key: 'geo.target_queries', kind: 'PREFERENCE', label: '关键词／搜索需求', required: false, hint: '选填。不懂关键词也没关系，可以描述顾客会问的问题。', example: '广州下午茶；天河有哪些适合聊天、可以吃蛋糕的咖啡店？' },
] as const

export type BrandProfileField = typeof BRAND_PROFILE_FIELDS[number]
export const isBrandProfileEntry = (entry: MemoryEntry) => entry.scope === 'PROJECT'
  && BRAND_PROFILE_FIELDS.some((field) => field.key === entry.key && field.kind === entry.kind)

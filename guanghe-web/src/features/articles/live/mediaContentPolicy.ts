import type { MediaArticlePricing } from '../../../api/mediaApi'

/** Detect content requiring the server's verified image-publication capability. */
export function mediaArticleHasImages(content: {
  bodyHtml: string
  assetCount: number
}): boolean {
  return content.assetCount > 0 ||
    /<img\b/i.test(content.bodyHtml) ||
    /\bdata-asset-id\s*=/i.test(content.bodyHtml)
}

/** Raw supplier labels never grant image publishing; only Java's verified projection can. */
export function mediaResourceSupportsImages(capabilities: Record<string, unknown>, count = 1): boolean {
  return capabilities.supportsImages === true &&
    ['INCLUDED', 'PER_IMAGE', 'PER_ARTICLE'].includes(String(capabilities.imageFeeMode)) &&
    typeof capabilities.maxImageCount === 'number' && Number.isSafeInteger(capabilities.maxImageCount) &&
    capabilities.maxImageCount >= count
}

/** Exact article prices come from Java, never from multiplying supplier labels in the browser. */
export function mediaArticlePricing(value: unknown): MediaArticlePricing | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined
  const pricing = value as Record<string, unknown>
  const keys = ['imageCount', 'baseSaleAmountCents', 'imageFeeAmountCents', 'totalSaleAmountCents',
    'basePoints', 'imageFeePoints', 'totalPoints'] as const
  if (keys.some((key) => typeof pricing[key] !== 'number' || !Number.isSafeInteger(pricing[key]) || pricing[key] < 0) ||
    !['NONE', 'INCLUDED', 'PER_IMAGE', 'PER_ARTICLE'].includes(String(pricing.imageFeeMode)) ||
    Number(pricing.imageCount) > 10 || Number(pricing.baseSaleAmountCents) < 1 || Number(pricing.basePoints) < 1 ||
    typeof pricing.description !== 'string' ||
    Number(pricing.baseSaleAmountCents) + Number(pricing.imageFeeAmountCents) !== pricing.totalSaleAmountCents ||
    Number(pricing.basePoints) + Number(pricing.imageFeePoints) !== pricing.totalPoints) return undefined
  return pricing as MediaArticlePricing
}

export function mediaImageFeeModeLabel(mode?: string): string {
  return ({ NONE: '纯文字', INCLUDED: '免费带图 / 已含图片费', PER_IMAGE: '按张计费', PER_ARTICLE: '按篇计费' } as Record<string, string>)[mode ?? ''] ?? '图片费用待核验'
}

export function mediaPricingEquals(left: MediaArticlePricing, right: MediaArticlePricing): boolean {
  return left.imageCount === right.imageCount && left.imageFeeMode === right.imageFeeMode &&
    left.baseSaleAmountCents === right.baseSaleAmountCents && left.imageFeeAmountCents === right.imageFeeAmountCents &&
    left.totalSaleAmountCents === right.totalSaleAmountCents && left.basePoints === right.basePoints &&
    left.imageFeePoints === right.imageFeePoints && left.totalPoints === right.totalPoints
}

/** Early feedback only: Java rechecks the immutable snapshot at quote and confirm. */
export function mediaArticleHasLinks(bodyHtml: string): boolean {
  return /https?:\/\//i.test(bodyHtml) || /<a\b[^>]*\bhref\s*=/i.test(bodyHtml)
}

/** Mirrors Java's resource capability policy, including its deny-by-default fallback. */
export function mediaResourceSupportsLinks(capabilities: Record<string, unknown>): boolean {
  const explicit = capabilities.supportsLink
  if (typeof explicit === 'boolean') return explicit
  const policy = capabilities.linkPolicy
  if (typeof policy !== 'string' || policy.includes('不可') || policy.includes('禁止')) return false
  return policy.includes('可带') || policy.toUpperCase() === 'SUPPORTED'
}

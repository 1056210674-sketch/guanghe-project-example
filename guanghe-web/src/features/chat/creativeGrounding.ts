import type { TaskDraft } from '../../api/conversationApi'

export type CreativeSourceMode = NonNullable<TaskDraft['sourceMode']>

export function creativeSourceLabel(source: CreativeSourceMode): string {
  const labels: Record<CreativeSourceMode, string> = {
    CATALOG_PRODUCT: '商品资料', CURRENT_ATTACHMENTS: '本次附件', TEXT_ONLY: '纯文本',
    SELECTED_ASSETS: '本次选择的图片', REGION_EDIT: '局部重绘底图与蒙版',
    PRODUCT_MATERIALS: '商品素材', GEO_REPORT: 'GEO 监测报告与商品资料',
    PREVIOUS_USER_ATTACHMENT: '上一轮上传图',
    PREVIOUS_IMAGE_RESULT: '上一张生成图',
  }
  return labels[source] ?? source
}

export type ChatComposerAttachmentStatus =
  | 'UPLOADING'
  | 'PROCESSING'
  | 'READY'
  | 'FAILED'

export interface ChatComposerAttachmentView {
  assetId: string
  name: string
  status: ChatComposerAttachmentStatus
  thumbnailUrl?: string
  progressPercent?: number
  errorMessage?: string
}

export interface CreativeSourceDecision {
  sourceMode?: CreativeSourceMode
  selectedProductId?: string
  referenceAssetIds: string[]
  ready: boolean
  guidance?: string
}

export function decideCreativeSource({
  attachments,
  selectedProductId,
}: {
  attachments: readonly ChatComposerAttachmentView[]
  selectedProductId?: string
}): CreativeSourceDecision {
  if (attachments.length > 0) {
    const readyAttachments = attachments.filter(
      (attachment) => attachment.status === 'READY',
    )
    const allReady = readyAttachments.length === attachments.length
    return {
      sourceMode: 'CURRENT_ATTACHMENTS',
      selectedProductId: undefined,
      referenceAssetIds: allReady
        ? readyAttachments.map(({ assetId }) => assetId)
        : [],
      ready: allReady,
      guidance:
        allReady
          ? '本次将以附件为准，不使用右侧商品资料。'
          : '图片处理完成后才能用于本次创作。',
    }
  }

  if (selectedProductId) {
    return {
      sourceMode: 'CATALOG_PRODUCT',
      selectedProductId,
      referenceAssetIds: [],
      ready: true,
    }
  }

  return {
    referenceAssetIds: [],
    ready: false,
    guidance: '请选择商品，或上传本次要参考的图片。',
  }
}

const CREATIVE_ERROR_MESSAGES: Record<string, string> = {
  PRODUCT_IDENTITY_ASSET_REQUIRED:
    '该商品还缺少可用的主图或白底图，请先到商品资料中补充图片。',
  REFERENCE_ASSET_NOT_READY: '参考图片仍在处理中，请稍后再试。',
  REFERENCE_ASSET_NOT_OWNED: '参考图片不可用，请重新选择或上传图片。',
  REFERENCE_IMAGE_MODE_UNAVAILABLE:
    '当前图片生成能力暂不支持参考图创作，请稍后再试。',
  IMAGE_TO_VIDEO_MODE_UNAVAILABLE:
    '当前视频生成能力暂不支持使用商品图片制作视频，请稍后再试。',
  CREATIVE_SOURCE_REQUIRED: '请选择商品，或上传本次要参考的图片。',
}

export function creativeGroundingErrorMessage(
  code: string,
  fallback: string,
): string {
  return CREATIVE_ERROR_MESSAGES[code] ?? fallback
}

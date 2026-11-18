export type ImageAspectRatio = '1:1' | '3:4' | '4:3' | '9:16' | '16:9'
export type ImageCreationMode = 'PRODUCT_GROUNDED' | 'TEXT_ONLY' | 'SELECTED_ASSETS' | 'REGION_EDIT' | 'GEO_REPORT'

export interface ImageCreationOption {
  label: string
  value: string
}

/** Presentation form state. The page container adapts this into generated API types. */
export interface ImageCreationDraft {
  mode: ImageCreationMode
  businessSpaceId: string
  productId?: string
  scene?: string
  style?: string
  lighting?: string
  aspectRatio: ImageAspectRatio
  count: number
  negativePrompt?: string
}

export type ImageCreationParameters = Pick<
  ImageCreationDraft,
  'scene' | 'style' | 'lighting' | 'aspectRatio' | 'count' | 'negativePrompt'
>

export interface ImageCreationViewError {
  message: string
  traceId?: string
}

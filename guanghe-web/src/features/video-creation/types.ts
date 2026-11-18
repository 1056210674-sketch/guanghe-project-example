export type VideoCreationMode =
  | 'TEXT_TO_VIDEO'
  | 'IMAGE_TO_VIDEO'
  | 'PRODUCT_MATERIAL'
  | 'GEO_INSIGHT'

export type VideoAspectRatio =
  | 'adaptive'
  | '16:9'
  | '4:3'
  | '1:1'
  | '3:4'
  | '9:16'

export type VideoResolution = '480P' | '720P' | '1080P'

export interface VideoCreationOption {
  label: string
  value: string
}

/** Presentation form state. The page container adapts this into generated API types. */
export interface VideoCreationDraft {
  mode: VideoCreationMode
  businessSpaceId: string
  productId?: string
  scene: string
  style?: string
  durationSeconds: number
  aspectRatio: VideoAspectRatio
  cameraMotion?: string
  resolution: VideoResolution
  audio: boolean
  seed: number
  promptExtend: boolean
  watermark: boolean
}

export interface VideoCreationViewError {
  message: string
  traceId?: string
}

export type VideoCreationParameters = Omit<
  VideoCreationDraft,
  'mode' | 'businessSpaceId' | 'productId'
>

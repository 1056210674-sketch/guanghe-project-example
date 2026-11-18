export const IMAGE_UPLOAD_MAX_BYTES = 20 * 1024 * 1024

const IMAGE_UPLOAD_CONTENT_TYPES = new Set(['image/png', 'image/jpeg'])

export function imageUploadValidationError(file: File): string | undefined {
  if (!IMAGE_UPLOAD_CONTENT_TYPES.has(file.type)) {
    return '仅支持 PNG 或 JPEG 图片。'
  }
  if (file.size > IMAGE_UPLOAD_MAX_BYTES) {
    return '单张图片不能超过 20 MiB。'
  }
  return undefined
}

export function imageUploadSelectionError(
  files: readonly File[],
  availableSlots?: number,
): string | undefined {
  const invalid = files
    .map((file) => ({ file, error: imageUploadValidationError(file) }))
    .find(({ error }) => error)
  if (invalid) {
    return `${invalid.file.name}：${invalid.error}`
  }
  if (availableSlots !== undefined && files.length > availableSlots) {
    return `最多还可添加 ${availableSlots} 张图片，已忽略超出部分。`
  }
  return undefined
}

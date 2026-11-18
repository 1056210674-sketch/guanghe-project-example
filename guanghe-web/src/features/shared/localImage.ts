export const SUPPORTED_IMAGE_MIME_TYPES = [
  'image/jpeg',
  'image/png',
  'image/webp',
] as const

const supportedImageMimeTypes = new Set<string>(SUPPORTED_IMAGE_MIME_TYPES)

export interface DecodedImageDimensions {
  width: number
  height: number
}

export function isSupportedImageMime(file: File): boolean {
  return supportedImageMimeTypes.has(file.type)
}

export function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.addEventListener('load', () => {
      if (typeof reader.result === 'string') {
        resolve(reader.result)
      } else {
        reject(new Error('FileReader did not return a data URL.'))
      }
    })
    reader.addEventListener('error', () => {
      reject(reader.error ?? new Error('Failed to read the image file.'))
    })
    reader.readAsDataURL(file)
  })
}

export function decodeImageUrl(source: string): Promise<DecodedImageDimensions> {
  return new Promise((resolve, reject) => {
    const image = new Image()
    let settled = false

    const finish = () => {
      if (settled) return
      const width = image.naturalWidth
      const height = image.naturalHeight
      if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
        settled = true
        reject(new Error('Decoded image has invalid dimensions.'))
        return
      }
      settled = true
      resolve({ width, height })
    }
    const fail = () => {
      if (settled) return
      settled = true
      reject(new Error('Image decoding failed.'))
    }

    image.addEventListener('load', finish, { once: true })
    image.addEventListener('error', fail, { once: true })
    image.src = source

    if (typeof image.decode === 'function') {
      void image.decode().then(finish, fail)
    }
  })
}

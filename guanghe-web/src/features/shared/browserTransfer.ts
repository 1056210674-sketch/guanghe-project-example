interface DownloadTextFileInput {
  content: string
  filename: string
  contentType?: string
}

export function downloadTextFile({
  content,
  filename,
  contentType = 'text/plain;charset=utf-8',
}: DownloadTextFileInput): void {
  const blob = new Blob([`\uFEFF${content}`], { type: contentType })
  downloadBlobFile(blob, filename)
}

export function downloadBlobFile(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.hidden = true
  document.body.append(anchor)

  try {
    anchor.click()
  } finally {
    anchor.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1_000)
  }
}

function copyWithSelectionFallback(text: string): boolean {
  const textarea = document.createElement('textarea')
  const activeElement = document.activeElement
  textarea.value = text
  textarea.readOnly = true
  textarea.setAttribute('aria-hidden', 'true')
  textarea.style.position = 'fixed'
  textarea.style.inset = '-1000px auto auto -1000px'
  document.body.append(textarea)
  textarea.focus()
  textarea.select()
  textarea.setSelectionRange(0, textarea.value.length)

  try {
    return document.execCommand('copy')
  } finally {
    textarea.remove()
    if (activeElement instanceof HTMLElement) activeElement.focus()
  }
}

export async function copyPlainText(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return
    } catch {
      // Self-signed HTTPS origins may expose Clipboard API but reject writes.
      // Fall through to the selection-based browser copy path.
    }
  }

  if (!copyWithSelectionFallback(text)) {
    throw new Error('浏览器未允许复制到剪贴板。')
  }
}

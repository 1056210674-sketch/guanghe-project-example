import { Alert, Button, Slider, Typography } from 'antd'
import { useRef, useState, type PointerEvent } from 'react'
import './creative-modes.css'

/** The image is never painted onto this canvas, so temporary image URLs cannot taint mask export. */
export function RegionMaskPainter({ imageUrl, savedMaskUrl, disabled = false, onChange }: {
  imageUrl: string
  savedMaskUrl?: string
  disabled?: boolean
  onChange: (file: File | undefined) => void
}) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const dragging = useRef(false)
  const lastPoint = useRef<{ x: number; y: number } | null>(null)
  const [brush, setBrush] = useState(40)
  const [size, setSize] = useState<{ width: number; height: number }>()
  const [hasPaint, setHasPaint] = useState(false)
  const [confirmed, setConfirmed] = useState(false)
  const [replaceSaved, setReplaceSaved] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [error, setError] = useState<string>()
  const showSaved = Boolean(savedMaskUrl && !replaceSaved)
  const validSize = Boolean(size && size.width >= 512 && size.height >= 512 && size.width <= 4096 && size.height <= 4096)
  const clear = () => {
    const current = canvas.current
    current?.getContext('2d')?.clearRect(0, 0, current.width, current.height)
    setReplaceSaved(true)
    setHasPaint(false)
    setConfirmed(false)
    onChange(undefined)
  }
  const paint = (event: PointerEvent<HTMLCanvasElement>, first: boolean) => {
    const current = canvas.current
    if (!current || !validSize || disabled || exporting || showSaved) return
    const ctx = current.getContext('2d')
    if (!ctx) return
    const bounds = current.getBoundingClientRect()
    if (!bounds.width || !bounds.height) return
    const point = {
      x: Math.max(0, Math.min(current.width, (event.clientX - bounds.left) * current.width / bounds.width)),
      y: Math.max(0, Math.min(current.height, (event.clientY - bounds.top) * current.height / bounds.height)),
    }
    ctx.strokeStyle = '#fff'
    ctx.fillStyle = '#fff'
    ctx.lineWidth = brush * current.width / bounds.width
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'
    ctx.beginPath()
    if (first || !lastPoint.current) {
      ctx.arc(point.x, point.y, ctx.lineWidth / 2, 0, Math.PI * 2)
      ctx.fill()
    } else {
      ctx.moveTo(lastPoint.current.x, lastPoint.current.y)
      ctx.lineTo(point.x, point.y)
      ctx.stroke()
    }
    lastPoint.current = point
    setHasPaint(true)
    setConfirmed(false)
  }
  const exportMask = async () => {
    const current = canvas.current
    if (!current || !hasPaint || !validSize) return
    setExporting(true)
    setError(undefined)
    try {
      const output = document.createElement('canvas')
      output.width = current.width
      output.height = current.height
      const ctx = output.getContext('2d', { alpha: false })
      const paintContext = current.getContext('2d')
      if (!ctx || !paintContext) throw new Error('无法读取绘图区，请换一个浏览器重试。')
      const pixels = paintContext.getImageData(0, 0, output.width, output.height)
      let whitePixels = 0
      for (let index = 0; index < pixels.data.length; index += 4) {
        const value = pixels.data[index + 3] >= 128 ? 255 : 0
        if (value) whitePixels += 1
        pixels.data[index] = value
        pixels.data[index + 1] = value
        pixels.data[index + 2] = value
        pixels.data[index + 3] = 255
      }
      if (!whitePixels) throw new Error('请先涂抹需要修改的区域。')
      ctx.putImageData(pixels, 0, 0)
      const blob = await new Promise<Blob>((resolve, reject) => output.toBlob((value) => value ? resolve(value) : reject(new Error('蒙版导出失败，请重新确认涂抹区域。')), 'image/png'))
      if (blob.size > 10 * 1024 * 1024) throw new Error('蒙版超过 10 MiB，请使用尺寸更小的底图。')
      onChange(new File([blob], 'region-mask.png', { type: 'image/png' }))
      setConfirmed(true)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '蒙版导出失败，请重试。')
    } finally { setExporting(false) }
  }
  return <section className="creative-source-panel" aria-label="局部重绘区域">
    <Typography.Paragraph>涂抹需要修改的区域；区域外保留原图，按原图尺寸输出。底图与蒙版须为 512–4096 像素且不超过 10 MiB。</Typography.Paragraph>
    {error ? <Alert type="error" showIcon title={error} /> : null}
    {size && !validSize ? <Alert type="warning" title="底图宽和高均须为 512–4096 像素，请更换图片。" /> : null}
    <label>画笔粗细<Slider ariaLabelForHandle="画笔粗细" min={5} max={100} value={brush} disabled={disabled || exporting || showSaved} onChange={setBrush} /></label>
    <div className="creative-mask-stage">
      <img src={imageUrl} alt="局部重绘底图" onLoad={(event) => {
        const next = { width: event.currentTarget.naturalWidth, height: event.currentTarget.naturalHeight }
        setSize(next)
        if (canvas.current && (canvas.current.width !== next.width || canvas.current.height !== next.height)) {
          canvas.current.width = next.width
          canvas.current.height = next.height
        }
      }} onError={() => setError('底图加载失败，请刷新图片后重试。')} />
      {showSaved ? <img className="creative-mask-saved" src={savedMaskUrl} alt="已保存的重绘区域蒙版" /> : null}
      <canvas ref={canvas} aria-label="涂抹重绘区域" role="img" className="creative-mask-canvas"
        onPointerDown={(event) => {
          if (!validSize || disabled || exporting || showSaved) return
          dragging.current = true
          event.currentTarget.setPointerCapture?.(event.pointerId)
          onChange(undefined)
          paint(event, true)
        }} onPointerMove={(event) => { if (dragging.current) paint(event, false) }}
        onPointerUp={() => { dragging.current = false; lastPoint.current = null }}
        onPointerCancel={() => { dragging.current = false; lastPoint.current = null }} />
    </div>
    <div className="creative-source-actions">
      <Button disabled={disabled || exporting} onClick={clear}>{showSaved ? '重新涂抹' : '清空涂抹'}</Button>
      {!showSaved ? <Button aria-label="确认涂抹区域" disabled={disabled || !hasPaint || !validSize || confirmed} loading={exporting} onClick={() => void exportMask()}>确认涂抹区域</Button> : null}
    </div>
    <Typography.Text role="status" type="secondary">{showSaved ? '已恢复服务端保存的蒙版，可直接重新报价；重新涂抹会替换原区域。' : confirmed ? '涂抹区域已确认，获取报价时将上传蒙版。' : '完成涂抹后点击「确认涂抹区域」。'}</Typography.Text>
  </section>
}

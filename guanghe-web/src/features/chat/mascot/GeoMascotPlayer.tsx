import { useEffect, useRef } from 'react'
import { GeoMascotAnimator } from './GeoMascotAnimator'
import type { GeoMascotAction } from './mascotAssets'
import './geo-mascot-player.css'

export type GeoMascotPlayerProps = {
  action: GeoMascotAction
  animationKey: string
  onComplete?: () => void
  className?: string
}

/** Business status text belongs to the parent, not to this decorative canvas. */
export function GeoMascotPlayer({ action, animationKey, onComplete, className }: GeoMascotPlayerProps) {
  const hostRef = useRef<HTMLDivElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const fallbackRef = useRef<HTMLImageElement>(null)
  const playerRef = useRef<GeoMascotAnimator | null>(null)
  const onCompleteRef = useRef(onComplete)

  useEffect(() => { onCompleteRef.current = onComplete }, [onComplete])

  useEffect(() => {
    if (!hostRef.current || !canvasRef.current || !fallbackRef.current) return
    const player = new GeoMascotAnimator(hostRef.current, canvasRef.current, fallbackRef.current,
      () => onCompleteRef.current?.())
    playerRef.current = player
    return () => {
      player.destroy()
      playerRef.current = null
    }
  }, [])

  useEffect(() => { playerRef.current?.setAnimation(action, animationKey) }, [action, animationKey])

  return (
    <div ref={hostRef} className={['geo-mascot-player', className].filter(Boolean).join(' ')} aria-hidden="true">
      <canvas ref={canvasRef} width={192} height={192} aria-hidden="true" />
      <img ref={fallbackRef} alt="" hidden draggable={false} />
    </div>
  )
}

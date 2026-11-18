import { useRef, useState, type PointerEvent } from 'react'
import { GeoMascotPlayer } from './GeoMascotPlayer'
import { MASCOT_FALLBACK, MASCOT_LAYOUTS, MASCOT_SPRITES, type GeoMascotAction } from './mascotAssets'
import './geoMascotAvatar.css'

export type GeoMascotAvatarProps = {
  action: GeoMascotAction | 'speaking'
  animationKey: string
  label: string
  onComplete?: () => void
}

const GREETING_COOLDOWN_MS = 2600
const IDLE_LAYOUT = MASCOT_LAYOUTS.greeting
const IDLE_FRAME_STYLE = {
  left: `${IDLE_LAYOUT.frames[0][0] * 100}%`,
  top: `${IDLE_LAYOUT.frames[0][1] * 100}%`,
  width: `${IDLE_LAYOUT.scale * 100}%`,
  height: `${IDLE_LAYOUT.scale * 100}%`,
}

function AvatarPresentation({ action, animationKey, label, onComplete }: GeoMascotAvatarProps) {
  const [greeting, setGreeting] = useState(false)
  const [greetingSequence, setGreetingSequence] = useState(0)
  const [stillSource, setStillSource] = useState<'sprite' | 'fallback' | 'unavailable'>('sprite')
  const lastGreeting = useRef(0)
  const canGreet = action === 'idle' && !greeting
  const visibleAction = greeting ? 'greeting' : action

  const resetTilt = (target: HTMLButtonElement) => {
    target.style.removeProperty('--geo-avatar-x')
    target.style.removeProperty('--geo-avatar-y')
    target.style.removeProperty('--geo-avatar-rotate')
  }

  const followPointer = (event: PointerEvent<HTMLButtonElement>) => {
    if (event.pointerType !== 'mouse') return
    const target = event.currentTarget
    const rect = target.getBoundingClientRect()
    if (!rect.width || !rect.height) return
    const x = Math.max(-1, Math.min(1, ((event.clientX - rect.left) / rect.width - 0.5) * 2))
    const y = Math.max(-1, Math.min(1, ((event.clientY - rect.top) / rect.height - 0.5) * 2))
    target.style.setProperty('--geo-avatar-x', `${(x * 3).toFixed(2)}px`)
    target.style.setProperty('--geo-avatar-y', `${(y * 2).toFixed(2)}px`)
    target.style.setProperty('--geo-avatar-rotate', `${(x * 3).toFixed(2)}deg`)
  }

  const greet = () => {
    const now = Date.now()
    if (!canGreet || now - lastGreeting.current < GREETING_COOLDOWN_MS) return
    lastGreeting.current = now
    setGreetingSequence((sequence) => sequence + 1)
    setGreeting(true)
  }

  return (
    <button
      type="button"
      className="geo-mascot-avatar"
      data-action={visibleAction}
      data-interactive={canGreet ? 'true' : 'false'}
      aria-label={canGreet ? `${label}，点击打个招呼` : label}
      aria-disabled={!canGreet}
      title={canGreet ? `${label} · 点击打个招呼` : label}
      onClick={greet}
      onPointerMove={followPointer}
      onPointerLeave={(event) => resetTilt(event.currentTarget)}
      onPointerCancel={(event) => resetTilt(event.currentTarget)}
      onBlur={(event) => resetTilt(event.currentTarget)}
    >
      <span className="geo-mascot-avatar__tilt" aria-hidden="true">
        <span className={`geo-mascot-avatar__art${visibleAction === 'speaking' ? ' geo-mascot-avatar__art--speaking' : ''}`}>
          {visibleAction === 'idle' ? (
            stillSource === 'unavailable' ? <span className="geo-mascot-avatar__placeholder">禾</span>
              : stillSource === 'sprite' ? (
                // Same greeting frame and placement as Canvas, without creating a
                // player for every historical reply. Clip before scaling the art.
                <span className="geo-mascot-avatar__still-frame" style={IDLE_FRAME_STYLE}>
                  <img
                    className="geo-mascot-avatar__sprite"
                    src={MASCOT_SPRITES.greeting}
                    alt=""
                    loading="lazy"
                    decoding="async"
                    draggable={false}
                    onError={() => setStillSource('fallback')}
                  />
                </span>
              ) : (
              <img
                className="geo-mascot-avatar__still geo-mascot-avatar__still--fallback"
                src={MASCOT_FALLBACK}
                alt=""
                loading="lazy"
                decoding="async"
                draggable={false}
                onError={() => setStillSource('unavailable')}
              />
            )
          ) : (
            <GeoMascotPlayer
              action={visibleAction === 'speaking' ? 'idle' : visibleAction}
              animationKey={greeting ? `${animationKey}:hello:${greetingSequence}` : animationKey}
              onComplete={greeting ? () => setGreeting(false) : onComplete}
            />
          )}
        </span>
      </span>
    </button>
  )
}

/** Scope local interaction to one message/action; a new request cancels old gestures. */
export function GeoMascotAvatar(props: GeoMascotAvatarProps) {
  return <AvatarPresentation key={`${props.action}:${props.animationKey}`} {...props} />
}

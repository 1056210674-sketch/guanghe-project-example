import {
  MASCOT_ACTIONS,
  MASCOT_FALLBACK,
  MASCOT_LAYOUTS,
  MASCOT_SPRITES,
  type GeoMascotAction,
} from './mascotAssets'

type LoadedArt = { image: HTMLImageElement; sprite: boolean }

/** Decorative animation only. No request completion or other business mutation. */
export class GeoMascotAnimator {
  private readonly host: HTMLDivElement
  private readonly canvas: HTMLCanvasElement
  private readonly fallback: HTMLImageElement
  private readonly context: CanvasRenderingContext2D | null
  private readonly onComplete: () => void
  private readonly media: MediaQueryList | null
  private readonly resizeObserver: ResizeObserver | null
  private readonly loads = new Map<string, Promise<HTMLImageElement | null>>()
  private readonly cancelLoads = new Set<() => void>()
  private action: GeoMascotAction = 'idle'
  private animationKey: string | null = null
  private generation = 0
  private art: LoadedArt | null = null
  private frame = 0
  private completed = false
  private startedAt: number | null = null
  private raf: number | null = null
  private timer: ReturnType<typeof setTimeout> | null = null
  private destroyed = false

  constructor(
    host: HTMLDivElement,
    canvas: HTMLCanvasElement,
    fallback: HTMLImageElement,
    onComplete: () => void,
  ) {
    this.host = host
    this.canvas = canvas
    this.fallback = fallback
    this.onComplete = onComplete
    let context: CanvasRenderingContext2D | null = null
    try { context = canvas.getContext('2d') } catch { /* Static fallback remains usable. */ }
    this.context = context
    this.canvas.hidden = !context
    this.media = typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-reduced-motion: reduce)') : null
    this.media?.addEventListener('change', this.onMotionChange)
    document.addEventListener('visibilitychange', this.onVisibilityChange)
    window.addEventListener('resize', this.resize)
    this.resizeObserver = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(this.resize) : null
    this.resizeObserver?.observe(host)
    this.fallback.addEventListener('error', this.onFallbackError)
    this.resize()
  }

  setAnimation(action: GeoMascotAction, animationKey: string) {
    if (this.destroyed || (action === this.action && animationKey === this.animationKey)) return
    this.stopScheduling()
    this.generation += 1
    const generation = this.generation
    this.action = action
    this.animationKey = animationKey
    this.completed = false
    this.startedAt = null
    this.frame = 0
    this.art = null
    this.host.dataset.action = action
    this.host.dataset.frame = '0'
    this.host.dataset.assetStatus = 'loading'
    this.context?.clearRect(0, 0, this.canvas.width, this.canvas.height)
    this.fallback.hidden = true

    // Pending images are shared only inside this mounted player. Late loads may be
    // cached for later use, but never draw into a newer request's animation.
    const sprite = this.context
      ? this.load(MASCOT_SPRITES[MASCOT_ACTIONS[action].asset], true)
      : Promise.resolve(null)
    void sprite.then(async (image) => {
      if (!this.isCurrent(generation)) return
      const art = image ? { image, sprite: true } : await this.loadFallback()
      if (!this.isCurrent(generation)) return
      this.art = art
      this.host.dataset.assetStatus = art ? (art.sprite ? 'ready' : 'fallback') : 'unavailable'
      this.draw(this.completed ? 8 : 0)
      this.schedule(generation)
    })
    if (document.hidden) this.onVisibilityChange()
  }

  private isCurrent(generation: number) {
    return !this.destroyed && generation === this.generation
  }

  private load(url: string, grid: boolean) {
    const cached = this.loads.get(url)
    if (cached) return cached
    const pending = new Promise<HTMLImageElement | null>((resolve) => {
      const image = new Image()
      let settled = false
      const finish = (value: HTMLImageElement | null) => {
        if (settled) return
        settled = true
        clearTimeout(timeout)
        this.cancelLoads.delete(cancel)
        image.onload = null
        image.onerror = null
        if (!value) image.removeAttribute('src')
        resolve(value)
      }
      const cancel = () => finish(null)
      const timeout = setTimeout(cancel, 10000)
      this.cancelLoads.add(cancel)
      image.onload = () => {
        const valid = image.naturalWidth > 0 && image.naturalHeight > 0
          && (!grid || (image.naturalWidth % 3 === 0 && image.naturalHeight % 3 === 0))
        finish(valid ? image : null)
      }
      image.onerror = cancel
      image.src = url
    })
    this.loads.set(url, pending)
    return pending
  }

  private async loadFallback(): Promise<LoadedArt | null> {
    const image = await this.load(MASCOT_FALLBACK, false)
    return image ? { image, sprite: false } : null
  }

  private resize = () => {
    if (this.destroyed) return
    const cssSize = Math.max(1, this.host.getBoundingClientRect().width || 96)
    const size = Math.round(Math.min(1024, cssSize) * Math.min(window.devicePixelRatio || 1, 2))
    if (this.canvas.width !== size || this.canvas.height !== size) {
      this.canvas.width = size
      this.canvas.height = size
      this.draw(this.frame)
    }
  }

  private draw(frame: number) {
    this.frame = frame
    this.host.dataset.frame = String(frame)
    const art = this.art
    if (!art) return
    if (!this.context) {
      if (this.fallback.getAttribute('src') !== art.image.src) this.fallback.src = art.image.src
      this.fallback.hidden = false
      return
    }
    const { image, sprite } = art
    const surface = this.canvas.width
    const sourceWidth = image.naturalWidth / (sprite ? 3 : 1)
    const sourceHeight = image.naturalHeight / (sprite ? 3 : 1)
    const contain = Math.min(surface / sourceWidth, surface / sourceHeight)
    let width = sourceWidth * contain
    let height = sourceHeight * contain
    let x = (surface - width) / 2
    let y = (surface - height) / 2
    if (sprite) {
      const layout = MASCOT_LAYOUTS[MASCOT_ACTIONS[this.action].asset]
      width *= layout.scale
      height *= layout.scale
      x += layout.frames[frame][0] * surface
      y += layout.frames[frame][1] * surface
    }
    this.context.clearRect(0, 0, surface, surface)
    this.context.imageSmoothingEnabled = true
    this.context.drawImage(image,
      sprite ? (frame % 3) * sourceWidth : 0,
      sprite ? Math.floor(frame / 3) * sourceHeight : 0,
      sourceWidth, sourceHeight, x, y, width, height)
  }

  private stopScheduling() {
    if (this.raf !== null) cancelAnimationFrame(this.raf)
    if (this.timer !== null) clearTimeout(this.timer)
    this.raf = null
    this.timer = null
  }

  private finish(generation: number) {
    if (!this.isCurrent(generation) || this.completed) return
    this.completed = true
    this.stopScheduling()
    this.onComplete()
  }

  private schedule(generation: number) {
    this.stopScheduling()
    if (!this.isCurrent(generation) || this.completed || document.hidden) return
    const spec = MASCOT_ACTIONS[this.action]
    if (!spec.duration) return
    this.startedAt ??= performance.now()
    if (this.media?.matches || !this.art?.sprite) {
      this.draw(0)
      if (!spec.loop) {
        const remaining = Math.max(0, spec.duration - (performance.now() - this.startedAt))
        this.timer = setTimeout(() => this.finish(generation), remaining)
      }
      return
    }
    const tick = (now: number) => {
      if (!this.isCurrent(generation) || this.completed || document.hidden) return
      // The first RAF timestamp can precede performance.now() from the same
      // render frame. Never index the sprite layout with a negative frame.
      const elapsed = Math.max(0, now - (this.startedAt ?? now))
      if (!spec.loop && elapsed >= spec.duration) {
        this.draw(8)
        this.finish(generation)
        return
      }
      const phase = spec.loop ? (elapsed % spec.duration) / spec.duration : elapsed / spec.duration
      const frame = Math.min(8, Math.floor(phase * 9))
      if (frame !== this.frame) this.draw(frame)
      this.raf = requestAnimationFrame(tick)
    }
    this.raf = requestAnimationFrame(tick)
  }

  private onMotionChange = () => {
    this.draw(0)
    this.schedule(this.generation)
  }

  private onVisibilityChange = () => {
    if (this.destroyed) return
    this.stopScheduling()
    const spec = MASCOT_ACTIONS[this.action]
    if (document.hidden) {
      // Welcome/success gestures are ephemeral: do not replay old congratulations
      // when the user comes back to this tab. Thinking resumes only if still active.
      if (spec.duration && !spec.loop && !this.completed) {
        const generation = this.generation
        queueMicrotask(() => this.finish(generation))
      }
    } else {
      this.startedAt = null
      this.schedule(this.generation)
    }
  }

  private onFallbackError = () => {
    this.fallback.hidden = true
    this.host.dataset.assetStatus = 'unavailable'
  }

  destroy() {
    if (this.destroyed) return
    this.destroyed = true
    this.generation += 1
    this.stopScheduling()
    for (const cancel of this.cancelLoads) cancel()
    this.cancelLoads.clear()
    this.loads.clear()
    this.art = null
    this.resizeObserver?.disconnect()
    window.removeEventListener('resize', this.resize)
    document.removeEventListener('visibilitychange', this.onVisibilityChange)
    this.media?.removeEventListener('change', this.onMotionChange)
    this.fallback.removeEventListener('error', this.onFallbackError)
    this.fallback.removeAttribute('src')
  }
}

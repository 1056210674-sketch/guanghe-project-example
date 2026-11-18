import greetingSprite from '../../../assets/geo-mascot/miyou-greeting-sprites.png'
import thinkingSprite from '../../../assets/geo-mascot/miyou-thinking-sprites.png'
import completeSprite from '../../../assets/geo-mascot/miyou-thought-complete-sprites.png'
import happySprite from '../../../assets/geo-mascot/miyou-happy-sprites.png'
import greetingStill from '../../../assets/geo-mascot/miyou-greeting.png'

export type GeoMascotAction = 'idle' | 'greeting' | 'thinking' | 'thought-complete' | 'happy'
export type GeoMascotAsset = Exclude<GeoMascotAction, 'idle'>

export const MASCOT_ACTIONS: Record<GeoMascotAction, {
  asset: GeoMascotAsset
  duration: number
  loop: boolean
}> = {
  idle: { asset: 'greeting', duration: 0, loop: false },
  greeting: { asset: 'greeting', duration: 1800, loop: false },
  thinking: { asset: 'thinking', duration: 1800, loop: true },
  'thought-complete': { asset: 'thought-complete', duration: 1600, loop: false },
  happy: { asset: 'happy', duration: 1400, loop: false },
}

export const MASCOT_SPRITES: Record<GeoMascotAsset, string> = {
  greeting: greetingSprite,
  thinking: thinkingSprite,
  'thought-complete': completeSprite,
  happy: happySprite,
}

export const MASCOT_FALLBACK = greetingStill

// Handoff calibration: apply scale after contain placement, without re-centering.
// The source grid remains 3 x 3; happy's vertical movement is already in these frames.
export const MASCOT_LAYOUTS: Record<GeoMascotAsset, {
  scale: number
  frames: readonly (readonly [number, number])[]
}> = {
  greeting: {
    scale: 0.91103,
    frames: [
      [0.03325, 0.04564], [0.05631, 0.04564], [0.09952, 0.04564],
      [0.03061, 0.10013], [0.05031, 0.10013], [0.09579, 0.10013],
      [0.03285, 0.15462], [0.05113, 0.15462], [0.09267, 0.15462],
    ],
  },
  thinking: {
    scale: 0.66723,
    frames: [
      [0.18068, 0.23596], [0.17283, 0.23596], [0.17207, 0.23596],
      [0.17473, 0.24235], [0.17049, 0.24235], [0.16768, 0.24235],
      [0.16528, 0.25512], [0.16781, 0.25512], [0.16623, 0.25512],
    ],
  },
  'thought-complete': {
    scale: 0.87728,
    frames: [
      [0.04447, 0.06049], [0.07654, 0.06049], [0.10671, 0.06049],
      [0.04047, 0.09617], [0.08233, 0.08988], [0.11131, 0.08988],
      [0.05754, 0.16123], [0.08583, 0.16123], [0.10832, 0.16123],
    ],
  },
  happy: {
    scale: 0.66723,
    frames: [
      [0.15171, 0.25831], [0.16932, 0.26431], [0.17907, 0.25469],
      [0.15154, 0.2812], [0.16404, 0.27876], [0.17562, 0.26045],
      [0.15537, 0.31858], [0.17013, 0.31418], [0.17394, 0.31258],
    ],
  },
}

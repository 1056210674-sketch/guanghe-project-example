import type { GeoPlatform } from '../../api/geoApi'

/** Local editor values, not API DTOs or authorization. Java revalidates every action. */
export interface GeoMonitorEditor {
  topicId?: string
  questionIds: string[]
  platformCodes: GeoPlatform['code'][]
  sampleMode: 'STANDARD' | 'DEEP'
  actionPoints: string
  dayPoints: string
  expiresLocal: string
}

export function positiveMonitorPoints(value: string): number | undefined {
  if (!/^[1-9]\d{0,9}$/.test(value)) return undefined
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed <= 2_147_483_647 ? parsed : undefined
}

/** The editor explicitly displays Beijing time, independent of browser time zone. */
export function monitorExpiryIso(value: string, now: number = Date.now()): string | undefined {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return undefined
  const timestamp = Date.parse(`${value}:00+08:00`)
  if (!Number.isFinite(timestamp) || timestamp <= now) return undefined
  if (new Date(timestamp + 8 * 60 * 60 * 1000).toISOString().slice(0, 16) !== value) return undefined
  return new Date(timestamp).toISOString()
}

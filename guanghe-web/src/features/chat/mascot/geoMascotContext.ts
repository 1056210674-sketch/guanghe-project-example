import { createContext, useContext } from 'react'
import type { GeoMascotAvatarProps } from './GeoMascotAvatar'

export interface GeoMascotPose {
  action: GeoMascotAvatarProps['action']
  key: number
  label: string
  messageId?: string
}

export const GEO_PENDING_REPLY = 'geo-avatar:pending-reply'
export const MascotContext = createContext<{ pose: GeoMascotPose; complete: (key: number) => void } | undefined>(undefined)
export function useGeoMascotConversation() { return useContext(MascotContext) }

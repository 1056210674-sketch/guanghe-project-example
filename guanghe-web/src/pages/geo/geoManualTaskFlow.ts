import type { GeoPlatform } from '../../api/geoApi'
import type { CreateTaskDraftRequest } from '../../api/conversationApi'
import type { GeoManualMonitoringIntent } from '../../features/geo'

export function toGeoTaskDraftRequest(
  conversationId: string,
  intent: GeoManualMonitoringIntent,
  context: {
    spaceId: string
    productId?: string | null
    region: string
    platformCodes: readonly GeoPlatform['code'][]
  },
): CreateTaskDraftRequest {
  if (!intent.topicId) {
    throw new Error('必须选择监测专题。')
  }
  if (intent.questionIds.length < 3 || intent.questionIds.length > 10) {
    throw new Error('GEO 监测必须选择 3～10 个正式问题。')
  }
  return {
    conversationId,
    taskType: 'GEO',
    spaceId: context.spaceId,
    productId: context.productId,
    parameterSchemaVersion: 1,
    parameters: {
      mode: 'MONITOR_RUN',
      topicId: intent.topicId,
      questionIds: [...intent.questionIds],
      platformCodes: [...context.platformCodes],
      sampleMode: intent.mode,
      region: context.region,
    },
  }
}

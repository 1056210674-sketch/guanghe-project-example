import { useQuery } from '@tanstack/react-query'
import { getMonitorAdmission, type MonitorAdmission } from '../../api/monitorExecutionApi'
import type { AuthSessionSnapshot } from '../../auth/tokenStorage'

export function monitorAdmissionInterval(value: MonitorAdmission | undefined, error: unknown, readCount: number): number | false {
  return !error && readCount < 30 && (value?.status === 'PENDING' || value?.status === 'WAITING_PROPOSAL') ? 3000 : false
}

/** Only a known explicit-action Run is observed. Never poll ordinary chat or dispatch a task here. */
export function useMonitorAdmission(ownerId: string, conversationId: string | undefined, runId: string | undefined, session: AuthSessionSnapshot) {
  return useQuery({
    queryKey: ['monitor-action', ownerId, conversationId, 'admission', runId],
    queryFn: () => getMonitorAdmission(conversationId!, runId!, session),
    enabled: Boolean(conversationId && runId), retry: false,
    refetchOnWindowFocus: false,
    refetchInterval: (query) => monitorAdmissionInterval(query.state.data, query.state.error, query.state.dataUpdateCount),
    refetchIntervalInBackground: false,
  })
}

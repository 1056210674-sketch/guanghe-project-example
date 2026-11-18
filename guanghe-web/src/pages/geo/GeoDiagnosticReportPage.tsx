import { Alert, Button } from 'antd'
import { useNavigate, useParams } from 'react-router-dom'
import { PageLoading } from '../../components/GlobalState'
import { GeoDiagnosticReportView } from '../../features/geo-diagnostics/GeoDiagnosticReportView'
import { UserShell } from '../../layouts/UserShell'
import { useGeoDiagnosticReport } from './useGeoDiagnosticReport'

export function GeoDiagnosticReportPage() {
  const { diagnosticId } = useParams<{ diagnosticId: string }>()
  const navigate = useNavigate()
  const state = useGeoDiagnosticReport(diagnosticId)
  return <UserShell>{state.error ? <Alert type="error" showIcon title={state.error} action={<Button onClick={state.refresh}>重新读取诊断报告</Button>} />
    : state.loading || !state.report ? <PageLoading label="正在读取诊断报告…" />
      : <GeoDiagnosticReportView report={state.report} downloading={state.downloading} retrying={state.retrying} actionError={state.actionError}
        observationsOpen={Boolean(state.observationsOpen)} observations={state.observations} observationsLoading={state.observationsLoading}
        observationsError={state.observationsError} observationsPage={state.observationsPage}
        onDownload={state.download} onRetry={state.retry} onRefresh={state.refresh}
        onChat={() => { if (state.diagnostic) navigate(`/app/chat?${new URLSearchParams({ conversation: state.diagnostic.conversationId })}`) }}
        onObservations={state.toggleObservations} onObservationsPage={state.changePage} onRefreshObservations={state.refreshObservations} />}</UserShell>
}

import { AdminFeedbackConsole } from '../../features/admin-billing/feedback'
import { AdminShell } from '../../layouts/AdminShell'

// 用户反馈 is wired to the feedback API. The console shows the submitter's own words and the replies
// that were actually recorded — an unanswered ticket says so rather than showing a placeholder reply.
export function AdminFeedbackPage() {
  return (
    <AdminShell>
      <AdminFeedbackConsole />
    </AdminShell>
  )
}

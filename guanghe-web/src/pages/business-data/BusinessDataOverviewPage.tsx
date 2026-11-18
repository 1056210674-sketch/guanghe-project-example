import { useNavigate } from 'react-router-dom'
import { BusinessDataOverview } from '../../features/business-data'
import { UserShell } from '../../layouts/UserShell'
import { APP_PATHS } from '../../routes/paths'

export function BusinessDataOverviewPage() {
  const navigate = useNavigate()

  return (
    <UserShell>
      <BusinessDataOverview
        onOpenBusinessSpaces={() => navigate(APP_PATHS.businessSpaces)}
        onOpenProducts={() => navigate(APP_PATHS.products)}
        onOpenCompetitors={() => navigate(APP_PATHS.competitors)}
      />
    </UserShell>
  )
}

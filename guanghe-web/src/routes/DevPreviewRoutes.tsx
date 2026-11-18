import { lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { DEV_PREVIEW_PATHS } from './devPreviewPaths'
import { APP_PATHS } from './paths'

const GeoMascotPreviewPage = lazy(() => import('../pages/chat/GeoMascotPreviewPage').then((module) => ({ default: module.GeoMascotPreviewPage })))

const ImageCreationPage = lazy(() =>
  import('../pages/image-creation/ImageCreationPage').then((module) => ({
    default: module.ImageCreationPage,
  })),
)
const VideoCreationPage = lazy(() =>
  import('../pages/video-creation/VideoCreationPage').then((module) => ({
    default: module.VideoCreationPage,
  })),
)
const AssetCenterPreviewPage = lazy(() =>
  import('../pages/assets/AssetCenterPreviewPage').then((module) => ({
    default: module.AssetCenterPreviewPage,
  })),
)
const ArticleSubmissionPage = lazy(() =>
  import('../pages/articles/ArticleSubmissionPage').then((module) => ({
    default: module.ArticleSubmissionPage,
  })),
)
const PointsBillingPage = lazy(() =>
  import('../pages/points-billing/PointsBillingPage').then((module) => ({
    default: module.PointsBillingPage,
  })),
)
const NotificationPreviewPage = lazy(() =>
  import('../pages/notifications/NotificationPreviewPage').then((module) => ({
    default: module.NotificationPreviewPage,
  })),
)
const AdminPricingPreviewPage = lazy(() =>
  import('../pages/admin/AdminFinancePreviewPages').then((module) => ({
    default: module.AdminPricingPreviewPage,
  })),
)
const AdminLedgerPreviewPage = lazy(() =>
  import('../pages/admin/AdminFinancePreviewPages').then((module) => ({
    default: module.AdminLedgerPreviewPage,
  })),
)
const AdminMediaPreviewPage = lazy(() =>
  import('../pages/admin/AdminPublishingPreviewPages').then((module) => ({
    default: module.AdminMediaPreviewPage,
  })),
)
const AdminInvoicePreviewPage = lazy(() =>
  import('../pages/admin/AdminPublishingPreviewPages').then((module) => ({
    default: module.AdminInvoicePreviewPage,
  })),
)
const AdminFeedbackPreviewPage = lazy(() =>
  import('../pages/admin/AdminReviewPreviewPages').then((module) => ({
    default: module.AdminFeedbackPreviewPage,
  })),
)
const AdminAuditPreviewPage = lazy(() =>
  import('../pages/admin/AdminObservabilityPreviewPages').then((module) => ({
    default: module.AdminAuditPreviewPage,
  })),
)
const AdminAgentVersionsPreviewPage = lazy(() =>
  import('../pages/admin/AdminObservabilityPreviewPages').then((module) => ({
    default: module.AdminAgentVersionsPreviewPage,
  })),
)
const AdminUserManagementPreviewPage = lazy(() =>
  import('../pages/admin/AdminPreviewPages').then((module) => ({
    default: module.AdminUserManagementPreviewPage,
  })),
)
const AdminTaskServicePreviewPage = lazy(() =>
  import('../pages/admin/AdminPreviewPages').then((module) => ({
    default: module.AdminTaskServicePreviewPage,
  })),
)

function previewSegment(path: string) {
  return path.replace(/^\/preview\//, '')
}

export function DevPreviewRoutes() {
  return (
    <Routes>
      <Route path="geo-mascot" element={<GeoMascotPreviewPage />} />
      <Route path={previewSegment(DEV_PREVIEW_PATHS.notifications)} element={<NotificationPreviewPage />} />
      <Route path={previewSegment(DEV_PREVIEW_PATHS.imageCreation)} element={<ImageCreationPage />} />
      <Route path={previewSegment(DEV_PREVIEW_PATHS.videoCreation)} element={<VideoCreationPage />} />
      <Route path={previewSegment(DEV_PREVIEW_PATHS.assets)} element={<AssetCenterPreviewPage />} />
      <Route path={previewSegment(DEV_PREVIEW_PATHS.articles)} element={<ArticleSubmissionPage />} />
      <Route path={previewSegment(DEV_PREVIEW_PATHS.pointsBilling)} element={<PointsBillingPage />} />
      <Route path={previewSegment(DEV_PREVIEW_PATHS.adminUsers)} element={<AdminUserManagementPreviewPage />} />
      <Route path={previewSegment(DEV_PREVIEW_PATHS.adminTasks)} element={<AdminTaskServicePreviewPage />} />
      <Route path={previewSegment(DEV_PREVIEW_PATHS.adminPricing)} element={<AdminPricingPreviewPage />} />
      <Route path={previewSegment(DEV_PREVIEW_PATHS.adminLedger)} element={<AdminLedgerPreviewPage />} />
      <Route path={previewSegment(DEV_PREVIEW_PATHS.adminMedia)} element={<AdminMediaPreviewPage />} />
      <Route path={previewSegment(DEV_PREVIEW_PATHS.adminInvoices)} element={<AdminInvoicePreviewPage />} />
      <Route path={previewSegment(DEV_PREVIEW_PATHS.adminFeedback)} element={<AdminFeedbackPreviewPage />} />
      <Route path={previewSegment(DEV_PREVIEW_PATHS.adminAudit)} element={<AdminAuditPreviewPage />} />
      <Route path={previewSegment(DEV_PREVIEW_PATHS.adminAgentVersions)} element={<AdminAgentVersionsPreviewPage />} />
      <Route path="*" element={<Navigate to={APP_PATHS.notFound} replace />} />
    </Routes>
  )
}

import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { PageLoading } from '../components/GlobalState'
import { PageErrorBoundary } from '../components/PageErrorBoundary'
import { useAuthStore } from '../stores/authStore'
import { AuthBootstrap } from './AuthBootstrap'
import { APP_PATHS, APP_ROUTE_PATTERNS } from './paths'
import { ProtectedRoute, PublicOnlyRoute } from './RouteGuards'
import { roleHome } from './routePolicy'

const LoginPage = lazy(() =>
  import('../pages/LoginPage').then((module) => ({
    default: module.LoginPage,
  })),
)
const UserHomePage = lazy(() =>
  import('../pages/UserHomePage').then((module) => ({
    default: module.UserHomePage,
  })),
)
const ChatPage = lazy(() =>
  import('../pages/chat/ChatPage').then((module) => ({
    default: module.ChatPage,
  })),
)
const AgentsPage = lazy(() => import('../pages/agents/AgentsPage').then((module) => ({ default: module.AgentsPage })))
const KnowledgeLibraryPage = lazy(() => import('../pages/knowledge/KnowledgeLibraryPage').then((module) => ({ default: module.KnowledgeLibraryPage })))
const ArticlePreparationPage = lazy(() => import('../pages/articles/ArticlePreparationPage').then((module) => ({ default: module.ArticlePreparationPage })))
const CanvasListPage = lazy(() => import('../pages/canvas/CanvasListPage').then(module => ({ default: module.CanvasListPage })))
const CanvasWorkspacePage = lazy(() => import('../pages/canvas/CanvasWorkspacePage').then(module => ({ default: module.CanvasWorkspacePage })))
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
const AssetCenterPage = lazy(() =>
  import('../pages/assets/AssetCenterPage').then((module) => ({
    default: module.AssetCenterPage,
  })),
)
const FirstLoginPage = lazy(() =>
  import('../pages/FirstLoginPage').then((module) => ({ default: module.FirstLoginPage })),
)
const ArticleSubmissionPage = lazy(() =>
  import('../pages/articles/ArticleSubmissionPage').then((module) => ({
    default: module.ArticleSubmissionPage,
  })),
)
const SubmissionCenterPage = lazy(() => import('../pages/submissions/SubmissionCenterPage').then((module) => ({ default: module.SubmissionCenterPage })))
const PointsBillingPage = lazy(() =>
  import('../pages/points-billing/PointsBillingPage').then((module) => ({
    default: module.PointsBillingPage,
  })),
)
const NotificationPage = lazy(() =>
  import('../pages/notifications/NotificationPage').then((module) => ({
    default: module.NotificationPage,
  })),
)
const TaskCenterPage = lazy(() =>
  import('../pages/task-center/TaskCenterPage').then((module) => ({
    default: module.TaskCenterPage,
  })),
)
const TaskCenterDetailPage = lazy(() =>
  import('../pages/task-center/TaskCenterPage').then((module) => ({
    default: module.TaskCenterDetailPage,
  })),
)
const GeoPage = lazy(() =>
  import('../pages/geo/GeoPage').then((module) => ({
    default: module.GeoPage,
  })),
)
const GeoTopicsPage = lazy(() =>
  import('../pages/geo/GeoPage').then((module) => ({
    default: module.GeoTopicsPage,
  })),
)
const GeoQuestionsPage = lazy(() =>
  import('../pages/geo/GeoPage').then((module) => ({
    default: module.GeoQuestionsPage,
  })),
)
const GeoManualMonitoringPage = lazy(() =>
  import('../pages/geo/GeoPage').then((module) => ({
    default: module.GeoManualMonitoringPage,
  })),
)
const GeoQuickMonitoringPage = lazy(() =>
  import('../pages/geo/GeoPage').then((module) => ({
    default: module.GeoQuickMonitoringPage,
  })),
)
const GeoPlansPage = lazy(() =>
  import('../pages/geo/GeoPage').then((module) => ({
    default: module.GeoPlansPage,
  })),
)
const GeoCompetitorsPage = lazy(() =>
  import('../pages/geo/GeoPage').then((module) => ({
    default: module.GeoCompetitorsPage,
  })),
)
const GeoReportsPage = lazy(() =>
  import('../pages/geo/GeoPage').then((module) => ({
    default: module.GeoReportsPage,
  })),
)
const GeoReportDetailPage = lazy(() =>
  import('../pages/geo/GeoReportDetailPage').then((module) => ({
    default: module.GeoReportDetailPage,
  })),
)
const GeoDiagnosticReportPage = lazy(() => import('../pages/geo/GeoDiagnosticReportPage').then((module) => ({ default: module.GeoDiagnosticReportPage })))
const WorkflowPlansPage = lazy(() =>
  import('../pages/workflow-plans/WorkflowPlansPage').then((module) => ({
    default: module.WorkflowPlansPage,
  })),
)
const WorkflowPlanDetailPage = lazy(() =>
  import('../pages/workflow-plans/WorkflowPlansPage').then((module) => ({
    default: module.WorkflowPlanDetailPage,
  })),
)
const BusinessDataOverviewPage = lazy(() =>
  import('../pages/business-data/BusinessDataOverviewPage').then((module) => ({
    default: module.BusinessDataOverviewPage,
  })),
)
const BusinessSpaceListPage = lazy(() =>
  import('../pages/business-data/BusinessSpacePages').then((module) => ({
    default: module.BusinessSpaceListPage,
  })),
)
const BusinessSpaceCreatePage = lazy(() =>
  import('../pages/business-data/BusinessSpacePages').then((module) => ({
    default: module.BusinessSpaceCreatePage,
  })),
)
const BusinessSpaceEditPage = lazy(() =>
  import('../pages/business-data/BusinessSpacePages').then((module) => ({
    default: module.BusinessSpaceEditPage,
  })),
)
const ProductListPage = lazy(() =>
  import('../pages/business-data/ProductPages').then((module) => ({
    default: module.ProductListPage,
  })),
)
const ProductCreatePage = lazy(() =>
  import('../pages/business-data/ProductPages').then((module) => ({
    default: module.ProductCreatePage,
  })),
)
const ProductEditPage = lazy(() =>
  import('../pages/business-data/ProductPages').then((module) => ({
    default: module.ProductEditPage,
  })),
)
const CompetitorListPage = lazy(() =>
  import('../pages/business-data/CompetitorPages').then((module) => ({
    default: module.CompetitorListPage,
  })),
)
const CompetitorCreatePage = lazy(() =>
  import('../pages/business-data/CompetitorPages').then((module) => ({
    default: module.CompetitorCreatePage,
  })),
)
const CompetitorEditPage = lazy(() =>
  import('../pages/business-data/CompetitorPages').then((module) => ({
    default: module.CompetitorEditPage,
  })),
)
const AdminHomePage = lazy(() =>
  import('../pages/AdminHomePage').then((module) => ({
    default: module.AdminHomePage,
  })),
)
const AdminPlatformPage = lazy(() => import('../pages/admin/AdminPlatformPage').then((module) => ({ default: module.AdminPlatformPage })))
const AdminUserManagementPage = lazy(() =>
  import('../pages/admin/AdminUserManagementPage').then((module) => ({
    default: module.AdminUserManagementPage,
  })),
)
const AdminTaskServicePage = lazy(() =>
  import('../pages/admin/AdminTaskServicePage').then((module) => ({
    default: module.AdminTaskServicePage,
  })),
)
const AdminPricingPage = lazy(() =>
  import('../pages/admin/AdminFinancePages').then((module) => ({
    default: module.AdminPricingPage,
  })),
)
const AdminLedgerPage = lazy(() =>
  import('../pages/admin/AdminFinancePages').then((module) => ({
    default: module.AdminLedgerPage,
  })),
)
const AdminRechargeOrdersPage = lazy(() =>
  import('../pages/admin/AdminFinancePages').then((module) => ({
    default: module.AdminRechargeOrdersPage,
  })),
)
const AdminInvoicePage = lazy(() =>
  import('../pages/admin/AdminPublishingPages').then((module) => ({
    default: module.AdminInvoicePage,
  })),
)
const AdminMediaPage = lazy(() =>
  import('../pages/admin/AdminPublishingPages').then((module) => ({
    default: module.AdminMediaPage,
  })),
)
const AdminFeedbackPage = lazy(() =>
  import('../pages/admin/AdminReviewPages').then((module) => ({
    default: module.AdminFeedbackPage,
  })),
)
const AdminAuditPage = lazy(() =>
  import('../pages/admin/AdminObservabilityPages').then((module) => ({
    default: module.AdminAuditPage,
  })),
)
const AdminAgentVersionsPage = lazy(() =>
  import('../pages/admin/AdminObservabilityPages').then((module) => ({
    default: module.AdminAgentVersionsPage,
  })),
)
const DevPreviewRoutes = import.meta.env.DEV
  ? lazy(() =>
      import('./DevPreviewRoutes').then((module) => ({
        default: module.DevPreviewRoutes,
      })),
    )
  : null
const ForbiddenPage = lazy(() =>
  import('../pages/ForbiddenPage').then((module) => ({
    default: module.ForbiddenPage,
  })),
)
const NotFoundPage = lazy(() =>
  import('../pages/NotFoundPage').then((module) => ({
    default: module.NotFoundPage,
  })),
)

function LandingRedirect() {
  const user = useAuthStore((state) => state.user)
  return <Navigate to={user ? roleHome(user.role) : APP_PATHS.login} replace />
}

export function AppRoutes() {
  const location = useLocation()

  return (
    <AuthBootstrap>
      <PageErrorBoundary
        key={location.pathname === APP_PATHS.userHome || location.pathname === APP_PATHS.chat || location.pathname === APP_PATHS.articles ? location.pathname : location.key}
        resetKey={location.key}
      >
      <Suspense fallback={<PageLoading label="正在加载页面…" />}>
        <Routes>
          <Route path={APP_PATHS.root} element={<LandingRedirect />} />
          <Route element={<PublicOnlyRoute />}>
            <Route path={APP_PATHS.login} element={<LoginPage />} />
            <Route path={APP_PATHS.firstLogin} element={<FirstLoginPage />} />
          </Route>
          {import.meta.env.DEV && DevPreviewRoutes ? (
            <Route path="/preview/*" element={<DevPreviewRoutes />} />
          ) : null}
          <Route element={<ProtectedRoute role="USER" />}>
            <Route path={APP_PATHS.notifications} element={<NotificationPage />} />
            <Route path={APP_PATHS.userHome} element={<ChatPage />} />
            <Route path={APP_PATHS.workbench} element={<UserHomePage />} />
            <Route path={APP_PATHS.agents} element={<AgentsPage />} />
            <Route path={APP_PATHS.knowledge} element={<KnowledgeLibraryPage />} />
            <Route path={APP_ROUTE_PATTERNS.articlePreparation} element={<ArticlePreparationPage />} />
            <Route path={APP_PATHS.chat} element={<ChatPage />} />
            <Route path={APP_PATHS.canvases} element={<CanvasListPage />} />
            <Route path={APP_ROUTE_PATTERNS.canvas} element={<CanvasWorkspacePage />} />
            <Route
              path={APP_PATHS.imageCreation}
              element={<ImageCreationPage />}
            />
            <Route
              path={APP_PATHS.videoCreation}
              element={<VideoCreationPage />}
            />
            <Route path={APP_PATHS.assets} element={<AssetCenterPage />} />
            <Route path={APP_PATHS.submissions} element={<SubmissionCenterPage />} />
            <Route
              path={APP_PATHS.articles}
              element={<ArticleSubmissionPage />}
            />
            <Route
              path={APP_PATHS.pointsBilling}
              element={<PointsBillingPage />}
            />
            <Route path={APP_PATHS.tasks} element={<TaskCenterPage />} />
            <Route
              path={APP_ROUTE_PATTERNS.taskDetail}
              element={<TaskCenterDetailPage />}
            />
            <Route
              path={APP_PATHS.workflowPlans}
              element={<WorkflowPlansPage />}
            />
            <Route
              path={APP_ROUTE_PATTERNS.workflowPlanDetail}
              element={<WorkflowPlanDetailPage />}
            />
            <Route path={APP_PATHS.geo} element={<GeoPage />} />
            <Route path={APP_PATHS.geoTopics} element={<GeoTopicsPage />} />
            <Route
              path={APP_PATHS.geoQuestions}
              element={<GeoQuestionsPage />}
            />
            <Route
              path={APP_PATHS.geoManualMonitoring}
              element={<GeoManualMonitoringPage />}
            />
            <Route
              path={APP_PATHS.geoDiagnostics}
              element={<GeoQuickMonitoringPage />}
            />
            <Route path={APP_PATHS.geoPlans} element={<GeoPlansPage />} />
            <Route
              path={APP_PATHS.geoCompetitors}
              element={<GeoCompetitorsPage />}
            />
            <Route path={APP_PATHS.geoReports} element={<GeoReportsPage />} />
            <Route path={APP_ROUTE_PATTERNS.geoDiagnosticReport} element={<GeoDiagnosticReportPage />} />
            <Route
              path={APP_ROUTE_PATTERNS.geoReportDetail}
              element={<GeoReportDetailPage />}
            />
            <Route
              path={APP_PATHS.businessData}
              element={<BusinessDataOverviewPage />}
            />
            <Route
              path={APP_PATHS.businessSpaces}
              element={<BusinessSpaceListPage />}
            />
            <Route
              path={APP_PATHS.businessSpaceCreate}
              element={<BusinessSpaceCreatePage />}
            />
            <Route
              path={APP_ROUTE_PATTERNS.businessSpaceEdit}
              element={<BusinessSpaceEditPage />}
            />
            <Route path={APP_PATHS.products} element={<ProductListPage />} />
            <Route
              path={APP_PATHS.productCreate}
              element={<ProductCreatePage />}
            />
            <Route
              path={APP_ROUTE_PATTERNS.productEdit}
              element={<ProductEditPage />}
            />
            <Route
              path={APP_PATHS.competitors}
              element={<CompetitorListPage />}
            />
            <Route
              path={APP_PATHS.competitorCreate}
              element={<CompetitorCreatePage />}
            />
            <Route
              path={APP_ROUTE_PATTERNS.competitorEdit}
              element={<CompetitorEditPage />}
            />
          </Route>
          <Route element={<ProtectedRoute role="ADMIN" />}>
            <Route path={APP_PATHS.adminHome} element={<AdminHomePage />} />
            <Route
              path={APP_PATHS.adminUsers}
              element={<AdminUserManagementPage />}
            />
            <Route
              path={APP_PATHS.adminTasks}
              element={<AdminTaskServicePage />}
            />
            <Route path={APP_PATHS.adminPricing} element={<AdminPricingPage />} />
            <Route path={APP_PATHS.adminLedger} element={<AdminLedgerPage />} />
            <Route
              path={APP_PATHS.adminRechargeOrders}
              element={<AdminRechargeOrdersPage />}
            />
            <Route path={APP_PATHS.adminInvoices} element={<AdminInvoicePage />} />
            <Route path={APP_PATHS.adminMedia} element={<AdminMediaPage />} />
            <Route path={APP_PATHS.adminFeedback} element={<AdminFeedbackPage />} />
            <Route path={APP_PATHS.adminAudit} element={<AdminAuditPage />} />
            <Route path={APP_PATHS.adminAgentVersions} element={<AdminAgentVersionsPage />} />
            <Route path={APP_PATHS.adminPlatform} element={<AdminPlatformPage />} />
          </Route>
          <Route path={APP_PATHS.forbidden} element={<ForbiddenPage />} />
          <Route path={APP_PATHS.notFound} element={<NotFoundPage />} />
          <Route
            path="*"
            element={<Navigate to={APP_PATHS.notFound} replace />}
          />
        </Routes>
      </Suspense>
      </PageErrorBoundary>
    </AuthBootstrap>
  )
}

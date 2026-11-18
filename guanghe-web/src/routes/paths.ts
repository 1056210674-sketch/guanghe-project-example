export const APP_PATHS = {
  root: '/',
  login: '/login',
  firstLogin: '/first-login',
  userHome: '/app',
  workbench: '/app/workbench',
  agents: '/app/agents',
  chat: '/app/chat',
  imageCreation: '/app/image-creation',
  videoCreation: '/app/video-creation',
  canvases: '/app/canvases',
  assets: '/app/assets',
  knowledge: '/app/knowledge',
  articles: '/app/articles',
  submissions: '/app/submissions',
  pointsBilling: '/app/points-billing',
  notifications: '/app/notifications',
  tasks: '/app/tasks',
  workflowPlans: '/app/workflow-plans',
  geo: '/app/geo',
  geoTopics: '/app/geo/topics',
  geoQuestions: '/app/geo/questions',
  geoManualMonitoring: '/app/geo/manual-monitoring',
  geoDiagnostics: '/app/geo/diagnostics',
  geoPlans: '/app/geo/plans',
  geoCompetitors: '/app/geo/competitors',
  geoReports: '/app/geo/reports',
  adminHome: '/admin',
  adminUsers: '/admin/users',
  adminTasks: '/admin/tasks',
  adminPricing: '/admin/pricing',
  adminLedger: '/admin/points-ledger',
  adminRechargeOrders: '/admin/recharge-orders',
  adminMedia: '/admin/media-submissions',
  adminInvoices: '/admin/invoices',
  adminFeedback: '/admin/feedback',
  adminAudit: '/admin/audit-logs',
  adminAgentVersions: '/admin/agent-versions',
  adminPlatform: '/admin/agent-platform',
  businessData: '/app/business-data',
  businessSpaces: '/app/business-data/business-spaces',
  businessSpaceCreate: '/app/business-data/business-spaces/new',
  products: '/app/business-data/products',
  productCreate: '/app/business-data/products/new',
  competitors: '/app/business-data/competitors',
  competitorCreate: '/app/business-data/competitors/new',
  forbidden: '/403',
  notFound: '/404',
} as const

export const APP_ROUTE_PATTERNS = {
  canvas: '/app/canvases/:canvasId',
  articlePreparation: '/app/article-preparations/:preparationId',
  taskDetail: '/app/tasks/:taskId',
  workflowPlanDetail: '/app/workflow-plans/:planId',
  geoReportDetail: '/app/geo/reports/:reportId',
  geoDiagnosticReport: '/app/geo/diagnostic-reports/:diagnosticId',
  businessSpaceEdit: '/app/business-data/business-spaces/:businessSpaceId/edit',
  productEdit: '/app/business-data/products/:productId/edit',
  competitorEdit: '/app/business-data/competitors/:competitorId/edit',
} as const

export const appLinks = {
  articlePreparation: (id: string) => `/app/article-preparations/${encodeURIComponent(id)}`,
  projectKnowledge: (projectId?: string, add?: 'document' | 'excerpt') => {
    const search = new URLSearchParams()
    if (projectId) search.set('project', projectId)
    if (add) search.set('add', add)
    return `/app/knowledge${search.size ? `?${search}` : ''}`
  },
  newAgentTask: (installationId?: string, projectId?: string) => {
    const search = new URLSearchParams()
    if (installationId) search.set('installation', installationId)
    if (projectId) search.set('project', projectId)
    return `/app${search.size ? `?${search}` : ''}`
  },
  taskDetail: (taskId: string) =>
    `/app/tasks/${encodeURIComponent(taskId)}`,
  workflowPlanDetail: (planId: string) =>
    `/app/workflow-plans/${encodeURIComponent(planId)}`,
  assetDetail: (assetId: string) =>
    `/app/assets?asset=${encodeURIComponent(assetId)}`,
  articleDetail: (articleId: string) =>
    `/app/articles?article=${encodeURIComponent(articleId)}`,
  geoReportDetail: (reportId: string) =>
    `/app/geo/reports/${encodeURIComponent(reportId)}`,
  geoDiagnosticReport: (diagnosticId: string) => `/app/geo/diagnostic-reports/${encodeURIComponent(diagnosticId)}`,
  geoReportChat: (reportId: string) =>
    `/app/chat?${new URLSearchParams({ geoReportId: reportId })}`,
  geoTopicTrend: (topicId: string) =>
    `/app/geo?${new URLSearchParams({ topicId, days: '30', questionScope: 'DISCOVERY' })}`,
  geoCompetitiveInsights: (topicId: string, reportId: string) =>
    `/app/geo/competitors?${new URLSearchParams({ topicId, reportId })}`,
  businessSpaceEdit: (businessSpaceId: string) =>
    `/app/business-data/business-spaces/${encodeURIComponent(businessSpaceId)}/edit`,
  productEdit: (productId: string) =>
    `/app/business-data/products/${encodeURIComponent(productId)}/edit`,
  competitorEdit: (competitorId: string) =>
    `/app/business-data/competitors/${encodeURIComponent(competitorId)}/edit`,
} as const

export type WorkflowResultRouteKind = 'task' | 'asset' | 'geo-report' | 'article'

export function workflowTaskResultPath(
  kind?: WorkflowResultRouteKind,
  id?: string,
): string | undefined {
  if (!kind || !id || !/^[1-9]\d*$/.test(id)) return undefined
  if (kind === 'task') return appLinks.taskDetail(id)
  if (kind === 'asset') return appLinks.assetDetail(id)
  if (kind === 'geo-report') return appLinks.geoReportDetail(id)
  return appLinks.articleDetail(id)
}

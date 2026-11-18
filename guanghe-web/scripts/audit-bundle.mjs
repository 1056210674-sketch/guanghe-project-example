import console from 'node:console'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { brotliCompressSync, constants, gzipSync } from 'node:zlib'

const distDirectory = path.resolve('dist')
const manifestPath = path.join(distDirectory, '.vite', 'manifest.json')

// Every production page module in AppRoutes must appear here. Keeping the route
// roots and their budgets together prevents a newly shipped commercial page
// from silently bypassing the bundle gate.
const routeAudits = {
  entry: { roots: [], budget: { jsRaw: 760_000, jsGzip: 250_000, cssRaw: 175_000 } },
  '/login': { roots: ['src/pages/LoginPage.tsx'], budget: { jsRaw: 830_000, jsGzip: 280_000, cssRaw: 175_000 } },
  '/first-login': { roots: ['src/pages/FirstLoginPage.tsx'], budget: { jsRaw: 950_000, jsGzip: 320_000, cssRaw: 175_000 } },
  '/app': { roots: ['src/pages/chat/ChatPage.tsx'], budget: { jsRaw: 1_670_000, jsGzip: 540_000, cssRaw: 175_000 } },
  '/app/workbench': { roots: ['src/pages/UserHomePage.tsx'], budget: { jsRaw: 1_670_000, jsGzip: 540_000, cssRaw: 175_000 } },
  '/app/agents': { roots: ['src/pages/agents/AgentsPage.tsx'], budget: { jsRaw: 1_050_000, jsGzip: 345_000, cssRaw: 175_000 } },
  '/app/knowledge': { roots: ['src/pages/knowledge/KnowledgeLibraryPage.tsx'], budget: { jsRaw: 1_670_000, jsGzip: 540_000, cssRaw: 175_000 } },
  '/app/article-preparations/:preparationId': { roots: ['src/pages/articles/ArticlePreparationPage.tsx'], budget: { jsRaw: 1_760_000, jsGzip: 560_000, cssRaw: 175_000 } },
  '/app/chat': { roots: ['src/pages/chat/ChatPage.tsx'], budget: { jsRaw: 1_820_000, jsGzip: 590_000, cssRaw: 175_000 } },
  '/app/image-creation': { roots: ['src/pages/image-creation/ImageCreationPage.tsx'], budget: { jsRaw: 1_680_000, jsGzip: 540_000, cssRaw: 175_000 } },
  '/app/video-creation': { roots: ['src/pages/video-creation/VideoCreationPage.tsx'], budget: { jsRaw: 1_690_000, jsGzip: 545_000, cssRaw: 175_000 } },
  '/app/assets': { roots: ['src/pages/assets/AssetCenterPage.tsx'], budget: { jsRaw: 1_550_000, jsGzip: 510_000, cssRaw: 175_000 } },
  '/app/articles': { roots: ['src/pages/articles/ArticleSubmissionPage.tsx'], budget: { jsRaw: 1_760_000, jsGzip: 560_000, cssRaw: 175_000 } },
  '/app/submissions': { roots: ['src/pages/submissions/SubmissionCenterPage.tsx'], budget: { jsRaw: 1_300_000, jsGzip: 430_000, cssRaw: 175_000 } },
  '/app/points-billing': { roots: ['src/pages/points-billing/PointsBillingPage.tsx'], budget: { jsRaw: 1_300_000, jsGzip: 430_000, cssRaw: 175_000 } },
  '/app/notifications': { roots: ['src/pages/notifications/NotificationPage.tsx'], budget: { jsRaw: 1_100_000, jsGzip: 370_000, cssRaw: 180_000 } },
  '/app/tasks': { roots: ['src/pages/task-center/TaskCenterPage.tsx'], budget: { jsRaw: 1_670_000, jsGzip: 540_000, cssRaw: 180_000 } },
  '/app/tasks/:taskId': { roots: ['src/pages/task-center/TaskCenterPage.tsx'], budget: { jsRaw: 1_670_000, jsGzip: 540_000, cssRaw: 180_000 } },
  '/app/workflow-plans': { roots: ['src/pages/workflow-plans/WorkflowPlansPage.tsx'], budget: { jsRaw: 1_650_000, jsGzip: 525_000, cssRaw: 185_000 } },
  '/app/workflow-plans/:planId': { roots: ['src/pages/workflow-plans/WorkflowPlansPage.tsx'], budget: { jsRaw: 1_650_000, jsGzip: 525_000, cssRaw: 185_000 } },
  '/app/geo': { roots: ['src/pages/geo/GeoPage.tsx'], budget: { jsRaw: 1_840_000, jsGzip: 590_000, cssRaw: 195_000 } },
  '/app/geo/topics': { roots: ['src/pages/geo/GeoPage.tsx'], budget: { jsRaw: 1_840_000, jsGzip: 590_000, cssRaw: 195_000 } },
  '/app/geo/questions': { roots: ['src/pages/geo/GeoPage.tsx'], budget: { jsRaw: 1_840_000, jsGzip: 590_000, cssRaw: 195_000 } },
  '/app/geo/manual-monitoring': { roots: ['src/pages/geo/GeoPage.tsx', 'src/pages/geo/GeoManualMonitoringSection.tsx'], budget: { jsRaw: 1_850_000, jsGzip: 600_000, cssRaw: 195_000 } },
  '/app/geo/diagnostics': { roots: ['src/pages/geo/GeoPage.tsx'], budget: { jsRaw: 1_840_000, jsGzip: 590_000, cssRaw: 195_000 } },
  '/app/geo/plans': { roots: ['src/pages/geo/GeoPage.tsx', 'src/pages/geo/GeoPlansSection.tsx'], budget: { jsRaw: 1_850_000, jsGzip: 600_000, cssRaw: 195_000 } },
  '/app/geo/competitors': { roots: ['src/pages/geo/GeoPage.tsx'], budget: { jsRaw: 1_840_000, jsGzip: 590_000, cssRaw: 195_000 } },
  '/app/geo/reports': { roots: ['src/pages/geo/GeoPage.tsx'], budget: { jsRaw: 1_840_000, jsGzip: 590_000, cssRaw: 195_000 } },
  '/app/geo/reports/:reportId': { roots: ['src/pages/geo/GeoReportDetailPage.tsx'], budget: { jsRaw: 1_760_000, jsGzip: 560_000, cssRaw: 195_000 } },
  // Diagnostic delivery is independently lazy-loaded, with the existing report-detail budget.
  '/app/geo/diagnostic-reports/:diagnosticId': { roots: ['src/pages/geo/GeoDiagnosticReportPage.tsx'], budget: { jsRaw: 1_760_000, jsGzip: 560_000, cssRaw: 195_000 } },
  '/app/business-data': { roots: ['src/pages/business-data/BusinessDataOverviewPage.tsx'], budget: { jsRaw: 1_450_000, jsGzip: 480_000, cssRaw: 175_000 } },
  '/app/business-data/business-spaces': { roots: ['src/pages/business-data/BusinessSpacePages.tsx'], budget: { jsRaw: 1_650_000, jsGzip: 520_000, cssRaw: 175_000 } },
  '/app/business-data/business-spaces/new': { roots: ['src/pages/business-data/BusinessSpacePages.tsx'], budget: { jsRaw: 1_650_000, jsGzip: 520_000, cssRaw: 175_000 } },
  '/app/business-data/business-spaces/:businessSpaceId/edit': { roots: ['src/pages/business-data/BusinessSpacePages.tsx'], budget: { jsRaw: 1_650_000, jsGzip: 520_000, cssRaw: 175_000 } },
  '/app/business-data/products': { roots: ['src/pages/business-data/ProductPages.tsx'], budget: { jsRaw: 1_670_000, jsGzip: 525_000, cssRaw: 175_000 } },
  '/app/business-data/products/new': { roots: ['src/pages/business-data/ProductPages.tsx'], budget: { jsRaw: 1_670_000, jsGzip: 525_000, cssRaw: 175_000 } },
  '/app/business-data/products/:productId/edit': { roots: ['src/pages/business-data/ProductPages.tsx'], budget: { jsRaw: 1_670_000, jsGzip: 525_000, cssRaw: 175_000 } },
  '/app/business-data/competitors': { roots: ['src/pages/business-data/CompetitorPages.tsx'], budget: { jsRaw: 1_650_000, jsGzip: 520_000, cssRaw: 175_000 } },
  '/app/business-data/competitors/new': { roots: ['src/pages/business-data/CompetitorPages.tsx'], budget: { jsRaw: 1_650_000, jsGzip: 520_000, cssRaw: 175_000 } },
  '/app/business-data/competitors/:competitorId/edit': { roots: ['src/pages/business-data/CompetitorPages.tsx'], budget: { jsRaw: 1_650_000, jsGzip: 520_000, cssRaw: 175_000 } },
  '/admin': { roots: ['src/pages/AdminHomePage.tsx'], budget: { jsRaw: 1_250_000, jsGzip: 415_000, cssRaw: 175_000 } },
  '/admin/platform': { roots: ['src/pages/admin/AdminPlatformPage.tsx'], budget: { jsRaw: 1_350_000, jsGzip: 450_000, cssRaw: 175_000 } },
  '/admin/users': { roots: ['src/pages/admin/AdminUserManagementPage.tsx'], budget: { jsRaw: 1_250_000, jsGzip: 415_000, cssRaw: 175_000 } },
  '/admin/tasks': { roots: ['src/pages/admin/AdminTaskServicePage.tsx'], budget: { jsRaw: 1_050_000, jsGzip: 345_000, cssRaw: 175_000 } },
  '/admin/pricing': { roots: ['src/pages/admin/AdminFinancePages.tsx'], budget: { jsRaw: 1_350_000, jsGzip: 450_000, cssRaw: 175_000 } },
  '/admin/points-ledger': { roots: ['src/pages/admin/AdminFinancePages.tsx'], budget: { jsRaw: 1_350_000, jsGzip: 450_000, cssRaw: 175_000 } },
  '/admin/recharge-orders': { roots: ['src/pages/admin/AdminFinancePages.tsx'], budget: { jsRaw: 1_350_000, jsGzip: 450_000, cssRaw: 175_000 } },
  '/admin/invoices': { roots: ['src/pages/admin/AdminPublishingPages.tsx'], budget: { jsRaw: 1_300_000, jsGzip: 430_000, cssRaw: 175_000 } },
  // Same module as the invoices route, so the same measured chunks and the same budget.
  '/admin/media-submissions': { roots: ['src/pages/admin/AdminPublishingPages.tsx'], budget: { jsRaw: 1_300_000, jsGzip: 430_000, cssRaw: 175_000 } },
  '/admin/feedback': { roots: ['src/pages/admin/AdminReviewPages.tsx'], budget: { jsRaw: 1_300_000, jsGzip: 430_000, cssRaw: 175_000 } },
  '/admin/audit-logs': { roots: ['src/pages/admin/AdminObservabilityPages.tsx'], budget: { jsRaw: 1_300_000, jsGzip: 430_000, cssRaw: 175_000 } },
  // Shares the observability page module with audit logs, so it carries the same measured chunks.
  '/admin/agent-versions': { roots: ['src/pages/admin/AdminObservabilityPages.tsx'], budget: { jsRaw: 1_300_000, jsGzip: 430_000, cssRaw: 175_000 } },
}

const routeRoots = Object.fromEntries(
  Object.entries(routeAudits).map(([route, audit]) => [route, audit.roots]),
)

const limits = {
  maxChunk: { raw: 520_000, gzip: 175_000 },
  maxCss: { raw: 195_000 },
  routes: Object.fromEntries(
    Object.entries(routeAudits).map(([route, audit]) => [route, audit.budget]),
  ),
}

function formatBytes(value) {
  return `${(value / 1024).toFixed(2)} KiB`
}

function listFiles(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const resolved = path.join(directory, entry.name)
    return entry.isDirectory() ? listFiles(resolved) : [resolved]
  })
}

function compressedSize(buffer) {
  return {
    raw: buffer.length,
    gzip: gzipSync(buffer).length,
    brotli: brotliCompressSync(buffer, {
      params: { [constants.BROTLI_PARAM_QUALITY]: 6 },
    }).length,
  }
}

if (!fs.existsSync(manifestPath)) {
  throw new Error('Bundle manifest is missing. Run `npm run build` first.')
}

const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
const entryKey = Object.keys(manifest).find((key) => manifest[key].isEntry)
if (!entryKey) throw new Error('Bundle manifest does not contain an entry chunk.')

const metrics = new Map()
function fileMetrics(relativePath) {
  if (!metrics.has(relativePath)) {
    metrics.set(
      relativePath,
      compressedSize(fs.readFileSync(path.join(distDirectory, relativePath))),
    )
  }
  return metrics.get(relativePath)
}

function addImports(key, keys) {
  if (!key || keys.has(key)) return
  const chunk = manifest[key]
  if (!chunk) throw new Error(`Manifest entry is missing: ${key}`)
  keys.add(key)
  for (const importedKey of chunk.imports ?? []) addImports(importedKey, keys)
}

function routeRootKey(sourcePath) {
  if (manifest[sourcePath]) return sourcePath
  // Vite can merge a lazily routed page into a shared chunk when another page
  // also imports it statically (for example, the article panel in chat).
  // Resolve only an unambiguous emitted chunk; never silently omit its budget.
  const name = path.basename(sourcePath, path.extname(sourcePath))
  const matches = Object.entries(manifest).filter(
    ([, chunk]) => chunk.name === name,
  )
  if (matches.length !== 1) {
    throw new Error(`Manifest route root is missing or ambiguous: ${sourcePath}`)
  }
  return matches[0][0]
}

function sumFiles(files) {
  return [...files].reduce(
    (total, file) => {
      const current = fileMetrics(file)
      total.raw += current.raw
      total.gzip += current.gzip
      total.brotli += current.brotli
      return total
    },
    { raw: 0, gzip: 0, brotli: 0 },
  )
}

const failures = []
const routeRows = []
for (const [route, roots] of Object.entries(routeRoots)) {
  const keys = new Set()
  addImports(entryKey, keys)
  for (const root of roots) addImports(routeRootKey(root), keys)
  const jsFiles = new Set()
  const cssFiles = new Set()
  for (const key of keys) {
    const chunk = manifest[key]
    if (chunk.file?.endsWith('.js')) jsFiles.add(chunk.file)
    for (const cssFile of chunk.css ?? []) cssFiles.add(cssFile)
  }
  const js = sumFiles(jsFiles)
  const css = sumFiles(cssFiles)
  const budget = limits.routes[route]
  if (js.raw > budget.jsRaw) failures.push(`${route} JS raw exceeds budget`)
  if (js.gzip > budget.jsGzip) failures.push(`${route} JS gzip exceeds budget`)
  if (css.raw > budget.cssRaw) failures.push(`${route} CSS raw exceeds budget`)
  routeRows.push({ route, jsFiles: jsFiles.size, js, cssFiles: cssFiles.size, css })
}

const allFiles = listFiles(distDirectory)
const sourceMaps = allFiles.filter((file) => file.endsWith('.map'))
if (sourceMaps.length) failures.push('Production source maps are present')

const jsAssets = allFiles.filter((file) => file.endsWith('.js'))
const previewPatterns = [
  '/preview/',
  'PREVIEW_ADMIN',
  'preview-agent',
  'preview-user',
  'AdminObservabilityPreview',
  'NotificationPreview',
  'AssetCenterPreview',
]
const fixturePatterns = ['src/test/fixtures', 'test/fixtures']
for (const file of jsAssets) {
  const content = fs.readFileSync(file, 'utf8')
  for (const pattern of [...previewPatterns, ...fixturePatterns]) {
    if (content.includes(pattern)) failures.push(`${path.basename(file)} contains ${pattern}`)
  }
}

const jsRows = jsAssets
  .map((file) => ({
    file: path.relative(distDirectory, file).replaceAll('\\', '/'),
    ...fileMetrics(path.relative(distDirectory, file).replaceAll('\\', '/')),
  }))
  .sort((left, right) => right.raw - left.raw)
const largestChunk = jsRows[0]
if (largestChunk.raw > limits.maxChunk.raw) failures.push('Largest JS chunk raw size exceeds budget')
if (largestChunk.gzip > limits.maxChunk.gzip) failures.push('Largest JS chunk gzip size exceeds budget')

const cssRows = allFiles
  .filter((file) => file.endsWith('.css'))
  .map((file) => ({
    file: path.relative(distDirectory, file).replaceAll('\\', '/'),
    ...fileMetrics(path.relative(distDirectory, file).replaceAll('\\', '/')),
  }))
  .sort((left, right) => right.raw - left.raw)
const largestCss = cssRows[0]
if (largestCss?.raw > limits.maxCss.raw) failures.push('Largest CSS chunk raw size exceeds budget')

console.log('Production route bundle audit')
console.table(routeRows.map((row) => ({
  route: row.route,
  jsChunks: row.jsFiles,
  jsRaw: formatBytes(row.js.raw),
  jsGzip: formatBytes(row.js.gzip),
  jsBrotli: formatBytes(row.js.brotli),
  cssChunks: row.cssFiles,
  cssRaw: formatBytes(row.css.raw),
  cssGzip: formatBytes(row.css.gzip),
})))
console.log(
  `Largest JS: ${largestChunk.file} ` +
  `(raw ${formatBytes(largestChunk.raw)}, gzip ${formatBytes(largestChunk.gzip)}, ` +
  `brotli ${formatBytes(largestChunk.brotli)})`,
)
if (largestCss) {
  console.log(`Largest CSS: ${largestCss.file} (raw ${formatBytes(largestCss.raw)})`)
}

if (failures.length) {
  console.error(`Bundle budget failed:\n- ${[...new Set(failures)].join('\n- ')}`)
  process.exitCode = 1
} else {
  console.log('Bundle budget passed.')
}

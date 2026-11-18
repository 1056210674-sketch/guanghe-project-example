import {
  AppstoreOutlined,
  BarsOutlined,
  ExportOutlined,
  FileImageOutlined,
  FileTextOutlined,
  FileUnknownOutlined,
  PlayCircleFilled,
  UploadOutlined,
  VideoCameraOutlined,
} from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Pagination,
  Progress,
  Select,
  Tag,
  Typography,
  Modal,
  Input,
} from 'antd'
import { diagnosticTraceDescription } from '../../../shared/tracePresentation'
import { useState } from 'react'
import { ErrorState, PageLoading } from '../../../components/GlobalState'
import { APP_PATHS } from '../../../routes/paths'
import {
  IMAGE_UPLOAD_MAX_BYTES,
  imageUploadValidationError,
} from '../../shared/imageUploadPolicy'
import type {
  AssetFilterView,
  AssetItem,
  AssetKind,
  AssetOption,
  AssetPaginationView,
  AssetSafetyStatus,
  AssetSource,
  AssetStatus,
  AssetStorageSummary,
  AssetViewError,
} from '../types'

const GIGABYTE = 1024 * 1024 * 1024
const ASSET_TABS: readonly { value: AssetKind | undefined; label: string }[] = [
  { value: undefined, label: '全部' },
  { value: 'IMAGE', label: '图片' },
  { value: 'VIDEO', label: '视频' },
]

const SOURCE_OPTIONS: readonly { label: string; value?: AssetSource }[] = [
  { label: '全部来源', value: undefined },
  { label: '任务生成', value: 'GENERATED' },
  { label: '用户上传', value: 'UPLOADED' },
  { label: '外部导入', value: 'IMPORTED' },
]

const KIND_LABELS: Record<AssetKind, string> = {
  IMAGE: '图片',
  VIDEO: '视频',
  ARTICLE: '文章',
  GEO_REPORT: 'GEO 报告',
}

const SOURCE_LABELS: Record<AssetSource, string> = {
  GENERATED: '任务生成',
  UPLOADED: '用户上传',
  IMPORTED: '外部导入',
}

const STATUS_META: Record<AssetStatus, { label: string; color: string }> = {
  UPLOADING: { label: '上传中', color: 'processing' },
  SCANNING: { label: '检查中', color: 'processing' },
  READY: { label: '就绪', color: 'success' },
  SAFETY_REVIEW: { label: '安全审核中', color: 'warning' },
  HIDDEN: { label: '已隐藏', color: 'default' },
  REJECTED: { label: '已拒绝', color: 'error' },
  FAILED: { label: '处理失败', color: 'error' },
  DELETING: { label: '删除中', color: 'default' },
}

const SAFETY_LABELS: Record<AssetSafetyStatus, string> = {
  PENDING: '素材处理中',
  APPROVED: '内容安全审核通过',
  TEST_BYPASS: '测试环境已跳过内容安全审核',
  NOT_REVIEWED: '未进行内容审核',
  REJECTED: '内容安全审核未通过',
  UNAVAILABLE: '内容安全状态暂不可用',
}

export interface AssetCenterWorkspaceProps {
  projectMode?: boolean
  assets: readonly AssetItem[]
  businessSpaces: readonly AssetOption[]
  uploadScopes?: readonly AssetOption[]
  products: readonly AssetOption[]
  storage: AssetStorageSummary
  filters: AssetFilterView
  pagination: AssetPaginationView
  loading: boolean
  error: AssetViewError | null
  detailOpen: boolean
  detailAsset: AssetItem | null
  detailLoading: boolean
  detailError: AssetViewError | null
  previewUrlsByAssetId: Readonly<Record<string, string | undefined>>
  previewExpiresAtByAssetId: Readonly<Record<string, string | undefined>>
  openingAssetId?: string
  actionError?: AssetViewError | null
  onFiltersChange: (filters: AssetFilterView) => void
  onPageChange: (page: number, size: number) => void
  onOpenDetail: (assetId: string) => void
  onCloseDetail: () => void
  onOpenOriginal: (assetId: string) => void
  onRetry: () => void
  onRetryDetail: () => void
  onUploadImage?: (input: {
    spaceId: string
    file: File
    name?: string
    onProgress?: (percent: number) => void
  }) => Promise<void> | void
}

function validByteCount(value: number | undefined): value is number {
  return value !== undefined && Number.isFinite(value) && value >= 0
}

function uploadFileError(file: File): string | undefined {
  return imageUploadValidationError(file)
}

function formatBytes(value?: number): string {
  if (!validByteCount(value)) return '—'
  if (value >= GIGABYTE) return `${(value / GIGABYTE).toFixed(1)} GB`
  if (value >= 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`
  if (value >= 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${value} B`
}

function formatDateTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      }).format(date)
}

function formatExpiry(value?: string): string | undefined {
  return value ? formatDateTime(value) : undefined
}

function kindIcon(kind: AssetKind) {
  if (kind === 'IMAGE') return <FileImageOutlined />
  if (kind === 'VIDEO') return <VideoCameraOutlined />
  if (kind === 'ARTICLE') return <FileTextOutlined />
  return <FileUnknownOutlined />
}

function AssetThumbnail({
  asset,
  imageUrl,
  controls = false,
}: {
  asset: AssetItem
  imageUrl?: string
  controls?: boolean
}) {
  const [loadFailed, setLoadFailed] = useState(false)
  const hasMedia = imageUrl && !loadFailed
  return (
    <div className={`asset-card__preview asset-card__preview--${asset.kind.toLowerCase()}`}>
      {hasMedia && asset.kind === 'VIDEO' ? (
        <video
          src={imageUrl}
          aria-label={`播放视频 ${asset.name}`}
          controls={controls}
          controlsList="nodownload"
          muted={!controls}
          preload="metadata"
          playsInline
          onClick={controls ? (event) => event.stopPropagation() : undefined}
          onError={() => setLoadFailed(true)}
        />
      ) : hasMedia && asset.kind === 'IMAGE' ? (
        <img
          src={imageUrl}
          alt=""
          width={320}
          height={220}
          loading="lazy"
          onError={() => setLoadFailed(true)}
        />
      ) : (
        <div className="asset-card__placeholder">
          {kindIcon(asset.kind)}
          <span>{KIND_LABELS[asset.kind]}</span>
        </div>
      )}
      {asset.kind === 'VIDEO' ? <PlayCircleFilled className="asset-card__play" /> : null}
      {asset.status === 'REJECTED' || asset.status === 'FAILED' ? (
        <div className="asset-card__blocked">素材未通过检查</div>
      ) : null}
    </div>
  )
}

function AssetCard({
  asset,
  projectMode,
  imageUrl,
  layout,
  onOpen,
}: {
  asset: AssetItem
  projectMode: boolean
  imageUrl?: string
  layout: 'grid' | 'list'
  onOpen: () => void
}) {
  const status = STATUS_META[asset.status]
  return (
    <article
      className={`asset-card asset-card--${layout}`}
      tabIndex={0}
      role="button"
      aria-label={`查看资产 ${asset.name}`}
      onClick={onOpen}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          onOpen()
        }
      }}
    >
      <AssetThumbnail key={imageUrl ?? 'no-image'} asset={asset} imageUrl={imageUrl} />
      <div className="asset-card__body">
        <div className="asset-card__title-row">
          <Typography.Text strong ellipsis>{asset.name}</Typography.Text>
          <Tag variant="filled">{KIND_LABELS[asset.kind]}</Tag>
        </div>
        <Typography.Text type="secondary" ellipsis>
          {projectMode ? `归属：${asset.businessSpaceName ?? '未归入客户项目'}` : `关联商品：${asset.productName ?? (asset.productId ? '已关联' : '未关联')}`}
        </Typography.Text>
        <div className="asset-card__meta">
          <time>{formatDateTime(asset.createdAt)}</time>
          <Tag color={status.color} variant="filled">{status.label}</Tag>
        </div>
      </div>
    </article>
  )
}

function AssetDetailDrawer({
  open,
  projectMode,
  asset,
  loading,
  error,
  imageUrl,
  expiresAt,
  opening,
  onClose,
  onOpenOriginal,
  onRetry,
}: {
  open: boolean
  projectMode: boolean
  asset: AssetItem | null
  loading: boolean
  error: AssetViewError | null
  imageUrl?: string
  expiresAt?: string
  opening: boolean
  onClose: () => void
  onOpenOriginal: () => void
  onRetry: () => void
}) {
  const status = asset ? STATUS_META[asset.status] : null
  return (
    <Drawer
      className="asset-detail-drawer"
      title="资产详情"
      size={420}
      open={open}
      onClose={onClose}
      destroyOnHidden
    >
      {loading ? (
        <PageLoading label="正在加载资产详情…" />
      ) : error ? (
        <ErrorState message={error.message} traceId={error.traceId} onRetry={onRetry} />
      ) : asset ? (
        <div className="asset-detail">
          <AssetThumbnail
            key={imageUrl ?? 'no-image'}
            asset={asset}
            imageUrl={imageUrl}
            controls={asset.kind === 'VIDEO'}
          />
          <div className="asset-detail__title">
            <Typography.Title level={4}>{asset.name}</Typography.Title>
            <Tag variant="filled">{KIND_LABELS[asset.kind]}</Tag>
            <Tag color={status?.color} variant="filled">{status?.label}</Tag>
          </div>
          <Typography.Text type="secondary">
            创建时间：{formatDateTime(asset.createdAt)}
          </Typography.Text>
          <section>
            <Typography.Title level={5}>基本信息</Typography.Title>
            <dl>
              <div><dt>类型</dt><dd>{KIND_LABELS[asset.kind]}</dd></div>
              <div><dt>格式</dt><dd>{asset.contentType}</dd></div>
              <div><dt>尺寸</dt><dd>{asset.width && asset.height ? `${asset.width} × ${asset.height}` : '—'}</dd></div>
              {asset.kind === 'VIDEO' ? (
                <div><dt>时长</dt><dd>{asset.durationSeconds ? `${asset.durationSeconds} 秒` : '—'}</dd></div>
              ) : null}
              <div><dt>大小</dt><dd>{formatBytes(asset.sizeBytes)}</dd></div>
              <div><dt>来源</dt><dd>{SOURCE_LABELS[asset.source]}</dd></div>
              <div><dt>内容审核</dt><dd>{SAFETY_LABELS[asset.safetyStatus]}</dd></div>
            </dl>
          </section>
          <section>
            <Typography.Title level={5}>关联信息</Typography.Title>
            <dl>
              <div><dt>{projectMode ? '素材归属' : '经营空间'}</dt><dd>{asset.businessSpaceName ?? (projectMode ? '未归入客户项目' : asset.businessSpaceId)}</dd></div>
              {!projectMode ? <div><dt>关联商品</dt><dd>{asset.productName ?? asset.productId ?? '未关联'}</dd></div> : null}
              {asset.sourceTaskId ? <div><dt>来源任务</dt><dd>{asset.sourceTaskId}</dd></div> : null}
            </dl>
          </section>
          {expiresAt ? (
            <Alert
              type="info"
              showIcon
              title={`临时链接有效期至 ${formatExpiry(expiresAt)}`}
              description="打开时会重新获取短效 HTTPS 链接。"
            />
          ) : null}
          <div className="asset-detail__actions">
            <Button
              aria-label={asset.kind === 'VIDEO' ? '打开资产原视频' : '打开资产原图'}
              type="primary"
              icon={<ExportOutlined />}
              loading={opening}
              disabled={asset.status !== 'READY'}
              onClick={onOpenOriginal}
            >
              {asset.kind === 'VIDEO' ? '打开原视频' : '打开原图'}
            </Button>
          </div>
        </div>
      ) : (
        <Empty description="请选择资产查看详情" />
      )}
    </Drawer>
  )
}

export function AssetCenterWorkspace({
  projectMode = false,
  assets,
  businessSpaces,
  uploadScopes,
  products,
  filters,
  pagination,
  loading,
  error,
  detailOpen,
  detailAsset,
  detailLoading,
  detailError,
  previewUrlsByAssetId,
  previewExpiresAtByAssetId,
  openingAssetId,
  actionError,
  onFiltersChange,
  onPageChange,
  onOpenDetail,
  onCloseDetail,
  onOpenOriginal,
  onRetry,
  onRetryDetail,
  onUploadImage,
}: AssetCenterWorkspaceProps) {
  const [layout, setLayout] = useState<'grid' | 'list'>('grid')
  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploadSpaceId, setUploadSpaceId] = useState<string>()
  const [uploadName, setUploadName] = useState('')
  const [uploadFile, setUploadFile] = useState<File>()
  const [uploading, setUploading] = useState(false)
  const [uploadProgress, setUploadProgress] = useState(0)
  const [uploadError, setUploadError] = useState<string>()
  const uploadOptions = uploadScopes ?? businessSpaces
  const currentPageCounts = {
    IMAGE: assets.filter((asset) => asset.kind === 'IMAGE').length,
    VIDEO: assets.filter((asset) => asset.kind === 'VIDEO').length,
  }
  const resetUploadForm = () => {
    setUploadOpen(false)
    setUploadSpaceId(undefined)
    setUploadName('')
    setUploadFile(undefined)
    setUploadProgress(0)
    setUploadError(undefined)
  }
  const openUploadForm = () => {
    setUploadOpen(true)
    setUploadSpaceId(uploadOptions.find((option) => option.value === filters.spaceId)?.value ?? uploadOptions[0]?.value)
    setUploadName('')
    setUploadFile(undefined)
    setUploadProgress(0)
    setUploadError(undefined)
  }

  return (
    <section className="asset-center" aria-labelledby="asset-center-title">
      <header className="asset-center-heading">
        <div>
          <Typography.Title id="asset-center-title" level={1}>资产中心</Typography.Title>
          <Typography.Text type="secondary">{projectMode ? '按客户项目整理图片和视频；无项目的个人创作也保留在这里。已保存的文章可在“文章”中编辑。' : '管理已上传的图片和生成的图片、视频；文章与 GEO 报告可从下方入口查看。'}</Typography.Text>
        </div>
        {onUploadImage ? <Button type="primary" icon={<UploadOutlined />} disabled={!uploadOptions.length} onClick={openUploadForm}>上传图片</Button> : null}
      </header>

      <Modal
        title="上传图片到资产中心"
        open={uploadOpen}
        okText="开始上传"
        cancelText="取消"
        confirmLoading={uploading}
        okButtonProps={{ disabled: !uploadSpaceId || !uploadFile }}
        onCancel={() => { if (!uploading) resetUploadForm() }}
        onOk={async () => {
          if (!onUploadImage || !uploadSpaceId || !uploadFile) return
          setUploading(true)
          setUploadError(undefined)
          setUploadProgress(0)
          try {
            await onUploadImage({
              spaceId: uploadSpaceId,
              file: uploadFile,
              name: uploadName.trim() || undefined,
              onProgress: (percent) => {
                setUploadProgress(Math.max(0, Math.min(100, Math.round(percent))))
              },
            })
            resetUploadForm()
          } catch (error) {
            setUploadError(error instanceof Error ? error.message : '图片上传失败，请稍后重试。')
          } finally {
            setUploading(false)
          }
        }}
      >
        {uploadError ? <Alert type="error" showIcon title={uploadError} /> : null}
        <label className="asset-upload-field">{projectMode ? '保存位置' : '经营空间'}<Select aria-label={projectMode ? '图片保存位置' : '上传经营空间'} value={uploadSpaceId} options={[...uploadOptions]} disabled={uploading} onChange={setUploadSpaceId} /></label>
        <label className="asset-upload-field">图片名称<Input aria-label="图片名称" value={uploadName} maxLength={120} onChange={(event) => setUploadName(event.target.value)} placeholder="可选" /></label>
        <label className="asset-upload-field">图片文件<input aria-label="图片文件" type="file" accept="image/png,image/jpeg" onChange={(event) => {
          const nextFile = event.target.files?.[0]
          setUploadProgress(0)
          setUploadError(undefined)
          if (!nextFile) {
            setUploadFile(undefined)
            return
          }
          const validationError = uploadFileError(nextFile)
          if (validationError) {
            setUploadFile(undefined)
            setUploadError(validationError)
            return
          }
          setUploadFile(nextFile)
        }} /></label>
        {uploading || uploadProgress > 0 ? <Progress percent={uploadProgress} status={uploadError ? 'exception' : undefined} aria-label="图片上传进度" /> : null}
        <Typography.Text type="secondary">仅支持 PNG/JPEG，单张不超过 {Math.round(IMAGE_UPLOAD_MAX_BYTES / 1024 / 1024)} MiB。系统会检查文件格式和大小；内容审核状态以资产详情为准。上传失败可重试。</Typography.Text>
      </Modal>

      <div className="asset-center-stats">
        <Card><FileImageOutlined /><div><span>本页图片</span><strong>{currentPageCounts.IMAGE}</strong></div></Card>
        <Card><VideoCameraOutlined /><div><span>本页视频</span><strong>{currentPageCounts.VIDEO}</strong></div></Card>
      </div>

      <div className="asset-center-destinations">
        <div className="asset-center-tabs" role="tablist" aria-label="素材类型">
          {ASSET_TABS.map((tab) => (
            <button
              type="button"
              role="tab"
              key={tab.value ?? 'ALL'}
              aria-selected={filters.type === tab.value}
              className={filters.type === tab.value ? 'is-active' : undefined}
              onClick={() => onFiltersChange({ ...filters, type: tab.value })}
            >
              {tab.label}
            </button>
          ))}
        </div>
        <nav className="asset-center-result-links" aria-label="文章与报告入口">
          {!projectMode ? <Button href={APP_PATHS.articles} aria-label="文章库" icon={<FileTextOutlined />}>文章库</Button> : null}
          <Button href={APP_PATHS.geoReports} aria-label="GEO 历史报告" icon={<FileUnknownOutlined />}>GEO 历史报告</Button>
        </nav>
      </div>

      <div className="asset-center-toolbar">
        <Select
          aria-label={projectMode ? '按项目筛选' : '按经营空间筛选'}
          placeholder={projectMode ? '全部项目与个人素材' : '经营空间'}
          allowClear
          value={filters.spaceId}
          options={[...businessSpaces]}
          disabled={businessSpaces.length === 0}
          onChange={(spaceId) => onFiltersChange({ ...filters, spaceId, productId: undefined })}
        />
        {!projectMode ? <Select
          aria-label="按商品筛选"
          placeholder="关联商品"
          allowClear
          value={filters.productId}
          options={[...products]}
          disabled={!filters.spaceId || products.length === 0}
          onChange={(productId) => onFiltersChange({ ...filters, productId })}
        /> : null}
        <Select
          aria-label="按来源筛选"
          value={filters.sourceType}
          placeholder="全部来源"
          allowClear
          options={[...SOURCE_OPTIONS].filter((option) => option.value)}
          onChange={(sourceType) => onFiltersChange({ ...filters, sourceType })}
        />
        <div className="asset-center-toolbar__spacer" />
        <div className="asset-layout-toggle" role="group" aria-label="展示方式">
          <Button type={layout === 'grid' ? 'primary' : 'default'} icon={<AppstoreOutlined />} aria-label="网格视图" onClick={() => setLayout('grid')} />
          <Button type={layout === 'list' ? 'primary' : 'default'} icon={<BarsOutlined />} aria-label="列表视图" onClick={() => setLayout('list')} />
        </div>
      </div>

      {actionError ? (
        <Alert type="error" showIcon title={actionError.message} description={diagnosticTraceDescription(actionError.traceId)} />
      ) : null}

      {loading ? (
        <PageLoading label="正在加载资产…" />
      ) : error ? (
        <ErrorState message={error.message} traceId={error.traceId} onRetry={onRetry} />
      ) : assets.length === 0 ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="暂无符合当前条件的永久资产"
        />
      ) : (
        <div className={`asset-center-content asset-center-content--${layout}`}>
          {assets.map((asset) => (
            <AssetCard
              key={asset.id}
              projectMode={projectMode}
              asset={asset}
              layout={layout}
              imageUrl={previewUrlsByAssetId[asset.id]}
              onOpen={() => onOpenDetail(asset.id)}
            />
          ))}
        </div>
      )}

      <footer className="asset-center-footer">
        <span>共 {pagination.total} 项</span>
        <Pagination
          current={pagination.page}
          pageSize={pagination.size}
          total={pagination.total}
          showSizeChanger
          onChange={onPageChange}
        />
      </footer>

      <AssetDetailDrawer
        open={detailOpen}
        projectMode={projectMode}
        asset={detailAsset}
        loading={detailLoading}
        error={detailError}
        imageUrl={detailAsset ? previewUrlsByAssetId[detailAsset.id] : undefined}
        expiresAt={detailAsset ? previewExpiresAtByAssetId[detailAsset.id] : undefined}
        opening={Boolean(detailAsset && openingAssetId === detailAsset.id)}
        onClose={onCloseDetail}
        onOpenOriginal={() => detailAsset && onOpenOriginal(detailAsset.id)}
        onRetry={onRetryDetail}
      />
    </section>
  )
}

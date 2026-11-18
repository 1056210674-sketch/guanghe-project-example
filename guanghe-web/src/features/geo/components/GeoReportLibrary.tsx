import {
  DownloadOutlined,
  InboxOutlined,
  RadarChartOutlined,
  RollbackOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import { Button, Card, Drawer, Input, Select, Space, Table, Typography } from 'antd'
import type {
  GeoAsyncViewProps,
  GeoPlatformView,
  GeoReportFilterView,
  GeoReportListItemView,
  GeoReportStatus,
  GeoSelectOption,
} from '../types'
import { GeoPlatformBadge } from './GeoPlatformBadge'
import { GeoStateFrame } from './GeoStateFrame'
import { GeoStatusTag } from './GeoStatusTag'

export interface GeoReportLibraryProps extends GeoAsyncViewProps {
  reports: readonly GeoReportListItemView[]
  archivedReports: readonly GeoReportListItemView[]
  platforms: readonly GeoPlatformView[]
  objectOptions: readonly GeoSelectOption[]
  typeOptions: readonly GeoSelectOption[]
  filters: GeoReportFilterView
  archiveOpen: boolean
  enabledFilters?: readonly ('OBJECT' | 'TYPE' | 'STATUS' | 'SEARCH')[]
  pagination?: { current: number; pageSize: number; total: number }
  archivePagination?: { current: number; pageSize: number; total: number }
  exportingReportId?: string
  archivingReportId?: string
  restoringReportId?: string
  onFiltersChange?: (filters: GeoReportFilterView) => void
  onPageChange?: (page: number, pageSize: number) => void
  onArchivePageChange?: (page: number, pageSize: number) => void
  onResetFilters?: () => void
  onViewReport?: (reportId: string) => void
  onExportReportIntent?: (reportId: string) => void
  onArchiveReportIntent?: (reportId: string) => void
  onRestoreReportIntent?: (reportId: string) => void
  onOpenArchive?: () => void
  onCloseArchive?: () => void
  onCreateMonitoring?: () => void
}

const REPORT_STATUS_LABELS: Record<GeoReportStatus, string> = {
  SUCCEEDED: '已完成',
  PARTIAL: '部分失败',
  FAILED: '失败',
}

export function GeoReportLibrary({
  loading,
  error,
  onRetry,
  reports,
  archivedReports,
  platforms,
  objectOptions,
  typeOptions,
  filters,
  archiveOpen,
  enabledFilters = ['OBJECT', 'TYPE', 'STATUS', 'SEARCH'],
  pagination,
  archivePagination,
  exportingReportId,
  archivingReportId,
  restoringReportId,
  onFiltersChange,
  onPageChange,
  onArchivePageChange,
  onResetFilters,
  onViewReport,
  onExportReportIntent,
  onArchiveReportIntent,
  onRestoreReportIntent,
  onOpenArchive,
  onCloseArchive,
  onCreateMonitoring,
}: GeoReportLibraryProps) {
  const visibleReports = reports.filter((report) => !report.archived)
  const archiveCount = archivePagination?.total ?? archivedReports.length

  return (
    <GeoStateFrame
      label="GEO 报告库"
      loading={loading}
      error={error}
      onRetry={onRetry}
      empty={
        reports.length === 0 &&
        archiveCount === 0 &&
        !filters.objectId &&
        !filters.type &&
        !filters.status &&
        !filters.search
      }
      emptyTitle="还没有 GEO 报告"
      emptyDescription="完成一次快速监测、手动监测或自动计划后，真实报告会出现在这里。"
      emptyAction={
        <Button
          type="primary"
          icon={<RadarChartOutlined />}
          disabled={!onCreateMonitoring}
          onClick={onCreateMonitoring}
        >
          创建监测
        </Button>
      }
    >
      <section className="geo-reports" aria-labelledby="geo-reports-title">
        <header className="geo-page-heading">
          <div>
            <Typography.Title id="geo-reports-title" level={1}>
              GEO 报告库
            </Typography.Title>
            <Typography.Paragraph type="secondary">
              {onOpenArchive
                ? '查看监测报告、失败范围和可追溯证据；归档不会删除报告证据。'
                : '查看监测报告、失败范围和可追溯证据。'}
            </Typography.Paragraph>
          </div>
          {onOpenArchive ? (
            <Button icon={<InboxOutlined />} onClick={onOpenArchive}>
              归档箱（{archiveCount}）
            </Button>
          ) : null}
        </header>

        <Card className="geo-panel-card geo-table-card">
          <div className="geo-report-filters">
            {enabledFilters.includes('OBJECT') ? (
              <Select
                allowClear
                placeholder="监测对象"
                value={filters.objectId}
                options={objectOptions.map((item) => ({
                  value: item.id,
                  label: item.label,
                }))}
                disabled={!onFiltersChange}
                onChange={(objectId) =>
                  onFiltersChange?.({ ...filters, objectId })
                }
              />
            ) : null}
            {enabledFilters.includes('TYPE') ? (
              <Select
                allowClear
                placeholder="报告类型"
                value={filters.type}
                options={typeOptions.map((item) => ({
                  value: item.id,
                  label: item.label,
                }))}
                disabled={!onFiltersChange}
                onChange={(type) => onFiltersChange?.({ ...filters, type })}
              />
            ) : null}
            {enabledFilters.includes('STATUS') ? (
              <Select
                allowClear
                placeholder="状态"
                value={filters.status}
                options={Object.entries(REPORT_STATUS_LABELS).map(
                  ([value, label]) => ({ value, label }),
                )}
                disabled={!onFiltersChange}
                onChange={(status) =>
                  onFiltersChange?.({
                    ...filters,
                    status: status as GeoReportStatus | undefined,
                  })
                }
              />
            ) : null}
            {enabledFilters.includes('SEARCH') ? (
              <Input
                allowClear
                prefix={<SearchOutlined />}
                placeholder="搜索报告名称或对象"
                value={filters.search}
                disabled={!onFiltersChange}
                onChange={(event) =>
                  onFiltersChange?.({ ...filters, search: event.target.value })
                }
              />
            ) : null}
            <Button disabled={!onResetFilters} onClick={onResetFilters}>
              重置
            </Button>
          </div>

          {visibleReports.length === 0 ? (
            <div className="geo-inline-empty geo-inline-empty--large">
              <Typography.Text strong>暂无符合条件的报告</Typography.Text>
              <Typography.Text type="secondary">
                调整筛选条件，或创建一次新的监测。
              </Typography.Text>
            </div>
          ) : (
            <GeoReportTable
              reports={visibleReports}
              platforms={platforms}
              pagination={pagination}
              exportingReportId={exportingReportId}
              archivingReportId={archivingReportId}
              onPageChange={onPageChange}
              onViewReport={onViewReport}
              onExportReportIntent={onExportReportIntent}
              onArchiveReportIntent={onArchiveReportIntent}
            />
          )}
        </Card>

        <Drawer
          title={`归档箱（${archiveCount}）`}
          size={620}
          open={archiveOpen}
          onClose={onCloseArchive}
          destroyOnHidden
        >
          <Typography.Paragraph type="secondary">
            归档只改变列表可见状态，报告和证据仍会保留。
          </Typography.Paragraph>
          {archivedReports.length === 0 ? (
            <div className="geo-inline-empty geo-inline-empty--large">
              <Typography.Text type="secondary">归档箱为空</Typography.Text>
            </div>
          ) : (
            <Table<GeoReportListItemView>
              rowKey="id"
              dataSource={archivedReports}
              pagination={archivePagination ? {
                current: archivePagination.current,
                pageSize: archivePagination.pageSize,
                total: archivePagination.total,
                onChange: onArchivePageChange,
              } : false}
              columns={[
                { title: '报告名称', dataIndex: 'name' },
                {
                  title: '归档时间',
                  dataIndex: 'archivedAt',
                  width: 170,
                  render: (value: string | undefined) => value ?? '—',
                },
                {
                  title: '操作',
                  key: 'action',
                  width: 170,
                  render: (_, report) => (
                    <Space size={0}>
                      <Button type="link" disabled={!onViewReport} onClick={() => onViewReport?.(report.id)}>
                        查看
                      </Button>
                      <Button
                        type="link"
                        icon={<RollbackOutlined />}
                        loading={restoringReportId === report.id}
                        disabled={!onRestoreReportIntent || Boolean(restoringReportId && restoringReportId !== report.id)}
                        onClick={() => onRestoreReportIntent?.(report.id)}
                      >
                        恢复
                      </Button>
                    </Space>
                  ),
                },
              ]}
            />
          )}
        </Drawer>
      </section>
    </GeoStateFrame>
  )
}

interface GeoReportTableProps {
  reports: readonly GeoReportListItemView[]
  platforms: readonly GeoPlatformView[]
  pagination?: { current: number; pageSize: number; total: number }
  exportingReportId?: string
  archivingReportId?: string
  onPageChange?: (page: number, pageSize: number) => void
  onViewReport?: (reportId: string) => void
  onExportReportIntent?: (reportId: string) => void
  onArchiveReportIntent?: (reportId: string) => void
}

function GeoReportTable({
  reports,
  platforms,
  pagination,
  exportingReportId,
  archivingReportId,
  onPageChange,
  onViewReport,
  onExportReportIntent,
  onArchiveReportIntent,
}: GeoReportTableProps) {
  return (
    <Table<GeoReportListItemView>
      rowKey="id"
      dataSource={reports}
      pagination={
        pagination
          ? {
              current: pagination.current,
              pageSize: pagination.pageSize,
              total: pagination.total,
              showSizeChanger: true,
              showTotal: (total) => `共 ${total} 份报告`,
              onChange: onPageChange,
            }
          : false
      }
      scroll={{ x: 980 }}
      columns={[
        { title: '报告名称', dataIndex: 'name', width: 220 },
        { title: '对象', dataIndex: 'objectName', width: 160 },
        { title: '类型', dataIndex: 'typeLabel', width: 120 },
        {
          title: '覆盖平台',
          dataIndex: 'platformCodes',
          width: 220,
          render: (codes: readonly string[]) =>
            codes.length === 0 ? '—' : (
              <Space size={[4, 4]} wrap>
                {platforms
                  .filter((platform) => codes.includes(platform.code))
                  .map((platform) => (
                    <GeoPlatformBadge
                      key={platform.code}
                      platform={platform}
                      compact
                    />
                  ))}
              </Space>
            ),
        },
        {
          title: '核心指标',
          dataIndex: 'primaryMetric',
          width: 110,
          render: (value: string | undefined) => value ?? '—',
        },
        {
          title: '生成时间',
          dataIndex: 'generatedAt',
          width: 170,
          render: (value: string | undefined) => value ?? '—',
        },
        {
          title: '状态',
          dataIndex: 'status',
          width: 110,
          render: (status: GeoReportStatus) => <GeoStatusTag status={status} />,
        },
        {
          title: '操作',
          key: 'actions',
          fixed: 'right',
          width: 235,
          render: (_, report) => (
            <Space size={2}>
              <Button
                type="link"
                size="small"
                disabled={!onViewReport}
                onClick={() => onViewReport?.(report.id)}
              >
                查看报告
              </Button>
              <Button
                type="link"
                size="small"
                icon={<DownloadOutlined />}
                loading={exportingReportId === report.id}
                disabled={
                  !onExportReportIntent ||
                  Boolean(exportingReportId && exportingReportId !== report.id)
                }
                onClick={() => onExportReportIntent?.(report.id)}
              >
                导出
              </Button>
              <Button
                type="link"
                size="small"
                loading={archivingReportId === report.id}
                disabled={!onArchiveReportIntent || Boolean(archivingReportId && archivingReportId !== report.id)}
                onClick={() => onArchiveReportIntent?.(report.id)}
              >
                归档
              </Button>
            </Space>
          ),
        },
      ]}
    />
  )
}

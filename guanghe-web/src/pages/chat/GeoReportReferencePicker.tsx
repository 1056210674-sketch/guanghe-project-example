import { Alert, Button, Empty, Modal, Select, Space, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import type { GeoReportSummary } from '../../api/geoApi'
import { useProjects } from '../../features/agent-platform/queries'
import { useGeoPlatforms, useGeoReports } from '../../features/geo/geoQueries'
import { GeoStatusTag } from '../../features/geo/components/GeoStatusTag'
import { formatGeoDateTime } from '../../features/geo/presentation'

interface GeoReportReferencePickerProps {
  lockedProjectId?: string
  selectedReportId?: string
  loading: boolean
  notice?: string
  onSelect: (reportId: string, projectId: string) => Promise<boolean>
  onClose: () => void
}

/** Data-owning dialog: no report request is made until an explicit project is selected. */
export function GeoReportReferencePicker({ lockedProjectId, selectedReportId, loading, notice, onSelect, onClose }: GeoReportReferencePickerProps) {
  const projects = useProjects()
  const platforms = useGeoPlatforms()
  const [chosenProjectId, setChosenProjectId] = useState<string>()
  const [page, setPage] = useState(1)
  const projectId = lockedProjectId ?? chosenProjectId
  const project = projects.data?.find((item) => item.id === projectId)
  const projectAvailable = project?.status === 'ACTIVE' && !projects.isError
  const reports = useGeoReports({ spaceId: projectId, archived: false, page, size: 10 }, Boolean(projectId && projectAvailable))

  return <Modal title="引用监测报告" open width={860} footer={<Button onClick={onClose}>取消</Button>} onCancel={onClose} destroyOnHidden>
    <div className="chat-report-picker">
      {lockedProjectId ? <Typography.Text>当前项目：{project?.name ?? (projects.isLoading ? '加载中…' : '项目不可用')}</Typography.Text> : (
        <Select aria-label="报告所属项目" placeholder="先选择报告所属项目" value={chosenProjectId}
          loading={projects.isLoading} disabled={loading || projects.isError} showSearch optionFilterProp="label"
          options={(projects.data ?? []).filter((item) => item.status === 'ACTIVE').map((item) => ({ value: item.id, label: item.name }))}
          onChange={(value) => { setChosenProjectId(value); setPage(1) }} />
      )}
      <Typography.Text type="secondary">引用到当前对话，可随时更换或移除。选择本身不会发送消息或启动监测。</Typography.Text>
      {projects.isError ? <Alert type="error" showIcon title="项目暂时无法加载" action={<Button onClick={() => void projects.refetch()}>重试</Button>} />
        : projectId && !projects.isLoading && !projectAvailable ? <Alert type="warning" showIcon title="这个项目已归档或不可访问，不能引用它的报告。" />
          : !projectId ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择一个项目，查看其历史监测报告" />
            : null}
      {notice ? <Alert type="warning" showIcon title={notice} /> : null}
      {projectAvailable ? reports.isError ? <Alert type="error" showIcon title="报告暂时无法加载，请重试" action={<Button onClick={() => void reports.refetch()}>重试</Button>} /> : (
        <Table<GeoReportSummary> rowKey="id" size="small" loading={reports.isFetching} dataSource={reports.data?.items ?? []} scroll={{ x: 660 }}
          locale={{ emptyText: '这个项目还没有可引用的监测报告' }}
          pagination={{ current: page, pageSize: 10, total: reports.data?.total ?? 0, showSizeChanger: false, hideOnSinglePage: true, disabled: loading, onChange: setPage }}
          columns={[
            { title: '监测专题', dataIndex: 'topicName', width: 210, render: (name: string | null | undefined) => <span className="chat-report-picker__name">{name?.trim() || '历史监测报告'}</span> },
            { title: '生成时间（北京时间）', dataIndex: 'generatedAt', width: 190, render: (value: string) => formatGeoDateTime(value) },
            { title: '平台', key: 'platforms', width: 140, render: (_, report) => report.platformCodes?.length ? <Space size={[0, 4]} wrap>{report.platformCodes.map((code) => <Tag key={code}>{platforms.data?.find((item) => item.code === code)?.name ?? code}</Tag>)}</Space> : '未记录' },
            { title: '状态', key: 'status', width: 100, render: (_, report) => report.archivedAt ? <Tag>已归档</Tag> : <GeoStatusTag status={report.status} /> },
            { title: '', key: 'select', width: 85, render: (_, report) => <Button size="small" type={selectedReportId === report.id ? 'default' : 'primary'}
              disabled={loading || reports.isFetching || Boolean(report.archivedAt) || !['SUCCEEDED', 'PARTIAL'].includes(report.status)}
              onClick={() => { if (projectId) void onSelect(report.id, projectId) }}>{selectedReportId === report.id ? '已引用' : '引用'}</Button> },
          ]} />
      ) : null}
    </div>
  </Modal>
}

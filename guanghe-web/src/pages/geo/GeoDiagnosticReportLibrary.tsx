import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Empty, Select, Table, Typography } from 'antd'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { listGeoDiagnosticReports } from '../../api/geoDiagnosticApi'
import { captureAuthSession } from '../../auth/tokenStorage'
import { useProjects } from '../../features/agent-platform/queries'
import { diagnosticKeys } from '../../features/geo-diagnostics/queries'
import { diagnosticPlatformName } from '../../features/geo-diagnostics/reportPresentation'
import { appLinks } from '../../routes/paths'
import { useAuthStore } from '../../stores/authStore'
import { diagnosticReportError } from './useGeoDiagnosticReport'
import '../../styles/geo-diagnostics.css'

export function GeoDiagnosticReportLibrary() {
  const owner = useAuthStore((state) => state.user?.id)
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const projects = useProjects()
  const projectId = params.get('projectId') ?? undefined
  const project = projects.isError ? undefined : projects.data?.find((row) => row.id === projectId)
  const [selection, setSelection] = useState<{ scope: string; page: number }>()
  const scope = `${owner}:${projectId}`
  const page = selection?.scope === scope ? selection.page : 1
  const query = useQuery({ queryKey: diagnosticKeys.projectList(owner, projectId, page), queryFn: async () => {
    const result = await listGeoDiagnosticReports(projectId!, page, captureAuthSession())
    if (result.items.some((row) => row.projectId !== projectId || !row.delivery)) throw new Error('DIAGNOSTIC_LIST_SCOPE_MISMATCH')
    return result
  }, enabled: Boolean(owner && project), retry: false, refetchInterval: (state) => !state.state.error && state.state.data?.items.some((row) => row.delivery && !['READY', 'PDF_FAILED', 'ANALYSIS_FAILED'].includes(row.delivery.status)) ? 5000 : false, refetchIntervalInBackground: false })
  const data = !query.isError && project ? query.data : undefined
  return <section className="geo-diagnostic-library" aria-label="诊断报告列表"><Typography.Title level={2}>品牌诊断报告</Typography.Title><Typography.Paragraph type="secondary">从原聊天六张卡片提交的后台诊断，保留自然可见度、品牌口碑、同类品牌及公开资料的完整报告。请选择客户项目，不会混列其他客户资料。</Typography.Paragraph>
    <div className="geo-diagnostic-library__toolbar"><Select aria-label="诊断报告客户项目" placeholder="请选择客户项目" loading={projects.isLoading} value={project?.id} options={(projects.data ?? []).map((row) => ({ value: row.id, label: row.name }))} onChange={(id: string) => { const next = new URLSearchParams(params); next.set('projectId', id); setParams(next, { replace: true }) }} /><Button disabled={!project} onClick={() => void query.refetch()}>刷新诊断报告</Button></div>
    {projects.isError ? <Alert type="error" title="客户项目暂时无法加载。" action={<Button onClick={() => void projects.refetch()}>重试读取项目</Button>} /> : projectId && !projects.isLoading && !project ? <Alert type="warning" title="这个项目不存在或当前账号无权查看。" /> : !project ? <Empty description="先选择客户项目，查看其诊断报告。" /> : query.isError ? <Alert type="error" title={diagnosticReportError(query.error)} /> : <Table rowKey="id" loading={query.isLoading} dataSource={data?.items ?? []} scroll={{ x: 780 }} pagination={{ current: page, pageSize: 20, total: data?.total ?? 0, showSizeChanger: false, onChange: (next) => setSelection({ scope, page: next }) }} columns={[
      { title: '品牌', render: (_, row) => row.profile.brandName }, { title: '目标地域', render: (_, row) => row.profile.region || '全国' },
      { title: '平台', render: (_, row) => row.platformCodes.map(diagnosticPlatformName).join(' / ') },
      { title: '状态', render: (_, row) => row.delivery?.hasNoData ? '监测未获取有效样本' : row.delivery?.status === 'READY' ? '报告已交付' : row.delivery?.canRetry ? '报告待恢复' : '后台处理中' },
      { title: '操作', render: (_, row) => <Button type="link" onClick={() => navigate(appLinks.geoDiagnosticReport(row.id))}>查看详细报告</Button> },
    ]} />}
  </section>
}

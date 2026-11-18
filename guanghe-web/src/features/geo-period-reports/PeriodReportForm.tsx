import { Alert, Button, Empty, Form, Input, Modal, Select, Typography } from 'antd'
import { useState } from 'react'
import type { Project } from '../../api/agentPlatformApi'
import type { GeoPeriodReportSummary } from '../../api/geoPeriodReportApi'
import { periodDateError, previousCompleteWeek, shanghaiToday } from './dateRange'

/** Local editor values, mapped to the generated API request by the data-owning page hook. */
export interface PeriodReportFormValues {
  projectId: string
  startDate: string
  endDate: string
  title: string
  focus: string
}

interface PeriodReportFormProps {
  projects: readonly Project[]
  defaultProjectId?: string
  projectsLoading: boolean
  projectsError?: string
  submitting: boolean
  error?: string
  onRetryProjects: () => void
  onGenerate: (values: PeriodReportFormValues) => void
  onClose: () => void
  history: readonly GeoPeriodReportSummary[]
  historyLoading: boolean
  historyError?: string
  onProjectChange: (projectId: string) => void
  onOpenSaved: (id: string) => void
  onRetryHistory: () => void
}

export function PeriodReportForm({ projects, defaultProjectId, projectsLoading, projectsError, submitting, error,
  onRetryProjects, onGenerate, onClose, history, historyLoading, historyError, onProjectChange, onOpenSaved, onRetryHistory }: PeriodReportFormProps) {
  const [values, setValues] = useState<PeriodReportFormValues>(() => ({ projectId: defaultProjectId ?? '', ...previousCompleteWeek(), title: '', focus: '' }))
  const options = projects.filter((project) => project.status === 'ACTIVE').map((project) => ({ value: project.id, label: project.name }))
  const selectedProject = options.find((project) => project.value === values.projectId)
  const dateError = periodDateError(values.startDate, values.endDate)
  const canGenerate = Boolean(selectedProject && !dateError && !projectsLoading && !projectsError && !submitting)
  const update = (patch: Partial<PeriodReportFormValues>) => setValues((current) => ({ ...current, ...patch }))

  return <Modal open title="导出周报 / 阶段报告" width={620} onCancel={onClose} closable={!submitting} maskClosable={!submitting}
    footer={<><Button onClick={onClose} disabled={submitting}>取消</Button><Button type="primary" loading={submitting} disabled={!canGenerate}
      onClick={() => { if (canGenerate) onGenerate(values) }}>生成报告</Button></>}>
    <div className="geo-period-report-form">
      <Typography.Paragraph type="secondary">汇总所选客户项目已有的监测、内容和投稿成果，不会启动新监测或投稿。默认选择上一个完整自然周。</Typography.Paragraph>
      {projectsError ? <Alert type="error" showIcon title={projectsError} action={<Button onClick={onRetryProjects}>重新加载项目</Button>} /> : null}
      {!projectsLoading && !projectsError && options.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有可用的客户项目。请先创建项目，再生成项目报告。" /> : null}
      <Form layout="vertical" component="div">
        <Form.Item label="客户项目" required>
          <Select aria-label="报告客户项目" placeholder="选择客户项目" value={selectedProject?.value} showSearch optionFilterProp="label"
            loading={projectsLoading} disabled={submitting || projectsLoading || Boolean(projectsError)} options={options} onChange={(projectId: string) => { update({ projectId }); onProjectChange(projectId) }} />
        </Form.Item>
        <div className="geo-period-report-form__dates">
          <Form.Item label="开始日期（北京时间）" required><Input aria-label="报告开始日期" type="date" value={values.startDate} max={shanghaiToday()} disabled={submitting}
            onChange={(event) => update({ startDate: event.target.value })} /></Form.Item>
          <Form.Item label="结束日期（北京时间）" required><Input aria-label="报告结束日期" type="date" value={values.endDate} max={shanghaiToday()} disabled={submitting}
            onChange={(event) => update({ endDate: event.target.value })} /></Form.Item>
        </div>
        {dateError ? <Typography.Paragraph type="danger" role="alert">{dateError}</Typography.Paragraph> : null}
        <Form.Item label="报告标题（可选）"><Input aria-label="客户报告标题" value={values.title} maxLength={100} placeholder="例如：小行星咖啡店 · GEO 阶段成果报告" disabled={submitting}
          onChange={(event) => update({ title: event.target.value })} /></Form.Item>
        <Form.Item label="补充重点（可选）"><Input.TextArea aria-label="报告补充重点" value={values.focus} maxLength={500} showCount autoSize={{ minRows: 3, maxRows: 5 }}
          placeholder="例如：重点说明广州地区的品牌提及、已发布文章及回链。" disabled={submitting} onChange={(event) => update({ focus: event.target.value })} /></Form.Item>
      </Form>
      {error ? <Alert type="error" showIcon title={error} description="表单内容已保留，可检查后重新生成。" /> : null}
      {submitting ? <Typography.Paragraph role="status">正在整理所选周期的已有成果，完成后将在右侧打开报告。</Typography.Paragraph> : null}
      {selectedProject ? <section className="geo-period-report-form__history" aria-label="最近已保存的周期报告"><Typography.Text strong>最近已保存的报告</Typography.Text>
        {historyError ? <Alert type="error" title={historyError} action={<Button onClick={onRetryHistory}>重试</Button>} />
          : historyLoading ? <Typography.Paragraph role="status">正在读取报告列表…</Typography.Paragraph>
            : history.length ? <ul>{history.map((item) => <li key={item.id}><Button type="link" disabled={submitting} onClick={() => onOpenSaved(item.id)}>{item.title}</Button>
              <Typography.Text type="secondary">{item.startDate} — {item.endDate} · {item.status === 'READY' ? '已生成' : item.status === 'SUMMARY_UNAVAILABLE' ? '总结生成失败' : '生成中'}</Typography.Text></li>)}</ul>
              : <Typography.Paragraph type="secondary">这个项目还没有保存的周期报告。</Typography.Paragraph>}
      </section> : null}
    </div>
  </Modal>
}

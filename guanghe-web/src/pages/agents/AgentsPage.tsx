import { Alert, Modal } from 'antd'
import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import type { AgentInstallation } from '../../api/agentPlatformApi'
import { AgentLibraryWorkspace } from '../../features/agent-platform/AgentLibraryWorkspace'
import { useAgentActions, useAgentCatalog, useAgentInstallations } from '../../features/agent-platform/queries'
import { UserShell } from '../../layouts/UserShell'
import { appLinks } from '../../routes/paths'

export function AgentsPage() {
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const catalog = useAgentCatalog()
  const installations = useAgentInstallations()
  const actions = useAgentActions()
  const [actionError, setActionError] = useState<string>()
  const [notice, setNotice] = useState<string>()
  const [removing, setRemoving] = useState<AgentInstallation>()
  const lock = useRef(false)
  const mounted = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const busy = actions.add.isPending || actions.update.isPending || actions.remove.isPending
  const selectTab = (tab: string, replace = false) => setParams((current) => { const next = new URLSearchParams(current); next.set('tab', tab); return next }, { replace })
  const reload = () => { setActionError(undefined); void Promise.all([catalog.refetch(), installations.refetch()]) }
  const execute = async (action: () => Promise<unknown>, message: string, added = false) => {
    if (lock.current) return
    lock.current = true
    const session = captureAuthSession()
    setActionError(undefined)
    setNotice(undefined)
    try {
      await action()
      if (!mounted.current || !isCurrentAuthSession(session)) return
      setNotice(message)
      setRemoving(undefined)
      if (added) selectTab('installed', true)
    } catch {
      if (mounted.current && isCurrentAuthSession(session)) {
        setActionError('操作结果未确认，请重新加载当前状态后重试。')
        void Promise.all([catalog.refetch(), installations.refetch()])
      }
    } finally { lock.current = false }
  }
  return <UserShell>
    <AgentLibraryWorkspace agents={catalog.data ?? []} installations={installations.data ?? []} loading={catalog.isLoading || installations.isLoading} error={actionError ?? (catalog.isError || installations.isError ? '智能体数据加载失败，请重试。' : undefined)} notice={notice} busy={busy} activeTab={params.get('tab') === 'installed' ? 'installed' : 'catalog'} onTabChange={(tab) => selectTab(tab)} onRetry={reload}
      onAdd={(agentKey) => void execute(() => actions.add.mutateAsync({ agentKey }), '已添加，可开始使用。', true)}
      onUse={(id) => navigate(appLinks.newAgentTask(id, params.get('project') ?? undefined))}
      onToggle={(item) => void execute(() => actions.update.mutateAsync({ id: item.id, request: { version: item.version, enabled: !item.enabled } }), item.enabled ? '已停用，历史任务与成果仍可查看。' : '已启用。')}
      onRemove={setRemoving} />
    <Modal title="移除智能体" open={Boolean(removing)} okText="移除" cancelText="取消" okButtonProps={{ danger: true }} confirmLoading={actions.remove.isPending} onCancel={() => { if (!busy) setRemoving(undefined) }} onOk={() => { if (removing) void execute(() => actions.remove.mutateAsync(removing.id), '已移除智能体，项目资料、历史会话与成果均已保留。') }}>
      移除“{removing?.name}”后，将不能用它创建新任务。项目资料、历史会话和已有成果不会被删除。
      {actionError ? <Alert type="error" showIcon title={actionError} /> : null}
    </Modal>
  </UserShell>
}

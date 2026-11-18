import { useQuery } from '@tanstack/react-query'
import { Card, Tag, Typography } from 'antd'
import type { ReactNode } from 'react'
import { getProjectGeoTarget, type Project } from '../../api/agentPlatformApi'
import { useAuthStore } from '../../stores/authStore'
import { ProjectGeoTargetCard } from './ProjectGeoTargetCard'

export function ProjectBrandProfileCard({ project, completedMemoryFields, children }: {
  project: Project
  completedMemoryFields?: number
  children: ReactNode
}) {
  const owner = useAuthStore((state) => state.user?.id)
  // Shares the existing identity cache with its editor; no second identity store.
  const identity = useQuery({ queryKey: ['project-geo-target', owner ?? null, project.id], queryFn: () => getProjectGeoTarget(project.id), enabled: Boolean(owner), retry: false })
  const completed = identity.isSuccess && completedMemoryFields !== undefined
    ? completedMemoryFields + (identity.data.configured && identity.data.name?.trim() ? 1 : 0) : undefined
  return <Card className="knowledge-card knowledge-brand-profile" title="品牌档案"
    extra={<Tag color={completed === 4 ? 'success' : 'default'}>{completed === undefined ? '基本资料读取中或暂不可用' : `基本资料 ${completed} / 4`}</Tag>}>
    <Typography.Paragraph type="secondary">不必一次填完。可以在项目聊天中逐步补充，也可以在这里修改。基本资料用于明确优化方向；补齐资料不会自动启动监测或投稿。</Typography.Paragraph>
    <ProjectGeoTargetCard project={project} embedded />
    {children}
  </Card>
}

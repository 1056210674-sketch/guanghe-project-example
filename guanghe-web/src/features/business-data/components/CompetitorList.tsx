import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import { Button, Card, Input, Select, Space, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import { EmptyState, ErrorState, PageLoading } from '../../../components/GlobalState'
import type {
  BusinessCollectionProps,
  BusinessSpaceOption,
  CompetitorListItemView,
  CompetitorType,
} from '../types'
import { DeleteConfirmation } from './DeleteConfirmation'

export interface CompetitorListProps
  extends BusinessCollectionProps<CompetitorListItemView> {
  businessSpaceOptions: BusinessSpaceOption[]
  businessSpaceId?: string
  searchValue?: string
  onBusinessSpaceChange: (id: string | undefined) => void
  onSearchChange?: (value: string) => void
  onOpenGeoWorkbench?: () => void
}

function competitorTypeLabel(competitorType: CompetitorType) {
  switch (competitorType) {
    case 'DIRECT':
      return '直接竞品'
    case 'INDIRECT':
      return '间接竞品'
    case 'SUBSTITUTE':
      return '替代品'
  }
}

export function CompetitorList({
  data,
  loading,
  error,
  pagination,
  permissions,
  businessSpaceOptions,
  businessSpaceId,
  searchValue,
  onBusinessSpaceChange,
  onSearchChange,
  onOpenGeoWorkbench,
  onCreate,
  onEdit,
  onDelete,
  onPageChange,
  onRetry,
  deleteError,
}: CompetitorListProps) {
  const [pendingDelete, setPendingDelete] =
    useState<CompetitorListItemView | null>(null)
  const [deleteConfirming, setDeleteConfirming] = useState(false)

  if (loading) {
    return (
      <section className="business-collection-state" aria-label="竞品加载状态">
        <PageLoading label="正在加载竞品…" />
      </section>
    )
  }

  if (error) {
    return (
      <section className="business-collection-state" aria-label="竞品错误状态">
        <ErrorState
          message={error.message}
          traceId={error.traceId}
          onRetry={onRetry}
        />
      </section>
    )
  }

  const confirmDelete = async () => {
    if (!pendingDelete) {
      return
    }

    setDeleteConfirming(true)
    try {
      await onDelete(pendingDelete.id)
      setPendingDelete(null)
    } catch {
      // The page container owns the mutation error exposed through deleteError.
    } finally {
      setDeleteConfirming(false)
    }
  }

  return (
    <section className="business-collection" aria-labelledby="competitor-list-title">
      <header className="business-page-heading">
        <div>
          <Typography.Title id="competitor-list-title" level={1}>
            竞品
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            这里维护正式竞品资料；前往 GEO 竞品工作台，可从监测回答发现候选、核对可见度并确认纳入。
          </Typography.Paragraph>
        </div>
        <Space wrap>
          {onOpenGeoWorkbench ? <Button onClick={onOpenGeoWorkbench}>GEO 竞品工作台</Button> : null}
          {permissions.canCreate ? (
          <Button type="primary" icon={<PlusOutlined />} onClick={onCreate}>
            新建竞品
          </Button>
          ) : null}
        </Space>
      </header>

      <Card className="business-list-card">
        <div className="business-filter-bar">
          <Select
            allowClear
            className="business-filter-bar__select"
            aria-label="按经营空间筛选竞品"
            placeholder="请选择经营空间"
            value={businessSpaceId}
            options={businessSpaceOptions.map((option) => ({
              label: option.name,
              value: option.id,
            }))}
            onChange={onBusinessSpaceChange}
          />
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder="搜索竞品名称或别名"
            value={searchValue}
            disabled={!onSearchChange}
            onChange={(event) => onSearchChange?.(event.target.value)}
          />
        </div>

        {!businessSpaceId ? (
          <EmptyState
            title={
              businessSpaceOptions.length === 0
                ? '还没有经营空间'
                : '请先选择经营空间'
            }
            description={
              businessSpaceOptions.length === 0
                ? '新建经营空间后，才能维护归属该空间的竞品。'
                : '竞品按经营空间独立维护，请先选择本次要查看的经营空间。'
            }
          />
        ) : data.length === 0 ? (
          <EmptyState
            title="还没有竞品"
            description="新建竞品后，可维护用于监测识别的名称与别名。"
            action={
              permissions.canCreate ? (
                <Button type="primary" icon={<PlusOutlined />} onClick={onCreate}>
                  新建竞品
                </Button>
              ) : undefined
            }
          />
        ) : (
          <Table<CompetitorListItemView>
            rowKey="id"
            dataSource={data}
            scroll={{ x: 1320 }}
            pagination={{
              current: pagination.current,
              pageSize: pagination.pageSize,
              total: pagination.total,
              showSizeChanger: true,
              showTotal: (total) => `共 ${total} 条`,
            }}
            onChange={(nextPagination) =>
              onPageChange(
                nextPagination.current ?? 1,
                nextPagination.pageSize ?? pagination.pageSize,
              )
            }
            columns={[
              {
                title: '竞品名称',
                dataIndex: 'name',
                width: 220,
                render: (name: string) => <Typography.Text strong>{name}</Typography.Text>,
              },
              {
                title: '所属经营空间',
                dataIndex: 'businessSpaceName',
                width: 200,
              },
              {
                title: '竞品别名',
                dataIndex: 'aliases',
                width: 260,
                render: (aliases: string[] | undefined, record) =>
                  aliases && aliases.length > 0 ? (
                    <Space size={[4, 4]} wrap>
                      {aliases.map((alias) => (
                        <Tag key={alias}>{alias}</Tag>
                      ))}
                    </Space>
                  ) : record.aliasCount !== undefined ? (
                    <Typography.Text type="secondary">
                      {record.aliasCount > 0 ? `${record.aliasCount} 个别名` : '—'}
                    </Typography.Text>
                  ) : (
                    <Typography.Text type="secondary">—</Typography.Text>
                  ),
              },
              {
                title: '竞品关系',
                dataIndex: 'competitorType',
                width: 140,
                render: (competitorType: CompetitorType) => (
                  <Tag color="red">{competitorTypeLabel(competitorType)}</Tag>
                ),
              },
              {
                title: '说明',
                dataIndex: 'description',
                width: 240,
                render: (description: string | undefined) =>
                  description || '—',
              },
              {
                title: '状态',
                dataIndex: 'status',
                width: 120,
                render: (status: string | undefined) => status || '—',
              },
              {
                title: '最近更新',
                dataIndex: 'updatedAt',
                width: 180,
                render: (updatedAt: string | undefined) => updatedAt || '—',
              },
              {
                title: '操作',
                key: 'actions',
                fixed: 'right',
                width: 154,
                render: (_, record) => (
                  <Space size={4}>
                    {permissions.canEdit ? (
                      <Button
                        type="link"
                        size="small"
                        icon={<EditOutlined />}
                        onClick={() => onEdit(record.id)}
                      >
                        编辑
                      </Button>
                    ) : null}
                    {permissions.canDelete ? (
                      <Button
                        type="link"
                        size="small"
                        danger
                        icon={<DeleteOutlined />}
                        onClick={() => setPendingDelete(record)}
                      >
                        删除
                      </Button>
                    ) : null}
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Card>

      <DeleteConfirmation
        open={pendingDelete !== null}
        entityLabel="竞品"
        entityName={pendingDelete?.name}
        impact="删除后，该竞品的名称、别名与经营空间关系将无法恢复。"
        confirming={deleteConfirming}
        error={deleteError}
        onCancel={() => setPendingDelete(null)}
        onConfirm={confirmDelete}
      />
    </section>
  )
}

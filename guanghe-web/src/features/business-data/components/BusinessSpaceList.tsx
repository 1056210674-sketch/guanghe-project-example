import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import { Button, Card, Input, Progress, Space, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import { EmptyState, ErrorState, PageLoading } from '../../../components/GlobalState'
import type {
  BusinessCollectionProps,
  BusinessSpaceListItemView,
  BusinessSpaceSummaryView,
} from '../types'
import { DeleteConfirmation } from './DeleteConfirmation'

export interface BusinessSpaceListProps
  extends BusinessCollectionProps<BusinessSpaceListItemView> {
  summary?: BusinessSpaceSummaryView
  searchValue?: string
  onSearchChange?: (value: string) => void
}

function displayMetric(value: number | undefined) {
  return value === undefined ? '—' : value.toLocaleString('zh-CN')
}

function displayPercentage(value: number | undefined) {
  return value === undefined ? '—' : `${value.toLocaleString('zh-CN')}%`
}

export function BusinessSpaceList({
  data,
  loading,
  error,
  pagination,
  permissions,
  summary,
  searchValue,
  onSearchChange,
  onCreate,
  onEdit,
  onDelete,
  onPageChange,
  onRetry,
  deleteError,
}: BusinessSpaceListProps) {
  const [pendingDelete, setPendingDelete] =
    useState<BusinessSpaceListItemView | null>(null)
  const [deleteConfirming, setDeleteConfirming] = useState(false)

  if (loading) {
    return (
      <section className="business-collection-state" aria-label="经营空间加载状态">
        <PageLoading label="正在加载经营空间…" />
      </section>
    )
  }

  if (error) {
    return (
      <section className="business-collection-state" aria-label="经营空间错误状态">
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
    <section className="business-collection" aria-labelledby="business-space-title">
      <header className="business-page-heading">
        <div>
          <Typography.Title id="business-space-title" level={1}>
            经营空间
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            统一维护品牌经营背景，为商品创作与GEO监测提供准确上下文。
          </Typography.Paragraph>
        </div>
        {permissions.canCreate ? (
          <Button type="primary" icon={<PlusOutlined />} onClick={onCreate}>
            新建经营空间
          </Button>
        ) : null}
      </header>

      <Card className="business-summary-card">
        <div className="business-summary-grid">
          <div>
            <Typography.Text type="secondary">经营空间</Typography.Text>
            <Typography.Title level={3}>
              {displayMetric(summary?.totalSpaces)}
            </Typography.Title>
          </div>
          <div>
            <Typography.Text type="secondary">平均完整度</Typography.Text>
            <Typography.Title level={3}>
              {displayPercentage(summary?.averageCompleteness)}
            </Typography.Title>
          </div>
          <div>
            <Typography.Text type="secondary">关联商品</Typography.Text>
            <Typography.Title level={3}>
              {displayMetric(summary?.linkedProducts)}
            </Typography.Title>
          </div>
          <div>
            <Typography.Text type="secondary">待完善</Typography.Text>
            <Typography.Title level={3}>
              {displayMetric(summary?.incompleteSpaces)}
            </Typography.Title>
          </div>
        </div>
      </Card>

      <Card className="business-list-card">
        <div className="business-filter-bar">
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder="搜索经营空间或品牌名称"
            value={searchValue}
            disabled={!onSearchChange}
            onChange={(event) => onSearchChange?.(event.target.value)}
          />
        </div>

        {data.length === 0 ? (
          <EmptyState
            title="还没有经营空间"
            description="新建经营空间后，可统一维护品牌经营背景。"
            action={
              permissions.canCreate ? (
                <Button type="primary" icon={<PlusOutlined />} onClick={onCreate}>
                  新建经营空间
                </Button>
              ) : undefined
            }
          />
        ) : (
          <Table<BusinessSpaceListItemView>
            rowKey="id"
            dataSource={data}
            scroll={{ x: 920 }}
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
                title: '经营空间名称',
                dataIndex: 'name',
                width: 220,
                render: (name: string) => <Typography.Text strong>{name}</Typography.Text>,
              },
              {
                title: '品牌名称',
                dataIndex: 'brandName',
                width: 180,
              },
              {
                title: '品牌别名',
                dataIndex: 'brandAliases',
                width: 240,
                render: (aliases: string[] | undefined) =>
                  aliases && aliases.length > 0 ? (
                    <Space size={[4, 4]} wrap>
                      {aliases.map((alias) => (
                        <Tag key={alias}>{alias}</Tag>
                      ))}
                    </Space>
                  ) : (
                    <Typography.Text type="secondary">—</Typography.Text>
                  ),
              },
              {
                title: '资料完整度',
                dataIndex: 'completeness',
                width: 160,
                render: (value: number | undefined) =>
                  value === undefined ? (
                    <Typography.Text type="secondary">—</Typography.Text>
                  ) : (
                    <Progress percent={value} size="small" />
                  ),
              },
              {
                title: '关联商品',
                dataIndex: 'linkedProductCount',
                width: 100,
                render: (value: number | undefined) => displayMetric(value),
              },
              {
                title: '最近更新',
                dataIndex: 'updatedAt',
                width: 170,
                render: (value: string | undefined) => value || '—',
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
        entityLabel="经营空间"
        entityName={pendingDelete?.name}
        impact="删除后，该经营空间维护的品牌背景将无法恢复，相关引用需由服务端校验后处理。"
        confirming={deleteConfirming}
        error={deleteError}
        onCancel={() => setPendingDelete(null)}
        onConfirm={confirmDelete}
      />
    </section>
  )
}

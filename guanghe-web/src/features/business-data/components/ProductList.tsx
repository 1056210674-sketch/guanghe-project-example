import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PictureOutlined,
  PlusOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import { Button, Card, Input, Select, Space, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import { EmptyState, ErrorState, PageLoading } from '../../../components/GlobalState'
import type {
  BusinessCollectionProps,
  BusinessSpaceOption,
  ProductListItemView,
} from '../types'
import { DeleteConfirmation } from './DeleteConfirmation'

export interface ProductListProps extends BusinessCollectionProps<ProductListItemView> {
  businessSpaceOptions: BusinessSpaceOption[]
  businessSpaceId?: string
  searchValue?: string
  onBusinessSpaceChange: (id: string | undefined) => void
  onSearchChange?: (value: string) => void
  onView: (id: string) => void
}

function ProductThumbnail({ url, name }: { url?: string; name: string }) {
  const [failed, setFailed] = useState(false)

  return (
    <div className="product-thumbnail">
      {url && !failed ? (
        <img
          src={url}
          alt={`${name}主图`}
          width={56}
          height={56}
          loading="lazy"
          onError={() => setFailed(true)}
        />
      ) : (
        <span className="product-thumbnail__placeholder" aria-label="暂无商品图片">
          <PictureOutlined aria-hidden="true" />
        </span>
      )}
    </div>
  )
}
export function ProductList({
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
  onView,
  onCreate,
  onEdit,
  onDelete,
  onPageChange,
  onRetry,
  deleteError,
}: ProductListProps) {
  const [pendingDelete, setPendingDelete] = useState<ProductListItemView | null>(null)
  const [deleteConfirming, setDeleteConfirming] = useState(false)

  if (loading) {
    return (
      <section className="business-collection-state" aria-label="商品加载状态">
        <PageLoading label="正在加载商品…" />
      </section>
    )
  }

  if (error) {
    return (
      <section className="business-collection-state" aria-label="商品错误状态">
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
    <section className="business-collection" aria-labelledby="product-list-title">
      <header className="business-page-heading">
        <div>
          <Typography.Title id="product-list-title" level={1}>
            商品
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            按经营空间维护商品资料，商品素材统一从资产中心引用。
          </Typography.Paragraph>
        </div>
        {permissions.canCreate ? (
          <Button type="primary" icon={<PlusOutlined />} onClick={onCreate}>
            新建商品
          </Button>
        ) : null}
      </header>

      <Card className="business-list-card">
        <div className="business-filter-bar">
          <Select
            allowClear
            className="business-filter-bar__select"
            aria-label="按经营空间筛选商品"
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
            placeholder="搜索商品名称或别名"
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
                ? '新建经营空间后，才能维护归属该空间的商品。'
                : '商品按经营空间独立维护，请先选择本次要查看的经营空间。'
            }
          />
        ) : data.length === 0 ? (
          <EmptyState
            title="还没有商品"
            description="新建商品后，可按经营空间统一维护商品资料。"
            action={
              permissions.canCreate ? (
                <Button type="primary" icon={<PlusOutlined />} onClick={onCreate}>
                  新建商品
                </Button>
              ) : undefined
            }
          />
        ) : (
          <Table<ProductListItemView>
            rowKey="id"
            dataSource={data}
            scroll={{ x: 1040 }}
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
                title: '商品',
                key: 'product',
                width: 270,
                render: (_, record) => (
                  <div className="product-cell">
                    <ProductThumbnail
                      key={record.mainAssetUrl || 'empty'}
                      url={record.mainAssetUrl}
                      name={record.name}
                    />
                    <div>
                      <Typography.Text strong>{record.name}</Typography.Text>
                      <Typography.Text type="secondary">
                        {record.modelOrSpecification || '暂无型号或规格'}
                      </Typography.Text>
                    </div>
                  </div>
                ),
              },
              {
                title: '经营空间',
                dataIndex: 'businessSpaceName',
                width: 180,
              },
              {
                title: '商品别名',
                dataIndex: 'aliases',
                width: 240,
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
                title: '品类',
                dataIndex: 'category',
                width: 140,
                render: (value: string | undefined) => value || '—',
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
                width: 220,
                render: (_, record) => (
                  <Space size={2}>
                    <Button
                      type="link"
                      size="small"
                      icon={<EyeOutlined />}
                      onClick={() => onView(record.id)}
                    >
                      详情
                    </Button>
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
        entityLabel="商品"
        entityName={pendingDelete?.name}
        impact="删除后，该商品的基础资料与别名将无法恢复，提交前请确认无需继续使用。"
        confirming={deleteConfirming}
        error={deleteError}
        onCancel={() => setPendingDelete(null)}
        onConfirm={confirmDelete}
      />
    </section>
  )
}

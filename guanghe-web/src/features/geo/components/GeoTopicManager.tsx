import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReadOutlined,
  SearchOutlined,
  TeamOutlined,
} from '@ant-design/icons'
import { Button, Card, Input, Select, Space, Table, Tag, Typography } from 'antd'
import type {
  GeoAsyncViewProps,
  GeoSelectOption,
  GeoTopicPaginationView,
  GeoTopicView,
} from '../types'
import { GeoStateFrame } from './GeoStateFrame'

export interface GeoTopicManagerProps extends GeoAsyncViewProps {
  spaces: readonly GeoSelectOption[]
  products: readonly GeoSelectOption[]
  selectedSpaceId?: string
  selectedProductId?: string
  keyword?: string
  topics: readonly GeoTopicView[]
  selectedTopic?: GeoTopicView
  pagination: GeoTopicPaginationView
  onSpaceChange?: (spaceId: string | undefined) => void
  onProductChange?: (productId: string | undefined) => void
  onKeywordChange?: (keyword: string) => void
  onCreateTopic?: () => void
  onSelectTopic?: (topicId: string) => void
  onEditTopicIntent?: (topicId: string) => void
  onOpenQuestionLibrary?: (topicId: string) => void
  onOpenCompetitorSet?: (topicId: string) => void
  onDeleteTopicIntent?: (topicId: string) => void
  onPageChange?: (page: number, pageSize: number) => void
}

export function GeoTopicManager({
  loading,
  error,
  onRetry,
  spaces,
  products,
  selectedSpaceId,
  selectedProductId,
  keyword,
  topics,
  selectedTopic,
  pagination,
  onSpaceChange,
  onProductChange,
  onKeywordChange,
  onCreateTopic,
  onSelectTopic,
  onEditTopicIntent,
  onOpenQuestionLibrary,
  onOpenCompetitorSet,
  onDeleteTopicIntent,
  onPageChange,
}: GeoTopicManagerProps) {
  return (
    <GeoStateFrame
      label="GEO 专题"
      loading={loading}
      error={error}
      onRetry={onRetry}
      empty={spaces.length === 0}
      emptyTitle="还没有经营空间"
      emptyDescription="请先完成经营空间资料，再创建 GEO 监测专题。"
    >
      <section className="geo-topics" aria-labelledby="geo-topics-title">
        <header className="geo-page-heading">
          <div>
            <Typography.Title id="geo-topics-title" level={1}>
              监测对象与专题
            </Typography.Title>
            <Typography.Paragraph type="secondary">
              专题基础资料、关键词、问题和竞品集合都会按专题独立保存。
            </Typography.Paragraph>
          </div>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            disabled={!onCreateTopic}
            onClick={onCreateTopic}
          >
            新建专题
          </Button>
        </header>

        <Card className="geo-panel-card" title="筛选专题">
          <div className="geo-foundation-filters">
            <Select
              allowClear
              placeholder="全部经营空间"
              value={selectedSpaceId}
              options={spaces.map((space) => ({ value: space.id, label: space.label }))}
              onChange={onSpaceChange}
            />
            <Select
              allowClear
              placeholder={selectedSpaceId ? '全部商品' : '请先选择经营空间'}
              value={selectedProductId}
              options={products.map((product) => ({ value: product.id, label: product.label }))}
              disabled={!selectedSpaceId}
              onChange={onProductChange}
            />
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder="搜索专题名称或关键词"
              value={keyword}
              maxLength={200}
              onChange={(event) => onKeywordChange?.(event.target.value)}
            />
          </div>
        </Card>

        <div className="geo-topics__live-layout">
          <Card
            title={`专题管理（${pagination.total}）`}
            className="geo-panel-card geo-table-card"
          >
            {topics.length === 0 ? (
              <div className="geo-inline-empty">
                <Typography.Text type="secondary">
                  当前筛选条件下没有监测专题
                </Typography.Text>
              </div>
            ) : (
              <Table<GeoTopicView>
                rowKey="id"
                dataSource={topics}
                scroll={{ x: 880 }}
                rowClassName={(record) =>
                  record.id === selectedTopic?.id ? 'geo-table-row--selected' : ''
                }
                onRow={(record) => ({ onClick: () => onSelectTopic?.(record.id) })}
                pagination={{
                  current: pagination.current,
                  pageSize: pagination.pageSize,
                  total: pagination.total,
                  showSizeChanger: true,
                  showTotal: (total) => `共 ${total} 个专题`,
                  onChange: onPageChange,
                }}
                columns={[
                  { title: '专题名称', dataIndex: 'name', width: 190 },
                  {
                    title: '经营空间',
                    dataIndex: 'spaceName',
                    width: 160,
                    render: (value: string | undefined) => value ?? '—',
                  },
                  {
                    title: '商品',
                    dataIndex: 'productName',
                    width: 160,
                    render: (value: string | undefined, record) =>
                      value ?? (record.productId ? '已关联商品' : '—'),
                  },
                  {
                    title: '区域',
                    dataIndex: 'region',
                    width: 120,
                    render: (value: string | undefined) => value ?? '—',
                  },
                  {
                    title: '更新时间',
                    dataIndex: 'updatedAt',
                    width: 190,
                    render: (value: string | undefined) => value ?? '—',
                  },
                  {
                    title: '操作',
                    key: 'actions',
                    width: 140,
                    fixed: 'right',
                    render: (_, record) => (
                      <Space size={2}>
                        <Button
                          type="link"
                          size="small"
                          icon={<EditOutlined />}
                          disabled={!onEditTopicIntent}
                          onClick={(event) => {
                            event.stopPropagation()
                            onEditTopicIntent?.(record.id)
                          }}
                        >
                          编辑
                        </Button>
                        <Button
                          type="link"
                          size="small"
                          danger
                          icon={<DeleteOutlined />}
                          disabled={!onDeleteTopicIntent}
                          onClick={(event) => {
                            event.stopPropagation()
                            onDeleteTopicIntent?.(record.id)
                          }}
                        >
                          删除
                        </Button>
                      </Space>
                    ),
                  },
                ]}
              />
            )}
          </Card>

          <Card title="专题详情" className="geo-panel-card geo-topic-detail-card">
            {selectedTopic ? (
              <>
                <div className="geo-detail-field">
                  <Typography.Text type="secondary">专题名称</Typography.Text>
                  <Typography.Text strong>{selectedTopic.name}</Typography.Text>
                </div>
                <div className="geo-detail-field">
                  <Typography.Text type="secondary">经营空间</Typography.Text>
                  <Typography.Text>{selectedTopic.spaceName ?? '—'}</Typography.Text>
                </div>
                <div className="geo-detail-field">
                  <Typography.Text type="secondary">关联商品</Typography.Text>
                  <Typography.Text>
                    {selectedTopic.productName ?? (selectedTopic.productId ? '已关联商品' : '—')}
                  </Typography.Text>
                </div>
                <div className="geo-detail-field">
                  <Typography.Text type="secondary">区域</Typography.Text>
                  <Typography.Text>{selectedTopic.region ?? '—'}</Typography.Text>
                </div>
                <div className="geo-detail-field">
                  <Typography.Text type="secondary">正式关键词</Typography.Text>
                  <Space size={[4, 4]} wrap>
                    {selectedTopic.keywords.length > 0
                      ? selectedTopic.keywords.map((item, index) => (
                          <Tag key={`${item}-${index}`}>{item}</Tag>
                        ))
                      : '—'}
                  </Space>
                </div>
                <div className="geo-detail-field">
                  <Typography.Text type="secondary">竞品集合</Typography.Text>
                  <Typography.Text>{selectedTopic.competitorSetLabel ?? '—'}</Typography.Text>
                </div>
                <div className="geo-detail-field">
                  <Typography.Text type="secondary">正式问题</Typography.Text>
                  <Typography.Text>{selectedTopic.questionCount ?? '—'}</Typography.Text>
                </div>
                <Space wrap className="geo-topic-detail-card__actions">
                  <Button
                    icon={<ReadOutlined />}
                    disabled={!onOpenQuestionLibrary}
                    onClick={() => onOpenQuestionLibrary?.(selectedTopic.id)}
                  >
                    关键词与问题
                  </Button>
                  <Button
                    icon={<TeamOutlined />}
                    disabled={!onOpenCompetitorSet}
                    onClick={() => onOpenCompetitorSet?.(selectedTopic.id)}
                  >
                    竞品集合
                  </Button>
                </Space>
                <Typography.Paragraph className="geo-destructive-note" type="secondary">
                  删除由服务端校验正式配置和审计引用；发生冲突时不会从页面移除专题。
                </Typography.Paragraph>
              </>
            ) : (
              <div className="geo-inline-empty">
                <Typography.Text type="secondary">选择专题后查看真实详情</Typography.Text>
              </div>
            )}
          </Card>
        </div>
      </section>
    </GeoStateFrame>
  )
}

import { SaveOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Select, Space, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import { diagnosticTraceDescription } from '../../../shared/tracePresentation'
import type {
  GeoAsyncViewProps,
  GeoCompetitorType,
  GeoCompetitorView,
  GeoSelectOption,
  GeoViewError,
} from '../types'
import { GeoStateFrame } from './GeoStateFrame'

export interface GeoCompetitorManagerProps extends GeoAsyncViewProps {
  topics: readonly GeoSelectOption[]
  selectedTopicId?: string
  topicName?: string
  currentVersion?: number
  competitors: readonly GeoCompetitorView[]
  selectedCompetitorIds: readonly string[]
  saving?: boolean
  saveError?: GeoViewError | null
  maximumCompetitors?: number
  embedded?: boolean
  onTopicChange?: (topicId: string) => void
  onSave?: (competitorIds: readonly string[]) => void
}

const COMPETITOR_TYPE_LABELS: Record<GeoCompetitorType, string> = {
  DIRECT: '直接竞品',
  INDIRECT: '间接竞品',
  SUBSTITUTE: '替代品',
}

const COMPETITOR_TYPE_COLORS: Record<GeoCompetitorType, string> = {
  DIRECT: 'blue',
  INDIRECT: 'green',
  SUBSTITUTE: 'purple',
}

export function GeoCompetitorManager({
  loading,
  error,
  onRetry,
  topics,
  selectedTopicId,
  topicName,
  currentVersion,
  competitors,
  selectedCompetitorIds,
  saving = false,
  saveError,
  maximumCompetitors = 20,
  embedded = false,
  onTopicChange,
  onSave,
}: GeoCompetitorManagerProps) {
  const [draftIds, setDraftIds] = useState<string[]>(() => [...selectedCompetitorIds])
  const dirty = JSON.stringify(draftIds) !== JSON.stringify(selectedCompetitorIds)

  return (
    <GeoStateFrame
      label="GEO 竞品集合"
      loading={loading}
      error={error}
      onRetry={onRetry}
      empty={topics.length === 0}
      emptyTitle="还没有 GEO 专题"
      emptyDescription="请先创建专题，再选择同一经营空间下的正式竞品。"
    >
      <section className="geo-competitors" aria-label={embedded ? '正式竞品集合' : undefined} aria-labelledby={embedded ? undefined : 'geo-competitors-title'}>
        {!embedded ? <header className="geo-page-heading">
          <div>
            <Typography.Title id="geo-competitors-title" level={1}>竞品集合</Typography.Title>
            <Typography.Paragraph type="secondary">
              {topicName ? `专题：${topicName}` : '选择专题后维护正式竞品集合。'}
            </Typography.Paragraph>
          </div>
          <Select
            className="geo-topic-selector"
            showSearch
            optionFilterProp="label"
            placeholder="选择专题"
            value={selectedTopicId}
            options={topics.map((topic) => ({ value: topic.id, label: topic.label }))}
            onChange={onTopicChange}
          />
        </header> : null}

        {!selectedTopicId ? (
          <div className="geo-inline-empty geo-inline-empty--large">
            <Typography.Text type="secondary">请选择一个专题</Typography.Text>
          </div>
        ) : (
          <Card
            title={`正式竞品（${draftIds.length}/${maximumCompetitors}）`}
            extra={
              <Typography.Text type="secondary">
                集合版本：{currentVersion === undefined ? '—' : `v${currentVersion}`}
              </Typography.Text>
            }
            className="geo-panel-card geo-table-card geo-competitor-set-card"
          >
            <div className="geo-table-card__content">
              <div className="geo-competitor-set-notice">
                <Typography.Text type="secondary">
                  可选资料仅来自该专题所属经营空间。勾选顺序将作为集合顺序整体保存，仅用于后续监测，不会修改已有自动计划的授权范围。
                </Typography.Text>
                {dirty ? <Tag color="warning">未保存草稿</Tag> : null}
              </div>
              {saveError ? (
                <Alert
                  type="error"
                  showIcon
                  title={saveError.message}
                  description={diagnosticTraceDescription(saveError.traceId)}
                />
              ) : null}
            </div>
            {competitors.length === 0 ? (
              <div className="geo-inline-empty">
                <Typography.Text type="secondary">当前经营空间还没有可选竞品</Typography.Text>
              </div>
            ) : (
              <Table<GeoCompetitorView>
                rowKey="id"
                dataSource={competitors}
                pagination={false}
                scroll={{ x: 760 }}
                rowSelection={{
                  type: 'checkbox',
                  selectedRowKeys: draftIds,
                  preserveSelectedRowKeys: true,
                  getCheckboxProps: (record) => ({
                    disabled:
                      !draftIds.includes(record.id) && draftIds.length >= maximumCompetitors,
                  }),
                  onSelect: (record, selected) => {
                    setDraftIds((current) =>
                      selected
                        ? current.includes(record.id) ? current : [...current, record.id]
                        : current.filter((id) => id !== record.id),
                    )
                  },
                  onSelectAll: (selected, selectedRows) => {
                    setDraftIds(selected ? selectedRows.slice(0, maximumCompetitors).map((item) => item.id) : [])
                  },
                }}
                columns={[
                  {
                    title: '竞品名称',
                    dataIndex: 'name',
                    width: 220,
                    render: (name: string, record) => (
                      <div className="geo-competitor-name">
                        <Typography.Text strong>{name}</Typography.Text>
                        {record.aliases.length > 0 ? (
                          <Typography.Text type="secondary">别名：{record.aliases.join('、')}</Typography.Text>
                        ) : (
                          <Typography.Text type="secondary">别名：—</Typography.Text>
                        )}
                      </div>
                    ),
                  },
                  {
                    title: '竞品关系',
                    dataIndex: 'competitorType',
                    width: 140,
                    render: (type: GeoCompetitorType) => (
                      <Tag color={COMPETITOR_TYPE_COLORS[type]}>{COMPETITOR_TYPE_LABELS[type]}</Tag>
                    ),
                  },
                  {
                    title: '当前集合顺序',
                    width: 130,
                    render: (_, record) => {
                      const index = draftIds.indexOf(record.id)
                      return index === -1 ? '—' : index + 1
                    },
                  },
                ]}
              />
            )}
            <div className="geo-table-card__footer">
              <Space wrap className="geo-competitor-set-actions">
                <Button
                  aria-label="整体保存竞品集合"
                  type="primary"
                  icon={<SaveOutlined />}
                  loading={saving}
                  disabled={!dirty || !onSave}
                  onClick={() => onSave?.(draftIds)}
                >
                  整体保存竞品集合
                </Button>
                <Typography.Text type="secondary">
                  保存失败不会覆盖上一版正式集合。
                </Typography.Text>
              </Space>
            </div>
          </Card>
        )}
      </section>
    </GeoStateFrame>
  )
}

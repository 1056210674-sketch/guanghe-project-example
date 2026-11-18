import {
  BgColorsOutlined,
  InfoCircleOutlined,
  PictureOutlined,
} from '@ant-design/icons'
import { Alert, Button, Card, Empty, Input, Select, Spin, Typography } from 'antd'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { diagnosticTraceDescription } from '../../../shared/tracePresentation'
import { useRecoveredFormState } from '../../shared/useRecoveredFormState'
import type {
  ChatReferenceAssetView,
  GeneratedImageResultGroupView,
  PaidTaskConfirmationView,
  ParallelTaskGroupView,
  TaskCreatedReceiptView,
  TaskExecutionOutcomeGroupView,
} from '../../chat'
import { GeneratedImageResultGallery } from '../../chat/components/GeneratedImageResultGallery'
import { PaidTaskConfirmationCard } from '../../chat/components/PaidTaskConfirmationCard'
import { ParallelTaskGroup } from '../../chat/components/ParallelTaskGroup'
import { TaskCreatedReceiptCard } from '../../chat/components/TaskCreatedReceiptCard'
import { TaskExecutionOutcomeGroup } from '../../chat/components/TaskExecutionOutcomeGroup'
import type { TaskCenterListItemView } from '../../task-center'
import {
  TASK_CENTER_TYPE_LABELS,
  TaskStatusTag,
} from '../../task-center'
import type {
  ImageAspectRatio,
  ImageCreationDraft,
  ImageCreationMode,
  ImageCreationParameters,
  ImageCreationOption,
  ImageCreationViewError,
} from '../types'

const CREATION_MODES = [
  { label: '商品资料生图', value: 'PRODUCT_GROUNDED' },
  { label: '纯文本生图', value: 'TEXT_ONLY' },
  { label: '本次附件生图', value: 'SELECTED_ASSETS' },
  { label: '局部重绘', value: 'REGION_EDIT' },
] as const

const ASPECT_RATIO_OPTIONS: readonly {
  label: string
  value: ImageAspectRatio
}[] = [
  { label: '1:1', value: '1:1' },
  { label: '3:4', value: '3:4' },
  { label: '4:3', value: '4:3' },
  { label: '9:16', value: '9:16' },
  { label: '16:9', value: '16:9' },
]

export interface ImageCreationWorkspaceProps {
  mode?: ImageCreationMode
  geoModeAvailable?: boolean
  onModeChange?: (mode: ImageCreationMode) => void
  sourceControls?: ReactNode
  businessSpaces: readonly ImageCreationOption[]
  products: readonly ImageCreationOption[]
  selectedBusinessSpaceId?: string
  selectedProductId?: string
  recoveredParameters?: { key: string; values: ImageCreationParameters }
  suggestedScene?: { key: string; value: string }
  onSceneChange?: (value: string) => void
  catalogReferenceAssets?: readonly ChatReferenceAssetView[]
  draftConfirmation?: PaidTaskConfirmationView
  taskReceipt?: TaskCreatedReceiptView
  taskProgress?: ParallelTaskGroupView
  taskOutcome?: TaskExecutionOutcomeGroupView
  imageResult?: GeneratedImageResultGroupView
  recentTasks?: readonly TaskCenterListItemView[]
  recentTaskPreviewUrls?: Readonly<Record<string, string | undefined>>
  catalogLoading?: boolean
  draftCreationEnabled?: boolean
  draftCreationDisabledReason?: string
  draftLoading?: boolean
  taskLoading?: boolean
  error?: ImageCreationViewError | null
  onBusinessSpaceChange?: (businessSpaceId: string | undefined) => void
  onProductChange?: (productId: string | undefined) => void
  onParametersChange?: () => void
  onPrepareDraft?: (draft: ImageCreationDraft) => void
  onEditDraft?: (draftId: string) => void
  onConfirmDraft?: (draftId: string) => void
  onOpenRecharge?: (draftId: string) => void
  onPrepareReestimate?: (draftId: string) => void
  onViewTask?: (taskId: string) => void
  onOpenImage?: (assetId: string) => void
  onOpenAssets?: (assetId: string) => void
  onRetry?: () => void
}

function RecentImageTaskPreview({
  task,
  url,
}: {
  task: TaskCenterListItemView
  url?: string
}) {
  const [failedUrl, setFailedUrl] = useState<string>()
  return (
    <div className="image-creator-history__thumb">
      {url && failedUrl !== url ? (
        <img
          src={url}
          alt={`${task.name ?? TASK_CENTER_TYPE_LABELS[task.taskType]}预览`}
          onError={() => setFailedUrl(url)}
        />
      ) : (
        <span>{task.previewAssetId ? '图片暂不可预览' : '暂无预览'}</span>
      )}
    </div>
  )
}

function RecentImageTasks({
  tasks,
  previewUrls,
  onViewTask,
}: {
  tasks: readonly TaskCenterListItemView[]
  previewUrls: Readonly<Record<string, string | undefined>>
  onViewTask: (taskId: string) => void
}) {
  return (
    <Card className="image-creator-history" title="最近图片任务">
      {tasks.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无图片任务" />
      ) : (
        <ul>
          {tasks.map((task) => (
            <li key={task.id}>
              <RecentImageTaskPreview task={task} url={previewUrls[task.id]} />
              <Typography.Text ellipsis>
                {task.name ?? TASK_CENTER_TYPE_LABELS[task.taskType]}
              </Typography.Text>
              <TaskStatusTag status={task.status} />
              <Button size="small" onClick={() => onViewTask(task.id)}>
                查看详情
              </Button>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

export function ImageCreationWorkspace({
  mode = 'PRODUCT_GROUNDED',
  onModeChange,
  sourceControls,
  businessSpaces,
  products,
  selectedBusinessSpaceId,
  selectedProductId,
  recoveredParameters,
  suggestedScene,
  onSceneChange,
  catalogReferenceAssets = [],
  draftConfirmation,
  taskReceipt,
  taskProgress,
  taskOutcome,
  imageResult,
  recentTasks = [],
  recentTaskPreviewUrls = {},
  catalogLoading = false,
  draftCreationEnabled = false,
  draftCreationDisabledReason,
  draftLoading = false,
  taskLoading = false,
  error,
  onBusinessSpaceChange = () => undefined,
  onProductChange = () => undefined,
  onParametersChange,
  onPrepareDraft = () => undefined,
  onEditDraft = () => undefined,
  onConfirmDraft = () => undefined,
  onOpenRecharge = () => undefined,
  onPrepareReestimate = () => undefined,
  onViewTask = () => undefined,
  onOpenImage = () => undefined,
  onOpenAssets = () => undefined,
  onRetry,
}: ImageCreationWorkspaceProps) {
  const catalogMode = mode === 'PRODUCT_GROUNDED' || mode === 'GEO_REPORT'
  const { values, setField: updateField } = useRecoveredFormState<ImageCreationParameters>(
    { scene: '', style: '', lighting: '', aspectRatio: '1:1', count: 1, negativePrompt: '' },
    recoveredParameters,
  )
  const { scene = '', style = '', lighting = '', aspectRatio, count, negativePrompt = '' } = values
  const appliedSuggestionKey = useRef<string | undefined>(undefined)
  useEffect(() => {
    if (suggestedScene && appliedSuggestionKey.current !== suggestedScene.key) {
      appliedSuggestionKey.current = suggestedScene.key
      updateField('scene', suggestedScene.value)
    }
  }, [suggestedScene, updateField])
  const setField = <K extends keyof ImageCreationParameters>(key: K, value: ImageCreationParameters[K]) => {
    updateField(key, value)
    onParametersChange?.()
  }

  const draft = useMemo<ImageCreationDraft | undefined>(() => {
    if (!selectedBusinessSpaceId || (catalogMode && !selectedProductId)) return undefined
    return {
      mode,
      businessSpaceId: selectedBusinessSpaceId,
      productId: selectedProductId,
      scene: scene.trim() || undefined,
      style: style.trim() || undefined,
      lighting: lighting.trim() || undefined,
      aspectRatio,
      count,
      negativePrompt: negativePrompt.trim() || undefined,
    }
  }, [
    aspectRatio,
    count,
    lighting,
    mode,
    catalogMode,
    negativePrompt,
    scene,
    selectedBusinessSpaceId,
    selectedProductId,
    style,
  ])
  const canPrepare = Boolean(
    draft?.scene && (mode !== 'REGION_EDIT' || draft.scene.length <= 800) && draftCreationEnabled && !draftLoading && !taskLoading,
  )

  return (
    <section className="image-creator" aria-labelledby="image-creator-title">
      <header className="image-creator-heading">
        <Typography.Title id="image-creator-title" level={1}>
          图片创作
        </Typography.Title>
        <Typography.Text type="secondary">
          选择商品资料、文字或参考图片，先确认报价再生成
        </Typography.Text>
      </header>

      {error ? (
        <Alert
          type="error"
          showIcon
          title={error.message}
          description={diagnosticTraceDescription(error.traceId)}
          action={onRetry ? <Button onClick={onRetry}>重试</Button> : undefined}
        />
      ) : null}

      <div className="image-creator-layout">
        <Card className="image-creator-config" title="创作设置">
          <div className="image-creator-modes" role="group" aria-label="图片创作方式">
            {CREATION_MODES.map((item) => (
              <button
                type="button"
                key={item.label}
                className={mode === item.value ? 'is-active' : undefined}
                aria-pressed={mode === item.value}
                disabled={draftLoading}
                onClick={() => onModeChange?.(item.value)}
              >
                <PictureOutlined aria-hidden="true" />
                <span>{item.label}</span>
              </button>
            ))}
          </div>

          <div className="image-creator-field image-creator-field--description">
            <label>{catalogMode ? '经营空间与商品' : '经营空间'}</label>
            <div className="image-creator-select-row">
              <Select
                aria-label="经营空间"
                value={selectedBusinessSpaceId}
                options={[...businessSpaces]}
                placeholder="请选择经营空间"
                loading={catalogLoading}
                disabled={catalogLoading || draftLoading || businessSpaces.length === 0}
                allowClear
                onChange={onBusinessSpaceChange}
              />
              {catalogMode ? <Select
                aria-label="商品"
                value={selectedProductId}
                options={[...products]}
                placeholder="请选择商品"
                loading={catalogLoading}
                disabled={!selectedBusinessSpaceId || catalogLoading || draftLoading}
                allowClear
                onChange={onProductChange}
              /> : null}
            </div>
          </div>

          {sourceControls}

          <div className="image-creator-field image-creator-field--description image-creator-field--scene">
            <label htmlFor="image-creator-scene">画面场景</label>
            <Input.TextArea
              id="image-creator-scene"
              aria-label="画面场景"
              disabled={draftLoading}
              value={scene}
              maxLength={mode === 'REGION_EDIT' ? 800 : 1000}
              showCount
              autoSize={{ minRows: 4, maxRows: 8 }}
              placeholder="例如：咖啡机放在晨光厨房台面上，突出产品主体"
              onChange={(event) => { setField('scene', event.target.value); onSceneChange?.(event.target.value) }}
            />
          </div>

          <div className="image-creator-parameter-grid">
            {mode !== 'GEO_REPORT' ? <div className="image-creator-field">
              <label htmlFor="image-creator-style">风格</label>
              <Input
                id="image-creator-style"
                aria-label="风格"
                disabled={draftLoading}
                value={style}
                maxLength={256}
                placeholder="例如：清新写实"
                onChange={(event) => setField('style', event.target.value)}
              />
            </div> : null}
            {mode !== 'GEO_REPORT' ? <div className="image-creator-field">
              <label htmlFor="image-creator-lighting">光线</label>
              <Input
                id="image-creator-lighting"
                aria-label="光线"
                disabled={draftLoading}
                value={lighting}
                maxLength={256}
                placeholder="例如：清晨自然光"
                onChange={(event) => setField('lighting', event.target.value)}
              />
            </div> : null}
            <div className="image-creator-field">
              <label htmlFor="image-creator-ratio">画幅</label>
              <Select
                id="image-creator-ratio"
                aria-label="画幅"
                disabled={draftLoading || mode === 'REGION_EDIT'}
                value={aspectRatio}
                options={[...ASPECT_RATIO_OPTIONS]}
                onChange={(value) => setField('aspectRatio', value)}
              />
              {mode === 'REGION_EDIT' ? <Typography.Text type="secondary">保持原图尺寸</Typography.Text> : null}
            </div>
            <div className="image-creator-field">
              <label htmlFor="image-creator-count">数量</label>
              <Select
                id="image-creator-count"
                aria-label="数量"
                disabled={draftLoading}
                value={count}
                options={[1, 2, 3, 4].map((value) => ({ label: `${value} 张`, value }))}
                onChange={(value) => setField('count', value)}
              />
            </div>
          </div>

          <div className="image-creator-field image-creator-field--description">
            <label htmlFor="image-creator-negative">不希望出现（选填）</label>
            <Input.TextArea
              id="image-creator-negative"
              aria-label="不希望出现"
              disabled={draftLoading}
              value={negativePrompt}
              maxLength={500}
              autoSize={{ minRows: 2, maxRows: 4 }}
              placeholder="例如：文字、水印、人物"
              onChange={(event) => setField('negativePrompt', event.target.value)}
            />
          </div>

          <div className="image-creator-config__footer">
            <span>
              <InfoCircleOutlined /> 创建任务草稿后将显示最新报价
            </span>
            <Button
              type="primary"
              size="large"
              icon={<BgColorsOutlined />}
              loading={draftLoading}
              disabled={!canPrepare}
              onClick={() => draft && onPrepareDraft(draft)}
            >
              获取报价并确认
            </Button>
          </div>
          {!draftCreationEnabled && draftCreationDisabledReason ? (
            <Typography.Text type="secondary" role="status">
              {draftCreationDisabledReason}
            </Typography.Text>
          ) : null}
        </Card>

        <div className="image-creator-main">
          <Card className="image-creator-preview" title={mode === 'GEO_REPORT' ? '创作依据：GEO 报告与商品资料' : mode === 'PRODUCT_GROUNDED' ? '创作依据：商品资料' : mode === 'TEXT_ONLY' ? '创作依据：文字描述' : '创作依据：所选图片'}>
            <div className="image-creator-preview-stage">
              {catalogReferenceAssets.length ? (
                catalogReferenceAssets.map((asset) => (
                  <div className="image-creator-preview-cell" key={asset.id}>
                    {asset.thumbnailUrl ? (
                      <img src={asset.thumbnailUrl} alt={asset.name} />
                    ) : (
                      <div className="image-creator-preview-placeholder">
                        <PictureOutlined aria-hidden="true" />
                        <span>{asset.name}</span>
                      </div>
                    )}
                  </div>
                ))
              ) : (
                <div className="image-creator-preview-cell image-creator-preview-cell--result">
                  <div className="image-creator-preview-placeholder">
                    <PictureOutlined aria-hidden="true" />
                    <span>{mode === 'TEXT_ONLY' ? '根据你的画面描述生成，不使用商品图片' : '选择图片后显示真实参考图'}</span>
                  </div>
                </div>
              )}
            </div>
          </Card>
          <RecentImageTasks tasks={recentTasks} previewUrls={recentTaskPreviewUrls} onViewTask={onViewTask} />
        </div>

        <aside className="image-creator-rail">
          <Card className="image-creator-suggestions" title="提交说明">
            <ul>
              <li><InfoCircleOutlined />{mode === 'GEO_REPORT' ? '使用报告建议与当前商品已绑定的身份图片' : mode === 'PRODUCT_GROUNDED' ? '使用当前商品已绑定的身份图片' : mode === 'REGION_EDIT' ? '仅重绘涂抹区域，区域外保持原图不变' : mode === 'TEXT_ONLY' ? '按文字描述生成，不要求商品或参考图' : '只使用本次明确选择的参考图片'}</li>
              <li><InfoCircleOutlined />创建草稿不会扣除积分或创建任务</li>
              <li><InfoCircleOutlined />确认后请以任务中心的最新状态为准</li>
              <li><InfoCircleOutlined />每次可生成 1–4 张候选图片，报价以确认卡为准</li>
            </ul>
          </Card>
        </aside>
      </div>

      <Card className="image-creator-confirmation" title="任务确认与结果">
        <div className="image-creator-live-state">
          {taskLoading ? <Spin description="正在读取真实任务状态…" /> : null}
          {draftConfirmation ? (
            <PaidTaskConfirmationCard
              confirmation={draftConfirmation}
              onEdit={onEditDraft}
              onConfirm={onConfirmDraft}
              onOpenRecharge={onOpenRecharge}
              onPrepareReestimate={onPrepareReestimate}
              onOpenPaidTaskReferenceAsset={onOpenImage}
              onAddPaidTaskReferenceAsset={() => undefined}
            />
          ) : null}
          {taskReceipt ? (
            <TaskCreatedReceiptCard receipt={taskReceipt} onViewTask={onViewTask} />
          ) : null}
          {taskProgress ? (
            <ParallelTaskGroup group={taskProgress} onViewTask={onViewTask} />
          ) : null}
          {taskOutcome ? (
            <TaskExecutionOutcomeGroup group={taskOutcome} onAction={() => undefined} />
          ) : null}
          {imageResult ? (
            <GeneratedImageResultGallery
              group={imageResult}
              onPreviewGeneratedImage={onOpenImage}
              onDownloadGeneratedImage={onOpenImage}
              onSaveGeneratedImageToAssets={onOpenAssets}
              onSelectGeneratedImage={() => undefined}
            />
          ) : null}
          {!draftConfirmation && !taskReceipt && !taskProgress && !taskOutcome && !imageResult && !taskLoading ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="填写参数后即可获取最新报价"
            />
          ) : null}
        </div>
      </Card>
    </section>
  )
}

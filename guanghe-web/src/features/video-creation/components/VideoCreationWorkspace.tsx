import {
  BgColorsOutlined,
  ClockCircleOutlined,
  InfoCircleOutlined,
  VideoCameraOutlined,
} from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  InputNumber,
  Select,
  Spin,
  Switch,
  Typography,
} from 'antd'
import { diagnosticTraceDescription } from '../../../shared/tracePresentation'
import { useEffect, useMemo, useRef, type ReactNode } from 'react'
import { useRecoveredFormState } from '../../shared/useRecoveredFormState'
import type {
  GeneratedVideoPlaybackSourceView,
  GeneratedVideoResultView,
  PaidTaskConfirmationView,
  ParallelTaskGroupView,
  TaskCreatedReceiptView,
  TaskExecutionOutcomeGroupView,
} from '../../chat'
import { GeneratedVideoResultPlayer } from '../../chat/components/GeneratedVideoResultPlayer'
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
  VideoAspectRatio,
  VideoCreationDraft,
  VideoCreationMode,
  VideoCreationOption,
  VideoCreationParameters,
  VideoCreationViewError,
  VideoResolution,
} from '../types'

const CREATION_MODES: readonly {
  value: VideoCreationMode
  label: string
  enabled: boolean
}[] = [
  { value: 'IMAGE_TO_VIDEO', label: '商品图片生成视频', enabled: true },
  { value: 'TEXT_TO_VIDEO', label: '纯文本生成视频', enabled: true },
  { value: 'PRODUCT_MATERIAL', label: '商品素材生成', enabled: true },
]

const ASPECT_RATIO_OPTIONS: readonly {
  label: string
  value: VideoAspectRatio
}[] = [
  { label: '自适应', value: 'adaptive' },
  { label: '16:9', value: '16:9' },
  { label: '4:3', value: '4:3' },
  { label: '1:1', value: '1:1' },
  { label: '3:4', value: '3:4' },
  { label: '9:16', value: '9:16' },
]

const RESOLUTION_OPTIONS: readonly {
  label: string
  value: VideoResolution
}[] = [
  { label: '480P', value: '480P' },
  { label: '720P', value: '720P' },
  { label: '1080P', value: '1080P' },
]

export interface VideoCreationWorkspaceProps {
  mode?: VideoCreationMode
  onModeChange?: (mode: VideoCreationMode) => void
  sourceControls?: ReactNode
  businessSpaces: readonly VideoCreationOption[]
  products: readonly VideoCreationOption[]
  selectedBusinessSpaceId?: string
  selectedProductId?: string
  recoveredParameters?: { key: string; values: VideoCreationParameters }
  suggestedScene?: { key: string; value: string }
  onSceneChange?: (value: string) => void
  draftConfirmation?: PaidTaskConfirmationView
  taskReceipt?: TaskCreatedReceiptView
  taskProgress?: ParallelTaskGroupView
  taskOutcome?: TaskExecutionOutcomeGroupView
  videoResult?: GeneratedVideoResultView
  recentTasks?: readonly TaskCenterListItemView[]
  catalogLoading?: boolean
  draftCreationEnabled?: boolean
  draftCreationDisabledReason?: string
  draftLoading?: boolean
  taskLoading?: boolean
  error?: VideoCreationViewError | null
  onBusinessSpaceChange?: (businessSpaceId: string | undefined) => void
  onProductChange?: (productId: string | undefined) => void
  onParametersChange?: () => void
  onPrepareDraft?: (draft: VideoCreationDraft) => void
  onEditDraft?: (draftId: string) => void
  onConfirmDraft?: (draftId: string) => void
  onOpenRecharge?: (draftId: string) => void
  onPrepareReestimate?: (draftId: string) => void
  onViewTask?: (taskId: string) => void
  onOpenVideo?: (assetId: string) => void
  onRefreshVideo?: (
    assetId: string,
  ) => Promise<GeneratedVideoPlaybackSourceView>
  onRetry?: () => void
}

function RecentVideoTasks({
  tasks,
  onViewTask,
}: {
  tasks: readonly TaskCenterListItemView[]
  onViewTask: (taskId: string) => void
}) {
  return (
    <Card className="video-creator-history" title="最近视频任务">
      {tasks.length === 0 ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="暂无视频任务"
        />
      ) : (
        <ul>
          {tasks.map((task) => (
            <li key={task.id}>
              <div className="video-creator-history__summary">
                <span className="video-creator-history__thumb">
                  <VideoCameraOutlined />
                </span>
                <div>
                  <Typography.Text strong ellipsis>
                    {task.name ?? TASK_CENTER_TYPE_LABELS[task.taskType]}
                  </Typography.Text>
                  <Typography.Text type="secondary">
                    {task.stage?.label ?? '等待真实任务阶段'}
                  </Typography.Text>
                </div>
              </div>
              <TaskStatusTag status={task.status} />
              <time>{task.createdAt}</time>
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

export function VideoCreationWorkspace({
  mode = 'IMAGE_TO_VIDEO',
  onModeChange,
  sourceControls,
  businessSpaces,
  products,
  selectedBusinessSpaceId,
  selectedProductId,
  recoveredParameters,
  suggestedScene,
  onSceneChange,
  draftConfirmation,
  taskReceipt,
  taskProgress,
  taskOutcome,
  videoResult,
  recentTasks = [],
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
  onOpenVideo = () => undefined,
  onRefreshVideo,
  onRetry,
}: VideoCreationWorkspaceProps) {
  const { values, setField: updateField } = useRecoveredFormState<VideoCreationParameters>(
    {
      scene: '', style: '', durationSeconds: 5, aspectRatio: 'adaptive',
      cameraMotion: '', resolution: '720P', audio: false, seed: -1,
      promptExtend: true, watermark: false,
    },
    recoveredParameters,
  )
  const {
    scene, style = '', durationSeconds, aspectRatio, cameraMotion = '',
    resolution, audio, seed, promptExtend, watermark,
  } = values
  const appliedSuggestionKey = useRef<string | undefined>(undefined)
  useEffect(() => {
    if (suggestedScene && appliedSuggestionKey.current !== suggestedScene.key) {
      appliedSuggestionKey.current = suggestedScene.key
      updateField('scene', suggestedScene.value)
    }
  }, [suggestedScene, updateField])
  const setField = <K extends keyof VideoCreationParameters>(key: K, value: VideoCreationParameters[K]) => {
    updateField(key, value)
    onParametersChange?.()
  }

  const draft = useMemo<VideoCreationDraft | undefined>(() => {
    if (!selectedBusinessSpaceId) return undefined
    return {
      mode,
      businessSpaceId: selectedBusinessSpaceId,
      productId: selectedProductId,
      scene: scene.trim(),
      style: style.trim() || undefined,
      durationSeconds,
      aspectRatio,
      cameraMotion: cameraMotion.trim() || undefined,
      resolution,
      audio,
      seed,
      promptExtend,
      watermark,
    }
  }, [
    aspectRatio,
    audio,
    cameraMotion,
    durationSeconds,
    mode,
    promptExtend,
    resolution,
    scene,
    seed,
    selectedBusinessSpaceId,
    selectedProductId,
    style,
    watermark,
  ])
  const canPrepare = Boolean(
    draft?.scene && draftCreationEnabled && !draftLoading && !taskLoading,
  )

  return (
    <section className="video-creator" aria-labelledby="video-creator-title">
      <Card className="video-creator-workspace">
        <header className="video-creator-heading">
          <Typography.Title id="video-creator-title" level={1}>
            视频创作
          </Typography.Title>
          <Typography.Text type="secondary">
            根据文字、商品素材或 GEO 洞察创建视频任务
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

        <div className="video-creator-workspace__grid">
          <div className="video-creator-config">
            <div className="video-creator-modes" role="group" aria-label="视频创作方式">
              {CREATION_MODES.map((item) => (
                <button
                  type="button"
                  key={item.value}
                  className={mode === item.value ? 'is-active' : undefined}
                  aria-pressed={mode === item.value}
                  disabled={draftLoading}
                  onClick={() => onModeChange?.(item.value)}
                >
                  {item.label}
                </button>
              ))}
            </div>

            <div className="video-creator-field video-creator-field--stacked">
              <label>{mode === 'TEXT_TO_VIDEO' ? '经营空间' : '经营空间与商品'}</label>
              <div className="video-creator-select-row">
                <Select
                  aria-label="经营空间"
                  value={selectedBusinessSpaceId}
                  options={[...businessSpaces]}
                  placeholder="请选择经营空间"
                  loading={catalogLoading}
                  disabled={catalogLoading || draftLoading || businessSpaces.length === 0}
                  allowClear
                  onChange={(value) => onBusinessSpaceChange(value)}
                />
                {mode !== 'TEXT_TO_VIDEO' ? <Select
                  aria-label="商品"
                  value={selectedProductId}
                  options={[...products]}
                  placeholder="请选择商品"
                  loading={catalogLoading}
                  disabled={!selectedBusinessSpaceId || catalogLoading || draftLoading}
                  allowClear
                  onChange={(value) => onProductChange(value)}
                /> : null}
              </div>
            </div>

            {sourceControls}

            <div className="video-creator-field video-creator-field--stacked">
              <label htmlFor="video-creator-scene">场景</label>
              <Input.TextArea
                id="video-creator-scene"
                aria-label="场景"
                disabled={draftLoading}
                value={scene}
                maxLength={1000}
                showCount
                autoSize={{ minRows: 4, maxRows: 8 }}
                placeholder="描述需要生成的视频场景、主体和动作"
                onChange={(event) => { setField('scene', event.target.value); onSceneChange?.(event.target.value) }}
              />
            </div>

            <div className="video-creator-parameter-grid">
              {mode !== 'GEO_INSIGHT' ? <div className="video-creator-field">
                <label htmlFor="video-creator-style">风格（选填）</label>
                <Input
                  id="video-creator-style"
                  aria-label="风格"
                  disabled={draftLoading}
                  value={style}
                  maxLength={256}
                  placeholder="例如：写实、电影感"
                  onChange={(event) => setField('style', event.target.value)}
                />
              </div> : null}
              {mode !== 'GEO_INSIGHT' ? <div className="video-creator-field">
                <label htmlFor="video-creator-camera">镜头运动（选填）</label>
                <Input
                  id="video-creator-camera"
                  aria-label="镜头运动"
                  disabled={draftLoading}
                  value={cameraMotion}
                  maxLength={256}
                  placeholder="例如：缓慢推进"
                  onChange={(event) => setField('cameraMotion', event.target.value)}
                />
              </div> : null}
              <div className="video-creator-field">
                <label htmlFor="video-creator-duration">时长（秒）</label>
                <InputNumber
                  id="video-creator-duration"
                  aria-label="时长"
                  disabled={draftLoading}
                  min={2}
                  max={30}
                  step={1}
                  value={durationSeconds}
                  onChange={(value) => setField('durationSeconds', value ?? 5)}
                />
              </div>
              <div className="video-creator-field">
                <label htmlFor="video-creator-ratio">画幅</label>
                <Select
                  id="video-creator-ratio"
                  aria-label="画幅"
                  disabled={draftLoading}
                  value={aspectRatio}
                  options={[...ASPECT_RATIO_OPTIONS]}
                  onChange={(value) => setField('aspectRatio', value)}
                />
              </div>
              <div className="video-creator-field">
                <label htmlFor="video-creator-resolution">分辨率</label>
                <Select
                  id="video-creator-resolution"
                  aria-label="分辨率"
                  disabled={draftLoading}
                  value={resolution}
                  options={[...RESOLUTION_OPTIONS]}
                  onChange={(value) => setField('resolution', value)}
                />
              </div>
              <div className="video-creator-field">
                <label htmlFor="video-creator-seed">随机种子</label>
                <InputNumber
                  id="video-creator-seed"
                  aria-label="随机种子"
                  disabled={draftLoading}
                  min={-1}
                  max={2147483647}
                  step={1}
                  value={seed}
                  onChange={(value) => setField('seed', value ?? -1)}
                />
              </div>
            </div>

            <div className="video-creator-switches" aria-label="视频生成选项">
              <label>
                <span>生成音频</span>
                <Switch aria-label="生成音频" disabled={draftLoading} checked={audio} onChange={(value) => setField('audio', value)} />
              </label>
              <label>
                <span>提示词扩展</span>
                <Switch
                  aria-label="提示词扩展"
                  disabled={draftLoading}
                  checked={promptExtend}
                  onChange={(value) => setField('promptExtend', value)}
                />
              </label>
              <label>
                <span>添加水印</span>
                <Switch aria-label="添加水印" disabled={draftLoading} checked={watermark} onChange={(value) => setField('watermark', value)} />
              </label>
            </div>
          </div>

          <div className="video-creator-preview">
            <div className="video-creator-player">
              <div className="video-creator-player__empty">
                <VideoCameraOutlined />
                <span>任务完成后将在这里展示永久视频资产</span>
              </div>
            </div>
            <div className="video-creator-preview__footer">
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
          </div>

          <Card className="video-creator-suggestions" title="提交说明">
            <ul className="video-creator-selling-points">
              <li><ClockCircleOutlined />时长范围为 2～30 秒</li>
              <li><InfoCircleOutlined />创建草稿不会扣除积分或创建任务</li>
              <li><InfoCircleOutlined />确认后请以任务中心的最新状态为准</li>
              <li><InfoCircleOutlined />{mode === 'TEXT_TO_VIDEO' ? '根据文字生成，不使用首帧图片' : mode === 'PRODUCT_MATERIAL' ? '以选中的商品素材作为参考，保留商品主体' : mode === 'GEO_INSIGHT' ? '依据真实监测报告与商品资料创作，不保证曝光效果' : '基于商品身份图生成视频'}</li>
            </ul>
          </Card>
        </div>
      </Card>

      <div className="video-creator-bottom">
        <RecentVideoTasks tasks={recentTasks} onViewTask={onViewTask} />
        <div className="video-creator-live-state">
          {taskLoading ? <Spin description="正在读取真实任务状态…" /> : null}
          {draftConfirmation ? (
            <PaidTaskConfirmationCard
              confirmation={draftConfirmation}
              onEdit={onEditDraft}
              onConfirm={onConfirmDraft}
              onOpenRecharge={onOpenRecharge}
              onPrepareReestimate={onPrepareReestimate}
              onOpenPaidTaskReferenceAsset={(_draftId, assetId) => onOpenVideo(assetId)}
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
          {videoResult ? (
            <GeneratedVideoResultPlayer
              video={videoResult}
              onOpenOriginal={onOpenVideo}
              onRefreshPlaybackUrl={onRefreshVideo}
            />
          ) : null}
          {!draftConfirmation && !taskReceipt && !taskProgress && !taskOutcome && !videoResult && !taskLoading ? (
            <Card className="video-creator-confirmation" title="任务确认与结果">
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="填写参数后即可获取最新报价"
              />
            </Card>
          ) : null}
        </div>
      </div>
    </section>
  )
}

import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useRef, useState } from 'react'
import { Alert, Button, Pagination, Select, Typography } from 'antd'
import { useNavigate } from 'react-router-dom'
import { isApiError } from '../../api/apiError'
import { createAssetDownloadUrl } from '../../api/assetApi'
import { createGeoCreativePlan, getGeoReport, listGeoReports, listGeoTopics } from '../../api/geoApi'
import type { GeoCreativePlanResponse } from '../../api/geoApi'
import { geoMerchantNarrative } from '../../features/geo/merchantNarrative'
import { GeoVisualPlanPanel } from '../../features/creative-modes/GeoVisualPlanPanel'
import { restoredGeoVisualPlan } from '../../features/creative-modes/geoPlanPresentation'
import '../../features/creative-modes/creative-modes.css'
import {
  getPaidTask,
  getTaskDraft,
} from '../../api/conversationApi'
import type { CreateTaskDraftRequest, PaidTask } from '../../api/conversationApi'
import {
  useAsset,
  useAssetDetails,
  useAssetDownloadUrls,
} from '../../features/assets/live/assetQueries'
import {
  useAllBusinessSpaceOptions,
  useAllProductOptions,
  useProductIdentityAssets,
} from '../../features/business-data/catalogQueries'
import {
  generatedVideoAssetReference,
  toGeneratedVideoResult,
  toPaidTaskConfirmation,
  toParallelTaskItem,
  toTaskCreatedReceipt,
  toTaskExecutionOutcome,
} from '../../features/chat/live/chatAdapters'
import { creativeGroundingErrorMessage } from '../../features/chat/creativeGrounding'
import { usePointAccount } from '../../features/chat/live/chatQueries'
import { useTaskCenterList } from '../../features/task-center'
import {
  prepareFormTaskDraft,
  resolveFormTaskDraftConversation,
  submitFormTaskDraftConfirmation,
} from '../../features/task-draft'
import { openExternalHttps } from '../../features/shared/openExternalHttps'
import { VideoCreationWorkspace } from '../../features/video-creation'
import type {
  VideoCreationDraft,
  VideoCreationMode,
  VideoCreationViewError,
} from '../../features/video-creation'
import {
  readVideoCreationRecovery,
  storeVideoCreationRecovery,
} from '../../features/video-creation/live/videoCreationRecovery'
import type { VideoCreationRecoveryState } from '../../features/video-creation/live/videoCreationRecovery'
import { UserShell } from '../../layouts/UserShell'
import { APP_PATHS, appLinks } from '../../routes/paths'
import { useAuthStore } from '../../stores/authStore'

const VIDEO_CONVERSATION_TITLE = '视频创作'
const TERMINAL_TASK_STATUSES: readonly PaidTask['status'][] = [
  'SUCCEEDED',
  'PARTIAL',
  'FAILED',
  'SKIPPED',
  'SYSTEM_TERMINATED',
]

function isTerminalTask(task?: PaidTask): boolean {
  return Boolean(task && TERMINAL_TASK_STATUSES.includes(task.status))
}

function toVideoCreationError(
  error: unknown,
  fallback = '视频任务数据加载失败，请稍后重试。',
): VideoCreationViewError | null {
  if (!error) return null
  if (isApiError(error)) {
    return {
      message: creativeGroundingErrorMessage(error.code, error.message),
      traceId: error.traceId,
    }
  }
  return { message: fallback }
}

function toCreateRequest(
  conversationId: string,
  draft: VideoCreationDraft,
  referenceAssetIds: readonly string[],
  geoSource?: { reportId: string; creativePlan: GeoCreativePlanResponse['plan'] },
): CreateTaskDraftRequest {
  const source = draft.mode === 'TEXT_TO_VIDEO'
    ? { sourceMode: 'TEXT_ONLY' as const, referenceAssetIds: [] }
    : draft.mode === 'PRODUCT_MATERIAL'
      ? { sourceMode: 'PRODUCT_MATERIALS' as const, referenceAssetIds: [...referenceAssetIds] }
      : draft.mode === 'GEO_INSIGHT'
        ? { sourceMode: 'GEO_REPORT' as const, referenceAssetIds: [...referenceAssetIds], geoReportId: geoSource?.reportId, creativePlan: geoSource?.creativePlan }
        : { sourceMode: 'CATALOG_PRODUCT' as const, referenceAssetIds: [...referenceAssetIds] }
  return {
    conversationId,
    taskType: 'VIDEO',
    spaceId: draft.businessSpaceId,
    productId: draft.mode === 'TEXT_TO_VIDEO' ? null : draft.productId,
    parameterSchemaVersion: 1,
    parameters: {
      requestText: draft.scene,
      mode: 'AGENT_ASSISTED',
      agentSummary: draft.scene,
      ...source,
      scene: draft.scene,
      ...(draft.mode === 'GEO_INSIGHT' ? {} : { style: draft.style, cameraMotion: draft.cameraMotion }),
      durationSeconds: draft.durationSeconds,
      aspectRatio: draft.aspectRatio,
      resolution: draft.resolution,
      audio: draft.audio,
      seed: draft.seed,
      promptExtend: draft.promptExtend,
      watermark: draft.watermark,
    },
  }
}

function selectVideoReferenceAssetId(
  items: readonly { assetId: string; role: string; sequence: number }[],
): string[] {
  const ordered = [...items].sort((left, right) => left.sequence - right.sequence)
  const preferred =
    ordered.find(({ role }) => role === 'PRIMARY') ??
    ordered.find(({ role }) => role === 'WHITE_BACKGROUND')
  return preferred ? [preferred.assetId] : []
}

export function VideoCreationPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const ownerId = useAuthStore((state) => state.user?.id)
  const [businessSpaceId, setBusinessSpaceId] = useState<string>()
  const [productId, setProductId] = useState<string>()
  const [selectedMode, setSelectedMode] = useState<VideoCreationMode>()
  const [materialIds, setMaterialIds] = useState<string[]>()
  const [selectedReportId, setSelectedReportId] = useState<string | null>()
  const [selectedTopicId, setSelectedTopicId] = useState<string | null>()
  const [topicPage, setTopicPage] = useState(1)
  const [reportPage, setReportPage] = useState(1)
  const [userDirection, setUserDirection] = useState('')
  const [creativePlan, setCreativePlan] = useState<GeoCreativePlanResponse>()
  const [planKey, setPlanKey] = useState('')
  const [planning, setPlanning] = useState(false)
  const [planError, setPlanError] = useState<string>()
  const planEpoch = useRef(0)
  const [recovery, setRecovery] = useState<VideoCreationRecoveryState>(() =>
    readVideoCreationRecovery(ownerId),
  )
  const [editingDraft, setEditingDraft] = useState(false)
  const [formRevision, setFormRevision] = useState(0)
  const [preparing, setPreparing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [actionError, setActionError] = useState<VideoCreationViewError | null>(null)
  const prepareLock = useRef(false)
  const confirmLock = useRef(false)

  const updateRecovery = (next: VideoCreationRecoveryState) => {
    setRecovery(next)
    storeVideoCreationRecovery(ownerId, next)
  }

  const draftId = recovery.draftAttempt?.draftId
  const taskId = recovery.confirmationAttempt?.taskId
  const spacesQuery = useAllBusinessSpaceOptions()
  const draftQuery = useQuery({
    queryKey: ['video-creation', ownerId ?? null, 'draft', draftId ?? null],
    queryFn: () => getTaskDraft(draftId as string),
    enabled: Boolean(draftId),
  })
  const taskQuery = useQuery({
    queryKey: ['video-creation', ownerId ?? null, 'task', taskId ?? null],
    queryFn: () => getPaidTask(taskId as string),
    enabled: Boolean(taskId),
    refetchInterval: (query) =>
      isTerminalTask(query.state.data) ? false : 5_000,
  })
  const selectedBusinessSpaceId =
    editingDraft ? businessSpaceId : businessSpaceId ?? draftQuery.data?.spaceId
  const selectedProductId =
    editingDraft
      ? productId
      : productId ?? draftQuery.data?.productId ?? undefined
  const restoredParameters = draftQuery.data && 'durationSeconds' in draftQuery.data.parameters ? draftQuery.data.parameters : undefined
  const mode: VideoCreationMode = selectedMode ?? (restoredParameters?.sourceMode === 'TEXT_ONLY' ? 'TEXT_TO_VIDEO' : restoredParameters?.sourceMode === 'PRODUCT_MATERIALS' ? 'PRODUCT_MATERIAL' : restoredParameters?.sourceMode === 'GEO_REPORT' ? 'GEO_INSIGHT' : 'IMAGE_TO_VIDEO')
  const chosenMaterials = materialIds ?? (restoredParameters?.sourceMode === 'PRODUCT_MATERIALS' ? restoredParameters.referenceAssetIds : [])
  const geoReportId = selectedReportId === undefined ? restoredParameters?.geoReportId : selectedReportId ?? undefined
  const productsQuery = useAllProductOptions(mode === 'TEXT_TO_VIDEO' ? undefined : selectedBusinessSpaceId)
  const identityAssetsQuery = useProductIdentityAssets(mode !== 'TEXT_TO_VIDEO' ? selectedProductId : undefined)
  const geoReportQuery = useQuery({
    queryKey: ['video-creation', ownerId, 'report', geoReportId],
    queryFn: () => getGeoReport(geoReportId!),
    enabled: mode === 'GEO_INSIGHT' && Boolean(geoReportId),
  })
  const quotedGeoPlan = !editingDraft && restoredParameters?.sourceMode === 'GEO_REPORT' && restoredParameters.creativePlan
    ? restoredGeoVisualPlan(restoredParameters.creativePlan, geoReportQuery.data)
    : undefined
  const geoTopicId = selectedTopicId === undefined ? geoReportQuery.data?.topicId : selectedTopicId ?? undefined
  const topicsQuery = useQuery({
    queryKey: ['video-creation', ownerId, 'topics', selectedBusinessSpaceId, selectedProductId, topicPage],
    queryFn: () => listGeoTopics({ spaceId: selectedBusinessSpaceId, productId: selectedProductId, page: topicPage, size: 20 }),
    enabled: mode === 'GEO_INSIGHT' && Boolean(selectedBusinessSpaceId && selectedProductId),
  })
  const reportsQuery = useQuery({
    queryKey: ['video-creation', ownerId, 'reports', geoTopicId, reportPage],
    queryFn: () => listGeoReports({ topicId: geoTopicId, page: reportPage, size: 20 }),
    enabled: mode === 'GEO_INSIGHT' && Boolean(geoTopicId),
  })
  const productImageIds = (identityAssetsQuery.data?.items ?? []).map(({ assetId }) => assetId)
  const productImageDetails = useAssetDetails(mode === 'PRODUCT_MATERIAL' ? productImageIds : [])
  const productImageDownloads = useAssetDownloadUrls(mode === 'PRODUCT_MATERIAL' ? productImageIds : [])
  const recentTasksQuery = useTaskCenterList({
    taskType: 'VIDEO',
    page: 1,
    size: 5,
  })
  const activeDraft = editingDraft || taskId ? undefined : draftQuery.data
  const legacyGeoDraft = Boolean(activeDraft && 'sourceMode' in activeDraft.parameters && activeDraft.parameters.sourceMode === 'GEO_REPORT' && !('creativePlan' in activeDraft.parameters && activeDraft.parameters.creativePlan))
  const pointAccountQuery = usePointAccount(Boolean(activeDraft))
  const recoveredDraft = draftQuery.data
  const recoveredParameters = recoveredDraft && 'durationSeconds' in recoveredDraft.parameters
    ? {
        key: `${recoveredDraft.id}:${formRevision}`,
        values: {
          scene: recoveredDraft.parameters.scene,
          style: recoveredDraft.parameters.style,
          durationSeconds: recoveredDraft.parameters.durationSeconds,
          aspectRatio: recoveredDraft.parameters.aspectRatio,
          cameraMotion: recoveredDraft.parameters.cameraMotion,
          resolution: recoveredDraft.parameters.resolution,
          audio: recoveredDraft.parameters.audio,
          seed: recoveredDraft.parameters.seed,
          promptExtend: recoveredDraft.parameters.promptExtend,
          watermark: recoveredDraft.parameters.watermark,
        },
      }
    : undefined
  const resultAssetId = taskQuery.data
    ? generatedVideoAssetReference(taskQuery.data)?.assetId
    : undefined
  const resultAssetQuery = useAsset(resultAssetId)
  const resultDownloadUrls = useAssetDownloadUrls(
    resultAssetId ? [resultAssetId] : [],
  )
  const confirmationReferenceIds = activeDraft?.referenceAssetIds ?? []
  const confirmationReferenceDetails = useAssetDetails(confirmationReferenceIds)
  const confirmationReferenceDownloads =
    useAssetDownloadUrls(confirmationReferenceIds)

  const businessSpaces = useMemo(
    () =>
      (spacesQuery.data ?? []).map((space) => ({
        label: space.name,
        value: space.id,
      })),
    [spacesQuery.data],
  )
  const products = useMemo(
    () =>
      (productsQuery.data ?? []).map((product) => ({
        label: product.name,
        value: product.id,
      })),
    [productsQuery.data],
  )
  const selectedProductName = mode !== 'TEXT_TO_VIDEO' ? products.find(
    (product) => product.value === (activeDraft?.productId ?? selectedProductId),
  )?.label : undefined
  const task = taskQuery.data
  const taskReceipt = task
    ? toTaskCreatedReceipt(task, pointAccountQuery.data, selectedProductName)
    : undefined
  const taskProgress = task && !isTerminalTask(task)
    ? {
        id: `video-task-progress-${task.id}`,
        title: '视频任务执行状态',
        tasks: [toParallelTaskItem(task, selectedProductName)],
      }
    : undefined
  const taskOutcome = task && isTerminalTask(task)
    ? {
        id: `video-task-outcome-${task.id}`,
        outcomes: [toTaskExecutionOutcome(task)],
      }
    : undefined
  const videoResult = task
    ? toGeneratedVideoResult(task, {
        creativeSourceMode: restoredParameters?.sourceMode,
        name: resultAssetQuery.data?.name,
        videoUrl: resultAssetId ? resultDownloadUrls[resultAssetId]?.url : undefined,
        expiresAt: resultAssetId
          ? resultDownloadUrls[resultAssetId]?.expiresAt
          : undefined,
      })
    : undefined

  const viewError =
    actionError ??
    toVideoCreationError(topicsQuery.error ?? reportsQuery.error ?? geoReportQuery.error, 'GEO 报告加载失败，请重试。') ??
    toVideoCreationError(spacesQuery.error ?? productsQuery.error, '经营资料加载失败，请稍后重试。') ??
    toVideoCreationError(identityAssetsQuery.error, '商品图片加载失败，请稍后重试。') ??
    toVideoCreationError(draftQuery.error, '任务草稿加载失败，请稍后重试。') ??
    toVideoCreationError(taskQuery.error, '任务状态加载失败，请稍后重试。') ??
    toVideoCreationError(resultAssetQuery.error, '永久视频资产加载失败，请稍后重试。') ??
    toVideoCreationError(recentTasksQuery.error, '最近视频任务加载失败，请稍后重试。')

  const handlePrepareDraft = async (formDraft: VideoCreationDraft) => {
    if (prepareLock.current) return
    prepareLock.current = true
    setPreparing(true)
    setActionError(null)
    try {
      if ((mode === 'IMAGE_TO_VIDEO' || mode === 'GEO_INSIGHT') && !identityAssetsQuery.data?.creativeReady) {
        setActionError({
          message: '该商品还缺少可用的主图或白底图，请先到商品资料中补充图片。',
        })
        return
      }
      if (mode === 'PRODUCT_MATERIAL' && (chosenMaterials.length < 1 || chosenMaterials.length > 3 || chosenMaterials.some((id) => !productImageIds.includes(id)))) {
        setActionError({ message: '请从当前商品已绑定的图片中选择 1–3 张素材。' }); return
      }
      if (mode === 'GEO_INSIGHT' && (!geoReportId || !geoReportQuery.data || !creativePlan || creativePlan.plan.reportId !== geoReportId || creativePlan.plan.scene !== formDraft.scene)) {
        setActionError({ message: '请先根据当前 GEO 报告生成创意方案；修改场景后需要重新生成，才能获取报价。' }); return
      }
      const resolved = await resolveFormTaskDraftConversation({
        recovery,
        title: VIDEO_CONVERSATION_TITLE,
        onRecoveryChange: updateRecovery,
      })
      const { conversationId } = resolved
      const request = toCreateRequest(
        conversationId,
        formDraft,
        mode === 'TEXT_TO_VIDEO' ? [] : mode === 'PRODUCT_MATERIAL' ? chosenMaterials : selectVideoReferenceAssetId(identityAssetsQuery.data?.items ?? []),
        mode === 'GEO_INSIGHT' && geoReportId && creativePlan ? { reportId: geoReportId, creativePlan: creativePlan.plan } : undefined,
      )
      const { draft: createdDraft } = await prepareFormTaskDraft({
        recovery: resolved.recovery,
        request,
        currentDraft: draftQuery.data,
        onRecoveryChange: updateRecovery,
      })
      queryClient.setQueryData(
        ['video-creation', ownerId ?? null, 'draft', createdDraft.id],
        createdDraft,
      )
      setEditingDraft(false)
    } catch (error) {
      setActionError(toVideoCreationError(error))
    } finally {
      prepareLock.current = false
      setPreparing(false)
    }
  }

  const handleConfirmDraft = async (confirmationId: string) => {
    const currentDraft = draftQuery.data
    if (
      confirmLock.current ||
      !currentDraft ||
      currentDraft.id !== confirmationId ||
      !currentDraft.confirmationToken
    ) {
      return
    }
    if ('sourceMode' in currentDraft.parameters && currentDraft.parameters.sourceMode === 'GEO_REPORT' && !('creativePlan' in currentDraft.parameters && currentDraft.parameters.creativePlan)) {
      setActionError({ message: '这份旧 GEO 草稿没有创意方案，请重新生成方案并获取报价。' })
      return
    }
    confirmLock.current = true
    setConfirming(true)
    setActionError(null)
    try {
      await submitFormTaskDraftConfirmation({
        recovery,
        draft: currentDraft,
        onRecoveryChange: updateRecovery,
      })
      await queryClient.invalidateQueries({
        queryKey: ['task-center', ownerId ?? null, 'list'],
      })
    } catch (error) {
      setActionError(toVideoCreationError(error))
    } finally {
      confirmLock.current = false
      setConfirming(false)
    }
  }

  const handleOpenVideo = async (assetId: string) => {
    setActionError(null)
    try {
      const download = await createAssetDownloadUrl(assetId)
      if (!openExternalHttps(download.url)) {
        setActionError({ message: '视频临时链接无法安全打开。' })
      }
    } catch (error) {
      setActionError(toVideoCreationError(error, '视频临时链接获取失败，请稍后重试。'))
    }
  }

  const retry = () => {
    setActionError(null)
    void spacesQuery.refetch()
    if (selectedBusinessSpaceId && mode !== 'TEXT_TO_VIDEO') void productsQuery.refetch()
    if (selectedProductId && mode !== 'TEXT_TO_VIDEO') void identityAssetsQuery.refetch()
    if (mode === 'GEO_INSIGHT') {
      if (selectedProductId) void topicsQuery.refetch()
      if (geoTopicId) void reportsQuery.refetch()
      if (geoReportId) void geoReportQuery.refetch()
    }
    if (draftId) void draftQuery.refetch()
    if (taskId) void taskQuery.refetch()
    if (resultAssetId) void resultAssetQuery.refetch()
    void recentTasksQuery.refetch()
  }

  const invalidateForm = () => {
    setEditingDraft(true)
    setBusinessSpaceId(selectedBusinessSpaceId)
    setProductId(selectedProductId)
    setActionError(null)
  }
  const clearPlan = () => {
    planEpoch.current += 1
    setCreativePlan(undefined)
    setPlanError(undefined)
    setPlanning(false)
  }
  const handleGeneratePlan = async () => {
    if (!geoReportId || !selectedProductId || planning) return
    const epoch = ++planEpoch.current
    setPlanning(true)
    setPlanError(undefined)
    try {
      const result = await createGeoCreativePlan(geoReportId, {
        taskType: 'VIDEO', productId: selectedProductId,
        ...(userDirection.trim() ? { userDirection: userDirection.trim() } : {}),
      })
      if (epoch === planEpoch.current) {
        setCreativePlan(result)
        setPlanKey(`${geoReportId}:${epoch}`)
        invalidateForm()
      }
    } catch (error) {
      if (epoch === planEpoch.current) setPlanError(toVideoCreationError(error, '创意方案生成失败，请保留要求并重试。')?.message)
    } finally {
      if (epoch === planEpoch.current) setPlanning(false)
    }
  }
  const clearSources = () => {
    clearPlan()
    setMaterialIds([])
    setSelectedReportId(null)
    setSelectedTopicId(null)
    setTopicPage(1)
    setReportPage(1)
  }
  const creationEnabled = Boolean(selectedBusinessSpaceId && (
    mode === 'TEXT_TO_VIDEO' ||
    (selectedProductId && mode === 'IMAGE_TO_VIDEO' && identityAssetsQuery.data?.creativeReady) ||
    (selectedProductId && mode === 'PRODUCT_MATERIAL' && chosenMaterials.length > 0 && chosenMaterials.length <= 3 && chosenMaterials.every((id) => productImageIds.includes(id))) ||
    (selectedProductId && mode === 'GEO_INSIGHT' && identityAssetsQuery.data?.creativeReady && geoReportId && geoReportQuery.data && creativePlan?.plan.reportId === geoReportId && !geoReportQuery.error)
  ))

  return (
    <UserShell>
      <VideoCreationWorkspace
        mode={mode}
        onModeChange={(next) => { if (next !== mode) { invalidateForm(); setSelectedMode(next); clearSources() } }}
        sourceControls={mode === 'PRODUCT_MATERIAL' ? <section className="creative-source-panel" aria-label="商品素材选择">
          <Typography.Text>选择 1–3 张商品图片作为视频参考（已选 {chosenMaterials.length}/3）</Typography.Text>
          <div className="creative-material-options">
            {productImageIds.map((id, index) => <button type="button" key={id} className="creative-material-option"
              aria-pressed={chosenMaterials.includes(id)} aria-label={`选择商品素材 ${index + 1}`} disabled={preparing || confirming || (!chosenMaterials.includes(id) && chosenMaterials.length >= 3)}
              onClick={() => { invalidateForm(); setMaterialIds(chosenMaterials.includes(id) ? chosenMaterials.filter((item) => item !== id) : [...chosenMaterials, id]) }}>
              {productImageDownloads[id]?.url ? <img src={productImageDownloads[id]?.url} alt={productImageDetails[id]?.name ?? `商品素材 ${index + 1}`} /> : null}
              <span>{productImageDetails[id]?.name ?? `商品素材 ${index + 1}`}</span>
              <span>{chosenMaterials.includes(id) ? `已选 · 参考 ${chosenMaterials.indexOf(id) + 1}` : '点击选择'}</span>
            </button>)}
          </div>
          {!productImageIds.length ? <Typography.Text type="secondary">请先选择商品；暂无图片时到经营资料中补充商品图片。</Typography.Text> : null}
        </section> : mode === 'GEO_INSIGHT' ? <section className="creative-source-panel" aria-label="GEO 视频创作依据">
          <div className="creative-source-select">
            <Select aria-label="GEO 专题" placeholder="选择当前商品的监测专题" value={geoTopicId} disabled={!selectedProductId || preparing || confirming} loading={topicsQuery.isLoading}
              options={(topicsQuery.data?.items ?? []).map(({ id, name }) => ({ label: name, value: id }))}
              onChange={(id) => { invalidateForm(); clearPlan(); setSelectedTopicId(id); setSelectedReportId(null); setReportPage(1) }} />
            {(topicsQuery.data?.total ?? 0) > 20 ? <Pagination size="small" current={topicPage} pageSize={20} total={topicsQuery.data?.total} showSizeChanger={false} onChange={setTopicPage} /> : null}
            <Select aria-label="GEO 报告" placeholder="选择已完成的监测报告" value={geoReportId} disabled={!geoTopicId || preparing || confirming} loading={reportsQuery.isLoading}
              options={(reportsQuery.data?.items ?? []).map((report) => ({ label: `${report.generatedAt} · ${report.status === 'PARTIAL' ? '部分完成' : '已完成'} · ${report.summary}`, value: report.id }))}
              onChange={(id) => { invalidateForm(); clearPlan(); setSelectedReportId(id) }} />
            {(reportsQuery.data?.total ?? 0) > 20 ? <Pagination size="small" current={reportPage} pageSize={20} total={reportsQuery.data?.total} showSizeChanger={false} onChange={setReportPage} /> : null}
          </div>
          {geoReportQuery.data ? <>
            <Typography.Paragraph>{geoMerchantNarrative(geoReportQuery.data).factualSummary}</Typography.Paragraph>
            <Typography.Paragraph>{geoMerchantNarrative(geoReportQuery.data).summary}</Typography.Paragraph>
            {geoReportQuery.data.methodVersion === 'geo-deterministic-v1' ? <Alert type="warning" showIcon title="这份报告尚未形成智能解读，可在完整报告中基于已保存回答生成解读。" /> : null}
            {geoReportQuery.data.status === 'PARTIAL' ? <Alert type="warning" title="报告部分样本未完成，请结合覆盖范围参考。" /> : null}
            {geoReportQuery.data.observations.filter((item) => item.status === 'SUCCEEDED').length === 0 ? <Alert type="warning" showIcon title="这份报告没有成功回答样本，无法生成有依据的创意方案。" /> : null}
            <Button size="small" onClick={() => navigate(appLinks.geoReportDetail(geoReportQuery.data!.id))}>查看完整报告</Button>
            <Typography.Text type="secondary">先从成功监测回答中提炼创意机会、画面与镜头节奏，再获取报价；不保证提高可见度。</Typography.Text>
          </> : <Typography.Text type="secondary">当前商品没有报告时，请先完成一次 GEO 监测。</Typography.Text>}
          <GeoVisualPlanPanel taskType="VIDEO" userDirection={userDirection} onDirectionChange={(value) => { setUserDirection(value); clearPlan(); invalidateForm() }}
            canGenerate={Boolean(geoReportId && selectedProductId && geoReportQuery.data && geoReportQuery.data.methodVersion === 'geo-synthesis-v1' && geoReportQuery.data.topic.productId === selectedProductId && geoReportQuery.data.topic.spaceId === selectedBusinessSpaceId && geoReportQuery.data.observations.some((item) => item.status === 'SUCCEEDED'))}
            loading={planning} plan={creativePlan ?? quotedGeoPlan} error={planError} onGenerate={() => void handleGeneratePlan()} />
          {legacyGeoDraft ? <Alert type="warning" showIcon title="旧 GEO 草稿缺少创意方案，不能直接确认；请生成方案后重新获取报价。" /> : null}
        </section> : undefined}
        businessSpaces={businessSpaces}
        products={products}
        selectedBusinessSpaceId={selectedBusinessSpaceId}
        selectedProductId={selectedProductId}
        recoveredParameters={recoveredParameters}
        suggestedScene={mode === 'GEO_INSIGHT' && creativePlan ? { key: planKey, value: creativePlan.plan.scene } : undefined}
        onSceneChange={(value) => { if (mode === 'GEO_INSIGHT' && creativePlan && value !== creativePlan.plan.scene) clearPlan() }}
        catalogLoading={spacesQuery.isLoading || productsQuery.isLoading}
        draftCreationEnabled={creationEnabled}
        draftCreationDisabledReason={
          !selectedBusinessSpaceId ? '请先选择经营空间。' : mode === 'TEXT_TO_VIDEO' ? undefined : mode === 'PRODUCT_MATERIAL' ? '请选择当前商品的 1–3 张参考素材。' : mode === 'GEO_INSIGHT' ? '请选择有成功样本的 GEO 报告并生成创意方案。' : selectedProductId && identityAssetsQuery.data && !identityAssetsQuery.data.creativeReady
            ? '该商品还缺少主图或白底图，请先到经营资料中补充后再创建视频。'
            : !selectedProductId
              ? '请先选择一个商品；商品资料完整后才能创建视频。'
              : identityAssetsQuery.isLoading
                ? '正在检查商品图片资料…'
                : undefined
        }
        draftLoading={preparing || confirming || draftQuery.isLoading}
        taskLoading={taskQuery.isLoading || resultAssetQuery.isLoading}
        error={viewError}
        draftConfirmation={
          activeDraft && !legacyGeoDraft
            ? toPaidTaskConfirmation(
                activeDraft,
                pointAccountQuery.data,
                selectedProductName,
                confirming,
                undefined,
                (activeDraft.referenceAssetIds ?? []).map((assetId, index) => ({
                  id: assetId,
                  name:
                    confirmationReferenceDetails[assetId]?.name ??
                    `参考图片 ${index + 1}`,
                  thumbnailUrl: confirmationReferenceDownloads[assetId]?.url,
                })),
              )
            : undefined
        }
        taskReceipt={taskReceipt}
        taskProgress={taskProgress}
        taskOutcome={taskOutcome}
        videoResult={videoResult}
        recentTasks={recentTasksQuery.data?.items ?? []}
        onBusinessSpaceChange={(nextBusinessSpaceId) => {
          setEditingDraft(true)
          setBusinessSpaceId(nextBusinessSpaceId)
          setProductId(undefined)
          clearSources()
        }}
        onProductChange={(nextProductId) => {
          setEditingDraft(true)
          setBusinessSpaceId(selectedBusinessSpaceId)
          setProductId(nextProductId)
          clearSources()
        }}
        onPrepareDraft={(draft) => void handlePrepareDraft(draft)}
        onParametersChange={() => {
          setBusinessSpaceId(selectedBusinessSpaceId)
          setProductId(selectedProductId)
          setEditingDraft(true)
        }}
        onEditDraft={() => {
          clearPlan()
          setFormRevision((value) => value + 1)
          setBusinessSpaceId(draftQuery.data?.spaceId)
          setProductId(draftQuery.data?.productId ?? undefined)
          setEditingDraft(true)
          setActionError(null)
        }}
        onConfirmDraft={(id) => void handleConfirmDraft(id)}
        onOpenRecharge={() => navigate(APP_PATHS.pointsBilling)}
        onPrepareReestimate={() => {
          setBusinessSpaceId(draftQuery.data?.spaceId)
          setProductId(draftQuery.data?.productId ?? undefined)
          setFormRevision((value) => value + 1)
          setEditingDraft(true)
        }}
        onViewTask={(id) => navigate(appLinks.taskDetail(id))}
        onOpenVideo={(assetId) => void handleOpenVideo(assetId)}
        onRefreshVideo={async (assetId) => {
          const download = await createAssetDownloadUrl(assetId)
          return { videoUrl: download.url, expiresAt: download.expiresAt }
        }}
        onRetry={retry}
      />
    </UserShell>
  )
}

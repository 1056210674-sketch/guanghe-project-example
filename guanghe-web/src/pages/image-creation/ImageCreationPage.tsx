import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Pagination, Select, Typography } from 'antd'
import { useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { isApiError } from '../../api/apiError'
import { createAssetDownloadUrl, uploadImageAsset } from '../../api/assetApi'
import { getPaidTask, getTaskDraft } from '../../api/conversationApi'
import type { CreateTaskDraftRequest, PaidTask } from '../../api/conversationApi'
import { createGeoCreativePlan, getGeoReport, listGeoReports, listGeoTopics } from '../../api/geoApi'
import type { GeoCreativePlanResponse } from '../../api/geoApi'
import { geoMerchantNarrative } from '../../features/geo/merchantNarrative'
import { GeoVisualPlanPanel } from '../../features/creative-modes/GeoVisualPlanPanel'
import { restoredGeoVisualPlan } from '../../features/creative-modes/geoPlanPresentation'
import '../../features/creative-modes/creative-modes.css'
import {
  useAssetDetails,
  useAssetDetailsState,
  useAssetDownloadUrls,
  ImageAssetPickerDialog,
} from '../../features/assets'
import { CreativeReferenceImages } from '../../features/creative-modes/CreativeReferenceImages'
import { RegionMaskPainter } from '../../features/creative-modes/RegionMaskPainter'
import { imageUploadSelectionError } from '../../features/shared/imageUploadPolicy'
import {
  useAllBusinessSpaceOptions,
  useAllProductOptions,
  useProductIdentityAssets,
} from '../../features/business-data'
import {
  generatedImageAssetReferences,
  toGeneratedImageResultGroup,
  toPaidTaskConfirmation,
  toParallelTaskItem,
  toTaskCreatedReceipt,
  toTaskExecutionOutcome,
} from '../../features/chat/live/chatAdapters'
import { usePointAccount } from '../../features/chat/live/chatQueries'
import { creativeGroundingErrorMessage } from '../../features/chat/creativeGrounding'
import type {
  ImageCreationDraft,
  ImageCreationMode,
  ImageCreationRecoveryState,
  ImageCreationViewError,
} from '../../features/image-creation'
import {
  ImageCreationWorkspace,
  readImageCreationRecovery,
  storeImageCreationRecovery,
} from '../../features/image-creation'
import { openExternalHttps } from '../../features/shared/openExternalHttps'
import {
  prepareFormTaskDraft,
  resolveFormTaskDraftConversation,
  submitFormTaskDraftConfirmation,
} from '../../features/task-draft'
import { useTaskCenterList } from '../../features/task-center/live/taskCenterQueries'
import { UserShell } from '../../layouts/UserShell'
import { APP_PATHS, appLinks } from '../../routes/paths'
import { useAuthStore } from '../../stores/authStore'

const IMAGE_CONVERSATION_TITLE = '图片创作'
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

function toImageCreationError(
  error: unknown,
  fallback = '图片任务数据加载失败，请稍后重试。',
): ImageCreationViewError | null {
  if (!error) return null
  if (isApiError(error)) {
    return {
      message: creativeGroundingErrorMessage(error.code, error.message),
      traceId: error.traceId,
    }
  }
  return { message: fallback }
}

function selectImageReferenceAssetIds(
  items: readonly { assetId: string; role: string; sequence: number }[],
): string[] {
  const ordered = [...items].sort((left, right) => left.sequence - right.sequence)
  const result: string[] = []
  const add = (assetId?: string) => {
    if (assetId && !result.includes(assetId) && result.length < 3) {
      result.push(assetId)
    }
  }
  const primary = ordered.find(({ role }) => role === 'PRIMARY')
  const whiteBackground = ordered.find(({ role }) => role === 'WHITE_BACKGROUND')
  add(primary?.assetId ?? whiteBackground?.assetId)
  add(whiteBackground?.assetId)
  ordered.forEach(({ assetId }) => add(assetId))
  return result
}

function toCreateRequest(
  conversationId: string,
  draft: ImageCreationDraft,
  referenceAssetIds: readonly string[],
  maskAssetId?: string,
  geoSource?: { reportId: string; recommendationId?: string; creativePlan: GeoCreativePlanResponse['plan'] },
): CreateTaskDraftRequest {
  const requestText = draft.scene ?? '基于商品资料生成商品图片'
  const source = draft.mode === 'TEXT_ONLY'
    ? { sourceMode: 'TEXT_ONLY' as const, referenceAssetIds: [] }
    : draft.mode === 'REGION_EDIT'
      ? { sourceMode: 'REGION_EDIT' as const, referenceAssetIds: [...referenceAssetIds], maskAssetId }
      : draft.mode === 'GEO_REPORT'
        ? { sourceMode: 'GEO_REPORT' as const, referenceAssetIds: [...referenceAssetIds], geoReportId: geoSource?.reportId, geoRecommendationId: geoSource?.recommendationId, creativePlan: geoSource?.creativePlan }
      : { sourceMode: draft.mode === 'PRODUCT_GROUNDED' ? 'CATALOG_PRODUCT' as const : 'SELECTED_ASSETS' as const, referenceAssetIds: [...referenceAssetIds] }
  return {
    conversationId,
    taskType: 'IMAGE',
    spaceId: draft.businessSpaceId,
    productId: draft.mode === 'PRODUCT_GROUNDED' || draft.mode === 'GEO_REPORT' ? draft.productId : null,
    parameterSchemaVersion: 1,
    parameters: {
      requestText,
      mode: 'AGENT_ASSISTED',
      agentSummary: requestText,
      ...source,
      scene: draft.scene,
      ...(draft.mode === 'GEO_REPORT' ? {} : { style: draft.style, lighting: draft.lighting }),
      aspectRatio: draft.aspectRatio,
      count: draft.count,
      negativePrompt: draft.negativePrompt,
    },
  }
}

export function ImageCreationPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const routedReportId = searchParams.get('geoReportId') ?? undefined
  const routedRecommendationId = searchParams.get('geoRecommendationId') ?? undefined
  const queryClient = useQueryClient()
  const ownerId = useAuthStore((state) => state.user?.id)
  const [businessSpaceId, setBusinessSpaceId] = useState<string>()
  const [productId, setProductId] = useState<string>()
  const [selectedMode, setSelectedMode] = useState<ImageCreationMode | undefined>()
  const [selectedReportId, setSelectedReportId] = useState<string | null | undefined>(routedReportId)
  const [selectedRecommendationId, setSelectedRecommendationId] = useState<string | null | undefined>(routedRecommendationId)
  const [selectedTopicId, setSelectedTopicId] = useState<string | null>()
  const [topicPage, setTopicPage] = useState(1)
  const [reportPage, setReportPage] = useState(1)
  const [userDirection, setUserDirection] = useState('')
  const [creativePlan, setCreativePlan] = useState<GeoCreativePlanResponse>()
  const [planKey, setPlanKey] = useState('')
  const [planning, setPlanning] = useState(false)
  const [planError, setPlanError] = useState<string>()
  const planEpoch = useRef(0)
  const [selectedAssets, setSelectedAssets] = useState<string[]>()
  const [selectedMaskId, setSelectedMaskId] = useState<string | null>()
  const [maskFile, setMaskFile] = useState<File>()
  const [assetPickerOpen, setAssetPickerOpen] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [recovery, setRecovery] = useState<ImageCreationRecoveryState>(() =>
    readImageCreationRecovery(ownerId),
  )
  const [editingDraft, setEditingDraft] = useState(false)
  const [formRevision, setFormRevision] = useState(0)
  const [preparing, setPreparing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [actionError, setActionError] = useState<ImageCreationViewError | null>(null)
  const prepareLock = useRef(false)
  const confirmLock = useRef(false)

  const updateRecovery = (next: ImageCreationRecoveryState) => {
    setRecovery(next)
    storeImageCreationRecovery(ownerId, next)
  }

  const draftId = recovery.draftAttempt?.draftId
  const taskId = recovery.confirmationAttempt?.taskId
  const spacesQuery = useAllBusinessSpaceOptions()
  const draftQuery = useQuery({
    queryKey: ['image-creation', ownerId ?? null, 'draft', draftId ?? null],
    queryFn: () => getTaskDraft(draftId as string),
    enabled: Boolean(draftId),
  })
  const taskQuery = useQuery({
    queryKey: ['image-creation', ownerId ?? null, 'task', taskId ?? null],
    queryFn: () => getPaidTask(taskId as string),
    enabled: Boolean(taskId),
    refetchInterval: (query) =>
      isTerminalTask(query.state.data) ? false : 5_000,
  })
  const restoredImageParameters = draftQuery.data && 'count' in draftQuery.data.parameters ? draftQuery.data.parameters : undefined
  const mode: ImageCreationMode = selectedMode ?? (restoredImageParameters?.sourceMode === 'TEXT_ONLY' ? 'TEXT_ONLY' : restoredImageParameters?.sourceMode === 'REGION_EDIT' ? 'REGION_EDIT' : restoredImageParameters?.sourceMode === 'SELECTED_ASSETS' ? 'SELECTED_ASSETS' : restoredImageParameters?.sourceMode === 'GEO_REPORT' ? 'GEO_REPORT' : 'PRODUCT_GROUNDED')
  const catalogMode = mode === 'PRODUCT_GROUNDED' || mode === 'GEO_REPORT'
  const geoReportId = mode === 'GEO_REPORT' ? selectedReportId === undefined ? restoredImageParameters?.geoReportId : selectedReportId ?? undefined : undefined
  const geoRecommendationId = mode === 'GEO_REPORT' ? selectedRecommendationId === undefined ? restoredImageParameters?.geoRecommendationId : selectedRecommendationId ?? undefined : undefined
  const geoReportQuery = useQuery({
    queryKey: ['image-creation', ownerId, 'report', geoReportId],
    queryFn: () => getGeoReport(geoReportId!),
    enabled: mode === 'GEO_REPORT' && Boolean(geoReportId),
  })
  const selectedBusinessSpaceId = editingDraft ? businessSpaceId : businessSpaceId ?? geoReportQuery.data?.topic.spaceId ?? draftQuery.data?.spaceId
  const selectedProductId = editingDraft ? productId : productId ?? geoReportQuery.data?.topic.productId ?? draftQuery.data?.productId ?? undefined
  const geoTopicId = selectedTopicId === undefined ? geoReportQuery.data?.topicId : selectedTopicId ?? undefined
  const quotedGeoPlan = !editingDraft && restoredImageParameters?.sourceMode === 'GEO_REPORT' && restoredImageParameters.creativePlan
    ? restoredGeoVisualPlan(restoredImageParameters.creativePlan, geoReportQuery.data)
    : undefined
  const topicsQuery = useQuery({
    queryKey: ['image-creation', ownerId, 'topics', selectedBusinessSpaceId, selectedProductId, topicPage],
    queryFn: () => listGeoTopics({ spaceId: selectedBusinessSpaceId, productId: selectedProductId, page: topicPage, size: 20 }),
    enabled: mode === 'GEO_REPORT' && Boolean(selectedBusinessSpaceId && selectedProductId),
  })
  const reportsQuery = useQuery({
    queryKey: ['image-creation', ownerId, 'reports', geoTopicId, reportPage],
    queryFn: () => listGeoReports({ topicId: geoTopicId, page: reportPage, size: 20 }),
    enabled: mode === 'GEO_REPORT' && Boolean(geoTopicId),
  })
  const attachmentIds = selectedAssets ?? restoredImageParameters?.referenceAssetIds ?? []
  const maskAssetId = selectedMaskId === undefined ? restoredImageParameters?.maskAssetId : selectedMaskId ?? undefined
  const productsQuery = useAllProductOptions(catalogMode ? selectedBusinessSpaceId : undefined)
  const identityAssetsQuery = useProductIdentityAssets(catalogMode ? selectedProductId : undefined)
  const recentTasksQuery = useTaskCenterList({ taskType: 'IMAGE', page: 1, size: 5 })
  const recentTasks = recentTasksQuery.data?.items ?? []
  const recentPreviewAssetIds = recentTasks.flatMap((task) => task.previewAssetId ? [task.previewAssetId] : [])
  const recentPreviewDownloads = useAssetDownloadUrls(recentPreviewAssetIds)
  const activeDraft = editingDraft || taskId ? undefined : draftQuery.data
  const legacyGeoDraft = Boolean(activeDraft && 'sourceMode' in activeDraft.parameters && activeDraft.parameters.sourceMode === 'GEO_REPORT' && !('creativePlan' in activeDraft.parameters && activeDraft.parameters.creativePlan))
  const pointAccountQuery = usePointAccount(Boolean(activeDraft))

  const selectedIdentityIds = useMemo(
    () => selectImageReferenceAssetIds(identityAssetsQuery.data?.items ?? []),
    [identityAssetsQuery.data?.items],
  )
  const confirmationReferenceIds = activeDraft?.referenceAssetIds ?? []
  const visibleReferenceIds = activeDraft
    ? confirmationReferenceIds
    : catalogMode ? selectedIdentityIds : mode === 'TEXT_ONLY' ? [] : attachmentIds
  const referenceDetails = useAssetDetails(visibleReferenceIds)
  const referenceDownloads = useAssetDownloadUrls(visibleReferenceIds)
  const maskDownloads = useAssetDownloadUrls(mode === 'REGION_EDIT' && maskAssetId ? [maskAssetId] : [])
  const referenceViews = visibleReferenceIds.map((assetId, index) => ({
    id: assetId,
    name: referenceDetails[assetId]?.name ?? `商品参考图 ${index + 1}`,
    thumbnailUrl: referenceDownloads[assetId]?.url,
  }))

  const resultAssetIds = taskQuery.data
    ? generatedImageAssetReferences(taskQuery.data).map(({ assetId }) => assetId)
    : []
  const resultAssetsQuery = useAssetDetailsState(resultAssetIds)
  const resultDownloadUrls = useAssetDownloadUrls(resultAssetIds)
  const recoveredDraft = draftQuery.data
  const recoveredParameters = recoveredDraft && 'count' in recoveredDraft.parameters
    ? {
        key: `${recoveredDraft.id}:${formRevision}`,
        values: {
          scene: recoveredDraft.parameters.scene ?? recoveredDraft.parameters.requestText,
          style: recoveredDraft.parameters.style,
          lighting: recoveredDraft.parameters.lighting,
          aspectRatio: recoveredDraft.parameters.aspectRatio,
          count: recoveredDraft.parameters.count,
          negativePrompt: recoveredDraft.parameters.negativePrompt,
        },
      }
    : undefined

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
  const selectedProductName = catalogMode ? products.find(
    (product) => product.value === (activeDraft?.productId ?? selectedProductId),
  )?.label : undefined
  const task = taskQuery.data
  const taskReceipt = task
    ? toTaskCreatedReceipt(task, pointAccountQuery.data, selectedProductName)
    : undefined
  const taskProgress = task && !isTerminalTask(task)
    ? {
        id: `image-task-progress-${task.id}`,
        title: '图片任务执行状态',
        tasks: [toParallelTaskItem(task, selectedProductName)],
      }
    : undefined
  const taskOutcome = task && isTerminalTask(task)
    ? {
        id: `image-task-outcome-${task.id}`,
        outcomes: [toTaskExecutionOutcome(task)],
      }
    : undefined
  const rawImageResult = task
    ? toGeneratedImageResultGroup(task, {
        creativeSourceMode: restoredImageParameters?.sourceMode ?? 'CATALOG_PRODUCT',
      })
    : undefined
  const imageResult = rawImageResult
    ? {
        ...rawImageResult,
        images: rawImageResult.images.map((image) => ({
          ...image,
          name: resultAssetsQuery.data[image.assetId]?.name ?? image.name,
          imageUrl: resultDownloadUrls[image.assetId]?.url,
          expiresAt: resultDownloadUrls[image.assetId]?.expiresAt,
          selectEnabled: false,
        })),
      }
    : undefined

  const viewError =
    actionError ??
    toImageCreationError(geoReportQuery.error ?? topicsQuery.error ?? reportsQuery.error, 'GEO 报告加载失败，请稍后重试。') ??
    toImageCreationError(spacesQuery.error ?? productsQuery.error, '经营资料加载失败，请稍后重试。') ??
    toImageCreationError(identityAssetsQuery.error, '商品图片加载失败，请稍后重试。') ??
    toImageCreationError(draftQuery.error, '任务草稿加载失败，请稍后重试。') ??
    toImageCreationError(taskQuery.error, '任务状态加载失败，请稍后重试。') ??
    toImageCreationError(resultAssetsQuery.error, '永久图片资产加载失败，请稍后重试。') ??
    toImageCreationError(recentTasksQuery.error, '最近图片任务加载失败，请稍后重试。')

  const handlePrepareDraft = async (formDraft: ImageCreationDraft) => {
    if (prepareLock.current) return
    prepareLock.current = true
    setPreparing(true)
    setActionError(null)
    try {
      if (catalogMode && (!identityAssetsQuery.data?.creativeReady || selectedIdentityIds.length === 0)) {
        setActionError({
          message: '该商品还缺少可用的主图或白底图，请先到商品资料中补充图片。',
        })
        return
      }
      if (mode === 'GEO_REPORT' && (!geoReportId || !creativePlan || creativePlan.plan.reportId !== geoReportId || creativePlan.plan.scene !== formDraft.scene)) {
        setActionError({ message: '请先根据当前 GEO 报告生成创意方案；修改场景后需要重新生成，才能获取报价。' })
        return
      }
      const references = catalogMode ? selectedIdentityIds : mode === 'TEXT_ONLY' ? [] : attachmentIds
      if ((mode === 'SELECTED_ASSETS' && (references.length < 1 || references.length > 3)) || (mode === 'REGION_EDIT' && (references.length !== 1 || (!maskAssetId && !maskFile)))) {
        setActionError({ message: mode === 'REGION_EDIT' ? '请选择一张底图并确认涂抹区域。' : '请选择 1–3 张参考图片。' })
        return
      }
      let uploadedMaskId = maskAssetId
      if (mode === 'REGION_EDIT' && maskFile) {
        const uploaded = await uploadImageAsset({ spaceId: formDraft.businessSpaceId, file: maskFile, name: '局部重绘区域蒙版', idempotencyKey: crypto.randomUUID() })
        uploadedMaskId = uploaded.id
        setSelectedMaskId(uploaded.id)
        setMaskFile(undefined)
      }
      const resolved = await resolveFormTaskDraftConversation({
        recovery,
        title: IMAGE_CONVERSATION_TITLE,
        onRecoveryChange: updateRecovery,
      })
      const request = toCreateRequest(
        resolved.conversationId,
        formDraft,
        references,
        mode === 'REGION_EDIT' ? uploadedMaskId : undefined,
        mode === 'GEO_REPORT' && geoReportId && creativePlan ? { reportId: geoReportId, recommendationId: geoRecommendationId, creativePlan: creativePlan.plan } : undefined,
      )
      const { draft: createdDraft } = await prepareFormTaskDraft({
        recovery: resolved.recovery,
        request,
        currentDraft: draftQuery.data,
        onRecoveryChange: updateRecovery,
      })
      queryClient.setQueryData(
        ['image-creation', ownerId ?? null, 'draft', createdDraft.id],
        createdDraft,
      )
      setEditingDraft(false)
    } catch (error) {
      setActionError(toImageCreationError(error))
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
      setActionError(toImageCreationError(error))
    } finally {
      confirmLock.current = false
      setConfirming(false)
    }
  }

  const handleOpenImage = async (assetId: string) => {
    setActionError(null)
    try {
      const download = await createAssetDownloadUrl(assetId)
      if (!openExternalHttps(download.url)) {
        setActionError({ message: '图片临时链接无法安全打开。' })
      }
    } catch (error) {
      setActionError(toImageCreationError(error, '图片临时链接获取失败，请稍后重试。'))
    }
  }

  const retry = () => {
    setActionError(null)
    void spacesQuery.refetch()
    if (selectedBusinessSpaceId && catalogMode) void productsQuery.refetch()
    if (selectedProductId && catalogMode) void identityAssetsQuery.refetch()
    if (mode === 'GEO_REPORT') {
      if (geoReportId) void geoReportQuery.refetch()
      if (selectedProductId) void topicsQuery.refetch()
      if (geoTopicId) void reportsQuery.refetch()
    }
    if (draftId) void draftQuery.refetch()
    if (taskId) void taskQuery.refetch()
    if (resultAssetIds.length) void resultAssetsQuery.refetch()
    void recentTasksQuery.refetch()
  }

  const invalidateForm = () => {
    setBusinessSpaceId(selectedBusinessSpaceId)
    setProductId(selectedProductId)
    setEditingDraft(true)
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
        taskType: 'IMAGE', productId: selectedProductId,
        ...(geoRecommendationId ? { recommendationId: geoRecommendationId } : {}),
        ...(userDirection.trim() ? { userDirection: userDirection.trim() } : {}),
      })
      if (epoch === planEpoch.current) {
        setCreativePlan(result)
        setPlanKey(`${geoReportId}:${epoch}`)
        invalidateForm()
      }
    } catch (error) {
      if (epoch === planEpoch.current) setPlanError(toImageCreationError(error, '创意方案生成失败，请保留要求并重试。')?.message)
    } finally {
      if (epoch === planEpoch.current) setPlanning(false)
    }
  }
  const updateAttachments = (ids: readonly string[]) => {
    invalidateForm()
    setSelectedAssets([...ids])
    setSelectedMaskId(null)
    setMaskFile(undefined)
  }
  const handleUpload = async (files: readonly File[]) => {
    if (!selectedBusinessSpaceId || uploading) return
    const limit = mode === 'REGION_EDIT' ? 1 : 3
    const error = imageUploadSelectionError(files, limit - attachmentIds.length)
    if (error) { setActionError({ message: error }); return }
    if (mode === 'REGION_EDIT' && files.some((file) => file.size > 10 * 1024 * 1024)) {
      setActionError({ message: '局部重绘底图不能超过 10 MiB。' }); return
    }
    invalidateForm()
    setUploading(true)
    const uploadedIds = [...attachmentIds]
    try {
      for (const file of files) {
        const uploaded = await uploadImageAsset({ spaceId: selectedBusinessSpaceId, file, name: file.name, idempotencyKey: crypto.randomUUID() })
        uploadedIds.push(uploaded.id)
        setSelectedAssets([...uploadedIds])
      }
      setSelectedMaskId(null)
      setMaskFile(undefined)
    } catch (error) { setActionError(toImageCreationError(error, '参考图片上传失败，请重试。')) }
    finally { setUploading(false) }
  }
  const creationEnabled = Boolean(selectedBusinessSpaceId && !uploading && (
    mode === 'TEXT_ONLY' ||
    (catalogMode && selectedProductId && (mode !== 'GEO_REPORT' || (geoReportId && creativePlan?.plan.reportId === geoReportId)) && identityAssetsQuery.data?.creativeReady && selectedIdentityIds.length) ||
    (mode === 'SELECTED_ASSETS' && attachmentIds.length > 0 && attachmentIds.length <= 3) ||
    (mode === 'REGION_EDIT' && attachmentIds.length === 1 && (maskFile || maskAssetId))
  ))

  return (
    <UserShell>
      <ImageCreationWorkspace
        mode={mode}
        geoModeAvailable
        onModeChange={(next) => {
          if (next === mode) return
          invalidateForm()
          setSelectedMode(next)
          setSelectedAssets([])
          setSelectedMaskId(null)
          setMaskFile(undefined)
          clearPlan()
          setSelectedReportId(null)
          setSelectedRecommendationId(null)
          setSelectedTopicId(null)
        }}
        sourceControls={mode === 'GEO_REPORT' ? <section className="creative-source-panel" aria-label="GEO 图片创作依据">
          <div className="creative-source-select">
            <Select aria-label="GEO 专题" placeholder="选择当前商品的监测专题" value={geoTopicId} disabled={!selectedProductId || preparing || confirming} loading={topicsQuery.isLoading}
              options={(topicsQuery.data?.items ?? []).map(({ id, name }) => ({ label: name, value: id }))}
              onChange={(id) => { invalidateForm(); clearPlan(); setSelectedTopicId(id); setSelectedReportId(null); setSelectedRecommendationId(null); setReportPage(1) }} />
            {(topicsQuery.data?.total ?? 0) > 20 ? <Pagination size="small" current={topicPage} pageSize={20} total={topicsQuery.data?.total} showSizeChanger={false} onChange={setTopicPage} /> : null}
            <Select aria-label="GEO 报告" placeholder="选择已完成的监测报告" value={geoReportId} disabled={!geoTopicId || preparing || confirming} loading={reportsQuery.isLoading}
              options={(reportsQuery.data?.items ?? []).map((item) => ({ label: `${item.generatedAt} · ${item.status === 'PARTIAL' ? '部分完成' : '已完成'} · ${item.summary}`, value: item.id }))}
              onChange={(id) => { invalidateForm(); clearPlan(); setSelectedReportId(id); setSelectedRecommendationId(null) }} />
            {(reportsQuery.data?.total ?? 0) > 20 ? <Pagination size="small" current={reportPage} pageSize={20} total={reportsQuery.data?.total} showSizeChanger={false} onChange={setReportPage} /> : null}
            {geoReportQuery.data && geoMerchantNarrative(geoReportQuery.data).recommendations.length ? <Select aria-label="参考建议" placeholder="可选：聚焦某条报告建议" value={geoRecommendationId} allowClear
              options={geoMerchantNarrative(geoReportQuery.data).recommendations.map((item) => ({ label: item.title, value: item.id }))}
              onChange={(id) => { invalidateForm(); clearPlan(); setSelectedRecommendationId(id ?? null) }} /> : null}
          </div>
          {geoReportQuery.data ? <>
            <Typography.Paragraph>{geoMerchantNarrative(geoReportQuery.data).factualSummary}</Typography.Paragraph>
            <Typography.Paragraph>{geoMerchantNarrative(geoReportQuery.data).summary}</Typography.Paragraph>
            {geoReportQuery.data.status === 'PARTIAL' ? <Alert type="warning" showIcon title="报告部分样本未完成，只使用已有的成功回答作依据。" /> : null}
            {geoReportQuery.data.methodVersion === 'geo-deterministic-v1' ? <Alert type="warning" showIcon title="这份报告尚未形成智能解读，可在完整报告中基于已保存回答生成解读。" /> : null}
            {geoReportQuery.data.observations.filter((item) => item.status === 'SUCCEEDED').length === 0 ? <Alert type="warning" showIcon title="这份报告没有成功回答样本，无法生成有依据的创意方案。" /> : null}
            <Button size="small" onClick={() => navigate(appLinks.geoReportDetail(geoReportQuery.data!.id))}>查看完整报告</Button>
          </> : <Typography.Text type="secondary">选择当前商品的 GEO 监测报告，再显式生成创意方案。</Typography.Text>}
          <GeoVisualPlanPanel taskType="IMAGE" userDirection={userDirection} onDirectionChange={(value) => { setUserDirection(value); clearPlan(); invalidateForm() }}
            canGenerate={Boolean(geoReportId && selectedProductId && geoReportQuery.data && geoReportQuery.data.methodVersion === 'geo-synthesis-v1' && geoReportQuery.data.topic.productId === selectedProductId && geoReportQuery.data.topic.spaceId === selectedBusinessSpaceId && geoReportQuery.data.observations.some((item) => item.status === 'SUCCEEDED'))}
            loading={planning} plan={creativePlan ?? quotedGeoPlan} error={planError} onGenerate={() => void handleGeneratePlan()} />
          {legacyGeoDraft ? <Alert type="warning" showIcon title="旧 GEO 草稿缺少创意方案，不能直接确认；请生成方案后重新获取报价。" /> : null}
        </section> : mode === 'SELECTED_ASSETS' || mode === 'REGION_EDIT' ? <>
          <CreativeReferenceImages images={referenceViews} maxSelection={mode === 'REGION_EDIT' ? 1 : 3}
            disabled={preparing || confirming || !selectedBusinessSpaceId} uploading={uploading}
            onChoose={() => setAssetPickerOpen(true)} onUpload={(files) => void handleUpload(files)}
            onRemove={(id) => updateAttachments(attachmentIds.filter((assetId) => assetId !== id))} />
          {mode === 'REGION_EDIT' && attachmentIds.length === 1 && referenceDownloads[attachmentIds[0]]?.url ?
            <RegionMaskPainter key={attachmentIds[0]} imageUrl={referenceDownloads[attachmentIds[0]]!.url}
              savedMaskUrl={maskAssetId ? maskDownloads[maskAssetId]?.url : undefined} disabled={preparing || confirming || uploading}
              onChange={(file) => { invalidateForm(); setMaskFile(file); setSelectedMaskId(null) }} /> : null}
        </> : undefined}
        businessSpaces={businessSpaces}
        products={products}
        selectedBusinessSpaceId={selectedBusinessSpaceId}
        selectedProductId={selectedProductId}
        recoveredParameters={recoveredParameters}
        suggestedScene={mode === 'GEO_REPORT' && creativePlan ? { key: planKey, value: creativePlan.plan.scene } : undefined}
        onSceneChange={(value) => { if (mode === 'GEO_REPORT' && creativePlan && value !== creativePlan.plan.scene) clearPlan() }}
        catalogReferenceAssets={referenceViews}
        catalogLoading={spacesQuery.isLoading || productsQuery.isLoading}
        draftCreationEnabled={creationEnabled}
        draftCreationDisabledReason={
          !selectedBusinessSpaceId ? '请先选择经营空间。' : mode === 'GEO_REPORT' && !creativePlan ? '请先选择有成功样本的 GEO 报告并生成创意方案。' : mode === 'REGION_EDIT' ? '请选择底图并确认涂抹区域后再获取报价。' : mode === 'SELECTED_ASSETS' ? '请选择或上传 1–3 张参考图片。' : mode === 'TEXT_ONLY' ? undefined : selectedProductId && identityAssetsQuery.data && !identityAssetsQuery.data.creativeReady
            ? '该商品还缺少主图或白底图，请先到经营资料中补充后再创建图片。'
            : !selectedProductId
              ? '请先选择一个商品；商品资料完整后才能创建图片。'
              : identityAssetsQuery.isLoading
                ? '正在检查商品图片资料…'
                : undefined
        }
        draftLoading={preparing || confirming || uploading || draftQuery.isLoading}
        taskLoading={taskQuery.isLoading || resultAssetsQuery.isLoading}
        error={viewError}
        draftConfirmation={
          activeDraft && !legacyGeoDraft
            ? toPaidTaskConfirmation(
                activeDraft,
                pointAccountQuery.data,
                selectedProductName,
                confirming,
                undefined,
                referenceViews,
              )
            : undefined
        }
        taskReceipt={taskReceipt}
        taskProgress={taskProgress}
        taskOutcome={taskOutcome}
        imageResult={imageResult}
        recentTasks={recentTasks}
        recentTaskPreviewUrls={Object.fromEntries(recentTasks.map((task) => [
          task.id,
          task.previewAssetId ? recentPreviewDownloads[task.previewAssetId]?.url : undefined,
        ]))}
        onBusinessSpaceChange={(nextBusinessSpaceId) => {
          setEditingDraft(true)
          setBusinessSpaceId(nextBusinessSpaceId)
          setProductId(undefined)
          setSelectedAssets([])
          setSelectedMaskId(null)
          setMaskFile(undefined)
          clearPlan()
          setSelectedReportId(null)
          setSelectedRecommendationId(null)
          setSelectedTopicId(null)
        }}
        onProductChange={(nextProductId) => {
          setEditingDraft(true)
          setBusinessSpaceId(selectedBusinessSpaceId)
          setProductId(nextProductId)
          clearPlan()
          setSelectedReportId(null)
          setSelectedRecommendationId(null)
          setSelectedTopicId(null)
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
        onOpenImage={(assetId) => void handleOpenImage(assetId)}
        onOpenAssets={(assetId) => navigate(appLinks.assetDetail(assetId))}
        onRetry={retry}
      />
      {assetPickerOpen ? <ImageAssetPickerDialog open title={mode === 'REGION_EDIT' ? '选择局部重绘底图' : '选择本次参考图片'}
        businessSpaceId={selectedBusinessSpaceId} selectedAssetIds={attachmentIds} maxSelection={mode === 'REGION_EDIT' ? 1 : 3}
        onCancel={() => setAssetPickerOpen(false)} onConfirm={(ids) => { updateAttachments(ids); setAssetPickerOpen(false) }} /> : null}
    </UserShell>
  )
}

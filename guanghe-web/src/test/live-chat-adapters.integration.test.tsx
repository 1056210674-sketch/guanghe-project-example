import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type {
  ConversationMessage,
  PaidTask,
  PointAccount,
  TaskDraft,
} from '../api/conversationApi'
import { ChatMessageStream, GeneratedVideoResultPlayer } from '../features/chat'
import {
  embeddedTaskDraftId,
  toChatMessageView,
  toGeneratedImageResultGroup,
  toGeneratedVideoResult,
  toPaidTaskConfirmation,
  toTaskExecutionOutcome,
} from '../features/chat/live/chatAdapters'
import { geoReportDto } from './fixtures/geo'
import { ChatGeoContextCard } from '../features/chat/components/ChatGeoContextCard'
import { readBusinessOutcome } from '../features/chat/live/businessOutcome'

const createdAt = '2026-09-04T10:00:00Z'
const pointAccount: PointAccount = {
  userId: '71',
  availablePoints: 100,
  frozenPoints: 0,
  version: 1,
}
const taskDraft: TaskDraft = {
  id: '41',
  draftSource: 'CHAT',
  taskType: 'IMAGE',
  parameterSchemaVersion: 1,
  status: 'WAITING_CONFIRMATION',
  spaceId: '21',
  productId: '31',
  sourceMode: 'CATALOG_PRODUCT',
  referenceAssetIds: ['81'],
  parameters: {
    requestText: '生成一张主图',
    mode: 'AGENT_ASSISTED',
    agentSummary: '生成方案',
    sourceMode: 'CATALOG_PRODUCT',
    referenceAssetIds: ['81'],
    scene: '桌面',
    aspectRatio: '1:1',
    count: 1,
  },
  warnings: ['临时结果需要及时下载'],
  inputHash: 'a'.repeat(64),
  pricingVersionId: '51',
  quotedPoints: 20,
  confirmationToken: 'opaque-token',
  expiresAt: '2099-01-01T00:00:00Z',
  createdAt,
}

const videoTaskDraft: TaskDraft = {
  ...taskDraft,
  id: '42',
  taskType: 'VIDEO',
  sourceMode: 'CATALOG_PRODUCT',
  referenceAssetIds: ['81'],
  parameters: {
    requestText: '生成一条产品展示视频',
    mode: 'AGENT_ASSISTED',
    agentSummary: '产品在晨光厨房中缓慢旋转',
    sourceMode: 'CATALOG_PRODUCT',
    referenceAssetIds: ['81'],
    scene: '产品在晨光厨房中缓慢旋转',
    style: '写实电影感',
    durationSeconds: 5,
    aspectRatio: '16:9',
    cameraMotion: '缓慢推进',
    resolution: '720P',
    audio: false,
    seed: -1,
    promptExtend: true,
    watermark: false,
  },
}

function message(
  content: Record<string, unknown>,
  role: ConversationMessage['role'] = 'ASSISTANT',
): ConversationMessage {
  return {
    id: '11',
    conversationId: '1',
    role,
    contentType: role === 'USER' ? 'TEXT' : 'INTERACTION',
    content,
    replyToMessageId: null,
    runId: null,
    attachmentAssetIds: [],
    sequence: 1,
    status: 'COMPLETED',
    createdAt,
    updatedAt: createdAt,
  }
}

function paidTask(
  status: PaidTask['status'],
  result: PaidTask['result'] = {},
): PaidTask {
  return {
    id: '61',
    taskType: 'IMAGE',
    status,
    stageCode: status,
    stageLabel: status === 'RUNNING' ? '正在生成图片' : '处理完成',
    spaceId: '21',
    productId: '31',
    quotedPoints: 20,
    settledPoints: status === 'SUCCEEDED' ? 20 : 0,
    frozenPoints: ['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(status) ? 20 : 0,
    progress: null,
    result,
    errorCode: status === 'FAILED' ? 'PROVIDER_UNAVAILABLE' : null,
    createdAt,
    updatedAt: createdAt,
  }
}

const noOpMessageCallbacks = {
  onSelectClarificationOption: vi.fn(),
  onEditPaidTaskConfirmation: vi.fn(),
  onConfirmPaidTaskConfirmation: vi.fn(),
  onOpenRecharge: vi.fn(),
  onPrepareReestimate: vi.fn(),
  onOpenPaidTaskReferenceAsset: vi.fn(),
  onAddPaidTaskReferenceAsset: vi.fn(),
  onViewTask: vi.fn(),
  onPreviewArticle: vi.fn(),
  onContinueEditingArticle: vi.fn(),
  onSaveArticleToAssets: vi.fn(),
  onPreviewGeneratedImage: vi.fn(),
  onDownloadGeneratedImage: vi.fn(),
  onSaveGeneratedImageToAssets: vi.fn(),
  onSelectGeneratedImage: vi.fn(),
  onTaskOutcomeAction: vi.fn(),
}

describe('Java conversation DTO 到 Chat View Model', () => {
  it('资源选择独立于补问；同名候选有范围标签，仅助手的服务端可用卡片可点击', () => {
    const interaction = { interactionType: 'RESOURCE_SELECTION', intent: 'GENERAL', question: '你要查看哪件商品？',
      missingFields: [], choices: ['咖啡', '咖啡'], candidateSetId: '901', available: true,
      candidates: [
        { choiceId: 'first', kind: 'PRODUCT', resourceId: '31', name: '咖啡', spaceId: '21', spaceName: '抖店', hasReport: false, exact: true },
        { choiceId: 'second', kind: 'PRODUCT', resourceId: '32', name: '咖啡', spaceId: '22', spaceName: '拼多多', hasReport: true, exact: true },
      ] }
    const view = toChatMessageView(message({ interaction }))
    expect(view.clarificationOptions).toBeUndefined()
    expect(view.resourceSelection?.candidates).toHaveLength(2)
    const select = vi.fn()
    const mounted = render(<ChatMessageStream messages={[view]} {...noOpMessageCallbacks} onSelectResourceCandidate={select} />)
    fireEvent.click(screen.getByRole('button', { name: '咖啡 商品 · 拼多多' }))
    expect(select).toHaveBeenCalledWith('11', '901', 'second')
    expect(screen.queryByText(/901|choice-/)).not.toBeInTheDocument()
    mounted.rerender(<ChatMessageStream messages={[{ ...view, resourceSelection: { ...view.resourceSelection!, available: false } }]} {...noOpMessageCallbacks} onSelectResourceCandidate={select} />)
    expect(screen.getByRole('button', { name: '咖啡 商品 · 拼多多' })).toBeDisabled()
    expect(screen.getByText(/这次选择已失效/)).toBeVisible()
    expect(toChatMessageView(message({ interaction }, 'USER')).resourceSelection).toBeUndefined()
    expect(toChatMessageView(message({ interaction }, 'SYSTEM_EVENT')).resourceSelection).toBeUndefined()
    mounted.rerender(<ChatMessageStream messages={[{ ...view, role: 'USER' }]} {...noOpMessageCallbacks} onSelectResourceCandidate={select} />)
    expect(screen.queryByRole('region', { name: '选择查询对象' })).not.toBeInTheDocument()
  })

  it('畸形或历史缺少 available 的资源卡不降级为可点击补问；其他历史补问兼容', () => {
    const candidate = { choiceId: 'first', kind: 'GEO_TOPIC', resourceId: '31', name: '咖啡曝光', hasReport: true, exact: false }
    const interaction = { interactionType: 'RESOURCE_SELECTION', question: '选专题', candidateSetId: '901', available: true, choices: ['咖啡曝光'], candidates: [candidate] }
    for (const invalid of [
      { ...interaction, available: undefined },
      { ...interaction, candidateSetId: '../foreign' },
      { ...interaction, candidates: [candidate, candidate] },
      { ...interaction, candidates: [{ ...candidate, kind: 'UNKNOWN' }] },
      { ...interaction, candidates: [{ ...candidate, resourceId: '0' }] },
      { ...interaction, candidates: Array.from({ length: 7 }, (_, index) => ({ ...candidate, choiceId: `c${index}` })) },
    ]) {
      const view = toChatMessageView(message({ interaction: invalid }))
      expect(view.resourceSelection).toBeUndefined()
      expect(view.clarificationOptions).toBeUndefined()
    }
    expect(toChatMessageView(message({ interaction })).resourceSelection?.candidates[0].reportLabel).toBe('已有监测报告')
    expect(toChatMessageView(message({ interaction: { question: '历史补问', choices: ['选项'] } })).clarificationOptions).toHaveLength(1)
  })

  it('业务失败和部分完成只读 Java 结果，历史终态和正文不被猜成成功', () => {
    const outcome = { schemaVersion: 1, status: 'FAILED', reasonCode: 'READONLY_PLAN_INVALID', failureStage: 'PLAN' } as const
    const failed = toChatMessageView(message({ text: '需要重新说明需求', businessOutcome: outcome }))
    expect(failed.businessOutcome).toEqual(outcome)
    const mounted = render(<ChatMessageStream messages={[failed]} {...noOpMessageCallbacks} />)
    expect(screen.getByRole('status')).toHaveTextContent('本次回复未完成')
    expect(screen.queryByText(/READONLY_PLAN_INVALID|PLAN/)).not.toBeInTheDocument()
    mounted.rerender(<ChatMessageStream messages={[toChatMessageView(message({ text: '有用结论，但后半部分失败', businessOutcome: { schemaVersion: 1, status: 'PARTIAL' } }))]} {...noOpMessageCallbacks} />)
    expect(screen.getByRole('status')).toHaveTextContent('本次回复部分完成')
    expect(toChatMessageView(message({ text: '完成了' })).businessOutcome).toBeUndefined()
    expect(toChatMessageView(message({ businessOutcome: outcome }, 'USER')).businessOutcome).toBeUndefined()
    expect(toChatMessageView({ ...message({ businessOutcome: outcome }), status: 'STREAMING' }).businessOutcome).toBeUndefined()
    expect(toChatMessageView({ ...message({ businessOutcome: outcome }), status: 'STOPPED' }).businessOutcome).toBeUndefined()
    expect(readBusinessOutcome({ ...outcome, status: 'COMPLETED' })).toBeUndefined()
    expect(readBusinessOutcome({ ...outcome, schemaVersion: 2 })).toBeUndefined()
    expect(readBusinessOutcome({ ...outcome, reasonCode: 'private response https://internal' })).toBeUndefined()
  })

  it('单任务退回金额仅取任务 reservation 投影，不从报价或 GEO result 推算', () => {
    const task = { ...paidTask('PARTIAL'), quotedPoints: 21, settledPoints: 16, frozenPoints: 0, releasedPoints: 5 }
    expect(toTaskExecutionOutcome(task).settlement).toEqual({ settledPoints: 16, returnedPoints: 5 })
    expect(toTaskExecutionOutcome({ ...task, releasedPoints: undefined }).settlement.returnedPoints).toBeUndefined()
    expect(toTaskExecutionOutcome({ ...task, releasedPoints: 0 }).settlement.returnedPoints).toBe(0)
    expect(toTaskExecutionOutcome({ ...task, settledPoints: null, frozenPoints: null, releasedPoints: null }).settlement).toEqual({ settledPoints: undefined, returnedPoints: undefined })
  })
  it('只接完成助手的结构化 GEO 引用，不从 Markdown/用户输入/失败消息推断报告', () => {
    const geoContext = { status: 'READY', selectionSource: 'AUTO_SINGLE_TOPIC', report: { reportId: '9001', topicId: '5001', spaceId: '4001', productId: '31', title: '实际报告', generatedAt: null, canCreateArticle: true, articleUnavailableReason: null }, topicChoices: [], topicsTruncated: false }
    const assistant = message({ text: '[报告](/app/geo/reports/9999)', geoContext })
    expect(toChatMessageView(assistant).geoContext?.report?.reportId).toBe('9001')
    expect(toChatMessageView(message({ text: '[报告](/app/geo/reports/9999)' })).geoContext).toBeUndefined()
    expect(toChatMessageView({ ...assistant, role: 'USER' }).geoContext).toBeUndefined()
    expect(toChatMessageView({ ...assistant, status: 'FAILED' }).geoContext).toBeUndefined()
    expect(toChatMessageView({ ...assistant, status: 'STREAMING' }).geoContext).toBeUndefined()
    expect(toChatMessageView(message({ geoContext: { ...geoContext, report: { ...geoContext.report, reportId: '../foreign' } } })).geoContext).toBeUndefined()
  })

  it('多专题卡只交回合法专题 ID，忽略 payload href；不可用不是无报告', () => {
    const view = toChatMessageView(message({ geoContext: { status: 'NEEDS_TOPIC', selectionSource: null, report: null,
      topicChoices: [{ topicId: '5001', spaceId: '4001', productId: '31', name: '通勤保温杯', href: 'https://untrusted.example/' }], topicsTruncated: true } }))
    const select = vi.fn()
    const mounted = render(<ChatGeoContextCard context={view.geoContext!} onViewReport={vi.fn()} onPrepareArticle={vi.fn()} onSelectTopic={select} onOpenTopics={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '打开专题：通勤保温杯' }))
    expect(select).toHaveBeenCalledWith('5001')
    expect(screen.getByRole('button', { name: '查看全部专题' })).toBeVisible()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    mounted.rerender(<ChatGeoContextCard context={{ ...view.geoContext!, status: 'NO_REPORT' }} onViewReport={vi.fn()} onPrepareArticle={vi.fn()} onSelectTopic={select} onOpenTopics={vi.fn()} />)
    expect(screen.getByText(/已有专题尚无报告/)).toHaveTextContent('不会重复新建专题')
    expect(screen.getByRole('button', { name: '配置监测：通勤保温杯' })).toBeVisible()
    expect(screen.queryByText(/下方/)).not.toBeInTheDocument()
    mounted.rerender(<ChatGeoContextCard context={{ ...view.geoContext!, status: 'UNAVAILABLE' }} onViewReport={vi.fn()} onPrepareArticle={vi.fn()} onSelectTopic={select} onOpenTopics={vi.fn()} />)
    expect(screen.getByText(/这不代表没有报告/)).toBeVisible()
    expect(screen.queryByRole('button', { name: '准备标题方案' })).not.toBeInTheDocument()
  })

  it('空内容建议不影响已解读有效报告准备文章；归档、未解读、零样本明确禁用', () => {
    const report = geoReportDto('geo-synthesis-v1', { recommendations: [] })
    const task: PaidTask = { ...paidTask('PARTIAL', {
      runId: report.runId, reportId: report.id, status: 'PARTIAL', expectedUnits: 4, succeededUnits: 2,
      failedUnits: 1, missingUnits: 1, settledPoints: 12, releasedPoints: 3,
    }), taskType: 'GEO' }
    const action = (source: typeof report) => toTaskExecutionOutcome(task, source).actions?.find(({ type }) => type === 'CREATE_ARTICLE_FROM_GEO')
    expect(action(report)).toMatchObject({ enabled: true })
    expect(action({ ...report, archivedAt: createdAt })).toMatchObject({ enabled: false, disabledReason: expect.stringContaining('归档') })
    expect(action({ ...report, sampleSize: 0 })).toMatchObject({ enabled: false, disabledReason: expect.stringContaining('有效样本') })
    expect(action(geoReportDto('geo-deterministic-v1'))).toMatchObject({ enabled: false, disabledReason: expect.stringContaining('智能解读') })
    expect(toTaskExecutionOutcome(task, { ...report, id: '999' }).actions?.some(({ type }) => type === 'CREATE_ARTICLE_FROM_GEO')).toBe(false)
  })

  it('用户和助手普通文本由真实 content.text 映射', () => {
    expect(toChatMessageView(message({ text: '用户输入' }, 'USER'))).toMatchObject({
      role: 'USER',
      content: '用户输入',
    })
    expect(toChatMessageView(message({ text: '助手回答' }))).toMatchObject({
      role: 'ASSISTANT',
      content: '助手回答',
    })
  })

  it('interaction.required 映射真实问题和选项，不写入执行逻辑', () => {
    const view = toChatMessageView(
      message({
        text: '请选择一个方向',
        interaction: {
          interactionType: 'CLARIFICATION',
          question: '主要用途是什么？',
          choices: ['商品主图', '生活场景'],
        },
      }),
    )
    expect(view.content).toBe('主要用途是什么？')
    expect(view.guidance).toBe('请选择一个方向')
    expect(view.clarificationOptions?.map(({ title }) => title)).toEqual([
      '商品主图',
      '生活场景',
    ])
  })

  it('历史消息可提取 Java 持久化 taskDraft ID 用于刷新恢复', () => {
    expect(
      embeddedTaskDraftId(message({ text: '确认方案', taskDraft })),
    ).toBe(taskDraft.id)
  })

  it('余额充足时确认卡可确认并保留真实价格、参数、有效期和警告', () => {
    const view = toPaidTaskConfirmation(taskDraft, pointAccount, '真实商品')
    expect(view).toMatchObject({
      id: '41',
      pointsCost: 20,
      pointBalance: 100,
      confirmEnabled: true,
      warningText: '临时结果需要及时下载',
    })
    expect(view.detailRows).toContainEqual({ label: '场景', value: '桌面' })
    expect(view.detailRows.some(({ label }) => label === '确认有效期')).toBe(true)
  })

  it('图片确认展示冻结的电商视觉方案；旧草稿不伪称已完成视觉策划', () => {
    const parameters = taskDraft.parameters
    if (!('count' in parameters)) throw new Error('Expected image fixture')
    const view = toPaidTaskConfirmation({ ...taskDraft, parameters: { ...parameters, visualBrief: {
      version: 'commerce-image-v1', platform: 'PINDUODUO', placement: 'MAIN_IMAGE',
      visualFocus: '杯身占画面主要区域', composition: '前景特写，保留瓶型', background: '明亮撞色背景', lighting: '清晰轮廓光', textPolicy: 'NONE', copyLines: [],
    } } }, pointAccount)
    expect(view.detailRows).toEqual(expect.arrayContaining([
      { label: '视觉用途', value: '拼多多 · 商品主图' },
      { label: '构图方案', value: '前景特写，保留瓶型' },
      { label: '新增文字', value: '不新增文字，保留商品原有包装文字' },
    ]))
    expect(toPaidTaskConfirmation(taskDraft, pointAccount).detailRows.some((row) => row.label === '视觉用途')).toBe(false)
    expect(view.pointsCost).toBe(taskDraft.quotedPoints)
  })

  it('GEO 智能发现草稿直接展示专题、候选问题和 Java 冻结的平台', () => {
    const discoveryDraft: TaskDraft = {
      ...taskDraft,
      id: '43',
      taskType: 'GEO',
      parameters: {
        requestText: '需求词吧',
        mode: 'QUESTION_DISCOVERY',
        agentSummary: '围绕当前商品整理需求词问题',
        topic: '美式咖啡需求词',
        region: '全国',
        questions: [
          '哪些人适合喝美式咖啡？',
          '什么场景适合选择美式咖啡？',
          '购买美式咖啡时关注什么？',
        ],
        platformCodes: ['DOUBAO', 'WENXIN'],
        sampleMode: 'STANDARD',
      },
      quotedPoints: 6,
    }

    const view = toPaidTaskConfirmation(
      discoveryDraft,
      pointAccount,
      '美式咖啡',
    )

    expect(view.detailRows).toContainEqual({
      label: '监测专题',
      value: '美式咖啡需求词',
    })
    expect(view.candidateQuestions).toEqual([
      '哪些人适合喝美式咖啡？',
      '什么场景适合选择美式咖啡？',
      '购买美式咖啡时关注什么？',
    ])
    expect(view.platformNames).toEqual(['豆包', '文心一言'])
    expect(view.confirmEnabled).toBe(true)
  })

  it('积分不足或数值非法时强制禁止确认且不在前端计算差额', () => {
    const insufficient = toPaidTaskConfirmation(taskDraft, {
      ...pointAccount,
      availablePoints: 10,
    })
    const invalid = toPaidTaskConfirmation(
      { ...taskDraft, quotedPoints: Number.NaN },
      pointAccount,
    )
    const unavailable = toPaidTaskConfirmation(taskDraft)
    expect(insufficient.confirmEnabled).toBe(false)
    expect(insufficient.pointsInsufficient?.shortfallPoints).toBeUndefined()
    expect(invalid.confirmEnabled).toBe(false)
    expect(unavailable.confirmEnabled).toBe(false)
    expect(unavailable.pointsInsufficient).toBeUndefined()
  })

  it('自动准入 ADMITTED 草稿不再展示可重复确认动作', () => {
    const view = toPaidTaskConfirmation({ ...taskDraft, status: 'ADMITTED' }, pointAccount)
    expect(view.confirmEnabled).toBe(false)
    expect(view.unavailableReason).toBe('此方案已确认，请在任务中心查看进度，不要重复提交。')
  })

  it('task progress 映射回执和并行状态，终态映射结果且不恢复确认按钮', () => {
    const running = toChatMessageView(message({ text: '' }), {
      task: paidTask('RUNNING'),
      pointAccount,
      productName: '真实商品',
    })
    const failed = toChatMessageView(message({ text: '' }), {
      task: paidTask('FAILED'),
      pointAccount,
    })
    expect(running.taskCreatedReceipt?.taskId).toBe('61')
    expect(running.parallelTaskGroup?.tasks[0].progressLabel).toBe('正在生成图片')
    expect(running.paidTaskConfirmation).toBeUndefined()
    expect(failed.taskExecutionOutcomeGroup?.outcomes[0]).toMatchObject({
      statusLabel: '失败',
      failureReason:
        '执行服务暂时异常，本次任务未完成，请稍后重新发起。',
    })
    expect(JSON.stringify(failed)).not.toContain('PROVIDER_UNAVAILABLE')
  })

  it('GEO 任务展示 Java 返回的真实监测点进度', () => {
    const running = toChatMessageView(message({ text: '' }), {
      task: {
        ...paidTask('RUNNING'),
        taskType: 'GEO',
        stageCode: 'GEO_SAMPLING',
        stageLabel: '正在采集平台回答',
        progress: { percent: 45, completedUnits: 5, totalUnits: 10 },
      },
    })

    expect(running.parallelTaskGroup?.tasks[0]).toMatchObject({
      progressLabel: '正在采集平台回答 · 已处理 5/10 个监测点',
      progressPercent: 45,
    })
  })

  it('图片和视频任务展示各自媒体单位，不把创作进度写成监测点', () => {
    const image = toChatMessageView({ ...message({ text: '' }), id: 'image-progress', sequence: 2 }, {
      task: {
        ...paidTask('RUNNING'),
        id: 'image-task',
        progress: { percent: 50, completedUnits: 2, totalUnits: 4 },
      },
    })
    const video = toChatMessageView({ ...message({ text: '' }), id: 'video-progress', sequence: 3 }, {
      task: {
        ...paidTask('RUNNING'),
        id: 'video-task',
        taskType: 'VIDEO',
        stageCode: 'VIDEO_GENERATING',
        stageLabel: '正在生成视频',
        progress: { percent: 50, completedUnits: 1, totalUnits: 2 },
      },
    })

    render(<ChatMessageStream messages={[image, video]} {...noOpMessageCallbacks} />)

    expect(screen.getByText('正在生成图片 · 已处理 2/4 张图片')).toBeVisible()
    expect(screen.getByText('正在生成视频 · 已处理 1/2 条视频')).toBeVisible()
    expect(screen.queryByText(/监测点/)).not.toBeInTheDocument()
  })

  it('永久资产结果只映射 assetId，短效地址由资产接口展示', () => {
    const task = paidTask('SUCCEEDED', {
      provider: 'QWEN_IMAGE',
      model: 'qwen-image-3.0',
      providerCostReported: false,
      asset: {
        assetId: '90378333853843456',
        assetType: 'IMAGE',
        status: 'READY',
        contentType: 'image/png',
        sizeBytes: 468446,
        sha256: '26bf7e36d039844cebe07b50b9ede918f9e0540aa429cbea952a28f8bf3947f4',
        width: 1024,
        height: 1024,
        safetyStatus: 'NOT_REVIEWED',
      },
    } as unknown as PaidTask['result'])
    const group = toGeneratedImageResultGroup(task, {
      name: '永久生成图片.png',
      imageUrl: 'https://asset.example/result.png',
      expiresAt: '2026-09-05T10:00:00Z',
    })
    expect(group?.images[0]).toMatchObject({
      assetId: '90378333853843456',
      name: '永久生成图片.png',
      imageUrl: 'https://asset.example/result.png',
      previewEnabled: true,
      downloadEnabled: true,
      saveToAssetsEnabled: true,
      persistedAsset: true,
      safetyStatusLabel: '未进行内容审核',
    })
  })

  it('VIDEO Draft 使用真实参数生成确认卡且不引入图片专属字段', () => {
    const view = toPaidTaskConfirmation(videoTaskDraft, pointAccount, '真实商品')

    expect(view.taskType).toBe('视频创作')
    expect(view.title).toBe('视频创作任务确认')
    expect(view.detailRows).toEqual(expect.arrayContaining([
      { label: '场景', value: '产品在晨光厨房中缓慢旋转' },
      { label: '时长', value: '5 秒' },
      { label: '画面比例', value: '16:9' },
      { label: '镜头运动', value: '缓慢推进' },
      { label: '分辨率', value: '720P' },
    ]))
    expect(view.detailRows.some(({ label }) => label === '负面提示词')).toBe(false)
  })

  it('VIDEO 只有永久 assetId 结果就绪后才映射安全播放器', () => {
    const running = { ...paidTask('RUNNING'), taskType: 'VIDEO' as const }
    const completed: PaidTask = {
      ...running,
      status: 'SUCCEEDED',
      stageCode: 'COMPLETED',
      stageLabel: '视频已保存到资产中心',
      result: {
        provider: 'WAN_VIDEO',
        model: 'wan3.0-video',
        providerCostReported: false,
        asset: {
          assetId: 'video-asset-1',
          assetType: 'VIDEO',
          status: 'READY',
          contentType: 'video/mp4',
          sizeBytes: 2048,
          sha256: 'b'.repeat(64),
          width: 1280,
          height: 720,
          durationSeconds: '5.000',
          safetyStatus: 'APPROVED',
        },
      },
    }

    expect(toGeneratedVideoResult(running)).toBeUndefined()
    expect(toGeneratedVideoResult(completed, {
      name: '永久视频',
      videoUrl: 'https://assets.example.test/video.mp4?signature=secret',
      expiresAt: '2099-01-01T00:00:00Z',
    })).toMatchObject({
      assetId: 'video-asset-1',
      name: '永久视频',
      contentType: 'video/mp4',
      persistedAsset: true,
    })
  })

  it('原生播放器只消费当前 HTTPS 签名地址且不会写入持久存储', () => {
    const storageSpy = vi.spyOn(Storage.prototype, 'setItem')
    const onOpenOriginal = vi.fn()
    render(
      <GeneratedVideoResultPlayer
        video={{
          assetId: 'video-asset-1',
          name: '永久视频',
          videoUrl: 'https://assets.example.test/video.mp4?signature=secret',
          expiresAt: '2099-01-01T00:00:00Z',
          persistedAsset: true,
          previewEnabled: true,
        }}
        onOpenOriginal={onOpenOriginal}
      />,
    )

    expect(screen.getByLabelText('播放视频 永久视频')).toHaveAttribute(
      'src',
      'https://assets.example.test/video.mp4?signature=secret',
    )
    expect(storageSpy).not.toHaveBeenCalled()
    storageSpy.mockRestore()
  })

  it('原生播放器加载失败时只重新签发一次并切换到新链接', async () => {
    const onRefreshPlaybackUrl = vi.fn().mockResolvedValue({
      videoUrl: 'https://assets.example.test/video.mp4?signature=fresh',
      expiresAt: '2099-01-02T00:00:00Z',
    })
    render(
      <GeneratedVideoResultPlayer
        video={{
          assetId: 'video-asset-1',
          name: '永久视频',
          videoUrl: 'https://assets.example.test/video.mp4?signature=expired',
          expiresAt: '2099-01-01T00:00:00Z',
          persistedAsset: true,
          previewEnabled: true,
        }}
        onOpenOriginal={vi.fn()}
        onRefreshPlaybackUrl={onRefreshPlaybackUrl}
      />,
    )

    fireEvent.error(screen.getByLabelText('播放视频 永久视频'))

    await waitFor(() => expect(onRefreshPlaybackUrl).toHaveBeenCalledOnce())
    expect(onRefreshPlaybackUrl).toHaveBeenCalledWith('video-asset-1')
    const refreshedPlayer = await screen.findByLabelText('播放视频 永久视频')
    expect(refreshedPlayer).toHaveAttribute(
      'src',
      'https://assets.example.test/video.mp4?signature=fresh',
    )
    fireEvent.error(refreshedPlayer)
    expect(onRefreshPlaybackUrl).toHaveBeenCalledOnce()
    expect(screen.getByText(/2099\/01\/02/)).toBeInTheDocument()
  })

  it('原生播放器发现签名链接已过期时自动重新签发一次', async () => {
    const onRefreshPlaybackUrl = vi.fn().mockResolvedValue({
      videoUrl: 'https://assets.example.test/video.mp4?signature=renewed',
      expiresAt: '2099-01-02T00:00:00Z',
    })
    render(
      <GeneratedVideoResultPlayer
        video={{
          assetId: 'video-asset-1',
          name: '永久视频',
          videoUrl: 'https://assets.example.test/video.mp4?signature=expired',
          expiresAt: '2020-01-01T00:00:00Z',
          persistedAsset: true,
          previewEnabled: true,
        }}
        onOpenOriginal={vi.fn()}
        onRefreshPlaybackUrl={onRefreshPlaybackUrl}
      />,
    )

    await waitFor(() => expect(onRefreshPlaybackUrl).toHaveBeenCalledOnce())
    expect(await screen.findByLabelText('播放视频 永久视频')).toHaveAttribute(
      'src',
      'https://assets.example.test/video.mp4?signature=renewed',
    )
  })

  it('自动刷新失败后允许用户手动重试成功，恢复后不会再次自动循环请求', async () => {
    const onRefreshPlaybackUrl = vi.fn()
      .mockRejectedValueOnce(new Error('temporary signing failure'))
      .mockResolvedValue({
        videoUrl: 'https://assets.example.test/video.mp4?signature=manual-fresh',
        expiresAt: '2099-01-02T00:00:00Z',
      })

    render(
      <GeneratedVideoResultPlayer
        video={{
          assetId: 'video-asset-1',
          name: '永久视频',
          videoUrl: 'https://assets.example.test/video.mp4?signature=expired',
          expiresAt: '2020-01-01T00:00:00Z',
          persistedAsset: true,
          previewEnabled: true,
        }}
        onOpenOriginal={vi.fn()}
        onRefreshPlaybackUrl={onRefreshPlaybackUrl}
      />,
    )

    await waitFor(() => expect(onRefreshPlaybackUrl).toHaveBeenCalledOnce())
    expect(await screen.findByText('视频暂时无法播放')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '重新加载视频' }))

    await waitFor(() => expect(onRefreshPlaybackUrl).toHaveBeenCalledTimes(2))
    const player = await screen.findByLabelText('播放视频 永久视频')
    expect(player).toHaveAttribute(
      'src',
      'https://assets.example.test/video.mp4?signature=manual-fresh',
    )

    fireEvent.error(player)
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '重新加载视频' })).toBeEnabled()
    })
    expect(onRefreshPlaybackUrl).toHaveBeenCalledTimes(2)

    fireEvent.click(screen.getByRole('button', { name: '重新加载视频' }))
    await waitFor(() => expect(onRefreshPlaybackUrl).toHaveBeenCalledTimes(3))
    expect(await screen.findByLabelText('播放视频 永久视频')).toBeInTheDocument()
  })

  it('同一视频资产只自动刷新一次，切换到新资产后恢复一次自动刷新', async () => {
    const onRefreshPlaybackUrl = vi.fn().mockRejectedValue(
      new Error('temporary signing failure'),
    )
    const renderPlayer = (
      assetId: string,
      videoUrl: string,
    ) => (
      <GeneratedVideoResultPlayer
        video={{
          assetId,
          name: `视频 ${assetId}`,
          videoUrl,
          expiresAt: '2020-01-01T00:00:00Z',
          persistedAsset: true,
          previewEnabled: true,
        }}
        onOpenOriginal={vi.fn()}
        onRefreshPlaybackUrl={onRefreshPlaybackUrl}
      />
    )

    const { rerender } = render(
      renderPlayer(
        'video-asset-1',
        'https://assets.example.test/video-1.mp4?signature=expired-1',
      ),
    )
    await waitFor(() => expect(onRefreshPlaybackUrl).toHaveBeenCalledOnce())

    await act(async () => {
      rerender(
        renderPlayer(
          'video-asset-1',
          'https://assets.example.test/video-1.mp4?signature=expired-2',
        ),
      )
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(onRefreshPlaybackUrl).toHaveBeenCalledOnce()

    rerender(
      renderPlayer(
        'video-asset-2',
        'https://assets.example.test/video-2.mp4?signature=expired',
      ),
    )
    await waitFor(() => expect(onRefreshPlaybackUrl).toHaveBeenCalledTimes(2))
    expect(onRefreshPlaybackUrl).toHaveBeenNthCalledWith(1, 'video-asset-1')
    expect(onRefreshPlaybackUrl).toHaveBeenNthCalledWith(2, 'video-asset-2')
  })

  it('旧资产刷新未完成时切换新资产，等待后自动刷新新资产且忽略旧结果', async () => {
    let resolveFirstRefresh: (value: {
      videoUrl: string
      expiresAt: string
    }) => void = () => undefined
    const firstRefresh = new Promise<{
      videoUrl: string
      expiresAt: string
    }>((resolve) => {
      resolveFirstRefresh = resolve
    })
    const onRefreshPlaybackUrl = vi.fn()
      .mockImplementationOnce(() => firstRefresh)
      .mockResolvedValueOnce({
        videoUrl: 'https://assets.example.test/video-2.mp4?signature=fresh',
        expiresAt: '2099-01-02T00:00:00Z',
      })
    const renderPlayer = (assetId: string, videoUrl: string) => (
      <GeneratedVideoResultPlayer
        video={{
          assetId,
          name: `视频 ${assetId}`,
          videoUrl,
          expiresAt: '2020-01-01T00:00:00Z',
          persistedAsset: true,
          previewEnabled: true,
        }}
        onOpenOriginal={vi.fn()}
        onRefreshPlaybackUrl={onRefreshPlaybackUrl}
      />
    )

    const { rerender } = render(
      renderPlayer(
        'video-asset-1',
        'https://assets.example.test/video-1.mp4?signature=expired',
      ),
    )
    await waitFor(() => expect(onRefreshPlaybackUrl).toHaveBeenCalledOnce())

    rerender(
      renderPlayer(
        'video-asset-2',
        'https://assets.example.test/video-2.mp4?signature=expired',
      ),
    )
    expect(onRefreshPlaybackUrl).toHaveBeenCalledOnce()

    await act(async () => {
      resolveFirstRefresh({
        videoUrl: 'https://assets.example.test/video-1.mp4?signature=stale',
        expiresAt: '2099-01-02T00:00:00Z',
      })
      await firstRefresh
    })

    await waitFor(() => expect(onRefreshPlaybackUrl).toHaveBeenCalledTimes(2))
    expect(onRefreshPlaybackUrl).toHaveBeenNthCalledWith(2, 'video-asset-2')
    const player = await screen.findByLabelText('播放视频 视频 video-asset-2')
    expect(player).toHaveAttribute(
      'src',
      'https://assets.example.test/video-2.mp4?signature=fresh',
    )
    expect(screen.queryByText('视频暂时无法播放')).not.toBeInTheDocument()
  })

  it('Java 测试 Provider 结果不会作为真实 Qwen 图片展示', () => {
    const task = paidTask('SUCCEEDED', {
      provider: 'LOCAL_TASK_PROVIDER',
      model: 'deterministic-provider-mock-v1',
      mock: true,
      artifacts: [
        {
          artifactType: 'IMAGE',
          schemaVersion: 1,
          providerUrl: 'https://mock-provider.invalid/result.png',
          contentType: 'image/png',
          metadata: { width: 1024, height: 1024, mock: true },
        },
      ],
    } as unknown as PaidTask['result'])

    expect(toGeneratedImageResultGroup(task)).toBeUndefined()
  })

  it('任务回执优先于付费确认卡，页面没有付费任务取消入口', () => {
    const view = toChatMessageView(message({ text: '' }), {
      paidTaskConfirmation: toPaidTaskConfirmation(taskDraft, pointAccount),
      task: paidTask('QUEUED'),
      pointAccount,
    })
    render(<ChatMessageStream messages={[view]} {...noOpMessageCallbacks} />)
    expect(screen.getByText('任务已创建')).toBeInTheDocument()
    expect(screen.queryByText(/确认开始 · .* 积分/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /取消任务/ })).not.toBeInTheDocument()
  })
})

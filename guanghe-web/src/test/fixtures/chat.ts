import {
  EditOutlined,
  FileImageOutlined,
  PlaySquareOutlined,
  RadarChartOutlined,
} from '@ant-design/icons'
import { createElement } from 'react'
import type {
  ArticleDraftPreviewView,
  ArticleMediaSubmissionDetailsView,
  ChatClarificationOptionView,
  ChatContextOptionView,
  ChatHistoricalContextView,
  ChatMessageView,
  ChatProductOptionView,
  ChatReferenceAssetView,
  ChatRecommendationView,
  ChatSessionView,
  ChatStageTransitionView,
  ChatTaskFocusOptionView,
  ChatTaskStatusSummaryView,
  ChatTaskContextView,
  GeneratedImageResultGroupView,
  ParallelTaskGroupView,
  PaidTaskConfirmationView,
  TaskCreatedReceiptView,
  TaskExecutionOutcomeGroupView,
  VideoStoryboardSceneView,
} from '../../features/chat'

export const chatSessionFixture: ChatSessionView = {
  id: 'chat-session-test-001',
  title: '测试会话',
  preview: '测试会话预览',
  updatedAt: '刚刚',
}

export const chatMessageFixture: ChatMessageView = {
  id: 'chat-message-test-001',
  role: 'USER',
  content: '测试用户消息',
  createdAt: '12:00',
}

export const chatAssistantMessageFixture: ChatMessageView = {
  id: 'chat-message-test-002',
  role: 'ASSISTANT',
  content: '测试光禾AI智能体框架消息',
  createdAt: '12:01',
}

export const articleDraftPreviewFixture: ArticleDraftPreviewView = {
  id: 'article-draft-preview-test-001',
  title: '测试文章草稿标题',
  versionLabel: '测试版本 v1.0',
  coverImageUrl: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=',
  excerpt: '这是一段只用于测试的文章草稿摘要。',
  wordCount: 1286,
  estimatedReadingMinutes: 4,
  generatedAt: '测试生成时间',
  actionsEnabled: true,
}

export const articleDraftMessageFixture: ChatMessageView = {
  id: 'chat-message-article-draft-test-001',
  role: 'ASSISTANT',
  content: '测试文章草稿已经生成。',
  articleDraftPreview: articleDraftPreviewFixture,
}

export const articleMediaSubmissionDetailsFixture: ArticleMediaSubmissionDetailsView =
  {
    articleSnapshot: {
      articleId: 'article-submission-snapshot-test-001',
      title: '测试投稿文章标题',
      versionLabel: '测试投稿版本 v1.0',
      generatedAt: '测试文章生成时间',
    },
    targets: [
      {
        id: 'media-submission-target-test-003',
        name: '测试媒体丙',
        estimatedTimeLabel: '测试预计时效三',
      },
      {
        id: 'media-submission-target-test-001',
        name: '测试媒体甲',
        estimatedTimeLabel: '测试预计时效一',
      },
      {
        id: 'media-submission-target-test-002',
        name: '测试媒体乙',
      },
    ],
  }

export const chatClarificationOptionsFixture: readonly ChatClarificationOptionView[] =
  [
    {
      id: 'clarification-option-monitor',
      title: 'GEO 监测',
      description: '测试监测选项说明',
      icon: createElement(RadarChartOutlined),
    },
    {
      id: 'clarification-option-image',
      title: '生成商品图片',
      description: '测试图片选项说明',
      icon: createElement(FileImageOutlined),
    },
    {
      id: 'clarification-option-video',
      title: '制作商品视频',
      description: '测试视频选项说明',
      icon: createElement(PlaySquareOutlined),
    },
    {
      id: 'clarification-option-article',
      title: '写推广文章',
      description: '测试文章选项说明',
      icon: createElement(EditOutlined),
      disabled: true,
    },
  ]

export const chatClarificationMessageFixture: ChatMessageView = {
  id: 'chat-message-clarification-test-001',
  role: 'ASSISTANT',
  content: '可以。你想先做哪一种？',
  createdAt: '12:02',
  clarificationOptions: chatClarificationOptionsFixture,
  guidance: '选择目标和商品后，我会先给出方案说明。',
}

export const paidTaskReferenceAssetFixture: ChatReferenceAssetView = {
  id: 'paid-task-reference-asset-test-001',
  name: '测试确认卡参考素材',
  thumbnailUrl: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=',
}

export const videoStoryboardScenesFixture: readonly VideoStoryboardSceneView[] =
  [
    {
      id: 'video-storyboard-scene-test-003',
      timeRangeLabel: '10–15 秒',
      title: '测试收尾画面',
      description: '测试第三个传入的分镜描述。',
      previewImageUrl: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=',
    },
    {
      id: 'video-storyboard-scene-test-001',
      timeRangeLabel: '0–5 秒',
      title: '测试开场画面',
      description: '测试第一个时间段但第二个传入的分镜描述。',
    },
    {
      id: 'video-storyboard-scene-test-002',
      timeRangeLabel: '5–10 秒',
      title: '测试过程画面',
      description: '测试第二个时间段但第三个传入的分镜描述。',
    },
  ]

export const paidTaskConfirmationFixture: PaidTaskConfirmationView = {
  id: 'paid-task-confirmation-test-001',
  taskType: 'GEO 监测',
  title: '测试付费任务确认',
  productName: '测试确认商品',
  productImageUrl: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=',
  detailRows: [
    { label: '监测专题', value: '测试监测专题' },
    { label: '执行方式', value: '测试执行方式' },
  ],
  referenceAssets: [paidTaskReferenceAssetFixture],
  referenceAssetEditingEnabled: true,
  candidateQuestions: ['测试候选问题一', '测试候选问题二'],
  platformNames: ['测试平台甲', '测试平台乙'],
  pointsCost: 350,
  pointBalance: 8680,
  warningText: '测试风险提示',
  confirming: false,
  confirmEnabled: true,
}

export const paidTaskMessageFixture: ChatMessageView = {
  id: 'chat-message-paid-task-test-001',
  role: 'ASSISTANT',
  content: '测试确认方案说明',
  paidTaskConfirmation: paidTaskConfirmationFixture,
}

export const taskCreatedReceiptFixture: TaskCreatedReceiptView = {
  id: 'task-created-receipt-test-001',
  taskId: 'task-id-test-001',
  taskNumber: 'TEST-TASK-0001',
  title: '任务已创建',
  taskType: '测试任务类型',
  statusLabel: '测试排队状态',
  statusTone: 'info',
  productName: '测试回执商品',
  productImageUrl: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=',
  version: '测试确认版本',
  frozenPoints: 240,
  pointBalance: 760,
  createdAt: '测试创建时间',
  message: '测试任务已经提交。',
  detailEnabled: true,
}

export const taskCreatedReceiptMessageFixture: ChatMessageView = {
  id: 'chat-message-task-created-test-001',
  role: 'ASSISTANT',
  content: '测试任务创建回执说明',
  taskCreatedReceipt: taskCreatedReceiptFixture,
}

export const parallelTaskGroupFixture: ParallelTaskGroupView = {
  id: 'parallel-task-group-test-001',
  title: '测试多任务并行中',
  tasks: [
    {
      taskId: 'parallel-task-id-test-003',
      taskNumber: 'TEST-PARALLEL-003',
      taskType: '测试监测任务',
      statusLabel: '测试执行中',
      statusTone: 'info',
      productName: '测试并行任务商品甲',
      productImageUrl: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=',
      progressLabel: '测试进度 18/35',
      progressPercent: 51.4,
      timingLabel: '测试预计剩余时间',
      detailEnabled: true,
    },
    {
      taskId: 'parallel-task-id-test-001',
      taskNumber: 'TEST-PARALLEL-001',
      taskType: '测试图片任务',
      statusLabel: '测试已完成',
      statusTone: 'success',
      productName: '测试并行任务商品乙',
      progressLabel: '测试完成 4 张',
      progressPercent: 100,
      timingLabel: '测试完成时间',
      detailEnabled: true,
    },
    {
      taskId: 'parallel-task-id-test-002',
      taskNumber: 'TEST-PARALLEL-002',
      taskType: '测试视频任务',
      statusLabel: '测试排队中',
      statusTone: 'warning',
      progressLabel: '测试等待执行',
      timingLabel: '测试预计开始时间',
      detailEnabled: false,
    },
  ],
}

export const parallelTaskMessageFixture: ChatMessageView = {
  id: 'chat-message-parallel-task-test-001',
  role: 'ASSISTANT',
  content: '测试多个独立任务正在并行处理。',
  parallelTaskGroup: parallelTaskGroupFixture,
}

export const generatedImageResultGroupFixture: GeneratedImageResultGroupView =
  {
    id: 'generated-image-result-group-test-001',
    title: '测试图片创作结果',
    images: [
      {
        assetId: 'generated-image-asset-test-003',
        name: '测试生成图三',
        imageUrl: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=',
        formatLabel: '测试 WEBP',
        dimensionsLabel: '测试 1024 × 1280',
        previewEnabled: true,
        downloadEnabled: true,
        saveToAssetsEnabled: true,
        selectEnabled: true,
      },
      {
        assetId: 'generated-image-asset-test-001',
        name: '测试生成图一',
        formatLabel: '测试 PNG',
        previewEnabled: false,
        downloadEnabled: true,
        saveToAssetsEnabled: false,
        selectEnabled: true,
      },
      {
        assetId: 'generated-image-asset-test-002',
        name: '测试生成图二',
        dimensionsLabel: '测试 1280 × 1024',
        previewEnabled: true,
        downloadEnabled: false,
        saveToAssetsEnabled: true,
        selectEnabled: false,
      },
    ],
    selectedAssetId: 'generated-image-asset-test-001',
  }

export const generatedImageResultMessageFixture: ChatMessageView = {
  id: 'chat-message-generated-image-result-test-001',
  role: 'ASSISTANT',
  content: '测试图片结果已经生成。',
  generatedImageResultGroup: generatedImageResultGroupFixture,
}

export const taskExecutionOutcomeGroupFixture: TaskExecutionOutcomeGroupView =
  {
    id: 'task-execution-outcome-group-test-001',
    outcomes: [
      {
        taskId: 'task-execution-outcome-test-003',
        taskType: '测试 GEO 监测结果',
        statusLabel: '测试部分完成',
        statusTone: 'warning',
        completedAt: '测试完成时间一',
        summary: '测试任务共返回三项结果。',
        failureReason: '测试结果项存在暂时不可用状态。',
        resultItems: [
          {
            id: 'task-outcome-item-test-003',
            label: '测试结果项丙',
            statusLabel: '测试成功',
            statusTone: 'success',
          },
          {
            id: 'task-outcome-item-test-001',
            label: '测试结果项甲',
            statusLabel: '测试失败',
            statusTone: 'danger',
          },
          {
            id: 'task-outcome-item-test-002',
            label: '测试结果项乙',
            statusLabel: '测试等待结算',
            statusTone: 'info',
          },
        ],
        settlement: {
          settledPoints: 300,
          returnedPoints: 50,
        },
        actions: [
          { type: 'PREPARE_RETRY_FAILED_ITEMS', enabled: true },
          { type: 'VIEW_COMPLETED_REPORT', enabled: true },
          { type: 'PREPARE_ADJUST_AND_RETRY', enabled: false },
        ],
      },
      {
        taskId: 'task-execution-outcome-test-001',
        taskType: '测试视频生成结果',
        statusLabel: '测试失败',
        statusTone: 'danger',
        failureReason: '测试任务执行环境暂时不可用。',
        settlement: {
          settledPoints: 0,
          returnedPoints: 450,
        },
        actions: [
          { type: 'CONTACT_SUPPORT', enabled: false },
          { type: 'PREPARE_RETRY', enabled: true },
        ],
      },
    ],
  }

export const taskExecutionOutcomeMessageFixture: ChatMessageView = {
  id: 'chat-message-task-execution-outcome-test-001',
  role: 'ASSISTANT',
  content: '测试任务执行结果已经返回。',
  taskExecutionOutcomeGroup: taskExecutionOutcomeGroupFixture,
}

export const chatStageTransitionFixture: ChatStageTransitionView = {
  previousStage: {
    id: 'chat-stage-previous-test-001',
    label: '测试上一阶段',
    statusLabel: '测试历史状态',
    statusTone: 'success',
  },
  currentStage: {
    id: 'chat-stage-current-test-001',
    label: '测试当前阶段',
    statusLabel: '测试当前状态',
    statusTone: 'warning',
  },
}

export const chatTaskFocusOptionsFixture: readonly ChatTaskFocusOptionView[] =
  [
    {
      taskId: 'chat-task-focus-test-003',
      label: '测试文章草稿',
      statusLabel: '测试编辑中',
    },
    {
      taskId: 'chat-task-focus-test-001',
      label: '测试 GEO 监测',
      statusLabel: '测试执行中',
    },
    {
      taskId: 'chat-task-focus-test-002',
      label: '测试图片创作',
      statusLabel: '测试已完成',
      disabled: true,
    },
    {
      taskId: 'chat-task-focus-test-004',
      label: '测试视频创作',
      statusLabel: '测试排队中',
    },
  ]

export const chatTaskStatusSummariesFixture: readonly ChatTaskStatusSummaryView[] =
  [
    {
      taskId: 'chat-task-focus-test-001',
      label: '测试 GEO 监测',
      statusLabel: '测试执行中 18/35 项',
      statusTone: 'info',
      focusEnabled: true,
    },
    {
      taskId: 'chat-task-focus-test-002',
      label: '测试图片创作',
      statusLabel: '测试已完成 4/4 张',
      statusTone: 'success',
      focusEnabled: false,
    },
    {
      taskId: 'chat-task-focus-test-004',
      label: '测试视频创作',
      statusLabel: '测试排队中',
      statusTone: 'warning',
      focusEnabled: true,
    },
  ]

export const chatCurrentReferenceAssetFixture: ChatReferenceAssetView = {
  id: 'chat-reference-current-test-001',
  name: '测试当前素材',
  thumbnailUrl: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=',
}

export const chatHistoricalReferenceAssetFixture: ChatReferenceAssetView = {
  id: 'chat-reference-historical-test-001',
  name: '测试历史素材',
}

export const chatCurrentTaskContextFixture: ChatTaskContextView = {
  id: 'chat-current-context-test-001',
  title: '测试当前意图',
  detailRows: [{ label: '测试当前参数', value: '测试当前参数值' }],
  referenceAssets: [chatCurrentReferenceAssetFixture],
  sourceLabel: '测试参数来源',
}

export const chatHistoricalTaskContextFixture: ChatHistoricalContextView = {
  id: 'chat-historical-context-test-001',
  title: '测试历史任务',
  statusLabel: '测试历史任务状态',
  summaryRows: [{ label: '测试历史参数', value: '测试历史参数值' }],
  referenceAssets: [chatHistoricalReferenceAssetFixture],
}

export const chatBusinessSpaceFixture: ChatContextOptionView = {
  id: 'chat-space-test-001',
  name: '测试经营空间',
}

export const chatSecondaryBusinessSpaceFixture: ChatContextOptionView = {
  id: 'chat-space-test-002',
  name: '第二测试经营空间',
}

export const chatProductFixture: ChatProductOptionView = {
  id: 'chat-product-test-001',
  businessSpaceId: chatBusinessSpaceFixture.id,
  name: '测试商品',
  category: '测试分类',
  modelOrSpecification: '测试规格 A',
  mainAssetUrl: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=',
  completeness: 92,
}

export const chatAlternativeProductFixture: ChatProductOptionView = {
  id: 'chat-product-test-002',
  businessSpaceId: chatBusinessSpaceFixture.id,
  name: '备选测试商品',
  category: '备选分类',
  modelOrSpecification: '测试规格 B',
  completeness: 76,
}

export const chatSecondarySpaceProductFixture: ChatProductOptionView = {
  id: 'chat-product-test-003',
  businessSpaceId: chatSecondaryBusinessSpaceFixture.id,
  name: '第二空间测试商品',
}

export const chatRecommendationFixture: ChatRecommendationView = {
  id: 'chat-recommendation-test-001',
  title: '测试推荐入口',
  description: '测试推荐入口说明',
  prompt: '测试推荐输入',
  icon: null,
}

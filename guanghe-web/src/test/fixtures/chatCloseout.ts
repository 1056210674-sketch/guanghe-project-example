import type {
  ChatHistoricalContextView,
  ChatMessageView,
  ChatReferenceAssetView,
  GeneratedImageResultGroupView,
  ChatStageTransitionView,
  ChatTaskContextView,
  PointsInsufficientView,
} from '../../features/chat'
import {
  articleDraftPreviewFixture,
  articleMediaSubmissionDetailsFixture,
  generatedImageResultGroupFixture,
  paidTaskConfirmationFixture,
  parallelTaskGroupFixture,
  taskCreatedReceiptFixture,
  taskExecutionOutcomeGroupFixture,
  videoStoryboardScenesFixture,
} from './chat'

// Deliberately differs from 900 - 320 so tests prove the UI trusts Props
// instead of calculating a shortfall in the browser.
export const closeoutPointsInsufficientFixture: PointsInsufficientView = {
  shortfallPoints: 581,
  rechargeEnabled: true,
  reestimateEnabled: true,
}

export const closeoutPointsInsufficientMessageFixture: ChatMessageView = {
  id: 'closeout-points-insufficient-message-test-001',
  role: 'ASSISTANT',
  content: '测试积分不足阻止任务创建。',
  paidTaskConfirmation: {
    ...paidTaskConfirmationFixture,
    id: 'closeout-points-insufficient-card-test-001',
    pointsCost: 900,
    pointBalance: 320,
    pointsInsufficient: closeoutPointsInsufficientFixture,
    confirmEnabled: true,
  },
}

export const closeoutNewUserConfirmationId =
  'closeout-new-user-confirmation-test-001'

export const closeoutNewUserMessagesFixture: readonly ChatMessageView[] = [
  {
    id: 'closeout-new-user-message-test-001',
    role: 'USER',
    content: '测试新用户提出模糊创作请求。',
  },
  {
    id: 'closeout-new-user-clarification-test-001',
    role: 'ASSISTANT',
    content: '测试系统补问本次创作目标。',
    clarificationOptions: [
      {
        id: 'closeout-new-user-option-test-001',
        title: '测试图片创作方向',
        description: '测试补问选项说明。',
        icon: null,
      },
    ],
    guidance: '测试补问后的说明文字。',
  },
  {
    id: 'closeout-new-user-confirmation-message-test-001',
    role: 'ASSISTANT',
    content: '测试付费方案等待用户确认。',
    paidTaskConfirmation: {
      ...paidTaskConfirmationFixture,
      id: closeoutNewUserConfirmationId,
      taskType: '测试首次图片创作',
      title: '测试首次付费任务确认',
    },
  },
]

export const closeoutGeoConfirmationId = 'closeout-geo-confirmation-test-001'
export const closeoutGeoTaskId = 'closeout-geo-task-test-001'

export const closeoutGeoMessagesFixture: readonly ChatMessageView[] = [
  {
    id: 'closeout-geo-clarification-test-001',
    role: 'ASSISTANT',
    content: '测试补问监测范围和平台。',
    clarificationOptions: [
      {
        id: 'closeout-geo-scope-option-test-002',
        title: '测试监测问题范围',
        description: '测试问题范围说明。',
        icon: null,
      },
      {
        id: 'closeout-geo-platform-option-test-001',
        title: '测试监测平台范围',
        description: '测试平台范围说明。',
        icon: null,
      },
    ],
  },
  {
    id: 'closeout-geo-confirmation-message-test-001',
    role: 'ASSISTANT',
    content: '测试 GEO 方案等待确认。',
    paidTaskConfirmation: {
      ...paidTaskConfirmationFixture,
      id: closeoutGeoConfirmationId,
      taskType: '测试 GEO 监测',
      title: '测试 GEO 付费确认',
      candidateQuestions: ['测试问题乙', '测试问题甲'],
      platformNames: ['测试平台丙', '测试平台甲', '测试平台乙'],
    },
  },
  {
    id: 'closeout-geo-receipt-message-test-001',
    role: 'ASSISTANT',
    content: '测试 GEO 唯一任务创建回执。',
    taskCreatedReceipt: {
      ...taskCreatedReceiptFixture,
      id: 'closeout-geo-receipt-test-001',
      taskId: closeoutGeoTaskId,
      taskNumber: 'CLOSEOUT-GEO-TEST-001',
      taskType: '测试 GEO 监测',
    },
  },
  {
    id: 'closeout-geo-parallel-message-test-001',
    role: 'ASSISTANT',
    content: '测试 GEO 平台并行状态。',
    parallelTaskGroup: {
      ...parallelTaskGroupFixture,
      id: 'closeout-geo-parallel-group-test-001',
      title: '测试 GEO 多平台并行中',
      tasks: [
        {
          ...parallelTaskGroupFixture.tasks[2],
          taskId: 'closeout-geo-platform-task-test-003',
          taskNumber: 'CLOSEOUT-GEO-PLATFORM-003',
          taskType: '测试平台丙监测',
        },
        {
          ...parallelTaskGroupFixture.tasks[0],
          taskId: 'closeout-geo-platform-task-test-001',
          taskNumber: 'CLOSEOUT-GEO-PLATFORM-001',
          taskType: '测试平台甲监测',
        },
      ],
    },
  },
  {
    id: 'closeout-geo-outcome-message-test-001',
    role: 'ASSISTANT',
    content: '测试 GEO 部分成功与失败结算。',
    taskExecutionOutcomeGroup: {
      id: 'closeout-geo-outcome-group-test-001',
      outcomes: [
        {
          ...taskExecutionOutcomeGroupFixture.outcomes[0],
          taskId: closeoutGeoTaskId,
          taskType: '测试 GEO 部分完成结果',
          actions: [
            { type: 'PREPARE_RETRY_FAILED_ITEMS', enabled: true },
          ],
        },
      ],
    },
  },
]

export const closeoutImageConfirmationId =
  'closeout-image-confirmation-test-001'
export const closeoutVideoConfirmationId =
  'closeout-video-confirmation-test-001'
export const closeoutSelectedGeneratedAssetId =
  'closeout-selected-generated-asset-test-001'

export const closeoutSelectedImageAssetFixture: ChatReferenceAssetView = {
  id: closeoutSelectedGeneratedAssetId,
  name: '测试当前选中的生成图片',
  thumbnailUrl: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=',
}

export const closeoutHistoricalAssetFixture: ChatReferenceAssetView = {
  id: 'closeout-historical-asset-test-001',
  name: '测试历史任务素材（不得继承）',
}

export const closeoutImageConfirmationMessagesFixture: readonly ChatMessageView[] =
  [
    {
      id: 'closeout-image-confirmation-message-test-001',
      role: 'ASSISTANT',
      content: '测试图片任务独立确认。',
      paidTaskConfirmation: {
        ...paidTaskConfirmationFixture,
        id: closeoutImageConfirmationId,
        taskType: '测试图片生成',
        title: '测试图片任务确认',
      },
    },
  ]

const closeoutSelectedImageResultGroup: GeneratedImageResultGroupView = {
  ...generatedImageResultGroupFixture,
  id: 'closeout-generated-image-group-test-001',
  images: [
    {
      ...generatedImageResultGroupFixture.images[1],
      assetId: closeoutSelectedGeneratedAssetId,
      name: closeoutSelectedImageAssetFixture.name,
    },
    {
      ...generatedImageResultGroupFixture.images[0],
      assetId: 'closeout-generated-asset-test-002',
      name: '测试另一张生成图片',
    },
  ],
  selectedAssetId: closeoutSelectedGeneratedAssetId,
}

const closeoutSelectedImageResultMessage: ChatMessageView = {
  id: 'closeout-generated-image-message-test-001',
  role: 'ASSISTANT',
  content: '测试图片结果等待显式选择。',
  generatedImageResultGroup: closeoutSelectedImageResultGroup,
}

export const closeoutImageToVideoMessagesFixture: readonly ChatMessageView[] = [
  closeoutSelectedImageResultMessage,
  {
    id: 'closeout-video-confirmation-message-test-001',
    role: 'ASSISTANT',
    content: '测试视频任务独立确认。',
    paidTaskConfirmation: {
      ...paidTaskConfirmationFixture,
      id: closeoutVideoConfirmationId,
      taskType: '测试视频生成',
      title: '测试视频任务确认',
      referenceAssets: [closeoutSelectedImageAssetFixture],
      videoStoryboardScenes: videoStoryboardScenesFixture,
    },
  },
]

export const closeoutImageSelectionPendingMessagesFixture: readonly ChatMessageView[] =
  [
    {
      ...closeoutSelectedImageResultMessage,
      generatedImageResultGroup: {
        ...closeoutSelectedImageResultGroup,
        selectedAssetId: undefined,
      },
    },
  ]

export const closeoutImageCurrentContextFixture: ChatTaskContextView = {
  id: 'closeout-image-current-context-test-001',
  title: '测试当前视频任务',
  detailRows: [{ label: '测试当前参数', value: '测试视频参数' }],
  referenceAssets: [closeoutSelectedImageAssetFixture],
  sourceLabel: '测试当前任务显式选择',
}

export const closeoutImageHistoricalContextFixture: ChatHistoricalContextView =
  {
    id: 'closeout-image-history-context-test-001',
    title: '测试历史图片任务',
    statusLabel: '测试历史已完成',
    summaryRows: [{ label: '测试历史参数', value: '测试历史图片参数' }],
    referenceAssets: [closeoutHistoricalAssetFixture],
  }

export const closeoutImageStageTransitionFixture: ChatStageTransitionView = {
  previousStage: {
    id: 'closeout-stage-image-test-001',
    label: '测试图片创作',
    statusLabel: '测试已完成',
    statusTone: 'success',
  },
  currentStage: {
    id: 'closeout-stage-video-test-001',
    label: '测试视频创作',
    statusLabel: '测试待确认',
    statusTone: 'warning',
  },
}

export const closeoutArticleConfirmationId =
  'closeout-article-confirmation-test-001'

export const closeoutArticleMessagesFixture: readonly ChatMessageView[] = [
  {
    id: 'closeout-article-draft-message-test-001',
    role: 'ASSISTANT',
    content: '测试文章草稿预览。',
    articleDraftPreview: {
      ...articleDraftPreviewFixture,
      id: 'closeout-article-draft-test-001',
      title: '测试待投稿文章',
    },
  },
  {
    id: 'closeout-article-confirmation-message-test-001',
    role: 'ASSISTANT',
    content: '测试投稿明细等待不可逆确认。',
    paidTaskConfirmation: {
      ...paidTaskConfirmationFixture,
      id: closeoutArticleConfirmationId,
      taskType: '测试文章媒体投稿',
      title: '测试投稿任务确认',
      mediaSubmissionDetails: {
        ...articleMediaSubmissionDetailsFixture,
        articleSnapshot: {
          ...articleMediaSubmissionDetailsFixture.articleSnapshot,
          articleId: 'closeout-article-snapshot-test-001',
          title: '测试待投稿文章',
        },
      },
      detailRows: [
        { label: '对外价格', value: '测试用户可见报价' },
        { label: '投稿方式', value: '测试异步投稿' },
      ],
      pointsCost: 570,
      pointBalance: 1000,
    },
  },
  {
    id: 'closeout-article-receipt-message-test-001',
    role: 'ASSISTANT',
    content: '测试投稿任务创建回执。',
    taskCreatedReceipt: {
      ...taskCreatedReceiptFixture,
      id: 'closeout-article-receipt-test-001',
      taskId: 'closeout-article-task-test-001',
      taskNumber: 'CLOSEOUT-ARTICLE-TEST-001',
      taskType: '测试文章媒体投稿',
    },
  },
  {
    id: 'closeout-article-outcome-message-test-001',
    role: 'ASSISTANT',
    content: '测试投稿执行结果。',
    taskExecutionOutcomeGroup: {
      id: 'closeout-article-outcome-group-test-001',
      outcomes: [
        {
          ...taskExecutionOutcomeGroupFixture.outcomes[0],
          taskId: 'closeout-article-task-test-001',
          taskType: '测试投稿结果',
        },
      ],
    },
  },
]

export const closeoutPriorityMessageFixture: ChatMessageView = {
  id: 'closeout-priority-message-test-001',
  role: 'ASSISTANT',
  content: '测试确认、回执和结果的显示优先级。',
  paidTaskConfirmation: {
    ...paidTaskConfirmationFixture,
    id: 'closeout-priority-confirmation-test-001',
    pointsInsufficient: closeoutPointsInsufficientFixture,
  },
  taskCreatedReceipt: {
    ...taskCreatedReceiptFixture,
    id: 'closeout-priority-receipt-test-001',
    taskId: 'closeout-priority-task-test-001',
  },
  taskExecutionOutcomeGroup: {
    id: 'closeout-priority-outcome-group-test-001',
    outcomes: [
      {
        ...taskExecutionOutcomeGroupFixture.outcomes[0],
        taskId: 'closeout-priority-task-test-001',
      },
    ],
  },
}

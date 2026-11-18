import type {
  ChatContextOptionView,
  ChatMessageView,
  ChatProductOptionView,
  ChatSessionView,
  ChatTaskFocusOptionView,
  ChatTaskStatusSummaryView,
} from '../../features/chat'

export const EMPTY_CHAT_SESSIONS: readonly ChatSessionView[] = []
export const EMPTY_CHAT_MESSAGES: readonly ChatMessageView[] = []
export const EMPTY_CHAT_CONTEXT_OPTIONS: readonly ChatContextOptionView[] = []
export const EMPTY_CHAT_PRODUCT_OPTIONS: readonly ChatProductOptionView[] = []
export const EMPTY_CHAT_TASK_FOCUS_OPTIONS: readonly ChatTaskFocusOptionView[] =
  []
export const EMPTY_CHAT_TASK_STATUS_SUMMARIES: readonly ChatTaskStatusSummaryView[] =
  []

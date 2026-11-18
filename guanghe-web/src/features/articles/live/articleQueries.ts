import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createArticle,
  createArticleSubmissionSnapshot,
  confirmArticleBody,
  confirmArticleCreativePlan,
  confirmArticleTitle,
  deleteArticle,
  getArticle,
  getArticleSubmissionSnapshot,
  listArticles,
  replaceArticleAssets,
  replaceArticleOutline,
  saveArticleCreativePlan,
  updateArticle,
} from '../../../api/articleApi'
import type {
  ArticleId,
  ArticleListQuery,
  ConfirmArticleCreativePlanRequest,
  ConfirmArticleBodyRequest,
  ConfirmArticleTitleRequest,
  CreateArticleRequest,
  ReplaceArticleAssetsRequest,
  ReplaceArticleOutlineRequest,
  SaveArticleCreativePlanRequest,
  UpdateArticleRequest,
} from '../../../api/articleApi'
import { useAuthStore } from '../../../stores/authStore'

function createArticleKeys(ownerId?: string | null) {
  const owner = ['articles', 'owner', ownerId ?? null] as const
  return {
    owner,
    lists: () => [...owner, 'list'] as const,
    list: (query: ArticleListQuery) => [...owner, 'list', query] as const,
    detail: (articleId?: ArticleId) =>
      [...owner, 'detail', articleId ?? null] as const,
    snapshot: (articleId?: ArticleId, snapshotId?: ArticleId) =>
      [...owner, 'snapshot', articleId ?? null, snapshotId ?? null] as const,
  }
}

export const articleKeys = {
  all: ['articles'] as const,
  forOwner: createArticleKeys,
}

function useArticleKeys() {
  return createArticleKeys(useAuthStore((state) => state.user?.id))
}

export function useArticles(query: ArticleListQuery = {}) {
  const keys = useArticleKeys()
  return useQuery({
    queryKey: keys.list(query),
    queryFn: () => listArticles(query),
  })
}

export function useArticle(articleId?: ArticleId) {
  const keys = useArticleKeys()
  return useQuery({
    queryKey: keys.detail(articleId),
    queryFn: () => getArticle(articleId as ArticleId),
    enabled: Boolean(articleId),
  })
}

function useArticleMutationCache() {
  const queryClient = useQueryClient()
  const keys = useArticleKeys()
  const store = async (article: Awaited<ReturnType<typeof getArticle>>) => {
    queryClient.setQueryData(keys.detail(article.id), article)
    await queryClient.invalidateQueries({ queryKey: keys.lists() })
  }
  return { queryClient, keys, store }
}

export function useCreateArticle() {
  const { store } = useArticleMutationCache()
  return useMutation({ mutationFn: createArticle, onSuccess: store })
}

export interface UpdateArticleVariables {
  articleId: ArticleId
  request: UpdateArticleRequest
}

export function useUpdateArticle() {
  const { store } = useArticleMutationCache()
  return useMutation({
    mutationFn: ({ articleId, request }: UpdateArticleVariables) =>
      updateArticle(articleId, request),
    onSuccess: store,
  })
}

export interface ReplaceOutlineVariables {
  articleId: ArticleId
  request: ReplaceArticleOutlineRequest
}

export function useReplaceArticleOutline() {
  const { store } = useArticleMutationCache()
  return useMutation({
    mutationFn: ({ articleId, request }: ReplaceOutlineVariables) =>
      replaceArticleOutline(articleId, request),
    onSuccess: store,
  })
}

export interface ReplaceAssetsVariables {
  articleId: ArticleId
  request: ReplaceArticleAssetsRequest
}

export function useReplaceArticleAssets() {
  const { store } = useArticleMutationCache()
  return useMutation({
    mutationFn: ({ articleId, request }: ReplaceAssetsVariables) =>
      replaceArticleAssets(articleId, request),
    onSuccess: store,
  })
}

export interface ConfirmArticleTitleVariables {
  articleId: ArticleId
  request: ConfirmArticleTitleRequest
}

export function useConfirmArticleTitle() {
  const { store } = useArticleMutationCache()
  return useMutation({
    mutationFn: ({ articleId, request }: ConfirmArticleTitleVariables) =>
      confirmArticleTitle(articleId, request),
    onSuccess: store,
  })
}

export interface SaveArticleCreativePlanVariables {
  articleId: ArticleId
  request: SaveArticleCreativePlanRequest
}

export function useSaveArticleCreativePlan() {
  const { store } = useArticleMutationCache()
  return useMutation({
    mutationFn: ({ articleId, request }: SaveArticleCreativePlanVariables) =>
      saveArticleCreativePlan(articleId, request),
    onSuccess: store,
  })
}

export interface ConfirmArticleCreativePlanVariables {
  articleId: ArticleId
  request: ConfirmArticleCreativePlanRequest
}

export function useConfirmArticleCreativePlan() {
  const { store } = useArticleMutationCache()
  return useMutation({
    mutationFn: ({ articleId, request }: ConfirmArticleCreativePlanVariables) =>
      confirmArticleCreativePlan(articleId, request),
    onSuccess: store,
  })
}

export interface ConfirmArticleBodyVariables {
  articleId: ArticleId
  request: ConfirmArticleBodyRequest
}

export function useConfirmArticleBody() {
  const { store } = useArticleMutationCache()
  return useMutation({
    mutationFn: ({ articleId, request }: ConfirmArticleBodyVariables) =>
      confirmArticleBody(articleId, request),
    onSuccess: store,
  })
}

export function useDeleteArticle() {
  const { queryClient, keys } = useArticleMutationCache()
  return useMutation({
    mutationFn: deleteArticle,
    onSuccess: async (_, articleId) => {
      queryClient.removeQueries({ queryKey: keys.detail(articleId) })
      await queryClient.invalidateQueries({ queryKey: keys.lists() })
    },
  })
}

export interface CreateSnapshotVariables {
  articleId: ArticleId
  articleVersion: number
}

export function useCreateArticleSnapshot() {
  const queryClient = useQueryClient()
  const keys = useArticleKeys()
  return useMutation({
    mutationFn: ({ articleId, articleVersion }: CreateSnapshotVariables) =>
      createArticleSubmissionSnapshot(articleId, { articleVersion }),
    onSuccess: (snapshot) => {
      queryClient.setQueryData(
        keys.snapshot(snapshot.articleId, snapshot.id),
        snapshot,
      )
    },
  })
}

export function useArticleSnapshot(articleId?: ArticleId, snapshotId?: ArticleId) {
  const keys = useArticleKeys()
  return useQuery({
    queryKey: keys.snapshot(articleId, snapshotId),
    queryFn: () =>
      getArticleSubmissionSnapshot(articleId as ArticleId, snapshotId as ArticleId),
    enabled: Boolean(articleId && snapshotId),
  })
}

export type { CreateArticleRequest }

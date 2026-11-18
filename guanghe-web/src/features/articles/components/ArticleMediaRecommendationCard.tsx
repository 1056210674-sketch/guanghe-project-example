import {
  GlobalOutlined,
  ReloadOutlined,
  SendOutlined,
  StarFilled,
} from '@ant-design/icons'
import { Alert, Button, Card, Checkbox, Empty, Tag, Typography } from 'antd'
import { useState } from 'react'
import { formatSaleAmount } from '../live/mediaAdapters'
import { mediaImageFeeModeLabel } from '../live/mediaContentPolicy'
import type {
  ArticleMediaRecommendationSetView,
  ArticleRecommendedSubmissionSelection,
  ArticleViewError,
} from '../types'

export interface ArticleMediaRecommendationCardProps {
  recommendations?: ArticleMediaRecommendationSetView
  loading?: boolean
  submitting?: boolean
  submissionBlocked?: boolean
  imageUnsupported?: boolean
  imageCount?: number
  hasExternalLinks?: boolean
  error?: ArticleViewError | null
  onConfirmSubmission: (
    selection: ArticleRecommendedSubmissionSelection,
  ) => void
  onRefresh: () => void
  onOpenFullSelection?: () => void
}

function recommendationKey(
  recommendations?: ArticleMediaRecommendationSetView,
  hasExternalLinks = false,
  imageCount = 0,
) {
  if (!recommendations) return 'empty'
  return [
    recommendations.articleId,
    recommendations.articleVersion,
    hasExternalLinks,
    imageCount,
    ...recommendations.items.map(
      (item) =>
        `${item.resource.id}:${item.resource.chargedPoints ?? 'invalid'}:${item.resource.quoteExpiresAt}:${item.resource.supportsLinks}:${item.resource.supportsImages}:${JSON.stringify(item.resource.articlePricing)}`,
    ),
  ].join('|')
}

export function ArticleMediaRecommendationCard(
  props: ArticleMediaRecommendationCardProps,
) {
  const { recommendations } = props
  const imageCount = props.imageCount ?? (props.imageUnsupported ? 1 : 0)
  const hasImages = imageCount > 0 || props.imageUnsupported === true
  const supportsArticleImages = (resource: ArticleMediaRecommendationSetView['items'][number]['resource']) =>
    imageCount > 0 && resource.supportsImages === true && (resource.maxImageCount ?? 0) >= imageCount &&
    resource.articlePricing?.imageCount === imageCount && resource.articlePricing.totalPoints === resource.chargedPoints
  const currentKey = recommendationKey(recommendations, props.hasExternalLinks, imageCount)
  const initialIds =
    recommendations?.items
      .filter(
        (item) =>
          item.resource.orderable &&
          (!props.hasExternalLinks || item.resource.supportsLinks) &&
          (!hasImages || supportsArticleImages(item.resource)) &&
          item.resource.chargedPoints !== undefined,
      )
      .map((item) => item.resource.id) ?? []
  const [selectionState, setSelectionState] = useState<{
    key: string
    ids: string[]
  }>({ key: currentKey, ids: initialIds })
  const selectedIds =
    selectionState.key === currentKey ? selectionState.ids : initialIds
  const selected = new Set(selectedIds)
  const selectedItems =
    recommendations?.items.filter((item) => selected.has(item.resource.id)) ?? []
  const selectionValid = selectedItems.every(
    (item) =>
      item.resource.orderable &&
      (!props.hasExternalLinks || item.resource.supportsLinks) &&
      (!hasImages || supportsArticleImages(item.resource)) &&
      item.resource.chargedPoints !== undefined,
  )
  const expectedPoints = selectedItems.reduce(
    (total, item) => total + (item.resource.chargedPoints ?? 0),
    0,
  )
  const submissionUnavailable = recommendations?.submissionEnabled === false
  const imageUnavailable = hasImages && !recommendations?.items.some((item) => supportsArticleImages(item.resource))

  const toggle = (resourceId: string) => {
    if (props.submitting || props.submissionBlocked ||
      (hasImages && !recommendations?.items.some((item) => item.resource.id === resourceId && supportsArticleImages(item.resource))) ||
      (props.hasExternalLinks && recommendations?.items.find(
        (item) => item.resource.id === resourceId,
      )?.resource.supportsLinks === false)) return
    setSelectionState({
      key: currentKey,
      ids: selected.has(resourceId)
        ? selectedIds.filter((id) => id !== resourceId)
        : [...selectedIds, resourceId],
    })
  }

  return (
    <Card
      className="article-media-recommendation"
      title="推荐投稿媒体"
      extra={
        <Button
          type="text"
          icon={<ReloadOutlined aria-hidden="true" />}
          disabled={props.loading || props.submitting}
          onClick={props.onRefresh}
        >
          刷新推荐
        </Button>
      }
    >
      <Typography.Paragraph type="secondary">
        根据当前文章和投稿要求，从 {recommendations?.strategy.candidateCount ?? 0} 条可用媒体中为你推荐。请核对媒体和费用，确认后才会投稿。
      </Typography.Paragraph>
      <Typography.Paragraph>当前文章：{imageCount} 张配图；投稿使用当前已保存版本。</Typography.Paragraph>

      {recommendations ? (
        <div className="article-media-recommendation__strategy">
          {recommendations.strategy.detectedCategories.map((category) => (
            <Tag key={category}>识别品类：{category}</Tag>
          ))}
          {recommendations.strategy.preferredRegion ? (
            <Tag>地域：{recommendations.strategy.preferredRegion}</Tag>
          ) : null}
          {recommendations.strategy.maxTotalPoints ? (
            <Tag>组合预算：{recommendations.strategy.maxTotalPoints} 积分</Tag>
          ) : null}
          {recommendations.strategy.requiresLink ? <Tag>必须带链</Tag> : null}
        </div>
      ) : null}

      {props.error ? (
        <Alert
          type="warning"
          showIcon
          title={props.error.message}
          description={
            props.error.conflict
              ? '正文版本、媒体状态或价格已经变化，请刷新推荐后再次明确确认。'
              : undefined
          }
        />
      ) : null}

      {props.loading ? (
        <div className="article-media-recommendation__loading">
          正在获取可投稿媒体…
        </div>
      ) : !recommendations?.items.length ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={hasImages
            ? '当前没有符合这篇含图稿件要求的已核验渠道，稿件和图片可继续保存在资产中心。'
            : '没有媒体同时满足当前地域、带链和组合预算约束，请调整投稿目标后重试'}
        />
      ) : (
        <ul className="article-media-recommendation__list">
          {recommendations.items.map((item) => {
            const { resource } = item
            const checked = selected.has(resource.id)
            return (
              <li
                key={resource.id}
                className={checked ? 'is-selected' : undefined}
              >
                <Checkbox
                  checked={checked}
                  disabled={
                    !resource.orderable ||
                    (props.hasExternalLinks && !resource.supportsLinks) ||
                    (hasImages && !supportsArticleImages(resource)) ||
                    props.submitting ||
                    props.submissionBlocked
                  }
                  aria-label={`选择投稿媒体 ${resource.name}`}
                  onChange={() => toggle(resource.id)}
                />
                <span className="article-media-recommendation__rank">
                  {item.rank}
                </span>
                <div className="article-media-recommendation__copy">
                  <div>
                    <GlobalOutlined aria-hidden="true" />
                    <strong>{resource.name}</strong>
                    {resource.resourceType ? (
                      <Tag>{resource.resourceType}</Tag>
                    ) : null}
                    {resource.region ? <Tag>{resource.region}</Tag> : null}
                    <Tag color={resource.supportsLinks ? 'success' : 'default'}>
                      {resource.supportsLinks ? '可带外链' : '不支持外链'}
                    </Tag>
                    {hasImages ? <Tag color={supportsArticleImages(resource) ? 'success' : 'default'}>
                      {supportsArticleImages(resource) ? `${mediaImageFeeModeLabel(resource.articlePricing?.imageFeeMode)} · 最多 ${resource.maxImageCount} 张` : '图片费用或规则待核验'}
                    </Tag> : null}
                  </div>
                  <Typography.Text>{item.reason}</Typography.Text>
                  <div>
                    <Tag color="blue">匹配分 {item.score}</Tag>
                    {item.evidence.map((evidence) => (
                      <Tag key={evidence}>{evidence}</Tag>
                    ))}
                  </div>
                  <Typography.Text type="secondary">
                    {resource.estimatedTimeLabel ?? '时效待媒体确认'} · 本稿总价{' '}
                    {hasImages && !supportsArticleImages(resource) ? '待核验' : resource.saleAmountLabel ?? '—'} · 报价有效至{' '}
                    {resource.quoteExpiresAt}
                  </Typography.Text>
                  {resource.articlePricing ? <Typography.Paragraph type="secondary">
                    基础费用 {formatSaleAmount(resource.articlePricing.baseSaleAmountCents)} / {resource.articlePricing.basePoints} 积分；
                    图片附加费 {formatSaleAmount(resource.articlePricing.imageFeeAmountCents)} / {resource.articlePricing.imageFeePoints} 积分
                    （{resource.articlePricing.imageCount} 张，{mediaImageFeeModeLabel(resource.articlePricing.imageFeeMode)}，已计入总价）。
                    {resource.articlePricing.description}
                  </Typography.Paragraph> : hasImages ? <Typography.Paragraph type="warning">尚无本稿图片费用明细，不能按免费处理或确认投稿。</Typography.Paragraph> : null}
                </div>
                <strong className="article-media-recommendation__points">
                  {resource.chargedPoints === undefined || (hasImages && !supportsArticleImages(resource)) ? (
                    '暂无可用报价'
                  ) : (
                    <><StarFilled aria-hidden="true" /> {resource.chargedPoints} 积分</>
                  )}
                </strong>
              </li>
            )
          })}
        </ul>
      )}

      {recommendations?.items.length ? (
        <div className="article-media-recommendation__summary">
          <div>
            <Typography.Text strong>
              已选择 {selectedItems.length} 家媒体
            </Typography.Text>
            <Typography.Text type="secondary">
              {selectionValid ? `预计合计 ${expectedPoints} 积分（含已核定的图片费用）` : '费用信息不完整，请刷新推荐'}
            </Typography.Text>
          </div>
          <div className="article-media-recommendation__actions">
            {props.onOpenFullSelection ? (
              <Button
                disabled={props.submitting}
                onClick={props.onOpenFullSelection}
              >
                调整媒体 / 查看投稿状态
              </Button>
            ) : null}
            <Button
              type="primary"
              icon={<SendOutlined aria-hidden="true" />}
              loading={props.submitting}
              disabled={
                props.loading ||
                props.submitting ||
                props.submissionBlocked ||
                submissionUnavailable ||
                imageUnavailable ||
                selectedItems.length === 0 ||
                !selectionValid
              }
              onClick={() =>
                recommendations &&
                props.onConfirmSubmission({
                  articleId: recommendations.articleId,
                  articleVersion: recommendations.articleVersion,
                  mediaResourceIds: selectedIds,
                  expectedPoints,
                  ...(selectedItems.every((item) => item.resource.articlePricing) ? {
                    expectedPricing: selectedItems.map((item) => ({ mediaResourceId: item.resource.id, pricing: item.resource.articlePricing! })),
                  } : {}),
                })
              }
            >
              确认投稿（{expectedPoints} 积分）
            </Button>
          </div>
        </div>
      ) : null}

      {props.hasExternalLinks ? (
        <Alert
          className="article-media-recommendation__notice"
          type="warning"
          showIcon
          title="当前正文含外链"
          description="只能选择标记为“可带外链”的媒体；不支持外链的媒体已不可选。也可以移除正文外链并保存新版本后刷新推荐。"
        />
      ) : null}

      {hasImages ? <Alert
        className="article-media-recommendation__notice"
        type={imageUnavailable ? 'warning' : 'info'}
        showIcon
        title={imageUnavailable ? '这篇文章含图，图文渠道尚未开放' : '图片费用已计入本稿报价'}
        description={imageUnavailable
          ? '当前没有满足图片数量及明确费用规则的媒体。未知图片费用不按免费处理；请保留含图稿件，可继续编辑，现在不会提交或扣分。'
          : '请核对各媒体的免费 / 按张 / 按篇规则及本稿总价。标题、段落和图片随当前已保存版本一并投稿，只有确认投稿才会下单。'}
      /> : null}

    </Card>
  )
}

import {
  PictureOutlined,
  PlusOutlined,
  WarningOutlined,
} from '@ant-design/icons'
import { Button, Tag, Typography } from 'antd'
import { useState } from 'react'
import type {
  ChatReferenceAssetView,
  PaidTaskConfirmationView,
} from '../types'
import { ArticleMediaSubmissionDetails } from './ArticleMediaSubmissionDetails'
import { ChatReferenceAssetButton } from './ChatReferenceAssetButton'
import { VideoStoryboardDraft } from './VideoStoryboardDraft'
import { creativeSourceLabel } from '../creativeGrounding'

const REQUIRED_SETTLEMENT_WARNING =
  '确认提交后任务不可取消，最终按实际成功结果结算'

export interface PaidTaskConfirmationCardProps {
  confirmation: PaidTaskConfirmationView
  onEdit: (cardId: string) => void
  onConfirm: (cardId: string) => void
  onOpenRecharge: (cardId: string) => void
  onPrepareReestimate: (cardId: string) => void
  onOpenPaidTaskReferenceAsset: (cardId: string, assetId: string) => void
  onAddPaidTaskReferenceAsset: (cardId: string) => void
}

function isValidPoints(value: number) {
  return Number.isSafeInteger(value) && value >= 0
}

function formatIntegerPoints(value: number) {
  return isValidPoints(value) ? value.toLocaleString('zh-CN') : '—'
}

function formatPoints(value?: number) {
  return value !== undefined && isValidPoints(value)
    ? `${value.toLocaleString('zh-CN')} 积分`
    : '—'
}

function ConfirmationProductImage({
  name,
  src,
}: {
  name?: string
  src?: string
}) {
  const [loadFailed, setLoadFailed] = useState(false)
  const accessibleName = name ?? '关联商品'

  if (!src || loadFailed) {
    return (
      <span
        className="paid-task-confirmation__image-placeholder"
        role="img"
        aria-label={`${accessibleName}暂无商品图片`}
      >
        <PictureOutlined aria-hidden="true" />
      </span>
    )
  }

  return (
    <img
      className="paid-task-confirmation__image"
      src={src}
      alt={`${accessibleName}商品图片`}
      width={56}
      height={56}
      loading="lazy"
      onError={() => setLoadFailed(true)}
    />
  )
}

function ConfirmationReferenceAssets({
  cardId,
  assets,
  editingEnabled,
  confirming,
  onOpenPaidTaskReferenceAsset,
  onAddPaidTaskReferenceAsset,
}: {
  cardId: string
  assets: readonly ChatReferenceAssetView[]
  editingEnabled: boolean
  confirming: boolean
  onOpenPaidTaskReferenceAsset: (cardId: string, assetId: string) => void
  onAddPaidTaskReferenceAsset: (cardId: string) => void
}) {
  return (
    <div
      className="paid-task-confirmation__section paid-task-confirmation__reference-section"
      aria-label="参考素材"
    >
      <Typography.Text type="secondary">参考素材</Typography.Text>

      {assets.length === 0 ? (
        <div className="paid-task-confirmation__reference-empty" role="status">
          <PictureOutlined aria-hidden="true" />
          <span>暂无参考素材</span>
        </div>
      ) : null}

      {assets.length > 0 || editingEnabled ? (
        <div className="chat-reference-assets paid-task-confirmation__reference-assets">
          {assets.map((asset) => (
            <ChatReferenceAssetButton
              key={`${asset.id}-${asset.thumbnailUrl ?? 'no-image'}`}
              asset={asset}
              onOpen={(assetId) =>
                onOpenPaidTaskReferenceAsset(cardId, assetId)
              }
            />
          ))}
          {editingEnabled ? (
            <Button
              className="paid-task-confirmation__reference-add"
              icon={<PlusOutlined aria-hidden="true" />}
              disabled={confirming}
              onClick={() => {
                if (!confirming) {
                  onAddPaidTaskReferenceAsset(cardId)
                }
              }}
            >
              添加参考素材
            </Button>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}

export function PaidTaskConfirmationCard({
  confirmation,
  onEdit,
  onConfirm,
  onOpenRecharge,
  onPrepareReestimate,
  onOpenPaidTaskReferenceAsset,
  onAddPaidTaskReferenceAsset,
}: PaidTaskConfirmationCardProps) {
  const {
    id,
    taskType,
    title,
    creativeSourceMode,
    productName,
    productImageUrl,
    detailRows,
    referenceAssets = [],
    referenceAssetEditingEnabled = false,
    videoStoryboardScenes,
    mediaSubmissionDetails,
    candidateQuestions,
    platformNames,
    pointsCost,
    pointBalance,
    pointsInsufficient,
    warningText,
    confirming,
    confirmEnabled,
    unavailableReason,
    editDisabledReason,
  } = confirmation
  const pointsAreValid =
    isValidPoints(pointsCost) &&
    (pointBalance === undefined || isValidPoints(pointBalance))
  const canConfirm =
    confirmEnabled &&
    pointsAreValid &&
    !confirming &&
    !unavailableReason &&
    pointsInsufficient === undefined
  const hasVideoStoryboard = Boolean(videoStoryboardScenes?.length)
  const hasLeadingReferenceSection =
    hasVideoStoryboard || mediaSubmissionDetails !== undefined

  return (
    <section
      className="paid-task-confirmation"
      data-chat-draft-id={id}
      aria-label={`付费任务确认卡：${title}`}
      aria-busy={confirming}
    >
      <header className="paid-task-confirmation__header">
        <Typography.Title level={3}>{title}</Typography.Title>
        <Tag>{taskType}</Tag>
      </header>

      {unavailableReason ? (
        <div className="paid-task-confirmation__warning" role="status" aria-label="方案当前状态">
          <WarningOutlined aria-hidden="true" />
          <span>{unavailableReason}</span>
        </div>
      ) : null}

      {!unavailableReason && editDisabledReason ? (
        <div className="paid-task-confirmation__warning" role="note" aria-label="方案修改限制">
          <span>{editDisabledReason}</span>
        </div>
      ) : null}

      {creativeSourceMode ? (
        <section
          className="paid-task-confirmation__creative-source"
          aria-label="创作依据"
        >
          <Typography.Text strong>
            创作依据：
            {creativeSourceLabel(creativeSourceMode)}
          </Typography.Text>
          {creativeSourceMode === 'CURRENT_ATTACHMENTS' &&
          confirmation.rightSideProductOverridden ? (
            <Typography.Text type="warning">
              本次将以附件为准，不使用右侧商品资料。
            </Typography.Text>
          ) : null}
        </section>
      ) : null}

      {productName || productImageUrl ? (
        <div className="paid-task-confirmation__product">
          <ConfirmationProductImage
            key={productImageUrl ?? 'no-image'}
            name={productName}
            src={productImageUrl}
          />
          <div>
            <Typography.Text type="secondary">关联商品</Typography.Text>
            {productName ? (
              <Typography.Text strong>{productName}</Typography.Text>
            ) : null}
          </div>
        </div>
      ) : null}

      {hasLeadingReferenceSection ? (
        <ConfirmationReferenceAssets
          cardId={id}
          assets={referenceAssets}
          editingEnabled={referenceAssetEditingEnabled && !unavailableReason}
          confirming={confirming}
          onOpenPaidTaskReferenceAsset={onOpenPaidTaskReferenceAsset}
          onAddPaidTaskReferenceAsset={onAddPaidTaskReferenceAsset}
        />
      ) : null}

      {mediaSubmissionDetails ? (
        <ArticleMediaSubmissionDetails details={mediaSubmissionDetails} />
      ) : null}

      {hasVideoStoryboard ? (
        <VideoStoryboardDraft scenes={videoStoryboardScenes ?? []} />
      ) : null}

      {detailRows.length > 0 ? (
        <dl className="paid-task-confirmation__details">
          {detailRows.map((row, index) => (
            <div key={`${index}-${row.label}-${row.value}`}>
              <dt>{row.label}</dt>
              <dd>{row.value}</dd>
            </div>
          ))}
        </dl>
      ) : null}

      {!hasLeadingReferenceSection ? (
        <ConfirmationReferenceAssets
          cardId={id}
          assets={referenceAssets}
          editingEnabled={referenceAssetEditingEnabled && !unavailableReason}
          confirming={confirming}
          onOpenPaidTaskReferenceAsset={onOpenPaidTaskReferenceAsset}
          onAddPaidTaskReferenceAsset={onAddPaidTaskReferenceAsset}
        />
      ) : null}

      {candidateQuestions?.length ? (
        <div className="paid-task-confirmation__section">
          <Typography.Text type="secondary">候选问题</Typography.Text>
          <ol className="paid-task-confirmation__questions">
            {candidateQuestions.map((question, index) => (
              <li key={`${index}-${question}`}>{question}</li>
            ))}
          </ol>
        </div>
      ) : null}

      {mediaSubmissionDetails === undefined && platformNames?.length ? (
        <div className="paid-task-confirmation__section">
          <Typography.Text type="secondary">目标平台</Typography.Text>
          <div className="paid-task-confirmation__platforms">
            {platformNames.map((platformName, index) => (
              <Tag key={`${index}-${platformName}`}>{platformName}</Tag>
            ))}
          </div>
        </div>
      ) : null}

      {pointsInsufficient && !unavailableReason ? (
        <section
          className="paid-task-confirmation__points-insufficient"
          role="alert"
          aria-label="积分不足"
        >
          <div
            id={`paid-task-confirmation-insufficient-${id}`}
            className="paid-task-confirmation__points-insufficient-title"
          >
            <WarningOutlined aria-hidden="true" />
            <strong>积分不足，任务尚未创建，也未扣除积分</strong>
          </div>
          <dl className="paid-task-confirmation__points paid-task-confirmation__points--insufficient">
            <div>
              <dt>预计消耗积分</dt>
              <dd>{formatPoints(pointsCost)}</dd>
            </div>
            <div>
              <dt>当前可用积分</dt>
              <dd>{formatPoints(pointBalance)}</dd>
            </div>
            <div>
              <dt>尚缺积分</dt>
              <dd>{formatPoints(pointsInsufficient.shortfallPoints)}</dd>
            </div>
          </dl>

          <div className="paid-task-confirmation__actions paid-task-confirmation__actions--insufficient">
            <Button
              block
              disabled={!pointsInsufficient.rechargeEnabled || confirming}
              onClick={() => {
                if (pointsInsufficient.rechargeEnabled && !confirming) {
                  onOpenRecharge(id)
                }
              }}
            >
              去充值
            </Button>
            <Button
              block
              disabled={!pointsInsufficient.reestimateEnabled || confirming || Boolean(editDisabledReason)}
              onClick={() => {
                if (pointsInsufficient.reestimateEnabled && !confirming && !editDisabledReason) {
                  onPrepareReestimate(id)
                }
              }}
            >
              调整方案重新估价
            </Button>
            <div className="paid-task-confirmation__insufficient-confirm-wrap">
              <Button
                block
                className="paid-task-confirmation__insufficient-confirm"
                type="primary"
                aria-label={confirming ? '正在提交' : `确认开始 · ${formatIntegerPoints(pointsCost)} 积分`}
                aria-describedby={`paid-task-confirmation-insufficient-${id}`}
                loading={confirming}
                disabled
              >
                确认开始 · {formatIntegerPoints(pointsCost)} 积分
              </Button>
              <Typography.Text type="secondary">
                积分不足，暂不可用
              </Typography.Text>
            </div>
          </div>
        </section>
      ) : (
        <dl className="paid-task-confirmation__points">
          <div>
            <dt>预计消耗积分</dt>
            <dd>{formatIntegerPoints(pointsCost)} 积分</dd>
          </div>
          {pointBalance !== undefined ? (
            <div>
              <dt>当前积分余额</dt>
              <dd>{formatIntegerPoints(pointBalance)} 积分</dd>
            </div>
          ) : null}
        </dl>
      )}

      {!unavailableReason && (pointsInsufficient === undefined || warningText) ? (
        <div
          className="paid-task-confirmation__warning"
          role="note"
          aria-label="任务确认提示"
        >
          <WarningOutlined aria-hidden="true" />
          <div>
            {warningText ? <span>{warningText}</span> : null}
            {pointsInsufficient === undefined &&
            warningText !== REQUIRED_SETTLEMENT_WARNING ? (
              <strong>{REQUIRED_SETTLEMENT_WARNING}</strong>
            ) : null}
          </div>
        </div>
      ) : null}

      {pointsInsufficient && !unavailableReason ? null : (
        <div className="paid-task-confirmation__actions">
          <Button
            block
            aria-label="返回修改"
            disabled={confirming || Boolean(unavailableReason) || Boolean(editDisabledReason)}
            onClick={() => {
              if (!confirming && !unavailableReason && !editDisabledReason) {
                onEdit(id)
              }
            }}
          >
            返回修改
          </Button>
          <Button
            block
            type="primary"
            aria-label={confirming ? '正在提交' : `确认开始 · ${formatIntegerPoints(pointsCost)} 积分`}
            loading={confirming}
            disabled={!canConfirm}
            onClick={() => {
              if (canConfirm) {
                onConfirm(id)
              }
            }}
          >
            确认开始 · {formatIntegerPoints(pointsCost)} 积分
          </Button>
        </div>
      )}
    </section>
  )
}

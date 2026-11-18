import {
  ExportOutlined,
  LinkOutlined,
  VideoCameraOutlined,
} from '@ant-design/icons'
import { Alert, Button, Typography } from 'antd'
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from 'react'
import type {
  GeneratedVideoPlaybackSourceView,
  GeneratedVideoResultView,
} from '../types'

const PLAYBACK_URL_EXPIRY_MARGIN_MS = 30_000

type PlaybackRefreshResult =
  | 'refreshed'
  | 'failed'
  | 'busy'
  | 'already-attempted'
  | 'stale'

export interface GeneratedVideoResultPlayerProps {
  video: GeneratedVideoResultView
  onOpenOriginal: (assetId: string) => void
  onRefreshPlaybackUrl?: (
    assetId: string,
  ) => Promise<GeneratedVideoPlaybackSourceView>
  onAssociateToProduct?: (assetId: string) => void
}

function formatExpiry(value?: string): string | undefined {
  if (!value) return undefined
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

function usableHttpsUrl(value?: string): value is string {
  if (!value) return false
  try {
    return new URL(value).protocol === 'https:'
  } catch {
    return false
  }
}

export function GeneratedVideoResultPlayer({
  video,
  onOpenOriginal,
  onRefreshPlaybackUrl,
  onAssociateToProduct = () => undefined,
}: GeneratedVideoResultPlayerProps) {
  const sourceKey = `${video.assetId}:${video.videoUrl ?? ''}:${video.expiresAt ?? ''}`
  const [refreshedSource, setRefreshedSource] = useState<
    (GeneratedVideoPlaybackSourceView & { sourceKey: string }) | undefined
  >()
  const [failedPlaybackSource, setFailedPlaybackSource] = useState<string>()
  const [refreshing, setRefreshing] = useState(false)
  const [playbackRevision, setPlaybackRevision] = useState(0)
  const refreshInFlight = useRef(false)
  const automaticallyRefreshedAssetIds = useRef(new Set<string>())
  const currentSourceKey = useRef(sourceKey)
  useLayoutEffect(() => {
    currentSourceKey.current = sourceKey
  }, [sourceKey])
  const playbackUrl =
    refreshedSource?.sourceKey === sourceKey
      ? refreshedSource.videoUrl
      : video.videoUrl
  const playbackExpiresAt =
    refreshedSource?.sourceKey === sourceKey
      ? refreshedSource.expiresAt
      : video.expiresAt
  const playbackSourceId = playbackUrl ?? `missing:${video.assetId}`
  const loadFailed = failedPlaybackSource === playbackSourceId
  const playable = usableHttpsUrl(playbackUrl) && !loadFailed

  const refreshPlaybackUrl = useCallback(
    async (
      reason: 'missing' | 'expiring' | 'load-error' | 'manual',
    ): Promise<PlaybackRefreshResult> => {
      if (!onRefreshPlaybackUrl) return 'failed'
      if (currentSourceKey.current !== sourceKey) return 'stale'
      if (refreshInFlight.current) return 'busy'
      if (
        reason !== 'manual' &&
        automaticallyRefreshedAssetIds.current.has(video.assetId)
      ) {
        return 'already-attempted'
      }

      if (reason !== 'manual') {
        automaticallyRefreshedAssetIds.current.add(video.assetId)
      }
      refreshInFlight.current = true
      setRefreshing(true)
      try {
        const refreshed = await onRefreshPlaybackUrl(video.assetId)
        if (currentSourceKey.current !== sourceKey) return 'stale'
        if (!usableHttpsUrl(refreshed.videoUrl)) return 'failed'
        setRefreshedSource({ ...refreshed, sourceKey })
        setPlaybackRevision((current) => current + 1)
        setFailedPlaybackSource(undefined)
        return 'refreshed'
      } catch {
        return currentSourceKey.current === sourceKey ? 'failed' : 'stale'
      } finally {
        refreshInFlight.current = false
        setRefreshing(false)
      }
    },
    [onRefreshPlaybackUrl, sourceKey, video.assetId],
  )

  useEffect(() => {
    if (!onRefreshPlaybackUrl || refreshing || refreshInFlight.current) return
    const expiry = playbackExpiresAt ? Date.parse(playbackExpiresAt) : Number.NaN
    const reason = !usableHttpsUrl(playbackUrl)
      ? 'missing'
      : Number.isFinite(expiry) &&
          expiry - Date.now() <= PLAYBACK_URL_EXPIRY_MARGIN_MS
        ? 'expiring'
        : undefined
    if (!reason) return

    const requestedSourceKey = sourceKey
    void Promise.resolve().then(() =>
      refreshPlaybackUrl(reason).then((result) => {
        if (
          currentSourceKey.current === requestedSourceKey &&
          (result === 'failed' || result === 'already-attempted')
        ) {
          setFailedPlaybackSource(playbackSourceId)
        }
      }),
    )
  }, [
    onRefreshPlaybackUrl,
    playbackExpiresAt,
    playbackSourceId,
    playbackUrl,
    refreshPlaybackUrl,
    refreshing,
    sourceKey,
  ])

  const handlePlaybackError = () => {
    const requestedSourceKey = sourceKey
    void refreshPlaybackUrl('load-error').then((result) => {
      if (
        currentSourceKey.current === requestedSourceKey &&
        (result === 'failed' || result === 'already-attempted')
      ) {
        setFailedPlaybackSource(playbackSourceId)
      }
    })
  }

  const handleManualReload = () => {
    const requestedSourceKey = sourceKey
    void refreshPlaybackUrl('manual').then((result) => {
      if (
        currentSourceKey.current === requestedSourceKey &&
        result === 'failed'
      ) {
        setFailedPlaybackSource(playbackSourceId)
      }
    })
  }

  return (
    <section className="generated-video-result" aria-label="视频生成结果">
      <header className="generated-video-result__header">
        <div>
          <Typography.Title level={5}>{video.name}</Typography.Title>
          <Typography.Text type="secondary">
            {[video.contentType, video.dimensionsLabel, video.durationLabel]
              .filter(Boolean)
              .join(' · ') || '永久视频资产'}
          </Typography.Text>
        </div>
        {video.safetyStatusLabel ? <span>{video.safetyStatusLabel}</span> : null}
      </header>

      <div className="generated-video-result__player">
        {playable ? (
          <video
            key={`${playbackUrl}:${playbackRevision}`}
            aria-label={`播放视频 ${video.name}`}
            src={playbackUrl}
            controls
            controlsList="nodownload"
            preload="metadata"
            playsInline
            onError={handlePlaybackError}
          />
        ) : (
          <div className="generated-video-result__placeholder">
            <VideoCameraOutlined />
            <span>
              {refreshing
                ? '正在重新获取视频链接…'
                : loadFailed
                  ? '视频暂时无法播放'
                  : '视频链接暂未获取'}
            </span>
          </div>
        )}
      </div>

      {playbackExpiresAt ? (
        <Alert
          type="info"
          showIcon
          title={`临时播放链接有效期至 ${formatExpiry(playbackExpiresAt)}`}
          description="链接过期后可重新获取，视频已永久保存到资产中心。"
        />
      ) : null}

      <div className="generated-video-result__actions">
        {!playable && onRefreshPlaybackUrl ? (
          <Button
            loading={refreshing}
            disabled={refreshing}
            onClick={handleManualReload}
          >
            重新加载视频
          </Button>
        ) : null}
        <Button
          icon={<ExportOutlined />}
          disabled={!video.previewEnabled}
          onClick={() => onOpenOriginal(video.assetId)}
        >
          打开原视频
        </Button>
        {video.creativeSourceMode === 'CURRENT_ATTACHMENTS' ? (
          <Button
            icon={<LinkOutlined />}
            disabled={!video.productAssociationEnabled}
            title={video.productAssociationDisabledReason}
            onClick={() => {
              if (video.productAssociationEnabled) {
                onAssociateToProduct(video.assetId)
              }
            }}
          >
            关联到商品
          </Button>
        ) : null}
      </div>
    </section>
  )
}

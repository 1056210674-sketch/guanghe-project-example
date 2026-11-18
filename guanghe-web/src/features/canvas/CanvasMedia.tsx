import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons'
import { Button, Spin } from 'antd'
import { useState } from 'react'
import { createAssetDownloadUrl } from '../../api/assetApi'
import { useAssetDetailsState, useAssetDownloadUrls } from '../assets/live/assetQueries'
import { openExternalHttps } from '../shared/openExternalHttps'

/** Existing owner-scoped asset queries refresh expiring URLs; no URL is persisted in the document. */
export function CanvasMedia({ assetId, name, download = false }: { assetId: string; name: string; download?: boolean }) {
  const detail = useAssetDetailsState([assetId])
  const urls = useAssetDownloadUrls([assetId])
  const [override, setOverride] = useState<string>()
  const [failed, setFailed] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState<string>()
  const video = detail.data[assetId]?.assetType === 'VIDEO'
  const url = override ?? urls[assetId]?.url
  const refresh = async () => {
    setRefreshing(true); setError(undefined)
    try { setOverride((await createAssetDownloadUrl(assetId)).url); setFailed(false) }
    catch { setError('素材暂时无法读取。'); setFailed(true) }
    finally { setRefreshing(false) }
  }
  const open = async () => {
    try { openExternalHttps((await createAssetDownloadUrl(assetId)).url) }
    catch { setError('下载链接获取失败，请重试。') }
  }
  return <div className="canvas-media nodrag nowheel">
    {failed || detail.error ? <Button size="small" icon={<ReloadOutlined />} loading={refreshing} onClick={() => void refresh()}>重新读取</Button>
      : !url ? <Spin size="small" /> : video ? <video key={url} src={url} controls preload="metadata" onError={() => setFailed(true)} onPlay={event => { document.querySelectorAll<HTMLVideoElement>('.canvas-workspace video').forEach(other => { if (other !== event.currentTarget) other.pause() }) }} />
        : <img src={url} alt={name} loading="lazy" draggable={false} onError={() => setFailed(true)} />}
    {download ? <Button size="small" type="text" icon={<DownloadOutlined />} onClick={() => void open()}>原文件</Button> : null}
    {error ? <small role="alert">{error}</small> : null}
  </div>
}

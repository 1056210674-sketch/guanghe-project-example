import { PictureOutlined } from '@ant-design/icons'
import { Typography } from 'antd'
import { useState } from 'react'
import type { VideoStoryboardSceneView } from '../types'

export interface VideoStoryboardDraftProps {
  scenes: readonly VideoStoryboardSceneView[]
}

function StoryboardScenePreview({
  scene,
}: {
  scene: VideoStoryboardSceneView
}) {
  const [loadFailed, setLoadFailed] = useState(false)

  if (!scene.previewImageUrl || loadFailed) {
    return (
      <span
        className="video-storyboard-draft__preview-placeholder"
        role="img"
        aria-label={`${scene.title}暂无分镜预览图`}
      >
        <PictureOutlined aria-hidden="true" />
      </span>
    )
  }

  return (
    <img
      className="video-storyboard-draft__preview"
      src={scene.previewImageUrl}
      alt={`${scene.title}分镜预览`}
      width={144}
      height={80}
      loading="lazy"
      onError={() => setLoadFailed(true)}
    />
  )
}

export function VideoStoryboardDraft({ scenes }: VideoStoryboardDraftProps) {
  if (scenes.length === 0) {
    return null
  }

  return (
    <section className="video-storyboard-draft" aria-label="视频分镜草稿">
      <Typography.Text strong>视频分镜草稿</Typography.Text>
      <ol className="video-storyboard-draft__scenes">
        {scenes.map((scene) => (
          <li key={scene.id} className="video-storyboard-draft__scene">
            <div className="video-storyboard-draft__copy">
              <Typography.Text className="video-storyboard-draft__scene-title">
                <strong>{scene.timeRangeLabel}</strong>
                <span aria-hidden="true">｜</span>
                <strong>{scene.title}</strong>
              </Typography.Text>
              <Typography.Paragraph type="secondary">
                {scene.description}
              </Typography.Paragraph>
            </div>
            <StoryboardScenePreview
              key={`${scene.id}-${scene.previewImageUrl ?? 'no-image'}`}
              scene={scene}
            />
          </li>
        ))}
      </ol>
    </section>
  )
}

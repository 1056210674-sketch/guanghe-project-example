export function PublishedMediaLinks({
  urls,
  published = false,
}: {
  urls: readonly string[]
  published?: boolean
}) {
  if (urls.length === 0) {
    return published ? (
      <div className="media-published-links" aria-label="发布回链">
        <span>已发布，上游暂未提供可访问链接</span>
      </div>
    ) : null
  }

  return (
    <div className="media-published-links" aria-label="发布回链">
      <span>发布链接（{urls.length}）</span>
      <ul>
        {urls.map((url, index) => (
          <li key={url}>
            <a href={url} target="_blank" rel="noopener noreferrer" title={url}>
              打开发布页面 {index + 1}
            </a>
          </li>
        ))}
      </ul>
    </div>
  )
}

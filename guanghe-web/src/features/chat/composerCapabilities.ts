export const REPORT_ARTICLE_PROMPT = '根据这份监测报告，结合项目资料写一篇适合发布的完整品牌文章。缺少关键事实时先问我，写完后推荐适合的媒体，投稿前由我确认。'

export type ComposerCreationCapability = 'article.generate' | 'image.generate' | 'video.generate'

export interface ComposerPrefillContext {
  draft: string
  hasAttachments: boolean
  hasReport: boolean
  canRecommendMedia: boolean
}

export interface ComposerPrefill {
  prompt?: string
  notice?: string
}

/** These shortcuts only prepare editable input; they never send a message or create a task. */
export function creationPrefill(capability: ComposerCreationCapability, context: ComposerPrefillContext): ComposerPrefill {
  if (capability === 'article.generate') {
    if (context.draft.trim() || context.hasAttachments) {
      return { notice: '输入内容已保留。直接说明要写的文章，发送后即可继续。' }
    }
    const prompt = context.hasReport
      ? REPORT_ARTICLE_PROMPT
      : '结合项目资料，帮我写一篇完整的品牌文章。缺少关键事实时先问我，文章写完后推荐适合的媒体，投稿前由我确认。'
    return { prompt: context.canRecommendMedia ? prompt : prompt.replace('文章写完后推荐适合的媒体，', '').replace('写完后推荐适合的媒体，', '') }
  }
  if (context.draft.trim()) {
    return { notice: capability === 'image.generate' ? '输入内容已保留，直接发送图片创作要求即可。' : '输入内容已保留，直接发送视频创作要求即可。' }
  }
  return { prompt: capability === 'image.generate'
    ? context.hasAttachments ? '根据我上传的参考图片，生成一张图片：' : '帮我生成一张图片：'
    : context.hasAttachments ? '根据我上传的参考图片，生成一段视频：' : '帮我生成一段视频：' }
}

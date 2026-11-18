import ReactMarkdown, { type Options } from 'react-markdown'
import remarkGfm from 'remark-gfm'

export interface MarkdownMessageContentProps {
  content: string
  remarkPlugins?: Options['remarkPlugins']
}

/**
 * Renders model-authored Markdown without allowing raw HTML or remote inline
 * images. Generated media is rendered by the typed result components instead.
 */
export function MarkdownMessageContent({
  content,
  remarkPlugins,
}: MarkdownMessageContentProps) {
  return (
    <div className="chat-message-markdown">
      <ReactMarkdown
        remarkPlugins={[remarkGfm, ...(remarkPlugins ?? [])]}
        skipHtml
        disallowedElements={['img']}
        components={{
          a: ({ children, ...props }) => (
            <a {...props} target="_blank" rel="noopener noreferrer">
              {children}
            </a>
          ),
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}

/** Reading-only spacing for old report summaries; it never rewrites their text. */
const LONG_PARAGRAPH_LENGTH = 260
const PREFERRED_PARAGRAPH_LENGTH = 180
const PREFERRED_MAX_LENGTH = 220
const MIN_BREAK_LENGTH = 100
const SHORT_TAIL_LENGTH = 80

const closingMarks: Readonly<Record<string, string>> = {
  '“': '”',
  '‘': '’',
  '「': '」',
  '『': '』',
  '《': '》',
  '（': '）',
  '(': ')',
  '【': '】',
  '[': ']',
  '{': '}',
}

/**
 * Split only at complete Chinese sentence boundaries outside quotations/brackets.
 * Keep whitespace byte-for-byte, including a trailing fragment. Existing line
 * breaks are intentional author formatting and are not rearranged. English
 * punctuation is deliberately excluded: dots and query marks may be in URLs,
 * decimals or abbreviations. A sentence without a safe boundary stays intact.
 */
export function splitReportParagraph(text: string): string[] {
  if (text.length < LONG_PARAGRAPH_LENGTH || /[\r\n]/u.test(text)) return [text]

  const sentences: string[] = []
  const expectedClosers: string[] = []
  let sentenceStart = 0
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index]
    const expectedCloser = expectedClosers.at(-1)
    if (character === expectedCloser) {
      expectedClosers.pop()
    } else if (closingMarks[character]) {
      expectedClosers.push(closingMarks[character])
    } else if (character === '"') {
      expectedClosers.push(character)
    } else if (expectedClosers.length === 0 && /[。？！]/u.test(character)) {
      // Repeated punctuation belongs to the same sentence, not an empty tail.
      if (/[。？！]/u.test(text[index + 1] ?? '')) continue
      sentences.push(text.slice(sentenceStart, index + 1))
      sentenceStart = index + 1
    }
  }
  if (sentenceStart < text.length) sentences.push(text.slice(sentenceStart))
  if (sentences.length < 2) return [text]

  const paragraphs: string[] = []
  let paragraph = ''
  for (const sentence of sentences) {
    if (paragraph.length >= MIN_BREAK_LENGTH
      && paragraph.length + sentence.length > PREFERRED_MAX_LENGTH) {
      paragraphs.push(paragraph)
      paragraph = ''
    }
    paragraph += sentence
    if (paragraph.length >= PREFERRED_PARAGRAPH_LENGTH) {
      paragraphs.push(paragraph)
      paragraph = ''
    }
  }
  if (paragraph) paragraphs.push(paragraph)
  if (paragraphs.length > 1 && paragraphs.at(-1)!.length < SHORT_TAIL_LENGTH) {
    const tail = paragraphs.pop()!
    paragraphs[paragraphs.length - 1] += tail
  }
  return paragraphs.length > 1 ? paragraphs : [text]
}

interface MarkdownNode {
  type: string
  value?: string
  children?: MarkdownNode[]
}

/**
 * Opt-in remark transformer for report summaries only. Operate on root-level
 * paragraphs whose children are all plain text. Lists, quotes, code, tables and
 * inline Markdown keep their original syntax/tree; do not recurse into them.
 */
export function remarkGeoReportReadability() {
  return (tree: MarkdownNode) => {
    if (tree.type !== 'root' || !tree.children) return
    tree.children = tree.children.flatMap((node) => {
      if (node.type !== 'paragraph' || !node.children?.length
        || !node.children.every((child) => child.type === 'text' && typeof child.value === 'string')) {
        return [node]
      }
      const parts = splitReportParagraph(node.children.map((child) => child.value).join(''))
      if (parts.length < 2) return [node]
      return parts.map((value) => ({ type: 'paragraph', children: [{ type: 'text', value }] }))
    })
  }
}

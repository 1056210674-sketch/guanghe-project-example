import { render, screen } from '@testing-library/react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { describe, expect, it } from 'vitest'
import { remarkGeoReportReadability, splitReportParagraph } from '../features/geo/reportReadability'

const sentence = `${'监测回答仅代表本次可核验的观察'.repeat(5)}。`
const longText = `${sentence}${sentence}${sentence}${sentence}${sentence}${sentence}`

describe('GEO 智能解读阅读分段', () => {
  it('按完整句子分组，不改字、不删空格、不遗漏末尾', () => {
    const original = `  ${longText}  最后的未完句片段  `
    const parts = splitReportParagraph(original)
    expect(parts.length).toBeGreaterThan(1)
    expect(parts.join('')).toBe(original)
    expect(parts.slice(0, -1).every((part) => /[。？！]$/u.test(part))).toBe(true)
    expect(parts.at(-1)).toContain('  最后的未完句片段  ')
  })

  it('短文、已有换行和没有安全句末的长文保持原样', () => {
    for (const original of ['简短结论。继续观察。', `${sentence}\n${longText}`, `${sentence}\r\n${longText}`, '长文'.repeat(200)]) {
      expect(splitReportParagraph(original)).toEqual([original])
    }
  })

  it('不切断引号、嵌套括号里的问题或句号', () => {
    const quote = `“适合办公室的品牌有哪些？（${'这是问题说明'.repeat(36)}。）是否便携？”`
    const original = `${sentence}${quote}这些都只是被监测的问题。${longText}`
    const parts = splitReportParagraph(original)
    expect(parts.join('')).toBe(original)
    expect(parts.some((part) => part.includes(quote))).toBe(true)
    const straightQuote = `"${'被引用的监测问题。'.repeat(40)}"`
    expect(splitReportParagraph(`${straightQuote}以上属于引文。${longText}`).some((part) => part.includes(straightQuote))).toBe(true)
  })

  it('未闭合引号保守不拆分，网址、小数、缩写不被当成句末', () => {
    const quoted = `“${longText}`
    expect(splitReportParagraph(quoted)).toEqual([quoted])
    const reference = 'https://example.com/path?q=valid!&value=3.14 U.S.A. '
    const original = `${reference.repeat(10)}其他内容。${longText}`
    const parts = splitReportParagraph(original)
    expect(parts.join('')).toBe(original)
    expect(parts[0]).toContain(reference.repeat(10))
  })

  it('句末连续标点留在同一个段落，不强切超长句子', () => {
    const extraLongSentence = `${'完整长句'.repeat(100)}？！`
    const parts = splitReportParagraph(`${extraLongSentence}${longText}`)
    expect(parts[0]).toBe(extraLongSentence)
    expect(parts.join('')).toBe(`${extraLongSentence}${longText}`)
  })
})

describe('仅智能解读顶层纯文本段落应用阅读分段', () => {
  it('长纯文本形成多个段落，显示文本仍逐字一致', () => {
    const { container } = render(<ReactMarkdown remarkPlugins={[remarkGeoReportReadability]}>{longText}</ReactMarkdown>)
    expect(container.querySelectorAll('p').length).toBeGreaterThan(1)
    expect(Array.from(container.querySelectorAll('p')).map((element) => element.textContent).join('')).toBe(longText)
  })

  it('列表、引用、代码、表格和含强调/链接/行内代码的段落保留结构', () => {
    const markdown = `# 解读标题\n\n- ${longText}\n\n> ${longText}\n\n\`\`\`text\n${longText}\n\`\`\`\n\n| 观察 | 结论 |\n| --- | --- |\n| 原样 | ${longText} |\n\n**重要说明** ${longText}\n\n[来源网页](https://example.com) ${longText}\n\n\`inline.value\` ${longText}`
    const { container } = render(<ReactMarkdown remarkPlugins={[remarkGfm, remarkGeoReportReadability]}>{markdown}</ReactMarkdown>)
    expect(screen.getByRole('heading', { name: '解读标题' })).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
    expect(container.querySelectorAll('blockquote p')).toHaveLength(1)
    expect(container.querySelectorAll('pre code')).toHaveLength(1)
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(container.querySelector('strong')?.parentElement?.textContent).toBe(`重要说明 ${longText}`)
    expect(screen.getByRole('link', { name: '来源网页' }).parentElement?.textContent).toBe(`来源网页 ${longText}`)
    expect(screen.getByText('inline.value').parentElement?.textContent).toBe(`inline.value ${longText}`)
    expect(container.querySelectorAll(':scope > p')).toHaveLength(3)
  })

  it('已有段落换行和作者的软换行不重排', () => {
    const markdown = `${longText}\n这是同段软换行。\n\n下一段简短内容。`
    const { container } = render(<ReactMarkdown remarkPlugins={[remarkGeoReportReadability]}>{markdown}</ReactMarkdown>)
    const paragraphs = container.querySelectorAll('p')
    expect(paragraphs).toHaveLength(2)
    expect(paragraphs[0].textContent).toBe(`${longText}\n这是同段软换行。`)
    expect(paragraphs[1].textContent).toBe('下一段简短内容。')
  })
})

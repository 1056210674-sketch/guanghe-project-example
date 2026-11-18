import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ArticleSubmissionWorkspace,
  type ArticleSubmissionWorkspaceProps,
  type MediaResourceItem,
} from '../features/articles'
import { APP_PATHS } from '../routes/paths'
import { DEV_PREVIEW_PATHS } from '../routes/devPreviewPaths'
import { useAuthStore } from '../stores/authStore'
import { userFixture } from './fixtures/auth'
import { renderApp } from './renderApp'

const emptyProps = {
  businessSpaces: [],
  products: [],
  articles: [],
  mediaResources: [],
  submissions: [],
} as const

const completedArticles = [
  {
    id: 'article-1',
    title: '咖啡品牌增长指南',
    status: 'COMPLETED' as const,
    versionLabel: 'V2',
    updatedAt: '2026-09-03 12:00',
  },
  {
    id: 'article-2',
    title: '办公室咖啡趋势',
    status: 'COMPLETED' as const,
    versionLabel: 'V1',
    updatedAt: '2026-09-03 13:00',
  },
]

const mediaResources: MediaResourceItem[] = [
  {
    id: 'media-news-coffee',
    name: '咖啡商业观察',
    type: '新闻媒体',
    publicationNote: '图文审核后发布',
    estimatedTimeLabel: '1–3 个工作日',
    chargedPoints: 13500,
    status: 'ACTIVE',
  },
  {
    id: 'media-industry-coffee',
    name: '咖啡行业志',
    type: '行业媒体',
    publicationNote: '需提供原创首发',
    estimatedTimeLabel: '2–4 个工作日',
    chargedPoints: 16800,
    status: 'ACTIVE',
  },
  {
    id: 'media-industry-retail',
    name: '零售行业周刊',
    type: '行业媒体',
    publicationNote: '工作日审核',
    estimatedTimeLabel: '1–2 个工作日',
    chargedPoints: 12000,
    status: 'ACTIVE',
  },
  {
    id: 'media-paused',
    name: '暂停服务媒体',
    type: '新闻媒体',
    publicationNote: '暂停接单',
    estimatedTimeLabel: '—',
    chargedPoints: 10000,
    status: 'PAUSED',
  },
]

function renderPopulated(
  overrides: Partial<ArticleSubmissionWorkspaceProps> = {},
) {
  return render(
    <ArticleSubmissionWorkspace
      {...emptyProps}
      articles={completedArticles}
      mediaResources={mediaResources}
      {...overrides}
    />,
  )
}

async function chooseSelectOption(
  user: ReturnType<typeof userEvent.setup>,
  label: string,
  option: string,
) {
  await user.click(screen.getByLabelText(label))
  const matches = await screen.findAllByText(option)
  await user.click(matches[matches.length - 1])
}

async function openSubmissionWithArticle(
  user: ReturnType<typeof userEvent.setup>,
) {
  await user.click(screen.getByRole('button', { name: '媒体投稿' }))
  await chooseSelectOption(user, '投稿文章', '咖啡品牌增长指南')
}

beforeEach(() => {
  vi.clearAllMocks()
  useAuthStore.setState({ status: 'anonymous', user: null, error: null })
})

describe('文章与投稿页面', () => {
  it('按设计结构展示流程、编辑器、检查区和真实空态', () => {
    render(<ArticleSubmissionWorkspace {...emptyProps} />)

    expect(screen.getByRole('heading', { name: '文章与投稿' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: '文章与投稿模块' })).toBeInTheDocument()
    expect(screen.getByRole('list', { name: '文章创作流程' })).toBeInTheDocument()
    expect(screen.getAllByText('创作设置')).toHaveLength(2)
    expect(screen.getByText('文章编辑与预览')).toBeInTheDocument()
    expect(screen.getByText('文章检查')).toBeInTheDocument()
    expect(screen.getByText('文章创作方案')).toBeInTheDocument()
    expect(screen.getByLabelText('经营空间')).toBeDisabled()
    expect(screen.getByLabelText('商品')).toBeDisabled()
    expect(screen.getByText('本地草稿')).toBeInTheDocument()
    expect(screen.getByText('尚未保存到服务器')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存到资产' })).toBeDisabled()
    expect(screen.getByRole('button', { name: /进入媒体投稿/ })).toBeDisabled()
  })

  it('输入完整草稿后切换三个页签仍保留全部字段', async () => {
    const user = userEvent.setup()
    render(
      <ArticleSubmissionWorkspace
        {...emptyProps}
        businessSpaces={[{ label: '空间甲', value: 'space-1' }]}
        products={[{ label: '商品甲', value: 'product-1' }]}
      />,
    )

    await chooseSelectOption(user, '经营空间', '空间甲')
    await chooseSelectOption(user, '商品', '商品甲')
    await chooseSelectOption(user, '创作目的', '商品推广')
    await chooseSelectOption(user, '发布渠道', '知乎')
    await chooseSelectOption(user, '表达语气', '简洁有力')
    await chooseSelectOption(user, '目标字数', '约 1600 字')
    await user.click(screen.getByLabelText('核心关键词'))
    await user.paste('便携咖啡机')
    await user.keyboard('{Enter}')
    await user.click(screen.getByLabelText('文章标题'))
    await user.paste('办公室咖啡自由指南')
    await user.click(screen.getByLabelText('文章正文'))
    await user.paste('从一杯稳定的咖啡开始。')

    await user.click(screen.getByRole('button', { name: '媒体投稿' }))
    await user.click(screen.getByRole('button', { name: '我的文章' }))
    await user.click(screen.getByRole('button', { name: '文章创作' }))

    expect(screen.getByLabelText('文章标题')).toHaveValue('办公室咖啡自由指南')
    expect(screen.getByLabelText('文章正文')).toHaveValue('从一杯稳定的咖啡开始。')
    expect(screen.getByText('便携咖啡机')).toBeInTheDocument()
    expect(screen.getByLabelText('经营空间').closest('.ant-select')).toHaveTextContent('空间甲')
    expect(screen.getByLabelText('商品').closest('.ant-select')).toHaveTextContent('商品甲')
    expect(screen.getByLabelText('创作目的').closest('.ant-select')).toHaveTextContent('商品推广')
    expect(screen.getByLabelText('发布渠道').closest('.ant-select')).toHaveTextContent('知乎')
    expect(screen.getByLabelText('表达语气').closest('.ant-select')).toHaveTextContent('简洁有力')
    expect(screen.getByLabelText('目标字数').closest('.ant-select')).toHaveTextContent('约 1600 字')
  })

  it('未保存草稿点击新建文章不会直接清空', async () => {
    const user = userEvent.setup()
    render(<ArticleSubmissionWorkspace {...emptyProps} />)
    await user.click(screen.getByLabelText('文章标题'))
    await user.paste('不能无提示丢失的草稿')

    await user.click(screen.getByRole('button', { name: /新建文章/ }))

    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('放弃当前草稿？')).toBeInTheDocument()
    expect(screen.getByLabelText('文章标题')).toHaveValue('不能无提示丢失的草稿')
    await user.click(screen.getByRole('button', { name: '继续编辑' }))
    expect(screen.getByLabelText('文章标题')).toHaveValue('不能无提示丢失的草稿')
  })

  it('明确确认放弃后才重置草稿', async () => {
    const user = userEvent.setup()
    render(<ArticleSubmissionWorkspace {...emptyProps} />)
    await user.type(screen.getByLabelText('文章标题'), '待放弃标题')
    await user.type(screen.getByLabelText('文章正文'), '待放弃正文')
    await user.type(screen.getByLabelText('核心关键词'), '待放弃关键词{Enter}')

    await user.click(screen.getByRole('button', { name: /新建文章/ }))
    await user.click(screen.getByRole('button', { name: '放弃并新建' }))

    expect(screen.getByLabelText('文章标题')).toHaveValue('')
    expect(screen.getByLabelText('文章正文')).toHaveValue('')
    expect(screen.queryByText('待放弃关键词')).not.toBeInTheDocument()
  })

  it('添加关键词按钮具有明确可访问名称', () => {
    render(<ArticleSubmissionWorkspace {...emptyProps} />)

    expect(screen.getByRole('button', { name: '添加关键词' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'plus' })).not.toBeInTheDocument()
  })

  it('creationEnabled 为 true 但回调缺失时确认生成仍禁用', async () => {
    const user = userEvent.setup()
    render(<ArticleSubmissionWorkspace {...emptyProps} creationEnabled />)

    await user.click(screen.getByRole('button', { name: '生成标题与创作方案' }))

    expect(screen.getByText('文章生成接口尚未接入')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '确认生成' })).toBeDisabled()
  })

  it('投稿备注去除首尾空格后进入 ArticleSubmissionDraft 与确认快照', async () => {
    const user = userEvent.setup()
    const onConfirmSubmission = vi.fn()
    renderPopulated({ submissionEnabled: true, onConfirmSubmission })
    await openSubmissionWithArticle(user)
    await user.click(screen.getByRole('button', { name: '选择媒体 咖啡商业观察' }))
    await user.type(screen.getByLabelText('投稿备注和排版要求'), '  保留小标题排版  ')

    await user.click(screen.getByRole('button', { name: '生成投稿确认' }))

    const snapshot = screen.getByLabelText('投稿确认快照')
    expect(snapshot).toHaveTextContent('咖啡品牌增长指南 · V2')
    expect(snapshot).toHaveTextContent('1 家')
    expect(snapshot).toHaveTextContent('13500')
    expect(snapshot).toHaveTextContent('保留小标题排版')
    await user.click(screen.getByRole('button', { name: '确认投稿' }))
    expect(onConfirmSubmission).toHaveBeenCalledWith({
      articleId: 'article-1',
      mediaIds: ['media-news-coffee'],
      note: '保留小标题排版',
    })
  })

  it('修改投稿备注后旧确认立即失效', async () => {
    const user = userEvent.setup()
    renderPopulated()
    await openSubmissionWithArticle(user)
    await user.click(screen.getByRole('button', { name: '选择媒体 咖啡商业观察' }))
    await user.type(screen.getByLabelText('投稿备注和排版要求'), '原备注')
    await user.click(screen.getByRole('button', { name: '生成投稿确认' }))
    expect(screen.getByLabelText('投稿确认快照')).toBeInTheDocument()

    await user.type(screen.getByLabelText('投稿备注和排版要求'), '已修改')

    expect(screen.queryByLabelText('投稿确认快照')).not.toBeInTheDocument()
    expect(screen.queryByText('投稿接口尚未接入')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '确认投稿' })).toBeDisabled()
  })

  it('修改文章或媒体后旧投稿确认立即失效', async () => {
    const user = userEvent.setup()
    renderPopulated()
    await openSubmissionWithArticle(user)
    await user.click(screen.getByRole('button', { name: '选择媒体 咖啡商业观察' }))
    await user.click(screen.getByRole('button', { name: '生成投稿确认' }))

    await chooseSelectOption(user, '投稿文章', '办公室咖啡趋势')
    expect(screen.queryByLabelText('投稿确认快照')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '生成投稿确认' }))
    expect(screen.getByLabelText('投稿确认快照')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '取消选择媒体 咖啡商业观察' }))
    expect(screen.queryByLabelText('投稿确认快照')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '确认投稿' })).toBeDisabled()
  })

  it('搜索词与动态媒体类型筛选同时生效', async () => {
    const user = userEvent.setup()
    renderPopulated()
    await user.click(screen.getByRole('button', { name: '媒体投稿' }))

    await chooseSelectOption(user, '媒体类型', '行业媒体')
    fireEvent.change(screen.getByLabelText('搜索媒体'), {
      target: { value: '咖啡' },
    })

    const table = screen.getByRole('table', { name: '媒体资源' })
    expect(within(table).getByText('咖啡行业志')).toBeInTheDocument()
    expect(within(table).queryByText('零售行业周刊')).not.toBeInTheDocument()
    expect(within(table).queryByText('咖啡商业观察')).not.toBeInTheDocument()
    expect(screen.getByLabelText('媒体类型').closest('.ant-select')).toHaveTextContent('行业媒体')
  })

  it('最多选择五家媒体且第六次选择不替换已有结果', async () => {
    const user = userEvent.setup()
    const sixMedia = Array.from({ length: 6 }, (_, index) => ({
      id: `media-${index + 1}`,
      name: `媒体 ${index + 1}`,
      type: '新闻媒体',
      publicationNote: '审核后发布',
      estimatedTimeLabel: '1–3 个工作日',
      chargedPoints: 1000,
      status: 'ACTIVE' as const,
    }))
    renderPopulated({ mediaResources: sixMedia })
    await user.click(screen.getByRole('button', { name: '媒体投稿' }))

    for (const media of sixMedia) {
      await user.click(screen.getByRole('button', { name: `选择媒体 ${media.name}` }))
    }

    expect(screen.getByText('已选 5 / 5')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /取消选择媒体/ })).toHaveLength(5)
    expect(screen.getByRole('button', { name: '选择媒体 媒体 6' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
  })

  it('可通过键盘选择和取消选择媒体', async () => {
    const user = userEvent.setup()
    renderPopulated()
    await user.click(screen.getByRole('button', { name: '媒体投稿' }))

    const selectButton = screen.getByRole('button', { name: '选择媒体 咖啡商业观察' })
    selectButton.focus()
    await user.keyboard('{Enter}')

    const cancelButton = screen.getByRole('button', { name: '取消选择媒体 咖啡商业观察' })
    expect(cancelButton).toHaveAttribute('aria-pressed', 'true')
    cancelButton.focus()
    await user.keyboard(' ')

    expect(screen.getByRole('button', { name: '选择媒体 咖啡商业观察' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
  })

  it('PAUSED 媒体不可选择且正确表达未选中状态', async () => {
    const user = userEvent.setup()
    renderPopulated()
    await user.click(screen.getByRole('button', { name: '媒体投稿' }))

    const pausedButton = screen.getByRole('button', {
      name: '选择媒体 暂停服务媒体',
    })
    expect(pausedButton).toBeDisabled()
    expect(pausedButton).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByText('已停用')).toBeInTheDocument()
    expect(screen.getByText('已选 0 / 5')).toBeInTheDocument()
  })

  it('合法 HTTPS 投稿回链可安全打开', async () => {
    const user = userEvent.setup()
    renderPopulated({
      submissions: [
        {
          id: 'submission-https',
          articleTitle: '已发布文章',
          mediaNames: ['咖啡商业观察'],
          status: 'PUBLISHED',
          chargedPoints: 13500,
          submittedAt: '2026-09-03 14:00',
          publishedUrl: 'https://media.example.com/posts/42',
        },
      ],
    })
    await user.click(screen.getByRole('button', { name: '媒体投稿' }))

    expect(screen.getByRole('link', { name: '查看回链' })).toHaveAttribute(
      'href',
      'https://media.example.com/posts/42',
    )
    expect(screen.getByRole('link', { name: '查看回链' })).toHaveAttribute(
      'rel',
      'noopener noreferrer',
    )
  })

  it.each([
    'javascript:alert(1)',
    'data:text/html,unsafe',
    'file:///etc/passwd',
    'http://media.example.com/posts/unsafe',
    'not a valid url',
    '',
  ])('非法或非 HTTPS 回链不可点击：%s', async (publishedUrl) => {
    const user = userEvent.setup()
    renderPopulated({
      submissions: [
        {
          id: 'submission-unsafe',
          articleTitle: '待核验文章',
          mediaNames: ['外部媒体'],
          status: 'PUBLISHED',
          chargedPoints: 1000,
          submittedAt: '2026-09-03 15:00',
          publishedUrl,
        },
      ],
    })
    await user.click(screen.getByRole('button', { name: '媒体投稿' }))

    expect(screen.queryByRole('link', { name: '查看回链' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '查看详情' })).toBeDisabled()
  })

  it('submissionEnabled 为 true 但回调缺失时仍不能确认投稿', async () => {
    const user = userEvent.setup()
    renderPopulated({ submissionEnabled: true })
    await openSubmissionWithArticle(user)
    await user.click(screen.getByRole('button', { name: '选择媒体 咖啡商业观察' }))
    await user.click(screen.getByRole('button', { name: '生成投稿确认' }))

    expect(screen.getByRole('button', { name: '确认投稿' })).toBeDisabled()
  })

  it('USER 历史文章路由仍可访问，主导航只保留资产与投稿入口', async () => {
    useAuthStore.setState({
      status: 'authenticated',
      user: userFixture,
      error: null,
    })

    renderApp([APP_PATHS.articles])

    expect(
      await screen.findByRole('heading', { name: '文章与投稿' }, { timeout: 5_000 }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '更多功能' })).not.toBeInTheDocument()
    const navigation = screen.getByRole('navigation', { name: '普通用户主导航' })
    expect(within(navigation).getByRole('button', { name: '资产中心' })).toBeInTheDocument()
    expect(within(navigation).getByRole('button', { name: '投稿' })).toBeInTheDocument()
  })

  it('开发环境提供免登录预览路由', async () => {
    renderApp([DEV_PREVIEW_PATHS.articles])

    expect(
      await screen.findByRole('heading', { name: '文章与投稿' }, { timeout: 5_000 }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '更多功能' })).not.toBeInTheDocument()
  })
})

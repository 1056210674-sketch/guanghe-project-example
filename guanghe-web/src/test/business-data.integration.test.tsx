import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  BusinessDataOverview,
  BusinessSpaceForm,
  BusinessSpaceList,
  CompetitorForm,
  CompetitorList,
  ProductDetailDrawer,
  ProductForm,
  ProductList,
  type BusinessViewError,
} from '../features/business-data'
import { BusinessSpaceEditView } from '../pages/business-data/BusinessSpacePages'
import { CompetitorEditView } from '../pages/business-data/CompetitorPages'
import { ProductEditView } from '../pages/business-data/ProductPages'
import { APP_PATHS } from '../routes/paths'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import {
  businessPagination,
  businessSpaceFixture,
  businessSpaceFormFixture,
  businessSpaceOption,
  competitorFixture,
  competitorFormFixture,
  fullBusinessPermissions,
  productDetailFixture,
  productFixture,
  productFormFixture,
  readOnlyBusinessPermissions,
} from './fixtures/businessData'
import { adminFixture, userFixture } from './fixtures/auth'
import {
  catalogBusinessSpacePage,
  catalogProduct,
  catalogTraceId,
} from './fixtures/catalogApi'
import { renderApp } from './renderApp'

const testError: BusinessViewError = {
  message: '测试服务暂时不可用',
  traceId: 'business-test-trace-id',
}

const secondaryBusinessSpaceOption = {
  id: 'space-test-002',
  name: '第二经营空间',
}

function catalogSuccessResponse(data: unknown, status = 200): Response {
  return new Response(
    JSON.stringify({ code: 'OK', message: 'success', data, traceId: catalogTraceId }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

function catalogErrorResponse(
  status: number,
  code: string,
  message: string,
): Response {
  return new Response(
    JSON.stringify({ code, message, details: {}, traceId: catalogTraceId }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

function collectionCallbacks() {
  return {
    onCreate: vi.fn(),
    onEdit: vi.fn(),
    onDelete: vi.fn(),
    onPageChange: vi.fn(),
    onRetry: vi.fn(),
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  window.localStorage.clear()
  storeAuthToken({
    accessToken: 'business-data-browser-token',
    expiresAt: '2099-01-01T00:00:00Z',
  })
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>().mockImplementation(() => Promise.resolve(
      catalogSuccessResponse({
        items: [],
        page: 1,
        size: 100,
        total: 0,
        hasNext: false,
      }),
    )),
  )
  useAuthStore.setState({ status: 'anonymous', user: null, error: null })
})

describe('经营资料入口', () => {
  it('只展示经营空间、商品和竞品三个正式入口', async () => {
    const user = userEvent.setup()
    const onOpenBusinessSpaces = vi.fn()
    const onOpenProducts = vi.fn()
    const onOpenCompetitors = vi.fn()
    const { container } = render(
      <BusinessDataOverview
        onOpenBusinessSpaces={onOpenBusinessSpaces}
        onOpenProducts={onOpenProducts}
        onOpenCompetitors={onOpenCompetitors}
      />,
    )

    expect(container.querySelectorAll('.business-entry-card')).toHaveLength(3)
    expect(screen.getByRole('heading', { name: '经营空间' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '商品' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '竞品' })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /进入管理/ })).toHaveLength(3)
    expect(container).not.toHaveTextContent(/店铺|SKU|库存|订单|知识库|局部重绘/i)

    const openButtons = screen.getAllByRole('button', { name: /进入管理/ })
    await user.click(openButtons[0])
    await user.click(openButtons[1])
    await user.click(openButtons[2])
    expect(onOpenBusinessSpaces).toHaveBeenCalledOnce()
    expect(onOpenProducts).toHaveBeenCalledOnce()
    expect(onOpenCompetitors).toHaveBeenCalledOnce()
  })
})

describe('列表状态', () => {
  const cases = [
    {
      name: '经营空间',
      loadingText: '正在加载经营空间',
      emptyText: '还没有经营空间',
      renderList: (state: {
        data: []
        loading: boolean
        error: BusinessViewError | null
      }) =>
        render(
          <BusinessSpaceList
            {...state}
            {...collectionCallbacks()}
            pagination={businessPagination}
            permissions={fullBusinessPermissions}
          />,
        ),
    },
    {
      name: '商品',
      loadingText: '正在加载商品',
      emptyText: '还没有商品',
      renderList: (state: {
        data: []
        loading: boolean
        error: BusinessViewError | null
      }) =>
        render(
          <ProductList
            {...state}
            {...collectionCallbacks()}
            pagination={businessPagination}
            permissions={fullBusinessPermissions}
            businessSpaceOptions={[businessSpaceOption]}
            businessSpaceId={businessSpaceOption.id}
            onBusinessSpaceChange={vi.fn()}
            onView={vi.fn()}
          />,
        ),
    },
    {
      name: '竞品',
      loadingText: '正在加载竞品',
      emptyText: '还没有竞品',
      renderList: (state: {
        data: []
        loading: boolean
        error: BusinessViewError | null
      }) =>
        render(
          <CompetitorList
            {...state}
            {...collectionCallbacks()}
            pagination={businessPagination}
            permissions={fullBusinessPermissions}
            businessSpaceOptions={[businessSpaceOption]}
            businessSpaceId={businessSpaceOption.id}
            onBusinessSpaceChange={vi.fn()}
          />,
        ),
    },
  ] as const

  it.each(cases)('$name 支持 Loading、Error、Empty 且 Loading 优先', (entry) => {
    entry.renderList({ data: [], loading: true, error: testError })
    expect(screen.getByText(new RegExp(entry.loadingText))).toBeInTheDocument()
    expect(screen.queryByText(testError.message)).not.toBeInTheDocument()

    cleanup()
    entry.renderList({ data: [], loading: false, error: testError })
    expect(screen.getByRole('heading', { name: '服务暂不可用' })).toBeInTheDocument()
    expect(screen.getByText(testError.message)).toBeInTheDocument()
    expect(screen.queryByText(`traceId：${testError.traceId}`)).not.toBeInTheDocument()

    cleanup()
    entry.renderList({ data: [], loading: false, error: null })
    expect(screen.getByText(entry.emptyText)).toBeInTheDocument()
  })
})

describe('列表回调与删除确认', () => {
  it('经营空间汇总展示四项真实模型，平均完整度使用百分号', () => {
    const { container } = render(
      <BusinessSpaceList
        data={[]}
        loading={false}
        error={null}
        pagination={businessPagination}
        permissions={readOnlyBusinessPermissions}
        summary={{
          totalSpaces: 4,
          averageCompleteness: 72.5,
          linkedProducts: 8,
        }}
        {...collectionCallbacks()}
      />,
    )
    const summary = container.querySelector<HTMLElement>('.business-summary-grid')

    expect(summary).not.toBeNull()
    expect(summary?.children).toHaveLength(4)
    expect(within(summary as HTMLElement).getByText('经营空间')).toBeInTheDocument()
    expect(within(summary as HTMLElement).getByText('平均完整度')).toBeInTheDocument()
    expect(within(summary as HTMLElement).getByText('72.5%')).toBeInTheDocument()
    expect(within(summary as HTMLElement).getByText('关联商品')).toBeInTheDocument()
    expect(within(summary as HTMLElement).getByText('待完善')).toBeInTheDocument()
    expect(within(summary as HTMLElement).getByText('—')).toBeInTheDocument()
  })

  it('经营空间列表把创建、编辑、搜索和翻页交给外部', async () => {
    const user = userEvent.setup()
    const callbacks = collectionCallbacks()
    const onSearchChange = vi.fn()
    const { container } = render(
      <BusinessSpaceList
        data={[businessSpaceFixture]}
        loading={false}
        error={null}
        pagination={businessPagination}
        permissions={fullBusinessPermissions}
        onSearchChange={onSearchChange}
        {...callbacks}
      />,
    )

    await user.click(screen.getByRole('button', { name: /新建经营空间/ }))
    await user.click(screen.getByRole('button', { name: /编辑/ }))
    await user.type(screen.getByPlaceholderText('搜索经营空间或品牌名称'), '测试')
    const secondPage = container.querySelector<HTMLElement>('.ant-pagination-item-2')
    expect(secondPage).not.toBeNull()
    fireEvent.click(secondPage as HTMLElement)

    expect(callbacks.onCreate).toHaveBeenCalledOnce()
    expect(callbacks.onEdit).toHaveBeenCalledWith(businessSpaceFixture.id)
    expect(onSearchChange).toHaveBeenLastCalledWith('测试')
    expect(callbacks.onPageChange).toHaveBeenCalledWith(2, businessPagination.pageSize)
  })

  it('商品列表把筛选、查看、编辑、搜索和翻页交给外部', async () => {
    const user = userEvent.setup()
    const callbacks = collectionCallbacks()
    const onBusinessSpaceChange = vi.fn()
    const onSearchChange = vi.fn()
    const onView = vi.fn()
    const { container } = render(
      <ProductList
        data={[productFixture]}
        loading={false}
        error={null}
        pagination={businessPagination}
        permissions={fullBusinessPermissions}
        businessSpaceOptions={[businessSpaceOption, secondaryBusinessSpaceOption]}
        businessSpaceId={businessSpaceOption.id}
        onBusinessSpaceChange={onBusinessSpaceChange}
        onSearchChange={onSearchChange}
        onView={onView}
        {...callbacks}
      />,
    )

    fireEvent.mouseDown(
      screen.getByRole('combobox', { name: '按经营空间筛选商品' }),
    )
    fireEvent.click(
      await screen.findByText(secondaryBusinessSpaceOption.name, {
        selector: '.ant-select-item-option-content',
      }),
    )
    await user.click(screen.getByRole('button', { name: /详情/ }))
    await user.click(screen.getByRole('button', { name: /编辑/ }))
    await user.type(screen.getByPlaceholderText('搜索商品名称或别名'), '测试')
    fireEvent.click(container.querySelector('.ant-pagination-item-2') as HTMLElement)

    expect(onBusinessSpaceChange).toHaveBeenCalledWith(
      secondaryBusinessSpaceOption.id,
      expect.objectContaining({ value: secondaryBusinessSpaceOption.id }),
    )
    expect(onView).toHaveBeenCalledWith(productFixture.id)
    expect(callbacks.onEdit).toHaveBeenCalledWith(productFixture.id)
    expect(onSearchChange).toHaveBeenLastCalledWith('测试')
    expect(callbacks.onPageChange).toHaveBeenCalledWith(2, businessPagination.pageSize)
  })

  it('竞品列表把筛选、编辑和搜索交给外部', async () => {
    const user = userEvent.setup()
    const callbacks = collectionCallbacks()
    const onBusinessSpaceChange = vi.fn()
    const onSearchChange = vi.fn()
    render(
      <CompetitorList
        data={[competitorFixture]}
        loading={false}
        error={null}
        pagination={businessPagination}
        permissions={fullBusinessPermissions}
        businessSpaceOptions={[businessSpaceOption, secondaryBusinessSpaceOption]}
        businessSpaceId={businessSpaceOption.id}
        onBusinessSpaceChange={onBusinessSpaceChange}
        onSearchChange={onSearchChange}
        {...callbacks}
      />,
    )

    fireEvent.mouseDown(
      screen.getByRole('combobox', { name: '按经营空间筛选竞品' }),
    )
    fireEvent.click(
      await screen.findByText(secondaryBusinessSpaceOption.name, {
        selector: '.ant-select-item-option-content',
      }),
    )
    expect(screen.getByText('直接竞品')).toBeInTheDocument()
    expect(screen.getByText('测试竞品说明')).toBeInTheDocument()
    expect(screen.getByText('测试状态')).toBeInTheDocument()
    expect(screen.getByText('2099-01-01 12:00')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /编辑/ }))
    await user.type(screen.getByPlaceholderText('搜索竞品名称或别名'), '测试')

    expect(onBusinessSpaceChange).toHaveBeenCalledWith(
      secondaryBusinessSpaceOption.id,
      expect.objectContaining({ value: secondaryBusinessSpaceOption.id }),
    )
    expect(callbacks.onEdit).toHaveBeenCalledWith(competitorFixture.id)
    expect(onSearchChange).toHaveBeenLastCalledWith('测试')
  })

  const deleteCases = [
    {
      name: '经营空间',
      id: businessSpaceFixture.id,
      renderList: (onDelete: (id: string) => void) =>
        render(
          <BusinessSpaceList
            data={[businessSpaceFixture]}
            loading={false}
            error={null}
            pagination={businessPagination}
            permissions={fullBusinessPermissions}
            {...collectionCallbacks()}
            onDelete={onDelete}
          />,
        ),
    },
    {
      name: '商品',
      id: productFixture.id,
      renderList: (onDelete: (id: string) => void) =>
        render(
          <ProductList
            data={[productFixture]}
            loading={false}
            error={null}
            pagination={businessPagination}
            permissions={fullBusinessPermissions}
            businessSpaceOptions={[businessSpaceOption]}
            businessSpaceId={businessSpaceOption.id}
            onBusinessSpaceChange={vi.fn()}
            onView={vi.fn()}
            {...collectionCallbacks()}
            onDelete={onDelete}
          />,
        ),
    },
    {
      name: '竞品',
      id: competitorFixture.id,
      renderList: (onDelete: (id: string) => void) =>
        render(
          <CompetitorList
            data={[competitorFixture]}
            loading={false}
            error={null}
            pagination={businessPagination}
            permissions={fullBusinessPermissions}
            businessSpaceOptions={[businessSpaceOption]}
            businessSpaceId={businessSpaceOption.id}
            onBusinessSpaceChange={vi.fn()}
            {...collectionCallbacks()}
            onDelete={onDelete}
          />,
        ),
    },
  ] as const

  it.each(deleteCases)('$name 删除只有确认后才调用外部回调', async (entry) => {
    const user = userEvent.setup()
    const onDelete = vi.fn()
    entry.renderList(onDelete)

    await user.click(screen.getByRole('button', { name: /删除/ }))
    const dialog = await screen.findByRole('dialog')
    expect(onDelete).not.toHaveBeenCalled()
    expect(within(dialog).getByText('此操作不可撤销')).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: '确认删除' }))
    await waitFor(() => expect(onDelete).toHaveBeenCalledWith(entry.id))
  })

  it.each([
    {
      name: '经营空间',
      renderList: () =>
        render(
          <BusinessSpaceList
            data={[businessSpaceFixture]}
            loading={false}
            error={null}
            pagination={businessPagination}
            permissions={readOnlyBusinessPermissions}
            {...collectionCallbacks()}
          />,
        ),
    },
    {
      name: '商品',
      renderList: () =>
        render(
          <ProductList
            data={[productFixture]}
            loading={false}
            error={null}
            pagination={businessPagination}
            permissions={readOnlyBusinessPermissions}
            businessSpaceOptions={[businessSpaceOption]}
            businessSpaceId={businessSpaceOption.id}
            onBusinessSpaceChange={vi.fn()}
            onView={vi.fn()}
            {...collectionCallbacks()}
          />,
        ),
    },
    {
      name: '竞品',
      renderList: () =>
        render(
          <CompetitorList
            data={[competitorFixture]}
            loading={false}
            error={null}
            pagination={businessPagination}
            permissions={readOnlyBusinessPermissions}
            businessSpaceOptions={[businessSpaceOption]}
            businessSpaceId={businessSpaceOption.id}
            onBusinessSpaceChange={vi.fn()}
            {...collectionCallbacks()}
          />,
        ),
    },
  ])('$name 只读权限隐藏创建、编辑和删除操作', (entry) => {
    entry.renderList()
    expect(screen.queryByRole('button', { name: /新建/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /编辑/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /删除/ })).not.toBeInTheDocument()
  })
})

describe('表单与详情展示边界', () => {
  it('经营空间表单提交外部回调并只提供资产中心引用入口', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const onChooseLogoAsset = vi.fn()
    const { container } = render(
      <BusinessSpaceForm
        mode="create"
        initialValues={businessSpaceFormFixture}
        onSubmit={onSubmit}
        onCancel={vi.fn()}
        onChooseLogoAsset={onChooseLogoAsset}
      />,
    )

    expect(screen.getByText('测试品牌别名')).toBeInTheDocument()
    expect(screen.getByText(/品牌 Logo 由资产中心统一管理/)).toBeInTheDocument()
    expect(container.querySelector('input[type="file"]')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /从资产中心选择/ }))
    await user.click(screen.getByRole('button', { name: '创建经营空间' }))

    expect(onChooseLogoAsset).toHaveBeenCalledOnce()
    await waitFor(() =>
      expect(onSubmit).toHaveBeenCalledWith(
        expect.objectContaining(businessSpaceFormFixture),
      ),
    )
  })

  it('商品表单提交外部回调，保留别名、参数与资产接口位置', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const onChooseAssets = vi.fn()
    const { container } = render(
      <ProductForm
        mode="edit"
        businessSpaceOptions={[businessSpaceOption]}
        initialValues={productFormFixture}
        identityAssetManagementEnabled
        onSubmit={onSubmit}
        onCancel={vi.fn()}
        onChooseAssets={onChooseAssets}
      />,
    )

    expect(screen.getByText('测试商品别名')).toBeInTheDocument()
    expect(screen.getByText('商品身份图片')).toBeInTheDocument()
    expect(screen.getByDisplayValue('测试参数值')).toBeInTheDocument()
    expect(container.querySelector('input[type="file"]')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /上传图片/ })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: /从资产中心选择/ }))
    await user.click(screen.getByRole('button', { name: '保存修改' }))

    expect(onChooseAssets).toHaveBeenCalledOnce()
    await waitFor(() =>
      expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining(productFormFixture)),
    )
  })

  it('竞品表单提交经营空间、别名和关系给外部回调', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    render(
      <CompetitorForm
        mode="create"
        businessSpaceOptions={[businessSpaceOption]}
        initialValues={competitorFormFixture}
        onSubmit={onSubmit}
        onCancel={vi.fn()}
      />,
    )

    expect(screen.getByText('测试竞品别名')).toBeInTheDocument()
    const description = screen.getByPlaceholderText('请输入竞品说明')
    expect(description).toHaveValue('测试竞品说明')
    expect(description).toHaveAttribute('maxlength', '1000')
    await user.click(screen.getByRole('button', { name: '创建竞品' }))
    await waitFor(() =>
      expect(onSubmit).toHaveBeenCalledWith(competitorFormFixture),
    )
  })

  it('接口不可用时表单字段和提交禁用，但仍可取消返回', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const onCancel = vi.fn()
    render(
      <ProductForm
        mode="create"
        businessSpaceOptions={[]}
        disabled
        onSubmit={onSubmit}
        onCancel={onCancel}
      />,
    )

    expect(screen.getByLabelText('商品名称')).toBeDisabled()
    expect(screen.getByRole('button', { name: '创建商品' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: /取\s*消/ }))
    expect(onCancel).toHaveBeenCalledOnce()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('编辑 View 仅在收到真实初始值后渲染表单并提交外部回调', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const { rerender } = render(
      <BusinessSpaceEditView
        initialValues={null}
        loading={false}
        error={null}
        onSubmit={onSubmit}
        onCancel={vi.fn()}
      />,
    )

    expect(screen.getByText('未找到经营空间')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '保存修改' })).not.toBeInTheDocument()

    rerender(
      <BusinessSpaceEditView
        initialValues={businessSpaceFormFixture}
        loading={false}
        error={null}
        onSubmit={onSubmit}
        onCancel={vi.fn()}
      />,
    )
    await user.click(screen.getByRole('button', { name: '保存修改' }))
    await waitFor(() =>
      expect(onSubmit).toHaveBeenCalledWith(
        expect.objectContaining(businessSpaceFormFixture),
      ),
    )
  })

  it('商品编辑 View 将真实初始值提交给外部回调', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    render(
      <ProductEditView
        initialValues={productFormFixture}
        businessSpaceOptions={[businessSpaceOption]}
        loading={false}
        error={null}
        onSubmit={onSubmit}
        onCancel={vi.fn()}
      />,
    )

    await user.click(screen.getByRole('button', { name: '保存修改' }))
    await waitFor(() =>
      expect(onSubmit).toHaveBeenCalledWith(
        expect.objectContaining(productFormFixture),
      ),
    )
  })

  it('竞品编辑 View 将真实初始值提交给外部回调', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    render(
      <CompetitorEditView
        initialValues={competitorFormFixture}
        businessSpaceOptions={[businessSpaceOption]}
        loading={false}
        error={null}
        onSubmit={onSubmit}
        onCancel={vi.fn()}
      />,
    )

    await user.click(screen.getByRole('button', { name: '保存修改' }))
    await waitFor(() =>
      expect(onSubmit).toHaveBeenCalledWith(competitorFormFixture),
    )
  })

  it('商品详情无图片时展示正式占位并把编辑与别名保存交给外部', async () => {
    const user = userEvent.setup()
    const onEdit = vi.fn()
    const onSaveAliases = vi.fn()
    render(
      <ProductDetailDrawer
        open
        product={productDetailFixture}
        onClose={vi.fn()}
        onEdit={onEdit}
        onSaveAliases={onSaveAliases}
      />,
    )

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(productDetailFixture.name)).toBeInTheDocument()
    expect(within(dialog).getByText('暂无图片')).toBeInTheDocument()
    expect(within(dialog).getByText('暂无素材引用')).toBeInTheDocument()
    expect(dialog.querySelector('img')).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: /编辑商品/ }))
    await user.click(within(dialog).getByRole('button', { name: '保存别名' }))

    expect(onEdit).toHaveBeenCalledWith(productDetailFixture.id)
    expect(onSaveAliases).toHaveBeenCalledWith(
      productDetailFixture.id,
      productDetailFixture.aliases,
    )
  })
})

describe('Catalog 页面恢复流程', () => {
  const businessSpaceOptionsPage = {
    ...catalogBusinessSpacePage,
    page: 1,
    size: 100,
    total: 1,
    hasNext: false,
  }

  async function chooseFormOption(label: string, option: string) {
    fireEvent.mouseDown(await screen.findByRole('combobox', { name: label }))
    fireEvent.click(
      await screen.findByText(option, {
        selector: '.ant-select-item-option-content',
      }),
    )
  }

  it('商品 POST 成功但别名保存失败时进入已创建商品编辑页并显示恢复错误', async () => {
    const user = userEvent.setup()
    const createdProduct = {
      ...catalogProduct,
      name: '已创建待恢复商品',
      aliases: [],
    }
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      const method = init?.method ?? 'GET'

      if (method === 'GET' && url.startsWith('/api/v1/spaces?')) {
        return catalogSuccessResponse(businessSpaceOptionsPage)
      }
      if (
        method === 'POST' &&
        url === `/api/v1/spaces/${createdProduct.spaceId}/products`
      ) {
        return catalogSuccessResponse(createdProduct, 201)
      }
      if (
        method === 'PUT' &&
        url === `/api/v1/products/${createdProduct.id}/aliases`
      ) {
        return catalogErrorResponse(409, 'RESOURCE_VERSION_CONFLICT', '别名保存冲突')
      }
      if (method === 'GET' && url === `/api/v1/products/${createdProduct.id}`) {
        return catalogSuccessResponse(createdProduct)
      }

      throw new Error(`Unexpected catalog request: ${method} ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
    renderApp([APP_PATHS.productCreate])

    await chooseFormOption('经营空间', catalogBusinessSpacePage.items[0].name)
    await user.type(screen.getByLabelText('商品名称'), createdProduct.name)
    await user.type(
      screen.getByRole('combobox', { name: /^商品别名/ }),
      '待保存别名{enter}',
    )
    await user.click(screen.getByRole('button', { name: '创建商品' }))

    expect(
      await screen.findByRole('heading', { name: '编辑商品', level: 1 }),
    ).toBeInTheDocument()
    expect(
      await screen.findByText(/商品已创建，但别名保存失败。请在此重试保存。/),
    ).toBeInTheDocument()
    expect(screen.getByText('待保存别名')).toBeInTheDocument()
    expect(screen.queryByText(`traceId：${catalogTraceId}`)).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/products/${createdProduct.id}/aliases`,
      expect.objectContaining({ method: 'PUT' }),
    )
  })
})

describe('经营资料路由与角色隔离', () => {
  it.each([
    [APP_PATHS.businessData, '经营资料'],
    [APP_PATHS.businessSpaces, '经营空间'],
    [APP_PATHS.products, '商品'],
    [APP_PATHS.competitors, '竞品'],
  ] as const)('USER 可以访问 %s', async (path, heading) => {
    useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
    renderApp([path])
    expect(await screen.findByRole('heading', { name: heading, level: 1 })).toBeInTheDocument()
  })

  it('旧经营资料路由仍可访问，但不再出现在用户主导航', async () => {
    useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
    renderApp([APP_PATHS.businessData])
    expect(
      await screen.findByRole('heading', { name: '经营资料', level: 1 }, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '更多功能' })).not.toBeInTheDocument()
    expect(within(screen.getByRole('navigation', { name: '普通用户主导航' })).queryByRole('button', { name: '经营资料' })).not.toBeInTheDocument()
  })

  it.each([
    APP_PATHS.businessData,
    APP_PATHS.businessSpaces,
    APP_PATHS.products,
    APP_PATHS.competitors,
  ])('ADMIN 访问 %s 会进入 403', async (path) => {
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
    renderApp([path])
    expect(
      await screen.findByRole('heading', { name: '403 拒绝访问' }),
    ).toBeInTheDocument()
  })
})

describe('M2A 生产源码边界', () => {
  const featureSources = import.meta.glob('../features/business-data/**/*.{ts,tsx}', {
    eager: true,
    import: 'default',
    query: '?raw',
  }) as Record<string, string>
  const pageSources = import.meta.glob('../pages/business-data/**/*.{ts,tsx}', {
    eager: true,
    import: 'default',
    query: '?raw',
  }) as Record<string, string>
  const routeSources = import.meta.glob('../routes/*.{ts,tsx}', {
    eager: true,
    import: 'default',
    query: '?raw',
  }) as Record<string, string>
  const productionSources = {
    ...featureSources,
    ...pageSources,
    ...routeSources,
  }

  it('页面只通过 Catalog 查询层访问 Java Browser API，不直连 Python 或 internal 路径', () => {
    const m2aRuntimeSources = { ...featureSources, ...pageSources }

    expect(Object.keys(m2aRuntimeSources).length).toBeGreaterThan(0)
    for (const [path, source] of Object.entries(m2aRuntimeSources)) {
      expect(source, path).not.toMatch(/\/internal\/v1\/|localhost:8000|127\.0\.0\.1:8000/)
      expect(source, path).not.toMatch(/\bfetch\s*\(|\bapiRequest\s*\(/)
      expect(source, path).not.toMatch(
        /(?:from|import\s*)[\s(']*['"][^'"]*(?:\/test\/|fixtures?)/,
      )
      expect(source, path).not.toMatch(/\b(?:setupWorker|http\.(?:get|post|put|patch|delete))\s*\(/)
    }
  })

  it('Catalog API 只声明真实 Java 路径，查询失效不会刷新整个应用缓存', () => {
    const catalogApiSources = import.meta.glob('../api/catalogApi.ts', {
      eager: true,
      import: 'default',
      query: '?raw',
    }) as Record<string, string>
    const catalogQuerySources = import.meta.glob(
      '../features/business-data/catalogQueries.ts',
      { eager: true, import: 'default', query: '?raw' },
    ) as Record<string, string>

    expect(Object.keys(catalogApiSources)).toHaveLength(1)
    expect(Object.keys(catalogQuerySources)).toHaveLength(1)
    for (const [path, source] of Object.entries(catalogApiSources)) {
      expect(source, path).toContain("'/api/v1/spaces'")
      expect(source, path).toContain('/api/v1/products/')
      expect(source, path).toContain('/api/v1/competitors/')
      expect(source, path).not.toMatch(/\/internal\/v1\/|:8000|\/api\/v1\/(?:geo|tasks|conversations)/)
      expect(source, path).not.toMatch(/(?:\/test\/|fixtures?)/)
    }
    for (const [path, source] of Object.entries(catalogQuerySources)) {
      expect(source, path).not.toMatch(/invalidateQueries\s*\(\s*\)/)
      expect(source, path).not.toMatch(/queryKey\s*:\s*catalogKeys\.all\b/)
      expect(source, path).not.toMatch(/(?:\/test\/|fixtures?)/)
    }
  })

  it('经营资料页面已移除接口未接入文案和生产空数组占位', () => {
    for (const [path, source] of Object.entries(pageSources)) {
      expect(source, path).not.toMatch(/接口尚未接入|后续开发|本次未提交数据/)
      expect(source, path).not.toMatch(
        /EMPTY_(?:BUSINESS_SPACES|PRODUCTS|COMPETITORS|BUSINESS_SPACE_OPTIONS)/,
      )
    }
  })

  it('不在生产源码中声明非空经营空间、商品或竞品记录数组', () => {
    for (const [path, source] of Object.entries(productionSources)) {
      expect(source, path).not.toMatch(
        /(?:BusinessSpaceListItemView|ProductListItemView|CompetitorListItemView)\s*\[\]\s*=\s*\[\s*{/s,
      )
      expect(source, path).not.toMatch(
        /\b(?:businessSpaces|products|competitors)\s*[:=]\s*\[\s*{/is,
      )
      expect(source, path).not.toContain('product-test-001')
      expect(source, path).not.toContain('space-test-001')
      expect(source, path).not.toContain('competitor-test-001')
    }
  })

  it('不出现本阶段禁止扩展的功能名称', () => {
    for (const [path, source] of Object.entries(productionSources)) {
      expect(source, path).not.toMatch(/店铺|SKU|库存|订单|知识库|局部重绘/i)
    }
  })
})

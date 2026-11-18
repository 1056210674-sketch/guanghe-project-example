import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createAssetDownloadUrl,
  getAsset,
  listAssets,
  uploadImageAsset,
} from '../api/assetApi'
import { ApiError } from '../api/apiError'
import { storeAuthToken } from '../auth/tokenStorage'

type FetchMock = ReturnType<typeof vi.fn<typeof fetch>>

function successResponse(data: unknown): Response {
  return new Response(JSON.stringify({
    code: 'OK',
    message: 'success',
    data,
    traceId: 'a'.repeat(32),
  }), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function stubFetch(...responses: Response[]): FetchMock {
  const fetchMock = vi.fn<typeof fetch>()
  responses.forEach((response) => fetchMock.mockResolvedValueOnce(response))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  storeAuthToken({
    accessToken: 'asset-browser-token',
    expiresAt: '2099-01-01T00:00:00Z',
  })
})

describe('永久资产 Browser API', () => {
  it('列表使用完整过滤参数并保持 Snowflake 字符串 ID', async () => {
    const page = {
      items: [], page: 2, size: 50, total: 0, hasNext: false,
    }
    const fetchMock = stubFetch(successResponse(page))
    await expect(listAssets({
      page: 2,
      size: 50,
      type: 'IMAGE',
      spaceId: '90299993499369472',
      productId: '90300589849706496',
      sourceType: 'GENERATED',
    })).resolves.toEqual(page)
    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/v1/assets?page=2&size=50&type=IMAGE&spaceId=90299993499369472&productId=90300589849706496&sourceType=GENERATED',
    )
  })

  it('详情和下载地址只访问 Java /api/v1 路径', async () => {
    const assetId = '90378333853843456'
    const asset = { id: assetId }
    const download = {
      url: 'https://asset.example/signed.png',
      expiresAt: '2099-01-01T00:00:00Z',
    }
    const fetchMock = stubFetch(successResponse(asset), successResponse(download))
    await expect(getAsset(assetId)).resolves.toEqual(asset)
    await expect(createAssetDownloadUrl(assetId)).resolves.toEqual(download)
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      `/api/v1/assets/${assetId}`,
      `/api/v1/assets/${assetId}/download-url`,
    ])
  })

  it.each([
    'http://asset.example/not-secure.png',
    'javascript:alert(1)',
    'not-a-url',
  ])('拒绝非 HTTPS 或无效地址：%s', async (url) => {
    stubFetch(successResponse({ url, expiresAt: '2099-01-01T00:00:00Z' }))
    await expect(createAssetDownloadUrl('90378333853843456')).rejects.toMatchObject({
      status: 200,
      code: 'INVALID_RESPONSE',
    } satisfies Partial<ApiError>)
  })

  it('真实错误保留 message 与 traceId', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(new Response(
      JSON.stringify({
        code: 'ASSET_NOT_READY',
        message: '资产尚未就绪',
        details: {},
        traceId: 'asset-error-trace',
      }),
      { status: 409, headers: { 'Content-Type': 'application/json' } },
    )))
    await expect(createAssetDownloadUrl('90378333853843456')).rejects.toMatchObject({
      status: 409,
      message: '资产尚未就绪',
      traceId: 'asset-error-trace',
    } satisfies Partial<ApiError>)
  })

  it('图片上传使用真实 multipart、幂等键并回传浏览器上传进度', async () => {
    const uploaded = {
      id: '90400000000000001',
      assetType: 'IMAGE',
      sourceType: 'UPLOADED',
      status: 'READY',
      safetyStatus: 'APPROVED',
      name: 'coffee.png',
      spaceId: '90299993499369472',
      contentType: 'image/png',
      sizeBytes: 4,
      sha256: 'a'.repeat(64),
      width: 1,
      height: 1,
      createdAt: '2026-09-14T10:00:00Z',
    }
    let requestBody: FormData | undefined
    const requestHeaders = new Map<string, string>()
    class FakeXmlHttpRequest {
      status = 201
      responseType = ''
      responseText = JSON.stringify({
        code: 'OK', message: 'success', data: uploaded, traceId: 'a'.repeat(32),
      })
      upload = { onprogress: null as ((event: ProgressEvent) => void) | null }
      onload: ((event: ProgressEvent) => void) | null = null
      onerror: ((event: ProgressEvent) => void) | null = null
      open(method: string, path: string) {
        expect(method).toBe('POST')
        expect(path).toBe('/api/v1/assets/images')
      }
      setRequestHeader(name: string, value: string) {
        requestHeaders.set(name, value)
      }
      send(body: FormData) {
        requestBody = body
        queueMicrotask(() => {
          this.upload.onprogress?.({
            lengthComputable: true, loaded: 2, total: 4,
          } as ProgressEvent)
          this.onload?.({} as ProgressEvent)
        })
      }
    }
    vi.stubGlobal('XMLHttpRequest', FakeXmlHttpRequest)
    const progress: number[] = []
    const file = new File(['test'], 'coffee.png', { type: 'image/png' })

    await expect(uploadImageAsset({
      spaceId: uploaded.spaceId,
      file,
      name: file.name,
      idempotencyKey: '11111111-1111-4111-8111-111111111111',
      onUploadProgress: (percent) => progress.push(percent),
    })).resolves.toEqual(uploaded)

    expect(requestBody?.get('spaceId')).toBe(uploaded.spaceId)
    expect(requestBody?.get('name')).toBe('coffee.png')
    expect(requestBody?.get('file')).toBe(file)
    expect(requestHeaders.get('idempotency-key')).toBe(
      '11111111-1111-4111-8111-111111111111',
    )
    expect(requestHeaders.has('content-type')).toBe(false)
    expect(requestHeaders.get('Authorization')).toBe('Bearer asset-browser-token')
    expect(progress).toEqual([50, 100])
  })
})

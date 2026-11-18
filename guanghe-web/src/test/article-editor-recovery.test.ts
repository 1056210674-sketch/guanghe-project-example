import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  articleEditorRecoveryKey,
  clearArticleEditorRecovery,
  readArticleEditorRecovery,
  saveArticleEditorRecovery,
} from '../features/articles'

const articleId = '90440000000000001'
const version = 5
const now = 1_800_000_000_000

beforeEach(() => {
  window.localStorage.clear()
})

describe('文章编辑器本地恢复', () => {
  it('按文章版本保存并读取标题和正文草稿', () => {
    saveArticleEditorRecovery(
      articleId,
      { baseVersion: version, title: '恢复标题', bodyText: '恢复正文' },
      now,
    )

    expect(readArticleEditorRecovery(articleId, version, now)).toEqual({
      baseVersion: version,
      title: '恢复标题',
      bodyText: '恢复正文',
      savedAt: now,
    })
    expect(window.localStorage.getItem(articleEditorRecoveryKey(articleId))).toContain(
      '恢复正文',
    )
  })

  it('同时保存创作方案和配图顺序，恢复副本不接受重复或越界数据', () => {
    saveArticleEditorRecovery(
      articleId,
      {
        baseVersion: version,
        title: '完整恢复标题',
        bodyText: '完整恢复正文',
        outline: [{ key: 'section-1', level: 2, heading: '恢复小节' }],
        assetIds: ['90440000000000005', '90440000000000006'],
      },
      now,
    )

    expect(readArticleEditorRecovery(articleId, version, now)).toMatchObject({
      outline: [{ key: 'section-1', level: 2, heading: '恢复小节' }],
      assetIds: ['90440000000000005', '90440000000000006'],
    })

    window.localStorage.setItem(
      articleEditorRecoveryKey(articleId),
      JSON.stringify({
        baseVersion: version,
        title: '标题',
        bodyText: '正文',
        outline: [
          { key: 'duplicate', level: 2, heading: '一' },
          { key: 'duplicate', level: 2, heading: '二' },
        ],
        assetIds: ['90440000000000005', '90440000000000005'],
        savedAt: now,
      }),
    )
    expect(readArticleEditorRecovery(articleId, version, now)).toBeUndefined()
  })

  it('服务端版本变化时拒绝并清理旧草稿', () => {
    saveArticleEditorRecovery(
      articleId,
      { baseVersion: version, title: '旧标题', bodyText: '旧正文' },
      now,
    )

    expect(readArticleEditorRecovery(articleId, version + 1, now)).toBeUndefined()
    expect(window.localStorage.getItem(articleEditorRecoveryKey(articleId))).toBeNull()
  })

  it('拒绝过期和未来时间戳，避免恢复不可验证的草稿', () => {
    saveArticleEditorRecovery(
      articleId,
      { baseVersion: version, title: '过期标题', bodyText: '过期正文' },
      now - 7 * 24 * 60 * 60 * 1000 - 1,
    )
    expect(readArticleEditorRecovery(articleId, version, now)).toBeUndefined()

    saveArticleEditorRecovery(
      articleId,
      { baseVersion: version, title: '未来标题', bodyText: '未来正文' },
      now + 1,
    )
    expect(readArticleEditorRecovery(articleId, version, now)).toBeUndefined()
  })

  it('遇到损坏或超长数据时不阻塞编辑并清理损坏记录', () => {
    const key = articleEditorRecoveryKey(articleId)
    window.localStorage.setItem(key, '{not-json')
    expect(readArticleEditorRecovery(articleId, version, now)).toBeUndefined()
    expect(window.localStorage.getItem(key)).toBeNull()

    saveArticleEditorRecovery(
      articleId,
      {
        baseVersion: version,
        title: 'x'.repeat(301),
        bodyText: '正文',
      },
      now,
    )
    expect(window.localStorage.getItem(key)).toBeNull()
  })

  it('localStorage 不可用时静默降级，服务端仍可作为事实来源', () => {
    const getItem = vi
      .spyOn(Storage.prototype, 'getItem')
      .mockImplementation(() => {
        throw new Error('storage unavailable')
      })
    expect(() => readArticleEditorRecovery(articleId, version, now)).not.toThrow()
    getItem.mockRestore()

    const setItem = vi
      .spyOn(Storage.prototype, 'setItem')
      .mockImplementation(() => {
        throw new Error('storage full')
      })
    expect(() =>
      saveArticleEditorRecovery(
        articleId,
        { baseVersion: version, title: '标题', bodyText: '正文' },
        now,
      ),
    ).not.toThrow()
    setItem.mockRestore()

    const removeItem = vi
      .spyOn(Storage.prototype, 'removeItem')
      .mockImplementation(() => {
        throw new Error('storage unavailable')
      })
    expect(() => clearArticleEditorRecovery(articleId)).not.toThrow()
    removeItem.mockRestore()
  })
})

import { describe, expect, it } from 'vitest'
import { toConversationContextRequest } from '../pages/chat/chatContextSelection'

describe('AI 对话上下文选择请求', () => {
  it('未在当前页面修改选择时继承服务端会话上下文并省略标识', () => {
    expect(toConversationContextRequest(undefined)).toEqual({
      contextSelectionMode: 'INHERIT',
    })
  })

  it('切换经营空间或商品时明确替换服务端会话上下文', () => {
    expect(
      toConversationContextRequest({
        businessSpaceId: '21',
        productId: '31',
      }),
    ).toEqual({
      contextSelectionMode: 'REPLACE',
      selectedSpaceId: '21',
      selectedProductId: '31',
    })
  })

  it('用户明确清空经营空间时发送清空指令并省略旧标识', () => {
    expect(toConversationContextRequest({})).toEqual({
      contextSelectionMode: 'CLEAR',
    })
  })

  it('本次图片附件不会清除或替换侧栏已选上下文', () => {
    const request = {
      ...toConversationContextRequest({
        businessSpaceId: '21',
        productId: '31',
      }),
      attachments: [{ assetId: '41' }],
    }

    expect(request).toEqual({
      contextSelectionMode: 'REPLACE',
      selectedSpaceId: '21',
      selectedProductId: '31',
      attachments: [{ assetId: '41' }],
    })
  })

  it('文章工作流强制上下文优先于侧栏的本地选择', () => {
    expect(
      toConversationContextRequest(
        { businessSpaceId: '21', productId: '31' },
        { spaceId: '22', productId: '32' },
      ),
    ).toEqual({
      contextSelectionMode: 'REPLACE',
      selectedSpaceId: '22',
      selectedProductId: '32',
    })
  })
})

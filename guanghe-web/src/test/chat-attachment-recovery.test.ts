import { beforeEach, describe, expect, it } from 'vitest'
import {
  clearCurrentAttachmentIds,
  moveCurrentAttachmentIds,
  readCurrentAttachmentIds,
  storeCurrentAttachmentIds,
} from '../features/chat/live/attachmentRecovery'

beforeEach(() => window.localStorage.clear())

describe('当前消息附件恢复', () => {
  it('只持久化去重后的资产 ID，并按用户和会话隔离', () => {
    storeCurrentAttachmentIds('user-1', 'new', [
      ' asset-1 ', 'asset-1', 'asset-2', 'asset-3', 'asset-4',
      'asset-5', 'asset-6', 'asset-7', 'asset-8', 'asset-9',
    ])

    expect(readCurrentAttachmentIds('user-1', 'new')).toEqual([
      'asset-1',
      'asset-2',
      'asset-3',
      'asset-4',
      'asset-5',
      'asset-6',
      'asset-7',
      'asset-8',
    ])
    expect(readCurrentAttachmentIds('user-2', 'new')).toEqual([])
    expect(readCurrentAttachmentIds('user-1', 'conversation-2')).toEqual([])
    expect(
      window.localStorage.getItem(
        'zhixiaohe.chat.current-attachments.v1.user-1.new',
      ),
    ).toBe(
      JSON.stringify({
        schemaVersion: 1,
        assetIds: [
          'asset-1', 'asset-2', 'asset-3', 'asset-4',
          'asset-5', 'asset-6', 'asset-7', 'asset-8',
        ],
      }),
    )
  })

  it('临时新会话创建成功后迁移附件，发送成功后可以精确清理', () => {
    storeCurrentAttachmentIds('user-1', 'new', ['asset-1'])
    expect(
      moveCurrentAttachmentIds('user-1', 'new', 'conversation-1'),
    ).toEqual(['asset-1'])
    expect(readCurrentAttachmentIds('user-1', 'new')).toEqual([])
    expect(readCurrentAttachmentIds('user-1', 'conversation-1')).toEqual([
      'asset-1',
    ])

    clearCurrentAttachmentIds('user-1', 'conversation-1')
    expect(readCurrentAttachmentIds('user-1', 'conversation-1')).toEqual([])
  })

  it('损坏或未知版本数据会被丢弃，避免继承历史脏状态', () => {
    window.localStorage.setItem(
      'zhixiaohe.chat.current-attachments.v1.user-1.conversation-1',
      '{bad-json',
    )
    window.localStorage.setItem(
      'zhixiaohe.chat.current-attachments.v1.user-1.conversation-2',
      JSON.stringify({ schemaVersion: 2, assetIds: ['asset-1'] }),
    )

    expect(readCurrentAttachmentIds('user-1', 'conversation-1')).toEqual([])
    expect(readCurrentAttachmentIds('user-1', 'conversation-2')).toEqual([])
    expect(window.localStorage.length).toBe(0)
  })
})

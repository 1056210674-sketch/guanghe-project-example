import { beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  CreateTaskDraftRequest,
  TaskDraft,
} from '../api/conversationApi'
import { storeAuthToken } from '../auth/tokenStorage'
import {
  createFormTaskDraftRecoveryStore,
  fingerprintFormTaskDraftInput,
  prepareFormTaskDraft,
  submitFormTaskDraftConfirmation,
} from '../features/task-draft'
import type { FormTaskDraftRecoveryState } from '../features/task-draft'
import {
  testAccessToken,
  testExpiresAt,
  testTraceId,
} from './fixtures/auth'

const now = '2026-09-08T01:00:00Z'

function ok(data: unknown, status = 200) {
  return new Response(
    JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

function failure(status: number, message: string) {
  return new Response(
    JSON.stringify({
      code: `HTTP_${status}`,
      message,
      details: {},
      traceId: testTraceId,
    }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

function videoRequest(scene: string): CreateTaskDraftRequest {
  return {
    conversationId: '11',
    taskType: 'VIDEO',
    spaceId: '21',
    productId: '31',
    parameterSchemaVersion: 1,
    parameters: {
      requestText: scene,
      mode: 'AGENT_ASSISTED',
      agentSummary: scene,
      sourceMode: 'CATALOG_PRODUCT',
      referenceAssetIds: ['81'],
      scene,
      durationSeconds: 5,
      aspectRatio: 'adaptive',
      resolution: '720P',
      audio: false,
      seed: -1,
      promptExtend: true,
      watermark: false,
    },
  }
}

function waitingDraft(
  id: string,
  request: CreateTaskDraftRequest,
): TaskDraft {
  return {
    id,
    draftSource: 'FORM',
    taskType: 'VIDEO',
    parameterSchemaVersion: 1,
    status: 'WAITING_CONFIRMATION',
    spaceId: request.spaceId,
    productId: request.productId,
    sourceMode: 'CATALOG_PRODUCT',
    referenceAssetIds: ['81'],
    parameters: request.parameters,
    warnings: [],
    inputHash: `${id}-input-hash`,
    pricingVersionId: `${id}-pricing`,
    quotedPoints: 10,
    confirmationToken: `${id}-confirmation-token`,
    expiresAt: '2099-01-01T00:00:00Z',
    createdAt: now,
  }
}

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
})

describe('FORM TaskDraft recovery core', () => {
  it('migrates legacy state into a versioned owner/flow envelope and persists only allowlisted values', () => {
    const ownerId = 'user-1'
    const legacyKey = `legacy.video.${ownerId}`
    window.localStorage.setItem(legacyKey, JSON.stringify({
      conversationId: '11',
      draftAttempt: {
        fingerprint: 'fingerprint',
        idempotencyKey: 'draft-key',
        draftId: '41',
        providerUrl: 'https://provider.example/result',
      },
      confirmationAttempt: {
        draftId: '41',
        inputHash: 'input-hash',
        pricingVersionId: '51',
        idempotencyKey: 'confirm-key',
        confirmationToken: 'must-not-persist',
      },
      signedUrl: 'https://oss.example/signed',
      accessToken: 'must-not-persist',
    }))
    const videoStore = createFormTaskDraftRecoveryStore({
      flow: 'video-creation',
      legacyStoragePrefixes: ['legacy.video.'],
    })
    const geoStore = createFormTaskDraftRecoveryStore({ flow: 'geo-manual' })

    expect(videoStore.read(ownerId)).toMatchObject({
      conversationId: '11',
      draftAttempt: { draftId: '41', idempotencyKey: 'draft-key' },
      confirmationAttempt: { draftId: '41', idempotencyKey: 'confirm-key' },
    })
    expect(window.localStorage.getItem(legacyKey)).toBeNull()
    const persisted = window.localStorage.getItem(videoStore.keyForOwner(ownerId)) ?? ''
    expect(persisted).toContain('"schemaVersion":1')
    expect(persisted).toContain('"flow":"video-creation"')
    expect(persisted).not.toMatch(/confirmationToken|accessToken|providerUrl|signedUrl|https?:/i)
    expect(videoStore.read('other-user')).toEqual({})
    expect(geoStore.read(ownerId)).toEqual({})
  })

  it('reuses a pending idempotency attempt after failure and creates only one recovered draft', async () => {
    const request = videoRequest('同一输入')
    const created = waitingDraft('41', request)
    let recovery: FormTaskDraftRecoveryState = { conversationId: '11' }
    let call = 0
    const keys: string[] = []
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (_input, init) => {
      call += 1
      keys.push((init?.headers as Headers).get('Idempotency-Key') ?? '')
      if (call === 1) throw new Error('temporary network failure')
      return ok(created, 201)
    }))

    await expect(prepareFormTaskDraft({
      recovery,
      request,
      onRecoveryChange: (next) => { recovery = next },
    })).rejects.toThrow('网络连接失败')
    const pendingKey = recovery.draftAttempt?.idempotencyKey
    const result = await prepareFormTaskDraft({
      recovery,
      request,
      onRecoveryChange: (next) => { recovery = next },
    })

    expect(keys).toEqual([pendingKey, pendingKey])
    expect(result.draft.id).toBe('41')
    expect(recovery.draftAttempt?.draftId).toBe('41')
  })

  it('uses a new attempt and revises the current FORM draft when input changes', async () => {
    const originalRequest = videoRequest('原始输入')
    const changedRequest = videoRequest('变更后的输入')
    const original = waitingDraft('41', originalRequest)
    const revised = waitingDraft('42', changedRequest)
    const originalFingerprint = await fingerprintFormTaskDraftInput(originalRequest)
    let recovery: FormTaskDraftRecoveryState = {
      conversationId: '11',
      draftAttempt: {
        fingerprint: originalFingerprint,
        idempotencyKey: 'original-key',
        draftId: '41',
      },
    }
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      expect(String(input)).toBe('/api/v1/task-drafts/41/revisions')
      expect(init?.method).toBe('POST')
      return ok(revised, 201)
    })
    vi.stubGlobal('fetch', fetchMock)

    const result = await prepareFormTaskDraft({
      recovery,
      request: changedRequest,
      currentDraft: original,
      onRecoveryChange: (next) => { recovery = next },
    })

    expect(result.draft.id).toBe('42')
    expect(recovery.draftAttempt?.idempotencyKey).not.toBe('original-key')
    expect(recovery.draftAttempt?.draftId).toBe('42')
  })

  it('drops a stale recovered draft after an authoritative 404 and creates a new attempt', async () => {
    const request = videoRequest('恢复输入')
    const fingerprint = await fingerprintFormTaskDraftInput(request)
    const created = waitingDraft('42', request)
    let recovery: FormTaskDraftRecoveryState = {
      conversationId: '11',
      draftAttempt: {
        fingerprint,
        idempotencyKey: 'stale-key',
        draftId: '41',
      },
    }
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      if (String(input) === '/api/v1/task-drafts/41') {
        return failure(404, '草稿不存在')
      }
      expect(String(input)).toBe('/api/v1/task-drafts')
      expect(init?.method).toBe('POST')
      return ok(created, 201)
    })
    vi.stubGlobal('fetch', fetchMock)

    await prepareFormTaskDraft({
      recovery,
      request,
      onRecoveryChange: (next) => { recovery = next },
    })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(recovery.draftAttempt?.draftId).toBe('42')
    expect(recovery.draftAttempt?.idempotencyKey).not.toBe('stale-key')
  })

  it('reuses the same confirmation attempt after a transient failure without persisting the token', async () => {
    const draft = waitingDraft('41', videoRequest('待确认输入'))
    let recovery: FormTaskDraftRecoveryState = { conversationId: '11' }
    let call = 0
    const keys: string[] = []
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (_input, init) => {
      call += 1
      keys.push((init?.headers as Headers).get('Idempotency-Key') ?? '')
      if (call === 1) throw new Error('temporary confirm failure')
      return ok({
        taskId: '61', status: 'QUEUED', quotedPoints: 10,
        frozenPoints: 10, createdAt: now,
      }, 202)
    }))

    await expect(submitFormTaskDraftConfirmation({
      recovery,
      draft,
      onRecoveryChange: (next) => { recovery = next },
    })).rejects.toThrow('网络连接失败')
    const pendingKey = recovery.confirmationAttempt?.idempotencyKey
    const result = await submitFormTaskDraftConfirmation({
      recovery,
      draft,
      onRecoveryChange: (next) => { recovery = next },
    })

    expect(keys).toEqual([pendingKey, pendingKey])
    expect(result.accepted.taskId).toBe('61')
    expect(recovery.confirmationAttempt?.taskId).toBe('61')
    expect(JSON.stringify(recovery)).not.toContain(draft.confirmationToken)
  })
})

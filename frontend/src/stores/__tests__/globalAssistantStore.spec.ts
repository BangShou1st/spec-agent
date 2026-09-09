import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { GaRunProjection, useGlobalAssistantStore } from '@/stores/globalAssistantStore'
import type { GaEventEnvelope } from '@/api/globalAssistant'

function envelope(sequence: number, type: string, payload: Record<string, unknown> = {}): GaEventEnvelope {
  return { eventId: 'e-' + sequence, runId: 'r-1', threadId: 't-1', type: type as GaEventEnvelope['type'], sequence, createdAt: '2026-01-01T00:00:0' + sequence + 'Z', payload }
}

describe('ga run projection', () => {
  it('ignores duplicate and out-of-order sequences', () => {
    const projection = new GaRunProjection()
    expect(projection.apply(envelope(1, 'STATUS', { message: 'a' }))).toBe(true)
    expect(projection.apply(envelope(1, 'STATUS', { message: 'b' }))).toBe(false)
    expect(projection.currentStatus).toBe('a')
    expect(projection.apply(envelope(2, 'STATUS', { message: 'b' }))).toBe(true)
    expect(projection.currentStatus).toBe('b')
  })

  it('accumulates assistant deltas in sequence order', () => {
    const projection = new GaRunProjection()
    projection.apply(envelope(1, 'ASSISTANT_DELTA', { text: 'hello ' }))
    projection.apply(envelope(2, 'ASSISTANT_DELTA', { text: 'world' }))
    expect(projection.streamingText).toBe('hello world')
  })

  it('tracks tool lifecycle running to success', () => {
    const projection = new GaRunProjection()
    projection.apply(envelope(1, 'TOOL_STARTED', { capabilityId: 'project.search', arguments: { query: 'mail' } }))
    expect(projection.activities).toHaveLength(1)
    expect(projection.activities[0].state).toBe('running')
    expect(projection.activities[0].displayName).toBe('搜索项目')
    projection.apply(envelope(2, 'TOOL_COMPLETED', { capabilityId: 'project.search', summary: 'Found 2' }))
    expect(projection.activities[0].state).toBe('success')
    expect(projection.activities[0].summary).toBe('Found 2')
  })

  it('marks tool failure with friendly copy', () => {
    const projection = new GaRunProjection()
    projection.apply(envelope(1, 'TOOL_STARTED', { capabilityId: 'project.search' }))
    projection.apply(envelope(2, 'TOOL_FAILED', { capabilityId: 'project.search', errorCode: 'TOOL_EXECUTION_FAILED', reason: 'boom' }))
    expect(projection.activities[0].state).toBe('failure')
    expect(projection.activities[0].summary).toContain('工具执行失败')
  })

  it('handles clarification, ui action and terminal states', () => {
    const projection = new GaRunProjection()
    projection.apply(envelope(1, 'USER_INPUT_REQUIRED', { question: '哪一个项目？' }))
    expect(projection.waitingQuestion).toBe('哪一个项目？')
    projection.apply(envelope(2, 'UI_ACTION', { destination: 'PROJECT', resourceId: 'pid-1' }))
    expect(projection.uiAction).toMatchObject({ destination: 'PROJECT', resourceId: 'pid-1' })
    projection.apply(envelope(3, 'RUN_COMPLETED', {}))
    expect(projection.terminal?.type).toBe('RUN_COMPLETED')
  })

  it('records run failed and cancelled terminals', () => {
    const failed = new GaRunProjection()
    failed.apply(envelope(1, 'RUN_FAILED', { errorCode: 'MODEL_UNAVAILABLE', reason: 'busy' }))
    expect(failed.terminal?.type).toBe('RUN_FAILED')
    expect(failed.terminal?.errorCode).toBe('MODEL_UNAVAILABLE')
    const cancelled = new GaRunProjection()
    cancelled.apply(envelope(1, 'RUN_CANCELLED', {}))
    expect(cancelled.terminal?.type).toBe('RUN_CANCELLED')
  })

  it('records approval requirement as restrained non-interactive state', () => {
    const projection = new GaRunProjection()
    expect(projection.apply(envelope(1, 'APPROVAL_REQUIRED', {}))).toBe(true)
    expect(projection.approvalRequired).toBe(true)
  })

  it('ignores unknown future events safely', () => {
    const projection = new GaRunProjection()
    expect(projection.apply(envelope(1, 'FUTURE_MAGIC', {}))).toBe(false)
    expect(projection.lastSequence).toBe(1)
    expect(projection.apply(envelope(2, 'STATUS', { message: 'ok' }))).toBe(true)
  })
});

describe('global assistant store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('recovers thread not found by clearing local identity', async () => {
    localStorage.setItem('spec-agent:global-assistant:thread:v1', 'missing-thread')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404, json: async () => ({ code: 'THREAD_NOT_FOUND', message: 'Thread not found' }) } as unknown as Response))
    const store = useGlobalAssistantStore()
    await store.init()
    expect(store.threadId).toBeNull()
    expect(localStorage.getItem('spec-agent:global-assistant:thread:v1')).toBeNull()
  })

  it('restores thread messages on boot', async () => {
    localStorage.setItem('spec-agent:global-assistant:thread:v1', 't-1')
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ threadId: 't-1', summary: '', summaryVersion: 1, workingStateVersion: 1, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }) } as unknown as Response)
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => [{ id: 'm-1', threadId: 't-1', role: 'USER', content: 'hi', runId: null, createdAt: '2026-01-01T00:00:00Z' }] } as unknown as Response)
    vi.stubGlobal('fetch', fetchMock)
    const store = useGlobalAssistantStore()
    await store.init()
    expect(store.threadId).toBe('t-1')
    expect(store.messages).toHaveLength(1)
  })

  it('keeps composer draft when run is already active', async () => {
    const store = useGlobalAssistantStore()
    store.threadId = 't-1'
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ threadId: 't-1', summary: '', summaryVersion: 1, workingStateVersion: 1, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }) } as unknown as Response)
      .mockResolvedValueOnce({ ok: false, status: 409, json: async () => ({ code: 'GLOBAL_ASSISTANT_RUN_ACTIVE', message: 'Thread already hosts an active run' }) } as unknown as Response)
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => [] } as unknown as Response)
    vi.stubGlobal('fetch', fetchMock)
    await store.sendMessage('hello again', { currentPage: 'PROJECTS', selectedEntity: null })
    expect(store.error?.code).toBe('GLOBAL_ASSISTANT_RUN_ACTIVE')
    expect(store.draft).toBe('hello again')
  })
});


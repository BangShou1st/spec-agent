import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { GaRunProjection, useGlobalAssistantStore } from '@/stores/globalAssistantStore'
import type { GaEventEnvelope } from '@/api/globalAssistant'
function envelope(sequence: number, type: string, payload: Record<string, unknown> = {}): GaEventEnvelope {
  return { eventId: 'e-' + sequence, runId: 'r-1', threadId: 't-1', type: type as GaEventEnvelope['type'], sequence, createdAt: '2026-01-01T00:00:0' + sequence + 'Z', payload }
}
describe('ga answer stream projection', () => {
  it('starts a generation with a clean draft', () => {
    const projection = new GaRunProjection()
    expect(projection.apply(envelope(1, 'ANSWER_STREAM_STARTED', { generation: 1 }))).toBe(true)
    expect(projection.streamGeneration).toBe(1)
    expect(projection.streamingText).toBe('')
  })
  it('ignores a repeated start for the same generation', () => {
    const projection = new GaRunProjection()
    projection.apply(envelope(1, 'ANSWER_STREAM_STARTED', { generation: 1 }))
    projection.apply(envelope(2, 'ANSWER_DELTA', { generation: 1, text: 'hello ' }))
    expect(projection.apply(envelope(3, 'ANSWER_STREAM_STARTED', { generation: 1 }))).toBe(false)
    expect(projection.streamingText).toBe('hello ')
  })
  it('appends same-generation deltas incrementally', () => {
    const projection = new GaRunProjection()
    projection.apply(envelope(1, 'ANSWER_STREAM_STARTED', { generation: 1 }))
    projection.apply(envelope(2, 'ANSWER_DELTA', { generation: 1, text: 'hello ' }))
    projection.apply(envelope(3, 'ANSWER_DELTA', { generation: 1, text: 'world' }))
    expect(projection.streamingText).toBe('hello world')
  })
  it('ignores empty deltas', () => {
    const projection = new GaRunProjection()
    projection.apply(envelope(1, 'ANSWER_STREAM_STARTED', { generation: 1 }))
    expect(projection.apply(envelope(2, 'ANSWER_DELTA', { generation: 1, text: '' }))).toBe(false)
    expect(projection.streamingText).toBe('')
  })
  it('replaces the draft when a delta arrives for a newer generation', () => {
    const projection = new GaRunProjection()
    projection.apply(envelope(1, 'ANSWER_STREAM_STARTED', { generation: 1 }))
    projection.apply(envelope(2, 'ANSWER_DELTA', { generation: 1, text: 'stale draft' }))
    projection.apply(envelope(3, 'ANSWER_DELTA', { generation: 2, text: 'fixed text' }))
    expect(projection.streamGeneration).toBe(2)
    expect(projection.streamingText).toBe('fixed text')
  })
  it('reconciles a repair reset without leaking the rejected draft', () => {
    const projection = new GaRunProjection()
    projection.apply(envelope(1, 'ANSWER_STREAM_STARTED', { generation: 1 }))
    projection.apply(envelope(2, 'ANSWER_DELTA', { generation: 1, text: 'stale draft shown first' }))
    projection.apply(envelope(3, 'ANSWER_STREAM_RESET', { supersededGeneration: 1, generation: 2 }))
    expect(projection.streamingText).toBe('')
    projection.apply(envelope(4, 'ANSWER_STREAM_STARTED', { generation: 2 }))
    projection.apply(envelope(5, 'ANSWER_DELTA', { generation: 2, text: 'fixed text stands alone' }))
    expect(projection.streamingText).toBe('fixed text stands alone')
  })
  it('keeps legacy at-once deltas working', () => {
    const projection = new GaRunProjection()
    projection.apply(envelope(1, 'ASSISTANT_DELTA', { text: 'legacy full text' }))
    expect(projection.streamingText).toBe('legacy full text')
    expect(projection.apply(envelope(2, 'ASSISTANT_DELTA', { text: '' }))).toBe(false)
  })
  it('drops duplicate and out-of-order stream sequences', () => {
    const projection = new GaRunProjection()
    projection.apply(envelope(2, 'ANSWER_STREAM_STARTED', { generation: 1 }))
    projection.apply(envelope(3, 'ANSWER_DELTA', { generation: 1, text: 'b' }))
    expect(projection.apply(envelope(3, 'ANSWER_DELTA', { generation: 1, text: 'again' }))).toBe(false)
    expect(projection.apply(envelope(1, 'ANSWER_DELTA', { generation: 1, text: 'late' }))).toBe(false)
    expect(projection.streamingText).toBe('b')
  })
})
describe('ga answer stream terminal cleanup', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [] } as unknown as Response))
  })
  it('clears the transient draft when a run completes', () => {
    const store = useGlobalAssistantStore()
    store.streamingText = 'partial draft...'
    store.streamGeneration = 1
    store.finishTerminal({ type: 'RUN_COMPLETED', errorCode: null, reason: null })
    expect(store.streamingText).toBe('')
    expect(store.streamGeneration).toBeNull()
  })
  it('clears the draft and surfaces the error when a run fails', () => {
    const store = useGlobalAssistantStore()
    store.streamingText = 'partial draft...'
    store.streamGeneration = 2
    store.finishTerminal({ type: 'RUN_FAILED', errorCode: 'MODEL_UNAVAILABLE', reason: 'busy' })
    expect(store.streamingText).toBe('')
    expect(store.streamGeneration).toBeNull()
    expect(store.error?.code).toBe('MODEL_UNAVAILABLE')
  })
  it('clears the draft and marks stopped when a run is cancelled', () => {
    const store = useGlobalAssistantStore()
    store.streamingText = 'partial draft...'
    store.streamGeneration = 1
    store.finishTerminal({ type: 'RUN_CANCELLED', errorCode: null, reason: null })
    expect(store.streamingText).toBe('')
    expect(store.streamGeneration).toBeNull()
    expect(store.stoppedNotice).toBe(true)
  })
})
import { gaStatusMessage, GA_SENDING_STATUS, GA_GENERATING_STATUS } from '@/presentation/globalAssistantPresentation'
describe('ga status presentation', () => {
  it('maps frozen backend runtime messages to Chinese', () => {
    expect(gaStatusMessage('Composing answer')).toBe('正在生成回答…')
    expect(gaStatusMessage('Listing recent projects')).toBe('正在列出最近项目…')
    expect(gaStatusMessage('Searching projects')).toBe('正在搜索项目…')
    expect(gaStatusMessage('Working')).toBe('正在处理…')
  })
  it('passes unknown future messages through verbatim', () => {
    expect(gaStatusMessage('Some future phase')).toBe('Some future phase')
  })
})
describe('ga optimistic status transition', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [] } as unknown as Response))
  })
  it('moves from sending to generating once draft text is visible', () => {
    const store = useGlobalAssistantStore()
    store.currentStatus = GA_SENDING_STATUS
    store.ingestEvent(envelope(1, 'ANSWER_STREAM_STARTED', { generation: 1 }))
    store.ingestEvent(envelope(2, 'ANSWER_DELTA', { generation: 1, text: 'hello' }))
    expect(store.streamingText).toBe('hello')
    expect(store.currentStatus).toBe(GA_GENERATING_STATUS)
  })
  it('keeps a real backend status instead of overwriting it', () => {
    const store = useGlobalAssistantStore()
    store.currentStatus = GA_SENDING_STATUS
    store.ingestEvent(envelope(1, 'STATUS', { message: 'Listing recent projects' }))
    expect(store.currentStatus).toBe('正在列出最近项目…')
    store.ingestEvent(envelope(2, 'ANSWER_DELTA', { generation: 1, text: 'hello' }))
    expect(store.currentStatus).toBe('正在列出最近项目…')
  })
})

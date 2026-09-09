import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useGlobalAssistantStore } from '@/stores/globalAssistantStore'

function jsonOk(body: unknown, status = 200): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response
}

function threadListBody(): unknown {
  return [
    { threadId: 't-new', title: '新会话标题', preview: '最新预览', updatedAt: '2026-09-10T10:00:00Z', createdAt: '2026-09-10T09:00:00Z' },
    { threadId: 't-old', title: '旧会话标题', preview: '旧预览', updatedAt: '2026-09-09T10:00:00Z', createdAt: '2026-09-09T09:00:00Z' },
  ]
}

describe('global assistant conversation library store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('loads history and highlights current thread', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonOk(threadListBody())))
    const store = useGlobalAssistantStore()
    store.threadId = 't-old'
    await store.loadThreads()
    expect(store.threads).toHaveLength(2)
    expect(store.threads[0].threadId).toBe('t-new')
  })

  it('never duplicates threads from backend', async () => {
    const dup = [
      { threadId: 't-1', title: 'a', preview: 'a', updatedAt: '2026-09-10T10:00:00Z', createdAt: '2026-09-10T09:00:00Z' },
      { threadId: 't-1', title: 'a', preview: 'a', updatedAt: '2026-09-10T10:00:00Z', createdAt: '2026-09-10T09:00:00Z' },
    ]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonOk(dup)))
    const store = useGlobalAssistantStore()
    await store.loadThreads()
    expect(store.threads).toHaveLength(1)
  })

  it('switches to an old conversation with canonical messages', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonOk([{ id: 'm-old', threadId: 't-old', role: 'USER', content: '旧问题', runId: null, createdAt: '2026-09-09T10:00:00Z' }]))
      .mockResolvedValueOnce(jsonOk({ threadId: 't-old', summary: '', summaryVersion: 0, workingStateVersion: 0, createdAt: '2026-09-09T09:00:00Z', updatedAt: '2026-09-09T10:00:00Z' }))
      .mockResolvedValueOnce(jsonOk(threadListBody()))
    vi.stubGlobal('fetch', fetchMock)
    const store = useGlobalAssistantStore()
    store.threadId = 't-new'
    store.messages = [{ id: 'm-new', threadId: 't-new', role: 'USER', content: '新问题', runId: null, createdAt: '2026-09-10T10:00:00Z' }]
    store.streamingText = 'stale'
    await store.switchThread('t-old')
    expect(store.threadId).toBe('t-old')
    expect(store.messages[0].content).toBe('旧问题')
    expect(store.streamingText).toBe('')
    expect(store.historyOpen).toBe(false)
    expect(localStorage.getItem('spec-agent:global-assistant:thread:v1')).toBe('t-old')
  })

  it('disables switching and new conversation while a run is active', async () => {
    const store = useGlobalAssistantStore()
    store.threadId = 't-1'
    store.activeRunId = 'r-1'
    store.activeStatus = 'RUNNING'
    const before = store.threadId
    await store.switchThread('t-2')
    expect(store.threadId).toBe(before)
    await store.startNewConversation()
    expect(store.threadId).toBe(before)
    expect(store.canSwitchThread).toBe(false)
  })

  it('recovers thread-not-found by clearing identity and refreshing history', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonOk([]))
      .mockResolvedValueOnce(jsonOk({ code: 'THREAD_NOT_FOUND', message: 'gone' }, 404))
      .mockResolvedValueOnce(jsonOk([]))
    vi.stubGlobal('fetch', fetchMock)
    const store = useGlobalAssistantStore()
    store.threadId = 'missing'
    await store.switchThread('also-missing')
    expect(store.threadId).toBeNull()
  })

  it('creates a new conversation and clears transient projection', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonOk({ threadId: 't-fresh' }, 201)))
    const store = useGlobalAssistantStore()
    store.threadId = 't-old'
    store.messages = [{ id: 'm-1', threadId: 't-old', role: 'USER', content: 'hi', runId: null, createdAt: '2026-09-10T10:00:00Z' }]
    store.streamingText = 'stale'
    await store.startNewConversation()
    expect(store.threadId).toBe('t-fresh')
    expect(store.messages).toHaveLength(0)
    expect(store.streamingText).toBe('')
  })

  it('keeps new empty threads out of history until first message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonOk([])))
    const store = useGlobalAssistantStore()
    await store.loadThreads()
    expect(store.threads).toHaveLength(0)
  })
})

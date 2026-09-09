import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useGlobalAssistantStore } from '@/stores/globalAssistantStore'

function jsonOk(body: unknown, status = 200): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response
}

describe('global assistant steer', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('steers active run with optimistic message', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonOk({ steerId: 's-1', status: 'QUEUED', interruptedRunId: 'r-1', successorRunId: null }))
    vi.stubGlobal('fetch', fetchMock)
    const store = useGlobalAssistantStore()
    store.threadId = 't-1'
    store.activeRunId = 'r-1'
    store.activeStatus = 'RUNNING'
    await store.sendMessage('换方向', { currentPage: 'PROJECTS', selectedEntity: null })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/runs/r-1/steer')
    expect(store.pendingSteer?.message).toBe('换方向')
    expect(store.messages.some((m) => m.content === '换方向')).toBe(true)
  })

  it('preserves draft on steer pending conflict', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonOk({ code: 'GLOBAL_ASSISTANT_STEER_PENDING', message: 'busy' }, 409))
    vi.stubGlobal('fetch', fetchMock)
    const store = useGlobalAssistantStore()
    store.threadId = 't-1'
    store.activeRunId = 'r-1'
    store.activeStatus = 'RUNNING'
    await store.sendMessage('再换一次', { currentPage: 'PROJECTS', selectedEntity: null })
    expect(store.draft).toBe('再换一次')
    expect(store.error?.code).toBe('GLOBAL_ASSISTANT_STEER_PENDING')
    expect(store.messages.some((m) => m.content === '再换一次')).toBe(false)
  })

  it('stop clears pending and keeps thread', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonOk({ activeRun: null, pendingSteer: null }))
    vi.stubGlobal('fetch', fetchMock)
    const store = useGlobalAssistantStore()
    store.threadId = 't-1'
    store.activeRunId = 'r-1'
    store.activeStatus = 'RUNNING'
    store.pendingSteer = { id: 's-1', message: 'x', status: 'PENDING', createdAt: '2026-01-01T00:00:00Z', optimisticId: null }
    await store.cancelActiveRun()
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/threads/t-1/stop')
    expect(store.threadId).toBe('t-1')
  })

  it('delete current idle selects next recent', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonOk(undefined, 204))
      .mockResolvedValueOnce(jsonOk([{ threadId: 't-2', title: 'b', preview: 'b', updatedAt: '2026-09-10T10:00:00Z', createdAt: '2026-09-10T09:00:00Z' }]))
      .mockResolvedValueOnce(jsonOk([]))
      .mockResolvedValueOnce(jsonOk({ threadId: 't-2', summary: '', summaryVersion: 0, workingStateVersion: 0, createdAt: '2026-09-10T09:00:00Z', updatedAt: '2026-09-10T10:00:00Z' }))
      .mockResolvedValueOnce(jsonOk({ activeRun: null, pendingSteer: null }))
    vi.stubGlobal('fetch', fetchMock)
    const store = useGlobalAssistantStore()
    store.threadId = 't-1'
    store.threads = [
      { threadId: 't-1', title: 'a', preview: 'a', updatedAt: '2026-09-10T11:00:00Z', createdAt: '2026-09-10T09:00:00Z' },
      { threadId: 't-2', title: 'b', preview: 'b', updatedAt: '2026-09-10T10:00:00Z', createdAt: '2026-09-10T09:00:00Z' },
    ]
    const ok = await store.deleteThread('t-1')
    expect(ok).toBe(true)
    expect(store.threadId).toBe('t-2')
  })

  it('dedupes optimistic steer against canonical message', async () => {
    const store = useGlobalAssistantStore()
    store.threadId = 't-1'
    store.pendingSteer = { id: 's-1', message: '换方向', status: 'PENDING', createdAt: '2026-01-01T00:00:00Z', optimisticId: 'local-steer-1' }
    store.messages = [
      { id: 'local-steer-1', threadId: 't-1', role: 'USER', content: '换方向', runId: null, createdAt: '2026-01-01T00:00:00Z' },
      { id: 'm-canonical', threadId: 't-1', role: 'USER', content: '换方向', runId: 'r-2', createdAt: '2026-01-01T00:00:01Z' },
    ]
    store.dedupeOptimistic()
    expect(store.messages.some((m) => m.id === 'local-steer-1')).toBe(false)
    expect(store.messages.some((m) => m.id === 'm-canonical')).toBe(true)
  })
})

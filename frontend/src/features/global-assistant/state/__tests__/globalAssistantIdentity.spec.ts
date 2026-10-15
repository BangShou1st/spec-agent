import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import { useGlobalAssistantStore, GA_THREAD_KEY, GA_IDENTITY_KEY } from '../globalAssistantStore'
import { createGaThread, listGaThreads } from '../../api/globalAssistant'
import { openGaEventStream } from '../../api/globalAssistantEvents'

vi.mock('../../api/globalAssistant', async () => ({
  ...(await vi.importActual<typeof import('../../api/globalAssistant')>('../../api/globalAssistant')),
  createGaThread: vi.fn(), listGaThreads: vi.fn(),
}))
vi.mock('../../api/globalAssistantEvents', () => ({ openGaEventStream: vi.fn() }))

describe('global assistant identity isolation', () => {
  beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); vi.clearAllMocks() })
  it('clears private state, initialization and remembered thread on identity change', () => {
    const store = useGlobalAssistantStore(); store.bindIdentity('alice/org')
    store.initialized = true; store.draft = 'private prompt'; store.streamingText = 'private answer'
    localStorage.setItem(GA_THREAD_KEY, 'alice-thread')
    store.bindIdentity('bob/org')
    expect(store.initialized).toBe(false)
    expect(store.draft).toBe('')
    expect(store.streamingText).toBe('')
    expect(store.messages).toEqual([])
    expect(store.threads).toEqual([])
    expect(localStorage.getItem(GA_THREAD_KEY)).toBeNull()
  })
  it('keeps a verified remembered thread on same-user reload but refuses unowned legacy state', () => {
    localStorage.setItem(GA_IDENTITY_KEY, 'alice/org'); localStorage.setItem(GA_THREAD_KEY, 'alice-thread')
    const store = useGlobalAssistantStore(); store.bindIdentity('alice/org')
    expect(localStorage.getItem(GA_THREAD_KEY)).toBe('alice-thread')
    store.bindIdentity(null)
    expect(localStorage.getItem(GA_THREAD_KEY)).toBeNull()
    localStorage.setItem(GA_THREAD_KEY, 'legacy-thread')
    store.bindIdentity('bob/org')
    expect(localStorage.getItem(GA_THREAD_KEY)).toBeNull()
  })
  it('discards a late historical list after another identity starts loading its list', async () => {
    const store = useGlobalAssistantStore(); store.bindIdentity('alice/org')
    let finish!: (value: never[]) => void
    vi.mocked(listGaThreads).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const old = store.loadThreads(); store.bindIdentity('bob/org')
    const marker = { threadId: 'bob-thread' } as Awaited<ReturnType<typeof listGaThreads>>[number]
    vi.mocked(listGaThreads).mockResolvedValueOnce([marker])
    await store.loadThreads(); finish([]); await old
    expect(store.threads).toEqual([marker])
  })
  it('rejects a thread creation even when identity changes between promise settlement and continuation', async () => {
    const store = useGlobalAssistantStore(); store.bindIdentity('alice/org')
    let finish!: (value: Awaited<ReturnType<typeof createGaThread>>) => void
    vi.mocked(createGaThread).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const old = store.ensureThread(); const rejected = expect(old).rejects.toBeInstanceOf(Error)
    finish({ threadId: 'alice-thread' } as Awaited<ReturnType<typeof createGaThread>>)
    queueMicrotask(() => store.bindIdentity('bob/org'))
    await rejected
    expect(store.threadId).toBeNull()
    expect(localStorage.getItem(GA_THREAD_KEY)).toBeNull()
  })
  it('closes the old stream and refuses its callback even if a run id is reused', async () => {
    const close = vi.fn(); vi.mocked(openGaEventStream).mockReturnValue({ close, done: Promise.resolve() })
    const store = useGlobalAssistantStore(); store.bindIdentity('alice/org')
    store.threadId = 'thread'; store.activeRunId = 'run'; store.openStream()
    const callbacks = vi.mocked(openGaEventStream).mock.calls[0]![2]
    store.bindIdentity('bob/org'); store.threadId = 'thread'; store.activeRunId = 'run'
    callbacks.onEvent({ runId: 'run', threadId: 'thread', eventId: 'e', sequence: 1,
      createdAt: '', type: 'ASSISTANT_DELTA', payload: { text: 'old private answer' } })
    await flushPromises()
    expect(close).toHaveBeenCalled()
    expect(store.streamingText).toBe('')
    expect(store.lastSequence).toBe(0)
  })
})

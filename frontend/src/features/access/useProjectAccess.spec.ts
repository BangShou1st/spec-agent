import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, ref, type EffectScope } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { apiClient } from '@/shared/http/client'
import { accessSession } from './accessSession'
import { useProjectAccess, PROJECT_ACCESS_REFRESH_MS, PROJECT_ACCESS_TIMEOUT_MS } from './useProjectAccess'

vi.mock('@/shared/http/client', () => ({ apiClient: { get: vi.fn() } }))
describe('project access presentation', () => {
  let scope: EffectScope
  const get = vi.mocked(apiClient.get)
  beforeEach(() => { vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] }); get.mockReset(); accessSession.identity = null; accessSession.revision = 0; scope = effectScope() })
  afterEach(() => { scope.stop(); vi.useRealTimers() })
  const start = (project = ref('a')) => scope.run(() => useProjectAccess(project))!

  it('starts without privileges and maps write independently from management', async () => {
    get.mockResolvedValue({ capabilities: ['PROJECT_READ', 'PROJECT_WRITE'] })
    const access = start()
    expect(access.canWrite.value).toBe(false)
    await flushPromises()
    expect(access.canWrite.value).toBe(true)
    expect(access.canManage.value).toBe(false)
  })
  it('clears old privileges immediately and ignores a stale project response', async () => {
    let old!: (value: unknown) => void
    get.mockImplementationOnce(() => new Promise(resolve => { old = resolve }))
    get.mockResolvedValueOnce({ capabilities: ['PROJECT_READ'] })
    const project = ref('a'); const access = start(project)
    project.value = 'b'
    await flushPromises()
    old({ capabilities: ['PROJECT_WRITE', 'PROJECT_MANAGE'] })
    await flushPromises()
    expect(access.canWrite.value).toBe(false)
    expect(access.canManage.value).toBe(false)
    expect(get).toHaveBeenLastCalledWith('/projects/b/access', { signal: expect.any(AbortSignal) })
  })
  it('removes permissions during an identity revision and on a failed refresh', async () => {
    get.mockResolvedValueOnce({ capabilities: ['PROJECT_WRITE'] })
    const access = start(); await flushPromises()
    expect(access.canWrite.value).toBe(true)
    get.mockRejectedValueOnce(new Error('offline'))
    accessSession.revision++
    expect(access.canWrite.value).toBe(false)
    await flushPromises()
    expect(access.failed.value).toBe(true)
  })
  it('fails closed on malformed response', async () => {
    get.mockResolvedValue({ capabilities: null })
    const access = start(); await flushPromises()
    expect(access.failed.value).toBe(true)
    expect(access.canWrite.value).toBe(false)
  })
  it('applies remote role revocation without remounting the page', async () => {
    get.mockResolvedValueOnce({ capabilities: ['PROJECT_WRITE'] })
    get.mockResolvedValueOnce({ capabilities: ['PROJECT_READ'] })
    const access = start(); await flushPromises()
    expect(access.canWrite.value).toBe(true)
    await vi.advanceTimersByTimeAsync(PROJECT_ACCESS_REFRESH_MS)
    expect(access.canWrite.value).toBe(false)
    expect(access.failed.value).toBe(false)
  })
  it('expires a stuck renewal, aborts it and ignores its late privilege response', async () => {
    get.mockResolvedValueOnce({ capabilities: ['PROJECT_WRITE'] })
    let late!: (value: unknown) => void
    get.mockImplementationOnce(() => new Promise(resolve => { late = resolve }))
    const access = start(); await flushPromises()
    await vi.advanceTimersByTimeAsync(PROJECT_ACCESS_REFRESH_MS)
    const signal = get.mock.calls[1]![1]!.signal!
    await vi.advanceTimersByTimeAsync(PROJECT_ACCESS_TIMEOUT_MS)
    expect(signal.aborted).toBe(true)
    expect(access.canWrite.value).toBe(false)
    expect(access.failed.value).toBe(true)
    late({ capabilities: ['PROJECT_WRITE'] }); await flushPromises()
    expect(access.canWrite.value).toBe(false)
  })
  it('refreshes on window focus and coalesces focus events while a check is pending', async () => {
    get.mockResolvedValueOnce({ capabilities: ['PROJECT_WRITE'] })
    get.mockImplementationOnce(() => new Promise(() => {}))
    const access = start(); await flushPromises()
    window.dispatchEvent(new Event('focus'))
    window.dispatchEvent(new Event('focus'))
    expect(access.canWrite.value).toBe(false)
    expect(get).toHaveBeenCalledTimes(2)
  })
  it('stops polling and removes focus listeners when the scope is disposed', async () => {
    get.mockResolvedValue({ capabilities: ['PROJECT_WRITE'] })
    start(); await flushPromises(); scope.stop()
    window.dispatchEvent(new Event('focus'))
    await vi.advanceTimersByTimeAsync(PROJECT_ACCESS_REFRESH_MS * 2)
    expect(get).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
  })
})

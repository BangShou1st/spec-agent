import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import SettingsLayout from '../SettingsLayout.vue'
import { renewSession } from '@/features/access/accessSession'

vi.mock('@/features/access/accessSession', async () => ({
  ...(await vi.importActual<typeof import('@/features/access/accessSession')>('@/features/access/accessSession')),
  renewSession: vi.fn(),
}))
describe('settings permission renewal lifecycle', () => {
  beforeEach(() => { vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] }); vi.mocked(renewSession).mockReset() })
  afterEach(() => vi.useRealTimers())
  async function start() {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/settings', component: { template: '<p>Settings content</p>' } }] })
    await router.push('/settings'); await router.isReady()
    return mount(SettingsLayout, { global: { plugins: [router] } })
  }
  it('renews capabilities periodically and stops after unmount', async () => {
    vi.mocked(renewSession).mockResolvedValue(undefined)
    const wrapper = await start()
    await vi.advanceTimersByTimeAsync(5_000)
    expect(renewSession).toHaveBeenCalledTimes(1)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(renewSession).toHaveBeenCalledTimes(1)
  })
  it('coalesces focus events and never re-arms a late refresh after unmount', async () => {
    let finish!: () => void
    vi.mocked(renewSession).mockImplementation(() => new Promise<void>(resolve => { finish = resolve }))
    const wrapper = await start()
    window.dispatchEvent(new Event('focus')); window.dispatchEvent(new Event('focus'))
    expect(renewSession).toHaveBeenCalledTimes(1)
    wrapper.unmount(); finish(); await flushPromises()
    window.dispatchEvent(new Event('focus'))
    await vi.advanceTimersByTimeAsync(10_000)
    expect(renewSession).toHaveBeenCalledTimes(1)
  })
})

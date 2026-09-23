import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useProviderSettingsStore } from '@/features/model-settings/state/providerSettingsStore'
import * as api from '@/features/model-settings/api/modelProviders'

vi.mock('@/features/model-settings/api/modelProviders', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/model-settings/api/modelProviders')>()
  return {
    ...actual,
    getActiveProvider: vi.fn(),
    activateProvider: vi.fn(),
  }
})

describe('provider settings orchestration', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('tab change does not activate the runtime provider', async () => {
    vi.mocked(api.getActiveProvider).mockResolvedValue({ activeProvider: 'OPENCODE_ZEN' })
    const store = useProviderSettingsStore()
    await store.loadActive()
    expect(store.activeProvider).toBe('OPENCODE_ZEN')
    expect(store.viewing).toBe('OPENCODE_ZEN') // 默认看第一个预设 Tab
    store.view('OPENCODE_ZEN')
    expect(store.viewing).toBe('OPENCODE_ZEN')
    expect(store.activeProvider).toBe('OPENCODE_ZEN')
    expect(api.activateProvider).not.toHaveBeenCalled()
  })

  it('explicit activation updates the active provider', async () => {
    vi.mocked(api.getActiveProvider).mockResolvedValue({ activeProvider: 'OPENCODE_ZEN' })
    vi.mocked(api.activateProvider).mockResolvedValue({ activeProvider: 'CUSTOM' })
    const store = useProviderSettingsStore()
    await store.loadActive()
    const ok = await store.activate('CUSTOM')
    expect(ok).toBe(true)
    expect(store.activeProvider).toBe('CUSTOM')
  })

  it('provider configs stay independent across tabs', async () => {
    vi.mocked(api.getActiveProvider).mockResolvedValue({ activeProvider: 'OPENROUTER' })
    const store = useProviderSettingsStore()
    await store.loadActive()
    store.view('OPENCODE_ZEN')
    expect(store.activeProvider).toBe('OPENROUTER')
    store.view('OPENROUTER')
    expect(store.activeProvider).toBe('OPENROUTER')
  })

  it('entering the page shows the currently active provider, not always OpenCode', async () => {
    vi.mocked(api.getActiveProvider).mockResolvedValue({ activeProvider: 'OPENROUTER' })
    const store = useProviderSettingsStore()
    await store.loadActive()
    expect(store.viewing).toBe('OPENROUTER')
  })

  it('stops auto-focusing the active provider once the user picked a tab manually', async () => {
    vi.mocked(api.getActiveProvider).mockResolvedValue({ activeProvider: 'CUSTOM' })
    const store = useProviderSettingsStore()
    await store.loadActive()
    expect(store.viewing).toBe('CUSTOM')
    store.view('OPENCODE_ZEN')
    // 用户手动切走后，再次进入设置页保持用户的选择。
    await store.loadActive()
    expect(store.viewing).toBe('OPENCODE_ZEN')
  })
})

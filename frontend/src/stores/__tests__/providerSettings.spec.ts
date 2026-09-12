import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useProviderSettingsStore } from '@/stores/providerSettingsStore'
import * as api from '@/api/modelProviders'

vi.mock('@/api/modelProviders', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/modelProviders')>()
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
    store.view('CUSTOM')
    expect(store.viewing).toBe('CUSTOM')
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
})

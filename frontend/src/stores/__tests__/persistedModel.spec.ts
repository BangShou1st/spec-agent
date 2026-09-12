import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useOpenRouterStore } from '@/stores/openRouterStore'
import { useCustomProviderStore } from '@/stores/customProviderStore'
import * as api from '@/api/modelProviders'

vi.mock('@/api/modelProviders', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/modelProviders')>()
  return {
    ...actual,
    getOpenRouterStatus: vi.fn(),
    listOpenRouterModels: vi.fn(),
    getCustomStatus: vi.fn(),
    discoverCustom: vi.fn(),
  }
})

describe('persisted model restoration', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('openrouter shows the persisted model before any fetch', async () => {
    vi.mocked(api.getOpenRouterStatus).mockResolvedValue({
      configured: true, maskedKey: 'xx', selectedModel: 'saved:free',
      configRevision: 1, validated: false, active: false,
    })
    const store = useOpenRouterStore()
    await store.loadStatus()
    expect(store.freeModels).toContain('saved:free')
    expect(store.selectedModel).toBe('saved:free')
  })

  it('openrouter flags unavailable after refresh drops it', async () => {
    vi.mocked(api.getOpenRouterStatus).mockResolvedValue({
      configured: true, maskedKey: 'xx', selectedModel: 'saved:free',
      configRevision: 1, validated: true, active: false,
    })
    vi.mocked(api.listOpenRouterModels).mockResolvedValue({ freeModels: ['other:free'] })
    const store = useOpenRouterStore()
    await store.loadStatus()
    await store.refreshModels()
    expect(store.freeModels).toContain('saved:free')
    expect(store.modelUnavailable).toBe(true)
  })

  it('openrouter unavailable persisted stays visible but not saveable', async () => {
    vi.mocked(api.getOpenRouterStatus).mockResolvedValue({
      configured: true, maskedKey: 'xx', selectedModel: 'saved:free',
      configRevision: 1, validated: true, active: false,
    })
    vi.mocked(api.listOpenRouterModels).mockResolvedValue({ freeModels: ['other:free'] })
    const store = useOpenRouterStore()
    await store.loadStatus()
    await store.refreshModels()
    // Visible for transparency.
    expect(store.freeModels).toContain('saved:free')
    // True available list excludes the injected unavailable value.
    expect(store.availableModels).not.toContain('saved:free')
    expect(store.availableModels).toContain('other:free')
    // Component save gating: display includes selected but available does not.
    const canSave = store.selectedModel !== null
      && store.freeModels.includes(store.selectedModel)
      && (store.availableModels.length > 0
        ? store.availableModels.includes(store.selectedModel)
        : !store.modelUnavailable)
    expect(store.modelUnavailable).toBe(true)
    expect(canSave).toBe(false)
  })

  it('openrouter recovers after selecting a real available model', async () => {
    vi.mocked(api.getOpenRouterStatus).mockResolvedValue({
      configured: true, maskedKey: 'xx', selectedModel: 'saved:free',
      configRevision: 1, validated: true, active: false,
    })
    vi.mocked(api.listOpenRouterModels).mockResolvedValue({ freeModels: ['other:free'] })
    const store = useOpenRouterStore()
    await store.loadStatus()
    await store.refreshModels()
    store.selectedModel = 'other:free'
    const isUnavailable = store.availableModels.length > 0
      ? !store.availableModels.includes(store.selectedModel)
      : store.modelUnavailable
    expect(isUnavailable).toBe(false)
    const canSave = store.selectedModel !== null
      && store.freeModels.includes(store.selectedModel)
      && !isUnavailable
    expect(canSave).toBe(true)
  })

  it('custom restores persisted manual mode after reload', async () => {
    vi.mocked(api.getCustomStatus).mockResolvedValue({
      configured: true, apiFormat: 'CHAT_COMPLETIONS', baseUrl: 'http://localhost:11434/v1',
      endpointPreview: 'http://localhost:11434/v1/chat/completions', hasKey: false,
      maskedKey: null, selectedModel: 'typed-model', manualModel: true,
      configRevision: 2, validated: true,
    })
    const store = useCustomProviderStore()
    await store.loadStatus()
    expect(store.manualModel).toBe(true)
    expect(store.selectedModel).toBe('typed-model')
  })

  it('custom shows the persisted discovered model before any fetch', async () => {
    vi.mocked(api.getCustomStatus).mockResolvedValue({
      configured: true, apiFormat: 'RESPONSES', baseUrl: 'https://gateway.example/v1',
      endpointPreview: 'https://gateway.example/v1/responses', hasKey: false,
      maskedKey: null, selectedModel: 'custom-model-a', manualModel: false,
      configRevision: 1, validated: false,
    })
    const store = useCustomProviderStore()
    await store.loadStatus()
    expect(store.discoveredModels).toContain('custom-model-a')
    expect(store.selectedModel).toBe('custom-model-a')
  })

  it('custom unavailable discovered stays visible but not saveable; manual unaffected', async () => {
    vi.mocked(api.getCustomStatus).mockResolvedValue({
      configured: true, apiFormat: 'CHAT_COMPLETIONS', baseUrl: 'http://localhost:11434/v1',
      endpointPreview: 'http://localhost:11434/v1/chat/completions', hasKey: false,
      maskedKey: null, selectedModel: 'custom-model-a', manualModel: false,
      configRevision: 1, validated: false,
    })
    vi.mocked(api.discoverCustom).mockResolvedValue({
      models: ['other-model'], manualModel: false, endpointPreview: 'http://localhost:11434/v1/chat/completions',
    })
    const store = useCustomProviderStore()
    await store.loadStatus()
    await store.discover(null)
    expect(store.discoveredModels).toContain('custom-model-a')
    expect(store.availableModels).not.toContain('custom-model-a')
    expect(store.modelUnavailable).toBe(true)
    const selected = store.selectedModel.trim()
    const isUnavailable = store.manualModel
      ? false
      : (store.availableModels.length > 0
        ? !store.availableModels.includes(selected)
        : store.modelUnavailable)
    expect(isUnavailable).toBe(true)
    const canSave = store.baseUrl.trim().length > 0 && selected.length > 0 && !isUnavailable
    expect(canSave).toBe(false)
    // Manual mode is unaffected: same selected value in manual mode saves.
    store.manualModel = true
    const manualBlocked = store.manualModel
      ? false
      : (store.availableModels.length > 0
        ? !store.availableModels.includes(selected)
        : store.modelUnavailable)
    expect(manualBlocked).toBe(false)
  })
})

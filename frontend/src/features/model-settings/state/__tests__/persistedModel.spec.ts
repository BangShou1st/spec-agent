// 文件名:persistedModel.spec.ts
// 用途:已持久化模型选择的可见性规则单测:展示列表注入已存值、
//       可用列表不含已下架值、保存门槛据此禁止写回不可用模型、手动模式不受影响。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useOpenRouterStore } from '@/features/model-settings/state/openRouterStore'
import { useCustomProviderStore } from '@/features/model-settings/state/customProviderStore'
import * as api from '@/features/model-settings/api/modelProviders'

vi.mock('@/features/model-settings/api/modelProviders', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/model-settings/api/modelProviders')>()
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
    expect(store.allModels).toContain('saved:free')
    expect(store.selectedModel).toBe('saved:free')
  })

  it('openrouter flags unavailable after refresh drops it', async () => {
    vi.mocked(api.getOpenRouterStatus).mockResolvedValue({
      configured: true, maskedKey: 'xx', selectedModel: 'saved:free',
      configRevision: 1, validated: true, active: false,
    })
    vi.mocked(api.listOpenRouterModels).mockResolvedValue({ allModels: ['other:free'], freeModels: ['other:free'] })
    const store = useOpenRouterStore()
    await store.loadStatus()
    await store.refreshModels()
    expect(store.allModels).toContain('saved:free')
    expect(store.modelUnavailable).toBe(true)
  })

  it('openrouter unavailable persisted stays visible but not saveable', async () => {
    vi.mocked(api.getOpenRouterStatus).mockResolvedValue({
      configured: true, maskedKey: 'xx', selectedModel: 'saved:free',
      configRevision: 1, validated: true, active: false,
    })
    vi.mocked(api.listOpenRouterModels).mockResolvedValue({ allModels: ['other:free'], freeModels: ['other:free'] })
    const store = useOpenRouterStore()
    await store.loadStatus()
    await store.refreshModels()
    // 注入值保证可见性。
    expect(store.allModels).toContain('saved:free')
    // 真实可用列表不包含注入的不可用值。
    expect(store.availableModels).not.toContain('saved:free')
    expect(store.availableModels).toContain('other:free')
    // 组件保存门槛:展示列表含选中项而可用列表不含。
    const canSave = store.selectedModel !== null
      && store.allModels.includes(store.selectedModel)
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
    vi.mocked(api.listOpenRouterModels).mockResolvedValue({ allModels: ['other:free'], freeModels: ['other:free'] })
    const store = useOpenRouterStore()
    await store.loadStatus()
    await store.refreshModels()
    store.selectedModel = 'other:free'
    const isUnavailable = store.availableModels.length > 0
      ? !store.availableModels.includes(store.selectedModel)
      : store.modelUnavailable
    expect(isUnavailable).toBe(false)
    const canSave = store.selectedModel !== null
      && store.allModels.includes(store.selectedModel)
      && !isUnavailable
    expect(canSave).toBe(true)
  })

  it('custom restores persisted manual mode after reload', async () => {
    vi.mocked(api.getCustomStatus).mockResolvedValue({
      configured: true, apiFormat: 'CHAT_COMPLETIONS', baseUrl: 'http://localhost:11434/v1',
      endpointPreview: 'http://localhost:11434/v1/chat/completions', hasKey: false,
      maskedKey: null, selectedModel: 'typed-model', manualModel: true,
      displayName: 'Local Gateway', configRevision: 2, validated: true,
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
      displayName: null, configRevision: 1, validated: false,
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
      displayName: null, configRevision: 1, validated: false,
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
    // 手动模式不受影响:同样的选中值在手动模式下可以保存。
    store.manualModel = true
    const manualBlocked = store.manualModel
      ? false
      : (store.availableModels.length > 0
        ? !store.availableModels.includes(selected)
        : store.modelUnavailable)
    expect(manualBlocked).toBe(false)
  })
})

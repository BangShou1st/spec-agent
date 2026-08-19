import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/client'
import { getOpenCodeSettings, probeOpenCode, saveOpenCode } from '@/api/modelSettings'
import { useModelSettingsStore } from '@/stores/modelSettingsStore'

vi.mock('@/api/modelSettings', () => ({
  getOpenCodeSettings: vi.fn(),
  probeOpenCode: vi.fn(),
  saveOpenCode: vi.fn(),
}))

const mockedGet = vi.mocked(getOpenCodeSettings)
const mockedProbe = vi.mocked(probeOpenCode)
const mockedSave = vi.mocked(saveOpenCode)

describe('model settings store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockedGet.mockResolvedValue({ configured: false, maskedKey: null, selectedModel: null })
  })

  it('keeps probe model selection explicit and commits only after save succeeds', async () => {
    mockedProbe.mockResolvedValue({ freeModels: ['alpha-free', 'beta-free'] })
    mockedSave.mockResolvedValue({ configured: true, maskedKey: '…1234', selectedModel: 'beta-free' })
    const store = useModelSettingsStore()

    await store.loadStatus()
    await expect(store.probe(' key ')).resolves.toEqual(['alpha-free', 'beta-free'])
    expect(store.selectedModel).toBeNull()
    store.selectedModel = 'beta-free'
    await expect(store.save(' key ', 'beta-free')).resolves.toBe(true)
    expect(store.status?.selectedModel).toBe('beta-free')
    expect(store.freeModels).toEqual([])
    expect(mockedSave).toHaveBeenCalledWith('key', 'beta-free')
  })

  it('preserves a working status when a candidate probe fails', async () => {
    const store = useModelSettingsStore()
    store.status = { configured: true, maskedKey: '…1234', selectedModel: 'alpha-free' }
    mockedProbe.mockRejectedValue(new ApiError('The model provider is temporarily rate limited', 'MODEL_PROVIDER_RATE_LIMITED', 429))

    await expect(store.probe('new-key')).resolves.toEqual([])
    expect(store.status?.selectedModel).toBe('alpha-free')
    expect(store.error?.code).toBe('MODEL_PROVIDER_RATE_LIMITED')
  })
})

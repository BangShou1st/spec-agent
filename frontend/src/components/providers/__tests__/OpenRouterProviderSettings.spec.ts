import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import OpenRouterProviderSettings from '@/components/providers/OpenRouterProviderSettings.vue'
import {
  getOpenRouterStatus,
  listOpenRouterModels,
  probeOpenRouter,
  saveOpenRouter,
  validateOpenRouter,
} from '@/api/modelProviders'

vi.mock('@/api/modelProviders', () => ({
  getOpenRouterStatus: vi.fn(),
  listOpenRouterModels: vi.fn(),
  probeOpenRouter: vi.fn(),
  saveOpenRouter: vi.fn(),
  validateOpenRouter: vi.fn(),
  getActiveProvider: vi.fn(),
  activateProvider: vi.fn(),
}))

const mockedGet = vi.mocked(getOpenRouterStatus)
const mockedList = vi.mocked(listOpenRouterModels)
const mockedProbe = vi.mocked(probeOpenRouter)
const mockedSave = vi.mocked(saveOpenRouter)
const mockedValidate = vi.mocked(validateOpenRouter)

async function mountCard(): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(OpenRouterProviderSettings)
  await flushPromises()
  return wrapper
}

describe('OpenRouterProviderSettings credential visibility', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockedList.mockResolvedValue({ allModels: ['alpha:free'], freeModels: ['alpha:free'] })
  })

  it('configured card shows the masked key only, no credential form, no revision line', async () => {
    mockedGet.mockResolvedValue({ configured: true, maskedKey: '••••5678', selectedModel: 'alpha:free', configRevision: 2, validated: true, active: false })
    const wrapper = await mountCard()

    expect(wrapper.get('[data-test="openrouter-masked"]').text()).toContain('••••5678')
    expect(wrapper.find('[data-test="openrouter-api-key"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="openrouter-change-key"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('配置版本')
    // 已配置时用已存密钥自动拉取模型。
    expect(mockedList).toHaveBeenCalledTimes(1)
  })

  it('unconfigured card shows the credential form', async () => {
    mockedGet.mockResolvedValue({ configured: false, maskedKey: null, selectedModel: null, configRevision: 0, validated: false, active: false })
    const wrapper = await mountCard()

    expect(wrapper.find('[data-test="openrouter-api-key"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="openrouter-change-key"]').exists()).toBe(false)
  })

  it('changing the key reveals the input, requires an explicit new key, and cancel restores saved-key models', async () => {
    mockedGet.mockResolvedValue({ configured: true, maskedKey: '••••5678', selectedModel: 'alpha:free', configRevision: 2, validated: true, active: false })
    const wrapper = await mountCard()

    await wrapper.get('[data-test="openrouter-change-key"]').trigger('click')
    expect(wrapper.find('[data-test="openrouter-api-key"]').exists()).toBe(true)
    // 更换密钥时必须输入新 Key，不允许空密钥静默保存。
    expect(wrapper.find('[data-test="openrouter-save-test"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-test="openrouter-api-key"]').setValue('new-secret')
    expect(wrapper.find('[data-test="openrouter-save-test"]').attributes('disabled')).toBeUndefined()

    await wrapper.get('[data-test="openrouter-cancel-change"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="openrouter-api-key"]').exists()).toBe(false)
    // 取消后用已存密钥重新拉取模型。
    expect(mockedList).toHaveBeenCalledTimes(2)
    expect((wrapper.get('[data-test="openrouter-model"]').element as HTMLSelectElement).value).toBe('alpha:free')
  })

  it('saving a model-only change sends null key and never renders the raw key', async () => {
    mockedGet.mockResolvedValue({ configured: true, maskedKey: '••••old1', selectedModel: 'alpha:free', configRevision: 2, validated: true, active: false })
    mockedList.mockResolvedValue({ allModels: ['alpha:free', 'beta:free'], freeModels: ['alpha:free', 'beta:free'] })
    mockedSave.mockResolvedValue({ configured: true, maskedKey: '••••old1', selectedModel: 'beta:free', configRevision: 3, validated: true, active: false })
    mockedValidate.mockResolvedValue({ configured: true, maskedKey: '••••old1', selectedModel: 'beta:free', configRevision: 3, validated: true, active: false })
    const wrapper = await mountCard()

    await wrapper.get('[data-test="openrouter-model"]').setValue('beta:free')
    await wrapper.get('[data-test="openrouter-save-test"]').trigger('click')
    await flushPromises()

    expect(mockedSave).toHaveBeenCalledWith(null, 'beta:free')
    expect(wrapper.text()).not.toContain('new-secret')
    expect(wrapper.get('[data-test="openrouter-masked"]').text()).toContain('••••old1')
  })

  it('probing with a new key populates the model list during credential change', async () => {
    mockedGet.mockResolvedValue({ configured: true, maskedKey: '••••old1', selectedModel: 'alpha:free', configRevision: 2, validated: true, active: false })
    mockedProbe.mockResolvedValue({ allModels: ['gamma:free'], freeModels: ['gamma:free'] })
    const wrapper = await mountCard()

    await wrapper.get('[data-test="openrouter-change-key"]').trigger('click')
    await wrapper.get('[data-test="openrouter-api-key"]').setValue('candidate-key')
    await wrapper.get('[data-test="openrouter-probe"]').trigger('click')
    await flushPromises()

    expect(mockedProbe).toHaveBeenCalledWith('candidate-key')
    // 探测后旧选择被清空，回到占位项，需重新选择。
    expect((wrapper.get('[data-test="openrouter-model"]').element as HTMLSelectElement).selectedIndex).toBe(0)
  })
})

import { accessSession } from '@/features/access/accessSession'
// 文件名:KiloProviderSettings.spec.ts
// 用途:Kilo Gateway 卡片组件测试:未配置时展示密钥表单、已配置时仅展示脱敏密钥并自动
//       拉取模型、免费模型可无密钥保存、付费模型缺密钥时禁止保存、探测后需重新选择模型。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import KiloProviderSettings from '@/features/model-settings/components/KiloProviderSettings.vue'
import {
  getKiloSettings,
  listKiloModels,
  probeKilo,
  saveKilo,
  validateKilo,
} from '@/features/model-settings/api/kiloSettings'

vi.mock('@/features/model-settings/api/kiloSettings', () => ({
  getKiloSettings: vi.fn(),
  listKiloModels: vi.fn(),
  probeKilo: vi.fn(),
  saveKilo: vi.fn(),
  saveKiloModel: vi.fn(),
  validateKilo: vi.fn(),
}))

const mockedGet = vi.mocked(getKiloSettings)
const mockedList = vi.mocked(listKiloModels)
const mockedProbe = vi.mocked(probeKilo)
const mockedSave = vi.mocked(saveKilo)
const mockedValidate = vi.mocked(validateKilo)

async function mountCard(): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(KiloProviderSettings)
  await flushPromises()
  return wrapper
}

describe('KiloProviderSettings', () => {
  beforeEach(() => {
    accessSession.identity = { mode: 'OIDC', authenticated: true, userId: 'editor', orgId: 'org', displayName: 'Editor', orgName: 'Org', orgRole: 'ADMIN', orgCapabilities: ['CREDENTIAL_MANAGE'], memberships: [] }
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockedGet.mockResolvedValue({ configured: false, maskedKey: null, selectedModel: null })
    mockedList.mockResolvedValue({ allModels: [], freeModels: [] })
  })

  it('unconfigured card shows the credential form', async () => {
    const wrapper = await mountCard()

    expect(wrapper.find('[data-test="kilo-api-key"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="kilo-change-key"]').exists()).toBe(false)
  })

  it('configured card shows the masked key only and auto-fetches models with the stored key', async () => {
    mockedGet.mockResolvedValue({ configured: true, maskedKey: '••••1234', selectedModel: 'alpha:free' })
    mockedList.mockResolvedValue({ allModels: ['alpha:free'], freeModels: ['alpha:free'] })
    const wrapper = await mountCard()

    expect(wrapper.get('[data-test="kilo-masked"]').text()).toContain('••••1234')
    expect(wrapper.get('[data-test="kilo-current"]').text()).toContain('alpha:free')
    expect(wrapper.find('[data-test="kilo-api-key"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="kilo-change-key"]').exists()).toBe(true)
    // 已配置时用已存密钥自动拉取一次模型目录。
    expect(mockedList).toHaveBeenCalledTimes(1)
  })

  it('saves a free model without a key', async () => {
    mockedList.mockResolvedValue({ allModels: ['alpha:free'], freeModels: ['alpha:free'] })
    mockedSave.mockResolvedValue({ configured: true, maskedKey: null, selectedModel: 'alpha:free' })
    const wrapper = await mountCard()

    await wrapper.get('[data-test="kilo-model"]').setValue('alpha:free')
    expect(wrapper.find('[data-test="kilo-save-test"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[data-test="kilo-save-test"]').trigger('click')
    await flushPromises()

    expect(mockedSave).toHaveBeenCalledWith(null, 'alpha:free')
    expect(wrapper.get('[data-test="kilo-current"]').text()).toContain('未配置（免费模型无需）')
  })

  it('requires an API key when a paid model is selected without a saved key', async () => {
    mockedList.mockResolvedValue({ allModels: ['alpha:free', 'paid-model'], freeModels: ['alpha:free'] })
    const wrapper = await mountCard()

    await wrapper.get('[data-test="kilo-model"]').setValue('paid-model')
    expect(wrapper.find('[data-test="kilo-save-test"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-test="kilo-api-key"]').setValue('paid-key')
    expect(wrapper.find('[data-test="kilo-save-test"]').attributes('disabled')).toBeUndefined()
  })

  it('probing with a candidate key repopulates the list and clears the selection', async () => {
    mockedGet.mockResolvedValue({ configured: true, maskedKey: '••••old1', selectedModel: 'alpha:free' })
    mockedProbe.mockResolvedValue({ allModels: ['gamma:free'], freeModels: ['gamma:free'] })
    const wrapper = await mountCard()

    await wrapper.get('[data-test="kilo-change-key"]').trigger('click')
    await wrapper.get('[data-test="kilo-api-key"]').setValue('candidate-key')
    await wrapper.get('[data-test="kilo-probe"]').trigger('click')
    await flushPromises()

    expect(mockedProbe).toHaveBeenCalledWith('candidate-key')
    expect((wrapper.get('[data-test="kilo-model"]').element as HTMLSelectElement).selectedIndex).toBe(0)
  })

  it('revalidates the stored pair without touching the credential', async () => {
    mockedGet.mockResolvedValue({ configured: true, maskedKey: '••••old1', selectedModel: 'alpha:free' })
    mockedList.mockResolvedValue({ allModels: ['alpha:free'], freeModels: ['alpha:free'] })
    mockedValidate.mockResolvedValue({ configured: true, maskedKey: '••••old1', selectedModel: 'alpha:free' })
    const wrapper = await mountCard()

    await wrapper.get('[data-test="kilo-validate"]').trigger('click')
    await flushPromises()

    expect(mockedValidate).toHaveBeenCalledTimes(1)
    expect(mockedSave).not.toHaveBeenCalled()
  })
})

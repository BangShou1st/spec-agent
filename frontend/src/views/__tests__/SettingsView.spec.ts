import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SettingsView from '@/views/SettingsView.vue'
import { ApiError } from '@/api/client'
import {
  getOpenCodeSettings,
  listOpenCodeModels,
  probeOpenCode,
  saveOpenCode,
  saveOpenCodeModel,
  validateOpenCode,
} from '@/api/modelSettings'

vi.mock('@/api/modelSettings', () => ({
  getOpenCodeSettings: vi.fn(),
  listOpenCodeModels: vi.fn(),
  probeOpenCode: vi.fn(),
  saveOpenCode: vi.fn(),
  saveOpenCodeModel: vi.fn(),
  validateOpenCode: vi.fn(),
}))

const mockedGet = vi.mocked(getOpenCodeSettings)
const mockedList = vi.mocked(listOpenCodeModels)
const mockedProbe = vi.mocked(probeOpenCode)
const mockedSave = vi.mocked(saveOpenCode)
const mockedSaveModel = vi.mocked(saveOpenCodeModel)
const mockedValidate = vi.mocked(validateOpenCode)

// OpenCode Zen 是常驻 Tab 之一；用例先切到该 Tab。
async function mountOnOpenCodeTab(): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(SettingsView)
  await flushPromises()
  await wrapper.get('[data-test="provider-tab-opencode_zen"]').trigger('click')
  await flushPromises()
  return wrapper
}

describe('SettingsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockedGet.mockResolvedValue({ configured: false, maskedKey: null, selectedModel: null })
    mockedList.mockResolvedValue({ allModels: [], freeModels: [] })
  })

  it('probes, requires an explicit model selection, then clears the key after save', async () => {
    mockedProbe.mockResolvedValue({ allModels: ['alpha-free'], freeModels: ['alpha-free'] })
    mockedSave.mockResolvedValue({ configured: true, maskedKey: '…1234', selectedModel: 'alpha-free' })
    mockedValidate.mockResolvedValue({ configured: true, maskedKey: '…1234', selectedModel: 'alpha-free' })
    const wrapper = await mountOnOpenCodeTab()
    await flushPromises()

    const key = wrapper.find('[data-test="opencode-api-key"]')
    await key.setValue('secret')
    await wrapper.find('[data-test="opencode-probe"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="opencode-model"]').attributes('disabled')).toBeUndefined()
    // 保存并测试必须显式选模型，不允许空选择静默保存。
    expect(wrapper.find('[data-test="opencode-save-test"]').attributes('disabled')).toBeDefined()

    await wrapper.find('[data-test="opencode-model"]').setValue('alpha-free')
    expect(wrapper.find('[data-test="opencode-save-test"]').attributes('disabled')).toBeUndefined()
    await wrapper.find('[data-test="opencode-save-test"]').trigger('click')
    await flushPromises()
    expect(mockedSave).toHaveBeenCalledWith('secret', 'alpha-free')
    expect(wrapper.find('[data-test="opencode-api-key"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="opencode-masked"]').text()).toContain('…1234')
  })

  it('shows an existing configuration as masked status and never renders a raw key', async () => {
    mockedGet.mockResolvedValue({ configured: true, maskedKey: '••••1234', selectedModel: 'alpha-free' })
    const wrapper = await mountOnOpenCodeTab()
    await flushPromises()

    // 状态胶囊与 OpenRouter 卡同源：已配置且已保存过的配置视为有效。
    expect(wrapper.get('[data-test="opencode-state"]').text()).toMatch(/当前使用|配置有效/)
    expect(wrapper.get('[data-test="opencode-current"]').text()).toContain('••••1234')
    expect(wrapper.get('[data-test="opencode-current"]').text()).toContain('alpha-free')
    expect(wrapper.text()).not.toContain('candidate-api-key')
  })

  it('retries the failed probe instead of merely clearing the error', async () => {
    let attempts = 0
    mockedProbe.mockImplementation(async () => {
      attempts += 1
      if (attempts === 1) {
        throw new ApiError('Unable to connect', 'NETWORK_ERROR', 503)
      }
      return { allModels: ['alpha-free'], freeModels: ['alpha-free'] }
    })
    const wrapper = await mountOnOpenCodeTab()
    await flushPromises()
    await wrapper.get('[data-test="opencode-api-key"]').setValue('candidate-api-key')
    await wrapper.get('[data-test="opencode-probe"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="opencode-error"]').text()).toContain('无法连接到 OpenCode')
    await wrapper.get('[data-test="opencode-error"]').find('button').trigger('click')
    await flushPromises()

    expect(attempts).toBe(2)
    expect(wrapper.get('[data-test="opencode-model"]').attributes('disabled')).toBeUndefined()
  })

  it('keeps the previous working configuration when save fails', async () => {
    mockedGet.mockResolvedValue({ configured: true, maskedKey: '••••old1', selectedModel: 'old-free' })
    mockedProbe.mockResolvedValue({ allModels: ['new-free'], freeModels: ['new-free'] })
    mockedSave.mockRejectedValue(new ApiError('Save failed', 'NETWORK_ERROR', 503))
    const wrapper = await mountOnOpenCodeTab()
    await flushPromises()
    await wrapper.get('[data-test="opencode-change-key"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="opencode-api-key"]').setValue('new-api-key')
    await wrapper.get('[data-test="opencode-probe"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="opencode-model"]').setValue('new-free')
    await wrapper.get('[data-test="opencode-save-test"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="opencode-masked"]').text()).toContain('••••old1')
    expect(wrapper.get('[data-test="opencode-current"]').text()).toContain('old-free')
    expect(wrapper.text()).not.toContain('new-api-key')
  })

  it('lists saved-key models on load and saves a model-only change with one 保存并测试', async () => {
    mockedGet.mockResolvedValue({ configured: true, maskedKey: '••••old1', selectedModel: 'old-free' })
    mockedList.mockResolvedValue({ allModels: ['old-free', 'new-free'], freeModels: ['old-free', 'new-free'] })
    mockedSaveModel.mockResolvedValue({ configured: true, maskedKey: '••••old1', selectedModel: 'new-free' })
    const wrapper = await mountOnOpenCodeTab()
    await flushPromises()

    expect(mockedList).toHaveBeenCalledTimes(1)
    // 已配置且未更换密钥时不需要重新输入密钥，改模型即可保存并测试。
    expect(wrapper.find('[data-test="opencode-api-key"]').exists()).toBe(false)
    await wrapper.get('[data-test="opencode-model"]').setValue('new-free')
    expect(wrapper.find('[data-test="opencode-save-test"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[data-test="opencode-save-test"]').trigger('click')
    await flushPromises()

    expect(mockedSaveModel).toHaveBeenCalledWith('new-free')
    expect(mockedSave).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="opencode-current"]').text()).toContain('new-free')
  })

  it('cancels API-key replacement by restoring the saved model and reloading choices', async () => {
    mockedGet.mockResolvedValue({ configured: true, maskedKey: '••••old1', selectedModel: 'old-free' })
    mockedList.mockResolvedValue({ allModels: ['old-free', 'new-free'], freeModels: ['old-free', 'new-free'] })
    const wrapper = await mountOnOpenCodeTab()
    await flushPromises()

    await wrapper.get('[data-test="opencode-change-key"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="opencode-api-key"]').setValue('candidate')

    await wrapper.get('[data-test="opencode-cancel-change"]').trigger('click')
    await flushPromises()

    expect(mockedList).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-test="opencode-api-key"]').exists()).toBe(false)
    expect((wrapper.get('[data-test="opencode-model"]').element as HTMLSelectElement).value)
      .toBe('old-free')
  })

  it('重新测试 revalidates the stored pair without touching the credential', async () => {
    mockedGet.mockResolvedValue({ configured: true, maskedKey: '••••old1', selectedModel: 'old-free' })
    mockedList.mockResolvedValue({ allModels: ['old-free'], freeModels: ['old-free'] })
    mockedValidate.mockResolvedValue({ configured: true, maskedKey: '••••old1', selectedModel: 'old-free' })
    const wrapper = await mountOnOpenCodeTab()
    await flushPromises()

    await wrapper.get('[data-test="opencode-validate"]').trigger('click')
    await flushPromises()

    expect(mockedValidate).toHaveBeenCalledTimes(1)
    expect(mockedSave).not.toHaveBeenCalled()
    expect(mockedSaveModel).not.toHaveBeenCalled()
  })
})

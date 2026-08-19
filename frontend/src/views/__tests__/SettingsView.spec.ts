import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SettingsView from '@/views/SettingsView.vue'
import { ApiError } from '@/api/client'
import { getOpenCodeSettings, probeOpenCode, saveOpenCode } from '@/api/modelSettings'

vi.mock('@/api/modelSettings', () => ({
  getOpenCodeSettings: vi.fn(),
  probeOpenCode: vi.fn(),
  saveOpenCode: vi.fn(),
}))

const mockedGet = vi.mocked(getOpenCodeSettings)
const mockedProbe = vi.mocked(probeOpenCode)
const mockedSave = vi.mocked(saveOpenCode)

describe('SettingsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockedGet.mockResolvedValue({ configured: false, maskedKey: null, selectedModel: null })
  })

  it('probes, requires an explicit free-model selection, then clears the key after save', async () => {
    mockedProbe.mockResolvedValue({ freeModels: ['alpha-free'] })
    mockedSave.mockResolvedValue({ configured: true, maskedKey: '…1234', selectedModel: 'alpha-free' })
    const wrapper = mount(SettingsView)
    await flushPromises()

    const key = wrapper.find('[data-test="opencode-api-key"]')
    await key.setValue('secret')
    await wrapper.find('[data-test="probe-opencode"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="opencode-model"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-test="save-opencode"]').attributes('disabled')).toBeDefined()

    await wrapper.find('[data-test="opencode-model"]').setValue('alpha-free')
    expect(wrapper.find('[data-test="save-opencode"]').attributes('disabled')).toBeUndefined()
    await wrapper.find('[data-test="save-opencode"]').trigger('click')
    await flushPromises()
    expect(mockedSave).toHaveBeenCalledWith('secret', 'alpha-free')
    expect((key.element as HTMLInputElement).value).toBe('')
    expect(wrapper.find('[data-test="masked-key"]').text()).toContain('…1234')
  })

  it('shows an existing configuration as masked status and never renders a raw key', async () => {
    mockedGet.mockResolvedValue({ configured: true, maskedKey: '••••1234', selectedModel: 'alpha-free' })
    const wrapper = mount(SettingsView)
    await flushPromises()

    expect(wrapper.get('[data-test="configuration-status"]').text()).toContain('已配置')
    expect(wrapper.get('[data-test="current-config"]').text()).toContain('••••1234')
    expect(wrapper.get('[data-test="current-config"]').text()).toContain('alpha-free')
    expect(wrapper.text()).not.toContain('candidate-api-key')
  })

  it('retries the failed probe instead of merely clearing the error', async () => {
    let attempts = 0
    mockedProbe.mockImplementation(async () => {
      attempts += 1
      if (attempts === 1) {
        throw new ApiError('Unable to connect', 'NETWORK_ERROR', 503)
      }
      return { freeModels: ['alpha-free'] }
    })
    const wrapper = mount(SettingsView)
    await flushPromises()
    await wrapper.get('[data-test="opencode-api-key"]').setValue('candidate-api-key')
    await wrapper.get('[data-test="probe-opencode"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="settings-error"]').text()).toContain('无法连接到 OpenCode')
    await wrapper.get('[data-test="settings-error"]').find('button').trigger('click')
    await flushPromises()

    expect(attempts).toBe(2)
    expect(wrapper.get('[data-test="opencode-model"]').attributes('disabled')).toBeUndefined()
  })

  it('keeps the previous working configuration when save fails', async () => {
    mockedGet.mockResolvedValue({ configured: true, maskedKey: '••••old1', selectedModel: 'old-free' })
    mockedProbe.mockResolvedValue({ freeModels: ['new-free'] })
    mockedSave.mockRejectedValue(new ApiError('Save failed', 'NETWORK_ERROR', 503))
    const wrapper = mount(SettingsView)
    await flushPromises()
    await wrapper.get('[data-test="opencode-api-key"]').setValue('new-api-key')
    await wrapper.get('[data-test="probe-opencode"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="opencode-model"]').setValue('new-free')
    await wrapper.get('[data-test="save-opencode"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="masked-key"]').text()).toContain('••••old1')
    expect(wrapper.get('[data-test="current-config"]').text()).toContain('old-free')
    expect(wrapper.text()).not.toContain('new-api-key')
  })
})

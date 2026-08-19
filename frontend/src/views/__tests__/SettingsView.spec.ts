import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SettingsView from '@/views/SettingsView.vue'
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
})

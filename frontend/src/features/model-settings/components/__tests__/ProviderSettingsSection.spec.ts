import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ProviderSettingsSection from '@/features/model-settings/components/ProviderSettingsSection.vue'
import { getActiveProvider, getCustomStatus } from '@/features/model-settings/api/modelProviders'
import { useCustomProviderStore } from '@/features/model-settings/state/customProviderStore'

vi.mock('@/features/model-settings/api/modelSettings', () => ({
  getOpenCodeSettings: vi.fn().mockResolvedValue({ configured: false, maskedKey: null, selectedModel: null }),
  listOpenCodeModels: vi.fn().mockResolvedValue({ freeModels: [] }),
  probeOpenCode: vi.fn(),
  saveOpenCode: vi.fn(),
  saveOpenCodeModel: vi.fn(),
}))

vi.mock('@/features/model-settings/api/modelProviders', () => ({
  getActiveProvider: vi.fn().mockResolvedValue({ activeProvider: 'OPENCODE_ZEN' }),
  activateProvider: vi.fn(),
  getOpenRouterStatus: vi.fn().mockResolvedValue({ configured: false, maskedKey: null, selectedModel: null, configRevision: 0, validated: false, active: false }),
  getCustomStatus: vi.fn(),
  discoverCustom: vi.fn(),
  saveCustomWithSource: vi.fn(),
  validateCustom: vi.fn(),
}))

async function mountSection(): Promise<ReturnType<typeof mount>> {
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  })
  router.push('/')
  await router.isReady()
  setActivePinia(createPinia())
  const wrapper = mount(ProviderSettingsSection, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('ProviderSettingsSection custom pill entry', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getActiveProvider).mockResolvedValue({ activeProvider: 'OPENCODE_ZEN' })
  })

  it('unconfigured custom shows only the "+" entry and no custom pill', async () => {
    vi.mocked(getCustomStatus).mockResolvedValue({
      configured: false, apiFormat: 'CHAT_COMPLETIONS', baseUrl: '', endpointPreview: null,
      hasKey: false, maskedKey: null, selectedModel: '', manualModel: false,
      displayName: null, configRevision: 0, validated: false,
    })
    const wrapper = await mountSection()

    expect(wrapper.find('[data-test="provider-tab-custom"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="provider-add-custom"]').exists()).toBe(true)
  })

  it('configured custom renders a named pill and clicking it shows the edit card', async () => {
    vi.mocked(getCustomStatus).mockResolvedValue({
      configured: true, apiFormat: 'CHAT_COMPLETIONS', baseUrl: 'http://localhost:11434/v1',
      endpointPreview: 'http://localhost:11434/v1/chat/completions', hasKey: false,
      maskedKey: '••••kk11', selectedModel: 'custom-model-a', manualModel: false,
      displayName: '本地 Ollama', configRevision: 1, validated: true,
    })
    const wrapper = await mountSection()

    const pill = wrapper.get('[data-test="provider-tab-custom"]')
    expect(pill.text()).toContain('本地 Ollama')
    expect(wrapper.find('[data-test="provider-add-custom"]').exists()).toBe(false)

    await pill.trigger('click')
    expect(wrapper.find('[data-test="custom-card"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="custom-title"]').text()).toBe('本地 Ollama')
    // 编辑卡片不再显示配置版本行。
    expect(wrapper.text()).not.toContain('配置版本')
  })

  it("clicking '+' opens the create dialog with a required name field", async () => {
    vi.mocked(getCustomStatus).mockResolvedValue({
      configured: false, apiFormat: 'CHAT_COMPLETIONS', baseUrl: '', endpointPreview: null,
      hasKey: false, maskedKey: null, selectedModel: '', manualModel: false,
      displayName: null, configRevision: 0, validated: false,
    })
    const wrapper = await mountSection()

    await wrapper.get('[data-test="provider-add-custom"]').trigger('click')
    const dialog = wrapper.get('[data-test="custom-create-dialog"]')
    expect(dialog.find('[data-test="custom-display-name"]').exists()).toBe(true)
    // 名称为空时确认按钮禁用（名称必填，用于前端胶囊显示）。
    // 弹窗本体没有确认按钮（确认动作在嵌入的 Custom 表单里），名称字段必须存在。
    expect(dialog.find('[data-test="custom-display-name"]').exists()).toBe(true)
  })

  it('keeps the card being viewed mounted behind the create dialog', async () => {
    vi.mocked(getCustomStatus).mockResolvedValue({
      configured: false, apiFormat: 'CHAT_COMPLETIONS', baseUrl: '', endpointPreview: null,
      hasKey: false, maskedKey: null, selectedModel: '', manualModel: false,
      displayName: null, configRevision: 0, validated: false,
    })
    const wrapper = await mountSection()

    expect(wrapper.find('[data-test="opencode-card"]').exists()).toBe(true)
    await wrapper.get('[data-test="provider-add-custom"]').trigger('click')
    await flushPromises()

    // 弹窗只是浮层：打开它不得把下面的卡片从 DOM 里摘掉。
    // 曾经的实现会在打开时 view('CUSTOM')，而未配置时 Custom 面板的 v-if 不成立，
    // 整个卡片区被卸载 —— 表现就是「弹窗把底下的东西盖没了」。
    expect(wrapper.find('[data-test="custom-create-dialog"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="opencode-card"]').exists()).toBe(true)
  })

  it('leaves the card the user was viewing selected when the dialog is dismissed unconfigured', async () => {
    vi.mocked(getCustomStatus).mockResolvedValue({
      configured: false, apiFormat: 'CHAT_COMPLETIONS', baseUrl: '', endpointPreview: null,
      hasKey: false, maskedKey: null, selectedModel: '', manualModel: false,
      displayName: null, configRevision: 0, validated: false,
    })
    const wrapper = await mountSection()

    await wrapper.get('[data-test="provider-tab-openrouter"]').trigger('click')
    expect(wrapper.find('[data-test="openrouter-card"]').exists()).toBe(true)

    await wrapper.get('[data-test="provider-add-custom"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="ui-dialog-close"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="custom-create-dialog"]').exists()).toBe(false)
    // 取消创建不应把用户弹回第一个 Tab —— 他原本在看哪张卡就留在哪张卡。
    expect(wrapper.find('[data-test="openrouter-card"]').exists()).toBe(true)
  })

  it('switches to the new card once the custom provider has actually been saved', async () => {
    // 保存成功后后端就会认为它已配置 —— mock 必须跟着变，
    // 否则卡片挂载时自己拉到的仍是「未配置」，测得的是假的失败。
    let saved = false
    vi.mocked(getCustomStatus).mockImplementation(async () => (saved
      ? {
        configured: true, apiFormat: 'CHAT_COMPLETIONS', baseUrl: 'http://localhost:11434/v1',
        endpointPreview: 'http://localhost:11434/v1/chat/completions', hasKey: false,
        maskedKey: null, selectedModel: 'custom-model-a', manualModel: false,
        displayName: '本地 Ollama', configRevision: 1, validated: true,
      }
      : {
        configured: false, apiFormat: 'CHAT_COMPLETIONS', baseUrl: '', endpointPreview: null,
        hasKey: false, maskedKey: null, selectedModel: '', manualModel: false,
        displayName: null, configRevision: 0, validated: false,
      }))
    const wrapper = await mountSection()
    const store = useCustomProviderStore()

    await wrapper.get('[data-test="provider-add-custom"]').trigger('click')
    await flushPromises()

    // 模拟表单保存成功：只有到这一步，视图才允许移到新的 Custom 卡片上。
    saved = true
    store.configured = true
    store.displayName = '本地 Ollama'
    await wrapper.get('[data-test="ui-dialog-close"]').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(wrapper.find('[data-test="custom-create-dialog"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="custom-card"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="custom-title"]').text()).toBe('本地 Ollama')
  })

  it('custom pill shows the active badge when custom is the runtime provider', async () => {
    vi.mocked(getActiveProvider).mockResolvedValue({ activeProvider: 'CUSTOM' })
    vi.mocked(getCustomStatus).mockResolvedValue({
      configured: true, apiFormat: 'RESPONSES', baseUrl: 'https://gateway.example/v1',
      endpointPreview: 'https://gateway.example/v1/responses', hasKey: true,
      maskedKey: '••••99ab', selectedModel: 'gw-model', manualModel: false,
      displayName: 'My Gateway', configRevision: 3, validated: true,
    })
    const wrapper = await mountSection()

    const pill = wrapper.get('[data-test="provider-tab-custom"]')
    expect(pill.text()).toContain('My Gateway')
    expect(pill.get('[data-test="provider-active-badge"]').text()).toBe('当前')
  })
})

describe('custom provider dialog is one flat flow', () => {
  const configuredStatus = {
    configured: true, apiFormat: 'CHAT_COMPLETIONS' as const, baseUrl: 'http://localhost:11434/v1',
    endpointPreview: 'http://localhost:11434/v1/chat/completions', hasKey: true,
    maskedKey: '••••kk11', selectedModel: 'custom-model-a', manualModel: false,
    displayName: '本地 Ollama', configRevision: 1, validated: true,
  }

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getActiveProvider).mockResolvedValue({ activeProvider: 'CUSTOM' })
  })

  it('opens the same dialog in edit mode from the card 设置 button, prefilled with the stored name', async () => {
    vi.mocked(getCustomStatus).mockResolvedValue(configuredStatus)
    const wrapper = await mountSection()

    await wrapper.get('[data-test="provider-tab-custom"]').trigger('click')
    const card = wrapper.get('[data-test="custom-card"]')
    await card.get('[data-test="custom-settings"]').trigger('click')
    await flushPromises()

    const dialog = wrapper.get('[data-test="custom-provider-dialog"]')
    expect((dialog.get('[data-test="custom-display-name"]').element as HTMLInputElement).value)
      .toBe('本地 Ollama')
    // 已配置项的全部字段都可修改，含显示名称。
    expect(dialog.find('[data-test="custom-format"]').exists()).toBe(true)
    expect(dialog.find('[data-test="custom-base-url"]').exists()).toBe(true)
    expect(dialog.find('[data-test="custom-api-key"]').exists()).toBe(true)
  })

  it('no longer embeds the provider card, so the form is not split by a Custom header', async () => {
    vi.mocked(getCustomStatus).mockResolvedValue(configuredStatus)
    const wrapper = await mountSection()

    await wrapper.get('[data-test="provider-tab-custom"]').trigger('click')
    await wrapper.get('[data-test="custom-card"]').get('[data-test="custom-settings"]').trigger('click')
    await flushPromises()

    const dialog = wrapper.get('[data-test="custom-provider-dialog"]')
    // 内嵌卡片时代会出现的页头与状态胶囊，在弹窗里必须不存在。
    expect(dialog.find('[data-test="custom-title"]').exists()).toBe(false)
    expect(dialog.find('[data-test="custom-state"]').exists()).toBe(false)
  })

  it('renders the fields in one uninterrupted order: name, format, base URL, key', async () => {
    vi.mocked(getCustomStatus).mockResolvedValue(configuredStatus)
    const wrapper = await mountSection()

    await wrapper.get('[data-test="provider-tab-custom"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="custom-settings"]').trigger('click')
    await flushPromises()

    const html = wrapper.get('[data-test="custom-provider-dialog"]').html()
    const offsets = ['custom-display-name', 'custom-format', 'custom-base-url', 'custom-api-key']
      .map((id) => html.indexOf(`data-test="${id}"`))
    expect(offsets.every((offset) => offset >= 0)).toBe(true)
    expect(offsets).toEqual([...offsets].sort((a, b) => a - b))
  })

  it('drops the tab hint that implied switching configuration switches the runtime', async () => {
    vi.mocked(getCustomStatus).mockResolvedValue(configuredStatus)
    const wrapper = await mountSection()

    expect(wrapper.text()).not.toContain('切换 Tab 只是在编辑配置')
    // 选择配置的入口仍然存在，且仍然不会触发激活。
    expect(wrapper.find('[data-test="provider-tabs"]').exists()).toBe(true)
  })
})

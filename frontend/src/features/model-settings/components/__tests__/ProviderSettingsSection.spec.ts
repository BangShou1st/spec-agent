import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { accessSession } from '@/features/access/accessSession'
import ProviderSettingsSection from '../ProviderSettingsSection.vue'
import * as api from '../../api/modelProviders'
vi.mock('../../api/modelProviders', () => ({
  listConnections: vi.fn(), saveConnection: vi.fn(), discoverConnection: vi.fn(),
  validateConnection: vi.fn(), activateConnection: vi.fn(), deleteConnection: vi.fn(),
}))
const identity = () => ({ mode: 'LOCAL' as const, authenticated: true, userId: 'owner', orgId: 'org', displayName: 'Owner',
  orgName: 'Org', orgRole: 'OWNER', orgCapabilities: ['CREDENTIAL_MANAGE'], memberships: [] })
const row = (id: string, displayName = id, active = false): api.ProviderConnection => ({
  id, preset: 'CUSTOM', displayName, active, configured: true, apiFormat: 'CHAT_COMPLETIONS', baseUrl: 'http://localhost:9/v1',
  endpointPreview: null, hasKey: true, maskedKey: '••••test', selectedModel: 'manual-model', manualModel: true,
  configRevision: 1, validated: true, verifiedCapabilities: { CHAT: 'VERIFIED', STRUCTURED: 'VERIFIED', NATIVE_TOOLS: 'UNTESTED' },
  capabilities: { userNamed: true, selectableFormat: true, editableBaseUrl: true, requiresApiKey: false, builtInCatalog: false },
})
let rows: api.ProviderConnection[]
async function render() {
  const wrapper = mount(ProviderSettingsSection, { global: { plugins: [createPinia()], stubs: {
    OpenCodeProviderSettings: { template: '<div data-test="zen-panel">Zen</div>' },
    KiloProviderSettings: { template: '<div>Kilo</div>' },
  } } })
  await flushPromises(); return wrapper
}
describe('provider connections', () => {
  beforeEach(() => {
    vi.resetAllMocks(); accessSession.identity = identity(); rows = [row('a', 'First'), row('b', 'Second')]
    vi.mocked(api.listConnections).mockImplementation(async () => ({ providers: rows, activeProvider: 'CUSTOM', activeProviderId: 'b' }))
  })
  it('shows two presets and independent custom tabs, keeping add available', async () => {
    const w = await render(); expect(w.findAll('[role="tab"]')).toHaveLength(4)
    expect(w.find('[data-test="provider-tab-openrouter"]').exists()).toBe(false)
    expect(w.find('[data-test="provider-add-custom"]').exists()).toBe(true)
    expect(w.get('[data-test="active-provider-name"]').text()).toBe('Second')
  })
  it('opening and cancelling an unsaved draft keeps the underlying panel and connections', async () => {
    const w = await render(); await w.get('[data-test="provider-add-custom"]').trigger('click')
    expect(w.find('[data-test="zen-panel"]').exists()).toBe(true)
    await w.get('[data-test="ui-dialog-close"]').trigger('click')
    expect(w.find('[data-test="zen-panel"]').exists()).toBe(true)
    expect(w.findAll('[role="tab"]')).toHaveLength(4)
  })
  it('tab selection does not activate the connection', async () => {
    const w = await render(); await w.get('[data-test="provider-tab-a"]').trigger('click')
    expect(w.get('[data-test="connection-panel"]').text()).toContain('First')
    expect(api.activateConnection).not.toHaveBeenCalled()
  })
  it('validates the selected UUID only', async () => {
    const w = await render(); await w.get('[data-test="provider-tab-a"]').trigger('click')
    await w.get('[data-test="connection-validate"]').trigger('click'); await flushPromises()
    expect(api.validateConnection).toHaveBeenCalledExactlyOnceWith('a')
  })
  it('activates the selected UUID only', async () => {
    const w = await render(); await w.get('[data-test="provider-tab-a"]').trigger('click')
    await w.get('[data-test="connection-activate"]').trigger('click'); await flushPromises()
    expect(api.activateConnection).toHaveBeenCalledExactlyOnceWith('a')
  })
  it('requires a second delete action and prevents deleting the active connection', async () => {
    const w = await render(); await w.get('[data-test="provider-tab-a"]').trigger('click')
    await w.findAll('button').find(b => b.text() === '删除连接')!.trigger('click')
    expect(api.deleteConnection).not.toHaveBeenCalled()
    await w.findAll('button').find(b => b.text() === '确认删除')!.trigger('click'); await flushPromises()
    expect(api.deleteConnection).toHaveBeenCalledExactlyOnceWith('a')
    await w.get('[data-test="provider-tab-b"]').trigger('click')
    expect(w.findAll('button').find(b => b.text() === '删除连接')!.attributes('disabled')).toBeDefined()
  })
  it('edits the chosen row and preserves other connections', async () => {
    const w = await render(); await w.get('[data-test="provider-tab-a"]').trigger('click')
    await w.findAll('button').find(b => b.text() === '设置')!.trigger('click')
    expect((w.get('[data-test="connection-name"]').element as HTMLInputElement).value).toBe('First')
    expect((w.get('[data-test="connection-key"]').element as HTMLInputElement).value).toBe('')
  })
  it('uses an empty draft when another custom connection already exists', async () => {
    const w = await render(); await w.get('[data-test="provider-add-custom"]').trigger('click')
    expect((w.get('[data-test="connection-name"]').element as HTMLInputElement).value).toBe('')
    expect((w.get('[data-test="connection-url"]').element as HTMLInputElement).value).toBe('')
  })
  it('retains a saved manual model absent from the catalog', async () => {
    const w = await render(); await w.get('[data-test="provider-tab-a"]').trigger('click')
    expect(w.get('[data-test="connection-panel"]').text()).toContain('manual-model')
  })
  it('shows capability results independently', async () => {
    const w = await render(); await w.get('[data-test="provider-tab-a"]').trigger('click')
    expect(w.get('[data-test="connection-panel"]').text()).toContain('原生工具尚未验证')
  })
  it('hides mutations without credential management', async () => {
    accessSession.identity!.orgCapabilities = []
    const w = await render(); expect(w.find('[data-test="provider-add-custom"]').exists()).toBe(false)
    await w.get('[data-test="provider-tab-a"]').trigger('click')
    expect(w.find('[data-test="connection-activate"]').exists()).toBe(false)
  })
  it('keeps a draft open during renewal of the same identity', async () => {
    const w = await render(); await w.get('[data-test="provider-add-custom"]').trigger('click')
    await w.get('[data-test="connection-name"]').setValue('Draft')
    accessSession.identity = identity(); await flushPromises()
    expect((w.get('[data-test="connection-name"]').element as HTMLInputElement).value).toBe('Draft')
  })
  it('clears private connections immediately on logout', async () => {
    const w = await render(); accessSession.identity = null; await flushPromises()
    expect(w.find('[data-test="provider-tab-a"]').exists()).toBe(false)
  })
})

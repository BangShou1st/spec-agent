import { accessSession } from '@/features/access/accessSession'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ProviderConnectionEditor from '../ProviderConnectionEditor.vue'
import { sameOrigin } from '@/shared/net/origin'
import * as api from '../../api/modelProviders'
vi.mock('../../api/modelProviders', () => ({ discoverConnection: vi.fn(), saveConnection: vi.fn() }))
const connection = (): api.ProviderConnection => ({
  id: 'uuid-a', preset: 'CUSTOM', configured: true, active: false, displayName: 'Existing',
  apiFormat: 'CHAT_COMPLETIONS', baseUrl: 'http://localhost:11434/v1', endpointPreview: null,
  hasKey: true, maskedKey: '••••test', selectedModel: 'saved-unlisted-model', manualModel: true,
  configRevision: 1, validated: true,
  verifiedCapabilities: { CHAT: 'VERIFIED', STRUCTURED: 'VERIFIED', NATIVE_TOOLS: 'UNTESTED' },
  capabilities: { userNamed: true, selectableFormat: true, editableBaseUrl: true, requiresApiKey: false, builtInCatalog: false },
})
beforeEach(() => {
  vi.resetAllMocks()
  accessSession.identity = { mode: 'LOCAL', authenticated: true, userId: 'owner', orgId: 'org', displayName: 'Owner',
    orgName: 'Org', orgRole: 'OWNER', orgCapabilities: ['CREDENTIAL_MANAGE'], memberships: [] }
})
describe('sameOrigin semantics match the backend guard', () => {
  it('treats default ports as equal origins', () => {
    expect(sameOrigin('http://localhost:80/v1', 'http://localhost/v1')).toBe(true)
    expect(sameOrigin('https://gw.example.com:443/v1', 'https://GW.example.com/v1')).toBe(true)
  })

  it('treats different ports, schemes or hosts as different origins', () => {
    expect(sameOrigin('http://localhost:8080/v1', 'http://localhost/v1')).toBe(false)
    expect(sameOrigin('http://localhost/v1', 'https://localhost/v1')).toBe(false)
    expect(sameOrigin('http://localhost/v1', 'http://127.0.0.1/v1')).toBe(false)
  })

  it('is conservative for unparseable input', () => {
    expect(sameOrigin('not a url', 'http://localhost/v1')).toBe(false)
    expect(sameOrigin('', 'http://localhost/v1')).toBe(false)
  })
})

describe('UUID connection editor', () => {
  it('blocks discover and save on origin change until a credential decision', async () => {
    const w = mount(ProviderConnectionEditor, { props: { connection: connection() } })
    await w.get('[data-test="connection-url"]').setValue('http://localhost:19099/v1')
    expect(w.find('[data-test="connection-origin-warning"]').exists()).toBe(true)
    expect(w.get('[data-test="connection-discover"]').attributes('disabled')).toBeDefined()
    expect(w.get('[data-test="connection-save"]').attributes('disabled')).toBeDefined()
    await w.get('[data-test="connection-key"]').setValue('fixture-new-key')
    expect(w.find('[data-test="connection-origin-warning"]').exists()).toBe(false)
  })
  it('allows same-origin path changes and keyless connections', async () => {
    const row = connection(); row.hasKey = false
    const w = mount(ProviderConnectionEditor, { props: { connection: row } })
    await w.get('[data-test="connection-url"]').setValue('http://other-host:9999/v1')
    expect(w.find('[data-test="connection-origin-warning"]').exists()).toBe(false)
  })
  it('keeps an unlisted model editable and saves only the chosen UUID', async () => {
    const row = connection(); vi.mocked(api.saveConnection).mockResolvedValue(row)
    const w = mount(ProviderConnectionEditor, { props: { connection: row } })
    await w.get('form').trigger('submit'); await flushPromises()
    expect(api.saveConnection).toHaveBeenCalledExactlyOnceWith('uuid-a', expect.objectContaining({
      apiKey: undefined, selectedModel: 'saved-unlisted-model', modelSource: 'MANUAL',
    }))
  })
  it('displays a free filter only with explicit zero-price metadata', async () => {
    vi.mocked(api.discoverConnection).mockResolvedValue({ models: ['free', 'unknown'], freeModels: ['free'], manualModel: false, endpointPreview: null })
    const w = mount(ProviderConnectionEditor, { props: { connection: connection() } })
    await w.get('[data-test="connection-discover"]').trigger('click'); await flushPromises()
    await w.get('[data-test="connection-free-only"] input').setValue(true)
    expect(w.findAll('datalist option').map(o => o.attributes('value'))).toEqual(['free'])
  })
  it('does not call discovery or save without management permissions', async () => {
    accessSession.identity!.orgCapabilities = []
    const w = mount(ProviderConnectionEditor, { props: { connection: connection() } })
    await w.get('form').trigger('submit'); expect(api.saveConnection).not.toHaveBeenCalled()
  })
  it('uses the same field and button styles as builtin providers', () => {
    const w = mount(ProviderConnectionEditor, { props: { connection: connection() } })
    expect(w.get('[data-test="connection-save"]').classes()).toEqual(expect.arrayContaining(['btn', 'btn-primary', 'settings-action']))
    expect(w.get('[data-test="connection-model"]').classes()).toContain('settings-control')
    expect(w.findAll('.settings-field__label')).toHaveLength(5)
  })
})

import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ConnectionLifecycleAction from '@/components/connections/ConnectionLifecycleAction.vue'

function detail(over: Record<string, unknown> = {}) {
  return { connectionId: 'c1', name: 'n', kind: 'CUSTOM_MCP', status: 'CREATED', enabled: false, config: {}, hasCredential: false, maskedSuffix: null, lastError: null, createdAt: 'x', updatedAt: 'x', ...over }
}

describe('ConnectionLifecycleAction', () => {
  it('offers test for created', () => {
    const w = mount(ConnectionLifecycleAction, { props: { detail: detail(), working: false, error: null } })
    expect(w.get('[data-test="lifecycle-test"]'))
  })
  it('offers connect for tested', () => {
    const w = mount(ConnectionLifecycleAction, { props: { detail: detail({ status: 'TESTED' }), working: false, error: null } })
    expect(w.get('[data-test="lifecycle-connect"]'))
  })
  it('offers enable for connected', () => {
    const w = mount(ConnectionLifecycleAction, { props: { detail: detail({ status: 'CONNECTED' }), working: false, error: null } })
    expect(w.get('[data-test="lifecycle-enable"]'))
  })
  it('shows enabled with refresh', () => {
    const w = mount(ConnectionLifecycleAction, { props: { detail: detail({ status: 'CONNECTED', enabled: true }), working: false, error: null } })
    expect(w.get('[data-test="lifecycle-enabled"]'))
    expect(w.get('[data-test="lifecycle-refresh"]'))
  })
  it('shows safe failure with retest', () => {
    const w = mount(ConnectionLifecycleAction, { props: { detail: detail({ status: 'FAILED', lastError: 'handshake failed' }), working: false, error: { code: 'CONNECTION_COMMAND_REJECTED', message: 'handshake failed' } } })
    expect(w.get('[data-test="lifecycle-test"]'))
    expect(w.get('[data-test="lifecycle-error"]').text()).toContain('handshake failed')
  })
})

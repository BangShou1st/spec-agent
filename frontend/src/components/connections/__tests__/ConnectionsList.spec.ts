import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ConnectionsList from '@/components/connections/ConnectionsList.vue'

const conns = [
  { connectionId: 'c1', name: 'Personal MCP', kind: 'CUSTOM_MCP', status: 'CONNECTED', enabled: true, config: { serverUrl: 'https://a/mcp' }, hasCredential: true, maskedSuffix: '****abcd', createdAt: 'x', updatedAt: 'x' },
  { connectionId: 'c2', name: 'Dev', kind: 'CUSTOM_MCP', status: 'CREATED', enabled: false, config: { serverUrl: 'https://b/mcp' }, hasCredential: false, maskedSuffix: null, createdAt: 'x', updatedAt: 'x' },
]

describe('ConnectionsList', () => {
  it('renders real names with lifecycle status text', () => {
    const w = mount(ConnectionsList, { props: { connections: conns, loading: false } })
    expect(w.get('[data-test="connection-row-c1"]').text()).toContain('Personal MCP')
    expect(w.get('[data-test="connection-row-c1"]').text()).not.toContain('GitHub')
    expect(w.get('[data-test="connection-status-c2"]').text()).not.toBe('')
  })

  it('emits select and disable', async () => {
    const w = mount(ConnectionsList, { props: { connections: conns, loading: false } })
    await w.get('[data-test="connection-select-c1"]').trigger('click')
    expect(w.emitted('select')).toEqual([['c1']])
    await w.get('[data-test="connection-more-c1"] summary').trigger('click')
    await w.get('[data-test="connection-disable-c1"]').trigger('click')
    expect(w.emitted('disable')).toEqual([['c1']])
  })
})

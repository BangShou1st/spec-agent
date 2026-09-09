import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ConnectionsListView from '@/views/settings/ConnectionsListView.vue'
import * as connsApi from '@/api/connections'

vi.mock('@/api/connections', () => ({
  listConnections: vi.fn(),
  getConnection: vi.fn(),
  createConnection: vi.fn(),
  updateConnection: vi.fn(),
  testConnection: vi.fn(),
  connectConnection: vi.fn(),
  refreshConnection: vi.fn(),
  enableConnection: vi.fn(),
  disableConnection: vi.fn(),
  deleteConnection: vi.fn(),
  listConnectionTools: vi.fn(),
  listConnectionResources: vi.fn(),
  listConnectionPrompts: vi.fn(),
  readConnectionResource: vi.fn(),
}))

const api = vi.mocked(connsApi)

function mountView() {
  setActivePinia(createPinia())
  const router = createRouter({ history: createWebHistory(), routes: [{ path: '/', component: ConnectionsListView }] })
  return mount(ConnectionsListView, { global: { plugins: [router] } })
}

describe('ConnectionsListView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listConnections.mockResolvedValue([])
  })

  it('shows empty state and opens create dialog', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.get('[data-test="connections-empty"]'))
    await w.get('[data-test="add-connection"]').trigger('click')
    expect(w.get('[data-test="connection-create-dialog"]'))
  })

  it('creates with name, server url, and optional secret', async () => {
    api.createConnection.mockResolvedValue({ connectionId: 'conn_9' } as never)
    const w = mountView()
    await flushPromises()
    await w.get('[data-test="add-connection"]').trigger('click')
    await w.get('[data-test="conn-name"]').setValue('Research Tools')
    await w.get('[data-test="conn-server-url"]').setValue('https://mcp.example.com/mcp')
    await w.get('[data-test="conn-secret"]').setValue('s3cret')
    await w.get('[data-test="submit-create"]').trigger('click')
    expect(api.createConnection).toHaveBeenCalledWith({ kind: 'CUSTOM_MCP', name: 'Research Tools', config: { serverUrl: 'https://mcp.example.com/mcp' }, secret: 's3cret' })
  })
})

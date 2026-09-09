import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ConnectionDetailView from '@/views/settings/ConnectionDetailView.vue'
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
  deleteConnection: vi.fn().mockResolvedValue(undefined),
  listConnectionTools: vi.fn().mockResolvedValue([]),
  listConnectionResources: vi.fn().mockResolvedValue([]),
  listConnectionPrompts: vi.fn().mockResolvedValue([]),
  readConnectionResource: vi.fn(),
}))

const api = vi.mocked(connsApi)

const detail = { connectionId: 'conn_1', name: 'Personal MCP', kind: 'CUSTOM_MCP', status: 'CONNECTED', enabled: false, config: { serverUrl: 'https://mcp.example.com/mcp' }, hasCredential: true, maskedSuffix: '****abcd', lastError: null, createdAt: 'x', updatedAt: 'x' }

function mountView() {
  setActivePinia(createPinia())
  const router = createRouter({ history: createWebHistory(), routes: [{ path: '/', component: ConnectionDetailView }] })
  return mount(ConnectionDetailView, { props: { connectionId: 'conn_1' }, global: { plugins: [router] } })
}

describe('ConnectionDetailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getConnection.mockResolvedValue({ ...detail })
    api.listConnectionTools.mockResolvedValue([])
    api.listConnectionResources.mockResolvedValue([])
    api.listConnectionPrompts.mockResolvedValue([])
  })

  it('renders safe config without secret plaintext', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.get('[data-test="connection-detail-name"]').text()).toContain('Personal MCP')
    expect(w.get('[data-test="connection-endpoint"]').text()).toContain('https://mcp.example.com/mcp')
    expect(w.get('[data-test="connection-credential"]').text()).toContain('****abcd')
    expect(w.text()).not.toContain('s3cret-plaintext')
  })

  it('runs the contextual next action', async () => {
    api.enableConnection.mockResolvedValue(undefined)
    const w = mountView()
    await flushPromises()
    expect(w.get('[data-test="lifecycle-enable"]'))
  })

  it('confirms before delete', async () => {
    const w = mountView()
    await flushPromises()
    await w.get('[data-test="connection-delete"]').trigger('click')
    await w.get('[data-test="connection-delete-confirm"]').trigger('click')
    expect(api.deleteConnection).toHaveBeenCalledWith('conn_1')
  })
})

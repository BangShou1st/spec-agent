import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useConnectionsStore } from '@/stores/connectionsStore'
import { ApiError } from '@/api/client'
import * as connectionsApi from '@/api/connections'

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

const api = vi.mocked(connectionsApi)

describe('connectionsStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads list and detail with tools', async () => {
    api.listConnections.mockResolvedValue([{ connectionId: 'conn_1' } as never])
    api.getConnection.mockResolvedValue({ connectionId: 'conn_1' } as never)
    api.listConnectionTools.mockResolvedValue([{ name: 't' }] as never)
    const store = useConnectionsStore()
    await store.loadList()
    expect(store.list).toHaveLength(1)
    await store.loadDetail('conn_1')
    expect(store.detail?.connectionId).toBe('conn_1')
    expect(store.tools).toEqual([{ name: 't' }])
    expect(store.error).toBeNull()
  })

  it('maps typed errors without raw leakage', async () => {
    api.listConnections.mockRejectedValue(new ApiError('bad', 'CONNECTION_COMMAND_REJECTED', 400))
    const store = useConnectionsStore()
    await store.loadList()
    expect(store.error?.code).toBe('CONNECTION_COMMAND_REJECTED')
    expect(store.list).toEqual([])
  })

  it('creates then refreshes list', async () => {
    api.createConnection.mockResolvedValue({ connectionId: 'conn_9' } as never)
    api.listConnections.mockResolvedValue([{ connectionId: 'conn_9' }] as never)
    const store = useConnectionsStore()
    const created = await store.create({ kind: 'CUSTOM_MCP', name: 'n', config: { serverUrl: 'https://m/x' } })
    expect(created?.connectionId).toBe('conn_9')
    expect(store.list).toHaveLength(1)
  })
})

import { accessSession } from '@/features/access/accessSession'
// 文件名:ConnectionsListView.spec.ts
// 用途:Connections 列表页组件测试:mock 连接 API,验证空态与新建弹窗的打开、
//       以及按名称/服务器地址/可选 secret 创建连接的请求参数。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ConnectionsListView from '@/features/connections/ConnectionsListView.vue'
import * as connsApi from '@/features/connections/api/connections'

vi.mock('@/features/connections/api/connections', () => ({
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
    expect(api.createConnection).toHaveBeenCalledWith({ ownerKind: 'USER', kind: 'CUSTOM_MCP', name: 'Research Tools', config: { serverUrl: 'https://mcp.example.com/mcp' }, secret: 's3cret' })
  })
})

beforeEach(() => { accessSession.identity = { mode: 'OIDC', authenticated: true, userId: 'editor', orgId: 'org', displayName: 'Editor', orgName: 'Org', orgRole: 'ADMIN', orgCapabilities: ['CREDENTIAL_MANAGE'], memberships: [] } })


it('allows personal creation but hides organization sharing from members', async () => {
  accessSession.identity!.orgCapabilities = []
  api.listConnections.mockResolvedValue([])
  const w = mountView()
  await flushPromises()
  await w.get('[data-test="add-connection"]').trigger('click')
  expect(w.get('[data-test="conn-owner"]').findAll('option').map(o => o.attributes('value'))).toEqual(['USER'])
  await w.get('[data-test="conn-name"]').setValue('My Tools')
  await w.get('[data-test="conn-server-url"]').setValue('https://example.test/mcp')
  await w.get('[data-test="submit-create"]').trigger('click')
  expect(api.createConnection).toHaveBeenCalledWith(expect.objectContaining({ ownerKind: 'USER' }))
  w.unmount()
})


it('sends explicit organization ownership only when sharing is selected', async () => {
  api.listConnections.mockResolvedValue([])
  const w = mountView()
  await flushPromises()
  await w.get('[data-test="add-connection"]').trigger('click')
  await w.get('[data-test="conn-owner"]').setValue('ORG')
  await w.get('[data-test="conn-name"]').setValue('Shared Tools')
  await w.get('[data-test="conn-server-url"]').setValue('https://example.test/mcp')
  await w.get('[data-test="submit-create"]').trigger('click')
  expect(api.createConnection).toHaveBeenCalledWith(expect.objectContaining({ ownerKind: 'ORG' }))
  w.unmount()
})

it('clears shared creation credentials and returns to personal scope after capability loss', async () => {
  api.listConnections.mockResolvedValue([])
  const w = mountView()
  await flushPromises()
  await w.get('[data-test="add-connection"]').trigger('click')
  await w.get('[data-test="conn-owner"]').setValue('ORG')
  await w.get('[data-test="conn-secret"]').setValue('unsaved-shared-secret')
  accessSession.identity!.orgCapabilities = []
  await flushPromises()
  expect((w.get('[data-test="conn-owner"]').element as HTMLSelectElement).value).toBe('USER')
  expect((w.get('[data-test="conn-secret"]').element as HTMLInputElement).value).toBe('')
  w.unmount()
})

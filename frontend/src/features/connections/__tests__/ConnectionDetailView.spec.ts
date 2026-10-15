import { accessSession } from '@/features/access/accessSession'
// 文件名:ConnectionDetailView.spec.ts
// 用途:Connection 详情页组件测试:mock 连接 API,验证详情渲染不泄露 secret 明文、
//       生命周期操作入口可用、删除前有二次确认。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ConnectionDetailView from '@/features/connections/ConnectionDetailView.vue'
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
  deleteConnection: vi.fn().mockResolvedValue(undefined),
  listConnectionTools: vi.fn().mockResolvedValue([]),
  listConnectionResources: vi.fn().mockResolvedValue([]),
  listConnectionPrompts: vi.fn().mockResolvedValue([]),
  readConnectionResource: vi.fn(),
}))

const api = vi.mocked(connsApi)

const detail = { ownerKind: 'ORG' as const, connectionId: 'conn_1', name: 'Personal MCP', kind: 'CUSTOM_MCP', status: 'CONNECTED', enabled: false, config: { serverUrl: 'https://mcp.example.com/mcp' }, hasCredential: true, maskedSuffix: '****abcd', lastError: null, createdAt: 'x', updatedAt: 'x' }

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

beforeEach(() => { accessSession.identity = { mode: 'OIDC', authenticated: true, userId: 'editor', orgId: 'org', displayName: 'Editor', orgName: 'Org', orgRole: 'ADMIN', orgCapabilities: ['CREDENTIAL_MANAGE'], memberships: [] } })


describe('connection ownership boundary', () => {
  it('keeps shared connection readable without management capability', async () => {
    accessSession.identity!.orgCapabilities = []
    api.getConnection.mockResolvedValue({ ...detail })
    const w = mountView()
    await flushPromises()
    expect(w.get('[data-test="connection-detail-name"]').text()).toContain('Personal MCP')
    expect(w.find('[data-test="edit-connection"]').exists()).toBe(false)
    expect(w.find('[data-test="lifecycle-enable"]').exists()).toBe(false)
    expect(w.find('[data-test="danger-zone"]').exists()).toBe(false)
    w.unmount()
  })
  it('lets a member manage their server-visible personal connection', async () => {
    accessSession.identity!.orgCapabilities = []
    api.getConnection.mockResolvedValue({ ...detail, ownerKind: 'USER' })
    const w = mountView()
    await flushPromises()
    expect(w.find('[data-test="edit-connection"]').exists()).toBe(true)
    expect(w.find('[data-test="lifecycle-enable"]').exists()).toBe(true)
    w.unmount()
  })
  it('removes shared edit and delete confirmations on capability loss', async () => {
    api.getConnection.mockResolvedValue({ ...detail })
    const w = mountView()
    await flushPromises()
    await w.get('[data-test="edit-connection"]').trigger('click')
    await w.get('[data-test="edit-secret"]').setValue('unsaved-secret')
    accessSession.identity!.orgCapabilities = []
    await flushPromises()
    expect(w.find('[data-test="connection-edit-dialog"]').exists()).toBe(false)
    accessSession.identity!.orgCapabilities = ['CREDENTIAL_MANAGE']
    await flushPromises()
    await w.get('[data-test="edit-connection"]').trigger('click')
    expect((w.get('[data-test="edit-secret"]').element as HTMLInputElement).value).toBe('')
    w.unmount()
  })
  it('denies management when an older response lacks ownership metadata', async () => {
    const { ownerKind: _ownerKind, ...legacy } = detail
    api.getConnection.mockResolvedValue(legacy)
    const w = mountView()
    await flushPromises()
    expect(w.find('[data-test="edit-connection"]').exists()).toBe(false)
    expect(w.find('[data-test="danger-zone"]').exists()).toBe(false)
    w.unmount()
  })
})


it('does not discover resources or prompts for a disabled connection', async () => {
  api.getConnection.mockResolvedValue({ ...detail, status: 'CREATED', enabled: false })
  api.listConnectionTools.mockClear()
  api.listConnectionResources.mockClear()
  api.listConnectionPrompts.mockClear()
  const w = mountView()
  await flushPromises()
  expect(w.get('[data-test="connection-detail-name"]').text()).toContain('Personal MCP')
  expect(api.listConnectionTools).not.toHaveBeenCalled()
  expect(api.listConnectionResources).not.toHaveBeenCalled()
  expect(api.listConnectionPrompts).not.toHaveBeenCalled()
  w.unmount()
})

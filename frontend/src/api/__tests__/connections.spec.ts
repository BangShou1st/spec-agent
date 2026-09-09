import { afterEach, describe, expect, it, vi } from 'vitest'
import { connectConnection, createConnection, deleteConnection, disableConnection, enableConnection, getConnection, listConnectionPrompts, listConnectionResources, listConnectionTools, listConnections, readConnectionResource, refreshConnection, testConnection, updateConnection } from '@/api/connections'

function ok(body: unknown, status = 200) {
  return { ok: true, status, text: async () => (body == null ? '' : JSON.stringify(body)), json: async () => body }
}

describe('connections api', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('lists and reads connections by product-level id', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(ok([{ connectionId: 'conn_1' }]))
      .mockResolvedValueOnce(ok({ connectionId: 'conn_1', config: { serverUrl: 'https://m/x' } }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(listConnections()).resolves.toEqual([{ connectionId: 'conn_1' }])
    await expect(getConnection('conn_1')).resolves.toMatchObject({ connectionId: 'conn_1' })
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/connections')
    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/connections/conn_1')
  })

  it('creates, patches, and runs lifecycle on connectionId URLs', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(ok({ connectionId: 'conn_9' }, 201))
      .mockResolvedValueOnce(ok({ connectionId: 'conn_9', name: 'renamed' }))
      .mockResolvedValueOnce(ok({ toolCount: 1 }))
      .mockResolvedValueOnce(ok({ toolCount: 1 }))
      .mockResolvedValueOnce(ok({ toolCount: 1 }))
      .mockResolvedValueOnce(ok(undefined, 204))
      .mockResolvedValueOnce(ok(undefined, 204))
      .mockResolvedValueOnce(ok(undefined, 204))
    vi.stubGlobal('fetch', fetchMock)
    await createConnection({ kind: 'CUSTOM_MCP', name: 'n', config: { serverUrl: 'https://m/x' } })
    await updateConnection('conn_9', { name: 'renamed' })
    await testConnection('conn_9')
    await connectConnection('conn_9')
    await refreshConnection('conn_9')
    await enableConnection('conn_9')
    await disableConnection('conn_9')
    await deleteConnection('conn_9')
    const urls = fetchMock.mock.calls.map((c) => String(c[0]))
    expect(urls).toEqual([
      '/api/v1/connections',
      '/api/v1/connections/conn_9',
      '/api/v1/connections/conn_9/test',
      '/api/v1/connections/conn_9/connect',
      '/api/v1/connections/conn_9/refresh',
      '/api/v1/connections/conn_9/enable',
      '/api/v1/connections/conn_9/disable',
      '/api/v1/connections/conn_9',
    ])
    expect(fetchMock.mock.calls[7][1].method).toBe('DELETE')
  })

  it('reads tools, resources, prompts, and resource content separately', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(ok([{ name: 't' }]))
      .mockResolvedValueOnce(ok([{ uri: 'docs://g' }]))
      .mockResolvedValueOnce(ok([{ name: 'p' }]))
      .mockResolvedValueOnce(ok({ uri: 'docs://g', text: 'b' }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(listConnectionTools('conn_1')).resolves.toEqual([{ name: 't' }])
    await expect(listConnectionResources('conn_1')).resolves.toEqual([{ uri: 'docs://g' }])
    await expect(listConnectionPrompts('conn_1')).resolves.toEqual([{ name: 'p' }])
    await expect(readConnectionResource('conn_1', 'docs://g')).resolves.toMatchObject({ uri: 'docs://g' })
    const urls = fetchMock.mock.calls.map((c) => String(c[0]))
    expect(urls[0]).toBe('/api/v1/connections/conn_1/tools')
    expect(urls[1]).toBe('/api/v1/connections/conn_1/resources')
    expect(urls[2]).toBe('/api/v1/connections/conn_1/prompts')
    expect(urls[3]).toContain('/api/v1/connections/conn_1/resources/read')
  })
})

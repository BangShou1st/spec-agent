import { test, expect } from '@playwright/test'

type Conn = { connectionId: string; name: string; status: string; enabled: boolean; serverUrl: string; secret: string | null }

const seed: Conn[] = [
  { connectionId: 'conn_1', name: 'Personal MCP', status: 'CONNECTED', enabled: true, serverUrl: 'https://mcp.example.com/mcp', secret: 's3cret' },
]

async function mockConns(page, list: Conn[]) {
  const tools: Record<string, { name: string; description: string }[]> = {
    conn_1: [{ name: 'search', description: 'Search things' }],
  }
  await page.route('**/api/v1/connections**', async (route) => {
    const req = route.request()
    const method = req.method()
    const url = new URL(req.url())
    const path = url.pathname.replace('/api/v1/connections', '') || '/'
    const json = (status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    const empty = () => route.fulfill({ status: 204, body: '' })
    const find = (id: string) => list.find((c) => c.connectionId === id)
    const view = (c: Conn) => ({ connectionId: c.connectionId, name: c.name, kind: 'CUSTOM_MCP', status: c.status, enabled: c.enabled, config: { serverUrl: c.serverUrl }, hasCredential: c.secret !== null, maskedSuffix: c.secret ? '****' + c.secret.slice(-4) : null, createdAt: '2026-01-01', updatedAt: '2026-01-01', lastError: c.status === 'FAILED' ? 'handshake failed' : null })
    if (method === 'GET' && path === '/') return json(200, list.map((c) => ({ ...view(c), lastError: undefined })))
    if (method === 'POST' && path === '/') {
      const body = req.postDataJSON() as { name: string; config: { serverUrl: string }; secret?: string }
      const id = 'conn_9'
      list.push({ connectionId: id, name: body.name, status: 'CREATED', enabled: false, serverUrl: body.config.serverUrl, secret: body.secret ?? null })
      tools[id] = []
      return json(201, view(find(id)!))
    }
    const m = path.match(/^\/([^/]+)(\/.*)?$/)
    if (!m) return route.fallback()
    const c = find(m[1])
    if (!c) return json(404, { code: 'CONNECTION_NOT_FOUND', message: 'not found' })
    const rest = m[2] ?? ''
    if (method === 'GET' && !rest) return json(200, view(c))
    if (method === 'PATCH' && !rest) {
      const body = req.postDataJSON() as { name?: string; config?: { serverUrl: string }; secret?: string }
      let touched = false
      if (body.name !== undefined && body.name !== c.name) { c.name = body.name; touched = true }
      if (body.config !== undefined) {
        if (body.config.serverUrl !== c.serverUrl) {
          c.serverUrl = body.config.serverUrl
          touched = true
          c.status = 'CREATED'
          c.enabled = false
          tools[c.connectionId] = []
        }
      }
      if (body.secret !== undefined) {
        c.secret = body.secret
        touched = true
        c.status = 'CREATED'
        c.enabled = false
        tools[c.connectionId] = []
      }
      void touched
      return json(200, view(c))
    }
    if (method === 'POST' && rest === '/test') {
      if (c.serverUrl.includes('bad')) { c.status = 'FAILED'; return json(400, { code: 'CONNECTION_COMMAND_REJECTED', message: 'handshake failed' }) }
      c.status = 'TESTED'
      tools[c.connectionId] = [{ name: 'search', description: 'Search things' }]
      return json(200, { toolCount: 1, resourceCount: 1, promptCount: 1, toolNames: ['search'], resourceUris: ['docs://g'], serverInfo: 's', protocolVersion: 'v' })
    }
    if (method === 'POST' && rest === '/connect') { c.status = 'CONNECTED'; return json(200, { toolCount: 1, resourceCount: 1, promptCount: 1, toolNames: ['search'], resourceUris: ['docs://g'], serverInfo: 's', protocolVersion: 'v' }) }
    if (method === 'POST' && rest === '/refresh') return json(200, { toolCount: 1, resourceCount: 1, promptCount: 1, toolNames: ['search'], resourceUris: ['docs://g'], serverInfo: 's', protocolVersion: 'v' })
    if (method === 'POST' && rest === '/enable') {
      if (c.status !== 'TESTED' && c.status !== 'CONNECTED') return json(400, { code: 'CONNECTION_COMMAND_REJECTED', message: 'must test first' })
      c.enabled = true
      return empty()
    }
    if (method === 'POST' && rest === '/disable') { c.enabled = false; return empty() }
    if (method === 'DELETE' && !rest) { list.splice(list.indexOf(c), 1); return empty() }
    if (method === 'GET' && rest === '/tools') return json(200, (tools[c.connectionId] ?? []).map((t) => ({ ...t, inputSchema: { type: 'object' }, annotations: {} })))
    if (method === 'GET' && rest === '/resources') return json(200, [{ uri: 'docs://g', name: 'guide', description: 'd', mimeType: 'text/plain' }])
    if (method === 'GET' && rest === '/prompts') return json(200, [{ name: 'review', description: 'd', argumentCount: 1 }])
    if (method === 'GET' && rest.startsWith('/resources/read')) return json(200, { uri: 'docs://g', text: 'guide body', mimeType: 'text/plain', provenance: { kind: 'MCP_RESOURCE' } })
    return route.fallback()
  })
}

test('create then walk the lifecycle to enabled', async ({ page }) => {
  await mockConns(page, [...seed])
  await page.goto('/settings/connections')
  await expect(page.getByTestId('connection-row-conn_1')).toBeVisible()
  await page.getByTestId('add-connection').click()
  await page.getByTestId('conn-name').fill('Research Tools')
  await page.getByTestId('conn-server-url').fill('https://mcp.example.com/mcp')
  await page.getByTestId('conn-secret').fill('s3cret')
  await page.getByTestId('submit-create').click()
  await expect(page).toHaveURL(/\/settings\/connections\/conn_9/)
  await expect(page.getByTestId('lifecycle-test')).toBeVisible()
  await page.getByTestId('lifecycle-test').click()
  await expect(page.getByTestId('lifecycle-connect')).toBeVisible()
  await page.getByTestId('lifecycle-connect').click()
  await expect(page.getByTestId('cap-tool-search')).toBeVisible()
  await page.getByTestId('lifecycle-enable').click()
  await expect(page.getByTestId('lifecycle-enabled')).toBeVisible()
  await page.getByTestId('cap-tab-resources').click()
  await expect(page.getByTestId('cap-resource-docs://g')).toBeVisible()
  await page.getByTestId('cap-tab-prompts').click()
  await expect(page.getByTestId('cap-prompt-review')).toBeVisible()
})

test('config update resets lifecycle and delete confirms', async ({ page }) => {
  await mockConns(page, [...seed])
  await page.goto('/settings/connections/conn_1')
  await expect(page.getByTestId('connection-detail-name')).toContainText('Personal MCP')
  await page.getByTestId('edit-connection').click()
  await page.getByTestId('edit-server-url').fill('https://bad.example.com/mcp')
  await page.getByTestId('submit-edit').click()
  await expect(page.getByTestId('lifecycle-test')).toBeVisible()
  await expect(page.getByTestId('cap-tool-search')).toHaveCount(0)
  await page.getByTestId('connection-delete').click()
  await page.getByTestId('connection-delete-confirm').click()
  await expect(page).toHaveURL(/\/settings\/connections$/)
})

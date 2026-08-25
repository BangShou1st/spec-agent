import { test, expect } from '@playwright/test'
import {
  buildThreeNodeLineage,
  closeFloatingWorkspaceWindows,
  createProject,
  fitGraph,
} from './helpers'

test('问 AI opens the Inspector and anchors NodeQuery to the canonical node id', async ({ page }) => {
  let canonicalRootNodeId: string | null = null
  page.on('response', async (response) => {
    if (!response.url().includes('/graph') || response.status() !== 200) return
    try {
      const body = await response.json() as {
        routes?: Array<{ lineageNodeIds?: string[] }>
      }
      const rootNodeId = body.routes?.[0]?.lineageNodeIds?.[0]
      if (rootNodeId) canonicalRootNodeId = rootNodeId
    } catch {
      // Ignore unrelated responses that happen to contain /graph in the URL.
    }
  })

  await createProject(page, 'E2E Contextual AI Identity')
  await buildThreeNodeLineage(page)
  await expect.poll(() => canonicalRootNodeId).not.toBeNull()
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)

  const root = page.locator('[data-test="graph-question-node"]').first()
  await root.hover()
  await root.getByTestId('contextual-ai').click()
  await expect(page.getByTestId('floating-window-inspector')).toBeVisible()
  await expect(page.getByTestId('node-detail-question')).toBeVisible()

  let queryUrl = ''
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().includes('/query')) {
      queryUrl = request.url()
    }
  })
  await page.getByTestId('ask-input').fill('这个节点的上下文是什么？')
  await page.getByTestId('ask-submit').click()
  await expect(page.getByTestId('ask-result')).toBeVisible()

  expect(queryUrl).toContain(`/nodes/${canonicalRootNodeId}/query`)
  expect(queryUrl).not.toContain('/nodes/pending:')
})

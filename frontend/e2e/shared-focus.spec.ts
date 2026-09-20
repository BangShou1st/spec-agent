import { test, expect } from '@playwright/test'
import { buildThreeNodeLineage, closeFloatingWorkspaceWindows, createProject, fitGraph, forkFromNode, hoverNode } from './helpers'

test('shared node current-reading selector changes Focus without activating a route', async ({ page }) => {
  await createProject(page, 'E2E Shared Focus Flow')
  await buildThreeNodeLineage(page)
  await fitGraph(page)

  // Create a second route so the root and answered child become shared nodes.
  await forkFromNode(page, 1, 'Route-B')
  const cards = page.locator('[data-route-id]')
  await expect(cards).toHaveCount(2)

  const activeCard = cards.filter({ has: page.getByTestId('active-route') }).first()
  const activeRouteId = await activeCard.getAttribute('data-route-id')
  expect(activeRouteId).not.toBeNull()
  const oldCard = cards.filter({ hasNot: page.getByTestId('active-route') }).first()
  const oldRouteId = await oldCard.getAttribute('data-route-id')
  expect(oldRouteId).not.toBeNull()

  // Clear the browser-only Focus (点击画布空白处即清除阅读聚焦).
  // Active remains the runtime working route.
  await page.locator('.vue-flow__pane').dispatchEvent('click')
  await expect(activeCard).not.toHaveClass(/route-card--focused/)
  await expect(activeCard.getByTestId('active-route')).toBeVisible()
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)
  await expect(page.locator('.graph-node--neutral')).toHaveCount(2)
  // 定位共享节点时用「当前查看」容器而不是下拉本身：下拉只在真歧义时存在，
  // 选定后会被只读徽标替换（Q2-D），用它做 filter 会让后续步骤定位不到节点。
  const sharedNode = page.locator('[data-test="graph-question-node"]').filter({
    has: page.getByTestId('shared-reading-route'),
  }).first()
  const selector = sharedNode.getByTestId('reading-route-select')
  await expect(selector).toHaveValue('')
  await expect(selector).toContainText('未选择')

  // Choose the historical route from the actual node control. This writes
  // Focus only; it does not activate the selected route.
  await selector.selectOption(oldRouteId!)
  // Q2-D：阅读路线一经确定就渲染为只读徽标，下拉随之消失。
  await expect(sharedNode.getByTestId('reading-route-select')).toHaveCount(0)
  await expect(sharedNode.getByTestId('reading-route-resolved')).toBeVisible()
  await expect(sharedNode.getByTestId('reading-route-resolved')).not.toHaveText('未选择')
  // Route sidebar is a fixed region: no floating window to open or close.
  await expect(page.getByTestId('left-sidebar')).toBeVisible()
  const activeCardAfterFocus = page.locator(`[data-route-id="${activeRouteId}"]`)
  const oldCardAfterFocus = page.locator(`[data-route-id="${oldRouteId}"]`)
  await expect(oldCardAfterFocus).toHaveClass(/route-card--focused/)
  await expect(activeCardAfterFocus.getByTestId('active-route')).toBeVisible()
  await fitGraph(page)

  // Branching uses the selected node Focus as sourceRouteId and never opens
  // a second source-route picker.
  let forkBody: { sourceRouteId?: string } | null = null
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().includes('/fork')) {
      forkBody = request.postDataJSON() as { sourceRouteId?: string }
    }
  })
  // The action rail is hidden until the node is hovered (or selected /
  // keyboard-focused). Hover the node's right half: the canvas toolbar
  // overlays the left canvas edge and can intercept a center hover.
  await hoverNode(sharedNode)
  await sharedNode.getByTestId('fork-node').click()
  await expect(page.getByTestId('fork-dialog')).toBeVisible()
  await expect(page.locator('[data-test="fork-source-route"]')).toHaveCount(0)
  await page.getByTestId('fork-submit').click()
  await expect(page.getByTestId('fork-dialog')).toHaveCount(0)
  await expect.poll(() => forkBody?.sourceRouteId).toBe(oldRouteId)
})

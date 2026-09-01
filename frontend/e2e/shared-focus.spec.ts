import { test, expect } from '@playwright/test'
import { buildThreeNodeLineage, closeFloatingWorkspaceWindows, createProject, fitGraph, forkFromNode } from './helpers'

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

  // Clear the browser-only Focus. Active remains the runtime working route.
  await activeCard.getByTestId('focus-route').click()
  await expect(activeCard).not.toHaveClass(/route-card--focused/)
  await expect(activeCard.getByTestId('active-route')).toBeVisible()
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)
  await expect(page.locator('.graph-node--neutral')).toHaveCount(2)
  const sharedNode = page.locator('[data-test="graph-question-node"]').filter({
    has: page.getByTestId('reading-route-select'),
  }).first()
  const selector = sharedNode.getByTestId('reading-route-select')
  await expect(selector).toHaveValue('')
  await expect(selector).toContainText('未选择')

  // Choose the historical route from the actual node control. This writes
  // Focus only; it does not activate the selected route.
  await selector.selectOption(oldRouteId!)
  await expect(selector).toHaveValue(oldRouteId!)
  await page.getByTestId('open-routes').click()
  const activeCardAfterFocus = page.locator(`[data-route-id="${activeRouteId}"]`)
  const oldCardAfterFocus = page.locator(`[data-route-id="${oldRouteId}"]`)
  await expect(oldCardAfterFocus).toHaveClass(/route-card--focused/)
  await expect(activeCardAfterFocus.getByTestId('active-route')).toBeVisible()
  await page.getByTestId('floating-window-routes').getByTestId('floating-window-close').click()
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
  // keyboard-focused). Real users must cross the gap to keep the rail
  // visible; the test mirrors that interaction explicitly.
  await sharedNode.hover()
  await sharedNode.getByTestId('fork-node').click()
  await expect(page.getByTestId('fork-dialog')).toBeVisible()
  await expect(page.locator('[data-test="fork-source-route"]')).toHaveCount(0)
  await page.getByTestId('fork-submit').click()
  await expect(page.getByTestId('fork-dialog')).toHaveCount(0)
  await expect.poll(() => forkBody?.sourceRouteId).toBe(oldRouteId)
})

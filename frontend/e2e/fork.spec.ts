import { test, expect } from '@playwright/test'
import {
  buildThreeNodeLineage,
  createProject,
  selectHistoricalNode,
} from './helpers'

/**
 * Fork flow: with enough history, select a historical node → Fork from here →
 * submit an optional route label → the new route appears and becomes active →
 * the old route remains → the new route lineage ends at the selected node
 * (root→fork-tip, no node copies).
 */
test('fork from a historical node creates an active historical view', async ({ page }) => {
  await createProject(page, 'E2E Fork Flow')
  await buildThreeNodeLineage(page)

  // Select the answered CHILD node (index 1); root=0, child=1, grandchild=2.
  await selectHistoricalNode(page, 1)

  // Fork with an optional label through the dialog.
  await page.getByTestId('fork-from-here').click()
  await expect(page.getByTestId('fork-dialog')).toBeVisible()
  await page.getByTestId('fork-label').fill('Forked from child')
  await page.getByTestId('fork-submit').click()

  // The new route becomes active: the workspace returns to the active
  // clarification view (no historical panel) and exactly one route is Active.
  await expect(page.getByTestId('historical-question')).toHaveCount(0)
  await expect(page.getByTestId('active-route')).toHaveCount(1)

  // Both routes remain visible: the original and the fork.
  await expect(page.getByTestId('route-card')).toHaveCount(2)

  // The selected (new active) route lineage runs root → fork-tip (2 nodes),
  // ending at the selected historical node (tip marker on the last node).
  await expect(page.getByTestId('lineage-node')).toHaveCount(2)
  await expect(page.getByTestId('lineage-node').nth(1).getByTestId('tip-node')).toBeVisible()
})
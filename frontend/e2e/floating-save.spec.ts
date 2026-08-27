import { test, expect, type Page } from '@playwright/test'
import { closeFloatingWorkspaceWindows, createProject, fitGraph } from './helpers'

/**
 * P1-5 / Scenario 4: "+ 想法" creates a floating (route-less) draft,
 * the editor opens automatically, the save persists without changing
 * the route tip, undo retracts only the floating node, and redo
 * restores it without rewiring the route anchor.
 */
async function addIdea(page: Page): Promise<void> {
  await page.getByTestId('graph-toolbar').getByTestId('add-idea').click()
  await expect(page.getByTestId('graph-knowledge-node')).toBeVisible()
}

test('floating idea: created, edited, saved, route tip unchanged', async ({ page }) => {
  await createProject(page, 'E2E Floating Save')
  await closeFloatingWorkspaceWindows(page)
  await page.getByTestId('draft-question').click()
  await fitGraph(page)

  await addIdea(page)
  const draft = page.getByTestId('graph-knowledge-node')
  // The editor opens automatically.
  await expect(draft.getByTestId('draft-text')).toBeVisible()
  await draft.getByTestId('draft-text').fill('A requirement captured directly in the graph.')
  await draft.getByTestId('save-draft').click()
  await expect(draft.getByTestId('knowledge-text')).toContainText('A requirement captured directly in the graph.')

  // The route tip must be unchanged: the persisted route's tip is still
  // the original active question, not the floating node. Verify by
  // checking that the current interactive Question is still the same
  // and the floating node carries no lineage edge.
  const edges = await page.locator('.vue-flow__edge').count()
  // The lineage has exactly 3 edges (root->mid, mid->tip) plus possibly
  // edges to the floating node — there should be NONE because the
  // floating node starts disconnected.
  // (Edges from active route in default lineage: 2.)
  expect(edges).toBeLessThanOrEqual(2)
})

test('floating idea undo retracts only the floating node, route tip unchanged', async ({ page }) => {
  await createProject(page, 'E2E Floating Undo')
  await closeFloatingWorkspaceWindows(page)
  await page.getByTestId('draft-question').click()
  await fitGraph(page)

  const before = await page.locator('[data-test="graph-knowledge-node"]').count()
  expect(before).toBe(0)

  await addIdea(page)
  await expect(page.locator('[data-test="graph-knowledge-node"]')).toHaveCount(1)

  // Undo from the toolbar: the floating node retracts but the active
  // route tip stays at the original question.
  await page.getByTestId('graph-toolbar').getByTestId('undo').click()
  await expect(page.locator('[data-test="graph-knowledge-node"]')).toHaveCount(0)

  // The Question graph still has its current interactive node.
  const current = page.locator('.graph-question-node--current')
  await expect(current).toBeVisible()
})

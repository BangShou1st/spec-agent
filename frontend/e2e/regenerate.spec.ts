import { test, expect } from '@playwright/test'
import {
  FAKE_ROOT_QUESTION,
  buildThreeNodeLineage,
  createProject,
  selectHistoricalNode,
} from './helpers'

/**
 * Deterministic regenerate flow: an answered NON-ROOT historical node on the
 * active OPEN route is regenerated. The old route becomes SUPERSEDED, the
 * replacement route becomes OPEN + ACTIVE, the replacement question is shown,
 * the old route stays visible, and the replacement lineage shows parent
 * lineage + replacement node only (no old child subtree).
 */
test('regenerate a historical node creates a replacement route without the old subtree', async ({ page }) => {
  await createProject(page, 'E2E Regenerate Flow')
  await buildThreeNodeLineage(page)

  // Select the answered NON-ROOT child (index 1).
  await selectHistoricalNode(page, 1)

  // Open the regenerate dialog: content is prefilled from the node (labels and
  // impacts only), then edited.
  await page.getByTestId('regenerate-this-question').click()
  await expect(page.getByTestId('regenerate-dialog')).toBeVisible()
  await expect(page.getByTestId('regenerate-question')).toHaveValue(FAKE_ROOT_QUESTION)

  await page.getByTestId('regenerate-question').fill('E2E Replacement Question')
  await page.getByTestId('regenerate-submit').click()

  // Replacement route becomes OPEN + ACTIVE and shows the replacement node.
  await expect(page.getByTestId('question')).toHaveText('E2E Replacement Question')
  await expect(page.getByTestId('active-route')).toHaveCount(1)

  // Both routes remain: the SUPERSEDED old route (created first) and the
  // replacement OPEN route.
  await expect(page.getByTestId('route-card')).toHaveCount(2)
  await expect(page.getByTestId('lifecycle-badge').nth(0)).toHaveText('Superseded')
  await expect(page.getByTestId('lifecycle-badge').nth(1)).toHaveText('Open')

  // The replacement lineage = parent (root) + replacement node only; the old
  // child subtree (the grandchild node) is absent, so 2 nodes and the tip is
  // the replacement node.
  await expect(page.getByTestId('lineage-node')).toHaveCount(2)
  await expect(page.getByTestId('lineage-node').nth(1)).toContainText('E2E Replacement Question')
  await expect(page.getByTestId('lineage-node').nth(1).getByTestId('tip-node')).toBeVisible()
  await expect(page.getByTestId('lineage-node').nth(1).getByTestId('supersedes-node')).toBeVisible()
})
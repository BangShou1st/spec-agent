import { test, expect } from '@playwright/test'
import { buildThreeNodeLineage, createProject, fitGraph } from './helpers'

test('re-answer keeps the canonical question and creates a distinct answer visual branch', async ({ page }) => {
  await createProject(page, 'E2E Re-answer Graph Flow')
  await buildThreeNodeLineage(page)
  await fitGraph(page)
  const inspector = page.getByTestId('floating-window-inspector')
  if (await inspector.isVisible()) {
    await inspector.getByTestId('floating-window-close').click()
  }

  const target = page.locator('[data-test="graph-question-node"]').nth(1)
  const canonicalId = await target.getAttribute('data-node-id')
  expect(canonicalId).not.toBeNull()
  await target.getByTestId('reanswer-node').click()
  await expect(page.getByTestId('reanswer-dialog')).toBeVisible()
  await expect(page.locator('[data-test="reanswer-source-route"]')).toHaveCount(0)
  await page.getByTestId('reanswer-submit').click()
  await expect(page.getByTestId('reanswer-dialog')).toHaveCount(0)

  // The old and new visual instances share a canonical Node id but have
  // different Vue Flow ids; the new Active instance is directly answerable.
  await expect(page.locator(`[data-test="graph-question-node"][data-node-id="${canonicalId}"]`)).toHaveCount(2)
  await expect(page.getByTestId('question')).toBeVisible()
  await expect(page.getByTestId('active-route')).toHaveCount(1)
})

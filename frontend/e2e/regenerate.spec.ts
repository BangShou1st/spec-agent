import { test, expect } from '@playwright/test'
import {
  FAKE_ROOT_QUESTION,
  buildThreeNodeLineage,
  createProject,
  fitGraph,
} from './helpers'

/**
 * Deterministic regenerate on the graph: an answered NON-ROOT historical
 * node is regenerated with explicit replacement content (no model). The old
 * route becomes 已替代, the replacement route becomes 当前路线, old history
 * stays visible, and the replacement relation is a distinct dashed edge.
 */
test('regenerate creates a replacement route and keeps old history', async ({ page }) => {
  await createProject(page, 'E2E Regenerate Graph Flow')
  await buildThreeNodeLineage(page)
  await fitGraph(page)

  // 非根的历史节点（child，index 1）重新生成。
  await page.locator('[data-test="graph-question-node"]').nth(1).getByTestId('regenerate-node').click()
  await expect(page.getByTestId('regenerate-dialog')).toBeVisible()
  await expect(page.getByTestId('regenerate-question')).toHaveValue(FAKE_ROOT_QUESTION)
  await page.getByTestId('regenerate-source-route').first().check()
  await page.getByTestId('regenerate-question').fill('E2E Replacement Question')
  await page.getByTestId('regenerate-submit').click()

  // 旧路线 → 已替代；替代路线 → 当前路线；替代节点为当前可回答节点。
  await expect(page.getByTestId('question')).toHaveText('E2E Replacement Question')
  await expect(page.getByTestId('active-route')).toHaveCount(1)
  const cards = page.locator('[data-route-id]')
  await expect(cards).toHaveCount(2)
  await expect(cards.nth(0).locator('.badge-superseded')).toBeVisible()
  await expect(cards.nth(1).locator('.badge-open')).toBeVisible()

  // 旧历史完整保留（root + child + grandchild），加上替代节点 = 4 个节点。
  await expect(page.locator('.graph-question-node')).toHaveCount(4)
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(3)

  // 替代关系是独立于 lineage 的虚线边（source = 被替代节点）。
  const replacementEdges = page.locator('.vue-flow__edge.graph-edge--replacement')
  await expect(replacementEdges).toHaveCount(1)
})

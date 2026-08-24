import { test, expect } from '@playwright/test'
import { buildThreeNodeLineage, closeFloatingWorkspaceWindows, createProject, fitGraph } from './helpers'

/**
 * Deterministic regenerate on the graph: an answered NON-ROOT historical
 * node is regenerated from a one-sentence direction through the deterministic
 * DRAFT_NODE boundary. The old route becomes 已替代 and no replacement edge
 * is added to the default lineage topology.
 */
test('regenerate creates a replacement route and keeps old history', async ({ page }) => {
  await createProject(page, 'E2E Regenerate Graph Flow')
  await buildThreeNodeLineage(page)
  await fitGraph(page)
  await closeFloatingWorkspaceWindows(page)

  // 非根的历史节点（child，index 1）重新生成。
  const root = page.locator('[data-test="graph-question-node"]').nth(0)
  await root.hover()
  await expect(root.getByTestId('regenerate-node')).toBeDisabled()
  const historicalNode = page.locator('[data-test="graph-question-node"]').nth(1)
  await historicalNode.hover()
  await expect(historicalNode.getByTestId('regenerate-node')).toBeVisible()
  await historicalNode.getByTestId('regenerate-node').click()
  await expect(page.getByTestId('regenerate-dialog')).toBeVisible()
  await expect(page.locator('[data-test="regenerate-question"]')).toHaveCount(0)
  await expect(page.locator('[data-test="replacement-option-row"]')).toHaveCount(0)
  await page.getByTestId('regenerate-instruction').fill('更关注如何验证这个需求是否达成')
  await page.getByTestId('regenerate-submit').click()

  // 旧路线 → 已替代；替代路线 → 当前路线；替代节点为当前可回答节点。
  await expect(page.getByTestId('question')).not.toHaveText('What is the most important outcome?')
  await page.getByTestId('open-routes').click()
  await expect(page.getByTestId('active-route')).toHaveCount(1)
  const cards = page.locator('[data-route-id]')
  await expect(cards).toHaveCount(2)
  await expect(cards.nth(0).locator('.badge-superseded')).toBeVisible()
  await expect(cards.nth(1).locator('.badge-open')).toBeVisible()

  // 旧历史完整保留（root + child + grandchild），加上替代节点 = 4 个节点。
  await expect(page.locator('.graph-question-node')).toHaveCount(4)
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(3)

  // 替代 provenance remains in Inspector; default topology is lineage-only.
  const replacementEdges = page.locator('.vue-flow__edge.graph-edge--replacement')
  await expect(replacementEdges).toHaveCount(0)
})

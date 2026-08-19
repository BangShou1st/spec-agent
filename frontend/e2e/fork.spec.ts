import { test, expect } from '@playwright/test'
import {
  buildThreeNodeLineage,
  createProject,
  fitGraph,
  forkFromNode,
} from './helpers'

/**
 * Fork semantics on the graph: from an answered shared history node the user
 * must pick an explicit source route. Shared nodes are never copied, old
 * answers stay immutable, and the new route drafts its first child.
 */
test('fork from an answered historical node keeps shared nodes and answers', async ({ page }) => {
  await createProject(page, 'E2E Fork Graph Flow')
  await buildThreeNodeLineage(page)
  await expect(page.locator('.graph-question-node')).toHaveCount(3)
  await fitGraph(page)

  // 从已回答的 child（index 1）fork；基路线默认 = 当前路线（active+open）。
  await forkFromNode(page, 1, 'Forked from child')

  // 两条路线可见；共享前缀仍是一份视觉节点，新路线已自动起草首个子问题。
  await expect(page.locator('[data-route-id]')).toHaveCount(2)
  await expect(page.locator('.graph-question-node')).toHaveCount(4)
  await expect(page.getByTestId('active-route')).toHaveCount(1)

  // 新路线的首个子问题是当前可回答节点。
  await expect(page.getByTestId('question')).toBeVisible()

  // 共享 root 的完整 per-route answer history 不再展开在图节点内；选择节点后
  // 在 Inspector 中查看，避免图卡承载 verbose route history。
  const sharedRoot = page.locator('[data-test="graph-question-node"]').nth(0)
  await page.getByTestId('floating-window-routes').getByTestId('floating-window-close').click()
  await sharedRoot.click()
  await expect(page.getByTestId('node-inspector')).toContainText('First answer content')
})

test('fork is blocked for an open-but-not-active base route until explicit activate', async ({ page }) => {
  await createProject(page, 'E2E Fork Base Rule')
  await buildThreeNodeLineage(page)
  await fitGraph(page)
  // 第一次 fork：child → 新路线 fork-B 成为当前路线；原路线 A 保持 OPEN 非当前。
  await forkFromNode(page, 1, 'Fork-B')
  const cards = page.locator('[data-route-id]')
  await expect(cards).toHaveCount(2)
  const nonActiveCard = cards.filter({ hasNot: page.getByTestId('active-route') }).first()
  await expect(nonActiveCard).toHaveCount(1)

  // 从共享 root（index 0）再次 fork，明确选择非当前的 OPEN 路线 A。
  const nonActiveId = (await nonActiveCard.getAttribute('data-route-id')) ?? ''
  await page.getByTestId('floating-window-routes').getByTestId('floating-window-close').click()
  await page.locator('[data-test="graph-question-node"]').nth(0).getByTestId('fork-node').click()
  await expect(page.getByTestId('fork-dialog')).toBeVisible()
  await page.locator(`input[name="fork-base-route"][value="${nonActiveId}"]`).check()
  await expect(page.getByTestId('fork-submit')).toBeDisabled()
  await page.getByTestId('activate-base-route').click()
  await expect(page.getByText('已设为当前路线。')).toBeVisible()
  await expect(page.getByTestId('fork-submit')).toBeEnabled()
  await page.getByTestId('fork-submit').click()
  await page.getByTestId('open-routes').click()
  await expect(cards).toHaveCount(3)
})

test('fork dialog completes restore and activate locally without implicit fork', async ({ page }) => {
  await createProject(page, 'E2E Fork Local Remediation')
  await buildThreeNodeLineage(page)
  await fitGraph(page)
  await forkFromNode(page, 1, 'Fork-B')

  const cards = page.locator('[data-route-id]')
  await expect(cards).toHaveCount(2)
  const archivedBase = cards.filter({ hasNot: page.getByTestId('active-route') }).first()
  const archivedId = await archivedBase.getAttribute('data-route-id')
  expect(archivedId).not.toBeNull()
  await archivedBase.getByTestId('archive-route').click()
  await page.getByTestId('confirm-route-action').click()
  await expect(page.getByText('已归档路线。')).toBeVisible()

  await page.getByTestId('floating-window-routes').getByTestId('floating-window-close').click()
  await page.locator('[data-test="graph-question-node"]').first().getByTestId('fork-node').click()
  await expect(page.getByTestId('fork-dialog')).toBeVisible()
  await page.locator(`input[name="fork-base-route"][value="${archivedId}"]`).check()
  await expect(page.getByTestId('fork-submit')).toBeDisabled()
  await expect(page.getByTestId('restore-base-route')).toBeVisible()
  await page.getByTestId('restore-base-route').click()
  await expect(page.getByTestId('fork-dialog')).toBeVisible()
  await expect(page.getByText('已恢复路线。')).toBeVisible()

  // Restore makes the recovered route Active in the existing Runtime contract.
  await expect(page.getByTestId('fork-submit')).toBeEnabled()
  await expect(page.getByTestId('fork-submit')).toBeEnabled()

  await page.getByTestId('fork-submit').click()
  await expect(page.getByTestId('fork-dialog')).toHaveCount(0)
  await page.getByTestId('open-routes').click()
  await expect(cards).toHaveCount(3)
})

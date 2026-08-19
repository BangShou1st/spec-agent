import { test, expect } from '@playwright/test'
import {
  buildThreeNodeLineage,
  createProject,
  fitGraph,
  forkFromNode,
} from './helpers'

/**
 * Fork semantics on the graph: from an answered shared history node the user
 * must pick an explicit base route; the runtime only forks when the base is
 * active + OPEN. Shared nodes are never copied, old answers stay, and the
 * new active route shows a waiting/answerable shared node.
 */
test('fork from an answered historical node keeps shared nodes and answers', async ({ page }) => {
  await createProject(page, 'E2E Fork Graph Flow')
  await buildThreeNodeLineage(page)
  await expect(page.locator('.graph-question-node')).toHaveCount(3)
  await fitGraph(page)

  // 从已回答的 child（index 1）fork；基路线默认 = 当前路线（active+open）。
  await forkFromNode(page, 1, 'Forked from child')

  // 两条路线可见；节点没有被复制（仍是 3 个）；新路线成为当前路线。
  await expect(page.locator('[data-route-id]')).toHaveCount(2)
  await expect(page.locator('.graph-question-node')).toHaveCount(3)
  await expect(page.getByTestId('active-route')).toHaveCount(1)

  // fork 不复制回答：共享 child 对新路线仍是等待回答状态（当前可回答节点）。
  await expect(page.getByTestId('question')).toBeVisible()

  // 共享 root 对旧路线 A 有回答、对新路线 B 没有：默认摘要为空（B 是当前
  // 路线，没有答案），展开后显示 A 的回答 —— 回答身份保持 route+node。
  const sharedRoot = page.locator('[data-test="graph-question-node"]').nth(0)
  await sharedRoot.getByTestId('toggle-expanded').click()
  await expect(sharedRoot.getByTestId('node-details')).toBeVisible()
  await expect(sharedRoot.getByText('First answer content')).toBeVisible()
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

  // 从共享 root（index 0）再次 fork，但选择非当前的 OPEN 路线 A → 禁止并提示先设为当前路线。
  await page.locator('[data-test="graph-question-node"]').nth(0).getByTestId('fork-node').click()
  await expect(page.getByTestId('fork-dialog')).toBeVisible()
  const nonActiveId = (await nonActiveCard.getAttribute('data-route-id')) ?? ''
  await page.locator(`input[name="fork-base-route"][value="${nonActiveId}"]`).check()
  await expect(page.getByTestId('fork-submit')).toBeDisabled()
  await expect(page.getByTestId('fork-blocker')).toContainText('先设为当前路线')
  await page.getByTestId('fork-cancel').click()

  // 显式把 A 设为当前路线后，同一节点可以正常 fork。
  await nonActiveCard.getByTestId('activate-route').click()
  await expect(page.getByTestId('active-route')).toHaveCount(1)
  await page.locator('[data-test="graph-question-node"]').nth(0).getByTestId('fork-node').click()
  await expect(page.getByTestId('fork-submit')).toBeEnabled()
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

  await page.locator('[data-test="graph-question-node"]').first().getByTestId('fork-node').click()
  await expect(page.getByTestId('fork-dialog')).toBeVisible()
  await page.locator(`input[name="fork-base-route"][value="${archivedId}"]`).check()
  await expect(page.getByTestId('fork-submit')).toBeDisabled()
  await expect(page.getByTestId('restore-base-route')).toBeVisible()
  const routesBeforeRestore = await cards.count()
  await page.getByTestId('restore-base-route').click()
  await expect(page.getByTestId('fork-dialog')).toBeVisible()
  await expect(page.getByText('已恢复路线。')).toBeVisible()
  await expect(cards).toHaveCount(routesBeforeRestore)

  // Restore makes the recovered route Active in the existing Runtime contract.
  // Select the other OPEN route to exercise the separate explicit Activate step.
  const nonActiveAfterRestore = cards.filter({ hasNot: page.getByTestId('active-route') }).first()
  const nonActiveId = await nonActiveAfterRestore.getAttribute('data-route-id')
  expect(nonActiveId).not.toBeNull()
  await page.locator(`input[name="fork-base-route"][value="${nonActiveId}"]`).check()
  await expect(page.getByTestId('activate-base-route')).toBeVisible()
  await expect(page.getByTestId('fork-submit')).toBeDisabled()
  await page.getByTestId('activate-base-route').click()
  await expect(page.getByTestId('fork-dialog')).toBeVisible()
  await expect(page.getByText('已设为当前路线。')).toBeVisible()
  await expect(cards).toHaveCount(routesBeforeRestore)
  await expect(page.getByTestId('fork-submit')).toBeEnabled()

  await page.getByTestId('fork-submit').click()
  await expect(page.getByTestId('fork-dialog')).toHaveCount(0)
  await expect(cards).toHaveCount(3)
})

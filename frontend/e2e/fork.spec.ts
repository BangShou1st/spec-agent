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
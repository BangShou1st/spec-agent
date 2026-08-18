import { test, expect } from '@playwright/test'
import { createProject, draftFirstQuestion } from './helpers'

/**
 * Route lifecycle through the graph route sidebar: archive active route →
 * no active route; restore → OPEN + ACTIVE; soft-delete → DELETED; restore
 * again. Every transition goes through the backend command API after an
 * explicit confirmation, and the graph keeps showing the historical nodes.
 */
test('archive, restore, and soft-delete a route through the graph sidebar', async ({ page }) => {
  await createProject(page, 'E2E Lifecycle Graph Flow')
  await draftFirstQuestion(page)

  const card = page.locator('[data-route-id]').first()
  await expect(card.getByTestId('active-route')).toBeVisible()

  // 归档需要显式确认；归档当前路线后没有当前路线。
  await card.getByTestId('archive-route').click()
  await expect(page.getByTestId('confirm-route-action-dialog')).toBeVisible()
  await page.getByTestId('confirm-route-action').click()
  await expect(card.locator('.badge-archived')).toBeVisible()
  await expect(page.getByTestId('active-route')).toHaveCount(0)
  // 历史节点仍在图上看得到（归档路线可只读检查）。
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(1)

  // 恢复 → OPEN + ACTIVE，根节点重新可回答。
  await card.getByTestId('restore-route').click()
  await expect(card.locator('.badge-open')).toBeVisible()
  await expect(page.getByTestId('active-route')).toHaveCount(1)
  await expect(page.locator('.graph-question-node--current')).toHaveCount(1)

  // 软删除需要确认；历史数据保留。
  await card.getByTestId('delete-route').click()
  await expect(page.getByTestId('confirm-route-action-dialog')).toBeVisible()
  await expect(page.getByTestId('confirm-description')).toContainText('软删除')
  await page.getByTestId('confirm-route-action').click()
  await expect(card.locator('.badge-deleted')).toBeVisible()
  await expect(page.getByTestId('active-route')).toHaveCount(0)
  // 已删除路线默认被生命周期筛选隐藏：图上不再有节点。
  await expect(page.locator('.graph-question-node')).toHaveCount(0)

  // 再恢复 → OPEN + ACTIVE，根节点重新出现在图上并回到可回答状态。
  await card.getByTestId('restore-route').click()
  await expect(card.locator('.badge-open')).toBeVisible()
  await expect(page.getByTestId('active-route')).toHaveCount(1)
  await expect(page.locator('.graph-question-node--current')).toHaveCount(1)
})

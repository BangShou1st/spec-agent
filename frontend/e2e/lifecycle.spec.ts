import { test, expect } from '@playwright/test'
import { createProject, draftFirstQuestion, openRouteFilters, openRouteMore } from './helpers'

/**
 * Route lifecycle through the graph route sidebar: archive active route →
 * no active route; restore → OPEN + ACTIVE. Every transition goes through the
 * backend command API after an explicit confirmation.
 *
 * 归档是唯一的"收起路线"动作（软删除入口已移除）：它只写
 * `routes.lifecycle_status = archived`，默认从视图隐藏，可在「已归档」筛选
 * 中只读找回并恢复；节点、答案与历史全部保留。
 */
test('archive and restore a route through the graph sidebar', async ({ page }) => {
  await createProject(page, 'E2E Lifecycle Graph Flow')
  await draftFirstQuestion(page)

  const card = page.locator('[data-route-id]').first()
  await expect(card.getByTestId('active-route')).toBeVisible()

  // 路线管理动作在每条路线的溢出菜单中。
  await openRouteMore(card)

  // 归档需要显式确认；归档当前路线后没有当前路线。
  await card.getByTestId('archive-route').click()
  await expect(page.getByTestId('confirm-route-action-dialog')).toBeVisible()
  await page.getByTestId('confirm-route-action').click()
  await expect(card.locator('.badge-archived')).toBeVisible()
  await expect(page.getByTestId('active-route')).toHaveCount(0)

  // 归档默认隐藏：图上不再有节点；历史数据未被删除。
  await expect(page.locator('.graph-question-node')).toHaveCount(0)

  // 仍在侧栏的卡片可以只读找回：勾选「已归档」筛选后节点重新出现。
  await openRouteFilters(page)
  await page.getByTestId('filter-archived').check()
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(1)

  // 恢复 → 运行路线徽标回到卡片，根节点重新可回答。
  // （open 生命周期不再展示常驻徽标，以 active-route 徽标为准。）
  await card.getByTestId('restore-route').click()
  await expect(card.getByTestId('active-route')).toBeVisible()
  await expect(page.locator('.graph-question-node--current')).toHaveCount(1)
})

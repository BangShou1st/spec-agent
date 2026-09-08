import { test, expect } from '@playwright/test'
import {
  answerActiveNode,
  buildThreeNodeLineage,
  closeFloatingWorkspaceWindows,
  createProject,
  fitGraph,
  forkFromNode,
  openRouteFilters,
  openRouteMore,
  openToolbarMore,
} from './helpers'

/**
 * Route display controls on the graph: locate ≠ focus ≠ activate, focus/
 * dim/hide/show-all, lifecycle filters, and Active-route protection.
 *
 * Route management controls live behind the per-route overflow menu
 * (data-test="route-more"); tests open it before interacting.
 */

test('locating a route only moves the viewport and never changes focus or active', async ({ page }) => {
  await createProject(page, 'E2E Locate Route')
  await buildThreeNodeLineage(page)
  await fitGraph(page)

  const card = page.locator('[data-route-id]').first()
  await openRouteMore(card)
  await card.getByTestId('locate-route').click()

  // locate ≠ focus ≠ activate：focus 状态不变，当前路线不变。
  await expect(card).not.toHaveClass(/route-card--focused/)
  await expect(page.getByTestId('active-route')).toHaveCount(1)
  await expect(page.locator('.graph-question-node')).toHaveCount(3)
})

test('focus, dim, hide, show-all and active protection on a two-route graph', async ({ page }) => {
  await createProject(page, 'E2E Route Display')
  await buildThreeNodeLineage(page)
  await fitGraph(page)
  // Fork 出第二条路线：B 成为当前路线，A 保持 OPEN 非当前。
  await forkFromNode(page, 1, 'Route-B')
  const cards = page.locator('[data-route-id]')
  await expect(cards).toHaveCount(2)

  // 聚焦非当前路线 A：只改变浏览器阅读上下文。
  const nonActive = cards.filter({ hasNot: page.getByTestId('active-route') }).first()
  await openRouteMore(nonActive)
  await nonActive.getByTestId('focus-route').click()
  await expect(nonActive).toHaveClass(/route-card--focused/)
  await expect(page.getByTestId('active-route')).toHaveCount(1)
  await expect(nonActive.getByTestId('focus-route')).toHaveText('取消浏览聚焦')

  // 弱化：路线保留可见但视觉降权。
  await nonActive.getByTestId('dim-route').click()
  await expect(nonActive).toHaveClass(/route-card--dimmed/)
  await expect(page.locator('.graph-question-node')).toHaveCount(4)

  // 隐藏非当前路线：只移除该路线专属元素，共享节点保留。
  await nonActive.getByTestId('hide-route').click()
  await expect(nonActive).toHaveClass(/route-card--hidden/)
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(2)

  // Focus A → Hide A：Focus 自动清除，焦点不再指向隐藏路线。
  await expect(nonActive).not.toHaveClass(/route-card--focused/)

  // 当前路线不可隐藏：按钮禁用。
  const active = cards.filter({ has: page.getByTestId('active-route') }).first()
  await openRouteMore(active)
  await expect(active.getByTestId('hide-route')).toBeDisabled()

  // 显示全部路线：清空手工 dim/hide；此前因隐藏 Focus A 已自动清除。
  // show-all 在 toolbar 溢出菜单中。
  await openToolbarMore(page)
  await page.getByTestId('show-all').click()
  await expect(nonActive).not.toHaveClass(/route-card--focused/)
  await expect(nonActive).not.toHaveClass(/route-card--dimmed/)
  await expect(nonActive).not.toHaveClass(/route-card--hidden/)
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(3)

  // 生命周期筛选：归档有专属节点的非当前路线 A（其专属节点 c）后再筛选
  // “已归档” → A 从图上消失，共享节点保留；B 的当前节点 b 不受影响。
  // （当前路线 B 与 A 共享 a/b，本身没有专属节点，归档它无法演示筛选。）
  await openRouteMore(nonActive)
  await nonActive.getByTestId('archive-route').click()
  await page.getByTestId('confirm-route-action').click()
  await expect(page.getByText('已归档路线。')).toBeVisible()
  await openRouteFilters(page)
  await page.getByTestId('filter-archived').uncheck()
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(2)
  await page.getByTestId('filter-archived').check()
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(3)
})

test('fixed route sidebar navigates without changing runtime active', async ({ page }) => {
  await createProject(page, 'E2E Fixed Route Sidebar')
  await buildThreeNodeLineage(page)
  await fitGraph(page)
  await forkFromNode(page, 1, 'Route-B')

  const routes = page.getByTestId('route-sidebar')
  await expect(routes).toBeVisible()
  const firstRoute = routes.locator('[data-route-id]').first()
  await firstRoute.click()
  await expect(firstRoute).toHaveClass(/route-card--focused/)
  // Focus 改变只影响浏览器阅读上下文；Runtime Active 不变。
  await expect(page.getByTestId('active-route')).toHaveCount(1)
})

test('fixed sidebars reserve layout space and leave the graph interactive', async ({ page }) => {
  await createProject(page, 'E2E Fixed Sidebars')
  await buildThreeNodeLineage(page)
  await fitGraph(page)
  await forkFromNode(page, 1, 'Route-B')
  await answerActiveNode(page, 'Route B answer')
  await expect(page.locator('.graph-question-node')).toHaveCount(5)
  // 新增节点不触发自动全图适应（已冻结行为）：显式 fit 把新节点带进视口。
  await fitGraph(page)

  const left = page.getByTestId('left-sidebar')
  const canvas = page.getByTestId('graph-canvas')
  const right = page.getByTestId('right-sidebar')
  await expect(left).toBeVisible()
  await expect(canvas).toBeVisible()
  await expect(right).toBeVisible()

  const [l, c, r] = await Promise.all([left.boundingBox(), canvas.boundingBox(), right.boundingBox()])
  expect(l).not.toBeNull()
  expect(c).not.toBeNull()
  expect(r).not.toBeNull()
  expect((l?.x ?? 0) + (l?.width ?? 0)).toBeLessThanOrEqual((c?.x ?? 0) + 1)
  expect((c?.x ?? 0) + (c?.width ?? 0)).toBeLessThanOrEqual((r?.x ?? 0) + 1)

  // 收起左侧栏后 Graph 获得更多空间；当前问题仍可回答。
  // 注意收起后路线卡片不在 DOM 中：重新展开后再断言 Focus 语义。
  await page.getByTestId('toggle-left').click()
  await expect(page.getByTestId('question')).toBeVisible()
  await page.getByTestId('toggle-left').click()

  // 点击当前节点：浏览器 Focus 跟随到 Active 路线；Runtime Active 不变。
  await page.getByTestId('question').click()
  const activeCard = page.locator('[data-route-id]').filter({ has: page.getByTestId('active-route') }).first()
  await expect(activeCard).toHaveClass(/route-card--focused/)
  await expect(page.getByTestId('active-route')).toHaveCount(1)
})

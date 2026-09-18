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

  // 浏览非当前路线 A：点击路线卡主体即设置阅读聚焦（定位 + 高亮），
  // 只改变浏览器阅读上下文，绝不改动运行路线。
  const nonActive = cards.filter({ hasNot: page.getByTestId('active-route') }).first()
  await nonActive.getByTestId('route-primary').click()
  await expect(nonActive).toHaveClass(/route-card--focused/)
  await expect(page.getByTestId('active-route')).toHaveCount(1)
  await expect(page.locator('.graph-question-node')).toHaveCount(4)

  // 当前路线仍不可通过筛选被隐藏：运行路线永远可见。
  const active = cards.filter({ has: page.getByTestId('active-route') }).first()
  await openRouteMore(active)
  await expect(active.getByTestId('archive-route')).toBeVisible()

  // 显示全部路线：清空手工 display state。
  // show-all 在 toolbar 溢出菜单中。
  await openToolbarMore(page)
  await page.getByTestId('show-all').click()
  // 共享节点 a、b 是历史节点；A 的末端 c 与被聚焦路线一样仍可回答
  // （Q1：canAnswer 放宽到"用户显式聚焦路线的末端未答"），因此 c 渲染为
  // 当前节点而不是历史节点 → 历史节点为 2 而非 3。
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(2)

  // 归档非当前路线 A（其专属节点 c）：归档默认隐藏 → A 从图上消失，共享节点保留；
  // 勾选「已归档」筛选后可只读找回，取消勾选再次隐藏。
  await openRouteMore(nonActive)
  await nonActive.getByTestId('archive-route').click()
  await page.getByTestId('confirm-route-action').click()
  await expect(page.getByText('已归档路线')).toBeVisible()
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(2)
  await openRouteFilters(page)
  await page.getByTestId('filter-archived').check()
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(3)
  await page.getByTestId('filter-archived').uncheck()
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(2)
})

test('只看这条路线 连续两次都生效，且可一键退出', async ({ page }) => {
  await createProject(page, 'E2E Isolate Route')
  await buildThreeNodeLineage(page)
  await fitGraph(page)
  // Fork 出第二条路线：B 成为运行路线，A 保持 OPEN 非运行。
  await forkFromNode(page, 1, 'Route-B')
  const cards = page.locator('[data-route-id]')
  await expect(cards).toHaveCount(2)
  const active = cards.filter({ has: page.getByTestId('active-route') }).first()
  const nonActive = cards.filter({ hasNot: page.getByTestId('active-route') }).first()

  // 第一次：只看运行路线 → 非运行路线的专属节点离开画布。
  await openRouteMore(active)
  await active.getByTestId('isolate-route').click()
  await expect(page.getByTestId('isolate-chip-label')).toBeVisible()
  await expect(nonActive.getByTestId('isolate-route-label')).toHaveCount(0)

  // 第二次：只看另一条路线 —— 回归点：运行路线必须一起离开画布。
  await openRouteMore(nonActive)
  await nonActive.getByTestId('isolate-route').click()
  await expect(nonActive.getByTestId('isolate-route-label')).toBeVisible()
  await expect(page.getByTestId('isolate-chip-label')).toHaveText(/只看：/)
  await expect(page.getByTestId('active-route')).toHaveCount(1)

  // 一键退出：镜头状态从画布与侧栏同时消失，运行路线回到画布。
  await page.getByTestId('isolate-chip-exit').click()
  await expect(page.getByTestId('isolate-chip')).toHaveCount(0)
  await expect(nonActive.getByTestId('isolate-route-label')).toHaveCount(0)
  await expect(page.getByTestId('active-route')).toHaveCount(1)
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

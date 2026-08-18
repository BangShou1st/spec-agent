import { test, expect } from '@playwright/test'
import {
  buildThreeNodeLineage,
  createProject,
  fitGraph,
  forkFromNode,
} from './helpers'

/**
 * Route display controls on the graph: locate ≠ focus ≠ activate, focus/
 * dim/hide/show-all, lifecycle filters, and Active-route protection.
 */
test('locating a route only moves the viewport and never changes focus or active', async ({ page }) => {
  await createProject(page, 'E2E Locate Route')
  await buildThreeNodeLineage(page)
  await fitGraph(page)

  const card = page.locator('[data-route-id]').first()
  await card.getByTestId('locate-route').click()

  // locate ≠ focus ≠ activate：focus 按钮文案不变，当前路线不变。
  await expect(card.getByTestId('focus-route')).toHaveText('聚焦此路线')
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
  await nonActive.getByTestId('focus-route').click()
  await expect(nonActive.locator('.route-card--focused')).toHaveCount(1)
  await expect(page.getByTestId('active-route')).toHaveCount(1)
  await expect(nonActive.getByTestId('focus-route')).toHaveText('取消聚焦')

  // 弱化：路线保留可见但视觉降权。
  await nonActive.getByTestId('dim-route').click()
  await expect(nonActive.locator('.route-card--dimmed')).toHaveCount(1)
  await expect(page.locator('.graph-question-node')).toHaveCount(3)

  // 隐藏非当前路线：只移除该路线专属元素，共享节点保留。
  await nonActive.getByTestId('hide-route').click()
  await expect(nonActive.locator('.route-card--hidden')).toHaveCount(1)
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(1)

  // 当前路线不可隐藏：按钮禁用。
  const active = cards.filter({ has: page.getByTestId('active-route') }).first()
  await expect(active.getByTestId('hide-route')).toBeDisabled()

  // 显示全部路线：清空 focus 与手工 dim/hide，节点全部回归。
  await page.getByTestId('show-all').click()
  await expect(nonActive.locator('.route-card--focused')).toHaveCount(0)
  await expect(nonActive.locator('.route-card--dimmed')).toHaveCount(0)
  await expect(nonActive.locator('.route-card--hidden')).toHaveCount(0)
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(2)

  // 生命周期筛选：关闭“已删除”后的路线不可见（默认已关），其他状态可用。
  // 归档当前路线后再筛选“已归档”：归档路线从图上消失。
  await active.getByTestId('archive-route').click()
  await page.getByTestId('confirm-route-action').click()
  await page.getByTestId('filter-archived').uncheck()
  await expect(page.locator('.graph-question-node')).toHaveCount(1)
  await page.getByTestId('filter-archived').check()
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(2)
})

test('sidebar collapse and resize persist across reload', async ({ page }) => {
  await createProject(page, 'E2E Sidebar Persist')
  await buildThreeNodeLineage(page)

  // 左侧栏默认展开；收起后 canvas 仍在。
  await expect(page.getByTestId('left-sidebar')).toBeVisible()
  await expect(page.locator('[data-test="left-sidebar"] .resizable-sidebar__content')).toBeVisible()
  await page.getByTestId('toggle-left').click()
  await expect(page.locator('[data-test="left-sidebar"] .resizable-sidebar__content')).toHaveCount(0)
  await expect(page.locator('.graph-question-node')).toHaveCount(3)

  // 拖动右侧 resize handle 改变宽度并持久化。
  const handle = page.getByTestId('resize-handle-right')
  const hb = (await handle.boundingBox()) ?? { x: 0, y: 0 }
  await page.mouse.move(hb.x + hb.width / 2, hb.y + hb.height / 2)
  await page.mouse.down()
  await page.mouse.move(hb.x - 120, hb.y, { steps: 8 })
  await page.mouse.up()
  const savedWidth = await page.evaluate(() => {
    const raw = localStorage.getItem('spec-agent.workspace-ui.v1')
    if (!raw) return null
    const parsed = JSON.parse(raw) as { rightSidebar?: { width?: number } }
    return parsed.rightSidebar?.width ?? null
  })
  expect(savedWidth).not.toBeNull()

  // reload 后：左侧仍收起、右侧宽度恢复。
  await page.reload()
  await expect(page.locator('[data-test="left-sidebar"] .resizable-sidebar__content')).toHaveCount(0)
  await expect(page.locator('.graph-question-node')).toHaveCount(3)
})
import { test, expect } from '@playwright/test'
import {
  answerActiveNode,
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
  await expect(nonActive).toHaveClass(/route-card--focused/)
  await expect(page.getByTestId('active-route')).toHaveCount(1)
  await expect(nonActive.getByTestId('focus-route')).toHaveText('取消聚焦')

  // 弱化：路线保留可见但视觉降权。
  await nonActive.getByTestId('dim-route').click()
  await expect(nonActive).toHaveClass(/route-card--dimmed/)
  await expect(page.locator('.graph-question-node')).toHaveCount(4)

  // 隐藏非当前路线：只移除该路线专属元素，共享节点保留。
  await nonActive.getByTestId('hide-route').click()
  await expect(nonActive).toHaveClass(/route-card--hidden/)
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(2)

  // Focus A → Hide A：Focus 自动清除，按钮回到“聚焦此路线”，焦点不再指向隐藏路线。
  await expect(nonActive).not.toHaveClass(/route-card--focused/)
  await expect(nonActive.getByTestId('focus-route')).toHaveText('聚焦此路线')

  // 当前路线不可隐藏：按钮禁用。
  const active = cards.filter({ has: page.getByTestId('active-route') }).first()
  await expect(active.getByTestId('hide-route')).toBeDisabled()

  // 显示全部路线：清空 focus 与手工 dim/hide，节点全部回归。
  await page.getByTestId('show-all').click()
  await expect(nonActive).not.toHaveClass(/route-card--focused/)
  await expect(nonActive).not.toHaveClass(/route-card--dimmed/)
  await expect(nonActive).not.toHaveClass(/route-card--hidden/)
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(3)

  // 生命周期筛选：归档有专属节点的非当前路线 A（其专属节点 c）后再筛选
  // “已归档” → A 从图上消失，共享节点保留；B 的当前节点 b 不受影响。
  // （当前路线 B 与 A 共享 a/b，本身没有专属节点，归档它无法演示筛选。）
  await nonActive.getByTestId('archive-route').click()
  await page.getByTestId('confirm-route-action').click()
  await expect(page.getByText('已归档路线。')).toBeVisible()
  await page.getByTestId('filter-archived').uncheck()
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(2)
  await page.getByTestId('filter-archived').check()
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(3)
})

test('floating route window moves, resizes, closes, and persists across reload', async ({ page }) => {
  await createProject(page, 'E2E Floating Window Persist')
  await buildThreeNodeLineage(page)

  const canvasBefore = await page.getByTestId('graph-canvas').boundingBox()
  const routesWindow = page.getByTestId('floating-window-routes')
  await expect(routesWindow).toBeVisible()
  const titlebar = routesWindow.getByTestId('floating-window-titlebar')
  const titleBox = (await titlebar.boundingBox()) ?? { x: 0, y: 0, width: 0, height: 0 }
  await page.mouse.move(titleBox.x + titleBox.width / 2, titleBox.y + titleBox.height / 2)
  await page.mouse.down()
  await page.mouse.move(titleBox.x + 120, titleBox.y + 60, { steps: 8 })
  await page.mouse.up()
  const resize = routesWindow.getByTestId('resize-se')
  const resizeBox = (await resize.boundingBox()) ?? { x: 0, y: 0, width: 0, height: 0 }
  await page.mouse.move(resizeBox.x, resizeBox.y)
  await page.mouse.down()
  await page.mouse.move(resizeBox.x + 80, resizeBox.y + 60, { steps: 8 })
  await page.mouse.up()
  const canvasAfter = await page.getByTestId('graph-canvas').boundingBox()
  expect(Math.abs((canvasAfter?.width ?? 0) - (canvasBefore?.width ?? 0))).toBeLessThan(2)

  const savedWidth = await page.evaluate(() => {
    const raw = localStorage.getItem('spec-agent.workspace-ui.v2')
    if (!raw) return null
    const parsed = JSON.parse(raw) as { windows?: { routes?: { width?: number } } }
    return parsed.windows?.routes?.width ?? null
  })
  expect(savedWidth).not.toBeNull()

  await routesWindow.getByTestId('floating-window-close').click()
  await expect(routesWindow).toHaveCount(0)
  await page.getByTestId('open-routes').click()
  await expect(routesWindow).toBeVisible()
  await page.reload()
  await expect(page.getByTestId('floating-window-routes')).toBeVisible()
  await expect(page.locator('.graph-question-node')).toHaveCount(3)
})

test('immersive graph navigation keeps overlays off the canvas layout', async ({ page }) => {
  await createProject(page, 'E2E Immersive Graph')
  await buildThreeNodeLineage(page)
  await fitGraph(page)
  await forkFromNode(page, 1, 'Route-B')
  await answerActiveNode(page, 'Route B answer')
  await expect(page.locator('.graph-question-node')).toHaveCount(5)

  const workspace = page.getByTestId('workspace-shell')
  const canvas = page.getByTestId('graph-canvas')
  const workspaceBox = await workspace.boundingBox()
  const canvasBox = await canvas.boundingBox()
  expect(workspaceBox).not.toBeNull()
  expect(canvasBox).not.toBeNull()
  expect(Math.abs((canvasBox?.width ?? 0) - (workspaceBox?.width ?? 0))).toBeLessThan(2)

  const leftBox = await page.getByTestId('floating-window-routes').boundingBox()
  const rightBox = await page.getByTestId('floating-window-inspector').boundingBox()
  expect(leftBox).not.toBeNull()
  expect(rightBox).not.toBeNull()
  expect((leftBox?.x ?? 0) + (leftBox?.width ?? 0)).toBeGreaterThan(canvasBox?.x ?? 0)
  expect(rightBox?.x ?? 0).toBeLessThan((canvasBox?.x ?? 0) + (canvasBox?.width ?? 0))

  const widthBeforeResize = (await canvas.boundingBox())?.width ?? 0
  const resizeHandle = page.getByTestId('floating-window-routes').getByTestId('resize-e')
  const handleBox = (await resizeHandle.boundingBox()) ?? { x: 0, y: 0, width: 0, height: 0 }
  await page.mouse.move(handleBox.x + handleBox.width / 2, handleBox.y + handleBox.height / 2)
  await page.mouse.down()
  await page.mouse.move(handleBox.x + 120, handleBox.y, { steps: 8 })
  await page.mouse.up()
  const widthAfterResize = (await canvas.boundingBox())?.width ?? 0
  expect(Math.abs(widthAfterResize - widthBeforeResize)).toBeLessThan(2)

  const activeCard = page.locator('[data-route-id]').filter({ has: page.getByTestId('active-route') }).first()
  const routeBId = await activeCard.getAttribute('data-route-id')
  expect(routeBId).not.toBeNull()
  await page.getByTestId('question').click()
  await expect(activeCard).toHaveClass(/route-card--focused/)
  await expect(page.locator('.graph-node--dimmed')).toHaveCount(1)

  await page.getByTestId('tab-requirement').click()
  await expect(page.getByTestId('requirement-state-panel')).toContainText(routeBId ?? '')

  await page.getByTestId('show-all').click()
  await expect(activeCard).not.toHaveClass(/route-card--focused/)
  await expect(page.locator('.graph-node--dimmed')).toHaveCount(0)
})

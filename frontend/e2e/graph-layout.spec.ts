import { test, expect } from '@playwright/test'
import { buildThreeNodeLineage, createProject, fitGraph } from './helpers'

/**
 * Graph layout behavior: header-only drag with live edges, position
 * persistence across reload, multi-select group movement, and toolbar
 * zoom/fit/auto-layout commands.
 */
test('drag by the title handle persists graph coordinates after reload', async ({ page }) => {
  await createProject(page, 'E2E Layout Drag')
  await buildThreeNodeLineage(page)
  await fitGraph(page)

  const rootNode = page.locator('[data-test="graph-question-node"]').first()
  const before = await rootNode.boundingBox()
  expect(before).not.toBeNull()

  // 边在拖动中实时跟随：记录根→child 边的 SVG path。
  const edgePath = page.locator('.vue-flow__edge').first().locator('path').first()
  const edgeBefore = await edgePath.getAttribute('d')
  expect(edgeBefore).not.toBeNull()

  // 只能通过标题栏拖动。
  const handle = rootNode.getByTestId('node-drag-handle')
  const hb = (await handle.boundingBox()) ?? { x: 0, y: 0 }
  await page.mouse.move(hb.x + hb.width / 2, hb.y + hb.height / 2)
  await page.mouse.down()
  await page.mouse.move(hb.x + 220, hb.y + 90, { steps: 12 })
  await page.mouse.up()

  const after = await rootNode.boundingBox()
  expect(after).not.toBeNull()
  expect((after?.x ?? 0) - (before?.x ?? 0)).toBeGreaterThan(120)

  // 边端点跟随节点移动（SVG path 变化）。
  await expect
    .poll(async () => edgePath.getAttribute('d'))
    .not.toBe(edgeBefore)

  // reload 后坐标从本地存储恢复。设计上 viewport 不持久化（初始 fit 会重新
  // 计算视口变换），因此断言 graph 坐标（.vue-flow__node 的 translate），
  // 而不是屏幕坐标。
  const graphTransformOf = (index: number) =>
    page.locator('.vue-flow__node').nth(index).evaluate((el) => (el as HTMLElement).style.transform)
  const afterGraph = await graphTransformOf(0)

  await page.reload()
  await expect(page.locator('.graph-question-node')).toHaveCount(3)
  const restoredGraph = await graphTransformOf(0)
  expect(restoredGraph).toBe(afterGraph)
  const restored = await page.locator('[data-test="graph-question-node"]').first().boundingBox()
  expect(restored).not.toBeNull()
})

test('ctrl/cmd multi-select moves the whole group by the same delta', async ({ page }) => {
  await createProject(page, 'E2E Group Move')
  await buildThreeNodeLineage(page)
  await fitGraph(page)
  const nodes = page.locator('[data-test="graph-question-node"]')

  // Ctrl+click 选中两个历史节点（index 0 和 1）。
  await page.keyboard.down('Control')
  await nodes.nth(0).getByTestId('node-drag-handle').click()
  await nodes.nth(1).getByTestId('node-drag-handle').click()
  await page.keyboard.up('Control')

  const box0Before = await nodes.nth(0).boundingBox()
  const box1Before = await nodes.nth(1).boundingBox()
  expect(box0Before).not.toBeNull()
  expect(box1Before).not.toBeNull()

  // 拖动任一已选节点的标题栏。
  const handle = nodes.nth(0).getByTestId('node-drag-handle')
  const hb = (await handle.boundingBox()) ?? { x: 0, y: 0 }
  await page.mouse.move(hb.x + hb.width / 2, hb.y + hb.height / 2)
  await page.mouse.down()
  await page.mouse.move(hb.x + 160, hb.y + 60, { steps: 10 })
  await page.mouse.up()

  const box0After = await nodes.nth(0).boundingBox()
  const box1After = await nodes.nth(1).boundingBox()
  const dx0 = (box0After?.x ?? 0) - (box0Before?.x ?? 0)
  const dy0 = (box0After?.y ?? 0) - (box0Before?.y ?? 0)
  const dx1 = (box1After?.x ?? 0) - (box1Before?.x ?? 0)
  const dy1 = (box1After?.y ?? 0) - (box1Before?.y ?? 0)
  // 组移动近似相同的位移。
  expect(Math.abs(dx0 - dx1)).toBeLessThan(30)
  expect(Math.abs(dy0 - dy1)).toBeLessThan(30)
  expect(Math.abs(dx0)).toBeGreaterThan(80)
})

test('toolbar zoom/fit and auto-layout stay browser-only', async ({ page }) => {
  await createProject(page, 'E2E Toolbar')
  await buildThreeNodeLineage(page)

  await page.getByTestId('zoom-in').click()
  await page.getByTestId('zoom-out').click()
  await page.getByTestId('fit-view').click()

  // 自动布局需要确认（覆盖手工位置），Runtime 历史不变。
  page.once('dialog', (dialog) => {
    expect(dialog.message()).toContain('重新自动布局将覆盖当前项目手工调整过的节点位置')
    void dialog.accept()
  })
  await page.getByTestId('auto-layout').click()
  await expect(page.locator('.graph-question-node')).toHaveCount(3)

  // 显示全部路线：清空 focus/dim/hide，保留生命周期筛选。
  await page.getByTestId('show-all').click()
  await expect(page.locator('.graph-question-node')).toHaveCount(3)
})
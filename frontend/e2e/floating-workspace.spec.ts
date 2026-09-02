import { test, expect, type Locator } from '@playwright/test'
import { buildThreeNodeLineage, createProject, fitGraph } from './helpers'

async function boxesDoNotOverlap(first: Locator, second: Locator): Promise<boolean> {
  const [a, b] = await Promise.all([first.boundingBox(), second.boundingBox()])
  if (!a || !b) return false
  return a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y
}

test.setTimeout(400000)

test('floating windows persist geometry and never change the graph canvas dimensions', async ({ page }) => {
  await createProject(page, 'E2E Floating Workspace')
  await buildThreeNodeLineage(page)

  const canvas = page.getByTestId('graph-canvas')
  const before = await canvas.boundingBox()
  const routes = page.getByTestId('floating-window-routes')
  const inspector = page.getByTestId('floating-window-inspector')
  await expect(routes).toBeVisible()
  await expect(inspector).toBeVisible()
  const currentNode = page.locator('.graph-question-node--current')
  await expect.poll(() => boxesDoNotOverlap(inspector, currentNode)).toBe(true)

  await inspector.getByTestId('floating-window-titlebar').click()
  await expect(inspector).toHaveCSS('z-index', '21')

  const title = routes.getByTestId('floating-window-titlebar')
  const titleBox = (await title.boundingBox()) ?? { x: 0, y: 0, width: 0, height: 0 }
  await page.mouse.move(titleBox.x + 30, titleBox.y + 18)
  await page.mouse.down()
  await page.mouse.move(titleBox.x + 5, titleBox.y + 5, { steps: 6 })
  await page.mouse.up()

  const edge = routes.getByTestId('resize-se')
  const edgeBox = (await edge.boundingBox()) ?? { x: 0, y: 0, width: 0, height: 0 }
  await page.mouse.move(edgeBox.x, edgeBox.y)
  await page.mouse.down()
  await page.mouse.move(edgeBox.x + 50, edgeBox.y + 40, { steps: 6 })
  await page.mouse.up()

  const after = await canvas.boundingBox()
  expect(Math.abs((after?.width ?? 0) - (before?.width ?? 0))).toBeLessThan(2)
  expect(Math.abs((after?.height ?? 0) - (before?.height ?? 0))).toBeLessThan(2)

  const saved = await page.evaluate(() => localStorage.getItem('spec-agent.workspace-ui.v2'))
  expect(saved).not.toBeNull()
  expect(JSON.parse(saved ?? '{}').windows.routes.positionMode).toBe('manual')
  await page.reload()
  await expect(page.getByTestId('floating-window-routes')).toBeVisible()
  await expect(page.getByTestId('floating-window-inspector')).toBeVisible()
  await page.getByTestId('floating-window-inspector').getByTestId('floating-window-reset').click()
  await expect.poll(async () => {
    const raw = await page.evaluate(() => localStorage.getItem('spec-agent.workspace-ui.v2'))
    return JSON.parse(raw ?? '{}').windows.inspector.positionMode
  }).toBe('auto')
  await expect.poll(() => boxesDoNotOverlap(
    page.getByTestId('floating-window-inspector'),
    page.locator('.graph-question-node--current'),
  )).toBe(true)
})

test('auto layout remains usable on a small viewport and keeps float windows apart', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 700 })
  await createProject(page, 'E2E Small Floating Workspace')
  await page.getByTestId('floating-window-inspector').getByTestId('floating-window-reset').click()
  await expect.poll(() => boxesDoNotOverlap(
    page.getByTestId('floating-window-routes'),
    page.getByTestId('floating-window-inspector'),
  )).toBe(true)

  await page.getByTestId('draft-question').click()
  const current = page.locator('.graph-question-node--current')
  try {
    await expect(current).toBeVisible({ timeout: 180000 })
  } catch {
    const retry = page.getByRole('button', { name: '重新请求' })
    if (await retry.isVisible()) {
      await retry.click()
      await expect(current).toBeVisible({ timeout: 180000 })
    } else {
      throw new Error('draft in small viewport did not produce current node')
    }
  }
  await fitGraph(page)
  await expect.poll(() => boxesDoNotOverlap(
    page.getByTestId('floating-window-inspector'),
    current,
  )).toBe(true)

  // Exercise the graph node itself after generation; this catches an overlay
  // that looks separated in screenshots but still intercepts pointer input.
  await current.hover()
  const nodeInput = current.getByTestId('free-text')
  await nodeInput.click()
  await expect(nodeInput).toBeFocused()

  await nodeInput.fill('small viewport input')
  await expect(nodeInput).toHaveValue('small viewport input')
})

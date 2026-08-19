import { test, expect } from '@playwright/test'
import { buildThreeNodeLineage, createProject } from './helpers'

test('floating windows persist geometry and never change the graph canvas dimensions', async ({ page }) => {
  await createProject(page, 'E2E Floating Workspace')
  await buildThreeNodeLineage(page)

  const canvas = page.getByTestId('graph-canvas')
  const before = await canvas.boundingBox()
  const routes = page.getByTestId('floating-window-routes')
  const inspector = page.getByTestId('floating-window-inspector')
  await expect(routes).toBeVisible()
  await expect(inspector).toBeVisible()

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
  await page.reload()
  await expect(page.getByTestId('floating-window-routes')).toBeVisible()
  await expect(page.getByTestId('floating-window-inspector')).toBeVisible()
  await page.getByTestId('reset-windows').click()
  await expect(page.getByTestId('floating-window-routes')).toBeVisible()
  await expect(page.getByTestId('floating-window-routes')).toHaveCSS('left', '24px')
  await expect(page.getByTestId('floating-window-routes')).toHaveCSS('top', '72px')
  await expect(page.getByTestId('floating-window-routes')).toHaveCSS('width', '320px')
  await expect(page.getByTestId('floating-window-routes')).toHaveCSS('height', '560px')
  await expect(page.getByTestId('floating-window-inspector')).toHaveCSS('left', '836px')
  await expect(page.getByTestId('floating-window-inspector')).toHaveCSS('top', '72px')
  await expect(page.getByTestId('floating-window-inspector')).toHaveCSS('width', '420px')
  await expect(page.getByTestId('floating-window-inspector')).toHaveCSS('height', '640px')
})

import { test, expect } from '@playwright/test'
import { buildThreeNodeLineage, createProject, fitGraph } from './helpers'

test.setTimeout(400000)

const viewports = [
  { width: 1366, height: 768 },
  { width: 1440, height: 900 },
  { width: 1920, height: 1080 },
]

for (const viewport of viewports) {
  test(`${viewport.width}x${viewport.height} keeps route, graph, and inspector non-overlapping`, async ({ page }) => {
    await page.setViewportSize(viewport)
    await createProject(page, `E2E Fixed Workspace ${viewport.width}`)
    await buildThreeNodeLineage(page)
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
  })
}

test('minimum desktop layout keeps the current question interactive', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 768 })
  await createProject(page, 'E2E Fixed Workspace Interaction')
  await buildThreeNodeLineage(page)
  await fitGraph(page)

  const current = page.locator('.graph-question-node--current')
  await expect(current).toBeVisible()
  const input = current.getByTestId('free-text')
  await input.click()
  await input.fill('fixed layout remains interactive')
  await expect(input).toHaveValue('fixed layout remains interactive')
})

test('max-width sidebars leave status and toast overlays inside the graph center', async ({ page }) => {
  // 1920 宽度 + 双栏 max-width（左 420 + 右 600）下中心列仍可操作；
  // 1366 + max-width 的中心列过窄本就不可操作，不作为验收目标。
  await page.setViewportSize({ width: 1920, height: 1080 })
  await createProject(page, 'E2E Overlay Center Constraint')
  await buildThreeNodeLineage(page)
  await fitGraph(page)

  // Grow both sidebars to their plan max-widths (left 420 + right 600).
  await page.evaluate(() => {
    localStorage.setItem('spec-agent.workspace-ui.v1', JSON.stringify({
      version: 1,
      leftSidebar: { open: true, width: 420 },
      rightSidebar: { open: true, width: 600 },
    }))
  })
  await page.reload()
  await expect(page.getByTestId('graph-canvas')).toBeVisible()
  await fitGraph(page)

  const left = page.getByTestId('left-sidebar')
  const right = page.getByTestId('right-sidebar')
  const [l, r] = await Promise.all([left.boundingBox(), right.boundingBox()])
  expect(l).not.toBeNull()
  expect(r).not.toBeNull()
  const centerLeft = (l?.x ?? 0) + (l?.width ?? 0)
  const centerRight = r?.x ?? 0
  expect(centerRight).toBeGreaterThan(centerLeft)

  // Trigger a real feedback toast through the answer flow, then assert the
  // toast layer stays strictly inside the Graph center region.
  // Max-width sidebars squeeze the canvas: fit again so the current node
  // is centered in the viewport before interacting with it (the canvas
  // pans via transform, so DOM scrollIntoView cannot bring it on screen).
  await fitGraph(page)
  const current = page.locator('.graph-question-node--current')
  await current.getByTestId('free-text').fill('overlay constraint probe')
  await current.getByTestId('submit-answer').click()
  const feedback = page.getByTestId('feedback')
  await expect(feedback).toBeVisible()
  const toastLayer = page.locator('.workspace-shell__toast-layer')
  const toastBox = await toastLayer.boundingBox()
  expect(toastBox).not.toBeNull()
  expect(toastBox?.x ?? 0).toBeGreaterThanOrEqual(centerLeft - 1)
  expect((toastBox?.x ?? 0) + (toastBox?.width ?? 0)).toBeLessThanOrEqual(centerRight + 1)

  // The project title pill obeys the same center constraint.
  const header = page.getByTestId('workspace-project-badge')
  const headerBox = await header.boundingBox()
  expect(headerBox).not.toBeNull()
  expect(headerBox?.x ?? 0).toBeGreaterThanOrEqual(centerLeft - 1)
  expect((headerBox?.x ?? 0) + (headerBox?.width ?? 0)).toBeLessThanOrEqual(centerRight + 1)
})

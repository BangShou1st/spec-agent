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

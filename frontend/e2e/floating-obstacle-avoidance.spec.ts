import { test, expect, type Locator } from '@playwright/test'
import { createProject } from './helpers'

/**
 * Floating-layout obstacle invariant: after any automatic layout, a floating
 * window must not cover the primary interactive graph obstacles — the current
 * graph node, the start placeholder's interactive content, and the toolbar.
 * The start placeholder role marks its interactive content column, not the
 * stretched full-canvas container.
 */

async function boxesOverlapWithPadding(
  first: Locator,
  second: Locator,
  padding = 0,
): Promise<boolean> {
  const [a, b] = await Promise.all([first.boundingBox(), second.boundingBox()])
  if (!a || !b) return false
  return a.x - padding < b.x + b.width
    && b.x - padding < a.x + a.width
    && a.y - padding < b.y + b.height
    && b.y - padding < a.y + a.height
}

async function expectClearOf(window: Locator, obstacle: Locator): Promise<void> {
  await expect.poll(() => boxesOverlapWithPadding(window, obstacle)).toBe(false)
}

test('empty project keeps both floating windows off the start placeholder CTA', async ({ page }) => {
  await createProject(page, 'E2E Obstacle Start Placeholder')

  const routes = page.getByTestId('floating-window-routes')
  const inspector = page.getByTestId('floating-window-inspector')
  const placeholder = page.locator('[data-layout-role="start-placeholder"]')
  await expect(routes).toBeVisible()
  await expect(inspector).toBeVisible()
  await expect(placeholder).toBeVisible()
  await expectClearOf(routes, placeholder)
  await expectClearOf(inspector, placeholder)

  // The core CTA must be reachable by a real click, not merely visible.
  await page.getByTestId('draft-question').click()
  await expect(page.getByTestId('graph-start-placeholder')).toHaveCount(0)
})

test('floating windows never cover the graph toolbar', async ({ page }) => {
  await createProject(page, 'E2E Obstacle Toolbar')

  const toolbar = page.getByTestId('graph-toolbar')
  for (const name of ['routes', 'inspector']) {
    const window = page.getByTestId(`floating-window-${name}`)
    await expect(window).toBeVisible()
    await expectClearOf(window, toolbar)
  }
})

test('inspector never covers the current interactive node', async ({ page }) => {
  await createProject(page, 'E2E Obstacle Current Node')

  await page.getByTestId('draft-question').click()
  const current = page.locator('.graph-question-node--current')
  await expect(current).toBeVisible()
  await expectClearOf(page.getByTestId('floating-window-inspector'), current)

  // Pointer-level proof: the node's input receives the click, not an overlay.
  const nodeInput = current.getByTestId('free-text')
  await nodeInput.click()
  await expect(nodeInput).toBeFocused()
})

test('small viewport with sufficient space still keeps windows off obstacles', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 700 })
  await createProject(page, 'E2E Obstacle Small Viewport')

  const routes = page.getByTestId('floating-window-routes')
  const inspector = page.getByTestId('floating-window-inspector')
  const placeholder = page.locator('[data-layout-role="start-placeholder"]')
  await expect(routes).toBeVisible()
  await expect(inspector).toBeVisible()
  await expect(placeholder).toBeVisible()
  await expectClearOf(routes, placeholder)
  await expectClearOf(inspector, placeholder)
  await expectClearOf(routes, page.getByTestId('graph-toolbar'))
  await expectClearOf(inspector, page.getByTestId('graph-toolbar'))

  await page.getByTestId('draft-question').click()
  await expectClearOf(inspector, page.locator('.graph-question-node--current'))
})

test('impossible-fit viewport degrades deterministically without NaN or drift', async ({ page }) => {
  await page.setViewportSize({ width: 480, height: 360 })
  await createProject(page, 'E2E Obstacle Tiny Viewport')

  const routes = page.getByTestId('floating-window-routes')
  const inspector = page.getByTestId('floating-window-inspector')
  await expect(routes).toBeVisible()
  await expect(inspector).toBeVisible()

  // Windows stay inside the viewport with finite geometry.
  for (const window of [routes, inspector]) {
    await expect.poll(async () => {
      const box = await window.boundingBox()
      if (!box) return false
      return box.x >= 0 && box.y >= 0
        && box.x + box.width <= 480 && box.y + box.height <= 360
        && [box.x, box.y, box.width, box.height].every(Number.isFinite)
    }).toBe(true)
  }

  // No infinite repositioning: geometry settles and stays put.
  const before = await Promise.all([routes.boundingBox(), inspector.boundingBox()])
  await page.waitForTimeout(800)
  const after = await Promise.all([routes.boundingBox(), inspector.boundingBox()])
  for (const [first, second] of [
    [before[0], after[0]],
    [before[1], after[1]],
  ]) {
    expect(first).not.toBeNull()
    expect(second).not.toBeNull()
    expect(Math.abs((first?.x ?? 0) - (second?.x ?? 0))).toBeLessThan(1)
    expect(Math.abs((first?.y ?? 0) - (second?.y ?? 0))).toBeLessThan(1)
  }

  const stored = await page.evaluate(() => localStorage.getItem('spec-agent.workspace-ui.v2'))
  expect(stored).not.toBeNull()
  expect(JSON.parse(stored ?? '{}').windows.inspector.positionMode).toBe('auto')
})

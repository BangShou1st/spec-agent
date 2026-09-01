import { test, expect } from '@playwright/test'
import { buildThreeNodeLineage, closeFloatingWorkspaceWindows, createProject, fitGraph } from './helpers'

/**
 * P1-3 E2E: the action rail lives on the RIGHT side of the node, and
 * the hover-to-button gap must not collapse the rail mid-flight. The
 * toolbar buttons remain clickable through real mouse input. Selection
 * and keyboard focus keep the rail visible too.
 */
test('action rail: hover node, cross gap to button, click without flicker', async ({ page }) => {
  await createProject(page, 'E2E Action Rail Hover Bridge')
  await buildThreeNodeLineage(page)
  await fitGraph(page)
  await closeFloatingWorkspaceWindows(page)

  // Target a historical (non-current) node — those expose the action rail.
  const historical = page.locator('[data-test="graph-question-node"]').nth(1)
  await expect(historical).toBeVisible()
  const rail = historical.locator('.graph-node-actions--toolbar')
  await expect(rail).toHaveCount(1)
  // Default state: hidden (no opacity/visibility for user), rail container is
  // still in the DOM so the bridge never tears down mid-flight.
  await expect(rail).toBeAttached()

  // The rail container is keyboard-focusable: tabindex=0, role=toolbar.
  await expect(rail).toHaveAttribute('role', 'toolbar')
  await expect(rail).toHaveAttribute('tabindex', '0')
  await expect(rail).toHaveAttribute('aria-label', '节点操作')

  // Hover the node so the rail becomes interactive.
  await historical.hover()
  const fork = historical.getByTestId('fork-node')
  await expect(fork).toBeVisible()
  // Cross the 10px bridge: move the pointer from the node edge into the
  // button without leaving the rail container. In Playwright we move
  // through the button box center; the rail MUST stay visible.
  const box = await fork.boundingBox()
  expect(box).not.toBeNull()
  await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2)
  await expect(fork).toBeVisible()
  // Clicking must still fire the fork dialog.
  await fork.click()
  await expect(page.getByTestId('fork-dialog')).toBeVisible()
})

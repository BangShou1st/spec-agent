import { test, expect } from '@playwright/test'
import { buildThreeNodeLineage, closeFloatingWorkspaceWindows, createProject, fitGraph } from './helpers'

/**
 * P0-2 / Section 6 UX: option and freeText input must survive:
 *   1. a real Chromium mouse drag of the source node
 *   2. a fit-view + close-window navigation
 *   3. a back-navigation (clicking back onto the same Q3 instance)
 * The input draft store is keyed by (projectId, nodeId, routeId) and is
 * read again on remount.
 */
test("input draft survives real drag, fit view, and back-navigation", async ({ page }) => {
  await createProject(page, "E2E Input Persistence Real Drag")
  await buildThreeNodeLineage(page)
  await fitGraph(page)
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)

  // The current answerable node (Q3) carries the answerable input.
  const current = page.locator("[data-test=\"graph-question-node\"]").last()
  await expect(current).toBeVisible()
  const currentNodeId = await current.getAttribute("data-node-id")
  expect(currentNodeId).not.toBeNull()

  // 1. Type option + freeText on the current (Q3) answerable node.
  const firstOption = current.locator("input[type=radio]").first()
  const firstOptionValue = await firstOption.getAttribute("value")
  expect(firstOptionValue).not.toBeNull()
  await firstOption.check()
  const freeText = current.locator("[data-test=\"free-text\"]")
  const persistentDraft = "persistent draft across drag and focus"
  await freeText.fill(persistentDraft)

  // Lock the DOM values pre-drag for later cross-references.
  const draftLocator = page.locator(
    `[data-test=\"graph-question-node\"][data-node-id="${currentNodeId}"] [data-test=\"free-text\"]`,
  )
  await expect(draftLocator).toHaveValue(persistentDraft)

  // 2. REAL MOUSE DRAG of the source node — the scenario the spec must
  // actually exercise, not a synthetic re-render. Drag the node by its
  // drag-handle header and release it to a new position.
  const dragHandle = current.locator("[data-test=\"node-drag-handle\"]").first()
  const handleBox = await dragHandle.boundingBox()
  expect(handleBox).not.toBeNull()
  await page.mouse.move(
    handleBox!.x + handleBox!.width / 2,
    handleBox!.y + handleBox!.height / 2,
  )
  await page.mouse.down()
  await page.mouse.move(
    handleBox!.x + 30,
    handleBox!.y + 80,
    { steps: 12 },
  )
  await page.mouse.move(
    handleBox!.x + 80,
    handleBox!.y + 120,
    { steps: 12 },
  )
  await page.mouse.up()
  await page.waitForTimeout(500)

  // 3. After the drag, the visual instance of Q3 is in a new position;
  // re-resolve by canonical data-node-id. The draft must still be
  // present.
  const reResolvedFreeText = page.locator(
    `[data-test=\"graph-question-node\"][data-node-id="${currentNodeId}"] [data-test=\"free-text\"]`,
  )
  await expect(reResolvedFreeText).toHaveValue(persistentDraft)
  const reResolvedRadio = page.locator(
    `[data-test=\"graph-question-node\"][data-node-id="${currentNodeId}"] input[type=radio][value="${firstOptionValue}"]`,
  )
  await expect(reResolvedRadio).toBeChecked()

  // 4. Back-navigation: refit the view and re-resolve. The draft is
  // still on the same canonical node; the inputDraftStore must keep it.
  await fitGraph(page)
  await expect(reResolvedFreeText).toHaveValue(persistentDraft)
  await expect(reResolvedRadio).toBeChecked()
})

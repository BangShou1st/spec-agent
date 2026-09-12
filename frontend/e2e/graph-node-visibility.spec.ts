import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { expect, test, type Locator, type Page } from '@playwright/test'

/**
 * BUG-02 regression: Vue Flow must never leave a projected graph node
 * permanently `visibility: hidden`.
 *
 * The failure this locks down is a *measurement lifecycle* failure, not a
 * "node missing from the DOM" failure: the `.vue-flow__node` element keeps a
 * real box (offsetWidth/Height stay non-zero) while Vue Flow's internal
 * `node.dimensions` is zeroed, so `NodeWrapper` renders `visibility: hidden`
 * forever — no refresh, Fit View, zoom or selection change brings it back.
 *
 * "Exists in the DOM" is therefore NOT an acceptable assertion here. Every
 * check below asserts real visibility AND a real, interactive box.
 *
 * The backend read model is stubbed (see fixtures/graph-node-visibility.json)
 * because draftQuestion currently fails with INTERNAL_ERROR (BUG-03); without
 * the stub every graph regression would fail for that unrelated reason. The
 * projection, GraphCanvas and Vue Flow integration under test are the real
 * ones.
 */
const GRAPH_FIXTURE = JSON.parse(
  readFileSync(
    fileURLToPath(new URL('./fixtures/graph-node-visibility.json', import.meta.url)),
    'utf8',
  ),
)

const EXPECTED_NODE_COUNT = 3

/** Stubs the graph read model for the given project id. */
async function stubGraphReadModel(page: Page, projectId: string): Promise<void> {
  await page.route('**/api/v1/projects/*/graph', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...GRAPH_FIXTURE, projectId }),
    })
  })
}

async function openStubbedProject(page: Page, title: string): Promise<string> {
  await page.goto('/projects')
  await page.getByLabel('Project title').fill(title)
  await page.getByRole('button', { name: '创建项目' }).click()
  await page.waitForURL(/\/projects\/[0-9a-f-]+/)
  const projectId = page.url().replace(/.*\/projects\//, '')
  await stubGraphReadModel(page, projectId)
  await page.goto(`/projects/${projectId}`)
  await expect(page.getByTestId('graph-canvas')).toBeVisible()
  await expect(page.locator('.vue-flow__node')).toHaveCount(EXPECTED_NODE_COUNT)
  return projectId
}

/**
 * Asserts every rendered node is genuinely visible with a real, interactive
 * box. Fails with the offending node's id + measured box so the failure names
 * the real symptom instead of a generic timeout.
 */
async function expectAllNodesMeasuredAndVisible(page: Page, context: string): Promise<void> {
  const nodes = page.locator('.vue-flow__node')
  const count = await nodes.count()
  expect(count, `${context}: node count`).toBeGreaterThan(0)

  for (let index = 0; index < count; index += 1) {
    const node: Locator = nodes.nth(index)
    const id = (await node.getAttribute('data-id')) ?? `#${index}`

    // 1. Playwright's own visibility (not-zero box + not visibility:hidden).
    await expect(node, `${context}: node ${id} must be visible`).toBeVisible()

    // 2. A real, non-degenerate box.
    const box = await node.boundingBox()
    expect(box, `${context}: node ${id} must have a bounding box`).not.toBeNull()
    expect(box!.width, `${context}: node ${id} width`).toBeGreaterThan(0)
    expect(box!.height, `${context}: node ${id} height`).toBeGreaterThan(0)

    // 3. The exact failure mode of BUG-02: Vue Flow's own computed visibility
    //    on the node wrapper, plus a real measured layout box.
    const measured = await node.evaluate((el) => ({
      visibility: getComputedStyle(el).visibility,
      offsetWidth: (el as HTMLElement).offsetWidth,
      offsetHeight: (el as HTMLElement).offsetHeight,
    }))
    expect(measured.visibility, `${context}: node ${id} computed visibility`).toBe('visible')
    expect(measured.offsetWidth, `${context}: node ${id} offsetWidth`).toBeGreaterThan(0)
    expect(measured.offsetHeight, `${context}: node ${id} offsetHeight`).toBeGreaterThan(0)
  }
}

test('projected nodes stay visible and measured through selection and viewport interaction', async ({ page }) => {
  await openStubbedProject(page, 'BUG02 Node Visibility')

  // Baseline: every node is measured and visible right after projection.
  await expectAllNodesMeasuredAndVisible(page, 'baseline')

  // A single selection must not drop measurement.
  await page.locator('.vue-flow__node').first().click({ position: { x: 12, y: 12 }, force: true })
  await page.waitForTimeout(400)
  await expectAllNodesMeasuredAndVisible(page, 'after single click')

  // Rapid selection churn: the audit's reproduction of the lifecycle race.
  const nodes = page.locator('.vue-flow__node')
  const count = await nodes.count()
  for (let i = 0; i < 30; i += 1) {
    await nodes.nth(i % count).click({ position: { x: 12, y: 12 }, force: true, timeout: 5000 })
  }
  await page.waitForTimeout(600)
  await expectAllNodesMeasuredAndVisible(page, 'after 30 rapid clicks')

  // Ctrl multi-select must not drop measurement either.
  for (let i = 0; i < count; i += 1) {
    await nodes.nth(i).click({ position: { x: 12, y: 12 }, modifiers: ['Control'], force: true, timeout: 5000 })
  }
  await page.waitForTimeout(400)
  await expectAllNodesMeasuredAndVisible(page, 'after ctrl multi-select')

  // Viewport operations must not be needed to "recover" a node, and must not
  // break measurement either.
  await page.getByTestId('zoom-in').click()
  await page.getByTestId('zoom-out').click()
  await page.waitForTimeout(400)
  await expectAllNodesMeasuredAndVisible(page, 'after zoom')

  await page.getByTestId('fit-view').click()
  await page.waitForTimeout(900)
  await expectAllNodesMeasuredAndVisible(page, 'after fit view')

  // A fresh projection (canonical refresh) must keep the measured state.
  await page.reload()
  await expect(page.locator('.vue-flow__node')).toHaveCount(EXPECTED_NODE_COUNT)
  await page.waitForTimeout(600)
  await expectAllNodesMeasuredAndVisible(page, 'after reload')
})

test('floating idea is immediately visible, editable and stays visible after save', async ({ page }) => {
  // No stub here: creating an idea never calls the model, so this exercises
  // the real end-to-end path.
  await page.goto('/projects')
  await page.getByLabel('Project title').fill('BUG02 Floating Idea')
  await page.getByRole('button', { name: '创建项目' }).click()
  await page.waitForURL(/\/projects\/[0-9a-f-]+/)
  await expect(page.getByTestId('graph-start-placeholder')).toBeVisible()

  await page.getByTestId('graph-start-placeholder').getByTestId('add-idea').click()

  const draft = page.getByTestId('graph-knowledge-node')
  await expect(draft).toBeVisible()

  const draftBox = await draft.boundingBox()
  expect(draftBox, 'draft idea must have a bounding box').not.toBeNull()
  expect(draftBox!.width).toBeGreaterThan(0)
  expect(draftBox!.height).toBeGreaterThan(0)

  const wrapperVisibility = await draft.evaluate(
    (el) => getComputedStyle(el.closest('.vue-flow__node') ?? el).visibility,
  )
  expect(wrapperVisibility, 'draft idea wrapper computed visibility').toBe('visible')

  // It enters edit mode by itself — no extra click, no refresh, no Fit View.
  const input = draft.getByTestId('draft-text')
  await expect(input).toBeVisible()
  await input.fill('A requirement captured directly in the graph.')
  await draft.getByTestId('save-draft').click()
  await expect(draft.getByTestId('knowledge-text')).toContainText(
    'A requirement captured directly in the graph.',
  )

  // Still visible and measured after save (a canonical refresh just happened).
  await expectAllNodesMeasuredAndVisible(page, 'after saving idea')

  // Two more ideas, each immediately usable.
  await page.getByTestId('graph-toolbar').getByTestId('add-idea').click()
  await page.waitForTimeout(500)
  await expectAllNodesMeasuredAndVisible(page, 'after 2nd idea')
  await page.getByTestId('graph-toolbar').getByTestId('add-idea').click()
  await page.waitForTimeout(500)
  await expectAllNodesMeasuredAndVisible(page, 'after 3rd idea')

  await page.getByTestId('fit-view').click()
  await page.waitForTimeout(900)
  await expectAllNodesMeasuredAndVisible(page, 'after fit view with ideas')
})

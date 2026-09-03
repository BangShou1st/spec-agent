import { expect, type Page } from '@playwright/test'

/**
 * Shared E2E helpers for the graph-first workspace. Every flow proves
 * browser-visible behavior against the real local backend with the fake
 * model gateway; nothing here inspects the database directly.
 */

export const FAKE_ROOT_QUESTION = 'What is the most important outcome?'

/** Creates a project through the UI and lands in its graph workspace. */
export async function createProject(page: Page, title: string): Promise<void> {
  await page.goto('/projects')
  await page.getByLabel('Project title').fill(title)
  await page.getByRole('button', { name: '创建项目' }).click()
  await page.waitForURL(/\/projects\/[0-9a-f-]+/)
  await expect(page.getByTestId('graph-canvas')).toBeVisible()
}

/** Fits the whole graph into the viewport (viewport-only; never moves nodes). */
export async function fitGraph(page: Page): Promise<void> {
  const canvas = page.getByTestId('graph-canvas')
  // True settlement: data-viewport-settled only advances after the
  // requested setViewport Promise resolves (the 300ms transition has
  // completed), not at request start. Capture before click, then wait
  // for a genuine advance.
  const before = await canvas.getAttribute('data-viewport-settled')
  await page.getByTestId('fit-view').click()
  await expect(page.locator('.vue-flow__node').first()).toBeVisible()
  if (before !== null) {
    await expect.poll(async () => canvas.getAttribute('data-viewport-settled')).not.toBe(before)
  } else {
    await expect.poll(async () => canvas.getAttribute('data-viewport-settled')).not.toBeNull()
  }
  // The settled write already implies the viewport transition completed.
  // Keep a DOM flush via double RAF for floating-window reflow visibility,
  // but it is AFTER real settlement — never a substitute for it.
  await page.waitForFunction(() => new Promise<void>((resolve) => {
    requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
  }))
}

/** Closes product overlays before a test performs direct canvas pointer input. */
export async function closeFloatingWorkspaceWindows(page: Page): Promise<void> {
  // Close the topmost window first; the inspector can temporarily cover the
  // route window while the responsive layout settles.
  for (const testId of ['floating-window-inspector', 'floating-window-routes']) {
    const window = page.getByTestId(testId)
    if (await window.isVisible()) {
      await window.getByTestId('floating-window-close').click()
    }
  }
}

/** Drafts the first question (explicit user action). */
export async function draftFirstQuestion(page: Page): Promise<void> {
  await page.getByTestId('draft-question').click()
  try {
    await expect(page.getByTestId('question')).toBeVisible({ timeout: 180000 })
    return
  } catch {
    const retry = page.getByRole('button', { name: '重新请求' })
    if (await retry.isVisible()) {
      await retry.click()
      await expect(page.getByTestId('question')).toBeVisible({ timeout: 180000 })
      return
    }
    throw new Error('draftFirstQuestion: question not visible and no retry affordance')
  }
}

/** Answers the active node with free text and waits for the recorded answer. */
export async function answerActiveNode(page: Page, text: string): Promise<void> {
  await page.getByTestId('free-text').fill(text)
  await page.getByTestId('submit-answer').click()
  await expect(page.getByText('回答已记录。')).toBeVisible()
  await expect(page.getByTestId('free-text')).toHaveValue('')
}

/** Builds a 3-node lineage: root, answered child, grandchild (1 route). */
export async function buildThreeNodeLineage(page: Page): Promise<void> {
  // Floating windows intentionally overlay the full canvas. Close the
  // inspector while answering the current node, then restore the product
  // defaults for the graph-native assertions that follow.
  const inspector = page.getByTestId('floating-window-inspector')
  if (await inspector.isVisible()) {
    await inspector.getByTestId('floating-window-close').click()
  }
  await draftFirstQuestion(page)
  await answerActiveNode(page, 'First answer content')
  await answerActiveNode(page, 'Second answer content')
  await expect(page.locator('.graph-question-node')).toHaveCount(3)
  // The graph owns the full canvas while sidebars float above it. Re-fit after
  // building the fixture so every historical node is in the unobscured
  // reading corridor before a test begins a graph-native interaction.
  await fitGraph(page)
  await page.getByTestId('open-inspector').click()
  await expect(page.getByTestId('floating-window-inspector')).toBeVisible()
  await expect.poll(async () => {
    const routesBox = await page.getByTestId('floating-window-routes').boundingBox()
    const inspectorBox = await page.getByTestId('floating-window-inspector').boundingBox()
    if (!routesBox || !inspectorBox) return false
    return routesBox.x + routesBox.width <= inspectorBox.x
      || inspectorBox.x + inspectorBox.width <= routesBox.x
      || routesBox.y + routesBox.height <= inspectorBox.y
      || inspectorBox.y + inspectorBox.height <= routesBox.y
  }).toBe(true)
}

/**
 * Forks from the given graph node index through the current visual reading
 * context. The operation dialog never asks the user to pick a route.
 */
export async function forkFromNode(page: Page, index: number, label: string): Promise<void> {
  const inspector = page.getByTestId('floating-window-inspector')
  if (await inspector.isVisible()) {
    await inspector.getByTestId('floating-window-close').click()
  }
  const node = page.locator('[data-test="graph-question-node"]').nth(index)
  await node.hover()
  await expect(node.getByTestId('fork-node')).toBeVisible()
  await node.getByTestId('fork-node').click()
  await expect(page.getByTestId('fork-dialog')).toBeVisible()
  if (label) {
    await page.getByTestId('fork-label').fill(label)
  }
  await page.getByTestId('fork-submit').click()
  await expect(page.getByTestId('fork-dialog')).toHaveCount(0)
}

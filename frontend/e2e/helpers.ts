import {
  expect,
  test as base,
  type APIRequestContext,
  type Locator,
  type Page,
} from '@playwright/test'

/**
 * Shared E2E helpers for the graph-first workspace. Every flow proves
 * browser-visible behavior against the real local backend with the fake
 * model gateway; nothing here inspects the database directly.
 *
 * Cleanup contract: every project created through {@link createProject}
 * is registered for deletion when the test finishes, so reruns on a dirty
 * environment only ever touch data this run created. Specs must import
 * `test` from this module (the extended fixture owns the teardown).
 */

export const test = base.extend<{ e2eProjectCleanup: void }>({
  e2eProjectCleanup: [async ({ request }, use) => {
    await use()
    for (const projectId of createdProjectIds.splice(0)) {
      await deleteProjectBestEffort(request, projectId)
    }
  }, { auto: true }],
})

export { expect }
export type { APIRequestContext, Locator, Page }

export const FAKE_ROOT_QUESTION = 'What is the most important outcome?'

/** Project ids created during the current test; drained by the auto fixture. */
const createdProjectIds: string[] = []

/** Deletes a project this test created; retries briefly if runs are settling. */
export async function deleteProjectBestEffort(
  request: APIRequestContext,
  projectId: string,
): Promise<void> {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const response = await request.delete(`/api/v1/projects/${projectId}`)
    if (response.status() === 204 || response.status() === 404) {
      return
    }
    // 409: 项目仍有未终态 run(fake 网关下应很快终态化),稍候重试。
    await new Promise((resolve) => setTimeout(resolve, 2000))
  }
}

/** Creates a project through the UI and lands in its graph workspace.
 * The title is made unique per call so reruns never collide with leftover
 * same-name projects from an interrupted earlier run. Returns the project id
 * (also registered for automatic cleanup). */
export async function createProject(page: Page, title: string): Promise<string> {
  const uniqueTitle = `${title} ${Date.now().toString(36)}`
  await page.goto('/projects')
  await page.getByLabel('Project title').fill(uniqueTitle)
  await page.getByRole('button', { name: '创建项目' }).click()
  await page.waitForURL(/\/projects\/[0-9a-f-]+/)
  await expect(page.getByTestId('graph-canvas')).toBeVisible()
  const match = page.url().match(/\/projects\/([0-9a-f-]+)/)
  const projectId = match ? match[1] : ''
  if (projectId) {
    createdProjectIds.push(projectId)
  }
  return projectId
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
  // Keep a DOM flush via double RAF for post-settlement visibility,
  // but it is AFTER real settlement — never a substitute for it.
  await page.waitForFunction(() => new Promise<void>((resolve) => {
    requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
  }))
}

/** Fixed layout reserves sidebar space: no floating windows to close. Kept
 * as a no-op so existing flows keep reading without product overlays. */
export async function closeFloatingWorkspaceWindows(_page: Page): Promise<void> {
}

/** Opens a native <details> overflow by clicking its summary (never the
 * details box itself: padding areas do not toggle). No-op when already open. */
export async function openOverflow(details: Locator): Promise<void> {
  if (await details.getAttribute('open') === null) {
    await details.locator('summary').click()
  }
}

/** Opens the per-route overflow menu for one route card. */
export async function openRouteMore(card: Locator): Promise<void> {
  await openOverflow(card.getByTestId('route-more'))
}

/** Opens the shared lifecycle-filter overflow in the route sidebar. */
export async function openRouteFilters(page: Page): Promise<void> {
  await openOverflow(page.getByTestId('route-filters'))
}

/** Opens the graph toolbar overflow menu. */
export async function openToolbarMore(page: Page): Promise<void> {
  await openOverflow(page.getByTestId('toolbar-more'))
}

/** Hovers a graph node on its right-upper area: the canvas toolbar overlays
 * the left canvas edge (a center hover can be intercepted there), and the
 * fitted zoom can shrink nodes well below their CSS size (a fixed offset can
 * land outside the card). The point is derived from the live bounding box. */
export async function hoverNode(node: Locator): Promise<void> {
  const box = await node.boundingBox()
  if (!box || box.width < 10 || box.height < 10) {
    await node.hover()
    return
  }
  await node.hover({
    position: {
      x: Math.round(box.width * 0.7),
      y: Math.round(Math.max(8, Math.min(40, box.height * 0.3))),
    },
  })
}

/** Clicks a blank canvas point (clears selection via pane-click). The fixed
 * left sidebar occupies the viewport's left ~280px, so raw coordinates from
 * the old floating layout no longer hit the canvas. */
export async function clickCanvasBlank(page: Page): Promise<void> {
  const canvas = page.getByTestId('graph-canvas')
  const box = await canvas.boundingBox()
  if (!box) {
    await canvas.click({ position: { x: 100, y: 100 } })
    return
  }
  const nodes = page.locator('[data-test="graph-question-node"], [data-test="graph-knowledge-node"]')
  const count = await nodes.count()
  const occupied: Array<{ x: number; y: number; width: number; height: number }> = []
  for (let i = 0; i < count; i += 1) {
    const b = await nodes.nth(i).boundingBox()
    if (b) occupied.push(b)
  }
  const candidates = [
    { x: Math.round(box.width - 60), y: Math.round(box.height - 60) },
    { x: Math.round(box.width - 60), y: 60 },
    { x: Math.round(box.width / 2), y: Math.round(box.height - 60) },
  ]
  for (const point of candidates) {
    const px = box.x + point.x
    const py = box.y + point.y
    const hitsNode = occupied.some((b) =>
      px >= b.x && px <= b.x + b.width && py >= b.y && py <= b.y + b.height,
    )
    if (!hitsNode) {
      await page.mouse.click(px, py)
      return
    }
  }
  await canvas.click({ position: candidates[0] })
}

/** Drafts the first question (explicit user action). */
export async function draftFirstQuestion(page: Page): Promise<void> {
  await page.getByTestId('draft-question').click()
  try {
    await expect(page.getByTestId('question')).toBeVisible({ timeout: 180000 })
    return
  } catch {
    // 起草失败时的兜底:旧的全局"重新请求"入口已删除(2026-09-27)。
    // 错误条的通用出口是"刷新状态"(重载 canonical);若刷新后草稿仍未
    // 落地,则以正常业务动作再起草一次。
    const refresh = page.getByRole('button', { name: '刷新状态' })
    if (await refresh.isVisible().catch(() => false)) {
      await refresh.click()
      await expect(page.getByTestId('graph-canvas')).toBeVisible({ timeout: 60_000 })
    }
    if (await page.getByTestId('question').isVisible().catch(() => false)) {
      return
    }
    const draft = page.getByTestId('draft-question')
    if (await draft.isVisible().catch(() => false)) {
      await draft.click()
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
  await expect(page.getByText('回答已记录')).toBeVisible()
  await expect(page.getByTestId('free-text')).toHaveValue('')
}

/** Builds a 3-node lineage: root, answered child, grandchild (1 route). */
export async function buildThreeNodeLineage(page: Page): Promise<void> {
  await draftFirstQuestion(page)
  await answerActiveNode(page, 'First answer content')
  await answerActiveNode(page, 'Second answer content')
  await expect(page.locator('.graph-question-node')).toHaveCount(3)
  // Fixed sidebars reserve real layout width. Re-fit after building the
  // fixture so every historical node is in view before a test begins a
  // graph-native interaction.
  await fitGraph(page)
  await expect(page.getByTestId('left-sidebar')).toBeVisible()
  await expect(page.getByTestId('right-sidebar')).toBeVisible()
}

/**
 * Forks from the given graph node index through the current visual reading
 * context. The operation dialog never asks the user to pick a route.
 */
export async function forkFromNode(page: Page, index: number, label: string): Promise<void> {
  const node = page.locator('[data-test="graph-question-node"]').nth(index)
  await hoverNode(node)
  await expect(node.getByTestId('fork-node')).toBeVisible()
  await node.getByTestId('fork-node').click()
  await expect(page.getByTestId('fork-dialog')).toBeVisible()
  if (label) {
    await page.getByTestId('fork-label').fill(label)
  }
  await page.getByTestId('fork-submit').click()
  await expect(page.getByTestId('fork-dialog')).toHaveCount(0)
}

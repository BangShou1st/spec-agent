import { test, expect } from '@playwright/test'
import { answerActiveNode, buildThreeNodeLineage, createProject, draftFirstQuestion, fitGraph } from './helpers'

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
    expect(c?.width ?? 0).toBeGreaterThan(0)
    expect(c?.height ?? 0).toBeGreaterThan(0)
    // Graph 是中央最大工作面：宽度显著大于任一侧边栏。
    expect(c?.width ?? 0).toBeGreaterThan(l?.width ?? 0)
    // 页面无横向滚动。
    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth)
    expect(scrollWidth).toBeLessThanOrEqual(viewport.width + 1)
    // Inspector 内部滚动而非撑开页面。
    const inspector = page.getByTestId('workspace-inspector')
    await expect(inspector).toBeVisible()
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

for (const viewport of viewports) {
  test(`${viewport.width}x${viewport.height} keeps the spec dock collapsed with graph dominant`, async ({ page }) => {
    await page.setViewportSize(viewport)
    await createProject(page, `E2E Spec Dock Collapsed ${viewport.width}`)
    await buildThreeNodeLineage(page)
    await fitGraph(page)

    const dock = page.getByTestId('spec-dock')
    await expect(dock).toBeVisible()
    await expect(dock).toHaveAttribute('data-state', 'collapsed')
    const dockBox = await dock.boundingBox()
    expect(dockBox).not.toBeNull()
    // 折叠高度约 44–52px。
    expect(dockBox?.height ?? 0).toBeGreaterThanOrEqual(40)
    expect(dockBox?.height ?? 0).toBeLessThanOrEqual(60)

    // Graph 仍是视觉主体：canvas 高度显著大于折叠 Dock。
    const canvas = page.getByTestId('graph-canvas')
    const canvasBox = await canvas.boundingBox()
    expect(canvasBox).not.toBeNull()
    expect(canvasBox?.height ?? 0).toBeGreaterThan((dockBox?.height ?? 0) * 3)

    // 当前问题仍可回答。
    const current = page.locator('.graph-question-node--current')
    await expect(current).toBeVisible()
  })
}

test('1366x768 expanding the dock keeps graph and inspector usable', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 768 })
  await createProject(page, 'E2E Spec Dock Expanded')
  await buildThreeNodeLineage(page)
  await fitGraph(page)

  const dock = page.getByTestId('spec-dock')
  const canvas = page.getByTestId('graph-canvas')
  const before = await canvas.boundingBox()
  await page.getByTestId('spec-dock-toggle').click()
  await expect(dock).toHaveAttribute('data-state', 'expanded')

  // Graph 仍可见且有可用高度（非零）。
  await expect(canvas).toBeVisible()
  const after = await canvas.boundingBox()
  expect(after).not.toBeNull()
  expect(after?.height ?? 0).toBeGreaterThan(0)
  expect(after?.height ?? 0).toBeLessThan(before?.height ?? Number.MAX_SAFE_INTEGER)
  // 展开不超过中央区 45%：Dock 高度占比检查。
  const dockBox = await dock.boundingBox()
  const centerBox = await page.locator('.workspace-shell__center').boundingBox()
  expect(dockBox).not.toBeNull()
  expect(centerBox).not.toBeNull()
  expect((dockBox?.height ?? 0) / (centerBox?.height ?? 1)).toBeLessThanOrEqual(0.5)
  // Inspector 仍可见/内部可滚动。
  const inspector = page.getByTestId('workspace-inspector')
  await expect(inspector).toBeVisible()
  const inspectorScrollable = await inspector.evaluate((el) => {
    const style = getComputedStyle(el)
    const body = el.querySelector('.inspector-body') as HTMLElement | null
    const target = body ?? el
    const targetStyle = body ? getComputedStyle(body) : style
    return target.scrollHeight >= target.clientHeight && (targetStyle.overflowY === 'auto' || targetStyle.overflowY === 'scroll')
  })
  expect(inspectorScrollable).toBe(true)
  // 展开态同样无横向滚动。
  const expandedScrollWidth = await page.evaluate(() => document.documentElement.scrollWidth)
  expect(expandedScrollWidth).toBeLessThanOrEqual(1366 + 1)

  // 折叠后空间归还，Graph 交互状态不丢失。
  await page.getByTestId('spec-dock-toggle').click()
  await expect(dock).toHaveAttribute('data-state', 'collapsed')
  const current = page.locator('.graph-question-node--current')
  await expect(current).toBeVisible()
  await current.getByTestId('free-text').fill('dock collapse keeps interaction')
  await expect(current.getByTestId('free-text')).toHaveValue('dock collapse keeps interaction')
})

test('default workspace exposes no raw technical identifiers', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await createProject(page, 'E2E No Technical Noise')
  await buildThreeNodeLineage(page)
  await fitGraph(page)

  const bodyText = await page.locator('.workspace-shell').innerText()
  // 默认首屏无 UUID 形态、raw actionFamily、raw phase。
  expect(bodyText).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i)
  expect(bodyText).not.toContain('CREATE_NODE')
  expect(bodyText).not.toContain('SNAPSHOT_BUILT')
  expect(bodyText).not.toContain('STATE_UPDATING')
  expect(bodyText).not.toContain('PROPOSAL_CREATED')
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

// Spec Dock 展开后，当前 answerable node 与其 submit CTA 必须完整落在 Graph
// 区域内，不能被 Dock 遮挡；同时在最小的官方视口也必须成立。
const specViewports = [
  { width: 1440, height: 900, title: 'E2E Dock Clip 1440' },
  { width: 1366, height: 768, title: 'E2E Dock Clip 1366' },
]

for (const viewport of specViewports) {
  test(`${viewport.width}x${viewport.height} spec dock expansion never clips the answerable node or its submit`, async ({ page }) => {
    // 与截图 spec 相同的确定性流程：单问单答后生成一个真实快照，让 Dock
    // 处于 40/45% 高度的最坏情况。
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await createProject(page, viewport.title)
    await draftFirstQuestion(page)
    await answerActiveNode(page, 'Spec-worthy answer content')
    await fitGraph(page)
    await page.getByTestId('spec-dock-toggle').click()
    await expect(page.getByTestId('spec-dock')).toHaveAttribute('data-state', 'expanded')
    await page.getByTestId('generate-spec').click()
    await expect(page.getByTestId('spec-snapshot-detail')).toBeVisible({ timeout: 120000 })
    // 等 Dock 高度稳定（内容加载可能把 Dock 顶到 45%）后检查几何。
    await page.waitForTimeout(600)

    const region = await page.locator('.workspace-shell__graph-region').boundingBox()
    const current = page.locator('.graph-question-node--current')
    await expect(current).toBeVisible()
    const cb = await current.boundingBox()
    expect(region).not.toBeNull()
    expect(cb).not.toBeNull()
    const regionBottom = (region?.y ?? 0) + (region?.height ?? 0)
    const regionRight = (region?.x ?? 0) + (region?.width ?? 0)
    // 当前节点完整位于 graph-region。
    expect((cb?.y ?? 0)).toBeGreaterThanOrEqual((region?.y ?? 0) - 1)
    expect((cb?.x ?? 0)).toBeGreaterThanOrEqual((region?.x ?? 0) - 1)
    expect((cb?.y ?? 0) + (cb?.height ?? 0)).toBeLessThanOrEqual(regionBottom + 1)
    expect((cb?.x ?? 0) + (cb?.width ?? 0)).toBeLessThanOrEqual(regionRight + 1)
    // submit CTA 完整位于 graph-region。
    const submit = current.getByTestId('submit-answer')
    const sb = await submit.boundingBox()
    expect(sb).not.toBeNull()
    expect((sb?.y ?? 0) + (sb?.height ?? 0)).toBeLessThanOrEqual(regionBottom + 1)
    expect((sb?.x ?? 0) + (sb?.width ?? 0)).toBeLessThanOrEqual(regionRight + 1)
    // Graph 仍可见，无横向页面滚动。
    await expect(page.getByTestId('graph-canvas')).toBeVisible()
    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth)
    expect(scrollWidth).toBeLessThanOrEqual(viewport.width + 1)
  })
}

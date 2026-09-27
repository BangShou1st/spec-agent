import path from 'node:path'
import { test, expect, createProject, draftFirstQuestion, fitGraph, openRouteMore } from './helpers'

// 第四轮 R4-B 的浏览器验收:顶部恢复入口是"只读汇总与定位"——单项只有
// "查看",多项展开后每项只有"定位";任何鼠标/键盘交互都不得发出 retry 或
// 生成请求。定位必须到达真实目标:起草失败定位下游失败占位卡(不是只选中
// 源节点),回答失败定位来源节点。真正的恢复动作仍留在对应失败位置(占位卡
// 与节点恢复栏的重试按钮),本用例同时验证它们仍然可用。零真实模型调用。
const SHOTS = path.join('test-results', 'top-recovery-locate-shots',
  new Date().toISOString().replace(/[:.]/g, '-'))

interface GraphAnswer { freeText?: string | null }

async function unresolvedFailures(page: import('@playwright/test').Page): Promise<Array<{
  runId: string
  sourceNodeId: string | null
  routeId: string | null
  availableAction: string
  operation: string
}>> {
  return page.evaluate(async () => {
    const projectId = location.pathname.split('/')[2]
    return fetch(`/api/v1/projects/${projectId}/agent-runs/unresolved`).then((r) => r.json())
  })
}

async function countAnswersWithText(page: import('@playwright/test').Page, text: string): Promise<number> {
  return page.evaluate(async (needle) => {
    const projectId = location.pathname.split('/')[2]
    const graph = await fetch(`/api/v1/projects/${projectId}/graph`).then((r) => r.json())
    return (graph.answers ?? []).filter(
      (a: { freeText?: string | null; inherited?: boolean }) =>
        !a.inherited && (a.freeText ?? '').includes(needle),
    ).length
  }, text)
}

/** 监听恢复/生成类 POST 请求:顶部任何交互都不允许发出。 */
function trackRecoveryRequests(page: import('@playwright/test').Page): string[] {
  const requests: string[] = []
  page.on('request', (request) => {
    if (request.method() !== 'POST') return
    const url = request.url()
    if (url.includes('/retry') || url.includes('/agent-runs') || url.includes('/generate-spec')) {
      requests.push(url)
    }
  })
  return requests
}

/**
 * 定位到达目标的真实几何断言:locateNode 把目标节点带入视口中心。
 * 断言目标卡片中心落在画布中央区域内(而不是只"没有报错")。
 */
async function targetIsCenteredInCanvas(
  page: import('@playwright/test').Page,
  selector: string,
): Promise<boolean> {
  return page.evaluate((sel) => {
    const canvas = document.querySelector('[data-test="graph-canvas"]')
    const node = document.querySelector(sel)
    if (!canvas || !node) return false
    const c = canvas.getBoundingClientRect()
    const n = node.getBoundingClientRect()
    if (n.width === 0 || n.height === 0) return false
    const nx = n.left + n.width / 2
    const ny = n.top + n.height / 2
    const cx = c.left + c.width / 2
    const cy = c.top + c.height / 2
    return Math.abs(nx - cx) < c.width * 0.35 && Math.abs(ny - cy) < c.height * 0.35
  }, selector)
}

test.describe('top recovery banner is locate-only', () => {
  test('single failure: banner shows 查看, no retry control, locating reaches the node without requests', async ({ page }) => {
    test.setTimeout(180_000)
    await createProject(page, 'E2E Top Locate Single')
    await draftFirstQuestion(page)
    await page.getByTestId('free-text').fill('顶部定位验证 [[fail-state-update:1]]')
    await page.getByTestId('submit-answer').click()

    await expect.poll(async () => (await unresolvedFailures(page)).length, {
      timeout: 60_000,
    }).toBeGreaterThan(0)
    const failures = await unresolvedFailures(page)
    const sourceNodeId = failures[0].sourceNodeId
    expect(sourceNodeId).toBeTruthy()

    const banner = page.getByTestId('pending-recovery-banner')
    await expect(banner).toBeVisible()
    // 只读契约:横幅内没有圆箭头重试控件,也没有"前往模型设置"
    expect(banner.getByTestId('node-recovery-retry')).toHaveCount(0)
    expect(banner.getByTestId('node-recovery-settings')).toHaveCount(0)
    expect(banner.locator('button', { hasText: '↻' })).toHaveCount(0)
    // 单项只有"查看"
    const locate = banner.getByTestId('pending-recovery-locate')
    await expect(locate).toBeVisible()
    await expect(locate).toContainText('查看')

    // 截图:顶部单项形态(无重试箭头)
    await fitGraph(page)
    await page.screenshot({ path: path.join(SHOTS, 'top-single-locate-only.png') })

    // 鼠标点击"查看":不发出任何 retry/生成请求,并把来源节点带到画布中心
    const requests = trackRecoveryRequests(page)
    await locate.click()
    await expect.poll(async () => targetIsCenteredInCanvas(
      page, `[data-node-id="${sourceNodeId}"]`), { timeout: 15_000 }).toBe(true)
    expect(requests).toEqual([])
    await expect(banner).toBeVisible()

    // 键盘操作同样只定位:焦点 + Enter 不得发出请求
    await locate.focus()
    await page.keyboard.press('Enter')
    await page.waitForTimeout(1_000)
    expect(requests).toEqual([])

    // 对应失败位置仍可重试:节点恢复栏的圆箭头仍然可用且发出重试
    const nodeRetry = page
      .locator(`[data-test="graph-question-node"][data-node-id="${sourceNodeId}"]`)
      .getByTestId('node-recovery-retry')
    await expect(nodeRetry).toBeVisible()
    await nodeRetry.click()
    await expect(page.getByTestId('node-recovery-bar')).toHaveCount(0, { timeout: 90_000 })
    await expect(page.getByTestId('pending-recovery-banner')).toHaveCount(0)
    expect(await countAnswersWithText(page, '顶部定位验证')).toBe(1)
  })

  test('multiple failures: expand to route + reason rows, each locate reaches its own target, zero retry requests', async ({ page }) => {
    test.setTimeout(300_000)
    await createProject(page, 'E2E Top Locate Multi')
    await draftFirstQuestion(page)

    // 第一个失败:正常回答并携带 [[fail-decision:5]](回答周期成功;武装
    // 起草失败预算)。自治续跑追加下一个问题;随后 fork 分支的首问起草按
    // 预算确定性失败(下游占位卡形态)。fork 会把新分支设为当前路线。
    await page.getByTestId('free-text').fill('多项定位验证 [[fail-decision:5]]')
    await page.getByTestId('submit-answer').click()
    await expect(page.locator('[data-test="graph-question-node"]')).toHaveCount(2, { timeout: 120_000 })
    const answerNodeId = await page.evaluate(async () => {
      const projectId = location.pathname.split('/')[2]
      const graph = await fetch(`/api/v1/projects/${projectId}/graph`).then((r) => r.json())
      return (graph.answers ?? []).find(
        (a: { freeText?: string | null }) => (a.freeText ?? '').includes('多项定位验证'))!.nodeId as string
    })
    const forkCard = page.locator(`[data-test="graph-question-node"][data-node-id="${answerNodeId}"]`)
    await fitGraph(page)
    await forkCard.hover()
    await forkCard.getByTestId('fork-node').click()
    await expect(page.getByTestId('fork-dialog')).toBeVisible()
    await page.getByTestId('fork-submit').click()
    await expect(page.getByTestId('fork-dialog')).toHaveCount(0)

    await expect.poll(async () => (await unresolvedFailures(page)).length, {
      timeout: 120_000,
    }).toBeGreaterThan(0)

    // 第二个失败:fork 已把分支设为当前路线,先把主路线重新设回当前,
    // 再对主路线 tip 提交携带 [[fail-state-update:1]] 的回答——Answer 已
    // 落库但后续处理失败(节点恢复栏形态)。注意 [[fail-state-update]] 会
    // 先于 [[fail-decision]] 武装抛出,两者不能在同一次回答里共存。
    const mainCard = page.locator('[data-route-id]')
      .filter({ hasNot: page.getByTestId('active-route') })
      .first()
    await openRouteMore(mainCard)
    await mainCard.getByTestId('activate-route').click()
    await expect(page.getByText('已设为当前路线')).toBeVisible()
    await page.getByTestId('free-text').fill('状态更新失败注入 [[fail-state-update:1]]')
    await page.getByTestId('submit-answer').click()

    await expect.poll(async () => (await unresolvedFailures(page)).length, {
      timeout: 120_000,
    }).toBeGreaterThan(1)

    const failures = await unresolvedFailures(page)
    const draftFailure = failures.find((f) => f.operation === 'DRAFT_QUESTION')
    const answerFailure = failures.find((f) => f.operation !== 'DRAFT_QUESTION')
    expect(draftFailure).toBeTruthy()
    expect(answerFailure).toBeTruthy()

    const banner = page.getByTestId('pending-recovery-banner')
    await expect(banner.getByTestId('pending-recovery-toggle')).toBeVisible()
    await expect(banner).toContainText('2 项待处理')
    // 多项形态下没有圆箭头重试控件
    expect(banner.getByTestId('node-recovery-retry')).toHaveCount(0)

    // 展开列表:路线 + 简短原因,每项只有"定位"
    await banner.getByTestId('pending-recovery-toggle').click()
    const list = banner.getByTestId('pending-recovery-list')
    await expect(list).toBeVisible()
    expect(list.getByTestId('node-recovery-retry')).toHaveCount(0)
    expect(list.locator('button', { hasText: '↻' })).toHaveCount(0)
    await expect(list.getByTestId(`pending-recovery-locate-${draftFailure!.runId}`)).toBeVisible()
    await expect(list.getByTestId(`pending-recovery-locate-${answerFailure!.runId}`)).toBeVisible()

    // 截图:多项展开形态(每项只有定位)
    await page.screenshot({ path: path.join(SHOTS, 'top-multi-locate-only.png') })

    // 定位起草失败:到达下游占位卡本身(带入视口中心),绝不发 retry
    const requests = trackRecoveryRequests(page)
    await list.getByTestId(`pending-recovery-locate-${draftFailure!.runId}`).click()
    await expect.poll(async () => targetIsCenteredInCanvas(
      page, `[data-node-id="pending:${draftFailure!.runId}"]`), { timeout: 15_000 }).toBe(true)
    expect(requests).toEqual([])

    // 定位回答失败:到达来源节点,两个目标互不串(列表在首次定位后仍展开)
    await list.getByTestId(`pending-recovery-locate-${answerFailure!.runId}`).click()
    await expect.poll(async () => targetIsCenteredInCanvas(
      page, `[data-node-id="${answerFailure!.sourceNodeId}"]`), { timeout: 15_000 }).toBe(true)
    expect(requests).toEqual([])

    // 真正的恢复入口仍在对应失败位置:占位卡与节点恢复栏都可重试
    const draftRetry = page
      .locator(`[data-node-id="pending:${draftFailure!.runId}"]`)
      .getByTestId('retry-pending')
    await expect(draftRetry).toBeVisible()
    const nodeRetry = page
      .locator(`[data-test="graph-question-node"][data-node-id="${answerFailure!.sourceNodeId}"]`)
      .getByTestId('node-recovery-retry')
    await expect(nodeRetry).toBeVisible()
    await nodeRetry.click()
    // 重试发起后横幅仍可能有另一条失败(起草失败),但本失败的节点恢复栏收敛
    await expect(
      page.locator(`[data-test="graph-question-node"][data-node-id="${answerFailure!.sourceNodeId}"]`)
        .getByTestId('node-recovery-bar'),
    ).toHaveCount(0, { timeout: 90_000 })
  })
})

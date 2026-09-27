import path from 'node:path'
import { test, expect, createProject, draftFirstQuestion, fitGraph } from './helpers'

// 第五轮复核 R5-A 的浏览器验收:规格恢复的 operation 契约。后端规格生成的
// run 是 triggerType=GENERATE_SPEC、operation=GENERATE_ARTIFACT,失败清单
// 返回 operation——前端定位与面板筛选都以 GENERATE_ARTIFACT 判定,并绑定
// 面板正在查看的路线(显式阅读路线),绝不因 Active 指针串目标。
// 规格失败由确定性引擎注入 [[fail-artifact:1]] 产生(仅 engine=fake 的测试
// 基础设施):回答文本武装、真实 worker 执行的规格生成 run 确定性失败——
// 失败清单载荷来自真实失败接口。零真实模型调用。
const SHOTS = path.join('test-results', 'spec-failure-shots',
  new Date().toISOString().replace(/[:.]/g, '-'))

async function unresolvedFailures(page: import('@playwright/test').Page): Promise<Array<{
  runId: string
  operation: string
  routeId: string | null
  availableAction: string
}>> {
  return page.evaluate(async () => {
    const projectId = location.pathname.split('/')[2]
    return fetch(`/api/v1/projects/${projectId}/agent-runs/unresolved`).then((r) => r.json())
  })
}

/** 监听恢复/生成类 POST 请求:顶部定位绝不允许发出。 */
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

test.describe('spec failure recovery (operation contract GENERATE_ARTIFACT)', () => {
  test('top 查看 opens the spec panel bound to the reading route; panel recovery retries the failed run', async ({ page }) => {
    test.setTimeout(300_000)
    await createProject(page, 'E2E Spec Failure Recovery')
    await draftFirstQuestion(page)

    // 回答携带 [[fail-artifact:1]]:回答与 STATE_UPDATE 正常完成(武装),
    // 自治续跑追加下一问;其后有效历史包含该节点的规格生成确定性失败。
    await page.getByTestId('free-text').fill('规格失败注入 [[fail-artifact:1]]')
    await page.getByTestId('submit-answer').click()
    await expect(page.locator('[data-test="graph-question-node"]')).toHaveCount(2, { timeout: 120_000 })
    const answerNodeId = await page.evaluate(async () => {
      const projectId = location.pathname.split('/')[2]
      const graph = await fetch(`/api/v1/projects/${projectId}/graph`).then((r) => r.json())
      return (graph.answers ?? []).find(
        (a: { freeText?: string | null }) => (a.freeText ?? '').includes('规格失败注入'))!.nodeId as string
    })

    // fork 出分支 B:fork 会把 B 设为当前路线,B 的 tip 是被 fork 的回答
    // 节点,B 的有效历史包含已武装节点 → 针对路线 B 的规格生成将失败。
    const forkCard = page.locator(`[data-test="graph-question-node"][data-node-id="${answerNodeId}"]`)
    await fitGraph(page)
    await forkCard.hover()
    await forkCard.getByTestId('fork-node').click()
    await expect(page.getByTestId('fork-dialog')).toBeVisible()
    await page.getByTestId('fork-label').fill('规格分支')
    await page.getByTestId('fork-submit').click()
    await expect(page.getByTestId('fork-dialog')).toHaveCount(0)

    // 展开 SpecDock,对当前路线 B 发起规格生成 → 确定性失败
    await page.getByTestId('spec-dock-toggle').click()
    await expect(page.getByTestId('generate-spec')).toBeVisible()
    await page.getByTestId('generate-spec').click()

    // 真实失败清单载荷:operation = GENERATE_ARTIFACT(不是 triggerType)
    await expect.poll(async () => (await unresolvedFailures(page))
      .filter((f) => f.operation === 'GENERATE_ARTIFACT').length, {
      timeout: 90_000,
    }).toBe(1)
    const specFailures = (await unresolvedFailures(page))
      .filter((f) => f.operation === 'GENERATE_ARTIFACT')
    const branchRouteId = specFailures[0].routeId
    expect(branchRouteId).toBeTruthy()

    // 收起面板:让"顶部查看 → 展开面板"成为可断言的真实状态变化
    await page.getByTestId('spec-dock-toggle').click()
    await expect(page.getByTestId('spec-dock-body')).toHaveCount(0)

    // 顶部单项"查看":不发出任何重试/生成 POST,并展开规格面板,
    // 面板内出现该失败任务的恢复条目(服务端 RETRY_SPEC 动作)。
    const requests = trackRecoveryRequests(page)
    const banner = page.getByTestId('pending-recovery-banner')
    await expect(banner.getByTestId('pending-recovery-locate')).toBeVisible()
    await banner.getByTestId('pending-recovery-locate').click()
    await expect(page.getByTestId('spec-dock-body')).toBeVisible({ timeout: 15_000 })
    expect(requests).toEqual([])
    const dock = page.getByTestId('spec-dock-body')
    const panelRetry = dock.getByTestId('node-recovery-retry')
    await expect(panelRetry).toBeVisible()
    await expect(dock).toContainText('规格分支')

    await page.screenshot({ path: path.join(SHOTS, 'spec-failure-panel.png') })

    // 阅读路线绑定:把阅读 Focus 切到主路线 A → 面板不再显示 B 的失败;
    // 切回 B → 失败条目与恢复按钮回来(绝不因 Active 指针串目标)。
    const cards = page.locator('[data-route-id]')
    const activeCard = cards.filter({ has: page.getByTestId('active-route') }).first()
    const otherCard = cards.filter({ hasNot: page.getByTestId('active-route') }).first()
    await otherCard.getByTestId('route-primary').click()
    await expect(dock.locator('[data-test="node-recovery-bar"]')).toHaveCount(0)
    await activeCard.getByTestId('route-primary').click()
    await expect(panelRetry).toBeVisible()

    // 面板内恢复:点击恢复按钮才提交该失败 runId 的重试;预算已耗尽,
    // 重试的规格生成成功 → 失败清单收敛,快照出现在面板。
    await panelRetry.click()
    await expect.poll(async () => (await unresolvedFailures(page))
      .filter((f) => f.operation === 'GENERATE_ARTIFACT').length, {
      timeout: 120_000,
    }).toBe(0)
    await expect(dock.locator('[data-test="node-recovery-bar"]')).toHaveCount(0, { timeout: 60_000 })
    // 路线 B 的规格快照已生成(派生产物就位)
    await page.evaluate(async (routeId) => {
      const projectId = location.pathname.split('/')[2]
      const specs = await fetch(`/api/v1/projects/${projectId}/routes/${routeId}/specs`).then((r) => r.json())
      if (!Array.isArray(specs) || specs.length === 0) {
        throw new Error('expected a spec snapshot for the branch route after recovery')
      }
    }, branchRouteId!)
  })
})

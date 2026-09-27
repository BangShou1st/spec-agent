import path from 'node:path'
import { test, expect, createProject, draftFirstQuestion, fitGraph } from './helpers'

// 第三轮复核 R3-B 的浏览器验收:重试运行中硬刷新后,释放后端任务,
// 不再刷新、不再点击,新产物必须自动出现(续接的 watcher 走与主动重试
// 相同的共享完成路径)。确定性窗口由仅测试环境的延迟指令
// [[delay-decision-ms:N]](确定性引擎内部,engine=fake 才注册)提供:
// 重试 run 在后端真实保持 running 一段时间,不依赖碰运气的 sleep。
const SHOTS = path.join('test-results', 'retry-reload-shots',
  new Date().toISOString().replace(/[:.]/g, '-'))

interface GraphAnswer { freeText?: string | null }

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

async function unresolvedFailures(page: import('@playwright/test').Page): Promise<Array<{
  runId: string
  routeId: string | null
  sourceNodeId: string | null
  availableAction: string
  retryRunId: string | null
  retryStatus: string | null
}>> {
  return page.evaluate(async () => {
    const projectId = location.pathname.split('/')[2]
    return fetch(`/api/v1/projects/${projectId}/agent-runs/unresolved`).then((r) => r.json())
  })
}

test.describe('retry watched across a hard reload (R3-B)', () => {
  test('hard refresh while the retry is running still auto-reveals the new artifact', async ({ page }) => {
    test.setTimeout(360_000)
    await createProject(page, 'E2E Retry Reload Auto Refresh')
    await draftFirstQuestion(page)

    // 回答携带两条确定性指令(仅测试基础设施):
    // - [[fail-decision:1]]:首次分支首问起草确定性失败 → 失败占位卡;
    // - [[delay-decision-ms:15000]]:接下来的两次独立起草(首次起草与
    //   重试)各延迟 15s——重试在硬刷新期间确实保持 running。
    await page.getByTestId('free-text')
      .fill('延迟重试验证 [[fail-decision:1]][[delay-decision-ms:15000]]')
    await page.getByTestId('submit-answer').click()
    // 回答 run 完成后其自治续跑子 run 也会消耗一次延迟预算,链路轮询
    // 要等它一起终态——放宽到 60s。
    await expect(page.getByText('回答已记录')).toBeVisible({ timeout: 60_000 })
    await expect(page.locator('[data-test="graph-question-node"]')).toHaveCount(2, { timeout: 120_000 })

    // fork 分支:其首问起草按预算确定性失败(先延迟后失败)
    const answerNodeId = await page.evaluate(async () => {
      const projectId = location.pathname.split('/')[2]
      const graph = await fetch(`/api/v1/projects/${projectId}/graph`).then((r) => r.json())
      return graph.answers[0].nodeId as string
    })
    const forkCard = page.locator(`[data-test="graph-question-node"][data-node-id="${answerNodeId}"]`)
    await forkCard.hover()
    await forkCard.getByTestId('fork-node').click()
    await expect(page.getByTestId('fork-dialog')).toBeVisible()
    await page.getByTestId('fork-submit').click()
    // forkNode 会等到分支首问起草终态(首次起草带 15s 确定性延迟后才失败)
    await expect(page.getByTestId('fork-dialog')).toHaveCount(0, { timeout: 90_000 })

    const branchRouteId = async (): Promise<string | null> => page.evaluate(async (nodeId) => {
      const projectId = location.pathname.split('/')[2]
      const graph = await fetch(`/api/v1/projects/${projectId}/graph`).then((r) => r.json())
      const branch = (graph.routes ?? []).find(
        (r: { tipNodeId: string }) => r.tipNodeId === nodeId)
      return branch ? branch.id : null
    }, answerNodeId)

    /** 分支首问起草的失败(重试生成动作),身份来自服务端权威清单。 */
    const branchDraftFailure = async (): Promise<{ runId: string } | null> => {
      const branchId = await branchRouteId()
      if (!branchId) return null
      const failures = await unresolvedFailures(page)
      return failures.find((f) => f.routeId === branchId
        && f.availableAction === 'RETRY_GENERATION') ?? null
    }

    // 首次起草延迟 15s 后失败 → 失败占位卡出现(身份来自服务端清单)
    await expect.poll(async () => (await branchDraftFailure())?.runId ?? 'none', {
      timeout: 180_000,
      intervals: [1000, 2500, 5000],
    }).not.toBe('none')
    const failure = await branchDraftFailure()
    const failureCard = page.locator(`[data-node-id="pending:${failure!.runId}"]`)
    const retryButton = failureCard.getByTestId('retry-pending')
    await expect(retryButton).toBeVisible()
    await expect(retryButton).toContainText('重试生成')

    // 失败截图
    await fitGraph(page)
    await page.screenshot({ path: path.join(SHOTS, 'retry-reload-failed.png'), fullPage: false })

    // 原位重试 → 服务端确认重试 run 已在途(running)→ 立即硬刷新。
    // 刷新之后不允许再做任何交互:不刷新、不点击。
    await retryButton.click()
    await expect.poll(async () => {
      const branchId = await branchRouteId()
      const failures = await unresolvedFailures(page)
      const current = failures.find((f) => f.routeId === branchId
        && f.availableAction === 'RETRY_GENERATION')
      return current?.retryRunId ?? 'none'
    }, { timeout: 30_000 }).not.toBe('none')

    await page.reload()
    await expect(page.getByTestId('graph-canvas')).toBeVisible({ timeout: 30_000 })

    // 刷新后的"恢复中"截图:续接 watcher 已接管,失败条目携带在途重试
    const recoveredFailures = await unresolvedFailures(page)
    const inFlight = recoveredFailures.find(
      (f) => f.availableAction === 'RETRY_GENERATION' && f.retryRunId)
    expect(inFlight, 'fresh page must see the in-flight retry from the server list').toBeTruthy()
    await page.screenshot({
      path: path.join(SHOTS, 'retry-reload-recovering.png'), fullPage: false,
    })

    // 不再刷新、不再点击:重试完成后,续接 watcher 必须自动——
    // 1) 失败清单收敛(服务端对账,重试成功后失败条目消失);
    await expect.poll(async () => (await unresolvedFailures(page))
      .filter((f) => f.availableAction === 'RETRY_GENERATION').length, {
      timeout: 180_000,
      intervals: [1000, 2500, 5000],
    }).toBe(0)
    // 2) 分支路线出现真实的新问题节点(tip 前进,fork 的回答节点不再是 tip)
    await expect.poll(async () => branchRouteId(), { timeout: 60_000 }).toBe(null)
    // 3) 顶部汇总横幅与占位卡消失,共享完成路径的反馈可见
    await expect(page.getByTestId('pending-recovery-banner')).toHaveCount(0)
    await expect(page.locator(`[data-node-id="pending:${failure!.runId}"]`)).toHaveCount(0)
    await expect(page.getByTestId('feedback')).toContainText('已从上次失败处恢复')

    // 成功截图:新产物在页面上自动可见
    await fitGraph(page)
    await page.screenshot({ path: path.join(SHOTS, 'retry-reload-recovered.png'), fullPage: false })

    // 该回答仍恰好一条(重试/续接绝不重复创建业务事实)
    expect(await countAnswersWithText(page, '延迟重试验证')).toBe(1)
  })
})

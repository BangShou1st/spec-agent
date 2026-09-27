import path from 'node:path'
import { test, expect, createProject, draftFirstQuestion, fitGraph } from './helpers'

// 失败位置独立恢复的浏览器验证。使用后端 test profile 的确定性失败注入
// ([[fail-state-update:1]] / [[fail-decision:N]] 指令,仅测试基础设施):
// 真实 Answer 先落库,随后该节点的下一次 STATE_UPDATE 被精确打断,或
// 独立起草 run 被按预算打断——构造真实的失败 run。零真实模型调用。
const SHOTS = path.join('test-results', 'failure-recovery-shots',
  new Date().toISOString().replace(/[:.]/g, '-'))

interface GraphAnswer { freeText?: string | null }

/** 从图读模型统计包含指定文本的回答条数——对"不重复创建 Answer"的
 * 强断言,不依赖画布展开等易碎的 UI 细节。 */
async function countAnswersWithText(page: import('@playwright/test').Page, text: string): Promise<number> {
  return page.evaluate(async (needle) => {
    const projectId = location.pathname.split('/')[2]
    const graph = await fetch(`/api/v1/projects/${projectId}/graph`).then((r) => r.json())
    // fork 分支会以继承视图暴露同一不可变 Answer:只统计所有者路线的
    // 那一份,才是"不重复创建 Answer"的正确断言。
    return (graph.answers ?? []).filter(
      (a: { freeText?: string | null; inherited?: boolean }) =>
        !a.inherited && (a.freeText ?? '').includes(needle),
    ).length
  }, text)
}

/** 服务端未解决失败清单:恢复身份的权威来源。 */
async function unresolvedFailures(page: import('@playwright/test').Page): Promise<Array<{
  runId: string
  sourceNodeId: string | null
  availableAction: string
}>> {
  return page.evaluate(async () => {
    const projectId = location.pathname.split('/')[2]
    return fetch(`/api/v1/projects/${projectId}/agent-runs/unresolved`).then((r) => r.json())
  })
}

test.describe('task-level failure recovery', () => {
  test('answer failure shows a per-node recovery bar; retry resumes without duplicate answer', async ({ page }) => {
    test.setTimeout(180_000)
    await createProject(page, 'E2E Failure Recovery')
    await draftFirstQuestion(page)

    // 提交携带确定性失败指令的回答
    await page.getByTestId('free-text').fill('登录方式要支持手机号与邮箱 [[fail-state-update:1]]')
    await page.getByTestId('submit-answer').click()

    // 服务端判定失败后,先取权威恢复身份,再定位到具体节点容器——
    // 绝不用全局 .first() 猜按钮归属(顶部横幅与节点复用同一 testid)。
    await expect.poll(async () => (await unresolvedFailures(page)).length, {
      timeout: 60_000,
    }).toBeGreaterThan(0)
    const failures = await unresolvedFailures(page)
    expect(failures[0].availableAction).toBe('CONTINUE_PROCESSING')
    const sourceNodeId = failures[0].sourceNodeId
    expect(sourceNodeId).toBeTruthy()

    // 恢复栏出现在来源节点卡片底部,常驻(不依赖 hover)
    const nodeCard = page.locator(`[data-test="graph-question-node"][data-node-id="${sourceNodeId}"]`)
    const bar = nodeCard.getByTestId('node-recovery-bar')
    await expect(bar).toBeVisible()
    const retryButton = bar.getByTestId('node-recovery-retry')
    await expect(retryButton).toBeVisible()
    // 无障碍契约:32px 图标按钮带完整 aria-label/tooltip
    const ariaLabel = await retryButton.getAttribute('aria-label')
    expect(ariaLabel ?? '').toContain('继续处理')
    expect(await retryButton.getAttribute('title')).toBe(ariaLabel)

    // 顶部入口独立存在(汇总 + 定位),不与节点恢复栏混用:重试点的是
    // 节点内的图标,不是顶部按钮。
    await expect(page.getByTestId('pending-recovery-banner')).toBeVisible()

    // 失败态截图
    await page.screenshot({
      path: path.join(SHOTS, 'answer-failed-recovery-bar.png'),
      fullPage: false,
    })

    // 硬刷新:失败未解决,恢复入口必须仍然存在(持久化恢复状态),
    // 且仍绑定同一节点
    await page.reload()
    await expect(page.getByTestId('graph-canvas')).toBeVisible({ timeout: 30_000 })
    const reloadedFailures = await unresolvedFailures(page)
    expect(reloadedFailures[0]?.sourceNodeId).toBe(sourceNodeId)
    const reloadedBar = page
      .locator(`[data-test="graph-question-node"][data-node-id="${sourceNodeId}"]`)
      .getByTestId('node-recovery-bar')
    await expect(reloadedBar).toBeVisible({ timeout: 30_000 })
    await expect(reloadedBar.getByTestId('node-recovery-retry')).toBeEnabled()

    // 确定失败(非结果未知):重试 → 进度态 → 恢复成功后恢复栏消失
    await retryButton.click()
    await expect(page.getByTestId('node-recovery-bar')).toHaveCount(0, { timeout: 90_000 })

    // 已有内容保留:恢复栏消失;图读模型中包含该文本的 Answer 恰好一条
    // (不重复创建 Answer)
    const recoveryBarsAfter = await page.getByTestId('node-recovery-bar').count()
    expect(recoveryBarsAfter).toBe(0)
    await expect(page.getByTestId('node-state').first()).toContainText('已确认')
    // 图读模型是回答内容的权威断言(不重复创建 Answer);画布历史节点的
    // 展开点击存在真实的稳定性窗口,不在关键路径上重复它。
    expect(await countAnswersWithText(page, '登录方式要支持手机号与邮箱')).toBe(1)

    // 恢复成功截图
    await page.screenshot({
      path: path.join(SHOTS, 'answer-recovered.png'),
      fullPage: false,
    })

    // 硬刷新:恢复成功后不再有待处理失败(顶部入口与恢复栏均消失)
    await page.reload()
    await expect(page.getByTestId('graph-canvas')).toBeVisible({ timeout: 30_000 })
    await expect(page.getByTestId('pending-recovery-banner')).toHaveCount(0)
    await expect(page.getByTestId('node-recovery-bar')).toHaveCount(0)
    // 回答在刷新后仍然存在(不可变 Answer 恰好一条)
    expect(await countAnswersWithText(page, '登录方式要支持手机号与邮箱')).toBe(1)
  })

  test('recovery bar keyboard access and canvas-drag isolation', async ({ page }) => {
    test.setTimeout(180_000)
    await createProject(page, 'E2E Failure Recovery Keyboard')
    await draftFirstQuestion(page)
    await page.getByTestId('free-text').fill('键盘可达性验证 [[fail-state-update:1]]')
    await page.getByTestId('submit-answer').click()
    await expect.poll(async () => (await unresolvedFailures(page)).length, {
      timeout: 60_000,
    }).toBeGreaterThan(0)
    const sourceNodeId = (await unresolvedFailures(page))[0].sourceNodeId
    expect(sourceNodeId).toBeTruthy()
    const nodeCard = page.locator(`[data-test="graph-question-node"][data-node-id="${sourceNodeId}"]`)
    const bar = nodeCard.getByTestId('node-recovery-bar')
    await expect(bar).toBeVisible()
    const retryButton = bar.getByTestId('node-recovery-retry')

    // 键盘可达:Tab 式聚焦后恢复按钮可获得焦点
    await retryButton.focus()
    await expect(retryButton).toBeFocused()

    // 画布隔离的真实状态断言:在恢复图标上按下并拖动,节点与画布都不得
    // 移动(nodrag + 事件隔离),拖动手势也不得触发恢复
    const positionBefore = await nodeCard.boundingBox()
    expect(positionBefore).toBeTruthy()
    const buttonBox = await retryButton.boundingBox()
    expect(buttonBox).toBeTruthy()
    await page.mouse.move(buttonBox!.x + buttonBox!.width / 2, buttonBox!.y + buttonBox!.height / 2)
    await page.mouse.down()
    await page.mouse.move(buttonBox!.x + buttonBox!.width / 2 + 80,
      buttonBox!.y + buttonBox!.height / 2 + 60, { steps: 8 })
    await page.mouse.up()
    const positionAfterDrag = await nodeCard.boundingBox()
    expect(Math.abs(positionAfterDrag!.x - positionBefore!.x)).toBeLessThan(2)
    expect(Math.abs(positionAfterDrag!.y - positionBefore!.y)).toBeLessThan(2)
    // 拖动手势没有被当成点击:恢复栏仍在,恢复未发起
    await expect(bar).toBeVisible()

    // 键盘 Enter 触发恢复(与鼠标路径同一身份、同一结果)
    await retryButton.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByTestId('node-recovery-bar')).toHaveCount(0, { timeout: 90_000 })
    await expect(page.getByTestId('node-state').first()).toContainText('已确认')
    // 键盘路径与鼠标路径同一身份、同一结果:Answer 恰好一条,不重复创建
    expect(await countAnswersWithText(page, '键盘可达性验证')).toBe(1)
  })

  test('draft failure placeholder retries in place, fails once more with a single card, then succeeds', async ({ page }) => {
    test.setTimeout(300_000)
    await createProject(page, 'E2E Draft Failure Recovery')
    await draftFirstQuestion(page)

    // 回答携带 [[fail-decision:5]]:回答周期自身成功(Answer 落库,自治
    // 续跑的 DECISION 属回答 run 不受影响);其后的独立起草/续跑 run
    // (自治续跑、fork 分支首问、以及重试)按预算确定性失败——每条恢复链
    // 独立展示,本用例绑定"分支首问起草"这条链做断言。
    await page.getByTestId('free-text').fill('起草失败注入 [[fail-decision:5]]')
    await page.getByTestId('submit-answer').click()
    await expect(page.getByText('回答已记录')).toBeVisible()
    await expect(page.locator('[data-test="graph-question-node"]')).toHaveCount(2, { timeout: 120_000 })

    // 用图读模型确定节点/路线身份,绝不猜 DOM 顺序
    const answerNodeId = await page.evaluate(async () => {
      const projectId = location.pathname.split('/')[2]
      const graph = await fetch(`/api/v1/projects/${projectId}/graph`).then((r) => r.json())
      return graph.answers[0].nodeId as string
    })
    const forkCard = page
      .locator(`[data-test="graph-question-node"][data-node-id="${answerNodeId}"]`)
    await forkCard.hover()
    await forkCard.getByTestId('fork-node').click()
    await expect(page.getByTestId('fork-dialog')).toBeVisible()
    await page.getByTestId('fork-submit').click()
    await expect(page.getByTestId('fork-dialog')).toHaveCount(0)

    /** 分支路线 id:fork 后其 tip 是被 fork 的回答节点。 */
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

    // fork 后自动起草分支首问 → 注入失败 → 占位卡出现
    await expect.poll(async () => (await branchDraftFailure())?.runId ?? 'none', {
      timeout: 120_000,
    }).not.toBe('none')

    const firstFailure = await branchDraftFailure()
    const firstCard = page.locator(`[data-node-id="pending:${firstFailure!.runId}"]`)
    const firstRetry = firstCard.getByTestId('retry-pending')
    await expect(firstRetry).toBeVisible()
    await expect(firstRetry).toContainText('重试生成')
    // 占位卡是任务视图:不创建业务节点,分支路线仍只有 fork 的 1 个节点
    expect(await branchRouteId()).toBeTruthy()

    // 失败占位卡截图
    await fitGraph(page)
    await page.screenshot({
      path: path.join(SHOTS, 'draft-failed-placeholder.png'),
      fullPage: false,
    })

    // 原位重试:同一恢复链在任何时刻只允许一张可见任务卡;失败被最新
    // 失败接替(新 runId),旧卡绝不残留(不叠卡)。
    // 第一次原位重试:占位卡原位转进度态(按钮禁用/进度显示)
    await firstRetry.click()
    let previousRunId = firstFailure!.runId
    let reloaded = false
    for (let attempt = 0; attempt < 8; attempt += 1) {
      await expect.poll(async () => {
        const current = await branchDraftFailure()
        return current ? current.runId : 'resolved'
      }, { timeout: 120_000, intervals: [500, 1000, 2500] })
        .not.toBe(previousRunId)
      // 重试期间/接替后:旧占位卡必须消失(同一恢复链只有一张卡)
      await expect(page.locator(`[data-node-id="pending:${previousRunId}"]`)).toHaveCount(0)
      const current = await branchDraftFailure()
      if (!current) break
      // 中途做一次硬刷新:恢复身份由服务端清单持久化,刷新后仍可原位重试
      if (!reloaded) {
        reloaded = true
        await page.reload()
        await expect(page.getByTestId('graph-canvas')).toBeVisible({ timeout: 30_000 })
      }
      const nextCard = page.locator(`[data-node-id="pending:${current.runId}"]`)
      const nextRetry = nextCard.getByTestId('retry-pending')
      await expect(nextRetry).toBeVisible({ timeout: 60_000 })
      previousRunId = current.runId
      await nextRetry.click()
    }

    // 预算耗尽后的最后一次重试成功:占位卡被真实节点取代,分支路线出现
    // 新问题(分支 tip 前进,不再是 fork 的回答节点)
    await expect.poll(async () => branchRouteId(), { timeout: 120_000 })
      .toBe(null)
    await expect(page.getByTestId('pending-recovery-banner')).toHaveCount(0)
    // 分支路线现有一个新的真实问题节点;该回答仍恰好一条(不重复创建)
    expect(await countAnswersWithText(page, '起草失败注入')).toBe(1)
    // 成功后截图:恢复入口消失,真实产物就位
    await fitGraph(page)
    await page.screenshot({
      path: path.join(SHOTS, 'draft-recovered.png'),
      fullPage: false,
    })
  })
})

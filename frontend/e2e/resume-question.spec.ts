import { test, expect } from '@playwright/test'
import { answerActiveNode, createProject, draftFirstQuestion, fitGraph, closeFloatingWorkspaceWindows } from './helpers'

/**
 * 历史未答问题（normal historical unanswered）的正确语义：
 *
 * Route A: Q1 -> Q2 waiting [tip]
 * Route B: 从 Q1 fork 出的另一条路线,当前 Active
 *
 * 用户选择回答 Q2:
 * - 激活 Route A(activate existing route)
 * - 不创建新 Route(route count 不变)
 * - Question 变为可回答
 *
 * 不存在 RESUME_QUESTION 分支;共享/多归属节点必须由用户先显式选定
 * 查看路线,绝不 Active/first/latest 回退。
 */
test("historical unanswered Question is answered by activating its owning route (no RESUME branch)", async ({ page, request }) => {
  await createProject(page, "E2E Historical Unanswered Activation")

  // Route A 主流程:Q1(已答)-> Q2(等待,tip)
  await draftFirstQuestion(page)
  await answerActiveNode(page, "root answer")
  await expect(page.locator('[data-test="graph-question-node"]')).toHaveCount(2)
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)

  const projectId = page.url().split("/projects/")[1].split(/[?#]/)[0] || ""
  const initialGraph = await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()
  const routeA = initialGraph.routes[0]
  const q1Id = routeA.lineageNodeIds[0]
  const q2Id = routeA.lineageNodeIds[1]

  // 从 Q1 fork 出 Route B —— fork 后 Route B 成为 Active。
  const forkRes = await request.post(
    `/api/v1/projects/${projectId}/nodes/${q1Id}/fork`,
    { data: { sourceRouteId: routeA.id, label: "Route B" } },
  )
  expect(forkRes.status()).toBe(200)
  const forkBody = await forkRes.json()
  const routeB = forkBody.route

  const beforeGraph = await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()
  const beforeRouteCount = beforeGraph.routes.length
  expect(beforeGraph.activeRouteId).toBe(routeB.id)

  // 重新加载页面,聚焦 Route A 上的历史未答 Q2。
  await page.reload()
  await page.waitForSelector(`[data-node-id="${q2Id}"]`, { timeout: 15_000 })
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)

  // Q2 只属于 Route A(单一 route)→ readingRouteId 自动解析为 Route A,
  // 卡片出现"回答这个问题"。
  const q2Node = page.locator(`[data-node-id="${q2Id}"]`)
  await q2Node.hover()
  await expect(q2Node.getByTestId("answer-this-question")).toBeVisible()
  await q2Node.getByTestId("answer-this-question").click()

  // 激活 Route A:route count 不变,无新分支;Q2 变为可回答。
  const beforeCount2 = (await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()).routes.length
  expect(beforeCount2).toBe(beforeRouteCount)
  await expect.poll(async () => {
    const g = await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()
    return g.activeRouteId
  }, { timeout: 15_000 }).toBe(routeA.id)
  await page.waitForSelector('[data-test="free-text"]', { timeout: 15_000 })
  await expect(page.getByTestId("free-text")).toBeVisible()
  const afterGraph = await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()
  expect(afterGraph.routes.length).toBe(beforeRouteCount)
  const routeBAfter = afterGraph.routes.find((r: { id: string }) => r.id === routeB.id)
  expect(routeBAfter).toBeTruthy()
  expect(routeBAfter.branchType).toBe("fork")
})
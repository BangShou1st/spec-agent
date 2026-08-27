import { test, expect } from '@playwright/test'
import { answerActiveNode, buildThreeNodeLineage, closeFloatingWorkspaceWindows, createProject, draftFirstQuestion, fitGraph, forkFromNode } from './helpers'

/**
 * 最终产品模型：一个 canonical Question Node 只有一个 immutable Answer
 * identity。共享节点(被多条 route 引用)：
 *  - 只渲染一个 visual card
 *  - route chips 显示全部 memberships
 *  - Answer 只显示一份，绝不出现 route-divergent 多答案
 *  - Focus 切换不改变 Answer 内容
 *  - Active 不随 Focus 变化
 */
test('shared Question renders once with route memberships and a single Answer identity', async ({ page, request }) => {
  await createProject(page, 'E2E Shared Node Single Answer')
  await buildThreeNodeLineage(page)
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)

  const projectId = page.url().split('/projects/')[1].split(/[?#]/)[0] || ''
  const graphBefore = await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()
  const routeA = graphBefore.routes[0]
  const q2Id = routeA.lineageNodeIds[1]
  const answerOnA = graphBefore.answers.find((a: { nodeId: string }) => a.nodeId === q2Id)

  // Fork from Q2: Route B 共享 [Q1, Q2]。fork 后 Route B 成为 Active。
  await forkFromNode(page, 1, 'Route-B')
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)

  const graphAfterFork = await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()
  const q2Answers = graphAfterFork.answers.filter((a: { nodeId: string }) => a.nodeId === q2Id)
  // 共享节点只有一份 Answer identity(owner + inherited 同 ID),内容一致。
  expect(q2Answers.length).toBeGreaterThanOrEqual(1)
  const distinctIds = new Set(q2Answers.map((a: { id: string }) => a.id))
  expect(distinctIds.size).toBe(1)
  expect(q2Answers[0].freeText).toBe(answerOnA.freeText)

  // 共享节点只渲染一个 visual card,并显示两条 route memberships。
  const q2Cards = page.locator(`[data-node-id="${q2Id}"]`)
  await expect(q2Cards).toHaveCount(1)
  const chip = q2Cards.getByTestId('route-membership')
  await expect(chip).toBeVisible()
  await expect(q2Cards.locator('.graph-route-chip')).toHaveCount(2)

  // 卡片上只显示一份 Answer 内容。
  await expect(q2Cards).toContainText(answerOnA.freeText as string)
})

test('Focus switching does not change the Answer content and Active never follows Focus', async ({ page, request }) => {
  await createProject(page, 'E2E Shared Focus Stability')
  await draftFirstQuestion(page)
  await answerActiveNode(page, 'root answer')
  await answerActiveNode(page, 'second answer')
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)

  const projectId = page.url().split('/projects/')[1].split(/[?#]/)[0] || ''
  const graphBefore = await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()
  const routeA = graphBefore.routes[0]
  // Fork 从 Q1:共享节点是 Q1(Route A 与 Route B 都引用它)。
  const sharedNodeId = routeA.lineageNodeIds[0]
  const answerText = graphBefore.answers.find((a: { nodeId: string }) => a.nodeId === sharedNodeId).freeText

  // Fork 使 Route B active。
  const forkRes = await request.post(
    `/api/v1/projects/${projectId}/nodes/${sharedNodeId}/fork`,
    { data: { sourceRouteId: routeA.id, label: 'Route-B' } },
  )
  expect(forkRes.status()).toBe(200)
  const forkBody = await forkRes.json()
  const routeBId = forkBody.route.id
  const activeBeforeFocus = forkBody.activeRouteId
  expect(activeBeforeFocus).toBe(routeBId)

  await page.reload()
  await page.waitForSelector(`[data-node-id="${sharedNodeId}"]`)
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)

  const sharedCard = page.locator(`[data-node-id="${sharedNodeId}"]`)
  // 卡片始终只显示这一份 Answer。
  await expect(sharedCard).toContainText(answerText)

  // 通过 reading-route select 切换 Focus 到 Route A。
  const select = sharedCard.getByTestId('reading-route-select')
  await select.selectOption(routeA.id)
  await page.waitForTimeout(300)
  await expect(sharedCard).toContainText(answerText)
  const activeAfterFocusA = (await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()).activeRouteId
  expect(activeAfterFocusA).toBe(routeBId) // Active 不随 Focus 变化

  // 切回 Route B,Answer 内容仍然不变。
  await select.selectOption(routeBId)
  await page.waitForTimeout(300)
  await expect(sharedCard).toContainText(answerText)
  const activeAfterFocusB = (await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()).activeRouteId
  expect(activeAfterFocusB).toBe(routeBId)
})
import { test, expect } from '@playwright/test'
import { buildThreeNodeLineage, closeFloatingWorkspaceWindows, createProject, fitGraph } from './helpers'

test('re-answer creates a NEW Question Node; old Question and Answer stay untouched', async ({ page, request }) => {
  await createProject(page, 'E2E Re-answer New Node')
  await buildThreeNodeLineage(page)
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)

  const projectId = page.url().split('/projects/')[1].split(/[?#]/)[0] || ''
  const graphBefore = await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()
  const routeABefore = graphBefore.routes[0]
  const oldQ2Id = routeABefore.lineageNodeIds[1]
  const oldAnswer = graphBefore.answers.find((a: { nodeId: string }) => a.nodeId === oldQ2Id)
  const oldRouteCount = graphBefore.routes.length
  const routeAId = routeABefore.id

  const target = page.locator(`[data-node-id="${oldQ2Id}"]`)
  await target.hover()
  await target.getByTestId('reanswer-node').click()
  await expect(page.getByTestId('reanswer-dialog')).toBeVisible()
  await page.getByTestId('reanswer-submit').click()
  await expect(page.getByTestId('reanswer-dialog')).toHaveCount(0)

  // 新路线出现,其 tip 是新的 Question Node(id 不同于旧 Q2)。
  const graphAfter = await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()
  expect(graphAfter.routes.length).toBe(oldRouteCount + 1)
  const reanswerRoute = graphAfter.routes.find(
    (r: { branchType: string | null }) => r.branchType === 'reanswer',
  )
  expect(reanswerRoute).toBeTruthy()
  const newQ2Id = reanswerRoute.tipNodeId
  expect(newQ2Id).not.toBe(oldQ2Id)

  // 新 Question 复制问题语义且等待回答(无 Answer)。
  const newNode = graphAfter.nodes.find((n: { id: string }) => n.id === newQ2Id)
  expect(newNode).toBeTruthy()
  expect(newNode.question).toBe(
    graphBefore.nodes.find((n: { id: string }) => n.id === oldQ2Id).question,
  )
  expect(graphAfter.answers.filter((a: { nodeId: string }) => a.nodeId === newQ2Id)).toHaveLength(0)

  // 旧路线不变:旧 Question + 旧 Answer 仍在,没有同节点第二个 Answer。
  const routeAAfter = graphAfter.routes.find((r: { id: string }) => r.id === routeAId)
  expect(routeAAfter.lineageNodeIds).toEqual(routeABefore.lineageNodeIds)
  const q2Answers = graphAfter.answers.filter((a: { nodeId: string }) => a.nodeId === oldQ2Id)
  expect(q2Answers.length).toBeGreaterThanOrEqual(1)
  expect(new Set(q2Answers.map((a: { id: string }) => a.id)).size).toBe(1)
  expect(q2Answers[0].freeText).toBe(oldAnswer.freeText)

  // 画面上:旧卡片与全新卡片是不同 canonical id,新卡片位于 active route 可直接回答。
  await page.waitForSelector(`[data-node-id="${newQ2Id}"]`, { timeout: 15_000 })
  await expect(page.locator(`[data-node-id="${oldQ2Id}"]`)).toHaveCount(1)
  await expect(page.locator(`[data-node-id="${newQ2Id}"]`)).toHaveCount(1)
  await expect(page.getByTestId('free-text')).toBeVisible()
})
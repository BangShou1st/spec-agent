import { test, expect, type Page } from '@playwright/test'
import { buildThreeNodeLineage, closeFloatingWorkspaceWindows, createProject, fitGraph } from './helpers'

/**
 * 真实 Chromium mouse drag: source handle → target handle 只产生 Pending
 * Relation Proposal。此时 backend 没有任何变化(relations = 0)；用户选择
 * 类型/方向并 Confirm 后才持久化；Cancel / Escape / click-away 保持 0 关系。
 *
 * 全程使用 page.mouse.move / down / move / up 真实指针事件,不使用
 * synthetic PointerEvent 冒充真实交互。
 */

interface GraphJson {
  relations: Array<{ id: string; sourceNodeId: string; targetNodeId: string; relationType: string }>
  routes: Array<{
    id: string
    lineageNodeIds: string[]
    tipNodeId: string
    branchType: string | null
    sourceRouteId: string | null
    branchAtNodeId: string | null
  }>
  activeRouteId: string | null
}

async function graphOf(page: Page, projectId: string): Promise<GraphJson> {
  return (await (await page.request.get(`/api/v1/projects/${projectId}/graph`)).json()) as GraphJson
}

async function nodeCounts(page: Page, projectId: string): Promise<{ nodes: string[]; tips: string[]; active: string | null }> {
  const g = await graphOf(page, projectId)
  return {
    nodes: [...g.routes.flatMap((r) => r.lineageNodeIds)],
    tips: g.routes.map((r) => r.tipNodeId),
    active: g.activeRouteId,
  }
}

/** 真实鼠标:从 source node 的 source-right handle 拖到 target node 的 target-left handle。 */
async function realDragConnect(page: Page, sourceNodeId: string, targetNodeId: string): Promise<void> {
  const sourceNode = page.locator(`[data-node-id="${sourceNodeId}"]`)
  const targetNode = page.locator(`[data-node-id="${targetNodeId}"]`)
  await sourceNode.scrollIntoViewIfNeeded()
  await targetNode.scrollIntoViewIfNeeded()
  // 清空上次拖拽的鼠标状态,并从画布空白处开始。
  await page.mouse.move(0, 0)
  await page.waitForTimeout(120)
  // hover 使 source handle 进入可命中状态。
  await sourceNode.hover()
  await page.waitForTimeout(250)
  const sourceHandle = page.locator(
    `.vue-flow__handle[data-handleid="source-right"][data-nodeid="${sourceNodeId}"]`,
  )
  const targetHandle = page.locator(
    `.vue-flow__handle[data-handleid="target-left"][data-nodeid="${targetNodeId}"]`,
  )
  await expect(sourceHandle).toBeAttached()
  await expect(targetHandle).toBeAttached()
  const sBox = await sourceHandle.boundingBox()
  const tBox = await targetHandle.boundingBox()
  expect(sBox).not.toBeNull()
  expect(tBox).not.toBeNull()
  const sx = sBox!.x + sBox!.width / 2
  const sy = sBox!.y + sBox!.height / 2
  const tx = tBox!.x + tBox!.width / 2
  const ty = tBox!.y + tBox!.height / 2
  // 重新 hover 保证 handle 可点后,再移动到 handle 中心并按下。
  await sourceNode.hover()
  await page.mouse.move(sx, sy)
  await page.mouse.down()
  await page.mouse.move(tx, ty, { steps: 24 })
  await page.mouse.up()
}

test('real mouse drag opens proposal without persisting; Confirm persists exactly the chosen relation', async ({ page }) => {
  await createProject(page, 'E2E Connection Proposal')
  await buildThreeNodeLineage(page)
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)
  await page.waitForTimeout(700)

  const projectId = page.url().split('/projects/')[1].split(/[?#]/)[0] || ''
  const g = await graphOf(page, projectId)
  const sourceNodeId = g.routes[0].lineageNodeIds[1]
  const targetNodeId = g.routes[0].lineageNodeIds[2]
  const before = await nodeCounts(page, projectId)

  // 1-3. 真实 drag source -> target → Proposal chooser 出现。
  await realDragConnect(page, sourceNodeId, targetNodeId)
  await expect(page.getByTestId('relation-proposal')).toBeVisible()
  await expect(page.getByTestId('relation-endpoints')).toBeVisible()

  // 4. 此时 backend 零变化。
  const during = await graphOf(page, projectId)
  expect(during.relations.length).toBe(0)

  // 5-7. 选择 SUPPORTS 并 Confirm → backend 恰好一条 SUPPORTS。
  await page.getByTestId('relation-type-supports').click()
  await page.getByTestId('relation-confirm').click()
  await expect(page.getByTestId('relation-proposal')).toHaveCount(0)
  await expect.poll(async () => {
    const g2 = await graphOf(page, projectId)
    return g2.relations.length
  }, { timeout: 15_000 }).toBe(1)
  const after = await graphOf(page, projectId)
  expect(after.relations[0].relationType).toBe('SUPPORTS')

  // 8. lineage / route tip / Active 全不变。
  const afterCounts = await nodeCounts(page, projectId)
  expect(afterCounts.nodes).toEqual(before.nodes)
  expect(afterCounts.tips).toEqual(before.tips)
  expect(afterCounts.active).toEqual(before.active)

  // 9. Cancel path: drag → proposal → Escape → relations 仍为 0。
  //    用一对新节点(root → 中间),避开已连接 pair 的重复交互。
  const g3 = await graphOf(page, projectId)
  const cancelSource = g3.routes[0].lineageNodeIds[0]
  const cancelTarget = g3.routes[0].lineageNodeIds[1]
  await realDragConnect(page, cancelSource, cancelTarget)
  await expect(page.getByTestId('relation-proposal')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByTestId('relation-proposal')).toHaveCount(0)
  const afterCancel = await graphOf(page, projectId)
  expect(afterCancel.relations.length).toBe(1)
})

test('confirm shows the created relation on the selected node; clearing selection hides it (layer off)', async ({ page }) => {
  await createProject(page, 'E2E Relation Visibility')
  await buildThreeNodeLineage(page)
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)
  await page.waitForTimeout(700)

  const projectId = page.url().split('/projects/')[1].split(/[?#]/)[0] || ''
  const g = await graphOf(page, projectId)
  const sourceNodeId = g.routes[0].lineageNodeIds[1]
  const targetNodeId = g.routes[0].lineageNodeIds[2]

  await realDragConnect(page, sourceNodeId, targetNodeId)
  await expect(page.getByTestId('relation-proposal')).toBeVisible()
  await page.getByTestId('relation-type-related_to').click()
  await page.getByTestId('relation-confirm').click()
  await expect(page.getByTestId('relation-proposal')).toHaveCount(0)
  await expect.poll(async () => {
    const g2 = await graphOf(page, projectId)
    return g2.relations.length
  }, { timeout: 15_000 }).toBe(1)

  // Confirm 后 source 保持选中 → direct relation 立即可见(全局层默认关)。
  const vg = await graphOf(page, projectId)
  // Vue Flow 会把 edge class 传播到 wrapper 与内部元素;用 wrapper 精确计数。
  await expect(page.locator('.vue-flow__edge.graph-edge--relation')).toHaveCount(1)

  // 取消选择 → global 层 OFF → relation 收起。
  await page.mouse.click(10, 400)
  await page.waitForTimeout(400)
  await expect(page.locator('.vue-flow__edge.graph-edge--relation')).toHaveCount(0)

  // Inspector 始终能看到 canonical 关系。
  await page.getByTestId('open-inspector').click()
  await expect(page.getByTestId('floating-window-inspector')).toBeVisible()
  const sourceNode = page.locator(`[data-node-id="${sourceNodeId}"]`)
  await sourceNode.click()
  await expect(page.getByTestId('node-relations')).toBeVisible()
  await expect(page.getByTestId('node-relations').first()).toContainText('相关')
})

test('symmetric duplicate is rejected: A RELATED_TO B then B RELATED_TO A', async ({ page }) => {
  await createProject(page, 'E2E Symmetric Duplicate')
  await buildThreeNodeLineage(page)
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)
  await page.waitForTimeout(700)

  const projectId = page.url().split('/projects/')[1].split(/[?#]/)[0] || ''
  const g = await graphOf(page, projectId)
  const nodeA = g.routes[0].lineageNodeIds[1]
  const nodeB = g.routes[0].lineageNodeIds[2]

  await realDragConnect(page, nodeA, nodeB)
  await page.getByTestId('relation-type-related_to').click()
  await page.getByTestId('relation-confirm').click()
  await expect(page.getByTestId('relation-proposal')).toHaveCount(0)
  await expect.poll(async () => {
    const g2 = await graphOf(page, projectId)
    return g2.relations.length
  }, { timeout: 15_000 }).toBe(1)

  // 反向 drag:RELATED_TO 对称 → UI/backend reject duplicate,关系数不变。
  await realDragConnect(page, nodeB, nodeA)
  await expect(page.getByTestId('relation-proposal')).toBeVisible()
  await page.getByTestId('relation-type-related_to').click()
  await page.getByTestId('relation-confirm').click()
  await expect(page.getByTestId('relation-proposal')).toHaveCount(0)
  await page.waitForTimeout(600)
  const after = await graphOf(page, projectId)
  expect(after.relations.length).toBe(1)
})

test('directional SUPPORTS both directions are distinct facts', async ({ page }) => {
  await createProject(page, 'E2E Directional Supports')
  await buildThreeNodeLineage(page)
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)
  await page.waitForTimeout(700)

  const projectId = page.url().split('/projects/')[1].split(/[?#]/)[0] || ''
  const g = await graphOf(page, projectId)
  const nodeA = g.routes[0].lineageNodeIds[1]
  const nodeB = g.routes[0].lineageNodeIds[2]

  await realDragConnect(page, nodeA, nodeB)
  await page.getByTestId('relation-type-supports').click()
  await page.getByTestId('relation-confirm').click()
  await expect(page.getByTestId('relation-proposal')).toHaveCount(0)
  await expect.poll(async () => {
    const g2 = await graphOf(page, projectId)
    return g2.relations.length
  }, { timeout: 15_000 }).toBe(1)

  // 反向 SUPPORTS 是不同事实,允许。
  await realDragConnect(page, nodeB, nodeA)
  await page.getByTestId('relation-type-supports').click()
  await page.getByTestId('relation-confirm').click()
  await expect(page.getByTestId('relation-proposal')).toHaveCount(0)
  await expect.poll(async () => {
    const g2 = await graphOf(page, projectId)
    return g2.relations.length
  }, { timeout: 15_000 }).toBe(2)
})
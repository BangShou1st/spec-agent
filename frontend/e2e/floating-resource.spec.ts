import { test, expect, type Page } from '@playwright/test'
import { createProject, draftFirstQuestion, fitGraph, openToolbarMore } from './helpers'

/**
 * 资源独立性：资源先作为**浮动节点**落到画布 —— 不要求 Active 路线、不挂 tip、
 * 不推进任何血缘；用户之后才用鼠标把它连到某条路线的末端节点上接入。
 *
 * 接入成功后：不再是独立节点（徽标消失、出现路线归属）。撤销 = 断开回浮动
 * （内容保留，不是 retract）；重做 = 重新接入。
 *
 * 注意：接入的父节点是"当前末端"，而常规流程下末端就是 AI 刚生成、还没回答的
 * 新问题。资源/知识节点不可回答，挂在上面不会跳过任何待回答的问题，因此后端
 * 对它豁免了 UNANSWERED_QUESTION_HAS_CHILD（kind-aware 校验）。
 *
 * 全程使用 page.mouse 真实指针事件，不用合成事件冒充拖拽。
 */

async function nodeIdOf(page: Page, selector: string): Promise<string> {
  const id = await page.evaluate((sel) => {
    const el = document.querySelector(sel)?.closest('.vue-flow__node')
    return el?.getAttribute('data-id') ?? null
  }, selector)
  expect(id, `canvas node id for ${selector}`).not.toBeNull()
  return id as string
}

/**
 * 真实鼠标：从 source 节点的 source handle 拖到 target 的 target handle。
 * handle 默认被节点体覆盖，所以先 hover 节点把它激活，再用坐标直接驱动
 * mouse —— 对 handle 本身做 hover 会被 node-body 拦截。
 */
async function dragConnect(page: Page, sourceNodeId: string, targetNodeId: string): Promise<void> {
  await page.mouse.move(0, 0)
  await page.locator(`.vue-flow__node[data-id="${sourceNodeId}"]`).hover()

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
  expect(sBox, 'source handle box').not.toBeNull()
  expect(tBox, 'target handle box').not.toBeNull()

  await page.mouse.move(sBox!.x + sBox!.width / 2, sBox!.y + sBox!.height / 2)
  await page.mouse.down()
  await page.mouse.move(tBox!.x + tBox!.width / 2, tBox!.y + tBox!.height / 2, { steps: 24 })
  await page.mouse.up()
}

test('floating resource attaches to a route tip by dragging, and undo/redo is reversible', async ({
  page,
}) => {
  await createProject(page, 'E2E Floating Resource Attach')
  await draftFirstQuestion(page)
  await fitGraph(page)

  // 1) 添加资源 → 浮动独立节点：不挂在任何路线上，也不是任何路线的末端。
  await openToolbarMore(page)
  await page.getByTestId('add-resource').click()
  await expect(page.getByTestId('resource-dialog')).toBeVisible()
  await page.getByTestId('resource-text').fill('规格摘录：结算按自然月，逾期按日息万分之五。')
  await page.getByTestId('resource-submit').click()

  const resource = page.locator('[data-test="graph-knowledge-node"]').first()
  await expect(resource).toBeVisible()
  await expect(resource.getByTestId('floating-badge')).toBeVisible()
  await expect(resource.getByTestId('floating-hint')).toBeVisible()
  await expect(resource.getByTestId('route-membership')).toHaveCount(0)

  await fitGraph(page)

  // 2) 拖线接入：路线末端（当前最新的未回答问题）→ 资源。
  const resourceId = await nodeIdOf(page, '[data-test="graph-knowledge-node"]')
  const tipId = await nodeIdOf(page, '.graph-question-node--current')
  await dragConnect(page, tipId, resourceId)

  // 接入后不再是独立节点：徽标与提示消失，路线归属出现。
  await expect(resource.getByTestId('floating-badge')).toHaveCount(0)
  await expect(resource.getByTestId('floating-hint')).toHaveCount(0)
  await expect(resource.getByTestId('route-membership')).toBeVisible()

  // 3) 撤销 = 断开回浮动，内容保留（不是 retract）。
  await page.getByTestId('undo').click()
  await expect(resource.getByTestId('floating-badge')).toBeVisible()
  await expect(resource.getByTestId('route-membership')).toHaveCount(0)
  await expect(resource.getByTestId('knowledge-text')).toContainText('结算按自然月')

  // 4) 重做 = 重新接入同一条路线的末端。
  await page.getByTestId('redo').click()
  await expect(resource.getByTestId('floating-badge')).toHaveCount(0)
  await expect(resource.getByTestId('route-membership')).toBeVisible()
})

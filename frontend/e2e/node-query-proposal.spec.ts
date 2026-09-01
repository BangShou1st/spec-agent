import { expect, test, type Page, type APIRequestContext } from '@playwright/test'
import {
  buildThreeNodeLineage,
  closeFloatingWorkspaceWindows,
  createProject,
  fitGraph,
} from './helpers'

/**
 * 真实 browser + 真实 proposal 端点（不 synthetic bypass，不 skip）。
 *
 * 完整链路：HTTP NodeQuery → RunWorker → Decision → AdvisorPolicy →
 * AgentProposal persisted → NodeQuery result API → 前端 Inspector
 * → Accept/Reject API → 真实后端 mutation 事务。
 *
 * 后端以 test profile + fake gateway 启动：LocalDeterministicDecisionEngine
 * 对含「语义关联」的明确查询输入返回确定性 CONNECT_NODE proposal（连接
 * lineage 前两个节点），所以这两个测试是 deterministic PASS。
 */

interface GraphViewShape {
  routes?: Array<{ lineageNodeIds?: string[] }>
  relations?: Array<Record<string, unknown>>
}

async function askOnNode(page: Page, request: APIRequestContext, question: string) {
  await createProject(page, 'E2E NodeQuery Proposal')
  await buildThreeNodeLineage(page)
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)

  const projectId = page.url().split('/projects/')[1].split(/[?#]/)[0] || ''
  const graph = (await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()) as GraphViewShape
  const lineage = graph.routes?.[0]?.lineageNodeIds ?? []
  // 锚定第二个 lineage 节点（root 的历史子节点）：它是历史卡片（有
  // contextual-ai 入口），其 lineage 含 root + 自身，deterministic fake 据此
  // 产出 root↔child 的 CONNECT_NODE proposal。
  const canonicalNodeId = lineage[1] as string

  const card = page.locator(`[data-node-id="${canonicalNodeId}"]`)
  await card.hover()
  await card.getByTestId('contextual-ai').click()
  await expect(page.getByTestId('floating-window-inspector')).toBeVisible()

  let queryRunId = ''
  // The create endpoint returns 202 + {runId} in the POST RESPONSE body; the
  // POST URL itself has no run id segment.
  page.on('response', async (res) => {
    if (res.request().method() === 'POST'
        && res.url().includes(`/nodes/${canonicalNodeId}/query`)) {
      try {
        const body = (await res.json()) as { runId?: string }
        if (body?.runId) queryRunId = body.runId
      } catch {
        // Non-JSON response (e.g. a 4xx) — the run id stays unknown.
      }
    }
  })
  await page.getByTestId('ask-input').fill(question)
  await page.getByTestId('ask-submit').click()
  await expect(page.getByTestId('ask-result')).toBeVisible()

  // 轮询真实 query 端点直到终态。
  let status = ''
  for (let i = 0; i < 40 && !queryRunId; i += 1) {
    await page.waitForTimeout(500)
  }
  for (let i = 0; i < 60; i += 1) {
    const res = await request.get(`/api/v1/projects/${projectId}/nodes/${canonicalNodeId}/query/${queryRunId}`)
    const body = (await res.json()) as { status: string; proposalId?: string | null }
    status = body.status
    // 终态判定对大小写都健壮;终态为语义状态
    // (AWAITING_APPROVAL / COMPLETED / FAILED ...)。
    if (status.toLowerCase() !== 'running' && status.toLowerCase() !== 'created') break
    await page.waitForTimeout(1000)
  }
  return { projectId, canonicalNodeId, queryRunId, status }
}

async function relationCount(request: APIRequestContext, projectId: string): Promise<number> {
  const graph = (await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()) as GraphViewShape
  return graph.relations?.length ?? 0
}

test('NodeQuery proposal → Accept refreshes the graph', async ({ page, request }) => {
  const { projectId, canonicalNodeId, queryRunId, status } = await askOnNode(
    page,
    request,
    '请为这个节点和它的子节点建立语义关联。',
  )
  // Deterministic fake 对该输入稳定产出 CONNECT_NODE → AWAITING_APPROVAL。
  expect(status).toBe('AWAITING_APPROVAL')

  await expect(page.getByTestId('accept-proposal')).toBeVisible()
  await expect(page.getByTestId('reject-proposal')).toBeVisible()
  // 候选动作摘要可见。
  await expect(page.getByTestId('ask-result')).toContainText('CONNECT_NODE')

  const relationsBefore = await relationCount(request, projectId)
  await page.getByTestId('accept-proposal').click()
  await expect(page.getByTestId('ask-result')).toContainText('提案已接受')

  // 真实 accept 后：proposal 状态 ACCEPTED（经 NodeQuery result API 读回）。
  await expect.poll(async () => {
    const res = await request.get(`/api/v1/projects/${projectId}/nodes/${canonicalNodeId}/query/${queryRunId}`)
    return (await res.json()).status
  }, { timeout: 15_000 }).toBe('ACCEPTED')

  // Graph canonical read refresh：语义关系真实落库，且恰好新增一条（无重复
  // mutation）。
  await expect.poll(async () => relationCount(request, projectId), { timeout: 15_000 })
    .toBe(relationsBefore + 1)
})

test('NodeQuery proposal → Reject leaves the graph unchanged', async ({ page, request }) => {
  const { projectId, canonicalNodeId, queryRunId, status } = await askOnNode(
    page,
    request,
    '请为这个节点和它的子节点建立语义关联。',
  )
  expect(status).toBe('AWAITING_APPROVAL')

  await page.getByTestId('reject-proposal').click()
  await expect(page.getByTestId('ask-result')).toContainText('提案已拒绝')

  await expect.poll(async () => {
    const res = await request.get(`/api/v1/projects/${projectId}/nodes/${canonicalNodeId}/query/${queryRunId}`)
    return (await res.json()).status
  }, { timeout: 15_000 }).toBe('REJECTED')

  // Reject 后 Graph 保持不变：无新增关系。
  expect(await relationCount(request, projectId)).toBe(0)
})

test('Floating node Ask AI sends routeId=null (real endpoint)', async ({ page, request }) => {
  await createProject(page, 'E2E Floating Ask Route-less')
  // 通过真实 API 创建一个浮动（route-less）draft 节点。
  const projectId = page.url().split('/projects/')[1].split(/[?#]/)[0] || ''
  const created = await request.post(`/api/v1/projects/${projectId}/floating-nodes`, {
    data: { routeId: null, subtype: 'IDEA', content: { text: 'floating idea' } },
  })
  const floatNode = (await created.json()) as { id: string }
  await page.goto(`/projects/${projectId}`)
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)

  const card = page.locator(`[data-node-id="${floatNode.id}"]`)
  await expect(card).toBeVisible()
  await card.hover()
  await card.getByTestId('contextual-ai').click()
  await expect(page.getByTestId('floating-window-inspector')).toBeVisible()

  let queryBody: { routeId: unknown } | null = null
  page.on('request', (req) => {
    if (req.method() === 'POST' && req.url().includes(`/nodes/${floatNode.id}/query`)) {
      queryBody = req.postDataJSON() as { routeId: unknown }
    }
  })
  await page.getByTestId('ask-input').fill('从任意浮动节点提问？')
  await page.getByTestId('ask-submit').click()
  await expect(page.getByTestId('ask-result')).toBeVisible()
  expect(queryBody).not.toBeNull()
  expect(queryBody?.routeId).toBeNull()
})
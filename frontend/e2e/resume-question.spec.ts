import { test, expect } from '@playwright/test'
import { buildThreeNodeLineage, createProject, fitGraph } from './helpers'

/**
 * Scenario A: source route is OPEN, the source tip is the target
 * Question, and the target has no effective answer on the source route.
 * Resume must NOT create a new route: the existing source route is
 * reactivated in place. Route count stays the same, the canonical
 * Question is preserved, and the Question is answerable.
 *
 * The current tip on the workspace is Q3 (answerable). We hit the
 * /api/v1/projects/{id}/nodes/{nid}/resume endpoint directly with
 * sourceRouteId = active route, asserting resumedNewRoute=false in the
 * response and that route count is unchanged.
 */
test("Scenario A: source OPEN + tip unanswered reactivates existing route (no new route)", async ({ page, request }) => {
  await createProject(page, "E2E Resume Question Scenario A")
  await buildThreeNodeLineage(page)
  await fitGraph(page)

  const projectId = page.url().split("/projects/")[1].split(/[?#]/)[0] || ""
  const before = await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()
  const beforeRouteCount = before.routes.length
  const sourceRouteId = before.activeRouteId || before.routes[0].id
  const sourceTipNodeId = before.routes[0].tipNodeId

  const response = await request.post(
    `/api/v1/projects/${projectId}/nodes/${sourceTipNodeId}/resume`,
    { data: { sourceRouteId } },
  )
  expect(response.status()).toBe(200)
  const body = await response.json()
  expect(body.resumedNewRoute).toBe(false)
  expect(body.route.id).toBe(sourceRouteId)
  expect(body.activeRouteId).toBe(sourceRouteId)

  const after = await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()
  expect(after.routes.length).toBe(beforeRouteCount)
  // The target Question is preserved (no clone) and is not retracted.
  const target = after.nodes.find((n: { id: string }) => n.id === sourceTipNodeId)
  expect(target).toBeTruthy()
  expect(target.kind).toBe("INTERACTION")
  expect(target.subtype).toBe("QUESTION")
})

/**
 * Scenario B: source route is OPEN, the target is a historical non-tip
 * Question, and the target has no effective answer on the source route.
 * Resume must create a new RESUME_QUESTION branch route. The canonical
 * Question id is preserved, the source route lineage is unchanged, the
 * new route becomes Active, and the Question is answerable.
 *
 * We engineer the historical non-tip target via the continuation API:
 * we append a new node to Q3 so Q3 becomes a historical non-tip on the
 * source route (with no effective answer on the source), then exercise
 * the resume endpoint against Q3.
 */
test("Scenario B: historical non-tip unanswered creates RESUME route", async ({ page, request }) => {
  await createProject(page, "E2E Resume Question Scenario B")
  await buildThreeNodeLineage(page)
  await fitGraph(page)

  const projectId = page.url().split("/projects/")[1].split(/[?#]/)[0] || ""
  const initialGraph = await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()
  const sourceRouteBefore = initialGraph.routes[0]
  const sourceRouteId = sourceRouteBefore.id
  const q3NodeId = initialGraph.nodes[2].id

  // Append a continuation so Q3 becomes a historical non-tip.
  const contRes = await request.post(
    `/api/v1/projects/${projectId}/nodes/${q3NodeId}/continuation`,
    {
      data: {
        routeId: sourceRouteId,
        subtype: "NOTE",
        content: { text: "advancing past Q3" },
      },
    },
  )
  expect(contRes.status()).toBe(201)
  // Snapshot the source lineage AFTER the continuation. Resume must
  // not further change the source lineage.
  const beforeResumeGraph = await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()
  const beforeRouteCount = beforeResumeGraph.routes.length
  const beforeLineage = [...beforeResumeGraph.routes[0].lineageNodeIds]

  // Resume Q3 against the source route. This is a non-tip historical
  // Question with no effective answer, so Scenario B must take the
  // new-route path.
  const resumeRes = await request.post(
    `/api/v1/projects/${projectId}/nodes/${q3NodeId}/resume`,
    { data: { sourceRouteId } },
  )
  expect(resumeRes.status()).toBe(200)
  const body = await resumeRes.json()
  expect(body.resumedNewRoute).toBe(true)
  expect(body.route.tipNodeId).toBe(q3NodeId)
  expect(body.route.sourceRouteId).toBe(sourceRouteId)
  expect(body.activeRouteId).toBe(body.route.id)

  const afterGraph = await (await request.get(`/api/v1/projects/${projectId}/graph`)).json()
  expect(afterGraph.routes.length).toBe(beforeRouteCount + 1)
  const newRoute = afterGraph.routes.find(
    (r: { id: string }) => r.id === body.route.id,
  )
  expect(newRoute).toBeTruthy()
  // Canonical Question id is preserved (no clone).
  expect(newRoute.tipNodeId).toBe(q3NodeId)
  // Source route lineage is unchanged.
  const sourceRouteAfter = afterGraph.routes.find(
    (r: { id: string }) => r.id === sourceRouteId,
  )
  expect(sourceRouteAfter).toBeTruthy()
  expect(sourceRouteAfter.lineageNodeIds).toEqual(beforeLineage)
  // The new route is Active and the Question is answerable.
  expect(afterGraph.activeRouteId).toBe(newRoute.id)
  await page.reload()
  await page.waitForSelector("[data-test=\"free-text\"]", { timeout: 15_000 })
})

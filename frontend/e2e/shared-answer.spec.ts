import { test, expect } from '@playwright/test'
import { buildThreeNodeLineage, closeFloatingWorkspaceWindows, createProject, fitGraph, forkFromNode } from './helpers'

/**
 * The shared-answer presentation must obey the V2 contract:
 *  - no Focus + divergent answers: each route's answer is shown
 *    independently; the UI NEVER falls back to "Active answer", "first
 *    answer", or "last answer" to fake a primary.
 *  - Focus A: the focused route's answer is shown verbatim.
 *  - Focus B: the focused route's answer is shown verbatim.
 *  - Focus route unanswered: the card shows "waiting", not a borrowed
 *    answer from another route.
 *  - Setting/clearing Focus must not change the runtime Active pointer.
 */
test('shared divergent answers render per-route; no Active/first/latest fallback', async ({ page }) => {
  await createProject(page, 'E2E Shared Answer Presentation')
  await buildThreeNodeLineage(page)
  await fitGraph(page)

  // Fork the source route from a historical node. The fork becomes the
  // second route and shares the same canonical Questions.
  await forkFromNode(page, 1, 'Route-B')
  const cards = page.locator('[data-route-id]')
  await expect(cards).toHaveCount(2)

  // Capture the route ids.
  const activeCard = cards.filter({ has: page.getByTestId('active-route') }).first()
  const activeRouteId = await activeCard.getAttribute('data-route-id')
  expect(activeRouteId).not.toBeNull()
  const otherCard = cards.filter({ hasNot: page.getByTestId('active-route') }).first()
  const otherRouteId = await otherCard.getAttribute('data-route-id')
  expect(otherRouteId).not.toBeNull()
  // Capture the active route id; we verify content directly via API and
  // the on-card text. We do not depend on a private class name.
  const activeRouteLabel = await activeCard.textContent()
  const otherRouteLabel = await otherCard.textContent()

  // Answer the SHARED historical node differently on each route. We do
  // this by activating the second route and answering again with a
  // different free text on the shared node.
  await otherCard.getByTestId('open-routes').click().catch(() => undefined)
  // Activate otherRoute so the new answer lands on the OTHER route.
  await otherCard.getByTestId('focus-route').click().catch(() => undefined)
  // Locate Route-B's card by its data-route-id and click activate.
  const otherCardById = page.locator(`[data-route-id="${otherRouteId}"]`)
  await otherCardById.getByTestId('open-routes').click().catch(() => undefined)
  // The card exposes an Activate action directly.
  await otherCardById.locator('button', { hasText: '激活' }).first().click().catch(() => undefined)
  // Fall back to the standard API activation if the button isn't visible.
  await page.evaluate(async (rid) => {
    const projectId = location.pathname.split('/projects/')[1].split(/[?#]/)[0]
    await fetch(`/api/v1/projects/${projectId}/routes/${rid}/activate`, { method: 'POST' })
  }, otherRouteId)
  await page.waitForTimeout(800)
  // Confirm the new active route is the OTHER one.
  const apiActive = await (await page.request.get(`/api/v1/projects/${page.url().split('/projects/')[1].split(/[?#]/)[0]}/active`)).json()
  expect(apiActive.activeRoute.id).toBe(otherRouteId)

  // Now answer the shared node differently on the other route. We use the
  // node card to do this; the freeText path is the route's local Answer.
  // First, find the shared historical node currently answerable on the
  // active route (Route-B). buildThreeNodeLineage left the tip of Route-A
  // (Q3) historical; the fork's tip is the shared node, so Route-B is
  // rooted at Q2 and has Q2 as the new tip-pending. We type a different
  // freeText into Route-B's Q2.
  await page.locator('[data-test="free-text"]').fill('B different answer')
  await page.getByTestId('submit-answer').click()
  await expect(page.getByText('回答已记录。')).toBeVisible()
  await page.waitForTimeout(600)

  // Switch back to Route-A so we observe shared divergent answers from
  // the original side.
  await otherCardById.getByTestId('open-routes').click().catch(() => undefined)
  await page.evaluate(async (rid) => {
    const projectId = location.pathname.split('/projects/')[1].split(/[?#]/)[0]
    await fetch(`/api/v1/projects/${projectId}/routes/${rid}/activate`, { method: 'POST' })
  }, activeRouteId)
  await page.waitForTimeout(800)

  // Clear any Focus.
  const currentActiveCard = page.locator(`[data-route-id="${activeRouteId}"]`)
  await currentActiveCard.getByTestId('open-routes').click().catch(() => undefined)
  // If the active card has a "Unfocus" button (because we left a Focus
  // earlier), click it to clear Focus.
  const unfocusBtn = currentActiveCard.locator('button', { hasText: '取消聚焦' }).first()
  if (await unfocusBtn.isVisible().catch(() => false)) {
    await unfocusBtn.click()
  }
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)

  // The shared node must show both answers distinctly (routeStates, NOT a
  // single primary borrowed from Active). We assert the DOM contains
  // both route labels and both free-text values, in any order, AND that
  // no single "primary" copy is fabricated.
  const graph = page.locator('[data-test="graph-question-node"]')
  await expect(graph).toHaveCount(3)
  const sharedNode = graph.filter({ has: page.getByTestId('reading-route-select') }).first()
  await expect(sharedNode).toBeVisible()
  // Both route labels must be present on the shared node.
  await expect(sharedNode).toContainText(activeRouteLabel || '主路线')
  await expect(sharedNode).toContainText(otherRouteLabel || '分支路线')
  // Neither answer is the only "primary" copy: the original
  // "First answer content" + "B different answer" are both listed as
  // routeStates, not collapsed into one.
  await expect(sharedNode).toContainText('First answer content')
  await expect(sharedNode).toContainText('B different answer')

  // Setting Focus to a route must NOT change Active. We pick the OTHER
  // route's focus and verify Active is still the same as before.
  const beforeActiveId = apiActive.activeRoute.id === activeRouteId ? activeRouteId : otherRouteId
  const otherRouteCard = page.locator(`[data-route-id="${otherRouteId}"]`)
  await otherRouteCard.getByTestId('open-routes').click().catch(() => undefined)
  await otherRouteCard.getByTestId('focus-route').click().catch(() => undefined)
  await page.waitForTimeout(300)
  const activeAfterFocus = await (await page.request.get(`/api/v1/projects/${page.url().split('/projects/')[1].split(/[?#]/)[0]}/active`)).json()
  expect(activeAfterFocus.activeRoute.id).toBe(beforeActiveId)
  // Close the route window again.
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)

  // Focus on otherRoute: the shared node's primary copy must now read
  // "B different answer", not the other route's "First answer content".
  const sharedAfterFocus = graph.filter({ has: page.getByTestId('reading-route-select') }).first()
  const focusReadingValue = await sharedAfterFocus.getByTestId('reading-route-select').inputValue()
  expect(focusReadingValue).toBe(otherRouteId)
  // The primary preview shows the focused route's answer.
  await expect(sharedAfterFocus).toContainText('B different answer')
  // And it must NOT silently show the OTHER route's answer.
  await expect(sharedAfterFocus).not.toContainText('First answer content')
})

/**
 * When the focused route has NOT answered the shared Question, the card
 * shows "waiting" — it does NOT borrow an answer from Active or any
 * other route. This is the core V2 invariant: shared + no Focus on the
 * answered route is honest divergence, never a hallucinated primary.
 */
test('Focus on unanswered route shows waiting, never a borrowed answer', async ({ page }) => {
  await createProject(page, 'E2E Shared Answer Focus Waiting')
  await buildThreeNodeLineage(page)
  await fitGraph(page)

  // Fork from a historical node. The fork's lineage is rooted at the
  // shared node; the new tip on the fork is the shared node itself.
  await forkFromNode(page, 1, 'Route-B')
  const cards = page.locator('[data-route-id]')
  await expect(cards).toHaveCount(2)

  const otherCard = cards.filter({ hasNot: page.getByTestId('active-route') }).first()
  const otherRouteId = await otherCard.getAttribute('data-route-id')
  expect(otherRouteId).not.toBeNull()

  // Open route navigation and click Focus on the OTHER route.
  await otherCard.getByTestId('open-routes').click().catch(() => undefined)
  await otherCard.getByTestId('focus-route').click().catch(() => undefined)
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)

  // The shared node's reading selector reflects the new Focus.
  const sharedNode = page.locator('[data-test="graph-question-node"]')
    .filter({ has: page.getByTestId('reading-route-select') })
    .first()
  await expect(sharedNode).toBeVisible()
  const reading = await sharedNode.getByTestId('reading-route-select').inputValue()
  expect(reading).toBe(otherRouteId)
  // Focused route has not answered the shared node; the card must show
  // "等待回答" / "waiting", not the other route's "First answer content".
  const cardText = await sharedNode.textContent()
  expect(cardText).toMatch(/等待|waiting|未回答/)
  expect(cardText).not.toContain('First answer content')
})

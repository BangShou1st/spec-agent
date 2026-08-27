import { test, expect } from '@playwright/test'
import { buildThreeNodeLineage, closeFloatingWorkspaceWindows, createProject, fitGraph } from './helpers'

/**
 * P1-4 E2E: a very long historical freeText answer must not produce
 * a thousand-pixel-tall graph card. The Inspector remains the place to
 * read the full content.
 *
 * We seed the answer via the API helper for determinism (the Fake model
 * gateway would otherwise respond with a short text).
 */
test('historical long freeText stays bounded on the graph card', async ({ page, request }) => {
  const longText = 'A'.repeat(2000) + ' middle break ' + 'B'.repeat(2000)
  // Use the UI helper so the workspace is fully initialized, then
  // submit the long answer through the API and verify the bounded card.
  await createProject(page, 'E2E Long Answer ' + Date.now())
  await buildThreeNodeLineage(page)
  const projectId = (await page.url()).split('/projects/')[1].split(/[?#]/)[0]
  const active = await (await request.get(`/api/v1/projects/${projectId}/active`)).json()
  const tipId = active.activeNode.id
  await request.post(`/api/v1/agent-runs`, {
    data: {
      operation: 'ANSWER_TIP',
      nodeId: tipId,
      selectedOptionId: null,
      freeText: longText,
    },
  })
  await page.reload()
  await fitGraph(page)
  await closeFloatingWorkspaceWindows(page)

  // The historical node card height must stay under a reasonable bound
  // (CSS -webkit-line-clamp: 4) so the canvas stays usable.
  const historical = page.locator('[data-test="graph-question-node"]').nth(1)
  await expect(historical).toBeVisible()
  const box = await historical.boundingBox()
  expect(box).not.toBeNull()
  // 4-line clamp + header + padding: well under 600px in any viewport.
  expect(box!.height).toBeLessThan(600)
})

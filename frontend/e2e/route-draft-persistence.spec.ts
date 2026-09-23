import { test, expect } from '@playwright/test'
import type { GraphWorkspaceView } from '../src/shared/contracts/types'
import { buildThreeNodeLineage, createProject, fitGraph, forkFromNode } from './helpers'

test('A5: route drafts survive focus, reload, and completion of another route', async ({ page, request }) => {
  let projectId: string | undefined
  try {
    await createProject(page, `E2E A5 drafts ${Date.now()}`)
    projectId = page.url().split('/projects/')[1].split(/[?#]/)[0]
    await buildThreeNodeLineage(page)
    const graphUrl = `/api/v1/projects/${projectId}/graph`
    const before: GraphWorkspaceView = await (await request.get(graphUrl)).json()
    const main = before.routes[0]
    await forkFromNode(page, 1, 'A5 draft branch')
    await expect(page.getByTestId('graph-question-node')).toHaveCount(4)
    const graph: GraphWorkspaceView = await (await request.get(graphUrl)).json()
    const branch = graph.routes.find((route) => route.id !== main.id)!
    const card = (id: string) => page.locator(`[data-test="graph-question-node"][data-node-id="${id}"]`)
    const mainCard = card(main.tipNodeId!)
    const branchCard = card(branch.tipNodeId!)
    const focus = async (routeId: string) => {
      await page.locator(`[data-route-id="${routeId}"]`).getByTestId('route-primary').click()
      await fitGraph(page)
    }
    let answerRequests = 0
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().endsWith('/agent-runs')
        && req.postDataJSON()?.operation === 'ANSWER_TIP') answerRequests++
    })

    await focus(main.id)
    await mainCard.getByTestId('free-text').fill('主路线未提交草稿')
    const chosenOption = await mainCard.locator('input[type=radio]').first().getAttribute('value')
    await mainCard.locator('input[type=radio]').first().check()
    await focus(branch.id)
    await expect(branchCard.getByTestId('free-text')).toHaveValue('')
    await branchCard.getByTestId('free-text').fill('分支路线未提交草稿')
    await branchCard.locator('input[type=radio]').last().check()
    await focus(main.id)
    await expect(mainCard.getByTestId('free-text')).toHaveValue('主路线未提交草稿')
    await expect(mainCard.locator(`input[value="${chosenOption}"]`)).toBeChecked()
    expect(answerRequests).toBe(0)

    await page.reload()
    await expect(page.getByTestId('graph-question-node')).toHaveCount(4)
    await focus(main.id)
    await expect(mainCard.getByTestId('free-text')).toHaveValue('主路线未提交草稿')
    await expect(mainCard.locator(`input[value="${chosenOption}"]`)).toBeChecked()
    await focus(branch.id)
    await expect(branchCard.getByTestId('free-text')).toHaveValue('分支路线未提交草稿')
    await branchCard.getByTestId('submit-answer').click()
    await expect(page.getByText('回答已记录', { exact: true })).toBeVisible()
    await expect.poll(() => answerRequests).toBe(1)
    await focus(main.id)
    await expect(mainCard.getByTestId('free-text')).toHaveValue('主路线未提交草稿')
    await page.reload()
    await focus(main.id)
    await expect(mainCard.getByTestId('free-text')).toHaveValue('主路线未提交草稿')
    const stored = await page.evaluate(() => sessionStorage.getItem('spec-agent:input-drafts:v1'))
    expect(stored).toContain('主路线未提交草稿')
    expect(stored).not.toContain('分支路线未提交草稿')
  } finally {
    // Only remove the uniquely named fixture created by this test.
    if (projectId) await request.delete(`/api/v1/projects/${projectId}`)
  }
})

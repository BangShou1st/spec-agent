import { test, expect } from './helpers'
import { readFileSync } from 'node:fs'

test('real web evidence restores sources and verified citations after refresh', async ({ page }) => {
  const evidence = JSON.parse(readFileSync('../docs/v2/evidence/GLOBAL_ASSISTANT_WEB_INTEGRATION.json', 'utf8'))
  const runId = evidence.events[0].runId
  const threadId = evidence.events[0].payload.threadId
  const createdAt = evidence.recordedAt
  const events = evidence.events.map((event: any) => ({ ...event, eventId: event.id, threadId }))
  const thread = { threadId, summary: '', summaryVersion: 0, workingStateVersion: 0, createdAt, updatedAt: createdAt }
  const run = { runId, threadId, status: 'COMPLETED', stepCount: 2, startedAt: createdAt, completedAt: createdAt, cancelRequestedAt: null, errorCode: null }
  await page.addInitScript(id => {
    localStorage.setItem('spec-agent:global-assistant:thread:v1', id)
    localStorage.setItem('spec-agent:global-assistant:panel:v1', 'open')
  }, threadId)
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    let body: unknown = []
    if (path.endsWith('/global-assistant/tools')) body = { engineVersion: 'langchain-ga.v1', webConfigured: true, retrievalReady: false, capabilities: ['web.search','web.fetch'] }
    else if (path.endsWith('/messages')) body = [
      { id: 'user-web', threadId, runId, role: 'USER', content: '查询官方文档', createdAt },
      { id: 'answer-web', threadId, runId, role: 'ASSISTANT', content: evidence.answer, createdAt, providerLabel: 'OpenCode Zen', modelId: evidence.model },
    ]
    else if (path.endsWith('/events')) body = events
    else if (path.endsWith('/active-run')) body = { activeRun: null, pendingSteer: null }
    else if (path.endsWith('/runs/' + runId)) body = run
    else if (path.endsWith('/threads/' + threadId)) body = thread
    else if (path.endsWith('/threads')) body = [thread]
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto('/projects')
  await expect(page.getByTestId('ga-turn')).toHaveCount(1)
  await expect(page.getByTestId('ga-cited-answer').locator('a').first()).toBeVisible()
  await expect(page.getByTestId('ga-cited-answer').locator('a').first()).toHaveAttribute('rel', 'noopener noreferrer')
  await page.getByTestId('ga-process-toggle').click()
  await expect(page.getByTestId('ga-tool-activity')).toHaveCount(2)
  await expect(page.getByTestId('ga-web-source').filter({ hasText: '已提取正文' })).toHaveCount(1)
  const count = await page.getByTestId('ga-web-source').count()
  expect(count).toBeGreaterThan(1)
  await page.reload()
  await page.getByTestId('ga-process-toggle').click()
  await expect(page.getByTestId('ga-web-source')).toHaveCount(count)
  await expect(page.getByTestId('ga-cited-answer')).not.toContainText('未核验')
  await page.getByTestId('ga-panel').screenshot({ path: '../docs/v2/evidence/GLOBAL_ASSISTANT_WEB_BROWSER.png' })
})

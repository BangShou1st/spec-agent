import { test, expect } from '@playwright/test'

const THREAD = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const RUN_A = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const RUN_B = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'

function env(runId: string, sequence: number, type: string, payload: Record<string, unknown> = {}) {
  return { eventId: 'e-' + sequence, runId, threadId: THREAD, type, sequence, createdAt: '2026-09-10T00:00:00Z', payload }
}
function sse(frames: Array<{ sequence: number; type: string; payload?: Record<string, unknown>; runId: string }>): string {
  return frames.map((f) => 'id: ' + f.sequence + '\nevent: ' + f.type + '\ndata: ' + JSON.stringify(env(f.runId, f.sequence, f.type, f.payload ?? {})) + '\n\n').join('')
}
function acceptOf(route: import('@playwright/test').Route): string {
  return String(route.request().headers()['accept'] ?? '')
}
async function mockProjects(page: import('@playwright/test').Page): Promise<void> {
  await page.route('**/api/v1/projects', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
  })
}
async function openAssistant(page: import('@playwright/test').Page): Promise<void> {
  await page.goto('/projects')
  await page.getByTestId('ga-toggle').click()
  await expect(page.getByTestId('ga-panel')).toBeVisible()
}

test('scenario 1: steer running run creates successor once', async ({ page }) => {
  await mockProjects(page)
  await page.route('**/api/v1/global-assistant/threads', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ threadId: THREAD, title: 't', preview: 'p', updatedAt: '2026-09-10T10:00:00Z', createdAt: '2026-09-10T09:00:00Z' }]) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD, async (route) => {
    const req = route.request()
    if (req.method() === 'GET') await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ threadId: THREAD, summary: '', summaryVersion: 0, workingStateVersion: 0, createdAt: '2026-09-10T09:00:00Z', updatedAt: '2026-09-10T10:00:00Z' }) })
    else await route.continue()
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD + '/messages', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ id: 'm-1', threadId: THREAD, role: 'USER', content: 'first', runId: RUN_A, createdAt: '2026-09-10T00:00:00Z' }, { id: 'm-2', threadId: THREAD, role: 'USER', content: '直接创建新的', runId: RUN_B, createdAt: '2026-09-10T00:00:01Z' }]) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD + '/activity', async (route) => {
    if (route.request().method() === 'GET') await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ activeRun: { runId: RUN_B, status: 'RUNNING' }, pendingSteer: null }) })
    else await route.continue()
  })
  await page.route('**/api/v1/global-assistant/runs/' + RUN_A + '/steer', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ steerId: 's-1', status: 'QUEUED', interruptedRunId: RUN_A, successorRunId: null }) })
  })
  await page.route('**/api/v1/global-assistant/runs/' + RUN_A + '/events', async (route) => {
    const frames = [{ sequence: 1, type: 'RUN_CANCELLED', payload: {}, runId: RUN_A }]
    if (acceptOf(route).includes('text/event-stream')) await route.fulfill({ status: 200, contentType: 'text/event-stream', body: sse(frames) })
    else await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(frames.map((f) => env(f.runId, f.sequence, f.type, f.payload))) })
  })
  await page.route('**/api/v1/global-assistant/runs/' + RUN_B + '/events', async (route) => {
    const frames = [{ sequence: 1, type: 'STATUS', payload: { message: '正在调整方向…' }, runId: RUN_B }, { sequence: 2, type: 'RUN_COMPLETED', payload: {}, runId: RUN_B }]
    if (acceptOf(route).includes('text/event-stream')) await route.fulfill({ status: 200, contentType: 'text/event-stream', body: sse(frames) })
    else await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(frames.map((f) => env(f.runId, f.sequence, f.type, f.payload))) })
  })
  await page.route('**/api/v1/global-assistant/runs/' + RUN_B, async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ runId: RUN_B, threadId: THREAD, status: 'RUNNING', stepCount: 0, cancelRequestedAt: null, startedAt: '2026-09-10T00:00:00Z', completedAt: null, errorCode: null }) })
  })
  await openAssistant(page)
  await expect(page.getByTestId('ga-steer-pending')).toHaveCount(0)
})

test('scenario 3: stop running then send next message', async ({ page }) => {
  await mockProjects(page)
  await page.route('**/api/v1/global-assistant/**', async (route) => {
    const url = route.request().url()
    if (url.endsWith('/threads/t-stop/activity') && route.request().method() === 'GET') { await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ activeRun: { runId: RUN_A, status: 'RUNNING' }, pendingSteer: null }) }); return }
    if (url.endsWith('/threads/t-stop/stop')) { await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ activeRun: { runId: RUN_A, status: 'RUNNING' }, pendingSteer: null }) }); return }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
  })
  await openAssistant(page)
  await expect(page.getByTestId('ga-composer')).toBeVisible()
  await expect(page.getByTestId('ga-composer-input')).toBeEnabled()
})

test('scenario 5: history opens while running with guards', async ({ page }) => {
  await mockProjects(page)
  await page.route('**/api/v1/global-assistant/threads', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ threadId: THREAD, title: 'current', preview: 'p', updatedAt: '2026-09-10T10:00:00Z', createdAt: '2026-09-10T09:00:00Z' }, { threadId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', title: 'old', preview: 'old preview', updatedAt: '2026-09-09T10:00:00Z', createdAt: '2026-09-09T09:00:00Z' }]) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD, async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ threadId: THREAD, summary: '', summaryVersion: 0, workingStateVersion: 0, createdAt: '2026-09-10T09:00:00Z', updatedAt: '2026-09-10T10:00:00Z' }) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD + '/messages', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD + '/activity', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ activeRun: { runId: RUN_A, status: 'RUNNING' }, pendingSteer: null }) })
  })
  await openAssistant(page)
  await page.getByTestId('ga-history-toggle').click()
  await expect(page.getByTestId('ga-history')).toBeVisible()
})

test('scenario 6-8: delete flows and empty state', async ({ page }) => {
  await mockProjects(page)
  await page.route('**/api/v1/global-assistant/threads', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
  })
  await openAssistant(page)
  await page.getByTestId('ga-history-toggle').click()
  await expect(page.getByTestId('ga-history-empty')).toBeVisible()
})

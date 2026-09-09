import { test, expect } from '@playwright/test'

const THREAD_ID = '11111111-1111-4111-8111-111111111111'
const PROJECT_ID = '22222222-2222-4222-8222-222222222222'

function sseBody(frames: Array<{ sequence: number; type: string; payload: Record<string, unknown>; runId: string }>): string {
  return frames.map((f) => {
    const envelope = { eventId: 'e-' + f.sequence, runId: f.runId, threadId: THREAD_ID, type: f.type, sequence: f.sequence, createdAt: '2026-09-10T00:00:00Z', payload: f.payload }
    return 'id: ' + f.sequence + '\nevent: ' + f.type + '\ndata: ' + JSON.stringify(envelope) + '\n\n'
  }).join('')
}

function acceptOf(route: import('@playwright/test').Route): string {
  return String(route.request().headers()['accept'] ?? '')
}

async function mockProjects(page: import('@playwright/test').Page): Promise<void> {
  await page.route('**/api/v1/projects', async (route) => {
    const req = route.request()
    if (req.method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ id: PROJECT_ID, title: 'E2E Project', activeRouteId: null, createdAt: '2026-09-10T00:00:00Z', updatedAt: '2026-09-10T00:00:00Z' }]) })
    } else {
      await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: PROJECT_ID, title: 'E2E Project', activeRouteId: null, defaultProfileId: null, createdAt: '2026-09-10T00:00:00Z', updatedAt: '2026-09-10T00:00:00Z' }) })
    }
  })
}

async function mockAssistantHappy(page: import('@playwright/test').Page, runId: string): Promise<void> {
  await page.route('**/api/v1/global-assistant/threads', async (route) => {
    await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ threadId: THREAD_ID }) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD_ID, async (route) => {
    const req = route.request()
    if (req.method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ threadId: THREAD_ID, summary: '', summaryVersion: 1, workingStateVersion: 1, createdAt: '2026-09-10T00:00:00Z', updatedAt: '2026-09-10T00:00:00Z' }) })
    } else { await route.continue() }
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD_ID + '/messages', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ id: 'm-u1', threadId: THREAD_ID, role: 'USER', content: '找 AI 邮件项目', runId, createdAt: '2026-09-10T00:00:00Z' }, { id: 'm-a1', threadId: THREAD_ID, role: 'ASSISTANT', content: '找到 2 个候选，已为你定位。', runId, createdAt: '2026-09-10T00:00:01Z' }]) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD_ID + '/runs', async (route) => {
    await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ runId, status: 'RUNNING' }) })
  })
  await page.route('**/api/v1/global-assistant/runs/' + runId, async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ runId, threadId: THREAD_ID, status: 'RUNNING', stepCount: 1, cancelRequestedAt: null, startedAt: '2026-09-10T00:00:00Z', completedAt: null, errorCode: null }) })
  })
  await page.route('**/api/v1/global-assistant/runs/' + runId + '/events', async (route) => {
    const accept = acceptOf(route)
    const frames = [
      { sequence: 1, type: 'RUN_STARTED', payload: { threadId: THREAD_ID }, runId },
      { sequence: 2, type: 'STATUS', payload: { message: '正在查找项目…' }, runId },
      { sequence: 3, type: 'TOOL_STARTED', payload: { capabilityId: 'project.search', arguments: { query: 'AI 邮件' } }, runId },
      { sequence: 4, type: 'TOOL_COMPLETED', payload: { capabilityId: 'project.search', summary: '找到 2 个候选' }, runId },
      { sequence: 5, type: 'ASSISTANT_DELTA', payload: { text: '找到 2 个候选，已为你定位。' }, runId },
      { sequence: 6, type: 'RUN_COMPLETED', payload: {}, runId },
    ]
    if (accept.includes('text/event-stream')) {
      await route.fulfill({ status: 200, contentType: 'text/event-stream', body: sseBody(frames) })
    } else {
      const envelopes = frames.map((f) => ({ eventId: 'e-' + f.sequence, runId: f.runId, threadId: THREAD_ID, type: f.type, sequence: f.sequence, createdAt: '2026-09-10T00:00:00Z', payload: f.payload }))
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(envelopes) })
    }
  })
  await page.route('**/api/v1/global-assistant/runs/' + runId + '/cancel', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ runId, threadId: THREAD_ID, status: 'CANCELLED', stepCount: 1, cancelRequestedAt: '2026-09-10T00:00:00Z', startedAt: '2026-09-10T00:00:00Z', completedAt: '2026-09-10T00:00:00Z', errorCode: null }) })
  })
}

test('open assistant from projects, first thread, submit and see ordered run timeline', async ({ page }) => {
  await mockProjects(page)
  await mockAssistantHappy(page, '33333333-3333-4333-8333-333333333333')
  await page.goto('/projects')
  await expect(page.getByTestId('ga-toggle')).toBeVisible()
  await page.getByTestId('ga-toggle').click()
  await expect(page.getByTestId('ga-panel')).toBeVisible()
  await expect(page.getByTestId('ga-empty')).toBeVisible()
  await page.getByTestId('ga-composer-input').fill('找 AI 邮件项目')
  await page.getByTestId('ga-send').click()
  await expect(page.getByTestId('ga-tool-activity').first()).toBeVisible()
  await expect(page.getByText('找到 2 个候选').first()).toBeVisible()
  const timelineText = await page.getByTestId('ga-timeline').innerText()
  expect(timelineText.indexOf('找 AI 邮件项目')).toBeLessThan(timelineText.indexOf('搜索项目'))
})

test('clarification keeps composer usable and reuses the same thread', async ({ page }) => {
  await mockProjects(page)
  const runId = '44444444-4444-4444-8444-444444444444'
  let runCount = 0
  await page.route('**/api/v1/global-assistant/threads', async (route) => {
    await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ threadId: THREAD_ID }) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD_ID, async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ threadId: THREAD_ID, summary: '', summaryVersion: 1, workingStateVersion: 1, createdAt: '2026-09-10T00:00:00Z', updatedAt: '2026-09-10T00:00:00Z' }) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD_ID + '/messages', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD_ID + '/runs', async (route) => {
    runCount += 1
    await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ runId, status: 'RUNNING' }) })
  })
  await page.route('**/api/v1/global-assistant/runs/**', async (route) => {
    const url = route.request().url()
    const accept = acceptOf(route)
    if (url.endsWith('/cancel')) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ runId, threadId: THREAD_ID, status: 'CANCELLED', stepCount: 1, cancelRequestedAt: '2026-09-10T00:00:00Z', startedAt: '2026-09-10T00:00:00Z', completedAt: '2026-09-10T00:00:00Z', errorCode: null }) })
      return
    }
    if (url.endsWith('/events')) {
      const frames = [{ sequence: 1, type: 'USER_INPUT_REQUIRED', payload: { question: '你指的是哪个项目？请补充说明。' }, runId }, { sequence: 2, type: 'RUN_COMPLETED', payload: {}, runId }]
      if (accept.includes('text/event-stream')) {
        await route.fulfill({ status: 200, contentType: 'text/event-stream', body: sseBody(frames) })
      } else {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(frames.map((f) => ({ eventId: 'e-1', runId: f.runId, threadId: THREAD_ID, type: f.type, sequence: f.sequence, createdAt: '2026-09-10T00:00:00Z', payload: f.payload }))) })
      }
      return
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ runId, threadId: THREAD_ID, status: 'RUNNING', stepCount: 1, cancelRequestedAt: null, startedAt: '2026-09-10T00:00:00Z', completedAt: null, errorCode: null }) })
  })
  await page.goto('/projects')
  await page.getByTestId('ga-toggle').click()
  await page.getByTestId('ga-composer-input').fill('找项目')
  await page.getByTestId('ga-send').click()
  await expect(page.getByTestId('ga-clarification')).toContainText('哪个项目')
  await expect(page.getByTestId('ga-composer-input')).toBeEnabled()
  expect(runCount).toBe(1)
  await page.getByTestId('ga-composer-input').fill('AI 邮件那个')
  await page.getByTestId('ga-send').click()
  await expect.poll(() => runCount, { timeout: 10000 }).toBe(2)
})

test('ui action navigates to project, failure is friendly and replay has no duplicates', async ({ page }) => {
  await mockProjects(page)
  const runId = '55555555-5555-4555-8555-555555555555'
  await page.route('**/api/v1/global-assistant/**', async (route) => {
    const url = route.request().url()
    const method = route.request().method()
    const accept = acceptOf(route)
    if (method === 'POST' && url.endsWith('/threads')) {
      await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ threadId: THREAD_ID }) })
      return
    }
    if (url.includes('/threads/') && url.endsWith('/messages')) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
      return
    }
    if (url.includes('/threads/') && method === 'GET' && !url.includes('/runs')) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ threadId: THREAD_ID, summary: '', summaryVersion: 1, workingStateVersion: 1, createdAt: '2026-09-10T00:00:00Z', updatedAt: '2026-09-10T00:00:00Z' }) })
      return
    }
    if (url.includes('/threads/') && url.endsWith('/runs')) {
      await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ runId, status: 'RUNNING' }) })
      return
    }
    if (url.endsWith('/cancel')) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ runId, threadId: THREAD_ID, status: 'CANCELLED', stepCount: 1, cancelRequestedAt: '2026-09-10T00:00:00Z', startedAt: '2026-09-10T00:00:00Z', completedAt: '2026-09-10T00:00:00Z', errorCode: null }) })
      return
    }
    if (url.endsWith('/events')) {
      const frames = [
        { sequence: 1, type: 'RUN_STARTED', payload: { threadId: THREAD_ID }, runId },
        { sequence: 2, type: 'TOOL_STARTED', payload: { capabilityId: 'project.search', arguments: { query: 'AI' } }, runId },
        { sequence: 3, type: 'TOOL_COMPLETED', payload: { capabilityId: 'project.search', summary: '找到 1 个候选' }, runId },
        { sequence: 4, type: 'UI_ACTION', payload: { destination: 'PROJECT', resourceId: PROJECT_ID }, runId },
        { sequence: 5, type: 'RUN_FAILED', payload: { errorCode: 'MODEL_UNAVAILABLE', reason: 'busy' }, runId },
      ]
      if (accept.includes('text/event-stream')) {
        await route.fulfill({ status: 200, contentType: 'text/event-stream', body: sseBody(frames) + sseBody(frames) })
      } else {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(frames.map((f) => ({ eventId: 'e-' + f.sequence, runId: f.runId, threadId: THREAD_ID, type: f.type, sequence: f.sequence, createdAt: '2026-09-10T00:00:00Z', payload: f.payload }))) })
      }
      return
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ runId, threadId: THREAD_ID, status: 'RUNNING', stepCount: 1, cancelRequestedAt: null, startedAt: '2026-09-10T00:00:00Z', completedAt: null, errorCode: null }) })
  })
  await page.goto('/projects')
  await page.getByTestId('ga-toggle').click()
  await page.getByTestId('ga-composer-input').fill('打开项目')
  await page.getByTestId('ga-send').click()
  await expect(page).toHaveURL(new RegExp(PROJECT_ID))
  await expect(page.getByTestId('ga-panel')).toBeVisible()
  await expect(page.getByTestId('ga-error')).toContainText('模型服务暂时不可用')
  await expect(page.getByTestId('ga-tool-activity')).toHaveCount(1)
})

test('refresh restores thread, double submit prevented, run-active handled, settings still work', async ({ page }) => {
  await mockProjects(page)
  const runId = '66666666-6666-4666-8666-666666666666'
  let createRunCalls = 0
  await page.route('**/api/v1/global-assistant/threads', async (route) => {
    await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ threadId: THREAD_ID }) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD_ID, async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ threadId: THREAD_ID, summary: '', summaryVersion: 1, workingStateVersion: 1, createdAt: '2026-09-10T00:00:00Z', updatedAt: '2026-09-10T00:00:00Z' }) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD_ID + '/messages', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ id: 'm-1', threadId: THREAD_ID, role: 'USER', content: 'hi', runId: null, createdAt: '2026-09-10T00:00:00Z' }]) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD_ID + '/runs', async (route) => {
    createRunCalls += 1
    if (createRunCalls > 1) {
      await route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ code: 'GLOBAL_ASSISTANT_RUN_ACTIVE', message: 'Thread already hosts an active run' }) })
      return
    }
    await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ runId, status: 'RUNNING' }) })
  })
  await page.route('**/api/v1/global-assistant/runs/' + runId, async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ runId, threadId: THREAD_ID, status: 'RUNNING', stepCount: 1, cancelRequestedAt: null, startedAt: '2026-09-10T00:00:00Z', completedAt: null, errorCode: null }) })
  })
  await page.route('**/api/v1/global-assistant/runs/' + runId + '/events', async (route) => {
    const accept = acceptOf(route)
    if (accept.includes('text/event-stream')) {
      await new Promise((resolve) => setTimeout(resolve, 1500))
      await route.fulfill({ status: 200, contentType: 'text/event-stream', body: sseBody([{ sequence: 1, type: 'STATUS', payload: { message: '正在处理…' }, runId }]) })
    } else {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
    }
  })
  await page.goto('/projects')
  await page.getByTestId('ga-toggle').click()
  await page.getByTestId('ga-composer-input').fill('第一个请求')
  await page.getByTestId('ga-send').click()
  await expect(page.getByTestId('ga-status')).toBeVisible()
  await expect.poll(() => createRunCalls, { timeout: 10000 }).toBe(1)
  await page.getByTestId('ga-composer-input').fill('第二个请求')
  await page.getByTestId('ga-composer-input').press('Enter')
  await page.waitForTimeout(800)
  expect(createRunCalls).toBe(1)
  await expect(page.getByTestId('ga-stop')).toBeVisible()
  await page.reload()
  if ((await page.getByTestId('ga-panel').count()) === 0) {
    await page.getByTestId('ga-toggle').click()
  }
  await expect(page.getByTestId('ga-panel')).toBeVisible()
  await expect(page.getByTestId('ga-message')).toContainText('hi')
  await page.goto('/settings/skills')
  await expect(page.getByTestId('settings-nav')).toBeVisible()
  await expect(page.getByTestId('settings-nav')).toContainText('Connections')
  await expect(page.getByTestId('ga-panel')).toBeVisible()
})

test('cancel stops the active run without implying rollback', async ({ page }) => {
  await mockProjects(page)
  const runId = '77777777-7777-4777-8777-777777777777'
  let cancelCalls = 0
  let cancelled = false
  await page.route('**/api/v1/global-assistant/threads', async (route) => {
    await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ threadId: THREAD_ID }) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD_ID, async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ threadId: THREAD_ID, summary: '', summaryVersion: 1, workingStateVersion: 1, createdAt: '2026-09-10T00:00:00Z', updatedAt: '2026-09-10T00:00:00Z' }) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD_ID + '/messages', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD_ID + '/runs', async (route) => {
    await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ runId, status: 'RUNNING' }) })
  })
  await page.route('**/api/v1/global-assistant/runs/' + runId, async (route) => {
    const status = cancelled ? 'CANCELLED' : 'RUNNING'
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ runId, threadId: THREAD_ID, status, stepCount: 1, cancelRequestedAt: cancelled ? '2026-09-10T00:00:00Z' : null, startedAt: '2026-09-10T00:00:00Z', completedAt: null, errorCode: null }) })
  })
  await page.route('**/api/v1/global-assistant/runs/' + runId + '/events', async (route) => {
    const accept = acceptOf(route)
    if (accept.includes('text/event-stream')) {
      await route.fulfill({ status: 200, contentType: 'text/event-stream', body: sseBody([{ sequence: 1, type: 'STATUS', payload: { message: '正在处理…' }, runId }]) })
    } else if (cancelled) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ eventId: 'e-9', runId, threadId: THREAD_ID, type: 'RUN_CANCELLED', sequence: 9, createdAt: '2026-09-10T00:00:00Z', payload: {} }]) })
    } else {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
    }
  })
  await page.route('**/api/v1/global-assistant/runs/' + runId + '/cancel', async (route) => {
    cancelCalls += 1
    cancelled = true
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ runId, threadId: THREAD_ID, status: 'RUNNING', stepCount: 1, cancelRequestedAt: '2026-09-10T00:00:00Z', startedAt: '2026-09-10T00:00:00Z', completedAt: null, errorCode: null }) })
  })
  await page.goto('/projects')
  await page.getByTestId('ga-toggle').click()
  await page.getByTestId('ga-composer-input').fill('一个耗时的请求')
  await page.getByTestId('ga-send').click()
  await expect(page.getByTestId('ga-stop')).toBeVisible()
  await page.getByTestId('ga-stop').click()
  await expect(page.getByTestId('ga-send')).toBeVisible({ timeout: 20000 })
  expect(cancelCalls).toBe(1)
})

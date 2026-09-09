import { test, expect } from '@playwright/test'

const THREAD_A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const THREAD_B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const THREAD_MAIN = '11111111-1111-4111-8111-111111111111'
const PROJECT_ID = '22222222-2222-4222-8222-222222222222'

async function mockProjects(page: import('@playwright/test').Page): Promise<void> {
  await page.route('**/api/v1/projects', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ id: PROJECT_ID, title: 'E2E Project', activeRouteId: null, createdAt: '2026-09-10T00:00:00Z', updatedAt: '2026-09-10T00:00:00Z' }]) })
  })
}

test('library lists switches and highlights current', async ({ page }) => {
  await mockProjects(page)
  const listBody = [
    { threadId: THREAD_B, title: 'LB Second Title', preview: 'LB Second Preview', updatedAt: '2026-09-10T10:00:00Z', createdAt: '2026-09-10T09:00:00Z' },
    { threadId: THREAD_A, title: 'LB First Title', preview: 'LB First Preview', updatedAt: '2026-09-09T10:00:00Z', createdAt: '2026-09-09T09:00:00Z' },
  ]
  await page.route('**/api/v1/global-assistant/threads', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(listBody) })
    } else {
      await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ threadId: THREAD_A }) })
    }
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD_A, async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ threadId: THREAD_A, summary: '', summaryVersion: 0, workingStateVersion: 0, createdAt: '2026-09-09T09:00:00Z', updatedAt: '2026-09-09T10:00:00Z' }) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD_A + '/messages', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ id: 'm-a1', threadId: THREAD_A, role: 'USER', content: 'LB First Question', runId: null, createdAt: '2026-09-09T10:00:00Z' }]) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD_B, async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ threadId: THREAD_B, summary: '', summaryVersion: 0, workingStateVersion: 0, createdAt: '2026-09-10T09:00:00Z', updatedAt: '2026-09-10T10:00:00Z' }) })
  })
  await page.route('**/api/v1/global-assistant/threads/' + THREAD_B + '/messages', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ id: 'm-b1', threadId: THREAD_B, role: 'USER', content: 'LB Second Question', runId: null, createdAt: '2026-09-10T10:00:00Z' }]) })
  })
  await page.goto('/projects')
  await page.getByTestId('ga-toggle').click()
  await expect(page.getByTestId('ga-panel')).toBeVisible()
  await page.getByTestId('ga-history-toggle').click()
  await expect(page.getByTestId('ga-history')).toBeVisible()
  await expect(page.getByTestId('ga-history-item')).toHaveCount(2)
  await page.getByTestId('ga-history-item').filter({ hasText: 'LB First Title' }).click()
  await expect(page.getByTestId('ga-timeline')).toContainText('LB First Question')
  await expect(page.getByTestId('ga-current-conversation')).toContainText('LB First Title')
  await page.getByTestId('ga-history-toggle').click()
  await expect(page.locator('[data-test="ga-history-item"][data-current="true"]')).toContainText('LB First Title')
  await page.getByTestId('ga-history-item').filter({ hasText: 'LB Second Title' }).click()
  await expect(page.getByTestId('ga-timeline')).toContainText('LB Second Question')
})

import { test, expect } from './helpers'

test('daily stack proxy and missing web credential notice are real', async ({ page, request }) => {
  test.skip(process.env.SPEC_AGENT_GA_STARTUP_TEST !== 'true', 'Explicit local daily startup verification')
  const response = await request.get('/api/v1/global-assistant/tools')
  expect(response.ok()).toBeTruthy()
  const status = await response.json()
  expect(status.engineVersion).toBe('langchain-ga.v1')
  expect(status.webConfigured).toBe(false)
  expect(status.retrievalReady).toBe(true)
  expect(status.capabilities).not.toContain('web.search')
  await page.addInitScript(() => localStorage.setItem('spec-agent:global-assistant:panel:v1', 'open'))
  await page.goto('/projects')
  await expect(page.getByTestId('ga-web-unconfigured')).toContainText('联网未配置')
  await expect(page.getByTestId('ga-retrieval-unready')).toHaveCount(0)
  await page.getByTestId('ga-panel').screenshot({ path: '../docs/v2/evidence/GLOBAL_ASSISTANT_DAILY_STARTUP.png' })
})

import { test, expect } from './helpers'

for (const [status, text] of [
  ['OLLAMA_UNAVAILABLE', 'qwen3-embedding:0.6b'],
  ['STORE_UNAVAILABLE', '共享令牌'],
  ['HELP_INDEX_NOT_READY', '帮助索引正在准备'],
]) {
  test('controlled readiness notice: ' + status, async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('spec-agent:global-assistant:panel:v1', 'open'))
    await page.route('**/api/v1/global-assistant/tools', route => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({ engineVersion: 'langchain-ga.v1', webConfigured: false, retrievalReady: false, retrievalStatus: status, capabilities: [] }),
    }))
    await page.route('**/api/v1/global-assistant/threads**', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(route.request().method() === 'POST' ? { threadId: '11111111-1111-4111-8111-111111111111' } : []) }))
    await page.goto('/projects')
    await expect(page.getByTestId('ga-web-unconfigured')).toContainText('联网未配置')
    await expect(page.getByTestId('ga-retrieval-unready')).toContainText(text)
  })
}

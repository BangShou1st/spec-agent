import { test, expect } from '@playwright/test'

test('model settings probes, selects, and saves without exposing the key', async ({ page }) => {
  // 更具体的路由必须先注册：已保存密钥的模型列表与设置本体是两个端点。
  await page.route('**/api/v1/settings/opencode/models', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ allModels: ['beta-free'], freeModels: ['beta-free'] }),
    })
  })
  await page.route('**/api/v1/settings/opencode**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ configured: false, maskedKey: null, selectedModel: null }) })
      return
    }
    if (route.request().method() === 'POST') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ allModels: ['alpha-free', 'beta-free'], freeModels: ['alpha-free', 'beta-free'] }) })
      return
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ configured: true, maskedKey: '••••1234', selectedModel: 'beta-free' }) })
  })

  await page.goto('/settings')
  await expect(page.getByTestId('settings-page')).toBeVisible()
  await page.getByTestId('opencode-api-key').fill('ui-only-secret')
  await page.getByTestId('opencode-probe').click()
  await expect(page.getByTestId('opencode-model')).toBeEnabled()
  // 保存并测试必须显式选择模型。
  await expect(page.getByTestId('opencode-save-test')).toBeDisabled()

  await page.getByTestId('opencode-model').selectOption('beta-free')
  await page.getByTestId('opencode-save-test').click()
  await expect(page.getByTestId('opencode-masked')).toContainText('••••1234')
  await expect(page.getByTestId('opencode-api-key')).toHaveCount(0)
  await expect(page.getByText('ui-only-secret')).toHaveCount(0)
})

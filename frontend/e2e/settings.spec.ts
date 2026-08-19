import { test, expect } from '@playwright/test'

test('model settings probes, selects, and saves without exposing the key', async ({ page }) => {
  await page.route('**/api/v1/settings/opencode**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ configured: false, maskedKey: null, selectedModel: null }) })
      return
    }
    if (route.request().method() === 'POST') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ freeModels: ['alpha-free', 'beta-free'] }) })
      return
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ configured: true, maskedKey: '••••1234', selectedModel: 'beta-free' }) })
  })

  await page.goto('/settings')
  await expect(page.getByTestId('settings-page')).toBeVisible()
  await page.getByTestId('opencode-api-key').fill('ui-only-secret')
  await page.getByTestId('probe-opencode').click()
  await expect(page.getByTestId('opencode-model')).toBeEnabled()
  await expect(page.getByTestId('save-opencode')).toBeDisabled()

  await page.getByTestId('opencode-model').selectOption('beta-free')
  await page.getByTestId('save-opencode').click()
  await expect(page.getByTestId('masked-key')).toContainText('••••1234')
  await expect(page.getByTestId('opencode-api-key')).toHaveValue('')
  await expect(page.getByText('ui-only-secret')).toHaveCount(0)
})

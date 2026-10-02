import { test, expect } from './helpers'

// Fresh Java JVM, persisted legacy/GA history and interrupted ledger; no API mocks.
test('migration history and startup recovery restore honest turn state', async ({ page }) => {
  test.skip(!process.env.SPEC_AGENT_GA_RECOVERY_THREAD, 'Requires isolated restart integration')
  await page.addInitScript(thread => {
    localStorage.setItem('spec-agent:global-assistant:thread:v1', thread)
    localStorage.setItem('spec-agent:global-assistant:panel:v1', 'open')
  }, process.env.SPEC_AGENT_GA_RECOVERY_THREAD!)
  await page.goto('/projects')
  await expect(page.getByTestId('ga-panel')).toBeVisible()
  await expect(page.getByTestId('ga-turn')).toHaveCount(3)
  await expect(page.getByTestId('ga-timeline')).toContainText('迁移前旧引擎回答')
  await expect(page.getByTestId('ga-timeline')).toContainText('新引擎已完成回答')
  const interrupted = page.locator(`[data-run-id="${process.env.SPEC_AGENT_GA_RECOVERY_RUN}"]`)
  await interrupted.getByTestId('ga-process-toggle').click()
  await expect(interrupted.getByTestId('ga-tool-activity')).toHaveCount(1)
  await expect(interrupted.getByTestId('ga-tool-activity')).toHaveAttribute('data-state', 'interrupted')
  await expect(page.getByTestId('ga-status')).toHaveCount(0)
  await expect(page.getByTestId('ga-streaming')).toHaveCount(0)
  await page.reload()
  await expect(page.getByTestId('ga-turn')).toHaveCount(3)
  await expect(page.getByTestId('ga-timeline')).not.toContainText('进行中')
  await page.getByTestId('ga-panel').screenshot({ path: process.env.SPEC_AGENT_GA_RECOVERY_SCREENSHOT ?? 'test-results/ga-recovery.png' })
})

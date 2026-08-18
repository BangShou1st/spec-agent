import { test, expect } from '@playwright/test'
import { createProject, draftFirstQuestion } from './helpers'

/**
 * Route lifecycle UI: archive active route → no active route; restore
 * archived route → OPEN + ACTIVE; soft-delete a route → DELETED and still
 * recoverable; restore deleted route → OPEN + ACTIVE. Every transition goes
 * through the backend command API after an explicit confirmation.
 */
test('archive, restore, and soft-delete a route through the lifecycle UI', async ({ page }) => {
  await createProject(page, 'E2E Lifecycle Flow')
  await draftFirstQuestion(page)

  // Archive requires confirmation.
  await page.getByTestId('archive-route').click()
  await expect(page.getByTestId('confirm-route-action-dialog')).toBeVisible()
  await page.getByTestId('confirm-route-action').click()
  await expect(page.getByTestId('lifecycle-badge')).toHaveText('Archived')
  await expect(page.getByText('No active route').first()).toBeVisible()

  // Restore the archived route → OPEN + ACTIVE (expand the collapsed section
  // so the archived route's actions are visible).
  await page.locator('[data-test="archived-deleted-section"] summary').click()
  await page.getByTestId('restore-route').click()
  await expect(page.getByTestId('lifecycle-badge')).toHaveText('Open')
  await expect(page.getByTestId('active-route')).toBeVisible()
  await expect(page.getByTestId('question')).toBeVisible()

  // Soft-delete requires confirmation and preserves the historical route.
  await page.getByTestId('delete-route').click()
  await expect(page.getByTestId('confirm-route-action-dialog')).toBeVisible()
  await expect(page.getByTestId('confirm-description')).toContainText('soft-delete')
  await page.getByTestId('confirm-route-action').click()
  await expect(page.getByTestId('lifecycle-badge')).toHaveText('Deleted')
  await expect(page.getByText('No active route').first()).toBeVisible()

  // Restore the deleted route → OPEN + ACTIVE again.
  await page.locator('[data-test="archived-deleted-section"] summary').click()
  await page.getByTestId('restore-route').click()
  await expect(page.getByTestId('lifecycle-badge')).toHaveText('Open')
  await expect(page.getByTestId('active-route')).toBeVisible()
  await expect(page.getByTestId('question')).toBeVisible()
})
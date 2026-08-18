import { test, expect } from '@playwright/test'
import {
  FAKE_ROOT_QUESTION,
  answerActiveNode,
  createProject,
  draftFirstQuestion,
} from './helpers'

/**
 * Core clarification flow: create project → open workspace → draft question →
 * answer → next node appears → requirement state updates. Reconfirms the
 * Phase 7.1 flow after the Phase 7.2 workspace changes.
 */
test('core clarification loop works end to end', async ({ page }) => {
  await createProject(page, 'E2E Core Flow')

  // Three-panel workspace is visible.
  await expect(page.getByText('Route Workspace')).toBeVisible()
  await expect(page.getByText('Clarification')).toBeVisible()
  await expect(page.getByTestId('tab-requirement')).toBeVisible()

  // Draft the first question; this is never automatic.
  await draftFirstQuestion(page)
  await expect(page.getByTestId('question')).toHaveText(FAKE_ROOT_QUESTION)

  // Answer it; the next node appears and requirement state updates.
  await answerActiveNode(page, 'The product must let one user clarify one requirement.')
  await expect(page.getByTestId('question')).toBeVisible()
  await expect(page.getByTestId('free-text')).toHaveValue('')
  await expect(page.getByText('The user clarified the main outcome.')).toBeVisible()
  await expect(page.getByText('The user must confirm scope boundaries.')).toBeVisible()

  // No error banner is shown.
  await expect(page.locator('.error-banner')).toHaveCount(0)
})
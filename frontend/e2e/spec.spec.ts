import { test, expect } from '@playwright/test'
import { answerActiveNode, createProject, draftFirstQuestion } from './helpers'

/**
 * Spec flow: on a route with clarified state, generate a spec snapshot for the
 * ACTIVE route. The snapshot appears, sections render, source references
 * render, unresolved items render, the snapshot is labeled derived, and the
 * route snapshot history contains the new snapshot. Fake gateway only — no
 * real model.
 */
test('generate and inspect a derived spec snapshot', async ({ page }) => {
  await createProject(page, 'E2E Spec Flow')
  await draftFirstQuestion(page)
  await answerActiveNode(page, 'Spec-worthy answer content')

  // Switch to the Spec Snapshots tab; generation targets the ACTIVE route.
  await page.getByTestId('tab-spec').click()
  await expect(page.getByTestId('generate-spec')).toBeVisible()
  await expect(page.getByText('Generate spec for active route')).toBeVisible()

  await page.getByTestId('generate-spec').click()

  // The generated snapshot is selected and rendered as a derived artifact.
  await expect(page.getByTestId('spec-snapshot-detail')).toBeVisible()
  await expect(page.getByTestId('derived-label')).toContainText('derived')

  // Sections render faithfully.
  await expect(page.getByText('Overview')).toBeVisible()
  await expect(page.getByText('The clarified requirement outcome.')).toBeVisible()

  // Unresolved items render.
  await expect(
    page.getByText('The user must confirm the primary outcome before final grounding.'),
  ).toBeVisible()

  // Source references display without invented descriptions.
  const sourceRef = page.getByTestId('source-reference').first()
  await expect(sourceRef).toContainText('context:')

  // The route snapshot history contains the new snapshot.
  await expect(page.getByTestId('spec-snapshot-item')).toHaveCount(1)
})
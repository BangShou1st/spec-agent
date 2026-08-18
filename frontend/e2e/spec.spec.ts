import { test, expect } from '@playwright/test'
import {
  answerActiveNode,
  buildThreeNodeLineage,
  createProject,
  draftFirstQuestion,
  selectHistoricalNode,
} from './helpers'

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

/**
 * Cross-route generation: the user browses a NON-active route's snapshot
 * history while the ACTIVE route differs, then generates a spec for the active
 * route. The UI must switch selection to the route that owns the new snapshot
 * and display it, instead of keeping the stale selected route's snapshot.
 */
test('cross-route generation selects and shows the active route new snapshot', async ({ page }) => {
  await createProject(page, 'E2E Spec Cross-Route')
  await buildThreeNodeLineage(page)

  // Fork from the answered child: the fork becomes the ACTIVE route (B), the
  // original route (A) stays non-active. Both are open and visible.
  await selectHistoricalNode(page, 1)
  await page.getByTestId('fork-from-here').click()
  await page.getByTestId('fork-label').fill('Forked alternative')
  await page.getByTestId('fork-submit').click()
  await expect(page.getByTestId('route-card')).toHaveCount(2)
  await expect(page.getByTestId('active-route')).toHaveCount(1)

  // Select the NON-active route A to browse its (empty) snapshot history.
  const nonActiveCard = page
    .locator('[data-test="route-card"]')
    .filter({ hasNot: page.getByTestId('active-route') })
    .first()
  await nonActiveCard.getByTestId('select-route').click()
  await expect(page.locator('[data-test="route-card"].selected')).toHaveCount(1)
  await expect(page.locator('[data-test="route-card"].selected').getByTestId('active-route')).toHaveCount(0)

  // Open Spec Snapshots: still showing route A, no snapshots generated yet.
  await page.getByTestId('tab-spec').click()
  await expect(page.getByTestId('generate-spec')).toBeVisible()

  // Generate targets the ACTIVE route B.
  await page.getByTestId('generate-spec').click()

  // The UI switches: the selected route card is now the ACTIVE route...
  await expect(page.locator('[data-test="route-card"].selected')).toHaveCount(1)
  await expect(page.locator('[data-test="route-card"].selected').getByTestId('active-route')).toHaveCount(1)

  // ...and the panel shows the new snapshot owned by that route.
  await expect(page.getByTestId('spec-snapshot-detail')).toBeVisible()
  await expect(page.getByTestId('spec-provenance')).toContainText('route:')
  await expect(page.getByTestId('spec-snapshot-item')).toHaveCount(1)
})
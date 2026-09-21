import { test, expect } from '@playwright/test'
import {
  createProject,
  draftFirstQuestion,
  answerActiveNode,
  fitGraph,
  forkFromNode,
} from './helpers'

/**
 * Historical answer recovery E2E: verify the answer lifecycle through
 * the graph workspace with inherited answers and spec generation.
 *
 * Uses real frontend, real backend, test database, fake model gateway.
 * The fake model completes STATE_UPDATE automatically and generates
 * generic specs. We verify the flow works correctly, not the spec content.
 */

test.describe('Historical answer recovery', () => {
  test('branch inherits answer and generates spec successfully', async ({
    page,
  }) => {
    // ── Step 1: Create project ──
    const projectName = `E2E-History-${Date.now()}`
    await createProject(page, projectName)

    // ── Step 2: Draft and answer ──
    await draftFirstQuestion(page)
    await answerActiveNode(
      page,
      '主要目标是提升会议效率。会议不超过45分钟，确保每次会议都有明确议程。',
    )

    // ── Step 3: Fork to create branch ──
    await fitGraph(page)
    await forkFromNode(page, 0, '分支路线')

    // Verify fork created
    await expect(page.locator('[data-route-id]')).toHaveCount(2)

    // ── Step 4: Generate spec on branch ──
    const dock = page.getByTestId('spec-dock')
    await expect(dock).toBeVisible()
    await page.getByTestId('spec-dock-toggle').click()
    await expect(dock).toHaveAttribute('data-state', 'expanded')

    await page.getByTestId('generate-spec').click()

    // ── Step 5: Verify spec generated ──
    await expect(page.getByTestId('spec-snapshot-detail')).toBeVisible({
      timeout: 30_000,
    })

    // Verify spec has content (fake model generates generic content)
    const specContent = await page.getByTestId('spec-snapshot-detail').textContent()
    expect(specContent).toBeTruthy()
    expect(specContent!.length).toBeGreaterThan(50)

    // Verify no error banner
    await expect(page.locator('.error-banner')).toHaveCount(0)

    // Verify spec has derived label
    await expect(page.getByText('派生产物')).toBeVisible()
  })

  test('multiple routes can generate specs independently', async ({
    page,
  }) => {
    // ── Create project with multiple routes ──
    const projectName = `E2E-MultiRoute-${Date.now()}`
    await createProject(page, projectName)

    await draftFirstQuestion(page)
    await answerActiveNode(page, 'First answer')

    // Fork to create second route
    await fitGraph(page)
    await forkFromNode(page, 0, 'Route B')

    // Generate spec on Route B
    const dock = page.getByTestId('spec-dock')
    await page.getByTestId('spec-dock-toggle').click()
    await expect(dock).toHaveAttribute('data-state', 'expanded')

    await page.getByTestId('generate-spec').click()
    await expect(page.getByTestId('spec-snapshot-detail')).toBeVisible({
      timeout: 30_000,
    })

    // Verify no error
    await expect(page.locator('.error-banner')).toHaveCount(0)

    // Verify spec content exists
    const specContent = await page.getByTestId('spec-snapshot-detail').textContent()
    expect(specContent).toBeTruthy()
    expect(specContent!.length).toBeGreaterThan(50)
  })
})

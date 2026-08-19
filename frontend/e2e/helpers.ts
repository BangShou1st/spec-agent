import { expect, type Page } from '@playwright/test'

/**
 * Shared E2E helpers for the graph-first workspace. Every flow proves
 * browser-visible behavior against the real local backend with the fake
 * model gateway; nothing here inspects the database directly.
 */

export const FAKE_ROOT_QUESTION = 'What is the most important outcome?'

/** Creates a project through the UI and lands in its graph workspace. */
export async function createProject(page: Page, title: string): Promise<void> {
  await page.goto('/projects')
  await page.getByLabel('Project title').fill(title)
  await page.getByRole('button', { name: '创建项目' }).click()
  await page.waitForURL(/\/projects\/[0-9a-f-]+/)
  await expect(page.getByTestId('graph-canvas')).toBeVisible()
  await expect(page.getByTestId('route-navigator')).toBeVisible()
}

/** Fits the whole graph into the viewport (viewport-only; never moves nodes). */
export async function fitGraph(page: Page): Promise<void> {
  await page.getByTestId('fit-view').click()
  await page.waitForTimeout(500)
}

/** Drafts the first question (explicit user action). */
export async function draftFirstQuestion(page: Page): Promise<void> {
  await page.getByTestId('draft-question').click()
  await expect(page.getByTestId('question')).toBeVisible()
}

/** Answers the active node with free text and waits for the recorded answer. */
export async function answerActiveNode(page: Page, text: string): Promise<void> {
  await page.getByTestId('free-text').fill(text)
  await page.getByTestId('submit-answer').click()
  await expect(page.getByText('回答已记录。')).toBeVisible()
  await expect(page.getByTestId('free-text')).toHaveValue('')
}

/** Builds a 3-node lineage: root, answered child, grandchild (1 route). */
export async function buildThreeNodeLineage(page: Page): Promise<void> {
  await draftFirstQuestion(page)
  await answerActiveNode(page, 'First answer content')
  await answerActiveNode(page, 'Second answer content')
  await expect(page.locator('.graph-question-node')).toHaveCount(3)
  // The graph owns the full canvas while sidebars float above it. Re-fit after
  // building the fixture so every historical node is in the unobscured
  // reading corridor before a test begins a graph-native interaction.
  await fitGraph(page)
}

/**
 * Forks from the given graph node index through the explicit source-route
 * dialog. The helper selects the only source route in this fixture.
 */
export async function forkFromNode(page: Page, index: number, label: string): Promise<void> {
  await page.locator('[data-test="graph-question-node"]').nth(index).getByTestId('fork-node').click()
  await expect(page.getByTestId('fork-dialog')).toBeVisible()
  await page.getByTestId('fork-base-route').first().check()
  if (label) {
    await page.getByTestId('fork-label').fill(label)
  }
  await page.getByTestId('fork-submit').click()
  await expect(page.getByTestId('fork-dialog')).toHaveCount(0)
}

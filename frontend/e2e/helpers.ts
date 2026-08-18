import { expect, type Page } from '@playwright/test'

/**
 * Shared E2E helpers. Every flow proves browser-visible behavior against the
 * real local backend with the fake model gateway; nothing here inspects the
 * database directly.
 */

export const FAKE_ROOT_QUESTION = 'What is the most important outcome?'

/** Creates a project through the UI and lands in its workspace. */
export async function createProject(page: Page, title: string): Promise<void> {
  await page.goto('/projects')
  await page.getByLabel('Project title').fill(title)
  await page.getByRole('button', { name: 'Create project' }).click()
  await page.waitForURL(/\/projects\/[0-9a-f-]+/)
  await expect(page.getByText('Route Workspace')).toBeVisible()
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
  // The backend recorded the answer and drafted the next node; the store
  // refreshed and the active question input was reset for the next answer.
  await expect(page.getByText('Answer recorded.')).toBeVisible()
  await expect(page.getByTestId('free-text')).toHaveValue('')
}

/** Builds a 3-node lineage: root, answered child, grandchild. */
export async function buildThreeNodeLineage(page: Page): Promise<void> {
  await draftFirstQuestion(page)
  await answerActiveNode(page, 'First answer content')
  await answerActiveNode(page, 'Second answer content')
  await expect(page.getByTestId('lineage-node')).toHaveCount(3)
}

/** Selects a historical lineage node by index (0 = root). */
export async function selectHistoricalNode(page: Page, index: number): Promise<void> {
  await page.getByTestId('lineage-node').nth(index).click()
  await expect(page.getByTestId('historical-question')).toBeVisible()
}
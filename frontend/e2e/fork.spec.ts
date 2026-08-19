import { test, expect } from '@playwright/test'
import { buildThreeNodeLineage, closeFloatingWorkspaceWindows, createProject, fitGraph, forkFromNode } from './helpers'

test('fork from a focused visual node has no route picker and preserves history', async ({ page }) => {
  await createProject(page, 'E2E Fork Graph Flow')
  await buildThreeNodeLineage(page)
  await fitGraph(page)

  const root = page.locator('[data-test="graph-question-node"]').nth(0)
  const rootPosition = await root.evaluate((el) => (el.parentElement as HTMLElement).style.transform)
  await forkFromNode(page, 1, 'Forked from child')

  await expect(page.locator('[data-route-id]')).toHaveCount(2)
  await expect(page.locator('.graph-question-node')).toHaveCount(4)
  await expect(page.getByTestId('active-route')).toHaveCount(1)
  await expect(page.getByTestId('question')).toBeVisible()
  expect(await page.getByTestId('fork-dialog')).toHaveCount(0)
  expect(await page.locator('[data-test="graph-question-node"]').nth(0).evaluate((el) => (el.parentElement as HTMLElement).style.transform))
    .toBe(rootPosition)
})

test('shared-node fork requires Focus and never renders a source picker', async ({ page }) => {
  await createProject(page, 'E2E Fork Focus Context')
  await buildThreeNodeLineage(page)
  await fitGraph(page)
  await forkFromNode(page, 1, 'Fork-B')

  const cards = page.locator('[data-route-id]')
  const nonActive = cards.filter({ hasNot: page.getByTestId('active-route') }).first()
  await nonActive.getByTestId('focus-route').click()
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)

  await page.locator('[data-test="graph-question-node"]').first().getByTestId('fork-node').click()
  await expect(page.getByTestId('fork-dialog')).toBeVisible()
  await expect(page.getByTestId('fork-submit')).toBeEnabled()
  await expect(page.locator('[data-test="fork-base-route"]')).toHaveCount(0)
  await page.getByTestId('fork-submit').click()
  await expect(page.getByTestId('fork-dialog')).toHaveCount(0)
  await page.getByTestId('open-routes').click()
  await expect(page.locator('[data-route-id]')).toHaveCount(3)
})

test('ambiguous shared-node fork is blocked until Current View is selected', async ({ page }) => {
  await createProject(page, 'E2E Fork Ambiguous Context')
  await buildThreeNodeLineage(page)
  await fitGraph(page)
  await forkFromNode(page, 1, 'Fork-B')

  const cards = page.locator('[data-route-id]')
  const nonActive = cards.filter({ hasNot: page.getByTestId('active-route') }).first()
  await nonActive.getByTestId('focus-route').click()
  await nonActive.getByTestId('focus-route').click()
  await closeFloatingWorkspaceWindows(page)
  await fitGraph(page)
  await page.locator('[data-test="graph-question-node"]').first().getByTestId('fork-node').click()
  await expect(page.getByTestId('fork-dialog')).toBeVisible()
  await expect(page.getByTestId('choose-reading-route')).toContainText('“当前查看”控件')
  await expect(page.getByTestId('reading-route-select').first()).toHaveValue('')
  await expect(page.getByTestId('fork-submit')).toBeDisabled()
  await expect(page.locator('[data-test="fork-base-route"]')).toHaveCount(0)
})

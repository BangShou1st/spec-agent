import { test, expect } from '@playwright/test'
import { answerActiveNode, buildThreeNodeLineage, createProject, draftFirstQuestion, fitGraph } from './helpers'

test.setTimeout(400000)

async function openDeterministicWorkspace(page, title: string, viewport: { width: number; height: number }) {
  await page.setViewportSize(viewport)
  await createProject(page, title)
  await buildThreeNodeLineage(page)
  await fitGraph(page)
}

test('capture default graph 1440x900', async ({ page }) => {
  await openDeterministicWorkspace(page, 'E2E Shot Default Graph', { width: 1440, height: 900 })
  await expect(page.getByTestId('graph-canvas')).toBeVisible()
  await expect(page.getByTestId('spec-dock')).toHaveAttribute('data-state', 'collapsed')
  await page.waitForTimeout(800)
  await page.screenshot({ path: 'test-results/ui-final/default-graph-1440x900.png' })
})

test('capture selected node 1440x900', async ({ page }) => {
  await openDeterministicWorkspace(page, 'E2E Shot Selected Node', { width: 1440, height: 900 })
  const historical = page.locator('.graph-question-node--historical').first()
  await expect(historical).toBeVisible()
  await historical.click()
  await expect(page.getByTestId('workspace-inspector')).toBeVisible()
  await page.waitForTimeout(800)
  await page.screenshot({ path: 'test-results/ui-final/selected-node-1440x900.png' })
})

test('capture spec expanded 1440x900', async ({ page }) => {
  // 轻量前置：单问单答后直接生成，避免 run 链排队超时。
  await page.setViewportSize({ width: 1440, height: 900 })
  await createProject(page, 'E2E Shot Spec Expanded')
  await draftFirstQuestion(page)
  await answerActiveNode(page, 'Spec-worthy answer content')
  await fitGraph(page)
  await page.getByTestId('spec-dock-toggle').click()
  await expect(page.getByTestId('spec-dock')).toHaveAttribute('data-state', 'expanded')
  await page.getByTestId('generate-spec').click()
  await expect(page.getByTestId('spec-snapshot-detail')).toBeVisible({ timeout: 120000 })
  await page.waitForTimeout(800)
  await page.screenshot({ path: 'test-results/ui-final/spec-expanded-1440x900.png' })
})

test('capture requirements 1440x900', async ({ page }) => {
  await openDeterministicWorkspace(page, 'E2E Shot Requirements', { width: 1440, height: 900 })
  await page.getByTestId('open-requirements').click()
  await expect(page.getByTestId('requirement-detail')).toBeVisible()
  await page.waitForTimeout(800)
  await page.screenshot({ path: 'test-results/ui-final/requirements-1440x900.png' })
})

test('capture spec expanded 1366x768', async ({ page }) => {
  await openDeterministicWorkspace(page, 'E2E Shot Spec Expanded Small', { width: 1366, height: 768 })
  await page.getByTestId('spec-dock-toggle').click()
  await expect(page.getByTestId('spec-dock')).toHaveAttribute('data-state', 'expanded')
  await expect(page.getByTestId('graph-canvas')).toBeVisible()
  await page.waitForTimeout(800)
  await page.screenshot({ path: 'test-results/ui-final/spec-expanded-1366x768.png' })
})

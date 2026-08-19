import { test, expect } from '@playwright/test'
import {
  answerActiveNode,
  buildThreeNodeLineage,
  createProject,
  draftFirstQuestion,
  fitGraph,
  forkFromNode,
} from './helpers'

/**
 * Spec flow on the graph: generate a derived snapshot for the ACTIVE route;
 * sections/unresolved items/source refs render; snapshot history updates.
 * Fake gateway only — no real model.
 */
test('generate and inspect a derived spec snapshot', async ({ page }) => {
  await createProject(page, 'E2E Spec Graph Flow')
  await draftFirstQuestion(page)
  await answerActiveNode(page, 'Spec-worthy answer content')

  await page.getByTestId('tab-spec').click()
  await expect(page.getByTestId('generate-spec')).toBeVisible()
  await expect(page.getByText('为当前路线生成规格')).toBeVisible()

  await page.getByTestId('generate-spec').click()

  await expect(page.getByTestId('spec-snapshot-detail')).toBeVisible()
  await expect(page.getByTestId('derived-label')).toContainText('派生产物')
  await expect(page.getByText('The clarified requirement outcome.')).toBeVisible()
  await expect(page.getByTestId('spec-snapshot-item')).toHaveCount(1)
  await expect(page.locator('.error-banner')).toHaveCount(0)
})

/**
 * Work/read context separation: Active=A + Focus=B → requirement state and
 * spec history read B, the only answerable node stays on A, generate always
 * targets A with an explicit warning, and after generation the reading
 * context follows the returned A artifact.
 */
test('Active=A Focus=B separates reading context from work context', async ({ page }) => {
  await createProject(page, 'E2E Read Context Flow')
  await buildThreeNodeLineage(page)
  await fitGraph(page)
  // A = 初始路线（当前）。Fork 出一个 B（child 之上）→ B 成为当前路线。
  await forkFromNode(page, 1, 'Route-B')
  await expect(page.locator('[data-route-id]')).toHaveCount(2)

  // 把 A 重新设为当前路线 → Active=A；B 保持 OPEN 非当前。
  const cards = page.locator('[data-route-id]')
  const cardA = cards.filter({ hasNot: page.getByTestId('active-route') }).first()
  await cardA.getByTestId('activate-route').click()
  // activate 是后端命令：等确认反馈（canonical 刷新完成）后再定位卡片，
  // 避免徽标切换竞态导致后续 locator 指向错误的路线。
  await expect(page.getByText('已设为当前路线。')).toBeVisible()
  await expect(page.getByTestId('active-route')).toHaveCount(1)

  // Focus=B（分支成功后已进入浏览上下文；切换 Active 不应清除 Focus）。
  const cardB = cards.filter({ hasNot: page.getByTestId('active-route') }).first()
  await expect(cardB).toHaveClass(/route-card--focused/)
  // Focus 不改变 Active。
  await expect(page.getByTestId('active-route')).toHaveCount(1)

  // 需求状态与规格历史跟随读取路线 B。
  await expect(page.getByTestId('requirement-state-panel')).toBeVisible()
  await expect(page.getByText('路线：Route-B').first()).toBeVisible()

  // 唯一可回答节点仍是 A 上的节点（Focus 不移动工作上下文）。
  await page.getByTestId('tab-spec').click()
  await expect(page.getByTestId('generate-focus-warning')).toContainText('Route-B')
  await expect(page.getByText('当前路线：主路线')).toBeVisible()
  await expect(page.getByTestId('specs-empty')).toBeVisible()

  // 生成始终针对当前路线 A，成功后读取上下文跟随返回的 A 产物。
  await page.getByTestId('generate-spec').click()
  await expect(page.getByTestId('spec-snapshot-detail')).toBeVisible()
  await expect(page.getByText('读取路线：主路线')).toBeVisible()
  await expect(page.getByTestId('spec-snapshot-item')).toHaveCount(1)
})

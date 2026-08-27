import { test, expect } from '@playwright/test'
import {
  FAKE_ROOT_QUESTION,
  answerActiveNode,
  createProject,
  draftFirstQuestion,
} from './helpers'

/**
 * Core graph clarification flow: empty project placeholder → explicit draft
 * → first real root node → answer directly inside the node → node becomes
 * a compact historical node → next current node appears to the right.
 */
test('empty start drafts the first real node and answers inside the graph node', async ({ page }) => {
  await createProject(page, 'E2E Core Graph Flow')

  // 空项目：居中纯 UI placeholder，没有任何假节点。
  await expect(page.getByTestId('graph-start-placeholder')).toBeVisible()
  await expect(page.getByText('开始需求澄清')).toBeVisible()
  await expect(page.getByText('还没有任何内容。')).toBeVisible()
  await expect(page.locator('.graph-question-node')).toHaveCount(0)

  // 起草第一个问题（显式操作）：placeholder 消失，出现一个真实的当前根节点。
  await page.getByTestId('draft-question').click()
  await expect(page.getByTestId('graph-start-placeholder')).toHaveCount(0)
  await expect(page.locator('.graph-question-node')).toHaveCount(1)
  await expect(page.getByTestId('question')).toHaveText(FAKE_ROOT_QUESTION)
  await expect(page.locator('.graph-question-node--current')).toHaveCount(1)

  const rootBox = await page.locator('.graph-question-node').first().boundingBox()
  expect(rootBox).not.toBeNull()

  // 在节点内直接回答：节点变历史（compact），下一个当前节点出现在右侧。
  await answerActiveNode(page, 'The product must let one user clarify one requirement.')
  await expect(page.locator('.graph-question-node')).toHaveCount(2)
  await expect(page.locator('.graph-question-node--historical')).toHaveCount(1)
  await expect(page.getByTestId('historical-question')).toHaveText(FAKE_ROOT_QUESTION)

  const rootBoxAfter = await page.locator('.graph-question-node').first().boundingBox()
  expect(rootBoxAfter).not.toBeNull()
  // 已有节点坐标不变（渲染容差内）。
  expect(Math.abs((rootBoxAfter?.x ?? 0) - (rootBox?.x ?? 0))).toBeLessThan(5)
  expect(Math.abs((rootBoxAfter?.y ?? 0) - (rootBox?.y ?? 0))).toBeLessThan(5)

  const nextBox = await page.locator('.graph-question-node').nth(1).boundingBox()
  expect(nextBox).not.toBeNull()
  // 新节点放在父节点右侧。
  expect((nextBox?.x ?? 0)).toBeGreaterThan(rootBoxAfter?.x ?? 0)

  // 需求状态跟随回答更新（后端派生内容 verbatim）。决策引擎的 STATE_UPDATE
  // 只产出一条 confirmed claim；不会再有旧的 unresolved claim。
  await expect(page.getByText('The user clarified the main outcome.')).toBeVisible()
  await expect(page.locator('.error-banner')).toHaveCount(0)
})

test('whats-kept-verbatim: purpose renders inside the node without translation', async ({ page }) => {
  await createProject(page, 'E2E Verbatim Options')
  await draftFirstQuestion(page)
  // 后端/AI 内容（purpose）原样展示，不翻译。
  const node = page.locator('.graph-question-node--current')
  await expect(node.getByText('This clarifies the primary requirement goal.')).toBeVisible()
})

test('draft idea enters edit mode immediately and saves content through the editor', async ({ page }) => {
  await createProject(page, 'E2E Draft Idea Editing')

  await page.getByTestId('graph-toolbar').getByTestId('add-idea').click()
  const draft = page.getByTestId('graph-knowledge-node')
  await expect(draft).toBeVisible()
  // + 想法 automatically opens the editor (one-shot edit request) — the
  // toolbar (including edit-draft) is intentionally hidden while editing.
  const input = draft.getByTestId('draft-text')
  await expect(input).toBeVisible()
  await input.fill('A requirement captured directly in the graph.')
  await draft.getByTestId('save-draft').click()
  await expect(draft.getByTestId('knowledge-text')).toContainText(
    'A requirement captured directly in the graph.',
  )
})

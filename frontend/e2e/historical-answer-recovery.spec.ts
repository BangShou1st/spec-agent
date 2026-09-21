import { test, expect, type APIRequestContext } from '@playwright/test'
import { createProject, draftFirstQuestion, fitGraph } from './helpers'

/**
 * Historical-answer recovery, driven entirely through the real browser against
 * the real backend (test profile + deterministic in-JVM engine).
 *
 * What this covers — the whole chain the previous version of this file only
 * claimed to cover:
 *
 *   1. an answer is SUBMITTED and persisted, but its processing fails, so the
 *      route owes its STATE_UPDATE checkpoint;
 *   2. generating a spec is refused by the artifact gate, and the refusal names
 *      the answer / route / node the user has to recover;
 *   3. the FIRST recovery attempt fails → the saved answer is kept and the
 *      notice stays with a retry affordance (nothing is retyped, nothing is
 *      silently dropped);
 *   4. the RETRY succeeds → the notice disappears and the recovery produced the
 *      missing checkpoint;
 *   5. the very same generate action now succeeds, the derived snapshot is
 *      rendered, and the provenance shown by the UI matches the canonical
 *      read model exactly.
 *
 * Reaching step 1 needs one deliberate failure: the product cannot create this
 * state through the UI any more (the draft path refuses to cross an unprocessed
 * answer), and the fake model never fails. The failure is therefore DECLARED in
 * the answer text and honoured by the deterministic in-JVM engine
 * (`DeterministicEngineFaultPlan`, registered only for
 * `spec.agent.brain.engine=fake`). It is scoped to this answered node and to a
 * fixed count, so the retry deterministically succeeds and no other test is
 * affected. Everything durable — the Answer row, the failed run, the gate
 * refusal, the recovery run, the AnswerPatch, the spec snapshot — is produced
 * by the ordinary runtime.
 *
 * Scope note (deliberate, not an omission): with the deterministic engine the
 * ARTIFACT_GENERATION output is fixed copy, so a user-derived constraint can
 * never appear in the spec BODY — see
 * `LocalDeterministicDecisionEngine.runArtifactGeneration`. Constraint
 * preservation is therefore asserted where it is really observable: the
 * persisted answer (canonical read model + Inspector) and the backend
 * integration test `HistoricalRecoveryFaultInjectionIntegrationTest`.
 */

const CONSTRAINT = '会议不超过45分钟，确保每次会议都有明确议程。'
/** Declared, node-scoped failure consumed by the deterministic engine. */
const FAULT_DIRECTIVE = '[[fail-state-update:2]]'

/** Deterministic artifact body shared with the Python fake model client. */
const DERIVED_OVERVIEW = '用户澄清了主要目标：明确最重要的成果。'
const DERIVED_OPEN_QUESTIONS = '范围边界尚未确认，需要用户进一步澄清。'

interface GraphAnswer {
  id: string
  nodeId: string
  freeText: string | null
}

interface GraphSnapshot {
  activeRouteId: string
  routes: Array<{ id: string; lineageNodeIds: string[] }>
  answers: GraphAnswer[]
}

interface SpecSnapshot {
  id: string
  routeId: string
  sections: Array<{ title: string; content: string }>
  sourceRefs: Array<{ kind: string; refId: string }>
  createdByRunId: string | null
}

async function readGraph(
  request: APIRequestContext,
  projectId: string,
): Promise<GraphSnapshot> {
  const response = await request.get(`/api/v1/projects/${projectId}/graph`)
  return (await response.json()) as GraphSnapshot
}

test.describe('Historical answer recovery', () => {
  test('recovers a saved-but-unprocessed answer after a failure, then generates the spec', async ({
    page,
    request,
  }) => {
    await createProject(page, `E2E-Historical-Recovery-${Date.now()}`)
    const projectId = page.url().split('/projects/')[1].split(/[?#]/)[0] || ''
    expect(projectId).not.toBe('')

    // ── 1. Draft and answer. The answer cycle fails on purpose, AFTER the
    //        Answer was persisted. ──
    await draftFirstQuestion(page)
    const questionText = (await page.getByTestId('question').innerText()).trim()
    expect(questionText.length).toBeGreaterThan(0)

    const graphBefore = await readGraph(request, projectId)
    const routeBefore = graphBefore.routes.find(
      (route) => route.id === graphBefore.activeRouteId,
    )
    expect(routeBefore).toBeDefined()
    const answeredNodeId = routeBefore!.lineageNodeIds[0] as string

    await page.getByTestId('free-text').fill(`${CONSTRAINT} ${FAULT_DIRECTIVE}`)
    await page.getByTestId('submit-answer').click()

    // The user is told the answer is kept, and is never asked to retype it.
    await expect(page.getByTestId('feedback')).toContainText('回答已保存')
    await expect(page.getByTestId('recovery-notice')).toBeVisible()

    // Real proof the answer survived its failed processing — read from the
    // canonical model, not from the DOM.
    const graphAfterFailure = await readGraph(request, projectId)
    const savedAnswers = graphAfterFailure.answers.filter(
      (answer) => answer.nodeId === answeredNodeId,
    )
    expect(savedAnswers).toHaveLength(1)
    expect(savedAnswers[0]!.freeText).toContain('会议不超过45分钟')

    // ── 2. Generation is refused by the gate, and the refusal names the
    //        recoverable answer instead of failing generically. ──
    const dock = page.getByTestId('spec-dock')
    await page.getByTestId('spec-dock-toggle').click()
    await expect(dock).toHaveAttribute('data-state', 'expanded')
    await page.getByTestId('generate-spec').click()

    const notice = page.getByTestId('recovery-notice')
    await expect(notice.getByTestId('recovery-title')).toHaveText('历史回答需要恢复')
    await expect(notice.getByTestId('recovery-message')).toContainText(questionText)
    await expect(notice.getByTestId('recovery-action')).toHaveText('恢复该回答')
    await expect(page.getByTestId('spec-snapshot-detail')).toHaveCount(0)

    // ── 3. First recovery attempt: still inside the declared budget → fails.
    //        The saved answer is kept and the retry affordance stays. ──
    await notice.getByTestId('recovery-action').click()
    await expect(page.getByTestId('feedback')).toContainText('历史回答恢复未完成')
    await expect(notice.getByTestId('recovery-title')).toHaveText('历史回答需要恢复')
    await expect(notice.getByTestId('recovery-action')).toHaveText('恢复该回答')
    await expect(page.getByTestId('spec-snapshot-detail')).toHaveCount(0)

    // A failed recovery must never fork a second Answer onto the node.
    const graphAfterFailedRecovery = await readGraph(request, projectId)
    expect(
      graphAfterFailedRecovery.answers.filter((answer) => answer.nodeId === answeredNodeId),
    ).toHaveLength(1)

    // ── 4. Retry: the ordinary runtime completes the checkpoint. The notice
    //        can only disappear when the terminal run reported the produced
    //        Patch (a historical-recovery session requires producedPatchId),
    //        so the disappearance IS the evidence that the missing
    //        STATE_UPDATE checkpoint was written. ──
    await notice.getByTestId('recovery-action').click()
    await expect(notice).toHaveCount(0)
    await expect(page.locator('.error-banner')).toHaveCount(0)

    // The completed cycle really advanced the graph (the answer was processed,
    // not merely marked done) and still holds exactly one Answer.
    const graphAfterRecovery = await readGraph(request, projectId)
    const routeAfter = graphAfterRecovery.routes.find(
      (route) => route.id === routeBefore!.id,
    )
    expect(routeAfter).toBeDefined()
    expect(routeAfter!.lineageNodeIds[routeAfter!.lineageNodeIds.length - 1]).not.toBe(
      answeredNodeId,
    )
    expect(
      graphAfterRecovery.answers.filter((answer) => answer.nodeId === answeredNodeId),
    ).toHaveLength(1)

    // The constraint the user typed is still the canonical answer, visible in
    // the Inspector for the recovered node.
    await fitGraph(page)
    await page.locator(`[data-node-id="${answeredNodeId}"]`).click()
    await expect(page.getByTestId('node-inspector')).toBeVisible()
    await expect(page.getByTestId('canonical-answer')).toContainText('会议不超过45分钟')

    // ── 5. The same generate action now succeeds and renders the derived
    //        snapshot. ──
    await page.getByTestId('generate-spec').click()
    const detail = page.getByTestId('spec-snapshot-detail')
    await expect(detail).toBeVisible({ timeout: 30_000 })
    await expect(page.getByTestId('derived-label')).toContainText('派生产物')
    await expect(page.locator('.error-banner')).toHaveCount(0)

    const sections = page.getByTestId('spec-section')
    await expect(sections).toHaveCount(2)
    await expect(detail).toContainText(DERIVED_OVERVIEW)
    await expect(detail).toContainText(DERIVED_OPEN_QUESTIONS)

    // ── 6. Provenance is correct: the UI list is exactly the canonical read
    //        model's list, and every reference is a well-formed kind:refId
    //        pair pointing at a real runtime record of this project. ──
    const specsResponse = await request.get(
      `/api/v1/projects/${projectId}/routes/${routeBefore!.id}/specs`,
    )
    expect(specsResponse.ok()).toBe(true)
    const specs = (await specsResponse.json()) as SpecSnapshot[]
    expect(specs).toHaveLength(1)
    const generated = specs[0]!
    expect(generated.createdByRunId).toBeTruthy()
    expect(generated.sections.map((section) => section.title)).toEqual([
      'Overview',
      'Open Questions',
    ])
    expect(generated.sourceRefs.length).toBeGreaterThan(0)
    for (const ref of generated.sourceRefs) {
      expect(['context', 'answer', 'node', 'patch']).toContain(ref.kind)
      expect(ref.refId).toMatch(/^[0-9a-f-]{36}$/)
    }

    await page.getByTestId('spec-provenance-toggle').click()
    const shownRefs = page.getByTestId('source-reference')
    await expect(shownRefs).toHaveCount(generated.sourceRefs.length)
    for (const [index, ref] of generated.sourceRefs.entries()) {
      await expect(shownRefs.nth(index)).toHaveText(`${ref.kind}：${ref.refId}`)
    }
  })
})

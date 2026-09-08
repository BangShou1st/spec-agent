# Spec Agent UI/UX Third Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the existing Graph-first workspace visually and ergonomically, harden accessibility/desktop geometry, remove proven-dead floating-window infrastructure, and produce real screenshots for human acceptance without adding product capabilities.

**Architecture:** Keep `WorkspaceView` + fixed `ResizableSidebar` + `GraphCanvas` + contextual `WorkspaceInspector` + `SpecDock` as the frozen information architecture. Presentation changes stay in existing Vue/CSS components; browser-only sidebar persistence remains V1. Legacy floating-window code is removed only after an explicit reference audit proves it has no production consumer.

**Tech Stack:** Vue 3.5, TypeScript 5.8, Pinia 2.3, Vue Flow 1.48.2, Vitest 3.1, Vue Test Utils 2.4, Playwright 1.62, plain CSS.

**Spec:** `docs/superpowers/specs/2026-09-08-ui-ux-third-slice-design.md`

## Global Constraints

- Branch: `ui-ux-third-slice`, based on `main` commit `7e98cca652eb44e87b2992f8f74b374778d2e5c3`.
- Do not modify `backend/`, `agent-brain/`, API contracts, migrations, or Runtime semantics.
- Do not add frontend dependencies.
- Do not add product features, top-level panels, tabs, search, comments, notifications, collaboration, personas, analytics, or dashboard concepts.
- Graph remains the visually dominant workspace.
- Focus Route remains browser reading context and never implicitly changes Runtime Active Route.
- Shared Nodes remain one canonical physical node.
- Answer submission remains in the current answerable Graph node only.
- Approval, Recovery, Spec ownership, reconciliation, idempotency, and continuation semantics remain unchanged.
- Default UI must not expose UUIDs, run ids, raw actionFamily, or raw runtime phase.
- Use TDD for behavior-affecting changes.
- Visual-only CSS refinements must still be covered by existing behavior tests plus final E2E geometry checks.
- Run `frontend-design` only after structural behavior is green.
- Run `web-design-guidelines` after visual polish as an audit, not as a product-definition source.
- Final screenshots are required and must not be committed unless explicitly requested.

---

## File Structure for This Slice

### Expected production files to modify

- `frontend/src/style.css` — final visual system, focus-visible, reduced-motion, overflow, spacing and surface hierarchy.
- `frontend/src/components/graph/GraphQuestionNode.vue` — compact metadata hierarchy only; no behavior change.
- `frontend/src/components/graph/GraphToolbar.vue` — accessibility/hit-area polish only; no commands added.
- `frontend/src/components/workspace/RouteSidebar.vue` — route row hierarchy/overflow/focus-active clarity.
- `frontend/src/components/workspace/WorkspaceInspector.vue` — contextual hierarchy/focus/overflow polish.
- `frontend/src/components/workspace/NodeInspector.vue` — selected-node density/secondary detail polish.
- `frontend/src/components/workspace/ProjectSummary.vue` — summary visual hierarchy.
- `frontend/src/components/workspace/RequirementDetailView.vue` — readable claim grouping/technical disclosure polish.
- `frontend/src/components/workspace/SpecDock.vue` — collapsed/expanded visual rhythm and overflow.
- `frontend/src/components/workspace/AgentProposalCard.vue` — visual consistency/accessibility only.
- `frontend/src/components/workspace/RecoveryNotice.vue` — visual consistency/accessibility only.
- `frontend/src/components/workspace/ResizableSidebar.vue` — only if a test proves a focus/geometry/accessibility defect.
- `frontend/src/stores/graphUiStore.ts` — remove floating-window-only state/actions after cleanup gate.
- `frontend/src/graph/graphLayoutStorage.ts` — remove floating-window V2 persistence after cleanup gate.
- `frontend/src/graph/graphTypes.ts` — remove floating-window-only types after cleanup gate.

### Candidate files to delete after proof of zero production consumers

- `frontend/src/components/workspace/FloatingWindow.vue`
- `frontend/src/components/workspace/RouteNavigator.vue`
- `frontend/src/graph/floatingWindowLayout.ts`
- `frontend/src/components/workspace/__tests__/FloatingWindow.spec.ts`
- `frontend/src/graph/__tests__/floatingWindowLayout.spec.ts`

Do not delete other old-looking components merely because they appear unused. This slice only has pre-approved cleanup authority for the floating-window presentation stack.

### Tests expected to modify/add

- `frontend/src/stores/__tests__/graphUiStore.spec.ts`
- `frontend/src/graph/__tests__/graphLayoutStorage.spec.ts`
- `frontend/src/components/graph/__tests__/GraphQuestionNode.spec.ts`
- `frontend/src/components/graph/__tests__/GraphToolbar.spec.ts`
- `frontend/src/components/workspace/__tests__/RouteSidebar.spec.ts`
- `frontend/src/components/workspace/__tests__/WorkspaceInspector.spec.ts`
- `frontend/src/components/workspace/__tests__/NodeInspector.spec.ts`
- `frontend/src/components/workspace/__tests__/SpecDock.spec.ts`
- `frontend/src/components/workspace/__tests__/AgentProposalCard.spec.ts`
- `frontend/src/components/workspace/__tests__/RecoveryNotice.spec.ts`
- `frontend/src/components/workspace/__tests__/ResizableSidebar.spec.ts` only if changed.
- `frontend/e2e/workspace-layout.spec.ts`
- `frontend/e2e/spec.spec.ts`
- `frontend/e2e/shared-focus.spec.ts`
- `frontend/e2e/node-query-proposal.spec.ts`
- `frontend/e2e/core-clarification.spec.ts` only if selectors need migration.

---

### Task 1: Prove the final baseline and freeze the cleanup set

**Files:**
- Read only initially: current production/test tree.
- Modify only if the audit finds stale tests that falsely claim floating-window behavior is current.

**Interfaces:**
- Consumes: current fixed workspace shell and existing tests.
- Produces: an evidence-backed deletion list for Task 2 and a clean pre-polish baseline.

- [ ] **Step 1: Sync and verify branch identity**

Run from repository root:

```bash
git fetch origin
git checkout ui-ux-third-slice
git pull --ff-only origin ui-ux-third-slice
git status --short
git rev-parse HEAD
git merge-base HEAD origin/main
```

Expected: clean tree; merge-base equals `7e98cca652eb44e87b2992f8f74b374778d2e5c3`.

- [ ] **Step 2: Prove current behavior baseline before touching code**

```bash
cd frontend
npm ci
npm test
npm run typecheck
npm run build
```

Expected: all commands exit 0. If any baseline command fails, stop and report before changing source.

- [ ] **Step 3: Audit floating-window production references**

From repository root:

```bash
rg -n --glob '!docs/**' --glob '!frontend/node_modules/**' \
  'FloatingWindow|RouteNavigator|floatingWindowLayout|FloatingWindowPreference|WorkspaceUiPreferencesV2|DEFAULT_WORKSPACE_UI_V2|FLOATING_WINDOW_RANGES|floatingWindows|windowZOrder|setFloatingWindow|bringWindowToFront|resetWindows|persistWorkspaceV2|loadWorkspaceUiPreferencesV2|saveWorkspaceUiPreferencesV2' \
  frontend
```

Classify every match as one of:

```text
A. real current production consumer
B. floating-window legacy production code
C. test dedicated only to legacy behavior
D. current fixed-sidebar test that must be preserved/migrated
```

Deletion is allowed only for B/C. If any A exists outside the candidate stack, keep the required legacy artifact and explain it in the final report.

- [ ] **Step 4: Record the audit in the task commit message, not a new product doc**

No extra audit file is required. Keep the repository documentation lean.

- [ ] **Step 5: Commit only if Step 3 required a test correction; otherwise do not create an empty commit**

---

### Task 2: Remove proven-dead floating-window infrastructure

**Files:**
- Delete when audit allows:
  - `frontend/src/components/workspace/FloatingWindow.vue`
  - `frontend/src/components/workspace/RouteNavigator.vue`
  - `frontend/src/graph/floatingWindowLayout.ts`
  - `frontend/src/components/workspace/__tests__/FloatingWindow.spec.ts`
  - `frontend/src/graph/__tests__/floatingWindowLayout.spec.ts`
- Modify:
  - `frontend/src/stores/graphUiStore.ts`
  - `frontend/src/graph/graphLayoutStorage.ts`
  - `frontend/src/graph/graphTypes.ts`
  - `frontend/src/stores/__tests__/graphUiStore.spec.ts`
  - `frontend/src/graph/__tests__/graphLayoutStorage.spec.ts`

**Interfaces:**
- Preserves: `WorkspaceUiPreferencesV1`, `loadWorkspaceUiPreferences`, `saveWorkspaceUiPreferences`, sidebar open/width state/actions.
- Removes only: floating-window geometry/state/persistence APIs.

- [ ] **Step 1: Write/adjust fixed-sidebar persistence tests first**

In `graphUiStore.spec.ts`, ensure the surviving contract is explicit:

```ts
it('persists fixed sidebar open state and clamped widths without floating-window state', () => {
  const store = useGraphUiStore()
  store.setLeftSidebar({ open: false, width: 9999 })
  store.setRightSidebar({ open: true, width: -10 })

  const saved = JSON.parse(localStorage.getItem('spec-agent.workspace-ui.v1') ?? '{}')
  expect(saved.leftSidebar.open).toBe(false)
  expect(saved.leftSidebar.width).toBe(420)
  expect(saved.rightSidebar.open).toBe(true)
  expect(saved.rightSidebar.width).toBe(300)
  expect('floatingWindows' in store).toBe(false)
})
```

If Pinia state proxy makes `'floatingWindows' in store` unsuitable, assert through the serialized store state or remove only that line; do not add production compatibility fields to satisfy the test.

- [ ] **Step 2: Run focused tests and observe failure before deletion implementation**

```bash
cd frontend
npm test -- src/stores/__tests__/graphUiStore.spec.ts src/graph/__tests__/graphLayoutStorage.spec.ts
```

Expected: the new absence assertion or updated storage expectation fails against current legacy state.

- [ ] **Step 3: Remove floating-window-only state/types/storage**

Required end-state in `graphUiStore.ts`:

```ts
state: () => {
  const workspaceUi = loadWorkspaceUiPreferences()
  return {
    // ...existing graph state...
    leftSidebarOpen: workspaceUi.leftSidebar.open,
    leftSidebarWidth: workspaceUi.leftSidebar.width,
    rightSidebarOpen: workspaceUi.rightSidebar.open,
    rightSidebarWidth: workspaceUi.rightSidebar.width,
  }
}
```

`persistWorkspaceState()` ends after:

```ts
saveWorkspaceUiPreferences(prefs)
```

Delete floating-only actions and imports. Do not replace them with no-op compatibility methods.

Required end-state in `graphTypes.ts`: keep `WorkspaceUiPreferencesV1`; remove `FloatingWindowPreference` and `WorkspaceUiPreferencesV2` if the Task 1 audit proves no current production consumer.

Required end-state in `graphLayoutStorage.ts`: keep V1 sidebar storage and project graph layout storage; remove V2 floating-window key/default/ranges/parser/load/save if audit allows.

Old browser `spec-agent.workspace-ui.v2` data is intentionally ignored; do not add migration code.

- [ ] **Step 4: Delete legacy files/tests proven dead by Task 1**

Use real deletion, not empty shells or deprecated wrappers.

- [ ] **Step 5: Run focused verification**

```bash
cd frontend
npm test -- src/stores/__tests__/graphUiStore.spec.ts src/graph/__tests__/graphLayoutStorage.spec.ts
npm run typecheck
npm run build
```

Expected: all exit 0.

- [ ] **Step 6: Re-run reference audit**

```bash
cd ..
rg -n --glob '!docs/**' --glob '!frontend/node_modules/**' \
  'FloatingWindow|RouteNavigator|floatingWindowLayout|FloatingWindowPreference|WorkspaceUiPreferencesV2|DEFAULT_WORKSPACE_UI_V2|FLOATING_WINDOW_RANGES|floatingWindows|windowZOrder|setFloatingWindow|bringWindowToFront|resetWindows|persistWorkspaceV2|loadWorkspaceUiPreferencesV2|saveWorkspaceUiPreferencesV2' \
  frontend
```

Expected: zero production references. Any remaining match must be an intentional historical string in a test description; prefer deleting stale descriptions rather than preserving dead terminology.

- [ ] **Step 7: Commit**

```bash
git add -A frontend/src

git commit -m "refactor(ui): remove legacy floating workspace stack"
```

---

### Task 3: Final Graph and route visual hierarchy

**Files:**
- Modify: `frontend/src/components/graph/GraphQuestionNode.vue`
- Modify: `frontend/src/components/graph/GraphToolbar.vue`
- Modify: `frontend/src/components/workspace/RouteSidebar.vue`
- Modify: `frontend/src/style.css`
- Test: corresponding Graph/Route unit tests.

**Interfaces:**
- No new emits, props, store commands, or route semantics.
- Only presentation structure/classes/accessible labels may change.

- [ ] **Step 1: Add presentation-contract tests before markup changes**

Add focused assertions such as:

```ts
expect(wrapper.find('[data-test="shared-route-count"]').text()).toContain('共享 · 4 条路线')
expect(wrapper.findAll('[data-test="route-membership-chip"]')).toHaveLength(0)
```

For Route Sidebar, assert Focus and Active remain independent attributes/labels rather than relying on color:

```ts
expect(focusRow.attributes('aria-current')).toBe('location')
expect(activeRow.text()).toContain('运行中')
```

Use the component's existing test selectors where already present; do not create duplicate DOM solely for tests.

- [ ] **Step 2: Run focused tests and confirm the new contract fails where markup is not yet present**

```bash
cd frontend
npm test -- \
  src/components/graph/__tests__/GraphQuestionNode.spec.ts \
  src/components/graph/__tests__/GraphToolbar.spec.ts \
  src/components/workspace/__tests__/RouteSidebar.spec.ts
```

- [ ] **Step 3: Implement the smallest markup/accessibility changes**

Rules:

```text
Historical node: Q + title + at most two high-emphasis state markers.
Shared membership: one quiet count label.
Latest: compact but obvious.
Current answerable node: unchanged ownership of answer controls.
Route Focus: reading context.
Route Active: runtime context.
Toolbar: no added commands.
```

Do not rename backend/runtime terms inside store data; only user-facing copy/classes may change.

- [ ] **Step 4: Apply scoped visual polish in `style.css`**

Prefer existing CSS variables. Add new variables only when they reduce repetition and represent a stable semantic role.

Required qualities:

```css
/* Example intent, adapt to existing selectors/tokens. */
.graph-question-node:focus-within,
.route-sidebar__row:focus-visible,
.graph-toolbar button:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 2px;
}
```

If `--color-focus` does not exist, reuse an existing accessible accent token or define one once in the current token block. Do not hard-code multiple unrelated accent colors.

- [ ] **Step 5: Run focused tests**

Same command as Step 2; expected all pass.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/graph frontend/src/components/workspace/RouteSidebar.vue frontend/src/style.css

git commit -m "style(ui): refine graph and route hierarchy"
```

---

### Task 4: Final Inspector, Spec, Approval, and Recovery polish

**Files:**
- Modify: `WorkspaceInspector.vue`
- Modify: `NodeInspector.vue`
- Modify: `ProjectSummary.vue`
- Modify: `RequirementDetailView.vue`
- Modify: `SpecDock.vue`
- Modify: `AgentProposalCard.vue`
- Modify: `RecoveryNotice.vue`
- Modify: `style.css`
- Test: corresponding workspace specs.

**Interfaces:**
- Preserve current props/emits/actions.
- Preserve one Approval pair and one Recovery CTA maximum.
- Preserve Inspector summary → requirements secondary flow.
- Preserve Spec reading route vs Active generation route.

- [ ] **Step 1: Add/strengthen contract tests**

Required assertions:

```ts
expect(wrapper.findAll('[data-test="proposal-accept"]')).toHaveLength(1)
expect(wrapper.findAll('[data-test="proposal-reject"]')).toHaveLength(1)
expect(wrapper.text()).not.toContain('CREATE_NODE')
```

Recovery:

```ts
expect(wrapper.findAll('[data-test="recovery-action"]')).toHaveLength(1)
```

Inspector technical details must remain unmounted until disclosure is opened. Spec Dock collapsed/expanded tests must continue asserting the Graph-owning shell contract, not pixel-perfect CSS values.

- [ ] **Step 2: Run focused tests before implementation**

```bash
cd frontend
npm test -- \
  src/components/workspace/__tests__/WorkspaceInspector.spec.ts \
  src/components/workspace/__tests__/NodeInspector.spec.ts \
  src/components/workspace/__tests__/ProjectSummary.spec.ts \
  src/components/workspace/__tests__/RequirementDetailView.spec.ts \
  src/components/workspace/__tests__/SpecDock.spec.ts \
  src/components/workspace/__tests__/AgentProposalCard.spec.ts \
  src/components/workspace/__tests__/RecoveryNotice.spec.ts
```

- [ ] **Step 3: Implement hierarchy/overflow/accessibility polish without behavior changes**

Keep:

```text
Inspector = contextual surface, never navigator tabs.
Spec Dock = bottom support surface, default collapsed.
Approval = only when real pending proposal exists.
Recovery = only when real recovery state exists.
Technical metadata = explicit disclosure.
```

Use semantic headings and native `<details>` where already appropriate. Avoid introducing custom accordions or modal frameworks.

- [ ] **Step 4: Ensure long content behaves correctly**

Verify CSS contains appropriate combinations of:

```css
min-width: 0;
overflow-wrap: anywhere;
text-overflow: ellipsis;
overflow-y: auto;
```

Apply only where needed. Do not globally force `overflow: hidden` on content regions that need scrolling.

- [ ] **Step 5: Run focused tests**

Same command as Step 2; expected all pass.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/workspace frontend/src/style.css

git commit -m "style(ui): finish inspector and spec surfaces"
```

---

### Task 5: Accessibility, focus, reduced-motion, and sidebar geometry hardening

**Files:**
- Modify: `frontend/src/style.css`
- Modify only if proven necessary: `ResizableSidebar.vue`, `WorkspaceInspector.vue`, `SpecDock.vue`, `GraphToolbar.vue`, `RouteSidebar.vue`.
- Tests: relevant unit tests and E2E.

**Interfaces:**
- No product behavior change.
- Keyboard users must reach all product controls that mouse users can reach.

- [ ] **Step 1: Add focused keyboard/focus tests where component behavior changes**

Examples:

```ts
await wrapper.get('[data-test="open-requirements"]').trigger('click')
await nextTick()
expect(document.activeElement?.getAttribute('data-test')).toBe('requirement-back')
```

For icon-only buttons, assert non-empty `aria-label`.

- [ ] **Step 2: Add a reduced-motion media block if transitions remain**

Final CSS must include a scoped rule equivalent to:

```css
@media (prefers-reduced-motion: reduce) {
  .spec-dock,
  .spec-dock *,
  .workspace-shell,
  .workspace-shell * {
    scroll-behavior: auto;
  }
}
```

Do not blindly disable every browser animation if there are none. If specific transitions exist, set their duration to `0.01ms`/none inside the media query using the actual selectors.

- [ ] **Step 3: Verify focus-visible styles for all icon-only controls and sidebar resize handles**

If the resize handle is keyboard-operable, preserve that behavior and focus style. If it is mouse-only by design today, do not invent new resize semantics in this slice; ensure sidebar open/close controls remain keyboard accessible.

- [ ] **Step 4: Run unit/type verification**

```bash
cd frontend
npm test
npm run typecheck
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src

git commit -m "fix(ui): harden workspace accessibility and focus"
```

---

### Task 6: Desktop E2E geometry and real screenshot acceptance

**Files:**
- Modify: `frontend/e2e/workspace-layout.spec.ts`
- Modify: `frontend/e2e/spec.spec.ts`
- Modify if needed: `frontend/e2e/shared-focus.spec.ts`
- Create local ignored artifacts only: `frontend/test-results/ui-final/*.png`

**Interfaces:**
- E2E asserts product geometry and semantics, not implementation-specific CSS class names where a stable `data-test` exists.

- [ ] **Step 1: Extend workspace layout E2E to all target sizes**

For each viewport:

```ts
for (const viewport of [
  { width: 1366, height: 768 },
  { width: 1440, height: 900 },
  { width: 1920, height: 1080 },
]) {
  // set viewport, open deterministic project, assert sidebars and graph boxes do not overlap
}
```

Required geometry assertions:

```text
left.right <= graph.left
graph.right <= right.left
graph.width > 0
graph.height > 0
page scrollWidth <= viewport width + small rounding tolerance
```

Do not require an arbitrary exact pixel width that makes future harmless polish brittle.

- [ ] **Step 2: Keep the existing Spec expanded acceptance and strengthen 1366×768**

At the smallest viewport assert:

```text
Spec expanded is visible.
Graph canvas remains visible with positive height.
Inspector remains visible/scrollable.
Current question remains reachable.
```

- [ ] **Step 3: Run the focused E2E files**

```bash
cd frontend
npx playwright test e2e/workspace-layout.spec.ts e2e/spec.spec.ts e2e/shared-focus.spec.ts
```

Expected: all pass before screenshot capture.

- [ ] **Step 4: Capture real 1440×900 screenshots**

Use the same deterministic backend/test setup as E2E. Capture without adding screenshot-only production code:

```text
frontend/test-results/ui-final/default-graph-1440x900.png
frontend/test-results/ui-final/selected-node-1440x900.png
frontend/test-results/ui-final/spec-expanded-1440x900.png
frontend/test-results/ui-final/requirements-1440x900.png
frontend/test-results/ui-final/spec-expanded-1366x768.png
```

If the directory is not ignored already, write screenshots under Playwright's existing ignored `test-results` tree; do not change `.gitignore` merely for screenshots unless necessary.

- [ ] **Step 5: Inspect all five screenshots manually before proceeding**

Reject the visual pass if any screenshot shows:

```text
Graph visually smaller than support surfaces without necessity.
overlapping sidebars/panels.
clipped primary controls.
raw UUID/run/actionFamily/phase in normal UI.
multiple competing high-emphasis badge clusters.
Spec covering the whole Graph.
horizontal page overflow.
```

- [ ] **Step 6: Commit only E2E source changes, never screenshots**

```bash
git add frontend/e2e

git commit -m "test(ui): lock final desktop workspace acceptance"
```

---

### Task 7: Run installed design skills as constrained review passes

**Files:**
- Modify only frontend presentation files justified by findings.
- Do not modify product semantics.

**Interfaces:**
- `frontend-design` proposes visual refinement only.
- `web-design-guidelines` audits interface quality only.

- [ ] **Step 1: Invoke `frontend-design`**

Give it the frozen constraints:

```text
Graph-first.
No new features/panels/tabs/commands.
No dependencies or fonts.
Preserve all current semantics and tests.
Only improve typography, spacing, hierarchy, surfaces, density, focus/hover treatment.
```

Review every suggestion before applying it. Reject suggestions that add concepts or decorative complexity.

- [ ] **Step 2: Re-run focused unit/type/build after accepted visual changes**

```bash
cd frontend
npm test
npm run typecheck
npm run build
```

- [ ] **Step 3: Invoke `web-design-guidelines`**

Audit at minimum:

```text
keyboard/focus
aria/labels
contrast
forms/buttons
reduced motion
overflow/scroll
1366/1440/1920 desktop behavior
```

If the skill cannot fetch its remote rule source, perform the same categories manually and state that limitation in the final report.

- [ ] **Step 4: Fix only in-scope findings and rerun affected tests**

Do not make speculative fixes to Backend/Runtime or add new product controls.

- [ ] **Step 5: Commit**

If there are accepted changes:

```bash
git add frontend/src frontend/e2e

git commit -m "style(ui): apply final product quality review"
```

If there are no justified changes, do not create an empty commit.

---

### Task 8: Full verification, scope proof, push, and final product report

**Files:**
- No intended source changes unless verification exposes a real regression.

- [ ] **Step 1: Run full frontend verification fresh**

```bash
cd frontend
npm test
npm run typecheck
npm run build
npm run test:e2e
```

Record exact counts and exit results. Do not summarize as green until all commands complete successfully.

- [ ] **Step 2: Verify scope from repository root**

```bash
cd ..
git diff --name-only origin/main...HEAD

git diff --name-only origin/main...HEAD -- backend agent-brain
```

Second command must output nothing.

Also check:

```bash
git status --short
```

Expected: clean except local ignored screenshot artifacts, which do not appear in Git status.

- [ ] **Step 3: Confirm no floating-window production residue**

```bash
rg -n --glob '!docs/**' --glob '!frontend/node_modules/**' \
  'FloatingWindow|RouteNavigator|floatingWindowLayout|FloatingWindowPreference|WorkspaceUiPreferencesV2|DEFAULT_WORKSPACE_UI_V2|FLOATING_WINDOW_RANGES|floatingWindows|windowZOrder|setFloatingWindow|bringWindowToFront|resetWindows|persistWorkspaceV2|loadWorkspaceUiPreferencesV2|saveWorkspaceUiPreferencesV2' \
  frontend
```

Expected: zero current production references. If any artifact was intentionally retained due to a real consumer, report the exact file/reference and why.

- [ ] **Step 4: Push branch**

```bash
git push -u origin ui-ux-third-slice
```

Do not open PR and do not merge `main` unless separately requested.

- [ ] **Step 5: Final report format**

Report exactly:

```text
1. final HEAD SHA
2. branch + clean/dirty status
3. commit list by Task
4. production files changed/deleted
5. unit count/result
6. typecheck result
7. build result
8. E2E count/result
9. 1366/1440/1920 geometry result
10. frontend-design suggestions accepted/rejected
11. web-design-guidelines findings/fixes/limitations
12. legacy cleanup evidence and anything retained
13. exact paths to the five screenshots
14. remaining known issues, or “none”
15. confirmation: no Backend/Runtime/API/migration changes; not merged to main
```

The screenshots are part of the deliverable. A test-only report is incomplete.

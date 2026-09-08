# Spec Agent UI/UX Second Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Productize the existing Spec, Requirement State, Agent progress, Approval, and Recovery capabilities inside a Graph-first workspace without adding new product modules or changing backend/runtime semantics.

**Architecture:** Keep `workspaceStore` authoritative for canonical/runtime state. Add small presentation helpers that map existing state to user-facing labels/models, then render those models through focused workspace components. `WorkspaceView.vue` remains orchestration only; the Graph center owns the collapsible Spec Dock and transient status/recovery surfaces, while Inspector owns contextual object detail and secondary requirement views.

**Tech Stack:** Vue 3, TypeScript, Pinia, Vitest, Vue Test Utils, Vue Flow, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-08-ui-ux-second-slice-design.md`

## Global Constraints

- The Graph remains the visibly largest and primary work surface.
- Only Routes, Graph, Inspector, Agent State, and Spec are top-level workspace concepts.
- No Backend, Agent Runtime, API-contract, database-migration, or agent-brain changes.
- No new product modules or concept-image-only features.
- Focus Route must never mutate Runtime Active Route.
- Spec reading follows explicit Focus Route (or the only route); Spec generation always targets Runtime Active Route.
- Answer submission remains inside the current answerable Graph node only.
- Shared Nodes remain one canonical physical node.
- Requirement claim categories remain backend-derived; frontend must never promote or reinterpret them.
- Unknown mutation outcomes never auto-repeat; existing reconcile/idempotency/recovery actions remain authoritative.
- Default UI must not expose UUIDs, run ids, raw `actionFamily`, or raw Runtime phases.
- `frontend-design` may be used only after structural behavior is correct, for visual polish only.
- `web-design-guidelines` is a final review step; fixes must remain inside this slice.
- Preserve desktop behavior at 1366×768, 1440×900, and 1920×1080.

---

## File Structure

### Create

- `frontend/src/presentation/agentPresentation.ts` — pure mapping from Runtime phase/action family to product copy.
- `frontend/src/presentation/recoveryPresentation.ts` — pure priority mapping from existing store recovery/error state to one notice model.
- `frontend/src/components/workspace/SpecDock.vue` — collapsed/expanded Spec surface attached to Graph center.
- `frontend/src/components/workspace/ProjectSummary.vue` — no-selection Inspector summary.
- `frontend/src/components/workspace/RequirementDetailView.vue` — secondary full requirement-state view with hidden technical metadata.
- `frontend/src/components/workspace/AgentProposalCard.vue` — product-facing durable proposal card.
- `frontend/src/components/workspace/RecoveryNotice.vue` — renders one recovery notice and emits a semantic CTA intent.
- Matching unit/component tests beside each new module following existing repository conventions.

### Modify

- `frontend/src/views/WorkspaceView.vue` — center-column orchestration: Agent status, RecoveryNotice, GraphCanvas, SpecDock.
- `frontend/src/components/workspace/WorkspaceInspector.vue` — remove top-level tabs; switch among selected node/edge, project summary, and requirement secondary view.
- `frontend/src/components/workspace/NodeInspector.vue` — use `AgentProposalCard`, move technical details under disclosures, remove raw proposal action family display.
- `frontend/src/components/RequirementStatePanel.vue` — either reduce to reusable readable claim rendering or retire from direct workspace use without deleting backend semantics.
- `frontend/src/components/SpecSnapshotPanel.vue` and `SpecSnapshotList.vue` — reuse/reshape internals for SpecDock rather than keeping Spec as Inspector navigation.
- `frontend/src/graph/phaseCopy.ts` — replace raw-phase fallback with stable product copy or delegate to `agentPresentation.ts` while preserving any existing test/API consumers.
- `frontend/src/components/graph/GraphQuestionNode.vue` — compact Shared route membership presentation only; no new behavior.
- `frontend/src/style.css` — structural styles first, visual polish last.
- Existing unit tests and E2E specs that currently assume `详情 / 需求状态 / 规格` tabs or raw metadata visibility.

---

### Task 1: Pure Agent Presentation Mapping

**Files:**
- Create: `frontend/src/presentation/agentPresentation.ts`
- Create: `frontend/src/presentation/__tests__/agentPresentation.spec.ts`
- Modify: `frontend/src/graph/phaseCopy.ts`
- Modify/Test: existing phase-copy tests if present

**Interfaces:**
- Produces: `agentPhaseLabel(phase: string | null | undefined): string`
- Produces: `agentActionLabel(actionFamily: string | null | undefined): string`
- `agentPhaseLabel` must never include the raw unknown phase in fallback copy.
- `agentActionLabel` must return readable Chinese labels and a generic `执行操作` fallback for unknown/null families.

- [ ] **Step 1: Write failing mapping tests**

Cover at minimum:

```ts
expect(agentPhaseLabel('SNAPSHOT_BUILT')).toBe('正在分析上下文')
expect(agentPhaseLabel('STATE_UPDATING')).toBe('正在整理需求')
expect(agentPhaseLabel('DECIDING')).toBe('正在规划下一步')
expect(agentPhaseLabel('AWAITING_APPROVAL')).toBe('等待你的确认')
expect(agentPhaseLabel('WAITING_USER')).toBe('等待你的输入')
expect(agentPhaseLabel('SOME_NEW_INTERNAL_PHASE')).toBe('处理中…')
expect(agentPhaseLabel(null)).toBe('处理中…')

expect(agentActionLabel('CREATE_NODE')).toBe('创建节点')
expect(agentActionLabel('UPDATE_NODE')).toBe('更新节点')
expect(agentActionLabel('CONNECT_NODE')).toBe('连接节点')
expect(agentActionLabel('CREATE_ROUTE')).toBe('创建路线')
expect(agentActionLabel('REQUEST_USER_INPUT')).toBe('请求你的输入')
expect(agentActionLabel('RESPOND_TO_USER')).toBe('回复你')
expect(agentActionLabel('INVOKE_CAPABILITY')).toBe('运行能力')
expect(agentActionLabel('GENERATE_ARTIFACT')).toBe('生成产物')
expect(agentActionLabel('WAIT')).toBe('等待')
expect(agentActionLabel('UNKNOWN_ACTION')).toBe('执行操作')
```

- [ ] **Step 2: Run targeted tests and verify failure**

Run from `frontend/`:

```bash
npm test -- --run src/presentation/__tests__/agentPresentation.spec.ts
```

Expected: FAIL because helper does not exist.

- [ ] **Step 3: Implement the pure helper**

Use stable lookup tables only. Do not inspect store state or infer chain semantics here.

- [ ] **Step 4: Make `phaseCopy.ts` delegate to the stable mapping**

Keep exported compatibility functions required by existing callers/tests, but remove fallback copy of the form `处理中…（RAW_PHASE）`.

- [ ] **Step 5: Run targeted + existing phase tests**

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/presentation frontend/src/graph/phaseCopy.ts frontend/src/**/__tests__
git commit -m "refactor(ui): centralize agent presentation copy"
```

---

### Task 2: Pure Recovery Priority Model

**Files:**
- Create: `frontend/src/presentation/recoveryPresentation.ts`
- Create: `frontend/src/presentation/__tests__/recoveryPresentation.spec.ts`

**Interfaces:**

```ts
export type RecoveryAction =
  | 'reconcile-answer'
  | 'resume-answer'
  | 'resubmit-answer'
  | 'retry-model-operation'
  | 'refresh-workspace'

export interface RecoveryNoticeModel {
  kind: 'unknown' | 'saved' | 'resubmit' | 'retryable' | 'stale' | 'blocked'
  title: string
  message: string
  action: RecoveryAction | null
  actionLabel: string | null
}
```

`recoveryNoticeFromState(...)` consumes only existing explicit state/flags/codes supplied by the caller. It must not call store actions or infer whether a mutation landed.

Priority is frozen:

1. `answerOutcomeUnknown`
2. `repairableAnswerId`
3. `resubmitAnswerPayload`
4. explicit manual retry intent
5. stale error
6. policy/permission/non-retryable error

- [ ] **Step 1: Write failing priority tests**

Tests must prove that when multiple flags are present, the highest-priority safe notice wins, especially unknown outcome over resubmit.

- [ ] **Step 2: Run targeted tests and verify failure**

```bash
npm test -- --run src/presentation/__tests__/recoveryPresentation.spec.ts
```

- [ ] **Step 3: Implement minimal pure mapping**

Copy expectations:

```text
unknown: 提交结果暂时无法确认 / 为了避免重复操作，请先同步最新状态。 / 同步状态
saved: 回答已经保存 / 后续生成没有完成，不需要重新填写回答。 / 继续生成
resubmit: 回答尚未保存 / 已确认可以安全地再次提交。 / 再次提交
```

For stale/policy/permission, reuse existing error code classification where available; do not add backend code assumptions.

- [ ] **Step 4: Run tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/presentation
git commit -m "refactor(ui): model recovery presentation safely"
```

---

### Task 3: RecoveryNotice + Unified Center Status

**Files:**
- Create: `frontend/src/components/workspace/RecoveryNotice.vue`
- Create: `frontend/src/components/workspace/__tests__/RecoveryNotice.spec.ts`
- Modify: `frontend/src/views/WorkspaceView.vue`
- Modify: `frontend/src/views/__tests__/WorkspaceView.spec.ts`
- Modify: `frontend/src/style.css`

**Interfaces:**
- `RecoveryNotice` prop: `model: RecoveryNoticeModel`
- Emits: `action: [RecoveryAction]`
- `WorkspaceView` translates semantic action into existing store methods only:
  - `reconcile-answer` → existing reconcile action
  - `resume-answer` → `repairAnswerForActiveFlow(existingAnswerId)`
  - `resubmit-answer` → `resubmitFailedAnswer()`
  - `retry-model-operation` → `retryManualModelOperation()`
  - `refresh-workspace` → `refreshWorkspace()`

- [ ] **Step 1: Write component tests**

Assert one card, one primary CTA maximum, title/message rendering, and no raw error code unless already part of approved user copy.

- [ ] **Step 2: Write WorkspaceView failing tests**

Cover:
- unknown outcome renders only reconcile CTA;
- saved answer renders resume CTA and never resubmit;
- normal active Agent phase renders one concise status;
- unknown Agent phase renders `处理中…` without phase code.

- [ ] **Step 3: Run targeted tests and verify failure**

- [ ] **Step 4: Implement component + center-column integration**

Replace the current scattered `ApiErrorBanner` + three recovery blocks with one recovery presentation path where safe. Keep a plain non-recovery `ApiErrorBanner` only for ordinary errors not represented by RecoveryNotice.

Do not remove existing store state or command semantics.

- [ ] **Step 5: Ensure status/recovery stays inside `.workspace-shell__center`**

The Graph region remains the containing block; do not reintroduce whole-workspace overlays.

- [ ] **Step 6: Run targeted tests**

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/workspace/RecoveryNotice.vue frontend/src/components/workspace/__tests__/RecoveryNotice.spec.ts frontend/src/views/WorkspaceView.vue frontend/src/views/__tests__/WorkspaceView.spec.ts frontend/src/style.css
git commit -m "refactor(ui): unify agent and recovery feedback"
```

---

### Task 4: Inspector Becomes One Contextual Surface

**Files:**
- Create: `frontend/src/components/workspace/ProjectSummary.vue`
- Create: `frontend/src/components/workspace/RequirementDetailView.vue`
- Create: matching component tests
- Modify: `frontend/src/components/workspace/WorkspaceInspector.vue`
- Modify: `frontend/src/components/RequirementStatePanel.vue` only if useful as a rendering primitive; otherwise stop using it from WorkspaceInspector.
- Modify: existing WorkspaceInspector/Requirement tests

**Interfaces:**
- `ProjectSummary` receives already-loaded route requirement state and route label/context; it does not fetch.
- Emits `open-requirements`.
- `RequirementDetailView` receives `RequirementStateView | null`, route label, loading state.
- Emits `back`.
- Claim grouping uses backend arrays exactly as supplied: confirmed, unresolved, assumed, rejected.

- [ ] **Step 1: Write ProjectSummary failing tests**

Assert count summaries, readable route label, no route UUID, and `查看完整需求状态` intent.

- [ ] **Step 2: Write RequirementDetailView failing tests**

Assert readable claim text is visible by default while `sourceNodeId`, `sourceAnswerId`, raw kind, confidence, and route UUID are hidden until `技术详情` is expanded.

- [ ] **Step 3: Write WorkspaceInspector failing tests**

Required states:
- no selection → ProjectSummary;
- selected node → NodeInspector;
- selected edge → edge contextual view;
- ProjectSummary → RequirementDetailView → back;
- no top-level `详情 / 需求状态 / 规格` tabs;
- no Spec component inside Inspector.

- [ ] **Step 4: Run targeted tests and verify failure**

- [ ] **Step 5: Implement the contextual Inspector state machine**

A local presentation state such as `secondaryView = 'summary' | 'requirements'` is allowed. It must reset sensibly when selecting a node/edge, without changing Graph Focus or Runtime Active Route.

- [ ] **Step 6: Preserve current requirement loading semantics**

Keep route-scoped loading based on explicit reading route. A single-route fallback is allowed exactly as today. Do not guess a route for shared multi-route context.

- [ ] **Step 7: Run tests**

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/components/workspace frontend/src/components/RequirementStatePanel.vue
git commit -m "refactor(ui): make inspector contextual and secondary"
```

---

### Task 5: Productize Durable Approval in NodeInspector

**Files:**
- Create: `frontend/src/components/workspace/AgentProposalCard.vue`
- Create: `frontend/src/components/workspace/__tests__/AgentProposalCard.spec.ts`
- Modify: `frontend/src/components/workspace/NodeInspector.vue`
- Modify: `frontend/src/components/workspace/__tests__/NodeInspector.spec.ts`

**Interfaces:**
- Props include only safe existing data: `actionFamily`, `message`, readable node context, pending flags.
- Emits `accept` and `reject`.
- Uses `agentActionLabel()`.
- Never displays raw `proposalId`, `runId`, raw `actionFamily`, raw proposal status.

- [ ] **Step 1: Write failing AgentProposalCard tests**

Assert:
- `CREATE_NODE` renders `创建节点`, not `CREATE_NODE`;
- existing message renders when provided;
- no fabricated impact text;
- buttons are `确认执行` and `拒绝`;
- disabled state respects pending flags.

- [ ] **Step 2: Update NodeInspector tests first**

Require existing durable/reloaded proposal to render through the card and preserve accept/reject wiring to existing store methods.

- [ ] **Step 3: Run targeted tests and verify failure**

- [ ] **Step 4: Implement card and replace inline proposal block**

Keep Ask AI behavior and durable proposal reconnection logic unchanged.

- [ ] **Step 5: Move proposal/relations/provenance technical identifiers to `更多详情`**

If a related node cannot be resolved to readable text, prefer a generic `节点` label in the default view rather than a UUID slice; raw id may live under technical details.

- [ ] **Step 6: Run tests**

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/workspace/AgentProposalCard.vue frontend/src/components/workspace/NodeInspector.vue frontend/src/components/workspace/__tests__
git commit -m "refactor(ui): productize proposal approval"
```

---

### Task 6: Collapsible Graph-Center Spec Dock

**Files:**
- Create: `frontend/src/components/workspace/SpecDock.vue`
- Create: `frontend/src/components/workspace/__tests__/SpecDock.spec.ts`
- Modify: `frontend/src/views/WorkspaceView.vue`
- Modify: `frontend/src/components/SpecSnapshotPanel.vue`
- Modify: `frontend/src/components/SpecSnapshotList.vue`
- Modify: relevant existing Spec tests
- Modify: `frontend/src/style.css`

**Interfaces:**
- SpecDock receives:
  - reading route id/label
  - active route id/label
  - snapshots
  - selected snapshot id
  - generating/pending flags
- Emits:
  - `generate-spec`
  - `select-snapshot`
  - no route mutation intent
- WorkspaceView or a narrow adapter owns reading-route computation using existing explicit Focus/single-route semantics.

- [ ] **Step 1: Write failing collapsed-state tests**

Assert:
- default collapsed;
- collapsed height class/structure uses a compact summary;
- displays reading route label, latest/version status, section count, unresolved count when data exists;
- no raw ids/provenance.

- [ ] **Step 2: Write failing expanded-state tests**

Assert:
- Graph remains mounted when Dock expands;
- selected Spec sections render;
- unresolved items render;
- provenance is behind `来源与追溯` disclosure;
- snapshot history uses compact selector/list, not card-wall navigation;
- focus/read route is not mutated by opening Dock.

- [ ] **Step 3: Write route-semantics tests**

When reading route != Active Route, show both explicitly before generation. `generate-spec` remains wired to the existing Active-route store action.

- [ ] **Step 4: Run targeted tests and verify failure**

- [ ] **Step 5: Implement SpecDock**

Default collapsed height target: 44–52 px.
Expanded target: approximately 40% of Graph-center height, CSS capped at 45%. No drag resize.

- [ ] **Step 6: Remove Spec from WorkspaceInspector**

Do not delete Spec APIs or snapshot state. Reuse the existing Spec components as internals if that keeps responsibility clean; otherwise reshape them into presentational primitives.

- [ ] **Step 7: Update WorkspaceView center layout**

Recommended structure:

```text
.workspace-shell__center
  header/status/recovery
  .workspace-shell__graph-region
    GraphCanvas
  SpecDock
```

The right Inspector spans the workspace body independently; Spec Dock occupies only the center column.

- [ ] **Step 8: Run unit/component tests**

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/components/workspace/SpecDock.vue frontend/src/components/workspace/__tests__/SpecDock.spec.ts frontend/src/components/SpecSnapshotPanel.vue frontend/src/components/SpecSnapshotList.vue frontend/src/views/WorkspaceView.vue frontend/src/style.css frontend/src/**/__tests__
git commit -m "feat(ui): add graph-centered spec dock"
```

---

### Task 7: Graph Shared-Node Density Reduction

**Files:**
- Modify: `frontend/src/components/graph/GraphQuestionNode.vue`
- Modify: `frontend/src/components/graph/__tests__/GraphQuestionNode.spec.ts`
- Modify: `frontend/src/components/workspace/NodeInspector.vue` only if membership details need a readable home
- Modify: `frontend/src/style.css`

**Interfaces:**
- No new Graph command/state.
- Shared historical card shows membership count, not a chip per route.
- Exact memberships remain available in Inspector details.

- [ ] **Step 1: Write failing historical Shared-node test**

Given a historical shared node with 4 route memberships, assert the card contains a compact label equivalent to `共享 · 4 条路线` and does not render four permanent route chips.

- [ ] **Step 2: Preserve current-node behavior tests**

The current answerable node must remain expanded and answer submission must remain inside Graph.

- [ ] **Step 3: Run targeted tests and verify failure**

- [ ] **Step 4: Implement minimal presentation-only change**

Do not change graph projection identity, route membership data, coordinates, Focus, Active, Shared canonical semantics, or answer ownership.

- [ ] **Step 5: Run Graph tests**

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/graph/GraphQuestionNode.vue frontend/src/components/graph/__tests__/GraphQuestionNode.spec.ts frontend/src/components/workspace/NodeInspector.vue frontend/src/style.css
git commit -m "refactor(ui): reduce shared-node graph density"
```

---

### Task 8: E2E Migration and Product Acceptance

**Files:**
- Modify/Create E2E specs under `frontend/e2e/`
- Prefer extending `workspace-layout.spec.ts`, `spec.spec.ts`, `contextual-ai.spec.ts`, and recovery-focused existing specs rather than creating redundant suites.

**Required E2E coverage:**

- [ ] **Step 1: Graph-first collapsed Dock acceptance**

At 1366×768, 1440×900, 1920×1080:
- Routes, Graph, Inspector do not overlap;
- Spec Dock is collapsed by default;
- Graph remains the dominant region;
- current question remains clickable and answerable.

- [ ] **Step 2: Expanded Dock acceptance**

At 1366×768:
- expand Spec Dock;
- Graph remains visible with non-zero usable height;
- Inspector remains usable;
- collapse returns space without remounting/loss of Graph interaction state.

- [ ] **Step 3: Focus vs Active Spec semantics**

Use deterministic routes to establish Focus on one route while Runtime Active is another. Assert the Dock says which route is being read and which route generation targets. Generate and confirm the command still affects Active semantics only.

- [ ] **Step 4: Approval acceptance**

Use existing deterministic NodeQuery proposal flow. Assert human-readable action copy, no raw `CREATE_NODE` in the default approval card, accept/reject wiring, and continuation behavior stays functional.

- [ ] **Step 5: Recovery acceptance where deterministic fixtures support it**

At minimum preserve the existing answer-saved/continuation-failed and unknown-outcome safety assertions already present in the suite. Update selectors/copy to RecoveryNotice without weakening behavior checks.

- [ ] **Step 6: Default technical-noise scan**

For normal loaded workspace states, assert no visible UUID-like route/run identifiers, raw `actionFamily`, or raw phase strings in default first-screen UI. Do not assert against hidden technical disclosures when intentionally expanded.

- [ ] **Step 7: Run focused E2E first**

```bash
npm run test:e2e -- workspace-layout.spec.ts spec.spec.ts contextual-ai.spec.ts
```

Use the repository's actual Playwright filtering syntax if different.

- [ ] **Step 8: Run full frontend verification**

From `frontend/`:

```bash
npm test
npm run typecheck
npm run build
npm run test:e2e
```

Expected: all pass.

- [ ] **Step 9: Scope verification**

```bash
git diff --name-only origin/main...HEAD
```

Expected: only `docs/` and `frontend/`. No `backend/`, `agent-brain/`, migrations, contracts, or API schema changes.

- [ ] **Step 10: Commit E2E migration/final structural fixes**

```bash
git add frontend/e2e frontend/src
git commit -m "test(ui): validate second-slice graph-first workspace"
```

---

### Task 9: Controlled Visual Polish with Installed Skills

**Files:**
- Modify only files already touched by this slice unless a very small shared style primitive is demonstrably needed.
- Primarily `frontend/src/style.css` and scoped styles in new components.

**Interfaces:**
- No product-behavior changes.
- No new navigation, modules, panels, buttons, or capabilities.

- [ ] **Step 1: Invoke ZCode `frontend-design` skill**

Give it the design spec and scope freeze. Explicit instruction:

```text
Improve only typography, spacing, visual hierarchy, surfaces, borders/shadows,
hover/focus states, state emphasis, and composition. Graph must remain dominant.
Do not add features, navigation, dashboards, notifications, collaboration,
new panels, new data, or decorative chrome.
```

- [ ] **Step 2: Review the skill's proposed changes before applying**

Reject any change that adds a product capability or violates the five top-level concepts.

- [ ] **Step 3: Apply the minimal accepted visual changes**

Prefer consistent tokens/spacing over one-off decorative CSS.

- [ ] **Step 4: Run `web-design-guidelines` skill**

Review all changed frontend files for accessibility, focus, forms, motion, responsive behavior, and interface quality.

- [ ] **Step 5: Fix only in-scope findings**

Mandatory categories to fix if found:
- missing accessible labels
- invisible keyboard focus
- color-only state distinctions
- unusable overflow/scroll keyboard behavior
- unsafe reduced-motion transitions
- Dock/Inspector focus loss caused by toggling views

Do not expand scope for generic architectural suggestions.

- [ ] **Step 6: Re-run full verification**

```bash
npm test
npm run typecheck
npm run build
npm run test:e2e
```

- [ ] **Step 7: Commit polish**

```bash
git add frontend/src frontend/e2e
git commit -m "style(ui): polish graph-first second slice"
```

---

### Task 10: Final Verification and Handoff

**Files:** No intentional product changes unless verification exposes a regression.

- [ ] **Step 1: Run final clean-tree verification**

```bash
git status --short
npm test
npm run typecheck
npm run build
npm run test:e2e
```

- [ ] **Step 2: Verify frozen semantics explicitly**

Run/confirm regressions for:
- clarification/current answer flow
- input persistence
- fork/reanswer/regenerate
- lifecycle/route visibility
- contextual AI + durable proposal reload
- connection/relation
- Focus vs Active
- Shared answer ownership
- Spec generation targeting
- recovery/idempotency/reconciliation

- [ ] **Step 3: Verify three desktop viewports manually or through Playwright geometry assertions**

1366×768, 1440×900, 1920×1080.

- [ ] **Step 4: Confirm default UI noise constraints**

No raw UUID, run id, raw `actionFamily`, or raw Runtime phase visible in default workspace UI.

- [ ] **Step 5: Confirm scope**

```bash
git diff --name-only origin/main...HEAD
```

No backend/runtime/API/migration changes.

- [ ] **Step 6: Produce final report**

Report:
- final HEAD SHA
- commits created
- files changed by area
- unit/typecheck/build/E2E results
- viewport acceptance
- skill review findings/fixes
- scope verification
- known non-blocking leftovers only

Do not merge to `main`. Push `ui-ux-second-slice` and stop for external review.

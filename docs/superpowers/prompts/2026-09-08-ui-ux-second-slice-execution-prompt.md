# ZCode Execution Prompt — Spec Agent UI/UX Second Slice

You are implementing the approved Spec Agent UI/UX second slice on branch `ui-ux-second-slice`.

## Required reading before touching code

Read these files in full:

1. `docs/superpowers/specs/2026-09-08-ui-ux-second-slice-design.md`
2. `docs/superpowers/plans/2026-09-08-ui-ux-second-slice.md`
3. `docs/ui/UI_SCOPE_FREEZE.md`
4. `docs/ui/UI_UX_AUDIT.md`
5. `docs/superpowers/specs/2026-09-07-ui-ux-deepening-design.md`

Also inspect the current implementation and tests before editing:

- `frontend/src/views/WorkspaceView.vue`
- `frontend/src/components/workspace/WorkspaceInspector.vue`
- `frontend/src/components/workspace/NodeInspector.vue`
- `frontend/src/components/RequirementStatePanel.vue`
- `frontend/src/components/SpecSnapshotPanel.vue`
- `frontend/src/components/SpecSnapshotList.vue`
- `frontend/src/components/graph/GraphQuestionNode.vue`
- `frontend/src/graph/phaseCopy.ts`
- `frontend/src/stores/workspaceStore.ts`
- relevant existing unit tests and `frontend/e2e/`

## Branch / safety preflight

Before implementation:

```bash
git fetch origin
git checkout ui-ux-second-slice
git pull --ff-only origin ui-ux-second-slice
git status --short
git log -5 --oneline
```

Required state:

- branch is `ui-ux-second-slice`
- working tree is clean
- branch contains the approved design and implementation plan
- do not merge to `main`

If the working tree contains unexpected user changes, STOP and report them instead of overwriting them.

## Execution mode

Execute the complete implementation plan continuously from Task 1 through Task 10.

Use TDD for every behavior change:

1. write/adjust the failing test;
2. run it and observe the expected failure;
3. implement the smallest correct change;
4. run the focused test until green;
5. commit a coherent slice;
6. continue to the next task.

Do not stop after each normal task to ask for permission. Continue automatically unless one of these blockers occurs:

- the approved UI requires a backend/API/Runtime capability that does not exist;
- a required semantic cannot be preserved without touching backend/runtime/contracts/migrations;
- destructive or unexplained local changes are present;
- tests reveal a real frozen-semantic conflict that the plan does not resolve;
- full verification remains red after a concrete debugging pass.

If a blocker occurs, STOP with evidence and the smallest decision required.

## Non-negotiable product rules

The product is a **Graph-first AI workspace**.

The workspace may have rich functionality, but the default interface only exposes what the current task needs.

Only these five top-level concepts are allowed:

1. Routes
2. Graph
3. Inspector
4. Agent State
5. Spec

Do not add a sixth permanent panel or top-level navigation concept.

### Do not add

- comments
- chat sidebar
- notifications
- research tab
- notes tab
- design tab
- collaboration
- sharing/permissions UI
- command palette
- agent persona/config UI
- analytics dashboard
- activity-log console
- decorative dashboard cards
- invented proposal impact text
- new backend-derived fields

### Do not change

- Backend
- Agent Runtime
- API contracts
- database migrations
- agent-brain
- Focus Route semantics
- Runtime Active Route semantics
- Shared Node canonical identity
- answer ownership/submission rules
- recovery/idempotency/reconciliation semantics

## Required UX result

### Workspace

Keep the fixed first-slice shell:

```text
Routes | Graph | Inspector
        | Spec Dock below Graph center only
```

Graph must remain visually dominant.

### Spec Dock

- collapsed by default;
- approximately 44–52px collapsed;
- expanded approximately 38–42% of center region, max 45%;
- Graph remains visible above it;
- no drag resize this slice;
- reading route follows explicit Focus / only-route fallback;
- generation targets Runtime Active Route only;
- when reading != Active, clearly display both;
- provenance/raw ids hidden behind `来源与追溯`.

### Inspector

Remove top-level `详情 / 需求状态 / 规格` tabs.

States:

- selected node → contextual Node Inspector
- selected edge → contextual Edge Inspector
- no selection → lightweight Project Summary
- Project Summary → secondary Requirement Detail → back

Do not put answer submission in Inspector.

### Requirement State

Default summary shows only readable counts and current route context.

Full detail groups backend arrays exactly:

- 已确认
- 未解决
- 假定
- 已拒绝

Raw kind/confidence/source node/source answer/route ids are under `技术详情`, not default UI.

### Agent State

Use one concise product-facing status.

Never expose raw unknown phases. Unknown fallback is exactly a generic form such as `处理中…`.

### Approval

Use existing durable NodeQuery proposal data only.

Default card shows:

- human-readable action label
- existing message when available
- node context
- `拒绝`
- `确认执行`

Do not show raw `actionFamily`, `proposalId`, `runId`, or internal status.

After approval, preserve existing continuation behavior; do not assume the accepted action is terminal.

### Recovery

Create one presentation model with this priority:

1. outcome unknown → reconcile first, never re-execute
2. answer saved / continuation incomplete → continue generation
3. canonical answer absent → safe resubmit
4. retryable model operation → retry
5. stale → refresh/reconcile
6. policy/permission/non-retryable → explain, no meaningless retry

At most one primary recovery CTA is visible.

### Shared Graph nodes

Do not add Graph features.

Historical Shared nodes should summarize membership as e.g. `共享 · 4 条路线`, not render many permanent route chips. Exact membership remains available in Inspector.

## Presentation architecture

Keep `workspaceStore` authoritative.

Pure presentation helpers may map existing state to user-facing models/copy, but they must never mutate runtime state or infer missing backend facts.

Prefer focused units from the plan, including:

- `presentation/agentPresentation.ts`
- `presentation/recoveryPresentation.ts`
- `workspace/SpecDock.vue`
- `workspace/ProjectSummary.vue`
- `workspace/RequirementDetailView.vue`
- `workspace/AgentProposalCard.vue`
- `workspace/RecoveryNotice.vue`

Do not turn `WorkspaceView.vue` into another monolith.

## Installed ZCode skills

Two global skills are already installed:

- `frontend-design`
- `web-design-guidelines`

Do NOT invoke `frontend-design` at the beginning.

First complete Tasks 1–8 and get structural behavior/tests green.

Then Task 9:

### `frontend-design`

Use it only for:

- typography
- spacing
- visual hierarchy
- surfaces
- borders/shadows
- hover/focus states
- restrained state emphasis
- composition

Explicitly tell the skill:

> Do not add product features, navigation, panels, dashboards, collaboration, notifications, new data, or decorative chrome. Graph must remain visually dominant. The approved Spec Agent design spec and UI_SCOPE_FREEZE override all aesthetic suggestions.

Reject any skill suggestion that changes product scope.

### `web-design-guidelines`

After implementation/polish, review the changed frontend for:

- keyboard reachability
- visible focus
- accessible names / aria for icon controls
- contrast
- forms
- scroll/overflow usability
- reduced motion
- focus preservation when Dock/Inspector views toggle
- desktop responsive quality

Fix only findings inside the approved slice.

## Commit discipline

Use small coherent commits aligned to the plan. Suggested sequence:

```text
refactor(ui): centralize agent presentation copy
refactor(ui): model recovery presentation safely
refactor(ui): unify agent and recovery feedback
refactor(ui): make inspector contextual and secondary
refactor(ui): productize proposal approval
feat(ui): add graph-centered spec dock
refactor(ui): reduce shared-node graph density
test(ui): validate second-slice graph-first workspace
style(ui): polish graph-first second slice
```

A small regression-fix commit is acceptable after full verification.

Do not squash locally. Do not merge to main.

## Test requirements

Run focused tests after each task.

Before final report, from `frontend/` run:

```bash
npm test
npm run typecheck
npm run build
npm run test:e2e
```

Also verify desktop behavior at:

- 1366×768
- 1440×900
- 1920×1080

Frozen semantic regressions must remain green:

- current clarification/answer flow
- input persistence
- fork
- reanswer
- regenerate
- lifecycle / route display
- contextual AI
- durable proposal reload
- connection / semantic relation
- Focus Route vs Runtime Active Route
- Shared answer ownership
- Spec generation targeting
- recovery/idempotency/reconciliation

## Scope check

Before final report:

```bash
git diff --name-only origin/main...HEAD
```

Expected implementation scope: only `frontend/` plus the already-approved `docs/` files on this branch.

There must be no changes under:

- `backend/`
- `agent-brain/`
- migrations
- contracts/API schemas

## Push / stop condition

When everything is green:

```bash
git status
git push -u origin ui-ux-second-slice
```

Do not create or merge the PR unless explicitly asked. Stop after push and report the final HEAD.

## Final report format

Return one consolidated report containing:

1. **Conclusion** — whether Tasks 1–10 are complete.
2. **Final HEAD SHA** and working-tree state.
3. **Implementation summary** by task.
4. **Production files changed**.
5. **Tests changed/added**.
6. **E2E changed/added**.
7. **Verification results**:
   - unit file/test counts
   - typecheck
   - build
   - Playwright count
   - three viewport acceptance
8. **Skill usage**:
   - what `frontend-design` changed
   - what `web-design-guidelines` found and what was fixed
9. **Scope verification** — explicit confirmation backend/runtime/API/migrations untouched.
10. **Known non-blocking leftovers** only.
11. Confirm branch was pushed and **not merged to main**.

Do not report success if any required final command is still failing.

# Branch Graph + Floating Workspace Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved Branch Graph + Floating Workspace redesign in one integrated correction round: durable branch provenance and frozen inherited history, explicit-source Fork/Re-answer/Replace Question semantics, visual graph identity, layout V2, floating Route Navigator/Inspector, and full regression coverage.

**Architecture:** Runtime remains authoritative for IDs, route lifecycle, active route, provenance, persistence, validation, history, and context. Branch routes are represented as a frozen inherited prefix plus route-local continuation; inherited history references immutable source Answers/Patches rather than cloning them. The frontend projects canonical Runtime facts into stable visual node identities, deduplicates lineage edges by visual endpoints, persists only browser presentation state keyed by `visualNodeKey`, and keeps floating windows completely independent of graph canvas layout.

**Tech Stack:** Java 21, Spring Boot 3.3.2, Spring Data JDBC, Flyway, PostgreSQL/Testcontainers, Vue 3.5, Pinia 2.3, TypeScript 5.8, Vue Flow 1.48.2, Vitest 3.1, Playwright 1.62.

**Spec:** `docs/superpowers/specs/2026-08-19-branch-graph-floating-workspace-redesign-design.md`

## Global Constraints

- Phase 8 remains not started.
- Keep `@vue-flow/core` at exactly `1.48.2`; do not upgrade it.
- Do not add Dagre/ELK or arbitrary DAG editing.
- Model proposes; Runtime validates, persists, and owns history.
- Context is lineage, not global chat history.
- SpecSnapshot remains derived state and Active-route owned.
- Focus is browser-only and never implicitly activates a Runtime route.
- Historical Nodes and Answers remain immutable.
- Fork must not clone old Answer/Patch rows.
- Re-answer keeps the same canonical Question and creates a distinct visual instance.
- Replace Question remains deterministic/manual replacement, not AI reroll.
- Active route cannot be hidden; hiding the Focus route clears Focus.
- Existing free drag, group drag, adaptive four-side edge routing, curved directed edges, ArrowClosed markers, drag-time reroute, viewport preservation, and explicit Locate behavior must remain.
- No automatic whole-graph fit or automatic global relayout on route/node/filter/focus changes.
- Deliver as one integrated correction round; do not push intermediate product slices for manual user testing.

---

## File Structure Map

### Backend runtime and persistence

- `backend/src/main/resources/db/migration/V4__route_branch_provenance.sql` — durable branch provenance and frozen inherited Answer references.
- `backend/src/main/java/com/specagent/route/RouteBranchType.java` — `FORK | REANSWER | REGENERATE`.
- `backend/src/main/java/com/specagent/route/RouteInheritedAnswer.java` — one frozen inherited Answer reference in a branch prefix.
- `backend/src/main/java/com/specagent/route/RouteInheritedAnswerRepository.java` — deterministic persistence/read of inherited prefix rows.
- `backend/src/main/java/com/specagent/route/RouteHistoryResolver.java` — single source of truth for effective route answers and inherited-prefix snapshotting.
- `backend/src/main/java/com/specagent/route/Route.java` — add durable branch provenance fields while preserving existing legacy metadata.
- `backend/src/main/java/com/specagent/route/RouteRepository.java` — persist/read new Route fields.
- `backend/src/main/java/com/specagent/route/RouteService.java` — explicit-source Fork/Re-answer/Replace Question commands.
- `backend/src/main/java/com/specagent/context/ContextBuilder.java` — build context from effective route history.

### Backend API/read model

- `backend/src/main/java/com/specagent/api/route/ForkRouteRequest.java` — require explicit `sourceRouteId`.
- `backend/src/main/java/com/specagent/api/route/ReanswerRouteRequest.java` — new request contract.
- `backend/src/main/java/com/specagent/api/route/RegenerateNodeRequest.java` — add explicit `sourceRouteId`.
- `backend/src/main/java/com/specagent/api/route/RouteCommandController.java` — expose Re-answer and explicit-source branch endpoints.
- `backend/src/main/java/com/specagent/api/route/RouteCommandService.java` — validation/error mapping around branch commands.
- `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceQueryService.java` — expose effective inherited answers and provenance.
- `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceRouteView.java` — expose `branchType`, `sourceRouteId`, `branchAtNodeId`.
- `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceAnswerView.java` — distinguish traversing route from immutable Answer owner.

### Frontend graph/runtime bridge

- `frontend/src/api/types.ts` — branch/read-model API types.
- `frontend/src/api/routes.ts` — explicit-source Fork/Re-answer/Replace Question requests.
- `frontend/src/stores/workspaceStore.ts` — integrated branch commands, Fork→Draft partial-success state.
- `frontend/src/graph/graphVisualIdentity.ts` — stable visual identity derived only from Runtime provenance.
- `frontend/src/graph/graphProjection.ts` — project visual instances and dedupe physical edges by visual endpoints.
- `frontend/src/graph/graphLayout.ts` — place new visual nodes without moving existing ones; explicit Auto Layout.
- `frontend/src/graph/graphTypes.ts` — V2 browser layout and floating-window types.
- `frontend/src/graph/graphLayoutStorage.ts` — V2 storage namespace and floating-window persistence.
- `frontend/src/stores/graphUiStore.ts` — selection/positions keyed by `visualNodeKey`, shared-edge selection, floating-window state.

### Frontend workspace UI

- `frontend/src/components/workspace/FloatingWindow.vue` — reusable floating panel abstraction.
- `frontend/src/components/workspace/RouteNavigator.vue` — Route list/navigation/runtime controls.
- `frontend/src/components/workspace/WorkspaceInspector.vue` — node/shared-edge details and full branch actions.
- `frontend/src/components/graph/GraphQuestionNode.vue` — cleaner graph card and compact historical-node action strip.
- `frontend/src/components/graph/GraphCanvas.vue` — visual-key selection, shared-edge selection, locate/reveal, explicit Auto Layout.
- `frontend/src/components/graph/GraphToolbar.vue` — Routes/Inspector reopen, Auto Layout, reset windows.
- `frontend/src/views/WorkspaceView.vue` — full-screen GraphCanvas plus two floating windows.
- `frontend/src/components/ReanswerRouteDialog.vue` — explicit-source Re-answer UI.
- `frontend/src/components/ForkRouteDialog.vue` — explicit source selection and finalized-answer prerequisites.
- `frontend/src/components/RegenerateNodeDialog.vue` — explicit source selection and deterministic replacement copy.

---

### Task 1: Durable Branch Provenance and Frozen Inherited Prefix

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__route_branch_provenance.sql`
- Create: `backend/src/main/java/com/specagent/route/RouteBranchType.java`
- Create: `backend/src/main/java/com/specagent/route/RouteInheritedAnswer.java`
- Create: `backend/src/main/java/com/specagent/route/RouteInheritedAnswerRepository.java`
- Create: `backend/src/main/java/com/specagent/route/RouteHistoryResolver.java`
- Modify: `backend/src/main/java/com/specagent/route/Route.java`
- Modify: `backend/src/main/java/com/specagent/route/RouteRepository.java`
- Test: `backend/src/test/java/com/specagent/route/RouteControlIntegrationTest.java`

**Interfaces:**
- Produces: `RouteBranchType { FORK, REANSWER, REGENERATE }`.
- Produces: Route provenance accessors `branchType()`, `sourceRouteId()`, `branchAtNodeId()`.
- Produces: `RouteHistoryResolver.snapshotInheritedPrefix(newRouteId, sourceRouteId, throughNodeId, includeThroughNode)`.
- Produces: `RouteHistoryResolver.resolveEffectiveAnswerRefs(routeId, lineageNodeIds)` returning ordered effective Answer references without cloning Answer rows.

- [ ] **Step 1: Write failing persistence/inheritance tests.**

Cover: original route has null branch provenance; Fork snapshot through Q2 contains Q1 and Q2 Answer refs; Re-answer snapshot through Q2 excludes Q2; inherited refs survive source-route lifecycle changes; a chained branch snapshots the source route's effective inherited+local history rather than only `answers.route_id = sourceRouteId`.

- [ ] **Step 2: Run the focused backend tests and confirm they fail for missing schema/types/resolver.**

Run:

```bash
cd backend
./gradlew test --tests 'com.specagent.route.RouteControlIntegrationTest'
```

- [ ] **Step 3: Add Flyway V4.**

Add nullable Route provenance columns and an inherited-answer table containing at least:

```text
branch_route_id
ordinal
node_id
answer_id
owner_route_id
```

Use foreign keys consistent with the existing runtime schema. `branch_route_id + ordinal` must define deterministic prefix order; do not duplicate Answer or Patch payloads.

- [ ] **Step 4: Implement `RouteBranchType`, Route mapping, inherited-answer repository, and `RouteHistoryResolver`.**

The resolver must merge frozen inherited refs with route-local Answers in lineage order and must never query sibling/global history as fallback.

- [ ] **Step 5: Re-run focused tests until green.**

- [ ] **Step 6: Keep this task local; do not push an intermediate product slice.**

---

### Task 2: Explicit-Source Fork and Re-answer Commands

**Files:**
- Modify: `backend/src/main/java/com/specagent/route/RouteService.java`
- Modify: `backend/src/main/java/com/specagent/api/route/ForkRouteRequest.java`
- Create: `backend/src/main/java/com/specagent/api/route/ReanswerRouteRequest.java`
- Modify: `backend/src/main/java/com/specagent/api/route/RouteCommandController.java`
- Modify: `backend/src/main/java/com/specagent/api/route/RouteCommandService.java`
- Modify: `backend/src/test/java/com/specagent/api/route/RouteForkApiIntegrationTest.java`
- Create: `backend/src/test/java/com/specagent/api/route/RouteReanswerApiIntegrationTest.java`

**Interfaces:**
- Produces: `Route forkFromNode(UUID projectId, UUID sourceRouteId, UUID sourceNodeId, String label)`.
- Produces: `Route reanswerFromNode(UUID projectId, UUID sourceRouteId, UUID targetNodeId, String label)`.
- Consumes: `RouteHistoryResolver.snapshotInheritedPrefix(...)` from Task 1.

- [ ] **Step 1: Rewrite Fork API tests to require `sourceRouteId`.**

Assert Runtime rejects wrong-project routes, non-OPEN routes, a node outside the explicit source route, and a branch point without a finalized effective Answer.

- [ ] **Step 2: Add Re-answer integration tests.**

Assert the new route keeps the same canonical target Node, inherits only the parent prefix, has no target Answer, leaves the old Answer/route unchanged, records `REANSWER` provenance, and becomes Active.

- [ ] **Step 3: Run the two API test classes and confirm failure.**

```bash
cd backend
./gradlew test --tests 'com.specagent.api.route.RouteForkApiIntegrationTest' --tests 'com.specagent.api.route.RouteReanswerApiIntegrationTest'
```

- [ ] **Step 4: Remove branch-command dependence on `findSourceRouteForNode(...)`.**

Every branch command must validate the explicit Route provided by the caller. Do not use Active/latest/first-route fallback when a canonical node is shared.

- [ ] **Step 5: Implement Fork.**

Fork snapshots the source effective history through the branch node inclusive, creates an `OPEN` route with `branchType=FORK`, `sourceRouteId`, `branchAtNodeId`, `tip=sourceNodeId`, leaves the source route untouched, and activates the new route.

- [ ] **Step 6: Implement Re-answer.**

Re-answer snapshots source effective history through the target's parent only, creates an `OPEN` route with `tip=targetNodeId`, `branchType=REANSWER`, and no target Answer, then activates it. The canonical Node is not cloned.

- [ ] **Step 7: Re-run focused tests until green.**

---

### Task 3: Explicit-Source Deterministic Replace Question and Context Semantics

**Files:**
- Modify: `backend/src/main/java/com/specagent/route/RouteService.java`
- Modify: `backend/src/main/java/com/specagent/api/route/RegenerateNodeRequest.java`
- Modify: `backend/src/main/java/com/specagent/api/route/RouteCommandController.java`
- Modify: `backend/src/main/java/com/specagent/api/route/RouteCommandService.java`
- Modify: `backend/src/main/java/com/specagent/context/ContextBuilder.java`
- Test: existing regenerate/context integration tests

**Interfaces:**
- Produces: explicit-source deterministic replacement command using `sourceRouteId`.
- Consumes: `RouteHistoryResolver.resolveEffectiveAnswerRefs(...)`.

- [ ] **Step 1: Extend regenerate tests for explicit source and frozen parent-prefix context.**

Verify Q2' uses `Q2.parentNodeId`, old route becomes `SUPERSEDED`, replacement route becomes `OPEN + Active`, and replacement context excludes Q2, Q2's Answer/Patch, Q2's child subtree, sibling conclusions, and old SpecSnapshot.

- [ ] **Step 2: Run the regenerate/context tests and confirm failure before implementation.**

- [ ] **Step 3: Make regenerate source route explicit.**

Do not call the old guessing helper. Record `branchType=REGENERATE`, `sourceRouteId`, `branchAtNodeId=targetNodeId` while keeping existing replacement metadata such as `supersedesRouteId` / `replacementOfNodeId` for compatibility.

- [ ] **Step 4: Update `ContextBuilder.buildFromActiveRoute(...)` to use effective route history.**

Normal context must be:

```text
canonical root→tip lineage
+ frozen inherited Answer refs
+ route-local Answers
+ Patches derived from the effective Answer IDs
```

- [ ] **Step 5: Keep `buildForRegenerate(...)` parent-lineage-only and use effective source history for that parent prefix.**

- [ ] **Step 6: Re-run focused tests until green.**

---

### Task 4: Graph Read Model Exposes Effective History and Provenance

**Files:**
- Modify: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceQueryService.java`
- Modify: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceRouteView.java`
- Modify: `backend/src/main/java/com/specagent/readmodel/graph/GraphWorkspaceAnswerView.java`
- Modify: `backend/src/test/java/com/specagent/readmodel/graph/GraphWorkspaceQueryServiceTest.java`

**Interfaces:**
- Produces route fields: `branchType`, `sourceRouteId`, `branchAtNodeId`.
- Produces effective answer presentation fields: traversing/logical `routeId`, immutable `ownerRouteId`, `inherited`.

- [ ] **Step 1: Add failing read-model tests for Fork inherited Answers and Re-answer waiting state.**

Fork route traversing Q2 must expose the inherited effective Answer under the Fork route's logical membership while preserving the original Answer owner's route ID. Re-answer target must expose no Answer for the new route.

- [ ] **Step 2: Run the graph read-model tests and confirm failure.**

- [ ] **Step 3: Replace direct `answerService.findAnswersForRouteAndNodeIds(route.id(), ...)` graph semantics with the shared effective-history resolver.**

- [ ] **Step 4: Expose provenance fields without moving Runtime authority into the read model.**

- [ ] **Step 5: Re-run focused tests until green.**

---

### Task 5: Frontend API Contracts and Workspace Commands

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/routes.ts`
- Modify: `frontend/src/stores/workspaceStore.ts`
- Modify: existing API/store unit tests

**Interfaces:**
- Produces explicit-source payloads for Fork, Re-answer, and Replace Question.
- Produces `PendingRouteCommand` value `'reanswer'`.
- Produces recoverable Fork→Draft partial-success state.

- [ ] **Step 1: Add failing API/store tests.**

Assert all branch commands send explicit `sourceRouteId`; the store never substitutes Focus/Active/latest as a hidden source; Re-answer refreshes canonical reads; successful Fork followed by failed Draft preserves the route and exposes retry state.

- [ ] **Step 2: Run targeted Vitest tests and confirm failure.**

```bash
cd frontend
npm run test:unit -- --run
```

- [ ] **Step 3: Update types and route API functions.**

- [ ] **Step 4: Implement workspace-store branch actions.**

Fork product flow is two Runtime commands:

```text
Fork command succeeds
→ canonical refresh
→ normal Draft command on new Active route
→ canonical refresh
```

If Draft fails, do not rollback Fork, fabricate a Node, or reactivate the old route. Preserve a retryable branch-end state.

- [ ] **Step 5: Re-run affected unit tests until green.**

---

### Task 6: Stable Visual Identity and Visual-Endpoint Edge Projection

**Files:**
- Create: `frontend/src/graph/graphVisualIdentity.ts`
- Create: `frontend/src/graph/__tests__/graphVisualIdentity.spec.ts`
- Modify: `frontend/src/graph/graphProjection.ts`
- Modify: `frontend/src/graph/__tests__/graphProjection.spec.ts`

**Interfaces:**
- Produces: `visualNodeKey` for every route/node traversal.
- Produces: projected visual node data containing `canonicalNodeId` and `visualNodeKey`.
- Produces: physical lineage-edge dedupe by `visualSourceKey -> visualTargetKey`.

- [ ] **Step 1: Add visual-identity tests for ordinary Fork, Re-answer, and chained Fork-from-Re-answer.**

Required examples:

```text
Fork at Q2: Q1/Q2 visual keys shared; divergence begins at new Q3 child.
Re-answer Q2: same canonical Q2, distinct visual keys immediately.
Fork from the Re-answer route at Q2: new Fork shares the Re-answer visual Q2, never merges back to original Q2/A.
```

- [ ] **Step 2: Add edge tests.**

Same visual endpoints produce one physical edge with multiple route IDs; divergent visual endpoints produce separate edges; later equal answers/lifecycle/filter/focus changes never recompute branch identity.

- [ ] **Step 3: Run focused tests and confirm failure.**

- [ ] **Step 4: Implement `graphVisualIdentity.ts` using only Runtime provenance.**

A stable encoding may use base keys like `node:<canonicalNodeId>` and Re-answer-qualified keys, but the algorithm must inherit the source visual path through `sourceRouteId` so branch identity remains monotonic.

- [ ] **Step 5: Refactor `projectGraph(...)`.**

Vue Flow node `id` becomes `visualNodeKey`, not canonical Node ID. Replacement edges remain visually distinct from lineage edges.

- [ ] **Step 6: Re-run focused tests until green.**

---

### Task 7: Layout V2 and Visual-Key UI State

**Files:**
- Modify: `frontend/src/graph/graphTypes.ts`
- Modify: `frontend/src/graph/graphLayout.ts`
- Modify: `frontend/src/graph/graphLayoutStorage.ts`
- Modify: `frontend/src/stores/graphUiStore.ts`
- Modify: related graph layout/store tests

**Interfaces:**
- Produces: `ProjectGraphPreferencesV2` keyed by `visualNodeKey`.
- Produces: visual-key node selection and explicit shared-edge selection.
- Produces: floating-window preference state used by Task 8.

- [ ] **Step 1: Add failing V2 storage tests.**

V1 canonical-node positions are not guessed into V2 visual identities. One deterministic relayout on first V2 use is acceptable.

- [ ] **Step 2: Convert `selectedNodeIds`, primary selection, expanded state, and node positions to visual keys.**

Keep Runtime node IDs available inside projected node data for command/API targeting.

- [ ] **Step 3: Preserve incremental layout rules.**

Saved coordinates win byte-for-byte. New branch creation only places genuinely new visual keys near the parent/branch lane; it never repositions existing dragged nodes.

- [ ] **Step 4: Add explicit Auto Layout API.**

Only the user-triggered command may reapply deterministic suggested layout. Active/Focus/filter/route count/node count refreshes must not trigger global layout or whole-graph fit.

- [ ] **Step 5: Re-run layout/store tests until green.**

---

### Task 8: Floating Window Workspace

**Files:**
- Create: `frontend/src/components/workspace/FloatingWindow.vue`
- Create: `frontend/src/components/workspace/RouteNavigator.vue`
- Create: `frontend/src/components/workspace/__tests__/FloatingWindow.spec.ts`
- Modify: `frontend/src/components/workspace/WorkspaceInspector.vue`
- Modify: `frontend/src/views/WorkspaceView.vue`
- Modify: workspace component tests
- Stop using: `frontend/src/components/workspace/ResizableSidebar.vue`
- Stop using: `frontend/src/components/workspace/RouteSidebar.vue`

**Interfaces:**
- Produces: floating-window state `{ x, y, width, height, open }` plus z-order.
- Produces: Route Navigator row click = Focus + Locate only; Runtime lifecycle/activation actions remain explicit.

- [ ] **Step 1: Add failing `FloatingWindow` unit tests.**

Cover title-bar-only drag, edge/corner resize, subtle edge snap, viewport clamp, click-to-front, close/reopen, persistence, viewport resize recovery, and reset.

- [ ] **Step 2: Add workspace tests proving GraphCanvas dimensions do not change when either window moves/resizes/opens/closes.**

- [ ] **Step 3: Implement `FloatingWindow.vue`.**

Its body scrolls independently. Moving/resizing must not cause graph relayout or canvas reflow.

- [ ] **Step 4: Replace sidebar assembly in `WorkspaceView.vue`.**

Structure becomes:

```text
full-screen GraphCanvas
+ FloatingWindow(RouteNavigator)
+ FloatingWindow(WorkspaceInspector)
```

The Graph toolbar stays a compact fixed floating canvas control, not a third draggable panel.

- [ ] **Step 5: Implement Route Navigator.**

Show lifecycle, Active, Focus, provenance, Locate/Focus/Dim/Hide/Show All, Activate/Archive/Restore/Delete. Normal route-row click performs Focus + Locate and never Activate.

- [ ] **Step 6: Re-run workspace tests until green.**

---

### Task 9: Node Actions, Inspector, Shared-Edge Selection, and Branch Dialogs

**Files:**
- Create: `frontend/src/components/ReanswerRouteDialog.vue`
- Modify: `frontend/src/components/ForkRouteDialog.vue`
- Modify: `frontend/src/components/RegenerateNodeDialog.vue`
- Modify: `frontend/src/components/graph/GraphQuestionNode.vue`
- Modify: `frontend/src/components/graph/GraphCanvas.vue`
- Modify: `frontend/src/components/graph/GraphToolbar.vue`
- Modify: `frontend/src/components/workspace/WorkspaceInspector.vue`
- Modify: `frontend/src/views/WorkspaceView.vue`
- Modify: related component tests

**Interfaces:**
- Produces compact graph actions: `从这里开新路线`, `重新选择答案`, `创建替代问题`.
- Produces full Inspector copy: `我接受现在，换未来。`, `问题没错，答案换一个。`, `问题本身换掉。`.
- Produces shared-edge Inspector selection without implicit route guessing.

- [ ] **Step 1: Add failing GraphQuestionNode/Inspector/Canvas tests.**

Graph cards should no longer carry verbose route chooser/history content. Selected/hover historical nodes expose the three compact actions. Inspector shows full Question/Answer/purpose/options/provenance/related routes and all three full branch actions.

- [ ] **Step 2: Add shared-edge click tests.**

Single-route edge may Focus its route. Multi-route edge selects the shared segment and leaves Focus unchanged.

- [ ] **Step 3: Implement explicit source-route selection in branch dialogs.**

When several candidate source routes traverse a visual node, the user chooses one. Never auto-pick Active, Focus, latest, or first.

- [ ] **Step 4: Implement deterministic replacement copy.**

Use `创建替代问题` / `替换这个问题`; do not imply random AI regeneration.

- [ ] **Step 5: Add toolbar controls for Auto Layout, Routes, Inspector, and Reset Windows while preserving existing zoom/locate utilities.**

- [ ] **Step 6: Re-run affected component tests until green.**

---

### Task 10: Integrated E2E and Full Regression

**Files:**
- Create: `frontend/e2e/reanswer.spec.ts`
- Create: `frontend/e2e/floating-workspace.spec.ts`
- Modify: `frontend/e2e/fork.spec.ts`
- Modify: `frontend/e2e/graph-layout.spec.ts`
- Modify: `frontend/e2e/graph-routes.spec.ts`
- Modify: `frontend/e2e/regenerate.spec.ts`
- Preserve: existing core clarification, lifecycle, spec, viewport, drag, adaptive-edge coverage

- [ ] **Step 1: Add E2E for Fork shared branch point and automatic first-child Draft.**

Assert Q2 stays one visual node, Q3-B creates the first divergent edge, source Q2 Answer is visible as inherited effective history, and existing dragged positions do not move.

- [ ] **Step 2: Add Fork partial-success E2E.**

Force Draft failure after successful Fork; assert the route remains canonical/Active, no fake child appears, and `重试生成` retries the normal Draft command.

- [ ] **Step 3: Add Re-answer E2E.**

Assert same canonical Q2 appears as distinct visual instances, the new one is directly answerable, old Answer remains, and equal new Answer text does not merge the branches.

- [ ] **Step 4: Expand regenerate E2E.**

Assert Q2' is same-depth sibling, replacement relation is visually distinct, and copy says deterministic replacement rather than AI reroll.

- [ ] **Step 5: Add floating workspace E2E.**

Cover drag, resize, snap, clamp, z-order, close/reopen, persistence after reload, reset windows, and unchanged GraphCanvas dimensions.

- [ ] **Step 6: Re-run all frontend unit/E2E and backend tests.**

Frontend:

```bash
cd frontend
npm run typecheck
npm run test:unit -- --run
npm run build
npm run test:e2e
```

Backend:

```bash
cd backend
./gradlew test
```

Repository checks:

```bash
git diff --check
git status --short
```

- [ ] **Step 7: Fix every regression before claiming completion.**

Do not delete or weaken meaningful tests to get green. Confirm Spec generation is still Active-route owned and Focus differs from Active only as a browser reading context.

---

## Final Integrated Delivery

After all tasks and the complete regression suite are green:

```bash
git add -A
git commit -m "feat: redesign branch graph and floating workspace"
git push origin main
git fetch origin main
test "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)"
```

Final report must include:

1. final commit SHA;
2. `origin/main` SHA;
3. files added/modified/deleted;
4. frozen inherited-prefix implementation;
5. explicit-source Fork behavior;
6. Re-answer behavior;
7. Replace Question behavior;
8. visual identity strategy;
9. visual-endpoint edge projection;
10. layout V2 behavior;
11. floating-window behavior;
12. Fork→Draft partial-success behavior;
13. exact verification commands and results;
14. any intentionally unchanged/out-of-scope items.

Do not start Phase 8.
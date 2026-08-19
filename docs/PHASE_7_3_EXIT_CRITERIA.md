# Phase 7.3 — Graph-First Workspace (Exit Criteria)

Status: closed, verified, committed, pushed

- Phase 7.3 final baseline: `6a4a6c5b3852d17ae3bbb4c05f9b5d412cb495e1`
  `fix: close graph workspace UX gaps`
- Final verification run at closeout: frontend typecheck + unit + production
  build + Playwright E2E, backend `./gradlew.bat cleanTest test` — all green.

## 1. Phase Scope

Phase 7.3 replaced the Phase 7.2 route-oriented workspace with the
graph-first workspace as the current frontend workspace, while keeping the
Runtime as the owner of history, lifecycle and the active pointer:

```text
Phase 7.3 features (already landed before this closeout):
  canonical graph workspace read model + API (backend read-only surface)
  route-scoped requirement-state read
  graph workspace UI state (browser-only: focus/dim/hide/filters/positions)
  interactive graph canvas (Vue Flow), graph is the primary workspace
  answers submitted directly inside graph nodes
  Chinese UI localization
  E2E coverage for the graph workspace

Phase 7.3 closeout (this change):
  Focus Route is always a visible route
  NodeInspector Fork/Regenerate only for historical nodes
  Phase 7.3 closure documentation
```

Phase 8 (CI/hardening) has not begun.

## 2. Accepted Baseline

The closeout started from the accepted baseline
`6a4a6c5b3852d17ae3bbb4c05f9b5d412cb495e1` with a clean working tree and
`HEAD == origin/main`. No Runtime code and no backend code were modified in
this closeout; all changes are frontend-only plus documentation.

## 3. Closeout Item 1 — Focus Route Is Always a Visible Route

Browser-only invariant enforced without touching Runtime or backend:

- Hiding the focused route (manual Hide) clears Focus first, then hides.
- Turning off the lifecycle filter whose lifecycle the focused route belongs
  to clears Focus before the filter takes effect.
- Focus can never be produced for a hidden or filtered-out route:
  - `graphUiStore.setFocusRoute` rejects a manually hidden route.
  - `RouteSidebar.toggleFocus` rejects hidden and filtered-out routes
    (route lifecycle is a Runtime fact, so the filter half lives in the
    sidebar, not in the browser-only UI store).
  - `graphUiStore.reconcile` (every canonical refresh) also clears Focus on
    a route that is lifecycle-filtered out or manually hidden, repairing any
    persisted stale state.

Unit coverage added:

- `graphUiStore.spec.ts`: hiding the focused route clears focus before
  hiding; focus never points at a manually hidden route; reconcile clears
  focus on a manually hidden focused route.
- `RouteSidebar.spec.ts`: turning off the lifecycle filter of the focused
  route clears focus first; focus never selects a filtered-out or hidden
  route.

E2E coverage added to the existing `graph-routes.spec.ts` scenario
`focus, dim, hide, show-all and active protection on a two-route graph`:

```text
Focus A → Hide A → Focus 被清除 (button back to 聚焦此路线, no focused class)
```

## 4. Closeout Item 2 — NodeInspector Fork/Regenerate Only for Historical Nodes

The current pending node (the answerable active node) keeps its full read-only
detail view in the inspector — question, purpose, options, route ownership,
per-route answered/waiting states — but no longer offers `从此分支` or
`重新生成这个问题`. Answer submission remains exclusively inside the Graph
node; the inspector never provides a second answer surface.

Unit coverage added (`WorkspaceInspector.spec.ts`):

- current pending node (`canAnswer: true`): details still render, no
  inspector-fork, no inspector-regenerate, no answer form.
- historical node (`canAnswer: false`): inspector-fork and
  inspector-regenerate are present.

## 5. Verification Results (actual final run)

Frontend (`cd frontend`):

```text
npm run typecheck      vue-tsc --noEmit          clean
npm run test:unit -- --run   24 files, 225 tests, all passed
npm run build          vue-tsc --noEmit && vite build   built in ~1s, no errors
npm run test:e2e       7 spec files, 14 tests, all passed (real local backend,
                       fake model gateway, zero public model requests)
```

Backend (`cd backend`):

```text
./gradlew.bat cleanTest test
  BUILD SUCCESSFUL in 43s
  67 test classes, 419 tests, 0 failures, 0 errors, 4 skipped
  (skipped: OpenCodeZenLiveSmokeTest ×2, OpenCodeZenRealFullLoopSmokeTest ×1,
   OpenCodeZenRouteIsolationSmokeTest ×1 — real-provider smoke tests gated on
   SPEC_AGENT_OPENCODE_KEY, skipped by default; zero public model requests)
```

The E2E suite ran against Docker Compose PostgreSQL (port 5434) and the local
backend (`SPEC_AGENT_MODEL_GATEWAY=fake`, the default).

## 6. Final Invariants

```text
Focus Route is always a visible route; focusRouteId never points at a
hidden or lifecycle-filtered-out route (enforced on every UI path and
repaired on every canonical refresh).
Fork / Regenerate act only on historical nodes; the current pending node
keeps read-only details in the inspector and answers only inside its
Graph node.
Graph-first workspace is the current frontend workspace.
Runtime remains the source of truth for history, route lifecycle and
Project.activeRouteId; the frontend only reads them.
No Runtime code and no backend code changed during the Phase 7.3 closeout.
Phase 8 (CI/hardening) has not begun.
```

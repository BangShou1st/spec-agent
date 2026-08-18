# Phase 7 — Frontend First Version (Exit Criteria)

Status: implemented, tested, E2E-verified, committed, pushed

- Accepted Phase 7.1 baseline: `d52a65a12e2286680a1ec18632c1e3994f553895`
  `fix: close phase 7.1 architecture and build gaps`
- Phase 7.2 implementation commit: see `git log` (`feat: complete route workspace and spec frontend`)

## 1. Phase Scope

Phase 7 completes the first frontend version as a full route-oriented
requirement clarification product over the frozen Phase 6 backend APIs. It has
two sub-phases:

```text
Phase 7.1 — frontend foundation: project list/create, workspace shell, core
            clarification loop, requirement-state read endpoint, read-only
            route visibility
Phase 7.2 — route workspace + spec closure: route history, historical node
            inspection, activate / fork / restore / archive / soft-delete /
            deterministic regenerate, requirement-state refresh, spec
            generation, spec snapshot history, source references, unit +
            Playwright E2E coverage
```

Phase 7.2 is the FINAL Phase 7 phase. Phase 8 (CI/hardening) has not begun.

## 2. Accepted Baseline

Phase 7.2 started from the accepted Phase 7.1 baseline commit
`d52a65a12e2286680a1ec18632c1e3994f553895` with a clean working tree and
`HEAD == origin/main`. The Windows Gradle wrapper fix from that baseline is in
effect: `.\gradlew.bat test` returns exit code 0 on success.

## 3. Frontend Architecture

```text
frontend/src/
  api/            typed HTTP modules — the only place fetch() lives
    client.ts     one typed client + API error contract parsing
    types.ts      TypeScript contracts mirroring the real backend DTOs
    projects.ts   GET/POST /api/v1/projects
    workspace.ts  active state, routes, draft-next, submit answer
    routes.ts     route commands + route-lineage read (Phase 7.2)
    spec.ts       spec generation + snapshot reads (Phase 7.2)
    requirementState.ts  GET requirement-state
  components/     focused UI components (RouteWorkspacePanel, RouteLineage,
                  HistoricalNodePanel, RouteActionMenu, ForkRouteDialog,
                  RegenerateNodeDialog, ConfirmRouteActionDialog,
                  WorkspaceRightPanel, SpecSnapshotPanel, SpecSnapshotList,
                  plus the Phase 7.1 ClarificationPanel / RequirementStatePanel)
  stores/         Pinia application state (projectStore, workspaceStore)
  views/          page-level coordination (ProjectsView, WorkspaceView)
  router/         Vue Router routes
  e2e/            Playwright specs + shared helpers
```

Views coordinate page-level behavior; components are primarily presentational
and interactivity-focused; stores own application/workflow state; API modules
own every HTTP call. There is no graph/canvas library, no large UI framework,
no SSE/WebSocket, and no background job infrastructure.

## 4. Workspace Layout

```text
┌────────────────────┬──────────────────────────┬──────────────────────┐
│ Route Workspace    │ Clarification            │ Requirement State /  │
│ (routes + lineage  │ (center) OR              │ Spec Snapshots       │
│  + lifecycle acts) │ Historical Node Panel    │ (tabbed, right)      │
└────────────────────┴──────────────────────────┴──────────────────────┘
```

- Left: routes grouped for readability — open + superseded visible normally;
  archived/deleted under a collapsed "Archived / Deleted" section. Deleted
  routes are never removed from frontend state; the backend stays
  authoritative.
- Center: the active clarification workflow by default; a HistoricalNodePanel
  replaces it while a historical node is selected, with a clear
  `Back to active question` action. Historical inspection never submits an
  answer to a historical node.
- Right: tabs preserve the Phase 7.1 Requirement State panel and add Spec
  Snapshots (selected route's snapshot history + generation for the ACTIVE
  route).

## 5. Route Lineage Read API (the only Phase 7.2 backend feature)

The frontend must not reconstruct authoritative lineage itself, so exactly one
narrow, read-only UI-support endpoint was added:

```http
GET /api/v1/projects/{projectId}/routes/{routeId}/lineage
```

It is provider-free, model-free, and persistence-free; it only inspects an
existing route. Architecture:

```text
com.specagent.api.route.RouteLineageController
        ↓
com.specagent.readmodel.route.RouteLineageQueryService
        ↓
ProjectService / RouteService / NodeService
```

- The controller does NOT use a repository directly.
- `readmodel..` gained no dependency on `api..` (ArchUnit green).
- No `ContextSnapshot` is created or persisted for the read.
- The read never calls `ModelGateway` and never changes `ContextBuilder`
  semantics.

Response shape (`RouteLineageView`, root→tip node order, safe fields only):

```json
{
  "projectId": "...",
  "routeId": "...",
  "rootNodeId": "...",
  "tipNodeId": "...",
  "lifecycleStatus": "open | superseded | archived | deleted",
  "isActive": true,
  "nodes": [
    {
      "id": "...",
      "projectId": "...",
      "parentNodeId": null,
      "supersedesNodeId": null,
      "question": "...",
      "purpose": "...",
      "options": [{ "id": "...", "label": "...", "impact": "..." }],
      "allowFreeAnswer": true,
      "createdAt": "..."
    }
  ]
}
```

Answers, patches, `ContextSnapshot`s, `ModelRequest`/`ModelResponse`, provider
data, and database metadata are never exposed. The route tree carries only
enough information to identify and inspect a historical clarification node
before fork/regenerate.

Semantics:

1. validates project exists
2. validates route exists
3. validates route belongs to the project
4. permits inspection for every lifecycle: open / superseded / archived / deleted
5. `tipNodeId == null` → empty node list
6. follows `parentNodeId` from tip to root
7. returns root→tip order
8. fails closed (`INTERNAL_INVARIANT_VIOLATION`, 500) when a node is missing
9. fails closed when a node belongs to another project (no foreign data in the
   response)
10. detects cycles and unreasonable depth (defensive bound 10 000 nodes)
11. verifies `route.rootNodeId` matches the resolved lineage when non-null

Replacement routes naturally expose parent lineage + replacement node; a
superseded target node is never injected into the replacement lineage merely
because `supersedesNodeId` points at it.

Expected public behavior:

```text
missing project            → 404 PROJECT_NOT_FOUND
missing/foreign route      → 404 ROUTE_NOT_FOUND
broken lineage integrity   → 500 INTERNAL_INVARIANT_VIOLATION
```

Raw exception messages are never exposed. Query failures use the closed
`RouteLineageQueryException` reason model (PROJECT_NOT_FOUND, ROUTE_NOT_FOUND,
INVARIANT_VIOLATION) mapped only at the API edge.

## 6. Route History Behavior

- The workspace opens on the backend-active route; lineage is loaded lazily
  from the route-lineage endpoint per selected route (never a global project
  history load).
- Historical node selection shows question, purpose, options, option impacts,
  provenance (node id, parent, supersedes, createdAt), whether the node is the
  current tip, and whether it supersedes another node. Nodes are immutable —
  no in-place editing.
- Route list state is never assembled locally: after every mutation the
  canonical backend reads (project, active state, routes, requirement state)
  are refreshed and affected lineage/spec caches are invalidated/reloaded.

## 7. Route Lifecycle UI

Lifecycle stays `open | superseded | archived | deleted`; there is NO `active`
lifecycle status. The active route is `Project.activeRouteId` surfaced as
`RouteResponse.isActive`. The lifecycle badge and the Active indicator are
visually and semantically separate (unit-tested).

Actions per lifecycle (backend remains authoritative; the UI hides clearly
invalid actions and still handles 400/409 through the typed error boundary):

```text
OPEN non-active             → Activate
SUPERSEDED/ARCHIVED/DELETED → Restore
non-ARCHIVED/non-DELETED    → Archive
non-DELETED                 → Delete
```

Archive and Delete require an explicit confirmation dialog. Delete copy states
this is a soft-delete of the route, historical runtime records are preserved,
and shared nodes/answers are not physically deleted.

## 8. Fork UI Semantics

```http
POST /api/v1/projects/{projectId}/nodes/{nodeId}/fork
{ "label": "...optional..." }
```

The request contains only the user-controlled label; runtime-owned fields
(routeId, rootNodeId, tipNodeId, createdFromNodeId, activeRouteId,
lifecycleStatus) are never sent. The runtime creates the new route id and makes
it active. After success the frontend refreshes the canonical reads, selects
the backend-created active route, reloads its lineage, and returns to the
active clarification view. No nodes/answers/patches are copied client-side.

## 9. Deterministic Regenerate UI Semantics

```http
POST /api/v1/projects/{projectId}/nodes/{nodeId}/regenerate
```

Regenerate is deterministic — there is NO model call. The request carries only
user-controlled content:

```json
{
  "instruction": "optional, max 2000",
  "replacementQuestion": "required, max 4000",
  "replacementPurpose": "optional, max 4000",
  "replacementOptions": [
    { "label": "required, max 500", "impact": "optional, max 2000" }
  ]
}
```

- The form prefills `replacementQuestion`, `replacementPurpose`, and option
  labels/impacts from the historical node — content only. Option ids are never
  copied; the runtime generates new ids.
- `replacementNodeId`, `replacementRouteId`, `contextSnapshotId`, supersedes
  ids, source refs, and provenance are never exposed or sent.
- Root regeneration is unsupported (`parentNodeId == null`): the button is
  disabled with "Root question regeneration is not supported."
- Regenerate is enabled only when the selected route is the active OPEN route;
  a non-active OPEN route says "Activate this route first", a
  SUPERSEDED/ARCHIVED/DELETED route says "Restore this route first". Nothing
  silently activates or restores the route as a side effect of opening the
  form.
- After success: the old route becomes SUPERSEDED, the replacement route
  becomes OPEN + active, and the replacement node becomes the active tip — via
  the runtime. The frontend refreshes backend reads, selects the replacement
  route, loads its lineage, clears the old selection, and shows the
  replacement active clarification state.

Regenerate isolation remains frozen: old target answer, old target patch, old
child subtree, sibling conclusions, SpecSnapshot-derived content, and global
chat history stay excluded from regenerate context; no model is called.

## 10. Requirement-State Behavior

`GET /api/v1/projects/{projectId}/requirement-state` remains canonical. After
activate/restore/archive/delete/fork/regenerate the frontend refreshes it; it
never reuses stale requirement state from a previous active route — route
isolation stays visible in UI behavior (fork/regenerate/restore switch the
requirement state to the new active route; archive/delete of the active route
clears it via the backend's safe empty read model).

## 11. Spec Generation

```http
POST /api/v1/projects/{projectId}/specs/generate
GET  /api/v1/projects/{projectId}/routes/{routeId}/specs
GET  /api/v1/specs/{snapshotId}
```

The Generate button means "Generate spec snapshot for ACTIVE route" and is
enabled only when a valid active route/tip exists. While running it shows
"Generating spec…" and duplicate commands are prevented. The frontend never
authors a SpecSnapshot; after success it reloads the snapshot list for the
active route and selects the new snapshot.

## 12. Spec Snapshot History and Presentation

- Snapshots are explicitly labeled `Derived Spec Snapshot` — never
  "source of truth".
- History shows multiple snapshots for the same (SELECTED) route (which may
  differ from the active route), newest-first for presentation only.
- Old snapshots are never removed from the browser when a new one is
  generated; the latest is never treated as canonical project state.
- Each snapshot displays created time, route, tip node, sections, unresolved
  items, source references, and subdued provenance metadata (createdByRunId,
  contextSnapshotId).
- Sections are rendered faithfully; content is never reinterpreted as new
  requirement state, and suggestions/assumptions are never promoted to facts.

## 13. Source Reference Display

Every source reference is displayed as `kind:refId` (the backend fields
verbatim). No source descriptions are invented client-side. When a reference
identifies a node already present in the loaded lineage, a future iteration may
offer local navigation; Phase 7.2 adds no generic source-resolution backend.

## 14. Command Concurrency

A workspace-level command lock prevents double route mutations: exactly one
precise route command (`activate | restore | archive | delete | fork |
regenerate`) can be in flight, answer submission and question drafting are
blocked while a route command runs, and route commands are blocked while an
answer/draft is pending. Spec generation has its own pending state and never
races a route command. No distributed locking or async job infrastructure was
added.

## 15. Error Handling

The Phase 7.1 typed `ApiError` boundary is reused. Route/spec errors (400
validation, 404 missing resource, 409 lifecycle conflict, 422 model contract
rejection, 429 rate limit, 502 provider failure, 503 provider unavailable/
configuration, 504 timeout) render the backend's safe sanitized message only.
Raw response bodies, provider implementation details, and OpenCode mentions
are never exposed unless the public API does.

## 16. Frontend Unit Tests

Vitest + Vue Test Utils, mocking the `src/api/*` boundary. The suite (129
tests) covers, among others:

- route lifecycle badge vs active indicator (separate, never `open == active`)
- lineage lazy loading + caching
- historical route selection and historical node selection
- Back to active question
- activate/restore/archive/delete request + canonical backend refresh
- archive/delete confirmation dialogs with correct copy
- fork request contains label only; fork success selects backend-created route
- regenerate root disabled; regenerate requires the active OPEN route
- regenerate form copies labels/impacts but NEVER option ids
- regenerate request contains no runtime-owned ids
- regenerate success refreshes canonical backend state
- spec list loading by selected route; generation loading/duplicate prevention
- generated snapshot becomes selected; source references display
- SpecSnapshot clearly marked derived; old snapshots retained
- provider/API errors remain safely rendered

## 17. Playwright E2E

`npm run test:e2e` runs a small `@playwright/test` suite in `frontend/e2e/`
(browser-visible behavior only; no database inspection). E2E uses local
PostgreSQL, the real local backend
(`SPEC_AGENT_MODEL_GATEWAY=fake`, the default — zero public OpenCode requests,
no OpenCode key required) and the Vite dev server (port 5174). The backend is
started separately; Playwright's `webServer` starts only the frontend.

Windows-friendly local commands:

```powershell
terminal 1:
cd E:\spec-agent
docker compose up -d

terminal 2:
cd E:\spec-agent\backend
.\gradlew.bat bootRun

terminal 3:
cd E:\spec-agent\frontend
npm ci
npx playwright install chromium   # once per machine; browsers are never committed
npm run test:e2e
```

Flows:

- core clarification: create project → open workspace → draft → answer →
  next node appears → requirement state updates
- fork: build a 3-node lineage → select historical node → Fork from here →
  label → new route active, old route remains, lineage ends at selected node
- regenerate: build root → answered non-root → later node → regenerate the
  answered non-root node → old route SUPERSEDED, replacement OPEN + ACTIVE,
  replacement question shown, old route visible, replacement lineage without
  the old child subtree
- lifecycle: archive active route → no active route; restore → OPEN + ACTIVE;
  soft-delete → DELETED still recoverable; restore → OPEN + ACTIVE
- spec: on a clarified route generate a snapshot → snapshot appears, sections
  render, source references render, unresolved items render, labeled derived,
  snapshot history contains the new snapshot

Class counts: 5 E2E tests (one per flow).

## 18. Backend Regression

- 14 new route-lineage integration tests: empty tip, root→tip order, foreign/
  unknown route 404, project 404, superseded/archived/deleted inspectable,
  fork shares nodes without copies, replacement lineage excludes old subtree,
  foreign/missing/cyclic/root-mismatch lineage fails closed with no foreign
  data exposed.
- Full `.\gradlew.bat test` (`$LASTEXITCODE`): BUILD SUCCESSFUL / 0. All
  Phase 0–7.1 tests green, ArchUnit green, new lineage tests green.

## 19. Network Behavior

The default gateway remains `SPEC_AGENT_MODEL_GATEWAY=fake`. The full backend
regression, all frontend unit tests, and the Playwright E2E suite make zero
public OpenCode requests. Real-provider smoke tests stay gated on
`SPEC_AGENT_OPENCODE_KEY` and are skipped by default. No provider keys ever
reach the frontend.

## 20. Deferred (Not in Phase 7)

Authentication, accounts, multi-user collaboration, roles/permissions, document
upload, RAG, vector database, browser tool execution, code generation, plugin
marketplace, provider management/credential UI, provider registry/router/
fallback, automatic retry, JSON repair, parser relaxation, model-drafted
regenerate, SSE, WebSocket, job queue, background workers, complex canvas,
React Flow, domain templates, analytics platform, CI workflow (Phase 8).

## 21. Final Invariants

```text
Runtime remains the source of truth for history.
Context remains lineage-scoped.
SpecSnapshot remains a derived artifact, never source of truth.
Frontend never writes Runtime history directly.
Active is not a route lifecycle status.
The route-lineage endpoint is read-only, route-scoped, and inspects any lifecycle.
Controllers never use repositories directly; readmodel.. never depends on api..
No model call during deterministic regenerate; regenerate isolation unchanged.
```
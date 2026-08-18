# Phase 7.1 — Frontend Foundation + Core Clarification Loop

Status: implemented, tested, committed, pushed (phase open for external review)

- Accepted baseline: `8e0e66ba9b5c39510a94cae2f2833c6a2ddc8317`
  `fix: enforce regenerate option validation`
- Phase 7.1 commit: see `git log` (`feat: add frontend clarification workspace`)
- Phase 7.2 (route workspace + spec closure, the FINAL Phase 7 phase):
  [`PHASE_7_EXIT_CRITERIA.md`](PHASE_7_EXIT_CRITERIA.md)

## 1. Scope

Phase 7.1 builds the first usable frontend over the frozen Phase 6 backend
APIs and proves the core clarification loop:

```text
Project list
→ create project
→ open workspace
→ inspect active route/state
→ draft first clarification question (explicit user action)
→ view focused node
→ select option and/or enter free text
→ submit answer
→ Runtime persists answer/patch and drafts next node
→ frontend refreshes active state
→ requirement-state panel reflects backend-derived state
```

Phase 7.1 is NOT the full route-management UI. Fork, regenerate, restore,
archive, delete, historical navigation, and spec-generation UI are deferred to
Phase 7.2.

The frontend is a client of the Runtime. It never recreates Runtime semantics
in JavaScript: it never builds history, never replays patches, never derives
route state, and never treats any local value as source of truth. Persistent
workspace state always comes from backend reads.

## 2. Phase Numbering Note

`docs/IMPLEMENTATION_PLAN.md` contains an older numbering scheme where the
frontend was previously called Phase 6. That document predates the accepted
Phase 6 Backend API Application Layer phase. The accepted history is:

```text
Phase 5 — Real Model Runtime
Phase 6 — Backend Application API Layer
Phase 7 — Frontend First Version
```

Old historical commits and planning documents are not renumbered.

## 3. Frontend Stack

```text
Vue 3
TypeScript
Vite
Pinia
Vue Router
Vitest
Vue Test Utils
npm
```

Standard stable packages only. No large UI framework, no React/Next/Nuxt/
Angular, no Element Plus/Vuetify, no Tailwind migration, no Redux, no
GraphQL/Apollo, no RxJS architecture. Plain Vue components plus scoped/global
CSS. `package-lock.json` is committed; `node_modules/`, `dist/`, and
`coverage/` are ignored.

## 4. Frontend Architecture

```text
frontend/src/
  api/            typed HTTP modules (the only place fetch() lives)
    client.ts     one typed client + API error contract parsing
    types.ts      TypeScript contracts generated from the real API DTOs
    projects.ts   GET/POST /api/v1/projects
    workspace.ts  active state, routes, draft-next, submit answer
    requirementState.ts  GET requirement-state
  components/     focused UI: ApiErrorBanner, ProjectCreateForm,
                  RouteListPanel, ClarificationPanel, RequirementStatePanel
  stores/         Pinia application state (projectStore, workspaceStore)
  views/          page-level coordination (ProjectsView, WorkspaceView)
  router/         Vue Router routes
  App.vue, main.ts
```

Views coordinate page-level behavior, components render focused UI, stores own
frontend application state, and API modules own HTTP calls.

## 5. Frontend Routes

```text
/                          → redirects to /projects
/projects                  project list + create form
/projects/:projectId       three-panel workspace
```

## 6. Development API Connectivity

Vite dev proxying forwards `/api` to the backend (default
`http://localhost:8080`, overridable via `VITE_API_PROXY_TARGET`):

```text
frontend dev server
/api/v1/*
      ↓
http://localhost:8080/api/v1/*
```

The API base supports a future `VITE_API_BASE_URL` override and defaults to
`/api/v1`. No authentication headers, no credentials UI, and no provider API
keys ever reach the frontend.

## 7. Backend API Endpoints Used

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/projects` | list projects |
| `POST` | `/api/v1/projects` | create project (`{ "title": "..." }`) |
| `GET` | `/api/v1/projects/{projectId}` | project details |
| `GET` | `/api/v1/projects/{projectId}/active` | active project state |
| `GET` | `/api/v1/projects/{projectId}/routes` | route list |
| `POST` | `/api/v1/projects/{projectId}/questions/next` | draft next question (explicit) |
| `POST` | `/api/v1/projects/{projectId}/answers` | submit an answer |
| `GET` | `/api/v1/projects/{projectId}/requirement-state` | **new Phase 7.1 read endpoint** |

Create-project uses the existing `title` field and labels it honestly as
project title/name. No initial-requirement field was invented.

## 8. Requirement-State Read Endpoint

Phase 6 did not expose `RequirementState` over HTTP. Phase 7.1 adds exactly one
narrow, read-only UI-support endpoint:

```http
GET /api/v1/projects/{projectId}/requirement-state
```

It never calls a model, never persists an answer/patch/node/route/spec, and
never makes `RequirementState` the source of truth. It derives the current
requirement state from the project's active route using the existing
`RequirementStateBuilder`; no `ContextSnapshot` is built or persisted to
satisfy the read.

Architecture boundary (the API layer still never depends on `context`):

```text
com.specagent.api.requirement.RequirementStateController
        ↓
com.specagent.readmodel.requirement.RequirementStateQueryService
        ↓
com.specagent.context.RequirementStateBuilder
```

The controller depends only on the read-model query boundary. The read-model
layer may depend on existing runtime/context services, but it is read-only and
is not a second Runtime Kernel.

Response shape (safe DTOs only; no domain `RequirementState`/`Claim` objects,
no persistence metadata, no prompts, no model payloads):

```json
{
  "projectId": "...",
  "routeId": "...",
  "confirmed": [
    {
      "kind": "goal",
      "text": "...",
      "status": "confirmed",
      "confidence": 0.9,
      "sourceNodeId": "...",
      "sourceAnswerId": "..."
    }
  ],
  "assumed": [],
  "unresolved": [],
  "rejected": [],
  "builtAt": "..."
}
```

Statuses are the backend claim statuses (`confirmed | assumed | unresolved |
rejected`); clients never infer a status. When the project has no active route,
a safe empty read model is returned (`routeId: null`, all groups empty) instead
of inventing a route. If the active pointer ever fails to resolve to a route
owned by the project, the read fails closed as `INTERNAL_INVARIANT_VIOLATION`
so foreign data can never be exposed. Unknown project → `404
PROJECT_NOT_FOUND`.

## 9. Workspace Behavior

The three-panel desktop layout is:

```text
┌────────────────────┬──────────────────────────┬──────────────────────┐
│ Route / Project    │ Clarification           │ Requirement State    │
│ (read-only)        │ (center, dominant)      │ (right)              │
└────────────────────┴──────────────────────────┴──────────────────────┘
```

Below a breakpoint the layout stacks vertically. No graph/canvas library is
used.

Opening the workspace loads the backend state over HTTP:

```text
GET /api/v1/projects/{projectId}
GET /api/v1/projects/{projectId}/active
GET /api/v1/projects/{projectId}/routes
GET /api/v1/projects/{projectId}/requirement-state
```

The frontend never infers the active route by scanning lifecycle `OPEN`; it
uses `Project.activeRouteId` via the backend's `activeRoute`/`RouteResponse
isActive`. Lifecycle stays `open | superseded | archived | deleted`; there is
no frontend lifecycle value named `active`.

Left panel (Phase 7.1): read-only route visibility — label, lifecycle badge,
backend-derived Active indicator, tip node id for diagnostics. No route
command buttons (activate/fork/regenerate/restore/archive/delete are Phase
7.2).

Center panel: when there is no active route, an honest empty state is shown
(no manufactured question). When the active route exists but has no tip node,
an explicit `Draft first question` button appears; calling it invokes
`POST /questions/next`. Drafting is never automatic on page open. When an
active node exists, the panel renders question (dominant), purpose (secondary),
single-selection options (radio group preserving runtime-owned option ids
internally), and free-text input only when `allowFreeAnswer == true`.

Right panel: loads only the new read endpoint and renders backend-derived
sections `Confirmed`, `Assumptions`, `Unresolved`, plus a visually subdued
`Rejected` section. The frontend never promotes an assumed/unresolved claim
into confirmed. Claim metadata (kind, text, confidence, source ids) is shown;
rich source navigation is Phase 7.2 or later.

## 10. Answer Behavior

```http
POST /api/v1/projects/{projectId}/answers
{ "selectedOptionId": "...optional...", "freeText": "...optional..." }
```

- At least one meaningful input is required (backend authoritative).
- When `allowFreeAnswer == false` no free-text editor is presented.
- Option-only, free-text-only, and combined option + free-text payloads are
  all supported; neither input is silently discarded.
- Option ids are preserved verbatim and never reinterpreted; the backend
  remains the authority for option membership and answer validity.
- Safe answer DTOs (`AgentRunResponse`, `AnswerResponse`, `AnswerPatchResponse`,
  `NodeResponse`) may be used; the frontend never requires `ModelRequest`,
  `ModelResponse`, raw `ContextSnapshot` content, prompts, or provider payloads.

After a successful answer, the frontend does NOT build the next state from the
returned patch. It refreshes the canonical backend read APIs (active state,
routes, requirement state). The Runtime owns history; the frontend displays it.

## 11. Loading and Error Behavior

Every network command has visible pending state (`Drafting question…`,
`Processing answer…`, `Loading workspace…`, `Loading projects…`) and duplicate
command buttons are disabled while a request is in flight; accidental double
answer submission is prevented both in the store and in the UI. The Phase 6
API is synchronous; no background-job polling was added.

The typed client understands the Phase 6 API error contract
(`{code, message, timestamp, errors}`). The UI renders the backend's
sanitized stable `message` only. Raw response bodies, stack traces, HTML error
pages, and provider payloads are never rendered; anything that cannot be
parsed as the contract becomes the generic fallback:

```text
Something went wrong. Please try again.
```

Provider-neutral errors (rate limit, provider unavailable, timeout, invalid
model response, etc.) are preserved. The UI offers a normal Retry button that
repeats the user's explicit request; no backend/model automatic
retry/fallback behavior was added.

## 12. Frontend Test Strategy

Vitest + Vue Test Utils, mocking the frontend API boundary (the `src/api/*`
modules) rather than internal Vue implementation details. No Playwright yet;
E2E closure belongs to Phase 7.2. No test calls a real provider.

Coverage (61 tests):

- API client: success parsing, stable API error contract, HTML/non-contract
  bodies → generic fallback, provider-neutral error preservation, network
  failure, POST body construction.
- projectStore: list, creation, safe backend errors.
- workspaceStore: workspace loading from all four reads, no auto-draft,
  explicit draft + refresh, option-only / free-text / combined answer
  payloads, duplicate-submit/draft prevention while pending, post-answer
  backend-state refresh, provider-neutral error surfacing, route visibility.
- Components: clarification question/purpose/options rendering, runtime-owned
  option ids, `allowFreeAnswer` behavior, draft empty state, answer payloads,
  duplicate-submit prevention while pending, route lifecycle badges without
  implying `OPEN == active`, requirement-state grouping per backend status,
  error banner safety.
- Views: project list/create/navigate, workspace three-panel load, draft
  flow, answer flow, safe error presentation.

## 13. Frontend Quality Commands

```powershell
cd E:\spec-agent\frontend
npm ci
npm run typecheck     # vue-tsc --noEmit
npm run test:unit -- --run
npm run build         # vue-tsc --noEmit && vite build
```

No lint script was added in this scaffold; typecheck is the static gate.

## 14. Backend Regression

Phase 7.1 adds the requirement-state read endpoint plus integration tests:

- new project with no derived claims → empty grouped state
- project after answer/patch through the normal orchestrator → claims grouped
  by actual runtime status (confirmed/unresolved from the fake gateway, plus
  assumed/rejected setup claims)
- route isolation: sibling-route sentinel claims never appear in the active
  route's requirement state
- unknown project → `404 PROJECT_NOT_FOUND`
- no-active-route state → safe empty read model with `routeId: null`
- corrupted foreign active-route pointer → fails closed with
  `INTERNAL_INVARIANT_VIOLATION`, no foreign data exposed

Full backend regression result: 63 test classes, 374 tests, 0 failures,
0 errors (4 skipped live-provider smoke tests gated on
`SPEC_AGENT_OPENCODE_KEY`, skipped by default). All Phase 0-6 tests green,
new requirement-state tests green, architecture tests green, zero public
OpenCode requests.

## 15. Zero Live Provider Default

The default gateway remains `SPEC_AGENT_MODEL_GATEWAY=fake`. The full backend
regression and all frontend tests make zero public OpenCode requests.
Real-provider smoke tests remain gated on `SPEC_AGENT_OPENCODE_KEY` and are
skipped by default. The frontend never receives or transmits provider keys.

## 16. Deferred to Phase 7.2

Route activation, fork controls, regenerate modal, restore, archive, delete,
spec-generation UI, spec history UI, historical node navigation,
source-reference navigation, complex route tree graph, Playwright full E2E
suite, SSE/WebSocket, background jobs, authentication, multi-user support,
collaboration, document upload, RAG, browser automation, code generation,
credential UI, provider configuration UI, provider registry/router/fallback,
retry/repair loops, JSON repair, model-drafted regenerate, and complex canvas
work.

No ModelGateway changes, no prompt changes, no parser relaxation, and no route/
context semantics changes were made in Phase 7.1.

## 17. Final Invariant Statement

```text
Frontend is a client of the Runtime.
Frontend never recreates Runtime history or state.
Lifecycle has no `active` status; active follows Project.activeRouteId.
RequirementState is derived, cacheable, never source of truth.
The requirement-state endpoint is read-only and route-scoped.
The API layer still never depends on context, model, repository, or credential.
```

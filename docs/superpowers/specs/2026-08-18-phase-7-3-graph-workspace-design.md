# Phase 7.3 — Graph Workspace UX Design

Date: 2026-08-18
Status: Approved design, pending implementation plan
Baseline: `a4e0e1439378928f32efd54ba044b62b8019f329`

## 1. Goal

Phase 7.3 replaces the Phase 7.2 three-panel workspace with a graph-first requirements exploration workspace while preserving the existing Runtime semantics.

The product should feel like a requirements exploration map rather than an administration dashboard.

The core doctrine remains unchanged:

- Model proposes.
- Runtime validates.
- Runtime persists.
- Runtime owns history.
- Context is lineage, not global chat history.
- SpecSnapshot is derived, not source of truth.
- Active route is distinct from route lifecycle.
- Regenerate remains deterministic.
- Frontend is a client/read projection, not a second Runtime.

Graph layout and other browser UI preferences never become Runtime facts.

## 2. Scope Summary

Phase 7.3 adds:

- Vue Flow graph canvas as the center of the workspace.
- All routes displayed in one shared graph.
- Shared nodes rendered once.
- Route-specific answers rendered on shared nodes without collapsing answer identity.
- Current unanswered node answered directly inside the graph node.
- Historical answered nodes show answer summaries directly on the node.
- Left-to-right initial layout.
- Manual node dragging, multi-select, marquee selection, and grouped movement.
- Edges that update continuously while nodes move.
- Browser-local node position persistence per project.
- Route focus, dim, hide, and show-all controls that do not mutate Runtime state.
- Collapsible and resizable left/right sidebars, default open, with browser-local persistence.
- Product-shell Chinese localization.
- Minimal backend read-model additions needed to render answers and read non-active route requirement state correctly.

Phase 7.3 does not add arbitrary graph editing, node deletion, parent rewiring, backend layout persistence, multi-device layout sync, AI translation, language switching, AI-random regenerate, WebGL rendering, or changes to Runtime route/context semantics.

## 3. Overall Workspace Layout

The workspace becomes:

```text
┌────────────┬──────────────────────────────────────┬──────────────┐
│ Left       │                                      │ Right        │
│ sidebar    │             Graph Canvas             │ inspector    │
│            │                                      │              │
│ routes     │      all routes and graph nodes      │ details      │
│ filters    │                                      │ req state    │
│ legend     │                                      │ specs        │
└────────────┴──────────────────────────────────────┴──────────────┘
```

The graph canvas is always the primary visual surface.

Both sidebars:

- are open by default;
- can be collapsed independently;
- can be resized by dragging their separator;
- persist open state and width locally;
- resize the visible graph viewport only;
- never recalculate stored graph coordinates merely because the sidebar width changes.

Suggested initial dimensions are implementation guidance, not a domain contract:

- left: about 280 px, clamped to a reasonable min/max;
- right: about 380 px, clamped to a reasonable min/max.

## 4. Graph Semantics

The graph uses these product semantics:

```text
Node = Question + route-specific answer presentation
Edge = lineage or replacement relationship
Route = path through the graph
Active Route = Runtime work context
Focus Route = browser-only reading context
```

All routes are displayed on one graph.

If several routes share nodes A and B, A and B are rendered once. The backend read model supplies route membership; the frontend must not reconstruct authoritative lineage by guessing.

Lineage edges are also deduplicated. If Route A and Route B both contain A -> B, the graph has one A -> B edge with both route memberships.

Replacement relationships are distinct from lineage:

- `parentNodeId` produces a normal lineage edge;
- `supersedesNodeId` may produce an additional replacement relationship indicator;
- `supersedesNodeId` must never be treated as the parent relationship.

## 5. Node Presentation

### 5.1 Current unanswered node

The current unanswered node is visually larger than historical nodes and contains the complete answer interaction:

- question;
- optional purpose, visually secondary;
- backend-provided options with label and impact;
- optional free-text input when `allowFreeAnswer` is true;
- submit action.

The existing answer contract remains valid:

- option only;
- free text only;
- option plus free text.

The frontend must submit Runtime-owned option IDs verbatim and must never manufacture replacements for an existing option ID.

Only the actual backend active node in the backend active route is answerable. A historical route tip must never become answerable merely because it is a tip.

### 5.2 Historical answered node

An answered node shows the answer directly on the node.

Default historical presentation is compact:

- question;
- selected option summary when present;
- free-text answer summary limited to roughly 3-4 lines;
- answered indicator;
- expand action when more detail exists.

Expanded state may show:

- full question;
- full purpose;
- full answer;
- all original options;
- which option was selected;
- all route-specific answers when the node is shared.

Expanding a node changes the node size but does not automatically re-layout the graph.

### 5.3 Shared node answers

Answer identity remains `routeId + nodeId`.

If one node is shared by several routes, the node is still rendered once, but it may display several route-specific answers.

Priority:

1. focused route answer, when a focus route exists;
2. otherwise active route answer;
3. other route answers in compact form.

The inspector exposes the full per-route answer set.

A fork does not copy an existing answer into the new route. Therefore a shared node may simultaneously display:

- an answered state for the old route;
- a waiting-for-answer state for the newly forked active route.

The UI must preserve this distinction.

## 6. Node Selection and Dragging

Only the node title/header is a drag handle.

Interactive node body controls must not initiate drag:

- radio option;
- textarea;
- button;
- text selection;
- expand/collapse control.

Selection behavior:

- normal click: single selection;
- Ctrl/Cmd + click: add/remove from multi-selection;
- marquee selection: select multiple nodes.

When multiple nodes are selected, dragging the title bar of any selected node moves the entire selection by the same delta.

Edges must update continuously during movement.

Local persistence occurs on drag completion rather than on every pointer-move event.

## 7. Layout

Initial automatic layout is left-to-right.

Typical shape:

```text
A ───── B ───── C
         \
          D ───── E
```

Once a user has manually placed existing nodes, new nodes must not trigger a full graph re-layout.

For a newly discovered node with no saved position:

- place it to the right of its parent;
- use approximately the parent vertical position;
- if occupied, choose a nearby available vertical slot.

Existing node coordinates remain unchanged.

A separate explicit `重新自动布局` command may recompute all visible node positions. This action must clearly warn that it overwrites the user’s current manual layout. It does not change any Runtime state.

## 8. Empty Project Graph

When the current route has no node, the center of the canvas displays a UI-only placeholder:

```text
开始需求澄清
还没有生成任何问题。
[起草第一个问题]
```

This placeholder:

- has no Runtime node ID;
- is not persisted as a graph node;
- is not part of route lineage;
- disappears only after a real root node is successfully created.

Drafting remains an explicit user action.

## 9. Route Navigation and Visual Controls

The left sidebar is route navigation, not a second node tree.

Each route exposes clearly separated groups.

Browser-only viewing actions:

- locate route;
- focus route;
- dim route;
- hide route.

Runtime actions:

- activate route;
- archive;
- restore;
- soft delete.

These categories must be visually separated so `隐藏路线` cannot be confused with `删除路线`.

### 9.1 Focus route

At most one route is focused.

Focus means reading context only:

- focused route stays visually prominent;
- other visible routes are temporarily dimmed;
- active route is not changed;
- exiting focus restores the previous per-route dim/hide state.

### 9.2 Dim route

Dim keeps the route visible but lowers visual weight.

### 9.3 Hide route

Hide removes only route-exclusive graph elements from the current browser view.

A shared node remains visible if it is still required by any visible route.

The active route cannot be permanently hidden. The active current node must always remain identifiable.

### 9.4 Lifecycle filters

Default filters:

- OPEN: visible;
- SUPERSEDED: visible;
- ARCHIVED: visible;
- DELETED: hidden.

Lifecycle filters are distinct from manual hide/dim state.

`显示全部路线` clears manual hide/dim/focus but does not silently change Runtime lifecycle.

## 10. Historical Backtracking UX

Backtracking never rewrites or truncates history.

Selecting a historical node exposes:

- inspect details;
- `从此分支`;
- `重新生成这个问题`.

### 10.1 Fork from here

Fork creates a new route from the selected route/node context.

Old route history remains unchanged.

Shared history nodes are not copied in the graph.

The new route becomes active according to existing Runtime behavior.

If the selected node belongs to multiple routes, the UI must require an explicit base route instead of guessing.

### 10.2 Regenerate

Regenerate remains deterministic.

The user supplies replacement content:

- replacement question;
- optional purpose;
- replacement options as label/impact only;
- optional instruction.

Old option IDs are never reused.

The Runtime creates the replacement route/node and owns all new IDs.

Old history remains visible. A replacement relationship is visually distinct from lineage.

## 11. Right Inspector

Recommended tabs:

- `详情`;
- `需求状态`;
- `规格`.

No selected node:

- default to requirement state for the reading route.

Selected node:

- switch to details;
- show complete question, purpose, answer(s), options, route membership, and provenance;
- show fork/regenerate actions for historical nodes.

The current node’s primary answer controls stay inside the graph node. The inspector must not expose a second competing submit UI.

## 12. Work Context vs Reading Context

The system explicitly separates:

```text
Active Route = work context
Focus Route = reading context
```

Define:

```text
readingRouteId = focusRouteId ?? activeRouteId
```

Use `readingRouteId` for:

- requirement state inspection;
- spec history;
- preferred shared-node answer presentation.

Use `activeRouteId` only for Runtime commands such as:

- draft question;
- submit answer;
- generate spec.

A user may legally have Active=A and Focus=B.

The UI must make this distinction obvious.

## 13. Requirement State Read API

The existing project requirement-state endpoint remains the active-route view.

Add a route-scoped read endpoint:

```text
GET /api/v1/projects/{projectId}/routes/{routeId}/requirement-state
```

It must:

- validate project existence;
- validate route existence;
- validate route ownership;
- derive state using the existing route history logic;
- permit read inspection for OPEN, SUPERSEDED, ARCHIVED, and DELETED routes;
- fail closed for invalid ownership/invariant conditions;
- never write state or persist a ContextSnapshot.

RequirementState remains derived and never becomes source of truth.

## 14. Graph Workspace Read API

Add a graph-oriented read endpoint:

```text
GET /api/v1/projects/{projectId}/graph
```

Conceptual response:

```text
GraphWorkspaceView
  projectId
  activeRouteId
  routes[]
    id
    label
    lifecycleStatus
    isActive
    rootNodeId
    tipNodeId
    createdFromNodeId
    supersedesRouteId
    replacementOfNodeId
    lineageNodeIds[]
  nodes[]
    id
    projectId
    parentNodeId
    supersedesNodeId
    question
    purpose
    options[]
    allowFreeAnswer
    createdAt
  answers[]
    id
    routeId
    nodeId
    selectedOptionId
    freeText
    createdAt
```

Requirements:

- nodes are deduplicated across routes;
- each route supplies authoritative lineage membership/order;
- route-specific answers remain separate;
- only safe answer presentation data is exposed;
- no patch/context/model/provider/credential/trace/DB metadata is exposed;
- graph read fails closed for foreign/missing/cyclic/root-mismatch lineage corruption rather than returning a misleading partial graph.

Architecture:

```text
GraphWorkspaceController
  -> GraphWorkspaceQueryService
     -> ProjectService / RouteService / NodeService / AnswerService
```

The API/controller does not access repositories directly.

The graph read model does not call ModelGateway, provider code, credentials, ContextBuilder, or any write path.

## 15. Frontend Architecture

Use `@vue-flow/core` as rendering infrastructure.

Vue Flow owns:

- viewport;
- pan/zoom;
- rendering infrastructure;
- node movement;
- selection;
- edge rendering.

Vue Flow does not own Runtime semantics.

Split frontend responsibilities:

### `workspaceStore`

Server-facing/canonical state and commands:

- project;
- active state;
- routes;
- GraphWorkspaceView;
- requirement state reads;
- specs;
- loading/submitting/drafting/pending commands.

After Runtime mutations, canonical backend state is re-read.

### `graphUiStore`

Browser-only view state:

- selected node IDs;
- primary selected node;
- focus route;
- lifecycle filters;
- route display state: normal/dimmed/hidden;
- expanded nodes;
- sidebar view state as appropriate.

It cannot change Runtime fields.

### `graphProjection.ts`

A pure projection layer receives:

```text
GraphWorkspaceView
+ graph UI state
+ saved node positions
```

and returns Vue Flow nodes/edges.

It computes visual membership, selected answer presentation, visibility, and edge style, but does not write Runtime data.

## 16. Browser Local Persistence

Per-project graph layout key:

```text
spec-agent.graph-layout.v1.<projectId>
```

Conceptual value:

```json
{
  "version": 1,
  "nodePositions": {},
  "routeDisplayStates": {}
}
```

Global workspace UI key:

```text
spec-agent.workspace-ui.v1
```

Conceptual value contains left/right sidebar open state and width.

Do not persist viewport pan/zoom in Phase 7.3.

Local persistence is best-effort only. Corrupt or unavailable localStorage must never block Runtime functionality.

Defensive behavior:

- parse failure -> defaults;
- invalid/non-finite coordinates -> ignore;
- out-of-range sidebar widths -> clamp;
- stale node/route IDs -> ignore;
- write failure -> continue without persistence.

## 17. Chinese Product UI

Phase 7.3 localizes the product shell into Chinese, including:

- project/workspace navigation;
- graph controls;
- route actions and statuses;
- node actions;
- empty/loading/success states;
- confirmation dialogs;
- error messages;
- requirement state labels;
- spec snapshot labels.

Do not translate backend/user content:

- AI-generated question;
- AI-generated purpose;
- option labels/impacts;
- user answer;
- spec content.

No language-switching/i18n framework is introduced in this phase.

Backend error codes remain machine-readable; UI-facing explanatory copy may be Chinese.

## 18. Runtime and Graph Safety Rules

The graph is an interactive Runtime History Viewer, not an arbitrary DAG editor.

Not provided:

- create arbitrary node;
- delete individual historical node;
- drag/reconnect edge;
- change parent by UI;
- edit historical answer in place.

Moving a node changes only x/y presentation state.

No graph action may directly mutate:

- `parentNodeId`;
- `supersedesNodeId`;
- route membership;
- lifecycle;
- active pointer;
- persisted answer.

## 19. Refresh/Error Behavior

After answer/fork/regenerate/activate/archive/restore/delete/spec commands, re-read canonical backend state.

When node IDs survive a refresh:

- preserve coordinates;
- preserve Vue Flow identity where practical;
- preserve valid selection where practical;
- do not automatically fit the whole graph on every refresh.

If a newly created current node is outside the viewport, smoothly bring it into view without moving existing nodes.

If a refresh fails after a graph was previously loaded, preserve the last successful graph and show a retryable error banner instead of clearing the canvas.

## 20. Performance Target

Phase 7.3 targets ordinary requirements exploration graphs of tens to hundreds of nodes.

Avoid:

- one HTTP call per node;
- frontend reconstruction of canonical lineage;
- localStorage writes on every mousemove;
- full automatic layout after each answer.

Do not build WebGL rendering, graph paging, virtualization infrastructure, or worker-based large-graph layout in this phase.

## 21. Verification Requirements

### Backend

Graph API tests must cover:

- single route;
- multiple routes;
- shared node deduplication;
- route lineage membership/order;
- Active independent from lifecycle;
- inspection across all lifecycle states;
- route-specific answers remain distinct;
- selected option/free text correctness;
- fork does not copy answers;
- regenerate replacement relationships;
- no superseded target subtree injection;
- foreign/missing/cycle/root mismatch fail closed.

Requirement-state tests must cover:

- existing active-route endpoint unchanged;
- route-scoped endpoint for every valid lifecycle;
- foreign route protection.

Architecture tests must ensure:

- API does not depend on repositories;
- read models do not depend on API;
- graph read model does not depend on model/provider/credential/context-building code;
- reads do not write state or create ContextSnapshots.

### Frontend unit/component tests

Cover at minimum:

- shared nodes dedupe correctly;
- shared edges dedupe with route memberships;
- focus answer priority over active answer;
- active answer priority with no focus;
- history remains read-only;
- only actual active node exposes answer controls;
- hidden-route exclusive nodes disappear while shared nodes remain;
- active route cannot disappear;
- focus does not activate;
- replacement edge is not lineage;
- title bar is drag handle;
- body controls do not drag;
- multi-select and grouped movement;
- positions persist after drag end;
- restored positions survive reload;
- new node does not change existing coordinates;
- sidebars collapse/resize/persist;
- invalid localStorage safely falls back.

### Playwright E2E

At minimum:

1. New project -> start placeholder -> draft root node.
2. Answer directly inside current node -> node becomes historical -> next node appears.
3. Drag node -> edge follows -> reload restores position.
4. Multi-select two nodes -> grouped movement.
5. Fork historical node -> new active route, shared node not duplicated, answer identity remains route-specific.
6. Deterministic regenerate -> replacement route visible, old history remains.
7. Focus/dim/hide/show-all and active-route visibility protection.
8. Active=A + Focus=B -> requirement state/spec history read B, Runtime generation clearly targets A, generated spec result updates reading selection correctly.

Full frontend unit suite, typecheck, production build, Playwright suite, and backend full suite must all pass.

## 22. Exit Criteria

Phase 7.3 is closed only when all of the following are true:

- Vue Flow graph-first workspace is production path.
- All routes render in one graph.
- Shared nodes and shared lineage edges render once.
- Route-specific answers are represented faithfully.
- Current node supports direct option/free-text answer submission.
- Answered nodes show answer summaries directly.
- Current node is larger; historical nodes are compact.
- Node single-select, multi-select, marquee selection, grouped drag work.
- Only title/header is draggable.
- Edges follow node movement live.
- Node positions persist locally per project.
- New nodes do not reposition existing nodes.
- Explicit re-layout exists.
- Focus, dim, hide, and active-route protection work without changing Runtime state.
- Fork and deterministic regenerate preserve all old history.
- Sidebars default open, collapse independently, resize, and persist.
- Requirement State and spec history follow reading route.
- Runtime commands remain explicitly active-route scoped.
- Product UI shell is Chinese; AI/user/spec content is not translated.
- Backend architecture tests pass.
- Frontend unit/component tests pass.
- Typecheck and production build pass.
- Graph Playwright E2E passes.
- Backend full suite passes.
- Final implementation commit is pushed and remote main equals local HEAD.

## 23. Non-Goals / Deferred Work

Explicitly deferred:

- arbitrary node creation/deletion;
- manual edge creation/reconnection;
- backend graph-position persistence;
- cross-device layout synchronization;
- viewport persistence;
- AI-generated language translation;
- UI language switching;
- prompt-language changes;
- AI-random regenerate;
- Runtime route/context semantic changes;
- WebGL/large-graph platform work;
- Phase 8 hardening/release work.

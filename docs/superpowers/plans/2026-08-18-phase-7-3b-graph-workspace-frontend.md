# Phase 7.3B Graph Workspace Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Phase 7.2 administration-style workspace with a Chinese graph-first requirements exploration workspace where all routes share one Vue Flow canvas and the current unanswered question is answered directly inside its graph node.

**Architecture:** Consume the Phase 7.3A canonical graph/read APIs into `workspaceStore`, keep browser-only selection/focus/filter/layout/sidebar state in a separate `graphUiStore`, and project canonical Runtime data through pure graph projection/layout functions into Vue Flow. Vue Flow owns viewport, rendering, selection and dragging; Runtime history, answers, route lifecycle, Active pointer, RequirementState and Spec remain backend-owned.

**Tech Stack:** Vue 3.5, TypeScript, Vite, Pinia, Vue Router, `@vue-flow/core@1.48.2`, Vitest, Vue Test Utils, Playwright.

**Spec:** `docs/superpowers/specs/2026-08-18-phase-7-3-graph-workspace-design.md`

**Backend prerequisite:** `origin/main` contains the completed Phase 7.3A APIs:

```text
GET /api/v1/projects/{projectId}/graph
GET /api/v1/projects/{projectId}/routes/{routeId}/requirement-state
```

## Global Constraints

- Graph is an interactive Runtime History Viewer, not an arbitrary DAG editor.
- Never add arbitrary node creation/deletion, edge reconnect, parent edits, historical-answer edits, or backend graph-layout persistence.
- `parentNodeId`, `supersedesNodeId`, route membership, lifecycle, Active pointer and persisted answers remain backend-owned.
- Active Route = work context. Focus Route = browser-only reading context.
- `readingRouteId = focusRouteId ?? activeRouteId`.
- Draft question, submit answer and generate Spec always target the backend Active Route, never Focus Route.
- Shared Node renders once; route-specific answers remain distinct by `routeId + nodeId`.
- Only a backend Active node with no finalized answer for the Active Route is answerable.
- Current answerable Node is visually larger; historical/answered Nodes stay compact.
- Historical answer text shows 3–4 lines by default and expands on demand.
- Only the Node title/header is a drag handle. Body controls must never initiate node drag.
- Existing node coordinates never change merely because canonical graph data gains a new node.
- Only explicit `重新自动布局` may overwrite manual positions.
- Node positions and per-route visual state persist locally per project.
- Left/right sidebar open state and widths persist locally as workspace preferences.
- Do not persist pan/zoom viewport in Phase 7.3.
- Product-shell copy is Chinese. Question, purpose, option label/impact, user answer and Spec content remain verbatim backend/user content.
- Do not add `vue-i18n`, a language switcher, Agent prompt language changes, provider changes, model fallback, parser repair, or AI-random regenerate.
- Use stable `data-test` hooks for tests.
- Use TDD. Every task must end with the relevant unit/type/build checks green before commit.
- Keep intermediate commits buildable. Do not remove legacy store fields/components until the new Graph Workspace no longer depends on them.
- Final E2E uses the existing real local backend with fake gateway.
- Push final implementation to `origin main`; finish with a clean working tree and `origin/main == HEAD`.

## Fork Compatibility Rule

The existing Runtime Fork command accepts `projectId + nodeId + label`; it does **not** accept `sourceRouteId`. Phase 7.3B must not change this command contract.

For a shared historical node that belongs to several routes:

1. Show all route memberships and require the user to select the intended base route.
2. If the selected route is not OPEN, disable Fork and show `先恢复这条路线`.
3. If it is OPEN but not Active, disable Fork and show `先设为当前路线`.
4. The user performs Restore/Activate explicitly through existing Runtime commands.
5. Only when the selected base route is Active + OPEN may `从此分支` call the existing Fork API.
6. Never guess a base route and never silently chain Activate+Fork.

This preserves current Runtime source-route selection semantics.

## File Structure

### Contracts and API

- Modify `frontend/package.json`, `frontend/package-lock.json` — add only `@vue-flow/core@1.48.2` as graph engine.
- Modify `frontend/src/main.ts` — import Vue Flow CSS before app overrides.
- Modify `frontend/src/api/types.ts` — add graph read DTOs.
- Create `frontend/src/api/graph.ts`.
- Modify `frontend/src/api/requirementState.ts`.

### Pure graph layer

- Create `frontend/src/graph/graphTypes.ts`.
- Create `frontend/src/graph/graphLayoutStorage.ts`.
- Create `frontend/src/graph/graphLayout.ts`.
- Create `frontend/src/graph/graphProjection.ts`.
- Create `frontend/src/graph/__tests__/graphLayoutStorage.spec.ts`.
- Create `frontend/src/graph/__tests__/graphLayout.spec.ts`.
- Create `frontend/src/graph/__tests__/graphProjection.spec.ts`.

### Stores

- Create `frontend/src/stores/graphUiStore.ts` and tests.
- Modify `frontend/src/stores/workspaceStore.ts` and tests to load/cache canonical graph, route-scoped RequirementState and route-scoped Specs.
- Keep old Phase 7.2 selection/lineage fields temporarily until Task 7 cuts the view over; remove them in that same cutover task.

### Graph components

- Create `frontend/src/components/graph/GraphQuestionNode.vue`.
- Create `frontend/src/components/graph/GraphStartPlaceholder.vue`.
- Create `frontend/src/components/graph/GraphToolbar.vue`.
- Create `frontend/src/components/graph/GraphCanvas.vue`.
- Create tests under `frontend/src/components/graph/__tests__/`.

### Workspace shell

- Create `frontend/src/components/workspace/ResizableSidebar.vue`.
- Create `frontend/src/components/workspace/RouteSidebar.vue`.
- Create `frontend/src/components/workspace/WorkspaceInspector.vue`.
- Create `frontend/src/components/workspace/NodeInspector.vue` when it keeps Inspector responsibilities focused.
- Modify `frontend/src/views/WorkspaceView.vue` and its tests.
- Adapt reusable RequirementState/Spec/dialog/route-action components.
- Modify `frontend/src/style.css`.

### Localization/cleanup/E2E

- Localize all product-shell copy in existing project/workspace components.
- Delete Phase 7.2-only workspace display components only after Task 7 has zero imports to them.
- Adapt all existing Playwright flows to Graph UI and add graph-layout/route-display scenarios.
- Create `docs/PHASE_7_3_EXIT_CRITERIA.md` only after verification.

---

### Task 1: Add Vue Flow and the canonical Graph API client contracts

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Modify: `frontend/src/main.ts`
- Modify: `frontend/src/api/types.ts`
- Create: `frontend/src/api/graph.ts`
- Create/modify: `frontend/src/api/__tests__/graph.spec.ts`
- Modify: `frontend/src/api/requirementState.ts`
- Create/modify: `frontend/src/api/__tests__/requirementState.spec.ts`

**Interfaces:**

```ts
export interface GraphWorkspaceOptionView {
  id: string
  label: string
  impact: string | null
}

export interface GraphWorkspaceNodeView {
  id: string
  projectId: string
  parentNodeId: string | null
  supersedesNodeId: string | null
  question: string
  purpose: string | null
  options: GraphWorkspaceOptionView[]
  allowFreeAnswer: boolean
  createdAt: string
}

export interface GraphWorkspaceAnswerView {
  id: string
  routeId: string
  nodeId: string
  selectedOptionId: string | null
  freeText: string | null
  createdAt: string
}

export interface GraphWorkspaceRouteView {
  id: string
  label: string | null
  lifecycleStatus: RouteLifecycleStatus
  isActive: boolean
  rootNodeId: string | null
  tipNodeId: string | null
  createdFromNodeId: string | null
  supersedesRouteId: string | null
  replacementOfNodeId: string | null
  lineageNodeIds: string[]
}

export interface GraphWorkspaceView {
  projectId: string
  activeRouteId: string | null
  routes: GraphWorkspaceRouteView[]
  nodes: GraphWorkspaceNodeView[]
  answers: GraphWorkspaceAnswerView[]
}
```

- [ ] **Step 1: Install the pinned graph dependency**

```bash
cd frontend
npm install @vue-flow/core@1.48.2
```

Do not add React Flow, Cytoscape, Dagre, ELK, D3 graph layout, or a second graph engine.

- [ ] **Step 2: Import Vue Flow CSS once**

In `src/main.ts`, before `./style.css`:

```ts
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
```

- [ ] **Step 3: Write failing API client tests**

Mock `apiClient.get` and assert exact paths:

```ts
expect(apiClient.get).toHaveBeenCalledWith('/projects/p1/graph')
expect(apiClient.get).toHaveBeenCalledWith('/projects/p1/routes/r2/requirement-state')
```

- [ ] **Step 4: Implement the API functions**

```ts
// api/graph.ts
export function getProjectGraph(projectId: string): Promise<GraphWorkspaceView> {
  return apiClient.get<GraphWorkspaceView>(`/projects/${projectId}/graph`)
}
```

Keep existing `getRequirementState(projectId)` and add:

```ts
export function getRouteRequirementState(
  projectId: string,
  routeId: string,
): Promise<RequirementStateView> {
  return apiClient.get<RequirementStateView>(
    `/projects/${projectId}/routes/${routeId}/requirement-state`,
  )
}
```

- [ ] **Step 5: Verify contracts**

```bash
npm run test:unit -- src/api/__tests__/graph.spec.ts src/api/__tests__/requirementState.spec.ts --run
npm run typecheck
npm run build
```

Expected: PASS / exit 0.

- [ ] **Step 6: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/main.ts frontend/src/api
git commit -m "feat: add graph workspace frontend contracts"
```

---

### Task 2: Add defensive local persistence and `graphUiStore`

**Files:**
- Create: `frontend/src/graph/graphTypes.ts`
- Create: `frontend/src/graph/graphLayoutStorage.ts`
- Create: `frontend/src/graph/__tests__/graphLayoutStorage.spec.ts`
- Create: `frontend/src/stores/graphUiStore.ts`
- Create: `frontend/src/stores/__tests__/graphUiStore.spec.ts`

**Interfaces:**

```ts
export type GraphRouteDisplayState = 'normal' | 'dimmed' | 'hidden'
export interface GraphPosition { x: number; y: number }

export interface ProjectGraphPreferencesV1 {
  version: 1
  nodePositions: Record<string, GraphPosition>
  routeDisplayStates: Record<string, GraphRouteDisplayState>
}

export interface WorkspaceUiPreferencesV1 {
  version: 1
  leftSidebar: { open: boolean; width: number }
  rightSidebar: { open: boolean; width: number }
}
```

Keys:

```text
spec-agent.graph-layout.v1.<projectId>
spec-agent.workspace-ui.v1
```

Defaults:

```ts
const DEFAULT_FILTERS = {
  open: true,
  superseded: true,
  archived: true,
  deleted: false,
}
```

Sidebar ranges:

```text
left: 220..420, default 280
right: 300..600, default 380
```

- [ ] **Step 1: Write storage failure tests first**

Prove:

```text
invalid JSON -> defaults
non-finite x/y -> ignored
sidebar width below/above range -> clamped
stale IDs do not throw
localStorage.getItem/setItem throws -> no exception escapes
```

- [ ] **Step 2: Implement storage helpers**

```ts
loadProjectGraphPreferences(projectId: string): ProjectGraphPreferencesV1
saveProjectGraphPreferences(projectId: string, value: ProjectGraphPreferencesV1): void
loadWorkspaceUiPreferences(): WorkspaceUiPreferencesV1
saveWorkspaceUiPreferences(value: WorkspaceUiPreferencesV1): void
```

Every helper catches browser storage exceptions.

- [ ] **Step 3: Write `graphUiStore` tests**

Cover:

```text
normal selection replaces old selection
Ctrl/Cmd toggle adds/removes selection
Focus is independent of Active
Hide Active is rejected
Show All clears Focus + manual dim/hide but preserves lifecycle filters
Reset View restores lifecycle-filter defaults too
expandedNodeIds toggles
sidebar open/width state persists
reconcile drops missing selected nodes
reconcile clears Focus when that route is no longer visible
reconcile repairs a persisted hidden state on Active Route
```

- [ ] **Step 4: Implement store state/actions**

Core state:

```ts
projectId: string | null
selectedNodeIds: string[]
primarySelectedNodeId: string | null
focusRouteId: string | null
lifecycleFilters: Record<RouteLifecycleStatus, boolean>
routeDisplayStates: Record<string, GraphRouteDisplayState>
expandedNodeIds: string[]
nodePositions: Record<string, GraphPosition>
leftSidebarOpen: boolean
leftSidebarWidth: number
rightSidebarOpen: boolean
rightSidebarWidth: number
```

Expose a function rather than a Pinia getter requiring arguments:

```ts
readingRouteId(activeRouteId: string | null): string | null {
  return this.focusRouteId ?? activeRouteId
}
```

Persist route-display/positions per project, sidebars globally. Do **not** persist selection, expanded nodes, Focus, or viewport pan/zoom.

- [ ] **Step 5: Verify**

```bash
npm run test:unit -- src/graph/__tests__/graphLayoutStorage.spec.ts src/stores/__tests__/graphUiStore.spec.ts --run
npm run typecheck
npm run build
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/graph frontend/src/stores/graphUiStore.ts frontend/src/stores/__tests__/graphUiStore.spec.ts
git commit -m "feat: add graph workspace UI state"
```

---

### Task 3: Implement pure graph projection and left-to-right layout

**Files:**
- Create: `frontend/src/graph/graphProjection.ts`
- Create: `frontend/src/graph/graphLayout.ts`
- Create: `frontend/src/graph/__tests__/graphProjection.spec.ts`
- Create: `frontend/src/graph/__tests__/graphLayout.spec.ts`

**Interfaces:**

```ts
export interface GraphAnswerPresentation {
  routeId: string
  selectedOptionId: string | null
  selectedOptionLabel: string | null
  freeText: string | null
  isPrimary: boolean
}

export interface SpecAgentGraphNodeData {
  node: GraphWorkspaceNodeView
  routeIds: string[]
  answers: GraphAnswerPresentation[]
  primaryAnswer: GraphAnswerPresentation | null
  isCurrent: boolean
  canAnswer: boolean
  isExpanded: boolean
  isShared: boolean
  visualWeight: 'active' | 'focus' | 'normal' | 'dimmed'
}

export interface SpecAgentGraphEdgeData {
  kind: 'lineage' | 'replacement'
  routeIds: string[]
  visualWeight: 'active' | 'focus' | 'normal' | 'dimmed'
}
```

`canAnswer` is true only when all are true:

```text
node.id == activeState.activeNode.id
activeRouteId is non-null
no graph answer exists with that activeRouteId + node.id
```

- [ ] **Step 1: Write projection semantics tests**

Fixture:

```text
Route A: A -> B -> C (Active)
Route B: A -> B -> D (OPEN)
Route C: A -> B -> E (ARCHIVED)
```

Assert:

```text
A/B render once
A->B lineage edge renders once with route memberships A/B/C
Focus B answer outranks Active A answer
without Focus, Active A answer is primary
hidden B removes only D; shared A/B remain
archived filter off removes E
manual hidden state can never hide Active A elements
Active membership has highest visual weight even if a persisted dim state exists
only unanswered Active node has canAnswer=true
replacement edge is separate from lineage and never changes parent relation
```

- [ ] **Step 2: Implement pure membership/visibility helpers**

```ts
getVisibleRouteIds(view, uiState): Set<string>
getNodeRouteMembership(view): Map<string, string[]>
getLineageEdgeMembership(view): Map<string, { source: string; target: string; routeIds: string[] }>
selectPrimaryAnswer(nodeId, answers, focusRouteId, activeRouteId, options): GraphAnswerPresentation | null
```

Lineage edge key:

```ts
`${source}->${target}`
```

Replacement edge key:

```ts
`replacement:${node.supersedesNodeId}->${node.id}`
```

Visual priority:

```text
membership includes Active -> active
else membership includes Focus -> focus
else manual/lifecycle dim -> dimmed
else normal
```

- [ ] **Step 3: Write layout tests**

Assert root-to-child x increases, siblings occupy distinct y slots, saved coordinates win, and placing a new node leaves every existing input coordinate byte-for-byte unchanged.

- [ ] **Step 4: Implement deterministic layout without another dependency**

```ts
export const HORIZONTAL_GAP = 360
export const VERTICAL_GAP = 220
```

Initial layout computes depth by `parentNodeId` and stable vertical slots from canonical node order.

New-node placement:

```ts
export function placeNewNode(
  parent: GraphPosition | null,
  occupied: GraphPosition[],
): GraphPosition
```

Try vertical offsets in order:

```ts
[0, 1, -1, 2, -2, 3, -3]
```

Only nodes missing saved coordinates get a computed position.

- [ ] **Step 5: Implement final projection function**

```ts
export function projectGraph(input: GraphProjectionInput): GraphProjectionResult
```

The output node uses Vue Flow custom type `question` and sets its drag handle selector to `.graph-question-node__header`; output edges are not connectable/updatable.

- [ ] **Step 6: Verify**

```bash
npm run test:unit -- src/graph/__tests__/graphProjection.spec.ts src/graph/__tests__/graphLayout.spec.ts --run
npm run typecheck
npm run build
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/graph
git commit -m "feat: project runtime history into graph view"
```

---

### Task 4: Add canonical Graph/reading caches to `workspaceStore` without breaking Phase 7.2 consumers

**Files:**
- Modify: `frontend/src/stores/workspaceStore.ts`
- Modify: relevant `frontend/src/stores/__tests__/*.spec.ts`

**Interfaces:**
- Adds canonical `graphView` and route-scoped read caches.
- Keeps existing `selectedRouteId`, `selectedNodeId`, `routeLineages`, `ensureRouteLineage`, etc. **temporarily** so current components compile until Task 7.

- [ ] **Step 1: Write failing load/refresh tests**

`loadWorkspace` and `refreshWorkspace` must fetch/reload canonical `GraphWorkspaceView`. After Runtime mutation, `graphView` must be replaced from backend response, never patched locally.

- [ ] **Step 2: Add canonical server state**

```ts
graphView: null as GraphWorkspaceView | null,
requirementStatesByRoute: {} as Record<string, RequirementStateView>,
loadingRequirementRouteId: null as string | null,
selectedSpecIdByRoute: {} as Record<string, string | null>,
```

Keep existing active-route `requirementState` during migration so old components still work.

- [ ] **Step 3: Add explicit route-reading actions**

```ts
async ensureRequirementState(routeId: string): Promise<RequirementStateView | null>
async loadRouteSpecs(routeId: string): Promise<void>
selectSpecForRoute(routeId: string, snapshotId: string | null): void
selectedSpecForRoute(routeId: string): SpecSnapshotResponse | null
```

RequirementState and Spec caches are indexed by explicit route ID; no selected-route global decides their ownership.

- [ ] **Step 4: Make canonical Graph part of every Runtime refresh**

Fetch graph alongside project/active/routes/active requirement-state:

```ts
const [project, activeState, routes, requirementState, graphView] = await Promise.all([
  getProject(this.projectId),
  getActiveState(this.projectId),
  listRoutes(this.projectId),
  getRequirementState(this.projectId),
  getProjectGraph(this.projectId),
])
```

Do not derive route membership from `routeLineages` for Graph UI.

- [ ] **Step 5: Preserve Active-only command targeting**

Tests must prove browser Focus cannot alter `draftQuestion`, `submitAnswer`, `activate/restore/archive/delete/fork/regenerate`, or `generateSpec`. `workspaceStore` must not import `graphUiStore`.

Change generate result handling to expose the real artifact:

```ts
async generateSpec(): Promise<SpecGenerationResponse | null>
```

On success load `result.specSnapshot.routeId` history, select the returned snapshot in `selectedSpecIdByRoute`, and return `result`. Do not set Focus inside the server store.

- [ ] **Step 6: Verify old UI still compiles after migration additions**

```bash
npm run test:unit -- src/stores/__tests__ --run
npm run typecheck
npm run build
```

Expected: PASS. If a legacy test asserts boolean `true` from `generateSpec`, update it to assert non-null returned result and correct route-scoped cache; do not delete the legacy UI state yet.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/stores/workspaceStore.ts frontend/src/stores/__tests__
git commit -m "refactor: add canonical graph workspace state"
```

---

### Task 5: Build the custom Question Node with direct answer interaction

**Files:**
- Create: `frontend/src/components/graph/GraphQuestionNode.vue`
- Create: `frontend/src/components/graph/__tests__/GraphQuestionNode.spec.ts`

**Interfaces:**
- Consumes `SpecAgentGraphNodeData`, `submitting`, `pending`.
- Emits `submit-answer`, `select-node`, `toggle-expanded`, `fork`, `regenerate`.

- [ ] **Step 1: Write current-answerable-node tests**

Assert exact backend option IDs are used, option-only/free-text-only/combined payloads work, submit disables with no input or while pending, and purpose/option impact render verbatim.

- [ ] **Step 2: Write historical/shared-node tests**

Assert historical node has no inputs, shows selected option label, answer summary uses a 3–4-line clamp class, expanded view shows full question/purpose/all options/full per-route answers, and shared-node primary answer follows projection data.

- [ ] **Step 3: Write sizing and drag-safety tests**

Assert `canAnswer=true` adds current-large class, historical/answered node uses compact class, the header has `data-test="node-drag-handle"`, and body controls carry Vue Flow `nodrag`/`nowheel` classes as appropriate.

- [ ] **Step 4: Implement component structure**

```vue
<article :class="['graph-question-node', { 'graph-question-node--current': data.canAnswer }]">
  <header class="graph-question-node__header" data-test="node-drag-handle">...</header>
  <div class="graph-question-node__body nodrag">...</div>
</article>
```

Local answer refs reset when node ID or Active-route answer identity changes.

- [ ] **Step 5: Implement historical action emits only**

`从此分支` / `重新生成这个问题` emit intent upward. Never edit historical answer in place and never mutate graph data locally.

- [ ] **Step 6: Verify**

```bash
npm run test:unit -- src/components/graph/__tests__/GraphQuestionNode.spec.ts --run
npm run typecheck
npm run build
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/graph
git commit -m "feat: answer questions directly in graph nodes"
```

---

### Task 6: Build Graph Canvas, selection/group drag, route/node location, and position persistence

**Files:**
- Create: `frontend/src/components/graph/GraphStartPlaceholder.vue`
- Create: `frontend/src/components/graph/GraphToolbar.vue`
- Create: `frontend/src/components/graph/GraphCanvas.vue`
- Create: `frontend/src/components/graph/__tests__/GraphCanvas.spec.ts`
- Modify: `frontend/src/style.css`

**Interfaces:**
- Consumes canonical projection and `graphUiStore`.
- Exposes `locateNode(nodeId: string)` and `locateRoute(routeId: string)` to Workspace shell.

- [ ] **Step 1: Write empty-project placeholder test**

With an Active route and zero nodes, assert centered UI-only copy:

```text
开始需求澄清
还没有生成任何问题。
起草第一个问题
```

It must not create a fake node ID or persist a placeholder coordinate.

- [ ] **Step 2: Render Vue Flow with connection editing disabled**

Use `v-model:nodes`, `v-model:edges`, custom `node-question` slot, `nodesConnectable=false`, and edge updates disabled. Use projection-provided `dragHandle` rather than making the whole node draggable.

- [ ] **Step 3: Configure selection behavior**

Maintain a `shiftSelecting` flag from keydown/keyup. Configure Vue Flow so:

```text
normal click -> one node
Ctrl/Cmd -> multi-selection
Shift + pane drag -> marquee selection
normal pane drag -> pan
pane click -> clear selection
```

Mirror Vue Flow selected node IDs into `graphUiStore`; do not persist selection.

Use the exact 1.48.2 exported prop/event types during implementation; if TypeScript exposes key-code unions rather than plain strings, use those exported types without changing package version.

- [ ] **Step 4: Persist group movement only on drag stop**

At drag stop, read all currently selected/moved Vue Flow nodes and merge their positions into `graphUiStore.nodePositions`. Do not write localStorage on pointer-move.

Rely on Vue Flow reactive edges for live endpoint movement; E2E will verify SVG path changes during drag.

- [ ] **Step 5: Reconcile canonical graph refresh without moving existing nodes**

For each canonical node:

```text
saved position exists -> use exactly
new ID -> place locally near parent via placeNewNode
```

Never run full auto-layout after answer/Fork/Regenerate/Activate refresh. A new current node outside viewport may be smoothly located by ID only.

- [ ] **Step 6: Implement explicit route/node location methods**

`locateNode(id)` smoothly brings that node into view without changing Focus/Active.

`locateRoute(routeId)` reads `graphView.routes[].lineageNodeIds` and fits/centers only that route's visible nodes. It must not set `focusRouteId` and must not change Active.

Expose both with `defineExpose` for `WorkspaceView`.

- [ ] **Step 7: Implement toolbar**

Actions:

```text
放大
缩小
适应视图
重新自动布局
显示全部路线
```

Before full auto-layout show:

```text
重新自动布局将覆盖当前项目手工调整过的节点位置。Runtime 历史不会改变。
```

On confirm calculate all visible positions, update flow nodes, persist positions. Do not change Focus/Active/lifecycle.

- [ ] **Step 8: Unit-test our handlers; leave geometry to Playwright**

Mock Vue Flow composables in jsdom where needed. Test placeholder, selection-state mirroring, persistence-on-stop, `locateRoute` route-membership selection, and auto-layout confirmation path.

- [ ] **Step 9: Verify**

```bash
npm run test:unit -- src/components/graph/__tests__/GraphCanvas.spec.ts --run
npm run typecheck
npm run build
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add frontend/src/components/graph frontend/src/style.css
git commit -m "feat: add interactive graph canvas"
```

---

### Task 7: Cut Workspace over to Graph-first shell and remove legacy selection/lineage ownership atomically

**Files:**
- Create: `frontend/src/components/workspace/ResizableSidebar.vue`
- Create: `frontend/src/components/workspace/RouteSidebar.vue`
- Create: `frontend/src/components/workspace/WorkspaceInspector.vue`
- Create: `frontend/src/components/workspace/NodeInspector.vue` when useful
- Create tests under `frontend/src/components/workspace/__tests__/`
- Modify: `frontend/src/views/WorkspaceView.vue`
- Modify: `frontend/src/views/__tests__/WorkspaceView.spec.ts`
- Modify: `frontend/src/stores/workspaceStore.ts`
- Modify: `frontend/src/stores/__tests__/*.spec.ts`
- Adapt: `frontend/src/components/RequirementStatePanel.vue`
- Adapt: `frontend/src/components/SpecSnapshotList.vue`
- Adapt: `frontend/src/components/SpecSnapshotPanel.vue`
- Adapt: `frontend/src/components/RouteActionMenu.vue`
- Adapt existing Fork/Regenerate/lifecycle dialogs
- Modify: `frontend/src/style.css`

**Interfaces:**
- Graph Canvas becomes the only primary node-history UI.
- `readingRouteId = graphUi.readingRouteId(workspace.activeRoute?.id ?? null)`.

- [ ] **Step 1: Write resizable sidebar tests**

Assert both default open, independent collapse, width clamps, resize persistence, and Graph Canvas remains mounted/expands when a sidebar collapses.

- [ ] **Step 2: Write Route Sidebar tests**

Each route shows Chinese lifecycle label + independent `当前路线` indicator + `lineageNodeIds.length`. Separate menu groups:

```text
查看: 定位路线 / 聚焦此路线 / 弱化路线 / 隐藏路线
路线状态: 设为当前路线 / 归档 / 恢复 / 删除路线
```

`定位路线` emits only a viewport request. `聚焦此路线` only changes Focus. `隐藏路线` cannot hide Active.

- [ ] **Step 3: Wire left route display controls**

Filters: 开放 / 已替代 / 已归档 / 已删除. Manual Dim/Hide and lifecycle filters remain distinct. Focus temporarily dims other visible routes but exiting Focus restores prior manual display state.

- [ ] **Step 4: Write Inspector reading-context tests**

Active=A, Focus=B must produce:

```text
需求状态 · B
规格快照 · B
ensureRequirementState(B)
loadRouteSpecs(B)
```

while the only answerable graph node remains Active A.

No Focus -> RequirementState/Spec use A.

- [ ] **Step 5: Implement Inspector tabs**

Tabs: `详情 / 需求状态 / 规格`.

No selected node -> default 需求状态. Selected node -> default 详情 with full question/purpose/options/all route-specific answers/route memberships/provenance. Never duplicate current answer submit in Inspector.

- [ ] **Step 6: Implement explicit Fork-base rule**

For a shared historical node let user choose a base route. Enforce the compatibility rule at top of this plan. Fork button becomes enabled only for chosen Active+OPEN route; otherwise show explicit Restore/Activate prerequisite.

Do not change Fork API payload.

- [ ] **Step 7: Keep deterministic Regenerate semantics**

Reuse/adapt current dialog. Chinese shell labels; replacement options submit label/impact only; root and non-eligible lifecycle guards remain. No model call.

- [ ] **Step 8: Keep Spec generation Active-only while reading Focus**

With Active=A and Focus=B, Spec tab shows B history but button copy is:

```text
为当前路线生成规格
当前路线：A
你目前正在查看 B，生成操作将针对当前路线 A。
```

Call `workspace.generateSpec()`. On non-null result, clear Focus so `readingRouteId` follows returned snapshot route and select `result.specSnapshot.id` in that route's cache. This preserves the previous cross-route spec fix.

- [ ] **Step 9: Implement Source Ref -> Graph navigation**

For node ref: `locateNode(refId)` + select node.

For answer ref: find exact `graphView.answers` item by answer ID; select its `nodeId`, locate node, and show that route's answer as Inspector detail context without changing Active. If unresolved, keep the ref visible and do not guess.

- [ ] **Step 10: Replace WorkspaceView with Graph-first layout**

Conceptually:

```text
Resizable Route Sidebar | GraphCanvas (flex:1) | Resizable Inspector
```

Both sidebars default open. Canvas fills all remaining height/width.

- [ ] **Step 11: Now remove legacy Phase 7.2 graph-navigation state from `workspaceStore`**

Only after the new `WorkspaceView` has zero references, remove:

```text
selectedRouteId
selectedNodeId
routeLineages
loadingLineage
ensureRouteLineage
reloadLineage
selectedHistoricalNode
selectedLineage
```

Keep Runtime commands and canonical caches. Update store/view tests in the same commit so typecheck never sees a broken intermediate tree.

- [ ] **Step 12: Verify the cutover as one buildable deliverable**

```bash
npm run test:unit -- src/components/workspace/__tests__ src/views/__tests__/WorkspaceView.spec.ts src/stores/__tests__ --run
npm run typecheck
npm run build
```

Expected: PASS.

- [ ] **Step 13: Commit**

```bash
git add frontend/src/components/workspace frontend/src/views frontend/src/stores frontend/src/components/RequirementStatePanel.vue frontend/src/components/SpecSnapshotList.vue frontend/src/components/SpecSnapshotPanel.vue frontend/src/components/RouteActionMenu.vue frontend/src/components/ForkRouteDialog.vue frontend/src/components/RegenerateNodeDialog.vue frontend/src/components/ConfirmRouteActionDialog.vue frontend/src/style.css
git commit -m "feat: make graph the primary workspace"
```

---

### Task 8: Complete Chinese product-shell localization and delete obsolete Phase 7.2 panels

**Files:**
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/views/ProjectsView.vue`
- Modify: `frontend/src/components/ProjectCreateForm.vue`
- Modify: `frontend/src/components/ApiErrorBanner.vue`
- Modify remaining user-visible reusable components/dialogs.
- Delete when no imports remain: `ClarificationPanel.vue`, `HistoricalNodePanel.vue`, `RouteLineage.vue`, `RouteWorkspacePanel.vue`, `WorkspaceRightPanel.vue` plus obsolete tests.

- [ ] **Step 1: Add Chinese-shell regression tests**

Key visible copy includes:

```text
项目 / 创建项目 / 工作区 / 路线 / 当前路线
开放 / 已替代 / 已归档 / 已删除
需求状态 / 规格快照
从此分支 / 重新生成这个问题 / 恢复 / 归档 / 删除路线 / 重试
```

- [ ] **Step 2: Add verbatim-content regression test**

Supply backend/user content:

```text
What outcome matters most?
Clarify the primary goal.
Product team
Keep this exact user answer.
Original English spec body.
```

Assert exact strings remain unchanged while shell labels are Chinese.

- [ ] **Step 3: Translate all shell feedback/errors without changing API codes**

Examples:

```text
Question drafted. -> 问题已起草。
Answer recorded. -> 回答已记录。
Route activated. -> 已设为当前路线。
Fork created. -> 已创建新分支路线。
Question regenerated. -> 已创建替代问题路线。
Spec snapshot generated. -> 已生成规格快照。
```

No `vue-i18n`.

- [ ] **Step 4: Prove legacy panels are unused, then delete them**

```bash
grep -R "ClarificationPanel\|HistoricalNodePanel\|RouteLineage\|RouteWorkspacePanel\|WorkspaceRightPanel" frontend/src --exclude-dir=node_modules
```

Before deletion, only the files themselves/tests may remain. Delete those files/tests; do not delete reusable command dialogs.

- [ ] **Step 5: Verify full frontend**

```bash
npm run test:unit -- --run
npm run typecheck
npm run build
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A frontend/src
git commit -m "feat: localize graph workspace in Chinese"
```

---

### Task 9: Rework Playwright around Graph Workspace behavior

**Files:**
- Modify: `frontend/e2e/helpers.ts`
- Modify: `frontend/e2e/core-clarification.spec.ts`
- Modify: `frontend/e2e/fork.spec.ts`
- Modify: `frontend/e2e/lifecycle.spec.ts`
- Modify: `frontend/e2e/regenerate.spec.ts`
- Modify: `frontend/e2e/spec.spec.ts`
- Create: `frontend/e2e/graph-layout.spec.ts`
- Create: `frontend/e2e/graph-routes.spec.ts`

**Environment:** real local backend, fake gateway, existing Playwright server config.

- [ ] **Step 1: Add stable graph selectors**

Use IDs/data attributes for graph canvas, start placeholder, node ID, drag handle, route ID, Focus/Dim/Hide, sidebars and toolbar. Avoid generated question text when a stable selector exists.

- [ ] **Step 2: E2E — empty start -> first real node**

Create project, verify centered placeholder, click `起草第一个问题`, verify placeholder disappears and one real root current node appears.

- [ ] **Step 3: E2E — direct node answer and local placement**

Answer inside current graph node. Assert answered node becomes compact historical node, next current node appears to the right, and the old node bounding box position does not shift beyond a small rendering tolerance.

- [ ] **Step 4: E2E — drag, live edge and reload persistence**

Drag by title handle only. Assert edge SVG path changes while/after movement, reload, and assert node returns near saved graph coordinate.

- [ ] **Step 5: E2E — multi-select group move**

Select two nodes with Ctrl/Cmd, drag one selected header, and assert both nodes move by approximately the same delta.

- [ ] **Step 6: E2E — route location is not Focus or Activate**

Click `定位路线`; viewport changes/route becomes visible, but Focus marker and Active route remain unchanged.

- [ ] **Step 7: E2E — Fork shared node semantics**

Fork from answered history. Assert shared node is not duplicated, old route answer stays, new active route has no copied answer and displays waiting/answerable state as appropriate. For a non-Active selected base route, verify Fork is disabled until explicit Activate.

- [ ] **Step 8: E2E — deterministic Regenerate**

Submit explicit replacement question/options; assert old route/history remains, old route becomes 已替代, replacement route becomes 当前路线, replacement node appears, and replacement relation differs visually from lineage.

- [ ] **Step 9: E2E — Focus/Dim/Hide/Show All/lifecycle**

Verify Focus never changes Active, Dim leaves route clickable, Hide removes only route-exclusive elements, shared nodes remain, Active cannot be hidden, Show All restores manual display states, and archive/restore/delete still change canonical lifecycle.

- [ ] **Step 10: E2E — sidebars and work/read context**

Set Active=A, Focus=B. Assert RequirementState/Spec history show B, current answerable node remains A, generate warning targets A, generation succeeds on A, reading context follows returned A artifact, and sidebar width/open state survives reload.

- [ ] **Step 11: Run complete Playwright suite**

```bash
npm run test:e2e
```

Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add frontend/e2e
git commit -m "test: cover graph workspace end to end"
```

---

### Task 10: Phase 7.3 closure verification and documentation

**Files:**
- Create: `docs/PHASE_7_3_EXIT_CRITERIA.md`
- Modify `README.md` only if it still describes the old three-panel workspace as current UI.

- [ ] **Step 1: Run final frontend verification**

```bash
cd frontend
npm run test:unit -- --run
npm run typecheck
npm run build
npm run test:e2e
```

Every command must exit 0.

- [ ] **Step 2: Run final backend verification from the same tree**

```bash
cd ../backend
./gradlew test
```

Expected: exit 0, zero failures; established live-provider skips remain acceptable under fake-gateway verification.

- [ ] **Step 3: Inspect forbidden scope**

```bash
cd ..
git diff --stat ca30d1d6fc2e576a1cbf140f3e0b9025acc2d99f..HEAD
```

Confirm no implementation of:

```text
manual graph rewiring or node deletion
backend layout persistence
AI translation/language switching
AI-random regenerate
provider/model routing or fallback
Runtime route/context semantic rewrite
```

- [ ] **Step 4: Write `docs/PHASE_7_3_EXIT_CRITERIA.md` using actual evidence**

Mark PASS only for verified items:

```text
Graph-first workspace
all routes one graph
shared node/edge dedupe
route-specific answers
current node direct answer
historical answer summary/expand
header-only dragging
multi-select/group movement
live edges
position persistence
new node does not move existing nodes
explicit auto-layout
locate/focus/dim/hide/show all
Active protection
Fork/Regenerate preserve old history
resizable/collapsible sidebars
readingRouteId RequirementState/Spec
Active-only Runtime commands
Chinese shell + verbatim content
backend/full tests
frontend unit/type/build
Playwright
origin/main == HEAD
```

- [ ] **Step 5: Commit closure docs**

```bash
git add docs/PHASE_7_3_EXIT_CRITERIA.md README.md
git commit -m "docs: close phase 7.3 graph workspace"
```

If README is unchanged, omit it from `git add`.

- [ ] **Step 6: Push and verify remote equality**

```bash
git status --short
git push origin main
git fetch origin
git rev-parse HEAD
git rev-parse origin/main
```

`git status --short` must be empty and the two SHAs identical.

## Phase 7.3B Completion Report

Report actual outputs; do not invent test counts:

```text
PHASE 7.3B

HEAD: output of git rev-parse HEAD
origin/main: output of git rev-parse origin/main
remote_equal: true only when equal
working_tree_clean: true only when git status --short is empty

Frontend:
- unit tests: PASS with actual file/test counts
- typecheck: PASS
- build: PASS
- Playwright: PASS with actual test count

Backend:
- ./gradlew test: PASS with actual test/skip counts

UX invariants:
- Graph-first workspace: PASS
- shared nodes/edges deduplicated: PASS
- route-specific answers: PASS
- direct current-node answer: PASS
- multi-select/group drag + live edges: PASS
- local layout persistence: PASS
- new node preserves old coordinates: PASS
- locate/focus/dim/hide: PASS
- Active route protected: PASS
- Fork/Regenerate history preserved: PASS
- sidebars resize/collapse/persist: PASS
- reading/work context separation: PASS
- Chinese shell/verbatim content: PASS

Forbidden scope:
- Runtime route/context semantics changed: NO
- model/provider behavior changed: NO
- graph layout persisted to backend: NO
- arbitrary node/edge editing added: NO
```

# Phase 7.3B Graph Workspace Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Phase 7.2 administration-style workspace with a Chinese graph-first requirements exploration workspace where all routes share one interactive Vue Flow canvas and the current question is answered directly inside its node.

**Architecture:** Consume the Phase 7.3A canonical graph/read APIs into `workspaceStore`, keep browser-only selection/focus/layout/sidebar state in a separate `graphUiStore`, and project canonical Runtime data through pure graph projection/layout functions into Vue Flow. Vue Flow owns rendering, pan/zoom, selection and dragging; it never owns Runtime history or lifecycle semantics.

**Tech Stack:** Vue 3.5, TypeScript, Vite, Pinia, Vue Router, `@vue-flow/core@1.48.2`, Vitest, Vue Test Utils, Playwright.

**Spec:** `docs/superpowers/specs/2026-08-18-phase-7-3-graph-workspace-design.md`

**Backend prerequisite:** Phase 7.3A must already be on `origin/main`, providing `GET /api/v1/projects/{projectId}/graph` and `GET /api/v1/projects/{projectId}/routes/{routeId}/requirement-state`.

## Global Constraints

- Graph is an interactive Runtime History Viewer, not an arbitrary DAG editor.
- Do not add create-node, delete-node, reconnect-edge, edit-parent, or edit-historical-answer UI.
- `parentNodeId`, `supersedesNodeId`, route membership, lifecycle, active pointer, answers, patches and specs remain backend-owned.
- `Active Route` is work context; `Focus Route` is browser-only reading context.
- `readingRouteId = focusRouteId ?? activeRouteId`.
- Draft question, submit answer and generate spec always target the backend Active Route, never Focus Route.
- Shared Node renders once. Route-specific answers remain separate by `routeId + nodeId`.
- Current active unanswered Node contains options/free-text/submit directly inside the graph node.
- Historical Node shows selected option and 3–4 lines of free-text answer by default, with expand for full details.
- Current node is visually larger than historical nodes.
- Existing nodes never move merely because a new node appears.
- Only explicit `重新自动布局` may overwrite saved manual node positions.
- Node positions and route display preferences are local browser state per project.
- Left/right sidebar open state and widths are browser-local workspace preferences.
- Do not persist viewport pan/zoom in Phase 7.3.
- Product shell is Chinese. Backend/user content (question, purpose, option label/impact, user answer, spec content) must remain verbatim.
- Do not add `vue-i18n` or a language switcher.
- Do not modify Agent prompts or model language behavior.
- Do not add model/provider/fallback/retry functionality.
- Use stable `data-test` attributes for unit/E2E selection.
- Use TDD and commit after each independently testable task.
- Final implementation must run under the existing fake-gateway E2E environment.
- Push implementation commits to `origin main`; finish with clean working tree and `origin/main == HEAD`.

## Important Fork Compatibility Rule

The existing Runtime fork command accepts `projectId + nodeId + label`; it does not accept `sourceRouteId`. Do not change that command contract in Phase 7.3B.

For a historical shared node that belongs to multiple routes:

1. UI must show all route memberships.
2. User must explicitly choose which OPEN route is the intended base.
3. If that route is not currently Active, do **not** guess and do **not** silently run two commands. Show `先设为当前路线` and require the user to activate it first.
4. Once that route is Active, `从此分支` may call the existing fork API; Runtime then deterministically prefers the Active OPEN route containing the node.
5. Archived/deleted/superseded routes must be restored before they can become a fork base, using the existing lifecycle command.

This preserves the approved no-Runtime-semantic-change boundary.

## File Structure

### Dependencies/API contracts

- Modify `frontend/package.json` and lockfile — add `@vue-flow/core@1.48.2` only; do not add another graph engine.
- Modify `frontend/src/main.ts` — import Vue Flow core/theme CSS.
- Modify `frontend/src/api/types.ts` — add `GraphWorkspaceView` contracts.
- Create `frontend/src/api/graph.ts` — graph endpoint client.
- Modify `frontend/src/api/requirementState.ts` — route-scoped requirement-state client.

### Pure graph/domain-view utilities

- Create `frontend/src/graph/graphTypes.ts` — browser graph types and node/edge data contracts.
- Create `frontend/src/graph/graphProjection.ts` — canonical graph + UI state -> deduped Vue Flow node/edge projection.
- Create `frontend/src/graph/graphLayout.ts` — initial left-to-right layout and local placement of new nodes.
- Create `frontend/src/graph/graphLayoutStorage.ts` — versioned defensive localStorage.
- Create corresponding tests under `frontend/src/graph/__tests__/`.

### Stores

- Modify `frontend/src/stores/workspaceStore.ts` — load canonical graph, route-scoped requirement state and spec caches; remove historical lineage reconstruction/selection as UI responsibility.
- Create `frontend/src/stores/graphUiStore.ts` — selection, focus, filters, display state, expanded nodes, positions and sidebar preferences.
- Modify/create tests under `frontend/src/stores/__tests__/`.

### Graph UI

- Create `frontend/src/components/graph/GraphCanvas.vue`.
- Create `frontend/src/components/graph/GraphQuestionNode.vue`.
- Create `frontend/src/components/graph/GraphStartPlaceholder.vue`.
- Create `frontend/src/components/graph/GraphToolbar.vue`.
- Create tests under `frontend/src/components/graph/__tests__/`.

### Workspace shell

- Create `frontend/src/components/workspace/ResizableSidebar.vue`.
- Create `frontend/src/components/workspace/RouteSidebar.vue`.
- Create `frontend/src/components/workspace/WorkspaceInspector.vue`.
- Create `frontend/src/components/workspace/NodeInspector.vue` if splitting keeps `WorkspaceInspector.vue` focused.
- Modify `frontend/src/views/WorkspaceView.vue`.
- Modify `frontend/src/style.css`.
- Adapt existing `RequirementStatePanel.vue`, `SpecSnapshotList.vue`, `SpecSnapshotPanel.vue`, route lifecycle dialogs and regenerate/fork dialogs rather than duplicating their Runtime behavior.

### Chinese shell

- Modify `frontend/src/App.vue`, `frontend/src/views/ProjectsView.vue`, `frontend/src/components/ProjectCreateForm.vue`, `ApiErrorBanner.vue`, lifecycle dialogs/menus, Requirement State/Spec components and all remaining user-visible workspace copy.
- Delete Phase 7.2-only display components after the new workspace no longer imports them: `ClarificationPanel.vue`, `HistoricalNodePanel.vue`, `RouteLineage.vue`, `RouteWorkspacePanel.vue`, `WorkspaceRightPanel.vue`. Keep reusable dialogs/components.

### E2E/docs

- Modify `frontend/e2e/core-clarification.spec.ts`, `fork.spec.ts`, `lifecycle.spec.ts`, `regenerate.spec.ts`, `spec.spec.ts`, `helpers.ts` to use graph UI.
- Create `frontend/e2e/graph-layout.spec.ts`.
- Create `frontend/e2e/graph-routes.spec.ts` if route display/focus coverage is clearer as a separate file.
- Create `docs/PHASE_7_3_EXIT_CRITERIA.md` at closure.

---

### Task 1: Add Vue Flow and canonical Graph API contracts

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Modify: `frontend/src/main.ts`
- Modify: `frontend/src/api/types.ts`
- Create: `frontend/src/api/graph.ts`
- Modify: `frontend/src/api/requirementState.ts`
- Test: `frontend/src/api/__tests__/graph.spec.ts`
- Modify test: `frontend/src/api/__tests__/requirementState.spec.ts` if it exists; otherwise create it.

**Interfaces:**
- Produces `getProjectGraph(projectId: string): Promise<GraphWorkspaceView>`.
- Produces `getRouteRequirementState(projectId: string, routeId: string): Promise<RequirementStateView>`.

Use these TypeScript contracts exactly:

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

Do not install React Flow, Cytoscape, D3 graph layout, ELK, Dagre, or a second graph engine in this phase.

- [ ] **Step 2: Import Vue Flow styles once**

In `frontend/src/main.ts` add:

```ts
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
```

Keep the existing app stylesheet import after library styles so Spec Agent CSS can override presentation.

- [ ] **Step 3: Write failing API client tests**

Mock `apiClient.get` and assert exact paths:

```ts
expect(apiClient.get).toHaveBeenCalledWith('/projects/p1/graph')
expect(apiClient.get).toHaveBeenCalledWith('/projects/p1/routes/r2/requirement-state')
```

- [ ] **Step 4: Implement the API clients**

`frontend/src/api/graph.ts`:

```ts
import { apiClient } from './client'
import type { GraphWorkspaceView } from './types'

export function getProjectGraph(projectId: string): Promise<GraphWorkspaceView> {
  return apiClient.get<GraphWorkspaceView>(`/projects/${projectId}/graph`)
}
```

`requirementState.ts` keeps the old active-route read and adds:

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

- [ ] **Step 5: Run API tests and typecheck**

```bash
npm run test:unit -- src/api/__tests__/graph.spec.ts src/api/__tests__/requirementState.spec.ts
npm run typecheck
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/main.ts frontend/src/api
git commit -m "feat: add graph workspace frontend contracts"
```

---

### Task 2: Add defensive browser persistence and `graphUiStore`

**Files:**
- Create: `frontend/src/graph/graphTypes.ts`
- Create: `frontend/src/graph/graphLayoutStorage.ts`
- Create: `frontend/src/graph/__tests__/graphLayoutStorage.spec.ts`
- Create: `frontend/src/stores/graphUiStore.ts`
- Create: `frontend/src/stores/__tests__/graphUiStore.spec.ts`

**Interfaces:**
- Produces `GraphRouteDisplayState = 'normal' | 'dimmed' | 'hidden'`.
- Produces `GraphPosition = { x: number; y: number }`.
- Produces localStorage keys `spec-agent.graph-layout.v1.<projectId>` and `spec-agent.workspace-ui.v1`.
- Produces `useGraphUiStore()` with selection/focus/filter/layout/sidebar actions.

Use these storage shapes:

```ts
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

Default lifecycle filters:

```ts
{
  open: true,
  superseded: true,
  archived: true,
  deleted: false,
}
```

Default sidebars:

```ts
left:  { open: true, width: 280 }
right: { open: true, width: 380 }
```

- [ ] **Step 1: Write storage failure tests first**

Cover all exact cases:

```text
invalid JSON -> defaults
NaN/Infinity x/y -> ignored
stale ids -> allowed in storage but ignored during reconcile
left width < 220 -> clamp 220
left width > 420 -> clamp 420
right width < 300 -> clamp 300
right width > 600 -> clamp 600
localStorage.setItem throws -> no exception escapes
```

Use a throwing mock storage to prove Runtime UI actions are not blocked.

- [ ] **Step 2: Implement defensive storage helpers**

Public functions:

```ts
loadProjectGraphPreferences(projectId: string): ProjectGraphPreferencesV1
saveProjectGraphPreferences(projectId: string, value: ProjectGraphPreferencesV1): void
loadWorkspaceUiPreferences(): WorkspaceUiPreferencesV1
saveWorkspaceUiPreferences(value: WorkspaceUiPreferencesV1): void
```

Every function catches browser storage exceptions.

- [ ] **Step 3: Write `graphUiStore` tests**

Test:

```text
single select clears old selection
Ctrl/Cmd toggle preserves/removes membership
focus is independent from active route
hide active route is rejected
showAll clears focus + dim/hide but keeps lifecycle filters
reset view restores default lifecycle filters too
expanded node ids toggle
sidebar width/open state persist
reconcile removes missing node selections
reconcile clears focus if focused route is not visible
reconcile forces active route display state away from hidden
```

- [ ] **Step 4: Implement `graphUiStore`**

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

Required getter:

```ts
readingRouteId(activeRouteId: string | null): string | null {
  return this.focusRouteId ?? activeRouteId
}
```

Required active-route protection:

```ts
hideRoute(routeId: string, activeRouteId: string | null): boolean {
  if (routeId === activeRouteId) return false
  this.routeDisplayStates[routeId] = 'hidden'
  this.persistProject()
  return true
}
```

- [ ] **Step 5: Run focused tests**

```bash
npm run test:unit -- src/graph/__tests__/graphLayoutStorage.spec.ts src/stores/__tests__/graphUiStore.spec.ts
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
- Consumes `GraphWorkspaceView`, active node ID, `graphUiStore`-shaped UI state and saved positions.
- Produces projected nodes/edges with route memberships and answer presentation.

Define node data independent of Vue components:

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

- [ ] **Step 1: Write projection tests for the approved semantics**

Use one fixture containing:

```text
Route A: A -> B -> C, active
Route B: A -> B -> D, open
Route C: A -> B -> E, archived
```

Assert:

- exactly five projected nodes, not nine;
- A->B edge exists once with routeIds A/B/C;
- Focus B answer is primary even when Active is A;
- without Focus, Active A answer is primary;
- hidden B removes B-exclusive D but preserves shared A/B;
- lifecycle-hidden archived C removes E;
- Active A cannot disappear even if its display state was corrupted to hidden;
- replacement edge is separate and dashed metadata; it never changes lineage parent.

- [ ] **Step 2: Implement route visibility and membership helpers**

Required pure helpers:

```ts
getVisibleRouteIds(view, uiState): Set<string>
getNodeRouteMembership(view): Map<string, string[]>
getLineageEdgeMembership(view): Map<string, { source: string; target: string; routeIds: string[] }>
selectPrimaryAnswer(nodeId, answers, focusRouteId, activeRouteId): GraphAnswerPresentation | null
```

Lineage edge key must be deterministic:

```ts
const edgeKey = `${source}->${target}`
```

Replacement edge ID must be distinct:

```ts
const replacementId = `replacement:${node.supersedesNodeId}->${node.id}`
```

- [ ] **Step 3: Write layout tests**

Assert:

```text
root has smallest x
child x > parent x
siblings get distinct y slots
saved positions win over computed positions
placeNewNode never changes input positions
occupied preferred slot causes vertical offset search
```

- [ ] **Step 4: Implement layout**

Use simple deterministic constants rather than another layout dependency:

```ts
export const HORIZONTAL_GAP = 360
export const VERTICAL_GAP = 220
```

For initial layout, compute depth from `parentNodeId` and assign stable vertical slots by deterministic node order from `GraphWorkspaceView.nodes`.

For a new node:

```ts
export function placeNewNode(
  parent: GraphPosition | null,
  occupied: GraphPosition[],
): GraphPosition
```

Try offsets in this order:

```ts
[0, 1, -1, 2, -2, 3, -3]
```

using `VERTICAL_GAP`; use `{x: 0, y: 0}` as root fallback only when no parent exists.

- [ ] **Step 5: Build the final pure `projectGraph` function**

```ts
export function projectGraph(input: GraphProjectionInput): GraphProjectionResult
```

It must preserve saved coordinates for every known node ID and calculate coordinates only for missing IDs.

- [ ] **Step 6: Run projection/layout tests**

```bash
npm run test:unit -- src/graph/__tests__/graphProjection.spec.ts src/graph/__tests__/graphLayout.spec.ts
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/graph
git commit -m "feat: project runtime history into graph view"
```

---

### Task 4: Refactor `workspaceStore` into canonical server state/cache

**Files:**
- Modify: `frontend/src/stores/workspaceStore.ts`
- Modify: `frontend/src/stores/__tests__/workspaceStore.spec.ts` and `workspaceRouteStore.spec.ts` as applicable.

**Interfaces:**
- Consumes `getProjectGraph` and `getRouteRequirementState`.
- Produces canonical `graphView`, route requirement-state cache, specs cache and Runtime commands.
- Stops owning graph selection/focus/positions.

- [ ] **Step 1: Write failing store tests for canonical graph refresh**

Assert `loadWorkspace` and `refreshWorkspace` fetch:

```text
project
active state
route list
graph workspace view
```

and never reconstruct graph membership from cached lineages.

- [ ] **Step 2: Add graph and route-read cache state**

Use:

```ts
graphView: null as GraphWorkspaceView | null,
requirementStatesByRoute: {} as Record<string, RequirementStateView>,
loadingRequirementRouteId: null as string | null,
```

Retain `specsByRoute`.

- [ ] **Step 3: Add explicit route-read actions**

```ts
async ensureRequirementState(routeId: string): Promise<RequirementStateView | null>
async loadRouteSpecs(routeId: string): Promise<void>
```

`ensureRequirementState` must call the new route-scoped endpoint and cache by returned `routeId`.

- [ ] **Step 4: Remove UI ownership of historical selection/lineage**

After the new Graph API is used, remove store state/actions that exist only to reconstruct Phase 7.2 navigation:

```text
selectedNodeId
routeLineages
loadingLineage
ensureRouteLineage
reloadLineage
selectedHistoricalNode
selectedLineage
```

Keep `selectedSpecId` if useful for spec detail selection, but it must not determine reading route.

Remove `selectedRouteId` once no component uses it for reading context. Specs/requirements must receive an explicit `routeId` from `graphUiStore.readingRouteId(...)` instead.

- [ ] **Step 5: Preserve command targeting semantics**

Tests must prove:

```text
focus state cannot alter draftQuestion target
focus state cannot alter submitAnswer target
focus state cannot alter generateSpec target
activate/restore/fork/regenerate refresh canonical graph
```

`workspaceStore` should not import `graphUiStore`; command success returns enough information for `WorkspaceView` to reconcile browser state.

Change `generateSpec` to return the backend result or `null`:

```ts
async generateSpec(): Promise<SpecGenerationResponse | null>
```

On success it loads the returned route's snapshots and returns `result`; it does not set Focus itself.

- [ ] **Step 6: Preserve the previous cross-route spec regression**

Test Active=B, Focus/reading=A at the view/UI layer later; at store level assert generated snapshot always belongs to backend result B and B's spec cache is refreshed.

- [ ] **Step 7: Run store tests**

```bash
npm run test:unit -- src/stores/__tests__
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/stores/workspaceStore.ts frontend/src/stores/__tests__
git commit -m "refactor: make workspace store graph canonical"
```

---

### Task 5: Build the custom Question Node and direct answer interaction

**Files:**
- Create: `frontend/src/components/graph/GraphQuestionNode.vue`
- Create: `frontend/src/components/graph/__tests__/GraphQuestionNode.spec.ts`
- Adapt: `frontend/src/components/ForkRouteDialog.vue`
- Adapt: `frontend/src/components/RegenerateNodeDialog.vue`

**Interfaces:**
- Consumes `SpecAgentGraphNodeData` and current Runtime pending states.
- Emits `submit-answer`, `select`, `fork`, `regenerate`, `toggle-expanded`.

- [ ] **Step 1: Write current-node component tests**

Render an active node with three options and `allowFreeAnswer=true` and assert:

```text
question and purpose visible
option label and impact visible
radio uses exact backend option id
free-text field visible
submit disabled with no input
option-only submit emits selectedOptionId
free-text-only submit emits freeText
option+free-text submit emits both
```

- [ ] **Step 2: Write historical-node tests**

Assert:

```text
historical node has no radio/textarea/submit
selected option label displayed
free text clamped by compact CSS class
expanded state displays full answer/options
shared node displays primary answer and compact other-route count/list
```

- [ ] **Step 3: Implement drag-safety markup**

Use the custom-node root with a draggable header and a Vue Flow `nodrag` body:

```vue
<article class="graph-question-node">
  <header class="graph-question-node__header" data-test="node-drag-handle">
    <span class="graph-question-node__grip">⠿</span>
    <span>{{ data.isCurrent ? '当前问题' : '需求问题' }}</span>
  </header>
  <div class="graph-question-node__body nodrag" data-test="node-body">
    <!-- options, textarea, buttons, historical answer -->
  </div>
</article>
```

Add `nowheel` to scrollable textarea/detail regions if needed so typing/scrolling does not zoom the canvas.

- [ ] **Step 4: Implement direct answer payload construction**

Use local refs reset whenever `data.node.id` or active route answer identity changes. Submit exactly:

```ts
const payload: SubmitAnswerRequest = {}
if (selectedOptionId.value) payload.selectedOptionId = selectedOptionId.value
const text = freeText.value.trim()
if (text) payload.freeText = text
emit('submit-answer', payload)
```

- [ ] **Step 5: Implement historical actions without editing history**

`从此分支` and `重新生成这个问题` only emit commands upward. No historical answer input is ever rendered.

If the node belongs to multiple routes, show route membership in the node/inspector; Fork execution rules are enforced in Task 7.

- [ ] **Step 6: Run component tests**

```bash
npm run test:unit -- src/components/graph/__tests__/GraphQuestionNode.spec.ts
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/graph frontend/src/components/ForkRouteDialog.vue frontend/src/components/RegenerateNodeDialog.vue
git commit -m "feat: answer questions directly in graph nodes"
```

---

### Task 6: Build Graph Canvas, selection, group drag and position persistence

**Files:**
- Create: `frontend/src/components/graph/GraphCanvas.vue`
- Create: `frontend/src/components/graph/GraphStartPlaceholder.vue`
- Create: `frontend/src/components/graph/GraphToolbar.vue`
- Create: `frontend/src/components/graph/__tests__/GraphCanvas.spec.ts`
- Modify: `frontend/src/style.css`

**Interfaces:**
- Consumes `workspaceStore.graphView`, `activeState.activeNode`, `graphUiStore`, `projectGraph`.
- Emits Runtime command intents upward where dialogs/confirmation are required.

- [ ] **Step 1: Write canvas empty-state test**

With Active Route and zero nodes, assert centered UI-only placeholder:

```text
开始需求澄清
还没有生成任何问题。
起草第一个问题
```

and assert it has no Runtime node ID/data-test node identity.

- [ ] **Step 2: Render Vue Flow with custom node slot**

Use separate node/edge bindings and disable connection editing. Required behavior:

```vue
<VueFlow
  v-model:nodes="flowNodes"
  v-model:edges="flowEdges"
  :nodes-connectable="false"
  :edges-updatable="false"
  :selection-key-code="'Shift'"
  :fit-view-on-init="true"
>
  <template #node-question="nodeProps">
    <GraphQuestionNode v-bind="nodeProps" />
  </template>
</VueFlow>
```

If the pinned library's TypeScript names differ slightly, use the 1.48.2 exported types and keep the behavior identical; do not upgrade to another major package during this task.

- [ ] **Step 3: Synchronize selection with `graphUiStore`**

Required user behavior:

```text
normal node click -> one selected node
Ctrl/Cmd + node click -> toggle membership
Shift + drag on pane -> marquee selection
pane click -> clear selection
```

Use Vue Flow selection as the rendered source and mirror its selected node IDs into `graphUiStore` after selection changes. Do not store selection in localStorage.

- [ ] **Step 4: Make selected-node group drag work**

When Vue Flow reports a drag stop, persist positions for **all selected/moved nodes**, not only the pointer target. During drag, Vue Flow's own reactivity must update connected edges continuously; do not manually compute DOM line coordinates.

Persist only at drag stop:

```ts
function persistCurrentPositions(nodes: Node[]): void {
  const positions = Object.fromEntries(nodes.map(node => [node.id, node.position]))
  graphUi.mergeNodePositions(positions)
}
```

- [ ] **Step 5: Preserve existing node positions on canonical refresh**

When `graphView` changes:

```text
known nodeId with saved position -> keep exactly
new nodeId -> placeNewNode(parent saved/current position, occupied positions)
removed/invisible node -> do not force cleanup or move others
```

Do not call `fitView()` on every answer/fork/regenerate refresh. If a newly created current node is outside the viewport, use a targeted smooth center/fit for that node only.

- [ ] **Step 6: Implement explicit auto-layout toolbar action**

Toolbar actions:

```text
放大
缩小
适应视图
重新自动布局
显示全部路线
```

`重新自动布局` opens/uses a confirmation message:

```text
重新自动布局将覆盖当前项目手工调整过的节点位置。Runtime 历史不会改变。
```

On confirm, compute full initial layout, update all node positions, persist them.

- [ ] **Step 7: Add component tests around persistence callbacks and placeholder/toolbar**

Mock Vue Flow where jsdom cannot model geometry; unit-test our handlers and leave real drag/edge behavior for Playwright.

- [ ] **Step 8: Run tests and typecheck**

```bash
npm run test:unit -- src/components/graph/__tests__
npm run typecheck
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/components/graph frontend/src/style.css
git commit -m "feat: add interactive graph canvas"
```

---

### Task 7: Build Route Sidebar, resizable shell and Inspector reading context

**Files:**
- Create: `frontend/src/components/workspace/ResizableSidebar.vue`
- Create: `frontend/src/components/workspace/RouteSidebar.vue`
- Create: `frontend/src/components/workspace/WorkspaceInspector.vue`
- Create: `frontend/src/components/workspace/NodeInspector.vue` if needed
- Create tests under `frontend/src/components/workspace/__tests__/`
- Modify: `frontend/src/views/WorkspaceView.vue`
- Modify: `frontend/src/views/__tests__/WorkspaceView.spec.ts`
- Modify: `frontend/src/style.css`
- Adapt: `frontend/src/components/RequirementStatePanel.vue`
- Adapt: `frontend/src/components/SpecSnapshotList.vue`
- Adapt: `frontend/src/components/SpecSnapshotPanel.vue`
- Adapt: `frontend/src/components/RouteActionMenu.vue`

**Interfaces:**
- Reading context uses `graphUi.readingRouteId(workspace.activeRoute?.id ?? null)`.
- Runtime commands use `workspaceStore` only.

- [ ] **Step 1: Write `ResizableSidebar` tests**

Assert:

```text
default open
collapse/expand independently
left width clamps 220..420
right width clamps 300..600
resize emits/persists width
canvas shell remains mounted when either sidebar collapses
```

- [ ] **Step 2: Write Route Sidebar behavior tests**

For each route show:

```text
label
lifecycle status in Chinese
independent 当前路线 indicator when active
node count from lineageNodeIds.length
```

Route menu must visually separate:

```text
查看: 定位路线 / 聚焦此路线 / 弱化路线 / 隐藏路线
路线状态: 设为当前路线 / 归档 / 恢复 / 删除路线
```

Assert Active Route cannot call `隐藏路线`.

- [ ] **Step 3: Implement Focus/Dim/Hide and filters**

Left sidebar filter labels:

```text
开放
已替代
已归档
已删除
```

`聚焦此路线` updates only `graphUiStore.focusRouteId`.

`设为当前路线` calls backend `workspace.activateRoute(routeId)` and does not silently overwrite an unrelated Focus Route.

- [ ] **Step 4: Write Inspector route-context tests**

Set Active=A, Focus=B and assert:

```text
需求状态 header says B
workspace calls ensureRequirementState(B)
规格历史 loads B
current answer controls still belong to Active A graph node
```

Without Focus, Requirement State/Spec history use Active A.

- [ ] **Step 5: Implement Inspector tabs**

Tabs:

```text
详情
需求状态
规格
```

No selected node -> default `需求状态`.

Selected node -> default `详情`, showing full question/purpose/options and all route-specific answers.

Do not render a duplicate answer-submit UI in Inspector.

- [ ] **Step 6: Enforce explicit Fork base-route compatibility**

For a selected shared historical node:

```text
基于路线
○ Route A
○ Route B
```

If chosen route lifecycle != OPEN, show `先恢复这条路线` and disable Fork.

If chosen OPEN route != Active, show `先设为当前路线` and disable Fork; provide the existing Activate action.

Only when chosen route is Active + OPEN may `[从此分支]` call `workspace.forkNode(nodeId, label)`.

This is an acceptance requirement, not optional polish.

- [ ] **Step 7: Preserve deterministic Regenerate gating**

Regenerate stays allowed only under the existing Runtime/UI guards. Keep replacement question/purpose/options editor, but translate shell labels to Chinese. Never call model generation for regenerate.

- [ ] **Step 8: Wire Spec generation to Active while viewing Focus**

When Active=A, Focus=B, Inspector spec tab displays B history but generate control must say:

```text
为当前路线生成规格
当前路线：A
你目前正在查看 B，生成操作将针对当前路线 A。
```

Call `workspace.generateSpec()`. On success, because returned snapshot belongs to the Active route, clear Focus so reading context follows the generated artifact's route, load/select that snapshot, and keep the old cross-route regression fixed.

- [ ] **Step 9: Implement Source Ref -> Graph navigation**

For `kind=node`, locate/select the matching graph node.

For `kind=answer`, find the answer in `graphView.answers`, select its `nodeId`, and make its `routeId` the preferred detail context without changing Active. If an answer ref cannot be resolved, leave the source ref visible but do not guess.

- [ ] **Step 10: Run workspace tests**

```bash
npm run test:unit -- src/components/workspace/__tests__ src/views/__tests__/WorkspaceView.spec.ts
```

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add frontend/src/components/workspace frontend/src/views/WorkspaceView.vue frontend/src/views/__tests__ frontend/src/components/RequirementStatePanel.vue frontend/src/components/SpecSnapshotList.vue frontend/src/components/SpecSnapshotPanel.vue frontend/src/components/RouteActionMenu.vue frontend/src/style.css
git commit -m "feat: make graph the primary workspace"
```

---

### Task 8: Complete Chinese product-shell localization and remove obsolete Phase 7.2 workspace panels

**Files:**
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/views/ProjectsView.vue`
- Modify: `frontend/src/components/ProjectCreateForm.vue`
- Modify: `frontend/src/components/ApiErrorBanner.vue`
- Modify: `frontend/src/components/ConfirmRouteActionDialog.vue`
- Modify: `frontend/src/components/ForkRouteDialog.vue`
- Modify: `frontend/src/components/RegenerateNodeDialog.vue`
- Modify all remaining product-shell components with visible English copy.
- Delete when unused: `frontend/src/components/ClarificationPanel.vue`
- Delete when unused: `frontend/src/components/HistoricalNodePanel.vue`
- Delete when unused: `frontend/src/components/RouteLineage.vue`
- Delete when unused: `frontend/src/components/RouteWorkspacePanel.vue`
- Delete when unused: `frontend/src/components/WorkspaceRightPanel.vue`
- Update/delete their tests accordingly.

**Interfaces:**
- No API or Runtime contract changes.

- [ ] **Step 1: Add localization regression tests for key screens**

Assert visible shell copy includes:

```text
项目
创建项目
工作区
路线
当前路线
开放
已替代
已归档
已删除
需求状态
规格快照
从此分支
重新生成这个问题
恢复
归档
删除路线
重试
```

- [ ] **Step 2: Add verbatim-content regression test**

Feed backend content:

```text
Question: What outcome matters most?
Purpose: Clarify the primary goal.
Option: Product team
Answer: Keep this exact user answer.
Spec content: Original English spec body.
```

Assert these exact strings remain unchanged while surrounding labels are Chinese.

- [ ] **Step 3: Translate shell copy directly**

Do not add `vue-i18n`. Keep machine error codes unchanged; only map/display Chinese explanatory copy.

Translate pending feedback as well, for example:

```text
Question drafted. -> 问题已起草。
Answer recorded. -> 回答已记录。
Route activated. -> 已设为当前路线。
Fork created. -> 已创建新分支路线。
Question regenerated. -> 已创建替代问题路线。
Spec snapshot generated. -> 已生成规格快照。
```

- [ ] **Step 4: Remove old workspace-only components once zero imports remain**

Verify first:

```bash
grep -R "ClarificationPanel\|HistoricalNodePanel\|RouteLineage\|RouteWorkspacePanel\|WorkspaceRightPanel" frontend/src --exclude-dir=node_modules
```

Expected before deletion: only the files themselves/tests. Delete them and obsolete tests only after the new graph workspace covers their behavior.

- [ ] **Step 5: Run all frontend unit tests and typecheck**

```bash
npm run test:unit -- --run
npm run typecheck
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A frontend/src
git commit -m "feat: localize graph workspace in Chinese"
```

---

### Task 9: Rework Playwright coverage around real Graph Workspace behavior

**Files:**
- Modify: `frontend/e2e/helpers.ts`
- Modify: `frontend/e2e/core-clarification.spec.ts`
- Modify: `frontend/e2e/fork.spec.ts`
- Modify: `frontend/e2e/lifecycle.spec.ts`
- Modify: `frontend/e2e/regenerate.spec.ts`
- Modify: `frontend/e2e/spec.spec.ts`
- Create: `frontend/e2e/graph-layout.spec.ts`
- Create: `frontend/e2e/graph-routes.spec.ts`

**Interfaces:**
- Uses real local backend with established fake gateway, not mocked browser API responses.

- [ ] **Step 1: Add stable Graph E2E selectors**

Use stable attributes such as:

```text
data-test="graph-canvas"
data-test="graph-start-placeholder"
data-test="graph-node-<nodeId>"
data-test="node-drag-handle"
data-test="route-<routeId>"
data-test="route-focus"
data-test="route-dim"
data-test="route-hide"
data-test="left-sidebar"
data-test="right-sidebar"
```

Do not select by generated question text when a deterministic data selector exists.

- [ ] **Step 2: E2E 1 — new project start and first real node**

Flow:

```text
create project
central 开始需求澄清 placeholder visible
click 起草第一个问题
placeholder disappears
one real root current node appears
```

- [ ] **Step 3: E2E 2 — direct Node answer and next-node placement**

Use free text under fake gateway:

```text
fill answer inside current graph node
submit
same node becomes historical/已作答
new current node appears to its right
old node bounding box stays unchanged within a small pixel tolerance
```

- [ ] **Step 4: E2E 3 — drag persistence and live edge**

Drag a node by `node-drag-handle`, record final bounding box, reload page, assert node returns near same graph position. During drag, assert the connected SVG edge remains present and its path `d` attribute changes from the pre-drag value.

- [ ] **Step 5: E2E 4 — multi-select group movement**

Create at least two historical nodes. Ctrl/Cmd-select both (choose modifier based on browser platform), drag one selected title bar, assert both bounding boxes move by approximately the same delta.

- [ ] **Step 6: E2E 5 — Fork from historical shared node**

Create a lineage with an answered historical node, Fork from it, then assert:

```text
new route becomes 当前路线
shared node count does not duplicate
old route answer remains visible on shared node
new active route shows 等待回答 for its own route answer
```

If selecting a base route different from Active, assert Fork is blocked with `先设为当前路线` until explicitly activated.

- [ ] **Step 7: E2E 6 — deterministic Regenerate**

Regenerate a non-root historical/current-eligible node using explicit replacement question/options. Assert:

```text
old route remains visible and becomes 已替代
replacement route becomes 当前路线
old node/history remains
replacement node appears
replacement relation is visually distinguishable from normal lineage
```

- [ ] **Step 8: E2E 7 — Focus/Dim/Hide/Show All + lifecycle**

Create at least two routes and verify:

```text
Focus leaves Active indicator unchanged
Dim lowers CSS/data visual state but leaves route clickable
Hide removes route-exclusive node(s) only
shared nodes remain
Active route hide action is unavailable/rejected
显示全部路线 restores manually hidden/dimmed routes
archive/restore/delete still call Runtime lifecycle and change canonical status
```

- [ ] **Step 9: E2E 8 — sidebars + reading/work context + spec**

Set Active=A and Focus=B. Assert:

```text
Requirement State says B
Spec history says B
current graph node remains on A
Generate Spec warning says it targets A
generate succeeds on A
reading context follows returned A snapshot after success
```

Also resize/collapse both sidebars, reload, and assert widths/open states persist.

- [ ] **Step 10: Run Playwright**

With backend running in fake-gateway mode:

```bash
npm run test:e2e
```

Expected: all graph E2Es PASS.

- [ ] **Step 11: Commit**

```bash
git add frontend/e2e
git commit -m "test: cover graph workspace end to end"
```

---

### Task 10: Phase 7.3 closure verification and documentation

**Files:**
- Create: `docs/PHASE_7_3_EXIT_CRITERIA.md`
- Modify: `README.md` only if it currently describes the old three-panel workspace as the current V1 UI.

**Interfaces:**
- Produces the evidence needed to declare Phase 7.3 closed.

- [ ] **Step 1: Write exit criteria from the approved design, with evidence slots filled by actual results**

The document must list and mark PASS only after verification for:

```text
Graph-first workspace
all routes on one graph
shared nodes deduplicated
route-specific answers
current node direct answer
historical answer summary/expand
multi-select/group move
header-only dragging
edge follows movement
local position persistence
new node does not move existing nodes
explicit auto-layout
focus/dim/hide/show all
active route protection
Fork/Regenerate old history retention
resizable/collapsible sidebars
reading route requirement state/spec
Active-only Runtime commands
Chinese product shell
verbatim AI/user/spec content
backend architecture/full tests
frontend unit/typecheck/build
Playwright
origin/main == HEAD
```

Do not write PASS before commands actually pass.

- [ ] **Step 2: Run full frontend verification**

```bash
cd frontend
npm run test:unit -- --run
npm run typecheck
npm run build
npm run test:e2e
```

Expected: every command exit 0.

- [ ] **Step 3: Run full backend verification from the same final tree**

```bash
cd ../backend
./gradlew test
```

Expected: exit 0, zero failures. Established live-provider skips are acceptable under fake-gateway verification.

- [ ] **Step 4: Inspect dependencies and forbidden changes**

```bash
cd ..
git diff --stat ca30d1d6fc2e576a1cbf140f3e0b9025acc2d99f..HEAD
```

Confirm frontend graph dependencies contain only the approved Vue Flow addition and no new provider/AI/runtime frameworks.

Confirm no code implements:

```text
manual graph rewiring
node deletion
backend layout persistence
AI translation/language switching
AI random regenerate
Runtime route/context semantic rewrite
```

- [ ] **Step 5: Commit closure docs**

```bash
git add docs/PHASE_7_3_EXIT_CRITERIA.md README.md
git commit -m "docs: close phase 7.3 graph workspace"
```

If README did not need changes, omit it from `git add`.

- [ ] **Step 6: Push and verify remote equality**

```bash
git status --short
git push origin main
git fetch origin
```

Then verify:

```bash
git rev-parse HEAD
git rev-parse origin/main
```

The two printed SHAs must be identical and `git status --short` must be empty.

## Phase 7.3B Completion Report

The implementing agent must report actual evidence in this shape:

```text
PHASE 7.3B

HEAD: output of git rev-parse HEAD
origin/main: output of git rev-parse origin/main
remote_equal: true only if the two outputs are identical
working_tree_clean: true only if git status --short is empty

Frontend:
- unit tests: PASS with actual file/test counts
- typecheck: PASS
- build: PASS
- Playwright: PASS with actual test count

Backend:
- ./gradlew test: PASS with actual test count / skipped count

UX invariants:
- Graph-first workspace: PASS
- Shared nodes deduplicated: PASS
- Route-specific answers: PASS
- Direct current-node answer: PASS
- Multi-select/group drag: PASS
- Edge follows nodes: PASS
- Position persistence: PASS
- Focus/Dim/Hide: PASS
- Active route protection: PASS
- Fork/Regenerate history preserved: PASS
- Resizable sidebars: PASS
- readingRouteId semantics: PASS
- Chinese shell/verbatim content: PASS

Forbidden scope check:
- Runtime route/context semantics changed: NO
- model/provider behavior changed: NO
- graph layout persisted to backend: NO
- arbitrary node/edge editing added: NO
```

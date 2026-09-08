# Spec Agent UI / UX First Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current floating-window-heavy workspace presentation with a calmer fixed Route / Graph / Inspector layout, reduce persistent controls, compact historical nodes, and simplify Route / Inspector hierarchy without changing product semantics or Agent Runtime behavior.

**Architecture:** Reuse the existing browser-only sidebar infrastructure (`ResizableSidebar`, `graphUiStore.left/rightSidebar*`) instead of inventing a new layout system. Keep `GraphCanvas`, `workspaceStore`, Graph projection, Focus/Active separation, answer submission, recovery, and AgentRun behavior intact; this slice changes presentation and information hierarchy only.

**Tech Stack:** Vue 3.5, TypeScript 5.8, Pinia 2.3, Vue Flow 1.48.2, Vitest 3.1, Vue Test Utils 2.4, Playwright 1.62, plain CSS.

**Spec:** `docs/superpowers/specs/2026-09-07-ui-ux-deepening-design.md`

## Global Constraints

- Base implementation work on branch `ui-ux-deepening-audit`, currently based on `main` commit `35039f80eea7f78a2c8d7a92833cff3d0c76400c`.
- Do not modify Agent Runtime, backend APIs, migrations, persistence semantics, or Python reasoning code.
- Do not add comments, research, notes, design modules, sharing, command palette, notification center, user-account features, agent personas, analytics, or any other capability that exists only in the concept image.
- Preserve: `Focus Route != Runtime Active Route`.
- Preserve: Shared Node is one canonical node and must not be duplicated for presentation.
- Preserve: current answer submission stays inside the Graph node in this slice.
- Preserve: input drafts survive remount / focus changes / generation states.
- Preserve: unknown mutation outcomes reconcile before retry; never auto-repeat an uncertain mutation.
- Preserve: workspace remains usable while AgentRun is active.
- Do not add frontend dependencies.
- User-visible UI copy remains Simplified Chinese.
- Desktop acceptance targets: 1366×768, 1440×900, 1920×1080.
- Use TDD for each behavior-changing presentation step; each task ends with a focused commit.

---

## File Structure Locked for This Slice

### Existing files to modify

- `frontend/src/views/WorkspaceView.vue` — compose fixed workspace regions and preserve store/event wiring.
- `frontend/src/views/__tests__/WorkspaceView.spec.ts` — shell wiring and contextual-AI sidebar behavior.
- `frontend/src/components/workspace/ResizableSidebar.vue` — reused as-is unless a focused test proves a small presentation defect.
- `frontend/src/components/workspace/RouteSidebar.vue` — default route navigation presentation.
- `frontend/src/components/workspace/__tests__/RouteSidebar.spec.ts` — route navigation / overflow behavior.
- `frontend/src/components/workspace/WorkspaceInspector.vue` — stable right-side inspector container.
- `frontend/src/components/workspace/NodeInspector.vue` — selected-node information hierarchy.
- `frontend/src/components/workspace/__tests__/WorkspaceInspector.spec.ts` — inspector routing / tabs.
- `frontend/src/components/workspace/__tests__/NodeInspector.spec.ts` — selected-node hierarchy and Agent proposal behavior.
- `frontend/src/components/graph/GraphCanvas.vue` — remove floating-window toolbar intents only; preserve graph behavior.
- `frontend/src/components/graph/GraphToolbar.vue` — compact visible toolbar + overflow.
- `frontend/src/components/graph/GraphQuestionNode.vue` — compact historical presentation, expanded current interaction.
- `frontend/src/components/graph/__tests__/GraphCanvas.spec.ts` — toolbar wiring compatibility.
- `frontend/src/components/graph/__tests__/GraphQuestionNode.spec.ts` — historical/current/pending presentation contracts.
- `frontend/src/style.css` — visual system and fixed-layout styling.
- `frontend/e2e/helpers.ts` — remove waits/comments that exist only for floating-window reflow while preserving Vue Flow settlement.
- `frontend/e2e/graph-routes.spec.ts` — migrate floating-route assumptions to fixed route sidebar.
- `frontend/e2e/shared-focus.spec.ts` — migrate route-panel selector while preserving Focus/Active assertions.
- `frontend/e2e/contextual-ai.spec.ts` — assert fixed Inspector instead of floating Inspector.
- `frontend/e2e/node-query-proposal.spec.ts` — assert fixed Inspector instead of floating Inspector.
- `frontend/e2e/connection.spec.ts` — stop using the old `open-inspector` floating-window command.

### New files

- `frontend/src/components/graph/__tests__/GraphToolbar.spec.ts` — focused toolbar hierarchy test.
- `frontend/e2e/workspace-layout.spec.ts` — fixed-layout desktop acceptance and Graph interaction coverage.

### Old presentation-specific E2E files to delete after replacement coverage is green

- `frontend/e2e/floating-workspace.spec.ts`
- `frontend/e2e/floating-obstacle-avoidance.spec.ts`
- `frontend/e2e/floating-save.spec.ts`

### Explicitly not deleted in this slice

- `frontend/src/components/workspace/FloatingWindow.vue`
- `frontend/src/components/workspace/RouteNavigator.vue`
- `frontend/src/components/workspace/__tests__/FloatingWindow.spec.ts`
- legacy floating preference fields in `graphUiStore` / `graphLayoutStorage`

Reason: first make the product layout stable and green. Removing legacy storage/schema code is a cleanup slice after the new presentation has shipped; mixing storage migration cleanup into the visual slice adds risk without user-visible value.

---

### Task 1: Replace Floating Workspace Composition with Fixed Sidebars

**Files:**
- Modify: `frontend/src/views/WorkspaceView.vue`
- Modify: `frontend/src/views/__tests__/WorkspaceView.spec.ts`
- Modify: `frontend/src/style.css`

**Interfaces:**
- Consumes: `graphUi.leftSidebarOpen`, `graphUi.leftSidebarWidth`, `graphUi.rightSidebarOpen`, `graphUi.rightSidebarWidth`, `graphUi.setLeftSidebar({ open, width })`, `graphUi.setRightSidebar({ open, width })`.
- Produces: fixed `[data-test="left-sidebar"]`, center Graph, fixed `[data-test="right-sidebar"]`; contextual AI selects the correct visual node and opens the right sidebar.

- [ ] **Step 1: Change the shell unit test to require the fixed three-region workspace**

Replace the old floating-shell assertion in `WorkspaceView.spec.ts` with:

```ts
it('loads the graph-first shell with fixed route and inspector sidebars', async () => {
  mockViews()
  const { wrapper } = await mountWorkspace()

  expect(wrapper.find('[data-test="left-sidebar"]').exists()).toBe(true)
  expect(wrapper.find('[data-test="route-sidebar"]').exists()).toBe(true)
  expect(wrapper.find('[data-test="graph-canvas-stub"]').exists()).toBe(true)
  expect(wrapper.find('[data-test="right-sidebar"]').exists()).toBe(true)
  expect(wrapper.find('[data-test="workspace-inspector"]').exists()).toBe(true)
  expect(wrapper.find('[data-test="floating-window-routes"]').exists()).toBe(false)
  expect(wrapper.find('[data-test="floating-window-inspector"]').exists()).toBe(false)
})
```

Change the existing contextual-AI shell test to:

```ts
it('selects the canonical target and opens the fixed inspector', async () => {
  mockViews()
  const { wrapper, graphUi } = await mountWorkspace()
  graphUi.setRightSidebar({ open: false, width: graphUi.rightSidebarWidth })

  await wrapper.findComponent(GraphCanvasStub).vm.$emit('contextual-ai', {
    canonicalNodeId: 'n2',
    visualNodeKey: 'n2',
  })

  expect(graphUi.primarySelectedNodeId).toBe('n2')
  expect(graphUi.rightSidebarOpen).toBe(true)
})
```

- [ ] **Step 2: Run the focused shell test and verify it fails**

```bash
cd frontend
npm test -- src/views/__tests__/WorkspaceView.spec.ts
```

Expected: FAIL because `WorkspaceView` still renders `FloatingWindow` / `RouteNavigator` and contextual AI still opens the floating Inspector.

- [ ] **Step 3: Replace floating imports and layout logic with the existing sidebar components**

In `WorkspaceView.vue`, use:

```ts
import ResizableSidebar from '@/components/workspace/ResizableSidebar.vue'
import RouteSidebar from '@/components/workspace/RouteSidebar.vue'
import WorkspaceInspector from '@/components/workspace/WorkspaceInspector.vue'
```

Delete imports and functions used only by floating-window geometry:

```ts
// Delete FloatingWindow and RouteNavigator imports.
// Delete FLOATING_WINDOW_RANGES.
// Delete computeAutoFloatingWindowLayout / FloatingRect.
// Delete resolveSafeFitRegion / FitViewportRegion.
// Delete floatingLayoutFrame, floatingLayoutSettledTimer, workspaceResizeObserver.
// Delete safeFitRegion, graphObstacles, floatingRect, changedGeometry,
// reflowFloatingWindows, scheduleFloatingLayout, and openWindow.
```

After those removals, `onMounted` becomes the normal project/load path only:

```ts
onMounted(() => {
  graphUi.initProject(props.projectId)
  void store.loadWorkspace(props.projectId).then(() => {
    void store.refreshUndoRedoAvailability()
  })
})
```

Remove the floating-layout `onBeforeUnmount` block if it has no remaining responsibility.

Contextual AI becomes:

```ts
function handleContextualAi(target: ContextualAiTarget): void {
  const visualNodeKey = resolveContextualAiTarget(target)
  if (!visualNodeKey) return
  graphUi.selectNode(visualNodeKey)
  graphUi.setRightSidebar({ open: true, width: graphUi.rightSidebarWidth })
}
```

- [ ] **Step 4: Replace the template composition with fixed left / center / right regions**

Use:

```vue
<div v-else class="workspace-shell__body workspace-shell__body--fixed">
  <ResizableSidebar
    side="left"
    :open="graphUi.leftSidebarOpen"
    :width="graphUi.leftSidebarWidth"
    :min-width="220"
    :max-width="420"
    @update:open="graphUi.setLeftSidebar({ open: $event, width: graphUi.leftSidebarWidth })"
    @update:width="graphUi.setLeftSidebar({ open: graphUi.leftSidebarOpen, width: $event })"
  >
    <RouteSidebar
      :routes="store.graphView?.routes ?? []"
      :active-route-id="store.activeRoute?.id ?? null"
      :command-pending="store.routeCommandPending"
      :pending-route-command="store.pendingRouteCommand"
      @locate-route="handleLocateRoute"
      @activate="store.activateRoute($event)"
      @restore="store.restoreRoute($event)"
      @archive="openConfirm('archive', $event)"
      @delete="openConfirm('delete', $event)"
    />
  </ResizableSidebar>

  <GraphCanvas
    ref="canvasRef"
    class="workspace-shell__canvas"
    :view="store.graphView"
    :active-node-id="store.activeState?.activeNode?.id ?? null"
    :submitting="store.submitting"
    :drafting="store.drafting"
    :pending="store.routeCommandPending"
    :runtime-node-id="store.pendingAnswerNodeId"
    :runtime-status="store.answerRunStatus"
    :runtime-phase="store.answerRunPhase"
    :pending-projection="store.pendingRouteProjection"
    @draft="handleDraft"
    @submit-answer="handleAnswer"
    @fork="handleFork"
    @reanswer="handleReanswer"
    @regenerate="handleRegenerate"
    @activate-route="handleActivateRouteForAnswer"
    @contextual-ai="handleContextualAi"
    @retry-pending="store.retryPendingAgentRun"
    @add-idea="handleAddIdea"
    @add-resource="resourceDialogOpen = true"
    @relation-proposal="handleRelationProposal"
    @undo="store.undoGraph"
    @redo="store.redoGraph"
  />

  <ResizableSidebar
    side="right"
    :open="graphUi.rightSidebarOpen"
    :width="graphUi.rightSidebarWidth"
    :min-width="300"
    :max-width="600"
    @update:open="graphUi.setRightSidebar({ open: $event, width: graphUi.rightSidebarWidth })"
    @update:width="graphUi.setRightSidebar({ open: graphUi.rightSidebarOpen, width: $event })"
  >
    <WorkspaceInspector
      :node-data="selectedNodeData"
      :selected-edge="selectedEdgeData"
      @fork="handleFork"
      @reanswer="handleReanswer"
      @regenerate="handleRegenerate"
    />
  </ResizableSidebar>
</div>
```

Do not pass `safe-region`; fixed sidebars reserve actual layout width instead of overlaying the canvas.

- [ ] **Step 5: Add fixed-shell CSS without polishing component details yet**

```css
.workspace-shell__body--fixed {
  display: flex;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.workspace-shell__body--fixed > .workspace-shell__canvas {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
}

.workspace-shell__body--fixed > .resizable-sidebar {
  flex: 0 0 auto;
  height: 100%;
  border-radius: 0;
}
```

- [ ] **Step 6: Run the shell test again**

```bash
cd frontend
npm test -- src/views/__tests__/WorkspaceView.spec.ts
```

Expected: PASS.

- [ ] **Step 7: Commit the fixed workspace shell**

```bash
git add frontend/src/views/WorkspaceView.vue frontend/src/views/__tests__/WorkspaceView.spec.ts frontend/src/style.css
git commit -m "refactor(ui): replace floating workspace with fixed sidebars"
```

---

### Task 2: Reduce the Graph Toolbar to Frequent Controls + Overflow

**Files:**
- Create: `frontend/src/components/graph/__tests__/GraphToolbar.spec.ts`
- Modify: `frontend/src/components/graph/GraphToolbar.vue`
- Modify: `frontend/src/components/graph/GraphCanvas.vue`
- Modify: `frontend/src/components/graph/__tests__/GraphCanvas.spec.ts`
- Modify: `frontend/src/style.css`

**Interfaces:**
- Produces visible primary actions: add idea, undo, redo, zoom out, zoom in, fit view.
- Produces overflow actions: add resource, auto layout, show all routes.
- Removes presentation-only emits: `routes`, `inspector`, `reset-windows`.

- [ ] **Step 1: Add a focused unit test for visible vs overflow controls**

Create `GraphToolbar.spec.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import GraphToolbar from '@/components/graph/GraphToolbar.vue'

describe('GraphToolbar', () => {
  it('keeps frequent controls visible and low-frequency controls in overflow', () => {
    const wrapper = mount(GraphToolbar, {
      global: { plugins: [createPinia()] },
    })

    expect(wrapper.get('[data-test="add-idea"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="zoom-in"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="zoom-out"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="fit-view"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="toolbar-more"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="toolbar-more"]').attributes('open')).toBeUndefined()

    expect(wrapper.find('[data-test="open-routes"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="open-inspector"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="reset-windows"]').exists()).toBe(false)
  })
})
```

- [ ] **Step 2: Run the toolbar test and verify it fails**

```bash
cd frontend
npm test -- src/components/graph/__tests__/GraphToolbar.spec.ts
```

Expected: FAIL because the current toolbar still renders the three floating-window commands and has no `toolbar-more` control.

- [ ] **Step 3: Rewrite the toolbar hierarchy without adding dependencies**

Use native `<details>` for overflow:

```vue
<div class="graph-toolbar" data-test="graph-toolbar" data-layout-role="toolbar">
  <button class="btn graph-toolbar__btn graph-toolbar__btn--primary" data-test="add-idea" @click="$emit('add-idea')">+ 想法</button>

  <div class="graph-toolbar__group" aria-label="撤销与重做">
    <button class="btn graph-toolbar__icon-btn" data-test="undo" aria-label="撤销" :disabled="!workspace.undoRedo.canUndo || workspace.graphCommandPending" @click="$emit('undo')">↶</button>
    <button class="btn graph-toolbar__icon-btn" data-test="redo" aria-label="重做" :disabled="!workspace.undoRedo.canRedo || workspace.graphCommandPending" @click="$emit('redo')">↷</button>
  </div>

  <div class="graph-toolbar__group" aria-label="视图缩放">
    <button class="btn graph-toolbar__icon-btn" data-test="zoom-out" aria-label="缩小" @click="$emit('zoom-out')">−</button>
    <button class="btn graph-toolbar__icon-btn" data-test="zoom-in" aria-label="放大" @click="$emit('zoom-in')">+</button>
    <button class="btn graph-toolbar__btn" data-test="fit-view" @click="$emit('fit-view')">适应</button>
  </div>

  <details class="graph-toolbar__more" data-test="toolbar-more">
    <summary class="btn graph-toolbar__icon-btn" aria-label="更多图操作">···</summary>
    <div class="graph-toolbar__menu">
      <button class="graph-toolbar__menu-item" data-test="add-resource" @click="$emit('add-resource')">添加资源</button>
      <button class="graph-toolbar__menu-item" data-test="auto-layout" @click="$emit('auto-layout')">重新自动布局</button>
      <button class="graph-toolbar__menu-item" data-test="show-all" @click="$emit('show-all')">显示全部路线</button>
    </div>
  </details>
</div>
```

Remove these emits from `GraphToolbar.vue` and `GraphCanvas.vue`:

```ts
routes: []
inspector: []
'reset-windows': []
```

Remove their toolbar listeners from `GraphCanvas.vue`.

- [ ] **Step 4: Update `GraphCanvas.spec.ts` to match the smaller toolbar contract**

If the test's `GraphToolbar` stub declares emits, the list must become:

```ts
emits: [
  'zoom-in', 'zoom-out', 'fit-view', 'auto-layout', 'show-all',
  'add-idea', 'add-resource', 'undo', 'redo',
]
```

Delete assertions that expect `routes`, `inspector`, or `reset-windows` to bubble out of `GraphCanvas`.

- [ ] **Step 5: Run toolbar + canvas unit tests**

```bash
cd frontend
npm test -- src/components/graph/__tests__/GraphToolbar.spec.ts src/components/graph/__tests__/GraphCanvas.spec.ts
```

Expected: PASS.

- [ ] **Step 6: Commit toolbar simplification**

```bash
git add frontend/src/components/graph/GraphToolbar.vue frontend/src/components/graph/GraphCanvas.vue frontend/src/components/graph/__tests__/GraphToolbar.spec.ts frontend/src/components/graph/__tests__/GraphCanvas.spec.ts frontend/src/style.css
git commit -m "refactor(ui): simplify graph toolbar"
```

---

### Task 3: Compact Historical Question Nodes Without Changing Current Answer Interaction

**Files:**
- Modify: `frontend/src/components/graph/GraphQuestionNode.vue`
- Modify: `frontend/src/components/graph/__tests__/GraphQuestionNode.spec.ts`
- Modify: `frontend/src/style.css`

**Interfaces:**
- Current answerable node continues to render options / free-text / submit.
- Pending projection continues to render runtime product copy and retry on failure.
- Historical node becomes navigation-first: Q label, Latest/Shared indicators, compact question, one state, contextual actions on hover/focus.
- Full answer remains readable in Inspector.

- [ ] **Step 1: Add tests using the existing `mountNode`, `currentData`, and `historicalData` helpers**

Add to `GraphQuestionNode.spec.ts`:

```ts
it('renders historical answered nodes as compact navigation cards', () => {
  const wrapper = mountNode(historicalData({
    isLatest: true,
    qLabel: 'Q3',
  }))

  expect(wrapper.get('[data-test="historical-question"]').exists()).toBe(true)
  expect(wrapper.get('[data-test="node-state"]').text()).toContain('已确认')
  expect(wrapper.find('[data-test="answer-summary"]').exists()).toBe(false)
  expect(wrapper.find('[data-test="free-text"]').exists()).toBe(false)
  expect(wrapper.find('[data-test="submit-answer"]').exists()).toBe(false)
})

it('keeps the current answerable node expanded', () => {
  const wrapper = mountNode(currentData({ qLabel: 'Q4' }))

  expect(wrapper.get('[data-test="question"]').exists()).toBe(true)
  expect(wrapper.get('[data-test="free-text"]').exists()).toBe(true)
  expect(wrapper.get('[data-test="submit-answer"]').exists()).toBe(true)
  expect(wrapper.get('[data-test="node-state"]').text()).toContain('就绪')
})
```

- [ ] **Step 2: Run the node test and verify the historical assertion fails**

```bash
cd frontend
npm test -- src/components/graph/__tests__/GraphQuestionNode.spec.ts
```

Expected: FAIL because the historical node currently renders `answer-summary` and has no single `node-state` presentation.

- [ ] **Step 3: Add one product-facing node-state mapping**

In `GraphQuestionNode.vue`:

```ts
const presentationState = computed(() => {
  if (props.data.runtimeStatus === 'FAILED') return { label: '需处理', className: 'badge-danger' }
  if (props.data.runtimeStatus === 'RUNNING') return { label: '生成中', className: 'badge-open' }
  if (isPendingCard.value || props.data.runtimeStatus === 'PENDING') return { label: '待处理', className: 'badge-warn' }
  if (props.data.canAnswer) return { label: '就绪', className: 'badge-ready' }
  if (props.data.primaryAnswer) return { label: '已确认', className: 'badge-confirmed' }
  return { label: '待处理', className: 'badge-neutral' }
})
```

Render exactly one state marker:

```vue
<span
  class="badge graph-question-node__state"
  :class="presentationState.className"
  data-test="node-state"
>
  {{ presentationState.label }}
</span>
```

Do not add a badge for each internal Runtime phase. Continue using `phaseToCopy()` inside generating/pending content where explanatory copy is needed.

- [ ] **Step 4: Replace historical answer preview with compact question presentation**

The historical branch becomes:

```vue
<template v-else>
  <h4 class="graph-node-question graph-node-question--compact" data-test="historical-question">
    {{ node.question }}
  </h4>
  <p v-if="node.purpose" class="graph-node-purpose graph-node-purpose--compact">
    {{ node.purpose }}
  </p>
</template>
```

Keep the existing hover/focus action rail for Fork / Reanswer / Regenerate / Ask AI. Do not move those actions into the permanent card body.

- [ ] **Step 5: Add compact node CSS**

```css
.graph-question-node--historical {
  width: 220px;
}

.graph-question-node--historical .graph-question-node__body {
  padding: 12px 14px 14px;
}

.graph-node-question--compact {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  font-size: 13px;
  line-height: 1.45;
}

.graph-node-purpose--compact {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 1;
  line-clamp: 1;
}
```

- [ ] **Step 6: Run node tests**

```bash
cd frontend
npm test -- src/components/graph/__tests__/GraphQuestionNode.spec.ts
```

Expected: PASS, including the existing submit, shared-reading, action-rail, and input-draft behavior in the file.

- [ ] **Step 7: Commit compact node presentation**

```bash
git add frontend/src/components/graph/GraphQuestionNode.vue frontend/src/components/graph/__tests__/GraphQuestionNode.spec.ts frontend/src/style.css
git commit -m "refactor(ui): compact historical graph nodes"
```

---

### Task 4: Turn Route Sidebar from Management Console into Navigation

**Files:**
- Modify: `frontend/src/components/workspace/RouteSidebar.vue`
- Modify: `frontend/src/components/workspace/__tests__/RouteSidebar.spec.ts`
- Modify: `frontend/src/style.css`

**Interfaces:**
- Row click still sets browser Focus and emits `locate-route`.
- Runtime `activate / restore / archive / delete` events remain unchanged.
- Dim / hide / lifecycle filters remain available but are not permanent first-level controls.

- [ ] **Step 1: Add a test helper and quiet-default assertions**

Add this helper below `routeView()` in `RouteSidebar.spec.ts`:

```ts
function mountSidebar(
  routes: GraphWorkspaceRouteView[] = [
    routeView('r1', 'open', ['n1', 'n2']),
    routeView('r2', 'open', ['n1', 'n3']),
  ],
  activeRouteId = 'r1',
) {
  return mount(RouteSidebar, {
    props: { routes, activeRouteId, commandPending: false, pendingRouteCommand: null },
  })
}
```

Add:

```ts
it('renders route rows as navigation first and keeps management controls behind overflow', () => {
  const wrapper = mountSidebar()
  const route = wrapper.find('[data-route-id="r1"]')

  expect(route.get('[data-test="route-primary"]').exists()).toBe(true)
  expect(route.get('[data-test="route-more"]').exists()).toBe(true)
  expect(route.get('[data-test="route-more"]').attributes('open')).toBeUndefined()
})

it('clicking the route row focuses and locates without activating runtime', async () => {
  const wrapper = mountSidebar()
  await wrapper.find('[data-route-id="r2"] .route-card__label').trigger('click')

  expect(useGraphUiStore().focusRouteId).toBe('r2')
  expect(wrapper.emitted('locate-route')?.[0]).toEqual(['r2'])
  expect(wrapper.emitted('activate')).toBeUndefined()
})
```

- [ ] **Step 2: Run the route sidebar test and verify it fails**

```bash
cd frontend
npm test -- src/components/workspace/__tests__/RouteSidebar.spec.ts
```

Expected: FAIL because the current route details are always open and the primary row does not use the new quiet structure.

- [ ] **Step 3: Make each row show only primary route information**

Use:

```vue
<div class="route-card__primary" data-test="route-primary">
  <div class="route-card__identity">
    <strong class="route-card__label">{{ route.label ?? route.id.slice(0, 8) }}</strong>
    <span class="meta-text">{{ route.lineageNodeIds.length }} 个节点</span>
  </div>
  <div class="route-card__state">
    <span v-if="isFocused(route.id)" class="badge badge-focus">正在浏览</span>
    <span v-if="route.id === activeRouteId" class="badge badge-active">运行路线</span>
    <span v-else-if="route.lifecycleStatus !== 'open'" class="badge" :class="`badge-${route.lifecycleStatus}`">
      {{ lifecycleLabels[route.lifecycleStatus] }}
    </span>
  </div>
</div>
```

Open routes do not need a permanent `开放` badge.

- [ ] **Step 4: Put route actions behind a closed native overflow**

Use:

```vue
<details class="route-card__more" data-test="route-more" @click.stop>
  <summary class="route-card__more-trigger" aria-label="路线更多操作">···</summary>

  <div class="route-card__menu">
    <div class="route-card__group" data-test="view-actions-group">
      <button class="route-card__menu-item" data-test="locate-route" @click="emit('locate-route', route.id)">定位路线</button>
      <button class="route-card__menu-item" data-test="focus-route" @click="toggleFocus(route)">
        {{ isFocused(route.id) ? '取消浏览聚焦' : '浏览此路线' }}
      </button>
      <button class="route-card__menu-item" data-test="dim-route" @click="setDim(route.id, displayState(route.id) !== 'dimmed')">
        {{ displayState(route.id) === 'dimmed' ? '取消弱化' : '弱化路线' }}
      </button>
      <button class="route-card__menu-item" data-test="hide-route" :disabled="route.id === activeRouteId" @click="setHidden(route.id, displayState(route.id) !== 'hidden')">
        {{ displayState(route.id) === 'hidden' ? '恢复显示' : '隐藏路线' }}
      </button>
    </div>

    <div class="route-card__group" data-test="runtime-actions-group">
      <button v-if="route.lifecycleStatus === 'open' && !route.isActive" class="route-card__menu-item" data-test="activate-route" :disabled="commandPending" @click="emit('activate', route.id)">设为运行路线</button>
      <button v-if="route.lifecycleStatus !== 'open'" class="route-card__menu-item" data-test="restore-route" :disabled="commandPending" @click="emit('restore', route.id)">恢复路线</button>
      <button v-if="!isArchivedOrDeleted(route)" class="route-card__menu-item" data-test="archive-route" :disabled="commandPending" @click="emit('archive', route.id)">归档</button>
      <button v-if="route.lifecycleStatus !== 'deleted'" class="route-card__menu-item route-card__menu-item--danger" data-test="delete-route" :disabled="commandPending" @click="emit('delete', route.id)">删除路线</button>
    </div>
  </div>
</details>
```

Move lifecycle filters into one separate closed `<details data-test="route-filters">` after the route list; keep the existing checkbox/store behavior unchanged.

- [ ] **Step 5: Update existing RouteSidebar tests to open overflow before triggering hidden actions**

For tests that click a route's action, open its details first:

```ts
const route = wrapper.find('[data-route-id="r2"]')
await route.get('[data-test="route-more"]').setValue(true)
await route.get('[data-test="focus-route"]').trigger('click')
```

If Vue Test Utils does not toggle `<details>` through `setValue`, use:

```ts
route.get('[data-test="route-more"]').element.setAttribute('open', '')
```

For lifecycle-filter tests, open the shared filter details before setting a checkbox:

```ts
wrapper.get('[data-test="route-filters"]').element.setAttribute('open', '')
await wrapper.get('[data-test="filter-archived"]').setValue(false)
```

The assertions about Focus, Active-route hide protection, and emitted runtime route IDs remain unchanged.

- [ ] **Step 6: Run route sidebar tests**

```bash
cd frontend
npm test -- src/components/workspace/__tests__/RouteSidebar.spec.ts
```

Expected: PASS.

- [ ] **Step 7: Commit route navigation simplification**

```bash
git add frontend/src/components/workspace/RouteSidebar.vue frontend/src/components/workspace/__tests__/RouteSidebar.spec.ts frontend/src/style.css
git commit -m "refactor(ui): simplify route navigation"
```

---

### Task 5: Reorder Inspector Around Question, Answer, Evidence, Agent Action

**Files:**
- Modify: `frontend/src/components/workspace/NodeInspector.vue`
- Modify: `frontend/src/components/workspace/WorkspaceInspector.vue`
- Modify: `frontend/src/components/workspace/__tests__/NodeInspector.spec.ts`
- Modify: `frontend/src/components/workspace/__tests__/WorkspaceInspector.spec.ts`
- Modify: `frontend/src/style.css`

**Interfaces:**
- Preserve contextual AI query API and proposal accept/reject behavior.
- Preserve route reading context and relation rendering.
- Preserve existing Requirement State / Spec tabs for this first slice; moving Spec into its own workspace region is a later slice.
- Reduce first-screen metadata; move timestamps, author, branch provenance, and relations into secondary details.

- [ ] **Step 1: Add order-focused tests using the existing `nodeData()` helper**

Add to `NodeInspector.spec.ts`:

```ts
it('prioritizes content, answer, and agent action before secondary metadata', () => {
  const wrapper = mount(NodeInspector, {
    props: {
      data: nodeData({
        qLabel: 'Q3',
        isLatest: true,
        primaryAnswer: {
          routeId: 'rA',
          selectedOptionId: null,
          selectedOptionLabel: null,
          freeText: '已确认的回答',
          isPrimary: true,
        },
      }),
    },
  })

  const sections = wrapper.findAll('[data-test^="inspector-section-"]')
    .map((section) => section.attributes('data-test'))

  expect(sections.slice(0, 3)).toEqual([
    'inspector-section-content',
    'inspector-section-answer',
    'inspector-section-agent',
  ])
  expect(wrapper.get('[data-test="inspector-secondary"]').attributes('open')).toBeUndefined()
  expect(wrapper.find('[data-test="agent-proposal"]').exists()).toBe(false)
})
```

Extend the existing awaiting-approval test with:

```ts
expect(wrapper.find('[data-test="agent-proposal"]').exists()).toBe(true)
```

- [ ] **Step 2: Run inspector unit tests and verify the new order assertion fails**

```bash
cd frontend
npm test -- src/components/workspace/__tests__/NodeInspector.spec.ts src/components/workspace/__tests__/WorkspaceInspector.spec.ts
```

Expected: FAIL because the current Inspector leads with technical metadata / Ask AI and has no grouped secondary section.

- [ ] **Step 3: Introduce a concise selected-node header**

At the top of `NodeInspector.vue`:

```vue
<header class="node-inspector__header">
  <div class="node-inspector__identity">
    <span v-if="data.qLabel" class="node-inspector__q">{{ data.qLabel }}</span>
    <span v-if="data.isLatest" class="badge badge-latest">最新</span>
    <span v-if="data.isShared" class="badge badge-neutral">共享</span>
  </div>
  <p class="node-inspector__route-context">
    当前查看：<strong>{{ data.readingRouteId ? membershipLabel(data, data.readingRouteId) : '未选择路线' }}</strong>
  </p>
</header>
```

Do not show raw node IDs.

- [ ] **Step 4: Reorder primary sections**

Use this order:

```vue
<section class="node-inspector__section" data-test="inspector-section-content">
  <h4>问题</h4>
  <p class="node-inspector__question">{{ nodeQuestion || contentText || '（空草稿）' }}</p>
  <p v-if="data.node.purpose" class="meta-text">{{ data.node.purpose }}</p>
</section>

<section v-if="data.node.kind === 'INTERACTION'" class="node-inspector__section" data-test="inspector-section-answer">
  <h4>回答</h4>
  <div v-if="data.primaryAnswer" class="node-inspector__answer">
    <span v-if="data.primaryAnswer.selectedOptionLabel" class="badge badge-open">{{ data.primaryAnswer.selectedOptionLabel }}</span>
    <p v-if="data.primaryAnswer.freeText" class="graph-answer-text">{{ data.primaryAnswer.freeText }}</p>
  </div>
  <p v-else class="muted">该节点还没有回答。</p>
</section>

<section class="node-inspector__section" data-test="inspector-section-agent">
  <h4>问 AI</h4>
  <!-- Keep the existing ask textarea, button, status, and proposal logic here. -->
</section>
```

For the existing awaiting-approval branch, wrap the current proposal content with:

```vue
<div class="node-inspector__proposal" data-test="agent-proposal">
  <p class="graph-answer-text">{{ queryResult.message || 'AI 提出了一个候选动作，等待你确认。' }}</p>
  <p class="meta-text">候选动作：<strong>{{ queryResult.actionFamily || '未知' }}</strong></p>
  <div class="node-inspector__proposal-actions">
    <button class="btn btn-small btn-primary" data-test="accept-proposal" :disabled="acceptingProposal || !queryResult.proposalId" @click="acceptProposal">接受</button>
    <button class="btn btn-small" data-test="reject-proposal" :disabled="rejectingProposal || !queryResult.proposalId" @click="rejectProposal">拒绝</button>
  </div>
</div>
```

- [ ] **Step 5: Move technical/provenance details into a closed secondary section**

```vue
<details class="node-inspector__secondary" data-test="inspector-secondary">
  <summary>更多详情</summary>
  <div class="node-inspector__secondary-body">
    <p class="meta-text">{{ kindLabel }} · 创建于 {{ formatTime(data.node.createdAt) }}</p>

    <h4 class="node-inspector__heading">路线归属</h4>
    <p class="meta-text">
      {{ data.routeIds.length }} 条路线
      <template v-if="data.isShared">（共享节点）</template>
    </p>

    <!-- Move the existing branch provenance block here unchanged. -->
    <!-- Move the existing semantic-relations block here unchanged. -->
  </div>
</details>
```

Historical Fork / Reanswer / Regenerate controls remain available below secondary details as low-frequency contextual actions.

- [ ] **Step 6: Keep `WorkspaceInspector` tab semantics stable while reducing chrome**

Keep:

```ts
type InspectorTab = 'details' | 'requirement' | 'spec'
```

Keep selected node -> `details`, no selection -> `requirement`. Do not move Spec in this task.

- [ ] **Step 7: Run inspector tests**

```bash
cd frontend
npm test -- src/components/workspace/__tests__/NodeInspector.spec.ts src/components/workspace/__tests__/WorkspaceInspector.spec.ts
```

Expected: PASS, including proposal accept/reject behavior.

- [ ] **Step 8: Commit inspector hierarchy**

```bash
git add frontend/src/components/workspace/NodeInspector.vue frontend/src/components/workspace/WorkspaceInspector.vue frontend/src/components/workspace/__tests__/NodeInspector.spec.ts frontend/src/components/workspace/__tests__/WorkspaceInspector.spec.ts frontend/src/style.css
git commit -m "refactor(ui): clarify inspector hierarchy"
```

---

### Task 6: Apply the Low-Noise Visual System to the New Layout

**Files:**
- Modify: `frontend/src/style.css`
- Modify: `frontend/src/App.vue` only if workspace header spacing needs a structural class; do not add controls.

**Interfaces:**
- Produces consistent tokens for surfaces, typography, selection, lifecycle status, focus ring, and sidebars.
- No behavior changes.

- [ ] **Step 1: Update root presentation tokens**

```css
:root {
  --color-bg: #f7f8fa;
  --color-surface: #ffffff;
  --color-surface-subtle: #fafbfc;
  --color-border: #e4e7ec;
  --color-border-strong: #d0d5dd;
  --color-text: #17202f;
  --color-text-secondary: #667085;
  --color-text-muted: #98a2b3;
  --color-accent: #3b6ff5;
  --color-accent-soft: #eef3ff;
  --color-success: #168456;
  --color-success-soft: #eaf8f1;
  --color-warn: #9a6700;
  --color-warn-soft: #fff7df;
  --color-danger: #c43232;
  --color-danger-soft: #fff0f0;
  --color-subdued: #f2f4f7;
  --radius-sm: 6px;
  --radius: 10px;
  --radius-lg: 14px;
  --shadow-float: 0 8px 24px rgb(16 24 40 / 10%);
  --focus-ring: 0 0 0 3px rgb(59 111 245 / 18%);
  --space: 16px;
}
```

Use a Chinese-safe system font stack:

```css
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', Arial, sans-serif;
}
```

- [ ] **Step 2: Make Graph visually dominant and sidebars quiet**

```css
.workspace-shell__canvas {
  background: var(--color-bg);
}

.resizable-sidebar {
  background: var(--color-surface);
  border: 0;
}

.resizable-sidebar--left {
  border-right: 1px solid var(--color-border);
}

.resizable-sidebar--right {
  border-left: 1px solid var(--color-border);
}
```

Normal historical nodes use subtle border/no heavy shadow; selected/current node uses accent outline and the stronger focus treatment.

- [ ] **Step 3: Standardize the small lifecycle set**

```css
.badge-ready,
.badge-confirmed {
  background: var(--color-success-soft);
  color: var(--color-success);
}

.badge-neutral {
  background: var(--color-subdued);
  color: var(--color-text-secondary);
}

.badge-latest {
  background: var(--color-accent-soft);
  color: var(--color-accent);
}
```

- [ ] **Step 4: Add keyboard-visible focus states for hover/contextual actions**

```css
button:focus-visible,
summary:focus-visible,
select:focus-visible,
textarea:focus-visible,
input:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.graph-question-node:focus-within .graph-node-actions--toolbar,
.graph-question-node--selected .graph-node-actions--toolbar,
.graph-question-node:hover .graph-node-actions--toolbar {
  opacity: 1;
  pointer-events: auto;
}
```

- [ ] **Step 5: Run unit tests and typecheck**

```bash
cd frontend
npm test
npm run typecheck
```

Expected: all unit tests PASS; typecheck exits 0.

- [ ] **Step 6: Commit visual-system pass**

```bash
git add frontend/src/style.css frontend/src/App.vue
git commit -m "style(ui): apply low-noise workspace visual system"
```

If `App.vue` has no diff, only `style.css` will be included in the commit.

---

### Task 7: Replace Floating-Window E2E Contracts with Fixed-Layout Acceptance

**Files:**
- Create: `frontend/e2e/workspace-layout.spec.ts`
- Modify: `frontend/e2e/graph-routes.spec.ts`
- Modify: `frontend/e2e/shared-focus.spec.ts`
- Modify: `frontend/e2e/contextual-ai.spec.ts`
- Modify: `frontend/e2e/node-query-proposal.spec.ts`
- Modify: `frontend/e2e/connection.spec.ts`
- Modify: `frontend/e2e/helpers.ts`
- Delete: `frontend/e2e/floating-workspace.spec.ts`
- Delete: `frontend/e2e/floating-obstacle-avoidance.spec.ts`
- Delete: `frontend/e2e/floating-save.spec.ts`

**Interfaces:**
- Preserve behavior coverage for Graph interaction, Focus/Active separation, contextual AI, proposal approval, and relations.
- Replace old geometry-persistence acceptance with fixed sidebars that reserve layout space.

- [ ] **Step 1: Add fixed-layout acceptance for all three desktop target sizes before deleting old tests**

Create `workspace-layout.spec.ts`:

```ts
import { test, expect } from '@playwright/test'
import { buildThreeNodeLineage, createProject, fitGraph } from './helpers'

test.setTimeout(400000)

const viewports = [
  { width: 1366, height: 768 },
  { width: 1440, height: 900 },
  { width: 1920, height: 1080 },
]

for (const viewport of viewports) {
  test(`${viewport.width}x${viewport.height} keeps route, graph, and inspector non-overlapping`, async ({ page }) => {
    await page.setViewportSize(viewport)
    await createProject(page, `E2E Fixed Workspace ${viewport.width}`)
    await buildThreeNodeLineage(page)
    await fitGraph(page)

    const left = page.getByTestId('left-sidebar')
    const canvas = page.getByTestId('graph-canvas')
    const right = page.getByTestId('right-sidebar')

    await expect(left).toBeVisible()
    await expect(canvas).toBeVisible()
    await expect(right).toBeVisible()

    const [l, c, r] = await Promise.all([left.boundingBox(), canvas.boundingBox(), right.boundingBox()])
    expect(l).not.toBeNull()
    expect(c).not.toBeNull()
    expect(r).not.toBeNull()
    expect((l?.x ?? 0) + (l?.width ?? 0)).toBeLessThanOrEqual((c?.x ?? 0) + 1)
    expect((c?.x ?? 0) + (c?.width ?? 0)).toBeLessThanOrEqual((r?.x ?? 0) + 1)
  })
}

test('minimum desktop layout keeps the current question interactive', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 768 })
  await createProject(page, 'E2E Fixed Workspace Interaction')
  await buildThreeNodeLineage(page)
  await fitGraph(page)

  const current = page.locator('.graph-question-node--current')
  await expect(current).toBeVisible()
  const input = current.getByTestId('free-text')
  await input.click()
  await input.fill('fixed layout remains interactive')
  await expect(input).toHaveValue('fixed layout remains interactive')
})
```

- [ ] **Step 2: Run only the new layout spec**

```bash
cd frontend
npx playwright test e2e/workspace-layout.spec.ts
```

Expected after Tasks 1–6: PASS.

- [ ] **Step 3: Migrate E2E selectors that only opened or asserted floating panels**

Use these substitutions:

```ts
// old
page.getByTestId('floating-window-routes')
// new
page.getByTestId('left-sidebar')
```

```ts
// old
page.getByTestId('floating-window-inspector')
// new
page.getByTestId('right-sidebar')
```

For tests that clicked `open-inspector`, select the target node directly. In `connection.spec.ts`:

```ts
const sourceNode = page.locator(`[data-node-id="${sourceNodeId}"]`)
await sourceNode.click()
await expect(page.getByTestId('right-sidebar')).toBeVisible()
await expect(page.getByTestId('node-inspector')).toBeVisible()
```

For `contextual-ai.spec.ts` and `node-query-proposal.spec.ts`, keep clicking the node's `contextual-ai` action and assert:

```ts
await expect(page.getByTestId('right-sidebar')).toBeVisible()
await expect(page.getByTestId('node-detail-question')).toBeVisible()
```

- [ ] **Step 4: Update `graph-routes.spec.ts` to test route navigation rather than floating-window geometry**

Replace titlebar drag/resize assertions with:

```ts
const routes = page.getByTestId('route-sidebar')
await expect(routes).toBeVisible()
const firstRoute = routes.locator('[data-route-id]').first()
await firstRoute.click()
await expect(firstRoute).toHaveClass(/route-card--focused/)
```

Keep existing Focus-vs-Active runtime assertions.

- [ ] **Step 5: Remove helper waits/comments whose only purpose is floating-window reflow**

In `frontend/e2e/helpers.ts`, keep viewport-settlement waits required by Vue Flow. Remove only the extra double-RAF branch/comment that explicitly waits for floating-window reflow after the viewport has already settled.

- [ ] **Step 6: Delete the three old floating-presentation E2E specs**

```bash
git rm frontend/e2e/floating-workspace.spec.ts
git rm frontend/e2e/floating-obstacle-avoidance.spec.ts
git rm frontend/e2e/floating-save.spec.ts
```

- [ ] **Step 7: Run the affected E2E set**

```bash
cd frontend
npx playwright test \
  e2e/workspace-layout.spec.ts \
  e2e/graph-routes.spec.ts \
  e2e/shared-focus.spec.ts \
  e2e/contextual-ai.spec.ts \
  e2e/node-query-proposal.spec.ts \
  e2e/connection.spec.ts \
  e2e/action-rail.spec.ts \
  e2e/input-persistence.spec.ts
```

Expected: PASS.

- [ ] **Step 8: Commit E2E migration**

```bash
git add frontend/e2e
git commit -m "test(ui): replace floating workspace acceptance with fixed layout"
```

---

### Task 8: Full Frontend Regression and Scope Check

**Files:**
- No production file should change unless a verification failure proves a regression introduced by Tasks 1–7.

**Interfaces:**
- Produces a verified first slice with no backend changes and no new product capability.

- [ ] **Step 1: Run the complete frontend unit suite**

```bash
cd frontend
npm test
```

Expected: PASS.

- [ ] **Step 2: Run typecheck and production build**

```bash
npm run typecheck
npm run build
```

Expected: both exit 0.

- [ ] **Step 3: Run the complete Playwright suite**

```bash
npm run test:e2e
```

Expected: PASS. Presentation-selector failures introduced by this slice are fixed by migrating the test to the new fixed-layout semantic selector; removed floating UI is not restored to satisfy an old selector.

- [ ] **Step 4: Re-run frozen semantic regressions explicitly**

```bash
npx playwright test \
  e2e/core-clarification.spec.ts \
  e2e/input-persistence.spec.ts \
  e2e/fork.spec.ts \
  e2e/lifecycle.spec.ts \
  e2e/contextual-ai.spec.ts \
  e2e/connection.spec.ts
```

Expected: PASS.

- [ ] **Step 5: Verify no backend/runtime files changed**

```bash
git diff --name-only main...HEAD
```

Expected changed paths are limited to:

```text
docs/
frontend/
```

No `backend/`, `agent-brain/`, migration, or Runtime file may appear.

- [ ] **Step 6: Verify no concept-image-only feature was added**

```bash
git diff -- frontend/src frontend/e2e | grep -Ei 'comments?|research|notes|command palette|notification|share permissions|agent persona|analytics' || true
```

Expected: no new product module matching those terms. Existing unrelated strings are reviewed against the diff rather than removed mechanically.

- [ ] **Step 7: Commit only if verification required a small presentation/test regression fix**

```bash
git add frontend
git commit -m "fix(ui): close first-slice regression gaps"
```

Skip this commit when Tasks 1–7 are already green without additional changes.

---

## Self-Review Against the Approved Spec

### Spec coverage

- Fixed Route / Graph / Inspector regions: Task 1.
- Graph remains primary and current answer stays in Graph: Tasks 1 and 3.
- Persistent control reduction: Task 2.
- Historical node compact presentation: Task 3.
- Route navigation over management-console UI: Task 4.
- Inspector priority and contextual AI proposal preservation: Task 5.
- Low-noise visual system / keyboard focus: Task 6.
- 1366×768, 1440×900, 1920×1080 fixed-layout acceptance and presentation-test migration: Task 7.
- Core behavior / Runtime scope protection: Task 8.
- Full Spec redesign: intentionally not implemented in this first slice, matching the approved design's explicit deferral.
- Full Approval / Recovery redesign: intentionally deferred; existing semantics are preserved.

### Plan-completeness scan

Every implementation task names concrete files, exact existing helpers/interfaces, executable test commands, expected outcomes, and implementation structure. There are no unresolved requirements inside this first slice.

### Type / interface consistency

- Sidebar state uses the existing `graphUi.setLeftSidebar({ open, width })` / `setRightSidebar({ open, width })` API.
- Runtime route commands remain emitted by `RouteSidebar` and handled by `WorkspaceView`.
- `GraphCanvas` keeps existing domain emits; only floating-window presentation emits are removed.
- Contextual AI continues to resolve canonical node -> visual key before selection.
- `GraphQuestionNode.spec.ts` uses its existing `mountNode`, `currentData`, `historicalData` helpers.
- `NodeInspector.spec.ts` uses its existing `nodeData()` helper.
- No new backend contract or API type is introduced.

## Completion Definition

This slice is complete only when all of the following are true:

1. Workspace renders fixed Route / Graph / Inspector regions by default.
2. No floating Route or Inspector windows are part of the active workspace presentation.
3. Graph toolbar has substantially fewer permanent controls.
4. Historical nodes are compact; current answerable node is still fully usable.
5. Route sidebar behaves like navigation; low-frequency controls are behind overflow.
6. Inspector first screen prioritizes question, answer, and AI action over technical metadata.
7. All three desktop target sizes are non-overlapping; 1366×768 remains fully interactive.
8. Full frontend unit, typecheck, build, and Playwright suites pass.
9. No backend/runtime code changed.
10. No concept-image-only product feature was added.

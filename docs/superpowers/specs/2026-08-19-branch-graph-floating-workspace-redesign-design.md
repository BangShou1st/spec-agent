# Branch Graph + Floating Workspace Redesign

Date: 2026-08-19
Status: Approved design for implementation planning
Scope: Phase 7.3 post-acceptance correction. Phase 8 remains not started.

## 1. Goal

Correct the product semantics and interaction model of the graph workspace without weakening Runtime ownership.

The workspace must make three user intents visually and semantically distinct:

- **Fork**: accept the current question and answer, then explore a different future from the next question.
- **Re-answer**: keep the same question, choose a different answer, then continue from that alternate answer.
- **Regenerate / replace question**: reject the current question itself and create a replacement question at the same logical depth.

The graph remains the primary workspace. Route navigation and inspection become movable floating tool windows rather than fixed sidebars.

## 2. Runtime doctrine that remains unchanged

The existing ownership boundaries stay in force:

- Runtime owns IDs, route lifecycle, active route, persistence, validation, history, provenance, and context construction.
- Frontend does not invent Runtime lineage, route identity, answers, or lifecycle state.
- Context is lineage, not global chat history.
- SpecSnapshot remains a derived artifact, not source of truth.
- Focus is browser-only reading context and never implicitly activates a Runtime route.
- No arbitrary DAG editing is introduced.

This redesign may extend route provenance and context semantics where the current Fork behavior is insufficient, but it must not move Runtime authority into the frontend.

## 3. Current mismatch

The current Runtime Fork operation creates a new route whose tip points at the selected historical node, but it does not inherit the source route's prior answers/patches as frozen lineage facts. The current ContextBuilder then reads answers by the new route ID, so a Fork route does not semantically carry the accepted answer history that the product expects.

The current frontend projection also assumes one canonical backend node always maps to one Vue Flow visual node. That prevents the graph from expressing Re-answer, where the same canonical question must appear as more than one visual instance because answers differ.

The current Route/Inspector UI is implemented as left/right sidebar-shaped overlays. The target UX is true movable floating tool windows.

## 4. Branch semantics

### 4.1 Fork

Fork means:

> I accept the current state up to and including this Question + Answer. Start another route from the next question.

Example:

```text
             Q3-A
            /
Q1/A1 ─ Q2/A2
            \
             Q3-B
```

Rules:

- The forked node remains unchanged.
- The forked node is not duplicated visually.
- The fork route inherits the accepted prefix through the forked node, including the forked node's answer.
- The first divergent visual node is the new child question generated after the fork point.
- The old route is not modified or superseded merely because of Fork.
- The new route becomes Active according to the existing route-control behavior.
- Source route must be explicit. Runtime must not guess a source route when a node belongs to more than one OPEN route.
- A normal Fork is valid only when the branch point has a finalized answer in the chosen source route. An unanswered current question does not need Fork; it can simply be answered.

### 4.2 Re-answer

Re-answer means:

> The question is correct, but I want to explore a different answer.

Example:

```text
              Q2 / Answer A ─ Q3-A
             /
Q1 / A1 ────
             \
              Q2 / Answer B ─ Q3-B
```

Rules:

- The canonical question is the same.
- The old answer and old route remain immutable history.
- A new route is created from the parent prefix of the target question.
- The target question is exposed on the new route without an answer so the user can answer it again.
- The new visual instance is distinct from the old one from the moment Re-answer begins.
- After the new answer is submitted, the new route continues normally from that answer.
- If the new answer later happens to equal the old answer, the already-diverged visual branches do not automatically merge.

### 4.3 Regenerate / replace question

Regenerate means:

> The current question itself is wrong or poorly framed. Replace it with a different question at the same logical depth.

Example:

```text
              Q2 / A2
             /
Q1 / A1 ────
             \
              Q2' / waiting
```

Rules:

- The replacement question is a new canonical node.
- The replacement node uses the old node's parent as its parent, so it is a sibling at the same logical depth rather than a child of the old node.
- The replacement relation remains explicitly recorded via Runtime provenance such as `supersedesNodeId` / replacement route metadata.
- The old route remains historical and is currently marked SUPERSEDED by the existing regenerate contract.
- The replacement route becomes OPEN + Active.
- The old answer, patch, and child subtree are excluded from replacement context; the shared parent lineage is retained.
- In this correction, Regenerate remains deterministic/manual replacement. It does not become an AI reroll feature.
- UI copy should therefore prefer **创建替代问题 / 替换这个问题** rather than implying stochastic model regeneration.

## 5. Frozen inherited prefix

A branch route needs a Runtime-owned way to represent accepted shared history without duplicating answer records.

Conceptually:

```text
Route = frozen inherited prefix + route-local continuation
```

For a Fork at `Q2/A2`:

```text
inherited prefix = Q1/A1 + Q2/A2
local continuation = Q3-B ...
```

For Re-answer at `Q2`:

```text
inherited prefix = Q1/A1
target question = Q2, unanswered on new route
local continuation begins after the new Q2 answer
```

For Regenerate at `Q2`:

```text
inherited prefix = Q1/A1
replacement target = Q2'
local continuation begins at Q2'
```

The exact persistence representation is an implementation concern, but the following behavior is mandatory:

- provenance is durable backend state, not localStorage memory;
- source route and branch point are explicit;
- inherited history is frozen at branch creation time;
- later sibling changes cannot leak into the branch;
- context construction can deterministically reconstruct the correct frozen prefix after refresh, restart, browser change, Archive/Delete/Restore, or route activation changes;
- no Answer/Patch cloning is required merely to make a Fork appear to inherit history.

A minimal branch provenance model must distinguish at least `FORK`, `REANSWER`, and `REGENERATE`, plus source route and branch point identity.

## 6. Visual graph identity

A Vue Flow visual node is no longer always identical to a canonical backend node.

The projection must distinguish:

```text
canonicalNodeId   backend question identity
route/provenance  Runtime branch identity
visualNodeKey     frontend graph instance identity derived from Runtime facts
```

### 6.1 Shared visual node

A normal shared trunk remains one visual node when the routes have not diverged at that position.

Example after ordinary Fork at Q2:

```text
             Q3-A
            /
Q1 ── Q2 ──
            \
             Q3-B
```

`Q1` and `Q2` are each rendered once.

### 6.2 Re-answer visual instance

Re-answer creates another visual instance for the same canonical question.

```text
              Q2/A
             /
Q1 ─────────
             \
              Q2/B
```

Both visual nodes can carry the same `canonicalNodeId`, but they must have different `visualNodeKey` values.

### 6.3 Replacement visual instance

Regenerate creates a different canonical node and therefore a distinct visual identity by definition.

```text
              Q2
             /
Q1 ─────────
             \
              Q2'
```

A weak/dashed replacement relation may connect Q2 to Q2', but it must not look like normal parent-child lineage.

### 6.4 Monotonic visual divergence

Once a branch has explicitly diverged through Re-answer or Regenerate, it does not automatically merge back because current values happen to match later.

Archive, Delete, Restore, Activate, Focus, Hide, or later equality of answers do not rewrite established branch identity.

Visibility filtering may temporarily remove a branch from rendering, but it does not erase or recompute Runtime provenance.

## 7. Edge projection

Edges express route structure more strongly than node-local route chips.

Rules:

- If multiple routes traverse the same **visual source -> visual target**, render one physical shared lineage edge with route membership metadata.
- When routes diverge to different visual targets, render separate outgoing edges.
- A shared visual node may therefore have many incoming/outgoing route edges.
- Shared-node route chooser chips should no longer be the primary route visualization.
- Single-route edge click may Focus that route.
- Multi-route shared edge click must not guess a route. It selects the shared segment; the Inspector/Route tool can expose its route membership.
- Existing adaptive four-direction handle routing, curved edges, arrow markers, drag-time rerouting, Focus/Active/dim visual weighting, and non-connectable handles remain in place.

## 8. Layout and dragging

Layout is **Shared Trunk + Soft Branch Lanes + Free Drag**.

The automatic/default layout uses:

- horizontal progress as an approximate logical-depth guide;
- vertical branch lanes as a deterministic placement hint;
- shared trunks kept visually compact;
- Fork children placed near their branch point;
- Re-answer and replacement nodes placed near the same logical depth as the original node.

These lanes are not hard constraints.

Rules:

- Users may freely drag nodes in both X and Y.
- Existing adaptive edges follow actual node geometry.
- User-moved positions are respected across refreshes.
- Adding a new branch only places genuinely new visual nodes; existing nodes are not globally relaid out.
- Active/Focus/filter changes do not trigger whole-graph layout changes.
- No automatic whole-graph fit is reintroduced.
- A deliberate **整理布局 / Auto Layout** command may reapply the deterministic suggested layout.
- When a new Active node is completely outside the viewport, the UI may gently reveal it without fitting the whole graph.

## 9. Browser layout persistence V2

Current graph positions are keyed by canonical node ID, which is insufficient once Re-answer can create multiple visual instances of one canonical node.

Introduce a V2 browser layout namespace keyed by stable `visualNodeKey`.

Rules:

- Runtime facts are never stored in this V2 layout state.
- Node positions and presentation-only route display state remain browser-local.
- Existing V1 canonical-node position data does not need a speculative migration into visual identities; a one-time deterministic relayout is acceptable.
- Pan/zoom viewport persistence remains out of scope.

## 10. Floating Tool Windows

The graph canvas remains full-screen and never reflows around tool windows.

Replace left/right sidebar behavior with two true floating windows:

### 10.1 Route Navigator

Responsibilities:

- list routes and lifecycle state;
- show Active and Focus distinctly;
- Locate / Focus / Dim / Hide / Show All presentation controls;
- Activate / Archive / Restore / Delete Runtime controls;
- show branch provenance such as Fork, Re-answer, or replacement origin where useful.

Normal route selection is reading/navigation, not implicit activation. Runtime-changing commands stay explicit.

### 10.2 Inspector

Responsibilities:

- show full selected node/edge details;
- show Question, Answer, purpose/options and provenance;
- show shared edge membership without guessing a route;
- expose the three branch actions with full labels;
- host verbose information that should no longer live inside every graph card.

### 10.3 Floating window behavior

Each window supports:

- title-bar drag anywhere in the workspace;
- resize from edges/corners;
- subtle snap near viewport edges;
- click-to-front z-order;
- close/collapse and toolbar reopening;
- browser-local `{x, y, width, height, open}` persistence;
- viewport clamping so the title bar remains recoverable after resizing the browser/display;
- a **重置窗口** recovery action.

The windows never resize or relayout the graph canvas.

The visual treatment should feel like compact graph/design-tool panels rather than fixed admin sidebars: lighter borders, clear elevation, restrained shadow, rounded corners, compact title bars, and independently scrolling content.

## 11. Graph toolbar

The toolbar remains a compact fixed floating canvas control, not a third draggable window.

It may contain controls such as:

```text
zoom / fit-or-locate utilities | 整理布局 | Routes | Inspector | 重置窗口
```

The existing rule against unsolicited whole-graph fit remains.

## 12. Node interaction and action hierarchy

Graph nodes should become cleaner. Detailed route lists and full answer history belong in the Inspector.

For selected/hovered historical nodes, expose a compact action strip. The three actions must remain visually and verbally distinct:

### 12.1 从这里开新路线

Tooltip/copy concept:

> 我接受现在，换未来。

Behavior:

- requires explicit source route;
- branch point keeps the same Question + Answer;
- creates a Fork route;
- after route creation, the product should attempt to draft the first new child question so the user sees an actual new branch immediately;
- route creation and Draft remain separate Runtime commands internally.

If Fork succeeds but Draft fails, preserve the created route and show a recoverable branch-end state with retry. Never fake or roll back canonical Runtime history in the frontend.

### 12.2 重新选择答案

Tooltip/copy concept:

> 问题没错，答案换一个。

Behavior:

- creates a Re-answer branch from the parent prefix;
- presents the same canonical Question as a new visual instance with no answer;
- activates that route and allows direct answering in the node;
- old route and old answer remain unchanged.

### 12.3 创建替代问题

Tooltip/copy concept:

> 问题本身换掉。

Behavior:

- uses the existing deterministic replacement semantics;
- creates a sibling replacement question at the same logical depth;
- keeps replacement relation visually distinct from normal lineage;
- current implementation remains manual/deterministic rather than AI reroll.

## 13. Focus, Active, visibility, and lifecycle

Existing semantics remain:

- `readingRouteId = focusRouteId ?? activeRouteId` for reading-oriented panels where a concrete route is required.
- Focus is browser-only and never implicitly activates a Runtime route.
- Active route cannot be hidden.
- Hiding a focused route clears Focus.
- Archive/Delete/Restore change lifecycle/visibility but do not rewrite branch identity.
- Shared visual trunks stay visible while any visible route traverses them.
- Spec generation remains Active-route owned and must continue warning/indicating when Focus differs from Active.

## 14. Error handling

- All branch commands are validated by Runtime.
- Frontend must not infer source route on ambiguous shared nodes.
- Fork/Re-answer/Regenerate dialogs or popovers keep prerequisites explicit.
- A successful first command followed by a failed second command is represented as partial success, not rolled back client-side.
- Canonical reads are refreshed after Runtime commands; frontend does not patch route lifecycle or historical provenance locally.
- Floating window persistence errors must degrade to default positions without affecting Runtime state.

## 15. Testing requirements

Implementation must add/adjust tests for at least these behaviors:

1. Fork inherits the accepted frozen prefix through the branch node without cloning old Answer/Patch records.
2. Fork source route is explicit and Runtime no longer guesses on ambiguous shared nodes.
3. Fork keeps the branch node shared and creates divergence only at the next child question.
4. Re-answer keeps the canonical Question but creates a distinct visual instance and a new route-specific answer.
5. Re-answer never mutates the old answer/route.
6. Regenerate remains a same-depth replacement and excludes the old target answer/patch/subtree from replacement context.
7. Existing divergence does not auto-merge after matching values, Restore, Archive, Delete, Activate, Focus, or visibility changes.
8. Shared visual endpoints deduplicate physical lineage edges; divergent endpoints produce distinct edges.
9. Multi-route shared edge click does not guess a Focus route.
10. New branches preserve existing manually dragged positions and do not trigger whole-graph fit or relayout.
11. Layout V2 keys positions by visual identity.
12. Floating windows drag, resize, snap, clamp, persist, reopen, z-order correctly, and never change canvas dimensions.
13. Existing adaptive edge routing, four-side handles, curved directed edges, selection/group drag, Focus/Dim/Hide, and viewport-preservation tests continue to pass.
14. Fork partial success (route created, first child draft fails) remains recoverable and does not fabricate rollback.

## 16. Scope boundaries

Included in this correction:

- backend branch provenance/context correction required for true Fork semantics;
- explicit source-route branch commands;
- Re-answer operation;
- graph visual identity/projection changes;
- shared trunk + soft branch lane default layout;
- V2 browser graph layout identity;
- floating Route Navigator and Inspector;
- final node action hierarchy and copy;
- focused tests and regression coverage.

Not included:

- Phase 8 work;
- auth/multi-user/collaboration;
- provider fallback/platform work;
- RAG/import/browser automation;
- arbitrary graph/DAG editing;
- AI-based regenerate/reroll;
- backend persistence of canvas coordinates or viewport;
- layout-engine dependency changes such as Dagre/ELK;
- Vue Flow dependency upgrades.

## 17. Acceptance summary

The correction is successful when a user can look at the graph and correctly infer:

- **Fork** keeps the current Question + Answer shared and changes only the future.
- **Re-answer** keeps the Question but creates a new answer branch at the current logical step.
- **Replace question** creates a new sibling question at the same logical step.
- shared history is rendered once;
- explicit divergence remains visually stable and never silently re-merges;
- the graph can still be freely rearranged by the user;
- route and inspection tools float above the canvas without controlling its layout;
- Runtime, not the frontend, remains the durable owner of branch provenance and context semantics.

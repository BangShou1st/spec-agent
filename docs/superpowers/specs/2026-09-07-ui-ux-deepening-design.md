# Spec Agent UI / UX Deepening Design

Date: 2026-09-07

Status: proposed for user review

## 1. Problem

Spec Agent's frontend is functionally mature but visually over-exposes capabilities. The current workspace is graph-first, yet Route and Inspector are floating, draggable, resizable windows; the toolbar exposes many low-frequency operations; historical nodes carry too much interaction; Route Navigator behaves like a management console; Spec is buried inside Inspector.

The next stage should improve presentation and information architecture without expanding product scope or changing Agent Runtime semantics.

## 2. Goals

The redesigned frontend should:

- keep Graph as the primary workspace;
- make the current route and latest node obvious within seconds;
- reduce persistent UI noise;
- keep the workspace usable while the agent runs;
- present route, node, approval, and recovery states truthfully;
- preserve shared-node and Focus/Active semantics;
- make the UI feel like a professional AI product workspace rather than a developer console;
- avoid adding features merely because they appear in visual concept images.

## 3. Non-goals

This design does not introduce:

- comments;
- collaboration history systems;
- research / notes / design modules;
- sharing / permission systems;
- command palettes;
- notification centers;
- agent persona configuration;
- analytics dashboards;
- new backend APIs;
- Agent Runtime refactors.

## 4. Recommended architecture

Use a stable four-region workspace:

```text
Top bar
┌────────────┬──────────────────────────┬────────────────────┐
│ Route nav  │          Graph           │     Inspector      │
│            │                          │                    │
├────────────┴──────────────────────────┤                    │
│                 Spec                 │                    │
└───────────────────────────────────────┴────────────────────┘
```

### 4.1 Top bar

Keep only high-value context:

- Spec Agent brand;
- project title / current workspace context;
- compact agent/save status;
- existing project/settings navigation where needed.

Do not add search or account UI unless backed by existing product requirements.

### 4.2 Route navigation

Replace the default floating route window with a fixed, narrow sidebar.

Default route row shows:

- readable route name;
- concise state;
- current browsing/focus indication;
- current runtime indication only when necessary to distinguish it from Focus.

Low-frequency lifecycle and display controls move into overflow menus.

### 4.3 Graph

Graph remains the largest and most visually dominant area.

Preserve:

- pan / zoom;
- drag / node position persistence;
- lineage / replacement / semantic relations;
- shared nodes as one physical entity;
- Focus Route as a browser-only reading context;
- runtime pending projection;
- explicit viewport operations.

Historical question nodes become compact. The active answerable question may remain expanded so the existing answer interaction stays intact.

### 4.4 Node information hierarchy

Historical node default:

```text
Q3          Latest
Data & Security
Ready
```

Primary order:

1. Q number
2. short semantic subtitle
3. one lifecycle state
4. Latest / Shared only when relevant

Detailed content belongs in Inspector.

The first redesign should not move answer submission from Graph to Inspector; that would change the interaction model and should be evaluated separately.

### 4.5 Graph toolbar

Reduce the current always-visible toolbar to a compact group of frequent controls.

Keep visible only what users need repeatedly, such as:

- create/add primary entry;
- zoom / fit controls;
- optional undo / redo as compact icons.

Move low-frequency commands into overflow:

- auto layout;
- show all routes;
- add resource if not primary;
- display reset controls.

Route and Inspector no longer need toolbar buttons to open floating windows if they are fixed layout regions.

### 4.6 Inspector

Inspector is a stable right-side deep-reading surface.

For a selected node, information priority is:

1. identity / state / route context;
2. question or node content;
3. answer / conclusion;
4. key claims / evidence / sources when available;
5. relations / provenance;
6. contextual AI and proposal state;
7. historical actions.

Metadata such as timestamps, author kind, low-level route ids, or technical phase strings should be secondary.

### 4.7 Agent state

Reuse the existing runtime-to-product copy mapping. Do not expose raw runtime phases as developer logs.

The user-visible model should stay small:

- preparing / analyzing;
- generating;
- waiting for input;
- waiting for approval;
- completed;
- failed / needs attention.

The workspace must remain interactive during background work.

### 4.8 Spec

Move Spec out of Inspector into an independent secondary workspace region.

This first design only changes placement and hierarchy. A later slice can improve:

- section navigation;
- unresolved items;
- source reading;
- snapshot comparison;
- version history.

## 5. Existing behavior that must remain true

- Focus Route is not Runtime Active Route.
- Shared Node is not duplicated.
- Frontend does not infer Runtime actions.
- Input drafts survive remounts / focus changes / generation states.
- Unknown mutation outcome is reconciled before retry.
- Approval can lead to autonomous continuation.
- Final RESPOND remains visible to the user.
- Graph remains usable while AgentRun is active.

## 6. Error and recovery behavior

The first visual slice preserves the existing error semantics:

- retryable request failure;
- saved answer but continuation failed;
- unknown outcome requiring reconciliation;
- safe resubmit when nothing was saved;
- model/settings requirement;
- stale state.

The redesign may consolidate their visual placement but must not collapse them into one generic “retry” state.

## 7. Testing strategy

Preserve behavioral coverage for:

- core clarification;
- input persistence;
- route switching;
- fork / reanswer / regenerate;
- lifecycle;
- contextual AI;
- semantic relation;
- pending / recovery;
- Agent chain terminal behavior.

Tests that only protect the old floating-window presentation can be replaced by fixed-layout tests, including:

- Route sidebar and Inspector do not cover Graph interaction;
- 1366×768 remains usable;
- 1440×900 remains comfortable;
- 1920×1080 uses space effectively;
- current node remains answerable;
- keyboard focus reaches hover/contextual actions.

## 8. First implementation slice

The first implementation slice should be deliberately narrow:

1. replace floating Route / Inspector presentation with fixed layout regions;
2. simplify Graph Toolbar;
3. introduce compact historical Question Node presentation;
4. simplify Route rows and move low-frequency actions into overflow;
5. reorder Inspector information without changing its data sources;
6. keep current answer submission behavior unchanged.

Do not include the full Spec redesign, full Approval redesign, full recovery visual system, or new functionality in this slice.

## 9. Success criteria

The slice is successful when:

- a first-time user understands the main workspace structure in about 10 seconds;
- the current route / latest node is discoverable in about 5 seconds;
- Graph is visibly the primary workspace;
- persistent controls are substantially reduced;
- historical nodes read like navigation objects, not forms;
- Agent activity does not lock the workspace;
- existing core E2E behavior remains covered;
- no backend runtime component changes are required.

## 10. Scope rule

> The UI concept image is a visual reference, not a feature specification.
>
> If a control or module is not supported by existing product behavior, project documentation, or explicit user approval, it is not implemented.

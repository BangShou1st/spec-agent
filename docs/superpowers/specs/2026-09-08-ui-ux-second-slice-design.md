# Spec Agent UI/UX Second Slice — Graph-First Productization Design

## Status

Approved product direction. This document turns the approved second-round UI/UX direction into implementation constraints for the `ui-ux-second-slice` branch.

## Goal

Make the frontend functionally complete for the existing product capabilities without turning the workspace into a control panel. The Graph remains the primary working surface. Spec, Requirement State, Agent progress, Approval, and Recovery become clearly placed supporting experiences rather than competing top-level panels.

## Non-Negotiable Product Principle

> Spec Agent is a Graph-first AI workspace. Functionality may be rich, but the default interface only exposes what the current task needs.

The UI must preserve existing backend/runtime semantics and must not infer new semantics client-side.

## Scope Guardrails

### In scope

- Collapsible bottom Spec Dock attached to the Graph center region.
- Inspector simplification into one contextual surface.
- Requirement State moved from a top-level Inspector tab into project summary + secondary detail view.
- Product-facing Agent status mapping.
- Product-facing Approval card for existing durable NodeQuery proposals.
- Product-facing Recovery notices for existing safe retry/reconcile states.
- Shared-node / Graph metadata density reduction.
- Visual hierarchy improvements directly required by the above.
- Desktop acceptance at 1366×768, 1440×900, and 1920×1080.

### Explicitly out of scope

- Backend changes.
- Agent Runtime changes.
- API contract changes.
- Database migrations.
- New product modules.
- Comments, chat sidebar, notifications, research, notes, design tabs, collaboration, sharing/permissions, command palette, agent persona configuration, analytics dashboard.
- Mobile-first redesign.
- Legacy `FloatingWindow` cleanup unless a compile/test failure makes a minimal removal necessary.
- New speculative proposal payloads or invented impact descriptions not provided by existing APIs.

## Top-Level Information Architecture

Only five top-level concepts are allowed in the workspace:

1. **Routes** — which line of thought is being viewed.
2. **Graph** — the project structure and current working position.
3. **Inspector** — details for the selected Graph object or lightweight project summary when nothing is selected.
4. **Agent State** — what the AI is doing right now, visible only while useful.
5. **Spec** — the derived delivery artifact, always subordinate to Graph.

No sixth permanent panel may be introduced in this slice.

## Workspace Layout

Desktop target:

```text
┌──────────────────────────────────────────────────────────────┐
│ Spec Agent · Project                       Agent state       │
├────────────┬──────────────────────────────┬──────────────────┤
│ Routes     │                              │ Inspector        │
│            │            Graph             │                  │
│            │                              │ selected object  │
│            │                              │ details          │
│            │                              │                  │
├────────────┴──────────────────────────────┤                  │
│ Spec · route · latest · unresolved      ▴│                  │
└───────────────────────────────────────────┴──────────────────┘
```

The Graph center region remains the dominant area.

## Spec Dock

### Default state

The Spec Dock is collapsed by default and consumes approximately 44–52 px of vertical space.

Collapsed summary should communicate only useful status, for example:

```text
规格   主路线 · 最新   8 个章节 · 2 个未解决项      ▴
```

The collapsed bar must not show raw ids, run ids, provenance ids, or technical format metadata.

### Expanded state

Expanded height should be approximately 38–42% of the Graph center region and must never exceed 45% in the default implementation.

The Graph remains visible above the Dock. Do not implement drag-to-resize in this slice.

Expanded structure:

- Header: `规格`, reading route, latest/version selector, collapse control.
- Primary action: generate new spec for Runtime Active Route.
- Body: selected snapshot sections.
- Secondary sections: unresolved items; sources/provenance disclosure.
- Snapshot history: compact selector/list, not a large card wall.

### Route semantics

Existing semantics are frozen:

- Reading a Spec follows explicit Focus Route when available; a single-route project may fall back to its only route.
- Generating a Spec always targets Runtime Active Route.
- Focus Route must never be changed implicitly merely because a Spec is opened.
- If reading route differs from Active Route, the UI must clearly state both before generation.

### Technical metadata

The following remain available but are hidden behind `来源与追溯` or equivalent disclosure:

- snapshot id
- route id
- tip node id
- createdByRunId
- source reference ids
- raw format metadata

## Inspector

The Inspector stops being a three-tab product navigator.

Remove top-level `详情 / 需求状态 / 规格` tabs.

### Selected node state

Order:

1. Identity: Q number, Latest/Shared, route/lifecycle context.
2. Question/content.
3. Answer.
4. Contextual Ask AI.
5. Existing proposal/approval card only when applicable.
6. Claims/relations summary.
7. `更多详情` disclosure for technical metadata/history/provenance.

The Inspector must not duplicate answer submission. Answer submission remains in the current answerable Graph node.

### Selected edge state

Show only:

- relation/replacement/lineage meaning
- connected object context
- route membership when useful
- technical ids only under secondary details

### No selection state

Show a lightweight project summary instead of an empty Inspector or a full Requirement dashboard.

Target summary:

```text
项目状态

已确认  18
未解决   3
假定     2
已拒绝   1

当前查看
主路线

查看完整需求状态 >
```

## Requirement State

Requirement State remains fully available but becomes a secondary Inspector view.

### Summary

Default no-selection Inspector shows counts for confirmed, unresolved, assumed, and rejected claims.

### Full detail view

User enters through `查看完整需求状态` and can return to project summary.

Group claims by backend-provided category:

- 已确认
- 未解决
- 假定
- 已拒绝

Do not reinterpret or promote claim categories client-side.

### Technical claim details

Default claim rows should focus on readable claim text.

Hide the following behind `技术详情` disclosure:

- sourceNodeId
- sourceAnswerId
- raw claim kind
- confidence value
- route UUID

No raw UUID should appear in the default project summary or default claim list.

## Agent State

Agent status is a single product-facing presentation layer, not a second runtime log.

### Rules

- Show one concise current state while work is active.
- Do not expose raw runtime phase names.
- Do not append unknown phase codes to fallback text.
- If the run chain continues through child runs, the product should feel like one continuing AI task.

### Presentation categories

Map backend/runtime phases into a small stable set of product states:

- `正在分析上下文`
- `正在整理需求`
- `正在规划下一步`
- `正在执行`
- `等待你的确认`
- `等待你的输入`
- `正在继续处理`
- `需要处理`
- generic unknown fallback: `处理中…`

The mapping belongs in a presentation helper, not inside Runtime/store mutation logic.

## Approval UX

This slice productizes existing durable NodeQuery proposal approval. It does not invent new proposal APIs.

### Placement

Approval appears in the Inspector near the selected node's Agent section.

It is visible only when a real proposal is pending for that node.

### Content

Display only information supported by existing frontend data:

- human-readable action label derived from `actionFamily`
- existing model/user-facing message when available
- selected/anchor node context
- clear `拒绝` and `确认执行` actions

Do not show `proposalId`, `runId`, raw `actionFamily`, or internal status codes in the default card.

Do not fabricate an `impact` sentence when the API does not provide enough data.

### Continuation

After approval, existing continuation behavior remains authoritative. The frontend must not assume the accepted action is terminal.

## Recovery UX

Recovery must expose the Runtime safety work clearly and simply.

Create a presentation layer that maps existing store states to one Recovery Notice model.

### Priority order

1. **Outcome unknown** — user must reconcile first; no re-execution CTA.
2. **Answer saved, continuation incomplete** — offer resume/continue, never ask user to answer again.
3. **Canonical state proves answer absent** — safe resubmit CTA.
4. **Retryable model operation** — retry operation.
5. **Stale context** — refresh/reconcile context.
6. **Policy/permission/non-retryable** — explain, no meaningless retry button.

Only one primary recovery CTA should be shown at once.

### Example copy

Answer saved:

```text
回答已经保存
后续生成没有完成，不需要重新填写回答。
[继续生成]
```

Unknown outcome:

```text
提交结果暂时无法确认
为了避免重复操作，请先同步最新状态。
[同步状态]
```

The presentation layer must reuse existing store actions and must not duplicate Runtime decision logic.

## Graph Density Polish

No new Graph capability is added in this slice.

### Shared nodes

Default Shared historical node should summarize route membership instead of rendering many route chips.

Preferred compact treatment:

```text
Q3                         Latest
数据与安全
Shared · 4 条路线           已确认
```

Specific route memberships move to Inspector details.

### Node principle

Graph shows structure and current work position. Inspector shows detail.

Historical nodes remain compact. Current answerable node remains expanded and owns answer submission.

## Presentation Architecture

Do not keep growing `WorkspaceView.vue` with conditionals.

Prefer focused presentation units such as:

```text
frontend/src/components/workspace/
  WorkspaceInspector.vue
  ProjectSummary.vue
  RequirementDetailView.vue
  AgentProposalCard.vue
  RecoveryNotice.vue
  SpecDock.vue

frontend/src/presentation/
  agentPresentation.ts
  recoveryPresentation.ts
```

Exact file names may follow existing repository conventions, but responsibility boundaries are mandatory:

- Store: canonical/server/runtime state and command actions.
- Presentation helpers: pure mapping from existing state to user-facing labels/models.
- Components: render and emit user intents.

Presentation code must not mutate Runtime semantics or infer missing backend facts.

## Visual Direction

Use the installed `frontend-design` skill only after structural behavior is correct.

Desired style:

- professional AI workspace
- quiet neutral surfaces
- strong hierarchy through spacing and type, not badge proliferation
- restrained semantic color
- subtle borders/shadows
- Graph visually dominant
- compact, discoverable secondary controls
- no decorative dashboard chrome

The skill may improve typography, spacing, composition, surfaces, hover/focus states, and interaction polish. It may not add product features or top-level navigation.

## Accessibility / Web Quality

After implementation, run `web-design-guidelines` as a review step.

Required behaviors:

- keyboard reachable controls
- visible focus states
- appropriate labels/aria for icon-only controls
- no color-only status distinction
- acceptable contrast
- reduced-motion-safe interaction
- overflow/scroll areas remain keyboard usable
- Dock and Inspector transitions preserve focus logically

## Desktop Acceptance

Validate at:

- 1366×768
- 1440×900
- 1920×1080

At 1366×768:

- Graph remains usable with Spec Dock collapsed.
- Opening Spec Dock must not make Graph disappear.
- Current question remains reachable and answerable.
- Inspector remains usable.

## Behavioral Acceptance

The slice is accepted only if all of the following hold:

- Graph remains the visibly largest and primary work surface.
- Routes / Graph / Inspector / Agent State / Spec are the only top-level workspace concepts.
- Spec is no longer a top-level Inspector tab.
- Requirement State is no longer a top-level Inspector tab.
- Default UI exposes no UUID, run id, raw actionFamily, or raw runtime phase.
- Requirement claims remain backend-derived and are never promoted client-side.
- Focus Route never mutates Runtime Active Route.
- Spec reading vs generation route semantics remain explicit and unchanged.
- Answer submission remains in Graph current node only.
- Shared Nodes remain one canonical physical node.
- Approval uses existing durable proposal data only.
- Recovery never offers an unsafe duplicate mutation.
- Existing recovery/idempotency/reconciliation logic remains authoritative.
- Backend, Agent Runtime, APIs, and migrations remain unchanged.

## Testing Strategy

Implementation must be TDD-oriented and preserve existing tests.

Required test groups:

- unit tests for pure Agent presentation mapping
- unit tests for Recovery priority mapping and CTA selection
- component tests for Spec Dock collapsed/expanded behavior
- component tests for Inspector no-selection/project-summary flow
- component tests for Requirement secondary view and hidden technical metadata
- component tests for Approval card visibility and accept/reject wiring
- Graph tests for compact Shared route-membership display
- E2E for Graph-first layout with collapsed and expanded Spec Dock
- E2E for Focus Route vs Active Route Spec generation warning/targeting
- E2E for approval continuation behavior using existing deterministic backend flow
- E2E for safe recovery states where current deterministic fixtures support them
- desktop viewport acceptance at all three target sizes

Final verification:

```text
npm test
npm run typecheck
npm run build
npm run test:e2e
```

Then run `web-design-guidelines` review and fix only issues within this slice's scope.

## Definition of Done

A user can open a project and understand the workspace in about 10 seconds; Graph is clearly central; all existing important product capabilities remain reachable; Spec, requirements, agent state, approval, and recovery are easy to find when relevant but do not compete for permanent screen space; and no backend/runtime semantics have been duplicated in the frontend.

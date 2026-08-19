# Phase 7.3 Post-Acceptance: Immersive Graph Interaction Correction

## Status

This is a frontend UX corrective patch applied after the Phase 7.3 manual
acceptance. Phase 7.3 remains closed. Phase 8 is not started.

## Manual acceptance findings

- The graph was being presented as one column inside a dashboard-like layout.
- Sidebar and status surfaces reduced the graph canvas instead of floating over it.
- Node and edge clicks did not provide a direct reading-context transition.
- Shared-node Fork remediation required leaving the Fork dialog to restore or
  activate a base route.
- A Current node could use a larger footprint than a Historical node and
  collide with its neighbor after a route transition.
- SmoothStep edges were functional but too rigid for the graph's reading flow.

## Product correction

The graph is the workspace, not a visualization inside the workspace.

- The workspace graph owns the full available viewport.
- Route Navigator, Inspector, project badge, toolbar, errors, and feedback are
  floating overlays and do not participate in graph layout sizing.
- A normal exclusive node or edge click selects/focuses its route. A shared
  node or edge preserves a valid current Focus, otherwise resolves to the
  Active member, and otherwise waits for an explicit node-local route chip.
- A plain pane click clears selection and browser Focus while preserving manual
  dim/hide, lifecycle filters, and saved positions.
- The route card remains a secondary navigation surface: clicking it focuses and
  locates the route, while low-frequency actions remain available under
  “更多”.

## Explicit Fork remediation

The existing Runtime and Fork API contracts are unchanged. When a selected base
route is not `OPEN`, the Fork dialog exposes an explicit “恢复此路线” action.
When it is `OPEN` but not Active, the dialog exposes an explicit “设为当前
路线” action. Each action emits only its own command, refreshes canonical state,
and leaves the dialog open. “创建分支” becomes available only after the user
has explicitly selected an `OPEN` Active base route. No Restore → Activate →
Fork chain is performed by the frontend.

## Stable geometry and adaptive edges

All question-node states use the stable outer footprint `320 × 220` pixels.
Current emphasis uses border, header, and shadow treatment; content and
expanded historical details scroll inside the fixed body. The first/incremental
layout spacing is `440` horizontal and `280` vertical, while saved positions
remain unchanged until the user explicitly chooses automatic layout.

Lineage and replacement relationships both use the adaptive custom edge
renderer with the existing four-side source/target handles, restrained Bezier
curves, and Vue Flow 1.48.2 arrow markers. Lineage remains solid; replacement
remains dashed and warning-colored. Drag-time handle rerouting and drag-stop
position persistence remain browser-only behavior.

## Runtime boundary

This patch changes frontend presentation, browser Focus/selection wiring, and
local dialog orchestration only. Runtime Active Route, route lifecycle,
history, answer semantics, context reconstruction, Fork, Regenerate, and
backend API contracts remain authoritative and unchanged.

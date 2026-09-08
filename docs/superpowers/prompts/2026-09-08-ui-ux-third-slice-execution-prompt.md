# ZCode Execution Prompt — Spec Agent UI/UX Third Slice

You are executing the final frontend closure slice for `spec-agent`.

## Read first

Read these files completely before changing code:

1. `docs/ui/UI_SCOPE_FREEZE.md`
2. `docs/superpowers/specs/2026-09-08-ui-ux-third-slice-design.md`
3. `docs/superpowers/plans/2026-09-08-ui-ux-third-slice.md`

Then execute **Tasks 1–8** from the implementation plan in order.

## Branch / base

Expected branch:

```text
ui-ux-third-slice
```

Expected base / merge-base:

```text
7e98cca652eb44e87b2992f8f74b374778d2e5c3
```

Do not work on `main`.

## Execution mode

- Continue through all normal tasks without asking the user for routine confirmation.
- Use TDD for behavior-affecting changes.
- Make focused commits at the task boundaries defined in the plan.
- Do not create empty commits.
- Stop only for a real blocker: baseline tests already failing, a candidate legacy artifact still has a real product consumer that makes the planned deletion unsafe, a critical backend/API gap, or an environment failure that prevents required verification.

## Product constraints — highest priority

This is **not** a feature round.

The product remains:

```text
Routes | GRAPH | Inspector
           |
        Spec Dock
```

Graph is the subject of the workspace.

Do not add:

- new top-level regions
- new panels/tabs
- comments/chat/collaboration
- notifications
- research/notes/design modules
- sharing/permissions
- command palette/search center
- account/profile logic
- agent personas/configuration UI
- analytics/dashboard UI
- new runtime states
- new route/node commands

Do not modify:

- Backend
- Agent Runtime
- API contracts
- migrations
- recovery/idempotency/reconciliation semantics
- approval continuation semantics
- Focus Route vs Runtime Active Route semantics
- Shared Node canonical identity
- answer submission ownership
- Spec reading/generation route semantics

Default UI must not expose UUIDs, run ids, raw `actionFamily`, or raw runtime phase.

## Legacy cleanup rules

The pre-approved cleanup set is the old floating-window presentation stack.

Before deleting anything, run the reference audit in Task 1 and classify every match.

You may delete only proven-dead floating-window artifacts such as:

```text
FloatingWindow.vue
RouteNavigator.vue
floatingWindowLayout.ts
FloatingWindowPreference
WorkspaceUiPreferencesV2
floating-window V2 storage helpers/defaults/ranges
graphUiStore floatingWindows/windowZOrder/setFloatingWindow/bringWindowToFront/resetWindows/persistWorkspaceV2
legacy-only tests
```

If any candidate still has a real current production consumer, keep it and report the evidence. Do not preserve dead compatibility methods or hidden production DOM only for tests.

Do not expand cleanup to unrelated old components just because they look unused.

## Visual work order

Do not invoke design skills first.

Order:

```text
1. baseline verification
2. legacy cleanup with tests
3. Graph / Route hierarchy polish
4. Inspector / Spec / Approval / Recovery polish
5. accessibility / focus / reduced-motion / geometry
6. E2E geometry + screenshots
7. frontend-design review
8. web-design-guidelines audit
9. full verification
```

### `frontend-design`

Use only for:

- typography
- spacing
- visual hierarchy
- surfaces
- density
- hover / selected / focus treatment

Reject suggestions that add product concepts, new navigation, new panels, new commands, new fonts/dependencies, decorative complexity, or animation systems.

### `web-design-guidelines`

Use after visual polish for:

- keyboard access
- focus-visible
- aria/labels
- contrast
- forms/buttons
- reduced motion
- overflow/scroll
- desktop geometry

If its remote rule source cannot be fetched, manually audit those same categories and report the limitation.

## Visual character

Target:

```text
professional AI/product-design workspace
quiet neutral surfaces
Graph visually dominant
strong hierarchy from spacing and typography
compact contextual controls
restrained semantic color
subtle boundaries
no badge explosion
no engineering-console feel
```

Historical Graph nodes stay compact. The current answerable node remains expanded and owns answer submission.

Routes remain readable without confusing Focus with Active.

Inspector remains contextual, not a product navigator.

Spec remains collapsed by default and never covers the whole Graph.

Approval and Recovery stay concise and appear only when real state requires them.

## Required test commands

At the end run fresh from `frontend/`:

```bash
npm test
npm run typecheck
npm run build
npm run test:e2e
```

Do not report success from earlier partial runs.

Then from repository root prove scope:

```bash
git diff --name-only origin/main...HEAD -- backend agent-brain
```

Expected output: empty.

## Required desktop acceptance

Validate:

```text
1366×768
1440×900
1920×1080
```

At each size confirm:

- Graph remains the largest work surface with Spec collapsed.
- sidebars do not overlap Graph.
- min/max sidebar widths do not break Graph.
- current question remains reachable/answerable.
- Inspector scrolls internally.
- Spec expanded still leaves Graph visible and usable.
- no horizontal page overflow.
- dialogs/menus stay usable.

## Required real screenshots

Tests alone are not enough.

After the final UI is working, capture these real deterministic screenshots under an ignored/local Playwright artifact directory such as `frontend/test-results/ui-final/`:

```text
default-graph-1440x900.png
selected-node-1440x900.png
spec-expanded-1440x900.png
requirements-1440x900.png
spec-expanded-1366x768.png
```

Do not add screenshot-only production code.
Do not commit the PNGs unless explicitly requested.

Open and inspect every screenshot yourself before finishing. If they show overlap, clipped controls, Graph losing dominance, raw technical ids, badge clutter, or Spec hiding Graph, fix the UI and recapture.

## Push / PR rule

When Tasks 1–8 are complete:

```bash
git push -u origin ui-ux-third-slice
```

Do **not** open a PR.
Do **not** merge `main`.

## Final report

Report only after fresh verification. Include:

1. final HEAD SHA
2. branch and working-tree state
3. Task-by-Task commit list
4. production files changed/deleted
5. unit exact count/result
6. typecheck result
7. build result
8. E2E exact count/result
9. 1366/1440/1920 geometry results
10. `frontend-design` suggestions accepted/rejected
11. `web-design-guidelines` findings/fixes/limitations
12. floating-window cleanup evidence and anything retained
13. exact paths to all five screenshots
14. remaining known issues, or `none`
15. confirmation that Backend/Runtime/API/migrations were untouched and `main` was not merged

The final screenshots are part of the deliverable. A report with tests but without screenshots is incomplete.

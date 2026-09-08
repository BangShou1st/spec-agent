# Spec Agent UI / UX First Slice — Combined Execution Prompt

Use this prompt in the local coding agent / Codex session that will perform the implementation.

---

You are implementing the first UI / UX deepening slice for **Spec Agent**.

## Execution mode

Work through the entire first slice in one continuous implementation session. Do **not** stop after each task to ask for approval. The plan is already approved.

Use the existing plan as the source of implementation truth:

1. `docs/superpowers/plans/2026-09-07-ui-ux-first-slice.md`
2. `docs/superpowers/specs/2026-09-07-ui-ux-deepening-design.md`
3. `docs/ui/UI_SCOPE_FREEZE.md`
4. `docs/ui/UI_UX_AUDIT.md`

Read all four before editing source code.

Use the Superpowers workflow:

- first use `superpowers:using-git-worktrees` to verify/create an isolated worktree;
- then use `superpowers:executing-plans` and execute the approved plan from Task 1 through Task 8;
- use TDD where the plan requires it;
- after all tasks and verification succeed, use `superpowers:verification-before-completion`;
- then use `superpowers:finishing-a-development-branch`.

If subagents are available, you may use them internally, but this is still one continuous execution: do not return to the user between ordinary tasks.

## Branch and safety

Implementation branch:

`ui-ux-deepening-audit`

The branch was created from:

`main @ 35039f80eea7f78a2c8d7a92833cff3d0c76400c`

Do not implement directly on `main`.

Before editing, inspect current branch/worktree status. Preserve unrelated user changes. Never reset, clean, stash, or overwrite unrelated work.

## Product objective

The frontend is already functionally mature. The objective is **not to add more product features**.

The objective is to make the existing Spec Agent workspace:

- calmer;
- simpler;
- visually higher quality;
- easier to understand immediately;
- less like a developer / graph-management console;
- more like a professional AI product-design workspace.

The Graph must remain the primary workspace.

The desired mental model is:

```text
┌────────────────────────────────────────────────────────────┐
│ Spec Agent / project context / compact agent status        │
├────────────┬──────────────────────────┬────────────────────┤
│            │                          │                    │
│   Routes   │          Graph           │     Inspector      │
│            │                          │                    │
│            │                          │                    │
└────────────┴──────────────────────────┴────────────────────┘
```

The Spec area is a later/full slice. Do not turn this first slice into a full Spec redesign.

## Non-negotiable scope rule

**The concept image is a visual reference, not a feature specification.**

Do not add a control, module, tab, service, API, store, or product capability simply because it would make the interface look more “complete”.

Unless already supported by existing product behavior, project documentation, or this approved plan, do not add:

- comments;
- collaboration history systems;
- Research module;
- Notes module;
- Design module;
- sharing / permission systems;
- command palette / global command search;
- notifications;
- account/avatar business logic;
- agent persona/configuration system;
- analytics/dashboard UI;
- trash/all-nodes/shared-nodes management center;
- any new backend API.

**Functionality that already exists may be visually demoted, grouped, or moved. Do not casually delete real product behavior.**

## Runtime / backend freeze

This is a frontend presentation slice.

Do not modify or refactor:

- `ContinuationCoordinator`;
- `DecisionExecutionService`;
- `ActionEligibilityValidator`;
- `RunWorker`;
- `ContextBuilder`;
- `ContextGuard`;
- Approval Runtime semantics;
- AgentRun persistence;
- migrations;
- backend action semantics.

Do not invent frontend inference to compensate for Runtime behavior.

If you discover a genuine backend API gap that blocks an approved UI behavior:

1. stop that specific implementation;
2. document the exact gap and evidence;
3. check whether presentation/read-model code can solve it;
4. continue other independent frontend work when safe;
5. report the blocker clearly at the end instead of guessing.

## Semantic invariants that must remain true

Do not regress any of these:

- **Focus Route != Runtime Active Route**.
- Focus is browser reading context only.
- Shared Node remains one canonical node; do not duplicate it for visual convenience.
- Frontend does not infer the next Runtime action from node kind, conflict, or visual state.
- Current answer submission remains inside the Graph question node in this slice.
- Historical answers remain read-only.
- Input drafts survive remounts, focus changes, route viewing changes, and generation states.
- Unknown mutation outcome must reconcile before retry; never blindly resend an uncertain mutation.
- Pending/approval/recovery UI must reflect real backend state.
- Agent work must not lock the entire workspace.
- Final user-visible responses remain visible.

## Design direction

Keep the frontend intentionally restrained.

### Overall

- Simplified Chinese UI copy.
- Light, neutral workspace.
- Generous spacing.
- Strong information hierarchy.
- Subtle borders rather than boxes around everything.
- Small, deliberate use of accent color.
- Avoid badge walls.
- Avoid permanent rows of management buttons.
- Avoid dashboard-like decorative modules.
- Avoid unnecessary animation.
- Do not add dependencies for appearance.

### Workspace shell

Replace the default floating Route and Inspector windows with stable sidebars using the existing `ResizableSidebar` and existing `graphUiStore.left/rightSidebar*` preferences.

The sidebars may collapse/resize because that capability already exists. Do not create another layout framework.

Remove floating-window composition and floating-window obstacle-management code from the active Workspace presentation when the plan says to do so.

Do not mix legacy floating-storage migration cleanup into this slice unless required by tests. Legacy unused files/fields may remain temporarily as specified by the plan.

### Graph

Graph must be the largest visual area.

Preserve:

- pan;
- zoom;
- node dragging;
- node position persistence;
- lineage / replacement / semantic relation display;
- selection;
- Focus Route behavior;
- pending projection;
- current answer interaction.

Do not redesign graph semantics.

### Historical question nodes

Make historical nodes compact navigation/read objects rather than forms.

Default hierarchy should be approximately:

```text
Q3              最新 (only when relevant)
数据与安全
已确认 / 就绪 / relevant concise state
```

Do not show full route chips, full answer prose, route selector, and all actions permanently on every historical node.

Keep historical actions available on hover/selection/contextual menu as the plan specifies:

- 从这里开新路线;
- 重新选择答案;
- 换一个问题;
- 问 AI.

Current answerable node may remain expanded and interactive.

### Route sidebar

The route sidebar is navigation first, management second.

Default row should emphasize:

- route name;
- concise lifecycle/state;
- current browsing Focus where applicable;
- Runtime Active distinction only when needed;
- node count only if useful and low-noise.

Do not expose Locate / Focus / Isolate / Dim / Hide / Activate / Restore / Archive / Delete all as permanent button rows.

Move low-frequency actions into a compact native overflow/details menu as defined in the plan.

Do not collapse Focus and Active into one state.

### Graph toolbar

The current 12-button vertical toolbar is too noisy.

Keep only frequent controls visible:

- add idea / primary create entry;
- undo / redo as compact controls;
- zoom out / zoom in;
- fit view.

Move lower-frequency existing actions into overflow, including as applicable:

- add resource;
- auto layout;
- show all routes.

Remove presentation-only controls for opening/resetting floating windows once sidebars are fixed.

Do not invent new toolbar functionality.

### Inspector

Inspector is a stable deep-reading surface.

Reorder content so a selected node answers these questions in order:

1. What is this node / what state is it in?
2. What was the question/content?
3. What is the answer/conclusion?
4. What evidence / relations / provenance matter?
5. What is AI doing or proposing?
6. What action, if any, does the user need to take?

Demote metadata such as timestamps, author kind, raw IDs, low-level branch details.

Keep contextual AI and proposal approval/rejection behavior real and functional. Do not create a fake Agent activity timeline.

Do not move answer submission from Graph to Inspector in this slice.

## Implementation discipline

Follow `docs/superpowers/plans/2026-09-07-ui-ux-first-slice.md` task-by-task.

Do not skip directly to a large CSS rewrite.

For each task:

1. write/update the failing test described by the plan;
2. run the focused test and verify the expected failure;
3. implement the smallest coherent change;
4. run the focused test again;
5. inspect the diff for accidental behavior changes;
6. commit that task with the plan's suggested commit boundary;
7. continue immediately to the next task.

Do not ask the user for approval between successful tasks.

Only stop early when one of these is true:

- the plan conflicts with the actual code in a way that cannot be safely resolved;
- a required test repeatedly fails for a reason unrelated to the intended presentation change;
- a genuine backend/API gap is proven;
- unrelated working-tree changes make a safe edit impossible;
- a destructive or scope-expanding choice is unavoidable.

When normal implementation details differ slightly from the plan because the repository has evolved, preserve the plan's behavior and architectural intent, document the deviation, and choose the least invasive solution.

## Testing requirements

Maintain or replace presentation-specific tests; do not weaken behavioral protection.

Core behavior that must remain covered:

- core clarification;
- input persistence;
- route switching;
- Focus/Active separation;
- shared focus/shared node behavior;
- fork;
- reanswer;
- regenerate;
- route lifecycle;
- contextual AI;
- node-query proposal approval/rejection;
- semantic relation creation/inspection;
- pending/recovery behavior;
- Agent chain terminal behavior.

Old tests that specifically protect floating-window geometry may be replaced once equivalent fixed-layout coverage is green.

Delete only the old presentation-specific E2E files explicitly listed by the plan after their replacement tests pass.

## Desktop visual/interaction acceptance

The final implementation must explicitly exercise these viewports in Playwright:

- `1366 × 768`
- `1440 × 900`
- `1920 × 1080`

At each relevant size verify at minimum:

- left Route sidebar visible when open;
- Graph receives the central usable area;
- right Inspector visible when open;
- sidebars do not overlay/intercept Graph interactions;
- current node remains answerable;
- Graph pan/zoom/fit remain usable;
- selected/contextual node details are reachable;
- layout does not require floating-window obstacle avoidance.

Do not judge success by screenshots alone. Verify actual pointer/input interaction.

## Visual quality bar

Do not settle for “tests pass but looks like old UI in fixed columns”.

Within the approved scope, polish:

- spacing rhythm;
- font size/weight hierarchy;
- neutral surface hierarchy;
- border contrast;
- selected/focus state;
- small status pills;
- button density;
- hover/focus affordances;
- sidebar proportions;
- Graph whitespace.

Prefer fewer visible controls and clearer hierarchy over decorative complexity.

Do not spend time adding ornamental illustration, gradients, avatars, fake data panels, or other non-product decoration.

## Required verification before completion

At the end run, from `frontend`:

```bash
npm test
npm run typecheck
npm run build
npm run test:e2e
```

If the repository has a documented CI-matching command/environment for the frontend, run that as well when available.

Also verify the diff against scope:

```bash
git diff --name-only main...HEAD
```

Confirm no backend/Runtime files were modified.

Search the changed frontend for accidentally introduced concept-only features such as comments/research/notes/share/notifications if relevant.

Inspect `git status --short` and preserve any unrelated files.

## Completion report

When all work is complete, report one consolidated result, not a task-by-task narration.

Include:

- final branch and HEAD SHA;
- concise summary of the new workspace structure;
- exact source files changed/created/deleted grouped by purpose;
- tests run and pass counts/results;
- desktop viewport checks performed;
- any deviations from the approved plan and why;
- confirmation that backend / Agent Runtime were untouched;
- confirmation that no concept-only product features were added;
- remaining UI / UX work intentionally deferred to later slices.

If anything failed, do not describe the work as complete. State the failing verification and the current safe stopping point.

## Final priority rule

When choosing between “more features” and “clearer existing behavior”, choose **clearer existing behavior**.

When choosing between “more controls visible” and “less visual noise”, choose **less visual noise**, provided the existing capability remains reachable.

When choosing between “match the concept image literally” and “preserve actual Spec Agent product semantics”, preserve **actual product semantics**.

Build the frontend so it feels deliberate, calm, and high quality — not feature-heavy.

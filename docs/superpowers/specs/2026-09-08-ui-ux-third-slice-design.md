# Spec Agent UI/UX Third Slice — Final Product Closure Design

## Status

Approved continuation of the first two UI/UX slices. This is the final frontend closure pass, not a new product-design round.

Baseline: `main` @ `7e98cca652eb44e87b2992f8f74b374778d2e5c3`.

Branch: `ui-ux-third-slice`.

## Goal

Turn the already-functionally-complete Graph-first workspace into a finished product surface: calmer visual hierarchy, consistent interaction states, stronger accessibility, reliable desktop geometry, and removal of clearly dead floating-window presentation code.

No new product capability is introduced. No existing real capability is removed.

## Non-Negotiable Product Principle

> Spec Agent remains a Graph-first AI workspace. Routes, Inspector, Agent State, and Spec support the Graph; they do not compete with it.

The final pass optimizes clarity and finish, not feature count.

## Scope Guardrails

### In scope

- Typography, spacing, surface hierarchy, borders, shadows, density, and semantic color refinement.
- Consistent hover / selected / active / disabled / focus-visible states.
- Graph node density polish, especially Shared / Latest / lifecycle presentation.
- Route Sidebar visual hierarchy without changing route commands or Focus/Active semantics.
- Inspector visual hierarchy without changing contextual ownership.
- Spec Dock visual finish while preserving collapsed-by-default Graph dominance.
- Agent, Approval, and Recovery visual consistency without changing behavior.
- Keyboard/focus/ARIA/contrast/reduced-motion/overflow review.
- Desktop acceptance at 1366×768, 1440×900, and 1920×1080.
- Safe deletion of legacy floating-window presentation code after proving it has no production consumers.
- Final screenshots of real deterministic product states for human visual review.

### Explicitly out of scope

- Backend changes.
- Agent Runtime changes.
- API contract changes.
- Database migrations.
- New product modules or top-level regions.
- New comments, collaboration, notifications, research, notes, design tabs, sharing, command palette, account logic, analytics, personas, or dashboards.
- Mobile-first redesign.
- New animation system.
- New fonts or dependency additions unless separately approved.
- Reworking Graph projection, recovery semantics, approval semantics, Focus/Active semantics, or Spec ownership.

## Top-Level Information Architecture Is Frozen

The workspace continues to expose only these five top-level concepts:

1. Routes
2. Graph
3. Inspector
4. Agent State
5. Spec

No new permanent panel, tab family, status console, or floating window may be introduced.

## Visual Direction

The target is a professional AI/product-design workspace rather than an engineering console.

### Hierarchy

- Graph is visually dominant.
- Sidebars are quieter than the center canvas.
- Selected context is clear without heavy borders or badge stacks.
- Text hierarchy should do more work than colored chips.
- Secondary metadata stays visually subordinate.
- The current answerable node is the strongest object on the canvas; historical nodes stay compact.

### Surfaces

- Use restrained neutral surfaces.
- Prefer one subtle boundary system over multiple competing card borders.
- Avoid nested-card-on-card visual noise.
- Shadows, if used, must be subtle and functional.
- Do not introduce decorative gradients or dashboard chrome.

### Status color

Semantic color must be restrained and never be the only carrier of meaning.

- Active/current states: clear but not saturated.
- Warning/recovery: noticeable without dominating the workspace.
- Destructive actions: reserved for explicit destructive contexts.
- Shared/Latest/lifecycle markers: compact and low-noise.

## Graph Final Polish

No Graph capability changes are allowed.

### Historical nodes

- Keep compact dimensions and readable title hierarchy.
- Q number remains easy to scan.
- `Latest` remains obvious without becoming a large badge.
- Shared historical nodes summarize membership as `共享 · N 条路线`.
- Avoid more than two simultaneous high-emphasis markers on a compact node.

### Current answerable node

- Remains expanded.
- Answer options/free text remain in Graph, never duplicated in Inspector.
- Submission states remain clear.
- Input persistence and generation behavior remain unchanged.

### Selection / focus

Different concepts must remain visually distinguishable:

- selected node
- Focus Route context
- Runtime Active Route
- pending/generating node

Do not collapse these states into one generic accent treatment.

### Toolbar / canvas

- Keep the existing compact toolbar hierarchy.
- Do not add commands.
- Ensure icon-only controls have labels, focus-visible state, and comfortable hit areas.
- Pan/zoom/fit and relation interactions must remain unobstructed at all target desktop sizes.

## Route Sidebar Final Polish

Route rows should answer, at a glance:

- what route this is
- whether it is the current reading Focus
- whether it is the Runtime Active route when relevant
- its lifecycle/display state when relevant

Low-frequency commands remain in overflow.

Rules:

- never show UUID fragments as labels
- never visually imply Focus == Active
- do not turn every route state into a badge
- long labels truncate safely and remain discoverable through accessible text/title behavior

## Inspector Final Polish

Inspector remains one contextual surface.

### Selected node

Priority stays:

1. identity/context
2. content/question
3. answer
4. contextual AI / proposal when present
5. claims/relations summary
6. secondary technical details

### No selection

Project Summary remains lightweight. Requirement State stays a secondary view.

### Technical details

UUIDs, run ids, raw relation ids, claim kind/confidence, and other engineering metadata remain behind explicit disclosure and must not leak into the default surface.

## Spec Dock Final Polish

Behavior is frozen:

- collapsed by default
- collapsed height approximately 48 px
- expanded center-region height approximately 40%, never above 45%
- Graph remains visible above it
- reading follows Focus/sole route semantics
- generation targets Runtime Active Route
- mismatch remains explicit

Polish goals:

- collapsed summary should read as a calm status/control bar, not a sixth panel
- expanded content should have clear section rhythm and readable long-form text
- version/history controls stay compact
- provenance remains secondary
- overflow must not create horizontal page scrolling

## Agent / Approval / Recovery Final Polish

Existing behavior remains authoritative.

### Agent

- one current product-facing status only
- no raw phase leakage
- no duplicate progress surfaces

### Approval

- one visible accept/reject control pair
- readable action label
- no fabricated impact information
- no hidden test-only production controls

### Recovery

- one recovery notice at a time
- one primary safe CTA at most
- unknown outcome continues to outrank resubmit
- answer-saved continuation failure never asks the user to answer again

Visual polish may change spacing, typography, borders, or iconography, but not decision semantics.

## Accessibility / Interaction Quality

The final pass must explicitly audit:

- full keyboard reachability for interactive controls
- visible `:focus-visible` states
- correct labels for icon-only buttons
- logical focus movement when secondary Inspector views change
- no color-only status meaning
- acceptable text/background contrast
- reduced-motion behavior for transitions
- scroll containers usable with keyboard
- no focus traps in native details/dialog flows
- no duplicated interactive elements hidden only for tests
- no clipped controls at sidebar min/max widths

## Desktop Geometry Acceptance

Validate all three viewports:

- 1366×768
- 1440×900
- 1920×1080

For each viewport verify:

- Graph is the largest work surface with Spec collapsed.
- Left and right sidebars do not overlap the center.
- Sidebar min/max widths do not break Graph interaction.
- Current question is reachable and answerable.
- Inspector scrolls internally rather than expanding the page unexpectedly.
- Spec expansion keeps Graph visible and usable.
- No horizontal page overflow.
- Menus/dialogs remain inside the usable viewport.

## Legacy Cleanup

The first two slices intentionally left floating-window infrastructure in the repository. This slice may remove it only after proving it is dead production code.

Candidate legacy artifacts:

- `frontend/src/components/workspace/FloatingWindow.vue`
- `frontend/src/components/workspace/RouteNavigator.vue`
- `frontend/src/graph/floatingWindowLayout.ts`
- tests dedicated only to those removed presentation units
- `FloatingWindowPreference`
- `WorkspaceUiPreferencesV2`
- `DEFAULT_WORKSPACE_UI_V2`
- `FLOATING_WINDOW_RANGES`
- V2 floating-window localStorage load/save helpers
- `graphUiStore.floatingWindows`
- `graphUiStore.windowZOrder`
- `setFloatingWindow`
- `bringWindowToFront`
- `resetWindows`
- `persistWorkspaceV2`

### Cleanup gate

Before deleting any candidate:

1. search the current branch for production imports/references;
2. confirm remaining references are only tests/docs/other legacy candidates;
3. confirm current fixed-sidebar behavior uses only `WorkspaceUiPreferencesV1` sidebar open/width persistence;
4. add/update tests for the surviving fixed-sidebar persistence path;
5. delete the legacy code;
6. run typecheck/unit/build before proceeding.

If any candidate still has a real product consumer, keep it and report why. Do not force deletion for cleanliness.

Stale browser `spec-agent.workspace-ui.v2` localStorage data may simply become ignored; no migration is required because it is browser-only presentation state and the current fixed-sidebar state lives under the V1 sidebar preference path.

## Skill Usage

The installed ZCode skills are helpers, not product authorities.

### `frontend-design`

Invoke only after functional behavior and baseline tests are green.

Allowed influence:

- typography
- spacing
- hierarchy
- surfaces
- compactness
- hover/selected/focus treatment
- restrained interaction polish

Forbidden influence:

- new features
- new top-level navigation
- new panels
- new product states
- new backend assumptions

### `web-design-guidelines`

Invoke after visual polish as a review pass.

Use it to audit accessibility, focus, forms, motion, overflow, responsive desktop behavior, and general interface quality. Fix only findings inside this frozen scope.

Priority order remains:

`UI_SCOPE_FREEZE / this design / real product semantics > installed design skills > aesthetic preference`.

## Testing Strategy

Use TDD for behavior-affecting changes and focused tests for presentation contracts.

Required coverage:

- Graph node hierarchy/Shared density
- Route row Focus vs Active presentation
- Inspector default/selected/secondary states
- Spec Dock collapsed/expanded layout
- Agent single-status contract
- Approval single-control-pair contract
- Recovery single-safe-CTA contract
- fixed-sidebar preference persistence after legacy cleanup
- keyboard/focus behavior for newly adjusted controls
- reduced-motion CSS behavior where transitions exist
- desktop E2E at all three target sizes

Final commands:

```text
npm test
npm run typecheck
npm run build
npm run test:e2e
```

No source change may be justified solely by making a test easier. Tests must follow the product DOM.

## Visual Artifact Acceptance

This slice is not complete with test output alone.

After all tests pass, run the deterministic product flow and capture real screenshots without adding screenshot-only UI code.

Required 1440×900 screenshots:

1. default Graph-first workspace with Spec collapsed
2. selected historical/current node with Inspector visible
3. Spec Dock expanded while Graph remains visible
4. Requirement State secondary Inspector view

Also capture one 1366×768 screenshot with Spec expanded to prove the smallest target remains usable.

Store screenshots in an ignored/local test artifact directory such as `frontend/test-results/ui-final/`; do not commit binary screenshots unless separately requested.

The final execution report must list exact screenshot paths so the user can inspect the finished product.

## Definition of Done

The third slice is complete only when:

- Graph is unmistakably the workspace subject.
- The UI looks intentionally finished rather than merely rearranged.
- Functionality remains complete but contextual and low-noise.
- Focus Route and Runtime Active Route remain unmistakably separate.
- Default UI contains no raw runtime/UUID noise.
- Approval and Recovery remain semantically safe.
- All target desktop geometries work.
- Accessibility review has no known blocking issue inside scope.
- Clearly dead floating-window code is removed, or explicitly retained with evidence of a real consumer.
- Backend/Agent Runtime/API/migrations are untouched.
- Unit/typecheck/build/E2E are green.
- Real screenshots are produced for human product review.

# Product Convergence, OpenCode Productization, and Phase 8 Hardening Design

Status: approved in-chat, self-reviewed, ready for implementation planning  
Date: 2026-08-19

## 1. Purpose and phase framing

This design closes three related gaps in one coordinated delivery:

1. **Phase 7 corrective closure** — fix Graph Workspace behavior and presentation problems found during manual acceptance.
2. **OpenCode productization** — turn the real OpenCode gateway delivered in Phase 5 into the normal user-facing product path.
3. **Phase 8 CI / hardening** — add the CI and architecture/scope hardening explicitly deferred by the Phase 7 closeout.

Phase numbering remains accurate:

- Phase 5 delivered the real OpenCode gateway and live runtime smokes.
- Phase 7 delivered the frontend and graph-first workspace.
- Phase 8 is CI / hardening, not the original OpenCode integration.
- This work productizes the existing OpenCode capability while also beginning and closing the intended Phase 8 hardening scope.

This document supersedes affected model-configuration and graph-interaction portions of prior Phase 7 designs. Existing lineage/history ownership, answer immutability, ContextSnapshot isolation, and derived-Spec rules remain authoritative unless explicitly revised here.

## 2. Non-negotiable runtime doctrine

```text
Model proposes.
Runtime validates.
Runtime persists.
Runtime owns history.

Context is lineage, not global chat history.
```

Therefore:

- Models never own route lifecycle, canonical identity, source-of-truth history, or persistence.
- Frontend code never guesses canonical history or silently chooses a route when the user's reading context is ambiguous.
- `SpecSnapshot` remains derived output, not source of truth.
- Model input is a frozen lineage-based context plus explicit run-local `taskInput`.
- Sibling routes, excluded answers/patches, old replacement subtrees, unrelated SpecSnapshots, and global chat history never leak into a run merely because they exist in the Project.

## 3. Scope and non-goals

### In scope

- Global OpenCode settings in product UI.
- User-supplied OpenCode API key stored in the local product database.
- Dynamic discovery of currently available `-free` models.
- Explicit global selected free model, effective without restart.
- Real OpenCode as the only normal production model path.
- Fake/scripted gateways retained only as deterministic test infrastructure.
- Rename/refactor `FakeAgentOrchestrator` to production-neutral `AgentOrchestrator` (or equivalent) without redesigning orchestration semantics.
- Model-powered `换一个问题` using the generic DRAFT_NODE capability.
- Active / Focus / Visibility corrections and removal of repeated route pickers.
- Shared-node reading-route selector bound to Focus.
- Reveal-new-node behavior without relayout.
- Pending-node sizing/scroll correction.
- Friendly route names and reduced UUID-first UI.
- Subtle replacement provenance; detailed provenance in Inspector.
- Spec source-reference presentation dedupe.
- Runtime lifecycle-transition guards.
- CI, architecture checks, anti-overfitting guards, deterministic regression, and real-provider release acceptance.

### Not in scope

- Multi-provider registry/router/ranking/fallback.
- Paid-model selection or cost optimization.
- Per-Project provider settings.
- Multi-user credential isolation / RBAC.
- Cloud secret manager or a separate master-key subsystem.
- Automatic retry, JSON repair, parser relaxation, automatic model switching, or Fake fallback.
- Route merge.
- Domain-specific runtime engines or domain-specific model adapters/prompts.

## 4. Global OpenCode product settings

### 4.1 Product model

OpenCode configuration is installation-global:

```text
OpenCodeSettings
- apiKey
- maskedSuffix
- selectedModel
- updatedAt
```

The first version is a single-user/local product. PostgreSQL is part of the trusted local application boundary. The user's OpenCode API key is stored as a retrievable database value so the backend can call OpenCode. This design deliberately does **not** require `SPEC_AGENT_CREDENTIAL_MASTER_KEY` or a second application-level root key.

Security boundaries still hold:

- full API key never returns from status/read APIs;
- full API key never appears in logs, AgentRun traces, model context, prompts, or public error payloads;
- UI only shows a masked suffix;
- real credentials are never committed to Git.

### 4.2 UI

Application-level entry:

```text
设置
└─ 模型设置
   └─ OpenCode
      API Key [................]
      [验证并获取模型]

      可用模型 [ current-free-model ▾ ]
      [保存]
```

The UI must not imply that the credential belongs to the currently open Project.

### 4.3 Two-step validation, atomic activation

**Probe**:

```text
user enters temporary key
→ backend uses that key only in memory for the request
→ credential validation + dynamic GET /models
→ filter supported `-free` models
→ return free-model list
→ no settings row is changed
```

**Save**:

```text
user explicitly selects a free model
→ backend re-validates the candidate key/configuration
→ backend re-checks that selectedModel is still in the current allowed free-model set
→ apiKey + maskedSuffix + selectedModel are persisted/activated together
```

The browser's earlier probe response is never treated as authoritative at save time. A failed probe/save leaves the previous working settings untouched.

### 4.4 Runtime resolution

The selected model must no longer be locked at bean construction/startup.

Each production model request resolves the current settings:

```text
AgentOrchestrator
→ ModelGateway
→ current OpenCodeSettings
→ apiKey + selectedModel
→ OpenCode transport
```

Changing the model in Settings affects the next request without backend restart.

Normal product behavior must not depend on `SPEC_AGENT_MODEL_GATEWAY=fake` or startup-only `SPEC_AGENT_OPENCODE_MODEL` selection.

## 5. Production vs deterministic test model paths

### 5.1 Production

```text
AgentOrchestrator
→ ModelGateway
→ OpenCodeZenModelGateway
→ OpenCode
```

If OpenCode is unconfigured, model-required commands fail with stable `NOT_CONFIGURED` behavior and a UI route to model settings.

Read/history operations remain usable while unconfigured: open projects, inspect graph/history/spec snapshots, drag nodes, manage Focus/visibility, and perform model-free lifecycle reads/actions where valid.

Model-required operations such as question drafting, answer-processing continuation, `换一个问题`, and spec generation are blocked. There is no silent Fake fallback.

### 5.2 Tests

Scripted/Fake gateways remain because safety tests must deterministically force malformed JSON, wrong actions, fabricated ids, cross-route source leakage, invalid source refs, reflection rejection, and provider failure at exact steps.

These tests prove runtime invariants. They do **not** count as product model acceptance.

### 5.3 Orchestrator naming

`FakeAgentOrchestrator` is already used by real OpenCode flows, so production naming must become neutral (`AgentOrchestrator` or equivalent). Test doubles stay behind `ModelGateway`.

Do not use this rename as an excuse for a broad orchestration rewrite; preserve the existing runtime-controlled sequence and fail-closed behavior.

## 6. Generic model-powered `换一个问题`

### 6.1 User interaction

Normal product action becomes `换一个问题`.

The user sees only one natural-language direction field:

```text
你接下来更想澄清哪个方面？
也可以直接说说你目前最关心的需求。

[自由输入]

[取消] [生成新问题]
```

The user does not author replacement question/purpose/options or runtime identity.

### 6.2 Reuse DRAFT_NODE; no special-purpose model

Use the existing generic NodeDraft/DRAFT_NODE output contract:

```text
question
purpose
options[{label, impact}]
allowFreeAnswer
```

Initial drafting, normal continuation, and redirected drafting share the same generic capability. Variation is expressed through generic `taskInput` / drafting intent and frozen context — not through domain-specific classes, feature-specific agents, or test-specific prompt wording.

### 6.3 Replacement context

For old target `Q2`:

```text
INCLUDE
- Q2.parent root-to-parent lineage
- effective answers/patches on that parent lineage
- old Q2 question
- old Q2 purpose
- user's run-local direction

EXCLUDE
- Q2 old answer
- Q2 old patch
- Q2 descendants / child subtree
- sibling-route conclusions
- old-route SpecSnapshot-derived content
- global chat history
```

Old question/purpose and user direction are rejection/drafting inputs only; the old target is not part of the new route lineage.

### 6.4 Self-review correction: pre-proposal ContextSnapshot identity

The current deterministic regenerate implementation accepts replacement route/node ids while building regenerate context. That shape must **not** force real-model replacement to create canonical replacement objects before the model succeeds.

For model-powered replacement, the pre-proposal frozen context is anchored to:

```text
sourceRouteId
+ targetNodeId metadata
+ target parent lineage/tip
+ operationType=REGENERATE/REPLACE
+ special/run-local inputs
```

It must not require an already-persisted accepted `replacementRouteId` or `replacementNodeId`.

Replacement ids are generated/persisted only after the model proposal passes parsing/reflection/runtime validation. The completed AgentRun then records produced node/route ids. A failed model run therefore cannot leave a ContextSnapshot that pretends an accepted replacement route/node already exists.

Implementation may preallocate opaque ids in memory if technically useful, but they must not become canonical persisted Node/Route identity before proposal acceptance.

### 6.5 Atomicity

Correct order:

```text
validate explicit source route + target
→ freeze replacement context
→ create/run AgentRun
→ real OpenCode DRAFT_NODE
→ strict structured parse
→ node reflection/runtime validation
→ validate replacement proposal
→ canonical transaction:
     create sibling replacement node
     create replacement route
     update source lifecycle where required
     set Project.activeRouteId
→ mark produced ids / run completion
```

If provider/model/validation fails, canonical Node/Route history and Active remain unchanged.

### 6.6 Structural semantics

Replacement is a sibling:

```text
       old Q2
      /
Q1 ──
      \
       new Q2'  ← current
```

Never `Q1 → old Q2 → new Q2'`.

Successful replacement from OPEN makes the source route SUPERSEDED and the new route OPEN + Active. If the explicit source is already SUPERSEDED, preserve its historical status and create the new OPEN route without a meaningless repeated transition.

Runtime requires a non-blank new question and may reject a conservatively normalized exact textual duplicate of the rejected question. Runtime does not perform domain-specific semantic grading.

## 7. Active, Focus, Visibility, and shared-node reading

These are independent:

```text
Active
= Runtime working route / Project.activeRouteId

Focus
= browser reading + highlight route
= never Activates by itself

Visibility
= browser show/dim/hide/isolate/filter state
= never Activates by itself
```

### 7.1 Default view

All non-deleted routes are visible by default:

- OPEN visible
- SUPERSEDED visible
- ARCHIVED visible
- DELETED hidden by default filter

Focus highlights/read-contextualizes a route and may de-emphasize others but must not hide them.

If single-route viewing remains, it is a distinct `独览此路线` visibility feature. `显示全部` restores non-deleted routes without relayout.

### 7.2 Shared nodes

A shared node exposes an explicit reading route:

```text
当前查看：未选择 ▾
```

or a friendly route name.

Selecting it sets global Focus and updates graph/Inspector projection. It does not set Active.

If multiple route interpretations exist and no Focus is selected, the shared node stays neutral. Do not silently fall back to Active, first route, or latest route.

### 7.3 No repeated source-route picker

Fork, Re-answer, and `换一个问题` derive source from the operated visual node plus its explicit Focus/read context. The frontend still sends explicit `sourceRouteId`; Runtime still validates ownership/lifecycle/node membership.

If a shared node is ambiguous because no reading route is selected, ask the user to select `当前查看` on the node. Do not open another branch-operation route picker.

## 8. Fork, Re-answer, and lifecycle eligibility

### Fork

Fork inherits effective history through the selected node, inclusive of that node's effective answer when present. Old route is unchanged.

Dialog contains only optional friendly route name.

Success: new OPEN + Active + Focus route; reveal new visual route/node; preserve all existing coordinates and other visible routes.

### Re-answer

Re-answer preserves the same canonical Question with a distinct visual route instance. It inherits the target parent prefix exclusive of the old target answer and leaves the new target unanswered.

Success: new OPEN + Active + Focus; reveal the new unanswered instance; preserve old route/answer and all existing positions.

### Lifecycle eligibility

- OPEN: normal valid branch operations.
- SUPERSEDED: historical/read-only route may still be an explicit source for further exploration where semantics allow.
- ARCHIVED: restore before new working mutation.
- DELETED: never a mutation source.

Focus may inspect SUPERSEDED without activating it.

## 9. Graph Workspace corrections

### 9.1 Reveal, never implicit relayout

Fork/Re-answer/Replacement success performs:

```text
canonical Active refresh
→ Focus new route
→ camera reveal/highlight new visual node
```

Reveal is viewport pan/zoom only. Branch operations, activation, Focus, visibility changes, and Show All never mutate existing positions. Only explicit `自动布局` may recompute layout.

### 9.2 Current question sizing

Historical nodes stay compact; answer summaries default to roughly 3–4 visible lines with full detail in Inspector.

Current answerable node grows enough to keep question/options/free-answer/submit controls reachable. Normal content must not create a whole-node vertical scrollbar that clips the submit area. The text input itself may scroll. Only the title bar is draggable.

### 9.3 Replacement provenance

Remove the permanent prominent yellow dashed cross-canvas replacement relation.

Primary graph edges represent lineage. Old node gets a subtle `已替换`; new node may show subtle `替代自：...`. Full supersedes/provenance belongs in Inspector. Any visual provenance edge is weak and selection-only, never lineage-like.

### 9.4 Friendly route names / UUID cleanup

Normal Chinese UI uses readable labels such as:

```text
主路线
分支路线 1
重新回答路线 1
换题路线 1
```

User labels override defaults. Remove `Initial route` and raw UUID-prefix fallback as normal display names.

UUIDs remain available under technical detail for diagnostics, not as primary route/spec identity.

### 9.5 Inspector

Inspector prominently shows `当前查看路线` when Focus/read context exists and prioritizes that route's answer/history/patches. Other route histories may be secondary/collapsed.

Inspector remains detailed/read-only for the current pending node and does not create a second answer surface.

### 9.6 Spec presentation

Spec remains derived and route-scoped. Friendly route names are primary.

Identical source references are presentation-deduped for counts/display while section-level provenance relationships remain preserved. Dedupe is not deletion of provenance.

## 10. Runtime lifecycle hardening

Runtime, not frontend visibility, enforces the transition matrix:

```text
OPEN
├─ archive → ARCHIVED
└─ delete  → DELETED

SUPERSEDED
├─ restore → OPEN
├─ archive → ARCHIVED
└─ delete  → DELETED

ARCHIVED
├─ restore → OPEN
└─ delete  → DELETED

DELETED
└─ restore → OPEN
```

Activation only accepts OPEN.

Illegal/repeated transitions fail explicitly with stable conflict semantics rather than relying on hidden buttons.

No random/implicit activation is introduced when an active route is archived/deleted.

## 11. Error handling and the 429 stop rule

Stable public model/provider categories include:

```text
NOT_CONFIGURED
AUTHENTICATION
RATE_LIMITED
TIMEOUT
CONNECTION
SERVER_ERROR / PROVIDER_UNAVAILABLE
INVALID_RESPONSE
INVALID_MODEL
MODEL_CONTRACT_REJECTED
```

UI shows sanitized product copy, not provider internals or stack traces.

For 429 / RATE_LIMITED:

- no retry loop;
- no automatic model/provider switch;
- no Fake fallback;
- no parser relaxation;
- no prompt change intended only to bypass rate limiting.

During Luna's real acceptance, 429 is a hard stop. Luna reports the operation, selected model, and completed checkpoint, waits for the user to change network segment, then resumes explicitly.

## 12. Anti-overfitting hard requirement

Production code and prompts must remain generic.

Forbidden examples:

```text
if instruction contains "MVP" → ask feature scope
if software project → software generator
MarketingQuestionModel
ReplacementMvpPrompt
EcommerceRequirementAnalyzer
```

Do not change production prompts solely to force one acceptance scenario's expected wording.

Differences are represented as generic data/contracts (`ContextOperationType`, drafting intent/taskInput, RequirementAspect, NodeDraft, RequirementState, QuestionPolicy), not concrete requirement-domain branches.

When real-model behavior is poor, first diagnose provider instability, context construction, generic contract clarity, or validation. Do not immediately hard-code a sentence that makes one smoke test pass.

Architecture/static tests continue enforcing that Runtime packages do not depend on model packages, ContextBuilder never calls an LLM, provider adapters remain requirement-domain neutral, and production code does not introduce concrete domain generators/analyzers.

## 13. Testing strategy

### 13.1 Deterministic safety/regression

Keep scripted/Fake tests for:

- lineage inclusion/exclusion;
- route/source ownership;
- answer immutability;
- replacement proposal atomicity;
- invalid model output fail-closed behavior;
- lifecycle transition matrix;
- source-reference grounding;
- no partial canonical mutation on rejection.

### 13.2 Real OpenCode product acceptance

A release candidate is not accepted solely because deterministic tests pass.

Luna runs from the real UI:

```text
enter user's OpenCode key
→ probe current free models
→ choose one explicitly
→ save settings
→ create/open project
→ real first question
→ answer + real interpretation/patch
→ real next question
→ Fork
→ Re-answer
→ 换一个问题
→ verify route/context isolation
→ generate real SpecSnapshot
→ verify shared-node Focus/read behavior and corrected presentation
```

Real-model automated assertions never require exact model wording. They verify structure and Runtime invariants; human acceptance judges contextual reasonableness.

Over time use several semantically different generic requirements, not a single golden test phrase. Production code must not know the acceptance inputs.

## 14. Phase 8 — CI / hardening

### 14.1 Deterministic CI

Add repository CI for ordinary commits/PRs/main with no external OpenCode credential requirement.

Backend gates:

- compile/test;
- database-backed integration/migration tests as supported by CI;
- architecture tests.

Frontend gates:

- lockfile install;
- typecheck;
- unit tests;
- production build;
- Playwright E2E against the real local backend/test database using deterministic test gateway wiring where external network would make CI nondeterministic.

Repository gates include existing anti-overfitting/architecture rules, secret-safety checks supported by project scripts, and protection against production Fake default/wiring regressions.

CI is a deterministic quality gate, not proof of real-provider usability.

### 14.2 Real-provider release gate

Real OpenCode verification is a separate explicit release/manual gate because it uses the user's credential and can be network/rate-limit sensitive.

Do not put the user's personal API key into ordinary CI.

Existing env-gated live smokes may remain, but final product acceptance additionally exercises the UI-configured settings path. Safe diagnostics may include selected model and masked suffix only.

### 14.3 Phase 8 closure

Phase 8 is complete when:

- deterministic CI gates are installed and green;
- architecture/anti-overfitting rules remain enforced;
- production has no silent Fake path;
- route/context ownership and lifecycle guards remain correct;
- corrected graph UX regressions are covered;
- real OpenCode product acceptance passes, or is explicitly paused only at the agreed RATE_LIMITED stop condition and then resumed after the user changes network.

## 15. Migration

The prior operator-only encrypted credential row/schema was not a delivered user-facing settings workflow. Do not keep the first-version product tied to `SPEC_AGENT_CREDENTIAL_MASTER_KEY` merely for compatibility.

Prefer a clean product-settings migration. Existing development/operator credential residue can be cleared and the user re-enters the API key in the new Settings UI.

Do not keep two long-lived authoritative model configurations (environment-selected model vs database-selected product model) that can silently disagree.

## 16. Implementation order

The implementation plan must follow dependency order:

1. OpenCode settings persistence/API/dynamic runtime resolution.
2. Production-neutral AgentOrchestrator wiring and Fake/scripted test-boundary cleanup.
3. Generic model-powered `换一个问题`, including pre-proposal ContextSnapshot identity correction, context isolation, and atomic canonical commit.
4. Active/Focus/Visibility/shared-node/source-route corrections.
5. Graph reveal/sizing/provenance/friendly-name/Inspector/Spec presentation fixes.
6. Runtime lifecycle transition guards.
7. Phase 8 CI/architecture hardening.
8. Full deterministic regression.
9. Real OpenCode product acceptance through UI.

Do not implement this as a UI-first patch pile while model/runtime semantics remain ambiguous.

## 17. Acceptance criteria

Complete when all are true:

1. User configures their own global OpenCode key from Settings, dynamically receives current free models, explicitly chooses one, saves it, and the next model call uses it without restart.
2. Save revalidates the candidate/current free model server-side; a stale/tampered browser list is not authoritative.
3. Normal product model operations use real OpenCode and never silently fall back to Fake.
4. Unconfigured model-required actions guide to Settings while history remains inspectable.
5. `换一个问题` accepts only a natural-language direction and reuses generic DRAFT_NODE.
6. Replacement context includes parent lineage + old question/purpose + user direction and excludes old target answer/patch/children/siblings/spec/global history.
7. Pre-proposal ContextSnapshot does not require an already-persisted accepted replacement route/node.
8. Failed replacement calls do not mutate canonical Node/Route/Active state.
9. Successful replacement creates a sibling node, preserves old history, activates/focuses/reveals the new route without relayout.
10. Fork/Re-answer/Replacement have no repeated source-route modal; frontend still sends explicit sourceRouteId from explicit visual read context.
11. Shared nodes are neutral without Focus and never silently use Active as reading context.
12. Active, Focus, and Visibility remain independent; Focus neither Activates nor hides other routes.
13. All non-deleted routes are visible by default; isolate/dim/hide are user visibility controls.
14. Branch operations reveal new nodes without changing existing positions.
15. Current answerable nodes keep submit controls reachable without normal-content whole-node scrolling.
16. Replacement provenance is subtle/Inspector-oriented, not a permanent prominent cross-canvas edge.
17. Normal product UI uses friendly localized route names; UUIDs are technical detail.
18. Inspector clearly emphasizes current viewing route and route-specific answer/history.
19. Spec source presentation dedupes identical refs without losing section provenance.
20. Runtime enforces lifecycle transitions and activation constraints.
21. No test/domain-specific production code or prompt overfitting is introduced.
22. Deterministic backend/frontend/E2E regression passes.
23. Phase 8 CI gates are installed and green.
24. Real OpenCode UI acceptance passes; on 429, Luna stops and waits for the user's network change rather than retrying or falling back.

## 18. Summary

The first-version product remains intentionally narrow:

```text
one product
one normal provider path (OpenCode)
one generic model contract family
one deterministic Runtime owner
one lineage-based context model
one graph workspace with explicit Active / Focus / Visibility semantics
```

The user experience is simple: configure OpenCode once, clarify a requirement one question at a time, branch/re-answer/replace without losing history, inspect every route explicitly, and generate a source-backed derived Spec. No provider platform, master-key platform, or domain-specific shortcut is added.
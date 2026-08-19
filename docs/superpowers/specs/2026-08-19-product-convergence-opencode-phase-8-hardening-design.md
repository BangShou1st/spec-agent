# Product Convergence, OpenCode Productization, and Phase 8 Hardening Design

Status: approved in-chat, written for implementation planning  
Date: 2026-08-19

## 1. Purpose

This design closes the gap between the current graph-first product, the real OpenCode runtime that already exists behind operator/test configuration, and the product behavior observed during manual acceptance.

It intentionally combines three closely related work streams in one coordinated implementation plan:

1. **Phase 7 corrective closure** — fix the Graph Workspace interaction and presentation problems found during manual acceptance without changing the established runtime ownership model.
2. **OpenCode productization** — turn the already-integrated Phase 5 OpenCode gateway into the normal user-facing model path: users configure their own OpenCode API key and explicitly select a current free model from the product UI.
3. **Phase 8 CI / hardening** — add the CI and scope/architecture hardening that the Phase 7 closeout documents explicitly deferred.

Phase numbering must remain accurate:

- Real OpenCode gateway integration was delivered in Phase 5.
- Phase 7 delivered the frontend and graph-first workspace.
- Phase 8 is CI / hardening, not the original provider integration.
- This design productizes the existing OpenCode capability while also carrying the work into Phase 8 hardening.

This design supersedes affected interaction/model-configuration portions of prior Phase 7 graph workspace designs. Runtime lineage, history ownership, answer immutability, ContextSnapshot semantics, and SpecSnapshot-derived status remain authoritative unless explicitly changed here.

## 2. Non-negotiable runtime doctrine

The product continues to follow:

```text
Model proposes.
Runtime validates.
Runtime persists.
Runtime owns history.

Context is lineage, not global chat history.
```

Consequences:

- The model never owns route lifecycle, canonical identity, source-of-truth history, or persistence.
- The frontend never guesses canonical history or silently chooses runtime identity.
- `SpecSnapshot` remains derived output, never source of truth.
- Model context is a frozen lineage-based `ContextSnapshot` plus explicit run-local `taskInput`.
- Sibling routes, excluded answers/patches, superseded child subtrees, and unrelated spec output never enter a model call merely because they exist in the project.

## 3. Scope and explicit non-goals

### In scope

- Global OpenCode settings in the product UI.
- User-supplied OpenCode API key stored in the local product database.
- Dynamic discovery of currently available `-free` models.
- Explicit selected free model, effective without application restart.
- Real OpenCode as the only normal product model path.
- Fake/scripted gateways retained only as deterministic test infrastructure.
- Production-neutral `AgentOrchestrator` naming/wiring.
- Model-powered `换一个问题` using the existing generic node-drafting capability.
- Graph Workspace corrections discovered during manual acceptance.
- Route lifecycle transition hardening.
- Friendly route/spec presentation and reduced UUID-first UI.
- CI, architecture checks, anti-overfitting guards, regression gates, and release-oriented real-provider acceptance.

### Not in scope

- Multi-provider platform, registry, routing, ranking, or automatic model switching.
- Paid-model selection or cost optimization.
- Per-project provider settings.
- Multi-user credential isolation or role-based permissions.
- Cloud secret management or a separate master-key system.
- Automatic retry, JSON repair, relaxed parsing, fallback model, or Fake fallback.
- Semantic route merge.
- Domain-specific requirement engines, prompts, or runtime branches.
- Master-key rotation or compatibility migration of old operator-only encrypted credentials.

## 4. Product model settings

### 4.1 Scope

OpenCode settings are **global installation settings**, not Project settings.

Conceptually:

```text
OpenCodeSettings
- apiKey
- maskedSuffix
- selectedModel
- updatedAt
```

The product is a single-user/local first version. The local PostgreSQL database is part of the trusted local application boundary for this version. The user-provided OpenCode API key is stored as a retrievable database credential so the backend can use it for provider calls. No `SPEC_AGENT_CREDENTIAL_MASTER_KEY` or second application-level root-key system is required by this design.

Security constraints remain:

- The complete API key is never returned by normal read/status APIs.
- The complete API key is never logged.
- The complete API key never enters AgentRun trace, ContextSnapshot model input, prompt content, or exception payloads shown to the frontend.
- UI status uses only a masked form such as `••••abcd`.
- Real secrets are never committed to the repository.

### 4.2 User experience

An application-level Settings entry provides:

```text
设置
└─ 模型设置
   └─ OpenCode
      API Key [................]
      [验证并获取模型]

      可用模型 [ current-free-model ▾ ]
      [保存]
```

The settings UI is global and must not visually imply that the credential belongs to the currently open Project.

### 4.3 Two-step validation, one atomic save

Step 1: probe.

```text
user enters temporary API key
→ backend validates credential availability
→ backend dynamically discovers current models
→ backend filters to supported `-free` models
→ frontend receives the free-model list
→ nothing is persisted
```

Step 2: save.

```text
user explicitly selects one returned free model
→ backend validates the candidate settings
→ apiKey + maskedSuffix + selectedModel become active together
→ one transaction persists the settings aggregate
```

A failed probe or failed save never damages a previously working configuration.

### 4.4 Runtime resolution

The selected model must no longer be constructor/startup-only configuration.

For each production model request:

```text
AgentOrchestrator
→ ModelGateway
→ OpenCode settings resolver
→ current apiKey + selectedModel
→ validate selectedModel remains a supported free model identifier
→ OpenCode transport
```

A user can change the selected free model and the next model request uses the new value without backend restart.

Normal product behavior must not depend on `SPEC_AGENT_MODEL_GATEWAY=fake` or `SPEC_AGENT_OPENCODE_MODEL`.

## 5. Production model path and test model path

### 5.1 Product

Normal product execution has one model path:

```text
AgentOrchestrator
→ ModelGateway
→ OpenCodeZenModelGateway
→ OpenCode
```

If OpenCode is not configured, model-required commands fail with a stable product error and a UI action leading to model settings.

Read-only/product-history capabilities remain available while unconfigured:

- open projects
- inspect routes/nodes/history
- drag nodes
- focus/dim/hide/show routes
- inspect existing requirement state/spec snapshots
- lifecycle actions that do not require a model

Commands that require a model are blocked when unconfigured, including drafting questions, answer-processing continuation, model-powered replacement, and spec generation.

There is no silent Fake fallback.

### 5.2 Tests

Fake/scripted model infrastructure remains because deterministic safety tests must be able to force invalid model behaviors, including:

- malformed JSON
- wrong action
- missing fields
- illegal enums
- fabricated identifiers
- sibling-route source leakage
- invalid source references
- reflection rejection
- provider failure at specific steps

These tests prove runtime invariants; they do not prove product usability.

Product acceptance requires real OpenCode execution.

### 5.3 Naming cleanup

`FakeAgentOrchestrator` is production-neutral orchestration in practice and must be renamed/refactored to `AgentOrchestrator` (or an equivalent production-neutral name). Fake/scripted behavior belongs at the `ModelGateway` test-double boundary, not in the orchestration type name.

No broad orchestrator rewrite is intended: preserve the existing runtime-controlled sequence and failure semantics while removing the stale Fake concept from production naming/wiring.

## 6. Model-powered `换一个问题`

### 6.1 Product interaction

The deterministic author-a-replacement form is removed from the normal product path.

A historical eligible node offers:

```text
换一个问题
```

Dialog copy:

```text
你接下来更想澄清哪个方面？
也可以直接说说你目前最关心的需求。

[自由输入]

[取消] [生成新问题]
```

The user supplies only a natural-language direction. The user does not author `replacementQuestion`, `replacementPurpose`, replacement options, or runtime identity.

### 6.2 Reuse the generic node-drafting capability

Do not create a domain-specific or replacement-specific model implementation.

The existing generic DRAFT_NODE/NodeDraft contract remains the model capability:

```text
question
purpose
options[{label, impact}]
allowFreeAnswer
```

Variation is expressed as run-local drafting intent/task input, not requirement-domain code branches and not test-specific prompt text.

The model contract must stay generic enough to serve initial drafting, normal continuation, and redirected drafting from a rejected question.

### 6.3 Regenerate/replacement context

For target node `Q2`, replacement context includes only:

```text
YES: Q2.parent root-to-parent lineage
YES: effective answers/patches on that parent lineage
YES: old Q2 question text
YES: old Q2 purpose
YES: user's run-local direction

NO: Q2 old answer
NO: Q2 old answer patch
NO: Q2 descendants / child subtree
NO: sibling route conclusions
NO: old route SpecSnapshot-derived content
NO: global chat history
```

The old question/purpose and the user direction are rejection/drafting inputs; they do not make the old target node part of the replacement lineage.

### 6.4 Atomicity and failure semantics

A provider/model failure must leave canonical route history unchanged.

Order:

```text
validate source route + target node
→ freeze replacement context
→ create/run AgentRun
→ call real OpenCode DRAFT_NODE
→ strict structured parse
→ node reflection/validation
→ validate replacement proposal
→ only then commit canonical replacement mutation
```

Canonical commit creates the sibling replacement and route state transition together.

Before proposal acceptance, do **not** supersede the source route and do **not** create a canonical replacement node/route that users can observe.

If OpenCode returns 429, invalid JSON, wrong action, invalid contract, or rejected node output:

```text
AgentRun fails
old route unchanged
old node unchanged
active route unchanged
no replacement route/node persisted as accepted canonical history
```

### 6.5 Structural semantics

Replacement is a sibling at the same logical depth:

```text
       old Q2
      /
Q1 ──
      \
       new Q2'  ← active replacement
```

It is never `Q1 → old Q2 → new Q2'`.

For an OPEN source route, successful replacement makes the old route SUPERSEDED and the new route OPEN + Active.

For a source route already SUPERSEDED, the existing historical lifecycle remains valid; replacement creates a new OPEN route without performing nonsensical duplicate lifecycle transitions.

The replacement question must at minimum be non-blank and not textually identical to the rejected question after conservative normalization. Runtime does not perform domain-specific semantic grading of whether two differently worded questions are "good enough" replacements.

## 7. Active, Focus, and Visibility

These concepts are independent and must never be conflated:

```text
Active
= runtime working route (`Project.activeRouteId`)
= where the next answer/draft operation belongs

Focus
= browser/UI reading and highlighting route
= does not change Active

Visibility
= browser/UI show/dim/hide/filters
= does not change Active
```

### 7.1 Default visibility

By default, all non-deleted routes are visible:

- OPEN visible
- SUPERSEDED visible
- ARCHIVED visible
- DELETED filtered from the normal canvas by default

Focus highlights one route and may visually de-emphasize others, but does not hide them.

If single-route viewing remains useful, expose it as a separate `独览此路线` visibility control. Do not overload `聚焦` with "only show this route" semantics.

`显示全部` clears browser-only isolate/manual hide/manual dim state and restores all non-deleted routes without relayout.

### 7.2 Shared nodes and reading context

There must not be a second hidden node-local route context independent of Focus.

For a shared node:

```text
当前查看：未选择 ▾
```

or

```text
当前查看：分支路线 2 ▾
```

Selecting a route sets global Focus and updates the node/Inspector projection. It never Activates the route.

If a shared node has multiple route interpretations and Focus is unset, the node remains neutral. It must not silently fall back to Active, first route, latest route, or any other guessed source.

### 7.3 Source route for branch operations

Fork, Re-answer, and `换一个问题` no longer display a repeated source-route picker.

The frontend resolves source from the operated visual node plus its explicit reading/Focus context and still sends an explicit `sourceRouteId` to the backend/runtime command.

The runtime continues validating project ownership, route lifecycle, and target-node membership. Frontend convenience never replaces runtime validation.

If a shared node is ambiguous because no Focus/read route has been selected, the product asks the user to choose `当前查看` on the node rather than opening a second branch-operation picker.

## 8. Fork, Re-answer, and historical-route behavior

### 8.1 Fork

Fork accepts the source route's effective history through the selected node, inclusive of that node's effective answer when present. The old route is unchanged.

Fork dialog contains only an optional friendly route name; source selection is not repeated.

Successful Fork:

- new route OPEN
- new route Active
- new route Focus
- new route/node revealed in the viewport
- other routes remain visible
- existing node coordinates remain unchanged

### 8.2 Re-answer

Re-answer preserves the same canonical Question but creates a distinct visual route instance whose target node is unanswered.

It inherits the target parent prefix **exclusive of the target's old answer**.

Successful Re-answer:

- new route OPEN + Active
- Focus moves to new route
- new unanswered visual instance is revealed
- old route and old answer remain visible/inspectable
- no existing node relayout

### 8.3 Lifecycle eligibility

- OPEN routes: normal branch operations allowed where otherwise valid.
- SUPERSEDED routes: read-only historical routes that may still be used as explicit sources for further exploration where the operation semantics permit.
- ARCHIVED routes: require explicit restore before new working mutation.
- DELETED routes: not valid mutation sources.

Focus may point to SUPERSEDED routes for inspection without activating them.

## 9. Graph Workspace corrections

### 9.1 Reveal without relayout

After Fork, Re-answer, or replacement success:

```text
set canonical Active
→ set browser Focus
→ reveal/highlight new visual node
```

Reveal means camera pan/zoom only. It must not mutate persisted/browser node coordinates.

Branch creation, activation, Focus, show-all, and route visibility changes must not rearrange existing nodes.

Only an explicit user `自动布局` action may recalculate node positions.

### 9.2 Question-node sizing

Historical nodes remain compact.

- answers default to approximately 3–4 visible lines and may be truncated for compact graph reading
- full history belongs in Inspector

The current pending/answerable node gets more space and must keep its answer controls reachable.

Normal pending-node content must not produce an internal whole-node vertical scrollbar that clips the submit area. A free-text input can scroll internally; the graph node itself should grow to a reasonable bounded size and delegate unusually long content to Inspector rather than become a miniature webpage.

Only the node title bar remains the drag handle.

### 9.3 Replacement provenance presentation

Do not show a permanent prominent yellow dashed cross-canvas "replacement" relation that can be confused with lineage.

Primary graph topology renders lineage only:

```text
       old Q2 [已替换]
      /
Q1 ──
      \
       new Q2' [当前]
```

The new node may show a subtle `替代自：...` cue. Detailed supersedes/provenance information belongs in Inspector.

If a replacement relation is ever drawn, it is weak and conditional (for example, selected-node inspection), never a permanent lineage-like edge.

## 10. Friendly route and Spec presentation

Normal product UI must not be UUID-first.

Default route labels are localized and readable, for example:

```text
主路线
分支路线 1
分支路线 2
重新回答路线 1
换题路线 1
```

User-supplied route labels take precedence.

`Initial route` and raw UUID-prefix fallbacks are removed from normal Chinese UI.

Full technical IDs remain available in Inspector/technical detail for diagnostics.

Spec UI displays friendly route names as the primary route identity. Technical route/node/run/context IDs are subdued technical details.

Identical duplicate source references are deduplicated for presentation/counting, while section-level provenance associations remain intact. Presentation dedupe must not erase legitimate source relationships.

## 11. Inspector reading model

Inspector becomes the explicit detailed reading surface rather than duplicating the graph's interactive answer controls.

For a selected shared node, Inspector prominently states the current reading route when Focus exists.

Current/Focus route answer/history is visually prioritized. Other route-specific answers/history may be available in collapsed secondary sections.

Inspector may expose:

- question/purpose/options
- current viewing route
- effective answer for that route
- accepted patches/claims
- lifecycle/read provenance
- supersedes/replaced-by metadata
- AgentRun / ContextSnapshot / technical IDs in a technical section

Inspector never introduces a second answer submission surface for the current pending node.

## 12. Route lifecycle runtime hardening

Frontend action visibility is not the authority. Runtime enforces the lifecycle matrix.

Allowed transitions:

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

Activation is allowed only for OPEN.

Illegal/repeated lifecycle commands fail explicitly (prefer stable conflict semantics) rather than succeeding accidentally because the frontend normally hides the button.

Archive/delete of the active route continues to respect the canonical active pointer rules; no implicit random route activation is introduced.

## 13. Error handling

Provider/model errors map to stable public categories/copy. Product UI does not show stack traces or provider transport internals.

Expected categories include:

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

### 13.1 429 stop rule

A 429 / RATE_LIMITED response has special acceptance behavior:

- no automatic retry
- no model switch
- no provider switch
- no Fake fallback
- no parser relaxation
- no prompt mutation intended merely to bypass the rate limit

During Luna's real-product acceptance, RATE_LIMITED is an explicit stop condition. Luna reports the operation, selected model, and completed verification point, then waits for the user to change network segment before continuing.

## 14. Anti-overfitting hard requirement

Anti-overfitting is a design constraint, not only a review preference.

Production code must not branch on concrete requirement domains or acceptance-test phrases.

Forbidden examples:

```text
if user text contains "MVP" → ask feature-scope question
if software project → use software generator
MarketingQuestionModel
ReplacementMvpPrompt
EcommerceRequirementAnalyzer
```

Prompt changes must also be general. Do not change production prompts solely to force a particular expected question from one smoke scenario.

Allowed differences are expressed as generic data/contracts such as:

```text
ContextOperationType
DraftingIntent / taskInput
RequirementAspect
NodeDraft
AnswerInterpretation
RequirementState
QuestionPolicy
```

The first diagnostic question for a real-model failure is whether the problem is provider instability, context construction, generic contract clarity, or validation — not "what hard-coded prompt sentence will make this test pass?"

Architecture tests/static scans should preserve existing rules that Runtime packages do not depend on model packages, ContextBuilder does not call an LLM, provider adapters remain requirement-domain neutral, and production code does not introduce concrete domain generators/analyzers.

## 15. Testing strategy

### 15.1 Deterministic runtime tests

Keep unit/integration tests for deterministic invariants and adversarial model responses.

These tests may use scripted/fake gateways but must not be used as evidence that the product's model UX works.

They cover:

- context lineage inclusion/exclusion
- route/source ownership
- answer immutability
- replacement atomicity
- invalid model output fail-closed behavior
- route lifecycle transitions
- source-reference validation
- no partial canonical mutation on rejected proposals

### 15.2 Real OpenCode product acceptance

A release candidate is not accepted solely because Fake/scripted tests pass.

Luna performs a real product flow starting from the frontend model settings UI:

```text
configure user's OpenCode API key
→ probe current free models
→ explicitly choose a free model
→ save global settings
→ create/open project
→ draft real first question
→ answer and process through real model
→ generate real next question
→ Fork
→ Re-answer
→ 换一个问题
→ verify route/context isolation
→ generate real SpecSnapshot
→ inspect Focus/shared-node behavior and presentation
```

Real-model tests do not assert exact wording. They assert structural contracts and runtime invariants; human/product acceptance evaluates whether the generated questions/spec are contextually reasonable.

Use several semantically different generic requirements over time rather than one hard-coded "golden" domain scenario. Production code must not know test prompts.

## 16. Phase 8 — CI / hardening

Phase 8 begins as part of this convergence effort after the architecture/product corrections are planned.

### 16.1 CI baseline

Add repository CI for ordinary commits/PRs/main that runs without external model credentials:

Backend gates:

- compile/test
- database-backed integration tests as supported by CI environment
- architecture tests
- migration validation

Frontend gates:

- dependency install from lockfile
- TypeScript/typecheck
- unit tests
- production build
- Playwright E2E against the real local backend and test database using deterministic test gateway wiring only where external model access would make CI nondeterministic

Repository gates:

- formatting/diff cleanliness where project scripts support it
- no committed secrets
- anti-overfitting/domain-keyword architecture checks already defined by project policy
- no production Fake gateway default or accidental production dependency on test doubles

CI is a deterministic quality gate, not the real-provider acceptance proof.

### 16.2 Real-provider release verification

Real OpenCode verification remains a separate explicit release/manual gate because it requires the user's credential and is sensitive to provider/network rate limits.

Do not place the user's personal API key into ordinary CI.

The existing env-gated provider smoke coverage may remain useful, but product acceptance must additionally exercise the actual UI-configured settings path. A successful live test records only safe diagnostics such as selected model and masked suffix; no full key, raw prompt, or raw model output is persisted/logged unless a separately approved debugging design changes that rule.

### 16.3 Hardening closure

Phase 8 acceptance includes:

- CI green on deterministic suites
- real OpenCode product acceptance completed (or explicitly paused only by the agreed RATE_LIMITED stop rule)
- architecture/anti-overfitting rules still enforced
- no Fake fallback in production
- no route/context ownership regression
- no UUID-first normal product regressions
- no unvalidated lifecycle transition path
- docs updated to reflect product model settings and the final phase status

## 17. Migration and compatibility

The prior Phase 5 operator-only encrypted credential row/schema was never a user-facing settings workflow. This design does not require carrying the old `SPEC_AGENT_CREDENTIAL_MASTER_KEY` model forward.

Prefer a clean product settings migration rather than a compatibility layer that decrypts/re-encrypts old operator data.

For development environments with old operator credentials, the supported path is to clear/recreate the product setting and have the user enter their API key through the new UI.

Do not create long-lived dual configuration where environment-selected model and database-selected product model can silently disagree.

## 18. Implementation ordering constraints

The implementation plan should preserve dependency order:

1. Product OpenCode settings persistence/API/runtime resolution.
2. Production-neutral AgentOrchestrator wiring; Fake/scripted test boundary cleanup.
3. Model-powered `换一个问题` with context isolation and atomic commit.
4. Active/Focus/Visibility/shared-node/source-route interaction corrections.
5. Graph reveal/sizing/provenance/friendly-name/Inspector/Spec presentation fixes.
6. Runtime lifecycle transition guards.
7. Phase 8 CI and architecture hardening.
8. Full deterministic regression.
9. Real OpenCode product acceptance through the UI.

Do not reorder this into a UI-first patch pile where model/runtime semantics remain ambiguous.

## 19. Acceptance criteria

This convergence is complete when all of the following hold:

1. A user can open global Settings, enter their own OpenCode API key, dynamically retrieve current free models, select one explicitly, and save the settings without restart.
2. Normal product model operations use real OpenCode and never silently fall back to Fake.
3. Unconfigured model-required actions guide the user to model settings while read-only history remains usable.
4. `换一个问题` accepts only a natural-language direction from the user and uses the generic DRAFT_NODE capability.
5. Replacement context contains parent lineage + old question/purpose + user direction and excludes old target answer/patch/children/sibling conclusions/spec output.
6. Failed replacement model calls do not mutate canonical route/node history.
7. Successful replacement creates a sibling question at the same logical depth, preserves the old route, and activates/focuses/reveals the new route without relayout.
8. Fork/Re-answer/Replacement do not show a repeated source-route picker; they use the visual node's explicit Focus/read route and still send an explicit runtime sourceRouteId.
9. Shared nodes are neutral when no Focus/read route is selected; they never silently fall back to Active.
10. Active, Focus, and Visibility remain separate; Focus does not hide other routes and does not Activate.
11. All non-deleted routes are visible by default; single-route viewing, dim, and hide are user-only visibility controls.
12. Branch operations reveal new nodes without changing existing positions.
13. Current answerable nodes no longer suffer normal-content whole-node scrolling that clips submission controls.
14. Replacement provenance is subtle/Inspector-oriented rather than a permanent prominent cross-canvas edge.
15. Normal UI uses readable localized route names; UUIDs move to technical detail.
16. Inspector clearly emphasizes the current viewing route and its route-specific answer/history.
17. Spec source presentation deduplicates identical references without losing section provenance.
18. Runtime enforces the lifecycle transition matrix, not merely the frontend.
19. Anti-overfitting architecture rules remain green and no feature/test-specific production branches/prompts are introduced.
20. Deterministic unit/integration/E2E suites pass.
21. Phase 8 CI gates are installed and green.
22. Real OpenCode product acceptance passes through the UI; if OpenCode returns 429, testing stops and waits for the user's network change rather than retrying/falling back.

## 20. Summary

The first-version product should feel simple even though the runtime remains strict:

```text
user configures OpenCode once
→ creates/opens a requirement project
→ answers one focused question at a time
→ explores visible branch history without losing context
→ can fork, re-answer, or ask for a different question
→ every route stays isolated and inspectable
→ generated Spec remains source-backed and derived
```

Internally, the design deliberately stays narrow:

```text
one product
one normal provider path (OpenCode)
one generic agent contract family
one deterministic runtime owner
one lineage-based context model
one graph workspace with explicit Active / Focus / Visibility semantics
```

No model/provider platform and no domain-specific shortcuts are introduced.
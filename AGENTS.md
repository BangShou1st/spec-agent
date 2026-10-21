# AI Development Instructions

This repository is expected to be developed with AI assistance. AI coding agents are useful here, but they are also likely to overfit implementation to concrete examples. These instructions are mandatory for future development.

## 1. Read Before Changing Code

Before implementing or modifying behavior, read:

1. `README.md`
2. `docs/PRODUCT_SPEC.md`
3. `docs/ARCHITECTURE.md`
4. `docs/AGENT_RUNTIME.md`
5. `docs/CONTEXT_RULES.md`
6. `docs/MODEL_GATEWAY.md`
7. `docs/DEVELOPMENT_ENVIRONMENT.md`
8. For V2 work: `docs/v2/README.md` and the canonical documents it lists.
9. For Global Assistant LangChain redesign work: `docs/v2/GLOBAL_ASSISTANT_LANGCHAIN_REDESIGN.md` (target design, staged migration and new-session handoff; V1 baseline remains applicable until each cutover).
10. For retrieval/embedding work: `docs/v2/UNIFIED_PYTHON_RAG_DESIGN.md` (latest target: shared Python RAG/local Ollama for both Agents; Java retains fact, scope and storage ownership; do not add a parallel Java Ollama provider).

Do not infer product scope from a single user example. The system is generic requirement clarification, not a domain-specific requirement generator.

**Authority split:** the V1 documents above describe the current production system. For V2 work, `docs/v2/README.md` and its canonical documents are authoritative. V1 rules remain binding only as migration compatibility constraints; where a `docs/v2` canonical document defines different target semantics, follow `docs/v2` and migrate explicitly — never silently reinterpret one side to match the other.

## 2. Product Boundary

Spec Agent is an AI-assisted requirement knowledge workspace: users and the Agent jointly explore, record, confirm, challenge and evolve requirements in a Graph (V2 target, see `docs/v2/AGENT_V2_OVERVIEW.md`). The V1 questionnaire-style clarification workflow remains supported during migration.

It is not:

- A collaboration platform.
- A project management system.
- A task board.
- A generic knowledge base or RAG product.
- A browser automation agent.
- A code generation agent.
- A generic multi-agent framework.
- A generic visual workflow/builder canvas product (the V2 Graph workspace is our own product surface, not a general-purpose graph editor).
- A domain-specific PRD generator.

If a requested change pushes the project toward one of these, stop and update the product/design docs before implementing.

## 3. Development Branch Policy

`dev` is the daily business development line. `main` is the release line and
only receives reviewed, self-contained changes.

```text
local dev ↔ remote dev      # day-to-day work
local main ↔ remote main    # released, reviewed changes only
```

Do not create feature branches or PRs unless the project owner explicitly
changes this rule.

Changes that belong to a larger initiative still go through a reviewed pull
request into `main`, and the reviewed work is merged back into `dev` by
path-level sync or a reviewed cherry-pick. Never merge a whole line blind:
`dev` and `main` move in parallel, so a blind merge can silently drop either
side's work. Resolve each conflict by semantics and keep the stricter rule.

### Closed owner overrides (history only)

These branches were authorized once and are closed. They authorize no new
work and must not be recreated.

- `capability-skills-mcp` — created from `main` at
  `f86bdb62a88fdae6330e532734f135ec82c22b39`, governed by
  `docs/v2/CAPABILITY_SKILLS_MCP_IMPLEMENTATION_PLAN.md`, merged and closed.
- `global-assistant-v1` — created from `main` at
  `41914ab356e7e076a28da71430ffae6619e1481e`, governed by
  `docs/v2/GLOBAL_ASSISTANT_IMPLEMENTATION_PLAN.md`. Backend must be accepted
  and frozen before Frontend development begins.

Pushing `dev` and merging into `main` both need the owner's explicit
authorization for the run in question. Do not infer one from the other.

## 4. Core Invariants

These describe the current V1 production system. During V2 migration they remain binding as migration compatibility constraints; where a `docs/v2` canonical document defines different target semantics, follow `docs/v2` and migrate explicitly.

Invariants that carry into V2 unchanged: immutable finalized Answers, append-preserving history, route isolation and shared-node identity, AnswerPatch recovery checkpoints, source grounding, Runtime-owned IDs/provenance, frozen OpenCode transport contract.

V1 baseline invariants:

1. Continuation history is append-preserving: established lineage must not be rewritten, and nodes must not be inserted retroactively between historical nodes.
2. In the current V1 schema every Node is a clarification Question. V2 generalizes Node to a Workspace Unit (`docs/v2/NODE_MODEL_V2.md`); existing nodes are treated as INTERACTION/QUESTION during migration.
3. Answers are immutable records.
4. A historical answer must not be overwritten.
5. Routes are explicit objects with root, tip, lifecycle status, label, and current focus metadata.
6. `active` is represented by `Project.activeRouteId`, not by `Route.lifecycleStatus`.
7. Route lifecycle status is `open | superseded | archived | deleted`.
8. Context is built from the active route's tip node by replaying parent lineage.
9. Sibling routes do not enter active context.
10. Superseded routes do not enter active context unless restored or forked.
11. Deleted routes do not enter active context.
12. Regenerate may include old question text and user regeneration instruction.
13. Regenerate must not include old answer, old patch, old child nodes, or old spec snapshot.
14. A spec snapshot is derived from one route tip and is not source of truth.
15. Confirmed spec claims must have source references.
16. Unsupported model output must be labeled assumption, suggestion, risk, or unresolved, not confirmed.
17. Runtime code must not contain concrete business-domain branching.
18. Runtime Kernel packages must not depend on ModelGateway or provider adapters.
19. A provider call must go through the ModelGateway, never straight to an
    HTTP client from a Runtime Kernel service.
20. A fake model may be used in tests and in the `test` profile. A real
    provider call requires explicit, opt-in configuration; it must never be
    the default of a shared profile.
21. Spring AI must not be added as the default model integration.

## 5. Anti-Overfitting Rules

Do not write runtime code such as:

```java
if (projectType == SOFTWARE_PROJECT) {
    askSoftwareScopeQuestion();
}
```

Do not add runtime classes such as:

```text
SoftwareRequirementAnalyzer
MarketingPlanQuestionGenerator
EcommerceSpecComposer
StartupPitchProfileService
StudentAssignmentClarifier
```

Do not add runtime enums such as:

```text
SOFTWARE_PROJECT
MARKETING_PLAN
ECOMMERCE_STORE
COURSE_ASSIGNMENT
STARTUP_IDEA
```

Allowed abstractions:

```text
RequirementAspect
RequirementProfile
QuestionPolicy
ClaimKind
Answer
AnswerPatch
ContextSnapshot
AgentRun
ReflectionGate
SpecSectionDefinition
SourceReference
```

Concrete domains may appear in tests as examples, in seed profile data, in prompts, in documentation examples, or in user-provided content. They must not become runtime control flow.

V2 extension discipline: add new product ability as a Node subtype/content handler, a capability descriptor/adapter, or payload semantics on the generic action families (`CREATE_NODE`, `INVOKE_CAPABILITY`, ...). Never add business-specific action names (`MARK_RISK`, `ANALYZE_FILE`), per-domain Agent classes (`FileAgent`, `RiskAgent`, `PRDAgent`), or per-file-type node classes (`PdfNode`, `GithubNode`).

## 6. Model Boundary

The model may reason, draft, interpret, and review. It must not own persistent state.

Allowed model outputs:

- Gap analysis.
- Agent plan.
- Node draft.
- Answer interpretation.
- Answer patch draft.
- Reflection result.
- Spec draft.

The model must not directly:

- Change route lifecycle status.
- Set `Project.activeRouteId`.
- Decide which historical route is valid.
- Delete nodes.
- Restore routes.
- Merge branches.
- Read global project history.
- Write database state without validation.

## 7. Model Gateway Rules

Read `docs/MODEL_GATEWAY.md` before adding any model-related dependency or code.

Model integration rules:

1. Use a custom HTTP ModelGateway when real model calls are introduced.
2. Do not use Spring AI as the default integration.
3. A real model call requires explicit opt-in. Tests use the fake adapter; a
   shared profile must not default to a paid provider.
4. A provider adapter must not leak provider-specific HTTP details into the
   Runtime Kernel.
5. Provider-specific HTTP details must stay behind ProviderAdapter.
6. Provider configuration must include base URL, endpoint path, model id, API key, timeout, and headers.
7. The OpenCode Zen provider path must support a configurable `User-Agent`. The
   published library currently sends `opencode/latest/2.0.29/cli`; read the
   value from the library or `docs/opencode-zen-gates.md` instead of pinning an
   old one here.
8. Do not hard-code provider headers inside Runtime Kernel, Route, Node, Answer, Context, Patch, or Spec services.
9. Do not store secrets in traces.
10. Validate model output before persistence.
11. Invalid model output must fail closed.

Forbidden examples:

```text
RuntimeService calls WebClient directly
ContextBuilder calls ModelGateway
RouteService calls OpencodeZenProviderAdapter
NodeService imports Spring AI client
SpecSnapshotService parses provider-native response JSON
```

Allowed examples:

```text
AgentReasoningService calls ModelGateway
ModelGateway calls ProviderAdapter
OpencodeZenProviderAdapter adds configured User-Agent
StructuredModelOutputParser validates response contracts
ModelCallTraceRecorder stores sanitized metadata
```

## 8. Runtime Boundary

Runtime Kernel code must be deterministic where possible.

Runtime Kernel owns:

- Project state.
- Route lifecycle state.
- Node lineage.
- Answer immutability.
- ContextSnapshot construction.
- Regenerate rule enforcement.
- Soft deletion.
- Source tracing.

Runtime Kernel must not call the model gateway.

## 9. Context Rules

Never pass whole project history to the model by default.

Always build a ContextSnapshot from:

```text
Project.activeRouteId
→ Route.tipNodeId
→ parent node lineage
→ ordered answers and patches
→ derived RequirementState
```

For regenerate operations, only include:

- Parent lineage.
- Old question text.
- Old purpose, if present.
- User regeneration instruction.

Never include:

- Old answer.
- Old answer patch.
- Old child nodes.
- Old spec snapshot.
- Sibling route conclusions.


## 10. Reflection Gates

Do not implement reflection as vague self-talk. Reflection must be structured.

Required gate types:

- Context Guard.
- Gap Reflection.
- Node Reflection.
- Patch Reflection.
- Spec Grounding Gate.

A gate must produce a machine-checkable result where possible.

## 11. Testing Requirements

When implementing a feature, add tests for the relevant invariants.

Minimum test categories:

- ContextBuilder lineage replay.
- Sibling route exclusion.
- Superseded route exclusion.
- Deleted route exclusion.
- Regenerate context contract.
- Route restore context rebuild.
- Soft delete shared ancestor safety.
- Answer immutability.
- Spec source references.
- Unsupported claim handling.
- Architecture dependency rules.
- Domain-specific runtime keyword prevention.
- Runtime package isolation from model packages.
- Provider adapter configurable `User-Agent` support.
- Spring AI absence unless explicitly approved later.

Do not claim a feature is complete without tests covering its context, route, model-boundary, and persistence behavior.

### 11.1 Test selections and what a green job actually proves

The build offers several Gradle test tasks and they do not cover the same
ground. Know which one you ran before you report a result:

| Task | Selects | Needs |
|---|---|---|
| `testOffline` | Only the reviewed `auditedOfflineTests` set | Nothing; no datasource, no live provider |
| `test` | Everything except `database-integration` / `external-service` tagged tests | An isolated database and `-PdatabaseIntegration=true` |
| `testDatabaseIntegration` | `*IntegrationTest`, `*AcceptanceTest`, `*EndToEndTest` by class name | An isolated database, `-PdatabaseIntegration=true`, and a Python interpreter carrying the `agent-brain` package |
| `testNonLive` | The complete backend suite except live-provider suites and the cross-language gate | An isolated database |
| `testCrossLanguage` | The Python brain cross-language exit gate | A broker-mode `agent-brain` on `localhost:8100` |
| `evalBFast` | The deterministic P2 evaluation corpus | An isolated database |
| `evalLive*` | Live provider suites | `-PexternalServices=true` plus real provider configuration |

Rules that follow from this:

1. A passing `testOffline` proves nothing about integration suites. It is a
   deliberately small, reviewed set.
2. `testDatabaseIntegration` selects by **class name**, not by
   `@Tag("database-integration")`. A test class that is an integration test
   but carries no such tag still runs. Do not "fix" a missing test by adding
   a tag.
3. Database suites require an explicitly isolated target. The guard rejects
   an ordinary development or test database by name and by the server-side
   identity it actually reports. Point the tests at a purpose-built database
   such as `spec_agent_p3b_live_*` and never at `localhost:5434` /
   `spec_agent_test` or any other business database.
4. When a suite cannot run in the current environment, say so. Do not
   report an environment gap as a pass.
5. `testDatabaseIntegration` starts a **real** `agent-brain` process for
   `C16RetrievalAwaitGenerationEndToEndTest`. The interpreter it starts must
   carry the brain package, otherwise the test fails with
   `No module named uvicorn`. Export that interpreter as
   `SPEC_AGENT_GA_TEST_PYTHON` (absolute path) instead of relying on an
   arbitrary `python3` from `PATH`. A missing runtime is an environment gap,
   not a product defect — but it still has to be fixed in the environment that
   runs the suite, never by weakening the test.

### 11.2 Skipped is not passed

A skipped test proves nothing. Before reporting a result, separate the
numbers:

```text
tests completed / failures / skipped
```

A skipped test is acceptable only when the source makes the condition
explicit, for example `Assumptions.assumeTrue(...)`,
`@EnabledIfEnvironmentVariable`, or `test.skip(...)`, and the condition is
an environment or fixture requirement rather than a defect. Record which
condition gated each skip.

Never fold a skipped or not-run test into a pass count. Never report a green
CI job as "all tests passed" without reading the per-task counts: a job that
runs several tasks can be green while one of them selected nothing.

## 12. Documentation Requirements

If implementation changes one of these, update docs in the same change:

- Product boundary.
- Core concepts.
- Route operation semantics.
- Active route semantics.
- Node or Answer immutability.
- Context rules.
- AgentRun lifecycle.
- ModelGateway strategy.
- Provider adapter behavior.
- Reflection gates.
- Anti-overfitting rules.

Do not let code silently diverge from docs.

Historical reports keep the coordinates, versions and protocol facts that were
true when they were written, but they must say so. When a dependency moves to
a new public coordinate, an old report that still names the previous one is
acceptable only if it is marked as history; a live document is not.

## 13. Implementation Style

Prefer small, explicit services:

- `ProjectService`
- `RouteService`
- `NodeService`
- `AnswerService`
- `ContextBuilder`
- `RequirementStateBuilder`
- `AgentRunService`
- `AnswerPatchService`
- `SpecSnapshotService`
- `ModelGateway`
- `ProviderAdapter`

Avoid god services that parse user language, mutate route state, call the model, generate specs, and persist results in one place.

## 14. Before Finishing Any Change

Check:

1. Did I introduce a concrete business-domain branch?
2. Did I introduce a domain-specific class, enum, package, or table?
3. Did I pass global history to the model?
4. Did I let model output mutate state without validation?
5. Did I preserve route lineage rules?
6. Did regenerate exclude old answer, patch, children, and spec?
7. Did spec content include source references?
8. Did I add a model dependency that was not explicitly opted into?
9. Did I accidentally add Spring AI as a default integration?
10. Did provider-specific code leak into Runtime Kernel?
11. Did tests cover the invariant touched by this change?
12. Did docs stay consistent with the code?
13. Did I report the real pass, failure and skip counts rather than a job-level green?
14. Did I run the test task that actually covers what I changed?
15. Did I avoid touching anything outside the scope of the change?

## 15. Repository Hygiene

1. Never `git add -A`. Stage the paths you actually changed.
2. Never `git reset --hard`, `git clean -fd`, or force-push.
3. Do not delete a file whose ownership you have not confirmed, and do not
   delete the user's uncommitted deletions, untracked files or evidence.
4. Keep secrets out of the repository. When scanning, report the location of a
   match, never the secret value.
5. `main` receives reviewed, self-contained changes. A long-lived development
   line is merged through a reviewed pull request, not by force.
6. Do not push a branch the owner has not authorized you to push.

# Implementation Plan

Status: first-version development path freeze  
Date: 2026-08-17

## 0. Principle

Do not start with broad scaffolding. Build the runtime invariants before product extras.

The first version should prove the core loop:

```text
vague requirement
→ clarification node
→ user answer
→ immutable answer record
→ answer patch
→ lineage context replay
→ fork / regenerate / restore / delete route
→ traceable spec snapshot
```

Development uses one branch only:

```text
local main ↔ remote main
```

Do not create feature branches for first-version development unless the development policy changes later. Keep commits small, test-backed, and directly aligned with the current phase.

## Phase 0: Documentation Freeze

Goal: freeze product scope and runtime invariants before implementation.

Deliverables:

- README.
- Product spec.
- Architecture doc.
- Agent runtime doc.
- Context rules doc.
- Development environment doc.
- AI development instructions.

Exit criteria:

- First-version scope is explicit.
- Non-goals are explicit.
- Anti-overfitting rules are explicit.
- Context and route invariants are written down.
- Docker development scope is minimal and PostgreSQL-only.

## Phase 1: Repository and Backend Test Foundation

Goal: create a reliable local development foundation without implementing model behavior.

Deliverables:

- Spring Boot backend skeleton.
- Gradle or Maven build.
- Java 21 configuration.
- Docker Compose with PostgreSQL only.
- Flyway setup.
- Local development profile.
- Test profile.
- Health endpoint.
- Base package structure.
- Basic architecture-test harness.

Required tests:

- Application context loads.
- Health endpoint works.
- PostgreSQL connection works in local/test profile.
- Flyway migrations run.
- Runtime packages do not depend on model packages.

Exit criteria:

- A developer can run PostgreSQL with Docker Compose.
- A developer can run backend tests locally.
- No model integration exists yet.
- No frontend exists yet.
- No Redis, MinIO, MySQL, RAG, browser automation, or external tools exist.

## Phase 2: Runtime Kernel and Persistence Model

Goal: implement deterministic state, persistence, and lineage mechanics without calling a model.

Deliverables:

- `projects` schema.
- `routes` schema.
- `nodes` schema.
- `answers` or finalized node-answer fields.
- `agent_runs` schema.
- `context_snapshots` schema.
- `answer_patches` schema.
- `spec_snapshots` schema.
- `profiles` schema or seed configuration.
- ProjectService.
- RouteService.
- NodeService.
- ContextBuilder.
- RequirementStateBuilder.
- SpecSnapshot source model.

Important semantics:

- `Project.activeRouteId` is the current working focus.
- `Route.lifecycleStatus` is route lifecycle: `open`, `superseded`, `archived`, or `deleted`.
- Route activity must not be represented by `Route.status = active`.
- Node question fields are immutable after creation.
- A node answer may be finalized once.
- Re-answering requires a new route, answer revision, or later explicit revision feature.
- RequirementState may be cached but is never source of truth.
- SpecPreview is transient UI output.
- SpecSnapshot is persisted derived output.

Required tests:

- Create project with active route.
- Create root node and child nodes.
- Build context from active route lineage.
- Replay answer patches into requirement state.
- Exclude sibling routes.
- Exclude deleted routes.
- Exclude superseded routes by default.
- Prevent answer overwrite after finalization.
- Ensure RequirementState can be rebuilt from patches.
- Ensure SpecSnapshot carries route tip and source references.

Exit criteria:

- The system can create projects, routes, nodes, answers, patches, context snapshots, and spec snapshots using fake data.
- Current requirement state is derivable from lineage and patches.
- No model integration exists yet.

## Phase 3: Route Control Operations

Goal: implement the product's core route operations deeply before model calls.

Deliverables:

- Continue active route.
- Fork from historical node.
- Regenerate historical node.
- Restore superseded route.
- Archive route.
- Soft delete route.
- Route lifecycle transitions.
- Shared ancestor protection.
- ContextSnapshot metadata for each route operation.

Required tests:

- Fork inherits only selected node lineage.
- Fork excludes sibling route conclusions.
- Regenerate includes old question text.
- Regenerate includes old question purpose if present.
- Regenerate includes user regeneration instruction.
- Regenerate excludes old answer.
- Regenerate excludes old answer patch.
- Regenerate excludes old child subtree.
- Regenerate excludes old spec snapshot.
- Old route becomes superseded and visible.
- Restored old route excludes replacement route context.
- Soft delete does not delete shared ancestors.
- Deleted route never contributes to active context.

Exit criteria:

- Route operations are deterministic and test-covered.
- Context isolation can be proven without a model.
- This phase proves the central product invariant: context is lineage, not global chat history.

## Phase 4: Agent Contracts with Fake Model

Goal: implement structured agent contracts and AgentRun lifecycle before real model calls.

Deliverables:

- AgentRun lifecycle.
- Closed agent action enum.
- Model contract DTOs.
- Fake model adapter.
- GapAnalysisResult.
- AgentPlan.
- NodeDraft.
- AnswerInterpretationResult.
- AnswerPatchDraft.
- ReflectionResult.
- SpecDraft.
- Context Guard.
- Node Reflection.
- Patch Reflection.
- Spec Grounding Gate.

Required tests:

- Invalid model output does not mutate persistent state.
- Unsupported action is rejected.
- Patch reflection catches unsupported confirmed claims.
- Node reflection rejects multi-question nodes.
- Spec grounding rejects sections without sources.
- AgentRun failure leaves no partial route mutation.
- ContextSnapshot id is attached to every fake model run.

Exit criteria:

- A fake agent can drive the full loop from initial requirement to spec snapshot.
- Runtime still owns history, route state, and persistence.
- The model boundary is stable before real provider integration.

## Phase 5: Real Model Gateway

Goal: add real model calls behind stable contracts.

Deliverables:

- OpenAI-compatible model adapter.
- Provider configuration.
- Prompt versioning.
- Structured JSON parsing.
- Retry or repair for invalid JSON.
- Model input/output hashes.
- Agent run trace summaries.
- Safe failure handling.

Required tests:

- Model adapter failures produce failed AgentRun, not partial mutation.
- Model output validation rejects invalid actions.
- ContextSnapshot ids are stored for every model call.
- Prompt version is recorded.
- Invalid JSON repair does not bypass validation.

Exit criteria:

- Real model can generate nodes, interpret answers, and draft specs without owning state.
- Runtime invariants still pass with real model integration.

## Phase 6: Frontend First Version

Goal: make the runtime visible and usable.

Deliverables:

- Vue 3 frontend skeleton.
- Project creation page.
- Three-panel workspace:
  - Route tree.
  - Current node answer panel.
  - Requirement state and spec preview.
- Route lifecycle display.
- Current active route indicator.
- Option answer support.
- Free-form answer support.
- Fork from historical node.
- Regenerate with optional user direction.
- Restore superseded route.
- Archive route.
- Delete route.
- Generate spec snapshot.

Required tests:

- Basic project creation flow.
- Answer node flow.
- Fork flow.
- Regenerate flow.
- Restore route flow.
- Delete route flow.
- Spec generation flow.

Exit criteria:

- A user can complete the core loop through UI.
- The UI shows route status, current requirement state, unresolved items, assumptions, and source-backed spec output.

## Phase 7: Hardening and Scope Guard

Goal: prevent scope expansion and code overfitting.

Deliverables:

- Architecture tests.
- Domain keyword scan for runtime packages.
- Documentation cross-check.
- Seed examples for generic requirements.
- CI workflow.
- Main-only development checklist.

Required tests:

- Runtime packages contain no concrete business-domain branches.
- ContextBuilder does not depend on model gateway.
- Route and Node services do not depend on prompts.
- Spec generation uses ContextSnapshot, not global project history.
- Production code does not introduce domain-specific analyzers, generators, composers, or enums.

Exit criteria:

- The implementation matches the design freeze.
- The project is ready for real product iteration without expanding into a broad platform.

## Explicitly Deferred

- Multi-user collaboration.
- Role-based permissions beyond local owner assumptions.
- Document upload and RAG.
- Task management.
- External tools.
- Browser automation.
- Code generation.
- Route merge.
- Complex canvas UI.
- Domain-specific requirement templates.
- Redis.
- MinIO.
- MySQL.
- pgvector.

## Development Order Summary

```text
Docs
→ backend + PostgreSQL foundation
→ runtime kernel and persistence model
→ route control operations
→ fake model contracts
→ real model gateway
→ frontend workspace
→ hardening and scope guard
```

## Stop Conditions

Pause implementation and update docs before coding if a change requires:

- Reading global project history as model context.
- Adding concrete business-domain runtime branches.
- Adding file upload, RAG, code generation, browser automation, or collaboration features.
- Changing route lifecycle semantics.
- Allowing answer overwrite after finalization.
- Generating confirmed spec claims without sources.

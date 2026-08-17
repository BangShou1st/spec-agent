# Architecture

Status: first-version design freeze  
Date: 2026-08-17

## 1. Architecture Goal

Spec Agent needs a stable business runtime more than it needs a broad agent framework. The architecture must protect history, context, route state, answer immutability, and traceability from accidental model behavior or AI-generated overfitting.

The system should be built as a modular monolith first.

```text
Vue frontend
  → Spring Boot API
  → Application Services
  → Runtime Kernel
  → Agent Reasoning Layer
  → Model Gateway
  → PostgreSQL
```

## 2. Planned Stack

Backend:

- Java 21.
- Spring Boot.
- PostgreSQL.
- Flyway.
- jOOQ or MyBatis.
- Jackson.
- SSE for event streaming.

Frontend:

- Vue 3.
- TypeScript.
- Vite.
- Pinia.
- Vue Router.

AI integration:

- Custom HTTP ModelGateway.
- ProviderAdapter boundary.
- OpenAI-compatible provider shape first.
- Configurable provider base URL, model id, endpoint, API key, timeout, and headers.
- Required support for configurable `User-Agent`, including `opencode/1.18.16` for opencode zen.
- Structured JSON contracts.
- Prompt versioning.
- Agent run trace persistence.
- Spring AI is not the first-version default.

Testing:

- JUnit.
- Integration tests.
- Architecture tests.
- Later Playwright for end-to-end UI flows.

## 3. Architectural Layers

### Application Layer

Coordinates user operations and transactions.

It may call both Runtime Kernel services and Agent Reasoning services, but it must preserve transaction boundaries and validation gates.

### Runtime Kernel

Deterministic where possible.

Owns:

- Project state.
- Route lifecycle state.
- Node tree.
- Answer immutability.
- AnswerPatch persistence.
- ContextSnapshot construction.
- RequirementState replay.
- Regenerate, restore, fork, and delete semantics.
- SpecSnapshot source references.

Runtime Kernel must not call models and must not contain domain-specific requirement logic.

### Agent Reasoning Layer

Calls models through structured contracts.

Produces:

- Gap analysis.
- Agent plans.
- Node drafts.
- Answer interpretations.
- AnswerPatch drafts.
- Reflection results.
- Spec drafts.

It cannot directly mutate route, node, answer, or spec state.

### Model Gateway

The Model Gateway is the only model-provider boundary.

The first version should use a thin custom HTTP gateway instead of Spring AI as the default integration.

```text
Agent Reasoning Layer
→ ModelGateway
→ ProviderAdapter
→ HTTP client
→ external model provider
```

The gateway owns provider protocol details, including base URL, endpoint, authentication header, `User-Agent`, timeout, request hashing, response hashing, and raw provider response handling.

Runtime Kernel must not depend on the Model Gateway.

### Verification / Reflection Gates

Validate model output before persistence.

Gates should be structured and machine-checkable where possible. They do not replace deterministic runtime validation.

### Persistence Layer

PostgreSQL is source of truth. It stores immutable lineage, route lifecycle, answers, patches, context snapshots, agent runs, profiles, and spec snapshots.

## 4. Package Boundaries

Recommended backend packages:

```text
com.specagent.project
com.specagent.route
com.specagent.node
com.specagent.answer
com.specagent.context
com.specagent.patch
com.specagent.agent
com.specagent.spec
com.specagent.profile
com.specagent.model
com.specagent.model.gateway
com.specagent.model.provider
com.specagent.model.contract
com.specagent.trace
com.specagent.common
```

Dependency rules:

```text
route/node/answer/context must not depend on model.
context must not call an LLM.
model must not mutate route, node, answer, or spec state.
agent may call model but must persist only through application services.
spec may compose drafts but must not read global history directly.
profile may define generic aspects but must not introduce runtime domain branches.
provider adapters must not contain requirement-domain behavior.
```

## 5. Runtime Kernel Responsibilities

The Runtime Kernel owns deterministic behavior:

- Creating projects.
- Creating routes.
- Creating nodes.
- Recording immutable answers.
- Advancing route tips.
- Setting `Project.activeRouteId`.
- Marking routes `open`, `superseded`, `archived`, or `deleted`.
- Building context snapshots from route lineage.
- Applying answer patches to build requirement state.
- Enforcing regeneration context rules.
- Enforcing soft deletion rules.
- Tracking spec snapshot source references.

The Runtime Kernel must not know whether a requirement is about software, marketing, education, sales, operations, or any other concrete domain.

## 6. Agent Reasoning Responsibilities

The Agent Reasoning Layer may produce:

- `GapAnalysis`: what is missing or inconsistent.
- `AgentPlan`: the next bounded action.
- `NodeDraft`: a question, purpose, options, option impacts, and free-answer support.
- `AnswerInterpretation`: structured understanding of a user answer.
- `AnswerPatchDraft`: claims, assumptions, constraints, risks, conflicts, and open questions.
- `SpecDraft`: a source-aware spec proposal.
- `ReflectionResult`: gate findings.

The Agent Reasoning Layer must not decide which historical route is valid. Route validity is determined by stored route lifecycle state and the current project pointer.

## 7. Model Gateway Responsibilities

See `docs/MODEL_GATEWAY.md` for the detailed model-provider boundary.

The architecture-level rules are:

- Use custom HTTP ModelGateway first.
- Do not add Spring AI as the default first-version integration.
- Hide provider-specific request and response JSON behind ProviderAdapter.
- Support configurable headers, especially `User-Agent`.
- Store model call metadata without storing secrets.
- Validate model output before persistence.
- Fail closed on invalid or unsupported model output.

## 8. Persistence Model

Core tables should map to these concepts:

- `projects`
- `routes`
- `nodes`
- `answers`
- `answer_patches`
- `agent_runs`
- `context_snapshots`
- `spec_snapshots`
- `profiles`

PostgreSQL is sufficient for the first version. Recursive queries can reconstruct lineage from `tip_node_id` through `parent_node_id`. JSONB may be used for structured patch content, model trace summaries, and spec sections, while route, node, answer, and source identity fields should remain strongly typed columns.

## 9. Transaction Boundaries

Operations that mutate route, node, answer, patch, or spec state should be transactional.

Examples:

- Answering a node saves the immutable Answer, interpretation, AnswerPatch, generated next node, and route tip update together.
- Regenerating a node marks the selected route superseded, creates the replacement node, creates the replacement route, stores the ContextSnapshot, and sets `Project.activeRouteId` together.
- Restoring a route updates `Project.activeRouteId` and records the operation together.
- Deleting a route marks the route deleted and updates active route focus together.

Do not let a model call partially mutate persistent state.

## 10. Context Construction

Context is not global chat history. Context is built by deterministic replay:

```text
Project.activeRouteId
→ Route.tipNodeId
→ parentNodeId chain
→ root-to-tip lineage
→ answers and patches on that lineage
→ derived RequirementState
→ ContextSnapshot
```

Sibling routes, superseded route patches, deleted route patches, and unsupported spec text are excluded by default.

## 11. Profile Layer

Profiles define generic requirement dimensions and output preferences. They are configuration, not code branches.

A profile may define:

- Requirement aspects.
- Aspect priority.
- Spec section definitions.
- Question policy hints.
- Tone and explanation style.

The first version should ship only one default profile: `generic_requirement`.

## 12. API Shape

The first API surface should be small:

```text
POST   /api/projects
GET    /api/projects/{projectId}
GET    /api/projects/{projectId}/routes
GET    /api/projects/{projectId}/routes/{routeId}
POST   /api/projects/{projectId}/nodes/{nodeId}/answer
POST   /api/projects/{projectId}/nodes/{nodeId}/fork
POST   /api/projects/{projectId}/nodes/{nodeId}/regenerate
POST   /api/projects/{projectId}/routes/{routeId}/restore
DELETE /api/projects/{projectId}/routes/{routeId}
POST   /api/projects/{projectId}/routes/{routeId}/spec-snapshots
GET    /api/projects/{projectId}/agent-runs/{runId}
```

This API should be refined during implementation, but it should not expand into project management, task management, RAG, browser automation, code generation, or external tool APIs.

## 13. Anti-Overfitting Architecture Tests

Add architecture tests early. They should fail if:

- Runtime packages depend on model gateway packages.
- Context builder calls an LLM.
- Route, node, answer, or context packages contain concrete business-domain enums.
- Classes named after specific domains appear in runtime packages.
- Spec generation reads global project history instead of a ContextSnapshot.
- Regeneration includes the old answer or child subtree in its ContextSnapshot.
- Production code introduces a domain-specific generator, analyzer, planner, or spec builder.
- Spring AI packages appear in production code before an explicit future design update.
- Provider adapters contain requirement-domain behavior.

## 14. Design Boundary

Spec Agent is not a generic agent platform. The architecture may later become reusable, but the first version should serve one product: branchable requirement clarification.

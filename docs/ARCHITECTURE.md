# Architecture

Status: first-version design freeze  
Date: 2026-08-17

## 1. Architecture Goal

Spec Agent needs a stable business runtime more than it needs a broad agent framework. The architecture must protect history, context, route state, and traceability from accidental model behavior or AI-generated overfitting.

The system should be built as a modular monolith first.

```text
Vue frontend
  → Spring Boot API
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

- Internal model gateway.
- OpenAI-compatible provider first.
- Structured JSON contracts.
- Prompt versioning.
- Agent run trace persistence.

Testing:

- JUnit.
- Integration tests.
- Architecture tests.
- Later Playwright for end-to-end UI flows.

## 3. Architectural Layers

```text
Application Layer
  - Coordinates user operations.
  - Starts AgentRun records.
  - Calls Runtime Kernel and Agent Reasoning Layer.

Runtime Kernel
  - Deterministic.
  - Owns Project, Route, Node, ContextSnapshot, AnswerPatch, SpecSnapshot rules.
  - Does not call models.
  - Does not contain domain-specific requirement logic.

Agent Reasoning Layer
  - Calls models through structured contracts.
  - Produces gap analysis, node drafts, answer interpretations, patch drafts, and spec drafts.
  - Cannot directly mutate route or node status.

Verification / Reflection Gates
  - Validate model output.
  - Check grounding and overclaiming.
  - Ensure model output can be persisted safely.

Persistence Layer
  - PostgreSQL source of truth.
  - Stores immutable lineage and route state.
```

## 4. Package Boundaries

Recommended backend packages:

```text
com.specagent.project
com.specagent.route
com.specagent.node
com.specagent.context
com.specagent.patch
com.specagent.agent
com.specagent.spec
com.specagent.profile
com.specagent.model
com.specagent.trace
com.specagent.common
```

Dependency rules:

```text
route/node/context must not depend on model.
context must not call an LLM.
model must not mutate route or node state.
agent may call model but must persist only through application services.
spec may compose drafts but must not read global history directly.
profile may define generic aspects but must not introduce runtime domain branches.
```

## 5. Runtime Kernel Responsibilities

The Runtime Kernel owns deterministic behavior:

- Creating projects.
- Creating routes.
- Creating nodes.
- Advancing route tips.
- Marking routes active, superseded, archived, or deleted.
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

The Agent Reasoning Layer must not decide which historical route is valid. Route validity is determined by stored route and node state.

## 7. Persistence Model

Core tables should map to these concepts:

- `projects`
- `routes`
- `nodes`
- `agent_runs`
- `context_snapshots`
- `answer_patches`
- `spec_snapshots`
- `profiles`

PostgreSQL is sufficient for the first version. Recursive queries can reconstruct lineage from `tip_node_id` through `parent_node_id`. JSONB may be used for structured patch content, model trace summaries, and spec sections, while route and node identity/status fields should remain strongly typed columns.

## 8. Transaction Boundaries

Operations that mutate route or node state should be transactional.

Examples:

- Answering a node saves the raw answer, interpretation, patch, generated next node, and route tip update together.
- Regenerating a node marks the old route segment superseded, creates the replacement node, creates or updates the active route, and records the context snapshot together.
- Deleting a route marks the route deleted and updates active route focus together.

Do not let a model call partially mutate persistent state.

## 9. Context Construction

Context is not global chat history. Context is built by deterministic replay:

```text
route.tipNodeId
→ parentNodeId chain
→ root-to-tip lineage
→ included nodes and patches
→ requirement state
→ context snapshot
```

Sibling routes, superseded route patches, deleted route patches, and unsupported spec text are excluded by default.

## 10. Profile Layer

Profiles define generic requirement dimensions and output preferences. They are configuration, not code branches.

A profile may define:

- Requirement aspects.
- Aspect priority.
- Spec section definitions.
- Question policy hints.
- Tone and explanation style.

The first version should ship only one default profile: `generic_requirement`.

## 11. API Shape

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

This API should be refined during implementation, but it should not expand into project management, task management, RAG, or external tool APIs.

## 12. Anti-Overfitting Architecture Tests

Add architecture tests early. They should fail if:

- Runtime packages depend on model gateway packages.
- Context builder calls an LLM.
- Route or node packages contain concrete business-domain enums.
- Classes named after specific domains appear in runtime packages.
- Spec generation reads global project history instead of a context snapshot.
- Regeneration includes the old answer or child subtree in its context snapshot.

## 13. Design Boundary

Spec Agent is not a generic agent platform. The architecture may later become reusable, but the first version should serve one product: branchable requirement clarification.

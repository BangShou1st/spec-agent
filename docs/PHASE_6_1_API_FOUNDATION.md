# Phase 6.1 — API Foundation + Read Model

Status: implemented (phase open for review)  
Commit: see git log (`feat: add backend API foundation and read model`)  
Date: 2026

## 1. Phase 6.1 Goal

Build the first formal backend HTTP application boundary over the already
stabilized runtime. Phase 6.1 is read/API foundation only:

- API foundation
- request/response DTOs
- validation
- safe error mapping
- project / query / read APIs
- controller integration tests
- architecture boundary tests

Phase 6.1 does **not** implement runtime commands or agent execution endpoints.
Those belong to Phase 6.2.

The doctrine remains non-negotiable:

```text
Model proposes.
Runtime validates.
Runtime persists.
Runtime owns history.

Runtime owns ids.
Context is lineage, not global chat history.
SpecSnapshot is a derived artifact, not source of truth.
Context is frozen snapshot + explicit run-local taskInput.
```

## 2. API Boundary

New package root: `com.specagent.api..`

```text
com.specagent.api
    common      ApiException, ApiErrorResponse, ApiFieldError, ApiExceptionHandler
    project     CreateProjectRequest, ProjectResponse, ProjectSummaryResponse,
                ActiveProjectStateResponse, ProjectController,
                ProjectRuntimeQueryService
    route       RouteResponse, RouteController
    node        NodeResponse, NodeOptionResponse (component DTOs only)
    spec        SpecSnapshotResponse, SpecSectionResponse, UnresolvedItemResponse,
                SourceReferenceResponse, SpecController
    agent       AgentRunResponse, AgentRunController
```

Existing runtime classes were **not** moved into the API package, and no
runtime package was redesigned.

### Dependency rule

Controllers never depend on repositories. They call services only:

```text
ProjectController        -> ProjectService, ProjectRuntimeQueryService
ProjectRuntimeQueryService -> ProjectService, RouteService, NodeService
RouteController          -> ProjectService, RouteService
SpecController           -> SpecSnapshotService, ProjectService, RouteService
AgentRunController       -> AgentRunService, ProjectService
```

`ProjectRuntimeQueryService` is a thin application query component that only
composes existing reads. It is not a second Runtime Kernel: it never writes
state, never calls the model, and never builds a `ContextSnapshot`.

## 3. Implemented Endpoints

All endpoints are versioned under `/api/v1`.

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/v1/projects` | Create a project (201) |
| `GET` | `/api/v1/projects` | List projects (`created_at` ASC) |
| `GET` | `/api/v1/projects/{projectId}` | Get one project |
| `GET` | `/api/v1/projects/{projectId}/active` | Active project state view |
| `GET` | `/api/v1/projects/{projectId}/routes` | List project routes |
| `GET` | `/api/v1/specs/{snapshotId}` | Get one spec snapshot |
| `GET` | `/api/v1/projects/{projectId}/routes/{routeId}/specs` | List route snapshots (ownership checked) |
| `GET` | `/api/v1/projects/{projectId}/runs` | List agent runs of a project |
| `GET` | `/api/v1/projects/{projectId}/runs/{runId}` | Get one agent run (ownership checked) |

### Active state semantics

`GET /api/v1/projects/{projectId}/active` returns
`{project, activeRoute, activeNode}` where:

- the project is loaded through the service boundary;
- `Project.activeRouteId` is the **only** active pointer (never a lifecycle
  status);
- `active` does **not** mean route lifecycle status;
- a project without an active route returns `activeRoute: null`;
- an active route without a tip node returns `activeNode: null` — no initial
  node is ever invented;
- the API never calls the model, never builds a `ContextSnapshot`, and never
  exposes requirement state or sibling-route history.

### Route read semantics

`lifecycleStatus` exposes only `open | superseded | archived | deleted`.
`isActive` is derived by comparing the route id with `Project.activeRouteId`
at read time. Reads never mutate route lifecycle. Phase 6.1 does not include
activate/fork/archive/delete/restore/regenerate — those are Phase 6.2.

### Spec read semantics

Spec snapshots remain **derived artifacts, not source of truth**. The API
exposes provenance (`snapshot id`, `project id`, `route id`, `tip node id`,
`context snapshot id`, `format`, `createdByRunId`, `createdAt`) plus sections,
unresolved items, and source references. Raw model/provider responses, raw
prompts, global requirement state, and database internals are never exposed.

Route-scoped spec reads verify ownership: a route from project A can never be
read through project B (404 `ROUTE_NOT_FOUND`).

### AgentRun read semantics

Safe operator reads expose metadata plus a sanitized trace-step list:

```json
"traceSteps": ["created", "context_built", "model_called:DRAFT_NODE", "completed"]
```

The stored trace intentionally contains diagnostic lifecycle steps rather than
raw provider payloads; the API converts the newline-joined trace into a list.
Never exposed: API credentials, credential values, raw prompts, raw
`ModelRequest` input, raw model output, raw provider HTTP payloads, stack
traces, database exceptions.

## 4. DTO Policy

The API never returns runtime domain objects (`Project`, `Route`, `Node`,
`AgentRun`, `SpecSnapshot`, `ContextSnapshot`, `ModelRequest`,
`ModelResponse`, ...) as its public contract. Every endpoint returns an
explicit API DTO. The DTO layer prevents internal model/storage changes from
silently changing the public API.

Runtime-owned fields are exposed read-only and are never accepted from
clients. In particular, `POST /api/v1/projects` accepts only `title`; it
rejects `projectId`, `activeRouteId`, `defaultProfileId`, `createdAt`, and
`updatedAt`. No Phase 6.1 API allows clients to supply runtime `NodeOption`
ids.

## 5. Validation

Spring `spring-boot-starter-validation` with DTO annotations:

- `title` required, not blank, at most 255 characters;
- malformed JSON -> 400 `MALFORMED_JSON`;
- malformed UUID path values -> 400 `INVALID_UUID`;
- unexpectedly long user-facing strings -> 400 `VALIDATION_ERROR`.

No JSON repair and no silent normalization: malformed requests fail closed.

## 6. Error Contract

Central mapping in `ApiExceptionHandler` (`@RestControllerAdvice`), stable
`ApiErrorResponse`:

```json
{
  "code": "PROJECT_NOT_FOUND",
  "message": "Project not found",
  "timestamp": "...",
  "errors": []
}
```

Policy:

```text
400 BAD_REQUEST   VALIDATION_ERROR / MALFORMED_JSON / INVALID_UUID / INVALID_ARGUMENT
404 NOT_FOUND     PROJECT_NOT_FOUND / ROUTE_NOT_FOUND / SPEC_NOT_FOUND / RUN_NOT_FOUND
409 CONFLICT      reserved for Phase 6.2 lifecycle conflicts (ApiException.conflict)
500               INTERNAL_ERROR with a generic safe message
```

Never returned: stack traces, SQL, provider raw error payloads, credentials,
master keys, API keys, raw prompts, raw model output. Unexpected exceptions
are logged server-side using only the exception class name (no message, so
secret-like content cannot reach the log), and the client receives a generic
safe message.

## 7. Small Service/Repository Additions

Phase 6.1 required exactly one read capability that did not exist:

- `ProjectRepository.findAll()` — deterministic order `created_at ASC, id ASC`;
- `ProjectService.listProjects()` — read-only list for `GET /api/v1/projects`.

No other runtime service/repository was modified.

## 8. Architecture Constraints

New ArchUnit rules added to `ArchitectureTests` (existing rules preserved,
none weakened):

- `com.specagent.api..` must not depend on classes whose simple name ends with
  `Repository`;
- `com.specagent.api..` must not depend on `com.specagent.model..`;
- `com.specagent.api..` must not depend on `com.specagent.context..` or
  `com.specagent.credential..`;
- `com.specagent.api..` must not depend on Spring AI / OpenAI SDK /
  LangChain4j;
- runtime kernel packages must not depend on `com.specagent.api..`.

## 9. Test Coverage

Controller integration tests use Spring Boot + MockMvc against the normal
test/runtime setup (Testcontainers-style local PostgreSQL on `:5434`, profile
`test`). Default gateway remains `SPEC_AGENT_MODEL_GATEWAY=fake`; Phase 6.1
requires zero public provider requests.

- `api.project.ProjectApiIntegrationTest` — create success, blank/missing/overly
  long title rejected, malformed JSON, get success, unknown project 404, list,
  malformed UUID 400.
- `api.project.ActiveStateApiIntegrationTest` — new project has active initial
  route with null active node; active state returns correct route and tip node
  after drafting; no sibling route/node leakage; unknown project 404.
- `api.route.RouteApiIntegrationTest` — list project routes, active route
  identified, lifecycle serialized as codes (no `active` status), reads do not
  mutate lifecycle.
- `api.spec.SpecApiIntegrationTest` — get snapshot, unknown snapshot 404, list
  route snapshots, wrong-project route rejected, unknown route 404.
- `api.agent.AgentRunApiIntegrationTest` — get run with sanitized traceSteps,
  list runs, unknown run 404, cross-project run read rejected, unknown project
  list 404, trace body contains no unsafe raw provider material.
- `api.common.ApiErrorHandlingIntegrationTest` — stable validation/error shape,
  unexpected 500 exposes no stack trace or internal message.

## 10. Deferred to Phase 6.2

Explicitly deferred (must not be implemented in Phase 6.1):

- answer submission endpoint
- draft-next-question endpoint
- agent execution endpoints (run commands)
- spec generation endpoint
- fork / activate / archive / delete / restore / regenerate route endpoints
- repair endpoints
- model-drafted regenerate
- provider registry / router / fallback / retry
- credentials REST APIs
- frontend, WebSocket, SSE, async job infrastructure

## 11. Known Answers (Phase 6.1 safety checklist)

- Did any controller directly depend on a Repository? **No**
- Did any API expose domain entities directly as its public contract? **No**
- Did any API expose raw ContextSnapshot / ModelRequest / ModelResponse? **No**
- Did any client-controlled runtime ID get introduced? **No**
- Did route/context/spec semantics change? **No**
- Did Phase 6.1 implement any Phase 6.2 command early? **No**

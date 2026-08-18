# Phase 6 — Final Exit Criteria (API Foundation + Runtime Commands + Agent API)

Status: implemented, tested, committed, pushed (phase open for external review)

- Phase 6.1 accepted baseline: `fd576d50a44507f17aef9cb63397f6af54f3e882`
  `feat: add backend API foundation and read model`
- Phase 6.2 implementation commit: see `git log` (`feat: expose runtime commands and close phase 6`)

## 1. Phase 6.1 Summary (accepted)

The formal backend HTTP application boundary over the stabilized runtime:

- API foundation, request/response DTOs, validation, safe error mapping
- read APIs for projects, active state, routes, spec snapshots, agent runs
- architecture boundary tests

Phase 6.1 remains intact; all Phase 6.1 read endpoints and error contracts are
unchanged in Phase 6.2.

## 2. All HTTP Endpoints

Versioned root `/api/v1`.

### Read APIs (Phase 6.1)

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/v1/projects` | Create a project |
| `GET` | `/api/v1/projects` | List projects |
| `GET` | `/api/v1/projects/{projectId}` | Get one project |
| `GET` | `/api/v1/projects/{projectId}/active` | Active project state |
| `GET` | `/api/v1/projects/{projectId}/routes` | List project routes |
| `GET` | `/api/v1/specs/{snapshotId}` | Get one spec snapshot |
| `GET` | `/api/v1/projects/{projectId}/routes/{routeId}/specs` | List route snapshots |
| `GET` | `/api/v1/projects/{projectId}/runs` | List agent runs |
| `GET` | `/api/v1/projects/{projectId}/runs/{runId}` | Get one agent run |

### Route command APIs (Phase 6.2)

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/v1/projects/{projectId}/routes/{routeId}/activate` | Activate an OPEN route |
| `POST` | `/api/v1/projects/{projectId}/routes/{routeId}/archive` | Archive a route |
| `POST` | `/api/v1/projects/{projectId}/routes/{routeId}/restore` | Restore a route to OPEN + active |
| `POST` | `/api/v1/projects/{projectId}/routes/{routeId}/delete` | Soft-delete a route |
| `POST` | `/api/v1/projects/{projectId}/nodes/{nodeId}/fork` | Fork a historical lineage view |
| `POST` | `/api/v1/projects/{projectId}/nodes/{nodeId}/regenerate` | Deterministic regenerate |

### Agent execution APIs (Phase 6.2)

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/v1/projects/{projectId}/questions/next` | Draft next question |
| `POST` | `/api/v1/projects/{projectId}/answers` | Submit an answer (option and/or free text) |
| `POST` | `/api/v1/projects/{projectId}/answers/{answerId}/repair` | Resume a failed answer-processing run |
| `POST` | `/api/v1/projects/{projectId}/specs/generate` | Generate a derived spec snapshot |

## 3. Runtime Ownership Rules

- Runtime owns ids: clients never supply `projectId`, `routeId`, `nodeId`,
  `optionId`, `snapshotId`, `runId`, `supersedes ids`, or provenance fields in
  request bodies. Requests accept only user-owned content.
- Runtime owns route lifecycle: lifecycle status is
  `open | superseded | archived | deleted`; there is no `active` status.
- Runtime owns the active-route pointer: it is always `Project.activeRouteId`,
  never derived from route lifecycle and never written by controllers.
- Model proposes, Runtime validates, Runtime persists, Runtime owns history.
- Context is lineage, not global chat history.
- SpecSnapshot is a derived artifact, not source of truth.
- Context is a frozen snapshot plus explicit run-local taskInput.

## 4. Selected-Option Answer Semantics

- `POST /api/v1/projects/{projectId}/answers` accepts an optional
  `selectedOptionId` referencing an existing runtime-owned option id previously
  returned by the active node, and/or non-blank `freeText`.
- At least one meaningful input is required; `freeText` may be absent only when
  a valid selected option is present.
- Free text is rejected when the active node does not allow free-form answers.
- The selected option is validated by the runtime against the exact active tip
  node: options from another node, from a sibling route, from historical nodes,
  or random fabricated ids are rejected (fail closed, 400). The request is
  never degraded into a free-text-only answer.
- Both inputs are preserved in the immutable Answer when permitted.
- Option answering never bypasses the orchestrator: it runs the identical full
  loop (context -> persist immutable answer -> interpret -> draft patch ->
  runtime grounds confirmed claims -> PatchReflectionGate -> persist patch ->
  draft next node -> NodeReflectionGate -> persist node).

## 5. Regenerate Semantics (frozen)

- Old route -> `SUPERSEDED`; replacement route -> `OPEN` and becomes active.
- Replacement node is created by the runtime, shares the target's parent, and
  `supersedes` the target node.
- Regenerate context is parent-lineage-only. Forbidden hidden context: old
  answer, old patch, old child subtree, sibling route conclusions, the
  replacement candidate as pre-existing history, SpecSnapshot-derived content,
  global chat history.
- Deterministic only: Phase 6.2 does NOT add model-drafted regenerate and makes
  zero model calls during regeneration.
- Runtime restrictions are not relaxed: unknown node/foreign node -> 404; root
  node regeneration unsupported -> 409; no OPEN source route -> 409; blank
  replacement question/options rejected -> 400.

## 6. Safe Error Model

Central `ApiExceptionHandler` (api.common) plus the single
`GatewayErrorAdvice` (com.specagent.web) that bridges provider-neutral gateway
failures into the API error contract.

```text
400  VALIDATION_ERROR / MALFORMED_JSON / INVALID_UUID / INVALID_ARGUMENT /
     INVALID_REQUEST (invalid answer payload, invalid regenerate option)
404  PROJECT_NOT_FOUND / ROUTE_NOT_FOUND / NODE_NOT_FOUND / ANSWER_NOT_FOUND /
     SPEC_NOT_FOUND / RUN_NOT_FOUND (ownership mismatches do not leak existence)
409  ROUTE_NOT_ACTIVATABLE / NO_ACTIVE_ROUTE / NO_ACTIVE_TIP_NODE /
     ANSWER_ALREADY_FINALIZED / ANSWER_NOT_IN_ACTIVE_FLOW /
     REGENERATE_ROOT_NOT_SUPPORTED / RUNTIME_CONFLICT
422  MODEL_CONTRACT_REJECTED / MODEL_PROVIDER_EMPTY_CONTENT
429  MODEL_PROVIDER_RATE_LIMITED
502  MODEL_PROVIDER_UNREACHABLE / MODEL_PROVIDER_ERROR / MODEL_PROVIDER_REJECTED /
     MODEL_PROVIDER_INVALID_RESPONSE
503  MODEL_PROVIDER_AUTHENTICATION / MODEL_PROVIDER_INVALID_MODEL /
     MODEL_PROVIDER_NOT_CONFIGURED
504  MODEL_PROVIDER_TIMEOUT
500  INTERNAL_ERROR / INTERNAL_INVARIANT_VIOLATION
```

Never returned: stack traces, SQL, raw provider bodies, credentials, master
keys, API keys, Authorization headers, raw prompts, `inputJson`, `outputJson`,
raw model/provider payloads, or raw exception messages from runtime/provider
exceptions. Unexpected exceptions are logged server-side by class name only.

## 7. Provider Failure Model

Gateway failures are mapped from the provider-neutral
`ModelGatewayException.gatewayCategory()` without any provider implementation
leakage: no OpenCode raw body, no transport message, no credential identifier.
All messages are static and provider-neutral (for example
`MODEL_PROVIDER_UNAVAILABLE`-style codes; the OpenCode name never appears).

## 8. Network Behavior

Default gateway remains `SPEC_AGENT_MODEL_GATEWAY=fake`. The full regression
makes zero public OpenCode requests. Real-provider smoke tests remain gated on
`SPEC_AGENT_OPENCODE_KEY` and are skipped by default. Phase 6.2 does not
auto-select OpenCode merely because a credential/model exists.

## 9. Architecture Protections

All Phase 6.1 ArchUnit rules preserved; Phase 6.2 additions:

- controllers (api `*Controller`) must not depend on `ModelGateway` or model
  provider packages
- controllers must not depend on `ContextBuilder`
- command composition services route all agent commands through the existing
  orchestrator and all route commands through `RouteService`

The API boundary never depends on repositories, model packages, context, or
credential packages; the runtime kernel never depends on the API boundary.

## 10. Test Counts / Results

Phase 6.2 adds 8 integration test classes (46 tests) on top of the 29 Phase 6.1
API tests, covering: route activation/archive/restore/delete, fork isolation,
deterministic regenerate + projection isolation + invalid cases, draft-next,
free-text and selected-option answers, cross-node/sibling option rejection,
repair (no duplicate answers), answer route isolation at the model-envelope
level, spec generation provenance, gateway failure mapping, and command error
safety. Full backend regression: all tests green (see final report for the
exact count before committing).

## 11. Final Invariant Statement

```text
SpecSnapshot remains derived.
Context remains lineage-scoped.
Runtime owns ids and history.
Model proposals are validated before persistence.
```

## 12. Deferred (out of Phase 6)

Frontend, authentication/multi-user, provider registry/router/fallback/retry,
credentials REST APIs, model-drafted regenerate, JSON repair, parser
relaxation, RAG, document import, browser automation, WebSocket/SSE, job
queue/async workers, and any external model SDK (Spring AI / OpenAI SDK /
LangChain4j).
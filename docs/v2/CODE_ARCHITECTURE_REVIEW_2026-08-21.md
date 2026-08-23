# Agent Runtime V2 Code Architecture Review

> Date: 2026-08-21  
> Status: **Review proposal — owner review required before implementation**  
> Scope: current `main` code vs canonical `docs/v2` target. No production code is changed by this review.

## 1. Executive conclusion

The current codebase is a strong Phase 8 **deterministic Graph Runtime + workflow-oriented Agent orchestration**. It is not necessary or desirable to rewrite the runtime. The correct migration is to preserve the proven Graph/Route/Answer/Recovery/Provider foundations and replace only the new reasoning/orchestration seams.

The most important implementation decision from this review is:

> **Introduce the Python Agent Brain early, before new V2 reasoning logic is written, but do not move the authoritative Runtime into Python.**

This avoids writing Reflection/Planning/Decision prompts and agent-loop logic in Java only to move them later. At the same time, it avoids the opposite mistake of porting stable Graph, recovery, persistence, provider settings, or policy code into Python.

Recommended ownership:

```text
Frontend
   |
   v
Spring Runtime (authoritative)
   |  Graph / Route / Answer / Patch / Snapshot
   |  AgentRun / operation state / recovery
   |  Policy / validation / persistence
   |  provider settings + frozen OpenCode transport
   |
   +---- AgentInputSnapshot ----------------------+
   |                                             |
   |                                      Python Agent Brain
   |                                      STATE_UPDATE
   |                                      Reflection + Planning
   |                                      AgentDecision
   |                                             |
   |<----------- Action Proposal ----------------+
   |
   +--> Runtime Policy / Validator / Executor
```

Skill/MCP are deliberately **not required for the first V2 cutover**. Their later integration remains low-coupling through `INVOKE_CAPABILITY` and a Capability Runtime boundary.

---

## 2. Blocking finding before Luna implementation: repository instructions conflict with V2

`AGENT.md` and `CLAUDE.md` still freeze the V1 product model:

- “Nodes form an immutable exploration tree.”
- “A Node is an immutable clarification prompt.”
- product positioning is “branchable requirement clarification agent”.
- a complex visual workspace / knowledge-style product is listed as a non-goal.

Those statements were correct for V1 but conflict with canonical V2 decisions:

- Node becomes a generic Workspace Unit;
- users can create draft/manual nodes;
- graph continuation can start from any node;
- semantic relations exist separately from continuation;
- the Graph workspace is the primary product surface.

Because coding agents are instructed to treat root agent instructions as mandatory, **Luna must not start V2 code changes while these root instructions still contradict `docs/v2`**.

Required pre-implementation action after owner approval:

1. update `AGENT.md` and `CLAUDE.md` to state that V1 invariants remain migration compatibility constraints, while `docs/v2/README.md` is authoritative for V2 work;
2. preserve the immutable Answer, route isolation, provider transport, recovery and grounding rules;
3. remove “Node must always be clarification prompt” as a future invariant;
4. make anti-overfitting rules explicitly include V2 generic actions/capabilities.

This is documentation alignment, not a production behavior change.

---

## 3. Current code map: keep / extract / evolve / retire

| Current component | Review | V2 disposition |
|---|---|---|
| `Route`, `RouteService`, `RouteHistoryResolver` | Strong deterministic history/provenance | **KEEP**, extend only for generic continuation rules |
| `Answer`, `AnswerService` | Strong immutable route-scoped checkpoint | **KEEP** |
| `AnswerPatch`, `AnswerPatchService` | Strong one-patch-per-answer recovery checkpoint | **KEEP**, make STATE_UPDATE produce it in one call |
| `ContextSnapshot`, `ContextBuilder` | Strong frozen lineage manifest | **KEEP**, do not rename to AgentState |
| `RequirementStateBuilder` | Useful derived replay state | **KEEP**, later generalize without destroying patch semantics |
| `AgentRun` / repository / failure service | Good execution identity and recovery anchor | **EVOLVE** with run events/steps; keep compatibility columns |
| `AgentOrchestrator` | Correct but overloaded fixed workflow | **DECOMPOSE incrementally**, do not rewrite in one commit |
| `ModelContextProjectionBuilder` | Useful deterministic projection but task-shaped | **EXTRACT** canonical `AgentInputSnapshotBuilder`; leave legacy adapter |
| `TaskPromptCatalog` / `AgentPromptRenderer` | Strong safety, but workflow-specific | **LEGACY ADAPTER**, new V2 brain prompts live in Python |
| `ModelGateway` / OpenCode provider transport | Hard-won stable provider boundary | **KEEP in Java** initially; extract lower-level inference seam |
| `StructuredModelOutputParser` / mapper | Excellent fail-closed precedent | **KEEP for legacy**, mirror strict V2 validation at Java/Python boundary |
| `NodeReflectionGate`, `PatchReflectionGate`, grounding guards | Deterministic validators misnamed as reflection | **KEEP as validators**, do not replace with LLM Reflection |
| `Node` / `NodeService` | Questionnaire-specific schema and route-tip coupling | **EVOLVE ADDITIVELY** to Workspace Unit + explicit mutation service |
| `GraphWorkspaceQueryService` | Strong canonical read-model composition | **KEEP/EXTEND** for generic nodes, relations, operation status |
| `graphProjection.ts` | Strong Active/Focus/route-answer semantics | **KEEP/EXTEND**; remove hardcoded question-only renderer assumption |
| `GraphCanvas.vue` | Strong viewport/layout separation | **KEEP**, add generic node shell and operation projection |
| `GraphQuestionNode.vue` | Current interaction-specific monolith | **SPLIT** into card shell + question body; move drafts out of component |
| `workspaceStore.ts` | Valuable canonical refresh/recovery but too many responsibilities | **SPLIT GRADUALLY**, preserve recovery logic |

---

## 4. Detailed backend findings

### 4.1 `AgentOrchestrator` is the main migration seam

It currently owns too many responsibilities in one class:

- project/active-route validation;
- AgentRun lifecycle;
- ContextSnapshot building;
- answer finalization;
- model calls;
- structured parsing;
- gate validation;
- claim grounding;
- AnswerPatch persistence;
- next-node creation;
- spec generation;
- regenerate/replacement;
- failure/recovery trace.

This was useful while the workflow was fixed. It becomes a coupling hotspot once the planner can select different actions.

Do **not** replace it with five empty façade classes immediately. Extract around existing durable checkpoints:

```text
AgentRuntime / V2 application coordinator
  |
  +-- AgentInputSnapshotBuilder
  +-- StateUpdateEngine      -> Python
  +-- AgentDecisionEngine    -> Python
  +-- AgentPolicyEngine      -> Java
  +-- AgentActionValidator   -> Java
  +-- AgentActionExecutor    -> Java
  +-- AgentRunService        -> Java
```

Legacy `AgentOrchestrator` remains callable until each legacy flow has a V2 equivalent.

### 4.2 Current answer path proves where the first cut should be

Today the successful answer path performs:

```text
INTERPRET_ANSWER
DRAFT_ANSWER_PATCH
DRAFT_NODE
```

The repair path is already checkpoint-aware: if the AnswerPatch exists, it skips answer interpretation/patch generation and continues from that checkpoint.

Preserve that behavior exactly while changing the model stages to:

```text
persist immutable Answer
      |
STATE_UPDATE  (Python brain, 1 model call)
      |
Java grounds + validates + persists AnswerPatch
      |
DECISION      (Python brain, 1 model call)
      |
Java validates/policy/executes primary action
```

If DECISION selects `REQUEST_USER_INPUT`, the question payload is included in that same response. No third “question writer” call is allowed in the normal path.

### 4.3 `AgentRun` is currently single-artifact-shaped

Current run columns include one `produced_node_id`, one answer, one patch and one spec. V2 runs may include multiple decision steps, approval waits, capability observations and more than one execution event.

Do not delete these compatibility fields immediately. Add an append-only run event model, for example:

```text
agent_run_events
- id
- run_id
- sequence
- phase
- event_type
- payload_json
- created_at
```

Suggested public phases:

```text
CREATED
SNAPSHOT_BUILT
STATE_UPDATING
STATE_UPDATED
DECIDING
PROPOSAL_CREATED
AWAITING_APPROVAL
EXECUTING
WAITING_USER
COMPLETED
FAILED
STALE
```

The UI progress text must derive from these real phases. The event payload is a sanitized trace/progress record, never hidden chain-of-thought.

### 4.4 Stale proposal protection is missing

The current model response is correlated to `agentRunId` and `contextSnapshotId`, which is good, but there is no final live-state precondition check immediately before applying a proposed graph mutation.

V2 proposal/execution must carry:

```text
baseContextSnapshotId
baseContextHash
baseRouteId / anchor where applicable
proposalId / idempotencyKey
```

Executor rejects `STALE_CONTEXT` when the authoritative route/anchor facts changed after the snapshot. First version must rerun from a new snapshot rather than silently rebasing a model proposal.

### 4.5 `ContextSnapshot` and `AgentInputSnapshot` must remain separate

`ContextSnapshot` is a durable manifest of exactly which lineage/answer/patch records were used. Keep it.

Build `AgentInputSnapshot` as the deterministic model-facing projection containing:

- protocol version;
- run + context snapshot identity/hash;
- explicit anchor node;
- explicit route/read context;
- ordered lineage;
- effective route-scoped answers;
- derived claims/state;
- selected semantic relations;
- low-authority metadata (`projectTitle`);
- allowed source refs;
- allowed action/capability descriptors;
- autonomy policy inputs;
- loop/decision budget.

The new builder may use repositories/services in Java. Python must never query production DB to reconstruct this state.

### 4.6 `ModelContextProjectionBuilder` has three jobs that V2 should separate

Today it mixes:

1. canonical context projection;
2. derived requirement-state projection;
3. task-specific input shaping (`initial`, `after_answer`, `redirected`, etc.).

Migration:

```text
AgentInputSnapshotBuilder        # generic, Java
StateUpdateRequestFactory        # small V2 request mapping
LegacyModelProjectionAdapter     # current task flow only
```

Do not make the generic snapshot builder know `DRAFT_NODE`/`INTERPRET_ANSWER` business workflow names.

### 4.7 Current root prompt still treats project title too strongly

The current production prompt tells the model to use a “meaningful” `projectTitle` to drive the initial question. V2 explicitly rejects this assumption.

During migration:

- do not modify the legacy prompt before the V2 path is ready unless separately needed;
- the Python V2 prompt treats project title only as weak workspace metadata;
- an empty project requires no automatic model call;
- objective must come from explicit user information or remain tentative/unknown.

### 4.8 Deterministic “Reflection Gates” are validators, not Agent Reflection

`NodeReflectionGate` and `PatchReflectionGate` perform deterministic structural/grounding checks. This is good and must remain Java-side.

Rename only when convenient; behavior matters more than names.

New Agent Reflection belongs in Python `DECISION` output as structured observation:

```text
known
unknowns
conflicts
risks
attention/focus
```

It must not write Graph and does not bypass the deterministic gates.

---

## 5. Python must be introduced early — but only as Agent Brain

### 5.1 Why early Python is justified

If we implement V2 Reflection, Planner, prompt schemas, call orchestration and evaluation deeply in Java first, then later move them into Python, we create avoidable migration work and two implementations of the most changeable layer.

The current stable Java code is primarily **Runtime and provider infrastructure**, not code that must move to Python.

Therefore the preferred cut is:

> New V2 reasoning code starts in Python from the first implementation stage. Stable Java Runtime code stays in Java permanently unless a future review proves otherwise.

### 5.2 Python does not become a second backend

Python must not own:

- project/route/node/answer persistence;
- ContextSnapshot truth;
- active/focus route truth;
- policy authority;
- user approval state;
- IDs/provenance;
- recovery checkpoints;
- provider credential persistence;
- direct production DB access.

Python receives a frozen request and returns structured reasoning output/proposals.

### 5.3 Python service shape

Recommended repository module:

```text
agent-brain/
  pyproject.toml
  src/spec_agent_brain/
    app.py
    contracts/
    prompts/
    state_update/
    decision/
    model_client/
    evaluation/
  tests/
```

Initial HTTP surface:

```text
GET  /health
POST /v1/state-updates
POST /v1/decisions
```

Use Pydantic models with `extra = forbid` and explicit `protocolVersion`.

No Skill/MCP endpoints are needed in the first implementation stage.

---

## 6. Important Python/provider boundary decision: keep the frozen OpenCode transport in Java

The current Java OpenCode transport/gateway contains substantial provider-specific diagnostics and a transport contract that was already stabilized in Phase 8. Reimplementing that transport in Python now would create two sources of provider truth and reopen solved reliability problems.

Recommended architecture:

```text
Spring background AgentRun worker
        |
        v
Python Agent Brain
        |
        | internal model inference request
        v
Spring Internal Model Inference Broker
        |
        v
existing Java OpenCode transport/settings
        |
        v
provider
```

This means:

- **Python owns prompts and model-call orchestration** for V2;
- **Java owns provider credentials, selected model and HTTP transport**;
- Python never receives the OpenCode API key;
- provider transport is not duplicated;
- future Skill/MCP work is unrelated to this model broker.

### 6.1 Extract a lower-level inference port

Current `OpenCodeZenModelGateway` combines prompt rendering and provider invocation. Extract a provider-neutral lower layer conceptually like:

```text
ModelInferenceGateway.complete(ModelInferenceRequest)
```

where request contains runtime-approved model messages rather than `AgentTaskType`.

Legacy flow remains:

```text
Legacy ModelGateway
  -> TaskPromptCatalog
  -> ModelInferenceGateway
```

V2 flow becomes:

```text
Python prompt/orchestration
  -> internal inference broker
  -> ModelInferenceGateway
```

Both share the same proven OpenCode transport.

### 6.2 Internal broker safety

The broker is not a browser/public product API.

Requirements:

- internal-network access only where possible;
- service authentication/shared internal secret;
- request tied to `runId` and call budget;
- no arbitrary user-supplied URL/header forwarding;
- no API key in response/log/trace;
- no provider fallback;
- no hidden retry;
- call type and prompt hashes recorded in sanitized AgentRun events;
- preserve the frozen OpenCode completion transport contract.

### 6.3 Why reverse HTTP is acceptable only with asynchronous AgentRun execution

Do not keep a browser HTTP command waiting while Spring calls Python and Python calls Spring again. Instead V2 mutation commands become durable asynchronous runs:

```text
browser POST command
   -> Spring creates AgentRun
   -> returns 202 + runId
   -> background worker runs Python/model work
```

The original request thread is already released before Python uses the internal inference broker. This also solves the product requirement that the UI remain usable and show a pending node/route immediately.

---

## 7. Async AgentRun is the backend prerequisite for the pending UI

Current `AgentCommandController` is explicitly synchronous. The frontend `workspaceStore` waits for the entire command and uses broad booleans such as `submitting`, `drafting`, and `routeCommandPending`.

That architecture cannot deliver truthful “route appears immediately + AI continues working” behavior cleanly.

V2 should add a new asynchronous command surface rather than breaking all legacy endpoints immediately.

Example:

```text
POST /api/v1/projects/{projectId}/agent-runs
-> 202
{
  "runId": "...",
  "operation": "CONTINUE|ANSWER|ASK_NODE|...",
  "phase": "CREATED"
}

GET /api/v1/projects/{projectId}/agent-runs/{runId}
GET /api/v1/projects/{projectId}/graph
```

First UI version may poll run/graph status. Do **not** require WebSocket/SSE in the first V2 slice. Polling is simpler, deterministic, and sufficient to validate the lifecycle. Streaming transport can be added later without changing Agent/Graph contracts.

Fork is already close to the target semantics because route creation and first-child drafting are separate commands. The V2 API should make that separation explicit and non-blocking.

---

## 8. Node/Graph persistence gap

### 8.1 Current DB schema is Question-specific

Current `nodes` table requires:

```text
question TEXT NOT NULL
purpose
options
allow_free_answer
```

This cannot represent a blank user node, Requirement, Resource, Artifact, or generic knowledge unit without hacks.

Use an additive migration, not a destructive rewrite.

Recommended staged representation:

```text
nodes
  existing identity/history columns
  + kind
  + subtype
  + content jsonb
  + author_kind
  + knowledge_status nullable
```

Compatibility rule:

- existing rows are interpreted as `INTERACTION / QUESTION`;
- current question/purpose/options columns remain readable during migration;
- new code writes the generic representation once all required readers support it;
- remove/relax obsolete `question NOT NULL` only in a later migration after compatibility tests.

Do not create one physical table/class per subtype.

### 8.2 `NodeService` currently mixes content creation and route advancement

Current node creation saves the Node and then changes route root/tip. That is acceptable for the old fixed workflow but too implicit for a generic Action Executor.

V2 should introduce explicit transactional graph mutation commands, e.g.:

```text
CreateNode
AppendContinuation
CreateBranchAndAppend
CreateSemanticRelation
ReviseNode
```

These are Runtime command objects/services, **not model action names**. The external Agent protocol remains small (`CREATE_NODE`, `CONNECT_NODE`, etc.).

The Runtime command handler owns atomicity and invariants.

### 8.3 Generic continuation must be broader than current Fork

Current fork semantics are intentionally strict and assume a previously answered clarification node. V2 must additionally support:

- user draft node from empty workspace;
- continuation from a user-authored node;
- continuation from a non-tip node even when “finalized answer exists” is not the relevant condition.

Do not weaken the existing Fork method until a generic continuation path is added and tested. Preserve old behavior as a compatibility command.

---

## 9. Frontend code findings

### 9.1 Preserve the good graph infrastructure

`GraphCanvas.vue`, `graphProjection.ts`, `graphUiStore` and route-answer projection already implement several V2 principles well:

- canonical backend graph is not reconstructed as truth in the browser;
- Active and Focus are separate;
- Focus dims rather than hides other routes;
- a shared route segment does not guess an arbitrary Focus;
- route-scoped answers are not silently replaced with Active-route answers;
- existing node positions are retained across canonical refresh;
- revealing a new node changes viewport, not every node coordinate.

Do not rewrite Vue Flow/layout just because V2 adds node kinds.

### 9.2 `GraphQuestionNode.vue` contains the known input-loss bug

The watcher returns a new array:

```ts
watch(() => [props.data.node.id, props.data.canAnswer], ...)
```

and local refs are cleared when it retriggers/remounts.

The V2 fix should be architectural, not just a watcher patch:

```text
GraphInputDraftStore
 key = project + canonicalNode + route/read-answer scope
 value = selected option + free text
```

Node components bind to that store. Dragging, focusing, canonical refresh and local component remount no longer destroy the user's input.

A small watcher correction can still be applied during migration, but it is not the final ownership model.

### 9.3 Split node shell from subtype body

Do not build `GraphFileNode`, `GraphRiskNode`, `GraphRequirementNode`, etc. as independent full card implementations.

Prefer:

```text
GraphNodeCard
  header: Q label / kind / Latest / route chips
  body renderer registry
     QuestionBody
     Text/KnowledgeBody
     ResourceBody
     ArtifactBody
  footer/action toolbar
```

Only the body varies by capability/content contract. Route badges, focus, provenance, hover actions and selection remain in the shell.

### 9.4 Pending cards are operation projections

The frontend projection should combine:

```text
canonical GraphWorkspaceView
+
in-flight AgentRun / GraphOperation views
```

into visual nodes.

A pending visual item has a stable UI key such as `run:{runId}:pending`, but **no canonical nodeId** until a validated Node is created.

### 9.5 Avoid global mutation lockouts

Current store often blocks route commands whenever `submitting` or `drafting` is true. V2 must scope conflict prevention to the affected route/node/operation.

Examples:

- duplicate submit of the same answer: block;
- incompatible mutation of the same active route tip: block/queue;
- pan/zoom/focus/read another route: allow;
- edit an unrelated user draft: generally allow;
- start another conflicting run on same anchor: reject deterministically.

This is necessary for the non-blocking workspace experience.

---

## 10. Architecture tests to add before/with V2 code

> Post-approval structure note (2026-08-22): the owner removed version
> markers from code structure, so the package names below were renamed.
> Authoritative mapping: `agent.v2.contract` -> `agent.contract`,
> `agent.v2.decision` -> `agent.decision`, `agent.v2.executor` ->
> `agent.runtime` plus a separate `agent.runevent` for run-event persistence;
> future Stage B packages are `agent.policy` and `agent.action`. The scope
> strings in `AgentBoundaryArchitectureTests` are the live authority.

The existing ArchUnit suite is a strong asset. Extend it rather than replacing it.

Required new rules:

1. `agent.v2.contract` contains no repository/service/provider dependencies.
2. `agent.v2.decision` Java client/adapter cannot depend on persistence repositories.
3. `agent.v2.policy` cannot depend on Python/FastAPI/provider implementation details.
4. `agent.v2.executor` cannot call an LLM/model gateway.
5. model/provider packages cannot depend on Python decision contracts beyond the neutral inference DTO.
6. read-model/UI DTOs do not expose API keys, provider native responses or hidden reasoning.
7. Capability adapters (when added) cannot mutate Graph except through Runtime executor ports.

Cross-language contract tests:

- golden JSON fixture accepted by Java and Python;
- unknown fields rejected;
- unknown protocol version rejected;
- invalid action payload rejected;
- invented source refs rejected Java-side;
- stale base context rejected Java-side;
- Python response can never authorize itself by setting risk/confidence fields.

---

## 11. Recommended larger implementation stages

The owner explicitly prefers fewer, more substantial stages. The following grouping is large enough to create real value without mixing unrelated product changes.

### Stage A — V2 Runtime Foundation + Python Brain Bootstrap

Do this first as one coherent implementation stage.

Deliverables:

- align root `AGENT.md` / `CLAUDE.md` with canonical V2 docs;
- shared/versioned AgentInput/Decision wire contract + golden fixtures;
- Java `AgentInputSnapshotBuilder` over current ContextSnapshot/graph facts;
- new Python `agent-brain` service with health, STATE_UPDATE and DECISION contracts;
- Python prompt/orchestration modules for STATE_UPDATE and DECISION;
- Java lower-level `ModelInferenceGateway` preserving current OpenCode transport;
- internal authenticated model-inference broker for Python;
- Spring background AgentRun executor + V2 run event/phase persistence;
- strict Java validation of every Python result;
- Fake Python/model path for deterministic integration tests;
- Docker/dev wiring for Spring + Python + Postgres;
- no Skill/MCP yet;
- no broad Graph schema/UI rewrite yet.

Exit gate:

- Python can complete a deterministic DECISION through the Java inference broker;
- Python has zero production DB access;
- provider key never appears in Python response/log/fixture;
- old synchronous V1 paths still work;
- architecture tests pass.

### Stage B — Answer Cutover + Advisor Runtime + Non-blocking UI

Deliverables:

- normal answer path cut to STATE_UPDATE + DECISION (2 serial model calls);
- checkpoint-aware repair preserved;
- generic V2 action proposal + stale-context check;
- Advisor policy and approval lifecycle for visible mutations;
- asynchronous command/read API used by frontend;
- AgentRun phase polling;
- Fork/new-route immediate appearance + virtual pending card;
- local input draft store fixes drag/submission loss;
- Q1/Q2/Latest/route chips/vertical options/hover actions;
- scoped lockouts instead of whole-workspace blocking.

Exit gate:

- ordinary answer success = exactly 2 provider calls;
- user sees durable answer save/progress without frozen canvas;
- route/pending projection appears before model completion;
- stale proposal cannot mutate Graph;
- legacy recovery scenarios remain green.

### Stage C — Graph Workspace V2

Deliverables:

- additive generic Node persistence model + compatibility migration;
- blank/manual user nodes;
- generic continuation from any node;
- non-tip continuation creates branch, never historical insertion;
- semantic relation persistence separated from visible continuation;
- shared-node/route-scoped-answer read model maintained;
- generic `GraphNodeCard` + subtype renderer registry;
- any-node contextual `RESPOND_TO_USER` / “ask AI about this node”;
- graph operation history foundation + first Undo/Redo reversible actions.

Exit gate:

- empty project is usable without a model call;
- user can author and branch from manual nodes;
- AI can answer about a node without forcing mutation;
- current Question workflow remains backward compatible;
- no node-type/business-agent explosion.

### Stage D — Capability Foundation (later)

Only after Stage C stabilizes:

- CapabilityRegistry/Runtime;
- Internal adapter first;
- Skill/MCP adapters later;
- Resource node subtypes incrementally.

This stage is intentionally not required for initial Agent V2 value.

---

## 12. What Luna must not do

During implementation Luna must not:

- rewrite Graph/Route/Answer/Recovery from scratch;
- port Java persistence/domain services into Python;
- give Python production database credentials;
- reimplement the OpenCode completion transport in Python while Java transport remains canonical;
- create separate LLM calls for Reflection and Planner by default;
- add a third LLM call just to render the next question;
- make `confidence` or model-suggested risk an authorization threshold;
- create `FileAgent`, `RiskAgent`, `RequirementAgent`, `PDFNode`, etc.;
- create a partially populated canonical Node to fake pending UI;
- infer Focus from Active on ambiguous shared nodes;
- turn on arbitrary free-form edge mutation before Runtime continuation/relation rules exist;
- delete immutable Answers as Undo;
- replace deterministic recovery with generic retry loops;
- change the frozen OpenCode transport contract without separate evidence/review.

---

## 13. Review verdict by area

| Area | Current quality | Migration risk | Verdict |
|---|---:|---:|---|
| Route/history | High | Low | preserve |
| Answer/patch/recovery | High | Medium | preserve checkpoints; change only model stages |
| ContextSnapshot | High | Medium | preserve; add V2 projection above it |
| AgentOrchestrator | Medium | High | primary decomposition target |
| Prompt/task pipeline | Medium | High | new V2 logic goes to Python; legacy stays during cutover |
| Provider transport/settings | High | High if moved | keep Java; expose narrow inference broker |
| AgentRun/trace | Medium | Medium | evolve to async run events |
| Node model/schema | V1-correct | High | additive generic migration required |
| Read model | High | Medium | extend rather than replace |
| Frontend graph/layout | High | Medium | preserve infrastructure |
| Frontend node/store interaction | Medium | Medium | split shell/body + draft/operation state |
| Skill/MCP | Not implemented | Low if boundaries kept | postpone safely |

---

## 14. Final recommendation for owner review

Proceed with V2 implementation after the owner approves this review, with one revision to the previous canonical implementation order:

> **Python is no longer a late optional Phase 11 item. Python Agent Brain bootstrap moves into Stage A, before any new V2 reasoning code is implemented.**

This does **not** mean moving the application backend to Python. It means placing the high-change intelligence layer in its intended language from the beginning while leaving durable runtime authority and the already-stabilized provider transport in Spring.

After approval, update the canonical implementation plan/Python boundary and root AI development instructions to match this review, then hand Stage A to Luna with explicit tests and exit criteria.

# Post-A–D Production Cutover

> Status: **in progress** — one slice per product flow, merged directly to `main`.
> Scope: move real product traffic onto the Stage B–D Agent Runtime, then retire
> the legacy `AgentOrchestrator` workflow. This document carries the migration
> matrix and the before/after record for each product operation.

## 1. Ground rules

- Java Runtime owns persistence, policy, graph mutation, proposal lifecycle,
  IDs, provenance, recovery, authorization, provider settings/transport.
- Python owns STATE_UPDATE / DECISION / ARTIFACT_GENERATION reasoning and the
  structured model-facing contracts. Python never touches the database, never
  invents runtime IDs, never holds provider credentials.
- Immutable finalized Answers, append-preserving history, route isolation,
  AnswerPatch recovery checkpoints, and the frozen OpenCode provider transport
  are never weakened.
- Cutover order: answer → question/continuation → artifact/spec + replacement.
  Legacy deletion only after each flow's production callers reach zero.

## 2. Migration matrix

State at branch point `0e5aa36` (verified against source, not delivery notes):

| Product operation | Frontend entry (before) | Backend entry (before) | Reasoning path (before) | New Runtime equivalent | Cutover state |
|---|---|---|---|---|---|
| initial/draft question | `workspaceStore.draftQuestion()` → POST `/questions/next` (sync) | `AgentCommandController.draftNextQuestion` → `AgentCommandService.draftNext` | `AgentOrchestrator.draftNextQuestion` (`DRAFT_NODE`) | DECISION_CYCLE run (`DRAFT_QUESTION`) → 1 DECISION → `REQUEST_USER_INPUT` executed by policy/executor | slice 2 ✅ |
| submit answer | `workspaceStore.submitAnswer()` → POST `/answers` (sync) | `AgentCommandController.submitAnswer` → `AgentCommandService.submitAnswer` | `AgentOrchestrator.answerActiveNodeAndDraftNext` (`INTERPRET_ANSWER` + `DRAFT_ANSWER_PATCH` + `DRAFT_NODE`) | ANSWER_CYCLE run (`ANSWER_TIP`) via POST `/agent-runs` + polling | slice 1 |
| repair answer | `workspaceStore.repairAnswerForActiveFlow()` → POST `/answers/{id}/repair` (sync) | `AgentCommandController.repairAnswer` → `AgentCommandService.repairAnswer` | `AgentOrchestrator.repairAnswerProcessingAndDraftNext` | ANSWER_CYCLE run (`RESUME_ANSWER`, persisted Answer replayed server-side) | slice 1 |
| contextual node query | `askNodeAI()` → POST `/nodes/{id}/query` + poll | `NodeQueryRunController` | 1 DECISION via `NodeQueryService` (read-only) | existing | already |
| generate spec | `workspaceStore.generateSpec()` → POST `/agent-runs` + poll | `AnswerCycleRunController` (`GENERATE_ARTIFACT`) | 1 ARTIFACT_GENERATION call, grounding gates preserved | ARTIFACT_GENERATION run (Python `POST /v1/artifacts`) via POST `/agent-runs` | slice 3 ✅ |
| regenerate/replace | `workspaceStore.regenerateNode()` → POST `/agent-runs` + poll | `AnswerCycleRunController` (`REGENERATE_NODE`) | `AgentOrchestrator.replaceQuestion` (`DRAFT_NODE` redirect) | REGENERATE_NODE run → 1 DECISION (`REQUEST_USER_INPUT` content) → deterministic `RouteService.commitReplacementFromNode` | slice 3 ✅ |
| graph commands (draft/continuation/relations/undo/redo/resources) | `api/graphCommands.ts` | `GraphCommandService` command layer | none (0 model calls) | existing | already |
| capabilities | resource attach + planner-visible descriptors | `CapabilityRuntime` + registry | INVOKE_CAPABILITY through descriptor side-effect policy | existing | already |

Per-slice before/after records are appended below as each slice lands.

### Slice 1 — Answer / Repair

Before: the only production answer path was the synchronous
`POST /api/v1/projects/{id}/answers` and
`POST /api/v1/projects/{id}/answers/{answerId}/repair` endpoints backed by
`AgentOrchestrator`'s 3-call workflow; the HTTP request blocked until the whole
model workflow finished.

After:

- migrated path: `POST /api/v1/projects/{projectId}/agent-runs`
  (`operation=ANSWER_TIP|RESUME_ANSWER`) → `202` + `{runId}` → frontend polls
  `GET /api/v1/projects/{projectId}/agent-runs/{runId}` (phase comes from the
  persisted run events; terminal statuses `completed|failed`) → canonical graph
  refresh after the run reaches a terminal state.
- retired path: sync `submitAnswer` / `repairAnswer` controller endpoints,
  `AgentCommandService.submitAnswer/repairAnswer`,
  `AgentOrchestrator.answerActiveNodeAndDraftNext` /
  `repairAnswerProcessingAndDraftNext`, and their private support code.
- compatibility path: none needed — the browser is the only production caller.
- remaining legacy: `draftNextQuestion` / `generateSpec` / `replaceQuestion`
  stay on `AgentOrchestrator` until slices 2–3.

Runtime hardening included in this slice (parity with legacy invariants that
the new cycle had not yet enforced):

- selected-option ownership validated against the exact answering node
  (random/cross-node/sibling-route option ids rejected before any Answer is
  persisted);
- answer input policy preserved (at least one meaningful input; free text only
  when the node allows it);
- stale-target rejection: an enqueued run whose recorded input node is no
  longer the active route tip at execution time fails instead of answering the
  wrong node;
- duplicate-submit protection at enqueue time: `ANSWER_TIP` against an
  already-answered tip either auto-converts to `RESUME_ANSWER` (202, cycle
  failed mid-way) or is rejected synchronously with `409
  ANSWER_ALREADY_FINALIZED` (tip already advanced);
- the run read API exposes the real persisted phase plus
  `producedAnswerId`/`producedPatchId`/`producedSpecSnapshotId` so clients can
  reconcile without guessing.

Deployment note: the answer flow now requires the background run worker.
Updated post-cutover hardening: the async AgentRun path is the ONLY
production mutation path, so `application.yml` now defaults BOTH
`SPEC_AGENT_BRAIN_ENABLED=true` and `SPEC_AGENT_BRAIN_WORKER_ENABLED=true`
in the default profile — real-stack deployments (including E2E `bootRun`) get
a working worker out of the box. A process that explicitly disables its
worker can never hide it: `/api/health` reports
`503 AGENT_WORKER_UNAVAILABLE`. The test profile keeps the worker off and
stays deterministic because tests drive `RunWorker` synchronously through
the test drivers.

### Slice 2 — Question / continuation

Before: the initial/continuation question was drafted synchronously through
`POST /api/v1/projects/{id}/questions/next` → `AgentOrchestrator.draftNextQuestion`
(the legacy 1-call DRAFT_NODE workflow with its own prompt, reflection gate, and
projection task input).

After:

- migrated path: `POST /agent-runs` with
  `operation=DRAFT_QUESTION` → `202` + `{runId}` → the worker executes a pure
  continuation (`DecisionCycleService`): ONE DECISION call against the frozen
  snapshot — never a mechanical STATE_UPDATE — then the shared fail-closed chain
  (validator → policy → auto-execute / persist proposal + AWAITING_APPROVAL /
  deny). An auto-executed `REQUEST_USER_INPUT` lands as an INTERACTION node via
  the runtime: the route's root node on an empty route, a tip child afterwards.
- policy extension: appending the bootstrap root node to a route without a tip
  is classified as append-only (auto-executable), mirroring the existing
  tip-child rule; a null anchor over a non-empty route stays fail-closed.
- stale-target rejection mirrors the answer cycle: the run's recorded tip must
  still be the active route's tip at execution time, and the run's route must
  still be the active route.
- frontend: `draftQuestion()` creates the run and polls it; FAILED/unknown
  outcomes reconcile canonical reads before offering a retry keyed to the
  pre-draft graph state. The fork flow keeps its durable-first sequencing:
  fork persists (and activates) the route first, the draft runs after, and a
  failed draft leaves the route in place with retry targeted at that route.
- retired path: sync `draftNextQuestion` controller/service endpoints,
  `AgentOrchestrator.draftNextQuestion`, `DraftQuestionResponse`,
  `AgentRunResult`/`FakeAgentRunResult`, and the Stage-A record-proposal-only
  decision cycle.
- remaining legacy: `AgentTaskType.DRAFT_NODE`, its TaskPromptCatalog prompt,
  and `NodeReflectionGate` stay until slice 3 retires `replaceQuestion`
  (the last DRAFT_NODE consumer).

### Slice 3 — Artifact/spec + replacement

Before: spec generation was the synchronous `POST /api/v1/projects/{id}/specs/generate`
(`AgentOrchestrator.generateSpec`, DRAFT_SPEC over the legacy ModelGateway);
regeneration was the synchronous `POST /api/v1/projects/{id}/nodes/{id}/regenerate`
with an authored-replacement compatibility branch and a model-powered branch
(`AgentOrchestrator.replaceQuestion`, redirected DRAFT_NODE).

After:

- artifact path: `POST /agent-runs` with `operation=GENERATE_ARTIFACT` → 202 +
  `{runId}` → the worker executes ONE `ARTIFACT_GENERATION` call against a new
  versioned Python contract (`POST /v1/artifacts`, protocol
  `agent-artifact.v1`, strict Pydantic with unknown-field rejection, no
  runtime-owned ids, per-section source grounding). The legacy grounding
  semantics are preserved verbatim: every section must cite an allowed source
  ref (`SpecGroundingGate`), all refs are re-validated against the frozen
  snapshot (`SpecSourceReferenceGuard`), and unresolved items stay derived-only.
  A route without a tip node is still rejected synchronously with
  `409 NO_ACTIVE_TIP_NODE`.
- replacement path: `POST /agent-runs` with `operation=REGENERATE_NODE`
  (explicit `nodeId`, `sourceRouteId`, optional instruction) → ONE DECISION
  returns only the replacement question CONTENT; topology stays deterministic
  in `RouteService.commitReplacementFromNode` (old route SUPERSEDED, new OPEN
  route with a fresh identity and source-route provenance — unchanged),
  followed by the durable regenerate context snapshot on the replacement
  route. Enqueue-time guards keep the readable API errors (unknown/foreign
  node → 404 NODE_NOT_FOUND, root replacement → 409 REGENERATE_ROOT_NOT_SUPPORTED).
- retired paths: sync `specs/generate` and `nodes/{id}/regenerate` endpoints,
  the authored-replacement DTO fields, and — production references now at
  zero — the whole legacy reasoning chain: `AgentOrchestrator`,
  `FakeAgentOrchestrator`, `TaskPromptCatalog` (DRAFT_SPEC/DRAFT_NODE),
  `AgentPromptRenderer`, `StructuredOutputMapper`,
  `StructuredModelOutputParser`, `NodeReflectionGate`,
  `ModelContextProjectionBuilder`, the legacy
  `ModelGateway`/`OpenCodeZenModelGateway`/`FakeModelAdapter` chain, and their
  dedicated tests. The frozen OpenCode transport survives untouched inside the
  inference broker (`ModelInferenceGateway` →
  `OpenCodeModelInferenceGateway`); grounding validators still used by the
  runtime are kept.
- frontend: `generateSpec()` and `regenerateNode()` create runs and poll them;
  FAILED/unknown outcomes reconcile through the shared fail-closed
  reconciliation helpers before any retry affordance appears.

## 3. Legacy retirement ledger

| Class / endpoint | Disposition | Where |
|---|---|---|
| `POST /answers`, `POST /answers/{id}/repair` | removed | slice 1 |
| `AgentCommandService.submitAnswer/repairAnswer` | removed | slice 1 |
| `AgentOrchestrator.answerActiveNodeAndDraftNext` / `repairAnswerProcessingAndDraftNext` | removed | slice 1 |
| `POST /questions/next`, `AgentOrchestrator.draftNextQuestion`, `DraftQuestionResponse`, `AgentRunResult`/`FakeAgentRunResult` | removed | slice 2 |
| `TaskPromptCatalog` `DRAFT_NODE` prompt, `AgentTaskType.DRAFT_NODE`, `NodeReflectionGate` | removed (last consumer `replaceQuestion`) | slice 3 |
| `POST /specs/generate`, `AgentOrchestrator.generateSpec`, `DRAFT_SPEC` prompt | removed | slice 3 |
| authored-replacement compatibility branch (`replacementQuestion` DTO fields + `RouteService.regenerateFromNode`) | removed (no production caller; verified) | slice 3 |
| `AgentOrchestrator.replaceQuestion` | removed | slice 3 |
| `AgentOrchestrator`, `TaskPromptCatalog`, `AgentPromptRenderer`, legacy `ModelGateway` chain | removed when production references reach zero | final cleanup |

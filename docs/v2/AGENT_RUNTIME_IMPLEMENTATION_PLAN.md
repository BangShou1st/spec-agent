# Agent Runtime V2 Implementation Plan

> Status: **Approved implementation order — 2026-08-21.**
> Updated after owner approval of `CODE_ARCHITECTURE_REVIEW_2026-08-21.md`.
> The migration runs as four large stages (A–D). The Python Agent Brain is bootstrapped in **Stage A**, before any new V2 reasoning logic is written; it is no longer a late optional phase.

## 1. Objective

Evolve the current workflow-oriented Agent layer into the target Graph Reasoning Runtime **without breaking the proven Graph/Route/Recovery foundations**.

This plan implements the canonical `docs/v2` architecture. It deliberately separates product model, runtime contracts, UI projection and capability extensions so later work remains high-cohesion and low-coupling.

## 2. Non-negotiable Invariants

Preserve unless a separately reviewed migration explicitly replaces them:

- immutable finalized Answer;
- route isolation and shared-node identity;
- Active / Focus / Visibility separation;
- replacement/regenerate history isolation;
- AnswerPatch repair checkpoint semantics;
- ContextSnapshot lineage-based context;
- AgentRun failure persistence and traceability;
- Runtime-owned IDs/provenance;
- Model Gateway/provider boundary;
- no LLM direct persistence;
- Agent V2 work does not casually change the frozen OpenCode HTTP transport contract; provider transport changes require separate evidence/review.

## 3. Migration Strategy

Do not rewrite the entire Agent layer in one change. Introduce stable seams first, then move behavior behind them.

New reasoning code (prompts, reflection/planning orchestration, decision parsing) is written in **Python from the first stage**. Authoritative runtime code stays in Java permanently.

```text
Current workflow
      |
      v
cross-language contracts + Python brain bootstrap      (Stage A)
      |
      v
answer cutover + advisor policy + async non-blocking UI (Stage B)
      |
      v
free graph interaction + operation history              (Stage C)
      |
      v
capability runtime + resource nodes                     (Stage D)
```

## 4. Stage Overview

| Stage | Focus | Exit gate (summary) |
|---|---|---|
| A | V2 runtime foundation + Python brain bootstrap | Python completes a deterministic DECISION through the Java inference broker; zero production DB access; legacy paths green |
| B | Answer cutover (3→2 calls) + Advisor runtime + non-blocking UI | normal answer = exactly 2 serialized provider calls; stale proposal cannot mutate Graph; pending UX works against real run states |
| C | Graph workspace V2: generic nodes, free continuation, semantic relations, undo/redo | empty project usable without model calls; Question workflow backward compatible |
| D | Capability foundation: registry, adapters, resource nodes | capability results enter the next cycle as provenance-preserving observations, never auto-confirmed truth |

Class-by-class code dispositions live in `CODE_ARCHITECTURE_REVIEW_2026-08-21.md`. Each stage below lists deliverables and its full exit gate.

## 5. Stage A — V2 Runtime Foundation + Python Brain Bootstrap

Deliverables:

- root `AGENT.md` / `CLAUDE.md` aligned with canonical V2 authority (done at approval time);
- baseline map of current `AgentOrchestrator`, `ContextBuilder`, `ModelContextProjectionBuilder`, `TaskPromptCatalog`, gates, `AnswerPatchService`, `NodeService`, `RouteService`, `AgentRun` and frontend workspace store; retain deterministic Fake scenarios for answer, repair, fork/re-answer/replacement/spec; record current call-count/latency instrumentation. No production model behavior change from this item alone;
- shared/versioned AgentInput/Decision wire contract + golden fixtures; contracts use generic Graph/action language (no question-workflow names); runtime-owned IDs and allowed source refs explicit; unknown fields and unknown protocol versions rejected;
- Java `AgentInputSnapshotBuilder` over current ContextSnapshot/graph facts, keeping `ContextSnapshot` compatibility; explicit fields for anchor, focus/read context, lineage, effective route-scoped answers/patches, selected relations, decisions/constraints, allowed source refs, resource context, capability descriptors, autonomy inputs; project title carried only as low-authority metadata and never promoted to objective;
- new Python `agent-brain/` service (`GET /health`, `POST /v1/state-updates`, `POST /v1/decisions`) with strict Pydantic contracts (`extra = forbid`) and explicit `protocolVersion`;
- Python prompt/orchestration modules for STATE_UPDATE and DECISION;
- Java lower-level `ModelInferenceGateway` preserving the frozen OpenCode transport, plus the internal authenticated inference broker for Python: no API key in Python, no arbitrary URL/header forwarding, no provider fallback, no hidden retry;
- Spring background AgentRun executor + append-only run event/phase persistence (phases such as CREATED / SNAPSHOT_BUILT / STATE_UPDATING / DECIDING / PROPOSAL_CREATED / AWAITING_APPROVAL / EXECUTING / WAITING_USER / COMPLETED / FAILED / STALE);
- strict fail-closed Java validation of every Python result;
- Fake Python/model path for deterministic integration tests;
- Docker/dev wiring for Spring + Python + Postgres;
- ArchUnit boundary tests for the final Java package layout `com.specagent.agent.{contract,snapshot,decision,broker,runevent,runtime}` (Stage B adds `policy` and `action`): contracts stay free of repository/service/provider dependencies; decision has no repositories; broker reaches neither repositories nor credentials/provider internals; runtime never touches model/gateway packages.

Explicitly NOT in Stage A: Skill/MCP, broad Graph schema/UI rewrite, product-visible behavior change.

Exit gate:

- Python can complete a deterministic DECISION through the Java inference broker;
- Python has zero production DB access; the provider key never appears in any Python response/log/fixture;
- old synchronous V1 paths still work; existing deterministic tests remain green;
- architecture tests reject direct repository dependency from contract/decision packages.

## 6. Stage B — Answer Cutover + Advisor Runtime + Non-blocking UI

Answer path convergence (3 calls → 2):

```text
persist immutable Answer
   |
Call 1: STATE_UPDATE  (interpret + grounded claims/patch)
   |
validate/persist AnswerPatch checkpoint
   |
Call 2: DECISION      (reflection + plan + primary action payload)
```

- if the primary action is `REQUEST_USER_INPUT`, the Question proposal is included in the same Decision response; a third "question writer" call is forbidden in the normal path;
- preserve repair semantics: once the Answer exists, retry resumes from the safe checkpoint and never creates a second Answer;
- preserve source grounding and structured validation; no hidden automatic provider retry; trace records state-update and decision stages separately.

Generic action proposal machinery:

- initial action families: CREATE_NODE, UPDATE_NODE, CONNECT_NODE, CREATE_ROUTE, REQUEST_USER_INPUT, RESPOND_TO_USER, INVOKE_CAPABILITY, GENERATE_ARTIFACT, WAIT;
- one primary action per Decision Cycle; no hidden fan-out;
- stale-proposal protection: proposals carry `baseContextSnapshotId` / `baseContextHash` / base route-anchor refs / idempotency key; the executor rejects `STALE_CONTEXT` and reruns from a new snapshot instead of silently rebasing;
- Runtime-owned loop limits: max decision steps, repeated/no-progress detection, stop on waiting-user/approval/failure/policy denial.

Advisor Mode (default before any autonomous execution):

- policy evaluates Runtime facts (mutation scope, lifecycle state, confirmed-intent change, destructiveness, external side effects, permission scope, replay safety), not model confidence alone;
- distinguish read-only/internal operations, visible Graph mutations, confirmed-intent changes, destructive/history operations, external side effects;
- proposal lifecycle read model: `PROPOSED -> ACCEPTED | MODIFIED | REJECTED | EXPIRED` (distinct from Node knowledge state).

Asynchronous command surface and non-blocking UI:

- `POST /api/v2/projects/{projectId}/agent-runs` -> 202 + runId; background worker executes; frontend polls run/graph status (no WebSocket/SSE required in this stage);
- Fork/new-route appears immediately; virtual pending card projected from in-flight run state; atomically replaced by the validated Node; never persist a half-generated Node for animation;
- implement the `UI_UX_IMPROVEMENT_PLAN.md` checklist: vertical option layout, Q1/Q2 labels preserving question text, single Latest marker, friendly route labels and multi-route Shared Node badges, route-scoped answer display, Focus highlight without isolation, hover/focus action toolbar, answer input persistence across drag/navigation/submission (dedicated input draft store keyed by node + route/read context), truthful dynamic phase copy, reveal without whole-graph relayout;
- scoped lockouts instead of whole-workspace blocking.

Exit gate:

- ordinary successful answer turn uses exactly 2 serialized provider calls; answer count remains one through failure/repair;
- user sees durable answer save/progress without a frozen canvas; route/pending projection appears before model completion;
- stale proposals cannot mutate Graph; important mutations require confirmation in Advisor Mode; high-risk/external side effects cannot self-authorize; accept/reject is traceable;
- legacy recovery scenarios remain green.

## 7. Stage C — Graph Workspace V2

Generic Workspace Unit model, introduced incrementally without breaking existing Question nodes:

1. additive node persistence migration: existing identity/history columns + `kind`, `subtype`, `content jsonb`, `author_kind`, nullable `knowledge_status`; existing rows interpreted as INTERACTION/QUESTION; relax obsolete `question NOT NULL` only in a later migration after compatibility tests; no physical table per subtype;
2. user-created blank/draft Nodes and user-authored Knowledge content; project creation remains 0 model calls;
3. Question Nodes remain answerable with immutable route-scoped Answers;
4. continuation from any Node; continuation from a non-tip source creates a branch/Route, never historical insertion;
5. explicit transactional graph mutation commands (CreateNode, AppendContinuation, CreateBranchAndAppend, CreateSemanticRelation, ReviseNode) owned by Runtime command handlers; external protocol action names stay generic;
6. semantic relations stored separately from visible continuation arrows; model-inferred relations are Advisor proposals;
7. shared Node remains one identity across routes; route-scoped answers stay explicit with neutral no-Focus ambiguity handling;
8. any Node can anchor a contextual AI query returning `RESPOND_TO_USER` without forcing mutation;
9. frontend: generic `GraphNodeCard` shell (header/body-renderer registry/action toolbar) instead of per-type card classes.

Graph Operation History / Undo Redo:

- typed operation log with actor/targets/before-after refs/reversibility;
- Undo as operation-specific compensation (draft restore, hide/archive/retract creation, revision/branch for finalized answers); Redo only when preconditions still hold;
- periodic materialized checkpoints allowed as optimization, never replacing operation semantics;
- do not delete immutable Answers to implement Undo; do not treat ContextSnapshot as a full undo snapshot; do not promise external side effects are reversible;
- downstream Agent derivation of undone operations handled explicitly, with traceability.

Exit gate:

- empty project is usable without a model call; user can author and branch from manual nodes;
- AI can answer about a node without forcing mutation;
- current Question workflow remains backward compatible;
- no node-type/business-agent explosion; undo/redo precondition tests pass.

## 8. Stage D — Capability Foundation

Only after Stage C stabilizes.

- `CapabilityRegistry`/`CapabilityRuntime` with generic adapter boundary:
```text
CapabilityRuntime
  ├ InternalCapabilityAdapter
  ├ SkillAdapter
  └ MCPAdapter
```
- capability descriptors (id, version, schemas, readOnly, sideEffectClass, permissions) filtered by permission before model exposure; Planner never branches on implementation class names;
- MCP adapter intentionally distinguishes tools/resources/prompts; host owns connections, credentials and side-effect policy;
- Resource Node subtypes (FILE/IMAGE/URL/REPOSITORY/...) added incrementally; no Agent per subtype; large contents retrieved as bounded excerpts/chunks with provenance, never full prompt injection;
- capability results enter the next bounded cycle as provenance-preserving observations; never auto-confirmed Graph truth; retry/idempotency metadata owned by the Runtime.

Exit gate:

- Planner sees only filtered descriptors; irrelevant capabilities are not called;
- capability success/failure scenarios pass without duplicate mutations;
- external side effects require correct approval policy.

## 9. Prompt Migration

Do not create a growing prompt catalog for every new operation/resource.

V2 prompts live in the Python brain and converge toward a small set of capabilities:

1. **STATE_UPDATE** — answer/evidence -> grounded claims/patch.
2. **DECISION** — reflection + planning + primary action/response payload.
3. **ARTIFACT_GENERATION** — Spec/summary/report under source grounding.

Optional capability-specific prompts live with their capability adapter/skill, not in a monolithic core `TaskPromptCatalog`. Legacy `TaskPromptCatalog` remains only as a V1 compatibility adapter until cutover completes.

Every prompt change must remain domain-general and preserve the Simplified Chinese user-visible language contract while leaving machine protocol keys/enums unchanged.

## 10. Evaluation and Anti-Overfitting Gate

Each Agent behavior change must evaluate:

- groundedness / unsupported assertions;
- conflict detection;
- useful unknown handling;
- user accept/reject/correction rate;
- duplicate/route contamination;
- illegal relation/history attempts;
- model calls per operation;
- total latency and first visible progress;
- loop steps/capability calls;
- failure/recovery idempotency.

Use varied domains and paraphrased scenarios; avoid exact prose assertions except protocol/schema tests. A prompt change requires a generalized contract explanation and regression coverage across more than one scenario family.

## 11. Stop Conditions for the Migration Itself

If implementation discovers that a stage requires changing frozen Graph invariants, broad database redesign, or a generic distributed workflow engine, stop and review the architecture rather than improvising inside the current stage.

# Agent Runtime V2 Implementation Plan

## 1. Objective

Evolve the current workflow-oriented Agent layer into the target Graph Reasoning Runtime **without breaking the proven Graph/Route/Recovery foundations**.

This plan implements the canonical `docs/v2` architecture. It deliberately separates product model, runtime contracts, UI projection and optional Python/Capability extensions so later work remains high-cohesion and low-coupling.

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
- no LLM direct persistence.

## 3. Migration Strategy

Do not rewrite the entire Agent layer in one change. Introduce stable seams first, then move behavior behind them.

```text
Current workflow
      |
      v
stable contracts
      |
      v
state projection + decision engine
      |
      v
generic action/policy/executor
      |
      v
free graph interaction + UI projection
      |
      v
capability runtime
      |
      v
optional Python brain
```

## 4. Phase 0 — Baseline and Architecture Guards

Before behavior changes:

- map current `AgentOrchestrator`, `ContextBuilder`, `ModelContextProjectionBuilder`, `TaskPromptCatalog`, gates, `AnswerPatchService`, `NodeService`, `RouteService`, `AgentRun` and frontend workspace store;
- add/retain architecture tests proving Model cannot persist directly;
- record current normal answer call count and latency test instrumentation;
- preserve deterministic Fake scenarios for answer, repair, fork/re-answer/replacement/spec.

No production model behavior change in this phase.

## 5. Phase 1 — Introduce Canonical Contracts (No Product Behavior Change)

Add versioned internal contracts/interfaces such as:

```text
AgentInputSnapshot
AgentObservation
AgentDecision
AgentActionProposal
AgentDecisionEngine
AgentPolicyDecision
AgentExecutionResult
```

Goals:

- current Java implementation can satisfy the interface;
- no database schema requirement solely to create the contract;
- contracts contain generic Graph/action language, not current question-workflow names;
- runtime-owned IDs and allowed source refs are explicit.

Acceptance:

- existing deterministic tests remain green;
- production outputs/behavior remain unchanged;
- architecture tests reject direct repository dependency from Decision Engine package.

## 6. Phase 2 — Deterministic AgentInputSnapshot Builder

Evolve current Context/Projection code into a deterministic state projection seam.

Keep current `ContextSnapshot` compatibility, but introduce explicit fields for:

- operation anchor;
- Focus/read context when provided;
- route lineage;
- effective route-scoped answers;
- effective claims/patches;
- semantic relation subset;
- important decisions/constraints;
- allowed source refs;
- selected resource context;
- available capability descriptors;
- autonomy/action policy inputs;
- low-authority metadata such as project title.

Critical fix:

- remove the assumption that a “meaningful” project title should drive the first question;
- project title remains metadata and cannot become objective/confirmed requirement by prompt rule.

Acceptance scenarios include empty project and misleading title.

## 7. Phase 3 — Converge Answer Processing from 3 Calls to 2

Current normal answer pipeline conceptually performs:

```text
INTERPRET_ANSWER
DRAFT_ANSWER_PATCH
DRAFT_NODE
```

Target:

```text
persist immutable Answer
   |
Call 1: STATE_UPDATE
  interpret + grounded claims/patch
   |
validate/persist AnswerPatch
   |
Call 2: DECISION
  reflection + plan + primary action payload
```

If primary action is `REQUEST_USER_INPUT`, Decision response includes the Question proposal; do not issue a third “question writer” call.

Requirements:

- preserve repair semantics: once Answer exists, retry resumes from safe checkpoint and never creates a second Answer;
- preserve source grounding and structured validation;
- no hidden automatic provider retry;
- trace records state-update and decision stages separately.

Acceptance:

- answer count remains one through failure/repair;
- normal successful answer turn uses target 2 serialized model calls;
- new question is route-correct and source-grounded;
- failures retain recoverable checkpoint.

## 8. Phase 4 — Decision Engine and Bounded Planner

Replace “always draft next question” as the only continuation with one primary generic action per Decision Cycle.

Initial action families:

- CREATE_NODE
- UPDATE_NODE
- CONNECT_NODE
- CREATE_ROUTE
- REQUEST_USER_INPUT
- INVOKE_CAPABILITY
- GENERATE_ARTIFACT
- WAIT

Reflection and Planning are logical fields of one default Decision call.

Add Runtime-owned limits:

- max decision steps per run;
- repeated/no-progress detection;
- stop on waiting user/approval;
- stop on failure/policy denial;
- capability follow-up step budget.

Do not add multi-Agent orchestration here.

## 9. Phase 5 — Policy Engine and Advisor Mode

Implement default Advisor Mode before optional autonomous execution.

Policy evaluates Runtime facts, not only model-supplied confidence.

Must distinguish:

- read-only/internal low-risk operations;
- visible Graph mutations;
- changes to confirmed user intent;
- destructive/history operations;
- external capability side effects.

Add proposal lifecycle/read model for frontend:

```text
PROPOSED -> ACCEPTED | MODIFIED | REJECTED | EXPIRED
```

This proposal lifecycle is not the same as Node knowledge state.

Acceptance:

- important mutations require confirmation in Advisor Mode;
- low-risk internal reads need no unnecessary modal;
- high-risk/external side effects cannot self-authorize;
- acceptance/rejection is traceable.

## 10. Phase 6 — Graph Model V2 Product Capabilities

Introduce the more general Workspace Unit model incrementally, without breaking existing Question nodes.

Required capabilities:

1. user-created blank/draft Node;
2. user-authored Knowledge content;
3. Question Node remains answerable;
4. continuation from any Node;
5. continuation from non-tip creates a branch/Route rather than historical insertion;
6. semantic relations stored separately from visible continuation arrows;
7. shared Node remains one identity across routes;
8. route-scoped answers remain explicit;
9. any Node can anchor contextual AI query.

Migration should prefer additive schema/content changes and adapters over rewriting all existing Nodes at once.

## 11. Phase 7 — Non-blocking UI / Pending Projection

Implement `UI_UX_IMPROVEMENT_PLAN.md` against real Runtime states.

Key rule:

- do not persist a half-generated Node solely for animation;
- create Route/AgentRun immediately;
- frontend renders a virtual pending card from in-flight operation state;
- atomically replace with real validated Node on success.

Also implement:

- vertical option layout;
- Q1/Q2 labels instead of current/history labels while preserving question text;
- at most one Latest marker;
- route friendly labels and multi-route Shared Node badges;
- route-scoped answer display;
- Focus highlight without isolation;
- hover/focus action toolbar;
- answer input persistence during drag/navigation/submission;
- dynamic truthful phase copy;
- reveal without whole-graph relayout.

## 12. Phase 8 — Graph Operation History / Undo Redo

Implement typed operation history and operation-specific compensation.

Start with local reversible Graph authoring actions, then extend to route/answer domain compensation.

Do not:

- delete immutable Answers to implement Undo;
- treat ContextSnapshot as a full undo snapshot;
- promise external capability side effects are reversible.

Add deterministic tests for undo/redo preconditions and downstream Agent derivation handling.

## 13. Phase 9 — Capability Runtime

Implement `CapabilityRegistry` and generic adapter boundary.

Initial architecture:

```text
CapabilityRuntime
  ├ InternalCapabilityAdapter
  ├ SkillAdapter
  └ MCPAdapter
```

MCP adapter must intentionally distinguish tools/resources/prompts and preserve host-controlled permissions/credentials.

Planner sees only filtered capability descriptors.

Capability result enters the next bounded cycle as a provenance-preserving observation/resource; it is not auto-confirmed Graph truth.

## 14. Phase 10 — Resource Nodes

Once Capability Runtime exists, add Resource subtypes such as FILE/IMAGE/URL/REPOSITORY incrementally.

Do not add one Agent per resource subtype.

Typical flow:

```text
Resource Node
   |
INVOKE_CAPABILITY
   |
provenance-preserving result
   |
Decision Cycle
   |
proposal(s)
```

Large resource contents use retrieval/chunk/excerpt references rather than full prompt injection.

## 15. Phase 11 — Optional Python Decision Engine

Only after Java contracts are stable and the local Decision Engine works.

Add a versioned `RemotePythonDecisionEngine` adapter if evaluation shows concrete benefit.

Spring continues to own:

- AgentInputSnapshot;
- Graph state;
- Policy/Validator;
- Capability host;
- persistence/transactions/secrets.

Python returns `AgentDecision`; it does not query production DB or mutate Graph directly.

## 16. Prompt Migration

Do not create a growing prompt catalog for every new operation/resource.

Converge toward a small set of capabilities:

1. **STATE_UPDATE** — answer/evidence -> grounded claims/patch.
2. **DECISION** — reflection + planning + primary action payload.
3. **ARTIFACT_GENERATION** — Spec/summary/report under source grounding.

Optional capability-specific prompts live with their capability adapter/skill where appropriate, not in a monolithic core `TaskPromptCatalog`.

Every prompt change must remain domain-general and preserve the Simplified Chinese user-visible language contract while leaving machine protocol keys/enums unchanged.

## 17. Evaluation and Anti-Overfitting Gate

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

Use varied domains and paraphrased scenarios; avoid exact prose assertions except protocol/schema tests.

## 18. Implementation Order Recommendation

Recommended practical order:

```text
Contracts
 -> AgentInputSnapshot
 -> answer 3-call to 2-call convergence
 -> Decision Engine
 -> Advisor Policy
 -> Graph V2 free-node/connection semantics
 -> UI pending/non-blocking UX
 -> Operation History
 -> Capability Runtime
 -> Resource Nodes
 -> optional Python
```

This gives Agent value early while keeping later Skill/MCP/file support behind stable boundaries.

## 19. Stop Conditions for the Migration Itself

If implementation discovers that a phase requires changing frozen Graph invariants, broad database redesign, or a generic distributed workflow engine, stop and review the architecture rather than improvising inside the current phase.

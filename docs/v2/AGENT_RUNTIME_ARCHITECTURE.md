# Agent Runtime Architecture V2

## 1. Goal

将当前固定 task pipeline 演进为一个 **bounded Graph Reasoning Runtime**。

Agent 的职责是决定下一步最有价值的动作；Runtime 的职责是决定哪些输入可信、哪些动作合法、什么会进入持久化历史。

## 2. Canonical Runtime Flow

```text
User / Graph / Capability Event
            |
            v
+-------------------------------+
| Spring Graph Runtime          |
| authoritative state           |
| route/history/source refs     |
+---------------+---------------+
                |
        AgentInputSnapshot
                |
                v
+-------------------------------+
| Decision Engine               |
|                               |
| Reflection + Planning         |
| default: ONE model call       |
+---------------+---------------+
                |
        Action Proposal
                |
                v
+-------------------------------+
| Policy Engine + Validator     |
+-----------+-------------------+
            |
      +-----+-------------------+
      |                         |
      v                         v
Graph Executor          Capability Runtime
      |                         |
      |                    Observation
      +-----------+-------------+
                  |
                  v
             New Graph State
                  |
                  v
        stop or next bounded cycle
```

## 3. Important Clarification: Reflection Is Not a Mandatory Extra Call

`Observe -> Reflect -> Plan -> Act` describes **logical responsibilities**, not four LLM requests.

Default Decision Cycle should combine reflection and planning into one structured model response:

```text
AgentInputSnapshot
        |
        v
one Decision call
  - observations
  - important gaps/conflicts/risks
  - primary next action
  - grounded payload/evidence refs
```

Do not implement a mandatory chain such as:

```text
LLM Reflection
  -> LLM Planner
  -> LLM Question Writer
```

That would increase latency without proving better product quality.

An additional Critic/Reflection model call is allowed only when explicit policy/evaluation evidence justifies it, for example:

- high-risk proposal;
- low-confidence or internally inconsistent proposal;
- unresolved conflict before final artifact publication;
- capability output that materially changes user intent;
- repeated low-progress cycles;
- evaluation shows a specific class of mistakes that one extra check fixes generally.

It must not become a domain-specific workaround.

## 4. Answer Processing Call Budget

Current production flow uses separate `INTERPRET_ANSWER -> DRAFT_ANSWER_PATCH -> DRAFT_NODE` calls. V2 should not preserve three calls merely by renaming them as actions.

Target normal answer path:

```text
User submits Answer
        |
        v
Runtime persists immutable Answer
        |
        v
Call 1: Grounded State Update
  - interpret answer
  - produce claims/patch
        |
        v
Runtime validate + persist AnswerPatch checkpoint
        |
        v
Call 2: Decision Cycle
  - reflect on updated state
  - choose primary next action
  - if REQUEST_USER_INPUT, include the question content
        |
        v
Runtime validate + execute/propose
```

Baseline target: **2 serialized model calls for a normal answered-question turn**.

Do not add a third content-generation call for the next question if the Decision Cycle already selected `REQUEST_USER_INPUT`; the same decision output should carry the grounded question proposal.

This is a target architecture, not an excuse to weaken validation. If later deterministic/real evaluation proves a one-call state-update+decision contract is reliable, it may be considered separately; do not collapse it merely for speed without evidence.

## 5. Other Operation Budgets

Desired baseline behavior:

- Project creation: **0 model calls**.
- Create/edit blank user draft Node: **0 model calls**.
- Create/Fork Route structure: **0 model calls**; route appears immediately.
- Continue exploration from a Node: normally **1 Decision call** after Runtime context build.
- Contextual AI query that only answers: normally **1 model call**.
- Generate Spec/artifact: normally **1 generation call** plus deterministic Runtime grounding/validation; additional critic call only by explicit policy.
- Capability execution: capability call itself plus at most bounded follow-up Decision cycles.

These are latency/cost budgets to test, not prompts to artificially skip necessary domain validation.

## 6. Components

### 6.1 AgentInputSnapshot Builder

Lives with the authoritative Graph Runtime. Deterministically projects:

- anchor Node;
- current route/read context;
- lineage plus the effective canonical Answer/AnswerPatch sequence resolved along that lineage;
- selected semantic relations;
- confirmed decisions/constraints;
- allowed source refs;
- relevant resource excerpts/descriptors;
- available capabilities;
- current autonomy policy and allowed action families.

It does not call a model.

The builder's projection is **freeze-once, replay-always**: the first projection of a `ContextSnapshot` is persisted as an immutable frozen input projection (payload hash + durable projection schema version `agent-input-projection.v1` + mutable-source fingerprint set), and every later projection of the same snapshot — retry, resume, repair — replays that stored payload instead of re-reading live mutable records. Graph-mutating proposals run a live fingerprint staleness gate before any mutation; read-only proposals stay ungated. Pre-contract `DECISION_STARTED` evidence with no frozen row fails closed as `LEGACY_FROZEN_INPUT_UNAVAILABLE`. See `AGENT_MEMORY_AND_CONTEXT.md` §11.

### 6.2 Decision Engine

Consumes `AgentInputSnapshot` and returns `AgentDecision`:

- structured observations;
- important uncertainty/conflict/risk assessment;
- one primary next action proposal;
- evidence/source references;
- confidence signal;
- short user-safe rationale/summary if needed.

The Decision Engine does not persist Graph state.

### 6.3 Policy Engine

Determines whether the proposal can:

- execute automatically;
- be staged as a user-confirmable proposal;
- be rejected by policy;
- require additional validation/confirmation.

Policy is Runtime-owned. The model may suggest risk/confidence, but cannot self-authorize.

### 6.4 Validator

Validates invariants such as:

- referenced IDs exist and belong to the project/context;
- route/focus/history rules;
- allowed Node kinds/subtypes;
- no historical insertion/rewrite;
- provenance/source refs;
- action payload schema;
- autonomy/permission requirements;
- duplicate/idempotency constraints.

### 6.5 Graph Executor

Applies validated Graph mutations through domain services and transactions. It is the only layer that makes Agent-proposed graph changes durable.

### 6.6 Capability Runtime

Resolves `INVOKE_CAPABILITY` into Skill, MCP adapter, internal service or other provider. Tool/capability output is treated as an observation/resource with provenance, not automatically as confirmed Graph truth.

### 6.7 Conflict Intelligence boundary (frozen)

Conflict intelligence reuses the existing two-call answer cycle; it adds no protocol version and no extra model call. `STATE_UPDATE` may emit `kind=conflict, status=unresolved` claims when a new answer cannot coexist with the prior effective state under the same scope/time/resource conditions. Mere uncertainty, preference tension or ordinary prioritization is not a conflict.

For every decision other than `NODE_QUERY`, an effective unresolved conflict is a fail-closed planning boundary:

- `observation.conflicts` must be non-empty; and
- the primary action must be `REQUEST_USER_INPUT`, or `CREATE_NODE` with `kind=KNOWLEDGE`, `subtype=DECISION` and a non-blank decision rationale (a confirmable conflict-resolution proposal).

`WAIT`, unrelated continuations and silent assumptions are rejected. The Python brain enforces this before returning, and the Java `AgentBrainResponseValidator` independently mirrors the rule at the trust boundary. `NODE_QUERY` is intentionally exempt: it is a read-only contextual flow and must remain usable while unresolved conflicts exist; its mutation-confirmation rules are unchanged.

Agent-authored `KNOWLEDGE/DECISION` content is a `CONFIRMED_INTENT_CHANGE` (see `AGENT_AUTONOMY_MODEL.md`): it always lands as a user-confirmable proposal, never auto-executed on confidence.

## 7. Bounded Loop and Stop Conditions

Every Agent run must have a finite step budget and explicit terminal outcomes.

Stop when one of these is true:

- user input is required;
- a proposal requires approval;
- primary goal for this run is achieved;
- `WAIT` is selected;
- no useful action is available;
- step budget reached;
- repeated action/no-progress detected;
- capability/model failure requires user/runtime recovery;
- policy rejects further automatic actions.

No recursive autonomous loop may continue without a Runtime-owned budget.

## 8. Single-Agent First

V2 starts with one Decision Engine composed of clear modules/interfaces. Do not create Planner Agent, Critic Agent, File Agent, Research Agent, etc. as independent conversational agents by default.

If future evaluation shows a separate specialized evaluator is materially useful, add it behind a stable interface and only for that evaluated purpose.

## 9. Trace

Existing `AgentRun`/trace should evolve to record safe lifecycle facts such as:

```text
run_created
input_snapshot_built
state_update_called
state_update_validated
state_update_persisted
decision_called
action_proposed:<type>
policy:<decision>
action_executed | awaiting_approval
capability_started/completed
run_completed/failed
```

Trace records operational decisions and references, not hidden chain-of-thought or raw secrets/prompts.

## 10. Anti-Overfitting Rules

- Prompt speaks in Graph/state/action terms, not one business domain.
- No special branch such as `if ecommerce -> ask X`.
- Action types remain generic; risk/requirement/summary are payload/subtype semantics where possible.
- New resource/tool types register capabilities rather than modify Planner core `if/else` chains.
- Agent quality is validated across varied domains, vague starts, manual graph edits, route conflicts and capability scenarios.

## 11. Architecture Invariants (from Hardening Investigation)

### 11.1 Closed-Set Dispatch Exhaustiveness

Action family dispatch must use compile-time exhaustive switch (Java enum switch expression, Python exhaustive match/if-elif). String-literal switches with silent `default` branches are forbidden in production dispatch code — they allow new families to compile and run while being silently misrouted. The `ActionFamily` enum is the single declared source of truth; all policy, validation, and execution layers must consume it directly.

### 11.2 Lineage Single Source of Truth

`RouteHistoryResolver.resolveLineage` is the canonical lineage walker for the runtime kernel. Read-model layers may add project-scoping, root/tip invariant checks, and retracted-node filtering on top, but the core tip→parent traversal must not be reimplemented. Silent truncation on cycles or missing nodes (e.g., `orElse(null)`) is a correctness bug — lineage walk must fail closed with a typed error on structural violations.

### 11.3 Mutation Ownership Clarity

Route topology (lifecycle, fork, re-answer, replacement), graph commands (node creation, relation), and node-level operations (subtype, content) have distinct ownership boundaries. Crossing these boundaries without explicit delegation creates implicit coupling. Legacy methods with zero production callers should be migrated and removed to shrink the mutation surface.

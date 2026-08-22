# Requirement Agent Runtime

Status: first-version design freeze  
Date: 2026-08-17

## 1. Purpose

Requirement Agent Runtime is the internal runtime for Spec Agent.

It is not a general-purpose agent framework. It is a narrow runtime for requirement clarification, route branching, context replay, reflection gates, and source-traceable spec generation.

Its core responsibility is to let the model reason while preventing the model from owning history.

```text
Model handles cognition.
Runtime handles history.
Model proposes.
Runtime constrains.
Answer records.
Patch records.
Spec summarizes.
Sources prove.
```

## 2. Runtime Layers

```text
Deterministic Runtime Kernel
  - Project, Route, Node, Answer, ContextSnapshot, AnswerPatch, SpecSnapshot.
  - Lineage replay.
  - Route operations.
  - Answer immutability.
  - Status transitions.
  - Source tracing.

Agent Reasoning Layer
  - Gap analysis.
  - Planning.
  - Node drafting.
  - Answer interpretation.
  - Patch drafting.
  - Spec drafting.

Reflection Gates
  - Context guard.
  - Node quality gate.
  - Patch reflection gate.
  - Spec grounding gate.
```

## 3. Core Runtime Objects

### Project

A requirement exploration workspace.

```text
Project
- id
- title
- activeRouteId
- defaultProfileId
- createdAt
- updatedAt
```

`activeRouteId` is the current working focus. It is not the same thing as route lifecycle status.

### Route

An explicit exploration route.

```text
Route
- id
- projectId
- rootNodeId
- tipNodeId
- lifecycleStatus: open | superseded | archived | deleted
- label
- createdFromNodeId
- supersedesRouteId
- replacementOfNodeId
- createdByRunId
- createdAt
- updatedAt
```

A route is a view over node lineage. It is not the source of all node content.

Lifecycle status meanings:

- `open`: normal route; may be selected as `Project.activeRouteId`.
- `superseded`: replaced by regeneration; visible, inspectable, restorable, and forkable.
- `archived`: intentionally hidden or deprioritized; recoverable.
- `deleted`: soft-deleted; excluded from active context and normal workspace view.

### Node

An immutable clarification prompt in the exploration tree.

```text
Node
- id
- projectId
- parentNodeId
- createdByRunId
- supersedesNodeId
- question
- purpose
- options
- allowFreeAnswer
- createdAt
```

Node question fields are immutable after creation. Regeneration creates a replacement node; it does not edit the old node.

### Answer

An immutable user answer to a node.

```text
Answer
- id
- projectId
- routeId
- nodeId
- selectedOptionId
- freeText
- createdByUser
- createdAt
```

A historical answer must not be overwritten. Re-answering creates a new route, replacement node, or answer revision.

### AnswerPatch

Structured requirement changes derived from one Answer.

```text
AnswerPatch
- id
- projectId
- routeId
- sourceNodeId
- sourceAnswerId
- confirmedClaims
- assumptions
- constraints
- openQuestions
- conflicts
- risks
- createdByRunId
- createdAt
```

The current RequirementState is built by replaying patches along the active route lineage.

RequirementState may be cached, but cache is not source of truth.

### AgentRun

One controlled agent execution.

```text
AgentRun
- id
- projectId
- routeId
- triggerType
- inputNodeId
- contextSnapshotId
- producedNodeId
- producedAnswerId
- producedPatchId
- producedSpecSnapshotId
- status: created | context_built | model_called | reflected | persisted | completed | failed
- trace
- createdAt
- completedAt
```

Each user operation that requires model reasoning should create an AgentRun.

### ContextSnapshot

The exact context used for one AgentRun.

```text
ContextSnapshot
- id
- projectId
- routeId
- tipNodeId
- operationType
- includedNodeIds
- includedAnswerIds
- includedPatchIds
- excludedRouteIds
- specialInputs
- contextHash
- createdAt
```

`specialInputs` may include old question text and user regeneration instructions for regenerate operations.

### SpecSnapshot

A generated spec for one route tip.

```text
SpecSnapshot
- id
- projectId
- routeId
- tipNodeId
- contextSnapshotId
- format
- sections
- unresolvedItems
- sourceRefs
- createdByRunId
- createdAt
```

SpecSnapshot is not source of truth. It is a derived artifact.

## 4. Closed Agent Actions

The agent should not freely choose arbitrary system behavior. It may produce only bounded actions:

```text
ASK_NEXT_QUESTION
INTERPRET_ANSWER
REQUEST_CONFIRMATION
EXPLAIN_CONFLICT
SUGGEST_BRANCH
GENERATE_SPEC
STOP
```

The runtime decides whether the action is valid in the current operation.

## 5. AgentRun Lifecycle

### Initial requirement flow

```text
User submits initial requirement
→ create Project
→ create root Node
→ create open Route
→ set Project.activeRouteId
→ create AgentRun
→ build ContextSnapshot
→ run GapReflection
→ draft first clarification Node
→ run NodeReflection
→ persist Node
→ update Route.tipNodeId
→ complete AgentRun
```

### Normal answer flow

```text
User answers node
→ create AgentRun
→ build ContextSnapshot from active route lineage
→ create immutable Answer
→ interpret answer
→ draft AnswerPatch
→ run PatchReflection
→ persist Answer and AnswerPatch
→ replay RequirementState
→ run GapReflection
→ draft next Node or stop
→ run NodeReflection if a node is produced
→ persist next Node
→ update Route.tipNodeId
→ complete AgentRun
```

### Spec flow

```text
User requests spec
→ create AgentRun
→ build ContextSnapshot from active route lineage
→ replay RequirementState
→ run SpecReflection
→ draft SpecSnapshot
→ verify source references
→ persist SpecSnapshot
→ complete AgentRun
```

### Regenerate flow

```text
User regenerates node N with optional instruction
→ create AgentRun
→ mark selected route superseded
→ build ContextSnapshot from N.parent lineage
→ include old question text
→ include old purpose if present
→ include user regeneration instruction
→ exclude old answer and children
→ draft replacement node N'
→ run NodeReflection
→ persist N'
→ create replacement open Route
→ set Project.activeRouteId to replacement Route
→ complete AgentRun
```

## 6. Context Contract

The runtime must construct context before any model call.

Normal context includes only:

```text
Project.activeRouteId
→ Route.tipNodeId
→ parent lineage to root
→ answers on that lineage
→ patches derived from those answers
→ current profile
→ immediate user operation
```

Normal context excludes:

```text
Sibling route conclusions
superseded route patches unless restored
archived route patches unless restored
all deleted routes
spec text without source references
model memory not backed by runtime records
```

Regenerate context is stricter:

Allowed:

```text
old node parent lineage
old node question text
old node purpose if present
user regeneration instruction
```

Forbidden:

```text
old answer
old answer patch
old child nodes
old route spec snapshot
sibling route conclusions
```

## 7. Reflection Gates

Reflection must not be vague self-talk. Each gate produces structured output.

### Context Guard

Verifies that the ContextSnapshot was built from allowed sources.

### Gap Reflection

Determines what is missing, conflicting, assumed, or ready to finalize.

### Node Reflection

Checks that a node asks one main question, explains why it matters, provides useful options, allows free-form answer, and does not import disallowed context.

### Patch Reflection

Checks that an AnswerPatch does not overclaim, does not mark unsupported assumptions as confirmed, and identifies whether user confirmation is required.

### Spec Grounding Gate

Checks that confirmed spec claims have source references and unresolved content is labeled correctly.

## 8. Generic Claim Model

The runtime should store claims in a domain-neutral shape.

```text
Claim
- id
- kind: goal | stakeholder | scope | constraint | success_criterion | output_expectation | risk | assumption | open_question | conflict | other
- text
- status: confirmed | assumed | unresolved | rejected
- confidence
- sourceNodeId
- sourceAnswerId
```

Do not add runtime claim kinds that encode specific domains such as software features, marketing channels, ecommerce products, or course assignments.

## 9. Anti-Overfitting Rule

Runtime code may know about requirement mechanics:

```text
aspect
claim
patch
route
node
answer
context
source
spec section
reflection gate
```

Runtime code must not know about concrete domains:

```text
software project
marketing plan
startup pitch
ecommerce store
student assignment
sales script
legal memo
```

If a domain needs specialized behavior later, it must enter through a configurable RequirementProfile and still pass through the same runtime contracts.

## 10. Required Invariants

1. Current context is built from `Project.activeRouteId` and its route lineage.
2. A route may be open but not active.
3. Superseded routes do not affect active context unless restored or forked.
4. Deleted routes never affect active context.
5. Node question content is immutable after creation.
6. Answers are immutable records.
7. Regeneration does not include old answer, old patch, old children, or old spec.
8. SpecSnapshot is derived from one ContextSnapshot.
9. Confirmed spec claims have source references.
10. Runtime code has no concrete business-domain branches.

## 11. Stage A: V2 Run Events and Phases

Stage A of the V2 migration adds an append-only run event model next to the
existing `agent_runs` compatibility columns (which are kept unchanged):

```text
agent_run_events
- id
- run_id
- sequence (per-run, assigned atomically)
- phase
- event_type
- payload (sanitized JSON: hashes, counts, categories)
- created_at
```

Public phases (`com.specagent.agent.runevent.AgentRunPhase`):

```text
CREATED / SNAPSHOT_BUILT / STATE_UPDATING / STATE_UPDATED / DECIDING /
PROPOSAL_CREATED / AWAITING_APPROVAL / EXECUTING / WAITING_USER /
COMPLETED / FAILED / STALE
```

Rules:

1. Events are append-only; they are never updated or deleted.
2. Event payloads are sanitized trace/progress records — never prompt text,
   provider payloads, credentials, or hidden chain-of-thought.
3. UI progress text must derive from these real phases.
4. The V2 background worker (`V2AgentRunWorker`) executes queued
   `v2_decision_cycle` runs through the `AgentDecisionEngine` port. In Stage A
   it records proposals only and never mutates the Graph; the worker is off by
   default (`SPEC_AGENT_BRAIN_WORKER_ENABLED`).
5. The legacy synchronous orchestrator paths are unchanged.

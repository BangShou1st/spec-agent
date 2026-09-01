# Graph Model V2

## 1. Purpose

This document defines route, continuation, semantic relation, shared-node and answer semantics for the V2 workspace.

The Graph must support free exploration without becoming an unreadable generic graph editor.

## 2. Two Different Relation Concepts

### 2.1 Visible continuation edge

The primary Canvas arrow means:

> this exploration continued from this Node to the next Node.

```text
A ─────→ B
```

Keep this visual language simple and stable. Do not encode many semantic meanings with different arrow styles in the default Canvas.

A visible continuation edge is **not a universal causal claim**.

### 2.2 Semantic relation

The data model may additionally record relations such as:

- RELATED_TO
- DEPENDS_ON
- DERIVED_FROM
- CONFLICTS_WITH
- SUPPORTS

These relations are useful for reasoning and Inspector views, but are not shown as default Canvas edges. Users may reveal them selectively later.

This separation prevents the Graph from becoming a spider web while still allowing richer reasoning.

Semantic relations are stored separately in `node_relations`. They are reasoning/inspection facts only. A semantic relation must **never** mutate:

- `parentNodeId` or any lineage/continuation edge;
- a route's root/tip;
- route membership;
- the Active Route or the Focus Route.

Lineage stays a strict historical tree/forest: semantic relations never reparent nodes, never insert history, and never rejoin a branch into an existing canonical current-version node.

Relation semantics:

- `RELATED_TO` and `CONFLICTS_WITH` are symmetric;
- `DEPENDS_ON`, `DERIVED_FROM`, and `SUPPORTS` preserve source → target direction;
- `DEPENDS_ON` and `DERIVED_FROM` share one combined DAG cycle check;
- `SUPPORTS` cycles are allowed in phase 1;
- symmetric pairs are normalized for duplicate detection;
- reverse directional relations are distinct;
- different relation types may coexist between the same two canonical nodes.

### 2.3 Who may create relations

- deterministic provenance relations created by Runtime from known operations may be persisted automatically;
- user-created relations are explicit user operations;
- model-inferred semantic relations are proposals in Advisor Mode and must pass Policy/Validator before persistence;
- Agent confidence alone cannot turn an inferred relation into fact.

### 2.4 Canvas drag creates a pending relation proposal (Scheme C)

Dragging a connection `A → B` on the Canvas is **only** a client-side pending relation proposal. The drag itself must not modify lineage, routes, route membership, Active/Focus, or any other durable state.

- endpoints must be canonical Node IDs in the same project;
- self-relations, nonexistent/retracted/deleted endpoints, and invalid cross-project endpoints are rejected;
- the user then chooses a relation type in the proposal dialog;
- **Confirm** → the Runtime persists one durable semantic relation through a GraphOperation recorded in the operation history;
- **Cancel / Esc / click-away** → nothing is persisted; the workspace is left exactly as before the drag.

The Inspector may list relations, but Canvas drag → proposal chooser → confirm is the primary creation surface. The global relation layer is OFF by default; a selected node may reveal its 1-hop relations.

### 2.5 Relation state machine

A persisted semantic relation has a status:

```text
PROPOSED → ACCEPTED | RETRACTED
ACCEPTED → RETRACTED
RETRACTED → ACCEPTED (undo)
```

- `PROPOSED`: created by drag or Agent proposal; visually yellow dashed + semi-transparent; Inspector shows "N pending".
- `ACCEPTED`: user confirmed; enters the normal relation layer.
- `RETRACTED`: soft-deleted; not shown on Canvas; recoverable by undo.

Agent-inferred relations (`origin = AGENT`) also start as `PROPOSED` and require explicit user confirmation before becoming `ACCEPTED`.

### 2.6 Hard constraints (fail-closed)

The Runtime rejects relation creation with stable domain error codes:

| Rule | Error code |
|---|---|
| source = target | `RELATION_SELF_LOOP` |
| source or target is pending (unpersisted) | `RELATION_PENDING_TARGET` |
| source or target is retracted | `RELATION_TARGET_RETRACTED` |
| duplicate active relation (same direction + type) | `RELATION_DUPLICATE` |
| proposed relation exists and not retracted/deleted | `RELATION_DUPLICATE` |
| type not in allowed set | `RELATION_TYPE_NOT_ALLOWED` |
| project not found / node cross-project | `PROJECT_NOT_FOUND` / `NODE_NOT_FOUND` |

### 2.7 User-facing relation type subset

Canvas drag → proposal chooser exposes three user-facing relation types:

- `RELATED_TO` (bidirectional)
- `SUPPORTS` (directional)
- `CONFLICTS_WITH` (directional)

`DEPENDS_ON` and `DERIVED_FROM` are reserved for Agent-inferred relations only, as they require reasoning context to use correctly.

## 3. Append-Preserving Connection Rule

Users and Agent may connect/continue from any existing Node, but they may not retroactively insert a new Node between two already-established historical continuation nodes.

Existing:

```text
Q1 ─────→ Q2
```

Forbidden as a history rewrite:

```text
Q1 ─────→ X ─────→ Q2
```

Allowed:

```text
Q1 ─────→ Q2
  
   └────→ X ─────→ ...
```

If the source Node is not the current tip of its route, continuing from it creates a new branch Route. A branch must never be rewritten into the existing history (`Q1 → Q2` must not become `Q1 → X → Q2`) and must never rejoin an existing canonical current-version node. The UI must not pretend that historical Q2 was generated with knowledge from X.

## 4. Route Model

A Route is an explicit exploration lineage/view over shared Node identities.

A Route owns:

- identity and friendly label;
- lineage/root/tip semantics;
- branch provenance;
- lifecycle metadata.

A Route does **not** own copies of shared Node content.

### 4.1 Active, Focus and Visibility

These remain independent:

- **Active Route** — runtime route used by commands that require one active lineage; it is the backend/runtime mutation target.
- **Focus Route** — browser UI/read context the user is currently emphasizing.
- **Visibility** — whether a route/node is shown on Canvas.

Changing Focus must not silently Activate a route. Focusing a route highlights it and reduces visual weight of others; it does not hide all other routes.

A route selector shown on a Shared Node changes **Focus/read context only**. It must not Activate a route, and it cannot select a different Answer — a shared canonical Question has at most one canonical Answer (see §5).

## 5. Shared Node

One Node may participate in multiple routes without duplication.

The UI should show all relevant route labels on a shared Node. Route membership is graph metadata, not a reason to clone the card.

### 5.1 Shared Node = Shared State

For a canonical Question, shared identity means shared answer state: it has **at most one immutable Answer identity project-wide**. If multiple routes contain the same canonical Question, they all resolve to the same Answer ID.

```text
Route A ─→ Shared Q2 ─→ Answer (single canonical identity)
Route B ─→ Shared Q2 ─↗
```

There is no route-scoped answer, no route-specific Answer selector, and no "second Answer for the same Question on another Route". A different answer always requires a different canonical Question identity: re-answer creates `Q2'` as a fresh Question; it never attaches a second Answer to `Q2` (see §10.2).

Read-model behavior is fail closed:

- if a shared Question resolves to different effective Answer IDs, the read model must fail with the stable domain error `SHARED_STATE_DIVERGENCE`;
- if some route memberships see the Question as answered while others see it unanswered, that is the same divergence and fails the same way;
- divergence is an invariant violation to be repaired at the data layer — never a UI mode, and never silently resolved by falling back to the Active route, first route, or most recent answer.

The Focus Route cannot select another Answer: there is nothing to select. Changing Focus changes highlighting and read emphasis only.

This Answer invariant applies to canonical Questions. Other shared Node kinds (Knowledge, Resource, Artifact) remain shared by canonical Node identity but are not implied to own Answer records.

## 6. Fork and Immediate Route Appearance

Fork is a structural user operation first, model generation second.

Preferred sequence:

```text
User clicks Fork
      |
      v
Runtime creates Route immediately
      |
      v
AgentRun starts
      |
      v
UI projects pending card on new route
      |
      v
Model/Agent decision completes
      |
      v
Validated Node is persisted atomically
```

The pending card is a projection of in-flight operation state, not a partially persisted final Node.

This lets the user immediately see the new route and continue navigating the workspace while generation runs.

Fork keeps the branch point shared: the new Route starts from the same canonical Node identity, not a copy of it.

## 7. Free Node Creation

Users may create a blank/draft Node in the workspace and attach it as a continuation from any existing Node. A draft may also stay floating — `routeIds = []` with `parentNodeId = null` is a valid persisted state — until the user attaches it (see §11).

This must not be limited to Fork, Re-answer, or “换一个问题”. Those remain convenience commands over the more general graph model.

From a draft/user Node, the user may request:

- continue exploring;
- generate a follow-up question;
- ask AI about the local context;
- create another branch;
- connect semantic relations through explicit UI/actions.

### 7.1 User-added requirement becomes route context

If the user continues from `Q2` into a user-authored Requirement Node:

```text
Q2 ─────→ Requirement R ─────→ ...
```

then `Requirement R` is part of that route's lineage/anchor context. A subsequent “继续生成” Decision Cycle must see it as current route context and should reason with it, rather than ignoring the user-authored node and continuing as if Q2 were still the tip.

This is how users can steer a route by adding their own requirements without requiring a special “branch requirement” button.

## 8. Route Naming

Routes need human-readable labels. Generated/default labels may be suggested, but users must be able to distinguish routes without UUIDs or database-oriented numbering.

Shared nodes display all applicable route names compactly.

## 9. History and Replacement

Re-answer, regenerate/replacement and future Undo/Redo must preserve why a route/node exists.

History should answer:

- what was the source route/node;
- what operation created the alternative;
- which prior state it replaces or revises;
- whether re-answer created a fresh canonical Question identity;
- whether the prior state remains visible/history-accessible.

No operation may silently mutate a historical question/answer as if the new information had always been present.

## 10. Question Lifecycle Invariants

### 10.1 Unanswered Question stays tip

An unanswered `INTERACTION/QUESTION` node must remain the tip of every route that contains it. A child continuation under an unanswered question:

```text
Q1 (unanswered) ─────→ Q2
```

is rejected by the Runtime with the stable domain error `UNANSWERED_QUESTION_HAS_CHILD`. The constraint applies equally to Agent-created Questions: an Agent proposal may not append a child under an unanswered Question.

### 10.2 Re-answer creates a fresh canonical Question identity

Re-answer never creates a second Answer on the existing Question. It creates a new canonical Question identity `Q2'` that:

- has a new Node ID;
- copies question text, purpose, options and `allowFreeAnswer`;
- has `Q2'.parentNodeId = Q2.parentNodeId`, so it is a sibling at the same lineage depth;
- is created on a Route whose `branchType = REANSWER`;
- records provenance for the `sourceRouteId` and old `Q2` as the branch point (`branchAtNodeId` / equivalent branch-point metadata);
- does **not** inherit the old `Q2` Answer in its context prefix;
- does **not** set or use `supersedesNodeId` — supersession belongs to regenerate/replacement semantics, not Re-answer;
- is a different canonical Question, answerable exactly once like any other.

Route-specific answers, an "unanswered route instance of the same canonical Question", and re-answer-as-a-second-Answer are forbidden representations of this operation.

### 10.3 Resume Question does not exist

There is no `RESUME_QUESTION` product feature and no "Resume Route". If the tip of an inactive/open Route is already an unanswered Question, the user activates that existing Route and answers the original Question — no new Route is created.

`RESUME_ANSWER` is a separate concept: the runtime repair/retry operation that resumes downstream processing of an already-persisted Answer without creating a second Answer. Do not confuse it with, or rename it into, a Resume Question feature.

## 11. Floating Nodes and Routeless NodeQuery

A canonical Node may float: `routeIds = []` with `parentNodeId = null` is a valid persisted state (for example a user-created draft not yet attached to a route).

A floating Node can be an AI context anchor. `NODE_QUERY` is the only model-facing flow allowed to carry `routeId = null`; both snapshot route fields must be null together. Route-bound operations (answers, normal decisions/state updates, artifact generation, regenerate/replacement) require route identity, and mixed null/UUID route fields are rejected fail closed.

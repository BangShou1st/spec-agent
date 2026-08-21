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

### 2.3 Who may create relations

- deterministic provenance relations created by Runtime from known operations may be persisted automatically;
- user-created relations are explicit user operations;
- model-inferred semantic relations are proposals in Advisor Mode and must pass Policy/Validator before persistence;
- Agent confidence alone cannot turn an inferred relation into fact.

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

If the source Node is not the current tip of the route, continuing from it creates or reuses an explicit branch/route according to Runtime rules. The UI must not pretend that historical Q2 was generated with knowledge from X.

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

- **Active Route** — runtime route used by commands that require one active lineage.
- **Focus Route** — UI/read context the user is currently emphasizing.
- **Visibility** — whether a route/node is shown on Canvas.

Changing Focus must not silently Activate a route. Focusing a route highlights it and reduces visual weight of others; it does not hide all other routes.

A route selector shown on a Shared Node changes **Focus/read context only**. It must not Activate a route.

## 5. Shared Node

One Node may participate in multiple routes without duplication.

The UI should show all relevant route labels on a shared Node. Route membership is graph metadata, not a reason to clone the card.

### 5.1 Route-scoped answers

Answers are immutable and route-scoped, so a shared Question Node may have different answers on different routes.

```text
Route A ─→ Shared Q2 ─→ Answer A
Route B ─→ Shared Q2 ─→ Answer B
```

UI/read rules:

- if effective answers are equivalent, the Node may show a common answer plus route membership;
- if effective answers differ, show route-labelled answer summaries;
- explicit Focus Route determines the default expanded answer/read context;
- other route answers remain discoverable and are not hidden merely because one route is focused;
- if answers differ and **no Focus is selected**, the shared-node read context stays neutral/ambiguous and the UI should ask the user to choose a route context when an operation requires one;
- do not fall back to Active Route, first route, most-recent route or latest answer merely to resolve shared-node ambiguity.

A shared Node must never invent one globally authoritative answer when the runtime truth is route-scoped.

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

## 7. Free Node Creation

Users may create a blank/draft Node in the workspace and attach it as a continuation from any existing Node.

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
- whether the prior state remains visible/history-accessible.

No operation may silently mutate a historical question/answer as if the new information had always been present.

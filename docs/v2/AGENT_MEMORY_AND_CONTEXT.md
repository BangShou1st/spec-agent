# Agent Memory and Context Architecture V2

## 1. Purpose

This document defines how Spec Agent selects and freezes context without falling back to global chat-history prompting.

Core principle:

> **Graph is durable memory. Context is a selected reasoning view. Agent state is temporary cognition.**

## 2. Memory Layers

```text
Durable Graph Memory
  - Nodes / Answers / Patches
  - Routes / provenance
  - semantic relations
  - operation history
  - snapshots / artifacts
          |
          v
Runtime Context Selection
          |
          v
AgentInputSnapshot
          |
          v
Decision Cycle
```

The Agent does not own a second competing database of “what the project really means”.

## 3. Context Selection Priorities

Context is selected from the current event and anchor, not from all history.

Recommended priority:

1. operation anchor Node / user event;
2. explicit Focus Route/read context;
3. route lineage relevant to the anchor;
4. effective canonical Answers plus the accepted AnswerPatches replayed along the anchor lineage;
5. direct semantic relations relevant to the current task;
6. confirmed decisions/constraints that affect the current scope;
7. recent relevant Graph operations;
8. selected resource excerpts/capability results;
9. higher-level summaries only when they are grounded and useful.

Sibling branches should not enter context by default merely because they belong to the same project.

## 4. Context for Any-Node AI Query

Any Node may act as a contextual AI anchor.

For a query on Node `N`, Runtime should prefer:

```text
N
+ Focus Route lineage containing N (when resolvable)
+ effective canonical answers/patches resolved along that lineage
+ directly related semantic nodes
+ confirmed constraints/decisions relevant to N
+ explicitly attached Resource context
```

The user may ask for a broader comparison across routes; only then should multiple route contexts be intentionally included.

This avoids turning “ask AI about this Node” into global project chat.

## 5. Shared Node Context

Shared Node = Shared State: a canonical Question resolves to at most one immutable Answer identity project-wide. Context selection therefore never chooses between “route answers” — there is only one effective Answer per shared Question.

Rules:

- a shared answered Question contributes its single canonical Answer to every route context that contains it;
- Answer identity is canonical/shared project-wide, but a route/context still derives its own effective Answer/AnswerPatch sequence from the canonical Answers reachable through its lineage; accepted AnswerPatches remain immutable checkpoint/provenance artifacts bound to their source Answer and to route/context provenance — there is no project-global patch sequence shared by all routes;
- if the read model ever finds different effective Answer IDs for one canonical Question — or some route memberships answered while others are unanswered — it fails closed with `SHARED_STATE_DIVERGENCE`; context selection must never paper over such divergence by picking Active/first/latest;
- Focus changes read emphasis only; it cannot select a different Answer, because no route-specific Answer exists;
- cross-route comparison means comparing different lineage contexts (each branch's own claims/conclusions), never different Answers of one Question.

## 6. Project Metadata

Project title/description may be present as metadata, but metadata is low-authority context.

Never infer a confirmed objective solely because the title appears meaningful.

If the user starts from an empty project, context should honestly represent an empty/unknown objective and allow the user to create a blank Node or request an exploratory question.

## 7. Resource Context

RESOURCE Nodes do not automatically dump full file/image/repository content into every prompt.

A resource adapter/capability should provide bounded, provenance-preserving context such as:

- metadata;
- selected excerpts/chunks;
- summaries with source locations;
- retrieval handles;
- hashes/version identifiers.

The Agent requests additional resource context only when needed.

## 8. Token and Context Budget

Avoid fixed domain-specific truncation heuristics. Use general policies:

- prefer direct evidence over summaries;
- prefer current route lineage over unrelated history;
- deduplicate equivalent claims;
- omit superseded content unless conflict/history is relevant;
- retrieve resource excerpts on demand;
- preserve source refs for every model-visible grounded fact.

When context exceeds budget, selection should degrade by relevance/authority, not by arbitrary “last N chat messages”.

## 9. Preventing Context Drift

The Agent must not assume:

- project title equals goal;
- a pre-re-answer Answer still applies to the fresh re-answer Question identity (the new identity's context prefix never contains the old Answer);
- sibling branch conclusions apply to the focused route;
- Agent-generated assumption is confirmed;
- semantic relation means causal fact;
- resource/tool output is user-approved truth.

Runtime must preserve provenance and status so the Decision Engine can distinguish these cases.

## 10. Context Snapshot Compatibility

Existing `ContextSnapshot` lineage isolation is valuable and should be preserved during migration.

V2 may evolve it into or alongside an `AgentInputSnapshot`, but should not discard the current deterministic, model-free snapshot boundary.

The migration goal is to add richer, explicitly selected context—not to replace lineage context with global history.

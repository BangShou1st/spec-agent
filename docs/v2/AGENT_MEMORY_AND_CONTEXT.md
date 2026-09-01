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

## 11. Durable Frozen Input Projection

The `ContextSnapshot` manifest freezes *which* records enter the context (ids, relations, special inputs, context hash). It does not freeze the *content* of records that stay live-mutable (editable node bodies, related-node bodies, route labels, recent capability observations). Projecting the same manifest twice could therefore yield different model input.

The runtime contract that closes this gap:

- the **first** projection of a `ContextSnapshot` into an `AgentInputSnapshot` is persisted once as a durable frozen projection (immutable payload + durable projection schema version `agent-input-projection.v1` + payload hash + mutable-source fingerprint set, insert-if-absent; the unique per-snapshot identity arbitrates concurrent first freezes — first writer wins, losers replay the winner's payload);
- the durable projection schema version `agent-input-projection.v1` is **independent** of the wire envelope version `agent-input.v2` (see `contracts/README.md`): the frozen `payload` happens to be the canonical serialization of the `snapshot` field of an `agent-input.v2` envelope, but the durable table's version evolves separately; `agent-input-projection.v1` is the first value persisted under the new column layout. Legacy rows shipped as `agent-input.v2` before the review fix stay readable (the loader accepts both values); any other version fails closed;
- every later projection of the **same** snapshot replays the stored payload exactly; retry, resume and repair never silently rebuild model input from live mutable records, never update a frozen payload in place, and never delete-and-recreate it;
- the frozen payload is versioned and bounded; oversize fails closed with a typed failure instead of truncating under the same identity;
- a corrupted, tampered (hash mismatch), unsupported-version, or foreign-identity frozen payload fails closed — it is never silently rebuilt from live records, because it is the audit/reproducibility evidence of what the model actually saw;
- the frozen payload only ever contains what the model legitimately saw at freeze time: projected node bodies, canonical answers/patches, effective claims, semantic relations with related-node bodies, bounded capability observations selected before the freeze, route/read context, allowed source refs, autonomy inputs. It never contains provider credentials, raw secrets, or hidden chain-of-thought;
- capability invocations and user edits that happen **after** a freeze never retroactively appear in that snapshot's replayed input; only a **new** ContextSnapshot may observe them;
- the frozen **source fingerprint set** (`NODE` + `RELATED_NODE` body hashes, derived from the same canonical body shape that enters the payload) is persisted alongside the payload and powers the live mutation staleness gate — it is not part of the wire payload itself, and it never leaks secrets;
- **frozen input identity ≠ live stale validation.** The frozen projection proves what the model saw (replay is always byte-identical to what was frozen); live execution eligibility is a separate gate. Graph-mutating proposals (`CREATE_NODE`, `CONNECT_NODE`, `INVOKE_CAPABILITY`) compare the frozen fingerprint set with current authoritative node bodies before any graph write and reject a mismatch as `STALE_CONTEXT` with zero mutations. Read-only families (`RESPOND_TO_USER`, `WAIT`) are never gated by this check, and unrelated workspace changes that were not model-visible never mark a proposal stale. The existing `baseContextSnapshotId` / `baseContextHash` / anchor-refs / route-tip checks stay in place as the identity/anchor layer; the fingerprint gate is the mutable-content layer on top;
- **legacy replay gap.** Snapshots that were already model-consumed before the frozen-input contract (no frozen row + `DECISION_STARTED`/`STATE_UPDATE` evidence exists) cannot be semantic-replayed by rebuilding current live state and pretending it is the old input. A typed domain failure `LEGACY_FROZEN_INPUT_UNAVAILABLE` is raised fail-closed: no second `Answer`, no second `Patch`, no silent `STATE_UPDATE` rerun, no live reconstruction of the old `DECISION` input. The caller must retry from a fresh `ContextSnapshot`; a never-consumed snapshot (no model-called evidence) still freezes normally on first projection.

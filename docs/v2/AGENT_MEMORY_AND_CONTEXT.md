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
4. route-scoped effective Answers and accepted AnswerPatches;
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
+ effective route-scoped answers/patches
+ directly related semantic nodes
+ confirmed constraints/decisions relevant to N
+ explicitly attached Resource context
```

The user may ask for a broader comparison across routes; only then should multiple route contexts be intentionally included.

This avoids turning “ask AI about this Node” into global project chat.

## 5. Shared Node Context

A shared Node may have multiple route-scoped answers. Context selection must not collapse them into one answer.

Rules:

- if Focus Route is explicit, use its effective answer as primary context;
- include other route answers only when the operation asks for cross-route comparison or when detecting a relevant conflict;
- preserve route labels/provenance for every included answer;
- no Active/first/latest fallback should silently resolve ambiguous shared-node read context.

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
- old answer remains authoritative on every route;
- sibling route facts apply to the focused route;
- Agent-generated assumption is confirmed;
- semantic relation means causal fact;
- resource/tool output is user-approved truth.

Runtime must preserve provenance and status so the Decision Engine can distinguish these cases.

## 10. Context Snapshot Compatibility

Existing `ContextSnapshot` lineage isolation is valuable and should be preserved during migration.

V2 may evolve it into or alongside an `AgentInputSnapshot`, but should not discard the current deterministic, model-free snapshot boundary.

The migration goal is to add richer, explicitly selected context—not to replace lineage context with global history.

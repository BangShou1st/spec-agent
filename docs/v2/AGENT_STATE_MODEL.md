# Agent State Model V2

## 1. Purpose

Agent State is the Agent's **temporary reasoning state**, not chat history and not the durable source of truth.

The authoritative boundary is split into two concepts:

1. `AgentInputSnapshot` — deterministic, Runtime-built, frozen facts/context for one decision cycle.
2. `AgentDecisionState` — model/engine-derived observation and planning state for that cycle.

The Graph remains durable truth.

## 2. AgentInputSnapshot

Built by Spring Graph Runtime without calling a model.

Suggested logical shape:

```json
{
  "event": {},
  "anchor": {},
  "routeContext": {},
  "lineage": [],
  "effectiveAnswers": [],
  "effectiveClaims": [],
  "relatedNodes": [],
  "importantDecisions": [],
  "resourceContext": [],
  "allowedSourceRefs": [],
  "availableCapabilities": [],
  "allowedActionFamilies": [],
  "autonomy": {},
  "metadata": {}
}
```

The exact Java DTO can differ, but these semantics are required.

## 3. Project Title Is Metadata, Not Objective

`projectTitle` may be included in metadata for display/context, but must never be automatically promoted into:

- objective;
- requirement;
- confirmed fact;
- scope.

A title such as `test123`, `demo`, or even a meaningful-looking product name is untrusted context until the user/Graph establishes actual intent.

If no objective exists, the Agent should represent that uncertainty instead of guessing one.

## 4. AgentDecisionState / Observation

The Decision Engine may derive:

```json
{
  "workingObjective": {},
  "focus": {},
  "known": [],
  "unknowns": [],
  "conflicts": [],
  "risks": [],
  "assumptions": [],
  "constraints": [],
  "progress": {},
  "candidateActions": []
}
```

These are interpretations for reasoning and evaluation. They are not durable truth merely because the model emitted them.

## 5. Objective

Objective authority levels should remain explicit:

- **explicit/confirmed** — supported by user-confirmed Graph content;
- **proposed** — Agent/user suggestion awaiting confirmation;
- **unknown** — no reliable objective yet.

Do not create a hidden global objective from project metadata.

## 6. Focus

Focus prevents uncontrolled exploration. It should include enough information to scope reasoning, for example:

- anchor Node;
- current Focus Route (if selected);
- current operation intent (`answer`, `continue`, `ask_ai`, `generate_spec`, etc.);
- relevant semantic area if explicitly established.

Focus is not the same as Active Route and must not silently change runtime command ownership.

## 7. Known / Unknown / Conflict / Risk

These concepts are useful for reasoning, but require evidence:

- `known` references grounded/confirmed graph facts;
- `unknown` represents missing information, not an invitation to guess;
- `conflict` points to at least two incompatible claims/decisions with source refs;
- `risk` distinguishes observed evidence from Agent inference;
- `assumption` remains explicitly unconfirmed.

Unknown reduction is valuable only if it comes from grounded confirmation, not unsupported inference.

## 8. Persistence Rules

Persist:

- authoritative Graph mutations;
- the `AgentInputSnapshot` as a durable frozen projection per `ContextSnapshot` (immutable payload + durable projection schema version `agent-input-projection.v1` + payload hash + mutable-source fingerprint set; first projection freezes, later projections replay — see `AGENT_MEMORY_AND_CONTEXT.md` §11; `LEGACY_FROZEN_INPUT_UNAVAILABLE` fail-closed for pre-contract replay gaps);
- AgentRun lifecycle (including the snapshot identity behind each DECISION call);
- structured action proposal/policy outcome references needed for traceability;
- user approval/rejection feedback.

Do not persist as truth:

- raw hidden chain-of-thought;
- unvalidated model observation;
- arbitrary temporary reasoning summaries.

## 9. Rebuildability

Agent reasoning state should be rebuildable from:

```text
Graph state
+ route/read context
+ operation event
+ capability descriptors/results
+ explicit policy
```

If the Agent requires a private mutable memory store to know what the project means, the boundary is wrong.

## 10. Versioning

`AgentInputSnapshot` and `AgentDecision` contracts should be versioned before introducing a remote/Python decision engine. This allows Java/Python implementations to evolve independently without coupling Python to database schema.

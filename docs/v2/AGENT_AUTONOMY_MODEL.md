# Spec Agent V2 Autonomy and Policy Model

## 1. Goal

Spec Agent should be an AI collaborator for Graph evolution, not an uncontrolled automation engine.

Default product behavior is **Advisor Mode**. Autonomous execution is optional and remains bounded by Runtime policy.

## 2. Advisor Mode (Default)

Agent may:

- inspect authorized Graph context;
- identify unknowns, conflicts, risks and assumptions;
- answer contextual questions;
- propose Question/Knowledge/Relation/Route changes;
- propose capability use;
- generate candidate artifacts.

Important changes are staged for user confirmation rather than silently applied.

Advisor Mode does **not** mean every harmless technical step needs a modal dialog. Runtime may automatically perform internal read-only steps, deterministic validation and non-user-visible bookkeeping.

## 3. Autonomous Mode

Advanced mode may automatically execute **policy-approved low-risk** actions.

It does not remove:

- schema validation;
- Graph invariants;
- route/history rules;
- capability permission boundaries;
- step budgets;
- provenance requirements;
- explicit confirmation for high-risk actions.

Autonomous Mode is “less approval friction”, not “LLM owns the application”.

## 4. Policy Inputs

Policy Engine should determine approval/execution from Runtime facts such as:

- action family and mutation scope;
- Node/route lifecycle state;
- whether confirmed user intent would change;
- whether history/destructive mutation is involved;
- whether an external side effect occurs;
- capability permission/scope;
- source grounding/evidence;
- replay/idempotency safety;
- current autonomy mode;
- confidence/consistency signals.

**Confidence alone must never authorize an action.** Avoid arbitrary rules such as “confidence > 0.85 means execute”. Confidence is one signal among Runtime-owned policy inputs.

## 5. Risk Classes

Risk classification is Runtime-owned and can evolve without changing Planner prompts.

### LOW

Typical examples:

- read-only capability call already authorized;
- produce an ephemeral explanation;
- create/update a non-destructive local summary candidate;
- deterministic metadata refresh.

May execute automatically when policy allows.

### MEDIUM

Typical examples:

- create a new Question/Knowledge Node;
- create a semantic relation;
- create a branch suggestion;
- invoke a capability that changes Graph interpretation but has no external side effect.

Advisor Mode normally asks for confirmation where the change materially affects the visible Graph. Autonomous Mode may execute only if policy explicitly allows that class.

### HIGH

Always requires explicit user confirmation unless a future product feature defines a narrowly scoped pre-authorization.

Examples:

- deleting/archiving durable user content in a destructive way;
- superseding or materially changing confirmed decisions/requirements;
- merging routes/history;
- publishing/locking a final artifact;
- external side effects such as sending messages, writing to third-party systems, creating remote resources;
- broad capability permission escalation.

## 6. User Intent Is Privileged

Agent may detect that an old requirement conflicts with a new statement, but it must not silently decide which user intent wins.

Correct pattern:

```text
Conflict detected
   |
   v
proposal / clarification
   |
   v
user decision
   |
   v
Runtime records confirmed transition / supersession
```

## 7. Proposal UX

A proposal should expose concise, user-safe information:

- what Agent wants to do;
- which Node/Route it affects;
- why it is useful at a high level;
- whether it changes existing confirmed intent;
- accept / modify / reject actions.

Do not expose hidden chain-of-thought.

User rejection/acceptance can be stored as evaluation feedback, but rejection does not become a hard-coded prompt exception for that exact example.

## 8. External Capability Side Effects

MCP/Skill/Internal capabilities are classified independently from Graph mutations.

Read-only retrieval may be low risk. A tool that sends, deletes, publishes, purchases, changes permissions, or writes remotely is a side effect and requires the capability policy to authorize it.

Graph Undo does not imply an external side effect can be undone.

## 9. Failure and Retry

Manual retry is a product affordance, not hidden transport retry.

Policy must avoid:

- duplicating immutable answers;
- repeating non-idempotent external side effects;
- silently switching provider/model;
- continuing autonomous loops after repeated failure/no progress.

A failure can transition the run to `WAITING_USER`, `FAILED`, or another explicit recoverable state.

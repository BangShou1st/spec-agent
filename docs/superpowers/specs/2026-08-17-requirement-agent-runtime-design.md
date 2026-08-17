# Requirement Agent Runtime Design

Date: 2026-08-17  
Status: approved design basis for first-version documentation

## Summary

Spec Agent is a branchable requirement clarification agent. Its first version focuses on making the process from vague requirement to clear spec trustworthy, controllable, recoverable, and traceable.

The system uses an immutable node tree and explicit route objects. Context is always rebuilt from the current route's tip node by replaying parent lineage. Regeneration, deletion, restoration, and spec generation are all defined around that lineage rule.

## Design Decision

Use a custom Requirement Agent Runtime instead of adopting a generic agent framework as the product core.

The runtime is domain-neutral. It implements:

- Nodes.
- Routes.
- Agent runs.
- Context snapshots.
- Answer patches.
- Reflection gates.
- Spec snapshots.
- Source tracing.

It does not implement concrete business-domain logic.

## Core Model

```text
Node = immutable clarification unit.
Route = explicit path view with root, tip, status, and focus.
Context = current route lineage replay.
Patch = structured change derived from user answer.
Spec = snapshot derived from one route tip.
```

## Regeneration Contract

When regenerating a historical node:

Allowed context:

- Old node parent lineage.
- Old node question text.
- Old node purpose, if available.
- User regeneration instruction.

Forbidden context:

- Old user answer.
- Old answer patch.
- Old child subtree.
- Old route spec snapshot.
- Sibling branch conclusions.

Old route content becomes superseded, not destroyed. Superseded routes are visible, restorable, and forkable.

## Anti-Overfitting Contract

The code must not include domain branches such as software-project logic, marketing-plan logic, ecommerce logic, or student-assignment logic. Concrete domains can appear in user input, examples, prompts, tests, or profiles, but not in Runtime Kernel control flow.

## First-Version Non-Goals

- Collaboration platform.
- Task management.
- RAG.
- Browser automation.
- Code generation.
- Multi-agent platform.
- Complex visual workflow canvas.
- Route merge.
- Domain-specific requirement engine.

## Implementation Direction

Build in this order:

```text
documentation freeze
→ deterministic backend runtime kernel
→ fake model structured contracts
→ route control operations
→ real model gateway
→ frontend workspace
→ hardening and architecture tests
```

Do not scaffold broad platform features before core invariants are tested.

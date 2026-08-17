# Spec Agent

Spec Agent is a branchable requirement clarification agent that turns vague ideas into traceable specs.

The first version is intentionally narrow. It is not a collaboration platform, project management system, browser automation tool, or generic agent framework. It focuses on one deep product loop: helping a user turn an unclear requirement into a clear, source-traceable specification through controlled agent questioning, free-form answers, branching, regeneration, route restoration, and lineage-based context isolation.

## First-Version Goal

The first version proves one thing:

> A vague requirement can be clarified through a controlled agent process into a trustworthy, controllable, recoverable, and traceable spec.

The product should make the clarification process visible and navigable. Users should be able to see what has been confirmed, what is still assumed, what remains unresolved, and which route produced the current spec.

## Core Loop

```text
Initial requirement
→ clarification node
→ user option answer or free-form answer
→ structured answer interpretation
→ answer patch
→ lineage-based context replay
→ next clarification node
→ branch / regenerate / restore / delete route when needed
→ traceable spec snapshot
```

## Non-Goals

The first version must not expand into these areas:

- Team collaboration platform
- Task board or project management
- Knowledge base or RAG system
- Browser automation
- Code generation
- Plugin marketplace
- Multi-agent platform
- Complex workflow canvas
- Domain-specific PRD generator
- Industry-specific requirement system

## Core Concepts

- `Project`: the user's requirement exploration workspace.
- `Route`: an explicit exploration route with root, tip, status, label, and working focus.
- `Node`: an immutable clarification unit in the exploration tree.
- `AgentRun`: one controlled agent execution triggered by a user operation.
- `ContextSnapshot`: the exact lineage context used for one agent run.
- `AnswerPatch`: structured changes derived from one user answer.
- `ReflectionGate`: a bounded review step for gap analysis, node quality, patch quality, or spec grounding.
- `SpecSnapshot`: a generated spec for one route tip, with source references.

## Architecture Principle

```text
Model handles reasoning.
Runtime handles history.
Model proposes.
Runtime constrains and persists.
Spec summarizes.
Sources prove.
```

The runtime must never rely on global chat history as source of truth. Current context is always reconstructed from the active route's tip node by replaying its parent lineage.

## Anti-Overfitting Principle

Runtime code must implement generic requirement-clarification mechanics only. It must not contain concrete business-domain logic such as `software project`, `marketing plan`, `startup pitch`, `ecommerce`, `student assignment`, or any similar domain-specific branch.

Domain knowledge may enter through user input, profiles, prompts, or model output, but it must not become hard-coded runtime behavior.

## Planned Stack

- Backend: Java 21, Spring Boot, PostgreSQL, Flyway, jOOQ or MyBatis, SSE.
- Frontend: Vue 3, TypeScript, Vite, Pinia.
- AI integration: internal model gateway with structured JSON contracts.
- Testing: JUnit, integration tests, architecture tests, later Playwright for UI flows.

## Documentation

Read these before implementation:

- [`docs/PRODUCT_SPEC.md`](docs/PRODUCT_SPEC.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/AGENT_RUNTIME.md`](docs/AGENT_RUNTIME.md)
- [`docs/CONTEXT_RULES.md`](docs/CONTEXT_RULES.md)
- [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md)
- [`AGENT.md`](AGENT.md)
- [`CLAUDE.md`](CLAUDE.md)

## Current Status

Design freeze for the first version. Do not scaffold broad platform features before the runtime, context, route, node, patch, and spec invariants are implemented and tested.

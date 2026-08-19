# Spec Agent

Spec Agent is a branchable requirement clarification agent that turns vague ideas into traceable specs.

The first version is intentionally narrow. It is not a collaboration platform, a project management system, a browser automation tool, a code generation product, or a generic agent framework. It focuses on one deep product loop: helping a single user turn an unclear requirement into a clear, source-traceable specification through controlled agent questioning, free-form answers, branching, regeneration, route restoration, and lineage-based context isolation.

## First-Version Goal

The first version proves one thing:

> A vague requirement can be clarified through a controlled agent process into a trustworthy, controllable, recoverable, and traceable spec.

The product should make the clarification process visible and navigable. Users should be able to see what has been confirmed, what is only assumed, what remains unresolved, and which route produced the current spec.

## Core Loop

```text
Initial requirement
→ clarification node
→ option answer or free-form answer
→ immutable answer record
→ structured answer patch
→ lineage-based context replay
→ next clarification node
→ fork / regenerate / restore / delete route when needed
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
- Industry-specific requirement engine

## Core Concepts

- `Project`: the user's requirement exploration workspace.
- `Route`: an explicit exploration route with root, tip, lifecycle status, label, and working focus.
- `Node`: an immutable clarification prompt in the exploration tree.
- `Answer`: an immutable user answer to a node.
- `AnswerPatch`: structured requirement-state changes derived from one answer.
- `AgentRun`: one controlled agent execution triggered by a user operation.
- `ContextSnapshot`: the exact lineage context used for one agent run.
- `ReflectionGate`: a bounded review step for gap analysis, node quality, patch quality, or spec grounding.
- `SpecSnapshot`: a generated spec for one route tip, with source references.

## Architecture Principle

```text
Model handles reasoning.
Runtime handles history.
Model proposes.
Runtime constrains and persists.
Patch records.
Spec summarizes.
Sources prove.
```

The runtime must never rely on global chat history as source of truth. Current context is always reconstructed from the active route's tip node by replaying its parent lineage.

## Route Principle

`active` is not a route lifecycle status. The current working route is represented by `Project.activeRouteId`.

Routes use lifecycle status:

```text
open | superseded | archived | deleted
```

A route may be open but not currently active. Superseded routes are gray historical routes: visible, inspectable, restorable, and forkable, but excluded from active context unless explicitly restored or forked.

## Immutability Principle

Nodes are immutable clarification prompts. Answers are immutable records. A node can receive an answer once in the current route flow. Re-answering or changing a historical answer must create a new route, replacement node, or answer revision; it must not overwrite the old answer.

## Anti-Overfitting Principle

Runtime code must implement generic requirement-clarification mechanics only. It must not contain concrete business-domain logic such as `software project`, `marketing plan`, `startup pitch`, `ecommerce`, `student assignment`, or any similar domain-specific branch.

Domain knowledge may enter through user input, profiles, prompts, examples, or model output, but it must not become hard-coded runtime behavior.

## Planned Stack

- Backend: Java 21, Spring Boot, PostgreSQL, Flyway, jOOQ or MyBatis, SSE.
- Frontend: Vue 3, TypeScript, Vite, Pinia.
- AI integration: custom HTTP ModelGateway with structured JSON contracts; Spring AI is not the first-version default.
- Testing: JUnit, integration tests, architecture tests, later Playwright for UI flows.

## Development Workflow

This repository uses a single-branch development model for now:

```text
local main ↔ remote main
```

Do not create feature branches unless the project owner explicitly changes this rule. Keep `main` healthy with small commits, tests, and documentation updates.

## Documentation

Read these before implementation:

- [`docs/PRODUCT_SPEC.md`](docs/PRODUCT_SPEC.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/AGENT_RUNTIME.md`](docs/AGENT_RUNTIME.md)
- [`docs/CONTEXT_RULES.md`](docs/CONTEXT_RULES.md)
- [`docs/MODEL_GATEWAY.md`](docs/MODEL_GATEWAY.md)
- [`docs/ANTI_OVERFITTING.md`](docs/ANTI_OVERFITTING.md)
- [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md)
- [`docs/DEVELOPMENT_ENVIRONMENT.md`](docs/DEVELOPMENT_ENVIRONMENT.md)
- [`docs/DEVELOPMENT_WORKFLOW.md`](docs/DEVELOPMENT_WORKFLOW.md)
- [`AGENT.md`](AGENT.md)
- [`CLAUDE.md`](CLAUDE.md)

## Current Status

Phase 7.3 is closed. The graph-first workspace is now the frontend workspace:
an interactive graph canvas (Vue Flow) renders the canonical route lineage
graph with per-route answers, the active question is answered directly inside
its graph node, and a resizable route sidebar + inspector provide route
navigation (locate / focus / dim / hide / show-all, lifecycle filters),
historical node inspection, backend-derived requirement state, and derived
spec snapshot history. Closed invariants: the Focus route is always a visible
route (Focus auto-clears when the focused route is hidden or filtered out),
and Fork / Regenerate act only on historical nodes, never on the current
pending question.

The frontend remains a client of the Runtime — history, route lifecycle,
active-route selection, provenance, and persistence stay owned and
authoritative on the backend. Phase 8 (CI/hardening) has not begun. See
`docs/PHASE_7_3_EXIT_CRITERIA.md` for the Phase 7.3 closure documentation.

Post-acceptance frontend UX corrective patch: the Phase 7.3 graph workspace
now treats the graph as the primary full-bleed workspace, with floating route
and inspector overlays, graph-native reading Focus, explicit local Fork
remediation, stable node geometry, and adaptive directed curves. Phase 7.3
remains closed; Phase 8 is NOT STARTED.

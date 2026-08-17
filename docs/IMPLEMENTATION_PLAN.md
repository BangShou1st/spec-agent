# Implementation Plan

Status: first-version design freeze  
Date: 2026-08-17

## 0. Principle

Do not start with broad scaffolding. Build the runtime invariants before product extras.

The first version should prove the core loop:

```text
vague requirement
→ clarification node
→ user answer
→ answer patch
→ lineage context replay
→ branch / regenerate / restore / delete route
→ traceable spec snapshot
```

## Phase 0: Documentation Freeze

Deliverables:

- README.
- Product spec.
- Architecture doc.
- Agent runtime doc.
- Context rules doc.
- AI development instructions.

Exit criteria:

- First-version scope is explicit.
- Non-goals are explicit.
- Anti-overfitting rules are explicit.
- Context and route invariants are written down.

## Phase 1: Backend Runtime Kernel

Goal: implement deterministic state and context mechanics without calling a model.

Deliverables:

- Spring Boot project skeleton.
- PostgreSQL + Flyway setup.
- Project, Route, Node, AgentRun, ContextSnapshot, AnswerPatch, SpecSnapshot schema.
- RouteService.
- NodeService.
- ContextBuilder.
- RequirementStateBuilder.
- SpecSnapshot source model.

Required tests:

- Build context from active route lineage.
- Exclude sibling routes.
- Exclude deleted routes.
- Exclude superseded routes by default.
- Restore superseded route and rebuild context.
- Delete route without deleting shared ancestors.

Exit criteria:

- The system can create projects, routes, nodes, and context snapshots using fake data.
- No model integration exists yet.

## Phase 2: Agent Contracts with Fake Model

Goal: implement structured contracts and agent run lifecycle before real model calls.

Deliverables:

- AgentRun lifecycle.
- Model contract DTOs.
- Fake model adapter.
- GapAnalysisResult.
- NodeDraft.
- AnswerInterpretationResult.
- AnswerPatchDraft.
- ReflectionResult.
- SpecDraft.

Required tests:

- Invalid model output does not mutate persistent state.
- Patch reflection catches unsupported confirmed claims.
- Node reflection rejects multi-question nodes.
- Spec grounding rejects sections without sources.

Exit criteria:

- A fake agent can drive the full loop from initial requirement to spec snapshot.

## Phase 3: Regenerate, Fork, Restore, Delete

Goal: implement route control operations deeply and safely.

Deliverables:

- Fork from historical node.
- Regenerate historical node.
- Restore superseded route.
- Soft delete route.
- Route status transitions.

Required tests:

- Regenerate includes old question text.
- Regenerate includes user regeneration instruction.
- Regenerate excludes old answer.
- Regenerate excludes old child subtree.
- Old route becomes superseded and visible.
- Restored old route excludes replacement route context.

Exit criteria:

- Route operations are deterministic and test-covered.

## Phase 4: Real Model Gateway

Goal: add model calls behind stable contracts.

Deliverables:

- OpenAI-compatible model adapter.
- Prompt versioning.
- Structured JSON parsing.
- Retry or repair for invalid JSON.
- Trace summaries and model input/output hashes.

Required tests:

- Model adapter failures produce failed AgentRun, not partial mutation.
- Model output validation rejects invalid actions.
- ContextSnapshot ids are stored for every model call.

Exit criteria:

- Real model can generate nodes, interpret answers, and draft specs without owning state.

## Phase 5: Frontend First Version

Goal: make the runtime visible and usable.

Deliverables:

- Project creation page.
- Three-panel workspace:
  - Route tree.
  - Current node answer panel.
  - Requirement state and spec preview.
- Route status display.
- Regenerate with optional user direction.
- Restore superseded route.
- Delete route.
- Generate spec snapshot.

Required tests:

- Basic route creation flow.
- Answer node flow.
- Fork flow.
- Regenerate flow.
- Restore route flow.
- Spec generation flow.

Exit criteria:

- A user can complete the core loop through UI.

## Phase 6: Hardening

Goal: prevent scope expansion and code overfitting.

Deliverables:

- Architecture tests.
- Domain keyword scan for runtime packages.
- Documentation cross-check.
- Seed examples for generic requirements.

Required tests:

- Runtime packages contain no concrete business-domain branches.
- ContextBuilder does not depend on model gateway.
- Route and Node services do not depend on prompts.
- Spec generation uses ContextSnapshot, not global project history.

Exit criteria:

- The implementation matches the design freeze and is ready for real product iteration.

## Explicitly Deferred

- Multi-user collaboration.
- Role-based permissions beyond local owner assumptions.
- Document upload and RAG.
- Task management.
- External tools.
- Browser automation.
- Code generation.
- Route merge.
- Complex canvas UI.
- Domain-specific requirement templates.

## Development Order Summary

```text
Docs
→ deterministic backend kernel
→ fake model contracts
→ route control operations
→ real model gateway
→ frontend workspace
→ hardening tests
```

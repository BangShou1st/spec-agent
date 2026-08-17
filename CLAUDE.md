# AI Development Instructions

This repository is expected to be developed with AI assistance. AI coding agents are useful here, but they are also likely to overfit implementation to concrete examples. These instructions are mandatory for future development.

## 1. Read Before Changing Code

Before implementing or modifying behavior, read:

1. `README.md`
2. `docs/PRODUCT_SPEC.md`
3. `docs/ARCHITECTURE.md`
4. `docs/AGENT_RUNTIME.md`
5. `docs/CONTEXT_RULES.md`
6. `docs/ANTI_OVERFITTING.md`
7. `docs/IMPLEMENTATION_PLAN.md`
8. `docs/DEVELOPMENT_WORKFLOW.md`

Do not infer product scope from a single user example. The system is generic requirement clarification, not a domain-specific requirement generator.

## 2. Product Boundary

Spec Agent is a branchable requirement clarification agent.

It is not:

- A collaboration platform.
- A project management system.
- A task board.
- A knowledge base or RAG product.
- A browser automation agent.
- A code generation agent.
- A generic multi-agent framework.
- A complex visual workflow canvas.
- A domain-specific PRD generator.

If a requested change pushes the project toward one of these, stop and update the product/design docs before implementing.

## 3. Development Branch Policy

For now, use one branch only:

```text
local main ↔ remote main
```

Do not create feature branches or PRs unless the project owner explicitly changes this rule.

Because all work lands on `main`, keep commits small, run tests before claiming completion, and avoid broad partial changes.

## 4. Core Invariants

These invariants must not be broken:

1. Nodes form an immutable exploration tree.
2. A Node is an immutable clarification prompt.
3. Answers are immutable records.
4. A historical answer must not be overwritten.
5. Routes are explicit objects with root, tip, lifecycle status, label, and current focus metadata.
6. `active` is represented by `Project.activeRouteId`, not by `Route.lifecycleStatus`.
7. Route lifecycle status is `open | superseded | archived | deleted`.
8. Context is built from the active route's tip node by replaying parent lineage.
9. Sibling routes do not enter active context.
10. Superseded routes do not enter active context unless restored or forked.
11. Deleted routes do not enter active context.
12. Regenerate may include old question text and user regeneration instruction.
13. Regenerate must not include old answer, old patch, old child nodes, or old spec snapshot.
14. A spec snapshot is derived from one route tip and is not source of truth.
15. Confirmed spec claims must have source references.
16. Unsupported model output must be labeled assumption, suggestion, risk, or unresolved, not confirmed.
17. Runtime code must not contain concrete business-domain branching.

## 5. Anti-Overfitting Rules

Do not write runtime code such as:

```java
if (projectType == SOFTWARE_PROJECT) {
    askSoftwareScopeQuestion();
}
```

Do not add runtime classes such as:

```text
SoftwareRequirementAnalyzer
MarketingPlanQuestionGenerator
EcommerceSpecComposer
StartupPitchProfileService
StudentAssignmentClarifier
```

Do not add runtime enums such as:

```text
SOFTWARE_PROJECT
MARKETING_PLAN
ECOMMERCE_STORE
COURSE_ASSIGNMENT
STARTUP_IDEA
```

Allowed abstractions:

```text
RequirementAspect
RequirementProfile
QuestionPolicy
ClaimKind
Answer
AnswerPatch
ContextSnapshot
AgentRun
ReflectionGate
SpecSectionDefinition
SourceReference
```

Concrete domains may appear in tests as examples, in seed profile data, in prompts, in documentation examples, or in user-provided content. They must not become runtime control flow.

## 6. Model Boundary

The model may reason, draft, interpret, and review. It must not own persistent state.

Allowed model outputs:

- Gap analysis.
- Agent plan.
- Node draft.
- Answer interpretation.
- Answer patch draft.
- Reflection result.
- Spec draft.

The model must not directly:

- Change route lifecycle status.
- Set `Project.activeRouteId`.
- Decide which historical route is valid.
- Delete nodes.
- Restore routes.
- Merge branches.
- Read global project history.
- Write database state without validation.

## 7. Runtime Boundary

Runtime Kernel code must be deterministic where possible.

Runtime Kernel owns:

- Project state.
- Route lifecycle state.
- Node lineage.
- Answer immutability.
- ContextSnapshot construction.
- Regenerate rule enforcement.
- Soft deletion.
- Source tracing.

Runtime Kernel must not call the model gateway.

## 8. Context Rules

Never pass whole project history to the model by default.

Always build a ContextSnapshot from:

```text
Project.activeRouteId
→ Route.tipNodeId
→ parent node lineage
→ ordered answers and patches
→ derived RequirementState
```

For regenerate operations, only include:

- Parent lineage.
- Old question text.
- Old purpose, if present.
- User regeneration instruction.

Never include:

- Old answer.
- Old answer patch.
- Old child nodes.
- Old spec snapshot.
- Sibling route conclusions.

## 9. Reflection Gates

Do not implement reflection as vague self-talk. Reflection must be structured.

Required gate types:

- Context Guard.
- Gap Reflection.
- Node Reflection.
- Patch Reflection.
- Spec Grounding Gate.

A gate must produce a machine-checkable result where possible.

## 10. Testing Requirements

When implementing a feature, add tests for the relevant invariants.

Minimum test categories:

- ContextBuilder lineage replay.
- Sibling route exclusion.
- Superseded route exclusion.
- Deleted route exclusion.
- Regenerate context contract.
- Route restore context rebuild.
- Soft delete shared ancestor safety.
- Answer immutability.
- Spec source references.
- Unsupported claim handling.
- Architecture dependency rules.
- Domain-specific runtime keyword prevention.

Do not claim a feature is complete without tests covering its context and route behavior.

## 11. Documentation Requirements

If implementation changes one of these, update docs in the same change:

- Product boundary.
- Core concepts.
- Route operation semantics.
- Active route semantics.
- Node or Answer immutability.
- Context rules.
- AgentRun lifecycle.
- Reflection gates.
- Anti-overfitting rules.

Do not let code silently diverge from docs.

## 12. Implementation Style

Prefer small, explicit services:

- `ProjectService`
- `RouteService`
- `NodeService`
- `AnswerService`
- `ContextBuilder`
- `RequirementStateBuilder`
- `AgentRunService`
- `AnswerPatchService`
- `SpecSnapshotService`

Avoid god services that parse user language, mutate route state, call the model, generate specs, and persist results in one place.

## 13. Before Finishing Any Change

Check:

1. Did I introduce a concrete business-domain branch?
2. Did I introduce a domain-specific class, enum, package, or table?
3. Did I pass global history to the model?
4. Did I let model output mutate state without validation?
5. Did I preserve route lineage rules?
6. Did regenerate exclude old answer, patch, children, and spec?
7. Did spec content include source references?
8. Did tests cover the invariant touched by this change?
9. Did docs stay consistent with the code?

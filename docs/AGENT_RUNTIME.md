# Requirement Agent Runtime

Status: first-version design freeze  
Date: 2026-08-17

## 1. Purpose

Requirement Agent Runtime is the internal runtime for Spec Agent.

It is not a general-purpose agent framework. It is a narrow runtime for requirement clarification, route branching, context replay, reflection gates, and source-traceable spec generation.

Its core responsibility is to let the model reason while preventing the model from owning history.

```text
Model handles cognition.
Runtime handles history.
Model proposes.
Runtime constrains.
Patch records.
Spec summarizes.
Sources prove.
```

## 2. Runtime Layers

```text
Deterministic Runtime Kernel
  - Project, Route, Node, ContextSnapshot, AnswerPatch, SpecSnapshot.
  - Lineage replay.
  - Route operations.
  - Status transitions.
  - Source tracing.

Agent Reasoning Layer
  - Gap analysis.
  - Planning.
  - Node drafting.
  - Answer interpretation.
  - Patch drafting.
  - Spec drafting.

Reflection Gates
  - Context guard.
  - Node quality gate.
  - Patch reflection gate.
  - Spec grounding gate.
```

## 3. Core Runtime Objects

### Project

A requirement exploration workspace.

```text
Project
- id
- title
- activeRouteId
- defaultProfileId
- createdAt
- updatedAt
```

### Route

An explicit exploration route.

```text
Route
- id
- projectId
- rootNodeId
- tipNodeId
- status: active | superseded | archived | deleted
- label
- createdFromNodeId
- supersedesRouteId
- createdByRunId
- createdAt
- updatedAt
```

A route is a view over node lineage. It is not the source of all node content.

### Node

An immutable clarification unit in the exploration tree.

```text
Node
- id
- projectId
- parentNodeId
- createdByRunId
- status: active | superseded | archived | deleted
- supersedesNodeId
- question
- purpose
- options
- allowFreeAnswer
- rawAnswer
- interpretation
- patchId
- createdAt
- updatedAt
```

In the first version, a node may contain both the question and the user's answer. If the model becomes too large later, question, answer, interpretation, and patch can be split into separate tables.

### AgentRun

One controlled agent execution.

```text
AgentRun
- id
- projectId
- routeId
- triggerType
- inputNodeId
- contextSnapshotId
- producedNodeId
- producedPatchId
- producedSpecSnapshotId
- status: created | context_built | model_called | reflected | persisted | completed | failed
- trace
- createdAt
- completedAt
```

Each user operation that requires model reasoning should create an AgentRun.

### ContextSnapshot

The exact context used for one AgentRun.

```text
ContextSnapshot
- id
- projectId
- routeId
- tipNodeId
- operationType
- includedNodeIds
- includedPatchIds
- excludedRouteIds
- specialInputs
- contextHash
- createdAt
```

`specialInputs` may include old question text and user regeneration instructions for regenerate operations.

### AnswerPatch

Structured requirement changes derived from a user answer.

```text
AnswerPatch
- id
- projectId
- routeId
- sourceNodeId
- confirmedClaims
- assumptions
- constraints
- openQuestions
- conflicts
- risks
- createdByRunId
- createdAt
```

The current requirement state is built by replaying patches along the active route lineage.

### SpecSnapshot

A generated spec for one route tip.

```text
SpecSnapshot
- id
- projectId
- routeId
- tipNodeId
- contextSnapshotId
- format
- sections
- unresolvedItems
- sourceRefs
- createdByRunId
- createdAt
```

SpecSnapshot is not source of truth. It is a derived artifact.

## 4. Closed Agent Actions

The agent should not freely choose arbitrary system behavior. It may produce only bounded actions:

```text
ASK_NEXT_QUESTION
INTERPRET_ANSWER
REQUEST_CONFIRMATION
EXPLAIN_CONFLICT
SUGGEST_BRANCH
GENERATE_SPEC
STOP
```

The runtime decides whether the action is valid in the current operation.

## 5. AgentRun Lifecycle

A normal answer flow:

```text
User answers node
→ create AgentRun
→ build ContextSnapshot from active route lineage
→ interpret answer
→ draft AnswerPatch
→ run PatchReflection
→ persist answer and patch
→ replay requirement state
→ run GapReflection
→ draft next Node
→ run NodeReflection
→ persist next Node
→ update Route.tipNodeId
→ complete AgentRun
```

A spec flow:

```text
User requests spec
→ create AgentRun
→ build ContextSnapshot from active route lineage
→ replay requirement state
→ run SpecReflection
→ draft SpecSnapshot
→ verify source references
→ persist SpecSnapshot
→ complete AgentRun
```

A regenerate flow:

```text
User regenerates node N with optional instruction
→ create AgentRun
→ mark old route segment superseded
→ build ContextSnapshot from N.parent lineage
→ include old question text
→ include user regeneration instruction
→ exclude old answer and children
→ draft replacement node N'
→ run NodeReflection
→ persist N'
→ make replacement route active
→ complete AgentRun
```

## 6. Reflection Gates

Reflection is not free-form self-talk. It is a bounded verification step.

### Context Guard

Checks that the model input uses only allowed context for the operation.

It should fail if a context snapshot includes sibling route patches, deleted route patches, superseded child patches, old regenerate answers, or unsupported spec text.

### Gap Reflection

Before asking the next question, checks:

- What requirement aspect is missing?
- Why does it matter?
- Is it worth asking now?
- Is a spec already possible?

### Node Reflection

After drafting a node, checks:

- The node asks one main question.
- The purpose is clear.
- Options are understandable.
- Options include impact explanations when relevant.
- Free-form answering is allowed.
- The node does not rely on forbidden context.

### Patch Reflection

After interpreting a user answer, checks:

- Confirmed claims are actually supported by the answer.
- Assumptions are not mislabeled as confirmed.
- Open questions are preserved.
- Conflicts are not hidden.
- Ambiguous interpretations request user confirmation.

### Spec Grounding Gate

Before persisting a spec snapshot, checks:

- Each confirmed section has source references.
- Unsupported claims are marked as assumption, suggestion, or unresolved.
- The spec is tied to a route tip and context snapshot.
- The spec does not import sibling branch conclusions.

## 7. Requirement State

The runtime should not maintain a mutable global requirement object as source of truth.

Requirement state is derived:

```text
current route tip
→ root-to-tip node lineage
→ ordered answer patches
→ current requirement state
```

This derived state may be cached, but cache invalidation must not affect correctness.

## 8. Requirement Profile

The runtime must remain domain-neutral. A profile may define generic requirement aspects and spec sections.

Default profile: `generic_requirement`.

Example generic aspects:

- goal
- stakeholder
- scenario
- scope
- constraint
- success_criterion
- output_expectation
- risk
- assumption
- open_question
- conflict

A profile may influence prompting and prioritization, but runtime code must not branch on concrete domains.

## 9. Model Contracts

Model outputs must be structured and validated.

Minimum contracts:

- `GapAnalysisResult`
- `AgentPlanResult`
- `NodeDraft`
- `AnswerInterpretationResult`
- `AnswerPatchDraft`
- `ReflectionResult`
- `SpecDraft`

Invalid model output should not partially mutate persistent state. The run should fail or request repair.

## 10. Failure Handling

Failures should be explicit:

- `MODEL_INVALID_OUTPUT`
- `CONTEXT_CONTRACT_VIOLATION`
- `PATCH_UNSUPPORTED_CLAIM`
- `SPEC_UNGROUNDED_SECTION`
- `ROUTE_STATE_CONFLICT`
- `PERSISTENCE_FAILURE`

A failed AgentRun should not corrupt route or node state.

## 11. Anti-Overfitting Contract

Runtime code must not contain domain-specific requirement logic.

Forbidden:

```text
SoftwareProjectPlanner
MarketingRequirementAnalyzer
EcommerceSpecComposer
StudentAssignmentClarifier
if requirementType == "software"
if outputFormat == "PRD" then software-specific sections
```

Allowed:

```text
RequirementAspect
RequirementProfile
QuestionPolicy
SpecSectionDefinition
ClaimKind
AnswerPatch
ContextSnapshot
```

Profiles and prompts may mention concrete examples. Runtime packages may not encode them as branching behavior.

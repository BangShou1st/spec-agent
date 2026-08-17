# Product Spec

Status: first-version design freeze  
Date: 2026-08-17

## 1. Product Positioning

Spec Agent is a branchable requirement clarification agent.

It helps a single user start from a vague requirement and progressively clarify it through structured questions, selectable options, free-form answers, branching routes, controlled regeneration, route restoration, and traceable spec snapshots.

The product is not a generic chat assistant. It is not a broad project collaboration platform. Its value is the visible, controllable, replayable process from unclear requirement to clear spec.

## 2. First-Version Goal

The first version should prove that a user can:

1. Enter a vague requirement.
2. Receive one focused clarification node at a time.
3. Answer with either predefined options or free text.
4. See how the answer changes the requirement state.
5. Go back to a previous node and explore another route.
6. Regenerate a historical node without polluting the new route with the old answer.
7. Restore a previously superseded route.
8. Delete an entire route without breaking shared history.
9. Generate a spec snapshot whose conclusions are traceable to nodes, answers, and patches.

The first version is successful if the process feels more reliable and inspectable than a normal chat conversation.

## 3. Target User

The first version is for a single user who wants help clarifying a requirement before implementation, planning, or decision-making.

The requirement domain is intentionally generic. A user may clarify a software idea, service idea, process idea, product idea, operational requirement, or other requirement. The runtime must not hard-code any of those domains.

## 4. Product Promise

Spec Agent should make four things explicit:

- What has been confirmed.
- What is only assumed.
- What remains unresolved.
- Which route and sources produced the current spec.

## 5. Core Experience

The recommended first-version UI has three areas:

```text
Left: route tree and route lifecycle status
Center: current clarification node and answer controls
Right: current requirement state, agent rationale, and spec preview
```

The product should not default to a complex canvas. A route tree is enough. The tree exists to show branching and history, not to become a visual workflow editor.

## 6. Clarification Node

A node is an immutable clarification prompt in the exploration tree.

A node contains:

- Agent question.
- Question purpose.
- Options.
- Option impact explanations.
- Free-form answer support.
- Parent node id.
- Source AgentRun id.
- Optional `supersedesNodeId` when created by regeneration.

A good node asks one main question and explains why it matters.

## 7. Answer and Patch

Every answerable node must support both:

1. Option answer: the user chooses one predefined option.
2. Free-form answer: the user writes their own answer.

Free-form answers are first-class. They must not be stored as plain text only. They must be interpreted into structured claims, constraints, assumptions, risks, conflicts, and open questions.

Answer semantics:

- An Answer is immutable.
- A node can receive an answer once in a route flow.
- A historical answer must not be overwritten.
- Re-answering a historical node creates a new route, replacement node, or answer revision.

Patch semantics:

- An AnswerPatch is derived from one Answer.
- The current requirement state is reconstructed by replaying patches along the active route lineage.
- RequirementState may be cached, but cache is not source of truth.

## 8. Route Model

Routes are explicit objects pointing to a root and tip node. A route is a view over node lineage.

`active` is not a route lifecycle status. The current working route is represented by `Project.activeRouteId`.

Route lifecycle statuses:

```text
open | superseded | archived | deleted
```

Meanings:

- `open`: a normal route that may be selected as active.
- `superseded`: a gray historical route replaced by regeneration. It remains visible, inspectable, restorable, and forkable.
- `archived`: a user-hidden route that remains recoverable.
- `deleted`: a soft-deleted route excluded from normal workspace view and active context.

## 9. Route Operations

### 9.1 Continue Current Route

The user answers the current node. The system creates an immutable Answer, stores an AnswerPatch, generates the next node when needed, and advances `Project.activeRouteId`'s route tip.

### 9.2 Fork from Historical Node

The user can return to a historical node and start a new route from that node. The old route remains unchanged. The new route inherits only the selected node's lineage.

### 9.3 Regenerate Historical Node

Regeneration means the user is dissatisfied with a node itself. It does not edit the old node in place.

The system marks the selected route as `superseded`, creates a replacement node from the old node's parent lineage, and makes the replacement route active.

Allowed regeneration context:

- The old node's parent lineage.
- The old node question text.
- The old node purpose, if present.
- The user's regeneration instruction.

Forbidden regeneration context:

- The old node's user answer.
- The old node's answer patch.
- The old node's child nodes.
- The old route's spec snapshot.
- Sibling route conclusions.

### 9.4 Restore Superseded Route

A superseded route is not invalid. It is only non-current. The user may inspect it, restore it as the active route, or fork from it.

Restoration changes `Project.activeRouteId`. It must not merge route contexts.

### 9.5 Delete Route

Deleting a route means deleting the whole route from the user's active workspace view. The first version implements this as soft deletion.

Shared ancestor nodes must not be physically deleted. Deleted routes must not contribute to active context or spec generation.

## 10. Spec Snapshot

A spec snapshot is generated from a route tip. It is not source of truth.

The source of truth is:

- Node lineage.
- Answers.
- Answer patches.
- Route state.
- Context snapshots.

A spec section must carry source references. Unsupported content must be marked as assumption, suggestion, risk, or unresolved, not confirmed fact.

SpecPreview and SpecSnapshot are different:

- SpecPreview is transient UI output.
- SpecSnapshot is a persisted derived artifact.
- Neither is source of truth.

## 11. Non-Goals

The first version must not include:

- Multi-user workspaces.
- Role-based project permissions.
- Task boards.
- Gantt charts.
- Document upload and RAG.
- Browser automation.
- External tool execution.
- Code generation.
- Generic agent marketplace.
- Complex visual workflow canvas.
- Domain-specific requirement engines.

## 12. Anti-Overfitting Requirement

The product may be used for many requirement domains, but runtime code must remain domain-neutral.

Forbidden examples:

```java
if (projectType == SOFTWARE_PROJECT) {
    askSoftwareScopeQuestion();
}
```

```java
class MarketingPlanQuestionGenerator {}
class EcommerceRequirementAnalyzer {}
class StudentAssignmentSpecBuilder {}
```

Allowed concepts:

- `RequirementAspect`
- `Claim`
- `Answer`
- `AnswerPatch`
- `Route`
- `Node`
- `SpecSection`
- `RequirementProfile`
- `QuestionPolicy`

Specific domain behavior may be expressed through configurable profiles, prompts, examples, and user content, not runtime branches.

## 13. Acceptance Criteria

The first version is acceptable when:

1. A user can create a project from an initial vague requirement.
2. The system creates a first clarification node.
3. Every node supports option answers and free-form answers.
4. A user answer produces an immutable Answer and a structured AnswerPatch.
5. Requirement state is reconstructed from the active route lineage.
6. A user can fork from a historical node.
7. A user can regenerate a historical node.
8. Regeneration does not inherit the old answer or old child route content.
9. The old route becomes visible as superseded and can be restored.
10. A user can soft-delete an entire route.
11. A generated spec is tied to the current route tip.
12. Spec conclusions carry source references.
13. Unsupported or inferred content is marked as assumption, suggestion, risk, or unresolved.
14. Runtime code contains no concrete business-domain branches.
15. Tests prove sibling routes, superseded routes, and deleted routes do not pollute active context.

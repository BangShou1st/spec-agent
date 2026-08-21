# Node Model V2

## 1. Core Definition

A Node is a **Workspace Unit**: a durable, addressable unit in the Graph that can carry content, be connected, become an Agent context anchor, and participate in route/history semantics.

A Node is **not only a Question**, and it is also broader than “Knowledge Item”. A Node may represent knowledge, interaction, external resources, or generated artifacts.

```text
Node
 ├── identity
 ├── kind / subtype
 ├── content
 ├── provenance
 ├── knowledge state (when applicable)
 ├── route / relation participation
 └── creation metadata
```

## 2. Stable Outer Kinds

Keep the outer model small and extensible.

### KNOWLEDGE

Human/Agent-created content that contributes to the requirement space.

Typical subtypes:

- IDEA
- NOTE
- REQUIREMENT
- DECISION
- RISK
- ASSUMPTION

### INTERACTION

Content that expects or records an interaction pattern.

Initial subtype:

- QUESTION

A Question Node can be answered. The immutable Answer remains a separate runtime record/event rather than becoming mutable Node content.

### RESOURCE

External context source.

Typical subtypes:

- FILE
- IMAGE
- URL
- REPOSITORY
- API_DOCUMENTATION
- TEXT

Do not create a new domain class such as `PdfNode`, `GithubNode`, or `SwaggerNode` for every resource. Resource-specific behavior belongs in capability/content handlers.

### ARTIFACT

Generated or curated outputs.

Typical subtypes:

- SUMMARY
- SPEC
- REPORT

The existing `SpecSnapshot` remains valid infrastructure; V2 does not require an immediate migration of every artifact into the Node table.

## 3. Draft / Blank Node Is First-Class

Users must be able to create a Node before they know exactly what it is.

A new user-authored draft may temporarily have:

```text
kind = KNOWLEDGE
subtype = NOTE (or equivalent generic draft subtype)
content = empty or partial
```

The user may then:

- type a requirement or idea;
- change the draft subtype while it is still user-editable;
- ask AI about this Node;
- connect it from an existing Node;
- continue exploration from it.

Creating a project must not automatically create a requirement or infer a goal from the project title.

## 4. Separate Runtime Progress from Knowledge State

Do **not** use one universal `NodeStatus` for unrelated meanings.

### 4.1 Generation / operation progress

This describes whether an Agent operation has finished:

```text
PENDING -> RUNNING -> SUCCEEDED | FAILED
```

This belongs to `AgentRun` / operation state and its UI projection. It is not the knowledge truth of the Node.

A half-generated Node should not be persisted and mutated field-by-field merely to show progress. The UI may render a **virtual pending card** from an in-flight route/run, then replace it with the atomically persisted Node after validation.

### 4.2 Knowledge state

Only content that represents claims/decisions needs knowledge-state semantics, for example:

```text
PROPOSED -> CONFIRMED
     |          |
     v          v
CHALLENGED -> SUPERSEDED
```

Not every Node kind must use every knowledge state. A Question or Resource does not need to become a “confirmed question/resource”.

`LOCKED` is not a generic Node lifecycle. If future product behavior needs locking, define it as an explicit editing/publishing policy for the relevant artifact or confirmed content.

## 5. Immutability and Editing

Different Node content has different mutability rules:

- Agent-generated accepted Question content should remain immutable after creation; replacement creates a new Node/route history rather than silently rewriting the old question.
- User-created draft content may be edited while it remains a draft.
- Once a user action has created durable downstream history, later semantic changes should use revision/replacement/compensating operations instead of rewriting history.
- Answers remain immutable and route-scoped.

The exact persistence representation may evolve, but the product invariant is append-preserving history.

## 6. Provenance

Every durable Node must preserve provenance sufficient to answer:

- who/what created it (user, agent run, capability result);
- from which route/context it was created;
- which source references grounded it, when applicable;
- whether it supersedes/revises earlier content.

Runtime owns IDs and provenance. The model cannot invent runtime IDs.

## 7. Extension Rule

Adding a new product ability should first ask:

1. Is this a new stable Node kind, or only a new subtype/content handler?
2. Can the Agent reason about it through generic metadata/capabilities?
3. Can the new behavior be added without modifying Planner core branches?

Prefer new subtype/handler/capability over new business-specific Agent code.

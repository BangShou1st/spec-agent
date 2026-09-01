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

An Agent-authored `DECISION` (or any other Agent-created content that changes confirmed user intent) is a `CONFIRMED_INTENT_CHANGE`: it always requires explicit user confirmation before becoming durable Graph state, regardless of model confidence (see `AGENT_AUTONOMY_MODEL.md`).

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
- Answers stay immutable and unique per canonical Question: at most one Answer identity project-wide (Shared Node = Shared State, see `GRAPH_MODEL_V2.md` §5); re-answer creates a fresh canonical Question identity rather than a second Answer.

The exact persistence representation may evolve, but the product invariant is append-preserving history.

## 6. Provenance

Every durable Node must preserve provenance sufficient to answer:

- who/what created it (user, agent run, capability result);
- from which route/context it was created;
- which source references grounded it, when applicable;
- whether it supersedes/revises earlier content.

Runtime owns IDs and provenance. The model cannot invent runtime IDs.

## 7. UX Contract

These are frozen product-level UX invariants for Node rendering and interaction.

### 7.1 Q Label Convention

Question Nodes use concise sequential labels (`Q1`, `Q2`, `Q3`) instead of state titles like "当前问题/历史问题". The question body text is always displayed alongside the label.

A `最新` (Latest) marker identifies the most recently produced or currently generating exploration position. At most one Latest marker exists per workspace visual context.

### 7.2 Option Layout

Options use a vertical structure: label on its own line, impact/explanation below. This layout must remain readable at varying Node widths and must not compress label and impact into side-by-side columns.

### 7.3 Non-Blocking Submission

When the user submits an answer:

- Only the controls that would cause duplicate submission are disabled.
- Canvas dragging, zooming, viewing other Nodes, and Fork/Focus operations that do not conflict with the current mutation remain available.
- The user's selected option or free text remains visible until the run succeeds or the user actively modifies it.
- Dragging the current question, switching focus, or rearranging the view must not clear the option/input state.
- On failure, the original answer input and retry affordance are preserved.

### 7.4 Input State Preservation

All input state is modeled per `nodeId + route/read context` in the frontend store, not by component mount lifecycle. This ensures that option selections and free-text input survive Canvas interactions, focus switches, and view rearrangements.

### 7.5 Hover / Focus Actions

Default cards stay low-noise. Secondary actions (continue, Fork, connect, regenerate, re-answer) appear on hover, keyboard focus, or Node selection. High-risk operations (delete, archive) go in a `More` menu. Important states (running, failed, awaiting confirmation) are always visible without hover.

### 7.6 Layout Stability

When a new Node completes:

- Reveal the new Node without triggering a full-graph relayout.
- Preserve the user's current viewport and existing Node positions.
- Auto-focus should be a lightweight reveal/pan, not a disruptive rearrangement that hides other routes.

### 7.7 Dynamic Progress

The UI may display verifiable Runtime phases (e.g., "正在保存回答…", "正在规划下一步…"). These are real Runtime status, not fabricated chain-of-thought. The UI must never display unrecorded internal model reasoning.

## 8. Extension Rule

Adding a new product ability should first ask:

1. Is this a new stable Node kind, or only a new subtype/content handler?
2. Can the Agent reason about it through generic metadata/capabilities?
3. Can the new behavior be added without modifying Planner core branches?

Prefer new subtype/handler/capability over new business-specific Agent code.

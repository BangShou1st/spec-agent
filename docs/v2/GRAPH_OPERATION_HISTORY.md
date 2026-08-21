# Graph Operation History V2

## 1. Purpose

Provide Word-like Undo / Redo without violating Spec Agent's append-preserving history and immutable-answer guarantees.

Undo/Redo is a **Runtime operation model**, not a Vue-only state stack and not a reuse of `ContextSnapshot`/`SpecSnapshot` as if they were full workspace checkpoints.

## 2. Operation Log

User-visible durable mutations should be represented as typed operations, for example:

```text
CREATE_DRAFT_NODE
EDIT_DRAFT_NODE
CONNECT_NODE
CREATE_ROUTE
CHANGE_FOCUS          (UI/read state may remain client-local if not durable)
FINALIZE_ANSWER
REVISE_ANSWER
SUPERSEDE_NODE
ARCHIVE_ROUTE
ACCEPT_AGENT_PROPOSAL
```

Logical record:

```json
{
  "operationId": "...",
  "projectId": "...",
  "actor": "USER|AGENT|SYSTEM",
  "type": "...",
  "targets": [],
  "beforeRefs": [],
  "afterRefs": [],
  "causedBy": "...",
  "reversible": true,
  "createdAt": "..."
}
```

Runtime owns operation semantics and history.

## 3. Undo Is Operation-Specific Compensation

Do not physically delete history merely to make the UI look reverted.

Examples:

### Draft edit

Undo may restore the prior editable draft version.

### Create Node / Route

Undo may hide/archive/retract it from the current materialized workspace while preserving operation provenance.

### Finalized Answer

An immutable Answer is not deleted or overwritten. Undo/revision must use the domain's revision/branch/re-answer mechanism and make the previous effective state visible again where allowed.

### Agent downstream work

If a user operation triggered downstream Agent-created Nodes, undoing the source operation must explicitly determine which derived work becomes inactive/superseded in the current view. It must not leave silently orphaned “current” results.

## 4. Redo

Redo re-applies the original logical operation only when its preconditions are still valid.

If intervening work changes the Graph such that replay would violate invariants, Redo must become unavailable or require a new explicit operation.

Never force replay over conflicting history.

## 5. Checkpoints

Operation log is the semantic source for Undo/Redo.

Runtime may create periodic materialized Graph checkpoints for efficient restoration/time-travel, but checkpoints are an optimization over operation history, not a replacement for operation semantics.

Existing `ContextSnapshot` remains an Agent context artifact and `SpecSnapshot` remains a generated-spec artifact; neither is automatically a full Graph undo checkpoint.

## 6. External Side Effects

Graph Undo cannot promise to reverse external capability effects.

Examples:

- read-only MCP call: no external mutation;
- create remote issue/message/file: may be reversible only via a separate provider action;
- send email/message or irreversible API call: cannot be undone by Graph Ctrl+Z.

UI must communicate non-reversible effects before execution where relevant.

## 7. Agent Runs and Undo

Undo/Redo operations can themselves produce new Graph events and may require re-evaluation.

Rules:

- do not silently rerun the model for every UI undo;
- first restore/apply Runtime graph state;
- only start a new Agent Decision Cycle when the resulting operation semantics require Agent follow-up;
- any downstream Agent run must be traceable to the undo/redo operation.

## 8. Client UX

Expose familiar controls:

```text
Undo  Redo
```

Optional short confirmation/status copy:

```text
已撤销：添加需求节点
已恢复：路线「MVP」
```

Do not expose internal storage mechanics.

## 9. Initial Scope Recommendation

Implementation may begin with fully reversible local Graph authoring operations and progressively add domain-specific compensation for answers/routes.

The product contract remains: users get a coherent forward/back experience, while durable history is preserved rather than destructively rewritten.

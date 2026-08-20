# Python Agent Runtime Boundary

## Principle

Keep Graph Runtime and Agent Brain separate.

## Spring Runtime Responsibilities

- API
- Authentication
- Database
- Graph persistence
- Routes
- Snapshots
- Transactions
- Validation

## Python Agent Runtime Responsibilities

- Agent State construction
- Reflection
- Planning
- LLM orchestration
- Evaluation

Communication should use generic events and actions.

Spring sends:

```json
{
 "event":"NODE_UPDATED",
 "graphContext":{}
}
```

Python returns:

```json
{
 "action":"CREATE_NODE",
 "payload":{}
}
```

The boundary must not expose domain-specific workflows.

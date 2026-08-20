# Agent Action Protocol V2

## Principle

Agent never writes Graph directly.

Flow:

```
Agent
 |
 v
Action Proposal
 |
 v
Policy Engine
 |
 v
Validator
 |
 v
Graph Mutation
```

## Initial Actions

- ASK_USER
- CREATE_NODE
- UPDATE_NODE
- LINK_NODE
- CREATE_ROUTE
- MARK_RISK
- CREATE_SUMMARY
- GENERATE_SPEC
- WAIT

Every action should include:

- action type
- payload
- confidence
- reason
- risk level
- approval requirement

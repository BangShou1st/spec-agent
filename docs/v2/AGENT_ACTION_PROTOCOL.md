# Agent Action Protocol

Agent never directly modifies persistence.

```
Agent
 |
 v
Action Proposal
 |
 v
Runtime Validator
 |
 v
Graph Mutation
```

## Initial Actions

```
ASK_USER
CREATE_NODE
UPDATE_NODE
LINK_NODE
CREATE_ROUTE
MARK_RISK
SUMMARIZE
GENERATE_SPEC
WAIT
```

Actions are generic Graph operations, not requirement-specific commands.

Example:

```json
{
  "action": "CREATE_NODE",
  "type": "QUESTION",
  "content": {
    "question": "Does this require realtime synchronization?"
  }
}
```

Runtime validates ownership, route rules, history constraints and consistency before applying changes.

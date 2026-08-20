# Agent State Schema

Agent State is the temporary cognition of the agent.

It is not chat history and not the source of truth.

## Structure

```json
{
  "objective": {},
  "focus": {},
  "knownFacts": [],
  "unknowns": [],
  "conflicts": [],
  "risks": [],
  "constraints": [],
  "availableActions": []
}
```

## Rules

- Graph stores facts.
- Agent State stores interpretation.
- State may be rebuilt.
- Historical truth remains in Graph snapshots.

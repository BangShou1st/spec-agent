# Agent State Model

Agent State is not chat history. It is the Agent's current understanding of the Graph.

```
AgentState
 ├── graphContext
 ├── objective
 ├── understanding
 ├── uncertainties
 ├── conflicts
 ├── risks
 ├── focus
 └── history
```

## graphContext
References current graph facts.

## objective
Current goal of exploration.

It must not be inferred only from project names.

## understanding
Temporary Agent interpretation with confidence.

## uncertainties
Information gaps that may require exploration.

## conflicts
Contradictory knowledge requiring resolution.

## focus
Prevents uncontrolled exploration across unrelated areas.

Agent State is derived and replaceable. The Graph remains the durable source of truth.

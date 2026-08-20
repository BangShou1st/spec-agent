# Agent Runtime Migration Guide

## Goal

Move from workflow-driven execution to graph reasoning while preserving existing capabilities.

## Preserve

- Graph
- Node
- Route
- Snapshot
- Recovery
- AgentRun trace
- Model Gateway

## Migration Steps

1. Introduce AgentState and Action contracts.
2. Wrap existing answer interpretation flow as actions.
3. Add Reflection Engine.
4. Add Planner decision layer.
5. Add Policy Engine.

Migration must be incremental and avoid replacing stable runtime components.

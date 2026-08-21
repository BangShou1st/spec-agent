# Agent Runtime Migration Guide (Compatibility Entry)

This file remains as a short migration pointer.

Canonical implementation/migration order:

- `docs/v2/AGENT_RUNTIME_IMPLEMENTATION_PLAN.md`

Preserve throughout migration:

- Graph / Node / Route history semantics;
- immutable Answer;
- AnswerPatch repair checkpoints;
- ContextSnapshot lineage isolation;
- AgentRun trace/failure persistence;
- Runtime-owned validation and persistence;
- Model Gateway boundary.

Do not perform a “big bang” rewrite. The canonical plan introduces contracts first, then state projection, two-call answer convergence, Decision Engine/Policy, Graph V2 interaction, UI projection, operation history, Capability Runtime and only later an optional Python brain.

# Python Agent Runtime Boundary V2

## 1. Principle

Keep authoritative Graph Runtime and Agent Brain replaceable through a versioned contract.

Python is a future **Decision Engine implementation option**, not a second application backend and not a database owner.

## 2. Spring Runtime Responsibilities

Spring remains authoritative for:

- HTTP/API boundary;
- authentication/authorization;
- database persistence;
- Node / Answer / Route / Snapshot / Operation History;
- Graph invariants and transactions;
- immutable Answer and recovery checkpoints;
- AgentRun lifecycle/trace;
- `AgentInputSnapshot` construction;
- allowed source refs;
- capability registry filtering and permission scope;
- Policy Engine;
- Action validation;
- Graph mutation execution;
- secrets/provider credentials.

**Agent State construction that depends on authoritative Graph semantics belongs here as deterministic input projection.** Python must not reconstruct truth by querying database tables independently.

## 3. Python Decision Engine Responsibilities

A future Python service/module may own:

- model orchestration inside one Decision Cycle;
- reflection + planning reasoning;
- structured `AgentDecision` generation;
- optional evaluator/critic strategies when explicitly enabled by policy/evidence;
- model-independent experimentation with planner implementations.

It consumes a frozen, versioned `AgentInputSnapshot` and returns proposals only.

## 4. Stable Interface

Conceptual request:

```json
{
  "protocolVersion": "agent-input.v2",
  "runId": "runtime-owned-id",
  "event": {},
  "snapshot": {},
  "capabilities": [],
  "decisionBudget": {}
}
```

Conceptual response:

```json
{
  "protocolVersion": "agent-decision.v2",
  "observation": {},
  "actionProposal": {},
  "usage": {},
  "diagnostics": {}
}
```

Runtime validates the response before any persistence/tool execution.

## 5. Forbidden Python Responsibilities

Python must not:

- directly access production DB as part of normal Agent reasoning;
- assign authoritative Node/Route/Answer IDs;
- silently mutate Graph state;
- store a competing durable project memory;
- own project route selection truth;
- receive raw provider/API credentials unless a narrowly defined future adapter explicitly requires it;
- bypass Capability/Policy approvals;
- assume MCP connections are unrestricted.

## 6. Java First, Python Later

Do not migrate merely because Python has more Agent libraries.

First create Java interfaces/contracts so the current Runtime can support:

```text
AgentDecisionEngine
  ├── LocalJavaDecisionEngine
  └── RemotePythonDecisionEngine
```

Only introduce Python when it provides measurable value such as faster Agent experimentation, richer model/tool libraries, or evaluation workflows.

The application must remain correct if the Decision Engine implementation changes.

## 7. Capability Execution

Python may request:

```text
INVOKE_CAPABILITY(capabilityId, args)
```

but the host Capability Runtime performs resolution, permission checks and execution.

For MCP specifically, the host owns connections, credentials, resource/tool exposure and side-effect policy. Python sees only allowed descriptors/results.

## 8. Failure Model

Remote Python failure should produce a typed Decision Engine failure. Spring keeps the existing durable Answer/Graph checkpoints and recovery semantics.

Do not fall back automatically to another planner/provider in a way that can duplicate mutations.

## 9. Performance

The remote boundary must not force Reflection and Planning into separate HTTP/LLM round trips. The default API represents one Decision Cycle.

Track serialization overhead, remote latency and total model calls before deciding that Python is beneficial in production.

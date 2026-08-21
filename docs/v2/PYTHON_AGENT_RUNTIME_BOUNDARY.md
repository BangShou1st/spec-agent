# Python Agent Runtime Boundary V2

> Status: **Frozen 2026-08-21** after owner approval of `CODE_ARCHITECTURE_REVIEW_2026-08-21.md`.
> Python is bootstrapped in Stage A, before any new V2 reasoning logic is written.

## 1. Principle

Keep authoritative Graph Runtime and Agent Brain replaceable through a versioned contract.

Python is the **Decision Engine implementation for V2** (the `agent-brain` service), not a second application backend and not a database owner. New V2 reasoning code starts in Python from the first stage; stable Graph, persistence, recovery, policy and provider transport stay in Java permanently.

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

The Python `agent-brain` service owns:

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
- receive raw provider/API credentials (model inference goes through the Java inference broker; Python never holds keys);
- bypass Capability/Policy approvals;
- assume MCP connections are unrestricted.

## 6. Python Early (Stage A) and the Internal Inference Broker

New V2 reasoning code starts in Python from the first implementation stage. Implementing Reflection/Planning/prompt orchestration in Java first and migrating later would create avoidable double work and two implementations of the most changeable layer.

This does **not** move the application backend to Python. The stable Java code is primarily Runtime and provider infrastructure, which stays in Java permanently unless a future review proves otherwise:

```text
AgentDecisionEngine
  ├── RemotePythonDecisionEngine   (V2 default)
  └── LocalFakeDecisionEngine      (deterministic tests)
```

Model inference crosses the language boundary through a host-owned broker, so provider transport is never duplicated:

```text
Spring background AgentRun worker
        |
        v
Python Agent Brain
        | internal model inference request
        v
Spring Internal Model Inference Broker
        |
        v
existing Java OpenCode transport/settings
        |
        v
provider
```

Consequences:

- Python owns prompts and model-call orchestration for V2;
- Java owns provider credentials, selected model and HTTP transport;
- Python never receives the OpenCode API key;
- swapping Agent Brain implementations does not touch providers; provider changes do not touch the Brain.

Broker safety requirements:

- internal-network access only where possible, with service authentication/shared internal secret;
- requests tied to `runId` and call budget;
- no arbitrary user-supplied URL/header forwarding;
- no API key in responses/logs/traces;
- no provider fallback and no hidden retry;
- call type and prompt hashes recorded as sanitized AgentRun events;
- the frozen OpenCode completion transport contract is preserved.

Asynchronous AgentRun execution is the prerequisite for this boundary: browser commands create a durable run and return 202 immediately, so no request thread waits while Spring ↔ Python ↔ Spring calls complete.

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

Track serialization overhead, remote latency and total model calls continuously. If the remote boundary measurably harms latency without offsetting benefits, revisit the decision inside a stage gate — never by silently duplicating provider transport into Python.

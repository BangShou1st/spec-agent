# Agent Action Protocol V2

## 1. Purpose

Define a small, domain-general contract between the Decision Engine and Runtime.

The protocol must prevent two failure modes:

1. LLM directly mutates persistence.
2. Action space grows into hundreds of business-specific commands (`ANALYZE_FILE`, `ASK_PRICING`, `CREATE_RISK_FOR_API`, ...).

## 2. Core Flow

```text
Decision Engine
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
      +--------> Graph Executor
      |
      +--------> Capability Runtime
```

Runtime assigns IDs, validates references and owns history.

## 3. Action Families

Keep the outer action family small.

### 3.1 `CREATE_NODE`

Propose a new Node/workspace unit.

Payload includes stable kind/subtype and content. Examples such as Risk or Requirement are payload semantics, not separate action names.

```json
{
  "action": "CREATE_NODE",
  "payload": {
    "kind": "INTERACTION",
    "subtype": "QUESTION",
    "content": {
      "question": "...",
      "purpose": "..."
    },
    "continuationFrom": "source-ref"
  }
}
```

### 3.2 `UPDATE_NODE`

Propose a legal state/content transition for an existing editable/revisable Node.

Runtime decides whether the requested change is an in-place draft edit, knowledge-state transition, revision/replacement, or forbidden historical rewrite.

### 3.3 `CONNECT_NODE`

Propose a continuation or semantic relation.

Payload must declare relation class explicitly:

```text
CONTINUATION
SEMANTIC
```

Runtime forbids historical insertion that would rewrite established continuation lineage.

### 3.4 `CREATE_ROUTE`

Propose a branch/route when a new continuation requires explicit route identity.

Structural route creation itself may be user-driven and require no model call.

### 3.5 `REQUEST_USER_INPUT`

Request user input and optionally include a proposed Question Node payload.

This is the normal planner action for “ask the user next”. It prevents needing a separate third LLM request solely to write the question after the planner already chose to ask.

### 3.6 `INVOKE_CAPABILITY`

Ask Capability Runtime to resolve and execute an available capability.

Examples:

- read/extract file knowledge;
- invoke an MCP tool;
- retrieve a resource;
- call an internal analyzer;
- perform a search when such capability is available.

Planner references capability descriptors, not concrete SDK/client implementation details.

### 3.7 `GENERATE_ARTIFACT`

Generate a derived artifact such as Spec/Summary/Report through the existing artifact runtime. This remains distinct because artifact persistence/grounding may have stronger invariants than ordinary Node creation.

### 3.8 `WAIT`

Take no graph mutation and stop the current automatic cycle. Typical reasons:

- information is sufficient for now;
- user must choose what to explore next;
- no useful action is justified;
- policy/budget says to stop.

## 4. One Primary Action per Decision Cycle

The Decision Engine returns one **primary** action for a normal cycle.

This prevents hidden fan-out such as creating five nodes, two routes and three tool calls from one opaque model response.

A state-update/extraction stage may produce multiple grounded claims as one checkpoint because that is data normalization, not free-form planning.

If a capability result requires another decision, Runtime starts the next bounded cycle with a new `AgentInputSnapshot`.

## 5. Proposal Envelope

Recommended logical envelope:

```json
{
  "protocolVersion": "agent-action.v2",
  "action": "REQUEST_USER_INPUT",
  "payload": {},
  "evidenceRefs": [],
  "confidence": 0.0,
  "rationaleSummary": "",
  "modelSuggestedRisk": "MEDIUM"
}
```

Rules:

- `evidenceRefs` must come from Runtime-provided allowed refs.
- `confidence` is a signal, not authorization.
- `modelSuggestedRisk` is advisory only; Policy Engine computes/enforces actual policy.
- `rationaleSummary` is a short user-safe/trace-safe explanation, not hidden chain-of-thought.
- IDs not supplied in allowed context must never be invented.

## 6. Validation

Runtime validates at least:

- schema/version;
- project/route ownership;
- referenced Node/Answer/source existence;
- continuation history rules;
- Node kind/subtype/content contract;
- allowed capability ID and argument schema;
- side-effect classification;
- approval requirement;
- idempotency/replay safety;
- duplicate/repeated no-progress action detection.

## 7. Why `MARK_RISK` / `CREATE_SUMMARY` Are Not Core Actions

`MARK_RISK` is `CREATE_NODE(kind=KNOWLEDGE, subtype=RISK)`.

A Graph summary can be `CREATE_NODE(...ARTIFACT/SUMMARY...)` or `GENERATE_ARTIFACT`, depending on persistence requirements.

This keeps the action protocol extensible without teaching Planner a new verb for every content type.

## 8. Retry and Failure

Action retry policy belongs to Runtime/Capability policy, not the model.

A failed capability/model action becomes a typed failure/observation. Runtime may:

- expose user retry;
- stop and wait;
- retry only if a specific idempotent policy allows it;
- never silently switch provider/model or duplicate a user mutation.

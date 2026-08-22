# Agent Runtime V2 Cross-Language Contracts

> Status: **Frozen wire contract for Stage A** (see `docs/v2/PYTHON_AGENT_RUNTIME_BOUNDARY.md`).
> This directory is the single authority for the Java ↔ Python agent-brain wire
> format and its golden fixtures. Both implementations must accept/reject these
> fixtures identically.

## 1. Protocol versions

| Contract | Version |
|---|---|
| Request envelope (Spring → Python) | `agent-input.v2` |
| Response envelope (Python → Spring) | `agent-decision.v2` |
| Internal model inference (Python → Spring broker) | `model-inference.v1` |

Unknown protocol versions are rejected by both sides, fail-closed.
Unknown fields are rejected by both sides (`extra = forbid` in Pydantic,
`FAIL_ON_UNKNOWN_PROPERTIES` in the Java strict mapper).

## 2. Request envelope — `POST /v1/state-updates`, `POST /v1/decisions`

```json
{
  "protocolVersion": "agent-input.v2",
  "runId": "<runtime-owned uuid>",
  "event": {
    "kind": "INITIAL | CONTINUE | ANSWER_SUBMITTED",
    "anchorNodeId": "<uuid|null>",
    "selectedOptionId": "<uuid|null>",
    "freeText": "<string|null>"
  },
  "snapshot": {
    "snapshotId": "<uuid>",
    "contextHash": "<sha256 hex>",
    "projectId": "<uuid>",
    "routeId": "<uuid>",
    "anchorNodeId": "<uuid|null>",
    "routeContext": {"routeId": "<uuid>", "tipNodeId": "<uuid|null>", "label": "<string|null>"},
    "lineage": [
      {
        "node": {
          "id": "<uuid>",
          "body": {
            "text": "<string>",
            "options": [{"id": "<uuid>", "label": "<string>"}],
            "acceptsFreeText": true
          }
        },
        "answer": {"id": "<uuid>", "nodeId": "<uuid>", "selectedOptionId": "<uuid|null>", "freeText": "<string|null>"},
        "patches": [{"id": "<uuid>", "claims": ["<claim view>"]}]
      }
    ],
    "effectiveClaims": ["<claim view>"],
    "metadata": {"projectTitle": "<low-authority display metadata>"},
    "allowedSourceRefs": ["node:<uuid>", "answer:<uuid>", "patch:<uuid>", "context:<uuid>", "route:<uuid>"],
    "availableCapabilities": [],
    "autonomy": {"mode": "ADVISOR"}
  },
  "capabilities": [],
  "decisionBudget": {"maxModelCalls": 2}
}
```

Rules:

- All IDs are runtime-owned. The brain never assigns authoritative IDs.
- `metadata.projectTitle` is low-authority display metadata only; it must never
  be promoted to objective/requirement/scope by prompts or reasoning.
- The snapshot may only be referenced through `allowedSourceRefs`; any other
  source reference in a response is rejected Java-side.
- Node bodies use generic Graph language (`body.text`, `acceptsFreeText`);
  question-workflow names must not appear in the contract.

### Claim view

```json
{
  "kind": "goal | stakeholder | scope | constraint | success_criterion | output_expectation | risk | assumption | open_question | conflict | other",
  "text": "<string>",
  "status": "confirmed | assumed | unresolved | rejected",
  "confidence": 0.0-1.0,
  "sourceNodeId": "<uuid|null>",
  "sourceAnswerId": "<uuid|null>"
}
```

## 3. Response envelope — state update

```json
{
  "protocolVersion": "agent-decision.v2",
  "runId": "<same as request>",
  "stateUpdate": {
    "claims": [
      {"kind": "...", "text": "...", "status": "...", "confidence": 0.9, "sourceRefs": ["answer:<uuid>"]}
    ]
  },
  "observation": null,
  "actionProposal": null,
  "usage": {"modelCalls": 1, "promptHashes": ["<sha256 hex>"]},
  "diagnostics": {}
}
```

Exactly one of `stateUpdate` / (`observation` + `actionProposal`) must be
non-null, matching the called endpoint.

## 4. Response envelope — decision

```json
{
  "protocolVersion": "agent-decision.v2",
  "runId": "<same as request>",
  "stateUpdate": null,
  "observation": {
    "known": ["<grounded statement>"],
    "unknowns": ["<missing information>"],
    "conflicts": ["<incompatible claims>"],
    "risks": ["<observed risk>"]
  },
  "actionProposal": {
    "actionFamily": "CREATE_NODE | UPDATE_NODE | CONNECT_NODE | CREATE_ROUTE | REQUEST_USER_INPUT | RESPOND_TO_USER | INVOKE_CAPABILITY | GENERATE_ARTIFACT | WAIT",
    "payload": {"...family-specific..."},
    "baseContextSnapshotId": "<must equal request.snapshot.snapshotId>",
    "baseContextHash": "<must equal request.snapshot.contextHash>",
    "sourceRefs": ["<subset of allowedSourceRefs>"]
  },
  "usage": {"modelCalls": 1, "promptHashes": []},
  "diagnostics": {}
}
```

Stage A fail-closed validation rules (enforced again in Java, which never
trusts the brain):

- `actionFamily` must be in the closed set above;
- `baseContextSnapshotId` / `baseContextHash` must echo the request snapshot;
- every `sourceRefs` entry must be in the request's `allowedSourceRefs`;
- proposal payloads must not contain runtime-owned identity fields
  (e.g. option/node ids invented by the model or brain);
- `REQUEST_USER_INPUT` payload requires non-blank `questionText`,
  an options array of `{label}` objects, and boolean `allowFreeAnswer`;
- confidence/risk fields in a response can never authorize execution by
  themselves; policy authority stays in Java (Stage B).

## 5. Internal model inference — `POST /internal/v1/model-inference`

Request (Python → Spring broker), authenticated by the shared internal token
header `X-Spec-Agent-Internal-Token`:

```json
{
  "protocolVersion": "model-inference.v1",
  "runId": "<uuid>",
  "callType": "STATE_UPDATE | DECISION",
  "messages": [{"role": "system", "content": "..."}, {"role": "user", "content": "..."}],
  "maxOutputTokens": 2048
}
```

Response:

```json
{
  "protocolVersion": "model-inference.v1",
  "content": "<completion text>",
  "finishReason": "stop",
  "usage": {"promptTokens": 0, "completionTokens": 0}
}
```

Broker guarantees: no provider key is ever sent to or echoed to Python; no
arbitrary URL/header forwarding; no provider fallback; no hidden retry; calls
are tied to `runId` and recorded as sanitized AgentRun events (call type +
prompt/output hashes only).

## 6. Golden fixtures

Both implementations run their contract test suites against
`fixtures/*.json`. A fixture with suffix `-invalid-` must be rejected by both
sides with a contract violation.

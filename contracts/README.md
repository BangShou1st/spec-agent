# Agent Runtime V2 Cross-Language Contracts

> Status: **Frozen wire contract** (Stage A frozen; Stage C additions marked below — see `docs/v2/PYTHON_AGENT_RUNTIME_BOUNDARY.md`).
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
    "kind": "INITIAL | CONTINUE | ANSWER_SUBMITTED | NODE_QUERY (Stage C)",
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
          },
          "kind": "KNOWLEDGE | INTERACTION | RESOURCE | ARTIFACT (Stage C; default INTERACTION)"
        },
        "answer": {"id": "<uuid>", "nodeId": "<uuid>", "selectedOptionId": "<uuid|null>", "freeText": "<string|null>"},
        "patches": [{"id": "<uuid>", "claims": ["<claim view>"]}]
      }
    ],
    "effectiveClaims": ["<claim view>"],
    "metadata": {"projectTitle": "<low-authority display metadata>"},
    "allowedSourceRefs": ["node:<uuid>", "answer:<uuid>", "patch:<uuid>", "context:<uuid>", "route:<uuid>"],
    "availableCapabilities": [],
    "capabilityResults": [],
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
- Stage C: non-interaction nodes project their primary content payload into
  `body.text` (the `question` column stays interaction-only), and `node.kind`
  carries the stable outer classification. `NODE_QUERY` events carry the user's
  contextual question in `freeText`; the expected primary action is
  `RESPOND_TO_USER`. A NODE_QUERY **never auto-applies** a confirmable Graph
  mutation: if the user explicitly requests a Graph mutation the Brain may
  return a mutation proposal, and the Java runtime persists it as an
  `AgentProposal` awaiting explicit user Accept/Reject (`AWAITING_APPROVAL`).
  AdvisorPolicy denies an unacceptable proposal (`POLICY_DENIED`) or marks an
  unexecutable one not-confirmable (`NOT_CONFIRMABLE`); it never auto-applies.
- Stage C NODE_QUERY routeless nullability: a `NODE_QUERY` against a
  Floating (routeless) Graph node is the **only** semantic flow that may
  carry `snapshot.routeId = null` and `snapshot.routeContext.routeId = null`.
  The invariant is exact and applies at the envelope level on both sides of
  the wire (Java strict mapper + Python `_route_id_contract` validator):

  - Route IDs are **required and equal** for all route-bound snapshots
    (`STATE_UPDATE`, normal `DECISION`, `ANSWER`, `SPEC`, `REGENERATE`).
  - **Only** `NODE_QUERY` may use the routeless mode.
  - Routeless mode requires **both** `snapshot.routeId` and
    `snapshot.routeContext.routeId` to be null simultaneously; mixed state
    (one null, one UUID) is rejected.
  - A `NODE_QUERY` that carries route UUIDs must keep the two fields equal;
    mismatched route-bound NODE_QUERY is rejected.
  - The anchor node itself is still a real Graph node, the snapshot still
    carries `projectId` / `snapshotId` / `contextHash`, and
    `allowedSourceRefs` must not invent a `route:` reference to substitute
    for the missing route identity.
  - See `fixtures/agent-input-routeless-node-query-valid.json` for the only
    valid routeless shape; the negative cases are exercised by
    `agent-brain/tests/test_contracts.py` and
    `backend/.../AgentCrossLanguageContractTest.java`.
- Stage D: `availableCapabilities` are runtime-owned descriptors filtered by
  permission and context relevance (`supports` declarations against lineage
  node kinds) — the planner never sees implementation classes, and irrelevant
  capabilities are invisible. `INVOKE_CAPABILITY` payloads carry
  `{capabilityId, arguments}`; any argument value shaped like a runtime ref
  must be inside `allowedSourceRefs`. `capabilityResults` are bounded
  observations from earlier invocations (external evidence / generated
  summaries with `sourceRefs` + `provenance`) — they are never auto-confirmed
  graph truth.
- Stage C bounded 1-hop semantic context (`NODE_QUERY` only; empty lists for
  every other operation): `snapshot.relations` is the ACTIVE SEMANTIC
  relations touching the anchor, direction preserved exactly as stored
  (`sourceNodeId` → `targetNodeId` → `relationType`). `snapshot.relatedNodes`
  is the distinct other-end canonical nodes, each with `nodeId`,
  `relationType`, `direction` (`OUTGOING` when the anchor is the relation
  source, `INCOMING` when it is the target), plus the projected `node` body of
  the related node itself — the model reads real body content, never only
  opaque ids. Semantics:

  - Related nodes are direct 1-hop context only; the runtime never infers a
    second hop and never scans the whole workspace.
  - `DEPENDS_ON` / `DERIVED_FROM` / `SUPPORTS` preserve source → target
    direction; `RELATED_TO` / `CONFLICTS_WITH` are symmetric facts
    (canonicalized at write time).
  - A model may only reference a related node through its
    `node:<relatedId>` entry in `allowedSourceRefs`.
  - Related nodes are never part of `lineage`; the lineage stays the pure
    ancestor chain of the anchor.

  See `fixtures/agent-input-node-query-semantic-context-valid.json` for the
  golden shape, parsed identically by the Python contract test and the Java
  `AgentCrossLanguageContractTest`.

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

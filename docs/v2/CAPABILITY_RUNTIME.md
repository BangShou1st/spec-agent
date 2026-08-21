# Capability Runtime V2

## 1. Purpose

Capability Runtime separates **what the Agent wants to accomplish** from **how a tool/provider implements it**.

The Agent should not know whether a capability is implemented by Java, Python, a Skill, MCP, or a future provider.

```text
Planner
  |
  v
INVOKE_CAPABILITY
  |
  v
Capability Runtime
  |
  +-- Internal Tool
  +-- Skill Adapter
  +-- MCP Adapter
  +-- Future Provider
```

## 2. Capability Descriptor

Planner receives bounded descriptors rather than concrete SDK clients.

Logical shape:

```json
{
  "capabilityId": "resource.extract_text",
  "version": "1",
  "description": "Extract grounded text from a resource",
  "inputSchema": {},
  "outputSchema": {},
  "readOnly": true,
  "sideEffectClass": "NONE",
  "requiredPermissions": [],
  "supports": ["RESOURCE:FILE", "RESOURCE:URL"]
}
```

Descriptors are Runtime-owned and may be filtered by project/user permissions before model exposure.

## 3. Capability Registry

The registry owns:

- capability discovery;
- version and schema lookup;
- permission filtering;
- adapter routing;
- side-effect classification;
- availability/health metadata;
- idempotency/retry metadata.

Planner core must not branch on implementation class names.

## 4. Skill Boundary

“Skill” is treated as a reusable capability package/procedure, not as a special Agent personality.

A Skill may contain:

- instructions/procedure;
- schemas;
- references/resources;
- calls to one or more underlying tools.

Skill internals may evolve without changing the Agent Action Protocol as long as the descriptor contract remains compatible.

Do not create `FileAgent`, `ResearchAgent`, `PRDAgent` solely because a Skill exists.

## 5. MCP Boundary

MCP is an adapter/protocol boundary, not equivalent to one generic “tool”. An MCP server may expose different primitives such as tools, resources and prompts.

Capability Runtime should map them intentionally:

- **MCP tools** → invokable capabilities with side-effect metadata;
- **MCP resources** → retrievable Resource context/provenance;
- **MCP prompts** → optional reusable prompt/template assets, never automatic system-policy override.

The application host remains responsible for connection, credentials, permissions, context exposure and user approvals.

The Agent should not receive raw credentials or unrestricted MCP server state.

## 6. Resource Results Are Not Confirmed Truth

A capability can return an observation/resource result such as:

```json
{
  "status": "SUCCEEDED",
  "content": {},
  "sourceRefs": [],
  "provenance": {},
  "warnings": []
}
```

Runtime may feed this into the next Decision Cycle, but the result does not automatically become a confirmed requirement/decision.

Agent/Runtime must preserve whether the result is:

- external source evidence;
- generated summary;
- inference;
- user-confirmed fact.

## 7. Side Effects

Capabilities declare/derive a side-effect class, for example:

```text
NONE
LOCAL_DURABLE
EXTERNAL_REVERSIBLE
EXTERNAL_IRREVERSIBLE
```

Policy Engine, not the model, decides approval requirements.

Examples:

- reading a file: read-only;
- writing a local Graph candidate: Graph mutation policy;
- creating a GitHub issue: external side effect;
- sending a message: external side effect;
- deleting a remote resource: high-risk external side effect.

## 8. Retry / Idempotency

Capability Runtime owns retry safety metadata.

Never generically “retry the last tool call”.

A capability invocation should have a Runtime invocation ID/idempotency key where the provider supports it. Failures return typed results so the Agent can stop, ask the user, or try a different **approved logical approach** without duplicating side effects.

## 9. Context and Token Isolation

Do not expose the entire capability catalog or resource content in every prompt.

Runtime selects relevant capability descriptors based on:

- Node kind/subtype/capabilities;
- current operation;
- user/project permissions;
- explicit user request;
- known resource attachments.

Large tool results should be stored/referenced and summarized/retrieved in bounded chunks with provenance.

## 10. Future Python Support

A Python Brain may request `INVOKE_CAPABILITY`, but capability execution remains behind the host Runtime contract.

Python does not directly own secrets, database writes or arbitrary MCP connections merely because it performs planning.

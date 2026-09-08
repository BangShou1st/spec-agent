# Spec Agent Capability / Skills / MCP Implementation Plan

> Status: **Implementation-ready design baseline**  
> Revision: **v4**  
> Date: 2026-09-09  
> Baseline: `f86bdb62a88fdae6330e532734f135ec82c22b39` — Frontend UI/UX v1 closure complete  
> Scope: personal-use Spec Agent  
> Relationship: complements `docs/v2/CAPABILITY_RUNTIME.md` and `docs/v2/AGENT_RUNTIME_ARCHITECTURE.md`; it does **not** replace the current Project Agent Runtime contract.

---

# 0. Executive decision summary

The frontend prerequisite is complete. The next initiative is backend Capability / Skill / Connection / MCP evolution.

Current repository reality:

- The core Agent/Capability Runtime already exists and must be **evolved, not recreated**.
- Existing foundations include `INVOKE_CAPABILITY`, `CapabilityRuntime`, a static `CapabilityRegistry`, frozen `AgentInputSnapshot`, Validator/Policy/approval enforcement, capability observations, idempotent invocation persistence, and bounded continuation after capability observations.
- The new initiative itself is not implemented yet: there is no production Skill package/runtime, dynamic Skill discovery, Connection registry, Remote MCP runtime, or dynamic MCP primitive provider stack.
- Tool/Skill/MCP integration must reuse the existing `Observation -> fresh Decision` path. Do **not** build a second autonomous tool loop.

Implementation status should be described as:

> **Capability Runtime foundation exists; Skill / Connection / MCP product capability is not yet implemented.**

Repository owner override for this initiative:

> The previous main-only branch rule is superseded for this initiative. Development may proceed on branch `capability-skills-mcp`, created from `main` commit `f86bdb62a88fdae6330e532734f135ec82c22b39`.

This branch-policy override is intentionally narrow. It does not redefine unrelated repository workflow policy.

---

# 1. Frozen architecture choices

1. **Tool is an umbrella term.** Model-usable operations may be Host Function Tools, provider built-in tools, or MCP-backed tools.
2. **Host Function Tool** means a schema-bounded, platform-owned callable operation executed through Spec Agent Runtime, e.g. `resource.extract_text`, `skill.activate`, `skill.read_resource`, and later `skill.search` / `capability.search` when justified.
3. **Skill is procedural/context knowledge, not another Agent.** It is a reusable package of instructions and resources, normally represented by open Agent Skills `SKILL.md` semantics.
4. **MCP is a protocol boundary, not one generic Tool.** MCP tools, resources, and prompts retain distinct semantics.
5. Product UI calls external integrations **Connections**. MCP wording is reserved for advanced/custom connection flows.
6. **Capability is the provider-neutral runtime abstraction for executable operations.**
7. **Runtime remains authority.** Model proposes/chooses; Runtime filters, authorizes, validates, executes, persists, and records provenance.
8. **Installed != loaded.** Installed Skills, connected MCP servers, discovered tools, resources, and prompts are never dumped into every model call.
9. **Semantic routing, never lexical routing.** User wording, synonyms, provider names, and benchmark phrases must not become production `if/else`, regex, or keyword control flow.
10. **Prompt Engineering is behavior configuration, not hidden business logic.** Prompt changes are versioned and evaluated; they do not duplicate Runtime authorization or provider-specific routing.
11. **High cohesion / low coupling is a hard invariant.** Each module owns one primary reason to change and communicates through narrow typed contracts.
12. Phase-one user Skill scripts are not executed.
13. Phase-one custom MCP uses remote HTTP first; local `stdio` is deferred until a local host/bridge exists.
14. Global Assistant, multi-agent, handoffs, marketplace/team features, LangGraph migration, and unrelated large refactors are out of scope.

---

# 2. Terminology and boundaries

## 2.1 Tool

A generic model-usable operation. Tool does not describe implementation origin.

### Host Function Tool

Platform-owned, schema-bounded, permission-aware, observable, and Runtime-executed.

Examples:

```text
resource.extract_text
skill.activate
skill.read_resource
skill.search          # only when scale/eval justifies it
capability.search     # only when scale/eval justifies it
```

### Provider Built-in Tool

A provider-native operation such as provider web/file/code/computer tooling. Provider specifics stay behind adapters; planner-facing semantics stay provider-neutral.

### MCP Tool

A dynamically discovered external operation exposed by an MCP Connection. Spec Agent owns connection state, credential handling, visibility, authorization, normalization, provenance, and invocation policy.

## 2.2 Skill

A reusable procedural/context package. A Skill may contain:

```text
SKILL.md
references/
assets/
scripts/
dependency metadata
```

A normal Skill does **not** become a `CapabilityDescriptor` merely because it exists. Skill is not equivalent to Tool.

The default model is:

```text
Skill Registry
  -> lightweight catalog
  -> skill.activate(skillId)
  -> bounded SKILL.md instructions
  -> optional skill.read_resource(...)
  -> Skill may instruct use of ordinary capabilities
```

The existing `SkillAdapter` may remain only as an optional executable-skill bridge. It must not define Skill semantics.

## 2.3 Connection

Product representation of an external service/integration. A Connection may be MCP-backed. Connection lifecycle/auth state is not planner logic.

## 2.4 MCP primitives

```text
MCP tool     -> dynamic Capability
MCP resource -> retrievable external Resource/context with provenance
MCP prompt   -> discovered/stored prompt asset, never automatic system policy
```

Do not flatten all MCP primitives into Tools.

---

# 3. Preserve the current Runtime

Canonical existing flow remains:

```text
Graph / User / Capability Event
        |
        v
ContextSnapshot
        |
        v
AgentInputSnapshot
        |
        v
Decision
        |
        v
Validator / Eligibility / Policy
        |
        +------------------+
        |                  |
        v                  v
Graph Executor      Capability Runtime
                           |
                           v
                      Observation
                           |
                           v
                    fresh bounded Decision
```

Rules:

- Do not add a second Tool loop.
- Do not add a Skill-specific Agent loop.
- Do not bypass `DecisionExecutionService`, existing Policy, Validator, approval, idempotency, or continuation machinery.
- Capability results remain Observation/evidence, not confirmed Graph truth.
- Fresh continuation must build a fresh context snapshot from current Runtime truth.
- Retry/replay of the **same frozen snapshot** must replay the exact frozen projection.

---

# 4. Existing contract gaps Phase 0 must resolve

## 4.1 Brain-facing Capability Descriptor is too small

Runtime descriptor already contains conceptually:

```text
capabilityId
version
description
inputSchema
outputSchema
readOnly
sideEffectClass
requiredPermissions
supports
```

The current Agent/Brain-facing descriptor is reduced to:

```text
id
version
description
readOnly
sideEffectClass
```

That works for the current small capability set but does not scale to arbitrary dynamic MCP tools because the model needs bounded argument-shape information to construct valid calls.

Phase 0/1 must define a **bounded planner-facing descriptor projection** that gives the model enough input schema/argument semantics without exposing implementation classes, credentials, endpoints, or unbounded provider metadata.

Do not solve this with per-tool prompt templates or provider-name branches.

## 4.2 Duplicate planner-facing capability surfaces

Current contracts contain both:

```text
AgentInputSnapshot.availableCapabilities
AgentRequestEnvelope.capabilities
```

Current snapshot builder populates `availableCapabilities` while the top-level envelope field is currently sent empty.

Phase 0 must freeze one canonical planner-facing source. Prefer a single frozen snapshot-owned projection unless a strong protocol reason justifies otherwise.

Do not let both surfaces independently evolve.

## 4.3 CapabilityInvocation evolution

Do not blindly add every conceptual field from design notes. Audit what is already represented by run/context/policy persistence.

Only persist fields needed for replay, authorization evidence, provenance, provider execution, and stable diagnostics. Candidate concepts include invocation origin, context snapshot identity, approval state reference, and idempotency identity.

---

# 5. High cohesion / low coupling boundaries

Recommended ownership:

```text
agent / brain
  owns: semantic reasoning, one bounded next-action choice
  depends on: frozen context + generic Skill/Capability projections
  must not know: MCP SDK classes, Skill filesystem layout, OAuth clients, DB repositories

capability
  owns: generic descriptors, provider registry, visibility/query contracts,
        invocation/result contracts, capability discovery projection
  must not depend on: concrete Skill package internals or MCP SDK details

skill
  owns: package parsing, validation, install/registry, versions,
        activation, resource reads, Skill discovery
  must not own: Agent planning, MCP transport, external credentials,
                Graph mutation, application authorization policy

connection
  owns: saved Connection lifecycle, auth-state references, enable/disable,
        health/status metadata
  must not own: planner semantics or MCP protocol implementation

mcp
  owns: transport, discovery, protocol-version adaptation,
        primitive normalization, MCP-backed provider implementations
  must not own: Agent prompt, Graph truth, application authorization policy

policy / validator
  owns: deterministic permission, schema, side-effect, trust-boundary enforcement
  must not depend on: user wording or provider marketing names

frontend
  owns: management UX and explicit user selections
  must not duplicate: backend authorization, Skill package validation,
                      MCP trust policy
```

Dependency rule:

```text
Agent --------------------> capability.api / skill discovery projection

Capability Registry <----- CapabilityProvider SPI
                           ^
                           |
                    MCP tool provider

SkillService ------> Skill domain/package/runtime
ConnectionService -> Connection domain -> MCP runtime
```

### Change-isolation test

The design fails low-coupling review if any ordinary change below requires editing planner core:

- add a new MCP server;
- add a new MCP tool;
- install a new Skill;
- change GitHub to another provider implementation;
- add a Skill reference file type;
- change OAuth implementation;
- add another internal read-only capability;
- change the Skill retrieval algorithm.

Those changes must be isolated to data/providers/owning modules.

---

# 6. Capability Registry evolution

The current `CapabilityRegistry(List<CapabilityAdapter>)` static map is valid for the existing foundation but insufficient for runtime-discovered MCP tools.

Evolve toward:

```text
CapabilityRegistry
  -> StaticCapabilityProvider / existing adapters
  -> McpToolCapabilityProvider
  -> future providers
```

Conceptual SPI:

```java
interface CapabilityProvider {
    Collection<CapabilityDescriptor> descriptorsFor(CapabilityQueryContext context);
    boolean canHandle(String capabilityId);
    CapabilityResult invoke(CapabilityInvocation invocation);
}
```

Exact Java signatures may differ. Architectural requirements are:

- provider-neutral planner-facing registry;
- static + dynamic providers coexist;
- duplicate capability IDs fail closed;
- provider internals do not leak into Brain;
- permission/availability/context filtering occurs before model exposure;
- existing `resource.extract_text` remains planner-compatible.

One MCP server is **not** one `CapabilityAdapter`. One server may expose many tools.

---

# 7. Skill Runtime

## 7.1 Skill format

Support open Agent Skills `SKILL.md` semantics rather than inventing an incompatible format.

Mandatory metadata at minimum:

```text
name
description
```

Spec Agent may store additional Runtime metadata separately:

```text
skillId
source kind
resolved source identity
content/version hash
enabled state
trust/install status
created/updated timestamps
```

Runtime-owned metadata must not require modifying the Skill package format.

## 7.2 Supported sources

Initial sources:

```text
BUILTIN
UPLOAD_ZIP
GIT_HTTPS
```

Git installs must resolve to immutable commit/content identity. Duplicate display names do not replace existing identities silently.

## 7.3 Installation pipeline

```text
source
  -> stage
  -> validate without executing
  -> inspect manifest/files
  -> security checks
  -> review/confirm
  -> install immutable version
  -> optionally enable
```

Validation must not execute scripts.

## 7.4 Skill resources

`skill.read_resource(skillId, path)`:

- requires an already visible/activated Skill as defined by policy;
- enforces path containment;
- rejects traversal and symlink escape;
- returns bounded text content with Skill/version provenance;
- phase one does not blindly inject binary assets.

---

# 8. Skill discovery during Agent execution

This is a first-class architecture concern.

## 8.1 Core invariant

> **Skill discovery runs for every fresh Decision context, not once per conversation.**

Example:

```text
Decision #1
  -> visible Skills A/B/C
  -> invoke repository/MCP capability
  -> Observation reveals a database migration

fresh Decision #2
  -> discovery runs again against the new context
  -> visible Skills A/D/F
  -> model may now activate migration-safety Skill D
```

Do not create a new loop to achieve this. Reuse the existing fresh continuation path.

Retry/replay distinction:

- same frozen snapshot -> exact same Skill catalog/projection;
- fresh continuation snapshot -> discovery may legitimately produce a different catalog.

## 8.2 Progressive discovery model

```text
Level 0 — Eligibility
  deterministic filtering

Level 1 — Automatic Discovery
  bounded lightweight Skill catalog

Level 2 — Search Fallback
  skill.search(query) only when catalog was truncated

Level 3 — Activation
  skill.activate(skillId) -> bounded full SKILL.md

Level 4 — Resource
  skill.read_resource(skillId, path) -> specific reference content
```

### Installed != loaded

```text
Installed Skill != full SKILL.md in every prompt
Connected MCP != every MCP tool in every prompt
Available Resource != full resource content in every prompt
```

## 8.3 Skill discovery components

```text
AgentInputSnapshotBuilder
        |
        v
SkillDiscoveryService          # Agent-facing facade
        |
        +--> SkillVisibilityService
        |      deterministic eligibility only
        |
        +--> SkillCandidateRetriever
        |      semantic candidate reduction/ranking only
        |
        +--> SkillCatalogProjector
               bounded model-facing projection
```

### SkillDiscoveryService

Single facade consumed by context construction.

Conceptual shape:

```java
SkillCatalogProjection discover(SkillDiscoveryContext context);
```

Agent code must not know which retrieval implementation is used.

### SkillVisibilityService

Owns deterministic eligibility:

```text
installed
 -> enabled
 -> compatible
 -> trusted enough to expose
 -> relevant structured scope
 -> available
```

It does **not** rank natural-language relevance.

### SkillCandidateRetriever

Owns candidate relevance only.

Conceptual shape:

```java
interface SkillCandidateRetriever {
    List<SkillCandidate> retrieve(
        SkillDiscoveryQuery query,
        List<SkillCatalogEntry> eligible,
        int limit);
}
```

It does not:

- grant permission;
- activate Skills;
- mutate Graph;
- invoke MCP;
- decide the final semantic action.

The model remains the final semantic Skill selector.

### SkillCatalogProjector

Owns model-facing bounds:

```text
Top-K / max entries
metadata byte/token budget
stable order
truncated flag
catalog fingerprint
```

Model-facing entry should remain small:

```text
skillId
name
description
optional bounded compatibility hint
```

Do not expose embedding scores, filesystem paths, secrets, or implementation details.

## 8.4 Small-scale first implementation

Do not introduce retrieval complexity before scale requires it.

For small eligible catalogs:

```text
Visibility
 -> PassThroughSkillCandidateRetriever
 -> bounded metadata catalog
 -> Model
```

No vector DB, no extra LLM router, no mandatory embedding service.

## 8.5 Scale-up strategy

When real model eval shows catalog-size/token/selection degradation, replace the Retriever behind the same interface.

Long-term allowed implementation:

```text
generic lexical retrieval (FTS/BM25)
        +
semantic retrieval (embedding)
        -> candidate union/ranking
        -> Top-K
```

Do not freeze arbitrary score weights in architecture docs. Tune against held-out eval.

Generic lexical retrieval is allowed. **Lexical routing is not.**

Allowed:

```text
query vs name/description/support metadata using generic IR algorithms
```

Forbidden:

```text
if userText contains "github" -> expose github tools
if prompt contains "install" -> activate install Skill
mentionsGithub = true
containsInstallWord = true
```

## 8.6 Discovery context

Do not search only the latest user sentence. Fresh continuation may have no new user message.

SkillDiscoveryContext should be derived deterministically from current frozen model context, e.g. bounded generic fields such as:

```text
current goal / requested operation
relevant trusted context summaries already in snapshot
resource kinds
recent bounded capability observations
structured compatibility facts
```

Do not add a mandatory extra LLM summarizer/classifier solely for Skill retrieval.

## 8.7 `skill.search` fallback

Reserve/add `skill.search` only when automatic catalog truncation becomes real.

Visibility rule:

```text
catalog not truncated -> skill.search need not be model-visible
catalog truncated     -> skill.search may be exposed
```

Input stays implementation-neutral:

```json
{ "query": "review backward compatibility of database migrations" }
```

Output returns **metadata candidates only**:

```text
skillId
name
description
compatibility hint if useful
```

`skill.search` must never auto-activate the top result.

Flow remains:

```text
skill.search
 -> candidate metadata
 -> model semantic judgment
 -> skill.activate(skillId)
 -> full bounded SKILL.md
```

## 8.8 Activation lifetime

Recommended behavior:

```text
Installed / Enabled  = durable
Skill version        = immutable identity
Activation           = bounded to current Agent run / continuation chain
Full Skill body      = not permanently injected into future user turns
```

A continuation in the same run may reuse the immutable activated Skill version without repeatedly re-activating it.

A later unrelated user turn returns to lightweight discovery unless product behavior explicitly requires otherwise.

---

# 9. Capability discovery at scale

Capability/tool discovery follows the same **principle** as Skill discovery but remains a separate domain abstraction.

```text
CapabilityProviders
  -> CapabilityVisibilityService
  -> CapabilityCandidateRetriever
  -> bounded capability catalog
  -> model
  -> INVOKE_CAPABILITY
```

Do not create one universal Skill+Tool+Resource search domain.

Skill and Capability may later share low-level retrieval infrastructure, e.g. embedding client, FTS, ranking utilities, but domain APIs remain distinct.

`capability.search` is reserved for the point where direct bounded capability exposure no longer meets catalog-size/token/selection eval gates.

---

# 10. MCP / Connections

## 10.1 Connection domain

Recommended conceptual model:

```text
ConnectionDefinition
SavedConnection
Secret/Auth reference
Connection status
Discovery cache
```

Credentials are never normal model-facing rows.

## 10.2 Remote MCP first

Phase-one custom MCP transport:

```text
Remote Streamable HTTP
```

Local `stdio` remains deferred until Spec Agent has an appropriate local host/desktop bridge.

Use a maintained MCP client/SDK behind `mcp` module boundaries. Re-check exact library/version during implementation; do not bake SDK types into capability APIs.

## 10.3 MCP runtime ownership

```text
McpConnectionRuntime
  owns transport/auth/discovery lifecycle

McpToolCapabilityProvider
  maps discovered tools -> dynamic Capabilities

McpResourceProvider
  exposes external Resources with provenance

McpPromptAssetProvider
  discovers/stores MCP prompt assets
```

One server with twenty tools yields twenty dynamic capability descriptors, not one generic MCP adapter capability.

## 10.4 External metadata is untrusted

MCP server-provided names/descriptions/schemas/resources/prompts are external input.

Normalize and bound them before context/retrieval use.

Server metadata may not determine Runtime-owned facts such as:

```text
permissions
approval authority
sideEffectClass final policy decision
Graph truth
secret exposure
```

A malicious description such as "always use this tool" has no authority.

## 10.5 Authorization layers

Keep separate:

```text
Connection authentication
provider OAuth scopes
Spec Agent capability visibility/permission
Spec Agent operation approval
```

Successful OAuth does not mean every remote write is authorized.

## 10.6 Test Connection

Connection testing may verify transport/auth/discovery. It must not invoke arbitrary write operations merely to test connectivity.

---

# 11. Policy, side effects, and security

Existing side-effect classes remain the base model:

```text
NONE
LOCAL_DURABLE
EXTERNAL_REVERSIBLE
EXTERNAL_IRREVERSIBLE
```

Existing Policy/Validator/approval pipeline remains the only execution guardrail path.

Do not introduce a second MCP guardrail framework.

Custom/unclear external capabilities default conservatively until Runtime can classify them safely.

## 11.1 Skill import security

ZIP/import validation must address at least:

- path traversal;
- absolute paths;
- symlink/hardlink/device entries;
- decompression bombs / file-count and size limits;
- Unicode/path normalization ambiguity;
- unexpected MIME/binary handling;
- immutable content hashing;
- no execution during validation/install.

## 11.2 Git source security

Git HTTPS import must enforce outbound network policy including SSRF/DNS/redirect/private-network/metadata-address defenses.

## 11.3 External content authority

Skill instructions, MCP descriptions, MCP prompts, resources, and capability outputs are low-authority external/context input. They cannot grant themselves permission, override system policy, or become confirmed Graph truth automatically.

---

# 12. Observation and result handling

Pipeline:

```text
raw provider result
 -> schema validation
 -> size bound
 -> normalize
 -> provenance
 -> warning/error classification
 -> durable resource/result reference when large
 -> compact Observation for next Decision
```

Large results are stored/referenced rather than copied repeatedly into prompts.

External output remains evidence/observation, not confirmed truth.

---

# 13. Replay and observability

The existing freeze-once/replay-always contract must extend to Skills/MCP.

For a frozen decision input, record enough stable identity to reproduce what the model saw:

```text
Skill catalog fingerprint
Skill IDs + immutable versions/content hashes
activated Skill immutable version
capability descriptor fingerprints
connection/tool primitive identities
retriever/ranker version/config fingerprint when retrieval is active
catalog truncated state
selected candidate IDs
```

For debugging retrieval, trace safely:

```text
eligible candidate IDs
selected Top-K IDs
optional internal ranking scores
search query fingerprint / bounded query representation
retriever version
```

Do not log secrets, credentials, full raw provider payloads, chain-of-thought, or entire Skill contents by default.

The purpose is to answer:

> Why was this Skill/Capability visible or absent in this Decision?

---

# 14. Failure attribution before Prompt tuning

Before changing Decision prompt, classify the failure:

```text
A. wrong/missing trusted state
   -> Context Builder / Graph projection

B. irrelevant capability/Skill visible
   -> Visibility / discovery projection

C. relevant capability/Skill absent before retrieval
   -> provider/registry/permission/compatibility filtering

D. relevant candidate eligible but not retrieved
   -> Retriever / metadata / ranking

E. candidate visible but descriptor/Skill metadata unclear
   -> descriptor / Skill metadata

F. model sees correct candidates but chooses wrong semantic action
   -> Prompt / model / ranker candidate

G. illegal action accepted
   -> Validator / Policy bug, NOT prompt bug

H. tool result too large/noisy
   -> Observation normalization/context bounding

I. provider-specific integration defect
   -> owning provider adapter/runtime
```

Prompt tuning is mainly justified after earlier layers are verified.

No prompt change is accepted merely because it fixes one exact sentence.

---

# 15. Evaluation model

## 15.1 Deterministic tests

Required classes include:

- CapabilityProvider registration/add/remove;
- duplicate ID failure;
- permission/availability filtering;
- planner-facing descriptor schema projection;
- Skill parser and package validation;
- Skill activation/resource containment;
- Skill catalog bounds/fingerprint/replay;
- Connection lifecycle/discovery cache;
- MCP tool/resource/prompt normalization;
- MCP result bounding/provenance;
- approval/side-effect enforcement;
- no credentials in Brain payloads.

## 15.2 Real-model Skill discovery metrics

Measure separately:

```text
Visibility Recall
Retrieval Recall@K
Search Recall@K
Activation precision
Activation recall
Unnecessary activation rate
Redundant activation rate
Catalog token/byte cost
Search invocation rate
Post-observation discovery success
```

Important scenario:

```text
initial user task does not strongly imply a database Skill
 -> Agent invokes repository/MCP capability
 -> Observation reveals destructive database migration
 -> fresh Decision re-runs Skill discovery
 -> migration-safety Skill becomes visible
 -> model activates it
```

This scenario proves Skill discovery works **during Agent execution**, not only at session start.

## 15.3 Anti-overfitting eval

Every behavior change needs:

- paraphrases;
- negative controls;
- boundary cases;
- protected regressions;
- declared metric/gate before prompt tuning.

Do not optimize to provider/product words such as GitHub, install, PDF, database, etc. unless they are true structured protocol facts rather than inferred wording.

---

# 16. Configuration boundaries

Define semantic knobs, but choose exact numeric defaults from tests/realistic usage rather than architecture guesses.

Candidate configuration:

```text
skill.catalog.max_visible
skill.catalog.max_metadata_bytes
skill.search.max_results
skill.activation.max_active
skill.activation.max_instruction_bytes
skill.resource.max_inline_bytes

capability.catalog.max_visible
capability.catalog.max_descriptor_bytes
capability.search.max_results

mcp.discovery.timeout
mcp.call.timeout
mcp.result.max_inline_bytes
mcp.connection.max_redirects
```

Model output cannot change these limits.

---

# 17. Frontend management surface

After current Graph-first frontend closure, add a top-level **Capabilities** management surface with two primary sections:

```text
Skills
Connections
```

Host Function Tools remain hidden from ordinary users.

## Skills

Support:

- list/detail;
- import staging;
- enable/disable;
- delete;
- inspect files/resources safely;
- source/version status;
- validation errors.

## Connections

Support:

- connection status;
- connect/disconnect;
- auth status without showing secrets;
- discovered primitive counts;
- permission/risk summary;
- Custom MCP name/server/auth configuration;
- test connection before Agent visibility.

Frontend must not reimplement backend policy.

---

# 18. Suggested backend module structure

Exact package names may follow repository conventions. Responsibility boundaries matter more than names.

```text
skill/
  domain/
  registry/
  import/
  runtime/
  discovery/
    SkillDiscoveryService
    SkillVisibilityService
    SkillCandidateRetriever
    SkillCatalogProjector

capability/
  api/
  spi/
  registry/
  discovery/
    CapabilityVisibilityService
    CapabilityCandidateRetriever
  runtime/

connection/
  domain/
  service/
  persistence/

mcp/
  transport/
  discovery/
  normalization/
  provider/
```

Do **not** prematurely create a `UniversalRetriever` abstraction combining Skills, Tools, Resources, and prompt assets.

If later implementations genuinely duplicate low-level components such as embedding clients, FTS indexes, or ranking utilities, extract those into a lower-level retrieval infrastructure module after the duplication is proven.

---

# 19. Implementation phases

## Phase 0 — repository reality, contracts, schema freeze

Goal: prepare implementation without changing model behavior.

Deliverables:

- this document merged into the working branch;
- repository reality audit against current `main` baseline;
- canonical planner-facing capability catalog location frozen (`AgentInputSnapshot` vs legacy envelope field);
- planner-facing Capability Descriptor schema/protocol decision;
- `CapabilityProvider` contract;
- Skill package/registry/discovery contracts;
- Connection/MCP contracts;
- invocation/provenance persistence decision based on actual existing schema;
- disposition of legacy `SkillAdapter` / `McpAdapter` placeholders;
- security limits/configuration keys;
- DB migration design;
- deterministic fixtures;
- branch-policy conflict documented/resolved for the initiative.

Do not change Decision prompt in Phase 0.

### Phase 0 stop gate

Do not enter Phase 1 until:

- no duplicate authoritative catalog contract remains unresolved;
- dynamic provider ownership is clear;
- Brain has a versionable path to receive bounded argument schema semantics;
- Skill discovery owner/interfaces are frozen;
- existing continuation/policy paths are explicitly reused rather than duplicated;
- migrations/security boundaries are reviewable;
- current backend tests remain baseline-known.

## Phase 1 — dynamic Capability foundation + Host Function Tools

Implement:

- evolve static registry to static + dynamic providers;
- preserve existing internal capability behavior;
- extract/evolve current visibility logic into `CapabilityVisibilityService` rather than duplicating it;
- establish one source of truth for Host Function Tool argument schema/descriptors;
- implement bounded brain-facing descriptor projection;
- keep existing Validator/Policy/approval path as the only guardrail;
- add `skill.activate` and `skill.read_resource` contracts with fake/test implementations first;
- add Skill discovery interfaces with simple pass-through implementation if catalog is small;
- preserve `resource.extract_text` planner compatibility.

Acceptance:

- runtime provider can add/remove descriptors dynamically;
- permission filtering still hides unauthorized descriptors;
- existing capabilities behave unchanged from planner perspective;
- no provider SDK leaks into Brain;
- no lexical routing;
- no new autonomous loop;
- no Decision prompt behavior change required yet.

## Phase 2 — Skill Runtime + Skills UI

Implement:

- Agent Skills parser;
- safe ZIP/Git staging import;
- immutable package store/versioning;
- Skill Registry;
- enable/disable/delete;
- activation/resource read implementations;
- Skill discovery projection into deterministic test contexts;
- Skills management UI.

Acceptance:

- valid ZIP/Git Skill installs safely;
- invalid/untrusted structures fail closed;
- scripts never run;
- enabled state controls discovery eligibility;
- full Skill content is not loaded until activation;
- resources are read only on demand with containment/provenance.

## Phase 3 — Remote MCP Runtime + Connections UI

Implement:

- Connection Registry;
- secret storage integration;
- Remote MCP client adapter;
- discovery/cache;
- dynamic MCP tool CapabilityProvider;
- MCP ResourceProvider;
- MCP prompt discovery/storage only;
- Custom MCP UI;
- connect/test/disconnect lifecycle.

Acceptance:

- one MCP server's multiple tools appear as dynamic capabilities;
- disabled/disconnected Connection disappears from planner-visible candidates;
- read-only invocation returns provenance-preserving Observation;
- writes use existing confirmation/policy path;
- credentials never reach Python Brain;
- resources/prompts are not flattened into tools.

## Phase 4 — Agent integration + Context Engineering + eval

Only after deterministic Skill/MCP management works:

- add Skill catalog projection to fresh Decision snapshots;
- add filtered/ranked Capability descriptors;
- add generic Skill activation guidance to Decision prompt;
- add generic capability-use guidance;
- reuse the existing bounded continuation after capability observations;
- enable `skill.search` only when catalog truncation + eval justify it;
- enable `capability.search` only when capability catalog scale/eval justify it;
- add real-model discovery/selection eval;
- tune descriptor/context/prompt only against measured failures.

Rules:

- no keyword/synonym/regex routing over user language;
- no domain-specific `if GitHub` planner/runtime branch;
- no benchmark-case-specific prompt clauses;
- deterministic control flow only from typed runtime facts;
- no mandatory extra model classifier unless eval proves value;
- Skill/capability selection stays generic and descriptor-driven;
- prompt/descriptor changes are versioned and tested with paraphrase + negative controls.

## Phase 5 — deferred

Global Assistant and broader orchestration remain deferred. This initiative must not require them.

---

# 20. Acceptance checklist

## Architecture

- [ ] Project Agent still chooses generic actions, not provider-specific branches.
- [ ] Capability Runtime remains execution authority.
- [ ] Existing Host Function Tools still work.
- [ ] Dynamic providers coexist with static capabilities.
- [ ] Skill is procedural/context knowledge by default, not forcibly an executable Tool.
- [ ] MCP tools/resources/prompts preserve distinct semantics.
- [ ] Existing Policy/Validator/approval pipeline is reused.
- [ ] Existing fresh continuation is reused; no duplicate tool loop exists.
- [ ] Agent/Brain has no dependency on MCP SDK, Skill filesystem, credentials, or DB repositories.

## Skill discovery

- [ ] Skill discovery runs on every fresh Decision context.
- [ ] Same frozen snapshot replays the exact frozen Skill catalog.
- [ ] Small catalogs work without embedding/vector infrastructure.
- [ ] Retriever is replaceable behind a narrow interface.
- [ ] Visibility and semantic retrieval are separate owners.
- [ ] `skill.search` is only a bounded fallback when catalog truncation justifies it.
- [ ] `skill.search` returns metadata only.
- [ ] Model remains final semantic selector before `skill.activate`.
- [ ] Full Skill body loads only after activation.
- [ ] Skill resources load only on demand.
- [ ] No natural-language keyword routing exists.

## MCP / Connections

- [ ] One server may expose many dynamic capabilities.
- [ ] disabled/unavailable Connections are invisible to planner candidates.
- [ ] credentials never enter model-visible context.
- [ ] external metadata is treated as untrusted.
- [ ] write operations use existing Runtime approval policy.
- [ ] resources preserve provenance.
- [ ] prompts cannot override system policy.
- [ ] local stdio remains deferred unless a suitable local host exists.

## Context / replay / evaluation

- [ ] installed != loaded invariant is enforced.
- [ ] capability/Skill projections are bounded.
- [ ] large outputs are bounded/referenced.
- [ ] descriptor/Skill versions or fingerprints are captured for replay.
- [ ] retrieval/discovery failures can be attributed to owning layers.
- [ ] paraphrase + negative-control anti-overfitting eval exists.
- [ ] real-model Tool/Skill selection eval exists before prompt optimization is accepted.

---

# 21. Explicit non-adoptions

Do not add the following merely because other Agent frameworks support them:

- Multi-Agent / Agents-as-Tools / Handoffs;
- LangGraph runtime migration;
- provider-native Function Calling as Spec Agent's internal contract;
- provider-owned session/memory as Project truth;
- marketplace/team/enterprise distribution;
- arbitrary Skill script execution;
- every Skill/MCP definition loaded into every model call;
- one Tool per REST/database endpoint;
- one MCP server == one Tool/Capability adapter;
- extra LLM router/classifier before evaluation proves need;
- universal Skill/Tool/Resource retrieval abstraction before real shared requirements exist.

---

# 22. Final invariant

The scaling model is intentionally simple:

```text
large Skill / Capability world
        |
        v
Runtime deterministic eligibility
        |
        v
small bounded candidate space
        |
        v
Model semantic judgment
        |
        v
Runtime validation / authorization / execution
```

The implementation must be able to grow from a handful of Skills and MCP tools to much larger catalogs without rewriting Agent core, while preserving replayability, provenance, security, bounded context, and existing Project Agent Runtime semantics.

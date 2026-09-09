# Capability / Skills / MCP Backend Completion Report

> Status: **Backend complete, deterministic gate green**
> Date: 2026-09-09
> Branch: `capability-skills-mcp`
> Plan: `docs/v2/CAPABILITY_SKILLS_MCP_IMPLEMENTATION_PLAN.md` (v4)
> Freeze: `docs/v2/CAPABILITY_SKILLS_MCP_PHASE0_FREEZE.md`

## Final verdict

**PASS** — deterministic backend gate green on both sides; no live-model
credential in this environment, so real-model selection eval is recorded as
**ENVIRONMENT BLOCKED** (deterministic eval harness in its place, all green).

## Branch / HEAD

- `capability-skills-mcp` off `main` (`f86bdb6`).
- Phase 0/1: `5360153` (contract freeze + dynamic Capability foundation).
- Phase 2: `eb5c03a` (Skill Runtime, secure import, discovery, activation).
- Phase 3: `de01515` (Connection + Remote MCP runtime).
- Phase 4: this change (Agent integration + eval, uncommitted at report time).

## Phase 0 — contracts frozen (done, `5360153`)

- Canonical planner catalog: `AgentInputSnapshot.availableCapabilities`;
  `AgentRequestEnvelope.capabilities` stays empty, never a second catalog.
- Brain descriptor extended (bounded `inputSchema` + `supports`), Java/Python
  synced, legacy frozen payloads still parse.
- `CapabilityProvider` SPI; registry = static adapters + dynamic providers;
  duplicate ids fail closed.
- `CapabilityVisibilityService` owns deterministic filtering; Builder only
  projects host → wire.

## Phase 1 — dynamic Capability foundation (done, `5360153`)

- Static + dynamic providers coexist; permission/supports/bounds filtering
  before model exposure; no SDK leaks; no lexical routing; no second loop;
  no prompt change.

## Phase 2 — Skill Runtime (done, `eb5c03a`)

- V26 migration; Skill domain/persistence, immutable versions, package files,
  staged imports, activations.
- SKILL.md parser; ZIP + Git HTTPS import; SafeZipExtractor;
  shared OutboundNetworkPolicy (SSRF/private/link-local/metadata defense).
- Skill scripts never execute.
- Discovery chain (Visibility → PassThrough Retriever → Projector);
  `skill.activate` / `skill.read_resource` Host Function Tools gated by
  `SkillHostToolVisibility` (installed != loaded regression fixed).

## Phase 3 — Connection + Remote MCP (done, `de01515`)

- V27 migration (`connections`, `connection_credentials`,
  `mcp_discovery_cache`); credentialRef-only rows.
- `SecretStore` + `LocalAesSecretStore` (AES-GCM, env master key); plaintext
  never enters Brain/snapshot/trace/descriptor/result/errors.
- MCP SDK 0.18.4 (Jackson 2 line; 1.x is Jackson 3 and incompatible).
- `McpClientFactory` sole SDK boundary: base+endpoint URL split, auth-header
  injection, isError-aware results, byte-budget bounding; shared outbound
  policy with test-only localhost opt-in (prod default blocks loopback).
- `McpToolCapabilityProvider` (`mcp.<connId>.<tool>`, conservative side
  effects), `McpResourceProvider` (provenance evidence),
  `McpPromptAssetProvider` (discover/inspect only).
- Root causes fixed with evidence: SDK endpoint check (`/fake-mcp/mcp`),
  client default-endpoint URL corruption, stale create() credentialRef,
  FAILED-status rollback, isError-as-success.
- Protocol-level integration kept: real SDK client ↔ SDK-backed in-process
  fake server (no hand-rolled JSON-RPC, no mock-only gate).
- Architecture tests lock low coupling (incl. MCP SDK containment).

## Phase 4 — Agent integration (this change)

- `AgentInputSnapshot.availableSkills`: bounded `SkillCatalogView`
  (`skills[]` = skillId/name/description/compatibilityHint, `truncated`,
  `fingerprint`). No SKILL.md bodies, paths, scores, or DB internals.
- Discovery runs per fresh Decision at first-freeze time; same frozen
  snapshot replays byte-identical catalog (freeze-once/replay-always).
- `skill.search` Host Function Tool added (metadata-only, never
  auto-activates); model-visible only when the catalog truncated.
- `skill.read_resource` stays behind the enabled-Skill gate with version-level
  enforcement at invocation; `capability.search` deliberately not built
  (catalog scale does not justify it); no UniversalRetriever.
- Decision prompt: one generic behavior clause (12a), no business keywords,
  no per-domain branches.
- Key acceptance test: `SkillDiscoveryEvolutionIntegrationTest` proves
  discovery follows evolving context (broad task → tool observation → fresh
  continuation surfaces the migration-safety Skill; replay identical).
- Retrieval eval harness pins attribution layers
  (Visibility / Retriever / Descriptor-Prompt-Model / Validator-Policy /
  MCP / Normalization) with recall/cost gates for the small-catalog version.

## Contract decisions

- `availableCapabilities` stays the single capability catalog;
  `availableSkills` added as the parallel Skill catalog (both bounded,
  fingerprinted, replay-safe).
- Descriptor `inputSchema`/`supports` remain optional-with-defaults;
  `availableSkills` likewise defaults to empty (legacy fixtures parse).
- Java record + Python Pydantic (`extra="forbid"`) extended in lockstep;
  unknown skill fields rejected on both sides (tests both sides).
- Envelope protocol versions unchanged (`agent-input.v2/v3` compatible
  extension, no silent reinterpretation).

## Capability architecture

```text
large Skill / Capability world
        |
        v
Runtime deterministic eligibility (Visibility)
        |
        v
small bounded candidate space (Retriever pass-through + Projector/bounds)
        |
        v
Model semantic judgment (activate / search-then-activate / invoke)
        |
        v
Runtime validation / authorization / execution (Validator/Policy/Approval)
```

- Domains stay separate: `SkillCandidateRetriever` vs future
  `CapabilityCandidateRetriever` vs resource retrieval; shared infra only
  after proven duplication.

## Skill Runtime / Discovery

- Install → enable → per-Decision discover → activate → read_resource.
- Activation bounded to run/continuation, version-pinned, provenance-recorded.
- `skill.search` returns metadata only; model decides; no Top-1 auto-activate.

## Connection / SecretStore / MCP

- See Phase 3 section; unchanged by Phase 4 except through the standard
  provider → visibility → snapshot path (no planner-core edits for new
  servers/tools).

## Agent integration

- Snapshot Builder is the only projection allowed to read the new stores;
  agent/brain never touch MCP SDK, Skill filesystem, credentials, or repos
  (arch tests enforce).
- Fresh continuation rebuilds context from runtime truth; frozen replay never
  re-reads live registries.

## Security

- ZIP traversal/absolute/symlink/device/bomb bounds; Git no-hooks/
  no-submodules/no-LFS-auto; SSRF policy shared; localhost opt-in test-only.
- Secrets: encrypted at rest, masked suffix in API, absent from all
  model-visible surfaces (integration-asserted).
- External metadata (tool/skill/resource/prompt) untrusted, bounded,
  normalized; no authority over permissions/policy/truth.
- Unknown MCP side effects default to EXTERNAL_REVERSIBLE (never NONE).

## Migrations

- V26 (Skill runtime), V27 (Connection runtime); Flyway validated on boot in
  every integration run; existing `capability_invocations` reused for MCP
  idempotency (no duplicate table).

## Prompt changes

- Single generic clause (agent-brain `decision.py` rule 12a). No
  GitHub/PDF/database/install routing; no benchmark-specific clauses.

## Tests exact results

| Suite | Command | Result |
|---|---|---|
| Backend full | `:test --offline --rerun-tasks` | **946 passed, 0 failed, 2 skipped** |
| Brain full | `.venv/Scripts/python -m pytest tests/ -q` | **94 passed** |
| Contract cross-lang | both suites above | green (fixtures + skill-catalog round-trip) |
| Phase 3 integration | SDK fake server (9) + controller API (2) | green |
| Phase 4 acceptance | evolution (2) + attribution (5) + eval (4) + search tool (3) | green |
| Architecture | boundary suites incl. Phase 3/4 rules | green |

Skips: 2 pre-existing environment-conditional skips (live-brain dependent).
Live-model selection eval: **ENVIRONMENT BLOCKED** (no model credential);
deterministic discovery/selection eval covers the backend gate.

## Commits

- `5360153` Phase 0/1, `eb5c03a` Phase 2, `de01515` Phase 3,
  Phase 4 change pending (this report + code).

## Deviations from plan

- `skill.read_resource` model-visibility stays at the enabled-Skill gate
  (version-level enforcement at invocation) rather than a run-scoped
  activation gate: the snapshot builder has no run binding, and adding one
  would couple projection to run state against the frozen-input design.
- `capability.search` deferred (catalog fits in bounds; deterministic filter
  suffices); interface seam reserved per plan.
- No vector DB / LLM router / cross-encoder / UniversalRetriever (per plan).

## Environment blockers

- Docker Desktop daemon was down at session start (started manually for
  Postgres 5434); no Postgres binary on PATH (winget available, unused).
- Shell default is Git Bash (path/`&&` quirks); used absolute paths +
  `pwsh` for Windows-native probes per repo convention.
- Agent-brain 8100 container not running: cross-language live tests skip by
  design (CI backend job has no brain either).
- No live model credential: real-model eval ENVIRONMENT BLOCKED.

## Remaining backend work

- None for the backend Definition of Done. Optional follow-ups (not gates):
  lexical/semantic retriever behind the same interface when eval shows
  recall degradation; `capability.search` when capability catalog scale
  justifies it; local stdio MCP when a host bridge exists.

## Frontend-ready APIs

- `GET /api/v1/connections`, `GET /api/v1/connections/{connectionId}`
  (masked suffix, no plaintext), `POST .../test|connect|refresh|enable|
  disable`, `DELETE ...`, `GET .../resources|prompts`,
  `GET .../resources/read?uri=`.
- Skills surface from Phase 2 (list/detail/import/enable/disable/delete).
- `availableSkills` + `availableCapabilities` in every Decision snapshot;
  `skill.search` appears only when truncated.

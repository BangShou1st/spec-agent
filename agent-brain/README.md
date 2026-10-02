# Spec Agent V2 Python decision engine (agent-brain)

Stage A bootstrap of the V2 Agent Brain (`docs/v2/PYTHON_AGENT_RUNTIME_BOUNDARY.md`).
The brain owns prompts and model-call orchestration inside one decision cycle;
it never touches the production database and never holds provider API keys —
model inference goes through the Spring internal inference broker.

## HTTP surface

The local service configuration extension also exposes authenticated
`POST /internal/v1/retrieval/ollama-probe`: isolated real document/query embeddings,
native dimension and model digest detection. Configured `retrieval.v2` workloads use
LangChain's standard `Embeddings` interface and host-approved profiles. Remote provider
keys stay in Java's encrypted revisions and Java embedding broker; Python receives only
bound vectors and safe semantics. `retrieval.v1` and project Brain defaults are retained.
See [local configuration guide](../docs/v2/LOCAL_DEPLOYMENT_SERVICE_CONFIGURATION_GUIDE.md).

```text
GET  /health
POST /v1/state-updates   # answer/evidence -> grounded claims
POST /v1/decisions       # reflection + planning -> one action proposal
```

All endpoints speak the frozen `contracts/v2` wire contract:
unknown fields and unknown protocol versions are rejected fail-closed.
When `SPEC_AGENT_BRAIN_INTERNAL_SECRET` is set, requests must carry the same
value in the `X-Spec-Agent-Internal-Token` header.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SPEC_AGENT_BRAIN_INTERNAL_SECRET` | *(empty)* | Shared internal token; empty disables auth (dev only) |
| `SPEC_AGENT_BRAIN_MODEL_MODE` | `fake` | `fake` = deterministic offline outputs; `broker` = call the Java inference broker |
| `SPEC_AGENT_INTERNAL_BROKER_URL` | `http://localhost:8080/internal/v1/model-inference` | Broker endpoint used in `broker` mode |

There is deliberately no database driver, no provider SDK, and no retry/fallback
logic in this service.

## Local development

```bash
cd agent-brain
python -m venv .venv
.venv/Scripts/pip install -e ".[dev]"      # Windows Git Bash
.venv/Scripts/python -m pytest             # run the test suite
.venv/Scripts/uvicorn spec_agent_brain.app:app --port 8100
```

## Docker

```bash
docker compose up -d agent-brain   # from the repository root
```

The compose service defaults to `broker` mode against
`http://host.docker.internal:8080` so a locally running Spring backend serves
inference. Set `SPEC_AGENT_BRAIN_MODEL_MODE=fake` to run fully offline.

## Global Assistant migration preparation

The isolated `global_assistant` package uses locked LangChain/LangGraph
components and a credential-free native model broker adapter. Authenticated
`POST /internal/v1/global-assistant/executions` now streams bounded NDJSON
execution events. It requires broker mode and a nonempty internal token, even
when Project Agent development endpoints permit fake mode. The host RPC origin
is derived from the configured broker URL, never accepted in an execution body.
Project Agent routes and model protocols remain unchanged.

Java `spec.global-assistant.engine` explicitly selects `java-legacy.v1` (current
default) or `langchain-ga.v1`. The new selection pins an envelope and claims the
existing lifecycle atomically; it does not run the old loop as a fallback.
Production selection/migration remains gated by the implementation report.
Python obtains a durable one-shot host execution claim before starting a graph.
Repeated starts and restarts cannot create another executor for the same run.

Only the last host-completed checkpoint is used for continuity. Interrupted
chains remain diagnostic material; new input starts from the completed boundary
with unconsumed public history. Graph summaries use the same Java model budget
and explicitly disable the pinned framework's automatic summary retries.
Observed project IDs/candidates/source refs remain in bounded structured state,
separate from summary text and current permission authority. AGENT calls use
authenticated host `/global-assistant/model-inference/stream`. Typed content
fragments flow through a bounded queue while create_agent is running; execution
events include STATUS, TEXT_DELTA(callId/text), TEXT_RESET(callId), and one terminal.
Tool arguments/reasoning/provider frames never enter text. Text before a tool
decision is a retractable candidate, not a completed answer. SUMMARY remains a
non-streaming request sharing the durable budget. Product completion verifies
draft/model/checkpoint equality. Java failure sends one advisory strict epoch/lease
cancel; persistent host fences remain authoritative. See the implementation status
for live versus fixture gates; production browser acceptance is still pending.

Install with `.venv/Scripts/python -m pip install -c requirements.lock -e ".[dev]"`.
`requirements.lock` pins the runtime/dev dependency closure validated on
Python 3.11 / Windows; other Python/platform combinations require qualification.
Regenerate it with `scripts/export_dependency_lock.py` after an explicit
dependency review. Regenerate GA schemas/fixtures with
`scripts/export_ga_contracts.py` and run the full pytest suite.

See `../contracts/global-assistant/README.md` and
`../docs/v2/GLOBAL_ASSISTANT_LANGCHAIN_IMPLEMENTATION_STATUS.md` for the
verified subset and outstanding host/live gates. In-memory storage is only
used in tests. `HostCheckpointSaver` requires an authenticated host RPC
implementation; it does not connect to a production database.

The independent `retrieval` module contains the shared retrieval.v1 implementation,
used by both agents. See `../contracts/retrieval/README.md`. Re-export schemas
with `scripts/export_retrieval_contracts.py`. The explicit
`scripts/probe_local_embedding.py` uses the installed local
`qwen3-embedding:0.6b` through OllamaEmbeddings, never pulls a model or writes an
index, and is not part of ordinary tests. Production retrieval/RPC cutover
is still pending; Java pgvector storage remains authoritative.

## Shared retrieval U1–U3 implementation

`retrieval.v1` now implements source-preserving chunks, pinned local Ollama embedding and bounded hybrid fusion for both agents. Authenticated Java host RPC owns the single pgvector store, canonical source validation, grants, leases, generation and CAS. The Python service has no production database connection or provider credentials.

Authenticated `GET /internal/v1/retrieval/health` reports storeReady and ollamaReady separately; no embedding generation or model pull occurs in readiness. Search/index/source-chunks use strict typed envelopes; Python request bodies are capped at 1 MiB and source responses at 2 MiB. Query/document embedding shares one semaphore. Ollama failures remain explicit, without another provider.

Project retrieval requires explicit `SPEC_AGENT_RETRIEVAL_ENGINE=python-rag.v1`; GA requires explicit `SPEC_AGENT_GLOBAL_ASSISTANT_ENGINE=langchain-ga.v1`. Defaults and production settings remain unchanged. See the implementation status and `GLOBAL_ASSISTANT_LANGCHAIN_RELEASE_RUNBOOK.md` for actual HTTPS/browser/restart/model evidence, the pending human-labelled evaluation and paired rollout/rollback. Interrupted tool calls are not automatically replayed.

Windows validation note (2026-10-02): the original uv CPython 3.11.15 build intermittently crashed in python311.dll during Ollama/Pydantic schema imports. An isolated CPython 3.11.14 environment using the unchanged requirements.lock passed 221 Python tests and the full 1739 Java regression including real RPC/Ollama tests. Dependency versions and the pydantic native-module hash were identical. The original .venv remains unchanged; Java integration harnesses accept SPEC_AGENT_GA_TEST_PYTHON to select the qualified interpreter. Other deployment builds still require qualification.

Pre-release qualification tools: scripts/prepare_candidate_runtime.ps1 creates/verifies a separate Windows candidate pinned by candidate-runtime.lock.json (3.11.14/build20260211 and binary fingerprints); no original venv replacement or crash retry. scripts/audit_retrieval_acceptance.py statically checks the frozen 68-query independent candidate and writes review materials without executing queries or approving labels. The existing 52-query set is development/calibration only. See docs/v2/GLOBAL_ASSISTANT_PRE_RELEASE_CLOSEOUT.md and the release runbook for startup commands, actual repeated-start evidence and remaining deployment/human gates.

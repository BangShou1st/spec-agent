# Python Agent Runtime Boundary V2

> Status: **Frozen 2026-08-21** after owner approval.
> Python is bootstrapped in Stage A, before any new V2 reasoning logic is written.

## 1. Principle

> 2026-10-01 scoped target extension: `UNIFIED_PYTHON_RAG_DESIGN.md` moves shared retrieval algorithms, resource splitting and local Ollama embedding to a Python module used by both Agents. Java keeps canonical facts, source/scope validation, index persistence and scheduling. Python may call the configured local Ollama embedding service through LangChain without provider credentials; it still must not access production databases or call remote chat providers outside the Java broker. This is a staged target, not an implemented cutover. The Project Decision Engine wire protocol and Graph ownership below remain unchanged.

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

## 10. Isolated Global Assistant migration preparation

`GLOBAL_ASSISTANT_LANGCHAIN_REDESIGN.md` governs the application assistant's
future `create_agent` execution boundary. The strict DTOs/schemas under
`contracts/global-assistant/` and Python `global_assistant` module are separate
from `/v1/decisions`, state updates, artifacts and `model-inference.v1`.
Current Project Agent semantics and validation are unchanged.

The GA model adapter only calls a configured Java internal broker; no provider
SDK/key or database access is added to Python. The host-backed saver delegates
storage/epoch/lease/CAS to Java via RPC. Its offline tests do not qualify the
Java storage service or authorize automatic restart recovery. Product GA runs
continue using the current Java engine until all live, host persistence and
cross-language gates in the implementation status report pass.

U0 shared retrieval DTOs/schemas are specified by
`contracts/retrieval/README.md`. The separate Python retrieval module may use
local OllamaEmbeddings for both agents; remote chat remains behind the Java
model broker. Existing frozen projection replay never re-queries retrieval or
embedding. U2's new first-projection availability metadata must distinguish
missing supplemental retrieval from canonical mandatory context; it must be
frozen before the context service is switched. U0 does not change current
production snapshot behavior or add a second vector index.

GA now has separate authenticated Java model and checkpoint endpoints. The
native provider transport reuses the existing OpenCode headers/proxy/credential
policy, retains Zen's non-executable reserved declarations, and checks the
response against the run-bound real catalog. Durable execution budgets and
checkpoint CAS stay in Java. Explicit test-host integration passed with the
configured live model and a synthetic read-only tool result. A subsequent real
create_agent/model/capability/checkpoint roundtrip also passed with the existing
project.list_recent business capability against the isolated test database.
Frozen catalogs and recorded native calls gate capability dispatch; uncertain
semantic writes are not retried under new identities. Product application/
dispatcher/coordinator/Python execution HTTP and product event completion now
also passed with the real configured model and an actual read-only capability
in the test database. Deterministic cross-language tests cover continuity,
duplicate starts, cancellation, steer, process interruption and deletion.
External/file effect failure windows and production recovery remain unqualified.
Typed body streaming passed with the configured model through Java broker,
Python create_agent, product events and frontend projection replay; production
browser acceptance remains pending. A restored checkpoint does not authorize phase-1 automatic
resume. See the latest implementation-status increment before enabling runs.

V47 persists the canonical execution envelope and one-shot executor claim.
Python cannot supply a new origin/model credential or reclaim an interrupted
run. Java records a completed checkpoint/public history boundary atomically
with the existing terminal message/events; new runs read this boundary rather
than the newest unfinished checkpoint. Framework summarization shares the
run's host model budget and explicitly removes automatic framework retries;
bounded observed project identities/source references live outside summary text.

2026-10-02 streaming increment: the Java native decoder releases candidate content
only, keeping complete tool parsing and credentials inside the broker. Python's
standard model adapter forwards typed candidates through a bounded queue without
adding an Agent decision loop. Java commits incremental receipts/product events
under the run fence and verifies final model/checkpoint output before completion.
Tool decisions reset earlier candidates; terminal states reject late text. V48
expands the internal event budget in the test database only. Existing approval
policy is retained: Skill staging does not install/enable; external write/approval
resume tools remain unavailable. Real model query/navigation/local creation/question
gates passed; Skill model/service/database integration uses a Git fixture and is
not live network/cleanup qualification. Shared Python RAG U1–U3 is still gated;
no parallel index, Java Ollama provider or Python production DB connection was added.

## 统一检索落地边界（2026-10-02）

共享 retrieval.v1 已通过实际 Python/Ollama/受控宿主 RPC/既有 pgvector 集成。Python 负责结构分块、embedding、RRF、去重和预算；Java 负责规范来源投影、权限和 workload/grant/epoch/lease、同表存储及来源版本/authority/位置校验。没有 Python 数据库连接、Java Ollama provider 或第二套活动向量索引。

检索通过 common.BrainConnectionSettings 获取既有内部服务配置，避免 retrieval 反向依赖项目 Agent。投影通过 ResourceProjectionJobs 端口创建资源分块任务；协议实现依赖业务投影策略，业务投影不依赖协议控制器。包循环架构门禁继续执行，不添加忽略规则。

项目首次输入仍由 Runtime 保证必需上下文并冻结；补充检索失败状态也冻结，历史输入不重新检索。全局助手的 help.search/project.content.discover 是有界只读按需能力。共享算法不强迫两者采用相同控制流程。

完整实现、非生产集成边界和剩余人工评测门槛见 [实施记录](GLOBAL_ASSISTANT_LANGCHAIN_IMPLEMENTATION_STATUS.md)。

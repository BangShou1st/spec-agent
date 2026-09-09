# Global Assistant Implementation Plan

> Status: implementation-ready design  
> Scope: Global Assistant（全局助手） only  
> Delivery strategy: **Backend one implementation round first, then Frontend one implementation round**  
> Current baseline: Tool / Skill / MCP capability foundation is already completed; Global Assistant V1 intentionally uses **Host Function Tools（宿主函数工具） only**.

---

## 0. Purpose

Global Assistant is the application-level conversational Agent for Spec Agent.

It is **not** a second Project Agent（项目需求智能代理） and must not take ownership of requirement Graph semantics such as claims, routes, conflicts, decisions, or Spec generation.

Its job is to let the user operate Spec Agent naturally:

```text
User
  |
  v
Global Assistant
  |
  +-- understand application-level intent
  +-- use current conversation/UI context
  +-- call bounded Host Function Tools
  +-- stream visible work progress
  +-- continue for a few bounded steps
  +-- return a concise final answer / navigation action
```

Target product feeling:

```text
User:
打开我之前那个支付项目

Assistant:
工作中

正在查找相关项目…

[Tool] 搜索项目
找到 2 个候选

正在确认项目状态…

[Tool] 读取项目概要
支付结算系统

已找到并打开「支付结算系统」。
```

The user should feel that the Agent is **doing work**, not merely returning a chatbot paragraph.

---

# 1. Product boundary

## 1.1 V1 responsibilities

Global Assistant V1 may:

- create a project;
- search existing projects;
- list recent projects;
- read a bounded project summary;
- resolve references from recent conversation such as “第一个 / 刚才那个 / 这个项目”;
- request clarification when multiple candidates remain;
- emit client navigation actions;
- answer lightweight questions about the application when no Tool is required;
- perform several bounded Tool steps in one run;
- stream progress, Tool lifecycle, final text, and UI actions.

## 1.2 Explicitly not V1

Do not implement in the first Global Assistant round:

- MCP use by Global Assistant;
- Skill activation/use by Global Assistant;
- Multi-Agent（多智能代理）;
- Handoff（Agent 移交） runtime;
- Project Agent requirement reasoning inside Global Assistant;
- claims / routes / conflict / decision mutation;
- arbitrary file-system or shell access;
- arbitrary internet browsing;
- destructive project deletion;
- complex long-term personal memory;
- autonomous background tasks;
- marketplace/plugin behavior.

The existing Tool / Skill / MCP platform remains available for future expansion, but V1 does not need it.

---

# 2. Core architectural decision

Global Assistant should be a **small application Tool Agent**.

Do not reuse the Project Agent's `STATE_UPDATE -> DECISION` semantic cycle.

Global Assistant has no requirement-state patch to persist before every answer.

Its normal loop is:

```text
User Message
    |
    v
Global Context Builder
    |
    v
Global Assistant Decision
    |
    +-------------------------------+
    |                               |
    v                               v
Final Response                Tool Request
                                    |
                                    v
                         Validator / Policy
                                    |
                                    v
                              Tool Execute
                                    |
                                    v
                               Observation
                                    |
                                    +------> next bounded Decision
```

This is intentionally simpler than the Project Agent.

---

# 3. Reuse existing architecture, do not create a second platform

Global Assistant should reuse:

- the existing model/provider boundary where practical;
- `CapabilityDescriptor` / Capability Runtime concepts;
- Host Function Tool execution;
- permission and side-effect metadata;
- Validator / Policy / approval conventions;
- typed Observation normalization;
- tracing conventions;
- idempotency rules.

It should **not** reuse:

- Project `AgentInputSnapshot` as-is;
- AnswerPatch replay;
- Route lineage as the primary context;
- Project Agent conflict-action protocol;
- Project Graph mutation action families.

Create a separate bounded context projection:

```text
GlobalAssistantContext
```

instead of forcing Global Assistant into Project Agent semantics.

---

# 4. High cohesion / low coupling rules

Recommended backend packages:

```text
com.specagent.globalassistant
  /api
  /context
  /conversation
  /model
  /runtime
  /stream
  /tool
  /eval
```

Responsibilities:

```text
api          HTTP/SSE contract only
conversation thread/message persistence
context      builds model-visible GlobalAssistantContext
model        provider-neutral request/response contracts
runtime      bounded agent loop and run lifecycle
stream       public event protocol
 tool        Global Assistant Host Function Tool adapters
eval         semantic/tool-use evaluation fixtures
```

Adding a future Tool such as `connection.get_status` should require implementing/registering the Tool plus tests/evals, not rewriting the Global Assistant core loop.

Changing model provider must not change Tool implementations, conversation persistence, streaming schema, or frontend rendering contract.

---

# 5. Anti-overfitting rule

Natural-language wording must never become deterministic routing logic.

Forbidden:

```java
if (message.contains("打开")) useProjectSearch();
if (message.contains("创建")) createProject();
if (message.contains("项目")) showProjectTools();
```

Also forbidden:

- regex lists of synonyms;
- benchmark-specific phrases;
- special branches for one test utterance;
- hidden prompt clauses for individual examples.

Allowed deterministic rules operate on **structured state**:

```text
selectedEntity.type == PROJECT
    -> selected project may be exposed in context

tool permission missing
    -> tool is invisible

tool sideEffectClass requires approval
    -> Runtime approval path

candidateProjects.size == 1
    -> candidate identity may be carried forward
```

Meaning is interpreted semantically by the model; safety and state invariants are enforced by Runtime.

---

# 6. Global Assistant context architecture

Global Assistant must support context from V1.

Do not equate context with “all previous messages”.

```text
GlobalAssistantContext
|
+-- Current Request
+-- Recent Conversation
+-- Conversation Summary
+-- UI Context
+-- Working State
+-- Recent Project Hints
+-- Available Tool Descriptors
```

## 6.1 Current Request

Exact current user message.

## 6.2 Recent Conversation

Bounded recent window. Recommended initial policy:

```text
last 12 user/assistant turns
```

The number is configuration, not prompt logic.

## 6.3 Conversation Summary

A rolling summary for earlier conversation, preserving only useful referents and goals.

Example:

```text
The user is looking for an older AI email project.
The previous search returned two candidate projects.
Candidate A is projectId ...
Candidate B is projectId ...
The user has not chosen yet.
```

Do not preserve verbose assistant prose.

## 6.4 UI Context

Provided structurally by the client:

```text
currentPage
selectedEntity
visibleProjectIds (bounded)
entryPoint
```

Example:

```json
{
  "currentPage": "PROJECTS",
  "selectedEntity": {
    "type": "PROJECT",
    "id": "..."
  }
}
```

UI context is evidence of what the user is pointing at; it is not natural-language routing.

## 6.5 Working State

Tracks only the current unresolved task:

```text
goal
candidate references
waitingFor
lastToolResultRefs
stepCount
```

This enables:

```text
Assistant: 找到两个项目：A / B
User: 第一个
```

without re-searching from scratch.

## 6.6 Recent Project Hints

Small bounded set, e.g. recently updated/visited project metadata:

```text
projectId
title
updatedAt
```

Never inject the full project catalog.

## 6.7 Available Tool Descriptors

Only Global Assistant V1 Tools.

No MCP catalog and no Skill catalog in V1.

---

# 7. Canonical-state rule

Application truth comes from services/database, not conversation memory.

```text
Conversation may remember:
“刚才用户在找 AI Mail”

Database/service must answer:
“这个 projectId 现在是否存在”
“它当前标题是什么”
“它最新状态是什么”
```

Conversation is for continuity; Tools are for current truth.

---

# 8. Conversation persistence

Persist lightweight conversation state so the Assistant survives navigation and refresh.

Recommended entities/tables:

## `global_assistant_threads`

```text
id
created_at
updated_at
summary
summary_version
```

## `global_assistant_messages`

```text
id
thread_id
role            USER | ASSISTANT
content
created_at
run_id nullable
```

Do **not** store hidden chain-of-thought.

## `global_assistant_runs`

```text
id
thread_id
status
started_at
completed_at
failed_at
model_provider
model_name
prompt_version
context_projection_version
step_count
error_code nullable
```

## `global_assistant_run_events`

Prefer persisting public events if implementation cost remains reasonable because it improves reconnect/debugging.

Never persist raw provider credentials or hidden reasoning.

---

# 9. Host Function Tools for Backend V1

Keep the initial set small.

## 9.1 `project.create`

Purpose: create a new Spec Agent project.

Input:

```json
{"title":"string"}
```

Output:

```text
projectId
title
activeRouteId
```

Implementation:

- reuse `ProjectService.createProject`;
- side effect = `LOCAL_DURABLE`;
- do not create claims or requirement nodes.

## 9.2 `project.search`

Purpose: find existing projects from user-described intent.

Input:

```json
{"query":"string","limit":5}
```

Output:

```text
projectId
title
updatedAt
```

Rules:

- deterministic retrieval/ranking over project metadata;
- lexical retrieval is acceptable infrastructure;
- no hard-coded user-phrase routing;
- semantic ranking may be added later only if evaluation proves necessary;
- never call Project Agent to search projects.

## 9.3 `project.list_recent`

Purpose: return recent projects without loading all projects into context.

Input:

```json
{"limit":5}
```

Output:

```text
projectId
title
updatedAt
```

If current project listing is creation-order, add a dedicated query/read-model method instead of changing existing `ProjectService.listProjects()` semantics globally.

## 9.4 `project.get_summary`

Purpose: bounded application-level project summary.

Input:

```json
{"projectId":"uuid"}
```

Suggested output:

```text
projectId
title
updatedAt
activeRouteId
activeRouteTitle
routeCount
nodeCount
latestActivityAt
specAvailable
```

Only include fields that can be obtained reliably and cheaply.

Do not return the entire Graph.

Create a dedicated read model/service, e.g. `GlobalProjectSummaryQueryService`, composing existing services/read models.

## 9.5 `ui.navigate`

This is a **Client Tool / UI Action**, not a database operation.

Input:

```json
{
  "destination":"PROJECT | PROJECTS | SKILLS | CONNECTIONS | SETTINGS",
  "resourceId":"uuid|null"
}
```

The backend emits a typed `UI_ACTION`; the frontend performs navigation.

Do not let the model construct arbitrary URLs.

---

# 10. Tools intentionally deferred

Do not add until V1 is stable:

```text
project.rename
project.archive
project.delete
skill.*
connection.*
mcp.*
filesystem.*
web.*
shell.*
```

`project.delete` must be a later explicit-confirmation slice.

---

# 11. Tool descriptor rules

Descriptors explain semantics, not trigger phrases.

Good:

```text
project.search

Search existing Spec Agent projects using a short description of the
project the user is trying to locate. Returns bounded candidate metadata.
Use when the target project identity is not already resolved from structured
UI or conversation context.
```

Bad:

```text
Use when the user says “找项目”, “打开项目”, “之前那个项目”...
```

Every descriptor should communicate:

```text
what it does
when useful
prerequisites
input semantics
result shape
limits / side effects
```

---

# 12. Model contract

Global Assistant gets its own provider-neutral decision contract.

Logical shape:

```text
GlobalAssistantDecision
|
+-- assistantText?
+-- statusText?
+-- toolRequest?          // max one primary Tool per decision
+-- uiAction?
+-- requiresUserInput?
+-- done
```

Prefer **one primary Tool call per model decision**.

Parallel tool calling is unnecessary in V1 and makes tracing/retry/UI sequencing harder.

Provider-native Function Calling may be used in the Brain adapter, but Runtime normalizes it into the internal decision/invocation contract.

---

# 13. Bounded Tool loop

Recommended defaults:

```text
MAX_STEPS = 6
MAX_TOOL_CALLS = 5
```

Normal runs should use 0–3 Tool calls.

Stop when:

- goal achieved;
- user input required;
- UI navigation completes the goal;
- approval required;
- repeated identical Tool call;
- no-progress detection;
- step budget exhausted;
- model returns final answer;
- unrecoverable Tool/runtime failure.

---

# 14. Streaming architecture

Streaming is a first-class backend contract, not a frontend animation.

## 14.1 Public event protocol

```text
RUN_STARTED
STATUS
ASSISTANT_DELTA
TOOL_STARTED
TOOL_COMPLETED
TOOL_FAILED
USER_INPUT_REQUIRED
APPROVAL_REQUIRED
UI_ACTION
ASSISTANT_COMPLETED
RUN_COMPLETED
RUN_FAILED
```

Suggested envelope:

```json
{
  "eventId":42,
  "runId":"...",
  "threadId":"...",
  "type":"TOOL_STARTED",
  "sequence":7,
  "createdAt":"...",
  "payload":{}
}
```

`sequence` is monotonic per run.

## 14.2 Visible status

Examples:

```text
正在查找相关项目…
正在读取项目概要…
正在创建项目…
```

Prefer Runtime/Tool metadata such as:

```text
runningLabel
completedLabel
```

Do not expose private chain-of-thought.

Allowed:

```text
正在确认目标项目…
```

Not allowed:

```text
I think the user probably means X because...
```

## 14.3 Assistant text streaming

Support `ASSISTANT_DELTA` with real provider token streaming where practical.

If the current Brain abstraction is not stream-capable immediately:

- freeze the SSE protocol now;
- stream status/tool events immediately;
- emit final text as one or several deltas;
- later add provider token streaming behind the adapter without changing frontend protocol.

Do not block the whole feature on perfect token-level streaming.

---

# 15. HTTP / SSE API

## Create thread

```text
POST /api/v1/global-assistant/threads
```

Response:

```text
threadId
```

## Create run

```text
POST /api/v1/global-assistant/threads/{threadId}/runs
```

Request:

```json
{
  "message":"...",
  "uiContext":{
    "currentPage":"HOME",
    "selectedEntity":null
  }
}
```

Response:

```json
{"runId":"...","status":"RUNNING"}
```

## Stream run events

```text
GET /api/v1/global-assistant/runs/{runId}/events
Accept: text/event-stream
```

Use SSE（Server-Sent Events，服务器发送事件） for V1.

Support `Last-Event-ID` when public events are persisted.

## Cancel

```text
POST /api/v1/global-assistant/runs/{runId}/cancel
```

Cancellation stops future steps where possible but does not pretend to roll back an already-completed durable Tool.

---

# 16. Prompt architecture

Keep the Global Assistant system prompt short and generic.

Suggested baseline:

```text
You are Spec Agent's application-level assistant.

Help the user operate Spec Agent using the currently available tools and
structured application context.

You do not replace the Project Agent for requirement exploration.

Rules:
- Choose tools by semantic need and context, never by keyword overlap alone.
- If structured context already identifies the target, use it.
- If current application truth is needed, use the appropriate tool instead of
  trusting old conversation text.
- Ask the user when essential ambiguity remains.
- Do not invent project IDs, URLs, capabilities, or tool results.
- Use only tools actually provided in the current request.
- Stop once the user's application-level goal is achieved.
- Avoid unnecessary tool calls.
- Destructive or privileged operations are controlled by Runtime policy.
```

Do not add business phrase dictionaries.

Tool-specific behavior belongs primarily in descriptors.

---

# 17. Prompt/context versioning

Record:

```text
global_prompt_version
context_projection_version
tool_catalog_fingerprint
model_provider
model_name
```

When behavior changes, diagnose whether the cause was:

```text
Prompt
Context
Tool descriptor
Retrieval/search
Model
Policy/runtime
```

Do not default to Prompt changes.

---

# 18. Rolling summary strategy

Do not summarize after every message.

Refresh when:

- recent-message count exceeds a configured threshold; or
- estimated context size exceeds a configured threshold.

Summary should preserve:

```text
unresolved referents
chosen project IDs
user-stated goals
important prior Tool outcomes
```

Discard:

```text
verbose explanations
status copy
repeated UI text
```

Never summarize hidden reasoning.

---

# 19. Example run

User:

```text
打开之前那个支付项目
```

Execution:

```text
1. persist USER message
2. create run
3. emit RUN_STARTED
4. build GlobalAssistantContext
5. model -> project.search(query="支付")
6. emit STATUS
7. emit TOOL_STARTED
8. execute project.search
9. persist normalized Observation
10. emit TOOL_COMPLETED
11. rebuild bounded context with Observation
12. model -> one candidate + UI action + final text
13. emit ASSISTANT_DELTA(s)
14. persist ASSISTANT message
15. emit UI_ACTION
16. emit ASSISTANT_COMPLETED
17. emit RUN_COMPLETED
```

If two candidates remain:

```text
model asks user to choose
-> USER_INPUT_REQUIRED
-> current run ends normally
```

Next user message continues the same thread with Working State.

---

# 20. Error handling

Typed public errors:

```text
PROJECT_NOT_FOUND
TOOL_ARGUMENT_INVALID
TOOL_EXECUTION_FAILED
MODEL_UNAVAILABLE
MODEL_INVALID_RESPONSE
RUN_STEP_LIMIT
RUN_CANCELLED
STREAM_DISCONNECTED
```

Never surface stack traces or provider internals.

A recoverable Tool failure becomes an Observation; repeated identical failure stops the loop.

---

# 21. Observability

Trace:

```text
threadId
runId
model invocation IDs
context version/hash
tool catalog fingerprint
selected tool
tool args hash/redacted args
tool duration
tool outcome
step count
terminal state
public event sequence
```

Never log credentials or hidden chain-of-thought.

---

# 22. Backend evaluation

Backend is not complete merely because unit tests pass.

## Core scenarios

### Create

```text
新建一个 AI 日历项目
帮我创建个做账单分析的项目
我想开始设计一个新的笔记产品
```

Expected semantic action: `project.create`.

### Search

```text
找一下之前做邮件分类那个项目
我那个支付系统在哪
把旧的 AI 日历项目找出来
```

Expected: `project.search`.

### Resolved navigation

With structured `selectedEntity = PROJECT`:

```text
打开这个
进去看看
```

Expected: no unnecessary search; emit `ui.navigate`.

### Summary

```text
那个支付项目现在做到哪了
看看 AI Mail 项目目前状态
```

Expected: resolve identity + `project.get_summary`.

### Ambiguity

Two plausible candidates:

```text
打开邮件那个
```

Expected: ask/show bounded choices, do not guess.

## Negative controls

```text
GitHub 上项目一般怎么组织？
“项目”这个词英文怎么说？
我可能以后会做一个支付项目
```

Expected: do not search/create merely because keywords appear.

## Metrics

```text
tool-needed accuracy
wrong-tool rate
unnecessary-tool rate
argument validity
multi-step completion rate
ambiguity handling accuracy
reference-resolution accuracy
step count
run completion rate
stream event ordering correctness
```

Prompt changes require paraphrase + held-out + negative-control regression.

---

# 23. Backend implementation round

This is **one coherent backend development round**.

## Slice A — contracts and persistence

Implement:

```text
GlobalAssistantThread
GlobalAssistantMessage
GlobalAssistantRun
GlobalAssistantEvent (if persisted)
GlobalAssistantContext
GlobalAssistantDecision
GlobalAssistantEvent protocol
```

Add migration(s).

## Slice B — Host Function Tools

Implement/register:

```text
project.create
project.search
project.list_recent
project.get_summary
ui.navigate
```

Reuse domain services/read models; do not expose repositories directly.

## Slice C — Context Builder

Implement:

```text
recent conversation projection
rolling summary projection
UI context projection
working state projection
recent project hints
bounded tool descriptors
```

## Slice D — Brain/model adapter

Add a Global Assistant inference path with:

```text
separate prompt
separate contract
provider-neutral normalization
optional provider-native Function Calling adapter
```

Do not put Global Assistant behavior into Project Agent DECISION prompt.

## Slice E — bounded runtime loop

Implement:

```text
run lifecycle
step budget
tool execution
Observation injection
no-progress detection
terminal states
cancel
```

## Slice F — SSE

Implement:

```text
event publisher
ordered per-run sequence
event endpoint
reconnect policy
token/status/tool/UI events
```

## Slice G — evaluation and acceptance

Run:

- unit tests;
- integration tests;
- persistence tests;
- SSE ordering tests;
- model contract tests;
- deterministic fake-agent tests;
- real-model targeted evaluation;
- full backend regression.

Do not start Frontend until Backend acceptance passes.

---

# 24. Backend acceptance gate

Backend = PASS only if all are true.

## Architecture

- Global Assistant does not mutate Project Agent requirement state directly.
- No natural-language keyword routing in code.
- No duplicate capability execution platform.
- Tools compose services/read models, not repositories from controller/tool layer.
- Model provider does not leak into public Agent contracts.

## Tool behavior

- create/search/recent/summary/navigation work.
- invalid IDs and missing projects fail typed.
- descriptors are bounded.
- repeated calls are detected.
- durable-write idempotency is defined.

## Context

- follow-up references work.
- refresh/new request can continue the same thread.
- current application truth comes from Tools/services.
- context remains bounded.
- old conversation cannot override database truth.

## Streaming

- events are ordered.
- Tool start/completion is visible before final answer.
- UI action is explicit and typed.
- final answer can stream through `ASSISTANT_DELTA`.
- disconnect/reconnect behavior is defined/tested.
- no chain-of-thought is emitted.

## Evaluation

- paraphrases produce stable semantic behavior.
- negative controls do not cause keyword-triggered actions.
- ambiguity is not silently guessed.
- no significant Project Agent regression.

Only after this gate:

```text
BACKEND GLOBAL ASSISTANT = FROZEN V1
```

Then start Frontend.

---

# 25. Frontend development round

Frontend consumes the frozen backend event protocol.

Do not redesign Agent behavior in React.

## 25.1 Home entry point

Add a clear Global Assistant input/conversation surface at application level, outside a specific Project Graph.

## 25.2 Minimum conversation UI

```text
user messages
assistant messages
working indicator
status lines
tool cards
clarification choices
final answer
```

## 25.3 Event mapping

```text
RUN_STARTED
  -> show “工作中”

STATUS
  -> show/update concise status

ASSISTANT_DELTA
  -> append assistant text

TOOL_STARTED
  -> create running Tool card

TOOL_COMPLETED
  -> mark success + short summary

TOOL_FAILED
  -> mark failure

USER_INPUT_REQUIRED
  -> stop spinner; show assistant question

UI_ACTION
  -> execute typed navigation

RUN_COMPLETED
  -> stop working state

RUN_FAILED
  -> show recoverable error state
```

## 25.4 Tool cards

Default compact rendering:

```text
✓ 搜索项目
  找到 2 个结果
```

Expandable details may show:

```text
display name
safe input summary
safe result summary
duration
```

Do not dump raw JSON by default.

## 25.5 “Thinking” presentation

Allowed:

```text
工作中
正在查找项目…
正在确认项目状态…
```

Not allowed:

- hidden chain-of-thought;
- internal reasoning paragraphs;
- fake reasoning invented only for visual effect.

UI shows **observable work**, not private reasoning.

## 25.6 Navigation

`UI_ACTION` calls existing router/navigation code.

Never use arbitrary model-generated URLs.

## 25.7 Cancel

Active run displays:

```text
[停止]
```

and calls backend cancel.

---

# 26. Frontend acceptance gate

Frontend = PASS when:

- assistant text appears progressively;
- status appears while work is active;
- Tool lifecycle visibly updates;
- failed Tool is clear;
- user can stop a run;
- ambiguous project selection works;
- navigation works reliably;
- refresh does not corrupt conversation;
- no private reasoning is rendered;
- existing Project workspace is not regressed;
- Skills/Connections pages continue to work;
- visual hierarchy remains restrained.

---

# 27. Exact delivery order

## Round 1 — Backend

```text
1. freeze Global Assistant contracts
2. persistence
3. project Host Function Tools
4. GlobalAssistantContext
5. dedicated model prompt/contract
6. bounded Tool loop
7. SSE event protocol
8. backend tests
9. real-model evaluation
10. backend acceptance/freeze
```

Do not modify real Frontend except a minimal test harness if unavoidable.

## Round 2 — Frontend

```text
1. conversation UI
2. SSE client
3. working/status presentation
4. Tool cards
5. clarification interaction
6. typed UI navigation
7. cancel/reconnect
8. polish/accessibility
9. E2E
10. final acceptance
```

Do not change backend semantics during frontend polish unless a real contract defect is found.

---

# 28. Future extensions after V1

Only after both rounds are stable:

```text
project.rename
project.archive
project.delete + confirmation

connection.get_status
connection.connect

skill.list
skill.install

MCP tools
provider built-in tools
longer-term preferences
```

These should plug into the same Tool/Event/Context model. No new Agent architecture should be required.

---

# 29. Final architecture

```text
                         User
                          |
                          v
                Global Assistant UI
                          |
                    POST message
                          |
                          v
              Global Assistant Runtime
                          |
                 Context Builder
                  /      |       \
                 /       |        \
        Conversation   UI State   App hints
                 \       |        /
                  \      |       /
                       LLM
                        |
                 Global Decision
                  /             \
                 /               \
          Final / Ask             Tool
                                    |
                                    v
                           Capability Runtime
                                    |
                            Host Function Tool
                                    |
                                    v
                               Observation
                                    |
                                    +------> next bounded step

Parallel public event stream:

RUN_STARTED
STATUS
TOOL_STARTED
TOOL_COMPLETED
ASSISTANT_DELTA
UI_ACTION
RUN_COMPLETED
```

---

# 30. Final principles

1. **Global Assistant is an application Agent, not another requirement Agent.**
2. **V1 uses Host Function Tools only.**
3. **Conversation gives continuity; services/database give truth.**
4. **Context is structured and bounded.**
5. **No natural-language keyword routing.**
6. **Tool descriptors carry semantics; Runtime carries invariants.**
7. **One primary Tool per decision keeps the loop understandable.**
8. **SSE streams public execution events, not private chain-of-thought.**
9. **Backend behavior freezes before Frontend begins.**
10. **Future Skill/MCP support must plug into this design, not force a rewrite.**

# Global Assistant V1 — Backend Phase 0 Freeze (Repository Reality Audit + Contract Freeze)

> Status: **Phase 0 frozen**. Backend implementation contract for Global Assistant V1.
> Authority: `docs/v2/GLOBAL_ASSISTANT_IMPLEMENTATION_PLAN.md` (baseline, unchanged) + this file (frozen integration boundary).
> Rule: 后续 Backend 实现只看上述两个文件即可开工；不得复用 Project Agent 推理语义、不得复制 Capability 平台、不得污染 Provider boundary。
> 本轮未实现任何 Global Assistant Runtime / Tool Loop / SSE / Controller / DB 实体 / Frontend（按 Phase 0 禁止项）。

## 1. repository baseline

Branch:

```text
global-assistant-v1
```

Base commit (required):

```text
41914ab356e7e076a28da71430ffae6619e1481e  # Skills and Connections frontend integration
```

Phase 0 start HEAD (= base):

```text
41914ab356e7e076a28da71430ffae6619e1481e
```

Remote专项分支在 fetch 后存在：

```text
origin/global-assistant-v1 = 2a9121cfa0b36dd34f4f3d9c233fdeeb0273c8e3
  2a9121c docs(global-assistant): add implementation plan baseline
  84866f8 docs: authorize Global Assistant V1 feature branch
  41914ab (base)
```

按 Phase 0 §0 执行 `git pull --ff-only origin global-assistant-v1` 后本地 HEAD = `2a9121c`，ancestry 包含 `41914ab`（`git merge-base --is-ancestor` = 0）。符合“设计文档 commit 向前移动可接受”条款。
新增文件仅为 `docs/v2/GLOBAL_ASSISTANT_IMPLEMENTATION_PLAN.md`（1467 行）+ `AGENT.md` 分支授权段落；无 production behavior 变更；未 merge main；未创建额外 feature branch；未修改 Frontend。

设计基线确认：

```text
docs/v2/GLOBAL_ASSISTANT_IMPLEMENTATION_PLAN.md  # 已在仓库，原样使用，未重写
```

必读资料均已读：`AGENT.md`、`docs/v2/README.md`、`AGENT_RUNTIME_ARCHITECTURE.md`、`AGENT_STATE_MODEL.md`、`AGENT_MEMORY_AND_CONTEXT.md`、`CAPABILITY_RUNTIME.md`、`PYTHON_AGENT_RUNTIME_BOUNDARY.md`、`AGENT_EVALUATION_MODEL.md`。

## 2. confirmed existing reusable infrastructure

以下均为真实代码证据，可直接复用（不复制、不重写）：

- Capability 平台：`backend/src/main/java/com/specagent/capability/CapabilityDescriptor.java`（record：capabilityId/version/description/inputSchema/outputSchema/readOnly/sideEffectClass/requiredPermissions/supports）；`CapabilityAdapter.java`（descriptor()+invoke()）；`InternalCapabilityAdapter.java`（Java 内部工具 marker）；`CapabilityRegistry.java`（静态 adapters + 动态 CapabilityProvider SPI，权限过滤 + id 去重 fail-closed，findAdapter 统一执行路径）；`CapabilityVisibilityService.java`（supports 上下文兼容 + CatalogLimits 截断，不做语义排序、不授权限）；`CapabilityRuntime.java`（invoke(invocationKey, capabilityId, projectId, runId, arguments)，claim/replay，RUNNING→IN_PROGRESS 诚实语义）；`CapabilityInvocation.java`（record）；`CapabilityInvocationRepository.java`（claim 用 ON CONFLICT(invocation_key) DO NOTHING 原子认领，complete 回写，findByRunId/findRecentCompleted）；`SideEffectClass.java`（NONE/LOCAL_DURABLE/EXTERNAL_REVERSIBLE/EXTERNAL_IRREVERSIBLE）；`CapabilityQueryContext.java`（grantedPermissions/contextKinds/scopeFacts 纯结构化事实）；`CapabilityCatalogLimits.java`。
- Model/inference：`model/inference/ModelInferenceGateway.java`（complete()，provider-neutral seam）；`ModelInferenceRequest.java`（runId/callType/messages/maxOutputTokens）；`ModelInferenceResponse.java`（content/finishReason/promptTokens/completionTokens，无凭证/原始 payload）；`OpenCodeModelInferenceGateway.java`（frozen OpenCode transport 适配，free-only 模型策略，无 retry/fallback）；`agent/broker/InternalModelInferenceController.java`（/internal/v1/model-inference，runId 绑定 + callType 白名单 + 内部 secret +  sanitized run events，为 GA 复用模板）。
- Project 域：`project/ProjectService.java`（createProject：project+初始 OPEN route+activeRoute，listProjects 为 created_at ASC 确定性语义）；`project/ProjectRepository.java`（save/findById/lockById(FOR UPDATE)/findAll ORDER BY created_at,id）；`api/project/ProjectController.java`（POST/GET /api/v1/projects，controller 只调 service）；`api/project/ProjectRuntimeQueryService.java`（组合 service 的只读视图模板，不得写/调模型/造 snapshot）。
- Run/event 持久化思想（复用设计思想，不复用事件模型）：`agent/runevent/AgentRunEventRepository.java`（append 用 SELECT COALESCE(MAX(sequence),0)+1 原子序号，findByRunId ORDER BY sequence）；migration `V7__agent_run_events.sql`（UNIQUE(run_id,sequence)）；`agent/runtime/RunWorker.java`（claimNext→RUNNING 才执行，trigger 分发）；`agent/AgentRunStatus.java` 现有状态机见 §3（GA 不复用）。
- Eval 基础设施：`backend/src/main/java/com/specagent/eval/`（ScenarioDefinition/ScenarioRunner/ExpectSpec/PropertyCheck/CallBudget/AttemptResult 等，属性/行为断言 + anti-overfitting 多样性）；agent-brain 有独立 `src/spec_agent_brain` + `tests/`（Python 决策引擎侧回归模板）。
- Schema 证据：`V13__capability_invocations.sql`（project_id UUID NOT NULL REFERENCES projects(id)，UNIQUE(invocation_key)）。

## 3. confirmed non-reusable Project Agent infrastructure

以下禁止复用（GA 是 application-level Tool Agent，不是第二个 Project Agent）：

- `STATE_UPDATE → DECISION` 两跳 answer 循环、`AgentInputSnapshot`（anchor/route lineage/canonical Answer/claims）、`AnswerPatch`、route lineage/claims/conflict/decision 图语义、Focus≠Active 但选 Answer 的共享态规则（见 AGENT_STATE_MODEL/MEMORY）。
- `ActionFamily`（CREATE_NODE/UPDATE_NODE/CONNECT_NODE/CREATE_ROUTE/REQUEST_USER_INPUT/RESPOND_TO_USER/INVOKE_CAPABILITY/GENERATE_ARTIFACT/WAIT，见 agent/contract/ActionFamily.java）——GA 不新增业务 action，也不复用这些 family。
- `AgentRunStatus` 现有值（CREATED/RUNNING/CONTEXT_BUILT/MODEL_CALLED/REFLECTED/PERSISTED/COMPLETED/FAILED）与 Project Agent worker 调度语义——GA 自建简化状态机（§9）。
- `AdvisorPolicyEngine` 整套 Graph mutation policy（READ_ONLY/VISIBLE_GRAPH/CONFIRMED_INTENT/DESTRUCTIVE/EXTERNAL，见 agent/policy/AdvisorPolicyEngine.java）——GA Tool policy 分离（§15）。
- Project Agent event/trace 模型（agent_run_events 的 phase/event_type 语义）——GA 自建 public event contract，仅复用序号思想（§10）。
- Python Project Brain 的 STATE_UPDATE/DECISION loop——GA 建专用 Brain（§14）。

## 4. application-scoped Capability solution (Contract A)

Freeze 决策：小幅泛化现有 Capability Runtime，不建 GlobalToolRuntime，不绕过、不伪造 projectId、不建 dummy project。

- 现状证据：`V13` 中 `capability_invocations.project_id NOT NULL REFERENCES projects(id)`；`CapabilityRuntime.invoke(..., UUID projectId, ...)` 必传；`CapabilityInvocationRepository.claim` 直接插入 project_id。
- V1 需求：`project.create` 执行前无目标 projectId；`project.search/list_recent` 为应用级上下文执行。
- 冻结方案（只冻结不实现）：新增 migration（如 V28）`ALTER TABLE capability_invocations ALTER COLUMN project_id DROP NOT NULL`（FK 保留，nullable 后仍引用合法 project）；Java 新增显式入口如 `invokeApplicationScoped(invocationKey, capabilityId, runId, arguments)`（projectId=null），现有 `invoke(...)` 保持兼容（传 null 即 fail-closed 或转调新入口，由实现时选定，但行为必须文档化）；application-scoped 调用仍走同一 registry→adapter→claim/complete/replay 路径，idempotency 仍由 `invocation_key` 唯一索引仲裁。
- 兼容性：现有 project-scoped 调用方零改动；`findRecentCompleted(projectId)` 等按 project 过滤的方法对 null 不返回（SQL 语义）；新增按 runId 查询即可覆盖 GA 观测需求。
- 回归要求：保留现有 CapabilityRuntime claim/replay 测试；新增 nullable 列 + 双入口兼容测试 + project.create 无 projectId 端到端（slice 级）测试。

## 5. Global tool catalog isolation (Contract B)

Freeze 决策：显式白名单 + 现有平台复用，不复制 Registry/Runtime。

```text
GlobalAssistantToolCatalog → existing CapabilityRegistry → existing CapabilityRuntime
```

- V1 Server Tool 白名单：`project.create/search/list_recent/get_summary`。不得自动继承 `skill.* / mcp.* / connection.*` 及 Project Agent planner catalog。
- `GlobalAssistantToolCatalog` 为产品能力边界（非自然语言硬编码）：显式定义 V1 Tool ID 集合，向模型仅暴露这 4 个 descriptor。
- 现有隔离机制证据：`CapabilityRegistry.descriptorsFor(context)` 已做 requiredPermissions 过滤 + 跨 provider 去重 fail-closed；`CapabilityVisibilityService` 做 supports 兼容 + 截断。GA catalog 在此之上再做白名单过滤。
- Defense-in-depth：冻结时预留结构化 scope marker（如 `APPLICATION:GLOBAL_ASSISTANT`），实现时二选一：(a) requiredPermissions 增 GA 专用 grant；(b) supports/scopeFacts 增应用域标记。必须双向保证：Global tools 不进入 Project Agent planner catalog；Skill/MCP tools 不进入 GA catalog。以 registry 单测 + visibility 单测锁定。

## 6. ui.navigate ownership (Contract C)

Freeze 决策：`ui.navigate` 只有一个语义身份——typed Client Action，不是 Capability tool。

- Server Host Function Tools：`project.create/search/list_recent/get_summary`（走 CapabilityRuntime + invocation 持久化）。
- `ui.navigate`：模型输出 `GlobalAssistantDecision.uiAction`，Runtime 只允许 enum destination `PROJECT/PROJECTS/SKILLS/CONNECTIONS/SETTINGS` + 合法 resourceId；Runtime emit `UI_ACTION`，Frontend 执行导航。
- 禁止：模型生成任意 URL；`ui.navigate` 进入 CapabilityRuntime invocation 持久化；同时实现 Capability tool + Decision.uiAction 双身份。
- 与现有 router/API 无冲突：Project API 仍是 REST（ProjectController），GA 不改变其语义；UI_ACTION 为 GA 公有事件新增类型，不复用 Project Agent 内部事件。

## 7. persistence schema freeze (Contract D + plan §8 对齐)

Freeze 决策：plan §8 的 4 表为基础，按 Phase 0 §7 补齐 working_state/summary 版本化（plan 偏简，Phase 0 为准，不视为冲突）。

```text
global_assistant_threads(id, summary, summary_version, working_state JSONB, working_state_version, created_at, updated_at)
global_assistant_messages(id, thread_id FK, role USER|ASSISTANT, content, created_at, run_id nullable)
global_assistant_runs(id, thread_id FK, status, step_count, started_at, completed_at, model_provider, model_name, prompt_version, context_projection_version, error_code nullable)
global_assistant_run_events(id, run_id FK, sequence, type, payload JSONB sanitized, created_at, UNIQUE(run_id, sequence))
```

- Working State 只存跨 Run 未完成任务的 bounded structured facts（如 goal/candidateProjects/waitingFor/lastResolvedProjectId/lastToolResultRefs）；禁止 hidden reasoning/CoT/完整 Tool dump/完整历史；stepCount 属于 Run 不属于 WorkingState。
- 更新 owner：Working State 由 Runtime 在 tool observation/decision 边界更新；summary 由 Runtime 在阈值触发时经 ModelInferenceGateway（callType=GLOBAL_ASSISTANT_SUMMARY）更新；模型只建议不直写。版本策略：乐观锁 version 递增，冲突 fail-closed 重读；bounded limits（大小 + 字段白名单），超限截断失败而非静默丢弃。
- messages 不存 CoT；ASSISTANT 文本只存最终 sanitized 文本；Tool 明细以 invocation + run_events 为准。

## 8. thread/run concurrency freeze (Contract E)

Freeze：V1 单 Thread 同时最多 1 个 active run。`CREATED/RUNNING` 为 active；建第二个 run 返回 `HTTP 409 GLOBAL_ASSISTANT_RUN_ACTIVE`。
实现方案：以 `global_assistant_runs(thread_id, status)` 部分唯一索引或事务内 SELECT FOR UPDATE 串行化建 run（类似 ProjectRepository.lockById + AgentRun claim 思想），禁止 parallel turns/branching/race-reconciliation。取消/完成才释放。

## 9. run lifecycle (Contract F)

Freeze 状态机：

```text
CREATED → RUNNING → COMPLETED | FAILED | CANCELLED
```

- `USER_INPUT_REQUIRED` 不是持久 status：emit 事件 + persist WorkingState + 正常 terminalize 当前 run；下一条 USER message 建新 Run。
- 顺序冻结：USER message 先 persist → 建 run 行（CREATED）→ emit RUN_STARTED（RUNNING）→ … → ASSISTANT message persist → emit RUN_COMPLETED/FAILED/CANCELLED。禁止模糊 terminalization。

## 10. SSE cursor/reconnect contract (Contract G)

Freeze：V1 public run events 必须持久化（plan §8 的“if reasonable”作废）。

- internal event id = UUID；public SSE id = per-run sequence（monotonic，UNIQUE(run_id,sequence)，复用 AgentRunEventRepository 原子序号思想但新表新类型）。
- Reconnect：`Last-Event-ID = last sequence → replay sequence > cursor → tail live`。
- `STREAM_DISCONNECTED` 仅 transport 条件，不得作为 terminal failure；Run 可继续。

## 11. read API contract (Contract H)

Freeze 最小读契约（补 plan §15 只有写/流的缺口）：

```text
POST /api/v1/global-assistant/threads
POST /api/v1/global-assistant/threads/{threadId}/runs
GET  /api/v1/global-assistant/runs/{runId}/events (SSE, Last-Event-ID)
POST /api/v1/global-assistant/runs/{runId}/cancel
GET  /api/v1/global-assistant/threads/{threadId}
GET  /api/v1/global-assistant/threads/{threadId}/messages
GET  /api/v1/global-assistant/runs/{runId}
```

满足：refresh 恢复消息 + 当前 run terminal/active 状态 + 重连 event stream。允许合并 endpoint，但三能力缺一不可。

## 12. GlobalAssistantContext contract

Freeze（对应 plan §6，排除 Project Agent lineage 语义）：

```text
GlobalAssistantContext { currentRequest, recentConversation(window), conversationSummary?, uiContext{currentPage, selectedEntity}, workingState?, recentProjectHints?, toolDescriptors }
```

- recentProjectHints 仅提示非真理；canonical 真值一律走 Tool/DB（plan §7 canonical-state rule）。
- Tool descriptors 走 §5 白名单；context 投影版本化（context_projection_version + tool_catalog_fingerprint，随 run 持久）。

## 13. GlobalAssistantDecision contract

Freeze（plan §12，严格 JSON）：

```text
GlobalAssistantDecision { assistantText?, statusText?, toolRequest?{capabilityId, arguments}, uiAction?{destination enum, resourceId?}, requiresUserInput?, done }
```

- 每 decision 最多 1 个 primary Tool；无并行调用；parser+validator fail-closed（未知 capabilityId/非法 UI destination/伪造 ID 即拒）。
- No-progress 结构化规则：same capabilityId + canonicalized same arguments + 无新 observation/state → 停（§21 同）。
- 禁止 message.contains/regex 同义词/中英短语表/benchmark 分支/个案特例；语义归模型，schema/ID/权限/side-effect/幂等/step/runstate/顺序/cancel/UI 校验归 Runtime。

## 14. model/inference path (Contract I/J)

Freeze：

```text
GlobalAssistantBrain → GlobalAssistantPromptRenderer → ModelInferenceGateway.complete(...) → GlobalAssistantDecisionParser → GlobalAssistantDecisionValidator
```

- V1 只用现有 provider-neutral `ModelInferenceGateway`；不改 Provider public contract；不新增第二套 OpenCode client/settings/credential/retry（复用 OpenCodeModelInferenceGateway + InternalModelInferenceController 安全模式）。
- 禁止塞入 Project Agent DECISION prompt / Python STATE_UPDATE loop。
- Provider-native Function Calling：V1 DEFER（Contract J）。当前 gateway 为 message→completion seam；结构化 JSON decision 足够；待真实评测证明不足再单开 slice。

## 15. Tool policy (Contract L + read model)

- GA 不复用 AdvisorPolicyEngine 整套 Graph policy。V1 `project.create` 为 LOCAL_DURABLE/additive/non-destructive/idempotent → 无需二次确认（模型决定即 Runtime 执行）；`APPROVAL_REQUIRED` 保留协议能力但 V1 catalog 不产生。
- Read model（plan §9.2-9.4）：`project.create→ProjectService.createProject`；`search/list_recent/get_summary` 建专用 query/read-model service（如 GlobalProjectSummaryQueryService），组合现有 service；不得改 `listProjects()` 的 created_at ASC 全局语义；list_recent 用 updated_at DESC 新查询。
- Search 允许：normalized title、lexical tokenization、exact/prefix/substring、updatedAt tie-break、bounded count、Unicode-safe；禁止：个案权重（支付/邮件/打开/之前那个）、synonym/benchmark 短语表。调不调用由模型定，调用后检索排序确定性。

## 16. cancellation (Phase 0 §20)

Freeze cooperative cancellation：cancel requested → 下个 model/tool step 前停；已进入同步 Provider call 不承诺硬中断；已成功 durable Tool 不回滚；最终 CANCELLED，不伪装 rollback。

## 17. rolling summary (Phase 0 §19)

Freeze：保留 Rolling Summary 但不每轮总结。recent window + refresh threshold 均可配置；summary 经同一 ModelInferenceGateway（callType=GLOBAL_ASSISTANT_SUMMARY）；只存 goals/unresolved refs/resolved identity/重要 tool outcomes；禁 private reasoning/status copy/tool-card 文案。

## 18. evaluation architecture

Freeze GA-specific eval（归 `com.specagent.globalassistant/eval`），复用现有 eval 方法论（AGENT_EVALUATION_MODEL：groundedness/unknown/conflict/action/stability/alignment/latency + scenario families + anti-overfitting 扰动 + safety 拒绝伪造 ID/绕过 policy/历史插入/副作用），不复用 Project Agent 的 graph 断言语义。
核心场景（plan §22）：create/search/resolved-navigation/summary/ambiguity + negative controls（skill/mcp 不泄漏、伪造 ID 拒绝、任意 URL 拒绝、并发 409）；metrics：tool 正确率/步骤数/重连恢复率/延迟。

## 19. planned migration numbers/files

- 新 migration（编号按当时最新顺延，当前最大 V27）：`V28__global_assistant_threads.sql`（threads+working_state/messages/runs/run_events，含 UNIQUE(run_id,sequence) + active-run 部分唯一约束）; `V29__capability_invocations_application_scope.sql`（project_id DROP NOT NULL）；如需 marker 则 `V30__global_assistant_scope_marker.sql`（permissions/supports 种子或列，视 §5 实现选择而定）。
- 新 Java 包（高内聚低耦合，见 §20）：`com.specagent.globalassistant/{api,conversation,context,model,runtime,stream,tool,eval}`；tool 层只调 ProjectService/read models，不直连 repository；runtime 只调 Brain 不碰 OpenCode transport；brain 只调 ModelInferenceGateway。

## 20. implementation dependency order

```text
Slice A contracts+persistence → Slice B host tools → Slice C context builder → Slice D brain/adapter → Slice E bounded loop → Slice F SSE → Slice G eval/acceptance
```

对应 plan §23 A–G；SSE 协议先冻（B/D 可先发 status/tool 事件，文本先整段 DELTA）。

## 21. explicit deferred items

```text
GlobalAssistantController/Runtime/adapters/migrations/SSE/Frontend/MCP/Skill/Multi-Agent/Handoff/Web/filesystem/shell/native function calling/project.rename-archive-delete
```

本轮仅 audit+freeze+baseline；任何 spike 不进 production。

## 22. acceptance checklist for Phase 1+

- [ ] 只看 plan + 本文件即可实现，不误用 Project Agent/复制 Capability/污染 Provider。
- [ ] §4–§11 每项有对应 migration + 单测 + 回归（现有 Capability/Model/Project 行为零回归）。
- [ ] catalog 双向隔离有单测；ui.navigate 非 capability 有单测；并发 409 有并发测；SSE replay 有断线重连测；refresh 读 API 有恢复测。
- [ ] search 无硬编码短语（扰动测试过）；no-progress 为结构化规则；cancel/crash 语义按 §16。
- [ ] 评测按 §18 全绿且延迟/step 达标。

## Appendix — key file evidence index

- capability: CapabilityRuntime/Registry/Descriptor/Adapter/InternalCapabilityAdapter/VisibilityService/QueryContext/CatalogLimits/Invocation/Repository/SideEffectClass（见 §2 路径）。
- model: ModelInferenceGateway/Request/Response/OpenCodeModelInferenceGateway，broker InternalModelInferenceController。
- project: ProjectService/Repository，ProjectController，ProjectRuntimeQueryService。
- run/event: runevent/AgentRunEventRepository，runtime/RunWorker，migration V7/V13。
- docs: AGENT.md（分支授权），docs/v2/README + 7 canonical，GLOBAL_ASSISTANT_IMPLEMENTATION_PLAN.md（1467 行基线）。

## Appendix B — Phase 0 baseline verification (2026-09-09)

- command: `cd backend; ./gradlew test --console=plain --rerun-tasks` → BUILD SUCCESSFUL (1m34s); tests=976, failures=0, errors=0, skipped=2 (173 suites). 含 model/inference 相关单测（随全量套件执行，未单独排除；EvalLive* 按构建脚本默认排除，无 credential 伪造）。
- command: `cd agent-brain; ./.venv/Scripts/python.exe -m pytest tests -q` → 94 passed, 1 warning (httpx/starlette deprecation, 非失败)。
- environment: Java 21.0.10, Gradle 8.9, postgres testcontainers, docker 可用；无 real-model credential 依赖的 PASS 伪造。
- production behavior: 本轮仅新增本文件（docs），无生产代码变更。

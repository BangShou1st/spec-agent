# P2-A：权限入口清单与迁移方案冻结

日期：2026-10-12。状态：**只读盘点 + 方案冻结（未实施）**。对应 [第二阶段身份、组织、项目权限与凭证隔离设计](PHASE2_IDENTITY_AUTHORIZATION_DESIGN.md) §13 第 1 批。

本文档是 P2-A 的交付物：冻结授权判定协议与锁顺序，枚举全部 HTTP 入口、内部 RPC、导出、检索、工具、缓存与仓储无范围查询，给出归属推导规则与数据迁移方案。**只读盘点 + 方案冻结；未修改任何业务代码、配置或既有文档。**

盘点基线：`main` 分支工作区（含第一阶段未提交改动），Flyway 最新迁移 `V58__execution_mode_admission.sql`，后端 Spring Boot 3.3.2 / Java 21。

设计 §13 要求 P2-A 的退出条件是「所有 controller、RPC、导出、检索、工具、缓存都有归属和校验点」。本文档 §3–§6 逐项给出归属来源与所需能力；**§9 给出盘点中发现的、今天即存在的具体漏点**（已在代码中定位到文件与方法，不是理论风险）。

---

## 1. 冻结的授权判定协议

### 1.1 判定表达式（唯一形式）

一次受保护操作放行，当且仅当下列条件**同时**成立：

1. **身份**：已验证会话（或已验证的服务身份），且 `app_users.status` 为启用；
2. **主体上下文**：`ActorContext{userId, orgId, source}` 来自**持久化事实**，不来自请求参数、模型输出或线程本地；
3. **组织成员资格**：`organization_members(org_id, user_id)` 存在且 `status` 有效；
4. **资源归属**：目标资源经**父关系链**解析出的 `org_id` 与上下文一致；
5. **能力**：`AccessDecision` 允许该 `(角色 → 能力)` 映射下的目标动作；
6. **执行授权**（仅执行路径）：当前执行 token 有效 **AND** 动作符合 Runtime 业务规则。

第 1–5 条对**读与写一律成立**；第 6 条叠加在写/执行路径之上。设计 §7 的「三个同时成立」在此具化为 1–6 的合取。**缺少任何一条即拒绝，不存在降级放行。**

### 1.2 三类主体（互不降级）

| 主体 | 归属来源 | 禁止事项 |
|---|---|---|
| 用户委托任务 | 命令事务写入的 `ActorContext` 快照（user + org + 来源） | Worker 内部认证**不得**提升用户权限；Brain 不得自称属于某用户 |
| 系统派生任务 | 显式窄服务权限（如 `SYSTEM_INDEXING`、`SYSTEM_INDEXING` 类）+ 已登记来源 | 无 `user` 字段**不得**被当作超级管理员 |
| 浏览器请求 | 已验证会话 | 请求里的 `orgId` 只是**选择条件**，不是授权依据 |

### 1.3 授权范围行与版本

新增 `authorization_scopes(scope_key, version)` 作为撤权与提交之间的串行化点。冻结的范围键命名（与 P1 执行范围键**分表**，避免与 `runtime_task_scopes` 语义混淆）：

| 族 | scope_key | 保护对象 |
|---|---|---|
| 组织 | `authz:org:{orgId}` | 组织成员增删、组织禁用、组织级凭证轮换 |
| 项目 | `authz:project:{projectId}` | 项目成员增删、项目删除、连接绑定变更 |
| 会话 | `authz:thread:{threadId}` | GA 会话 owner 变更、会话删除 |
| 凭证 | `authz:credential:{credentialId}` | 凭证停用、轮换、owner 变更 |
| 用户 | `authz:user:{userId}` | 用户禁用（影响其全部组织资格） |

**排序规则**：同一事务内需要多把授权锁时，一律按 `scope_key` 升序，与 P1 的执行范围锁规则同构。

### 1.4 合并后的锁顺序（冻结，不可颠倒）

P1 冻结的顺序是：

```text
task → 容量槽（slot_key 升序）→ execution scope（scope_key 升序）→ 业务 Run → 业务行
```

P2 在 **execution scope 与业务 Run 之间插入 authorization scope**：

```text
task
  → 容量槽（slot_key 升序）
  → execution scope（runtime_task_scopes.scope_key 升序）
  → authorization scope（authorization_scopes.scope_key 升序）
  → 业务 Run
  → 业务行
```

不涉及的锁可以跳过。**为什么插在这个位置**：授权检查必须在写业务行之前；若放在业务 Run 之后，撤权与提交的先后就由业务行锁决定，而业务行锁粒度不覆盖「组织级撤权」——一个组织下存在多个项目与会话，组织级事实变更无法靠单行业务锁串行化。

### 1.5 撤权与提交的提交顺序语义

- **执行事务**：对相关 `authorization_scopes` 取**共享锁**（`FOR SHARE`）并读取最新成员/状态；授权锁持有到事务结束。执行期间 HTTP 不在锁内。
- **管理撤权事务**：只对 `authorization_scopes` 及授权事实取**排他锁**，更新事实与 `version`；**不在持锁时取消 task**。
- 撤权提交后写**持久化取消请求 / outbox**，由控制面按 P1 的 task 锁顺序处理。这解决了 P1-A §3.6 遗留的「项目/会话删除当前是 409 拒绝、不是取消协议」，且**避免 `authorization → task` 与提交链形成锁环**。
- 语义结论：写结果先拿到有效授权锁 ⇒ 该短事务可先提交，撤权随后完成；撤权先提交 ⇒ 后续检查拒绝。
- **明确不接受的替代品**：一次普通 `SELECT`、TTL 缓存、创建任务时的角色快照。

---

## 2. 数据表落地

### 2.1 新增表（7 张，设计 §4.1）

`app_users`、`user_identities`、`organizations`、`organization_members`、`project_members`、`authorization_scopes`、`security_audit_events`。

关键约束（冻结）：

- `user_identities` 唯一 `(issuer, subject)`；**不以邮箱作为身份主键**。
- `project_members` 必须能推出项目所属组织（复合外键或受约束的写入路径），禁止跨组织授予。
- `organization_members` 唯一 `(org_id, user_id)`；最后一个 `OWNER` 不允许退出或降级。
- `authorization_scopes` 全部相关行在 P2-B 迁移中预建（可懒建，但必须可排序、可加锁）。
- `security_audit_events` 不含正文与密钥，只记 actor / org / action / resource / outcome / correlationId / 时间。
- 邀请由管理员签发，单次使用、有截止时间，**只保存邀请 token 的 hash**。

### 2.2 既有表归属改造：按归属推导方式分三类

**A 类 — 直接新增 `org_id`（视情况加 `created_by` / `owner_user_id`）**

| 表 | 来源迁移 | 新增列 |
|---|---|---|
| `projects` | V2 | `org_id`、`created_by` |
| `global_assistant_threads` | V28 | `org_id`、`owner_user_id` |
| `connections` / `connection_credentials` | V27 | `org_id`、`owner_kind`、`owner_user_id`、可见性 |
| `skills` / `skill_staged_imports` / `skill_activations` | V26 / V86–V87 | `org_id`；版本、包文件、暂存文件经受约束父关系推导。builtin 字节可共享，但组织安装/启用不得全局可写 |
| `model_providers` / `model_provider_settings` / `opencode_settings` / `openrouter_settings` / `custom_provider_settings` | V37、V32、V5、V33、V34 | `org_id` |
| `search_settings` / `embedding_service_settings` / `embedding_service_revisions` / `embedding_probes` / `embedding_rebuilds` | V53 | `org_id` |
| `embedding_profiles` → `embedding_profile_registrations` | V53 / V75 | 语义内容哈希 registry 保留；批准/配置关系带 `org_id` + 同组织修订 FK（实施修正，见第二阶段设计 §4.2） |
| `provider_credentials` | V3 → V5 删除 | **2026-10-14 核实修正：不是现存改造对象**。V5 显式 DROP，Java 无调用，隔离 PG `to_regclass` 为 NULL；实际模型密钥在组织级 provider/settings 路径处理，不能新建同名废表冒充修复 |
| `runtime_tasks` | V55 | `org_id`、`actor_user_id`、`authorization_context_ref` |

**B 类 — 不加列，靠受约束父关系推导（关键查询必须 join 授权父资源）**

- 经 `projects`：`routes`、`nodes`、`answers`、`answer_patches`、`spec_snapshots`、`context_snapshots`、`node_relations`、`graph_operations`、`route_inherited_answers`、`agent_proposals`、`agent_runs`、`agent_run_events`、`agent_input_projections`、`capability_invocations`、`agent_run_continuation_checks`、`profiles`（待确认，见 §11-3）。
- 经 `global_assistant_threads`：`global_assistant_messages`、`global_assistant_runs`、`global_assistant_run_events`、`global_assistant_pending_turns`、`ga_executions`、`ga_execution_calls`、`ga_checkpoint_heads`、`ga_checkpoints`、`ga_checkpoint_writes`、`ga_execution_events`。

必要时可冗余 `org_id`，但必须用**复合外键**防止跨组织关联（设计 §4.2）。

**C 类 — 需新增组织/服务归属与生成代次**

- `retrieval_entries`(V40)、`retrieval_index_generations` / `retrieval_index_heads` / `retrieval_index_jobs` / `retrieval_scope_grants`(V49)、`retrieval_source_projections`(V50)：来源可见性 + 后台索引服务身份。
- `mcp_discovery_cache`(V27)：缓存键必须含组织与凭证版本。
- `executor_ownership`(V42) / `runtime_execution_mode`(V58)：**不改语义**，但身份模式与安全协议版本需在此登记（见 §2.4）。

### 2.3 迁移顺序（冻结三步，禁止一步到 NOT NULL）

1. **加列 + 回填**：`ADD COLUMN ... NULL`，按 §2.5 归属规则回填。
2. **校验**：每个待收紧列跑一次「0 行不满足」查询；不通过则不进入下一步。
3. **收紧**：`SET NOT NULL` + 外键 + 复合唯一 / 复合外键（防跨组织关联）。

**迁移号策略**：实现时分配，**不预占 V58 之后的序号**。建议 P2-B 起从 **V59** 顺延，每批 1–3 个迁移文件，命名前缀 `V{n}__identity_*` / `__org_scope_*` / `__credential_owner_*`。最终生产路径不接受 `null` org/actor（设计 §11 末句）。

### 2.4 模式准入与旧执行器隔离（复用 P1 成果）

- 身份模式登记：`LOCAL | OIDC` + **安全协议版本**；同库实例必须一致，模式与协议版本在数据库范围登记。
- **缺配置拒绝多人模式启动，不自动回退 LOCAL**；`OIDC` 模式下禁止本地身份绕过登录。
- 旧执行器隔离沿用 P1 的 admission 锁协议（`V58`）。**旧 API-only 进程无法靠数据库登记表约束**——部署准入 + 网络入口移除 + 全部旧进程停机是迁移前提（设计 §11）。

### 2.5 存量归属规则（冻结，禁止自动分配）

| 存量对象 | 归属 | 约束 |
|---|---|---|
| 存量项目 | 显式 `legacy` 组织 | 管理员离线审核映射；**不得分配给第一个 OIDC 登录者** |
| 存量 GA 会话 | 显式 `legacy` 用户 | 同上；会话默认私有 |
| 现有模型 / MCP 密钥 | 仅迁入 `legacy` 组织 | **不复制到每个新组织** |
| 孤立引用或无法判断归属的来源 | 隔离清单 | 不默认公开 |

---

## 3. HTTP 入口清单

约定：**归属来源** = 解析资源 `org_id` 的依据；**能力** = 需要的能力代码；**批次** = 首次落校验的批次。

能力代码（设计 §5）：`PROJECT_READ`、`PROJECT_WRITE`、`PROJECT_MANAGE`、`CREDENTIAL_USE`、`CREDENTIAL_MANAGE`、`ORG_MANAGE`、`ORG_OWNER`、`THREAD_OWNER`。

### 3.1 workspace 模块（35 个端点）

| 入口 | 方法 / 路径 | 归属来源 | 能力 | 批次 |
|---|---|---|---|---|
| `ProjectController` | `POST /api/v1/projects` | 创建者 org（会话上下文） | 组织成员 | P2-C |
| | `GET /api/v1/projects` | **成员资格过滤（当前为全库，§9-G1）** | PROJECT_READ | P2-C |
| | `GET /api/v1/projects/{projectId}` | `projects.org_id` | PROJECT_READ | P2-C |
| | `GET /api/v1/projects/{projectId}/active` | 同上 | PROJECT_READ | P2-C |
| | `PUT /api/v1/projects/{projectId}/title` | 同上 | PROJECT_MANAGE † | P2-C |
| | `DELETE /api/v1/projects/{projectId}` | 同上 | PROJECT_MANAGE | P2-C |
| `GraphCommandController` | `GET /relations`、`GET /graph-operations`、`GET /graph-operations/availability` | 同项目 | PROJECT_READ | P2-C |
| | `POST /nodes`、`/floating-nodes`、`/nodes/{nodeId}/connect`、`/disconnect`、`/continuation`、`PATCH /nodes/{nodeId}/draft`、`POST /nodes/{nodeId}/knowledge-status`、`POST /resources`、`POST /relations` | 同项目 | PROJECT_WRITE | P2-C |
| | `POST /graph-operations/undo`、`/redo` | 同项目 | PROJECT_WRITE | P2-C |
| `GraphWorkspaceController` | `GET /api/v1/projects/{projectId}/graph` | 同项目 | PROJECT_READ | P2-C |
| `RouteCommandController` | `POST /routes/{routeId}/activate\|archive\|restore\|delete`、`POST /nodes/{nodeId}/fork` | 经 route / node → project | PROJECT_WRITE | P2-C |
| | `POST /nodes/{nodeId}/reanswer`、`/start-route` | 同上（**会创建 Run**） | PROJECT_WRITE + CREDENTIAL_USE | P2-C/D |
| `RouteController` | `GET /api/v1/projects/{projectId}/routes` | 同项目 | PROJECT_READ | P2-C |
| `RouteLineageController` | `GET .../routes/{routeId}/lineage` | 经 route → project | PROJECT_READ | P2-C |
| `RequirementStateController` | `GET .../requirement-state` | 同项目 | PROJECT_READ | P2-C |
| `RouteRequirementStateController` | `GET .../routes/{routeId}/requirement-state` | 经 route → project | PROJECT_READ | P2-C |
| `SpecController` | `GET /api/v1/specs/{snapshotId}` | **需由 snapshot → project → org 反向推导** | PROJECT_READ | P2-C |
| | `GET /api/v1/specs/{snapshotId}/export.md` | 同上（**唯一导出面**） | PROJECT_READ | P2-C |
| | `GET /api/v1/projects/{projectId}/routes/{routeId}/specs` | 已有嵌套一致性检查，仅缺成员校验 | PROJECT_READ | P2-C |

† 重命名项目所需能力未在能力矩阵中显式定义。**建议**归 `PROJECT_MANAGE`（与成员管理同权），因为标题是对外可见的项目标识。若产品上允许 EDITOR 改标题，需在能力矩阵中**显式补一行**，不靠实现者默认。

### 3.2 agent 模块（13 个端点）

| 入口 | 方法 / 路径 | 归属来源 | 能力 | 批次 |
|---|---|---|---|---|
| `AgentRunController` | `GET /api/v1/projects/{projectId}/runs`、`/{runId}` | 经 run → project | PROJECT_READ | P2-C |
| `AnswerCycleRunController` | `POST /api/v1/projects/{projectId}/agent-runs` | 同项目 | PROJECT_WRITE + CREDENTIAL_USE | P2-C/D |
| | `GET /{runId}`、`/active` | 经 run → project | PROJECT_READ | P2-C |
| `NodeQueryRunController` | `POST /api/v1/projects/{projectId}/nodes/{nodeId}/query` | **需校验 node 属于 project** | PROJECT_WRITE | P2-C |
| | `GET /{runId}` | 经 run → project | PROJECT_READ | P2-C |
| `AgentRunRecoveryController` | `GET /unresolved`、`POST /{runId}/retry` | 经 run → project | PROJECT_WRITE | P2-D（撤权后必须拒绝） |
| `AgentProposalController` | `GET /api/v1/projects/{projectId}/proposals` | 同项目 | PROJECT_READ | P2-C |
| | `POST /api/v1/proposals/{proposalId}/accept` | 经 proposal → project | PROJECT_WRITE | **P2-D**（§9-G7） |
| | `POST /api/v1/proposals/{proposalId}/reject` | 同上 | PROJECT_WRITE | P2-D |

### 3.3 assistant（全局助手）模块（23 个端点）

| 入口 | 方法 / 路径 | 归属来源 | 能力 | 批次 |
|---|---|---|---|---|
| `GlobalAssistantController` | `POST /api/v1/global-assistant/threads` | 创建者 org + **owner = 当前用户** | 组织成员 | P2-C |
| | `GET /threads` | **owner 过滤（当前全库，§9-G2）** | THREAD_OWNER | P2-C |
| | `GET /threads/{threadId}`、`/messages`、`/activity` | `threads.owner_user_id` | THREAD_OWNER | P2-C |
| | `POST /threads/{threadId}/runs` | 同上 | THREAD_OWNER + CREDENTIAL_USE | P2-C/D |
| | `POST /threads/{threadId}/stop`、`DELETE /threads/{threadId}` | 同上 | THREAD_OWNER | P2-C |
| | `GET /runs/{runId}` | 经 run → thread | THREAD_OWNER | P2-C |
| | `GET /runs/{runId}/events`（JSON 补发 **与** SSE） | 同上 + **周期授权复查** | THREAD_OWNER | P2-C/D |
| | `POST /runs/{runId}/cancel`、`/steer` | 同上 + CSRF | THREAD_OWNER | P2-C |
| `GaToolsController` | `GET /api/v1/global-assistant/tools` | 按当前用户能力**投影目录** | THREAD_OWNER | P2-D |
| `SearchSettingsController` | `GET` / `PUT` / `DELETE` / `POST /test`（`/api/v1/settings/search`） | **现为全局单例 → 迁组织配置** | ORG_MANAGE + CREDENTIAL_MANAGE | P2-E |

### 3.4 connection / skill / modelsettings / retrieval（71 个端点）

| 入口 | 归属来源 | 能力 | 批次 |
|---|---|---|---|
| `ConnectionController` 全部 14 个（`GET ""`、`GET /{id}`、`POST ""`、`PATCH /{id}`、`POST /{id}/test\|connect\|refresh\|enable\|disable`、`DELETE /{id}`、`GET /{id}/tools\|resources\|prompts`、`GET /{id}/resources/read`） | `connections.org_id` + `owner_kind` | 读：可见性；写：CREDENTIAL_MANAGE | P2-E |
| `SkillController` 全部 16 个（列表/详情/版本/资源/导入 zip+git/暂存/安装/拒绝/启停/删除/资源读取） | `skills.org_id` | 组织成员读；CREDENTIAL_MANAGE 或 ORG_MANAGE 写 | P2-E |
| `ModelProvidersController` 8 个（`GET ""`、`POST ""`、`PUT /{id}`、`DELETE /{id}`、`POST /{id}/activate\|probe\|validate`、`GET /{id}/models`） | `model_providers.org_id` | ORG_MANAGE + CREDENTIAL_MANAGE | P2-E |
| `ModelProviderSettingsController` 2 个（`GET /active`、`POST /activate`） | 同上 | ORG_MANAGE | P2-E |
| `OpenCodeSettingsController` 6 个 / `OpenRouterSettingsController` 5 个 / `CustomProviderSettingsController` 4 个 | 安装级 vs 组织级需**逐条归类** | 见 §9-G11 | P2-E |
| `EmbeddingSettingsController` 8 个（`GET`、`PUT`、`DELETE /credential`、`POST /test`、`POST /rebuild`、`/rebuild/{id}/retry\|activate\|discard`） | `embedding_*` 表 `org_id` | ORG_MANAGE + CREDENTIAL_MANAGE；`rebuild` 还需服务身份 | P2-E |

### 3.5 公开与错误映射边缘（1 个端点 + 4 个 advice）

| 位置 | 处置 |
|---|---|
| `HealthController` `GET /api/health` | 保持公开。**但 `management.endpoint.health.show-details: always` 需在 P2-B 收紧**（§9-G12） |
| `ApiExceptionHandler` / `GatewayErrorAdvice` / `LocalServiceSettingsErrorAdvice` / `SettingsProvidersErrorAdvice` | 统一语义：**资源不可见 → 404；可见但无操作权 → 403；登录失效/用户禁用 → JSON 401**（设计 §6） |
| `ApiException` | 增加 `UNAUTHENTICATED` / `FORBIDDEN` 类型，避免用 400 表达授权失败 |

**端点统计（按 `@*Mapping` 注解计数）**：30 个 `@RestController`，共 **143 个映射方法** = workspace 35 + agent 13 + assistant 23 + connection 14 + skill 16 + modelsettings 25 + retrieval 16 + common 1。全部在 §3.1–§3.5 有归属来源与能力归属。

---

## 4. 内部 RPC 入口清单（服务身份 + 业务授权双绑，14 个端点）

现状：**单一共享 secret + 时间恒定比较**（`X-Spec-Agent-Internal-Token`），只证明「请求来自受信任服务」，**不绑定任何业务授权**。

| 入口（前缀） | 端点 | 现状 | P2 要求 | 批次 |
|---|---|---|---|---|
| `RetrievalStoreController`（`/internal/v1/retrieval-store`） | `GET /health`、`POST /projection-grants`、`POST /index-grants`、`POST /candidates`、`POST /validate-sources` | `authorized(token)` 通过即接受请求中的**任意项目 ID** | 每次检索校验业务授权范围；索引 grant 绑定 job / org / source / generation / deadline | P2-D |
| `EmbeddingBrokerController`（同前缀） | `POST /embedding-profile`、`GET /active-embedding`、`POST /embeddings` | 同上 | 绑定组织与 embedding profile 版本 | P2-E |
| `InternalModelInferenceController`（`/internal/v1/model-inference`） | `POST` | 仅 token | 追加 task token + ActorContext | P2-D |
| `GaHostRpcController`（`/internal/v1/global-assistant`） | `POST /model-inference`、`/model-inference/stream`、`/checkpoints/{operation}`、`/capabilities`、`/execution-claim` | 仅 token | 关键在 **`/execution-claim`**：Brain 认领执行时必须用持久化 `authorization_context_ref` 重建 ActorContext | P2-D |

**冻结规则**：

- 内部 token 的正确性**只**说明来源可信；**绝不**单独构成业务授权。
- 运行中的 RPC 必须同时验证 **task token** 与 **ActorContext**。
- 内部接口**不得**因为 token 正确就接受任意项目 ID。
- 浏览器 cookie 与内部服务链使用**不同安全链**（设计 §6）。

---

## 5. 仓储层无范围查询清单（待收紧）

盘点范围：`**/*Repository.java`，41 个。下表为含 `findById` / `findAll` / `deleteById` 等无范围查询的仓储与命中数。

| 仓储 | 无范围命中 | 风险 | 处置批次 |
|---|---|---|---|
| `ProjectRepository` | 5（`findById`、`findAll`、`existsByTitleIgnoreCase`×2、`findByTitleContaining`） | **高**：`findAll` / `findByTitleContaining` 为全库，直供 GA 工具 | P2-C/D |
| `AgentProposalRepository` | 4 | 高：accept 路径 | P2-D |
| `ConnectionRepository` | 2 | 高：凭证归属 | P2-E |
| `GlobalAssistantThreadRepository` | 2 | 高：会话私有性 | P2-C |
| `GlobalAssistantRunRepository` | 2 | 高：会话私有性 | P2-C |
| `PendingTurnRepository` | 2 | 中 | P2-C |
| `AnswerPatchRepository` | 2 | 中：业务正文 | P2-C |
| `ProfileRepository` | 2 | 中：需确认是否组织级 | P2-C |
| `ModelProviderRepository` | 2 | 中 | P2-E |
| `JdbcModelProviderRepository` | 2 | 中 | P2-E |
| `AgentRunRepository` | 2 | 中：execution **与** authorization 双查 | P2-C/D |
| `NodeRelationRepository`、`NodeRepository`、`AnswerRepository`、`ContextSnapshotRepository`、`SpecSnapshotRepository`、`RouteRepository`、`GraphOperationRepository`、`RuntimeTaskRepository` | 各 1 | 低–中｜均需父链校验 | P2-C/D |
| `GlobalAssistantThreadListRepository` | **自定义 SQL，无 owner/org 谓词**（非 `findById` 族，故未计入上表） | **高**：见 §9-G2 | P2-C |

**冻结规则**：公共接口**禁止**调用无范围 `findById`；一律走「带归属的查找」或「先解析父资源再校验」（设计 §12）。

---

## 6. 检索 / 工具 / 导出 / 缓存 / SSE 清单

### 6.1 检索

| 面 | 现状 | 要求 | 批次 |
|---|---|---|---|
| 候选生成 | `RetrievalStoreController.candidates` 由 grant 驱动；`retrieval_scope_grants` 已有 `epoch` | 过滤必须发生在**候选生成阶段**（org → 可读项目 → 来源可见性），之后再排名；不能先搜全库再隐藏来源标题 | P2-D |
| grant 生成 | Java Host 侧 `SharedRetrievalHost` | grant 不可伪造；Python 不能传「我是管理员」过滤器获取更多数据 | P2-D |
| 组织级全局搜索 | — | 不含无项目权限的私有项目 | P2-D |
| 公共帮助 corpus | `HelpCorpusJobs`、`help.search` | 独立标记，只允许部署提供的可读资料 | P2-D |
| 后台索引 | `retrieval_index_jobs` | 明确 `SYSTEM_INDEXING` 服务身份与来源归属 | P2-D |

### 6.2 工具

`GlobalAssistantToolCatalog` 为显式白名单（**10 个 id**：`project.create`、`project.search`、`project.list_recent`、`project.get_summary`、`skill.import`、`skill.import.discover`、`help.search`、`project.content.discover`、`web.search`、`web.fetch`），并有逐工具的参数允许集合。

每次工具调用必须验证：目标所属组织、用户动作权限、连接绑定、凭证使用权限、Runtime gate；**写入工具的写权限由 Java 固定，模型自称 read-only 不构成证明**。

> **实现建议**：`isAllowed` 当前由 `TOOL_IDS` 常量与内联 `Set.of` 两处拼合，`allowedArguments` 为长 `if` 链。权限阶段建议改为**单一表驱动**的 `(capabilityId → 参数契约 + 读/写 + 所需能力)` 注册表，否则每加一个工具都要改多处精确集合断言（已有此惯例成本）。

### 6.3 导出

- `SpecController.exportSnapshot`（`text/markdown;charset=UTF-8`）是**唯一的导出面**，归属推导见 §3.1。
- 设计 §7 点名「列表 / 详情 / **导出**」为校验点：导出必须走与读**同一条**授权路径，不能因为是「纯视图」而绕过。

### 6.4 缓存

| 缓存 | 位置 | 现状 | P2 处置 |
|---|---|---|---|
| SSE 订阅者表 | `GlobalAssistantStreamService`：`live`、`locks`（**实例本地** `ConcurrentHashMap`） | 进程内 | 撤权关闭连接必须**跨实例**（§9-G8） |
| Semantic trace | `SemanticTraceRecorder.traces`（进程内 `ConcurrentHashMap`） | 无归属 | 评估是否含业务正文；含则纳入 org 隔离 |
| 模型会话 id | `OpenCodeZenSessionIds.RUN_SESSIONS`（静态 `ConcurrentMap`） | 按 runId 键，无 org | 缓存键需含 org / 凭证版本 |
| MCP 发现 | `mcp_discovery_cache` 表 | 未含 org | 缓存键含 org + 凭证版本 |
| 检索 / embedding 代次 | `retrieval_index_generations` / `embedding_service_revisions` | 已版本化 | 版本参与缓存键 |

**冻结的缓存键组成**：`org` + 主体/有效访问集合**版本** + 资源/模型/凭证**版本**。撤权提交后发布**失效标记**，多实例依据数据库 `version` 校验。**跨组织原始 prompt / 结果 / 摘要缓存默认禁用**。

### 6.5 记忆与 checkpoint

- GA 历史 / 摘要 / working state / checkpoint 必须保存 `source` / `project` 依赖与**授权版本**。
- 项目撤权后：**保守隐藏受影响会话与派生记忆，并拒绝旧 checkpoint 续跑**，提示创建新会话；有独立验证过的清理/重建流程后才恢复展示。
- **不得**只过滤下一次检索却继续把旧摘要送模型。
- 依赖记录不全时**隔离整条会话**，不猜测「没有敏感内容」。

---

## 7. 前端入口面

现有 API 客户端（`frontend/src/features/**/api/*.ts`，16 个文件）：`globalAssistant.ts`、`globalAssistantEvents.ts`、`projects.ts`、`workspace.ts`、`graph.ts`、`graphCommands.ts`、`routes.ts`、`spec.ts`、`requirementState.ts`、`agentRuns.ts`、`connections.ts`、`connectionTypes.ts`、`modelProviders.ts`、`modelSettings.ts`、`skills.ts`、`skillTypes.ts`。

P2 要求：

- 新增 `/api/me`、组织切换、成员与项目角色管理、归属清晰的设置页面。
- 统一 401 / 403 / 404 处理与 **CSRF token 支持**（变更类请求：退出、取消、steer、凭证配置等；**不得**把全部 `/api/**` 设为 CSRF 例外）。
- **组织切换是显式路由/请求上下文**，不依赖 session 内一个可变 `orgId`，避免不同标签页串组织；切换后清理对应缓存并关闭旧组织订阅。
- 前端权限字段**只控制显示**；后端不信任按钮状态。
- 改 UI 文案需同步三处：组件/store、`src/**/__tests__`、`e2e/`。

---

## 8. 新增模块与端口

新增中性包 **`com.specagent.access`**（设计 §12）：

| 类型 | 职责 | 批次 |
|---|---|---|
| `ActorContext` | 冻结的（user、org、来源）三元组 | P2-B |
| `AccessDecision` | 判定结果（允许/拒绝 + 原因码，供审计） | P2-B |
| `AuthorizationGuard` | 授权范围锁 + 版本读取 + 判定编排 | P2-B/D |
| `ProjectAccessPort` | 项目归属解析与成员校验 | P2-C |
| `ThreadAccessPort` | GA 会话 owner 与记忆依赖校验 | P2-C/D |
| `CredentialAccessPort` | 凭证归属、`CREDENTIAL_USE`、版本校验 | P2-E |

OIDC / session 适配位于 **security 基础设施**（建议 `com.specagent.access.security` 或独立顶层 `security`）内，业务包**通过端口接入**；**公共 runtime 不依赖 `workspace` / `assistant`**。

落地要点：

- Controller 只做身份解析、CSRF 与参数转换；资源规则在**应用服务**校验——**避免只加几个 `@PreAuthorize` 就漏掉 Worker / RPC**。
- Runtime task：持久化授权引用、检查当前权限、恢复/续跑保持 actor、受控失权终态化。
- **门禁成本（已实测）**：新增顶层包必须同步登记到 `backend/src/test/java/com/specagent/architecture/ArchitectureTests.java`（模块 slice 分配与 `RUNTIME_KERNEL` 等显式包清单）与 `HttpBoundaryGateFixtureTest`。模块级 slice 为**零容忍 + 空匹配守卫**，未登记会直接门禁失败。此项应列入 P2-B 的**第一个提交**，不要等实现完再补。

---

## 9. 盘点发现的实证缺口（现状即漏点）

> 以下每一项都已在当前代码中定位到文件与方法。它们不是「将来可能出问题」，而是**今天单机模式下不构成越权、但多组织部署下立即成为跨组织/跨用户泄露**的路径。P2-A 的价值主要在这里。

**G1 — 项目全库枚举（跨组织）**
`ProjectRepository.findAll()`（`SELECT * FROM projects ORDER BY created_at, id`）与 `findByTitleContaining()` 无任何组织过滤，被 `GlobalProjectSearchService.search()` / `listRecent()` 直接消费，即 GA 的 `project.search` 与 `project.list_recent` 工具。多组织下任一用户可枚举**其他组织**的项目标题。
→ P2-D：过滤器必须落在候选生成阶段；`ProjectRepository` 增加带组织范围的查询方法。

**G2 — GA 会话列表全库返回（跨用户）**
`GlobalAssistantThreadListRepository.listRecent(limit)` 的 SQL 只 join `global_assistant_threads` 与消息表，**没有任何 owner / org 谓词**，返回全库最近会话的**标题与预览**（预览取自最后一条消息正文）。经 `GET /api/v1/global-assistant/threads` 暴露。
→ P2-C：加 owner 过滤。这也是设计 §9「缓存的助手记忆不能继续泄露项目内容」最直接的入口。

**G3 — 规格快照按 ID 直读与导出**
`SpecController.getSnapshot(snapshotId)` 与 `exportSnapshot(snapshotId)` 仅按 `snapshotId` 取，**无父资源一致性校验**；而同一文件内的 `listRouteSpecs` 已做 `route.projectId().equals(projectId)` 检查。ID 猜中即读正文并导出 Markdown。
→ P2-C：复用既有模式补齐 `snapshot → project → org` + 成员校验。

**G4 — 内部服务认证不绑定业务授权**
`RetrievalStoreController.authorized(token)`、`EmbeddingBrokerController`、`InternalModelInferenceController`、`GaHostRpcController` 一律是单一共享 secret + `MessageDigest.isEqual`。通过后 `candidates` 接受请求中任意项目 ID。
→ P2-D：追加 task token + ActorContext。

**G5 — Brain 可认领执行**
`GaHostRpcController` 暴露 `POST /execution-claim`。设计 §4.2 明确「不能由 Brain 声称自己属于某用户」。
→ P2-D：claim 时用持久化 `authorization_context_ref` 重建 ActorContext，而非信任请求负载。

**G6 — 凭证缺失退回匿名**
`McpCredentialResolverAdapter` 以 `credentialRef` 解析，缺失时**退回未认证连接**（设计 §2 已点名）。多组织下这会把手误或跨组织引用变成「合法的公开连接」。
→ P2-E：显式配置的 `anonymous` 与「引用凭证缺失/禁用/跨组织」必须区分；后者**失败，不回退匿名重试**。

**G7 — proposal accept 路径不受执行租约保护**
`ProposalAcceptanceService` 不调用 `ExecutionFence`（P1-A §7 风险 2 已记录），因此既不在 P1 的项目范围协调内，也不在 P2 的授权范围锁内。
→ P2-D：`POST /proposals/{proposalId}/accept` 必须纳入**授权 + 项目范围双重护栏**，并与 `AgentRunRepository.attachApprovalProducedNode`（唯一不带 ownership 守卫的 run 写入）一并处理。

**G8 — SSE 订阅者表是实例本地的**
`GlobalAssistantStreamService` 用 `ConcurrentHashMap<UUID, List<Subscriber>>` 与 `locks` 维护订阅，**仅本进程可见**。P1 已记「跨实例 SSE」属 P1-D；P2 在其上叠加**授权撤销必须跨实例生效**（设计 §6：各实例共享授权事实，不能只发本机退出通知）。
→ P2-C/D：撤销后停止发送并关闭连接，验收初值「既有连接 5 秒内发现撤销」；不承诺收回已发送字节。

**G9 — 会话进程缓存与静态 Map 无归属**
`SemanticTraceRecorder.traces`（进程内）、`OpenCodeZenSessionIds.RUN_SESSIONS`（静态 `ConcurrentMap`）、`mcp_discovery_cache`（表）均无 org 维度。
→ P2-D/E：按 §6.4 的缓存键组成评估；含业务正文者必须参与 org 隔离。

**G10 — 访问控制事实上由回环绑定承担**
`server.address` 默认 `127.0.0.1`；`build.gradle.kts` 无 Spring Security / Spring Session 依赖（仅 web、data-jdbc、validation、postgresql、flyway、actuator、jgit、compress、mcp）。
→ P2-B：引入 Security 时**必须同时**把回环绑定改为显式配置，**不能让回环成为隐式的授权层**——否则多人部署一改绑定即全面敞开。

**G11 — 全局单例设置未区分安装级与组织级**
`search_settings`、`embedding_service_settings`、`model_provider_settings`、`opencode_settings`、`openrouter_settings`、`custom_provider_settings` 均为单例语义。设计 §10 要求「安装级地址、内部 secret、master key 与部署参数仅部署管理员可操作，不暴露为组织管理员 API」。
→ P2-E：逐条归类，不能整表迁为组织配置。

**G12 — 健康端点细节过度暴露**
`management.endpoint.health.show-details: always` + 公开 `GET /api/health`。
→ P2-B：公开端点最小化，细节移入需认证的诊断面。

**G13 — 内部 secret 是单一前提**
`spec.agent.brain.internal-secret` 默认读 `./data/internal-secret.txt`，后端与 Brain **共享同一值**。拿到它即拿到全部内部 RPC。
→ P2-E：仅部署管理员可操作，不得暴露为组织管理员 API。

---

## 10. 明确不在 P2-A 范围

| 项 | 归属 |
|---|---|
| 表结构、OIDC、共享 session、本地模式准入 | P2-B |
| 项目与会话授权、查询隔离、SSE 与缓存 | P2-C |
| Worker、恢复、工具、检索、记忆的动态授权 | P2-D |
| 组织凭证与配置隔离 | P2-E |
| 多身份双实例 / browser 验收、迁移回退、发布演练 | P2-F |
| 旧执行器隔离的部署准入与网络入口移除 | P2-F（前置条件，不因权限建设而放松） |
| 共享供应商 RPM/TPM、计费、完整监控、任意第三方 API token、自动跨组织共享 | 本阶段**不做** |
| PostgreSQL RLS | 后续纵深保护，非首批（需适配事务连接上下文、后台服务身份、表 owner/BYPASSRLS、迁移角色） |
| 基础框架升级 | 另行评估，不与权限迁移强绑定 |
| 容量参数成为承载承诺 | **必须测量后才能声明**（P1 §9/§12 末句，延续） |

---

## 11. 风险与待确认

1. **密钥一致性是硬前置**：需核实 `ModelCredentialCrypto` 当前密钥来源是否存在「缺失即本地生成」的回退。若存在，多人部署会出现「A 实例写的密文 B 实例解不开」。P2-E 必须改为**失败关闭 + 显式配置一致性校验**，并支持密钥 ring。
2. **密文格式迁移不可逆**：新格式以 `credentialId / orgId / purpose` 作 AAD，需逐行重新加密；旧格式只在明确迁移窗口兼容。**回退只能靠备份恢复**，需在 P2-F 演练中验证。
3. **`profiles`(V2) 的组织归属未定**：设计未显式说明。需在 P2-C 确认它是安装级模板还是组织级配置，直接影响 §2.2 的 A/B 分类。
4. **重命名项目的所需能力未在矩阵中定义**（§3.1 †）。需在 P2-C 前明确，否则实现者会隐式决定。
5. **§2.2 B 类的「关键查询强制 join」无法靠数据库强制**，只能靠评审与测试覆盖。建议 P2-C 引入一条 ArchUnit 规则约束 `Repository` 公共方法；但需先设计成**可判定、不误报**的形式，否则会引发大面积 `allowEmptyShould` 式绕过。
6. **`capability_invocations` 的 `RUNNING` 记录永不超时**（P1-A §7 已记）。授权层叠加后，「未授权不得继续读取正文」与「永不超时」会互相放大——需在 P2-D 一并处理。
7. **端点计数口径**：按 `@*Mapping` 注解统计为 143。存在同一路径多 `produces` 变体（如 `GlobalAssistantController` 的 JSON 与 SSE 两个 `GET /runs/{runId}/events` 共用一个路径），实际路由数略低于注解数。以注解数为准，不影响归属与校验点结论。
8. **实现前需确认**（设计 §15）：目标 IdP、初始组织/Owner 映射、目标部署域名与 TLS。这三项可独立准备，**不阻塞 P2-A**。

---

## 12. P2-A 交付确认

| 设计 §13 退出条件 | 覆盖位置 | 状态 |
|---|---|---|
| 所有 controller 有归属和校验点 | §3.1–§3.5 | 完成（30 controller / 143 端点） |
| 所有 RPC 有归属和校验点 | §4 | 完成（4 个内部入口族 / 14 端点） |
| 所有导出有归属和校验点 | §6.3 | 完成（1 处） |
| 所有检索面有归属和校验点 | §6.1 | 完成 |
| 所有工具有归属和校验点 | §6.2 | 完成（10 工具 id） |
| 所有缓存有归属和校验点 | §6.4 | 完成（5 类） |
| 权限矩阵 | 设计 §5（本文档对齐能力代码） | 完成，1 处待补（重命名） |
| 数据迁移方案 | §2 | 完成（三类分类 + 三步收紧 + 号段策略 + 存量规则） |
| 锁顺序冻结 | §1.4–§1.5 | 完成（与 P1 合并，authorization scope 插入位置已定） |

**本轮范围**：只读盘点 + 新增本文档。**未修改任何业务代码、配置或既有文档。**

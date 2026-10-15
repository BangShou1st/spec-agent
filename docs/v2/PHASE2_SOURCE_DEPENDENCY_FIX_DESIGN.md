# P2 来源依赖与撤权隔离：最小修复设计（第一刀）

日期：2026-10-15。分支 `main`，基线 `520a8c728edaa54da3a37a82a2f50c119c4c34a2`。
状态：**复核完成 + 已实现并验证**（第二刀落地；第一刀只交付本设计与复现测试）。

> 实现说明（与本文档初稿的一处差异，如实记录）：初稿建议以
> `capability_invocations.result` 作为依赖账本；实现改用
> `global_assistant_run_events`（`TOOL_COMPLETED.payload.resourceRefs` +
> `UI_ACTION.payload.resourceId`）。理由：`capability_invocations` 只有
> `invocation_key`/`project_id` 索引，按 `thread → runs → invocations` 重建依赖
> 会对一张全库增长表做顺序扫描（列表接口每线程一次，不可接受），而
> `global_assistant_run_events` 有 `idx_ga_run_events_run`。项目粒度的引用不会
> 被展示截断（`projectResources` 上限 10 ≥ 工具 limit 上限 10，content.discover
> 候选上限 4），该不变量已由 `GaSourceDependencyContractTest` 钉死；一旦被破坏即
> 触发停止条件。
>
> 落地收口：`GaSourceDependencyGuard`（单一 SQL 谓词，Java 单线程判定与会话列表
> 候选生成共用）→ 会话列表 SQL、`GlobalAssistantConversationService`
> 读取收口、`GlobalAssistantStreamService.requireAccess`（JSON 事件 + SSE）、
> `GaCheckpointStore.apply`（GET/LIST/PUT/PUT_WRITES，DELETE_THREAD 除外）、
> `GaExecutionPreparation.prepare`（拒绝时以 `GA_SOURCE_REVOKED` 控制面终态化）。
> 验收测试：`RevokedProjectObservationReuseIntegrationTest`（含并发撤权）、
> `GaSourceDependencyContractTest`。

对应文档：[PHASE2_A_PERMISSION_ENTRY_INVENTORY.md](PHASE2_A_PERMISSION_ENTRY_INVENTORY.md) §6.1/§6.5、
[PHASE2_IDENTITY_AUTHORIZATION_DESIGN.md](PHASE2_IDENTITY_AUTHORIZATION_DESIGN.md) §9。

---

## 1. 复现结论（真实 PostgreSQL）

复现测试：`backend/src/test/java/com/specagent/assistant/RevokedProjectObservationReuseIntegrationTest.java`
（隔离库，`@ActiveProfiles("test")`，两组织 / 三用户 / 一个先获授权后被撤角色的项目 / 依赖会话 + 对照会话）。

撤权（`DELETE FROM project_members`）之后实测仍成立：

| 检查项 | 结果 |
| --- | --- |
| 项目读取 `projectDecisionFor(owner, project, PROJECT_READ)` | 已拒绝（`RESOURCE_NOT_VISIBLE`） |
| 组织成员资格 `findMembership` | 仍然有效（ACTIVE） |
| GA 会话属主资格 `requireThreadOwnershipFor` / `findThreadOwned` | 仍然有效 |
| 会话列表预览 `GlobalAssistantThreadListRepository.listRecent` | 仍返回引用该项目的助手正文 |
| 消息详情 `GlobalAssistantConversationService.listMessages` | 仍返回引用该项目的观察正文 |
| checkpoint 读取 `GaCheckpointStore.apply("GET")` | 仍返回包含该项目观察的 checkpoint |
| 后续模型调用 `GaExecutionPreparation.prepare` 装配的 `history` | 仍包含旧项目观察正文 |
| 对照会话（无该项目依赖） | 可读、不受影响、不含该观察 |

根因：GA 的派生记忆（消息正文 / 摘要 / working state / checkpoint）**只受会话属主资格约束**，
没有记录 project / source 依赖，也没有记录依赖的授权版本。因此撤权之后系统无从判定"哪条派生
记忆受影响"，而冻结要求（P2-A §6.5）恰恰是：

- 派生记忆必须保存 `source` / `project` 依赖与授权版本；
- 项目撤权后保守隐藏受影响会话与派生记忆、拒绝旧 checkpoint 续跑、提示创建新会话；
- **不得**只过滤下一次检索却继续把旧摘要送模型；
- 依赖记录不全时隔离整条会话。

本轮**未复现**的部分（如实记录，不伪造）：Python 侧 `IdentityMemory.before_model` 把成功的
ToolMessage 观察固化进 `ga_facts` 并注入系统提示，以及 LangGraph checkpoint 的 ToolMessage 状态
重放。二者需要 broker 模式 agent-brain（Python 进程）联动，Host 侧只在复现测试里证明了
同一份观察在撤权后仍可达（checkpoint GET + 历史装配）。**阻断位置**：无在线 Python brain 时，
`GaExecutionPreparation` 只装配 host 消息历史，模型前的 `ga_facts` 注入发生在 Python 进程内。

---

## 2. 哪些工具结果需要记录 project / source 依赖

判据：**结果是否解析到项目范围内的正文或来源**。依赖必须取自 Host 投影，绝不取模型正文或用户提示词。

| 工具 | 需要依赖 | 现有事实载体 |
| --- | --- | --- |
| `project.content.discover` | 是：项目 id 集合 + `source.entryId` / `sourceRef` / `indexGeneration` | `global_assistant_run_events`(TOOL_COMPLETED) `payload.resourceRefs` + `capability_invocations.result` |
| `project.get_summary` | 是：项目 id | 同上（`directProjectId` → `resourceRefs`） |
| `project.search` / `project.list_recent` | 是：候选项目 id 集合 | 同上（`candidatePairs` → `resourceRefs`） |
| `project.create` | 是：新建项目 id | 同上 |
| `help.search` | 否（公共帮助语料，部署提供） | — |
| `web.search` / `web.fetch` | 否（外部来源，无项目归属） | — |
| `skill.import` / `skill.import.discover` | 否（组织级，非项目正文） | — |

**关键结论：这些依赖事实今天已经在库，不需要新增"来源依赖表"。**

- `GaCapabilityBroker.execute` 成功后即为每条 `project:` 资源写入 `sourceRefs`，并把
  `resourceRefs` 写进 `TOOL_COMPLETED` 事件（`GlobalAssistantToolPresentation.projectResources`
  只接受真实 UUID，未知能力返回空）；
- `CapabilityRuntime` 把完整 `CapabilityResult.content` 落到 `capability_invocations.result`
  （`candidates[].projectId`、`sources[].source.projectId/entryId`，`project_id` 列自 V29 起可空，
  GA 应用范围调用即用空值，项目归属在 `result` 内容里）。

---

## 3. 依赖如何与 thread / message / checkpoint 关联

不新增关联列，用**既有外键链**推导：

```text
global_assistant_threads(thread_id)
  └─ global_assistant_runs(thread_id)                       ← 轮次
       ├─ global_assistant_run_events(run_id, type='TOOL_COMPLETED')  ← resourceRefs(project:<id>)
       └─ capability_invocations(run_id)                    ← result 内的 projectId / source
  └─ ga_checkpoints(thread_id, checkpoint_id)               ← Python 记忆（observation 的持久载体）
       └─ ga_checkpoint_writes(thread_id, checkpoint_id)
```

- **thread 级依赖集** = 该线程全部 run 的 `TOOL_COMPLETED.resourceRefs` 中 `kind=PROJECT` 的 id 并集
  ∪ `capability_invocations.result` 中出现的 `projectId` 并集。
- **message 级**：观察属于产生它的 run（`global_assistant_messages.run_id`），因此"某条消息是否
  携带项目观察"由该 run 的依赖集回答。
- **checkpoint 级**：checkpoint 属于 thread（`ga_checkpoints.thread_id`），其内容（编码树）对 Host
  不透明。**不做内容级解析**：checkpoint 的依赖集直接**继承其 thread 的依赖集**。任一依赖被撤权
  ⇒ 该线程的全部 checkpoint 视为不可继续。

---

## 4. 撤权后的隔离判定

**判定表达式（在既有六条合取之上叠加的读侧规则）**：

```text
可见(thread) ⟺ 依赖集已知(thread) ∧ ∀ p ∈ projects(thread): projectDecisionFor(actor, p, PROJECT_READ).allowed()
```

- 任一 `p` 被拒（撤权 / 跨组织 / 用户禁用）⇒ 整条会话按"不可见"处理（404 语义），与项目判定同构：
  不区分"会话不存在"与"依赖已失效"。
- 依赖集为空且该线程**没有**任何项目范围工具观察 ⇒ 正常可见（例如纯 `web.search`/`help.search` 会话）。
- 撤销不删除数据：只改变读侧判定，历史行原样保留（不改变历史数据语义）。

**落地点（收敛到唯一的收口，避免新增入口漏检）**：

1. `GlobalAssistantConversationService.requireThread` / `findThread` / `listMessages` —— 会话详情与
   正文的唯一收口点，叠加依赖判定；
2. `GlobalAssistantThreadListRepository.listRecent` —— 列表预览的 SQL 过滤；候选生成阶段即排除
   （与 P2-A 相同的原则：数量与排序本身就是信息）；
3. `GaCheckpointStore.apply`（GET / LIST / PUT）—— 撤权线程拒绝 checkpoint 读取与写入，等价于
   "拒绝旧 checkpoint 续跑"；
4. `GaExecutionPreparation.prepare` —— 装配 `history` 前对该线程依赖做同一判定；失效则
   **失败关闭**（`GA_HISTORY_SOURCE_REVOKED`），提示创建新会话，而不是把旧观察送模型。

---

## 5. 依赖未知的旧记录如何失败关闭

"依赖未知"定义为下列任一：

- 该线程存在 `langchain-ga.v1` 的 run，且其 `TOOL_COMPLETED` 事件的 `resultKind` / `capabilityId`
  表明是项目范围工具，但 `resourceRefs` 为空（工具未按契约产出 `project:` 引用）；
- `capability_invocations.result` 与事件引用**不一致或缺失**（例如被截断 / 旧版本写入）。

处理：**整条会话按不可见处理（fail closed）**，不猜测"大概没有敏感内容"。这与 P2-A §6.5
"依赖记录不全时隔离整条会话"一致。

代价与边界：这会连带隐藏"恰好缺引用的旧会话"。为控制影响面，第一刀只对 **GA（`langchain-ga.v1`）
且存在项目范围工具调用的线程**生效；纯 `java-legacy.v1` 的旧轮次本来就不进入模型历史
（`GaExecutionPreparation` 只装配规范消息），不受影响。

---

## 6. 锁顺序（不改变冻结顺序）

冻结顺序（P2-A §1.4）：

```text
task → 容量槽(slot_key 升序) → execution scope → authorization scope(scope_key 升序) → 业务 Run → 业务行
```

本设计的读侧判定**不引入新的锁层级**：

- 依赖判定读取授权事实，发生在**既有的 authorization scope 窗口内**，沿用
  `RetrievalGrantAuthorization` 的做法（`lockSharedFailClosed` 按 `scope_key` 升序取
  `authz:org:* → authz:user:* → authz:project:*`），随后 `requireProjectFor(...)`；
- **不**取项目行锁（读判定不需要项目行锁，避免把项目行拖进会话读路径）；
- checkpoint PUT 的写入顺序不变（`GaCheckpointStore.apply` 先 `executions.authorize` 取 Run 锁，
  再 `ga_checkpoint_heads ... FOR UPDATE`）；依赖判定插在 `executions.authorize` 的授权范围检查处，
  位于 Run 锁之前，因此与"task → scope → run → 业务行"的既有链一致，不构成锁环。

**若实现过程中发现必须改变当前授权锁顺序（例如需要把项目行锁前置）→ 立即按停止条件停手**。

---

## 7. 最小迁移与回退代价

- **迁移：0 个 Flyway 文件。** 不新增表、不加列、不改历史行；依赖集在读取时由既有
  `global_assistant_run_events` + `capability_invocations` 推导。因此不触碰"不修改已有迁移 /
  不改变历史数据语义"两条硬约束。
- **写入侧增量（可选、非必须）**：`GaCapabilityBroker` 已产出 `project:` 引用；若要消灭
  §5 的"依赖未知"类，只需在工具结果契约里**要求**项目范围工具必须产出项目引用（属实现加固，
  不是数据迁移）。
- **回退代价：一个提交。** 三处收口（会话服务 / 列表仓储 / checkpoint 与 prepare）各自是独立的
  判定叠加；回退即去掉判定，行为回到本设计的现状（本复现测试即为回退后的基线）。
- **性能代价**：列表查询需要为每个线程聚合其 run 的项目依赖（有界：按 thread + run 过滤，
  refs ≤ 10/工具调用、工具调用 ≤ 5/run）；可在列表 SQL 内用 `EXISTS` 子查询完成，避免 N+1。

---

## 8. 本轮明确不做（越界即停）

- 不新增来源依赖表 / 来源可见性表；
- 不修改 GA checkpoint 跨语言协议（`ga-checkpoint.v1` 信封字段与编码树不变）；
- 不改变现有授权锁顺序；
- 不重写 Python 记忆系统；Python 侧 `IdentityMemory` 的输入由 Host 决定——Host 在 §4 的四处
  收口上完成隔离后，Python 收到的 ToolMessage 与 checkpoint 状态自然不带失效来源。

若后续判断必须新增来源依赖表（例如工具引用契约无法覆盖），即命中停止条件
"需要新增来源依赖表或改变现有历史数据语义"，应交由独立的一轮设计-评审-迁移，
不在本轮扩张。

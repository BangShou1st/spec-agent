# P1-A：执行协议冻结与写入/取消入口盘点

日期：2026-10-11。状态：**已实施**。对应 [第一阶段多 Worker 执行架构设计](PHASE1_MULTI_WORKER_EXECUTION_DESIGN.md) 的 §11 第 1 批。

本文档是 P1-A 的交付物：冻结执行协议，并枚举全部认领、checkpoint、失败、人工接受、续跑、恢复、工具写入与取消入口，确认 token 保护覆盖与锁顺序。**只读盘点 + 协议定义 + 骨架落地；未改变任何既有业务语义。**

---

## 1. 冻结的执行协议

### 1.1 所有权粒度：从"全库单执行器"改为"每任务"

| | 旧（单执行器） | 新（任务级） |
|---|---|---|
| 互斥载体 | `executor_ownership` 单行 + 会话级 advisory lock | `runtime_tasks` 行 + 执行代次/租约 |
| 互斥范围 | 整个数据库 | 每个任务独立 |
| 写入验证 | 条件 UPDATE 内联比较全局 `epoch` | 事务内对 task 行取**排他锁**并核实 token |
| 主开关 | 无（运行时永远单实例） | `spec.agent.execution.multi-worker-enabled` |

两者**互斥**，由同一个开关选择（design §11"禁止新旧调度器混跑"）。开关关闭时行为与历史逐字一致。

### 1.2 执行协议版本

- `RuntimeExecutionProtocol.VERSION = 1`，落库在 `runtime_tasks.protocol_version`。
- 认领查询带 `protocol_version = 1` 条件：不兼容的 Worker **认领不到任务**，而不是降级执行。

### 1.3 范围键（只由 Java 生成，模型与客户端绝不能指定）

| 族 | 范围键 | 并行语义 |
|---|---|---|
| WORKSPACE | `project:{projectId}` | 同项目串行、不同项目并行 |
| GLOBAL_ASSISTANT | `ga-thread:{threadId}` | 同会话至多一个活跃 Run |

### 1.4 统一锁顺序（不可颠倒）

```text
task 行 → 容量槽（slot_key 升序）→ scope 行（scope_key 升序）→ 业务 Run → 项目/会话与业务行
```

- 没有涉及的锁可以跳过。
- 范围有存活占用者 → **立即**回滚候选并扫描其他候选，**绝不持锁等待**。
- 唯一例外：入队配额行（`runtime_queue_quota`）是**叶子锁**，只被入队路径取得，认领/终态/恢复一律不碰，因此不参与上述顺序，不可能形成等待环。

---

## 2. 数据表落地

迁移：`backend/src/main/resources/db/migration/V55__runtime_task_scheduling.sql`

| 表 | 作用 | 关键约束 |
|---|---|---|
| `runtime_tasks` | 公共调度事实源 | `CHECK ((workspace_run_id IS NULL) <> (ga_run_id IS NULL))`；每个业务 Run 唯一任务（两个部分唯一索引） |
| `runtime_task_scopes` | 任务需要的范围集合 | 主键 `(task_id, scope_key)` |
| `runtime_scope_owners` | 范围当前占用者 | `scope_key` 主键 → 同一范围至多一个持有者 |
| `runtime_capacity_slots` | 预建固定执行槽 | `CHECK (used <= capacity)`；预置 GLOBAL=4 / WORKSPACE=2 / GLOBAL_ASSISTANT=2 |
| `runtime_task_capacity` | 任务↔槽占用绑定 | 容量计数的**唯一事实来源** |
| `runtime_task_attempts` | 每次执行尝试审计 | `UNIQUE (task_id, execution_epoch)` |
| `runtime_queue_quota` | 入队配额串行化点 | 每族一行，入队事务 `FOR UPDATE` |
| `workspace_run_steps` | 画布步骤账本（P1-C 用） | 主键 `(run_id, step_key)`；步骤身份**不含 attempt** |

索引：排队索引 `(family, available_at, created_at, id) WHERE state='QUEUED'`；过期扫描索引 `lease_until WHERE state='LEASED'`。

---

## 3. 执行入口清单（token 保护覆盖）

### 3.1 认领入口

| 位置 | 旧行为 | P1-B 处置 |
|---|---|---|
| `AgentRunRepository.claimNext*`（11 个方法） | 单条 UPDATE + 全局 epoch 条件 | **保留**（单执行器模式）；任务级模式改用 `RuntimeTaskRepository.lockNextQueuedCandidate` + `AgentRunClaimRepository.markClaimed` |
| `RunService.claimNext*`（10 个入口） | 每个先 `lockOwnershipForWrite()` | 保留；任务级模式下由调度层候选扫描 + 容量/范围锁串行化 |
| `RunWorker.tryClaimAndExecute` | 固定顺序试六条队列，各认领一个 | 保留；任务级模式改由 `WorkspaceMultiWorkerDriver.claimAndExecuteOne` 逐队列调度 |
| `GaExecutionStore.claimExecutor` | `executor_claimed` CAS + hash | **保留**；P1-D 接入 `runtime_tasks`（epoch 硬编码 1 需移除） |

### 3.2 受所有权保护的写入

全部经 `ExecutionFence.lockOwnershipForWrite()` 作为事务第一条语句：

| 位置 | 同事务写入内容 |
|---|---|
| `AnswerCycleService:226` | Answer 落库 + `markPersistedAnswer` |
| `AnswerCycleService:517` | AnswerPatch + `markPersistedAnswerPatch` |
| `ArtifactCycleService:244` | SpecSnapshot + `markPersistedSpecSnapshot` |
| `ReplacementCycleService:216` | 拓扑提交 + `markPersistedNode` + `complete` |
| `DecisionExecutionService:214` | `actionExecutor.execute` + `completeWithResponse` |
| `AgentRunFailureService:94` | `fail` + `RUN_FAILED` 事件 |
| `RunService:582` | 全部认领 |
| `AgentRunService.fencedWrite` / `.fail` | 通用包装 |

**任务级模式变化**：`lockOwnershipForWrite()` 不再比较全局代次，改为要求线程绑定 `ExecutionToken` 并取 task 行排他锁。**缺少 token 的受保护写入一律拒绝**（`epoch=0` 放行被删除，design §6.3）。

### 3.3 业务结果提交点

`AnswerRepository:60`、`AnswerPatchRepository:50`、`SpecSnapshotRepository:55`、`NodeRepository:65`、`AgentProposalRepository:54/75`、`CapabilityInvocationRepository:55`（`ON CONFLICT DO NOTHING` 幂等仲裁）、`AgentRunTerminalizationService:49/65/80`（终态 + 事件 + 续跑 outbox 同事务）。

### 3.4 checkpoint 与步骤状态

- 画布：`AnswerCycleService.runStateUpdate`（`AnswerPatchService.saveOrReuse` 是面向并发恢复的幂等 checkpoint 写入）、`AnswerProcessingGate`、`AnswerPatchService:84`（多条 patch 直接抛异常，绝不 `first()` 回退）。
- GA：`GaCheckpointStore`（版本锁 + CAS）、`GaExecutionCompletion.verifyCheckpoint`。
- 新增 `workspace_run_steps` 承载 P1-C 的逐步核验。

### 3.5 失败与终态化

| 入口 | 事务形态 | P1-B 处置 |
|---|---|---|
| `AgentRunFailureService.fail`（2 个重载） | `REQUIRES_NEW` | **单执行器模式保留**；任务级模式增加 `lock_timeout` + 捕获锁超时/数据库不可达 → `OWNERSHIP_REFUSED` |
| `TurnHandoffService.tryHandoffAfterCommit:112` | `REQUIRES_NEW` | 保留（P1-D 处理） |
| 各周期服务 `failIfNotTerminal`（6 处） | 调失败服务 | 保留 |

**`REQUIRES_NEW` 自等待分析（design §6.3 点名的陷阱）**：

- 旧实现不自死锁，靠的是 `executor_ownership` 上的 **FOR SHARE** 与外层共享锁兼容（共享锁之间不等待）。
- 任务级模式取的是 task 行**排他**锁，与外层不兼容。因此本批次的处置是：失败事务用 `SET LOCAL lock_timeout`，正常路径（外层执行事务已结束）照常生效；万一外层仍持有该行，则在超时后立即 `OWNERSHIP_REFUSED`，由当前合法所有者按租约收敛——**绝不无限期挂住执行线程**。

### 3.6 取消入口

| 位置 | 语义 | 现状 |
|---|---|---|
| `GlobalAssistantController:166/198/205` | run 取消 / 停止会话 / 删除会话 | GA CANCELLED 是第三种终态；`cancel_requested_at` 只是信号列 |
| `GlobalAssistantRunRepository.requestCancel:106` | 协作式取消信号，**绝不直接翻转状态** | 保留 |
| `GaExecutionCoordinator:103` | 向 Python 发一次取消 | **advisory only**；数据库护栏仍是权威 |
| 画布 run | **无用户取消入口**（`AgentRunStatus` 只有 COMPLETED/FAILED） | 本阶段取消映射为 `FAILED` + 专用取消原因（design §6.5） |
| 项目删除 / 会话删除 | **409 拒绝**，不是取消 | design §4.6 要求改为显式取消协议——**尚未实现**，属后续批次 |
| 模型流中断 | `HttpOpenCodeZenTransport` / `ProviderHttpSupport` 轮询取消谓词 | 保留 |

新增：`RuntimeTaskScheduler.cancel`（锁 task → 撤销 token → 终态 + 释放容量范围）与 `requestCancel`（保留 token 的协作式取消）。

### 3.7 续跑 / 手工接受 / steer

- 续跑：`ContinuationCoordinator`、`ContinuationDispatchService`（子 run 创建 + 代数标记同事务）、`RunService.createContinueRun`。`findPending` 无 `SKIP LOCKED`、无 worker 归属 → **P1-B/P1-D 需加**。
- 手工接受：`ProposalAcceptanceService:114`（锁序 project → proposal → graph）。它是**不受执行器租约保护**的业务写入路径（不调 `ExecutionFence`）——design §4.4 要求项目范围协调覆盖工具入口，属后续批次。
- steer：`TurnHandoffService.acceptSteer`；`RunTerminalEvent` 是**进程内** Spring 事件，跨实例不可见（P1-D）。

### 3.8 孤儿 / 启动恢复

| 位置 | 旧行为 | P1-B 处置 |
|---|---|---|
| `AgentRunOrphanRecoveryService.recoverOrphans` | 终态化全库非终态 Run | **任务级模式：跳过有存活租约的 Run**（design §12"新 Worker 启动不终止其他 Worker 活跃任务"） |
| `AgentRunOrphanRecoveryListener` | `@EventListener(ApplicationReadyEvent)` | 保留 |
| `GlobalAssistantRunRecoveryService` | 中断全部活跃 run | **P1-D 处理** |
| `EmbeddingRebuilds:84` | 启动把所有 QUEUED/RUNNING 置为 INTERRUPTED | 独立机制，不在本阶段范围 |

### 3.9 外部副作用的模型/工具调用

- 画布：`InternalModelInferenceController`、`RemotePythonDecisionEngine`（无重试、无降级）。
- GA：`GaNativeModelBroker`（`reserve` → 调用 → `complete`，异常 `unknown`）、`GaCapabilityBroker`（catalog 校验 → `recordedTool` → `reserve` → `uncertainTool` → 执行）、`TavilyWebService`（20s 硬 deadline + 取消轮询）、`McpConnectionRuntime.callTool`。
- 已知风险：`capability_invocations` 的 `RUNNING` 记录永不超时，重试永久 `IN_PROGRESS`（design §7 承认"UNKNOWN 默认不重放"）。`CapabilityRuntime` 把外部调用包在数据库事务内 → 多 Worker + 排他锁下长事务会成为瓶颈，需在 P1-C/P1-E 处理。

---

## 4. 新增模块与端口

新增包 `com.specagent.runtime.execution`（design §10），只依赖公共基础设施与自己定义的端口：

| 类型 | 职责 |
|---|---|
| `ExecutionToken` | 显式所有权凭证（taskId/epoch/leaseId/workerId/family/runId） |
| `ExecutionTokenContext` | 执行线程的 token 管线（**不是**权威） |
| `TaskWriteGuard` | 受保护写入的排他行锁 + token 逐项核实（**权威**） |
| `RuntimeTaskScheduler` | 认领 / 心跳 / 终态 / 过期恢复 / 取消 |
| `RuntimeTaskRepository` | 调度表全部语句级原语 |
| `RuntimeTaskFamily` / `RuntimeTaskState` / `RuntimeTask` | 领域类型 |
| `TaskExecutionAdapter` | 业务执行端口 |
| `TaskScopeResolver` | 范围解析端口 |
| `TaskLifecyclePort` | 业务生命周期端口（认领推进 / 终态收口 / 恢复判定） |
| `RecoveryAdapter` / `RecoveryVerdict` | 自动恢复的核验端口（**只有提供适配器且通过故障测试的 task_type 才可自动恢复**） |
| `LocalExecutionSlots` | 本进程实际空闲执行槽（不是全局限额） |
| `RuntimeExecutionSettings` | 全部实验初值（可配置） |
| `ExecutionOwnershipMode` | 所有权粒度开关，供仓储层选择守卫形式 |
| `WorkerIdentity` | 每进程启动新 UUID |

画布侧实现：`WorkspaceTaskEnqueuer`、`WorkspaceTaskScopeResolver`、`WorkspaceTaskLifecyclePort`、`WorkspaceTaskExecutionAdapter`、`WorkspaceMultiWorkerDriver`、`AgentRunClaimRepository`。

---

## 5. 容量计数漂移的自愈（实施中发现并修复）

`runtime_capacity_slots.used` 是原子认领所需的**冗余计数**，但它会漂移：

- 业务行被级联删除时 `runtime_task_capacity` 随之消失，而计数不会递减；
- 进程在"占槽"与"写绑定行"之间崩溃会留下半个占用。

漂移的后果是**执行槽被永久吞噬**，系统逐渐不再认领任何任务。因此过期扫描的每个周期都重算一次：`RuntimeTaskRepository.reconcileCapacity()` 以占用绑定行为唯一事实修正计数。这是幂等自愈，不是业务动作。

---

## 6. 明确不在 P1-A/P1-B 范围

- GA 的 task 接入、`executionEpoch=1` 硬编码移除、调用账本按 attempt 校验（**P1-D**）。
- Answer/Patch 链的**自动安全接管**（`RecoveryAdapter` 实现与故障测试，**P1-C**）。当前所有画布 task_type 都诚实失败（`EXECUTION_OUTCOME_UNKNOWN`），这正是 design §7 的默认要求。
- 项目删除 → 显式取消协议（**后续批次**，design §4.6）。
- 跨实例 SSE 与事件游标读取（**P1-D**）。
- `Python` 侧协议升级与 attempt 标识（**P1-D**）。
- 容量参数成为承载承诺：**必须测量后才能声明**（design §9、§12 末句）。

---

## 7. 风险清单（承接盘点结论）

1. **`agent_run_events` 的 sequence 用无锁 `MAX(sequence)+1`**：接管场景下新旧执行者同时 append 会撞 `UNIQUE (run_id, sequence)`。任务级模式下调度任务终态由驱动收口，但周期服务内的事件追加仍可能在极窄窗口并发——需在 P1-C 按 attempt 分区序号。
2. **`AgentRunRepository.attachApprovalProducedNode` 是唯一不带 ownership 守卫的 run 写入**（`ProposalAcceptanceService` 事务内）。它只改 `produced_node_id`；需在工具入口纳入项目范围协调时一并处理。
3. **三套独立 lease 机制并存**（画布 `executor_ownership`、GA `ga_executions.execution_epoch`、检索 `retrieval_scope_grants.epoch`）。本阶段只迁移画布；GA 与检索在 P1-D 及以后。
4. **`ProjectDeletionService.NON_TERMINAL_RUN_STATUSES` 是手写字面量**（ArchUnit 禁止 workspace 依赖 `agent..`），由 `ProjectDeletionStatusGuardTest` 对照真实枚举防漂移。
5. **`WorkerPollingGate` 的 `startup-recovery` 开关语义易误解**：关闭恢复**不等于**允许多执行器；多 Worker 必须走 `multi-worker-enabled`。

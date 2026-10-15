# 第一阶段实施记录：P1-A / P1-B / P1-C / P1-D

日期：2026-10-11。状态：**P1-A、P1-B、P1-C 完成；P1-D 完成（含收尾加固）；P1-E 双进程验收已执行**（模式准入协议、事件补发与 Worker 开关解耦、失败服务事务边界审计与事件收敛、Python 协议协商、GA 端到端接管故障注入、双实例故障验收证据）。

对应 [第一阶段多 Worker 执行架构设计](PHASE1_MULTI_WORKER_EXECUTION_DESIGN.md)：

- P1-A 交付物见 [P1-A 执行入口清单](PHASE1_A_EXECUTION_ENTRY_INVENTORY.md)
- P1-B = 画布调度与事务护栏
- P1-C = 画布安全恢复（本记录 §4）
- P1-D = 全局助手接入（本记录 §5）
- **P1-E = 双实例与故障验收（本记录 §7）**

---

## 1. 总开关与运行形态

```yaml
spec.agent.execution.multi-worker-enabled: false   # 默认
```

|       | 关闭（默认）                                       | 开启                                  |
| ----- | -------------------------------------------- | ----------------------------------- |
| 所有权粒度 | 全库单执行器（`executor_ownership` + advisory lock） | 每任务（`runtime_tasks` 行 + 代次/租约）      |
| 认领驱动  | `RunWorker.tryClaimAndExecute`（历史路径）         | `WorkspaceMultiWorkerDriver`（公共调度层） |
| 公共调度层 | **完全不参与**（不登记任务、不检查配额）                       | 登记任务、容量槽、范围锁、心跳、过期恢复                |
| 行为    | 与历史逐字一致                                      | 多实例并行                               |

**禁止混跑是强制的**，不是文档约定：

1. 单执行器模式下 `WorkspaceTaskEnqueuer` 的 `enqueue` 与 `assertQueueCapacity` 都是 no-op。若仍然登记任务，任务永远无人消费，而排队配额会被这些虚假积压占满，最终拒绝所有正常入队——因此**必须**由开关选择一套调度器。
2. 任务级模式下 `ExecutorLease` 不取全库锁、`epoch()` 恒为 0；旧协议的一切全局代次条件被替换为任务行锁。

### 1.1 数据库范围的模式准入协议（design §11，V58）

本地 `multi-worker-enabled` 是**每实例**开关，本身无法阻止两个实例带着不同模式连到同一个数据库。准入协议由两层互补构成（`ExecutionModeAdmission`，无条件装配）：

| 层 | 机制 | 覆盖 |
|---|---|---|
| 登记表 | `runtime_execution_mode`（单行，V58 基线 `SINGLE_EXECUTOR`）：启动时校验登记模式与本实例一致，不一致**拒绝启动**（带维护窗口补救指引）；模式切换 = 停全部实例 → 显式改写该行 → 启动新舰队 | 新二进制之间的配置分歧，含 API-only 实例（入队路径与消费路径的模式必须一致，否则任务静默饿死） |
| advisory lock | 任务级实例（**含 API-only**）在进程生命周期内持 `pg_advisory_lock_shared(LOCK_KEY)`；单执行器执行器实例（旧或新）持同一键的**排他**锁（`ExecutorLease`）。共享与排他互斥、共享之间兼容 | **旧二进制边界**：旧执行器进不来（其现有错误文案"Another executor already owns this database"）、任务级实例也进不去旧执行器的数据库 |

- **剩余边界（如实声明）**：旧 *API-only* 实例既不取锁也不认识登记表，无法被它不知道的协议拦截——由发布流程的维护窗口覆盖（design §11"停止全部旧 Java/Python 执行进程"）。
- **持有中丢失 fail-closed**：会话级锁随连接消失；周期 pg_locks 验证（`admission-verify-interval-ms`，默认 5s），判定丢失即永久闩锁——停止认领（经 `WorkerPollingGate.isPollingAllowed`）、健康检查报 `EXECUTION_MODE_ADMISSION_LOST`（对 API-only 实例同样生效），绝不静默重取。
- **测试豁免**：`spec.agent.execution.mode-admission-enabled=false` 仅供测试（缓存的单执行器测试上下文与任务级上下文在同一 JVM 内锁冲突）；生产不得关闭。
- 健康暴露：`/api/health` 新增 `executionModeAdmission`（登记模式）与 `workerId`（design §9 的 worker 身份关联）。

---

## 2. P1-B 交付内容

### 2.1 入队原子性（design §6.1）

`RunService` 的六条入队路径共用两个收口点：

- `lockProjectForEnqueue` → 顺带执行**容量闸门**（在创建 Run 之前拒绝超限，且用 `runtime_queue_quota` 行排他锁串行化并发入队——`COUNT` 后无锁插入会让上限形同虚设）。
- `appendRunCreatedIfInserted` → 写 `RUN_CREATED` 事件的同一事务里登记 `runtime_tasks` + `runtime_task_scopes`。

**同一事务**提交，因此事务提交后立即崩溃也不丢任务；幂等重放命中的既有 Run 不会产生第二个任务（`insertIfAbsent` + 部分唯一索引）。

### 2.2 认领（design §6.2）

`RuntimeTaskScheduler.tryClaim` 严格按六步：

1. 调用方先取得本进程**实际空闲执行槽**（`LocalExecutionSlots`）；没有空闲槽就不认领——绝不认领到本地却排队等待。
2. 先确认全局容量 > 0（避免为注定失败的认领开事务）。
3. 逐个候选独立事务（`FOR UPDATE SKIP LOCKED`，排序 `available_at, created_at, id`），**排除已尝试的 id**——否则范围冲突的候选会被反复选中直到扫描上限，后面的项目永远轮不到。
4. 事务内按固定锁序：容量槽（`slot_key` 升序）→ 范围（`scope_key` 升序）→ 推进 LEASED（数据库内递增代次）→ attempt 审计 → 业务 Run 推进。
5. 容量或范围不可用 → 抛 `CapacityUnavailable` **回滚整个候选**（含已取得的部分容量），记录候选 id 继续扫描，**绝不等待**。
6. 提交后才返回给执行器。

### 2.3 心跳（design §6.4）

`RuntimeTaskScheduler.heartbeat` 用条件续租：token 三项匹配 + 仍是 LEASED + 未取消 + 旧租约仍有效。**过期租约不能复活**；`deadline_at` 用 `COALESCE` 只在首次执行时固定，续租绝不延长它。数据库不可达即判定续租失败（明确终止执行，不在网络恢复后沿用旧 token）。

### 2.4 终态与取消（design §6.5）

`terminalize` 在同一事务写：attempt 结束 → 任务终态 → 释放范围 → 释放容量 → 业务终态回调。已终态是幂等 no-op（绝不追加第二次终态）；已被接管则抛 `TaskOwnershipLostException`。

`cancel`（控制面）与 `requestCancel`（协作式，保留 token）两种取消。画布取消映射为 `FAILED` + 专用取消原因。

### 2.5 写入保护（design §6.3）

`TaskWriteGuard.lockOwnedTask` 是唯一权威：对 `runtime_tasks` 行取**排他锁**，锁内逐项核实 state=LEASED、worker/epoch/lease 三项匹配、租约与 deadline 未过期（**用数据库时钟**）、未取消。任何一项不满足即抛 `TaskOwnershipLostException`，调用方事务整体回滚。

- `ExecutionFence` 在任务级模式下不再比较全局代次，改用 token + 行锁；**缺少 token 的受保护写入一律拒绝**（`epoch=0` 放行被删除）。
- `AgentRunRepository` 的守卫在任务级模式下只保留"未终态 + 认领代次不晚于本次代次"（跨表全局代次子查询与任务代次无关，必须去掉）。

### 2.6 `REQUIRES_NEW` 自等待（design §6.3 点名的陷阱）

失败服务在任务级模式下：`SET LOCAL lock_timeout` + 捕获锁超时/`DataAccessException`/`TaskOwnershipLostException` → 一律 `OWNERSHIP_REFUSED`。旧实现靠 FOR SHARE 共享锁兼容才不自死锁；排他 task 行锁与自身不兼容，因此必须靠超时与拒绝，**绝不无限期挂住执行线程**。

**收尾审计结论（本轮）**：全部 `AgentRunFailureService.fail` 调用点（6 个周期服务的 `failIfNotTerminal`、`RunWorker` 六条队列的失败收口、孤儿恢复）均运行在**不持有 task 行锁的上下文**——周期服务的公共方法非事务（受保护写入收敛在内层 `transactionTemplate`，模板回滚即释放锁；适配器与驱动不在事务内调用 `fail`）。因此"外层持锁再调内层失败事务"的自等待在现有调用图上**不可达**；`lock_timeout` 保留为**兜底**而非主防线。`RunService`（类级 `@Transactional`）不调用失败服务。

**事件一致收敛（本轮修复）**：调度层把业务 Run 收敛为失败的三条路径——驱动失败收口、恢复判定（`EXECUTION_OUTCOME_UNKNOWN`）、任务取消——原先只写 Run 状态、**不追加 `RUN_FAILED` 事件**。`WorkspaceTaskLifecyclePort.terminalizeRun` 现在在**同一个调度终态事务**（持 task 行锁）内补写 `RUN_FAILED`：任务终态、业务 Run 终态、终态事件、容量与范围释放要么同时提交要么同时丢失。已终态的 Run 幂等返回，绝不追加第二次终态事件（与失败服务协议一致）。GA 侧 `GaTaskLifecyclePort` 原本就经 `GlobalAssistantRunLifecycleService` 原子写助手消息+终态+事件，无需修改。

### 2.7 过期恢复与启动恢复（design §7、§11、§12）

- 过期扫描：`SKIP LOCKED` 分批 → `beginRecovery`（递增代次、撤销 token）→ 核验 → 安全则重排、不安全则 `FAILED` + `EXECUTION_OUTCOME_UNKNOWN` + 核对记录。
- **启动恢复不终止其他实例的活跃 Run**：`AgentRunOrphanRecoveryService` 跳过有存活租约的 Run（有专门测试）。
- **容量计数自愈**：`reconcileCapacity()` 每个过期扫描周期以占用绑定行为唯一事实修正冗余计数。漂移（级联删除、崩溃留半个占用）会让执行槽被永久吞噬，最终系统不再认领任何任务——这是实施中发现并修复的真实缺陷。

### 2.8 健康与配置

- `WorkerRuntimeStatus.isMultiWorkerTaskOwnership()`；`/api/health` 暴露 `executionOwnership: PER_TASK | SINGLE_EXECUTOR_LEASE`。
- 全部初值可配置（`spec.agent.execution.*`），并明确标注为**实验初值**。

---

## 3. 测试证据

| 测试                                            | 覆盖                                                                                                                                                   |
| --------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `MultiWorkerSchedulingIntegrationTest`（6 项）   | 双 Worker 并行不同项目；同项目串行**且不阻塞其他项目**；并发认领同一任务只有 1 个有效 token（且 attempt=1、epoch=1）；kill 后接管；代次严格递增使旧 token 失效                                             |
| `TaskWriteGuardTest`（8 项）                     | 匹配 token 通过；旧 epoch 被拒；过期租约被拒（即使无人接管）；取消被拒；**绝对 deadline 过期被拒**；非 LEASED 被拒；续租延长租约但**绝不延长 deadline**；过期租约不可续租                                        |
| `StartupRecoveryLiveOwnerIsolationTest`（2 项）  | 启动恢复不终止他实例活跃 Run；租约过期后该 Run 重新成为孤儿                                                                                                                   |
| `AnswerChainRecoveryAdapterTest`（9 项）         | P1-C 的安全接管判定（见 §4）+ 适配器**只读**证明                                                                                                                      |
| `AnswerChainTakeoverEndToEndTest`（2 项）        | **端到端**：Answer 已落库、checkpoint 未写出时进程死亡 → 租约过期 → 真实适配器核验 → **新 Worker 接管 → 周期从核验 checkpoint 继续完成，且不产生第二个 Answer/Patch**；未知副作用的模型步骤 → 诚实失败 + 核对记录，绝不重放 |
| `GaMultiWorkerSchedulingIntegrationTest`（3 项） | GA 不同会话并行、同会话串行；执行输入持久化可重建；租约过期无适配器时诚实失败**且业务 Run 一并收敛**                                                                                             |
| `ContinuationCheckWorkerClaimTest`（6 项）       | V57 认领:同批检查不被两个 Worker 同时认领;处理完成后不重复评估;瞬时失败立即释放认领;认领过期后其他 Worker 可接管                                                                                 |
| `AgentRunEventConcurrentAppendTest`（2 项）      | 真实 PostgreSQL 并发:同一 run 8 写者并发 append,40 条事件序号**连续、无重号、无缺号**;不同 run 的追加不互相串行化                                                                        |
| `ExecutionModeAdmissionTest`（6 项）          | 模式准入协议(V58):SINGLE_EXECUTOR 正常准入;排他 advisory lock 在场时任务级准入被拒(**旧二进制边界**);两个任务级准入(共享锁)共存;登记为 PER_TASK 时单执行器实例拒绝启动、反之亦然;共享锁随连接消失后 fail-closed 闩锁,绝不静默重取;测试豁免开关 | 
| `GaTakeoverEndToEndTest`（3 项）              | **GA 端到端接管故障注入**(§5.2):已记账模型调用 → kill → 接管 → 预算与账本原样保留、`executor_claimed` 重置、旧 token 被拒;`RESERVED` 调用拒绝接管并诚实收敛;缺 checkpoint head 拒绝接管                                                    |
| `testNonLive`（全量，1654 项）                      | 既有测试全绿；`com.specagent.architecture.*` ArchUnit 门禁全绿                                                                                                  |
| `agent-brain` pytest                          | GA 执行/流式/checkpoint/健康公告套件 29 项全绿（`test_app.py` + `test_global_assistant_langchain.py`;全量 22 项见上一批记录）                                                      |

关键验收对照（design §12）：

| 场景                    | 结果                                                       |
| --------------------- | -------------------------------------------------------- |
| 同一个 Run 被同时认领         | ✅ 只有一个有效 token，attempt/epoch 均为 1                        |
| 不同项目慢任务可重叠、第五个排队      | ✅ 容量槽 + 范围锁；GLOBAL=4                                     |
| 同项目有序、不同项目仍能继续        | ✅ 范围冲突只回滚候选，扫描下一个                                        |
| 新 Worker 启动不终止他实例活跃任务 | ✅ 有存活租约的 Run 被跳过                                         |
| 认领后 kill              | ✅ 租约到期 → 恢复扫描 → 撤销 token                                 |
| **Answer 后 kill**     | ✅ **接管安全、复用原 Answer、继续完成、无重复**                           |
| **Patch 后 kill**      | ✅ **复用已核验补丁，不重跑 STATE_UPDATE**                           |
| 旧 Worker 恢复后不能提交结果    | ✅ 代次递增 + token 三项校验                                      |
| 同一 run 并发事件追加（接管窗口）   | ✅ run 行锁串行化，序号连续无重号（`AgentRunEventConcurrentAppendTest`） |

测试环境封闭性说明：`GlobalAssistantSliceBIntegrationTest.modelCatalogHoldsExactlyTheV1Tools`  
与 `GlobalAssistantHardeningMiscTest.gaCatalogSurvivesUnrelatedCapabilityFlood` 原先隐含  
"宿主机未配置 Tavily key" 的假设——用户配置 `SPEC_AGENT_TAVILY_API_KEY` 后目录按设计  
纳入 web 工具，这两个断言随之失败。已按仓库既有 `@SpyBean TavilyWebService` 模式对  
运行环境封闭，测试结果不再依赖宿主机配置。  
| 迟到写入拒绝 | ✅ `TaskWriteGuard` 行锁 |  
| 工具结果不确定 | ✅ 诚实失败 + 可查询核对记录 |  
| 数据库短时中断 | ✅ 心跳判定失败、拒绝未授权提交 |  
| **跨实例回调和事件、GA 接管、发布演练** | ✅ **自动化 + 真实双进程证据见 §7（P1-E）** |

---

## 7. P1-E：双实例与故障验收（2026-10-11 执行，10/10 通过）

**执行形态**：两个独立 Java 进程（bootJar，端口 7601/7602）+ 独立 PostgreSQL 容器（专用库 `spec_agent_p1e`）+ 真实 Python brain（broker 模式，fake 模型推理）。驱动脚本 `tools/p1e_dual_process_acceptance.py`，租约节奏为观测窗口初值（8s/3s/认领 200ms 轮询；生产默认 60s/10s 不变）。

**证据文件**：`docs/v2/evidence/P1E_DUAL_PROCESS_EVIDENCE.json`（逐步 PASS/FAIL 与细节）；进程日志 `scratch/p1e/*.log`。

### 7.1 真实双进程证据（全部 PASS）

| 场景 | 结果与关键事实 |
|---|---|
| S1a 任务级实例在 SINGLE_EXECUTOR 数据库上被准入协议拒绝启动 | ✅ 启动即退出，日志含 "Execution mode admission refused" |
| S1c 双 Worker 在 PER_TASK 准入下启动 | ✅ `executionOwnership=PER_TASK`、`executionModeAdmission=PER_TASK`、workerId 互异 |
| S1d 旧模式（单执行器）实例在舰队运行时启动 | ✅ 被 advisory lock 边界拒绝（"Another executor already owns this database"）——旧二进制进不来 |
| S2 四个 GA 会话并发 | ✅ 不同 Worker 的 attempt 执行区间重叠（overlapPairs=2）、GLOBAL 槽并发占用峰值 2、4 个任务全部收敛。以持久化 attempt 审计为事实，不与瞬态窗口赛跑 |
| S3 杀死持有在途租约的进程 | ✅ 幸存实例接管核验：任务 epoch 1→2、`EXECUTION_OUTCOME_UNKNOWN`、业务 Run 收敛为 FAILED、**RUN_FAILED 事件在同一调度事务内落库**；其余排队任务由幸存者收敛（不丢失） |
| S5a GA 跨实例 SSE + 回调 | ✅ 执行者=B、SSE 挂在 A（非执行实例）收到终态事件（数据库游标补发）、事件序号连续无重号；模型调用账本已记账（model_calls=1）；Python 回调按部署配置指向 A，执行者 B 时即跨实例回调 |
| S5b GA steer 跨实例接受 | ✅ steer 打到非执行实例被接受：旧 run CANCELLED、继任 run 被创建并收敛（继任的业务结果取决于模型可用性，本环境失败于模型步） |
| S6 数据库中断（容器 pause 14s > 租约 8s） | ✅ 恢复后租约被接管核验（epoch 1→2，绝不复活旧租约）、任务诚实收敛、双实例健康恢复 |
| S7 容量边界 | ✅ 8 个 GA 任务并发冲击下 GLOBAL 槽占用峰值 2（≤4 上界，曾达 2 以上证明并发真实发生）、全部收敛 |
| S8 回退演练 | ✅ 切回 SINGLE_EXECUTOR（登记行改写）→ 新实例可启动（`SINGLE_EXECUTOR_LEASE`）、可处理入队、`runtime_tasks` 历史保留 |

### 7.2 证据分层的如实声明

| 场景 | 证据类型 | 说明 |
|---|---|---|
| S4 迟到写入拒绝（旧 Worker 暂停后恢复提交） | **仅自动测试** | Windows 无法对真实进程做暂停后恢复，不冒充进程级证据。覆盖：`TaskWriteGuardTest`(8) + `AgentRunEventConcurrentAppendTest`(2) + `AnswerChainTakeoverEndToEndTest`(2) |
| 画布 Answer/Patch 链接管后继续完成 | **仅自动测试** | `AnswerChainTakeoverEndToEndTest`:接管复用原 Answer、从核验 checkpoint 继续、无重复产物（fake 画布任务执行窗口 ~0.3s,进程级 kill 窗口不可靠） |
| GA 业务结果 COMPLETED | **未执行** | 验收环境无真实模型凭据:GA 执行在模型流步骤诚实失败（失败于模型步,非调度失败）;真实模型只做有限烟测(design §12)。GA 的调度/接管/SSE/steer 机制证据不受影响 |
| 优雅退出（design §11 的 drain） | **未演练** | Windows 无 SIGTERM;S3 硬杀与 S8 硬停回退已覆盖 |
| GA 租约过期诚实失败 | **自动测试 + 进程级** | 自动:`GaMultiWorkerSchedulingIntegrationTest`+`GaTakeoverEndToEndTest`;进程级:S3(杀进程)与 S6(断库)即 GA 无适配器诚实失败路径 |

**结论**：`ga-recovery-enabled` 维持默认关闭。生产开启前需在真实部署形态（有模型凭据）补充 GA 接管后业务继续完成的证据。

### 7.3 验收过程中发现并修复的真实缺陷

**`reconcileCapacity` 容量对账死锁（真实复现,已修复）**：双实例并发下,`reconcileCapacity` 的单条多行 UPDATE 对 `runtime_capacity_slots` 的取锁顺序取决于执行计划扫描顺序（实测为 WORKSPACE→GLOBAL）,与认领事务的取锁顺序（GLOBAL→family）相反,构成死锁对——PostgreSQL 判死锁后中止其中一个事务,`TaskUtils` 记录 "Unexpected error occurred in scheduled task"（P1E 进程日志 `probeA.log` 实证）。修复:对账改为**逐槽、按 slot_key 升序**执行,与全库统一的槽锁序（认领 GLOBAL→family、终态 ORDER BY slot_key）一致。这是多实例并发下才会暴露的缺陷——单执行器时代不可达,正是 P1-E 双进程验收存在的意义。

---## 4. P1-C：画布 Answer/Patch 链的安全接管（已完成）

### 4.1 步骤账本

`workspace_run_steps`（`(run_id, step_key)` 主键，**身份不含 attempt**）：

- `prepare` **绝不降级**已 SUCCEEDED 的步骤（否则接管后的重放会把"已完成的外部调用"退回"待执行"）；已完成步骤即使重放时算出的 hash 不同也直接返回（它不需要重跑）。
- 输入 hash 不一致时拒绝复用未完成步骤。
- 步骤状态推进与对应业务结果写入**同一事务**提交，避免"标记成功但产物不存在"或"产物存在但标记未完成"两种误判。

### 4.2 接管核验（`WorkspaceAnswerChainRecoveryAdapter`）

逐条对照 design §7 表格，**只读**、不发起任何外部调用：

| 核验                       | 判定                           |
| ------------------------ | ---------------------------- |
| 不可重放步骤 UNKNOWN           | ⛔ 拒绝 + 核对记录                  |
| 不可重放步骤 IN_FLIGHT         | ⛔ 拒绝 + 核对记录                  |
| SUCCEEDED 步骤结果引用悬空       | ⛔ 拒绝                         |
| Answer 多于一个 / Patch 多于一个 | ⛔ 拒绝                         |
| patch 缺 source answer    | ⛔ 拒绝                         |
| 以上皆无                     | ✅ 安全：复用 Answer ID 与已核验 patch |

只有 `answer_cycle` 被授权自动恢复；其余画布 task_type 一律诚实失败——design §7「只有提供 RecoveryAdapter 且通过故障测试的 task_type 可以自动恢复」的字面要求。

### 4.3 接管真的能继续（端到端已证明）

核验安全后任务转回 QUEUED；新 Worker 认领并执行。为使接管真正可执行，本批次还改了三处**必要**语义：

1. `AgentRunClaimRepository.markClaimed`：**绝不回退业务进度**。恢复只改变调度状态，绝不把 `PERSISTED` 重置为 `CREATED`（design §5.1）。认领互斥由任务行锁保证，不再依赖"Run 仍是 created"。
2. `RunWorker.executeClaimedRun`：接管入口不做"必须是 RUNNING"的门禁（被接管的 Run 可能已是 `PERSISTED`），但**终态仍被拒绝**；真正的保护来自任务级 token 护栏。
3. `AnswerCycleService.reusableAnswer`：接管时若本 Run 已在该节点落库不可变 Answer，则**复用它**并从已核验 checkpoint 继续，绝不再次 finalize（design §7「复用 Answer ID，不创建第二个 Answer」）。复用前提三项缺一不可：`(route,node)` 已有 Answer、属于本项目与路线、且确为本次尝试写下（产出引用匹配或该节点是本 Run 的输入目标）。

`AnswerCycleService` 同时在 `answer_persisted`、`state_update_model`、`patch_persisted` 三处写入步骤账本（模型步骤标 `UNSAFE` + `IN_FLIGHT`）。单执行器模式下 `WorkspaceRunStepRepository` 未装配，周期服务行为与历史逐字一致。

### 4.4 端到端证据

`AnswerChainTakeoverEndToEndTest` 跑在**完整任务级路径**上（入队 → 公共调度层认领 → 真实周期 → 真实适配器恢复 → 新 Worker 接管 → 周期继续），断言：

- 接管判定为安全（`state=QUEUED`，无 `last_error_code`）；
- 接管后该节点仍**只有一条** Answer，且身份就是原来那条；
- 周期**真的继续**：补丁被创建（恰好一条），Run 到达 `completed`；
- 旧 Worker 的 token 在接管后无法提交任何东西；
- 另一种形态（模型步骤 UNKNOWN）→ `FAILED` + `EXECUTION_OUTCOME_UNKNOWN` + 含步骤名的核对记录，且**副作用未被重放**（补丁从未写出）。

---

## 5. P1-D：全局助手接入（主体完成）

### 5.1 已完成

| 项                        | 实现                                                                                                                                                                                                                                                                                                                                                                                       |
| ------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **持久化 dispatch 参数**      | `GaTaskEnqueuer.createRunWithTask` 在同一事务里创建 Run + 用户消息 + 调度任务 + 执行输入载荷（`runtime_tasks.input_payload`）。崩溃后接管者从数据库重建 message 与 UI context，不再依赖内存参数。                                                                                                                                                                                                                                        |
| **接入统一调度**               | `GaTaskScopeResolver`（`ga-thread:{threadId}`）、`GaTaskLifecyclePort`、`GaTaskExecutionAdapter`、`GaMultiWorkerDriver`；与画布驱动并列，各自只认领自己族。                                                                                                                                                                                                                                                     |
| **移除 `epoch=1` 硬编码**     | `GaExecutionPreparation.prepare(..., executionEpoch)` 从执行 token 读取代次并写入 `ga_executions.execution_epoch` 与执行信封。`GaExecutionStore.initialize` 改为 `ON CONFLICT DO UPDATE`：接管按新代次/新租约更新绑定，`executor_claimed` 重置以便新 attempt 重新做"恰好一次"CAS。                                                                                                                                                     |
| **预算绝不重置**               | `initialize` 的 SET 列表刻意不含 `model_calls`/`tool_calls`，接管沿用原模型/能力快照与剩余预算（design §7）。                                                                                                                                                                                                                                                                                                       |
| **事件按 attempt 分区**       | `ga_execution_events` 唯一键从 `(run_id, internal_sequence)` 改为 `(run_id, execution_epoch, internal_sequence)`（V56）。旧 attempt 的内部序号不再与新 attempt 冲突。                                                                                                                                                                                                                                          |
| **Python 活动标识按 attempt** | `execution.py` 的活动表键从 `run_id` 改为 `(run_id, epoch, lease_id)`；取消只作用于 epoch+lease 完全匹配的那次 attempt——旧代次的取消绝不能取消已接管的新 attempt。                                                                                                                                                                                                                                                              |
| **启动恢复不终止他实例活跃 Run**     | `GlobalAssistantRunRecoveryService` 跳过持有存活租约的 GA Run（与画布侧对称）。                                                                                                                                                                                                                                                                                                                            |
| **GA 自动恢复默认关闭**          | `GaExecutionRecoveryAdapter` 仅在 `spec.agent.execution.ga-recovery-enabled=true` 时装配；即便开启，存在 `RESERVED`/`UNKNOWN` 调用时仍拒绝接管。默认下租约过期的 GA 任务诚实失败。                                                                                                                                                                                                                                            |
| **诚实失败收敛业务态**            | 恢复判定不安全时，`onRecoveryDecision` 把 GA Run 一并终态化——否则它会永远占着 `uq_ga_runs_single_active` 的唯一活跃槽，该会话再也无法创建新 Run。                                                                                                                                                                                                                                                                                 |
| **跨实例 SSE 游标补发**         | `GlobalAssistantStreamService.catchUpAll` + `GlobalAssistantStreamCatchUpPoller`：进程内 `publish` 只送达本进程订阅者，因此额外按每个订阅者的 `lastSent` 游标从 `global_assistant_run_events` 读取已提交事件并补发。两条路径以序列号对齐，既不重复也不漏投；每次每个订阅者最多读取 200 条（有界）。                                                                                                                                                                  |
| **跨实例轮次续转**              | `TurnHandoffCatchUpPoller`：终态续转原本由进程内 `RunTerminalEvent` + AFTER_COMMIT 驱动。若 run 在 Worker A 终态化而未决轮次属于实例 B，监听器永不触发，该轮次会一直搁置到下次启动。轮询器基于数据库事实完成续转，幂等由 thread 行锁 + 确定性继任 Run 身份保证。                                                                                                                                                                                                          |
| **续跑检查 worker 归属**       | `ContinuationCheckRepository.claimPending`（V57）：`FOR UPDATE SKIP LOCKED` + 会过期的认领。多 Worker 不再重复评估同一条检查；瞬时失败立即释放认领；认领者崩溃后认领过期即可被接管——绝不让一个死掉的 Worker 永久搁置检查。单执行器模式（workerId 为 null）保持历史查询、不写认领列。                                                                                                                                                                                           |
| **画布事件并发序号串行化**          | `AgentRunEventRepository.append`（画布 `agent_run_events`）：先 `SELECT ... FOR UPDATE` 锁该 run 的业务行、再 `MAX(sequence)+1` 插入，且整个"锁行 + 插入"必须在同一事务（`@Transactional`，自动提交下 FOR UPDATE 会在 INSERT 前释放、保护不了任何东西）。Read Committed 下两个并发 append 原本会算出同一序号、后到者撞 `UNIQUE (run_id, sequence)`；单执行器时代该竞争不可达，多 Worker 接管窗口内（继任执行者与收尾路径并发）可达。锁粒度是 run 行，与全局锁顺序一致，不同 run 互不阻塞。其他唯一性冲突仍对调用方可见（create-run 幂等语义不变）。 |
| **架构无环**                 | 引入中性端口 `TaskQueueDriver`（公共调度层），轮询器不再依赖 assistant 包——消除 `agent → assistant → agent` 顶层模块环（ArchUnit 门禁）。                                                                                                                                                                                                                                                                                  |

### 5.2 收尾加固（2026-10-11 第二批，全部完成）

| 项 | 实现 |
|---|---|
| **Python 侧协议版本协商** | `/health` 新增 `gaExecutionProtocolVersions` 能力公告（`ga-execution.v1`、`ga-execution-event.v1`，与 `contracts.py` 的 Literal 字面量同步演进）。Java 侧 `GaExecutionCoordinator` 在发出执行信封**之前**读取公告并核对必需版本；缺任一版本 → 类型化 `GA_PROTOCOL_MISMATCH` 失败（区别于不可达），不默认允许旧请求（design §8）。Python 端 `Literal` 校验保证未知版本在执行端点同样被 422 拒绝——协商与拒绝双层封闭。 |
| **GA 端到端接管故障注入** | `GaTakeoverEndToEndTest`（3 项，`ga-recovery-enabled=true` 仅限该测试类开启）：①已记账（有回执）的模型调用 → kill → 接管 → 绑定按新代次/新租约重绑、`executor_claimed` 重置、预算与账本原样保留、旧 token 被拒；②存在 `RESERVED` 调用 → 拒绝接管 + `EXECUTION_OUTCOME_UNKNOWN` + 核对记录 + 业务 Run 一并收敛；③缺 checkpoint head → 拒绝接管。 |
| **事件补发独立于 Worker 开关** | `GlobalAssistantStreamCatchUpPoller` 与 `TurnHandoffCatchUpPoller` 移除 `worker.enabled` 条件、无条件装配——补发的消费方是持有 SSE 连接/未决轮次的一侧，纯 API 实例恰恰是其主要使用者。单执行器单实例下扫描恒为空集，只有有界读取开销。 |

**GA 自动恢复保持关闭**：`ga-recovery-enabled` 默认仍为 `false`。自动化故障注入已通过（上表），但在真实双进程验收证据（§7）齐备前不在生产配置开启——"验证通过"的判定包含双实例证据，不以回归测试数量代替故障验收。

### 5.3 仍然待办

| 项 | 批次 |
|---|---|
| 项目删除 → 显式取消协议（当前是 409 拒绝） | 后续 |
| 工具入口（`ProposalAcceptanceService`）的项目范围协调 | 后续 |
| `ga-recovery-enabled` 生产开启（待双实例 GA 接管证据积累后评估） | P1-E 之后 |

**容量参数仍是实验初值**：`local-slots=2`、全局 4、两类各 2、租约 60s、过期扫描 5s、最多 3 attempt、排队上限 1000/范围 20。只有测量之后才能成为承载承诺。

**「有效 token 下业务结果至多一次提交」不等于「模型和外部工具只执行一次」**：接管可能让无副作用的模型步骤重新推理，承认可能的重复计费。

---

## 6. 回退

`multi-worker-enabled=false` 即回到单执行器路径。注意：

- 已产生的新恢复历史（`runtime_tasks` 中的 RECOVERING/终态行、`workspace_run_steps`）保留为历史事实，**不重写**。
- 回退前必须 drain：先停止认领、让在途执行收敛，再切换开关。**禁止直接混跑**（旧实例不理解 task token，会破坏新所有权）。
- 若已启用多 Worker 且存在活跃租约，直接切回单执行器模式会让旧协议重新竞争全库锁，而任务级所有权行的残留需要按 design §11 的维护窗口流程处理。
- **登记行切换是回退/升级的强制步骤**（§1.1）：停全部实例后显式改写 `runtime_execution_mode`（`UPDATE runtime_execution_mode SET mode='SINGLE_EXECUTOR' ... WHERE id=1`，反向同理），再启动新舰队。单执行器实例在登记为 PER_TASK 的数据库上会被准入协议拒绝启动。真实切换演练见 §7 S8。

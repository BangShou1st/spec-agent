# C16 真实集成验收 — 2026-10-21

CODE_COMPLETE / OFFLINE_VERIFIED 之外，本轮首次取得**真实数据库、真实跨进程、真实 Python 检索服务**的集成证据。
本文件是本轮的一手证据索引；原始日志与 XML 见同目录 `raw/`。

---

## A. 环境隔离证明

| 项 | 值 |
|---|---|
| Docker context | `desktop-linux`（Docker Desktop 29.8.0，本地 WSL2 引擎，非远程生产主机） |
| 容器 | `spec-agent-iso-20261021-postgres`（id `0113486f9fb8`，镜像 `pgvector/pgvector:pg17` = `sha256:cf134a767f47…`） |
| 网络 | `spec-agent-iso-20261021-net`（本轮新建） |
| 数据卷 | `spec-agent-iso-20261021-pgdata`（本轮新建，未复用 `spec-agent_spec-agent-postgres-data`） |
| 客户端绑定 | `127.0.0.1:55432` → 容器 `5432`（**未使用历史 5434 入口**） |
| 数据库 | `spec_agent_p3b_live_iso20261021`（主库）+ `…_miga/_migb/_migb2/_migb3/_migc/_migc2/_migd/_migd2/_migb_boot`（场景库） |
| 账户 | `spec_agent_iso_20261021`（本轮容器内新账户，密码仅存 `scratch/iso-20261021/db.env`，不入库） |
| 服务端自报端点 | `inet_server_addr()=172.19.0.2/32`，`inet_server_port()=5432`（守卫按服务器自报值比对） |

**未触碰的对象**：`localhost:5434`、历史 `spec_agent_test`、`spec-agent-postgres` 容器及其数据卷、
`ai-collab-*` 等其他项目容器与卷、`docker system prune`/全局卷清理（本轮未执行任何 prune/reset）。

**隔离校验**：项目自带的 `EvalLiveIsolatedDatabase.verifyBeforeContext` 在每次 Spring 上下文启动前
独立 JDBC 复核库名/账户/服务器端点，并拒绝 `spec_agent`、`spec_agent_test` 等名称；
本轮所有 Spring 集成测试均经该守卫进入，目标库名为 `spec_agent_p3b_live_*`。

**交叉验证**：除项目守卫外，本轮另用裸 JDBC 探针与 `docker exec psql` 分别确认
`current_database()/current_user()/inet_server_addr()/inet_server_port()` 一致，且容器内 `pg_database`
仅含 `postgres/template0/template1` 与本轮命名的库。

---

## B. V98–V103 迁移结果

迁移链：`backend/src/main/resources/db/migration/V1__…__V103__…`（**V17 不存在**，共 102 个文件）。

| 场景 | 结果 | 证据 |
|---|---|---|
| 全新安装 | **通过** | 生产 bootJar 启动 → 102 个迁移全部成功，`flyway_schema_history` 102 行 success=t，最新版本 103 |
| 重复启动 | **通过** | 再次启动：`Successfully validated 102 migrations` + `Schema "public" is up to date. No migration necessary.` |
| V98 | **通过** | `agent_runs.model_binding_fingerprint CHAR(64) NULL` |
| V99 | **通过（历史升级被安全阻断，阻断后人工处置后成功）** | 见下 |
| V100 | **通过** | 触发器 `protect_agent_run_model_binding` 存在；改动已绑定指纹 → SQLSTATE 23514 `RUN_MODEL_BINDING_IMMUTABLE`；首次 NULL→指纹合法；同值改写合法 |
| V101 | **通过** | `capability_invocations.pending_deadline TIMESTAMPTZ` + 部分索引 `idx_capability_invocations_pending_deadline`；历史 RUNNING 行未被提升 |
| V102 | **通过** | `agent_run_dependency_projections` / `agent_run_wait_subscriptions` + 约束（state ∈ WAITING/READY/INVALIDATED）+ 部分索引 |
| V103 | **通过** | `embedding_rebuilds.readiness_revision`（CHECK ≥0）、`embedding_rebuild_outcomes`、`capability_rebuild_waits` + 索引；回填已终态项目 generation；旧 Writer（不写 readiness_revision）仍可插入并落默认 0；retry 递增为新代次 |
| 失败与恢复 | **通过** | 事务内失败迁移整体回滚（无残留表、无历史行）；移除后重试成功；约束冲突不影响其他行；未提交事务关闭即回滚 |

### V99 守卫的真实执行时序（本轮重点）

生产路径：`LegacyBindingMigrationGuard` 是 `org.flywaydb.core.api.callback.Callback` Bean，
Spring Boot 3.3.2 的 `FlywayAutoConfiguration` 会自动注入并注册全部 `Callback` Bean
（`ObjectProvider<Callback>` → `configureCallbacks`），因此守卫**真实运行在产品迁移链里**。

1. **库级实证**：在隔离库用真实历史迁移建立 V98 基线 → 写入一条已绑定有效摘要的在途 Run
   → 用真实 Flyway 链（注册同一守卫）升级 → 抛 `V99_BINDING_OVERWRITE_BLOCKED`，
   `flyway_schema_history` 停在版本 98，绑定摘要**逐字保留**（未被 V99 的 UPDATE 覆盖）。
2. **进程级实证（生产 bootJar）**：
   - `--spring.flyway.target=98` 启动 → 应用正常启动（V98 基线可用）；
   - 写入带有效摘要的在途 Run；
   - 再次以正常迁移链启动 → **应用启动失败**，日志：
     `V99_BINDING_OVERWRITE_BLOCKED: preserve existing bindings; drain workers and review the upgrade before retrying`
     （`Error creating bean with name 'flywayInitializer'` → 上下文启动中止）；
   - 事后核对：`version='99'` 的历史行数 = 0；该 Run 的绑定摘要原样保留。

**结论**：V99 的危险 UPDATE 条件是 `status NOT IN ('created','completed','failed')`；
守卫在 **V99 语句执行之前**、同一迁移事务内持 `SHARE ROW EXCLUSIVE` 表锁并先行校验，
发现"非终态 + 非空 + 非 legacy-unbound"即阻断升级（fail-closed，数据不损坏）。
历史升级被安全阻断属于**预期安全结果**，不是"升级成功"。

---

## C. C16 实际闭环

生产路径：管理端/服务创建真实 generation → 真实后台 worker 经**真实 Python 检索进程**执行重建 →
终态事实与业务状态同事务落库 → `retrieval.await_generation` 绑定具体 generation + readiness revision →
WAIT 订阅具体依赖 → 完成扫描 CAS → continuation outbox → 续跑子 Run 真实执行 → 终态收敛。

| 步骤 | 证据 | 结果 |
|---|---|---|
| 1 真实任务创建 | `EmbeddingRebuilds.start()` → `embedding_rebuilds` state=QUEUED，id = generation | 通过 |
| 2 真实 worker 执行 | 真实 Python `uvicorn` 进程（broker 模式）+ 真实本地 Ollama `qwen3-embedding:0.6b`（digest 由 Python 侧独立校验）→ `embedding_rebuilds` state=READY | 通过 |
| 3 终态事实 | `embedding_rebuild_outcomes(generation,0,'READY')` 同事务落库 | 通过 |
| 4 能力绑定 | `capability_rebuild_waits(invocation → generation, readiness_revision=0)`；invocation status=PENDING | 通过 |
| 5 WAIT 订阅 | `agent_run_wait_subscriptions` state=**WAITING**；冻结投影 `agent_run_dependency_projections` 落库 | 通过 |
| 6 完成 CAS | 扫描后 invocation=**SUCCEEDED**，订阅=**READY**（完成事件早于订阅亦安全） | 通过 |
| 7 outbox | `agent_run_continuation_checks` 恰好 1 行 | 通过 |
| 8 续跑 | 唯一子 Run（parent_run_id=等待 Run，cycle_index=1）被真实 claim 并执行 → 终态 | 通过 |

**恢复不是"只生成 outbox"**：跨进程实测中，由**独立进程**消费 outbox 并创建子 Run；
续跑子 Run 的 V4 冻结投影携带那条确切的完成结果（completedResults）。

### 失败路径（7/7 通过）

| 场景 | 结果 |
|---|---|
| generation FAILED | invocation=FAILED，订阅=READY，outbox=1 |
| generation 不存在 | 明确 `REBUILD_NOT_FOUND`，**不产生** binding，不会伪装成 PENDING |
| 尚未完成 | 不结算；只有已提交事实才触发 |
| 旧 generation / 旧 retry attempt | retry 使 readiness_revision 递增；旧代次**不能**结算新绑定（绑定仍指 revision 0） |
| 跨项目 | 外部项目在冻结投影中**看不到**该 pending 依赖（依赖列表为空） |
| 过期/撤权订阅 | 成员被禁用后，迟到完成**不能**唤醒该 owner（invocation 非 SUCCEEDED） |
| 超时后收到完成事件 | 超时先赢 → invocation 保持 FAILED/`DEPENDENCY_TIMEOUT`，完成 CAS 不改写终态 |
| 重复完成事件 | 只结算一次、只 1 行 outbox、request_generation 不递增 |

---

## D. 竞争与故障结果（真实独立进程）

测试形态：`backend/src/test/java/com/specagent/capability/C16CrossProcessWorkerAcceptanceTest.java`
—— worker 是**独立操作系统进程**（`java -jar build/libs/spec-agent-backend-0.0.1-SNAPSHOT.jar`），
指向同一隔离数据库；测试 JVM 自身所有后台 worker 关闭。

| # | 场景 | 结果 |
|---|---|---|
| 1 | 两个 Worker 同时扫描同一已提交事实 | **通过**：只有一个 CAS 赢家；invocation 恰好 1 行、SUCCEEDED；订阅 READY；outbox 恰好 1 行 |
| 2 | 完成事实提交后、扫描前终止进程 | **通过**：杀死拥有者后 invocation 仍为 PENDING；新进程启动后交付该事实，恰好一次 |
| 3 | outbox 已提交、跨重启消费 | **通过**：重启后 outbox 被处理（processed_at 非空）恰好 1 次；子 Run 不重复 |
| 4 | 同一任务的两个独立进程同时认领 | 通过（租约 CAS：`execution_epoch` 只递增一次；`embedding_rebuild_outcomes` 只有 1 行） |
| 5 | 重复投递与幂等恢复 | 通过（重复完成事件不产生第二次 outbox 行，见 C 节） |
| 6 | 撤权与完成并发 | 通过（撤权后不再唤醒） |
| 7 | 重试代次切换后旧事件延迟到达 | 通过（旧 revision 不结算新绑定） |

**多 Worker 前置条件**：产品禁止新旧调度器混跑。单执行器模式持全库排他 advisory lock，
两个实例无法并存；本轮按文档规定的**维护窗口切换**执行：
`UPDATE runtime_execution_mode SET mode='PER_TASK'` → 两实例共享 advisory lock 并存
（worker 日志：`Execution mode admission: PER_TASK admitted (shared advisory lock 736251904113…)`），
测试结束后回滚为 `SINGLE_EXECUTOR`。

---

## E. 代码修复

### P0-1：完成扫描读取了不存在的列 → C16 永不结算

- **现象**：真实数据库中 `RebuildWaitCompletionRecovery.recover` 抛 `BadSqlGrammarException`，
  SQLSTATE **42703**（undefined column）；底层为
  `PSQLException: ResultSet 中找不到栏位名称 id`。
- **根因**：`RebuildWaitRepository.pending()` 的语句是 `RETURNING invocation_id`，
  行映射却按列名 `"id"` 读取。
- **影响**：`retrieval.await_generation` 的 PENDING 调用**永远不会被完成扫描结算** →
  WAIT 永远不会因为就绪事实而醒来 → C16 主链路在生产中不可用。
- **为什么离线测试没发现**：既有离线测试用 Mockito 打桩仓储，未执行真实行映射。
- **修复**：`backend/src/main/java/com/specagent/capability/RebuildWaitRepository.java`
  —— 行映射列名改为 `invocation_id`。
- **全仓同类审计**：`RETURNING` 的其余 12 处（`CapabilityInvocationRepository.expirePending`、
  `ContinuationCheckRepository.claimPending`、`ExecutorLease.bumpOwnershipEpoch`、
  `RuntimeTaskRepository.tryAcquireCapacity` 等）列名与映射**一致**，无同类缺陷。

### P0-2：续跑调度线程没有身份 → continuation outbox 永不被消费

- **现象**：独立进程日志持续出现
  `Continuation recovery deferred for run …: Project not found`（每个 poll 一次，无限重试）。
- **根因**：`ContinuationDispatchService.process()` 在后台 `scheduling-*` 线程执行，
  线程上**没有绑定任何 actor**；`ContinuationCoordinator.continueIfEligible()` →
  `RunService.createContinueRun()` → `lockProjectForEnqueue()` → `ProjectService.authorizeWrite()`
  依赖线程级 `ActorContextHolder`，于是必然失败。
- **影响**：已提交就绪事实能完成调用、写入 outbox，但**没有任何执行者真正消费 outbox**，
  等待中的 Run 不会被恢复 → C16 闭环在生产中断于最后一步。
- **修复**：`backend/src/main/java/com/specagent/agent/runtime/ContinuationCoordinator.java`
  —— 新增 `WorkspaceRunActorRepository` 注入（`setRunActors`），
  创建子 Run 时用**父 Run 持久化的 workspace 委托**（`workspace_run_authorizations`）绑定 actor；
  没有持久化委托的旧 Run 保持既有 fail-closed 行为，不放宽任何授权边界。
- **安全性**：只使用已持久化的委托身份，未新增任何权限，未放宽 ActionEligibility、参数引用或跨项目校验。

### P2（记录，未修改）

- **非终态 owner Run 的 continuation 检查会无限重试**：完成事实同时唤醒调用方 Run 与等待 Run；
  若调用方 Run 仍未终态，`ContinuationCoordinator.evaluate` 抛
  `Continuation needs a terminal run`，`process()` 释放认领后每个 poll 重试，无退出条件。
  本轮按"不扩大风险"原则未改动该语义（等待 Run 终态是合理的收敛条件），仅记录观察。
- **冷启动本地模型会超过重建租约**：本机 Ollama 首次加载 639MB 模型使首个重建超过
  120s 默认租约，租约到期后被另一 Worker 以 `execution_epoch=2` 接管并完成。
  这是可配置参数与冷启动叠加的结果，未修改产品默认值。

---

## F. Git

见 `final-git-state.json` 与 `commits.json`（同目录）。

---

## G. 发布资格

| 结论 | 依据 |
|---|---|
| `CODE_COMPLETE` | 链路代码完整，且本轮修复了 2 个 P0 |
| `OFFLINE_VERIFIED` | `testOffline` 全绿；`backend/independent` 独立构建 `test verificationScope` 全绿 |
| `DATABASE_MIGRATION_VERIFIED` | 全新安装、重入、历史升级阻断/处置、V100–V103 语义、失败恢复，全部在真实 PostgreSQL 上执行 |
| `REAL_WORKER_VERIFIED` | 独立进程 worker 真实执行重建（真实 Python 进程 + 真实本地 Ollama）并提交终态事实 |
| `CROSS_PROCESS_RECOVERY_VERIFIED` | 两个独立进程竞争同一事实只有一个赢家；崩溃后重启交付恰好一次；outbox 跨重启消费恰好一次 |
| `E2E_VERIFIED` | 真实 generation → WAIT → READY → CAS → outbox → 续跑子 Run 终态，全链路在真实数据库上闭合 |
| `RELEASE_READY_FOR_REVIEW` | 见最终报告的限定条件 |

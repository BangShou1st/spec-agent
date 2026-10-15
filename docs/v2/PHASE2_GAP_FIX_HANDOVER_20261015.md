# P2 缺口修复交接文档

日期:2026-10-15
分支:main(工作树,未提交)
前序:runtime-p1gaps-protocol13.jar(SHA256 11111A73…84909)专项 312 suites 全绿的历史基线不证明本次四项缺口已闭合。

---

## 1. 本次完成的四项修复(已实现、已测试、专项回归全绿、双 JVM e2e 关键场景已验收)

### 修复 1:TaskWriteGuard 取锁后用实时数据库时钟

- 问题:`backend/src/main/java/com/specagent/runtime/execution/TaskWriteGuard.java` `lockOwnedTask` 取 `FOR UPDATE` 锁后用 `now()`(PostgreSQL 事务开始时间)校验 `lease_until`/`deadline_at`,锁等待跨过到期点时过期租约被放行。
- 修复:校验与诊断视图全部改为 `clock_timestamp()`(独立第二条语句,保证时钟在取锁后求值)。
- 审计同类入口(`RuntimeTaskRepository`):`beginRecovery` 的过期判定、`hasLiveOwner*`/`*RunsWithLiveOwner` 存活判定、`scanExpiredLeases` 扫描条件、候选扫描的 `deadline_at` 条件,全部改 `clock_timestamp()`;`available_at <= now()` 的调度时机语义保留 `now()`(有意)。
- 测试:`MultiWorkerSchedulingIntegrationTest.guardedWriteRefusedWhenLeaseExpiresDuringTaskRowLockWait`(真实 PG,行锁等待跨期 → 拒绝且无残留;租约有效窗口的正确短事务可完成)。**已通过。**

### 修复 2:GA HTTP 回调失权检查不依赖 ThreadLocal

- 问题:`backend/src/main/java/com/specagent/assistant/api/GaNativeModelBroker.java` `ownershipLost()` 只读 `ExecutionTokenContext`(ThreadLocal);真实内部 RPC 与 StreamingResponseBody 线程无绑定;跨实例回调看不到执行实例内存标记。
- 修复(三层,见源码 javadoc):
  1. **持久化失权事实**:`runtime_tasks` 同 run 行已非 LEASED / epoch 已变 / 已请求取消 → 拒(任务行不存在=legacy 路径,不判失,由既有围栏把关)。**注意:只按 state/epoch/cancel 判,不按 lease 判**——`ga_executions` 绑定行的 lease 是 `GaExecutionPreparation.prepare` 阶段新生成的绑定 lease,与任务行的认领 lease 天然不同,按 lease 比对会把合法回调误杀(第一次构建踩过,见 §3 回归修正)。
  2. **本进程已知失权**:执行线程带 token 时查 `ExecutionHeartbeatRegistry`;无 ThreadLocal 的线程走新增的 `isOwnershipLostByEpochAndLease(taskId, epoch, lease)` 按 epoch 精确匹配(同 epoch=同一次认领,跨 attempt 永不匹配)。
  3. **流式预检**:`GaHostRpcController.modelStream` 在响应头提交前调 `precheckStream`,失权直接 409,不走 200 NDJSON。
- 尽力取消传播:`LeaseHeartbeat` 判失权时通过新接口 `OwnershipLostListener` 通知 `assistant/runtime/GaOwnershipLostCancelPropagator`(GA 族专用),向 Python `POST /executions/{runId}/cancel`(epoch+lease 精确匹配,不影响新 attempt)。**有意撤回**了初版"心跳失权写 `requestCancel`"方案:恢复扫描把未清的 `cancel_requested_at` 视作取消并终态化接管后的新 attempt,违反 token 隔离。
- 测试:`GaBrokerOwnershipLostIntegrationTest`(multi-worker profile、真实调度认领、真实 HTTP):接管后迟到回调 409 且预算/账本零推进;已判失权但旧租约未到期窗口内无 ThreadLocal 流式调用 409、未判失时同调用 200。**已通过。**

### 修复 3:NodeQueryService 提案收敛到同一受保护短事务

- 问题:`backend/src/main/java/com/specagent/agent/runtime/NodeQueryService.java` awaiting_approval 路径先独立事务 `createProposal` 再另一事务终态化,之间撤权会留下"提案已提交、run 永不收口"。
- 修复:提案 + PROPOSAL_CREATED 事件 + AWAITING_APPROVAL 终态化收敛进 `transactionTemplate` 短事务,第一条语句 `executionFence.lockOwnershipForWrite()`(与 DecisionExecutionService.guarded 同协议)。`createProposal` 调用点审计:DecisionExecutionService 已收敛,NodeQuery 本次收敛,无其他调用方。
- 测试:`NodeQueryTerminalAtomicityIntegrationTest.revocationBetweenCheckAndProposalLeavesNoProposalOrTerminalState`(检查通过后、提案落库前撤权 → 无提案/无事件/run 保持控制面终态)。**已通过。**

### 修复 4:受阻续租测试补真实心跳循环

- 问题:`MultiWorkerSchedulingIntegrationTest.aBlockedRenewalDoesNotDelayAnotherTasksRenewal` 只锁 A 直接续租 B,未经过 `LeaseHeartbeat`。
- 补充:`heartbeatLoopKeepsOtherTaskAliveWhileOneRenewalWaitsForARowLock`——A、B 真实任务注册到生产形态单线程 `LeaseHeartbeat`(4s lease/1s 心跳),A 的续租进入真实行锁等待(证据:阻塞窗口内 A 租约零推进),B 在同窗口被续租且余量 > 1 心跳周期,A/B 均未被误判失权。**已通过。**
- 附带:`LeaseHeartbeat` 构造函数新增 `List<OwnershipLostListener>` 参数(现有两个既有测试调用点已同步补 `List.of()`)。

## 2. 专项回归状态(全绿)

| 套件 | 结果 |
| --- | --- |
| MultiWorkerSchedulingIntegrationTest(14 用例,含新增 2) | 全绿 |
| GaBrokerOwnershipLostIntegrationTest(新增,2 用例) | 全绿 |
| GaHostRpcIntegrationTest(26) | 全绿 |
| NodeQueryTerminalAtomicityIntegrationTest(5,含新增 1) | 全绿 |
| com.specagent.assistant.* + com.specagent.runtime.*(约 28 suites) | 全绿 |
| agent-brain pytest | 239 passed |

## 3. 验收 jar 与 e2e(关键场景已验收)

- **验收 jar**:`backend/build/p2-acceptance/runtime-p2gaps-fix-protocol13.jar`
  **SHA256 = 1FA2585526AB82D6E6949936F7F6A2E0C31B8A40047E36609BB03C0BF428EEEA**
  (第一版 9B0C278F…93FA2 含修复 2 的 binding-lease 误判回归,已被上式取代;历史候选全部保留在 p2-acceptance 目录。)
- **e2e 证据**:`docs/v2/evidence/P2_P2GAPS_FIX_DUAL_WORKER_LONG_RUN.json`
  环境:全新隔离库 `spec_agent_p2_workers_20261015a`(127.0.0.1:5546 容器 `spec-agent-p2-auth`,维护窗口内登记 `security_mode=OIDC`+issuer、`runtime_execution_mode=PER_TASK`);Keycloak realm 重导入(删 H2 后重启);模型设置 fixture 用 test-profile 主密钥(`application-test.yml` 第 85 行 `MDEy…ZWY=`,指纹与 `credential_key_admission` 一致)注入两个组织;双 JVM(A 8383/B 8384)+ Python brain(8104,broker 模式,internal-secret 取 `data/internal-secret.txt`);vite 8282→A、8286→B(`--host 127.0.0.1`);browser-skill 真实 OIDC 登录 alice。
  已验收场景:①长任务(6s native fixture)跨 15s lease 由 3s 心跳保活 COMPLETED;②A 执行、B(8286)跨实例 SSE 读到完整 7 帧;③模型在途时撤权 → run CANCELLED、账本 MODEL=UNKNOWN、无 ASSISTANT_COMPLETED/RUN_COMPLETED、无助手消息、在途流被围栏中止(brain 日志 "GA model stream failed")、原模型调用返回后才核对终态。

## 4. 启动脚本(可复用)

- `backend/build/p2-acceptance/spec_agent_p2_workers_20261015a/workers.properties`(internal-secret 已对齐真实值)
- `start-ab.ps1`(SPRING_CONFIG_ADDITIONAL_LOCATION **环境变量**方式注入;命令行 `--spring.config.additional-location=` 会被 Start-Process 拆成两个参数导致 profile 落回 local——踩过的坑)
- `start-brain.ps1`(secret 从 data/internal-secret.txt 读取)
- fixture 生成:`scratch/p2_20261015a_model_fixture.py`(AES-GCM + org/table/row/api_key AAD,复刻 `ModelCredentialCrypto`)

## 5. 未完成事项(按优先级)

1. **完整后端回归 testNonLive 仍未拿到全绿基线。** 三轮尝试全部因同一环境问题失败:`password authentication failed for user "spec_agent"`,波及 46 套件级联(上下文加载失败),非产品回归。已排查/排除:
   - 5546 容器 `pg_hba.conf` 宿主 TCP 走 scram,但**容器内 127.0.0.1 也 trust 且 spec_agent_dev/TCP 双密码均实测可登录**;
   - daemon 复用旧环境已排除(第三轮 `--no-daemon` 仍失败);
   - bash 传 `SPEC_AGENT_DB_PASSWORD=p2_isolated_test SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5546/spec_agent_test` 是否真正进入测试 JVM **未最终证实**(被取消的单套件验证可确证);怀疑 Git Bash → gradlew.bat → java 的 env 链路在某环丢失,或某配置以绝对优先级钉死 5434/dev(注意 `application-test.yml` 硬编码 `localhost:5434/spec_agent_test` + `spec_agent_dev`,而 5434 容器 `spec-agent-postgres` 当时处于 Exited→已启动,需先确认失败连接到底打向 5434 还是 5546)。
   - **下一步**:跑 `--tests com.specagent.SpecAgentApplicationTests` 单套件(约 2 分钟)确认 env 生效路径;若失败,用 `--info` 看实际 JDBC URL;最直接的替代方案是让 5434 容器承载回归(历史基线 1944 tests 当时的环境),或把 `application-test.yml` 的 url 改为环境变量占位。
2. **剩余 P2 验收**:慢客户端、杀进程接管、数据库故障、密钥轮换/迁移回退、原 P2 剩余入口审计(本轮只闭合了四项缺口 + 长任务/跨实例 SSE/在途撤权)。
3. e2e 的 cancel API 响应语义(cancelRequestedAt 已置、status 仍 RUNNING,终态由收敛写入)在证据 JSON 的 limitations 中如实记录,不阻塞。

## 6. 环境现状(下次接手先核对活性,勿按旧 PID 重启)

- Docker:spec-agent-postgres(5434,测试库)、spec-agent-p2-auth(5546,隔离库)、spec-agent-p2-keycloak(8184,H2 已重建重导入)均在运行;Docker Desktop 曾崩溃过一次,已按主程序方式拉起。
- e2e 进程:A/B JVM(新 jar)+ brain(8104)+ vite 8282/8286 可能仍在;重启用 §4 脚本,先 `Get-NetTCPConnection` 核对端口占用。
- bsk 会话:rkiz(浏览器已登录 alice@8286);验收未完成前不要关闭。
- 工作树:414 处改动(含历史 P2 未提交工作);本次新增/修改的文件见 §1 与 git status;**未提交、未推送、未部署**。

## 7. 关键教训(避免重蹈)

- GA 绑定 lease ≠ 认领 lease:`prepare()` 为 `ga_executions` 生成独立绑定 lease,任何"拿请求 lease 对比 runtime_tasks 行"的判定都会误杀合法回调。
- 恢复扫描对 `cancel_requested_at` 的语义是"取消并终态化(含新 attempt)",旧 attempt 的失权绝不能写它。
- Gradle daemon 继承启动时的环境变量;切换数据库/密码后必须 `--stop` 或 `--no-daemon`,并单套件验证 env 真正到达测试 JVM。
- `spring.config.additional-location` 经 Start-Process 命令行传递会被拆参;用环境变量 `SPRING_CONFIG_ADDITIONAL_LOCATION`。
- v6 vite 默认只绑 ::1,而 realm 白名单是 127.0.0.1;启动必须 `--host 127.0.0.1`。

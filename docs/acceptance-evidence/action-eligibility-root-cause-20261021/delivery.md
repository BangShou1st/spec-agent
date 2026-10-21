# ActionEligibility 9 项集成失败：根因、基线复核与修复

本轮在 `dev` 主线上处理 9 项数据库集成失败、两个 P2，并完成真实集成回归与交付收口。
所有结论均来自本轮实际执行的命令与数据库/日志证据。

---

## 1. 9 项失败的逐项清单与最终分类

9 项失败全部落在 3 个测试类上，**断言签名只有两种**，且根因相同：语义追踪里
`ACTION_ELIGIBILITY` 阶段缺失，因此 `observation.semanticTrace().stages().get("ACTION_ELIGIBILITY")`
返回 `null`。

| # | 测试类 | 测试方法 | 断言 | 实际 | 堆栈锚点 | 分类 |
|---|---|---|---|---|---|---|
| 1 | `com.specagent.eval.ActionEligibilityEnforcementIntegrationTest` | `duplicateCreateNodeIsVetoedBeforePolicyOrExecutor` | `assertThat(eligibility).containsEntry("mode","ENFORCED")…` | `eligibility` 为 `null` | `:40` → `eligibilityStage()` | PRODUCT_DEFECT（根因 1+2） |
| 2 | 同上 | `authorizedDecisionNodePassesEligibilityAndReachesPolicy` | 同上 | `null` | `:77` | PRODUCT_DEFECT（根因 1+2） |
| 3 | 同上 | `unresolvedConflictAllowsOrdinaryCreateNodeAndReachesPolicy` | 同上 | `null` | `:107` | PRODUCT_DEFECT（根因 1+2） |
| 4 | 同上 | `unresolvedConflictAllowsVisibleReadOnlyCapability` | 同上 | `null` | `:141` | PRODUCT_DEFECT（根因 1+2） |
| 5 | `com.specagent.eval.P3BC09AmbiguousGateEnforcementTest` | `missingQueryInvocationOnAmbiguousTaskIsVetoedBeforeAnyEffect` | `assertThat(stage).as("ACTION_ELIGIBILITY stage must be recorded").isNotNull()` | `null` | `:37` → `:129` | PRODUCT_DEFECT（根因 1+2） |
| 6 | 同上 | `clarificationOnAmbiguousTaskRemainsEligibleUnderEnforcement` | 同上 | `null` | `:59` → `:129` | PRODUCT_DEFECT（根因 1+2） |
| 7 | `com.specagent.eval.P3BEnforcedCapabilityNecessityTest` | `invocationMissingADeclaredRequiredArgumentIsVetoedBeforeAnySideEffect` | 同上 | `null` | `:35` → `:144` | PRODUCT_DEFECT（根因 1+2） |
| 8 | 同上 | `invocationWithItsDeclaredRequiredArgumentPresentStillExecutes` | 同上 | `null` | `:59` → `:144` | PRODUCT_DEFECT（根因 1+2） |
| 9 | 同上 | `capabilityWithoutDeclaredRequiredArgumentsIsUnaffected` | 同上 | `null` | `:102` → `:144` | PRODUCT_DEFECT（根因 1+2） |

**是否影响实际 ActionEligibility 安全或业务语义？**

安全边界**没有被绕过**：失败是 fail-closed 的（决策从未执行、能力从未被调用）。
但**enforced 档在真实数据库上整体不可用**：每个受强制档约束的决策都在门禁之前
就以 `WAIT_SCOPE_MISMATCH` 失败，既没有留下裁决证据，也没有产生任何动作。
即"安全但不可用"。

### 根因 1（PRODUCT_DEFECT）：WAIT 范围校验把快照钉死在 Run 已挂载的那一份

`WaitDependencyRepository.requireScope` 原本以 `c.id = r.context_snapshot_id` 连接
`context_snapshots`，再要求 `c.id = :snapshot`。而回答周期的 DECISION 按设计使用
**事后(post-state)快照**（`AnswerCycleService` 第 511–516 行注释明确说明），
它不是 run 当前挂载的 `context_snapshot_id` → 谓词永不可能匹配 →
`WAIT_SCOPE_MISMATCH`。

**决定性证据**（诊断运行，直接读库）：

```
runRows = [{id=aa1a051f…, status=failed, run_route=3ba1b70f…,
             attached=518ed1a3…（hash f4694532…）, …}]
projectSnapshots = [{id=518ed1a3…, context_hash=f4694532…},   ← 回答前（已挂载）
                    {id=432b92a7…, context_hash=ac4f134f…}]   ← 事后（决策实际携带）
executionResult = failed:IllegalStateException: IllegalStateException: WAIT_SCOPE_MISMATCH
```

修复：范围改为钉在**请求实际携带的那份快照**上（`c.id = CAST(:snapshot AS uuid)`），
其余约束**一律保留**：同一 project、同一 route、快照 tip 等于请求 anchor、
快照存储的 `context_hash` 等于请求携带的 hash、run 仍是非终态。

### 根因 2（PRODUCT_DEFECT）：门禁留证被 wire 校验抢先

`DecisionExecutionService.execute` 先调用 `AgentBrainResponseValidator.validateDecision`，
后调用门禁的 `assess()/captureActionEligibility()`。V4 请求的响应校验会**重复**执行
资格校验并先行抛 `ActionIneligibleException` → 门禁永远拿不到裁决机会 →
`ACTION_ELIGIBILITY` 证据阶段从未产生。

修复：门禁**先裁决并留证**，wire 校验随后照旧 fail-closed，拒绝的家族与原因码完全一致。

### 根因 3（TEST_ENVIRONMENT_DEFECT）：评测脚本大脑不会说 V4 响应契约

`ScriptedBrain.responseProtocolVersion` 只映射 V3→`agent-decision.v3`，**V4 请求回落到
`agent-decision.v2`**，同时又附带资格选择字段 → 被产品契约正确拒绝：
`agent-decision.v2 must not carry eligibility selection fields`。

修复（**仅测试侧**）：脚本大脑按请求契约精确映射（v4↔v4、v3↔v3、v2↔v2）。
产品侧校验一字未改，测试对资格裁决与 VETO 行为的断言也一字未放宽。

---

## 2. 独立基线复核（实际证据）

之前"回退本轮产品代码后仍然失败"的结论**证据不足**：那是在**已经迁移到 V103 的同一
数据库**上跑的旧版本测试，且只回退了本轮的两个 P0 文件，并未建立真正的基线。

本轮用**独立 worktree + 独立新库**重做：

| 基线 | worktree | 独立数据库 | 结果 |
|---|---|---|---|
| `ede535e`（C16 V4 WAIT 之前） | `E:\project\baseline-ede535e` | `spec_agent_p3b_live_base20261021`（全新迁移） | **9/9 通过** |
| `ce16b77`（引入 V4 WAIT） | `E:\project\baseline-ce16b77` | `spec_agent_p3b_live_v4regr20261021`（全新迁移） | **9/9 失败**，失败签名与今天完全一致 |
| `24ab221`（本轮起点） | 主工作区 | `spec_agent_p3b_live_iso20261021` | 9/9 失败 |
| 本轮修复后 | 主工作区 | 同上 | **9/9 通过** |

**结论：这 9 项失败不是历史遗留，而是 C16 V4 WAIT 提交 `ce16b77` 引入的回归。**
`git ls-tree` 同时确认：3 个测试类在 `ede535e` 已存在，而门禁的 `dependencies.freeze(...)`
正是 `ce16b77` 引入的。

两个基线 worktree 已 `git worktree remove` + `prune` 清理；两个基线库仍在本轮专用隔离
容器内（`spec_agent_p3b_live_base20261021`、`spec_agent_p3b_live_v4regr20261021`），
未触碰任何既有数据库。

---

## 3. 生产代码与测试的具体修改

| 文件 | 性质 | 内容 |
|---|---|---|
| `backend/src/main/java/com/specagent/agent/runtime/WaitDependencyRepository.java` | 生产 | 范围校验改钉在请求携带的快照（其余约束不变） |
| `backend/src/main/java/com/specagent/agent/runtime/DecisionExecutionService.java` | 生产 | 门禁先裁决并留证，wire 校验顺序不变 |
| `backend/src/main/java/com/specagent/agent/runtime/ContinuationCoordinator.java` | 生产 | 非终态 run → `RUN_NOT_TERMINAL` 判定（不再抛异常） |
| `backend/src/main/java/com/specagent/agent/runtime/ContinuationVerdict.java` | 生产 | 新增 `RUN_NOT_TERMINAL` 判定（证据 = agent_runs 状态行） |
| `backend/src/main/java/com/specagent/retrieval/workload/RebuildLeaseCoordinator.java` | 生产 | 租约心跳续期 + 过期重排队次数上限 |
| `backend/src/test/java/com/specagent/eval/ScriptedBrain.java` | 测试 | 按请求契约精确映射响应版本 |
| `backend/src/test/java/com/specagent/agent/runtime/WaitScopeAcceptanceTest.java` | 测试（新增） | 5 项：事后快照被接受；跨项目/跨 tip/哈希不符/终态 run 全部仍被拒 |
| `backend/src/test/java/com/specagent/agent/runtime/ContinuationOwnerPendingAcceptanceTest.java` | 测试（新增） | 非终态 owner 的检查按代次收口，Run 终态化后仍创建续跑子 Run |
| `backend/src/test/java/com/specagent/retrieval/workload/RebuildLeaseAcceptanceTest.java` | 测试（新增） | 存活 worker 续租且不改围栏代次；反复丢租约后按 FAILED 收敛 |
| `backend/src/test/java/com/specagent/retrieval/RebuildLeaseIntegrationTest.java`、`…/workload/RebuildReadinessOfflineTest.java` | 测试 | 适配新的协调器构造参数 |

**安全边界未被放宽**：没有放宽 ActionEligibility、没有接受非法绑定摘要或
`legacy-unbound`、没有绕过 JSON Schema required、没有放宽嵌套参数引用校验、
没有允许跨项目订阅、C03 仍为 REJECT、生产默认 Prompt 未改、
V98–V103 历史迁移一字未改（`git diff --name-only -- db/migration` 为空）。

---

## 4. 两个 P2

### P2-A：非终态 owner Run 的 continuation 无限重试 —— 已修复

- **触发状态**：owner Run 处于任一非终态（`created/running/context_built/model_called/reflected/persisted`）。
  终态 `FAILED` → `FAILED` 判定（不重试）；`COMPLETED` → 正常评估。
- **原有机制**：**没有**最大重试次数、**没有**退避、**没有**超时、**没有**可观察失败状态。
  `process()` 抛异常 → `releaseClaimQuietly()` → 下一个 poll 重新认领同一条记录。
- **后果**：每个 poll（默认 2s）一次认领/写回，持续占用 outbox 行、持续刷 WARN；
  无重复副作用（子 Run 创建有幂等键围栏）。
- **修复**：非终态映射为显式判定 `RUN_NOT_TERMINAL`，该检查按代次正常收口。
  **可恢复性未被丢弃**：Run 终态化时 `AgentRunTerminalizationService` 会重新请求
  同一条检查，此时 Run 已终态 → 续跑子 Run 照常创建（回归测试显式断言了这一点）。
- **没有**用"重试上限"丢弃有效续跑。

### P2-B：本地模型冷启动超过默认重建租约 —— 已修复（未增大超时）

- **实际租约机制**：`rebuild-lease-seconds`（默认 120，范围 100–300）在认领时一次性写入
  `lease_until`；**全仓没有任何续租/心跳**；`recoverExpired()` 把过期的 RUNNING 行
  无条件重排队（`error_code='REBUILD_LEASE_EXPIRED'`），**没有次数上限**。
- **合法最长耗时**：split 批次 deadline 30s + index 批次 deadline 90s = **120s**，
  与默认租约**同量级且无余量**；而 embedding 模型冷启动（本地 Ollama 639MB）
  足以让单次请求逼近该上限。
- **实际观测**：跨进程验收中首个 Worker 的租约到期，另一 Worker 以
  `execution_epoch=2` 接管并重做同一份工作（结果被围栏正确拒绝，无数据损坏）。
- **修复（两项，均不增大超时）**：
  1. **租约续期**：协调器持有本实例的租约令牌，由守护心跳按 `(id,epoch,lease,worker)`
     围栏续期（默认周期 = 租约/3）。进程死亡 → 心跳停止 → 租约照常过期并被接管；
     续期不改变围栏代次。
  2. **重排队上限**：`recoverExpired` 只在 `execution_epoch < rebuild-max-attempts`
     （默认 3）时重排队，超过后按 `FAILED / REBUILD_LEASE_EXPIRED` 收口，
     使失败可观察而不是无限重做同一份工作。**复用已持久化的 `execution_epoch` 作为
     尝试计数，因此不需要新迁移**，历史迁移序列未被改写。
- **未做**：没有把租约本身无限调大；没有改变 split/index 的批次 deadline。

---

## 5. 本轮实际执行的命令与结果

| 命令 | 结果 |
|---|---|
| `gradlew testDatabaseIntegration -PdatabaseIntegration=true`（本轮修复后） | **PASS — 94 tests / 22 classes，0 失败 0 错误 0 跳过** |
| 同上（`ede535e` 基线 + 独立新库） | **9/9 PASS**（基线复核） |
| 同上（`ce16b77` 基线 + 独立新库） | **9/9 FAIL**（回归点定位） |
| `gradlew testOffline` | PASS |
| `gradlew -p independent clean test verificationScope` | PASS |
| `pytest agent-brain/tests tools/tests -q` | **507 passed** |
| `gradlew bootJar` | PASS（两个 P0 修复后重建，用于跨进程验收） |

**逐类结果**（数据库集成套件，全部 0 失败）：
`ActionEligibilityEnforcementIntegrationTest` 4、`P3BC09AmbiguousGateEnforcementTest` 2、
`P3BEnforcedCapabilityNecessityTest` 3、`WaitScopeAcceptanceTest` 5、
`ContinuationOwnerPendingAcceptanceTest` 1、`RebuildLeaseAcceptanceTest` 2、
`C16RetrievalAwaitGenerationEndToEndTest` 1、`C16ReadinessFailureAcceptanceTest` 7、
`C16CrossProcessWorkerAcceptanceTest` 3、`IsolatedMigrationAcceptanceTest` 8，
以及既有 16 个评测/追踪集成类。

---

## 6. C16 与 V98–V103 验收证据是否仍然有效

**仍然有效**，且本轮在其之上又修了一个更深的缺陷：

- 上一轮 C16 的 3 个验收类（主链路 1、失败路径 7、跨进程 3）在本轮修复后**全部通过**
  （它们包含在 94 项全绿结果中）。
- `IsolatedMigrationAcceptanceTest`（8 项迁移验收）**全部通过**。
- 上一轮的两个 P0 修复**仍然存在**：
  `RebuildWaitRepository.pending()` 仍按 `invocation_id` 读取；
  `ContinuationCoordinator` 仍使用父 Run 持久化委托绑定 actor。
- **重要补充**：本轮根因 1 揭示，C16 V4 WAIT 引入后 **enforced 档在真实数据库上整体不可用**
  ——这是上一轮仅覆盖 C16 专用路径、没有覆盖 ActionEligibility 强制档所遗漏的。
  修复后，enforced 档的 9 项验收与 C16 的 12 项验收同时成立。

---

## 7. 剩余发布风险

| 风险 | 状态 |
|---|---|
| 9 项 ActionEligibility 失败 | **已修复**（2 个生产缺陷 + 1 个测试契约缺陷），并有回归测试 |
| P2-A 无限重试 | **已修复**（判定收口 + 终态化重新请求，可恢复性保留） |
| P2-B 冷启动/租约 | **已修复**（续租 + 次数上限，租约长度未改） |
| 重建租约心跳线程 | 新增守护线程；仅在持有租约时产生写操作；进程退出即消失；无新增迁移 |
| `rebuild-max-attempts` / `rebuild-lease-heartbeat-ms` | 新增可配置项，默认值保守（3 次 / 租约÷3） |
| V98–V103 历史迁移 | 一字未改（`git diff --name-only -- db/migration` 为空） |
| 未执行的场景 | 真实付费模型与真实生产检索服务（按授权排除）；外部服务套件（`external-service` 标签）本轮未运行 |

**是否适合推送 dev**：本轮所有改动均已在隔离真实数据库上取得直接回归证据，
工作区除用户原有的删除状态外无未提交改动，`main` 与 PR #19 未被触碰。
从代码与测试证据看已具备推送条件；**推送与否属于发布决策**，本轮按要求不推送。

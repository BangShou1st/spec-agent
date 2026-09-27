# 第四轮交付独立复核

日期：2026-09-27。本轮未修改产品代码，只新增复核材料。

## 结论与已确认进展

旧全局“继续生成”“重新请求”“重试起草”的特定文案/兼容分发已清理；刷新续接调用共享 finalizeRetryCompletionAction，前一轮指出的“只更新失败清单”路径已改动。现有 testNonLive XML 汇总为 272 套件、1,650 项、0 failures/errors/skipped；Playwright .last-run.json 为 passed。本轮没有重跑全部后端/前端/E2E，以上是已有测试产物核对。

仍不能接受“全部完成”：当前 epoch 条件 UPDATE 未对所有权切换与旧事务提交建立互斥，真实数据库竞争已复现。顶部也仍可执行恢复，不符合“仅汇总和定位”的要求。

## R4-A / P1：epoch 子查询读取旧快照，旧事务仍可在接管后提交

位置：

- backend/src/main/java/com/specagent/agent/runtime/AgentRunRepository.java:69（FENCED_STATUS_GUARD）
- 同文件 :86（fencedClaimGuard）、:350（fail 条件）
- backend/src/main/java/com/specagent/agent/runtime/ExecutorLease.java:146（接管递增 epoch）

当前 UPDATE 在 WHERE 中读取：

```sql
(SELECT epoch FROM executor_ownership WHERE id = 1) = :ownerEpoch
```

这条 UPDATE 本身是原子的，但查询所有权行使用语句快照，并未锁住所有权行至业务事务提交。另一个连接仍可以更新 executor_ownership 并完成接管。将条件写进同一条 SQL，不能自动建立两个不同数据行/事务之间的提交互斥。

### 本次独立复现

编译当前 ExecutorLease、ExecutionFence、AgentRunService、AgentRunRepository 源码；在专用隔离数据库使用真实 Spring TransactionTemplate 与 PostgreSQL：

1. 旧执行器取得 epoch=1，任务为 running、owner_epoch=1。
2. 测试连接只锁住该任务行，不改变其内容。
3. 旧业务事务写入一条测试产物，并调用真实 AgentRunService.markModelCalled；UPDATE 在 PostgreSQL 内等待任务行锁。
4. 用 pg_stat_activity 确认 UPDATE 已在数据库中等待 Lock，不是暂停在 Java 方法入口。
5. pg_terminate_backend 终止旧租约会话，新执行器取得 advisory lock 并提交 epoch=2。
6. 释放测试任务行锁。旧 UPDATE 依据开始时的快照通过条件，旧业务事务提交。

实际输出：

```text
OLD_UPDATE_BLOCKED_INSIDE_POSTGRES=true
OLD_EPOCH=1
NEW_EPOCH=2
OLD_TRANSACTION_COMMITTED_AFTER_TAKEOVER=true
FINAL_RUN_STATUS=model_called
OLD_BUSINESS_EFFECT_ROWS=1
```

测试产物是隔离库中的 review_business_effect 行，不是真实 Answer/规格。它和生产检查点处于同一 Spring 事务，证明“业务产物与条件检查点放在一起便必然回滚”的事务机制仍有反例；本次没有把所有业务端点都说成已动态复现。

证据：scratch/round4-review/SnapshotFenceHarness.java、snapshot-fence-result.txt。隔离数据库 spec_agent_review_snapshot_20260927 已删除，连接和工作线程均清理。

### 为什么新增屏障测试仍然通过

ExecutionFenceAtomicityBarrierTest 暂停在 Java Repository 方法内部、SQL 发出之前。恢复旧线程时 SQL 才开始，因此能读到 epoch=2 并拒绝。它覆盖了上轮的具体顺序，但没有覆盖“SQL 已开始、正在等数据库锁时接管”的交错。

PostgreSQL 的 Read Committed 使用语句快照；更新等待目标行后，并不因此重新读取其他行的最新状态。此行为与官方[事务隔离文档](https://www.postgresql.org/docs/current/transaction-iso.html#XACT-READ-COMMITTED)一致，也已经由本地真实数据库验证。

### 修复与验收方向

让业务事务和接管事务真正竞争同一个所有权记录/锁：业务写入前在同一业务事务、同一连接上锁定并验证所有权代次，保持锁直到提交；接管的代次变更取得与之冲突的锁。必须统一锁顺序，避免项目/任务锁与所有权锁倒置形成死锁。

可接受的顺序是：旧事务先完成，接管等待它结束后才提交；或者接管先完成，旧事务验证新代次后整体拒绝。不能出现新代次已提交、旧事务仍按旧快照成功提交的结果。

保留现有 token 与终态条件，但不要仅再加普通 SELECT、同样的快照子查询或 Java 检查。也不要直接假定把隔离级别改为 Serializable 就满足“接管提交后旧提交必须被排除”的实时顺序；需要针对所选协议验证。

新增本次 SQL 等锁交错的真实 PostgreSQL 测试，并覆盖未提交业务产物、claim、checkpoint、complete、fail 的适用路径及锁顺序。外部副作用维持单独的幂等/执行授权边界，不放进长时间持锁的数据库事务。

## R4-B / P2：顶部仍能执行重试，不是仅汇总和定位

位置：frontend/src/features/workspace/WorkspaceView.vue:1085、:1090；NodeRecoveryBar.vue 的 onIconClick / retry 按钮。

旧文案及无目标分发确实已删除；但顶部 pending-recovery-banner 仍渲染完整 NodeRecoveryBar，并绑定：

```vue
@retry="handleRetryFailure"
```

单失败时圆箭头 emit('retry')，多失败列表也可发 retry。handleRetryFailure 对普通失败直接调用 store.retryFailedRun。因此顶部仍可执行生成/恢复，和节点上的入口并存，不能在报告里称作“仅汇总和定位”。

当前留存截图 frontend/test-results/retry-reload-shots/2026-09-27T13-29-28-803Z/retry-reload-failed.png 也显示页面顶部圆箭头，与下游失败卡的重试按钮同时存在。

应为顶部提供只读恢复摘要/定位模式：显示“N 项待处理 · 查看”，展开后每项定位到正确节点、失败占位卡、规格面板或查询检查器。不能只是移除监听器却保留看似能重试的按钮。真正的恢复动作仅在对应失败位置提供。

验收：顶部的每种交互均不产生 POST .../retry 或生成请求；能定位实际目标。节点/规格/检查器内的明确恢复动作继续可用。不能只断言页面不包含旧文案。

## 本轮范围说明

本次确认上述两个问题，认可旧文案删除和共享完成流程的进展。报告所列首次 retry-reload 截图目录当前不存在，但后续完整运行留存了上述新的时间戳目录；本次查看的是实际存在的后续截图。

没有修改产品代码、没有调用真实模型、没有使用真实厂商密钥、没有操作用户业务库。既有真实厂商验收、漏洞扫描、历史升级等外部门禁继续保留。

## 新会话交接前补查：R4-C / P1，已明确丢锁但没有接管者时仍可写检查点

复核日期同上。进一步沿写入调用链检查并做真实数据库复现：

- ExecutionFence.ownerEpoch() 直接返回租约对象的 epoch，不检查永久丢失标记。
- AgentRunService.markModelCalled/attachContext/markReflected/markPersisted* 已不再调用同步所有权检查，直接执行 epoch 条件写入。
- 如果租约会话已经被终止、assertOwned 已检测并永久闩锁，但尚无新执行器将全局 epoch 递增，旧 epoch 仍等于全局值，更新通过。

独立测试结果：

```text
LEASE_PERMANENTLY_LOST=true
WRITE_AFTER_KNOWN_LOSS_ACCEPTED=true
RUN_STATUS=model_called
```

证据：scratch/round4-review/LostOwnerHarness.java、lost-owner-result.txt。测试使用隔离库 spec_agent_review_lost_owner_20260927，结束后已清理。

修复时需同时满足“已知丢锁后拒绝新写入”和“接管与提交的数据库互斥”，不能只修其中之一。重新加本地丢锁检查可以关闭这条具体路径，但不替代 R4-A 所需的数据库事务协议。

### 同一修复中的关联验收要求（以下为源码核对，不声称都已动态复现）

- AgentRunFailureService.fail 返回 void 且丢弃更新行数；RunWorker.failIfNotTerminal、AgentRunOrphanRecoveryService.terminalizeInterrupted 在其后仍无条件 append RUN_FAILED。需要把状态转换结果和事件/outbox 一致性纳入协议：条件更新未生效时，不得追加会改变业务解释的“失败已发生”事件或把恢复计数记为成功。
- AgentRunFailureService 使用 REQUIRES_NEW；孤儿恢复外层另有 TransactionTemplate。引入所有权/任务行锁时必须检查内外事务锁顺序及自等待，不能一层锁住后调用另一连接等同一锁。
- 本轮新增 V42 表，下一轮需验证空库带 worker 启动及 V41→最新升级，明确迁移完成先于租约读取所有权表。这是补充启动验收要求，尚未将当前启动顺序判定为已复现缺陷。
- 顶部定位应覆盖失败占位卡、共享节点的具体路线、规格面板与节点查询检查器；仅 selectNode(sourceNodeId) 不能替代这些不同目标的实际定位。

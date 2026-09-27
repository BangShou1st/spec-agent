# 第三轮交付独立复核

日期：2026-09-27。本轮只复核，未修改产品代码。

## 结论

第三轮有明确进展：回环守卫原反例已被独立复跑阻止，模板注释不再出现在新截图中，起草失败占位卡确有任务级恢复入口，租约连接失效也已有检测。但“全部反例闭合”“新执行器接管后旧执行器无法提交”的结论仍不成立。

保留一个 P1 核心问题（数据库所有权与写入没有原子保障，包含终态写入约束缺失）和一个 P2 恢复结果刷新问题。不要再次以“无分布式共识无法消除”的表述把当前实现缺口当作正常限制。

## 验证范围

- 读取现有 testNonLive XML：271 个套件，1,645 项，0 failures/errors/skipped。此为已有产物核对，本次未重跑全套。
- frontend/test-results/.last-run.json 标记 passed；不能仅凭它独立确认 80 项计数。
- 独立重新编译当前 LoopbackBindingGuard，复跑旧的最小 Spring/Tomcat 守卫反例：进程退出码 1，明确因非回环配置失败，没有 UNSAFE_CONTEXT_STARTED。
- 独立执行真实 PostgreSQL 的有界竞争复现：使用当前 ExecutorLease、ExecutionFence、AgentRunService、AgentRunRepository 源码，专用隔离库与最小任务表，不加载产品业务数据。
- 直接提取并执行当前 resumeInFlightRetryWatches 函数，检查成功后的调用顺序；依赖使用可观察的测试替身，不声称是浏览器全链路实测。
- 查看本轮 draft-failed-placeholder.png；开发注释已消失，节点恢复入口可见。未重做全部浏览器验收。

## R3-A / P1：先检查租约再写入，不是原子的旧执行器写入隔离

位置：

- backend/src/main/java/com/specagent/agent/runtime/ExecutionFence.java:28
- backend/src/main/java/com/specagent/agent/runtime/AgentRunService.java:223
- backend/src/main/java/com/specagent/agent/runtime/AgentRunRepository.java:305
- backend/src/main/java/com/specagent/agent/runtime/ExecutorLease.java:43

ExecutionFence 调用专属租约连接的 assertOwned()。检查通过后，AgentRunService 再调用 Repository，通过另一条业务连接更新任务。数据库不会因之前在其他连接上查过 pg_locks，就自动阻止此后的旧进程写入。租约 epoch、所有权行锁或等效的数据库事务级互斥没有参与该写入。

可确定重现的顺序：

1. 旧执行器持锁，调用真实 AgentRunService.markModelCalled。
2. 当前生产 ExecutionFence 检查通过；测试仅在进入 Repository 写入前设同步屏障。
3. 用 pg_terminate_backend 终止旧租约会话，新执行器取得锁。
4. 新执行器通过真实服务将原 run 恢复为 failed。
5. 放开屏障，旧执行器继续执行真实 Repository SQL。

结果：

```text
STATE_AFTER_NEW_OWNER_RECOVERY=failed
STATE_AFTER_OLD_DELAYED_WRITE=model_called
```

即，新执行器已终态化的任务被旧执行器改回非终态。暂停点模拟正常线程调度，不依赖概率性 sleep，也没有替换 fence 的判断结果。

报告所谓“单条语句窗口”没有给出实际时间上界：旧线程可以在检查通过后暂停任意时间。DecisionExecutionService:127 的检查还在模型请求之前，而模型返回后的 actionExecutor.execute 在其后执行；这也说明不能把写入与外部动作保护泛称为“每次副作用前都已校验”。本次动态复现证明的是 run 检查点写入，不把其他动作路径都说成已动态复现。

### fail() 无条件放行不是终态安全保障

AgentRunRepository.fail 的 WHERE 只有 id，无所有权与预期状态条件。隔离测试额外通过真实服务将一行设为 completed，再调用已失去租约的旧服务 fail，结果为 failed：

```text
STATE_AFTER_NEW_OWNER_COMPLETE=completed
STATE_AFTER_OLD_OWNER_FAIL=failed
```

这项是服务/仓储层反例，不表示每个生产调用都必然覆盖 completed：部分调用方有先读终态的检查。但是“先读再无条件写”同样有竞争，仓储层没有保证终态不可覆盖；旧进程还可覆盖新进程写入的失败原因。

### 修复与验收要求

采用在现有 PostgreSQL 内可验证的原子协议，使所有权校验、业务写入/检查点与接管操作有统一的事务顺序。例如持久化单调所有权代次，业务事务验证并持有所有权记录上的锁直至提交，接管通过同一记录的互斥更新完成。应评估并实现符合项目架构的方案，而不是机械堆叠查询。

单独加一次检查、简单把 Java 代码放进 @Transactional、或在另一个连接上检查 pg_locks，都不足以证明原子性。即使使用 epoch，也不能只做与提交无锁关联的先读后写。

失败终态化应有原子的预期状态/所有权条件，不能让旧进程任意改写终态。未拥有任务的旧执行器可记录本地故障，把业务恢复交给当前所有者。外部副作用需单独说明现有幂等与所有权边界，不能承诺数据库事务能回滚外部请求。

验收必须保留“检查通过→暂停→丢锁→新执行器接管并恢复→旧执行器继续”的屏障测试。上轮测试只覆盖“先丢锁→再检查→拒绝”，不足以覆盖该竞争。

证据：scratch/round3-review/FenceRaceHarness.java、fence-race-result.txt。隔离数据库 spec_agent_review_fence_20260927 已在 finally 清理，连接和线程已关闭。

## R3-B / P2：刷新后续接的重试成功，只清失败清单，不更新业务结果

位置：frontend/src/features/workspace/state/workspaceLoader.ts:271。

resumeInFlightRetryWatches 在硬刷新后恢复观察在途 retryRunId，但终态后只调用 rebuildUnresolvedFailures，没有 refreshWorkspace 或按操作刷新节点查询/规格结果。

一般用户点击 retryFailedRunAction 的旧路径会在成功后刷新工作区。但硬刷新会销毁旧页面及该调用栈，新页面依赖新 watcher，不能继续依赖旧页面的刷新动作。

生产函数级验证：给 watcher 一个在途重试，令轮询完成并报告新节点，记录所有调用：

```text
calls = ["poll:retry-run", "rebuild-failures"]
graphAfterRetryCompletion.nodes = ["old-node"]
```

故恢复入口可消失，但本地 canonical 图/新产物仍是重试完成前的版本；用户需要再次刷新才能看到结果。

现有起草 E2E 在新的失败条目出现之后刷新，再点击重试，并未通过受控暂停稳定覆盖“重试运行中刷新→后端完成→不再操作页面也自动显示结果”。

修复建议：把同会话终态后的结果刷新与恢复清单对账作为共享完成路径，由主动重试和重建 watcher 复用；根据操作刷新图、规格或节点查询结果。保留会话过期保护，区分 unknown 与已确认终态，避免递归重复观察/刷新。

验收：用测试屏障让重试保持 running，硬刷新后再释放后端，断言新节点/规格/查询结果自动可见且提示消失，不允许测试最后额外刷新页面补救。

证据：scratch/round3-review/retry-watch.cjs、retry-watch-result.json。

## 本轮认可的进展与交付表述

回环守卫旧启动反例已被阻止；新的起草失败截图不再出现开发注释，节点失败入口有实际界面证据；现有后端通过记录可核对。以上不抵消旧执行器能破坏任务终态的反例。

下一步应集中修复 R3-A、R3-B，不必重做已闭合事项或继续扩展功能。原报告的真实厂商验收、漏洞扫描、历史升级与显式提案 barrier 浏览器门禁继续保留。不能将“全套现有测试通过”替代上述并发与刷新场景的行为保证。

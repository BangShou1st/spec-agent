# 第二轮交付复核

日期：2026-09-27。本轮为独立复核，未修改产品代码。

## 结论

第二轮存在实质改进，worker 启动门控以及任务级恢复 API 已落地，但“修复全部完成”“失败位置独立恢复完整验证”不能接受。当前仍有守卫失效、执行器丢锁未察觉、起草失败卡丢失操作身份等可复现问题。

已有 testNonLive XML 汇总为 268 个套件、1,630 项、0 failures；Playwright .last-run.json 为 passed。本轮未重新执行全部测试，不能把这些产物说成本轮独立重跑，也不能仅据 .last-run.json 证明 79 项全部路径均覆盖。

## R2-A / P1：回环守卫两层检查均失效

位置：backend/src/main/java/com/specagent/common/security/LoopbackBindingGuard.java:28、:41、:105。

- 类已不再 implements ApplicationRunner，run(ApplicationArguments) 也没有其他生命周期调用入口。测试直接 new guard.run(null) 仍可通过，但真实 Spring 启动不会调用该方法。
- WebServerInitializedEvent 分支通过反射调用 Tomcat Connector.getProperties()。本项目 Tomcat 10.1.26 的 Connector 没有该方法，异常被捕获后返回 null，外层直接放行。
- 因而空地址与明确非回环地址均没有得到报告所述启动保障。

独立复现：直接编译当前 guard 源码，将它放进最小 Spring/Tomcat 上下文，设置实际地址 0.0.0.0、风险开关 false、随机端口。结果：

```text
GUARD_IS_APPLICATION_RUNNER=false
TOMCAT_GET_PROPERTIES=false
UNSAFE_CONTEXT_STARTED=true
BOUND_ADDRESS=0.0.0.0
```

复现上下文不加载产品 API 或数据库，结束后关闭服务器。证据：scratch/round2-review/GuardHarness.java、guard-result.txt。

修复验收应通过实际 Spring 启动与实际 connector 地址测试，不能只直接调用辅助方法。校验读取失败也不能被等同于安全回环。

## R2-B / P1：数据库锁已释放，旧执行器仍自认持有所有权

位置：backend/src/main/java/com/specagent/agent/runtime/ExecutorLease.java:90、:95；WorkerPollingGate.isPollingAllowed。

owned() 和 assertOwned() 只读布尔值。该值获取锁后为 true，仅 destroy() 改为 false；没有检查专属连接存活或数据库所有权。

数据库重启、连接被终止等情况可使 PostgreSQL 释放会话锁，但应用进程仍存活。新进程取得锁后，旧进程通过池中的其他连接仍可能认领任务，违背恢复所依赖的单执行器前提。

独立复现：创建隔离数据库，使用非连接池的真实 PostgreSQL 连接获取锁；关闭旧执行器的物理租约连接但不销毁执行器对象，再创建第二个执行器：

```text
NEW_EXECUTOR_ACQUIRED=true
OLD_EXECUTOR_STILL_REPORTS_OWNED=true
OLD_EXECUTOR_ASSERT_OWNED_PASSED=true
```

测试后两个对象均清理，隔离数据库已删除。证据：scratch/round2-review/LeaseHarness.java、lease-result.txt。

需补失去租约后的停止认领、健康失败及在途写入保护策略；重新连接不自动等于恢复所有权。验证真实断连/终止租约连接，不能只验证主动 destroy()。

## R2-C / P1：起草失败卡没有接上任务级恢复身份

位置：frontend/src/features/workspace/WorkspaceView.vue:358；GraphQuestionNode.vue:397。

pendingProjections 优先放入 legacy pendingRouteProjection 并把 runId 写入 seen。该对象未补入 failure。后续 unresolved failure 因 seen 被跳过，而新的按钮只在 data.pendingFailure 存在时显示。因此当前页面刚发生的起草失败可能显示失败卡却没有卡内恢复按钮。

另一路径中，registry 的失败条目加入 projections 后没有加入 seen；同一 run 的 unresolved failure 随后再次加入，产生相同 run 的两条投影。

独立复现直接提取并执行当前 WorkspaceView 的 computed 函数体（TypeScript 转译），使用同一失败任务的三类实际状态输入：

```text
legacyFailure: 1 条 FAILED 投影，hasFailureIdentity=false
registryFailure: 同一 runId 两条 FAILED 投影，一条无身份，一条有身份
```

证据：scratch/round2-review/projection.cjs、projection-result.json。这是生产投影函数级复现，尚未宣称完成该路径的浏览器复现。

应统一按任务/恢复链合并，补全身份，再投影；覆盖首次失败、刷新后失败、恢复在途、再次失败、最终成功。报告已承认起草失败浏览器实测未完成，这一项不能标记为完整交付。

## R2-D / P2：临时服务故障被永久导向设置页

位置：backend/src/main/java/com/specagent/agent/runtime/AgentRunRecoveryService.java:146、:163；frontend/src/features/workspace/WorkspaceView.vue:457。

isModelConfigFailure 把 brain_unavailable、model_provider_failure 都当成配置错误。实际 BrainFailureCode 明确包含连接拒绝、5xx、服务不可用；它们不能证明凭据配置错误。

读取恢复动作只依据旧失败事件。即使服务恢复、用户修正设置并返回，同一个失败事件不变，界面仍只给 GO_TO_MODEL_SETTINGS，无法在原位置重试。原有“稍后重试”错误文案与动作也冲突。

应区分可确认的配置错误和临时故障；设置修正后重新评估或允许明确重试原任务，避免设置页循环。需要对服务短暂不可用→服务恢复→原位置恢复做回归。

## R2-E / P2：提交的验收截图已经显示产品 UI 缺陷

位置：frontend/src/features/workspace/graph/components/GraphNodeShell.vue:43、:79。

Vue template 内使用了 JavaScript 风格 /** ... */，会当作可见文本渲染。报告提供的 scratch/recovery-screenshots/answer-failed-recovery-bar.png 明确显示整段“自适应边锚点”“操作轨道”开发注释占据节点上下区域。

这不是推测；源码与截图相互印证。应改为合法模板注释并检查实际画面。截图产出成功不能替代截图检查。

## 测试证据还需收紧

- failure-recovery.spec.ts 使用 getByTestId('node-recovery-bar').first()。WorkspaceView 把顶部横幅放在画布前，顶部和节点都复用此 test-id，因此用例实际上可以点击顶部恢复按钮；未准确证明对应节点内的图标可操作。
- 建议定位具体 canonical node 的容器，再限定恢复栏和任务身份；另测顶部入口，避免混淆。
- 键盘用例名称包含画布拖拽隔离，但未比较节点位置/画布状态；该名称大于实际断言范围。
- retryFailedRunAction 在 await retryAgentRun 之后才 markRetryStarted，网络请求在途期间按钮尚未立即禁用。后端幂等是必要保障，但不能代替要求中的前端即时状态；需延迟响应测试。
- 同一节点的不同 NODE_QUERY 问题被 sameFamily + routeId/inputNodeId 当作同一任务，后一个不相关问题成功可隐藏前一个失败。应按可信意图或明确恢复链判定是否已解决，不能仅用位置和操作家族。
- AgentRunOrphanRecoveryService 内部逐行 catch 后不向上汇报失败，Listener 的 recoveryError 只记录外层异常；部分任务恢复失败仍可能显示恢复成功。需测试部分失败的健康告警。

## 复核范围与后续验收

本次独立执行三项有界复现：实际守卫上下文、真实数据库连接丢失、生产前端投影函数。另做源码核查及已留存截图检查。没有调用真实模型，没有更改用户业务数据，没有重跑 1,630 项或 79 项全套。

本轮新增复核文档与 scratch 证据；未修复产品实现。临时 Spring/Tomcat 进程已关闭，租约测试数据库已清理。下一轮应先闭合上述具体反例，再运行相关回归和关键浏览器场景。真实厂商验收、漏洞扫描、历史升级等原报告未完成项继续保留。

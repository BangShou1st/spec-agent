# 第五轮独立复核

日期：2026-09-28。本轮未修改产品代码。

**后续复核更新：R5-A 已关闭。** 下文 R5-A 保留为修复前的问题记录；补充修复后的核对结果见文末。

## 结论

本次认可上一轮两条数据库反例的修复和顶部仅定位模式的落地。独立重跑 ExecutionFenceAtomicityBarrierTest：8 项通过，0 失败、0 错误、0 跳过。当前确认的剩余问题是规格恢复的前后端操作名称不一致，不需要因此重做所有权协议。

## 独立验证与已核对证据

执行命令（backend 目录）：

```text
gradlew.bat testNonLive --tests com.specagent.agent.runtime.ExecutionFenceAtomicityBarrierTest --rerun-tasks
```

退出码 0，BUILD SUCCESSFUL，8/8 通过。日志：scratch/round5-review/focused-fence-tests.log。

源码检查确认：ExecutionFence 在事务绑定连接取所有权 FOR SHARE；业务事务及接管围绕同一所有权记录互斥；已知丢锁被入口拒绝。失败服务根据更新结果，仅在 APPLIED 时在同一事务追加 RUN_FAILED。

顶部 WorkspaceView 已设置 locate-only 且仅绑定 locate；NodeRecoveryBar 的只读分支实际不渲染 retry 控件，旧顶部恢复问题可关闭。

复跑之前，已有 testNonLive XML 汇总为 272 套件、1,654 项、0 failures/errors/skipped。本次只独立重跑上述 8 项；完整旧 XML 已备份到 scratch/round5-review/baseline-xml，避免定向复跑覆盖证据。未独立重跑前端全套或 83 项 E2E。

迁移日志核对：empty-db-startup.log 显示 V42 迁移完成时间先于租约获取；这些是上一轮保存的动态验收记录，本次没有再次新建完整应用做迁移演练。

## R5-A / P2：规格恢复的 operation 契约不一致

后端 RunService.createQueuedArtifactGeneration 创建的 run：

- triggerType = GENERATE_SPEC
- operation = GENERATE_ARTIFACT

UnresolvedFailureView.of 返回的是 run.operation()，不会把它转换为 triggerType。因此正常规格失败清单里的 operation 是 GENERATE_ARTIFACT。

前端却在两个位置判断 GENERATE_SPEC：

1. frontend/src/features/workspace/WorkspaceView.vue:624：handleLocateFailure 只有在 operation === GENERATE_SPEC 时才调用 specDockRef.open()。真实规格失败点击“查看”只选中/定位源节点，不展开规格面板。
2. 同文件 :1218：传给 SpecDock 的 spec-failures 也只筛选 GENERATE_SPEC。真实规格失败因此被过滤掉，规格面板拿不到恢复项。

第二个筛选还使用 store.activeRoute 而非面板正在查看的路线；修正枚举时应一起对齐显式阅读路线，避免查看分支 B 却展示/恢复路线 A 的任务。

### 函数级复现

直接提取并执行当前 handleLocateFailure 函数，外围 UI 方法用可观察测试替身：

```text
实际后端 operation=GENERATE_ARTIFACT:
  focus:r1 → select:n1 → locate:n1

前端误判值 operation=GENERATE_SPEC:
  focus:r1 → select:n1 → open-spec-dock → locate:n1
```

证据：scratch/round5-review/spec-locate.cjs、spec-locate-result.json。本次是生产函数级复现与契约源码核对，不声称已跑完整规格失败浏览器链路。

### 修复验收

- 前端以 API 的 operation 契约 GENERATE_ARTIFACT 为准，避免与后端 triggerType 混用；集中操作类型映射，检查同类分发和筛选。
- 规格失败点击顶部“查看”实际展开对应路线的规格面板，不发重试 POST。
- 面板显示该任务的失败原因及恢复按钮；点击恢复才提交该失败 runId。
- Active 路线 A、阅读路线 B、B 规格失败时，定位和恢复仍绑定 B。
- 使用真实失败接口载荷做前端/浏览器回归，不能在测试夹具中也手写错误的 GENERATE_SPEC 然后自证通过。
- 保留顶部只定位、节点恢复、刷新续接等已通过能力。

## 交付范围

核心数据库修复通过本次针对性复验；规格恢复功能仍有上述局部缺口，不能写成所有操作族全部完成。真实厂商验收、Java/Python 漏洞扫描、历史升级及显式提案 barrier 浏览器实测继续按原报告保留。没有真实模型调用，没有产品代码修改。

## R5-A 补充修复后的复核：已关闭

已核对当前源码：定位与失败项筛选均使用集中定义的 SPEC_GENERATION_OPERATION=GENERATE_ARTIFACT；SpecDock 的失败条目及路线标签使用阅读路线。

独立提取并执行当前生产定位函数和 specFailureEntries 计算表达式，验证：真实 operation 触发 open-spec-dock；阅读 B 仅返回 B 的失败，阅读 A 仅返回 A 的失败。结果 PASS。证据：scratch/round5-review/spec-contract-verified.cjs、spec-contract-verified-result.json。这是函数级复验，UI 依赖使用测试替身，非本次浏览器复跑。

核对新增 spec-failure-recovery.spec.ts：失败载荷取自真实 /unresolved 接口；断言顶部定位展开面板、定位不发送恢复/生成 POST、阅读路线切换过滤、面板恢复后 B 路线规格快照落库。保存日志 scratch/round5-evidence/spec-e2e-run.log 为 1 passed。

已有后端 XML 本次汇总为 272 套件、1,656 项、0 failures/errors/skipped；这是产物核对，未重新执行整套。前一轮独立执行的 8 项数据库屏障测试结果仍作为对应问题的复验记录。

本次针对 R5-A 的复核未发现新的阻塞项，上一轮明确指出的局部缺口已闭合。可以进入本机单用户形态的交付验收；原报告列明的外部门禁仍未完成，不能因此宣称真实厂商生产验收已全部通过。

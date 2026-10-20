# dev 修复、合并资格与后续 main CI

本轮按用户后续指示执行：先修复 dev 并验证，再评估能否合入 main，最后单独修复 main CI。未操作数据库、Provider 配置、生产 Prompt；新增真实模型请求为 0。原有删除、lockfile 及历史事故材料保留。

## dev 实际结果

| 检查 | 状态 | 结果及边界 |
| --- | --- | --- |
| 前端 `npm run typecheck` / `npm run build` | PASS | 构建完成；现有大 chunk 警告，不涉及页面验收 |
| `npm run test:unit -- --run` | PASS | 953 passed、1 skipped；跳过项不计成功 |
| Brain `python -m pytest agent-brain/tests -q` | PASS | 255 passed |
| 工具 `python -m pytest tools/tests -q` | PASS | 215 passed，包含场景镜像与历史机器证据回归 |
| `gradlew.bat testOffline --offline --rerun-tasks` | PASS | 完整 production/eval/test 源码重新编译；48 tests、0 skipped、0 failed；本机已有 Zen 解析材料，不代表干净 CI 可下载 |
| `gradlew.bat bootJar --offline` | PASS | 本机生产 jar 打包成功；没有启动应用 |
| `gradlew.bat -p independent clean test verificationScope --offline` | PASS | 85 tests、0 skipped、0 failed；真实源码白名单，不编译完整业务；包含 eligibility 30、输出契约 8、AE4 35、C03 6、评分分母 6 |
| independent `dependencyInsight --dependency opencode --configuration testRuntimeClasspath` | PASS | 无匹配依赖；独立 settings 不加载生产 build 或 composite |
| 未授权 `test` | PASS（拒绝反例） | task graph 阶段拒绝，未执行任务动作 |
| `testOffline bootRun` | PASS（拒绝反例） | task graph 阶段拒绝 |
| `testOffline test -PdatabaseIntegration=true --dry-run` | PASS（拒绝反例） | 显式集成授权也不能混入 offline 组合 |
| `testOffline qualifyGaNative -PexternalServices=true --dry-run` | PASS（拒绝反例） | 禁止 offline 与 live JavaExec 混用 |
| `testDatabaseIntegration -PdatabaseIntegration=true --dry-run` | PASS（入口配置） | 保留显式授权入口；仅任务图，未执行任何集成测试 |
| 真实 Spring 应用、隔离数据库集成、生产迁移 | NOT_MEASURED | 没有创建或访问数据库；本轮不把本地 Bean/Mock 测试当应用启动通过 |
| 干净环境完整后端 CI | BLOCKED | Zen 编译期类型在干净 CI 不可解析；没有移除默认生产依赖 |

`backend/independent` 是为真实独立编译建立的最小 Gradle 边界，直接引用已有源码，未复制实现、伪造 starter 或引入替代生产依赖。完整生产构建的默认依赖保持不变。

## 本轮修复

- AE4：普通/组合/层级 Spring Context、Boot slice 与组合外部服务标签的授权漏洞；明确白名单与任务组合预执行拒绝；C03 纯规则与集成类型发现分开，原断言保留。
- P3B：UNKNOWN 不再稀释 eligibility FN/FP 分母；新增六例纯评分反例，未知、INFRA、known gap 分开。C01–C18 Java/JSON/Oracle 静态镜像核对；C10/C11 只补状态说明，不改标签。
- 真实证据：Kilo 26 次有机器记录，模型选择 6/10，另外 3 次未测；C07 不必要调用 0/2、C03 重复追问 1/2。C06 无 live 记录；额外两次诊断只具文档证据。Gemini 8 次 A/B 有机器记录，额外 1 次兼容探测只有报告说明；REJECT 保留。
- 架构：C03 候选不推广；C07 参数齐全不等于调用必要性/执行授权；C13/C15 门禁与来源内容推断分层；C16 缺 pending dependency 表示，交付最小契约设计与回归矩阵，不用关键词模拟 WAIT 支持。

详见 [AE4](ae4.md)、[P3B](p3b.md)、[真实证据](live-evidence.md)、[架构及合并审查](architecture.md)。早期失败日志保留，最终 `dev-offline-final.log` / `dev-independent.log` 是最后统跑结果；两套测试有重叠，不能相加成独立覆盖率。

## dev → main 的决定

重新 fetch 后 main=`74b9815d69a562d6782c4efbef1b7daf3f075905`，dev 原 HEAD=`899ccbc2d7476e57ff1487ae88fd15ac9483d356`，main 是其祖先，0 behind / 28 ahead，技术上可 fast-forward。

当前不合并、不推送 main。dev 相对 main 含生产模型绑定、协议/Prompt 与 V98/V99 迁移，不只是本轮离线修复。V99 会更新旧活动运行的绑定指纹，存在主动 fail-closed 的业务行为；需要获准的新隔离库验证迁移、升级、活动/审批 run、重启和跨实例一致性。当前缺这些证据，不能把无冲突等同于发布验收通过。

后续 main CI 使用隔离的小分支，只包含独立验证与诚实状态报告，不夹带这 28 个提交，不直接推送或合并 main。所有远端运行链接和提交结果在 CI 交付报告单独记录。

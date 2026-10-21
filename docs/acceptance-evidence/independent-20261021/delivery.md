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

## 后续 main CI 实跑交付

按上述顺序完成 dev 修复/合并评估后，CI 小分支 `codex/main-ci-independent` 已提交并推送：`f004530c64a96a4a1e3361b005cba67e6dbeb843`。分支基于 main、仅 1 个 CI 提交和 8 个文件，不含 dev 生产改动。main 本身未修改、未合并；没有创建 PR。

真实远端运行：[39463930433](https://github.com/BangShou1st/spec-agent/actions/runs/39463930433)，`workflow_dispatch` 显式 `full_validation=false`，独立范围结果成功。已下载远端 JUnit/source/dependency 产物核对，元数据留在 `ci-run.json`。

| job | 状态 | 实际结果 |
| --- | --- | --- |
| backend-independent | PASS | 54 个生产 Java 源文件，38 tests / 0 failed / 0 skipped；远端依赖图无 sharedlibs/Zen |
| frontend | PASS | 类型检查/生产构建通过；952 passed、1 skipped（不同于 dev 的 953 passed） |
| brain | PASS | 246 passed（不同于 dev 的 255 passed） |
| contracts-static | PASS | 141 passed（仅 main 已有工具加 CI 配置回归，不冒充 dev 的 215） |
| validation-mode / validation-summary | PASS | scope 显式；失败、取消、意外跳过会使 summary 失败，无 continue-on-error |
| backend | SKIPPED_EXTERNAL_DEPENDENCY | 完整业务编译依赖真实 Zen 类型 |
| eval-baseline | SKIPPED_EXTERNAL_DEPENDENCY | 完整 eval/应用编译及数据库链路依赖 |
| cross-language | SKIPPED_EXTERNAL_DEPENDENCY | Java-Python 真实进程互调未执行；静态共享 fixture 另由独立任务覆盖 |
| e2e | SKIPPED_EXTERNAL_DEPENDENCY | 后端真实启动/浏览器端到端未执行 |
| 实时模型/完整应用启动/真实数据库及迁移 | NOT_MEASURED | 无新增实验、无数据库操作 |

原 CI Run #171 的全部四个失败日志已审查，首要原因确为 starter 解析失败。分支保护只读查询返回 `Branch not protected`，仓库 rulesets/active rules 为空；没有改 required checks 或保护配置。恢复完整验证保留手动 `full_validation=true` 入口，但本轮未启用。

结论是**独立 CI 分支已真实验证通过，完整后端 CI 验证仍受外部依赖限制，main 尚未应用修复**。不是“所有 CI 已修复”。

Git 状态：dev 修复代码提交 `b487993`，补充证据提交另见 `git log`；dev 未推送。原有 `report.md` 删除、`agent-brain/uv.lock`、`docs/P3B_AE4_DATABASE_INCIDENT_REVIEW.md`、`docs/acceptance-evidence/p3bae4/` 保留为原有未提交/未跟踪内容。CI 分支已推送，无未提交源码修改。

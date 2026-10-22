# 项目风险闭环：静态调查与离线修复交付

日期：2026-10-21，Asia/Shanghai。基础 HEAD：dev `2ff6f077c2a33bb58287f0eca386e03543b08712`。隔离分支：`codex/risk-closure-offline-20261021`，worktree：`E:/project/spec-agent-risk-closure`。原 dev、main 和 PR #19 分支均未修改。

本轮修复一个可复现的 P1 测试启动守卫缺陷，完成事故静态复核、V98/V99 兼容性分析、只读审计方案及离线验证。**事故实际数据库影响和真实迁移资格仍未闭环**。没有数据库连接、SQL 执行、Spring 应用启动、新真实模型请求、Provider/Prompt 修改、推送或 PR 合并。

## 继承计划与完成范围

从原交付目录 `docs/acceptance-evidence/independent-20261021/` 恢复的四阶段为：AE4 测试隔离（ae4.md）、P3B 基线/评分与契约（p3b.md）、既有真实模型证据复核（live-evidence.md）、架构与语义决策（architecture.md）。已有 85 项独立 Java、48 项根离线、255 Brain/215 工具及 C03 REJECT 等结果只作为历史基线，不当本轮重跑结果。PR #19 已完成独立 CI 审查，本轮没有重审或更改该 PR。

| 本轮阶段 | 完成内容 | 状态与仍开放项 |
| --- | --- | --- |
| A 事故静态调查（接续 AE4） | 核对原报告、首次披露、XML 元数据、启动及事务/清理路径；复现并修复 nested 守卫绕过；执行已有部分清理失败测试；形成 17 查询只读审计方案 | 静态调查/修复 PASS；历史影响 UNKNOWN，数据库审计 NOT RUN（未授权） |
| B V98/V99（接续架构合并边界） | 核实真实 SQL、持久化仓储、网关、实体状态、审批终态、恢复/并发和现有集成测试边界；提供升级验收矩阵 | 静态分析完成，源码未改；真实升级/重试/锁行为 NOT RUN，发布验收 BLOCKED（未授权隔离库） |
| C 核心契约（接续 P3B/架构） | eligibility/policy/executor 分层静态复核；源引用/WAIT 旧缺口保留；运行真实契约/评分/回放、历史证据重算与 16 项纯 Mock 路由冻结回归 | 当前离线范围 PASS；C16 表示缺口、人审、真实跨进程/多实例闭合仍开放 |
| D 验证 | 独立 Java、根离线、Python、完整源码离线编译及 bootJar、任务图拒绝、SQL 方案静态检查 | 实际执行项 PASS；修复前反例 FAIL 并留证；完整应用/数据库/E2E NOT RUN；本分支远端 CI NOT RUN（未推送） |

## 按优先级的真实发现与风险

本轮未发现 P0；不据此宣布整个项目没有 P0。

### P1，已修复：nested Spring 配置和外部服务标签可绕过启动守卫

位置：`backend/src/test/java/com/specagent/support/DatabaseTestContextGuard.java:39`、`:64`；回归 `DatabaseTestContextGuardTest.java:123`、`:131`、`:141`。

原实现的 AnnotatedElementUtils 查找未覆盖 Spring TestContext 对非静态 nested 类的 enclosing configuration 继承。外层 `@ContextConfiguration` 的 `@Nested` 测试可令工厂返回 null；外层组合 `external-service` 标签也可被漏识别。直接 IDE/JUnit 运行 nested 测试或将来扩展离线白名单时，会缺失启动前保护；根 Gradle 当前显式白名单/任务图仍是另一道防线，不能据此断言历史事故由 nested 导致。

修复前两个无网络反例真实失败（11 tests、2 failed，见 nested-guard-red.log 和保存的 XML）；没有实例化真实业务上下文或连接数据库。修复改为 Spring TestContextAnnotationUtils 识别配置继承，并按 JUnit nested 规则逐层检查外部服务标签。显式 `NestedTestConfiguration.OVERRIDE` 不误继承外层 Spring 配置；外部服务标签在 nested override 自带上下文时仍受限制。新增三项回归均通过。

### P1，开放：事故涉及测试之外的启动事务，净影响无法从当前文件确认

原报告 `E:/project/spec-agent/docs/P3B_AE4_DATABASE_INCIDENT_REVIEW.md`、历史 `tools/p3b/c03-live-runs/gemini-lite-20261020/p3bae3_review_v2.md:83` 披露首次执行在 2026-10-20 23:36–23:37 连接 spec_agent_test、出现 JDBC commit；本轮没有找到能够替代该披露的首次完整 stdout/逐 SQL/XID。p3bae4 中 15:39:39 XML 是后来规则 6 项重跑，11:46:34 / 11:51:45 XML 是更早运行，均不能当作事故当次证据。

源码确认的路径：

1. C03 原继承 EvalHarnessBase 的 SpringBootTest/ActiveProfiles(test)，两个 test profile 指向共享测试库；Gradle --offline 仅约束依赖解析。
2. ScenarioRunner.java:218 调用事务性 ProjectService.createProject，返回后登记项目，再进行建图/运行。ProjectService 保存项目、成员、路由和 activeRoute；没有测试总事务包围后续链路。
3. RunService 类级事务，AnswerCycleService.java:309/610 的两个 TransactionTemplate 分别提交回答/patch 和 checkpoint；AgentRunFailureService.java:129/138 的 REQUIRES_NEW 可在外层失败后独立提交失败事实。
4. EvalHarnessBase.java:86 的多条 JdbcTemplate DELETE 没有总事务；成功写后清理不是回滚。现有 EvalProjectCleanup 在一次清理中继续尝试其他项目并聚合异常；失败清理仍可能残留，后续 AfterEach 也不能证明所有数据被删除。
5. ModelCredentialEncryptionMigrator.java:54/77/101 可逐行更新八张凭据表；ConnectionCredentialEncryptionMigrator.java:42/49 逐身份事务且 ensure scope，甚至当前密文无须重写也可能先触及授权范围。
6. GlobalAssistantRunRecoveryService.java:68/79/92/95 可通过逐 run 生命周期事务、REQUIRES_NEW handoff 和 dispatch 影响 C03 项目之外的既有 GA run/pending turn。中途失败不回滚先前已完成的其他事务；监听器捕获异常也不是全局回滚。

上述为可达性及事务边界，不证明事故当次真的重写凭据、终止 GA run 或发出额外模型请求。test 默认 worker=false、seed=false 等只能收窄条件风险，不能替代事故环境记录。原始业务写入 UUID/行数、清理成败、启动写入、并发观察和最终净变化继续 UNKNOWN。

### P1，开放：V99 会覆盖 V98 已存在的有效活动指纹

位置：`V98__agent_run_model_binding.sql:3`、`V99__legacy_run_model_binding_fail_closed.sql:4`、`AgentRunModelBindingRepository.java:19`、`RoutingModelInferenceGateway.java:187`。

V98 增加 nullable CHAR(64)；V99 对 status 非 created/completed/failed 的所有行赋 legacy-unbound，无 IS NULL 条件。它既处理旧 NULL，也覆盖先部署 V98 后由新网关保存的有效活动指纹。仓储只接受 NULL 或与候选 fingerprint 完全相同的非终态行，因此哨兵使后续新推理绑定失败关闭，不能恢复被覆盖的旧配置。

此为现有明确的安全取舍及升级可用性风险，不通过随意修改已提交版本化迁移解决。未知目标库是否执行过 V98/V99，改历史 SQL 可能造成 checksum 不一致；本轮保持两个 SQL 的规范化源码字节与 2ff6f07 完全相同（SHA256 见 offline-verification.json）。

若运行中的网关已完成绑定并发出请求，V99 不会撤销在途 HTTP；反过来 V99 先写哨兵，后续绑定会拒绝。必须验证停机/drain/混合版本窗口，不能把单条 UPDATE 当执行围栏或在线迁移方案。

### P2，开放：测试与产品表示的已知边界

- MigrationV99ModelBindingBackfillTest.java:24 只造 model_called/created 的 NULL 数据；没有已绑定活动行、全状态、并发或中断升级矩阵，且方法会 CREATE DATABASE（:43）、DROP ... WITH (FORCE)（:76），不是只读测试。本轮不运行，不用 mock 宣称补齐真实迁移验证。
- AgentRunModelBindingIntegrationTest 的并发赢家/哨兵反例需要真实 PostgreSQL，未运行。网关内存端口的并发/LRU/重新构造实例回归不代表数据库多实例验证。
- C16 的可信 pending dependency 表示仍缺失，WAIT 失败关闭；不从自由文本虚构待办依赖，不扩动作族。C07 必要性与执行授权分层、C13/C15 白名单与内容证据分层继续保留；未发现需要本轮改变生产行为的新缺陷。
- 设计索引中的早期 conflict 限制与当前冻结 eligibility 语义存在历史漂移，不能按旧文字追加全面 deny；需要明确设计版本后再推进契约变更。C03 候选仍 REJECT，不推广、不改 Prompt。

## V98/V99 安全性明确结论

**静态可证明其失败关闭意图和覆盖范围，不能认定升级安全或可恢复性已验收。**

| 数据库 status（AgentRunStatus 的实际 8 种） | V99 对 NULL/既有指纹的行为 |
| --- | --- |
| created | 不改；仓储后续允许首次绑定或验证既有相同指纹 |
| running/context_built/model_called/reflected/persisted | 全部覆写 legacy-unbound，之后绑定拒绝 |
| completed/failed | 不改；仓储拒绝终态绑定 |

审批语义纠正：awaiting_approval 不是 AgentRunStatus 的一项。DecisionExecutionService.java:200 与 NodeQueryService.java:190 在待审批路径将实际 run 置为 completed、记录 AWAITING_APPROVAL 事件，V99 通常不会改这些已完成 origin。ProposalAcceptanceService.java:125 在授权/stale/fence 下执行既有提案，可能请求后继 run；后继拥有自己的绑定。若历史库确实存在非规范 awaiting_approval 字符串，V99 的 SQL 仍会选中，但目前没有数据库证据证明存在。不能沿用旧报告“所有等待审批行都会覆写”的概括。

幂等/中断边界：V98 原始 SQL 重跑会重复列报错；V99 重跑会再次覆盖当时活动行，包括新增的有效指纹。预期由 Flyway 版本历史避免重复执行，不将裸 SQL 称作业务幂等。仓库没有关闭事务的逐迁移配置，但本轮未验证 PostgreSQL/Flyway 实际提交、故障回滚或数据库锁。V98 新列不要求修改普通 AgentRun rowMapper；绑定由独立仓储管理。旧二进制忽略指纹意味着 schema 可读不等于新冻结语义生效，不批准混合滚动部署。

获准的新隔离库验收应覆盖：V97→V99、V98→V99、空库；八种实际状态 × NULL/有效指纹/哨兵；completed+PROPOSED 审批与 acceptance 后继；并发首次绑定/V99 行锁、终态竞态；迁移中断事务回滚/重启、已成功版本重复启动、旧/新实例 drain 与恢复。每项需要真实数据库执行证据，当前全部 NOT RUN。

## 本轮修改

| 文件 | 原因/验证 |
| --- | --- |
| backend/src/test/java/com/specagent/support/DatabaseTestContextGuard.java | 修复 enclosing 配置/外部标签绕过，启动授权先于环境/连接/Bean |
| 对应 DatabaseTestContextGuardTest.java | 三项纯离线回归；先红后绿，不调用真实数据源 |
| backend/independent/build.gradle.kts | 白名单加入现有 EvalProjectCleanup、EvalCleanupFailureHandler、EvalProjectCleanupTest，仅内存/Consumer/JUnit；执行原有部分清理失败断言，不编译 EvalHarnessBase |
| backend/build.gradle.kts | 已审查的 16 项 RoutingModelInferenceGatewayTest 加入 testOffline；Mockito 网关、内存绑定/并发/LRU，无 HTTP 或真实模型；原任务图拒绝保持 |
| 本目录 database-readonly-audit-plan.md | 17 组只读查询，逐组说明目的/表/输出/风险；连接身份、时区、RLS、超时及原始日志证据边界 |
| 本目录 verify_offline_evidence.py | 汇总本地 JUnit、检查迁移源码未变、检查 SQL 模板只读 token；不导入数据库驱动，不发起连接 |

业务源码、V98/V99、Provider 配置、生产 Prompt、标签与历史评测文件均未修改。SQL 模板只完成词法只读检查，未声称 PostgreSQL 语法/执行成功。

## 实际命令与结果

原始日志/完整 JUnit 与初始状态保存在 `E:/project/spec-agent/docs/acceptance-evidence/risk-closure-20261021/`，不覆盖历史证据。本报告和审计方案/验证脚本位于隔离 worktree 同名目录。

| 实际命令（除说明外在隔离仓库根目录） | 状态与结果 |
| --- | --- |
| git status/diff/worktree/branch/rev-parse；git fetch origin | PASS，保全原始状态后隔离分支从 dev 建立 |
| backend/gradlew.bat -p backend/independent test --tests com.specagent.support.DatabaseTestContextGuardTest --offline | FAIL（修复前两条反例），日志/原 XML 保留；修复后 PASS |
| backend/gradlew.bat -p backend/independent clean test verificationScope --offline --console=plain（最终含 cleanup） | PASS，91 tests、0 failures/errors/skips；54 生产/31 测试支持源码；independent-with-cleanup.log |
| gradlew.bat testOffline bootJar --offline --console=plain（backend） | PASS，完整 production/eval/test 源码实际编译，51 tests，生成约 50.8 MB bootJar，未启动应用；dev-offline-and-bootjar.log |
| gradlew.bat testOffline --offline --console=plain（增加路由白名单后，backend） | PASS，最终 67 tests、0 failures/errors/skips，其中路由冻结 16；root-offline-final.log |
| testOffline test -PdatabaseIntegration=true --dry-run --offline | PASS（拒绝反例），命令 exit 1，任务图拒绝混跑，没有任务动作；deny-offline-mixed.log |
| test --dry-run --offline | PASS（拒绝反例），命令 exit 1，未授权选集在任务图阶段拒绝；deny-default-test.log |
| 原目录 agent-brain/.venv/Scripts/python.exe -m pytest agent-brain/tests tools/tests -q，PYTHONPATH 指向隔离源码，禁用外部 pytest 插件自动加载 | PASS，470 passed；python-offline.log |
| 同解释器 tools/p3b/evidence_audit.py | PASS（既有文件一致性），historical-evidence-audit.json；没有新增 live 请求、没有把缺失历史请求补作成功 |
| 同解释器 tools/p3b/p3b_decision_replay.py --output 独立证据路径 | PASS，11 outcome/action 匹配、0 payload mismatch；合成冻结 fixture，不是模型质量/Java进程实测 |
| 同解释器执行本目录 verify_offline_evidence.py，git diff --check | PASS，JUnit 91/67 汇总，17 查询方案词法检查，V98/V99 源码不变；offline-verification.json |
| 应用启动、真实数据库/迁移、跨语言真实进程、E2E、新模型调用 | NOT RUN（未授权/本轮明确边界） |
| 干净远端完整后端构建 | BLOCKED（Zen 私有依赖）；本机缓存可完成离线编译，不能推广成干净 CI 能下载 |
| 本风险分支远端 CI / 本轮前端重跑 | NOT RUN（未推送；无前端修改） |
| PR #19 四个完整作业 | 沿用先前已审查的 SKIPPED_EXTERNAL_DEPENDENCY，本轮不重跑/不计 PASS |

根构建运行时仅在该命令进程清空 OPENCODE_ZEN_LOCAL，避免加载本地 composite；不更改 Provider 或持久配置。使用已有 Gradle/JDK/依赖缓存，没有安装库、启动 Docker 或服务。现有编译/弃用警告保留，没有放宽标准。91 与 67 有重叠，不相加作为独立覆盖。

## Git 与下一步

所有修复发生在新隔离 worktree，原目录继续 dev/2ff6f07、领先 origin/dev 3 提交，原删除、uv.lock、事故文档、p3bae4、既有交付证据均保护；初始 SHA256 与最终核对见原目录证据中的 protected-files-check.json。暂存区不会混入原用户文件，main、dev 和 CI 分支保持原 HEAD。仅将本轮自有 4 个代码/构建文件及本目录 3 个文档/脚本整理为本地提交；不推送。精确提交和最终状态见 final-git-state.json/交付回复。

下一最有价值执行项：用户确认只读审计的目标实例、账户权限与输出范围后，执行单独的只读方案并关联既有服务端日志/备份；该授权不包括升级测试。随后另行授权新隔离库的 V97/V98→V99 验收矩阵，先决定有效活动绑定应被保留还是明确失效，再设计版本化部署方案。当前不替用户决定或操作数据库，也不以该部分阻塞取消其他已完成离线工作。

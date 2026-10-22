> [!NOTE]
> **Document Status**: `Historical` (历史归档)\
> **Authority**: 本文档为历史阶段性记录，仅供追溯与复盘使用。当前系统的实际行为与规约以 [docs/README.md](../../README.md) 及 [docs/v2/README.md](../../v2/README.md) 为准。

---

# P3-B-AE-4 测试数据库误连接事件核查与防复发

事件影响结论：**尚未确认，事件尚未闭环**。上一轮已披露连接 `spec_agent_test` 并提交事务，本轮接受这一事件事实，不将后续离线通过解释为“没有数据库变化”。本轮完成代码、已有报告及本地测试产物核查，并实施测试入口防复发修改；未连接任何数据库，未执行迁移、SQL、数据修复或数据库清理，未调用模型 API 或外部服务。

C03 继续 **REJECT，不推广**。未开始 C08/C09 工作，未修改生产默认 Prompt，未提交、暂存或推送。

## 1. 仓库、证据和可信度

实际目标仓库为 `E:/project/spec-agent`，对应 `BangShou1st/spec-agent`。开始时分支 `dev`，HEAD `899ccbc2d7476e57ff1487ae88fd15ac9483d356`，与任务给出的基线相同。最初的工作目录 `E:/project/ai-collab` 属于另一个仓库，仅读取位置、Git 状态和目录后转入目标仓库，没有在该仓库修改文件。

本轮开始时已有 7 项 tracked 修改、1 项 tracked 删除和 5 项未跟踪文件；**这些不是本轮新增修改**。完整清单见 `acceptance-evidence/p3bae4/initial-status.txt`。特别保护：

- 删除状态：`tools/structured-output-probes/qwen25-7b-schema-20261020/report.md`。
- 未跟踪：`agent-brain/uv.lock`、拆出的 C03 集成测试、两份复核 JSON 和 AE-3 v2 报告。
- 既有 Python 裁决器、测试修改及原始 A/B JSON、attempts JSONL、历史裁决均保留。

证据目录为 `docs/acceptance-evidence/p3bae4/`：

| 文件 | 用途 |
|---|---|
| `initial-diff.patch` / `initial-staged-diff.patch` | 修改前完整 tracked diff；暂存 diff 为空 |
| `initial-sha256.json` | 原有改动文件及 C03 原始评测产物的字节哈希 |
| `prior-TEST-*.xml` | 本轮开始时留存的 23:39 离线重跑 XML，先保全再验证 |
| `historical-c03-xml-metadata.json` | 其他任务目录中的历史 C03 XML 元数据和哈希 |
| `code-evidence-index.txt` | 测试启动、事务和业务调用链的源码路径与行号 |
| `p3bae4-only.patch` | **仅本轮**防复发增量，含新增文件；不把旧修改计为本轮修复 |
| `current-full-diff.patch` | 当前完整 tracked diff，包含旧改动；新文件增量另见上项 |
| `offline-test-summary.json`、`TEST-*.xml`、各命令 `.log` | 本轮实际验证证据 |
| `protected-files-check.json` | 原始产物和已有文件保护检查 |
| `final-git-state.txt` | 收尾分支、HEAD、工作区及暂存区状态 |

证据分层：源码和留存 XML 为本轮直接可检查证据；AE-3 v2 对 23:36–23:37 首次执行的记载为上一轮披露证据；由源码推导的路径单独标为推断。**本地现存 `test` 结果已经被上轮 23:39 的重跑替换，未找到该次误连接完整原始 stdout/XML**，因此不能给出已独立核验的逐条 SQL、事务 ID、影响行数或连接会话 ID。未查阅 API 密钥文件或实际密钥值。

## 2. 可审计时间线

下表时间为 Asia/Shanghai。历史 XML 的原始 timestamp 无时区后缀；转换按 Gradle 此处的 UTC 输出口径，与 AE-3 的 23:39 重跑记录相互对应，未用文件修改时间代替执行时间。

| 时间 / 顺序 | 发生事项 | 证据与限制 |
|---|---|---|
| 2026-10-20 19:46:34（XML `11:46:34`） | `evalBFast` 目录留存 C03 7 项通过记录 | 本轮直接读取的历史 XML 元数据。不是本次 23:36 事件，不能混用日志或据此证明该次写入 |
| 19:51:45（XML `11:51:45`） | `testNonLive` 目录也留存 C03 7 项通过记录 | 同上；说明这种带数据库依赖的测试曾被普通任务发现 |
| AE-3 首次 Java 执行前 | C03 类继承 `EvalHarnessBase`，6 项语料断言和第 7 项执行链断言在同一类 | `git show HEAD` / 初始完整 diff / AE-3 报告一致；此时没有本轮新增全局启动守卫 |
| 23:36–23:37 | 报告记录执行 `gradlew.bat test --tests com.specagent.eval.P3BLiveDatabaseGuardTest --tests com.specagent.eval.P3BC03NaturalLanguageScenarioTest --offline`，25+7 项通过 | 上轮披露，不作为本轮离线通过证据。Gradle `--offline` 仅限制依赖解析 |
| 同次启动 | C03 继承的 `@SpringBootTest` / `@ActiveProfiles("test")` 加载生产应用；连接 `localhost:5434/spec_agent_test`，PostgreSQL 17.11 | 端点、版本来自上轮记录；两处 test profile 中默认数据库 URL 的代码证据独立吻合，现场最终属性和完整环境未保存 |
| 同次启动、测试方法之前 | Flyway 校验、数据库准入、ApplicationRunner 和 ApplicationReady 监听器具备执行条件 | 报告称 Flyway 校验 98 项且 schema 最新、无需迁移；本轮无法用该次原始日志独立复核。其他启动组件见下节 |
| 第 7 项方法内 | `offlineBfastExecutionSucceedsForNaturalLanguageC03` → `runScenario` → 创建项目、建图、提交回答、脚本化决策、持久化运行结果 | 源码明确的成功路径；方法名里的 offline 和脚本化 Brain 不隔离数据库 |
| 方法之后 | 继承的 `@AfterEach` 手动执行项目相关 DELETE | 没有测试总事务或自动回滚；不能视作回滚已提交的业务/启动事务 |
| 同次执行期间 | 上轮日志披露出现 JDBC commit | 承认已发生提交；具体提交次数、事务与 SQL 的对应关系尚未确认 |
| 误连接披露后 | 移除规则类集成父类，将第 7 项移至 `P3BC03NaturalLanguageScenarioDatabaseIntegrationTest` | 本轮开始时已存在该拆分；集成类为未跟踪文件。不是本轮新拆分，也不是数据修复 |
| 23:39:39（XML `15:39:39`） | 上轮重跑只留存规则 6 项、Mock 守卫 25 项，均通过 | 本轮开始先保全 XML；只能证明该次重跑，无法取代第一次记录 |
| 本轮 AE-4 | 离线审查、增加构建及 Spring 启动守卫、执行白名单离线测试和构建拒绝检查 | 详见第 6 节；未执行集成测试的方法或实例化其测试对象。对集成类只检查继承/标签及启动元数据拒绝行为 |

## 3. 加载链、事务和影响范围

### 3.1 继承及扩展

误连接时 C03 → `EvalHarnessBase` → `Object`，没有额外父类或实现接口。父类的 `@SpringBootTest` 引入 SpringExtension，`@ActiveProfiles("test")` 选择数据库配置，`@Import` 装配 `ScriptedBrain.Config` 和 `EvalProbeCapabilities.Config`。父类注入真实 `ScenarioRunner` 与 `JdbcTemplate`。

父类还注册 `EvalCleanupFailureHandler`（实现 `TestExecutionExceptionHandler`），失败时调用同一项目清理器并重新抛出异常；`@AfterEach` 负责正常收尾。没有 `@Transactional`、`@Rollback` 或 `@Commit` 测试包围事务。扩展不是数据库 Mock，也不撤销已提交数据。

拆分后规则类直接继承 Object，无接口、Spring 注解、测试扩展或全局 fixture。调用的是 `P3BCorpus`、自然语言场景定义及 Given/Expect/GraphStep/UserEvent/Variant 等值对象的构造、校验和哈希；静态初始化为集合/常量，不调用 Service、JDBC 或启动上下文。测试资源未发现既有的 JUnit 自动扩展注册；本轮新增的 spring.factories 只参与 Spring Test bootstrap，纯规则类不触发它。脚本化能力实现为进程内计数器，不能据此把整个 EvalHarnessBase 认作离线。

### 3.2 确定的成功调用链与提交边界

源码行号索引见 `code-evidence-index.txt`。下列为代码能够确定的执行机制；**具体到事故会话的每一笔提交和行数仍缺原始审计**。

1. `ScenarioRunner:218` → `ProjectService.createProject:91`（Spring `@Transactional`）。保存 projects、project manager 授权关系、初始 routes，并更新 active route；成功返回时提交自身事务，不会等到测试结束才统一回滚。
2. `ScenarioRunner:295` → `NodeService.createRootNode` 为自然语言根问题建图，并按场景推进 tip。涉及 nodes/routes 及相关图操作记录。
3. `ScenarioRunner:479` → `RunService.createQueuedRunWithInput` / `claimAnswerCycleRun:639` → `RunWorker.executeRun`。RunService 是类级 `@Transactional`；创建、认领和后续周期没有测试总事务将其包住。
4. `AnswerCycleService:309` 用 TransactionTemplate 提交回答及 run checkpoint；`:610` 用另一 TransactionTemplate 提交 AnswerPatch、claims 及 checkpoint；快照和投影路径可写 context_snapshots / agent_input_projections。
5. `ScriptedBrain` 提供 STATE_UPDATE 与 DECISION，C03 脚本为 RESPOND_TO_USER。后续 `decisionExecution` / `AgentRunTerminalizationService` 将事件、终态及 continuation-check 请求在各自事务中落库。执行 completed 不代表没有数据库写入。
6. 异常路径的 `AgentRunFailureService:129,138` 使用 REQUIRES_NEW，可独立提交失败记录；这是潜在路径，不因类存在就认定本次执行过异常分支。
7. 父类 `cleanUpProject:86` 依次 DELETE agent_run_events、agent_run_continuation_checks、agent_runs、agent_input_projections、context_snapshots、agent_proposals、capability_invocations、route_inherited_answers（两种引用）、answer_patches、answers、node_relations、graph_operations、spec_snapshots、routes、nodes、projects。清理器无总事务，JdbcTemplate 在无外层事务时通常逐语句自动提交；具体 autoCommit 值缺事故原始连接记录。清理中途失败也可能留下部分结果。

这里的事务管理属于 Spring JDBC / PlatformTransactionManager 与 TransactionTemplate 链路；本地业务配置未找到替代事务管理器声明。事故运行时管理器具体实现类、连接 autoCommit、事务 XID 未保留，不伪造为直接日志结论。live 种子方法另行 new DataSourceTransactionManager，不能据此推定此次 C03 使用了该种子事务或发生了 Provider 重置。

### 3.3 启动阶段与事务外风险

| 组件 / 表范围 | 代码证据及行为 | 本次实际发生程度 |
|---|---|---|
| Flyway | 两处 application-test.yml 都启用 migration、validate、baseline-on-migrate；位置 classpath:db/migration | 上轮记载执行了校验、无待应用迁移。**本轮尚未独立确认是否写过 schema history、DDL 或迁移 DML**，不能仅凭“98 validated”排除其他历史状态 |
| ModelCredentialEncryptionMigrator | 无条件 ApplicationRunner；扫描 model_providers、opencode_settings、openrouter_settings、kilo_settings、custom_provider_settings、search_settings、embedding_service_settings、embedding_service_revisions。可加密明文或轮换旧密文；无外层事务，按行 UPDATE 可自动提交 | 能到达扫描链路；是否存在符合条件行、实际读写哪些凭据及影响行数**尚未确认**。不是测试用例直接保存或激活 Provider 的证据 |
| ConnectionCredentialEncryptionMigrator | 无条件 Runner；扫描 connection_credentials，每行 TransactionTemplate；ensure 授权 scope，可能重写 encrypted_secret、updated_at，并递增 connections.configuration_version | 数据、密文版本和授权 scope 是否实际变化**尚未确认**；原值已是当前格式也可能先经过 scope ensure 路径 |
| GlobalAssistantRunRecoveryListener / Service | 无条件 ApplicationReady 监听器；查找 active GA runs，经生命周期服务事务将 orphan 标记失败；可衔接 stranded pending turn 并 dispatch | **需重点补审，非 C03 项目范围内的潜在业务写入**。是否有既有 run 被终止、pending turn 被消费、后继任务被派发甚至引出其他调用，现证据不能判定 |
| TurnHandoffCatchUpPoller、stream catch-up、授权撤销轮询、embedding rebuild 恢复等 | 项目还有 Scheduled / ApplicationReady 写入口；调度受配置、调度启用条件和已有数据影响，不能仅凭 worker=false 一概排除 | 执行、锁竞争、清理以及并发影响**尚未确认**；未把有条件路径当作已发生 |
| SecurityModeAdmission / ExecutionModeAdmission | @PostConstruct / 构造阶段访问数据库登记行及连接；包含 FOR SHARE / advisory lock 等准入路径，不能称启动完全无数据库副作用 | 代码可到达；事故会话的锁、模式及最终释放情况缺直接证据。此两处模式校验本身不自动改写登记模式 |
| ExecutorLease | worker 开启时租约可 INSERT/UPDATE executor_ownership.epoch | test profile 默认 worker=false；缺环境覆盖证据，列为条件风险，不能认定本次递增过 |
| BuiltinSkillSeeder | test profile 默认 seed-enabled=false | 默认关闭，不能像上轮概述那样笼统认定已经播种；有覆盖时才存在导入/安装/启用写风险 |
| SkillLocalMirrorBackfill | 可读取技能并投影到本地镜像；测试资源配置 local-mirror-enabled=false | 本质是文件投影，不直接等同数据库写入；现场覆盖与实际文件变化未知 |
| session / SQL 初始化 | 两份 test profile 均 session.jdbc.initialize-schema=never；未发现本场景显式 SQL schema 初始化脚本入口。普通 PostgreSQL 上 Boot SQL initializer 的默认行为不能冒充事故运行时日志 | session 自动建表默认禁止；具体覆盖配置及现场执行情况未独立证明 |

### 3.4 已证实、推断、未知的明确区分

- **已披露事实**：该次 Java 执行误连接共享测试库，日志出现提交。本轮承认事件，不因原始日志未留存而否认它。
- **本轮直接证据**：带真实数据库的继承和成功执行调用链；事务和清理代码；启动写入口；两处数据库 test profile；现存重跑 XML；较早的历史集成发现记录。
- **高可信代码推断**：若上轮第 7 项按记录完整通过，就走过项目/图/回答/run 等真实写调用，并在后续执行手动删除。写后再删并非零影响，仍可能有审计、触发器、授权行、其他关联表或并发观察残留。
- **未发现 C03 直接调用** `ModelProviderSettingsService` 保存、激活 Provider 或 live seed DELETE/INSERT。此次命令也未选择 Gemini 配置测试或 EvalLiveP3B。此结论不排除启动凭据 Runner 修改 Provider 相关数据，也不排除 GA 恢复链处理其他业务。
- **尚未确认**：实际新增/更新/删除行和 UUID、事务数量/XID、是否仍有残留、是否触发级联/触发器、是否修改凭据及版本/授权范围、是否处理既有 GA run、是否发生启动恢复派发或并发干扰、数据库净变化。不能将这些未知写作“没有影响”。

## 4. 根因与防复发修改

直接根因是按测试名称和脚本化 Brain 误判隔离范围，没有在执行前检查父类、Spring 上下文与启动副作用。结构性根因是规则与集成断言混在一个继承体系中，普通 Test 任务按命名过滤 live 套件却允许数据库类进入，数据库目标回退至共享 test profile，且缺少统一启动前拒绝机制。

拆分本身已有但不充分：新集成类仍能由普通 `test` / `testNonLive` / eval 包过滤发现，也没有独立 opt-in。对测试方法第一行加检查无法保护 Flyway、Bean 构造、Runner 或启动监听器。

本轮仅修改 8 个构建/测试/资源文件，精确增量见 `p3bae4-only.patch`：

| 文件 | 本轮增量 |
|---|---|
| backend/build.gradle.kts | 复用 Test 任务配置；增加三个已审查类的 `testOffline` 白名单。其他 Test 任务缺少显式 databaseIntegration=true 时在执行测试 JVM 前失败；新增 tagged `testDatabaseIntegration` 入口；external-service 另需 externalServices=true |
| EvalHarnessBase.java | 增加可继承 database-integration 标签，覆盖原 C03 父类及整个脚本化集成家族 |
| EvalLiveHarnessBase.java | 增加 database-integration / external-service 两类标签 |
| GeminiCustomProviderSettingsVerificationTest.java | 仅在原有修改上增加两类标签；原有测试代码及 AE-3 防护保持 |
| P3BLiveDatabaseGuardTest.java | 纯副作用顺序夹具改为显式 ContextConfiguration；保留 DynamicPropertySource 失败断言，不再把这个无数据库夹具标作生产 Boot 上下文 |
| support/DatabaseTestContextGuard.java | 测试专用 ContextCustomizerFactory：对 SpringBootTest、DataJdbcTest、JdbcTest（含继承/组合注解）在 context loader 之前检查精确 opt-in；未启用时不读取连接环境。显式启用后复用可注入的独立 JDBC 预检查，无默认 URL/凭据；应用与 Flyway 属性固定到通过检查的目标，再于 Bean 初始化前拒绝属性漂移。外部套件另作开关检查 |
| support/DatabaseTestContextGuardTest.java | 8 项纯离线测试验证入口拒绝顺序、缺失/共享目标不连接、C03 继承及标签/方法数、外部服务独立启用、数据源和 Flyway 漂移在 Bean 副作用前失败；仅使用 mock、dummy 参数及手工 GenericApplicationContext |
| src/test/resources/META-INF/spring.factories | 注册启动守卫；不依赖开发者记忆在每个测试方法调用 |

守卫失败向上抛出；本轮未添加自动重试、备选数据库或默认连接回退。独立预检查的真实 DriverManager 路径在本轮**没有执行**；mock 路径复用上轮 25 项守卫测试覆盖，实际驱动和服务器身份仍未实测。

未来授权数据库测试时要求显式 `SPEC_AGENT_TEST_DB_URL / USERNAME / PASSWORD / SERVER_ADDRESS / SERVER_PORT`（均带完整 `SPEC_AGENT_TEST_DB_` 前缀）；现复用已有守卫，库名仅接受 `spec_agent_p3b_live_*`，拒绝 spec_agent_test 等共享库。此处只说明接口要求，**本轮没有读取现场这些连接值或获得数据库执行授权**。现有 live 入口原有 EVAL/TARGET_DB 环境也需与经验证目标一致，漂移会拒绝；不为复跑猜测目标或凭据。

## 5. 测试分层与保护边界

| 层 | 本轮明确入口 / 机制 | 状态 |
|---|---|---|
| 纯规则 / 值对象 | testOffline → C03 规则类 6 项；Python 仅选 C03 规则 | 不继承集成基类，不装配真实 DataSource，无 Flyway/Runner |
| Mock 数据库 / 启动顺序 | testOffline → 原守卫 25 项 + 新全局入口守卫 8 项 | Mockito JDBC、dummy 工厂及手工无自动配置的 Spring context；拒绝路径在任何真实数据库调用前结束 |
| 本地数据库集成 | database-integration 标签、显式 Test 任务 opt-in、Spring bootstrap 工厂、显式隔离目标预检查 | 原 C03 执行断言完整保留，未在本轮执行；普通回归不默默包含该层 |
| 真实模型 / 外部服务 | 已有 evalLive* 家族和 Gemini 配置测试加 external-service；独立 externalServices 开关 | 本轮未启用或运行；live 源集和既有专用任务继续复用 |

行为变更需注意：**普通 `test`、`testNonLive`、evalBFast 等宽选集不再无条件执行**；即使附带 --offline 或 --tests，也不绕过 opt-in。离线回归明确使用 `testOffline`，该任务将两种启用属性强制为 false。未将“少跑测试”称作“全量回归通过”，其他既有测试未在本轮验收。testNonLive 只代表排除 live 套件，原本不代表无数据库。

限制仍需记录：这不是网络沙箱或任意 JDBC 调用的拦截器；直接手写 JDBC、非 Boot 的自定义 Spring 装配、第三方扩展/静态初始化，以及未来加入白名单的代码仍需路径审查。默认 Gradle 入口会拒绝未审查宽选集；Spring 工厂保护上述 Boot/JDBC 测试入口，不能声称覆盖所有 IDE 插件或任意自建任务。预检查只核对可观察库名/用户/端点，不是可信实例 UUID、TLS 身份或每连接授权；连接后换路由、自定义 DataSource/ConnectionDetails、服务连接、额外数据库 Bean 或驱动行为尚未实证。启动和真实集成行为本轮没有通过真实数据库验证。

## 6. 本轮实际离线命令与结果

所有 Gradle 命令工作目录为 `E:/project/spec-agent/backend`；Python、Git 和证据脚本在仓库根目录。只使用已有环境，无依赖安装、数据库操作或密钥操作。

| 命令 / 执行轮次 | 实际结果 | 证据 |
|---|---|---|
| `gradlew.bat testOffline --offline --console=plain` 第 1 次 | exit 1，构建脚本编译失败：当前 Gradle TestFilter API 无 commandLineIncludePatterns；未启动测试。已去除兼容性错误，采用所有宽选集必须显式启用的规则 | testOffline.log |
| 同命令 r2 | exit 0，BUILD SUCCESSFUL，新增初版守卫与既有规则/Mock 测试通过；后续加断言后再次验证，不用该次数量替代最终证据 | testOffline-r2.log |
| 同命令 r3 | exit 0，BUILD SUCCESSFUL；补充缺失目标和禁止读取环境路径后通过 | testOffline-r3.log |
| 同命令 final | exit 0，BUILD SUCCESSFUL；**39 tests，0 failures/errors/skips**（规则 6 + 原 Mock 守卫 25 + 新入口守卫 8） | testOffline-final.log、offline-test-summary.json、3 份 TEST XML |
| `gradlew.bat test --offline --console=plain` | **预期 exit 1**；doFirst 拒绝未授权宽选集，测试执行动作未开始；不是执行集成类后失败 | default-test-blocked.log |
| `gradlew.bat testNonLive --offline --console=plain` | **预期 exit 1**；同样在执行测试 JVM 前拒绝 | testNonLive-blocked.log |
| `$env:PYTEST_DISABLE_PLUGIN_AUTOLOAD='1'; .\agent-brain\.venv\Scripts\python.exe -m pytest tools/tests/test_p3b_semantic_ab.py -k c03 -q` | exit 0；**24 passed，32 deselected**。禁用外部 pytest 插件自动加载；只做 C03 离线规则及既有产物读取，未运行 C08/C09 测试或生成新裁决 | python-c03.log |
| `git diff --check` | exit 0；仅既有换行提示，无 whitespace error | diff-check.log |
| 本地 SHA-256 保护脚本、XML 汇总、增量 diff 生成 | exit 0；除两项明确追加修改的既有测试文件外，所有初始清单文件和原始评测产物字节不变；原删除仍不存在 | protected-files-check.json、initial-sha256.json、p3bae4-only.patch |

本轮初始/收尾执行 `git status --short`、`git branch --show-current`、`git rev-parse HEAD` 和暂存/未暂存 diff 检查；本地文件搜索、源码读取、Git 历史读取均为只读。先检查 ai-collab 后定位 spec-agent；若干初步文件定位未命中，已用实际路径继续，不作为验收命令通过记录。所有验证退出码均如实列出，首轮构建失败保留日志；历史报告中的 75 项 Python / 31 项 Java 不计入本轮结果。

## 7. 需要另行授权的只读审计

**需要**，但本轮到此停止，不自行连接补证。只读审计也不能保证从当前快照还原所有历史写入；若无前后备份、SQL审计或可用 WAL/事务日志，有些影响可能永久无法精确归因。

建议由授权人员在确认实例身份后，仅输出脱敏元数据和聚合结果，不输出任何 API key、密文、凭据原文或业务正文：

1. 核对事故时段 23:36–23:37 的连接/断连、事务提交、失败与回滚记录，确认服务端实例、数据库、用户、进程/会话、事务与 SQL 类别，区分其他并发会话。
2. 审计 flyway_schema_history 的版本、校验值、installed_on、success 等非敏感字段和事故前备份，确认是否确实只有校验、有无 schema history/DDL/DML 变化。
3. 按场景项目名 `eval-P3B-C03-NL-base-p3b-c03-nl-title-33501`、对应 UUID 和事务链核查项目、授权关系、路由、节点、回答、patch、snapshot/projection、run、events、continuation-check 与清理记录；检查 FK/触发器、关联表残留和部分清理。不能以项目行现已不存在证明历史没写过。
4. 对凭据迁移范围仅核对行数、时间/版本及加密格式元数据（由授权人员脱敏提取），确认 model/connection 凭据是否被重写、connections.configuration_version 是否递增、authorization scope 是否新增。
5. 对 global_assistant_runs/events/pending turns、后继运行及公共调度任务核对 orphan terminalization、handoff、dispatch 和潜在恢复处理；核实是否触及 C03 项目以外既有业务。
6. 核对事故配置覆盖、worker/调度实际启用状况、executor_ownership.epoch 和锁相关记录，以及独立 Flyway 连接/额外 DataSource。审计历史可收窄风险，不能通过修改现场重现来替代只读审计。

未准备或执行回滚、重建、修复 SQL；任何后续修复都需要先有影响证据与单独授权。

## 8. 收尾 Git 状态

最终分支 `dev`，HEAD 保持 `899ccbc2d7476e57ff1487ae88fd15ac9483d356`，暂存区为空；没有提交、推送、切换分支或修改 main。原删除和所有原未跟踪文件保留。既有修改仅两个测试文件增加本轮必要的增量；其他原有改动及原始评测产物哈希不变。

在初始状态基础上，本轮新增修改 tracked 文件：backend/build.gradle.kts、EvalHarnessBase.java、EvalLiveHarnessBase.java；在已修改的 Gemini 配置测试增加标签、在 P3BLiveDatabaseGuardTest 更换纯启动顺序夹具注解。新增未跟踪为全局启动守卫、其离线测试、spring.factories、本报告和证据目录。完整实际状态以 `final-git-state.txt` 为准。

本轮完成授权范围内的离线核查和防复发验证；**历史实际数据库影响尚未确认，不能认定事件已解决。工作在此停止。**

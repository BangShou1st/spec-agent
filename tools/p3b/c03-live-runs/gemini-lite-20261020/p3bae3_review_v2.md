# P3-B-AE-3 复核记录 v2

结论：C03 保持 REJECT，不推广。允许继续 C08/C09 的代码审查和纯离线工作；不适合进入真实模型/数据库实验阶段。本轮发生了一次违反“不得连接真实数据库”的测试执行，不能宣称全程离线或安全边界已被完整验证。

## Git 与文件保护

实际仓库 `E:/project/spec-agent`，开始和结束均为 `dev`，HEAD `899ccbc2d7476e57ff1487ae88fd15ac9483d356`。没有提交、暂存、推送或切换分支。

开始时五个给定文件已有未暂存修改；`tools/structured-output-probes/qwen25-7b-schema-20261020/report.md` 为未暂存删除，`agent-brain/uv.lock` 为未跟踪；旧 `c03_ab_decision_p3bae_recheck.json` 也为未跟踪。结束仍保留这些状态。它们没有被还原、覆盖、删除或暂存。

新的复核产物为 `c03_ab_decision_p3bae3_recheck_v2.json`，包含版本、当前裁决器 SHA-256、四份输入文件 SHA-256、历史两份裁决及逐次尝试的三维结论。生成前后核对原始输入字节不变。历史两份结果均保持 REJECT。

## 数据库三个阶段与实际时序

| 阶段 | 原工作区实现 | 本轮结果 |
|---|---|---|
| 静态 URL 检查 | Gemini DynamicPropertySource 已检查；P3B live 入口仅设置默认 URL | 两个入口均在注册数据源属性前检查；拒绝缺失 URL、普通库、不符合 live 库名规则 |
| 独立 JDBC 身份检查 | 没有 | 新增 `verifyBeforeContext`，ConnectionFactory 可注入；独立连接只执行服务器身份 SELECT，设置 readOnly、查询超时并关闭资源；检查库名、用户、服务器地址/端口 |
| 应用连接池身份复核 | 测试方法内读取服务器身份，但只按库名比较 | 原库名复核保留，并添加显式服务器地址/端口、用户比较；种子事务内库名复核保留 |

严格时序说明：`DynamicPropertySource` 是 Spring 测试的上下文定制回调，此时 ApplicationContext 对象可能已经构造；检查位于 refresh、应用数据源 Bean 初始化、Flyway、ApplicationRunner 之前。这里不把“Bean 初始化前”说成“ApplicationContext 对象构造前”，也不把 URL 字符串校验或测试方法内复核冒充独立 JDBC 检查。方法名 `verifyBeforeContext` 指启动初始化前的检查。

凭据路径：此前两个 test profile 含数据库用户名/密码配置，入口只覆盖 URL，容易继承共享测试库配置。本轮不推测或读取现场凭据，改为要求显式 `SPEC_AGENT_TARGET_DB_USERNAME`、`SPEC_AGENT_TARGET_DB_PASSWORD`，同一组值传给独立 JDBC 和 Spring 数据源。另要求 `SPEC_AGENT_TARGET_DB_SERVER_ADDRESS`、`SPEC_AGENT_TARGET_DB_SERVER_PORT`，无默认值；地址/端口必须是 PostgreSQL `inet_server_addr/port` 的报告值。Gemini 的 URL 仍为 `SPEC_AGENT_TARGET_DB_URL`，P3B live 的 URL 为 `SPEC_AGENT_EVAL_DB_URL`，移除了后者的默认地址。缺失配置时在连接前失败关闭。认证仅支持显式用户名/密码路径，不推测无密码认证、证书或其他方式。没有读取环境中的实际值、密钥文件或 API 密钥来实施该修改。

增强前：合法命名 URL 被路由到另一个同名库时，库名检查可以全部通过；Flyway 及 Runner 也会先于测试方法复核执行，因此可能已经写入。

增强后：固定路由到另一服务器、且服务器报告地址/端口或用户不同，会在框架数据库初始化前拒绝。离线 mock 覆盖同名库但 host/port/user 不匹配。独立检查不是端到端隔离证明：连接池若随后换路由、其他数据源配置覆盖目标、Flyway 被配置独立 URL/凭据，或者目标具有相同可观察端点/用户/库名，仍可能在阶段三前发生写入。未实现可信实例 UUID/签名身份、TLS 认证或对每个框架写连接逐一授权；没有安全依据去猜测这些配置。

启动中实际可能发生：test profile 启用 Flyway（DDL、迁移 DML、schema history 写入），JDBC session 自动建表为 never；随后 ModelCredentialEncryptionMigrator 和 ConnectionCredentialEncryptionMigrator 可重写凭据/版本/授权范围，BuiltinSkillSeeder 可写技能数据，SkillLocalMirrorBackfill 可写本地镜像。测试方法内复核只能约束其后的操作，无法撤销此前行为。独立连接还不能证明服务器侧连接钩子、连接日志等完全无副作用。

## Gemini Lite C03 独立归因

| 尝试 | semantic_prompt_verdict | runtime_safety_verdict | 实际执行状态 | 独立原因 |
|---|---|---|---|---|
| A base | PASS | FAIL | awaiting_approval | MISSING_TYPED_PERSISTENCE_INTENT；ACTION_ELIGIBILITY_VETOED；EXECUTION_NOT_COMPLETED |
| A holdout | PASS | FAIL | awaiting_approval | 同上 |
| B base | INCORRECT | PASS | completed | OUT_OF_SCOPE_QUESTION_REQUESTED |
| B holdout CREATE_NODE | PASS | FAIL | awaiting_approval | MISSING_TYPED_PERSISTENCE_INTENT；ACTION_ELIGIBILITY_VETOED；EXECUTION_NOT_COMPLETED |

非追问动作的 PASS 仅表示符合冻结候选动作集合及不重复追问规则，不表示内容质量、人类批准或持久化授权已获证明。资格否决没有改变 semantic_correctness。B base 追问其他技术栈/中间件，属于范围漂移，重复追问违规数为 0、范围漂移违规数为 1。不能把 B holdout 或 A 的资格问题归为 Prompt 语义错误。

B 汇总：语义 INCORRECT，运行时 FAIL，推广 REJECT。除逐次原因外，还记录范围门槛、资格门槛、执行门槛失败；A 未证明存在重复追问，故本实验也不能证明消除了基线重复行为。原始历史 REJECT 的证据保留在独立产物的 historical_decisions；旧裁决未被改写为 ACCEPT。

`UNRESOLVED/TARGET_NOT_PROVEN` 保留其语义结论；证据裁决可以 INCONCLUSIVE，但独立 overall_promotion_verdict 为 REJECT。只有全部门槛满足才允许 ACCEPT。awaiting_approval 永远不计为 completed。持久化变更与资格、授权证据单独交叉检测；实际发生变更但缺失明确授权时也拒绝。没有变更证据不等于已证明数据库没有发生变更。

## 本轮最小修改依据

以下仅描述本轮增量；`git diff HEAD` 还包含前两轮尚未提交的修改，不能把总 diff 全算成本轮。

- `backend/src/eval/java/com/specagent/eval/EvalLiveIsolatedDatabase.java`：新增可注入独立 JDBC 预检查和服务器端点比较；清楚限制原种子守卫对启动副作用的保护范围；驱动异常不附原消息/原因，避免泄漏连接参数。
- `backend/src/test/java/com/specagent/modelsettings/GeminiCustomProviderSettingsVerificationTest.java`：在数据源属性注册前做独立预检查；显式数据库凭据同源；保留并增强池复核。
- `backend/src/test/java/com/specagent/eval/EvalLiveP3BDecisionQualityTest.java`：相同启动防护，移除默认目标，防止另一 P3B 入口保留同一漏洞。
- `backend/src/test/java/com/specagent/eval/P3BLiveDatabaseGuardTest.java`：新增 5 个离线测试方法，覆盖静态失败无连接、缺失配置、工厂失败且异常脱敏、成功/空结果/库名及同名库端点错配、真实 Spring DynamicPropertySource 失败阻止框架 Bean 初始化。全部连接对象为 mock，不创建真实数据源。
- `tools/p3b/p3b_semantic_ab.py`：保留原语义判断，加独立运行时和推广结论；资格未证明、未完成、异常终态、未授权变更失败关闭；逐项累计原因。完整性、Prompt 规则集及预算导致非接受时也保留已有维度和原因。
- `tools/tests/test_p3b_semantic_ab.py`：新增 10 个测试实例；成功夹具明确声明资格 PASSED，缺失资格另测拒绝；覆盖多重失败、语义/资格正交、未知语义、awaiting_approval、未授权变更及历史归因。
- `backend/src/test/java/com/specagent/eval/P3BC03NaturalLanguageScenarioTest.java`：移除共享集成父类；保留 6 项纯语料/规则测试。原第 7 项执行链路测试原样移至新 `P3BC03NaturalLanguageScenarioDatabaseIntegrationTest.java`，保留 completed、RESPOND_TO_USER、violations 断言；没有放宽业务标准。
- 新增本报告及版本化复核 JSON；未改生产默认 Prompt、冻结语料或旧实验产物。

## 实际执行与结果

仓库根目录，Python 命令使用已有环境（未安装依赖或修改 uv.lock）：

```powershell
.\agent-brain\.venv\Scripts\python.exe -m pytest tools/tests/test_p3b_semantic_ab.py -q
# 最终 56 passed
.\agent-brain\.venv\Scripts\python.exe -m pytest tools/tests/test_p3b_corpus.py tools/tests/test_p3b_replay.py tools/tests/test_p3b_semantic_ab.py -q
# 最终 75 passed
```

最初直接执行 pytest 时 PATH 无命令；系统 python 别名也没有运行测试。首次使用有效环境的回归为 44 passed、2 failed：旧成功夹具没有 eligibility_outcome。修正夹具证据、另加缺失资格拒绝测试后最终通过；没有放宽运行时门槛。

backend 目录，最终真正离线命令：

```powershell
.\gradlew.bat test --tests com.specagent.eval.P3BLiveDatabaseGuardTest --tests com.specagent.eval.P3BC03NaturalLanguageScenarioTest --offline
# BUILD SUCCESSFUL；25 + 6 tests，0 failures/errors/skips
```

最终 XML 仅这两个类，扫描无 HikariPool/FlywayExecutor/PgConnection 记录。守卫的预期启动失败由测试断言捕获。另曾单独执行守卫 `test --tests com.specagent.eval.P3BLiveDatabaseGuardTest --offline --rerun-tasks`，25 passed。编译有项目既有弃用/unchecked 警告；`git diff --check` 通过。

### 必须披露的首次 Java 执行越界

在检查测试父类之前，错误地执行了同一组合命令。当时 C03 测试仍继承 EvalHarnessBase：实际 25 + 7 项执行通过，但其中 C03 类启动真实 Spring、连接 `localhost:5434/spec_agent_test`（PostgreSQL 17.11），Flyway 校验 98 项迁移、报告 schema 已最新无需迁移，日志显示 JDBC 事务提交。时间为 2026-10-20 23:36–23:37（Asia/Shanghai）。Gradle `--offline` 只限制依赖解析，不阻止应用联网或连接数据库。

因此首次 BUILD SUCCESS 不能计入离线验收；已经违反本轮“不连接真实数据库”的边界。进程已正常结束，之后未重新运行数据库集成类，也未连接数据库检查或尝试撤销；本轮不会以更多数据库操作扩大影响。没有主动读取/打印/复制 API 密钥或调用真实模型实验；但由于生产上下文及凭据迁移 Runner 被启动，不能证明框架没有读取/重写凭据，不能宣称数据库净变化为零、清理完整或其他并发影响不存在。脚本化大脑测试的“offline”名称不能证明数据库离线。

此后完成测试拆分，原 6 项纯断言真正离线重新通过，数据库集成断言仍保留但未重跑。首次越界不是被后续通过抵消的结果。

## 未解决边界与下一阶段

独立连接可测分支与启动前 Bean 顺序已离线验证；真实 PostgreSQL 驱动、认证方式、服务器身份可信度、代理/NAT、多主机、后续路由漂移、独立 Flyway 数据源、运行时真实无持久化变更均未被离线证明。具体端点与认证配置须由已知可信运维来源提供，不能猜测。首次越界造成的实际数据影响同样尚未核实，本轮没有授权再次访问数据库核查。

明确结论：C03 不推广；C08/C09 仅可进入代码审查、纯离线测试准备，不进入真实模型/数据库实验。未提交、未推送，工作到此停止。

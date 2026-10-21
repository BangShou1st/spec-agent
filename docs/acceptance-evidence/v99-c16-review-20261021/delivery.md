# V99/V100/V101 与 C16 四阶段复核交付

日期：2026-10-21（Asia/Shanghai）。结论：完成代码审查、最小修复、离线验收及 dev 本地代码提交。新发现 P0 0 项、P1 2 项、P2 1 项，三项已修复；数据库迁移和完整 C16 WAIT 的发布资格仍未关闭。未连接数据库、执行 Flyway、启动业务应用、调用模型或其他外部服务，未推送。

## A. 审查发现和证据

行号对应本轮代码提交 6ac8ad2。修复前完整目标变更与修复后代码分别可由原 v99-c16-dev 证据及本目录 reviewed-full.patch.gz（本地亦保留未压缩原件）、reviewed-files.json 比较。

| 编号 | 严重性、位置 | 触发、影响和证据 | 最小修复与状态 |
| --- | --- | --- | --- |
| R1 | P1；ContinuationCheckRepository.java:37–49 | 完成事务已更新 invocation，但读取父 run 时仍看到非终态；父终态事务随后提交，其消费者读到尚未提交的 PENDING 并标记 PARKED。旧完成事务因 `r.status = 'completed'` 未写 outbox，最后提交后没有新的检查代数，结果永久失去唤醒。READ COMMITTED 下可构造此交错；父终态自己的 enqueue 不能排除此窗口。 | 对持久化同项目父身份始终 enqueue，不用父状态过滤；仍由协调器拒绝非终态/FAILED、执行预算和单子约束。早到检查评估失败保持可重试。已修复，离线 SQL 提交断言红→绿；数据库交错 NOT RUN。 |
| R2 | P1；CapabilityInvocationRepository.java:167–197 | completeDeferred 在事务中先读取身份和复核授权。旧 SQL 的 `now()` 固定为事务开始时间；授权/锁等待跨过 deadline 时，仍可能接收已超时结果；扫描若加入长事务也可能延迟超时判断。 | 完成和超时 CAS 比较及 completed_at 改为数据库 `clock_timestamp()`；保留 PENDING 条件和唯一行赢家。已修复，完成/扫描时钟断言红→绿；PostgreSQL 执行和锁后边界 NOT RUN。 |
| R3 | P2；ContinuationDispatchService.java:128–143 | expirePending 在 claimPending 前调用，原代码让超时扫描或其 outbox 异常直接退出。持续失败的过期批次可使其他已提交的普通 continuation 检查每轮都得不到处理。 | 独立捕获扫描 RuntimeException，记录异常类型，继续既有检查分发，过期批次下一轮重试。未吞掉检查分发错误。已修复；真实分发器、mock 事务/仓储测试红→绿。真实事务回滚 NOT RUN。 |

重要证据：regression-red.log 与 red-xml/ 实际记录 14 项中 3 项失败；regression-green.log 同集合通过。timeout-isolation-red.log 与 timeout-red-xml/ 实际记录 6 项中 1 项失败，最终整集合及提交后回归通过。R1/R2 的反例验证实际提交的 SQL 不变量，不执行 SQL，也不冒充真实并发复现；R3 实际驱动分发器及异常分支。

已逐项审阅 29 个目标文件（与上轮 changed-files.json 初始哈希完全一致）、完整 tracked diff、新文件、仓储 SQL、业务调用入口、状态与授权路径、测试和契约文档。保留已有嵌套资源引用、根 JSON Schema required、绑定摘要、ActionEligibility fail-closed、fake 引擎契约。未修改生产默认 Prompt；历史 C03 仍为 REJECT。

## B. V99/V100/V101 的实际保护范围

- V98 添加 nullable CHAR(64) 摘要，普通 AgentRun rowMapper 不读写该值，独立绑定仓储负责 bindOrVerify。只接受小写十六进制 64 位摘要，`legacy-unbound` 不能验证自身。已存在的有效摘要不允许换值。
- V99 原文对 `status NOT IN ('created', 'completed', 'failed')` 全部写入哨兵，没有有效摘要/NULL 保护。实际非终态六种为 created/running/context_built/model_called/reflected/persisted，终态只有 completed/failed。created 被 V99 特别排除；未知非 NULL 状态可能被覆盖，NULL 状态不满足 SQL NOT IN。awaiting_approval 是事件/UI phase，审批 origin 以 completed 落库，不能当作第九种持久化状态。acceptance 和失败重试创建独立后继，不复用历史绑定。
- LegacyBindingMigrationGuard.java:16–50 的 BEFORE_EACH_MIGRATE callback 仅在未应用的 V99 前运行，拒绝 autocommit，锁 agent_runs，发现将被覆写的任意非空非哨兵值就阻断，包含非法历史值。不会自动改数据、修改 run.status、repair Flyway history 或编造旧摘要。
- 正常 Spring Boot 入口组件扫描包含守卫；本机实际缓存 Boot 3.3.2 字节码表明 FlywayConfiguration 收集 Callback Bean，再构造 Flyway。Flyway 10.10.0 字节码表明逐迁移 callback 先于迁移执行，并在事务执行模板路径内。证据为 boot-flyway-bytecode.txt、flyway-migrate-bytecode.txt。这是静态装配与时序依据，实际 PostgreSQL 锁生命期、启动装配和回滚尚未运行。
- 应用外 CLI、裸 SQL、自建 Flyway 实例、自定义替换配置均不自动继承 Spring callback。仓库显式入口包括 MigrationV99ModelBindingBackfillTest.java:46/64 与 MigrationV94V95CompatibilityTest.java:72/103，使用 Flyway.configure() 且未注册该守卫；本轮没有运行这些数据库测试。不能把应用守卫宣称为所有迁移入口安全。
- V100 是前向不可变保护 trigger：NULL 首次设置允许；已有非空有效值、非法值、哨兵只能保持相同，换值或清空均拒绝。它保护不可变性，并不替代 Java 的候选摘要格式/状态校验。Java 只允许六种真实非终态首次绑定或验证；终态和未知状态 fail-closed。SQL 不做“自动修复”非法历史值，两者职责一致。
- V99 若已覆写原摘要，V100 不能恢复；需要独立历史快照/备份证据及另行审定的数据处置。当前实例是否应用过 V99 未查询，按可能已应用处理。没有改写 V98/V99，static-migration-fixtures.json 记录固定哈希；98→99→100→101 无重复版本。
- V101 给 V13 invocation 表增加 nullable TIMESTAMPTZ pending_deadline 与 PENDING 部分索引。状态原列 VARCHAR(20) 可承载 PENDING；Java rowMapper 读取状态/result，deadline 由 SQL 写入和扫描，不要求 DTO 再存一份。原 RUNNING 行不提升为 PENDING，旧数据兼容；旧二进制不认识 PENDING，混合版本滚动运行未获资格。

存在发布阻塞边界：未授权真实迁移、DDL/trigger、守卫锁与历史数据矩阵，不能据本次离线结果批准部署。

## C. C16 的实现与未完成项

已实现 Host 基础设施：显式 DeferredCapabilityAdapter（继承提交授权接口、要求完成授权）、V3/enforced 自动动作 opt-in、结果完整 id/key/capability 校验、持久化 PENDING 与适配器限定 1–86400 秒等待、重复调用回放而不重新提交、SUCCEEDED/FAILED 条件更新、同事务结果与 continuation outbox、PENDING 停靠、有界 SKIP LOCKED 超时扫描及既有轮询恢复。RUNNING/IN_PROGRESS 仍是既有认领/崩溃窗口，不伪装成可恢复依赖。失败父 run 不会被复活，子 run 继续使用既有锚点/预算/当前授权/唯一性与独立模型绑定。

完成入口只在内部 Java API，加载持久化 invocation 身份，适配器重新核验项目/actor/revision/source authority，再 CAS。outbox 从 durable invocation/run JOIN 校验同项目，不信任事件携带的父身份。提交前也经过 GuardedCapabilityAdapter；模型参数和资源引用仍经原资格/Schema/grounding/策略链。当前没有生产适配器，所以这里证明接口和调用次序，不能声称真实回调认证、租户撤权及下游资源授权已经实现并验收。

结果、超时与 outbox 使用 Spring 事务入口，失败传播使结果事务能够回滚；唯一行 CAS 与 outbox generation、既有单子约束构成持久一致性的实现设计。单元测试只证明 SQL 提交、返回值控制、异常传播、编排和存储状态读取，未证明数据库事务/锁/多进程 exactly-once。

仍未实现：生产 DeferredCapabilityAdapter、可靠下游提交/幂等和完成事件源、传输身份认证、早到回调持久重投、版本化 pending dependency 投影、WAIT 执行器订阅、等待提案与完成事件的身份关联。V3/enforced 无可信依赖的 WAIT 继续拒绝，V2/shadow 保留兼容 no-op。完成观测共享 fixture 可在 Java/Python 中往返，PENDING 不进入既有 wire。

真正唤醒、进程重启恢复、完成/超时并发、提交→登记崩溃窗口、真实权限撤回和完整 E2E 全部 NOT RUN。Host 基础设施交付不等于完整 WAIT 功能交付。

## D. 本轮真实验证

安全预检：读取根/independent 构建、settings、testOffline 白名单、各测试继承/扩展/上下文 fixture、DatabaseTestContextGuard 和 spring.factories 注册。普通测试只创建纯规则、Mockito 或明确本地 GenericApplicationContext；拒绝 Spring bootstrap 的负例在 datasource/动态属性/应用 Bean 前失败。独立构建排除 EvalHarnessBase；所有 JDBC 为 mock。root-task-graph.log、independent-task-graph.log 和 offline-selection.json 记录依赖图/范围。仅编译 eval/test 源码，不执行数据库集成类。

下列 Java 命令工作目录为 E:/project/spec-agent/backend，均仅在本次命令进程设置 `$env:OPENCODE_ZEN_LOCAL=''`，避免 composite build 带入外部任务；未设置数据库/外部服务授权。没有运行 cleanTest test 或完整应用。

| 状态 | 命令/证据 | 实际结果 |
| --- | --- | --- |
| FAIL（预期反例） | `.\gradlew.bat testOffline --tests com.specagent.agent.runtime.DeferredContinuationOfflineTest --tests com.specagent.capability.DeferredCapabilityRepositoryTest --offline --console=plain`；regression-red.log | 14 tests，3 failures，0 errors/skipped；对应 R1/R2 |
| PASS | 同上；regression-green.log | 14 tests，0 failures/errors/skipped |
| FAIL（预期反例） | `.\gradlew.bat testOffline --tests com.specagent.agent.runtime.DeferredContinuationOfflineTest --offline --console=plain`；timeout-isolation-red.log | 6 tests，1 failure，0 errors/skipped；对应 R3 |
| PASS | `.\gradlew.bat testOffline bootJar --offline --console=plain`；root-final-verified.log、root-final-xml/ | 最终 113 tests，0 failures/errors/skipped；bootJar 成功。较早 root-final.log 为 112 项，不累计 |
| PASS | `.\gradlew.bat -p independent clean test verificationScope --offline --console=plain`；independent-final.log、independent-final-xml/ | 103 tests，0 failures/errors/skipped；仅独立构建自身 clean，不触发根 test |
| PASS | 提交后 testOffline 指定 LegacyBindingMigrationGuardTest、AgentRunModelBindingRepositoryOfflineTest、DeferredContinuationOfflineTest、DeferredCapabilityRepositoryTest、DeferredCapabilityRuntimeTest、C16WaitContractTest 六类；post-commit-key-regression.log、post-commit-xml/ | 35 tests，0 failures/errors/skipped |
| PASS | 仓库根：`$env:PYTHONPATH='E:\project\spec-agent\agent-brain\src'; $env:PYTEST_DISABLE_PLUGIN_AUTOLOAD='1'; agent-brain/.venv/Scripts/python.exe -m pytest agent-brain/tests tools/tests -q`；python-final.log | 489 passed，0 failures/skipped |
| PASS | 同 PYTHONPATH/解释器：`tools/p3b/evidence_audit.py`；historical-evidence.json | 只读本地历史文件一致性；C03=REJECT |
| PASS | 同解释器：`tools/p3b/p3b_decision_replay.py --output docs/acceptance-evidence/v99-c16-review-20261021/contract-replay.json` | 11/11 outcome、11/11 action、0 payload mismatch；合成冻结契约回放，无模型调用 |
| PASS | `git diff --check`、提交暂存 diff 检查、固定迁移/原有 fixture 对比 | 格式通过；V98/V99 未改；已有契约 fixture 零变更，新增完成观测 fixture 双语言通过 |
| PASS | 四种任务图拒绝，均 `--offline --console=plain --dry-run`：`test`；`testOffline bootRun -PdatabaseIntegration=true`；`testOffline test -PdatabaseIntegration=true`；`testOffline evalLive -PdatabaseIntegration=true -PexternalServices=true` | 均 exit 1 且命中目标拒绝规则；task-rejections.json、reject-*.log；预期拒绝，无任务动作执行 |
| NOT RUN | PostgreSQL/Flyway 实际升级、DDL trigger、回调锁/提交/回滚、真实并发、重启恢复 | 本轮明确禁止访问/创建数据库，待独立授权 |
| NOT RUN | 真实模型/外部适配器、完整 WAIT、跨进程双语言 E2E、远端 CI、干净环境完整后端构建、UI/浏览器 | 不在本次安全离线验证范围 |

两套 Java 集合和提交后 35 项存在重叠，绝不相加计算独立覆盖。离线 bootJar 使用本机依赖缓存，不能证明私有 Zen 包在干净 CI 可解析。静态 SQL 与 JDBC mocks 不替代真实迁移和数据库并发；回放不代表真实模型行为质量。编译弃用、unchecked 与 JVM CDS 警告保留在日志。完整目标 patch 以 gzip 原字节入库，避免 patch 上下文空行被误判为新增尾随空格；超时反例 XML 的可读副本仅规整行尾空白，原始 XML 同目录 gzip 保留。

## E. Git 交付与用户文件保护

起点为 dev@9f85ff5a7adb0614fc08e167ebc73c8b572d45fc，原暂存区为空。两处 worktree 保持原样，没有新建分支/worktree。上轮 29 项目标文件起始哈希全部匹配，说明本轮确实在既有交付上复核；当前提交不是替代先前已提交的安全修复。

1. b2689f00488056f23d0ba78e841c23a583054ec8：迁移守卫、V100、绑定仓储及其离线回归，5 文件。
2. 6ac8ad23bbfb90c804b69e845ea9fcf0737a43d2：异步基础设施、V101、三个新发现修复、双语言契约/fixture、测试白名单和实现边界文档，24 文件。
3. 本报告及本轮审查/离线证据另作 docs 提交；最终 dev HEAD、全部提交文件清单和最终原始 Git 状态见本轮完成后生成的 final-git-state.json、commit-files.json、final-status.txt。报告自身提交的 SHA 由这些提交后旁证记录，避免文档自引用哈希。

前两项逐文件清单在 code-commits.json。仅显式暂存这些目标路径和本目录自生成审查证据；未使用 git add -A / commit -a、stash、reset、clean、强推或历史文件恢复。

开始时逐文件记录了 597 个修改/删除/未跟踪路径。除 29 个授权目标外的 568 个路径逐项复核，内容哈希和存在/删除状态全部不变，见 before.json、protected-files-check.json。上轮单独保护的 468 个文件也全部一致，见 historical-protection.json。用户原 report.md 删除、uv.lock、事故文档、历史审计/交付目录及授权清单均保留，不进入本轮代码提交。initial.patch 是含用户原有无关删除的完整起始 diff，仅留作本地旁证，不纳入 docs 提交。

main 保持 74b9815d69a562d6782c4efbef1b7daf3f075905；codex/main-ci-independent 保持 400ac62be3a6518f649e652b4634c2adf0de75e5，其工作树仍干净。PR #19 未操作。推送：否。最终暂存区应为空，工作区仍保留用户原有修改/删除/未跟踪文件；不将“不是干净工作区”误报为未完成交付。

## F. 后续最小路线（继续使用 dev）

1. 先取得一次性隔离 PostgreSQL 环境的明确授权：单独实例/端口/数据库/凭据和可销毁范围，禁止指向既有 spec_agent/spec_agent_test；指定确切目标和身份核验，workers 停写，保留回滚快照，不复用历史数据库只读审计授权。授权前本轮这些操作均 NOT RUN。
2. 在隔离库跑空库、V97/V98→101 及已 V99 目标；六非终态/两终态/未知历史状态 × NULL/有效摘要/哨兵/非法值。验证 V99 回调阻断及锁、created/终态保留、V100 首次/同值/覆写/清空、DDL 失败回滚与重复启动；自建 Flyway 必须显式注册 callback。已损失摘要只能依据独立证据处置。
3. 用受控事务屏障复现 R1 的完成→读旧父状态→父提交→消费者停靠→完成提交交错，并覆盖早于/晚于终态、失败父 run、outbox 注入失败回滚、过期批次失败不阻塞普通检查；跨 deadline 的长授权事务及真实完成/超时 CAS 竞争必须执行 PostgreSQL 反例。
4. 实现一个经过授权的生产适配器：下游 invocationKey 幂等、持久提交/登记恢复、可靠完成认证和重投、撤权与资源/revision 复核；验证提交/登记/完成/outbox 各提交点 kill/restart、多 worker 竞争和单子唯一性。不要把当前接口当生产适配器。
5. 版本化 pending dependency 投影与 Java/Python schema 同步，再实现 WAIT 订阅、proposal/dependency/completion 关联、期限/取消/重试策略；完成负例与成功唤醒后，才讨论开放 V3 WAIT。
6. 单独处理干净 CI 的私有依赖解析、隔离数据库 CI、双语言真实进程与完整 E2E。保持 C03 REJECT、生产默认 Prompt 和 PR #19 范围；本次不创建新长期分支。

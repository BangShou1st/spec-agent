# C16 V4 WAIT：实现、复核与离线交付

日期：2026-10-21（Asia/Shanghai）。起点 dev@ede535e4acd920d2a090a676ac88563be611476d。本轮完成 V4 pending 投影、WAIT 订阅、完成/超时事件路由、续跑状态恢复的 Host 实现和本地提交；没有生产异步适配器，不能宣称完整异步 WAIT 已通过生产或 E2E 验收。

## 已实现的执行链

1. **版本化、持久冻结**：新增 agent-input.v4 / agent-decision.v4，以及 pending-dependencies.v1。ActionEligibilityGate.java:71 在 enforced 决策中调用 Host 端口冻结扩展；shadow 仍返回 V2。V2/V3 均不能携带 pendingDependencies，显式 null 也拒绝，不修改旧图快照形状。Java/Python、脚本 Brain 和本地确定性引擎均支持 V4 响应。
2. **依赖来源与边界**：WaitDependencyService.java:24 和 Repository.java:16/52/70 校验持久化 run/contextSnapshot/project/route/anchor，并通过 ProjectService 的当前 actor/org/project 写权限锁和适配器/来源授权守卫。只投影同项目、同路线、同锚点、已登记且未过期的 PENDING invocation，排除自己、失败/未知 owner；RUNNING 不能成为依赖。最多 20 项，不暴露下游 token/凭据。投影按 run 唯一冻结、JSON 哈希校验，重放不重新扫描 live pending；读取旧投影仍重新授权。整个扩展最多 1,000,000 字符。
3. **具体订阅**：WAIT payload 必须恰好是 dependencyRef，值为投影内 invocation UUID。Java、Python、Java 最终动作信任边界和独立 Python 响应校验器均拒绝空值、非字符串、虚构引用、跨作用域或额外参数。WAIT 不能通过模型自由文本创造依赖。订阅再次验证 owner/capability/project/context，授权锁先于 invocation 行锁。
4. **事务边界**：DecisionExecutionService.java:233 的 V4 WAIT 分支在既有 guarded/fencing 事务回调中执行订阅和 run 终态化/outbox；注册服务要求 MANDATORY 事务，不允许独立静默落订阅。WAIT 的 run 仍为 COMPLETED，实际停靠状态由 WAITING/READY/INVALIDATED 订阅表达，结果 outcome/trace 为 waiting_dependency，不增加伪造 run.status。旧执行器 WAIT no-op 仅保留旧协议兼容路径。
5. **完成与订阅早晚次序**：Repository.java:93 在锁 invocation 后登记订阅。冻结后已经 SUCCEEDED/FAILED 的 invocation 直接得到 READY，不再等待另一个回调；未过期 PENDING 得到 WAITING；过期 PENDING 拒绝新订阅。每个 wait run 一条订阅，冲突不覆盖 invocation/proposal/snapshot 身份。
6. **完成/超时到 outbox**：ContinuationCheckRepository.java:37 在结果事务中将匹配的 WAITING 订阅改为 READY，同一 SQL CTE 将 invocation owner 与同项目订阅 run 的请求代数一起写入现有 outbox。没有按 run 是否已 COMPLETED 过滤，保留此前丢失唤醒修复。重复完成仍受原 PENDING CAS 限制；超时与完成继续使用原同一行竞争、数据库 wall clock 和有界扫描。UNION 去重目标，既有 generation 和单子唯一约束继续裁决重复唤醒。
7. **恢复、失效和失败证据**：Coordinator.java:146 从持久订阅读取 WAITING 停靠、READY 续跑、INVALIDATED 停止；已有子 run 先返回既有身份。路线/锚点变化、失败或未知 owner 在评估时失效，失败 subscriber 不会复活。明确的项目/组织永久撤权、用户禁用拒绝，在失败评估事务结束后通过 REQUIRES_NEW 取消仍无子 run 的 WAIT，并标记对应 outbox 代数；未知授权范围、无 actor 和临时错误保持失败/重试，普通 continuation 不被取消。
8. **续跑能看到对应事实**：恢复子 run 的 V4 投影包含恰好被订阅的完成结果，不依赖最近 20 条结果窗口。结果在冻结/重放时重新授权，SUCCEEDED/FAILED（含超时失败）都是外部观测，不能自动变成 confirmed Graph truth，也不扩大旧图快照 allowedSourceRefs。

V102 增加 agent_run_dependency_projections 与 agent_run_wait_subscriptions、外键/唯一性和 WAITING 索引；没有执行该迁移。既有异步 invocation owner 仍自动停靠，另一条有权、同路线/锚点的决策可以显式选择 WAIT；本轮没有为了让模型选 WAIT 而额外强制调用模型。

## 本轮复核、失败反例及修复

以下反例针对本轮初版实现或 ce16b77，不是声称在原 ede535e 上执行了尚不存在的新类测试。

| 反例 | 实际红结果 | 最终修复/证据 |
| --- | --- | --- |
| 冻结依赖与输入能力目录不一致仍被重放 | java-prototype-red.log：17 tests，1 failure | 重放检查能力目录一致性，并继续当前 adapter/source 授权；java-green.log 通过 |
| 非字符串 dependencyRef 导致 Python TypeError；独立校验器允许 V4 响应降为 V3 | python-red.log：16 tests，2 failures、14 passed | 先验证类型，再匹配引用；校验请求/响应版本。Python 全集最终通过 |
| 永久撤权导致 WAIT outbox 无限尝试 | revocation-red.log：5 tests，1 failure | 失败事务结束后以显式 REQUIRES_NEW 失效订阅；实际 Spring 无资源事务管理器验证 2 次 begin、1 rollback、1 commit；另以 membership-red.log 的 9 tests/3 failures 反例补齐 NOT_ORG_MEMBER、MEMBERSHIP_DISABLED、ORG_DISABLED；未知授权范围测试保证不误取消 |
| Java V2/V3 接受 pendingDependencies:null，Python 拒绝 | null-extension-red.log：6 tests，1 failure | AgentContracts 在请求解析时额外检查字段存在性，旧版本一律拒绝；两端补充同一负例，最终根/独立/Python 全集通过 |

另补：无依赖/跨项目/伪造引用/重复依赖/不匹配 snapshot、目录撤销、项目撤权、冻结首次写入及重放、私有结果重新授权、超大投影、完成早于订阅、订阅不可替换、结果事件路由、WAITING→READY、协调器重建、重复唤醒返回同一 child、INVALIDATED/FAILED 不续跑、注册失败不终态化。结果为 source/SQL 提交和确定性状态机/编排证据，不能证明真实 SQL 并发。

早期 python-prototype-red.log 曾包含一个测试数据错误：替换 contextHash 的值与原值相同。已纠正，此项不计为实现缺陷；正式 python-red.log 的两项失败才作为红绿证据。原始日志均保留本地。

## 适配器的真实结论

已只读调研 resource.extract_text、MCP callTool 和既有检索索引工作负载。选定后续桥接**已经请求的项目检索代际就绪状态**（建议 retrieval.await_generation），复用 RetrievalSourceJobs / RetrievalIndexJobs 的持久身份、version、lease、grant、deadline。

目前仍缺 invocation↔generation 身份/权限委托、可靠完成事件 outbox、早到事件重投和真实环境资格；本轮没有创建该生产描述符/适配器，没有把同步 MCP 调用或进程内 Future 伪装成异步任务。具体源文件、桥接组件和前置条件见 adapter-research.md。

## 本轮实际验证

安全预检和范围在 offline-scope.md；完整命令/环境在 commands.json。以下均实际运行，Java 集合有重叠，不相加。

| 状态 | 实际命令（仓库根；Java 每个命令进程清空 OPENCODE_ZEN_LOCAL） | 结果 |
| --- | --- | --- |
| PASS | `.\backend\gradlew.bat -p backend testOffline bootJar --offline --console=plain` | 最终 144 tests，0 failures/errors/skipped；本机缓存编译/bootJar 成功，root-membership-final.log |
| PASS | `.\backend\gradlew.bat -p backend testOffline --offline --console=plain` | 严格 null 修复提交后再次 141 tests，0 failures/errors/skipped；post-fix-full.log；后续组织参数反例纳入上述最终 144 项全集 |
| PASS | `.\backend\gradlew.bat -p backend/independent clean test verificationScope --offline --console=plain`；最终更新后同命令去掉 clean 再执行 | 109 tests，0 failures/errors/skipped；independent-full.log、independent-null-final.log；只 clean 独立 build |
| PASS | testOffline 指定本轮五类离线测试；修复提交后再次指定 ActionEligibilityWaitV4Test | 分别 28 项、6 项通过；组织撤权修复提交后再执行 WaitLifecycleOfflineTest，9 项通过；与全集重叠，不累计 |
| PASS | `agent-brain/.venv/Scripts/python.exe -m pytest agent-brain/tests tools/tests -q`，PYTHONPATH 指向当前 agent-brain/src，PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 | 506 passed，0 failures/skipped；python-null-final.log |
| PASS | 同解释器 `tools/p3b/evidence_audit.py` 与 `tools/p3b/p3b_decision_replay.py --output docs/acceptance-evidence/c16-wait-v4-20261021/contract-replay.json` | 历史一致性通过，C03=REJECT；11/11 outcome、11/11 action、0 payload mismatch；没有真实模型调用 |
| PASS | 三组 `--dry-run`：根 test；testOffline+bootRun；testOffline+evalLive（含 databaseIntegration/externalServices opt-in） | 均 exit 1 且命中目标任务图拒绝，未执行任务动作；task-rejections.json |
| PASS | git diff/check、V98–V101 与生产 decision Prompt 原字节/归一哈希、原有文件保护 | 无格式错误；既有迁移、守卫和 Prompt 未被改写；574 个原路径无变化 |
| NOT RUN | V102 真实迁移、SQL/约束执行、Spring/Flyway 真装配、事务锁/回滚与并发竞争 | 本轮禁止任何现有数据库连接/迁移及新建数据库；静态检查和 mocks 不能替代 |
| NOT RUN | 真实 adapter、真实完成源、跨进程重启/重投/权限撤回、双语言真实进程 E2E、浏览器 UI 验收、远端/干净环境 CI | 生产适配器和对应环境条件未就绪；未启动应用、真实 Brain 或模型服务 |

java-test-xml.zip 保存最终根/独立、提交后以及红例 XML；verification.json 提供结构化计数。弃用、unchecked、JVM CDS 与 Gradle 警告保留。离线 bootJar 不证明私有包在干净环境可解析；脚本模型测试和契约回放不代表真实模型表现。

## Git 与文件保护

- ce16b777baa2bc2a4e0db1cedc63fdcbdfaaf953：V4/Host WAIT 实现、V102、双语言契约/fixture、测试和设计文档，共 34 文件。
- 8fa66205b4198c1654f5fffb7d4416b441d05d4b：旧协议显式 null 扩展拒绝及双语言反例，3 文件。
- 8108535b9ce73d530c4933b58a7666f28cf578eb：补齐组织禁用、成员移除、成员禁用的 WAIT 失效分支及三项反例，2 文件。
- 本报告及精简验收证据另作 docs 提交。三笔代码累计 35 个唯一目标路径，完整清单/哈希见 code-files.json，各提交文件见 code-commits.json。最终 dev HEAD、含证据提交的清单和原始 Git 状态由文档提交完成后的 final-git-state.json / commit-files.json / final-status.txt 旁证记录，避免文档自引用 SHA。

仅显式暂存上述目标文件及本目录自生成证据。原有 574 个修改/删除/未跟踪路径逐项检查内容哈希、存在状态和 Git 状态，均保留；包括原 report.md 删除、uv.lock、事故文档、历史审计清单和全部历史证据。本轮原始日志/XML的完整副本保留本地；提交的归档和精简证据不是缓存或应用运行数据。没有 stash/reset/clean、覆盖用户文件或恢复原删除。

保持 dev 为唯一开发主线，没有创建分支/worktree。main 保持 74b9815d69a562d6782c4efbef1b7daf3f075905，PR #19 工作树/分支保持 400ac62be3a6518f649e652b4634c2adf0de75e5 且干净。未操作 PR #19，未推送。最终暂存区为空，工作区保留用户原状态及少量提交后旁证文件。

## 剩余发布门槛与下一步

1. 明确授权一次性隔离 PostgreSQL 目标/身份/可销毁范围后，执行 V102 的真实建表、FK、删除顺序、冻结唯一性与损坏拒绝；不得使用既有 spec_agent/spec_agent_test。本轮没有重复 V99/V100/V101 迁移代码审查或执行迁移；既有守卫纯离线回归仍随白名单保留运行。
2. 用事务屏障执行 subscribe-before-complete、complete-before-subscribe、过期边界、两个完成与超时、多 worker、取消/撤权、outbox 失败回滚和 generation 变化矩阵；验证行锁顺序、早到 READY 与单子唯一性。检验永久撤权取消的新事务在真实外层/afterCommit 路径下独立提交。
3. 实现所选检索代际适配桥接及可靠事件源；复核 actor/org/project/source version 的当前授权，补提交/登记/完成各崩溃点的持久恢复。真实适配器接入前没有生产 PENDING 来源，本轮不能关闭完整 WAIT 功能交付资格。
4. 协调 Host+Brain V4 与 V102 发布，停写/排空 worker，验证旧版回退和混合版本拒绝，不允许静默协议降级。当前 shadow 默认和生产默认 Prompt 均未修改。
5. 在 dev 继续真实进程恢复、数据库集成 CI、完整 E2E 与可见等待状态验收；解决干净 CI 依赖条件。C03 继续 REJECT，禁止把本轮单元测试计作完整异步 WAIT 已验收。

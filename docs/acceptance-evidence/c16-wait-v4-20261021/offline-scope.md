# 离线执行边界

本轮在 ede535e 基线上继续 C16，没有重复 V99/V100/V101 代码审查，仅核对原字节保护。

执行前读取 backend/build.gradle.kts、settings.gradle.kts、independent/build.gradle.kts、DatabaseTestContextGuard、已注册 spring.factories、现有离线白名单与新增五类测试的完整源码。root-task-graph.log 记录 dry-run：compileJava/processResources、compileEvalJava、compileTestJava、testOffline；没有应用启动任务。

新增允许类：ActionEligibilityWaitV4Test、WaitDependencyServiceOfflineTest、WaitDependencyRepositoryOfflineTest、WaitLifecycleOfflineTest、WaitDecisionExecutionOfflineTest。没有 SpringBootTest、真实数据源、DriverManager、测试基类继承或外部调用。Lifecycle 中实际 Spring TransactionTemplate 使用无资源的 AbstractPlatformTransactionManager，只验证 callback/commit/rollback 次序，不连接数据库，不证明数据库回滚。

独立构建只纳入真实 protocol/action/validator 等白名单源码及 ActionEligibility*Test，包括新 V4 契约测试；没有引入 Host JDBC 服务或 EvalHarnessBase。其 clean 仅清理 independent/build，不执行根 test。

每条 Java 命令仅在所属进程清空 OPENCODE_ZEN_LOCAL，避免 composite build 任务混入；--offline 只限制依赖解析，安全保证来自经过审计的测试范围和任务图。根 test / testOffline+bootRun / testOffline+evalLive 均以 dry-run 验证拒绝，未执行任务动作。

Python 使用现有 venv、当前源码 PYTHONPATH、PYTEST_DISABLE_PLUGIN_AUTOLOAD=1；本轮新测试使用本地 scripted client。没有真实 Brain/model 服务、数据库或集成应用启动。未做 UI 改动或浏览器验收。

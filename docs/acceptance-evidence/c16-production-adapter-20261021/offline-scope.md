# 本轮离线执行范围

读取并复核根 build.gradle.kts/settings.gradle.kts、independent 白名单、既有数据库测试启动保护、
新增四类测试及两个 ArchUnit 类的完整调用链后才执行测试。新增类不使用 SpringBootTest、
数据源、DriverManager、测试容器、模型客户端调用或继承集成基类。

RebuildReadinessOfflineTest 实际构造生产管理/worker 对象，但所有 JDBC、授权、配置及工作接口
均为 Mockito mock。只调用 start/retry/discard/finish/fail；不调用 tick、HTTP 或 Python worker。
管理对象构造的 PythonRetrievalClient 仅保存配置，不发起连接。LocalTransactions 是无资源的
AbstractPlatformTransactionManager，验证真实 Spring callback 次序，不证明数据库回滚。
ArchUnit 只读取字节码，不初始化业务类或启动应用。

根任务只运行 testOffline 的经过审计白名单。compileEvalJava/compileTestJava 是编译，不能
把它们输出的历史 IntegrationTest 文件名误认为执行了集成。bootJar 只打包，不启动应用。
independent clean 仅清理其 build；范围由 verificationScope 输出记录。成功任务图含编译、
资源、testOffline 和 bootJar，无其他 Test/JavaExec。三条危险组合均在 --dry-run 被拒绝。
首轮编译命令使用现有环境；其任务日志只有根任务，没有 composite build，测试未执行便
因新测试的包名被同名 UUID 变量遮蔽而编译失败。修正后命令显式清空 OPENCODE_ZEN_LOCAL。

Python 使用当前 venv、明确的源码 PYTHONPATH、PYTEST_DISABLE_PLUGIN_AUTOLOAD=1，
新增用例使用本地 scripted Completion，复用已审计的既有离线套件。契约重放和历史证据审计
只读取本地文件，没有新模型请求。C03 历史结果仍 REJECT。

最终成功命令：

```
backend/gradlew.bat -p backend testOffline bootJar --offline --console=plain
backend/gradlew.bat -p backend/independent clean test verificationScope --offline --console=plain
agent-brain/.venv/Scripts/python.exe -m pytest agent-brain/tests tools/tests -q
agent-brain/.venv/Scripts/python.exe tools/p3b/evidence_audit.py
agent-brain/.venv/Scripts/python.exe tools/p3b/p3b_decision_replay.py --output <本轮证据>/contract-replay.json
```

没有连接既有或新建数据库、执行 SQL/Flyway、调用真实模型、启动应用或浏览器验收。
SQL 提交/RowMapper 测试不执行 PostgreSQL；事务、迁移、跨进程和 E2E 均 NOT RUN。

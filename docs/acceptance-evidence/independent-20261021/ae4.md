# AE-4 独立验收（2026-10-21，dev）

状态：**PASS（35/35 离线守卫测试；不是完整 Spring/数据库集成验收）**。

## 变更与依据

- 保留已有 AE-4 补丁；`DatabaseTestContextGuard` 原来只识别 `SpringBootTest`、`DataJdbcTest`、`JdbcTest`，`ContextConfiguration` 可绕开启动前授权。现在同时识别 `ContextConfiguration`、`ContextHierarchy`、组合注解 `SpringJUnitConfig` 和 `BootstrapWith`（包含 Boot slices）。无授权在创建 TestContext 时立即拒绝，先于 DynamicPropertySource、凭据读取、连接请求及应用 Bean 初始化。
- 外部服务标签使用 Spring 的 merged repeatable annotation 查找，覆盖组合注解。单独数据库授权不能批准外部服务。
- 新增无授权入口反例及显式授权正例；正例仅通过 Mockito 提供数据库身份，验证 readOnly → statement → timeout → identity query 顺序，无真实数据库连接。
- 原 `c03RuleAndIntegrationDiscoveryStaySeparate` 原样移至 `DatabaseTestDiscoveryTest`，保留真实类发现断言。它需要完整 eval/业务类型，独立编译不纳入此类；根 `testOffline` 白名单由主代理维护。
- 更早的入口拒绝使旧 `dynamicPropertyPreflightFailsBeforeFrameworkBeanInitialization` 断言不再适用。现分成两条精确契约：未授权 TestContext 抛授权错误；直接调用非法目标 preflight 抛普通库拒绝错误。后者 connection factory 遇调用即失败，继续证明无 JDBC 连接。
- `backend/independent` 只增加真实源码的明确白名单和 Spring/JUnit/Mockito 依赖。该构建不使用完整 backend outputs、starter 或复制实现，守卫的 spring.factories 按实际资源加载。测试属性固定 false。

## 实际命令与结果

以下 PowerShell 命令在仓库根目录执行，Gradle 命令在 `backend` 执行。

| 命令/证据 | 结果 | 说明 |
| --- | --- | --- |
| `& docs/acceptance-evidence/independent-20261021/run-ae4.ps1`，`ae4-manual.log` | 初次失败，33/34 | 新守卫更早拒绝，旧测试期待 DynamicPropertySource 的普通库错误。保留失败日志。 |
| 同一脚本，`ae4-manual-rerun.log` | PASS 35/35，0 skipped/aborted | 调整契约测试后通过。 |
| `./gradlew.bat -p independent clean test verificationScope --offline --console=plain`，`ae4-gradle.log` | 初次依赖解析失败 | 本地没有 Mockito 默认传递的 byte-buddy-agent 1.14.12。 |
| 同一 Gradle 命令，`ae4-gradle-rerun.log` | PASS 73/73，0 skipped | Byte Buddy 显式对齐后端 Boot 3.3.2 的 1.14.18；其中 AE-4 为 35/35，其他既有独立测试 38/38。 |
| `./gradlew.bat -p independent test --offline --console=plain`，`ae4-gradle-final.log` | 编译失败，无测试执行 | 并行加入的 P3B 聚合闭包当时缺 `PropertyCheck`；主代理已接手补齐与最终统跑。此日志不视为 AE-4 通过证据。 |
| 最终版本同一手工脚本，`ae4-manual-final.log` | PASS 35/35，0 skipped/aborted | 包含最后加入的 BootstrapWith/slice 入口拒绝；使用当前 Java 源重新编译、真实 Spring factory 和 JUnit Launcher，不用旧 class。 |

手工脚本从本机 Gradle 缓存读取 Spring 6.1.11、Boot 3.3.2、JUnit 5.10.3、Mockito 5.11.0 等真实 jar，不下载或构造 starter。它是离线补充证据，不能取代固定依赖的独立 Gradle 构建。主代理汇总文档记录最终合并闭包后的 Gradle 结果。

## 安全边界和未验收项

- PASS：无授权 Spring 测试入口拒绝；组合外部标签拒绝；显式授权与模拟身份通过；静态普通库拒绝；同名异服务器/用户/端口拒绝；数据源与独立 Flyway 目标漂移在应用 Bean 前拒绝；模拟连接资源关闭；种子重置前拒绝非法库。所有正反例无真实 DB/provider 请求。
- 本机最终完整 classpath 的 `testOffline` 已执行 C03 集成类型发现测试并通过（只加载元数据，不执行集成方法）；该类不纳入独立编译项目。干净环境中需要完整业务类型的这一检查仍受 Zen 编译依赖限制。真实 Spring 应用集成验证未运行，不能将本报告的本地 GenericApplicationContext 成功描述为生产 Spring 上下文启动通过。
- `NOT_MEASURED`：真实隔离数据库的显式授权集成、Flyway/数据库网络访问、Provider 执行、子进程端到端测试。本轮没有创建、连接、迁移或修改数据库，没有改变 Provider/Prompt，没有新增模型请求。
- factory 保护 Spring TestContext 引导，不能构成任意 JVM 代码沙箱；手写 SpringApplication、ApplicationContextRunner、子进程不自动受此 factory 保护。这些入口不在已审计离线白名单，独立构建也不编译其测试。未来扩充白名单必须审查继承、静态初始化、extensions 和整个调用链。
- 根 Gradle 的任务图守卫由主代理补齐；实际拒绝 `testOffline bootRun`、带授权的 offline+test/offline+live 组合以及未授权 test。最终根白名单 48/48、独立项目 85/85 通过，详情和原始日志见 delivery.md；这两套有重叠，不能相加作为覆盖数。
- 恢复共享库后须重跑完整源码编译、C03 真实类型发现，以及仅针对新建且核实隔离库的 Spring 启动和显式授权集成验收。

# 独立 Java 确定性验证

本目录是单独的 Gradle build。它直接编译仓库中的真实 Java 源码并运行已有测试，不复制或修改业务实现，不生成第三方替身，也不加载 `backend/build.gradle.kts`、生产 source-set output、生产依赖配置或 eval output。原 backend 构建及产品运行行为不变。

在仓库根目录运行（JDK 21）：

```powershell
backend/gradlew.bat -p backend/independent clean test verificationScope
backend/gradlew.bat -p backend/independent dependencies --configuration testRuntimeClasspath
backend/gradlew.bat -p backend/independent dependencyInsight --dependency opencode --configuration testRuntimeClasspath
```

Linux / CI 对应命令：

```sh
bash backend/gradlew -p backend/independent clean test verificationScope
bash backend/gradlew -p backend/independent dependencies --configuration testRuntimeClasspath
bash backend/gradlew -p backend/independent dependencyInsight --dependency opencode --configuration testRuntimeClasspath
```

测试工作目录固定为原 `backend`，因此原测试读取 `../contracts/fixtures` 的路径无需改动。首次使用需要下载 Gradle 和 Maven Central 公开依赖；依赖缓存就绪后可追加 `--offline`。

## 编译与测试范围

2026-10-21 本地实跑：54 个生产 Java 文件、4 个测试文件，38 个测试通过，0 失败、0 跳过。白名单由本目录 `build.gradle.kts` 定义；`verificationScope` 输出当前提交的精确文件列表和数量。

| 真实生产源码范围 | 文件数 |
| --- | ---: |
| `agent/protocol` DTO、枚举、严格 JSON 契约及异常 | 38 |
| `ActionEligibilityEvaluator`、`ActionEligibilityValidator`、`ActionEligibilityGate` | 3 |
| `AgentDecisionEngine`、`AgentBrainResponseValidator`、`DeterministicEngineFaultPlan`、`LocalDeterministicDecisionEngine` | 4 |
| `Hashes`、`Json` | 2 |
| `RetrievedContextItem`、`RetrievalSourceKind`、`RetrievalScope`、`MemoryAuthority` | 4 |
| `ModelOutputContract`、`ModelInferenceRequest`、`ModelInferenceMessage` | 3 |

| 已有测试类 | 测试数 |
| --- | ---: |
| `ActionEligibilityCharacterizationTest` | 22 |
| `ActionEligibilityEvaluatorTest` | 4 |
| `ActionEligibilityGateTest` | 4 |
| `ModelOutputContractTest` | 8 |

测试覆盖资格拒绝规则、确定性哈希、shadow/enforced 行为，以及 provider 无关输出契约。报告保存在 `build/reports/tests/test/index.html`，JUnit XML 在 `build/test-results/test/`。

## 依赖边界与结论限制

依赖只从 Maven Central 解析：Jackson 2.17.2、Spring Context 6.1.11、Spring Boot Autoconfigure 3.3.2（仅为真实源码中的注解提供类定义）、JUnit 5.10.3、AssertJ 3.26.3 及其公开传递依赖。测试不启动应用上下文、数据库或 provider，也不需要凭证。

实跑的 `testRuntimeClasspath` 报告无 `com.sharedlibs`、OpenCode Zen 或 Kilo SDK；`dependencyInsight --dependency opencode` 输出 `No dependencies matching given input were found`。这里通过只证明上述业务子集通过，**不表示完整 backend、Spring 装配、数据库迁移、eval、跨语言或浏览器 E2E 集成已通过**；这些仍由显式完整验证路径负责。

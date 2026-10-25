---
status: Historical
authority: Historical Reference Only
---

# PR-22-REVIEW-FIX-01 — 修复说明(fix-summary)

## 修改清单(1 个产品文件 + 3 个测试文件 + 2 处文档)

| 文件 | 变更 |
|---|---|
| `backend/src/main/java/com/specagent/modelsettings/CustomProviderSettingsService.java` | `connectionSettings(id, revision)` 新增:当 `revision == null`(新 Run 准入)时要求当前 (org, UUID, config_revision) 的 `STRUCTURED=VERIFIED`,否则抛 `GA_MODEL_BINDING_MISMATCH`;`revision != null`(冻结绑定)不变 |
| `backend/src/test/java/com/specagent/modelsettings/ModelConnectionsOfflineTest.java` | +1 用例:降级后 UUID 新 Run 三个读取入口拒绝、冻结引用继续、恢复 VERIFIED 后放行、NATIVE_TOOLS 证明不替代 STRUCTURED |
| `backend/src/test/java/com/specagent/modelsettings/ModelConnectionsIntegrationTest.java` | +2 用例(隔离库 + FakeModelProvider):见下 |
| `backend/src/test/java/com/specagent/assistant/GaExecutionModelTargetResolverTest.java` | +1 用例:运行时边界拒绝时 GA 准备阶段终态化为 `GA_MODEL_NOT_CONFIGURED` |
| `docs/acceptance-evidence/release-readiness-01-20261025/a3-verification.md` | P2 勘误:skipped 分类 25→23,附勘误说明 |
| `docs/acceptance-evidence/pr-22-review-fix-01-20261025/` | 本证据目录 |

集成回归覆盖(`ModelConnectionsIntegrationTest`):

1. `structuredDowngradeClosesNewRunAdmissionButKeepsFrozenBindings`:VERIFIED→激活→项目 Run
   绑定冻结→同 revision 复验失败(429)→能力降级且 validated 标志保留→断言:新 GA Run
   `resolveActive()`=Unavailable;`activeTarget()` 抛 `GA_MODEL_BINDING_MISMATCH`;新
   Project Agent Run 经网关 `complete` 抛 `MODEL_BINDING_MISMATCH` 且零供应商请求;直接
   `requireRuntimeSettings()`/`(UUID)` 读取拒绝;`requireActivatable()` 同拒;冻结引用
   `requireRuntimeSettings(reference)` 与 `requireFrozen` 继续;错误消息不含密钥;恢复 200
   复验后全部恢复且新 Run 端到端完成。
2. `downgradedConnectionInheritsNoProofFromAnotherIdentity`:同 URL/模型/密钥不同 UUID 的
   连接 B 拿到自己的 VERIFIED 证明后,A 的降级不因 B 恢复;其他组织不能解析;修订号漂移后
   旧 revision 冻结引用与新 Run 准入均失败关闭。

## 行为对比

| 场景 | 修复前 | 修复后 |
|---|---|---|
| STRUCTURED 降级后新 GA Run 准备 | Ready(凭过期 validated 标志) | `Unavailable(GA_MODEL_NOT_CONFIGURED)` |
| 降级后新 Project Agent Run 首次推理 | 取得配置并发起供应商请求 | `MODEL_BINDING_MISMATCH`,零供应商请求 |
| 降级后未绑定直连推理(`CustomInferenceGateway.resolve()`) | 取得配置 | `GA_MODEL_BINDING_MISMATCH` |
| 降级后激活门禁 | 拒绝(既有) | 拒绝(不变,两门禁结论一致) |
| 降级后已冻结 Run | 继续(设计契约) | 继续(不变) |
| 冻结绑定的 revision 漂移/删除/跨组织 | `GA_MODEL_BINDING_MISMATCH`(既有) | 不变 |
| legacy 单例 CUSTOM(`custom-settings:` 身份) | 走 `requireActivatable()` 既有语义 | 不变 |
| TEXT_CHAT 既有规则(任何已验证配置可文本聊天) | 不变 | 不变(STRUCTURED 证明本就是验证成功的必要产物,合法 TEXT_CHAT 连接同样持有该证明) |

## 设计取舍

- **为什么加在 `connectionSettings` 边界而不是清除 `validated_revision`**:清除会破坏冻结
  路径对 `row.validated()` 的既有依赖(冻结 Run 会被复验失败误杀,违反 requireFrozen javadoc
  声明的设计契约);边界门禁使运行时门禁与激活门禁读取同一权威能力行,不新增平行状态源。
- **机器码/wire 契约**:拒绝路径沿用 `connectionSettings` 既有 `GA_MODEL_BINDING_MISMATCH`
  (ISE),与"未验证连接"现状一致;GA 准备阶段既有 catch 把它终态化为
  `GA_MODEL_NOT_CONFIGURED`;未新增或改变任何 HTTP 状态码/机器码。
- **能力语义**:NATIVE_TOOLS=VERIFIED 不能替代 STRUCTURED 证明(有专项断言);org 隔离沿用
  `OrganizationConfigurationScope` 与能力表键控,未新增检查面。

---
status: Historical
authority: Historical Reference Only
---

# MS-CROSS-R1 验收补正 — 历史短 Key 泄漏修复与 Probe 路径证明增强

- 日期: 2026-10-25
- 分支: `dev`(基线 `843936a7ea599663865307e01bd8d65b11cf2ab4`,MS-CROSS 最终提交;本地与 `origin/dev` 同步 0/0)
- 独立复审输入:R1-A(P2)历史极短 Key 经 `maskedSuffix` 泄漏;R1-B(P2)F-1 回归断言 `stream:false` 无旁路区分力
- 真实供应商请求:**0 次**(MS-CROSS 已有 Gemini 4 请求证据;本轮全部用确定性 Fake 与隔离数据库)
- 结论:见 [verification.json](verification.json)

## 1. R1-A:历史极短 Key 不再泄漏

### 根因

MS-CROSS 的 F-2 只修了 `suffix()`(新保存路径),但 legacy `CustomProviderSettingsService.status()` 仍直接信任历史持久化的 `masked_suffix` 列:历史弱脱敏时代 `apiKey="abcd"` 行的 `maskedSuffix` 也是 `"abcd"`,`mask()` 后 UI 得到 `••••abcd` —— 整密钥披露。`save(apiKeyInput=null)` 还会原样继承旧后缀。

### 生产修复(`CustomProviderSettingsService.java`)

1. `status()`:掩码输出改为 `mask(suffix(s.apiKey()))` —— **从实际密钥重新推导,不信任历史持久化后缀**。null/空/≤4 → null;>4 → `••••`+末 4 位。
2. `save()` 密钥解析:`apiKeyInput=null` 路径的 `masked` 改为 `suffix(resolvedKey)` 重算,不再继承 `existing.maskedSuffix()`;写库时持久化弱脱敏值自然纠正。

### 语义保证(测试逐条断言)

| 场景 | 结果 |
| --- | --- |
| 历史 4 字符 Key(`apiKey=abcd, maskedSuffix=abcd`) | `maskedKey=null` |
| 历史 3 字符 Key | `maskedKey=null` |
| null / 空密钥 | `maskedKey=null`,`hasKey` 语义不变 |
| 长密钥 | 仅 `••••`+末 4 位,JSON 序列化不含完整密钥 |
| 不改任何字段保存 | 走内容一致短路,不写库、`configRevision` 不变(3→3) |
| 改 displayName 保存 | legacy 既有语义:内容变化即 revision+1(先于本轮,未改动);写库时 `masked_suffix` 纠正为 null,Key 保留 |

- **无需数据库迁移**:读取边界重推导即时生效;写路径自然纠正持久化值。
- **资格不无故失效**:内容一致短路不写库 → revision 不变 → validated 状态不变。
- UUID 连接侧(`ModelProviderViewService:76`)此前已有 `length()>4` 防护,无需改动。
- 其他 Status DTO 字段(apiFormat/baseUrl/endpointPreview/selectedModel/displayName 等)无凭据派生,无同类问题。

### 旧数据测试(`LegacyShortKeyMaskingTest`,9 用例)

经真实 `CustomProviderSettingsService.status()/save()` 边界 + Mockito 仓储构造旧版行;含 Status JSON 序列化断言(`doesNotContain("abcd")`)。已加入 `auditedOfflineTests` 审计清单。

## 2. R1-B:Probe 传输路径的真正区分证明

### 差异分析

| 维度 | 旧旁路(F-1 修复前) | 生产传输(当前) |
| --- | --- | --- |
| 请求 `stream:false` | ✅(adapter.requestBody 本来就带) | ✅ —— **无区分力** |
| 冻结绑定一致性(GA_MODEL_BINDING_MISMATCH) | ❌ 无 | ✅ |
| 预算/active 围栏 | ❌ 无 | ✅ |
| `GaHttpSafetyDiagnostic` 捕获 | ❌ 无 | ✅ |
| 传输实例来源 | 手写 postJson | `new*Transport` 工厂 |

### 修复:最小传输创建 seam

`CompatibilityProbeService` 新增三个**包私有**工厂方法(`newResponsesTransport` / `newAnthropicTransport` / `newChatCompletionsTransport`),生产实现原样实例化三个真实生产传输;`probeNativeTools` 经工厂调用。不引入第二套 Registry、不替换任何生产安全校验——只是把"new 的位置"变成可观察点。

### 新回归(`ModelProtocolHarnessTest.nativeProbeRoutesThroughTheProductionTransports`)

三协议各自:双轮 probe_echo 闭环(2 次 HTTP)+ `verify(times(2)).new<Protocol>Transport(any())` + `never()` 断言其他两个工厂不被调用;Anthropic 额外断言 `x-api-key` 身份头经生产传输到达。原有回归(工具 ID 保真、错误工具拒绝、Responses function_call_output、Gemini thought_signature、400/429/500 分类、无重试)全部保留。

### 变异验证(差异证明)

1. 备份生产实现到 `/tmp`(不进仓库);
2. 临时恢复 F-1 修复前的旁路分支(直接 `ProviderHttpSupport.postJson + adapter.parse`);
3. `nativeProbeRoutesThroughTheProductionTransports` **红**:`Wanted but not invoked: newChatCompletionsTransport(Wanted 2 times, was 0)`——同时确认旁路下 `stream:false` 断言仍绿(证明旧断言无区分力);
4. 恢复生产实现:`ModelProtocolHarnessTest` 11/0/0 全绿。

## 3. 本轮改动清单

| 文件 | 变更 |
| --- | --- |
| `backend/.../modelsettings/CustomProviderSettingsService.java` | R1-A:status/save 掩码重推导 |
| `backend/.../model/provider/CompatibilityProbeService.java` | R1-B:三个包私有传输工厂方法,probe 经工厂创建生产传输 |
| `backend/build.gradle.kts` | `LegacyShortKeyMaskingTest` 加入 auditedOfflineTests(注释避免右括号,兼容 CI 校验脚本解析) |
| `backend/.../modelsettings/LegacyShortKeyMaskingTest.java` | 新增:9 个旧数据掩码用例 |
| `backend/.../model/provider/ModelProtocolHarnessTest.java` | 新增:三协议生产传输路径断言 |

不改:MS-C1/C2/C3 业务适配、`ga-model-inference.v2`、Python Agent、CapabilityRuntime、账本、Checkpoint、Sidecar、Registry、Flyway、前端。

## 4. 用户 WIP 保留

`ModelSettingsIsolationIntegrationTest.java`、`SHARED_RAG_ACCEPTANCE_PRECHECK.json` 与全部 untracked 材料原样保留,未纳入提交。

## 5. 范围外

无新协议、无 MS-C4、无真实 Provider 调用;历史真实 Gemini 证据(4 请求)验证的是 MS-CROSS F-1 原代码修复路径,本轮新增的测试注入与历史 Key 修复不援引该证据宣称 live 覆盖。

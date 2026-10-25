# ARCH-QUALITY-01 · 实施簇重构方案

status: Historical(本轮快照,2026-10-25)
authority: Historical Reference Only

基线:`a30de0cc85caf46f7bca42742e76b3ee20e103f2`(dev,与 origin/dev 同步)

## 簇 1 · GA Response 契约校验统一

### 旧依赖与旧结构

```text
GaChatCompletionsAdapter.validateResponse   (自带实现, 71-83)
GaAnthropicMessagesAdapter.validateResponse (自带实现, 172-190, 零测试覆盖)
GaResponsesAdapter.validateResponse         (自带实现, 71-89, 零测试覆盖)
GaNativeModelBroker:200 ──全限定 new──> GaChatCompletionsAdapter  [assistant.api → model.provider 跨包]
```

调用点共 6 处,分属两个信任边界:
- Provider 传输层:ChatCompletionsGaTextTransport:103、KiloGaNativeTransport:148、HttpOpenCodeZenTransport:234、AnthropicGaNativeTransport:66、ResponsesGaNativeTransport:62
- Broker 权威复核:GaNativeModelBroker:200

### 新依赖与新结构

```text
com.specagent.model.contract.GaResponseContractValidator  (新增, 纯静态, 唯一实现)
    ↑ 委托                        ↑ 直接调用
三个 Adapter.validateResponse    GaNativeModelBroker:200
(公共 API 不变, 6 个传输层调用点零改动)
```

### 改动文件

| 文件 | 改动 |
|---|---|
| `model/contract/GaResponseContractValidator.java` | 新增:唯一实现(toolChoice 一致性 + 工具名授权 + id 不重放),异常消息与原三份逐字一致 |
| `model/provider/GaChatCompletionsAdapter.java` | validateResponse 方法体改为委托 |
| `model/provider/GaAnthropicMessagesAdapter.java` | 同上 |
| `model/provider/GaResponsesAdapter.java` | 同上 |
| `assistant/api/GaNativeModelBroker.java` | `new GaChatCompletionsAdapter().validateResponse(...)` → `GaResponseContractValidator.validate(...)`,消除 assistant.api → model.provider 唯一跨包引用 |
| `test/.../architecture/ArchitectureTests.java` | 新增守卫 `assistantApiShouldNotDependOnProviderImplementationPackages`(先红:阻断 Broker 改前违规;后绿) |
| `test/.../model/contract/GaResponseContractValidatorTest.java` | 新增 6 个直接单测(接受/toolChoice none/required/保留工具名/历史 id 重放/消息文案),补上 Anthropic/Responses 版本从未有过的拒绝路径覆盖 |

### 行为不变量(等价性论证)

1. 三份旧实现的规则集合与异常消息**逐字相同**(审计逐行比对确认,仅集合构建风格不同);新实现即其中一份的原文搬运,另两份语义恒等。
2. 两个信任边界(传输层 + Broker)各执行一次的布局**不变**——共享的是实现,不是执行次数。
3. 协议专属规则(Responses function_call/mixed-output、Anthropic tool_use/thinking/保真重放、Gemini thought_signature)全部留在各自 `parse`,未搬入通用校验器。
4. 未新增任何校验语义(如 response 内部 id 唯一性检查不存在于旧实现,亦不添加)。
5. 失败关闭行为不变:任一违规仍抛 `IllegalArgumentException` → `GA_MODEL_PROTOCOL_ERROR`,可执行 ToolCall 不释放。

## 簇 2 · Model Settings 冗余判断与脱敏归一

### 旧结构

- `ModelProvidersService.update`:`changed` 布尔式含冗余项(`identityChanged` 已含 selectedModel 比较)。
- 脱敏原语四份:`suffix` ×4(两组语义:null vs "")、`hasKey` ×2、`equalsNullable` ×2 逐字重复。

### 新结构

- `com.specagent.modelsettings.ApiKeyMasking`(新增,包私有纯静态):`suffix`(短密钥→null,MS-CROSS-R1 语义)/`hasKey`/`equalsNullable` 唯一实现。
- 四个服务全部委托;`ModelProvidersService.update` 删除冗余布尔项。

### 改动文件

| 文件 | 改动 |
|---|---|
| `modelsettings/ApiKeyMasking.java` | 新增共享原语 |
| `modelsettings/ModelProvidersService.java` | 删除冗余比较;私有 `equalsNullable`/`suffix` 删除,改委托 |
| `modelsettings/CustomProviderSettingsService.java` | 私有 `equalsNullable` 删除;`suffix` 改为委托(保留包私有签名,测试引用) |
| `modelsettings/KiloSettingsService.java` | 私有 `suffix`(短密钥→`""`)删除,唯一调用点改 `ApiKeyMasking.suffix`;`hasKey` 改委托;**mask 不动** |
| `modelsettings/OpenCodeSettingsService.java` | 同 Kilo |
| `test/.../modelsettings/ApiKeyMaskingTest.java` | 新增 4 个边界单测(null/空白/≤4/>4) |

### 行为不变量(等价性论证)

1. **update 冗余项删除**:`A||A||B||C ≡ A||B||C` 纯布尔化简;revision 递增条件(identityChanged)、validatedRevision 保留条件(!identityChanged)、无变化短路(!changed)全部不动。既有测试 `ModelConnectionsOfflineTest`(renamePreservesQualification/modelChangeInvalidatesQualification/originChangeCannotReuseCredential 等 10 例)锁定。
2. **Kilo/OpenCode 短密钥 `""`→`null`**:两处存储列(kilo_settings.masked_suffix / opencode_settings.masked_suffix)的**全部读取点**均经各自 mask,Kilo.mask 与 OpenCodeSettingsStatusMask.mask 都对 blank/null 归一(前者→null,后者→"••••"),外部输出逐字节不变;历史已存 `""` 行读取路径相同,不受影响。`KiloSettingsServiceTest` 12 例(含密钥掩码断言)与 `LegacyShortKeyMaskingTest` 9 例(MS-CROSS-R1 全套)锁定。
3. **mask 不统一**:OpenCode 无密钥显示 "••••"(UI 兜底)与 Custom/Kilo 返回 null 是刻意差异,保留各自实现。
4. **安全语义不放宽**:短密钥原文绝不出现在后缀(统一 null),来源守卫、revision 递增、激活门禁、Legacy 单例、跨连接 Credential Guard、短 Key 防泄漏全部不动。

## 范围控制

- 本轮生产代码仅上述两簇(8 改 2 增),测试 1 改 2 增。
- Python / Frontend / Graph Runtime / Capability 零改动。
- 用户 WIP(`ModelSettingsIsolationIntegrationTest.java` 行尾态、`SHARED_RAG_ACCEPTANCE_PRECHECK.json` 时间戳)未触碰。

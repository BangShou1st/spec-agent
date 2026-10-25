---
status: Canonical
authority: Current Authority
---

# MS-CROSS 跨阶段审查发现清单(2026-10-24)

审查基线:MS-C1(`dc36d40`→`9e31553`)+ MS-C2(`efba1cb`→`e0185a9`)+ MS-C3(`00db644`→`5e49768`)组合,`dev` 分支 HEAD `5e49768`,与 `origin/dev` 同步(0/0)。

## 已确认并修复的缺陷

### F-1(P1)Chat Completions native probe 旁路实现,与生产传输不一致

- 位置:`backend/src/main/java/com/specagent/model/provider/CompatibilityProbeService.java` `probeNativeTools()`
- 现象:RESPONSES 与 ANTHROPIC_MESSAGES 分支已改走生产传输(`ResponsesGaNativeTransport`/`AnthropicGaNativeTransport`,见分支内注释"绝不使用旁路实现"),但 CHAT_COMPLETIONS 分支仍是手写旁路:`new GaChatCompletionsAdapter()` + 直接 `ProviderHttpSupport.postJson`。
- 影响:(1) probe 请求缺 `stream:false`(生产传输显式设置);(2) 绕过生产传输的冻结绑定一致性检查、`requireTextOnly` 资格门禁与 `GaHttpSafetyDiagnostic` 诊断捕获;(3) 违反"资格验证必须与生产同路径"的架构约束(任务书 §四D),probe 通过不能证明生产传输可用。
- 修复:三分支统一为 `switch (format)` 构造对应生产原生传输(`ChatCompletionsGaTextTransport`/`ResponsesGaNativeTransport`/`AnthropicGaNativeTransport`),同一 `RuntimeCustomSettings` 快照 + `GaModelTarget` 冻结绑定 + `SETTINGS_TIMEOUT` 预算,删除旁路代码。
- 修复前复现:`ModelProtocolHarnessTest.nativeRoundTripPreservesCallIdentityAndOpaqueSignature`(修复前请求无 `stream` 字段——断言 `stream:false` 会失败)。
- 修复后回归:同测试新增 `assertThat(fake.requests).allSatisfy(r->assertThat(r.path("stream").asBoolean()).isFalse())`;`testOffline` 全绿;真实验证见 verification.json(Gemini 免费层,4 次请求)。

### F-2(P2)极短密钥(≤4 字符)在 legacy Custom 设置中原样暴露到 UI

- 位置:`backend/src/main/java/com/specagent/modelsettings/CustomProviderSettingsService.java` `suffix()`
- 现象:legacy 单例的 `suffix()` 对长度 ≤4 的密钥原样返回,`maskedKey` 变为 `••••<全文>`,密钥明文进 UI;而 `ModelProvidersService.suffix()`(UUID 连接)对 ≤4 返回 null。两处行为不一致且前者泄漏。
- 修复:对齐为 `key.length() <= 4 → null`(密钥本身绝不外泄)。
- 回归:`testOffline` 全绿(`ModelConnectionsOfflineTest`/`CustomProviderSettingsService` 相关用例无 maskedKey 短密钥依赖)。

### F-4(P2)Anthropic Messages 适配器把第 2+ 条 system 消息静默降级为 user 消息

- 位置:`backend/src/main/java/com/specagent/model/provider/AnthropicMessagesProtocolAdapter.java` `buildRequestBody()`
- 现象:旧逻辑 `if ("system".equals(role) && system == null)` 只取第一条 system,后续 system 掉入 role 映射分支变成 user 消息(协议失真:指令文本被伪装成用户输入)。
- 影响:当前 Python brain 各引擎只发一条 system(decision/state_update/artifact 均单条),生产不触发;但 broker 契约允许多条 system,属于可达的防御缺口。
- 修复:收集全部 system 到 `systemParts`,`\n\n` 连接进顶层 `system`(与 GA 侧 `GaAnthropicMessagesAdapter` 策略一致)。
- 修复后回归:新增 `AnthropicMessagesProtocolAdapterTest.multipleSystemMessagesMergeIntoTopLevelSystem`;`testOffline` 全绿。

## 审查确认无问题的要点(抽样)

| 审查项 | 结论 | 证据 |
| --- | --- | --- |
| 三协议终态一致性 | 通用契约 `GaModelContract.Response` 构造器统一强制 `finishReason ∈ {stop, tool_calls}`;Chat Completions 的 length/content_filter、Responses 的 incomplete、Anthropic 的 max_tokens/refusal 均在此失败关闭 | `GaModelContract.java:292-293` |
| 工具调用 ID 配对 | Responses `function_call.call_id`↔`function_call_output.call_id`;Anthropic `tool_use.id` 原样保留↔`tool_result.tool_use_id`;Chat Completions `tool_calls[].id`↔`tool_call_id`;历史重复 ID 由 `validateResponse` priorIds 拒绝 | 三适配器 + `GaAnthropicMessagesAdapter.validateResponse` |
| 错误回退 | `RoutingGaNativeInferenceGateway` 按冻结 API Format switch 分发,未注册形态抛 `UNSUPPORTED_AGENT_MODEL`;无任何 fallback | `RoutingGaNativeInferenceGateway.java:83-99` |
| MS-C1 授权链 | `GaModelContinuationStore.resolve` JOIN `ga_executions`(epoch+lease)+ `global_assistant_runs`(RUNNING 且未请求取消)+ org + thread/provider/model/revision 四重一致 + source MODEL call SUCCEEDED;密文 AES-256-GCM,AAD 绑定 `(org, table, row)` | `GaModelContinuationStore.java:125-156` |
| sidecar 原子性 | `completeModelWithContinuation(scope, callId, wire, persist-callback)`:加密/落库失败则 MODEL 永不 SUCCEEDED、无 ToolCall 释放 | `GaNativeModelBroker.java:219-225` |
| 幂等账本 | MODEL/TOOL reserve+requestHash;SUCCEEDED 重放零流量;语义幂等键只读按 callId、副作用按 argumentsHash(不同 toolCallId 不能重复本地变更);并行批次仅允许只读+NONE 副作用工具 | `GaNativeModelBroker.java:181-205`、`GaCapabilityBroker.java:63-97` |
| 能力资格身份 | `model_connection_capabilities` 主键 `(org, provider_id, config_revision, capability)`;编辑 identity 字段递增 revision 使旧资格自然失效;激活门禁除 validated 标记外还要求当前修订 STRUCTURED=VERIFIED(防御纵深) | `V105__model_connection_capabilities.sql`、`ModelProvidersService.requireActivatable` |
| 旧连接迁移 | `LegacyModelConnectionMigrator` 导入行 `validated_revision=null`(绝不继承资格),幂等去重表,确定性 UUID | `LegacyModelConnectionMigrator.java:36-95` |
| 组织隔离 | 连接/能力/continuation 全部按 org 过滤;凭据密文 AAD 含 org;跨 org 无法读写 | 各 repository + crypto |
| SSRF | scheme 白名单、loopback-only http、危险字面量与 DNS 解析双向拒绝、URL 禁带凭据/query/fragment | `ProviderUrlSecurity.java` |
| v2 契约入口 | `GaNativeModelBroker.requireRpcWireV2` 在 HTTP 入口强制 `ga-model-inference.v2`;Project 侧 broker 恒定时间 token + RPC grants + 只允许 system/user | `GaNativeModelBroker.java:287-289`、`InternalModelInferenceController.java` |
| 凭据卫生 | 无重试(grep 全传输层零 retry);错误消息 `Bearer/sk-` 脱敏 + 300 字符截断;日志只记异常类型 | `ProviderHttpSupport.java:322-324` |
| Flyway | V1→V110 顺序唯一(V17 历史性从未存在,非删除);V105 能力表 ON DELETE CASCADE;V107 激活引用 FK 兜底应用层删除检查 | `db/migration/` |

## 记录但不修复的事项(P2/P3,含理由)

1. **三份 `validateResponse` 副本**:`GaChatCompletionsAdapter`/`GaResponsesAdapter`/`GaAnthropicMessagesAdapter` 的 `validateResponse` 逻辑逐字相同(通用契约校验),且 `GaNativeModelBroker` 对所有协议统一用 Chat Completions 版本。合并为一个通用校验器是纯重构,无行为收益,留待后续(避免本轮无意义 churn)。
2. **Chat Completions `extra_content` 非官方字段**:为承载 Gemini `thought_signature` 续接而有意发送(`GaChatCompletionsAdapter.java:40-42`),是 MS-C1 已验收设计;Responses/Anthropic 严格无私有字段。已在兼容性矩阵标注。
3. **`ModelProvidersService.update()` 冗余比较**:`changed` 重复包含 `selectedModel` 比较(与 `identityChanged` 重叠),无行为影响。
4. **Anthropic SSE 增量、extended thinking 续接、Server Tools**:MS-C3 明确范围外,`UNSUPPORTED` 状态维护在矩阵。

## 测试架构边界评估

- 通用逻辑(契约构造器、账本幂等、资格身份、URL/加密)各只有一份测试,协议专项测试不再重复断言这些。
- 协议差异(字段形状、混合输出策略、终态白名单、历史映射)在各自适配器/传输测试中独立验证,由 `ModelProtocolHarnessTest` 提供跨协议共享 HTTP fixture。
- Fake Provider 是入站协议断言器(错误形状 400 + 失败原因记录),不是宽松桩;MS-C3 报告记载 Fake 缺陷被适配器严格解析当场拒绝的实例。
- 发现的漏洞已由本轮补齐:probe 传输一致性此前无断言(F-1 修复后 `stream:false` 断言 + 真实 4 请求验证)。

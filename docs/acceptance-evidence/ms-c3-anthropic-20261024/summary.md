---
status: Historical
authority: Historical Reference Only
---

# MS-C3 验收证据 — Custom Anthropic Messages 结构化输出与原生工具协议

- 日期: 2026-10-24
- 分支: `dev`(基线 `e0185a9e57d669a091aea869db4c23d6b7dd1617`,MS-C2 已验收提交)
- 结论: `MS_C3_PASS`(本地全部套件通过;远端 CI 10/10 绿,见文末)
- 实时网络: `LIVE_NOT_RUN` —— 全部协议验证基于严格 Fake Anthropic Provider,不宣称已验证任何真实 Anthropic 或 Anthropic-compatible API;仅宣称有测试证据覆盖的协议形态。
- 隔离数据库: `spec-agent-model-v2-acceptance` 容器内专用库 `spec_agent_p3b_live_ms_c3`(守卫校验的服务器报告端点 `172.17.0.2/32:5432`)。未连接任何业务数据库。

## 1. Project Agent:JSON_OBJECT 安全桥接

宿主内部 JSON Envelope(`anthropic-json-envelope.v1`)承载 `ModelOutputContract.JsonObject`:

- 请求:官方 GA 字段 `output_config.format` = `{"type":"json_schema","schema":{"type":"object","properties":{"json":{"type":"string"}},"required":["json"],"additionalProperties":false}}`(不使用已弃用的 `output_format`,不伪造 `response_format`,不发送 beta 头),并在顶层 system 附加序列化指令。
- 响应:仅在合法完成终态(`end_turn`/`stop_sequence`)解包;外层必须恰为单一 `json` 字符串字段;内层必须是语法合法的 JSON 对象;重复键、尾随 token、额外字段、孤立代理对(非法 Unicode)、超长(内层 >131072 字符)一律失败关闭;仅内层对象的规范化序列化作为推理内容返回。外层 Envelope 绝不作为决策输出。
- 流式:JSON 桥接显式非流式(流式入口对 JsonObject 契约失败关闭),绝不把未验证的 Envelope 聚合文本当决策返回。
- 语义边界:桥接仅保证外层类型;内层 JSON 的业务合法性仍由 Python STATE_UPDATE/DECISION 契约、Pydantic 严格校验、Java 审核、ActionEligibility 与业务不变量权威验证——模型通过格式校验不代表业务 Action 获批。
- 证据:`AnthropicMessagesProtocolAdapterTest`(14 测试,含 10 类非法 envelope 负例)、`AnthropicProjectJsonBridgeEndToEndTest`(4 数据库端到端:DECISION 与 STATE_UPDATE 经真实 `InternalModelInferenceController` → `RoutingModelInferenceGateway`(冻结绑定)→ `CustomInferenceGateway` → 适配器 → Fake Anthropic 完整往返,返回内容与 Python fake model 契约 fixture 同形;非法 envelope/raw JSON 直出/refusal 均 502 + `MODEL_INFERENCE_FAILED` 事件;运行中修改 URL/Key/Revision 后同 Run 后续调用失败关闭且不重定向)。

## 2. Global Assistant:原生工具双轮协议

新增 `AnthropicGaNativeTransport` + `GaAnthropicMessagesAdapter`(冻结绑定、身份头、SSRF、无重试/无降级语义与 Responses 传输对齐):

- 请求:原生 `model`/顶层 `system`/`messages`/`tools[{name,description,input_schema}]`/`max_tokens`;`tool_choice` 映射 `none→{"type":"none"}`、`auto→{"type":"auto","disable_parallel_tool_use":true}`、`required→{"type":"any","disable_parallel_tool_use":true}`(强制工具并非所有模型支持;不支持时按失败处理,绝不暗中降级为 auto)。刻意不发送 `strict:true`:冻结 Catalog schema 不保证满足 strict 限制,参数合法性仍由 Java CapabilityRuntime 权威校验。
- `tool_use` 解析:stop_reason/内容一致性、ID 非空唯一(≤128)、名称在冻结 Catalog、输入为有界对象;`tool_use.id` 原样保留为 GA ToolCall id(绝不生成新 ID);MODEL 账本成功提交后才释放可执行调用。
- 历史:assistant = text block(可选,在前)+ tool_use block(在后);工具结果合并为紧随其后的同一条 user 消息的多个 `tool_result` block(官方要求,顺序保留,`tool_use_id` 精确配对);绝不伪装成普通用户文本、绝不使用 Responses `function_call_output`、绝不转发 Chat Completions `role=tool`。
- 证据:`GaAnthropicNativeToolEndToEndTest`(5 数据库端到端:双轮闭环经 Java Broker → MODEL 账本 → CapabilityRuntime → TOOL 账本 → 第二次 HTTP → `end_turn` 终态;两次模型请求、`toolu_01` ID 精确关联、账本 SUCCEEDED、重复 callId 幂等重放零流量;v2 线协议无任何 Anthropic 专属字段)。传输级:`AnthropicGaNativeTransportTest`(11,含双只读工具合并、绑定漂移 fail-closed、legacy 无 UUID 拒绝工具、401/429/500/malformed 类型化错误)。

## 3. 正文与工具混合 + thinking 私有状态(提前解决)

- 合法形状:**text* → tool_use+**(可见正文进 `content`,工具进 `toolCalls`,`finishReason=tool_calls`,下一轮精确恢复 `text block → tool_use → user tool_result`)。
- 第一轮即拒绝(发生在 Java 释放可执行 ToolCall 之前,无第二次 Provider 请求,MODEL 未 SUCCEEDED,无 TOOL 账本):tool_use 之后出现 text;thinking/redacted_thinking 与可执行 tool_use 并存;未知 content block;stop_reason 与内容不一致;`max_tokens`/`pause_turn`/`refusal`/null 终态;截断的部分 ToolUse。
- thinking 私有内容绝不进入 GA 响应、MODEL 公开结果、普通 Checkpoint 或 Python;不新增 Anthropic Continuation Sidecar 类型。
- 证据:`GaAnthropicNativeToolEndToEndTest.mixedTextThenToolUseContinuesFullyAcrossBothRounds` / `toolUseFollowedByTextIsRejectedBeforeAnyToolExecutionOrLedgerSuccess` / `thinkingPlusToolUseIsRejectedBeforeRelease`;适配器级 11+14 项。

## 4. 资格验证与冻结路由

- `ModelConnectionCapabilities`:Anthropic 连接的 CHAT(文本)、STRUCTURED(真实 `output_config.format` + Envelope + `CompatibilityDecisionSemantics` 权威 FINAL 决策校验)、NATIVE_TOOLS(生产 `AnthropicGaNativeTransport` 双轮 `probe_echo`,恰好两次 Provider HTTP、无重试、不触及业务工具)分别按 `(org, UUID, configRevision, capability)` 记录;网络/401/429/超时/坏响应/未知 400 → TEMPORARILY_UNAVAILABLE;CHAT 成功不授予其他资格;激活门禁仍要求当前修订 STRUCTURED=VERIFIED。
- 路由:`GaExecutionModelTargetResolver` — UUID 连接 + `nativeToolsVerified` → NATIVE_TOOLS,否则 TEXT_CHAT;无 Native 资格不发送工具;legacy Anthropic 单例可 TEXT_CHAT 但绝不继承 Native 资格;切换 UUID/模型/修订号不继承资格;Frozen Revision 漂移失败关闭;`RoutingGaNativeInferenceGateway` 按冻结 API Format 分发,Anthropic 错误绝不回退到 Responses/Chat Completions。
- 证据:`ModelConnectionsIntegrationTest.anthropicConnectionQualifiesChatStructuredAndNativeToolsThroughTheEnvelope`(数据库:创建 → validate → 三能力 VERIFIED → activate → GA NATIVE_TOOLS 解析 → 原生传输身份头 `x-api-key` + `anthropic-version: 2023-06-01`);`GaExecutionModelTargetResolverTest`(legacy TEXT_CHAT / UUID NATIVE↔TEXT / 未知格式 UNSUPPORTED);`RoutingGaNativeInferenceGatewayTest`(Anthropic 传输分发 + 无回退)。

## 5. 严格 Fake Anthropic Provider

`FakeAnthropicMessagesProvider` 是真正的入站协议断言服务,对以下请求返回 HTTP 400 并记录原因:Chat Completions `messages[].tool_calls`/`tool_call_id`/`response_format`、Responses `input`/`instructions`/`function_call_output`/`previous_response_id`、错误/孤儿/重复/乱序/拆分的 `tool_result`、`tool_result` 不在 user content block 前部、非法 `tools.input_schema`、Chat Completions 工具形状(`parameters`/`function` 包装)、字符串 `tool_choice`、缺失 `max_tokens`、已弃用 `output_format`、伪造 `anthropic-beta` 头、错误身份头(`Authorization`/版本)。可控响应模式覆盖:纯文本、Project envelope(及缺失字段/额外字段/内层非对象/坏 JSON/重复键/非法 Unicode/超长/raw 直出/空文本九种失败形态)、单工具、双只读工具、text→tool_use、tool_use→text、thinking+tool_use、重复 ID、缺 ID、stop_reason 不一致、null/refusal/pause_turn/max_tokens、429/401/500、malformed、截断。Fake 的失败是协议正确性证据:本轮开发中 Fake 的 envelope 转义缺陷(字符串值内字面换行)正是被适配器的严格解析当场拒绝后修复的。

## 6. Checkpoint 与既有机制不回归

- `ga-model-inference.v2` 与 `ga-json.v2` 契约零改动;Python 无任何 Anthropic 专属字段(`agent-brain` 源码无 anthropic 引用);历史工具调用经普通状态恢复;Gemini `continuationRef` 用例(`GaModelContinuationEndToEndTest`、`GaModelContinuationCrossLanguageWireIntegrationTest`)随全量通过;MS-C1 Sidecar 与 MS-C2 Responses/Gemini/Chat Completions/Zen/Kilo 套件随全量通过。

## 7. 测试数量(本地)

| 套件 | 完成 | 失败 | 跳过 |
| --- | --- | --- | --- |
| Java `testOffline`(含新增 3 个审计类) | 53 类全部通过 | 0 | 0 |
| Java `test` 全量 | 2654 | 0 | 41 |
| Java `testDatabaseIntegration` 全量 | 1005 | 0 | 41 |
| Python brain `pytest` | 344 | 0 | 0 |
| Python tools 契约 `pytest` | 239 | 0 | 0 |
| `testCrossLanguage`(broker-mode brain :8100) | BUILD SUCCESSFUL | 0 | 0 |
| Frontend typecheck / unit / build | 987 通过 | 0 | 1(既有 vitest skip) |

跳过项说明:Java 全量 41 个跳过为既有 live-provider/环境门禁用例(`@EnabledIfEnvironmentVariable`/`Assumptions`,无 MS-C3 新增跳过);前端 1 个跳过为既有用例。

## 8. Git 与远端 CI

- 提交:见 manifest(PENDING 最终 SHA)
- Actions: PENDING

## 9. 范围外(本轮明确不做)

Anthropic SSE 增量、extended thinking 续接、Server Tools、Computer Use、Prompt Caching 新系统、新 Agent Loop、新 Python Anthropic SDK、新 Provider Registry、模型设置 UI 重构、Responses/Gemini Sidecar 重构、其他 Provider 协议升级;无新增 Flyway 迁移。

## 10. 用户 WIP 保留

用户既有工作区修改(`ModelSettingsIsolationIntegrationTest.java`、`docs/v2/evidence/SHARED_RAG_ACCEPTANCE_PRECHECK.json` 及全部 untracked 验收证据目录)原样保留,未纳入本轮提交。

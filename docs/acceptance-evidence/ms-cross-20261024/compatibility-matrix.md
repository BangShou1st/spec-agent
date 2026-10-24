---
status: Canonical
authority: Current Authority
---

# Model Service V2 跨协议兼容性验收矩阵

- 日期: 2026-10-24
- 范围: MS-C1(continuation sidecar)+ MS-C2(Responses 原生工具)+ MS-C3(Anthropic Messages)组合后的跨协议统一矩阵
- 基线提交: `5e4976883097687080eb14b81feed49ca6d79236`(本轮修复见 manifest.json)

## 状态定义(不可互相替代)

| 状态 | 含义 |
| --- | --- |
| `IMPLEMENTED` | 存在生产实现,但无对应测试证据 |
| `OFFLINE_VERIFIED` | 通过离线/合成(Fake Provider、契约 fixture、隔离数据库)测试验证 |
| `LIVE_VERIFIED` | 精确连接、模型、修订号与能力已通过真实供应商验证 |
| `UNSUPPORTED` | 明确不支持并失败关闭 |
| `NOT_RUN` | 尚未验证 |

## 矩阵

| 维度 | Custom / CHAT_COMPLETIONS | Custom / RESPONSES | Custom / ANTHROPIC_MESSAGES | Custom / Gemini(CHAT_COMPLETIONS 兼容) | OpenCode Zen(内置) | Kilo(内置) |
| --- | --- | --- | --- | --- | --- | --- |
| **实际协议与路由** | OpenAI Chat Completions;`RoutingGaNativeInferenceGateway → ChatCompletionsGaTextTransport`;Project 侧 `RoutingModelInferenceGateway → CustomInferenceGateway` | OpenAI Responses;`→ ResponsesGaNativeTransport` | Anthropic Messages;`→ AnthropicGaNativeTransport` | 同 CHAT_COMPLETIONS 列 + `google.thought_signature` 续接 | Zen 专有(Chat Completions 形状 + 会话亲和 `OpenCodeZenSessionIds`);`HttpOpenCodeZenTransport` | Chat Completions 形状;`KiloGaNativeTransport` |
| **CHAT** | `OFFLINE_VERIFIED`(probeChat + 协议单测) | `OFFLINE_VERIFIED` | `OFFLINE_VERIFIED` | `LIVE_VERIFIED`(2026-10-23,UUID `a95bc5e8`) | `OFFLINE_VERIFIED`(历史生产默认) | `OFFLINE_VERIFIED` |
| **STRUCTURED** | `OFFLINE_VERIFIED`(FINAL 决策语义) | `OFFLINE_VERIFIED` | `OFFLINE_VERIFIED`(宿主 JSON Envelope `anthropic-json-envelope.v1`,官方 `output_config.format`) | `LIVE_VERIFIED`(同上) | `OFFLINE_VERIFIED` | `OFFLINE_VERIFIED` |
| **NATIVE_TOOLS** | `OFFLINE_VERIFIED`(本轮修复后 probe 走生产传输) | `OFFLINE_VERIFIED`(function_call/function_call_output 历史映射) | `OFFLINE_VERIFIED`(tool_use/tool_result + ID 原样保留) | `LIVE_VERIFIED`(probe_echo 双轮) | `OFFLINE_VERIFIED`(元数据验证的 Chat 模型恒为 NATIVE_TOOLS 模式) | `OFFLINE_VERIFIED` |
| **混合正文+工具** | 允许(官方形状,content+tool_calls 并存) | `UNSUPPORTED`(混合即拒绝,e0185a9) | 仅 text→tool_use 顺序;tool_use 后 text、thinking+tool_use 拒绝 | 同 CHAT_COMPLETIONS | 同 Chat Completions 语义 | 同 Chat Completions 语义 |
| **Continuation** | 不需要(无供应商私有状态);Gemini 签名经 `extra_content` 往返 | 不需要(`store:false` 全量重放,无 `previous_response_id`) | 不引入 sidecar(thinking 与工具并存即拒绝,绝不外泄) | `LIVE_VERIFIED`(MS-C1 sidecar 加密保存/恢复,`gacr-` 引用) | 不需要 | 不需要 |
| **终态策略** | 通用契约强制 `finishReason ∈ {stop, tool_calls}`,length/refusal 失败关闭 | `status=completed` + `incomplete_details` 缺失,否则拒绝 | `stop_reason ∈ {end_turn, stop_sequence, tool_use}`,max_tokens/pause_turn/refusal/null 拒绝 | 同 CHAT_COMPLETIONS | 同 Chat Completions 语义 | 同 Chat Completions 语义 |
| **错误回退** | 无:按冻结 API Format 分发,`UNSUPPORTED_AGENT_MODEL` 失败关闭 | 无:RESPONSES 绝不回退 Chat Completions | 无:ANTHROPIC 绝不回退其他协议 | 无 | 无 | 无 |
| **Security** | SSRF 校验、凭据 AES-256-GCM(org+table+row AAD)、密钥不进日志/异常/DTO | 同左 | 同左 + 身份头 `x-api-key`+`anthropic-version`(无 beta 头) | 同左 | 同左 + 可配置 User-Agent | 同左(免费模型匿名执行) |
| **Idempotency** | MODEL/TOOL 账本 reserve+requestHash;SUCCEEDED 重放零流量;语义幂等键(只读按 callId,副作用按 argsHash) | 同左 | 同左 | 同左 + continuation 唯一约束 `(source_run_id, source_call_id)` | 同左 | 同左 |
| **Project Agent 生产路径** | `InternalModelInferenceController`(恒定时间 token、RPC grants)→ `RoutingModelInferenceGateway`(Run 冻结指纹)→ `CustomInferenceGateway` | 同左 | 同左 + Envelope 桥接 | 同左 | 同左 → `OpenCodeModelInferenceGateway` | 同左 → `KiloInferenceGateway` |
| **Global Assistant 生产路径** | `GaNativeModelBroker`(v2 强制、冻结绑定、账本、sidecar 原子提交)→ 路由网关 | 同左 | 同左 | 同左 + continuationRef 注入 | 同左 | 同左 |
| **资格与激活门禁** | 独立三能力 (org,UUID,revision);激活要求 STRUCTURED=VERIFIED;编辑递增 revision 使旧资格自然失效;迁移不继承 | 同左 | 同左 | 同左 | 元数据验证;无 per-revision 能力记录 | `kiloQualified` 登记;未登记仍可 TEXT_CHAT |
| **Offline Evidence** | `ModelProtocolHarnessTest`、`ChatCompletionsProtocolAdapterTest`、`ChatCompletionsGaTextTransportTest`、`ModelConnectionsIntegrationTest`、`ModelConnectionsOfflineTest` | `ResponsesProtocolAdapterTest`、`ResponsesGaNativeTransportTest`、`GaResponsesNativeToolEndToEndTest`、`FakeResponsesProvider` | `AnthropicMessagesProtocolAdapterTest`、`AnthropicGaNativeTransportTest`、`GaAnthropicNativeToolEndToEndTest`、`AnthropicProjectJsonBridgeEndToEndTest`、`FakeAnthropicMessagesProvider` | `GaGeminiProtocolOfflineTest`、`GaModelContinuation*Test`(3 个) | `HttpOpenCodeZenTransport*Test`(5 个)、`OpenCodeModelProtocolTest` | `KiloGaNativeTransportTest`、`KiloCredentialBoundaryTest`、`KiloFrozenModelBindingTest` |
| **Live Evidence** | 无独立真实证据(官方 OpenAI 端点未验证) | `LIVE_NOT_RUN` | `LIVE_NOT_RUN` → 本轮补充(见 verification.json) | `model-service-v2-live-20261023`(12 次请求,三能力+双 Agent 链路) | V2 架构下无专门 live 复验证据(`NOT_RUN`) | `NOT_RUN` |
| **Unsupported** | — | 混合 text+function_call;SSE 增量原生流 | SSE 增量(非流式 JSON 桥接)、extended thinking 续接、strict:true、Server Tools、beta 头 | — | — | — |

## 跨协议共享层(只验证一次的通用逻辑)

以下能力与协议无关,由通用层承载并只维护一份测试,协议专项测试不重复断言:

1. **通用响应契约**:`GaModelContract.Response` 紧凑构造器统一强制 finishReason 白名单、调用数 ≤5、ID 唯一、continuationRef 格式(`GaNativeModelContractTest`)。
2. **模型调用账本与幂等**:`GaNativeModelBroker` 的 reserve/replay/sidecar 原子提交(`GaAnthropicNativeToolEndToEndTest` 等端到端共享该机制)。
3. **能力资格记录与失效**:`ModelConnectionCapabilities` + `ModelProvidersService.requireActivatable`(STRUCTURED=VERIFIED 门禁,防御纵深注释见 V105/V109)。
4. **URL 安全与凭据加密**:`ProviderUrlSecurity`、`ModelCredentialCrypto`(单测覆盖,不在协议专项重复)。
5. **Probe 生产同路径**:`CompatibilityProbeService.probeNativeTools` 三协议统一走生产原生传输(本轮修复对齐)。

## 新增 Custom 连接的验收路径

用户接入一个遵循既有三种协议的新 Custom 连接时:填写 API Format、Base URL、API Key、Model ID → validate(三能力真实探测,probe 走生产传输)→ activate(STRUCTURED=VERIFIED 门禁)。**不需要任何模型专用运行时逻辑或代码改动**;协议差异全部由模型服务层的适配器与传输承载。仅当引入第四种协议时才需要新的 ProtocolAdapter + GaTransport + 资格探测分支。

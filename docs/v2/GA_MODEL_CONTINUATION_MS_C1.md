---
status: Canonical
authority: Current Authority
date: 2026-10-24
---

# GA Provider Continuation Boundary · MS-C1

## 目标与边界

供应商私有续接状态的唯一权威位置是 Java Model Gateway。Python/LangChain 只保存
和回传不透明 `continuationRef`；SafeCheckpointCodec 只持久化该引用，不再持久化
raw `extra_content` / `thought_signature`。

本设计不新增供应商、不改 Provider Registry、不改变 Zen/Kilo/Custom 冻结身份和
能力验证语义。

## 数据流

改造后：

```text
Provider(extra_content)
→ Java adapter parse → GaModelContract.ToolCall.extraContent (Java 内部)
→ GaModelContinuationStore.extract (仅 google.thought_signature, ≤1024 字符)
→ AES-GCM encryptScoped(AAD=org+table+run:call) → ga_model_continuations
→ 同事务 completeModelWithContinuation(ga_execution_calls)
→ ga-model-inference.v2 Response.continuationRef → Python AIMessage.additional_kwargs.model_continuation_ref
→ Checkpoint ga-json.v2 (仅引用)
→ 下一轮请求 assistant Message.continuationRef
→ Java resolve(Run/Thread/org/provider/model/revision/toolIds/ledger)
→ 协议适配器重新输出 extra_content → Provider
```

## 存储

`V110__ga_model_continuation.sql` 新增 `ga_model_continuations`：

- `id UUID PK`：随机引用主体（行线为 `gacr-<id>`）；
- `org_id/thread_id/source_run_id`：组织、Thread、来源 Run，Thread 删除级联；
- `source_call_id`：与来源 Run 组成唯一约束，必须对应 `ga_execution_calls`
  中 `kind=MODEL, status=SUCCEEDED` 的调用；
- `provider/model/settings_revision`：冻结模型身份，读取时与当前活动绑定比对；
- `tool_call_ids`：JSON 数组，读取时与请求 ToolCall ID 精确匹配；
- `payload`：AES-GCM 组织作用域密文；明文限 4096 字节，仅允许
  `{google:{thought_signature:string}}` 形状；
- `payload_count/payload_length/state_version`：审计与大小约束。

## 授权与生命周期

引用不是授权凭据。解析时依次校验：当前 Run 仍 RUNNING、未取消、epoch/lease
匹配、组织一致、Thread 一致、provider/model/settingsRevision 与活动绑定一致、
来源 MODEL 调用已 SUCCEEDED、ToolCall ID 完全一致、密文 AAD 匹配。任何一项
失败均失败关闭（`GA_CONTINUATION_NOT_AUTHORIZED` /
`GA_CONTINUATION_BINDING_MISMATCH`），不自动忽略引用、不回退无签名请求。

同一 Thread 后续 Run 在完全相同 provider/model/revision 下可复用历史引用；跨
Thread、跨组织、跨模型、跨连接失败关闭。

每条记录 ≤8KB 密文存储；每个 MODEL 调用最多 1 条；Thread 删除经 FK 级联清理。

## 历史兼容

- `ga-json.v1` Checkpoint 保留只读兼容；Python `_legacy_message_metadata` 严格
  校验旧 `tool_call_extra_content` 形状；
- 读取旧 checkpoint 时，`HostCheckpointSaver` 调用 Java
  `MIGRATE_CONTINUATION`：Java 用同 Thread 已成功 MODEL 账本（含 D4-R3 v1
  result）核验 ToolCall ID 与签名内容后写入 sidecar 并返回真实 `gacr-` 引用；
- 迁移幂等（unique(run,call)），不改写历史 checkpoint 行、pending writes、head
  CAS 或父子链；伪造载荷失败关闭；
- 新写入一律 `ga-json.v2`，仅允许 `model_continuation_ref`。

## 已知限制

- Responses / Anthropic 协议仍未实现续接，沿用现有 UNSUPPORTED 失败关闭；
- 历史 `ga_execution_calls.result`（v1 明文 extraContent）按历史证据受控保留，
  不改写；后续可做独立数据维护清理；
- 本轮未执行真实 Gemini 网络验收（LIVE_NOT_RUN），离线 Fake Provider 已覆盖
  完整闭环。

## 验收记录

离线/数据库/Python 回归结果与关键证据见
[MS-C1 验收摘要](../acceptance-evidence/ga-ms-c1-continuation-20261024/summary.md)。

## R1 修复（2026-10-24）

独立审查确认并修复五处缺陷：

1. Java v2 `parseRequest` 校验 `continuationRef` 后未赋值，导致引用在真实
   JSON 行线上丢失；现保留到 `Message.continuationRef` 并由跨语言 fixture
   回归覆盖。
2. Python `ToolCall` 保留 `extra_content` 可选字段会在实际
   `model_dump_json` 中输出 `extraContent:null`；已从 v2 契约彻底移除该字段，
   Pydantic `extra=forbid` 使显式 extraContent 直接拒绝。
3. 旧 Checkpoint 迁移把 sidecar 绑定到当前消费者 Run；现从单条同 Thread
   SUCCEEDED MODEL 记录解析真实 `sourceRunId/sourceCallId`，多候选歧义
   失败关闭，重复迁移幂等。
4. Java 重包 pending writes 外层为 v2 后 Python 跳过旧格式迁移；现按载荷
   形状递归扫描 `tool_call_extra_content`（含 tuple/Send/dict/list），不再
   依赖外层 codec 版本。
5. `extract()` 曾把多工具签名合并后复制给所有工具；现按 ToolCall ID 保存
   独立签名，仅允许单一 `google.thought_signature` 命名空间，恢复时按
   `call.id` 精确注入。

## R2 安全收口（2026-10-24）

1. 历史 Checkpoint 迁移现在在同一次来源查询中校验来源执行行的
   provider/model/settingsRevision 与当前消费者冻结绑定精确一致；
   `custom-provider:<UUID>:<revision>` 的完整字符串比对天然包含 Custom
   连接 UUID，跨连接/跨修订/跨 Provider/跨模型均失败关闭且不新增 sidecar。
2. Python 可达的 HTTP 模型 RPC（同步与流式预检/入口）只接受
   `ga-model-inference.v2`；v1 及 raw `extraContent` 在授权、预算、账本与
   Provider 调用之前即以 `GA_MODEL_PROTOCOL_ERROR` 拒绝。v1 仍保留用于
   Java 内部历史账本读取与协议适配器表示。
3. 混合签名形态（同一响应中部分 ToolCall 带 `google.thought_signature`
   而另一部分没有）在首轮返回可执行工具调用前即被 `extract()` 拒绝，
   不会等到工具副作用之后第二轮恢复时失败；全签名与全无签名多工具批量
   保持原有支持。

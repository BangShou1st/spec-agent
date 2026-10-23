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

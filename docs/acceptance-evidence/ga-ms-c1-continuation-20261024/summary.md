# GA MS-C1 Provider Continuation Boundary 验收摘要

日期：2026-10-24（Asia/Shanghai）

## 结论

离线与数据库验收通过；真实 Gemini 网络验收未执行（LIVE_NOT_RUN）。

## 测试结果

- Java `testOffline`：401 completed / 0 failed / 0 skipped。
- Java `testDatabaseIntegration`（隔离库
  `spec_agent_p3b_live_ms_c1_20261024`）：986 completed / 0 failed / 41 skipped。
  跳过项为真实外部服务/实时提供商/跨语言 broker 的显式 opt-in 套件，非本轮功能缺口。
- Python `pytest`：342 completed / 0 failed / 0 skipped。

## 关键验收点

1. v2 线协议拒绝 provider `extraContent`、非法/错位 `continuationRef`；v1
   请求禁止携带引用字段。
2. Fake Provider 闭环：ToolCall+签名 → sidecar AES-GCM 密文 → `gacr-` 引用 →
   ToolResult → Java 恢复注入 `extra_content` → Provider 收到正确签名 → 最终正文。
   Python wire、模型账本 `ga_execution_calls.result`、sidecar 密文均不含明文签名。
3. sidecar 与 MODEL 账本同一事务提交；保存失败时账本不能变 SUCCEEDED。
4. 跨 Thread、stale epoch、取消、绑定漂移、伪造迁移载荷均失败关闭；同 Thread
   同 provider/model/revision 的后续 Run 可复用引用。
5. `ga-json.v1` 旧 checkpoint 读取后经 `MIGRATE_CONTINUATION` 换取真实 Java
   引用；迁移幂等，不改写历史行；新 checkpoint 为 `ga-json.v2` 且只含引用。
6. Thread 删除级联清理 sidecar。

## 已知限制

- 未执行真实 Gemini 请求（无本轮免费额度授权声明），标记 LIVE_NOT_RUN。
- Responses / Anthropic 协议仍为 UNSUPPORTED，沿用既有失败关闭。
- 历史 v1 账本 result 中的明文续接材料按历史证据受控保留，未做回写清理。

# GA MS-C1-R1 Continuation 协议缺陷修复验收摘要

日期：2026-10-24（Asia/Shanghai）

## 结论

P1-A/P1-B/P1-C/P1-D 与 P2 全部修复；新增真实 Python JSON → Java 行线闭环、
旧跨 Run 迁移与 pending writes 兼容回归。全部本地套件通过，远端 CI 见交付报告。

## 缺陷与修复

| 编号 | 根因 | 修复 | 修复前复现 |
|---|---|---|---|
| P1-A | Java v2 解析校验引用后未赋值 | `parseRequest` 保留 ref | `v2RequestJsonPreservesContinuationRefThroughReadRequest` 修复前失败 |
| P1-B | Python ToolCall 保留可选 extra_content，序列化输出 null | v2 契约移除字段 | fixture 断言 JSON 无 `extraContent`；Pydantic forbid 显式拒绝 |
| P1-C | 迁移 sidecar 绑定当前消费者 Run | 单条记录解析真实 source run/call，歧义失败 | 新增 Run A→Run B 集成测试 |
| P1-D | Java 重包 pending writes 后跳过迁移 | 按载荷形状递归迁移（含 tuple） | 新增 v1 pending write rewrapped 回归修复前失败 |
| P2 | 多工具签名合并后复制 | 按 ToolCall ID 独立保存/恢复 | 多工具 shape 单测 |

## 真实行线闭环

`GaModelContinuationCrossLanguageWireIntegrationTest` 消费
`contracts/global-assistant/fixtures/ms-c1-r1/` 中 Python
`ModelRequest.model_dump_json` 实际输出：第一轮 Fake Gemini 返回签名 ToolCall，
Java 加密 sidecar 并返回 `gacr-` 引用；第二轮 Python JSON 携带该引用，Java
`readRequest` 保留引用、sidecar 授权恢复、适配器按 ToolCall ID 注入签名，
Fake Provider 核验历史签名并返回最终回答。模型账本与 sidecar 密文均无明文签名。

## 测试结果

- Java `testOffline`：403 completed / 0 failed / 0 skipped。
- Python `pytest`：343 completed / 0 failed / 0 skipped。
- Java `testDatabaseIntegration`（隔离库 `spec_agent_p3b_live_ms_c1_r1_20261024`）：
  989 completed / 0 failed / 41 skipped；跳过项为外部服务/实时提供商/跨语言
  broker 显式 opt-in 套件。
- 前端 `npm run build`（vue-tsc + vite）：通过。

---
status: Historical
authority: Historical Reference Only
date: 2026-10-23
---

# Model Service V2-Live 本地交付记录

结论：`MODEL_SERVICE_V2_LIVE_PASS`。真实免费 Gemini 以普通 Custom 连接完成三项能力资格、项目 Agent 真实回答和全局助手真实工具闭环；同时发现并最小修复了当前用户回答无法确认 claim 的协议缺陷。当前规则见 [Model Service V2](../../v2/MODEL_SERVICE_V2.md)、[Agent Memory And Context](../../v2/AGENT_MEMORY_AND_CONTEXT.md) 与 [自定义 API Runbook](../../runbooks/custom-model-api-runbook.md)。数字证据见 [验收摘要](../../acceptance-evidence/model-service-v2-live-20261023/summary.md)。

## 1. Git 状态

基线 `dev` HEAD `cec2f3f`。工作区原有未提交修改与未跟踪验收材料全部保留，未清理、未还原、未纳入本次提交。提交说明见文末。

## 2. 真实 Gemini Custom 接入

按产品路径新建 Custom 连接（UUID `a95bc5e8-…`，`CHAT_COMPLETIONS`，`https://generativelanguage.googleapis.com/v1beta/openai`，`gemini-3.5-flash-lite`，Key 仅由 Java 加密保存）。模型目录返回 60 个模型、ID 带 `models/` 前缀且不公布价格；目录只作发现，选中模型保持裸 ID。凭据从操作员桌面文件只读进入 Java，未打印、未传给 Python、未入库到证据。

## 3. CHAT / STRUCTURED / NATIVE_TOOLS

三项分别验证、分别记录：普通聊天 1 次请求；项目 JSON_OBJECT 决策 1 次；原生工具同一 `probe_echo` 往返 2 次。结果均为 VERIFIED。普通聊天成功没有被扩展为结构化或工具成功。

## 4. 两类 Agent 的真实执行

项目 Agent：2 次生产模型调用、0 重试、`completed`，落库 2 节点 / 1 回答 / 1 补丁，Run 行冻结 `custom-provider:<uuid>:1` 与 SHA-256 指纹。全局助手：`NATIVE_TOOLS`，2 次模型调用、1 次 `project.get_summary` SUCCEEDED，工具结果后继续真实回答，HostCheckpoint 恢复一致，最终回答 180 字符。

## 5. 多连接、资格失效、冻结与删除

改模型 ID 使三项能力全部 UNTESTED 且修订号递增；重新验证后全部 VERIFIED。当前连接拒绝删除；删除另一条非活动连接后当前选择不变。多连接切换与跨实例冻结继续由 `ModelConnectionsIntegrationTest` 的双 Fake Provider 用例覆盖：切换后旧 Run 仍重放原连接，删除后旧运行 `MODEL_BINDING_MISMATCH` 且不发起新请求。现场未重复真实双连接切换，原因见第 8 节。

## 6. 通用 Harness 复用

复用 `ModelProvidersService` / `ModelConnectionCapabilities` / `NativeToolRoundTripVerifier` / `ScenarioRunner`（remote-python 生产接线）/ `GaNativeModelBroker` / 既有 GA Python 校验脚本 / `SharedPythonRetrievalFixture` 的解释器定位与隔离库守卫。新增用例只组合既有入口，没有第二套验收系统，也没有 Gemini 专用运行时分支。

## 7. 回归

| 检查 | completed / failures / skipped |
| --- | --- |
| Java `testOffline` | 401 / 0 / 0（原 396 + 5 个新确认来源用例） |
| Java 审查过的 58 类 `test` | 459 / 0 / 0 |
| Python | 341 / 0 / 0 |
| 前端 | 988 → 987 通过 / 0 / 1（`SPEC_AGENT_GA_LIVE_REPLAY=true`） |
| 前端类型检查 / 构建 | 通过 / 通过 |

在全新空库上额外试跑的宽口径集合（1382 / 3 / 28）出现 3 个环境依赖失败：Python 检索 fixture 未就绪，以及两个需要预置 Provider 状态的 GA 用例。它们不在审查过的 459 集合内，本轮改动路径未触及，未通过放宽断言处理。

## 8. 请求、Token、耗时与费用

正式验收轮 12 次底层请求，0 自动重试：CHAT 2、STRUCTURED 2、原生工具往返 4、项目 Agent 2、全局助手 2。助手侧 2195 prompt / 154 completion tokens；项目 Agent 与探测的 token 未由内部 broker/探测路径暴露。项目 Agent 单次运行 7.2 s，验收测试总时长约 27 s。目录无价格，免费额度为 owner 明示声明；API 不暴露实际扣费，期望为 0，未使用付费层。

调试阶段另发生约 44 次显式请求（每次为独立运行，不是自动重试），累计约 56 次，超过单轮 12 次建议值。原因是用真实模型定位一处分层协议缺陷并修复后重复正式验收；该超限如实记录，不隐藏。

## 9. 未完成能力与真实风险

- 供应商私有续接材料仍在 Python/LangGraph Checkpoint；Java 托管 continuation reference 未迁移，需要独立的历史兼容与恢复验收。
- GA Custom 仍为 buffered，不能宣称供应商 SSE。
- Custom Responses / Anthropic Messages 的 GA 能力仍明确拒绝或 UNSUPPORTED。
- 免费 Flash-Lite 的结构化输出质量有波动；运行时继续 fail-closed，不因一次成功推断长期资格。
- 项目 Agent token 用量不可见，成本只能按请求数而不是 token 计。

## 10. Continuation-reference 是否独立阶段

是。本轮只在既有 D4-R3 基线上验证真实原生工具闭环，没有改动 Checkpoint 格式。供应商私有状态迁往 Java 需要 Run/epoch/lease 授权、工具幂等、跨实例恢复与版本演进，必须作为独立基础设施阶段，不在 V2-Live 中重写。

## 11. 修改文件及必要性

| 文件 | 必要性 |
| --- | --- |
| `AgentBrainResponseValidator` | 修复“当前路线用户回答已持久化并列入允许来源、却因不在 lineage 而无法确认”的真实阻断 |
| `RagD4ClaimAuthorityTest` | 覆盖新放行边界与外部证据/其他路线/非用户权威仍拒绝 |
| `ModelServiceV2LiveAcceptanceTest` | 通用、opt-in 的 V2-Live 真实链路验收 |
| `ModelConnectionLiveQualificationTest` | 目录无价格时允许 owner 声明作为唯一免费证明 |
| `docs/v2/AGENT_MEMORY_AND_CONTEXT.md` | 记录确认权威边界 |
| `docs/v2/MODEL_SERVICE_V2.md` | 记录 L4 免费证明规则 |
| `docs/runbooks/custom-model-api-runbook.md` | 记录声明方式、Gemini 目录前缀与验收入口 |

## 12. 最终提交与推送建议

本地 `dev` 提交只包含上述文件与验收证据；不 push、不动 `main`、不建 PR、不 force push。建议 owner 审阅后单独授权推送，并在此之前决定是否补一次真实双 Custom 切换与 Zen/Kilo 烟雾测试。

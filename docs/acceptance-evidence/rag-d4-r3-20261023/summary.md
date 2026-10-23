---
status: Canonical
authority: Current Authority
---

# Spec Agent · D4-R3：Gemini 全局助手原生工具续接收口报告

日期：2026-10-23  
本地开发分支：`dev`  
初始基线：`106f14ff43081fa34c94ee2efffff037746bb2f0`

---

## 1. 真实根因与诊断证据

### 1.1 根因分析
在 D4-R2 阶段，Gemini 首次模型调用能够生成原生的 `project_get_summary` Tool Call，但在第二轮携带工具 Observation（`ToolMessage`）调用模型时，上游 HTTP 报 400（`INVALID_ARGUMENT`），经 Java Gateway 映射为 `PROVIDER_REQUEST_ERROR`，Python 接收到 502。

经排查，真实根因包含以下三点协议与架构层缺失：
1. **Gemini OpenAI 兼容接口思考签名丢失**：Google Gemini 思考型模型（如 `gemini-3.5-flash-lite`）在 OpenAI 兼容端点（`/chat/completions`）返回 tool calls 时，在 `extra_content.google.thought_signature` 中携带推理状态签名。原协议适配器在解析首轮响应时将该私有字段丢弃，导致第二轮向 Gemini 发送带工具调用的历史 assistant 消息时丢失了 `thought_signature`，上游直接拒绝。
2. **Assistant 消息在携带 tool_calls 时的 content 格式约束**：OpenAI 协议与 Gemini 兼容端点要求包含 `tool_calls` 的 assistant 消息，其 `content` 若为空字符串则必须传 `null`，否则被视为格式不合规。
3. **LangGraph Checkpoint 编解码白名单拒绝**：Python 端 `SafeCheckpointCodec` 严格校验消息元数据，原本要求 `not data.get("additional_kwargs")`，导致存储在 `additional_kwargs` 的 `tool_call_extra_content` 在状态持久化时触发 `ValueError("provider metadata is forbidden in checkpoints")`。

### 1.2 脱敏诊断证据
根据诊断规范，在 HTTP 传输层集成了 `GaHttpSafetyDiagnostic`：
- **上游 HTTP 状态**：`200 OK`（首轮与次轮均成功）。
- **工具调用 ID 关联**：assistant 消息中的 `tool_call_id` 与 tool 回执中的 ID 严格匹配（均为 `call_351569`），无孤立或未应答调用。
- **思考签名字段存在性**：`hasThoughtSignature: true`（仅记录布尔值，严禁输出明文签名值）。
- **脱敏错误类别**：`NONE`，失败阶段：`SUCCEEDED`。

---

## 2. 修改文件与必要性

| 层次 / 文件 | 修改内容 | 必要性 |
|---|---|---|
| **Java 契约**<br>`backend/.../model/contract/GaModelContract.java` | `ToolCall` 增加可选 `Map<String, Object> extraContent`，配置 `@JsonInclude(NON_NULL)` | 最小版本化契约演进，无损承载上游协议特定元数据，完全向后兼容旧场景 |
| **Java 适配器**<br>`backend/.../model/provider/GaChatCompletionsAdapter.java` | 1. `parse` 解析提取 `extra_content` / `extraContent`；<br>2. `requestBody` 在回传带 tool_calls 的 assistant 消息时，若 content 为空则置为 `null`，并携带 `extra_content` | 满足 Google Gemini `/chat/completions` 原生工具历史调用格式规范 |
| **Java HTTP 安全诊断**<br>`backend/.../model/provider/GaHttpSafetyDiagnostic.java`<br>`backend/.../model/provider/ChatCompletionsGaTextTransport.java` | 记录请求与响应的上下文、模型、端点类别、HTTP 状态、消息结构摘要、ID 匹配及签名布尔标记 | 满足诊断要求，不泄露 API Key、Authorization、正文或签名原值 |
| **Python 跨语言契约**<br>`agent-brain/.../global_assistant/contracts.py` | `ToolCall` 增加 `extra_content: dict[str, Any] | None`（别名 `extraContent`） | 跨语言 Broker 序列化与反序列化对齐 |
| **Python 模型适配器**<br>`agent-brain/.../global_assistant/model_adapter.py` | 经由 `AIMessage.additional_kwargs["tool_call_extra_content"]` 透传签名；构建请求时完整复原回传给 Java Broker | 避开 LangChain `ToolCall` TypedDict 对未知键的校验异常，无损跨轮透传 |
| **Python Checkpoint**<br>`agent-brain/.../global_assistant/checkpoints.py` | `_safe_message_metadata` 仅放行 `tool_call_extra_content`，继续拒绝 `reasoning_content` 及其他未授权私有字段 | 保证 LangGraph Checkpoint 安全边界，支持工具调用轮次正常持久化恢复 |
| **离线协议测试**<br>`backend/.../model/provider/GaGeminiProtocolOfflineTest.java`<br>`agent-brain/tests/test_global_assistant_langchain.py`<br>`agent-brain/tests/test_global_assistant_checkpoints.py` | 覆盖单/多工具往返、ID 错配、缺失签名诊断、纯文本无工具回归、未取得资格拒绝等 7 大场景 | 确保进入真实调用前离线协议完备性，符合门禁要求 |

---

## 3. 测试与验证结果

### 3.1 离线协议与回归测试
1. **Java `testOffline` 审计套件**：
   - 367 项测试，0 失败，0 忽略，100% 通过（包含新增的 `GaGeminiProtocolOfflineTest` 7 项全场景）。
2. **Python Pytest 套件**：
   - 341 项测试，0 失败，全部 PASS（包含 `test_tool_call_extra_content_thought_signature_roundtrip` 与 Checkpoint 编解码测试）。

### 3.2 真实 Gemini 工具调用闭环
在隔离 PostgreSQL（`spec_agent_p3b_live_rag_d4_r3_20261023`，容器 `spec-agent-rag-d4-r3-20261023`）中运行 `RagD4R3LiveDeliveryTest`：
- **调用序列**：
  1. `COMPATIBILITY_PROBE`：探测成功（3,189 ms）。
  2. `GA_NATIVE`（第 1 轮）：Gemini 生成 `project_get_summary` Tool Call，携带合法 `thought_signature`（1,079 ms）。
  3. **宿主执行**：Java CapabilityRuntime 校验参数与权限，执行 `project.get_summary` 返回 `SUCCEEDED`。
  4. **Python Observation**：Python 接收到包含项目标题的 `ToolMessage`。
  5. `GA_NATIVE`（第 2 轮）：携带 assistant 签名与 tool回执再次请求 Gemini，返回最终回答（930 ms，HTTP 200）。
  6. **回答验证**：回答准确包含项目标题 `D4 R3 Gemini 工具续接验收`，状态机与 Checkpoint 成功持久化并还原。
- **闭环结论**：**真实 Gemini 原生工具调用完整闭环 100% 通过**。

---

## 4. 资格开关与绑定配置

根据规范，资格开关必须保持显式绑定、最小权限、失败关闭：
1. **绑定配置项**：`spec.global-assistant.ga-native.custom-qualified-targets`。
2. **精确合格目标**：
   - `base-url`: `https://generativelanguage.googleapis.com/v1beta/openai`
   - `model`: `gemini-3.5-flash-lite`
3. **默认生产行为**：默认列表保持为空（`[]`）。未显式登记的目标或其它 Custom 端点继续由 `requireTextOnly` 强行拒绝工具，保持失败关闭。

---

## 5. 调用用量、耗时与费用

- **底层请求总数**：3 次（1 次兼容探测 + 1 次工具生成 + 1 次工具续接回答）。
- **请求预算执行**：3 / 8（严格低于 8 次调用预算，0 自动重试）。
- **Token 消耗**：
  - Prompt Tokens：2,162
  - Completion Tokens：105
  - Total Tokens：2,267
- **耗时**：
  - 探测：3,189 ms
  - 第 1 轮推理：1,079 ms
  - 第 2 轮推理：930 ms
- **费用**：USD 0.00（已核实属于免费测试层级，未触发任何付费账单）。

---

## 6. 不支持的协议形态与已知限制

1. **并行工具写操作 / 交互工具**：仅支持只读工具批处理，拒绝并行写操作。
2. **Prompt 模拟工具调用**：坚决不通过系统 Prompt 模拟 Tool Calling，仅支持上游原生协议。
3. **未取得资格的 Custom 端点**：对于任何未登记的 Base URL 或模型，一律保持 `requireTextOnly`，直接拒绝原生工具调用。
4. **SSE 流式思考签名恢复**：当前 Custom GA 原生工具传输采用有界 Buffered 模式，暂不开放流式签名中间帧拼接。

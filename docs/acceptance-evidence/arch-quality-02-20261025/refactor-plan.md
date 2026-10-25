# ARCH-QUALITY-02 · 实施方案

status: Historical(本轮快照,2026-10-25)
authority: Historical Reference Only

依据 audit.md 的证据,按簇 A → B → C 顺序实施;每簇先定向测试再进入下一簇。

## 簇 A:统一结构化错误诊断

**新增 `src/spec_agent_brain/contracts/diagnostics.py`**(跨语言 wire 契约层,与 Java `ModelContractDiagnostic` 镜像):

1. `ContractDiagnostic` frozen dataclass —— 从 decision/engine.py **原样搬移**(字段、默认值逐字不变)。
2. `contract_diagnostic(...)` 构造器 —— 即原 `_contract_diagnostic` 工厂。
3. 共享安全提取原语:`safe_field_path(location, known_fields)`、`actual_type(value)`、`expected_type(error_type)`(known_fields 由调用方按各自输出模型传入,白名单机制不变)。

**decision/engine.py**:删除本地定义,改 import;`ContractDiagnostic` 经由模块命名空间继续可导入(test_app.py 既有 `from spec_agent_brain.decision.engine import ContractDiagnostic` 不变)。行为零变化。

**state_update/engine.py**:
- `BrainContractError` 增加 `diagnostic: ContractDiagnostic | None = None` 参数(与 decision 同签名模式)。
- JSON 解析失败 → 挂 MODEL_OUTPUT_INVALID_JSON 诊断(stage `brain.model_output_parse`,validator `json.loads`);消息保持 "model output is not valid JSON",`from None`。
- schema 失败 → 挂 MODEL_OUTPUT_SCHEMA_INVALID 诊断(stage `brain.model_output_validation`,validator `ModelStateUpdateOutput`,fieldPath 经白名单);**消息不再嵌 `{exc}`**,改为与 decision 同风格的无值布局前缀,`from None` 切断 pydantic 异常链。

**artifact/engine.py**:
- `ArtifactBrainContractError` 增加 diagnostic 参数。
- schema 失败 → MODEL_OUTPUT_SCHEMA_INVALID(validator `ModelArtifactOutput`),去 `{exc}` 嵌入。
- 越界 ref → MODEL_OUTPUT_UNGROUNDED_REFERENCE(stage `brain.output_grounding`,validator `sectionSourceRefAllowlist`,fieldPath `sections[].sourceRefs`),**消息不再嵌 ref 值**。
- 空 sourceRefs → 新机器码 `MODEL_OUTPUT_SECTION_WITHOUT_SOURCE_REFS`(stage `brain.output_grounding`,validator `sectionSourceRefPresence`),**消息不再嵌 section.title**。该机器码不在 Java 白名单 → Java 分类不变(BRAIN_UNAVAILABLE),纯增量。

**app.py**:零修改(`_fail` 的 getattr 透传天然支持)。

**兼容性论证**:异常类名全部不变 ⇒ `brain_failure:<类名>` 与 Java 分类不变;机器码复用 decision 既有词汇(artifact UNGROUNDED 恰好落入 Java 白名单对,为设计内增强);HTTP 状态码 502/409/422 不变;UNKNOWN 语义保留给传输失败与未知异常。

**新增测试**(新文件 `tests/test_engine_contract_diagnostics.py`):
- 三引擎失败路径的诊断存在性、机器码、stage、validatorId、字段路径一致性。
- 响应体不含模型输出文本(泄漏回归)。
- 异常消息本身不含模型输出值。
- /v1/state-updates、/v1/artifacts 端到端(经 TestClient)contractDiagnostic 出现、UNKNOWN 消失。
- 既有 UNKNOWN 行为(ModelClientError)不回归。

## 簇 B:收敛 WAIT 校验纯逻辑

**contracts/decisions.py 新增纯谓词**:

```python
def wait_payload_matches_contract(payload, allowed_dependency_refs) -> bool
```

无副作用、无 I/O、不依赖请求类型;判定 = 恰好一个 `dependencyRef` 字符串键 ∧ 值 ∈ 允许集合。两处调用方各自保留异常类型与消息(生产 `ActionIneligibleBrainError("WAIT requires a projected dependencyRef")` / 辅助 `ValueError` 同文案)。`validate_v3_response_for_request` 函数保留(信封级自检,非纯冗余)。Java 权威复核不动。

**测试**:在 tests/test_c16_wait_v4.py 补拒绝矩阵(缺失/None/错误类型/未知 ref/额外字段/空对象)对生产路径与契约辅助路径的**一致性**断言(同一矩阵两路都必须拒绝);合法 payload 两路都接受。Java 侧由既有 ActionEligibility 套件与跨语言 fixture replay 锁定(不改 Java)。

## 簇 C:协议版本单一事实来源

1. **global_assistant/contracts.py** 顶部以 `Final` 常量定义:`GA_EXECUTION_PROTOCOL_VERSION`、`GA_EXECUTION_EVENT_PROTOCOL_VERSION`、`GA_MODEL_INFERENCE_PROTOCOL_VERSION`、`GA_CAPABILITY_INVOCATION_PROTOCOL_VERSION`、`GA_CAPABILITY_RESULT_PROTOCOL_VERSION`、`GA_MODEL_STREAM_PROTOCOL_VERSION`;各 DTO 的 `Literal[常量]`(PEP 586 合法:Final 字面量参数)。`ga-model-inference.v1`(Java 内部代际)保持不引入 Python、不合并。
2. **app.py**:`GA_EXECUTION_PROTOCOL_VERSIONS` 改由常量组成;`workspaceRpcGrantVersions` 引用 `contracts.protocol.WORKSPACE_RPC_GRANT_VERSION`。
3. **contracts/protocol.py**:新增 `WORKSPACE_RPC_GRANT_VERSION`(从 broker_client.py 迁入,跨语言协议常量的既有归属);`INFERENCE_PROTOCOL_VERSION` 保持原地。
4. **model_client/broker_client.py**:payload `protocolVersion` 改用 `protocol.INFERENCE_PROTOCOL_VERSION`;删除本地未使用的 WORKSPACE_RPC_GRANT_VERSION(实测零引用)。
5. **global_assistant/execution.py** 事件发送、**model_adapter.py** ModelRequest 构造改用常量。

**不做**:不升级任何版本、不改 wire schema、不给未协商的协议新增公告、不合并 v1/v2 代际。

**新增测试**(tests/test_protocol_announcement.py):
- /health 公告列表 == [GA_EXECUTION_PROTOCOL_VERSION, GA_EXECUTION_EVENT_PROTOCOL_VERSION](常量),且每个公告版本能被对应 DTO 实际接受(构造最小合法 ExecutionRequest / ExecutionEvent)、未公告版本被拒——公告与接受能力的行为级一致性。
- workspaceRpcGrantVersions == [WORKSPACE_RPC_GRANT_VERSION] 且 broker 请求 protocolVersion == INFERENCE_PROTOCOL_VERSION(经 MockTransport 捕获)。

## 风险与回退

- 最大风险是簇 A 改动错误消息文本影响隐藏断言——已 grep 全部 tests 确认仅 test_engines.py:133 断言 decision "eligibility mask" 文案(不动);state_update/artifact 无消息级断言。
- 全程 Fake/MockTransport,无真实供应商、无 DB;每簇定向 pytest,最终全量 pytest + Java testOffline/架构守卫抽样。
- 任一簇若出现不可解释的回归,保留原状并在 summary 记录理由。

# ARCH-QUALITY-02 · Python Brain 契约与诊断治理审计

status: Historical(本轮快照,2026-10-25)
authority: Historical Reference Only(后续以最新代码为准)

## 1. 基线核对(实际执行,非引用旧报告)

- 分支 `dev`,HEAD = `8f231e8401f012370d611b1dc12761e4232cff93` = `origin/dev`,0 ahead / 0 behind。
- 工作区:2 个用户修改文件(`ModelSettingsIsolationIntegrationTest.java`、`docs/v2/evidence/SHARED_RAG_ACCEPTANCE_PRECHECK.json`)+ 大量历史 untracked 证据目录,均与本轮 Python Brain 范围无重叠,全部保留不动。
- ARCH-QUALITY-01 的 audit.md §5 记录的 Python P1 候选即本轮三个簇的来源;本轮逐项在当前代码上重新核实(行号以本轮 HEAD 为准)。

## 2. 簇 A:三个引擎的结构化错误诊断

### 现状调用关系

```
state_update/engine.py ─┐
decision/engine.py ─────┼─ handle_*() ─→ app.py 端点 ─→ _fail()
artifact/engine.py ─────┘                  │
                                           ├─ 有 .diagnostic → 序列化 contractDiagnostic(camelCase)
                                           └─ 无 .diagnostic → diagnosticStatus=UNKNOWN + 日志
                                                    ↓ HTTP 502 body {"detail":"brain_failure:<类名>", ...}
                                           Java RemotePythonDecisionEngine.classifyBrainFailureDetail
                                           (按 detail 中的异常类名映射 BrainFailureCode)
                                           + modelContractDiagnostic(白名单 (类名, 机器码) 对才提取)
```

### 逐引擎证据

| 引擎 | 异常体系 | 诊断 | 消息脱敏问题 |
|---|---|---|---|
| decision | `BrainContractError(msg, diagnostic)` + 5 个类型化子类(engine.py:57-96) | 5 条失败路径全部带 `ContractDiagnostic`(engine.py:42-54 dataclass;机器码 MODEL_OUTPUT_INVALID_JSON / SCHEMA_INVALID / AMBIGUOUS_SOURCE_REFS / UNGROUNDED_REFERENCE / CONFLICT_NOT_SURFACED) | 无(字段路径白名单 `_safe_field_path`,类型名词汇 `_actual_type`/`_expected_type`,`from None` 切断异常链) |
| state_update | 裸 `BrainContractError(RuntimeError)`(engine.py:22),无 diagnostic 属性 | 无 → HTTP 响应恒为 `diagnosticStatus=UNKNOWN` | **有**:engine.py:59 将 pydantic `ValidationError` 原文 `{exc}` 嵌入异常文本(含模型输出的值) |
| artifact | 裸 `ArtifactBrainContractError(RuntimeError)` + `UngroundedReferenceError`(engine.py:24-33) | 无 → UNKNOWN | **有**:engine.py:69 同样嵌 `{exc}`;:78 嵌模型生成的 `section.title`;:82 嵌越界 ref 值 |

### Java 兼容性约束(实测 RemotePythonDecisionEngine.java:148-170, 333-347)

- 分类完全按 `brain_failure:{ClassName}` 里的**异常类名**:白名单为 UngroundedReferenceError / ModelClientError / BrokerTimeoutError / ModelOutputContractError / AmbiguousSourceRefsError / ConflictSurfacingError,其余类名 → BRAIN_UNAVAILABLE。
- 诊断提取仅在 MODEL_CONTRACT_VIOLATION / MODEL_UNGROUNDED_REFERENCE 失败码下发生,且受 `compatibleModelDiagnostic(类名, 机器码)` 白名单二次门控。
- 结论:**异常类名与机器码词汇表不变 ⇒ Java 分类不变**;为 state_update/artifact 新增的 `contractDiagnostic` 字段对 Java 是纯增量(未列入白名单的组合被安全忽略,parseFailureDetail 已兼容读取顶层 `contractDiagnostic`)。
- 附注:state_update 的 `BrainContractError` 与 decision 的同名但不同类,Java 端均不识别 → BRAIN_UNAVAILABLE,维持现状不重命名(重命名会改变 Java 错误映射,本轮禁止)。artifact 的 `UngroundedReferenceError` 与 decision 同名,挂上 MODEL_OUTPUT_UNGROUNDED_REFERENCE 后恰好落入 Java 白名单对——按既有设计被提取,属预期增强而非破坏。

### semantic_diagnostics 与 ContractDiagnostic 的职责边界(审查结论,不合并)

- `diagnostics.py::semantic_diagnostics`:**成功/观察侧**,嵌入响应信封 `diagnostics` 字段,内容为脱敏后的语义输入追踪(prompt 哈希 + sanitized modelInput)。
- `ContractDiagnostic`:**失败侧**,经 HTTP 错误体 `contractDiagnostic` 字段过线,内容为结构化契约违约事实(阶段/校验器/机器码/字段路径/类型期望),Java `ModelContractDiagnostic` 一一镜像。
- 两者数据边界、消费方、生命周期均不同;本轮把 `ContractDiagnostic` 的定义与构造原语移入 `contracts/diagnostics.py`(它是跨语言 wire 契约),`diagnostics.py` 保持纯语义证据职责,文件级分离。

### 传输失败的处理决定

`ModelClientError` / `BrokerTimeoutError`(broker 超时/不可达/非 200)不挂 `ContractDiagnostic`:它们不是契约违约,诊断形状(validatorId/fieldPath/expectedType)对其是伪结构;Java 已按类名映射 MODEL_PROVIDER_FAILURE / BRAIN_TIMEOUT。这些路径保留 `diagnosticStatus=UNKNOWN`,如实表示"无契约诊断可给",不为消灭 UNKNOWN 伪造诊断。`ActionIneligibleBrainError` 保持 409 ACTION_INELIGIBLE 专用路径不变。

## 3. 簇 B:WAIT / dependencyRef 重复规则

三份实现(逐字核对):

| # | 位置 | 检查对象 | 异常/后果 | 调用方 |
|---|---|---|---|---|
| 1 | `decision/engine.py:470-475`(`_check_eligibility_action`) | 模型输出 `ModelDecisionOutput.action.payload`(V4 请求 + WAIT family) | `ActionIneligibleBrainError` → app.py → **HTTP 409 ACTION_INELIGIBLE**(生产前置拒绝) | handle_decision ← app `/v1/decisions` |
| 2 | `contracts/decisions.py:165-171`(`validate_v3_response_for_request`) | 响应信封 `AgentV3ResponseEnvelope.action_proposal.payload` | `ValueError` | **src 内零生产调用**,仅 tests(test_c16_wait_v4.py、test_contracts.py) |
| 3 | Java `ActionEligibilityValidator.java:63-69` | Java 侧 ActionProposal payload | `ActionIneligibleException(NO_PENDING_DEPENDENCY)`(**权威复核**) | ActionEligibilityGate.assess |

- #1 与 #2 的 WAIT 判定谓词逐行相同:`set(payload) != {"dependencyRef"} or not isinstance(payload.get("dependencyRef"), str) or payload["dependencyRef"] not in allowed`。
- #2 不是 #1 的冗余副本:**校验对象不同层**(模型输出 DTO vs 响应信封),且函数还承担版本匹配、eligibility 摘要、evidence refs grounding 等信封级自检,供契约测试直接消费。结论:保留函数,仅收敛其内部 WAIT 谓词。
- 与 Java 的合理差异:Java 检查 `pending == null`(Java 信封允许缺省该字段);Python `AgentV4RequestEnvelope.pending_dependencies` 为必填字段(inputs.py:425,缺省在 pydantic 解析即被拒),故 Python 无需 null 分支。此差异保留,依据是两侧信封必填性不同。
- prompt 侧 `waitPayloadSchema`(decision/engine.py:212-214)是对模型声明的 JSON Schema(告知约束),与校验谓词(执行约束)表示形式不同、生命周期不同,不强行同源。

## 4. 簇 C:协议版本与能力公告

逐版本追踪(全仓 grep 实测):

| 字面量 | 常量/Literal 定义 | 发送/校验处 | /health 公告处 | 问题 |
|---|---|---|---|---|
| `model-inference.v1` | `contracts/protocol.py:17 INFERENCE_PROTOCOL_VERSION`(**定义后零使用**) | `broker_client.py:51` 硬编码发送 | 无(不协商) | 发送值与常量脱钩 |
| `workspace-rpc-grant.v1` | `broker_client.py:15 WORKSPACE_RPC_GRANT_VERSION`(**定义后零使用**);Java `WorkspaceRpcGrants.VERSION` | Java 签发/校验 grant ticket | `app.py:183` 硬编码公告 | 公告值与常量脱钩,且常量住在 model_client 层而非契约层 |
| `ga-execution.v1` | `global_assistant/contracts.py:161` Literal | ExecutionRequest 校验 | `app.py:46` 硬编码 | 公告与 Literal 脱钩 |
| `ga-execution-event.v1` | `contracts.py:274` Literal | ExecutionEvent 校验 | `app.py:47` 硬编码;`execution.py:107` 发送时再硬编码一次 | 三处脱钩 |
| `ga-model-inference.v2` | `contracts.py:65,112` **两个重复 Literal** | `model_adapter.py:174` 硬编码发送 | 无 | 重复定义 + 发送脱钩 |
| `ga-capability-invocation.v1` / `ga-capability-result.v1` / `ga-model-stream.v1` | contracts.py Literal | DTO 校验 | 无 | 正常 |
| agent-input/decision/artifact/ranking 等族 | protocol.py 常量 | DTO Literal + 引擎 | `protocolVersion: INPUT_PROTOCOL_VERSION` | 已单一来源 |
| `ga-model-inference.v1` | Java `GaModelContract.VERSION`(v1 代际) | **Java 内部**表示(允许携带 host 解析后的 provider 材料);v2 才是 Python-facing wire | 无 | **有意区分的两代版本,文档已声明,不得与 v2 合并** |
| `langchain-ga.v1`(engineVersion) | contracts.py:162 Literal | — | 无 | 正常 |

协商消费方(实测):Java `GaExecutionCoordinator`(assistant/runtime)在发起执行前读取 `/health` 的 `gaExecutionProtocolVersions` 并与必需版本求交,不认识绝不发送——公告一致性具有真实运行时后果,值得测试锁定。

依赖方向可行性:contracts ← global_assistant/execution ← app 与 contracts ← model_client(broker_client 仅需纯常量模块 protocol.py;实测 contracts 不反向依赖 model_client,无环)。

## 5. 范围外发现(记录不实施)

- state_update 预算检查(`max_model_calls < 1`)与 decision 同款均无诊断——请求侧输入错误,Java wire 校验先拦,不为它构造模型输出诊断。
- `_render_model_input` 的 waitPayloadSchema 枚举与校验谓词的一致性靠测试矩阵锁定(本轮补),不做表示形式统一。
- ARCH-QUALITY-01 audit §5 其余 P2 项(SHA-256 五种写法、ref 前缀词表、conftest 夹具路径等)本轮不动。

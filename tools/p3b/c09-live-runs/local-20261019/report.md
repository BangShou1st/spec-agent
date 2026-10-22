# C09 本地 Custom(Ollama) Live A/B 运行报告 — 2026-10-19

## 判定

**INCOMPLETE / INCONCLUSIVE。** A 臂（生产默认 Prompt，`enforced`）完整跑通并留下可用证据；B 臂（生产默认 Prompt + 现有 c09 候选规则）两次尝试都在 `STATE_UPDATE` 失败（一次 90s 推理超时、一次连接失败），按冻结协议的停止条件（HTTP 502 / connection failure / provider-unavailable）停止。自动裁判 `_decide_c09()` 因 B 臂不完整返回 `INCONCLUSIVE`，`experiment_status = INCOMPLETE`。

未修改冻结 oracle、候选标签或评分标准；未修改生产默认 Prompt。

## 应用实例配置（dev 实例，问题 A）

- 应用实例：本机 Spec Agent，后端 `127.0.0.1:8081`，数据库 `spec_agent`（本轮之前该实例并未运行；用项目自带 dev 入口启动，Ollama 未重启）。
- 全程只走**官方配置接口**（未使用 SQL 直接写表）：
  - `POST /api/v1/settings/custom/discover` → 返回 `nomic-embed-text:latest, qwen3-embedding:0.6b, qwen3.5:4b`
  - `PUT /api/v1/settings/custom` → `configured=true, displayName=Local Ollama - Qwen3.5 4B, configRevision=1`
  - `POST /api/v1/settings/custom/validate` → `validated=true`（`CompatibilityProbeService` 对 Ollama 发一次最小推理并解析出合法 FINAL 决策）
- UI 复核（`/settings/models`）与接口一致：显示名称 `Local Ollama - Qwen3.5 4B`、状态标签 **配置有效**、API Format `CHAT_COMPLETIONS`、Base URL `http://127.0.0.1:11434/v1`、Model `qwen3.5:4b`、API Key `未设置`。见 [ui-custom-provider.png](ui-custom-provider.png)。
- 激活范围：dev 实例的全局激活**保持 KILO**（界面"当前使用 Kilo Gateway"），Kilo/OpenCode/OpenRouter 既有配置未被改动；Custom 处于"已验证、可激活"状态。真实的 CUSTOM 激活只发生在本轮新建的隔离评测实例。
- 持久化：写入 `spec_agent` 库 `custom_provider_settings`（单例行，`api_key=NULL`），应用重启后仍然存在（已复核）。

## 冻结 Provider 与协议

- Provider：**Custom**（本机 Ollama，OpenAI 兼容 Chat Completions）。
- Base URL：`http://127.0.0.1:11434/v1`；Java 网关自动拼接为 `http://127.0.0.1:11434/v1/chat/completions`。
- Model ID：`qwen3.5:4b`（Ollama 报告版本 0.40.6，Q4_K_M，ctx 262144）。
- 凭据：隔离库 `custom_provider_settings.api_key = NULL`（显式留空）；请求不携带任何 Authorization 头。
- 模型目录 `GET /v1/models` 返回 3 个模型，含 `qwen3.5:4b`。
- 冻结 Prompt 哈希与 `p3b_ab_protocol_20261019.json` 完全一致：
  - A（无候选规则）DECISION system prompt SHA-256 `7417cbe5…d42bf7`
  - B（+c09）DECISION system prompt SHA-256 `2078dd4e…5db38d`
  - 两臂 STATE_UPDATE system prompt SHA-256 `d8490e85…351d77`（相同）
- ActionEligibility：`ENFORCED`（与生产默认一致；仓库 test profile 默认是 `shadow`，本轮**未**使用 shadow 跑冻结臂）。
- 预算：总量 8、每臂 4、judge 0、retry 0、fallback 0。
- 本协议全文见 [protocol.json](protocol.json)。

## 隔离环境

- 复用项目自带的 `spec-agent-postgres` 容器（`127.0.0.1:5434`，pgvector 0.8.6），新建专用 live 库 `spec_agent_p3b_live_local_20261019`。
- Java 侧经 `EvalLiveIsolatedDatabase` 校验真实库身份为 `spec_agent@172.21.0.2/32:5432/spec_agent_p3b_live_local_20261019`，种子只作用于该库。
- 用户开发库 `spec_agent` 未被评测读写（其中的 Custom 配置是本轮通过官方 API 保存/验证的，见下）。
- 独立 Python Brain 在 `127.0.0.1:8123` 以 broker 模式运行；Java Host/Internal Broker 端口 `18082`。
- 调用链：Java Host → Python Brain → Java Internal Model Broker → **CustomInferenceGateway** → Ollama。
- 真实 Provider 事件证明链路成立：每个完成尝试记录 `STATE_UPDATE` + `DECISION` 两条 `MODEL_INFERENCE` 成功事件；`provider=CUSTOM`、`model=qwen3.5:4b`、`endpoint=http://127.0.0.1:11434/v1`。

## Arm A 实际动作与自动语义评价（`enforced`，完整）

冻结场景：目标需要检索项目资料，`memory.search` 可用，`scope=PROJECT` 已给出，必填 `query` 缺失。正确行为是**只**向用户索取检索词/query，不重复询问 scope，不编造参数，不调用缺参工具。

| 变体 | 上游请求 | 模型选择动作 | 追问文本（payload.questionText） | 结构校验 | 语义判定 | 能力调用 | 门禁 |
|---|---:|---|---|---|---|---:|---|
| base | 2 | `REQUEST_USER_INPUT` | 「您提交的自由文本答案是否需要进一步确认或补充说明？」 | PASS | `UNRESOLVED`（`TARGET_NOT_PROVEN`） | 0 | PASSED |
| holdout | 2 | `REQUEST_USER_INPUT` | 「您是否接受当前基于假设状态的目标？」 | PASS | `UNRESOLVED`（`TARGET_NOT_PROVEN`） | 0 | PASSED |

补充事实：

- 两条都选择了 `REQUEST_USER_INPUT`（属于 oracle 唯一可接受集合 `acceptable_primary_actions={REQUEST_USER_INPUT}`），Payload 合法（`payload_validity=VALID`），Run 正常终态 `completed`，`failure_classification=NONE`。
- 两条 `source_reference_audit.source_refs_allowed=true`，引用（2 条）都在 `allowedSourceRefs` 内；未引用作废来源。
- 两条都**没有**执行缺参能力调用（`capability_call_count=0`），因此不存在 fail-closed 被绕过的证据；也**没有**重复询问 `scope`。
- 但两条追问都不是"索取缺失的检索词/query"，而是围绕"答案是否需要确认/是否接受假设目标"。冻结的 `p3b-c09-rule-judge.v1` 无法证明其为无关请求，按保守规则记为 `UNRESOLVED / TARGET_NOT_PROVEN`（不是 `INCORRECT`）。
- 耗时（`stage_latency_ms`）：base = STATE_UPDATE 24.7s + DECISION 34.9s；holdout = STATE_UPDATE 20.1s + DECISION 20.8s。

## Arm B 失败与停止

| 尝试 | 失败点 | gateway_category | 结果 |
|---|---|---|---|
| 1 | `STATE_UPDATE` | `TIMEOUT` | 90s 推理超时（ProviderHttpSupport.INFERENCE_TIMEOUT=90s），Brain 记 `ModelClientError`，返回 502 |
| 2 | `STATE_UPDATE` | `CONNECTION` | 连接失败，Brain 记 `ModelClientError`，返回 502 |

两次都发生在 B 臂第一个变体 `base` 的 `STATE_UPDATE`，只发出 1 次请求即停止，从未到达 `DECISION`，也没有进入 c09 规则真正作用的那个调用。故 B 臂不可评分 → `INCONCLUSIVE`。

## 模型能力判断（RTX 4060 8GB + 16GB，qwen3.5:4b）

1. **JSON 语法遵循**：可靠。兼容性探测通过；Arm A 的 4/4 请求都解析出合法 JSON；直接向 Ollama 发的 3 次 `response_format=json_object` 探测 3/3 合法，且没有 `thinking` 字段泄漏到 `content`。
2. **契约/Schema 遵循**：**不可靠**。另有 2 次 `enforced` A 臂尝试在 `STATE_UPDATE` 触发 brain `BrainContractError`（"model output is not valid JSON / violates the STATE_UPDATE contract"）。直接探测复现了根因形态：模型给出枚举外的 `kind`（`"functional_requirement"`，不在允许集合内），即"JSON 语法正确但 Schema/枚举不合规"。
3. **C09 动作正确性**：结构上正确（选 `REQUEST_USER_INPUT`），但**语义上偏靶**——没有索取缺失的 `query`。
4. **是否正确索取缺失参数**：未做到（两条追问都与缺失的 `query` 无关）。
5. **sourceRefs 与工具授权安全性**：Arm A 两条都确定性通过（引用合法、无作废引用、无越权能力调用）；未能测到"缺参调用被 fail-closed 拦截"的场景（模型没发起该调用）。
6. **性能/资源**：Ollama 把模型 100% 卸载到 GPU，显存占用约 3.7–6.0 GB，未观察到 CPU 卸载或 OOM；但加载上下文为 **16384**（非目标 4096），且出现一次 `STATE_UPDATE` 超过 90s 的推理超时（"异常变慢"）。单次 STATE_UPDATE 约 20–25s、DECISION 约 21–35s。

结论：当前失败**不是** Ollama API 不兼容、不是 thinking 内容泄漏、不是 JSON 语法问题，而是 **4B 模型的 Schema/枚举契约稳定性 + 偶发性能超时** 两个问题叠加；TARGET_NOT_PROVEN 属语义问题。

## 请求计数（本地 Ollama 上游推理请求）

| 用途 | 次数 |
|---|---:|
| 冻结 A 臂（`enforced`，完整） | 4 |
| 冻结 B 臂 attempt1（TIMEOUT，1 次即停） | 1 |
| 冻结 B 臂 attempt2（CONNECTION，1 次即停） | 1 |
| 被弃用的 shadow A 臂（模式探测） | 4 |
| 被弃用的 enforced A 臂契约失败尝试 ×2 | 2 |
| 直接 Ollama JSON 形态诊断探测 | 3 |
| dev 实例兼容性探测（`validate`） | 1 |
| **合计** | **16** |

冻结 A/B 自身为 A 4 + B 2 = **6** 次；另有 10 次为模式纠正/契约诊断/兼容性验证，全部在此登记。

## 回归结果

| 套件 | 结果 |
|---|---|
| `agent-brain/tests`（pytest） | 253 passed |
| `tools/tests`（pytest，含 `test_p3b_semantic_ab.py`） | 160 passed |
| `evalBFast` | 134 tests, 0 failures/errors/skipped |
| `testNonLive` | 2,197 tests, 0 failures/errors, 36 skipped（349 suites） |
| `testCrossLanguage`（Java↔Python 门禁，专用 brain） | 2 tests, 0 failures/errors |
| `evalLiveP3B`（本轮 C09 运行） | 1 test（断言只覆盖运行完成，报告完整性由裁判判定） |

- Custom Provider / CompatibilityProbe / Routing Gateway / Model Freeze 等定向测试包含在 `testNonLive` 内，全部通过。
- 评测数据库只用隔离库 `spec_agent_p3b_live_local_20261019`；用户开发库 `spec_agent` 仅经官方配置接口写入 Custom 设置，未被评测清理或迁移。

## 产物

- 冻结协议：[protocol.json](protocol.json)
- A 臂（冻结，`enforced`）：[arm-a.json](arm-a.json)、明细 [arm-a.json.attempts.jsonl](arm-a.json.attempts.jsonl)
- B 臂（不完整）：[arm-b.json](arm-b.json)、明细 [arm-b.json.attempts.jsonl](arm-b.json.attempts.jsonl)
- 冻结裁判输出：[c09-semantic-decision.json](c09-semantic-decision.json)
- dev 实例 Custom 配置的 UI 证据：[ui-custom-provider.png](ui-custom-provider.png)
- 被弃用证据：`arm-a-shadow-complete.json`、`arm-a-enforced-contract-error-1.json`、`arm-a-enforced-contract-error-2.json`

## 未决问题 / 下一轮

- B 臂（c09 候选规则）在本机 `qwen3.5:4b` 上**未取得可评分证据**，不能判断该规则是否改善本地模型的追问质量。
- 即便 B 臂完整，本样本下 A 臂语义为 `UNRESOLVED`，按冻结规则整体仍可能停在 `INCONCLUSIVE`；这不是本地独有的问题。
- 4B 模型在 ~15k 上下文的 Project Agent 契约下稳定性不足。若要继续本地 live 开发，建议改用**非 thinking、参数量更大（如 7B–8B 量化）且显式约束 JSON schema** 的模型，或为本地提供商采用更小的上下文与更保守的采样参数；本轮不擅自下载任何新模型。

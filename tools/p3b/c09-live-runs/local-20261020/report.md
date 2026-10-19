# 本地 Ollama 8K 上下文与结构化决策诊断报告 — 2026-10-20

一轮受控、可复现的本地诊断，**不是**新的 C09 A/B 实验。未修改开发实例的全局 Provider、未重配 Custom API、未改生产 Prompt / Schema / 校验 / 工具授权 / 超时阈值。所有新请求都按 §7 计数，共 **8 次**。

## 结论速览

| 问题 | 结论 |
|---|---|
| 8K 是否真正生效 | **是**，且无截断（`n_ctx_slot=8192`，所有请求 `truncated=0`） |
| `STATE_UPDATE` 为何违规 | 脑侧只强制 `response_format=json_object`（无 schema/枚举约束），枚举限制只存在于 Prompt 文本里；属**模型对无约束 JSON 通道的遵循失败**，不是适配器/截断/Prompt 矛盾 |
| `DECISION` 为何不问 query | **评测输入不可代表**（场景文本全是占位符，`scope=PROJECT` 只是夹具脚本声明）+ 4B 指令遵循弱；不是缺失事实没进 DECISION，也不是 Java/Python 转换错误 |
| 4B 是否达到开发基线 | **未达到**：8K 下 4 次尝试只有 2 次跑完，2 次因 90s 推理超时失败；C09 追问 query 0/2 |
| 是否继续调优 4B | **建议停止调 Prompt/工程**；下一步应换更合适的模型，或先修 live 语料的文本代表性 |

## 一、8K 是否真正生效（§三、§四）

- `ollama --version` = 0.40.6；`ollama ps` 加载后 `CONTEXT = 8192`。
- **决定性证据**：Ollama 服务进程（PID 44712，启动于 `2026-10-20T00:03:01`）自身启动日志记录 `OLLAMA_CONTEXT_LENGTH:8192`；runner 日志对**每一次**请求都记 `n_ctx_slot = 8192`。
- 注意：`HKCU:\Environment\OLLAMA_CONTEXT_LENGTH` 仍为 16384。GUI 的 8K 值作用于**服务进程的环境**（进程日志为准），注册表值陈旧；结论以**实际生效的进程参数 + 每次请求的 `n_ctx_slot`** 为准，而不是以 UI 滑块或注册表为准。
- **Spec Agent 侧不传 `num_ctx`**：`ChatCompletionsProtocolAdapter.buildRequestBody` 只发送 `model` / `messages` / `response_format`（`grep` 确认无 `num_ctx`、无 `temperature`、无 `max_tokens`）。因此上下文长度**完全由 Ollama 服务默认值决定**，8K 对 Custom Gateway 是自动生效的。
- 模型 100% 卸载到 GPU（`PROCESSOR=100% GPU`，显存占用约 3.4–6.0 GB / 8.0 GB），**无 CPU 卸载、无 OOM**。

## 二、两个推理阶段的真实输入规模（§四）

数据来自 Ollama 自身 runner 日志（`prompt eval time` / `eval time` / `stop processing`），是**实测值**，不是估算：

| 阶段 | 输入 tokens（实测） | 输出 tokens（实测） | 实际上下文 | 是否足够 |
|---|---:|---:|---:|---|
| STATE_UPDATE | 1165 / 1173 / 1179 / 1196（本轮 4 次） | 521 / 573（成功）；>3982 / >4310（超时被中断） | 8192（`n_ctx_slot`） | **足够**，`truncated=0` |
| DECISION | 4203 / 4621（本轮 2 次）；4036 / 4270 / 4435 / 4642（上一轮同 Prompt，ctx 无关） | 1106 / 816（本轮）；927–1534（上一轮） | 8192 | **足够**，`truncated=0` |

- 8K 下的最大观测占用：DECISION `4621+816 = 5437`（约 66%）；STATE_UPDATE `1196+521 = 1717`（约 21%）。**未观测到任何一次截断**。
- 风险点：模型偶发的超长生成（上一轮在同一 Prompt 下观测到 3293–5521 输出 tokens）在 8K 下**会**超出剩余空间（如 `4642+5521 > 8192`），而 16K 能容纳。即 8K 足够覆盖正常输出，但对"超长 thinking"形态是边际的。
- 因此**不触发**"真实输入接近/超过 8K 就停止语义可靠性判断"的条件——正常输入远低于 8K。

## 三、`STATE_UPDATE` 契约错误定位（§五）

逐层核对后的结论：**没有发现 Java/Python 适配层的语义转换错误**，问题在"约束强度"与"模型遵循"。

1. **约束只到 JSON Object 为止（代码事实）**。`InternalModelInferenceController.completeAuthorized` 对**所有** brain 调用硬编码 `ModelOutputContract.jsonObject()`，注释即写明"brain 严格按 JSON 解析;强制结构化输出"。`ChatCompletionsProtocolAdapter` 因此只发送 `response_format={"type":"json_object"}`。**没有任何枚举/字段级 schema 约束到达模型**（OpenCode/Kilo 网关虽有 `json_schema` 能力，但 brain 路径不使用）。
2. **Prompt 与 Schema 一致，不存在互相冲突**。Python 侧 `ProposedClaim` 校验 `kind ∈ CLAIM_KINDS`（`goal, stakeholder, scope, constraint, success_criterion, output_expectation, risk, assumption, open_question, conflict, other`）与 `status ∈ CLAIM_STATUSES`（`confirmed, assumed, unresolved, rejected`）；`state_update.py` 的 system prompt 列出的正是同一组 11 个 kind / 4 个 status。→ 上一轮观测到的 `kind="functional_requirement"` 是**模型输出了 prompt 已明确禁止、但仍属合法 JSON 的值**。
3. **不是被截断**：本轮所有请求 `truncated=0`；且被拒的上一轮响应 `finish_reason=stop`（适配器只有在 `stop` 时才返回内容），说明不是 length 截断。
4. **不是 thinking 泄漏**：`ChatCompletionsProtocolAdapter.parseNonStreamResponse` 只读 `choices[0].message.content`（`reasoning_content` / `thinking` 一律不进入 content）；直接探测 3/3 也未出现 `thinking` 字段。
5. **本轮未再观测到 schema 违规**：2 次完成调用的 `STATE_UPDATE` 都通过校验（0/2 违规），失败形态变成了**超时**。

**超时根因（可量化）**：`INFERENCE_TIMEOUT = 90s`。模型约 45 tokens/s，90s 只能容纳约 4000 tokens，而本轮两次失败分别生成到 `n_tokens=5147 / 5489`（约 4000 tokens 的 thinking），刚好越过墙。用完全相同的最小 Prompt 对照：默认 979 output tokens / 32.9s；`"think": false` 时 **438 tokens / 11.0s**。→ **默认 thinking 的冗长是超时的主因**。

**本轮允许并已执行的隔离优化**：`think:false` 在 `/v1/chat/completions` 上被 Ollama 接受且有效（输出减半、耗时降约 3 倍）。但它**未**被用于生产链路：Custom Provider 契约没有通用的 provider 专有参数透传通道，为本地模型硬编码 `think` 会破坏标准 OpenAI 兼容语义——这属于新功能，本轮不做。
未做且明确禁止：放宽枚举、跳过 Pydantic 校验、静默改写决策、降低 sourceRefs 要求、改 ActionEligibility、伪造 JSON、隐藏式重试、抬高超时。

## 四、`DECISION` 为何不索取 query（§六）

逐条回答：

1. **"缺 query"的信息是否真实存在**：能力 schema 层面**存在**——DECISION 输入的 `availableCapabilities[].input_schema` 来自真实快照，`memory.search` 声明 `required: ["query"]`（评测侧 `P3BSafeCapabilityEvidence.schemaFacts` 读的是真实 descriptor）。但 **`scope=PROJECT 已提供` 这个前提在模型输入里并不存在**：它只存在于夹具的**脚本化决策**里（`P3BCorpus.invokeMemorySearch(Map.of("scope","PROJECT"))`，`fixtureProvidedArgumentNames` 正是从该脚本 payload 推导）。
2. **场景的用户可见文本是占位符（决定性）**：`P3BCorpus` 的 P3B 场景全部用 `GraphStep.CreateRootQuestion`（种子渲染），而 `ScenarioRunner` 一律传 `Map.of()` 作为 paraphrases，因此 `GraphStep.renderText` 恒定返回 `[<scenarioId>:<seed>#pN]`。即根问题渲染为 `[P3B-C09:p3b-c09-root#p0]`、回答事件渲染为 `[P3B-C09:answer#p0]`。**主证据**：模型自己的输出里逐字回显了这个占位符——上一轮 shadow 臂 holdout：「您的答案**[P3B-C09:answer#p1]**具体内容是否已补充完整？」；本轮 diag-03：「答案已收到：**[P3B-C09:answer#p0]**」。→ 模型看到的是无意义的合成文本，而不是"我需要检索项目资料"这类真实意图。
3. **STATE_UPDATE 是否保留该信息**：`unresolved_effective_claims=0`、`answered_lineage_entries=1`，未见缺失参数事实被保留。
4. **required arguments 是否正确表示**：**正确**——`available_capabilities` 里 `memory.search` 的 `required_arguments=["query"]` 且 `required_arguments_verified=true`。
5. **是否过分关注无关上下文**：是——但这是"输入语义为空"的必然结果：模型只能抓住 lineage 里的答案占位符，于是问"答案是否已确认/是否接受假设目标"。
6. **是否存在 Prompt 优先级冲突**：存在一个结构性张力——DECISION prompt 规则 21 要求 `REQUEST_USER_INPUT` 仅在缺少"可靠推进**确实必需**"的信息时才 eligible；而当前输入**没有任何证据**表明本次任务需要检索。因此"应当追问 query"的期望与输入所能支持的行为不一致。
7. **是否模型能力不足**：是次要因素——即使 schema 事实在输入里，4B 也没有把"能力可见 + query 必填"转化为"就缺 query 发问"。

**定位**：以**评测输入代表性**为主（占位符场景文本 + 仅脚本声明的 scope 前提），模型指令遵循弱为次；**不是**"缺失事实没进 DECISION"，也**不是** Java/Python 转换错误。**因此不追加任何 C09 专用 Prompt 规则**。正确的下一步是让 live 语料给出真实自然语言（`CreateRootQuestionText`），本轮按范围限制不做。

## 五、本轮受控运行与指标（§七、§八）

固定场景 `P3B-C09`（base 变体），`enforced`，生产默认 Prompt（规则集 `none`），隔离库 `spec_agent_p3b_live_local_20261019`，每次独立实验 ID：

| # | 实验 ID | 请求 | STATE_UPDATE | DECISION | 结果 |
|---|---|---:|---|---|---|
| 0 | 最小结构化探测 | 1 | — | — | 合法 JSON；42 prompt / 979 out / 32.9s（另计 `think:false` 对照 438 out / 11.0s） |
| 1 | c09-local-diag-20261020-01 | 1 | 提示 1165 → >3982 | 未到达 | **TIMEOUT** |
| 2 | c09-local-diag-20261020-02 | 1 | 提示 1179 → >4310 | 未到达 | **TIMEOUT** |
| 3 | c09-local-diag-20261020-03 | 2 | 1196 → 521（10.4s） | 4203 → 1106（23.0s） | 完成；动作 `RESPOND_TO_USER` |
| 4 | c09-local-diag-20261020-04 | 2 | 1173 → 573（11.5s） | 4621 → 816（16.4s） | 完成；动作 `RESPOND_TO_USER` |

| 指标 | 目标 | 实测 |
|---|---|---|
| 8K 生效且不截断 | 是 | **是**（`truncated=0`） |
| 完整推理 | ≤3 次受控验证 | 4 次尝试，其中 **2 次完整**（#3、#4） |
| JSON 语法 | 通过 | **通过**（4/4 返回内容的调用均可解析） |
| STATE_UPDATE Schema | 尽量 3/3 | **2/4**（#3、#4 通过；#1、#2 未产生输出而被超时中断） |
| DECISION 契约 | 尽量 3/3 | **2/2**（到达 DECISION 的全部调用都通过） |
| Provider 超时 | 0 | **2**（未达标，如实记录） |
| C09 正确追问 query | 单独记录 | **0/2**（两次都选 `RESPOND_TO_USER`） |

行为细节：两次完成调用都不在 oracle 可接受集合内（`modelSelectionAccuracy=0.0`），`payload_validity=VALID`、`capability_calls=0`、`eligibility=PASSED`、Run 正常终态 `completed`；源引用审计确定性通过。

请求预算：**8/8**（1 探测 + 1 对照 + 4 次实验中的 6 次推理）。无云端模型、无裁判模型、无模型切换、无新模型下载、未抬高超时。

## 六、本轮实际改动（§九、§十一）

只修两个**确定性工程问题**，各配离线回归：

1. **评测器声明的预算上限**（`tools/p3b/p3b_semantic_ab.py`）：`evaluate_c09_only` 之前把报告里的预算渲染成全局的 64，与冻结本地协议的 8 矛盾。现新增 `max_requests` 参数与 `--max-new-requests`，**同时**用于报告字段与臂可靠性上限（更低的声明预算只会**收紧**完整性门禁）；默认仍为 64 以保持历史行为。已用 `--max-new-requests 8` 重算 `local-20261019/c09-semantic-decision.json`，判定不变（`INCONCLUSIVE` / `INCOMPLETE`），预算字段由 64 纠正为 **8**。
2. **"已停止的尝试不得重跑"守卫**（`LiveAbAttemptStopGuard`）：在任何新请求之前读取同一报告路径上的既往报告，只要记录了任一停止标志（429 / provider failure / retry / unclassified / model contract / budget），就**失败关闭**并给出明确原因。上一轮 B 臂首次 TIMEOUT 之后又被独立重跑一次正是该规则被绕过的实例。已在 `EvalLiveP3BDecisionQualityTest` 起始处接入。**注意**：本轮诊断按 §七"同一场景的多次新运行分别记录实验 ID"使用**各自全新**的报告路径，因此不被该守卫阻断——守卫只禁止对**同一次已停止的尝试**重跑。

未改：生产 Prompt、Schema、校验、ActionEligibility、超时、Provider 契约、开发实例全局 Provider、Custom 配置。

## 七、测试

| 套件 | 结果 |
|---|---|
| `agent-brain/tests`（pytest） | 253 passed |
| `tools/tests`（pytest，含 2 个新增预算回归） | 162 passed |
| `LiveAbAttemptStopGuardTest`（新增，离线） | 6 passed |
| `evalBFast` | 140 tests, 0 failures/errors/skipped |
| `testNonLive`（含 Custom Provider / CompatibilityProbe / Routing / Model Freeze / Brain 契约定向测试） | 2,203 tests, 0 failures/errors, 36 skipped（350 suites） |
| `testCrossLanguage`（专用 brain） | 2 tests, 0 failures/errors |

评测数据库只用隔离库 `spec_agent_p3b_live_local_20261019`；开发库 `spec_agent` 未被读写。

## 八、未决问题

- 本轮未取到 **DECISION 在 8K 下的超时样本**（两次失败都在 `STATE_UPDATE`），DECISION 的 8K 边际风险由"上一轮同 Prompt 超长输出"间接推断。
- `think:false` 的稳定化收益只在**最小 Prompt** 上做过对照（n=1），未在真实链路复现（无透传通道）。
- live 语料的文本代表性缺陷（占位符 + 脚本声明的 scope 前提）本轮只做定位与证据，未修。

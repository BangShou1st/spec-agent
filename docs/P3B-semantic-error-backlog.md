# P3-B 语义错误清单与下一阶段最小 Prompt A/B 方案

状态：**PENDING_HUMAN_REVIEW**（AI 候选标签，尚未人工审核）

本轮不修改 DECISION Prompt，也不修改 18×2 oracle 与 acceptableActions；本文件只做
"从真实证据出发的语义错误归档 + 下一阶段最小 A/B 提议"。

## 证据来源（只读，未被改写）

| 文件 | 提供商 / 模型 | 覆盖场景 |
| --- | --- | --- |
| `tools/p3b/p3b_live_run1_poolside_20261018.json` | Kilo / `poolside/laguna-s-2.1:free` | C03(base, 命中 429) |
| `tools/p3b/p3b_live_run2_cohere_20261018.json` | Kilo / `cohere/north-mini-code:free` | C03、C07、C08 |
| `tools/p3b/p3b_live_run3_c13c15c09_20261018.json` | Kilo / `cohere/north-mini-code:free` | C13、C15、C09 |
| `tools/p3b/p3b_live_run4_c09_20261018.json` | Kilo / `cohere/north-mini-code:free` | C09 |

口径：以上报告是 v1 口径（`execution_success_rate` 未区分 Run 终态与动作执行）。本轮回归到
v2 后，`run_normal_terminal_rate` / `action_submitted_rate` / `action_completed_rate` 三者在
这批真实数据上数值一致（它们的"成功"全部是 `completed`，没有任何 `policy_denied` 或
`awaiting_approval`），因此历史结论不受口径修正影响。

## 语义错误清单

### 1. C03 / holdout — 改写后的重复追问

- 证据：run2 `P3B-C03/holdout`，模型动作 `REQUEST_USER_INPUT`（acceptable = false），
  `repeated_clarification = true`，`unresolved_semantic_error_signal = true`。
- 现象：同一个已被回答的问题被**改写**后再次追问（"您刚才回答的 … 是否是引用，还是希望补充说明？"）。
  逐字重复（C02）已由门禁 `RESOLVED_BLOCKER` 覆盖，但**改写**形式没有确定性判据，模型仍然追问。
- 归类：语义缺口（prompt/模型判断），非门禁缺陷。
- 待人工确认：该 holdout 的 oracle 是否应把"等价改写"也归入"不必要追问"。

### 2. C08 / base — 应调用能力却改为追问

- 证据：run2 `P3B-C08/base`，模型动作 `REQUEST_USER_INPUT`（acceptable 集合要求 `INVOKE_CAPABILITY`，
  且 `REQUEST_USER_INPUT` 是禁止动作），`unresolved_semantic_error_signal = true`。
- 现象：能力可见、声明必填参数齐全（query + scope），模型仍选择追问（"能否提供更详细的答案？"），
  没有调用 `memory.search`。
- 归类：语义缺口（能力必要性判断），非门禁缺陷。门禁侧 C08 的 `GateExpectation.PASS` 与
  "参数齐全必须保持 eligible"不变。

### 3. C09 / base — 必要能力步骤处理不足

- 证据：run4 `P3B-C09/base` 模型动作 `RESPOND_TO_USER`（acceptable 为 `REQUEST_USER_INPUT`），
  门禁 `PASSED`（期望 VETO），`gate_accuracy = 0.0`。
- run3 `P3B-C09/holdout` 模型动作 `REQUEST_USER_INPUT`（acceptable），但门禁同样 `PASSED`（期望 VETO）。
- 现象：缺少 `memory.search` 声明的必填参数 `query` 时，模型没有发出该调用（因此门禁也没有可否决
  的提案），而是直接回应/追问。**runtime 门禁的 fail-closed 判据只在模型真的提出该调用时才生效**，
  模型不提案时这条 evaluate 语义无法被触达。
- 归类：语义缺口（"缺必填参数时应如何推进"未在 prompt 中约束）＋评测需显式标注"未触达 veto 面"。
  门禁实现本身（`MISSING_REQUIRED_CAPABILITY_ARGUMENT`）未被削弱。

### 4. C13 / holdout — 中文质量异常

- 证据：run3 `P3B-C13/holdout`，`REQUEST_USER_INPUT`（acceptable = false），
  `questionText = "说明患由你提历的内容 '[P3B-C13:p3b-c13-answer#p1]' 的义道，你要求说点的悪？"`，
  `purpose = "需要了解患由的用户提历内容以控完步进度."`。
- 现象：输出是**明显损坏的中文**（错别字/乱码式词组），语义不可读。
- 归类：模型输出质量问题（语言层），**不是** JSON/契约失败——payload 是合法 JSON 且通过
  Java fail-closed 校验（`payload_validity = VALID`）。当前评测没有语言质量维度，只能由人工标注。
- 待人工确认：是否需要一条"输出语言可读性"人工标签维度（本轮不新增自动化判据）。

### 5. C08 / holdout 与 C15 / holdout — Brain 失败归因（本轮已修提取口径）

- 证据：run2 `P3B-C08/holdout` 与 run3 `P3B-C15/holdout` 的 `execution_result` 都是
  `failed:AgentBrainUnavailableException: Agent brain call failed: /v1/decisions`，
  `successful_model_requests = 2`、`failed_model_requests = 0`（两次模型请求都成功，
  失败发生在 `/v1/decisions` 的响应校验边界）。
- **归因链已确认存在**：Python `app.py::_fail` → `502 + detail="brain_failure:<ErrorType>"`；
  Java `RemotePythonDecisionEngine.classifyBrainFailureDetail` → `BrainFailureCode`；
  `RunFailureReasons.reasonCode/payload` → 持久化到 `RUN_FAILED.reason`。
- **保留的 live 隔离库无法直接取证**：`spec_agent_p3b_live_20261018` 当前 0 project / 0 run /
  0 event —— `EvalLiveHarnessBase` 在每个用例结束后按 run 删除 `agent_run_events`，因此这批
  C08/C15 的 `RUN_FAILED` 行已被清理。这是**证据留存缺陷**，本轮如实记录（见"遗留限制"）。
- 替代性证据：同机保留的 `spec_agent_p3b_20261017` 库中，**同样 run 类型（`answer_cycle`/`ANSWER_TIP`）**
  的 `RUN_FAILED` 确实写入了类型化机器码（`model_contract_violation`、`model_ungrounded_reference`、
  `model_provider_failure`、`brain_timeout`），证明机器码能穿过边界。
- 结论：报告里那两条 `failed:AgentBrainUnavailableException…` **不含任何类型化机器码**（旧评测把
  泛化异常文案放在了 `RUN_FAILED.reason` 之前），因此按"原因未证实即 UNKNOWN"如实维持 UNKNOWN。
  修复后评测先读 `RUN_FAILED.reason`；下一次 live 运行会得到明确分类
  （该形态最可能是 `MODEL_PROTOCOL_VIOLATION`，但**本轮不据此下断言**）。

## 下一阶段最小 Prompt A/B 方案（本轮不执行真实模型调用）

原则：只改 DECISION 的**指令级约束**，不动 oracle / acceptableActions，不新增工具，不新增模型调用次数
（每个用户任务仍是 STATE_UPDATE + DECISION 两次）。

| # | 假设 | A（对照） | B（唯一变量） | 判据 | 预算 |
| --- | --- | --- | --- | --- | --- |
| 1 | 改写重复追问 | 现状 | 增加一条规则："若当前悬置问题与既往已答问题**语义等价**（含改写），不得再次追问；改为基于既有答案推进" | C03/holdout `unnecessary_clarification` 由 1→0，且 C01/C02 不回归 | ≤ 8 次请求（C03×2 + C01/C02 各 1） |
| 2 | 应调用却追问 | 现状 | 增加一条规则："当目标明确要求检索现有资料、且所需能力可见且声明必填参数齐全时，**优先调用**该能力，不得改为追问" | C08/base 与 C08/holdout 的 `model_selection_acceptable` 由 0→1，且 C07 不回归（不制造不必要调用） | ≤ 8 次请求（C08×2 + C07×2） |
| 3 | 缺必填参数的处理 | 现状 | 增加一条规则："若某个必要调用缺少声明必填参数，不要发出该调用；就**缺失的那一项**向用户追问一次" | C09/base 动作由 `RESPOND_TO_USER`→`REQUEST_USER_INPUT`，且 C09 语义上"未触达 veto 面"的标注消失 | ≤ 6 次请求（C09×2 + 回归 1） |

约束：A/B 各自的场景集与请求预算必须**在跑之前冻结**并写进报告；两组使用同一模型 id 与同一隔离库；
命中 429 / 认证错误立即停止（不重试、不换模型）。人工审核完成前，结论一律标注
`PENDING_HUMAN_REVIEW`，不得宣称达到正式语义质量验收门槛。

## 遗留限制（如实记录）

1. **冻结绑定的进程内 LRU**：容量 4096、访问序淘汰；不能跨进程重启或多实例。同一 Run 跨进程时
   冻结对会丢失（需要在 run 记录上持久化绑定，本轮不做）。
2. **live 证据留存**：`EvalLiveHarnessBase` 的逐用例清理会删掉该用例的 run 事件（含 `RUN_FAILED`）。
   因此"事后从隔离库复查某次 live 失败的机器码"目前不可行；本轮以保留的报告 JSON + 同机
   `spec_agent_p3b_20261017` 库作为替代证据。
3. **语言质量维度缺失**：C13 一类的中文损坏只能人工标注，尚无自动化判据（本轮不新增）。

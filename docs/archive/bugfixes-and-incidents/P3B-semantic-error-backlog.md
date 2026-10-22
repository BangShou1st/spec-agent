> [!NOTE]
> **Document Status**: `Historical` (历史归档)\
> **Authority**: 本文档为历史阶段性记录，仅供追溯与复盘使用。当前系统的实际行为与规约以 [docs/README.md](../../README.md) 及 [docs/v2/README.md](../../v2/README.md) 为准。

---

# P3-B 语义错误清单、最小 Prompt A/B 方案与执行证据

状态：**PENDING_HUMAN_REVIEW**（AI 候选标签，尚未人工审核）

本文件先归档截至 2026-10-18 的真实语义错误与原始 A/B 提议；2026-10-19 的执行结果见下方。
默认生产 DECISION Prompt 未推广候选规则；18×2 oracle 与 acceptableActions 始终保持冻结。

## 2026-10-19 自主 A/B 执行补充

以下记录覆盖上面“本轮不执行真实模型调用”的历史计划。原始场景标签和 oracle 仍保持
`PENDING_HUMAN_REVIEW`，未改写、未据模型输出调标签。预注册方案及完整机器报告：
`tools/p3b/p3b_ab_protocol_20261019.json`、
`tools/p3b/p3b_semantic_ab_report_20261019.json`；逐臂报告与请求级脱敏证据位于
`tools/p3b/ab_runs/20261019/`。

- 固定模型：Kilo `cohere/north-mini-code:free`；同一隔离库、运行时、场景事实和资格门禁；
  每个场景只执行一次，每次两个模型请求；没有 judge、重试或 fallback。
- C03：A/B 各 16 次请求。fresh A 的 C03/holdout 是 `RESPOND_TO_USER`，B 变成
  `REQUEST_USER_INPUT`；因此按预注册行为判据 **REJECT**。C03/base 两臂均回应。
  A 的 C02/base 与 B 的 C01/base 各有一个模型契约失败，计为未测动作。
- C08：A 有 8 次成功请求，C08/base 和 holdout 都选择追问；C07/holdout 有一个模型契约失败。
  B 在 C08/base 遇到模型契约失败，C08/holdout 遇到 502 提供商失败，随即触发停止规则，
  只完成 4 次请求，C07 的 B 变体未运行。故 C08 **INCONCLUSIVE**，不能据此声称改善。
- C09：未启动 live A/B；C08 B 的提供商失败触发了预注册停止条件，故 **INCONCLUSIVE**。
- 合计 44/64 次新上游请求：43 成功、1 失败；0 次 429、0 次重试、0 次裁判请求。
  另有 4 个 `MODEL_PROTOCOL_VIOLATION` 尝试（它们的模型请求本身成功），以及 5 条
  已持久化的 `RUN_FAILED` 机器码证据；失败机器码摘要见总报告。
- 自动复核只断言结构、资格门禁、参数声明、来源引用和实际执行等可验证事实。
  C03 的语义等价判断与无依据事实断言仍是 `UNRESOLVED`；没有独立裁判，也没有把
  `PENDING_HUMAN_REVIEW` 改成已审核。
- 没有规则达到 `ACCEPT`，默认生产 Prompt 维持原样；C03 候选规则被拒绝，C08/C09 候选规则
  不推广。C01/C02 的问题未被证明消除；已完成的 C07 变体没有发生能力调用，C08 B 的 C07
  未测。结果只代表这些单次 live 样本，不代表稳定语义质量。
- 回归结果：Brain pytest `249 passed`；`tools/tests` `140 passed`；`evalBFast` `133 passed`；
  `testNonLive` `2,154 passed / 36 skipped / 0 failed`。四次 live arm 调用的 harness 均正常结束，
  但这不改变 C08/C09 的 A/B 结论为 `INCONCLUSIVE`。

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
2026-10-19 A/B 产生的新 `RUN_FAILED` 证据已单独保存于本文件上方所列报告；它不恢复或替代
旧 C08/C15 run 的错误机器码，旧样本仍维持 `UNKNOWN`。

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

1. **冻结绑定 LRU 限制（已关闭）**：4096 项访问序 LRU 仅作热缓存；完整运行配置的 SHA-256
   指纹已通过原子条件更新持久化在 `agent_runs.model_binding_fingerprint`。并发首绑只有一个配置能成功，
   活跃 Run 被淘汰后或服务重启/切换实例后，数据库仍拒绝不同指纹重绑。迁移前已进入执行阶段的 Run
   无法恢复旧进程内指纹，因此 V99 为它们写入不可匹配标记并失败关闭；新 Run 正常绑定。数据库不保存明文凭据。
2. **live 证据留存**：`EvalLiveHarnessBase` 的逐用例清理会删掉该用例的 run 事件（含 `RUN_FAILED`）。
   因此"事后从隔离库复查某次 live 失败的机器码"目前不可行；本轮以保留的报告 JSON + 同机
   `spec_agent_p3b_20261017` 库作为替代证据。
3. **语言质量维度缺失**：C13 一类的中文损坏只能人工标注，尚无自动化判据（本轮不新增）。

## 2026-10-19 P3-B 自动语义验收与契约诊断

### C09 正向路径与证据边界

根因已在实际评分链确认：`_assess_c09_attempt()` 原先只确认冻结 oracle 允许的动作和追问结构；对动作正确、结构合法的追问，语义项仍固定为 `UNRESOLVED`。`_decide_c09()` 又要求所有样本没有未决项，因此没有任何完整样本能进入 `ACCEPT`。Java 导出的 `memory_search_missing_arguments=["query"]` 只能证明 fixture/schema 状态，不能证明模型的问题真的索取了缺失信息。

本轮增加零模型调用的盲规则评判器 `p3b-c09-rule-judge.v1`。输入版本为 `c09-clarification-input.v1`，输出版本为 `c09-semantic-review.v1`；只读取已脱敏的冻结能力事实与追问文本，不读取 A/B 标签、候选动作或 oracle 结论。它仅在文本明确请求检索词/搜索内容等缺失 query 信息时给出 `PASS`；明确追问已知 scope 或无关信息时给出 `INCORRECT`；泛问、只提到 query、询问定义/格式、schema/文本/摘要缺失或格式无效时保持 `UNRESOLVED`。每条结果记录评判器 ID、输入/输出版本、输入摘要、判定码、布尔证据及 `model_calls=0`。

正向样例“请问需要检索哪些关键词？范围按当前项目即可。”在 Java 冻结事实导出后，经 Python 评判器可达 `STRUCTURE=PASS`、`SEMANTIC=PASS`；它只证明该窄判据命中，不声称证明模型的内部理解或规则普遍有效。完整 C09 A/B 仍须具备 fresh A/B、base/holdout、相同输入事实及 prompt/scenario 完整性；B 两个变体都须正确且不调用能力，A 至少有一个明确错误，任何未决项或 fail-closed 证据不完整均为 `INCONCLUSIVE`。结构合法不能单独通过语义验收。

### 契约失败诊断链

- Python Brain 在 JSON 解析与 `ModelDecisionOutput` schema 校验处生成稳定诊断 ID、阶段、validator、白名单错误码、脱敏字段路径及预期/实际类型。日志和 HTTP 错误不含异常文本、模型输出或字段值；旧 `detail="brain_failure:<Type>"` 形状保留，诊断放在独立的 `contractDiagnostic` 字段，缺失时明确报告 `UNKNOWN`。
- Java `AgentContracts.read()` 区分 wire JSON/未知字段/类型错误；`AgentBrainResponseValidator` 记录响应字段校验失败。只有来自这些可验证边界的错误才变为 `ModelResponseContractException`；普通 `AgentContractException` 不一概归因为模型契约错误。未识别的 HTTP 502、未知 Brain 类型、缺少/不兼容错误码继续保持未知或 unavailable。
- 经确认的诊断由 `RemotePythonDecisionEngine` 关联到同一个 `diagnosticId`，进入 `RUN_FAILED` payload；语义评测导出器另存 event ID、diagnostic ID、machine code、stage、validator、字段路径、类型、边界和是否进入 Java wire validator/executor。历史报告里原本缺失机器码的失败仍为 UNKNOWN，不回填推测分类。

未改 ActionEligibility、工具授权、DECISION 生产 Prompt、迁移、oracle、冻结协议或历史报告。

### 当前真实模型验证决策

本轮没有发送模型请求。仓库冻结 live 协议仍登记 Kilo / `cohere/north-mini-code:free`，与优先目标不同；本机没有 OpenCode eval 模型/凭据环境变量，Ollama 仅有 embedding 模型。生产 OpenCode runtime 从数据库设置取模型；live OpenCode eval 则要求显式 `SPEC_AGENT_EVAL_OPENCODE_MODEL`，付费档另需 `SPEC_AGENT_EVAL_OPENCODE_KEY`，不会回退读取数据库设置。当前无法安全确认本地数据库中活动模型/提供商或凭据来源，因此不能证明目标路由已配置可达，也不能把历史 502 当作本轮可达性证据。

OpenCode 的只读模型目录 API 本轮可达，共返回 85 个模型并包含下方所列 ID；这只确认目录/模型元数据可访问，未发起 Chat Completions，不能证明推理服务可达。

现有 Kilo 路径也已核实：固定 OpenAI-compatible Chat Completions 网关 `https://ai-gateway.kilo.ai/api/v1`；免费档匿名调用，付费凭据按组织加密保存在 `kilo_settings`。本轮未读取或修改该用户设置，不能确认活动组织行或实际凭据状态；没有因 OpenCode 路由不明而切换到 Kilo。

OpenCode 当前文档列出的对应免费模型 ID 是 `deepseek-v4.1-flash-free`，协议为 OpenAI Chat Completions；用户口头别名 `dsv4.1free` 与本地活动设置的对应关系仍待验证（[模型目录](https://dev.opencode.ai/v2/docs/console/models)、[Inference API](https://dev.opencode.ai/v2/docs/console/api/inference)）。虽然离线正反例门槛已通过，当前仍不建议花费真实请求：活动路由与认证未证实，且既有预注册协议的模型是 Kilo/Cohere。

若后续单独冻结一个 DSV4.1 C09 预注册方案，建议仅做 C09 的 fresh A/B：A 为当前 DECISION prompt，B 只增加“缺少必填参数时不得调用能力，并只追问缺失项”的单条候选指令；同一模型、参数、runtime、隔离库和场景事实，各自跑 base/holdout 一次。每次任务固定 STATE_UPDATE + DECISION，因此上限为 `2 arms × 2 variants × 2 upstream requests = 8`；评判器零模型调用。运行前冻结 prompt/scenario/input/评分代码摘要与停止条件，逐次保留脱敏机器证据及原始机器可读报告；任一失败也计入 8 次预算，遇 429、认证、提供商、重试或未分类错误立即停止，不重试、不换模型、不切换提供商。该方案是待验证建议，不修改原有 Kilo 协议，也不是本轮 live 运行授权。

### 本轮离线验证

| 命令 | 结果 |
| --- | --- |
| `uv run --project agent-brain --extra dev python -m pytest tools/tests/test_p3b_semantic_ab.py -q` | 21 passed |
| `uv run --project agent-brain --extra dev python -m pytest tools/tests -q` | 156 passed |
| `uv run --project agent-brain --extra dev python -m pytest agent-brain/tests -q` | 253 passed |
| `backend/gradlew.bat evalBFast testNonLive --rerun-tasks` | 134 / 0 failures；2197 / 0 failures / 0 errors / 36 skipped |

Java 定向诊断、持久化与 Java→Python C09 跨语言测试也已单独重跑，详见本轮提交测试报告与 Gradle XML；以上 Gradle 测试都指向独立 tmpfs `spec_agent_test`，未清理或修改开发数据库。

> [!NOTE]
> **Document Status**: `Historical` (历史归档)\
> **Authority**: 本文档为历史阶段性记录，仅供追溯与复盘使用。当前系统的实际行为与规约以 [docs/README.md](../../README.md) 及 [docs/v2/README.md](../../v2/README.md) 为准。

---

# C09 语义边界修正与 4B 本地单臂验证报告 — 2026-10-20

本轮只做两件事：**(1)** 让 C09 的测试题与正确答案在语义上站得住脚；**(2)** 用 **4 次**本地 Ollama 推理判断 `qwen3.5:4b` 是否值得继续承担 Spec Agent 的结构化决策任务。

- 这是**单模型、单 Prompt 的场景有效性验证**，不是 A/B：`is_ab_experiment=false`，未产出任何 A/B `ACCEPT`。
- 未修改生产 Prompt / Schema / 校验 / ActionEligibility / 超时 / 全局 Provider / Custom 配置 / Ollama 全局 8K 设置；未启用 fallback、未自动切换模型、未下载新模型、未调用裁判模型。
- 真实本地推理请求数：**4/4**（失败与超时也计数）。

---

## 1. 宽泛检索为什么不一定缺少 query？

`memory.search` 的 `query` 在 schema 里是**必填参数**，但"参数必填"约束的是**调用形态**，不约束**用户必须逐字说出来**。

对"帮我查一下当前项目里过去的相关资料和讨论记录，检索范围就限定在当前项目"这种任务：

- 用户已经给出了**宽泛但有意义、且可执行**的检索目标（"过去的资料和讨论记录"）；
- Agent 完全可以把该意图转换成 `memory.search(scope=PROJECT, query="项目历史资料和讨论记录")` 并安全执行；
- 其中的检索词逐字来自用户可见目标（`项目` / `资料` / `讨论记录`），**不是捏造**的具体主题。

因此，把 `REQUEST_USER_INPUT` 硬性规定为唯一合法动作是**语义越界**——它把"用户没说关键词"错误地等同于"query 必然缺失"。

**这不影响安全门禁**：门禁管的是"调用形态是否合规"（缺 required 参数 → fail-closed VETO）。"用户没说关键词"与"提案缺 query 参数"是两件不同的事。

> 因此本轮新增场景 A `P3B-C09-BROAD`：同一道宽泛检索题，但 oracle 同时接受"派生 query 的 `memory.search` 调用"与"追问"，只有"既不检索也不追问"（`WAIT` / `RESPOND_TO_USER`）才是错的。离线回归 `P3BC09NaturalLanguageScenarioTest#broadRetrievalGoalIsNotInherentlyMissingRequiredQuery` 证明该派生调用 **gate=PASSED**、**不含 `MISSING_REQUIRED_CAPABILITY_ARGUMENT`**、且**真正执行落库**。

---

## 2. 新增的"特定对象未知"场景具体是什么？

`P3B-C09-AMBIG`（version 2，hash `6e2484e0a7547e2c`），两个语义等价、措辞不同的变体：

| 变体 | 可见根问题 | 可见用户回答 |
|---|---|---|
| base | 帮我从当前项目的历史讨论中找出与那项具体问题有关的记录。只查当前项目，不需要其他话题。 | 对，范围就是当前项目。具体是哪项问题，我还没告诉你。 |
| holdout | 我想找本项目过去针对某一件事的相关资料，只在这个项目里查，不用展示所有历史资料。 | 没错，只看本项目。至于具体是哪件事，我现在还没有说明。 |

它满足 6 条冻结事实：

1. 需要查找**某一个特定对象**，不是浏览所有资料（base「找出与那项具体问题有关的记录」/ holdout「针对某一件事的相关资料」，并显式排除穷举）；
2. 项目范围已确定（base「只查当前项目」/ holdout「只在这个项目里查」）；
3. 特定对象的身份**尚未给出**——而且由**可见回答**显式承认（base「具体是哪项问题，我还没告诉你」/ holdout「至于具体是哪件事，我现在还没有说明」）；
4. 其他上下文没有足以确定该对象的事实（夹具无资源、无额外节点、唯一能力是只读诱饵、脚本化决策不是能力调用）；
5. 不存在可从当前意图**可靠推导且具有区分力**的检索词（区别于场景 A：那里可以派生出"项目历史资料和讨论记录"）；
6. 不允许假装已知具体对象，也不允许用泛泛检索代替目标明确的查找。

正确下一步是 `REQUEST_USER_INPUT`：**询问用户究竟要查找哪件事、哪个问题或哪方面的具体记录**。

---

## 3. 模型实际看到哪些内容？有无隐藏线索泄漏？

**模型真正看到的是真实中文**（不再是 `[P3B-C09:p3b-c09-root#p0]` 这类占位符）：

- 根问题与用户回答来自 `GraphStep.CreateRootQuestionText` / `UserEvent.AnswerTipText`，按变体的 `paraphraseIndex` 各自选一条真实文本；
- 能力目录里是**真实**的 `memory.search`（由运行时注册），其 `input_schema` 声明 `query` 必填、`scope` 枚举 `ROUTE|PROJECT|RESOURCE`。

本次 live 报告的 `input_evidence` 逐变体为准（两次相同）：

```
memory_search_visible            = true
memory_search_query_required      = true
required_arguments_verified       = true
memory_search_provided_argument_names = ["scope"]      ← 来自可见任务文本里"只查当前项目"
memory_search_missing_arguments    = ["query"]
available_capabilities[0].required_arguments = ["query"]
answered_lineage_entries = 1，unresolved_effective_claims = 1
allowed_source_ref_count = 9，retrieved_context_count = 2
```

**隐藏线索泄漏检查（离线负向断言，全部通过）**：

- `given.initialGraph()` 只有 1 个 `CreateRootQuestionText`，`given.resources()` 为空——没有可"预置"检索对象的节点或资源；
- 唯一声明的能力是 `eval.decoy.read-only`，**不是** `memory.search`：对象不可能来自夹具能力；
- 脚本化决策是 `REQUEST_USER_INPUT`（不是能力调用），其文案与 claims 都不含任何具体对象候选词；
- 可见根问题 + 回答 + 夹具文案都不含 `AMBIG_LEAK_CANARY_TOKENS`（登录/支付/权限/性能/预算/部署/缓存/导出/审核/revision/rollback/schema/feature flag 等）中的任何一个。

`scope=PROJECT` 来自**真实可见事实**（可见文本"只查当前项目"），并由场景显式声明 `modelVisibleProvidedArguments=["scope"]`——**不是**夹具里从未执行的脚本化工具调用（旧 C09 的缺陷正是后者）。

---

## 4. 原 C09-NL 与新场景如何隔离？

- `P3B-C09-NL` **一字未改**（题面、oracle、given/expect、变体、哈希全部原样），继续作为历史证据；离线测试 `historicalNaturalLanguageScenarioAndOracleRemainFrozen` 锁定其哈希。
- 三道题各有**独立 id / 版本 / 内容哈希**，且两两不同：

| 场景 | id | version | 内容哈希 |
|---|---|---|---|
| 历史种子题 | `P3B-C09` | 1 | `dd7bff1a6c77fb51` |
| 历史自然语言题 | `P3B-C09-NL` | 2 | `b9b2bcb0d0abb417` |
| 场景 A（宽泛可执行） | `P3B-C09-BROAD` | 2 | `ae34865dd18c51ed` |
| 场景 B（对象未知） | `P3B-C09-AMBIG` | 2 | `6e2484e0a7547e2c` |

- 结果目录隔离：本轮写入全新的 `tools/p3b/c09-live-runs/ambig-20261020/`；历史的 `local-20261019/`、`local-20261020/`、`20261019-01/` 只读未动。
- 报告路径隔离：`SPEC_AGENT_EVAL_P3B_REPORT_PATH` 指向全新路径，`LiveAbAttemptStopGuard` 因此不会被历史停止记录阻断，也**不会**复用历史臂。
- 场景集隔离：默认场景集不变，`P3B-C09-AMBIG` 只在 `SPEC_AGENT_EVAL_P3B_SCENARIOS` 显式列出时才被选中；`P3B-C09-BROAD` 本轮**不发**任何模型请求。
- 配对隔离：本轮只有**一个臂**，不做 A/B；即使有人误把不同 scenario id 的两臂喂给既有评估器，它也会 fail-closed 记 `INCONCLUSIVE`（"cross-scenario arms ... are never paired"）。

### 原 C09-NL 冻结 oracle 的评测局限（如实报告，不篡改）

`P3B-C09-NL` 的 frozen oracle 只承认 `REQUEST_USER_INPUT`，而它的可见题面其实是**宽泛但可执行**的检索目标。因此：

- 该 oracle **不适用于宽泛检索语义**：一个合理的、带派生 query 的 `memory.search` 调用会被判为超出可接受集合；
- 该局限**不影响**它作为"占位符修复"历史证据的价值，也**不**被本轮修正——历史 oracle、历史哈希、历史报告一律只读保留；
- 修正以**新场景**（`P3B-C09-BROAD`）承载，而不是原地修改。

---

## 5. 离线门禁测试结果（真实推理前全部通过）

| 套件 | 结果 |
|---|---|
| `P3BC09NaturalLanguageScenarioTest`（含新增场景 A/B、哈希冻结、泄漏负向、派生 query 回归） | **20 tests, 0 failures** |
| `P3BC09AmbiguousGateEnforcementTest`（enforced 档位，独立确定性门禁构造） | **2 tests, 0 failures** |
| `tools/tests`（pytest） | **165 passed** |
| `agent-brain/tests`（pytest） | **253 passed** |
| `evalBFast` | **29 suites / 162 tests，0 failures / 0 errors / 0 skipped** |
| `testNonLive` | **352 suites / 2225 tests，0 failures / 0 errors，36 skipped** |
| `testCrossLanguage` | **2 tests，0 failures** |

逐条覆盖（对应任务书 §五）：

- 两类任务的语义事实验证 → `capabilityEvidenceIsBackedByVisibleFactsAndRealSchema` / `broadScenarioAcceptsBothDerivedQueryCallAndClarification` / `ambiguousVisibleTextCarriesTargetScopeAndUnknownObject`
- 新场景无占位符验证 → `broadAndAmbiguousVisibleTextContainNoSeedPlaceholders`
- base/holdout 语义等价验证 → `broadBaseAndHoldoutAreSemanticallyEquivalentButDifferInWording` / `ambiguousBaseAndHoldoutAreSemanticallyEquivalentButDifferInWording`
- 缺失对象确实无法从任务中推导 → `ambiguousScenarioExposesNoHiddenClueThatIdentifiesTheObject` / `ambiguousTaskDiffersSemanticallyFromTheBroadTask`
- **宽泛检索目标不是天然缺参**的回归 → `broadRetrievalGoalIsNotInherentlyMissingRequiredQuery`（gate=PASSED、无缺参原因码、调用落库）/ `broadDerivedQueryIsGroundedInTheUserGoalNotFabricated`
- `memory.search` 实际 schema 验证 → 读真实 `MemorySearchCapability.descriptor()`，判定 `query` 确为必填、`provided=["scope"]`、`missing=["query"]`
- **正确追问**与**错误缺参工具调用**的独立门禁测试 → `ambiguousCorrectClarificationIsEligibleWithoutAnyVeto`（PASS、无 veto 原因码）对 `missingRequiredArgumentCapabilityCallIsFailClosedSeparately` + enforced 档 `missingQueryInvocationOnAmbiguousTaskIsVetoedBeforeAnyEffect`（VETO + `MISSING_REQUIRED_CAPABILITY_ARGUMENT` + 未进 POLICY + 零落库 + 不执行）
- 新旧场景哈希与报告隔离 → `allNaturalLanguageC09ScenariosHaveFrozenDistinctHashes` / `historicalC09CorpusRemainsUntouchedByTheNewScenarios`
- 历史 C09 / C09-NL / 旧 oracle 与冻结评分兼容回归 → `historicalC09ScenarioAndOracleRemainUnchanged` / `historicalNaturalLanguageScenarioAndOracleRemainFrozen`（连同 `P3BDecisionQualityTest` 的 18×2 冻结基线一并全绿）

门禁结论：`ActionEligibility` 对 `memory.search(scope=PROJECT)`（缺必填 `query`）返回 **VETOED**，原因码含 **`MISSING_REQUIRED_CAPABILITY_ARGUMENT`**，且该否决发生在 policy/executor 之前、能力**永不执行**。这一条由**构造出的坏动作**证明，**没有**要求模型在真实任务中故意输出错误工具调用。

---

## 6. 真实本地推理的实际请求数（上限 4）

**4 次**，全部成功，0 失败、0 重试、0 超时、0 裁判调用。

- 首次请求未出现超时/连接失败/契约失败，因此未触发"立即停止、不发后续请求"的规则；
- 每个变体只执行一次，未重试；
- 逐变体 2 次（`STATE_UPDATE` + `DECISION`）× 2 变体 = 4。

---

## 7. 每个变体的 STATE_UPDATE / DECISION 结果

| 变体 | STATE_UPDATE | DECISION | 结果 |
|---|---|---|---|
| base | prompt ≈1214 tok → 输出 582 tok，实测 9.23s（链路计 15.17s） | prompt ≈4937 tok → 输出 698 tok，11.45s（链路计 13.09s） | 完成，动作 `REQUEST_USER_INPUT` |
| holdout | prompt ≈1022 tok → 输出 **3754 tok**，实测 **59.81s**（链路计 60.33s） | prompt ≈4966 tok → 输出 881 tok，14.30s（链路计 16.03s） | 完成，动作 `REQUEST_USER_INPUT` |

读取口径：两列耗时分别来自 Ollama runner 日志的 `prompt eval time` / `eval time`，以及评测链路 `stage_latency_ms`。四次请求 `truncated=0`。

两次 `STATE_UPDATE` 都产出了通过 Python 侧 schema 校验的结构化声明（`failure_classification=NONE`、`run_failed_evidence.present=false`、`unresolved_effective_claims=1`）；两次 `DECISION` 都产出了通过 Java fail-closed 契约校验的动作（`payload_validity=VALID`）。

---

## 8. 两次 DECISION 是否准确追问缺失的具体对象？

**是，2/2。** 逐条对照 §2 的冻结事实（按**实际语义**判定，非固定字符串）：

| 变体 | 模型原始追问文本 | 是否问"哪件具体事" | 是否复述已知 scope | 是否问无关话题 | 是否假装已知 |
|---|---|---|---|---|---|
| base | 请问您当前需要处理具体哪项问题？ | ✅ | ❌ 未复述 | ❌ 未问 | ❌ 未假装 |
| holdout | 您在本项目中想查找资料的具体是哪一件事或项目主题呢？ | ✅ | ❌ 未复述 | ❌ 未问 | ❌ 未假装 |

- base 的 `purpose` 字段："明确需检索的问题内容以便精准搜索历史讨论记录"；
- holdout 的 `purpose` 字段："为确定后续资料检索的精确目标，需明确用户所关注的特定事件或主题名称"；
- 两次都 **0 次能力调用**（未用泛泛检索代替目标明确的查找）。

**旁证与边界**：既有裁判 `p3b-c09-rule-judge.v1` 的规则面向"就缺失的检索词/关键词发问"，对"问哪一件具体事"这类语义返回 `UNRESOLVED`（`TARGET_NOT_PROVEN`）——它**没有**判定为 `INCORRECT`。本轮**不修改**该裁判（评分规则保持原样），只把它作为旁证记录并标注其适用边界；上表的人工语义核对是本轮对该问题的直接回答。

---

## 9. JSON、Schema、sourceRefs、Eligibility 与执行结果

| 检查项 | base | holdout |
|---|---|---|
| `DECISION_OUTPUT` JSON 可解析 | 是 | 是 |
| `payload_validity` | `VALID` | `VALID` |
| `STATE_UPDATE` 契约（Python schema/枚举） | 通过 | 通过 |
| `source_reference_audit.source_refs_allowed` | `true` | `true` |
| `source_ref_count` / `anchor_ref_count` | 1 / 0 | 1 / 0 |
| `invalidated_source_reference_used` | `false` | `false` |
| `eligibility_outcome` / 原因码 | `PASSED` / 空 | `PASSED` / 空 |
| `accepted_action` / `executed_action` | `REQUEST_USER_INPUT` / `REQUEST_USER_INPUT` | 同左 |
| `execution_state` | `completed` | `completed` |
| `capability_calls` / `provider_retries` / `judge_model_calls` | 0 / 0 / 0 | 0 / 0 / 0 |
| `failure_classification` | `NONE` | `NONE` |
| `candidate_action_match`（对冻结候选标签，只比对不修改） | `true` | `true` |
| `model_selection_accuracy`（原始模型选择，非"通过门禁的动作"） | 1.0 | 1.0 |

汇总：`report_status=COMPLETE`，`gate_accuracy=1.0`，`payload_validity=1.0`，`eligibility_veto_count=0`，`eligibility_unknown_count=0`，`action_completed_rate=1.0`，`run_normal_terminal_rate=1.0`，`python_state_update_delta=2`、`python_decision_delta=2`（Python 侧记账与 2 次尝试一致），任何停止标志均为 `false`。

---

## 10. 耗时、超时、上下文与 GPU 运行情况

| 项 | 观测 |
|---|---|
| 超时 | **0**（`INFERENCE_TIMEOUT=90s` 未触发；最慢单次推理 59.81s，最慢整体尝试 77.05s） |
| 上下文 | `n_ctx_slot = 8192`，与"当前实际生效的 8192"一致；Spec Agent 侧不传 `num_ctx`；四次请求 `truncated = 0` |
| 上下文占用峰值 | DECISION holdout `4966 + 881 = 5847`（约 71%）；STATE_UPDATE holdout `1022 + 3754 = 4776`（约 58%） |
| GPU | **100% 卸载**：`offloaded 33/33 layers to GPU`，`CUDA0 model buffer 2515.44 MiB` + `CUDA_Host 497.31 MiB`（无 CPU 卸载、无 OOM） |
| 吞吐 | 生成约 60–63 tok/s；prompt 约 1.7k–3.3k tok/s |
| 环境改动 | 未修改 Ollama 全局 8K 设置；未重启/停止用户 Ollama |

**风险点（如实记录）**：holdout 的 `STATE_UPDATE` 输出了 **3754 tokens / 59.81s**，占满 90s 墙的约 **67%**，而 base 的同阶段只有 **582 tokens / 9.23s**。`STATE_UPDATE` 的**生成长度波动极大**（相差 6.5 倍），这是本轮唯一逼近冻结停止条件的形态——它这次没有触发，但余量有限。

---

## 11. 是否应该继续用 `qwen3.5:4b`？

**结论：本轮不再是"不行"，而是"可用但边际"。**

支持保留的证据（本轮）：

- 输入修正后，**2/2 变体都给出了正确的语义追问**，0 能力调用、0 假装已知、0 复述已知 scope、0 无关提问；
- 4/4 请求全部成功；`STATE_UPDATE` schema 与 `DECISION` fail-closed 契约**全通过**；sourceRefs 审计确定性通过；门禁与执行全绿；
- 相比上一轮（8K 下 2 次超时、C09 追问 0/2），这是明确的改善，且**唯一变量**是题目文本代表性与 oracle 语义边界。

不支持把它当稳定主力的证据：

- **样本量只有 n=1/变体**。前四轮手动短上下文测试已记录过波动（Thinking 开：1 次长时间无答案 + 1 次正确；Thinking 关：1 次错误工具调用 + 1 次正确）。一次"2/2 正确"不能建立稳定性；
- **长生成 / 超时风险真实存在**：同一次运行里 `STATE_UPDATE` 从 582 到 3754 tokens，最慢 59.81s / 90s 墙。8K 上下文对"超长输出"形态是**边际**的（`4966+3754` 之类组合会接近 8192）；
- 4B 的指令遵循仍属"需要 Prompt 明确约束才稳定"的一档。

**建议**：把 `qwen3.5:4b` 保留为**低成本、固定题面的场景有效性冒烟基线**（用于验证题面/门禁/链路是否正常），**不建议**将其作为结构化决策的稳定主力；继续在 7B 档做对照，以降低长生成与超时风险。

---

## 12. 若换模型，优先候选及理由（不自动下载）

**优先候选：同一 Qwen 系列的 7B 档指令模型（例如 `qwen3.5` 家族 7B 量级，或 `Qwen3-8B` 指令版）。**

理由：

1. **同族tokenizer/prompt 形态迁移成本最低**：现有 `STATE_UPDATE` / `DECISION` 生产 Prompt、枚举与 fail-closed 契约不需要为新模型重做，可保持"单一变量"对照；
2. **直接对冲本轮观测到的风险**：4B 在 `STATE_UPDATE` 上出现了 3754-token 的长生成（59.81s / 90s）。7B 档在同等上下文下通常更少"兜圈"且 JSON 遵循更强，能显著降低逼近 90s 墙的概率；
3. **任务性质匹配**：结构化决策 + 严格 JSON/枚举 + 必须"知道自己缺什么信息"这类要求，落在 4B 的边际能力区间，7B 档是当前最合理的下一档。

**执行纪律**：本轮**不得**自动下载任何新模型，也不得改动 Ollama 全局设置或 Spec Agent 全局 Provider。下一阶段启动前，先离线查询模型目录确认**实际可用的 tag**（避免猜测 tag 名称），再由用户批准后拉取，并沿用本轮的隔离库 + broker 链路 + 请求预算纪律。

---

## 13. 最新状态、测试结果与报告路径

- 分支：`dev`（未改 `main`，未 force push，未丢弃任何未提交修改）。
- 开始核实状态：`dev = 2e8ab6f4ef224294c98e7af552e30fa200088edd`，`main = 74b9815d69a562d6782c4efbef1b7daf3f075905`（与任务书参考一致；工作区仅有未跟踪的 `agent-brain/uv.lock`）。
- 本轮在 `dev` 上的提交：`2ddf823`（C09 场景拆分为"宽泛可执行"与"对象未知"两类 + 门禁）、`4de9ff8`（本报告与真实推理证据）。推送后 `dev` HEAD 见 `git log`（仅推送 `origin/dev`，未触碰 `main`）。
- 离线测试：`P3BC09NaturalLanguageScenarioTest` 20/20、`P3BC09AmbiguousGateEnforcementTest` 2/2、`tools/tests` 165、`agent-brain/tests` 253、`evalBFast` 162、`testNonLive` 2225（36 skipped）、`testCrossLanguage` 2 —— 全部 0 失败。
- 真实推理证据：[single-arm.json](computer://e:\project\spec-agent\tools\p3b\c09-live-runs\ambig-20261020\single-arm.json)（报告）与 `single-arm.json.attempts.jsonl`（逐尝试 sidecar）。
- 冻结协议：[p3b_c09ambig_local_protocol.v1.json](computer://e:\project\spec-agent\tools\p3b\p3b_c09ambig_local_protocol.v1.json)。
- 隔离评测库：`spec_agent_p3b_live_c09fix_20261020`（新建、全新迁移）；开发库 `spec_agent` 未被读写。

---

## 附：本轮实际改动

| 文件 | 改动 |
|---|---|
| [P3BC09NaturalLanguageScenarios.java](file:///e:/project/spec-agent/backend/src/test/java/com/specagent/eval/P3BC09NaturalLanguageScenarios.java) | 新增场景 A `P3B-C09-BROAD`（派生 query 与追问同等合法）与场景 B `P3B-C09-AMBIG`；`P3B-C09-NL` 逐字保留 |
| [P3BC09NaturalLanguageScenarioTest.java](file:///e:/project/spec-agent/backend/src/test/java/com/specagent/eval/P3BC09NaturalLanguageScenarioTest.java) | 新增宽泛目标非天然缺参回归、泄漏负向断言、哈希冻结与隔离、三题语义区分等离线门禁 |
| [P3BC09AmbiguousGateEnforcementTest.java](file:///e:/project/spec-agent/backend/src/test/java/com/specagent/eval/P3BC09AmbiguousGateEnforcementTest.java) | 新增：enforced 档位下，缺 `query` 的调用在场景 B 题面上被否决且绝不执行；正确追问仍 eligible |
| [p3b_c09ambig_local_protocol.v1.json](computer://e:\project\spec-agent\tools\p3b\p3b_c09ambig_local_protocol.v1.json) | 新场景的独立冻结协议（题面、预算、停止条件、runtime、离线门禁、单臂声明） |

未改：生产 Prompt、Schema、校验规则、`ActionEligibility`、超时阈值、Provider 与 Custom 配置、Ollama 全局设置、历史 C09/C09-NL 的场景与 oracle、历史报告与冻结评分。

# P3-B 项目 Agent 需求澄清与决策质量优化

状态：**已完成 P3-B 决策基线、四类故障归因、一项有证据支持的通用最小修复与完整回归；
真实模型成绩为 BLOCKED。**

本轮只处理 **项目 Agent（Workspace / Graph Reasoning Agent）** 的三类核心判断：
何时追问、何时调用工具、何时结束或回应。未新增第二套 Planner、未引入多 Agent 辩论、
未新增 Reranker / RAG / Redis / Kafka / 独立决策微服务，未做 Global Assistant 与项目
Agent 的桥接，未每轮强制额外一次模型调用，未改动 Zen 共享库、私有 Maven 发布与模型协议。
始终只支持 Chat Completions。

---

## 1. 真实 Decision 架构与代码路径

| 层 | 真实实现 | 证据 |
|---|---|---|
| 上下文装配 | `ContextBuilder` → `ContextSnapshot` → `AgentInputSnapshotBuilder` | [AgentInputSnapshotBuilder.java](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/snapshot/AgentInputSnapshotBuilder.java) |
| 决策输入 | `AgentInputSnapshot`：lineage / effectiveClaims / availableCapabilities / capabilityResults / retrievedContext / allowedSourceRefs | [inputs.py](file:///e:/project/spec-agent/agent-brain/src/spec_agent_brain/contracts/inputs.py)、`prompts/decision.py::render_user_prompt` |
| 动作资格（掩码） | `ActionEligibilityGate.prepareDecisionRequest` → `ActionEligibilityEvaluator.evaluate`（默认 `shadow`） | [ActionEligibilityGate.java:62-71](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/action/ActionEligibilityGate.java#L62-L71)、[ActionEligibilityEvaluator.java:40-72](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/action/ActionEligibilityEvaluator.java#L40-L72) |
| 决策调用 | Python `handle_decision`（1 次模型调用完成 reflection + planning，含一次有界冲突修复） | [decision/engine.py:82-154](file:///e:/project/spec-agent/agent-brain/src/spec_agent_brain/decision/engine.py#L82-L154) |
| 决策提示词 | 27 条 positive-evidence 规则（RUI / CREATE_NODE / IC / WAIT / RTU 的独立资格门槛） | [prompts/decision.py:15-56](file:///e:/project/spec-agent/agent-brain/src/spec_agent_brain/prompts/decision.py#L15-L56) |
| 响应校验 | `AgentBrainResponseValidator`（refs / 预算 / eligibility basis / 冲突暴露 / **按族 payload 形状**） | [AgentBrainResponseValidator.java:123-393](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/decision/AgentBrainResponseValidator.java#L123-L393) |
| 动作资格（校验器） | `ActionEligibilityValidator.validateSelection`（重复持久化单元、未解析提问、能力可见性、能力参数 grounding、typed persistence intent） | [ActionEligibilityValidator.java](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/action/ActionEligibilityValidator.java) |
| 策略 | `AdvisorPolicyEngine`（按动作族与能力副作用分类：auto-execute / require-confirmation / deny） | [AdvisorPolicyEngine.java:56-179](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/policy/AdvisorPolicyEngine.java#L56-L179) |
| 执行 | `DecisionExecutionService` → `ActionExecutor`，节点产物与终态化同事务；能力调用在事务外 | [DecisionExecutionService.java:113-270](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/runtime/DecisionExecutionService.java#L113-L270) |
| 续跑 / 停止 | `ContinuationCoordinator.evaluate`（PARKED_USER_INPUT / TERMINAL_RESPONSE / EXECUTED_NEW_OBSERVATION / NO_EFFECT / PARKED_APPROVAL / ALREADY_CONTINUED / BUDGET_EXHAUSTED） | [ContinuationCoordinator.java:107-153](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/runtime/ContinuationCoordinator.java#L107-L153) |
| 需求状态 | `RequirementStateBuilder` 回放 AnswerPatch（派生物，非事实源） | [RequirementStateBuilder.java](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/workspace/context/RequirementStateBuilder.java) |

**关键配置事实（决定基线含义）**：
`spec.agent.action-eligibility.mode` 默认 **shadow**（测试档第 62-65 行、生产档默认值同）
——因此 eligibility 门禁在默认安装下**只记录裁决、不改变行为**；
`ActionEligibilityEvaluator` 是刻意最小的纯函数，只实现两条件：WAIT 恒以
`NO_PENDING_DEPENDENCY` 拒绝、`availableCapabilities` 为空时拒绝 IC。

---

## 2. P3-B 新增样本数量、标签状态和运行环境

* **新增场景 18 个**（`P3B-C01` … `P3B-C18`），覆盖任务书 16 个必备类别；其中 C02/C03 与
  C06/C07/C09 各自拆成两条，用来把"门禁可判"和"只能由模型判"彻底分开。
  领域覆盖业务流程、服务规则、运营管理与软件交付，**不止一种软件开发场景**。
* **每个场景 2 个变体**：`base`（GENERAL）+ `holdout`（改写 + 打乱无关上下文的 HOLDOUT），
  共 **36 次尝试**。holdout 变体不参与调优，用于未参与调优的独立样本复测。
* 每个样本记录：用户当前意图、已知需求事实、必须解决的 blocker、eligible action families、
  可接受动作集合、禁止动作及原因、所需 payload 条件、期望的后续周期行为、
  **标签来源与审核状态**。
* **标签状态**：全部为 AI 编写的候选标签，`PENDING_HUMAN_REVIEW`；`acceptableActions` 是
  刻意收窄的**动作族集合**（P3-A 的宽松集合无法区分三种故障模式）。
* **运行环境**：
  * Java 侧：`SpringBootTest` + `@ActiveProfiles("test")`，`provider=fake`、
    `model=scripted-brain`、`evaluation_profile=B_FAST`；Postgres 测试库
    `spec_agent_test`（docker `spec-agent-postgres`，127.0.0.1:5434）。
    `ScriptedBrain` 只在**结构化大脑输出边界**替身，图/快照/eligibility/policy/executor/
    续跑裁决全部是生产实现。
  * Python 侧：`agent-brain/.venv`，离线回放真实 `handle_decision`，无网络、无 provider 调用。
  * **真实模型**：`BLOCKED`（上游免费档配额限流 HTTP 429 `FreeUsageLimitError`，
    且本地无 broker 模式 agent-brain 运行）。因此所有数字都是
    **Runtime 门禁质量的 deterministic 基线，不是真实模型决策质量**；不宣称真实模型决策质量提高。

---

## 3. 修改前各项指标及分母

产物：`backend/build/eval-p3b/p3b-decision-quality.json`（脚本化档位，36 次尝试）。
以下每个比率都带**显式分母**；不把 3～4 个有效样本的比例描述成整体正确率。

| 指标 | 修改前 | 分母（样本数） |
|---|---|---|
| Action acceptable-set accuracy | **0.6667** | 36（全部产出动作） |
| Unnecessary clarification rate | **0.2857** | 14（把追问列为禁止的场景 × 变体） |
| Repeated clarification rate（门禁漏判） | **0.0000** | 2（逐字重复探针） |
| Capability necessity accuracy | **0.6250** | 16 |
| Stop / continue correctness | **1.0000** | 20 |
| Payload validity | **1.0000** | 36（全部走到 Java 契约校验） |
| Eligibility false negatives | **0** | 18（期望放行的场景 × 变体） |
| Eligibility false positives | **4** | 8（期望否决的非已知缺口场景 × 变体） |
| 已知且被接受的 eligibility 缺口 | **2** | C16 的 WAIT（base + holdout） |
| 语义类不可判分样本 | **8** | 4 个 `NO_DETERMINISTIC_SURFACE` 场景 × 变体 |
| 协议违约 | **0** | 36 |
| INFRA（transport / provider） | **0** | 36 |
| 模型调用 / 任务 | **2.0000** | 36（STATE_UPDATE + DECISION） |
| 持久化能力调用总数 | **10** | 36 |

Python 侧 Payload/protocol 回放（`tools/p3b/p3b_decision_replay.py`，11 个
`SYNTHETIC_FROZEN_FIXTURE`）：分类全部符合声明（11/11），无一条契约违约落入 INFRA。

---

## 4. 典型失败场景及根因分类

按任务书四类（Context / Eligibility / Decision / Payload）归因，并另设 INFRA 与协议违约。
每个问题都留下：输入快照、eligible mask、模型（脚本化）输出、Java 验证结果、失败归因、
可复现测试。**绝不因为最终动作错就断定是 Prompt 问题。**

| # | 场景 | 类别 | 真实表现 | 根因 | 证据 |
|---|---|---|---|---|---|
| 1 | P3B-C06 / C09 (×2) | **Eligibility** | `INVOKE_CAPABILITY` 调用 `memory.search` 却**没有给出它声明的必填参数 `query`**；门禁 PASS → 能力真正执行并以 `arguments.query must be non-blank` 失败，白白消耗一次调用 | IC 资格只校验了"能力可见 + 参数 refs 已 grounding"，**从未校验描述符声明的必填参数**；而 `LocalDeterministicDecisionEngine` 早就在用同一份必填参数事实避开这类调用——两处实现不一致 | `gate_veto_reason_breakdown` 修改前无该码；`persistedCapabilityInvocations=10` |
| 2 | P3B-C07 (×2) | **Decision**（语义，非门禁） | 参数齐全，但用户只是确认一条业务目标，仍调用 `memory.search` | "相关 ≠ 必须"不能被结构事实表达——既有 R4/B-2 `SHADOW_REJECTED` 报告已证明 | `capabilityNecessityAccuracy=0.625`（6/16） |
| 3 | P3B-C02 (×2) | **Decision + 执行档位** | 逐字重复已答问题；门禁裁决 `VETO / RESOLVED_BLOCKER`，但 shadow 档位不执行，问题节点仍被创建 | 默认档位刻意"约束执行而非推理"，只记录裁决 | `per_scenario_clarification`：`[UNNECESSARY]` 且 `post_selection_veto_result=VETO` |
| 4 | P3B-C03 (×2) | **Decision**（语义） | 换一种措辞重复同一问题 | Runtime 只有精确归一化比对，改写重复**无确定性判据**；属于模型侧语义判断 | 该场景标记 `NONE_SEMANTIC_ONLY` |
| 5 | P3B-C16 (×2) | **Eligibility**（已知缺口） | 存在真实外部等待条件，正确动作是 `WAIT` | `agent-input.v2` 没有"待处理依赖"的表示 → `WAIT` 恒以 `NO_PENDING_DEPENDENCY` 拒绝（R4 §9 已记录） | `known_eligibility_gaps_observed=2`，单独计数 |
| 6 | P3B-RP-02 / RP-03 | **Payload** | Brain **接受** `questionText` 为对象 / 缺失 `allowFreeAnswer` 的畸形 payload，一路送到 Java，由 Java fail-closed 拦截 | Brain 的 `payload` 是有界通用 map，**不做按动作族的形状校验**；与 P3-A B05 的 `questionText must be a string` 同路径 | `tools/tests/test_p3b_replay.py::test_brain_accepts_malformed_per_family_payload_and_java_is_the_authority` |
| 7 | — | **Context / representation** | 本轮**未观察到**该类故障 | 默认 shadow 档位刻意不把掩码发给模型；无法在离线条件下测量"事实是否准确进入 DECISION 输入" | 如实记录为未测量，不编造 |

**结论口径**：唯一**可复现且可确定性修复**的根因是第 1 条（Eligibility false positive）；
第 2/4/5 条是语义/表示层缺口，本轮**不修**（R4 已证明用启发式规则修它们会跨场景误伤）；
第 3 条是执行档位的既定语义；第 6 条是契约边界的既定分工；第 7 条未测量。

---

## 5. 修改的生产代码及对应证据

**唯一一处生产改动**（通用、最小、有界）：

| 文件 | 改动 |
|---|---|
| [ActionEligibilityReasonCode.java](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/protocol/ActionEligibilityReasonCode.java) | 新增原因码 `MISSING_REQUIRED_CAPABILITY_ARGUMENT`（纯增量，不改变既有取值） |
| [ActionEligibilityValidator.java:164-258](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/action/ActionEligibilityValidator.java#L164-L258) | `validateVisibleCapability` 在"能力可见"之外，新增"被调用能力**声明的必填参数**必须在提案 `arguments` 中给出实值"。判据只读 Runtime 持有的 `inputSchema`（同时支持 JSON-Schema `properties`+`required` 与紧凑 `{"query":{"required":true}}` 两种既有形态）；`null` 与空白字符串同等视为"没有给出" |

**为什么这是通用语义修复而不是业务分支**：

* 判据完全来自**能力描述符自己声明的内容**——不引入任何业务词表、场景 ID、固定措辞或
  benchmark 识别；没有声明必填字段的能力（评测探针、旧版冻结 payload）永远不受影响，
  不会凭空制造新的必填条件。
* 它不是动作族优先级树、不是 second ranking model、不是 eligibility/policy 绕过，
  也没有放宽任何业务协议（Java 保持 fail-closed，本次是**新增**一条确定性必要条件）。
* 它与既有确定性决策引擎对"必填 query"的处理**从两套实现收敛为一套事实**。

**对应失败证据**（同一开发集，仅此一处改动）：

| 证据 | 修改前 | 修改后 |
|---|---|---|
| Eligibility false positives | 4 / 8 | **0 / 8** |
| 门禁原因码 `MISSING_REQUIRED_CAPABILITY_ARGUMENT` | 0 | **4** |
| Eligibility false negatives（是否制造反向错误） | 0 / 18 | **0 / 18**（无新增） |
| 已知缺口计数（WAIT） | 2 | 2（未掩盖、未抹掉） |
| 其他全部比率 / 模型调用 / 持久化调用 | 见 §3 | **完全不变** |

强制档位（`@TestPropertySource(mode=enforced)`）下的**行为**前后对照：
[P3BEnforcedCapabilityNecessityTest.java](file:///e:/project/spec-agent/backend/src/test/java/com/specagent/eval/P3BEnforcedCapabilityNecessityTest.java)
证明修复后该提案在 policy/executor **之前**被否决（`ACTION_INELIGIBLE`、
`POLICY_DECISION` 阶段不存在、`actualPrimaryAction=null`、持久化能力调用 = 0），
且**参数齐全的调用（C08/C17）与未声明必填字段的能力都不受影响**。

---

## 6. 修改后与修改前的可比结果

同一开发集（18 场景 × 2 变体 = 36 次尝试）、同一链路、唯一差别是那一处 eligibility 判据：

| 指标 | 修改前 | 修改后 | 分母 | 判定 |
|---|---|---|---|---|
| Action acceptable-set accuracy | 0.6667 | 0.6667 | 36 | 不变（shadow 不执行裁决） |
| Unnecessary clarification rate | 0.2857 | 0.2857 | 14 | 不变（语义缺口未修） |
| Repeated clarification rate | 0.0000 | 0.0000 | 2 | 不变（既有规则已够） |
| Capability necessity accuracy | 0.6250 | 0.6250 | 16 | 不变（shadow 不执行裁决） |
| Stop / continue correctness | 1.0000 | 1.0000 | 20 | 不变 |
| Payload validity | 1.0000 | 1.0000 | 36 | 不变 |
| **Eligibility false positives** | **4** | **0** | 8 | **按预期改善** |
| Eligibility false negatives | 0 | 0 | 18 | **无反向恶化** |
| 模型调用 / 任务 | 2.0000 | 2.0000 | 36 | **不增加** |
| 持久化能力调用总数 | 10 | 10 | 36 | shadow 档位不变 |

强制档位（enforced）下的**行为**前后对照由
[P3BEnforcedCapabilityNecessityTest.java](file:///e:/project/spec-agent/backend/src/test/java/com/specagent/eval/P3BEnforcedCapabilityNecessityTest.java)
实测：同一形态的缺参提案在修复后被否决且**持久化能力调用 = 0**（提案在 policy/executor
之前即不成立）；参数齐全的提案与未声明必填字段的能力仍正常执行并留下调用行。
据此按语料结构**推导**（非实测，明确标注）：全量语料的 10 次持久化能力调用在 enforced
档位下会降为 **6 次**（C06/C09 的 4 次注定失败的调用被拦，C07 的 2 次参数齐全、门禁本就允许）。

**必须如实说明的一点**：默认档位是 shadow，因此这处修复在**默认安装下只改变门禁裁决**
（可观测、可审计），**不改变**默认行为；行为层面的收益在 enforced 档位下成立
（配置项未被本轮改动）。这一点已在报告与测试注释中写明，未用任何数字掩盖。

---

## 7. 旧 R4 与 P3-A 是否出现回归

* **未启用**旧 ranking selector、旧 heuristic adapter 或任何通用动作优先级树；
  `SemanticRankingSelector` 与 `agent-ranking.v1` 契约、shadow 报告与冻结样本**未改动**。
* `tools/tests`：**116 → 133 passed**（新增 10 条 P3-B 协议回放测试 + 7 条语料守卫），
  既有 R4 harness / P3-A 计分测试**全部继续通过**，无一条被删除、跳过或放宽。
* `evalBFast`：**102 / 0 / 0 → 106 / 0 / 0**（新增的 4 条全部是本轮 P3-B 测试）。
* Java：`ActionEligibilityCharacterizationTest` 14 → **19 passed**（新增 5 条刻画用例）；
  `P3ADecisionScoringTest` / `P3AClaimGroundingTest` 未改动且通过。
* `testNonLive`：**2064 tests / 17 failed / 34 skipped**；这 17 例在基线 `c5e695e` 上
  **同样失败（用例名逐个相同）**，根因是共享隔离测试库残留的非终态续跑行，
  与本轮改动无关（证据见 §8）。其余 2013 例全部通过。
* 冻结物（`contracts/fixtures/*`、`docs/v2/evidence/*`、语料 JSON）**一律未改写**。
* `INVOKE_CAPABILITY ↔ REQUEST_USER_INPUT` 的迁移方向：本轮修复**只影响 IC 自身**，
  不可能把错误迁移到 RUI 方向；证据是 `eligibilityFalseNegatives 0/18` 与
  RUI 相关指标（不必要追问、重复追问）**完全不变**。

---

## 8. Java / Python / 离线 / 真实模型测试结果

| 套件 | 命令 | 结果 |
|---|---|---|
| 后端确定性全量 | `backend> gradlew.bat testNonLive` | **2064 / 17 failed / 34 skipped**，其中 17 例在基线 `c5e695e`（把我的改动 `git stash` 掉后）**完全同样地失败**（同一批用例名），且根因是隔离测试库里残留的非终态续跑行 —— **非本轮回归**，见下方证据 |
| 既有 Agent 评测 | `backend> gradlew.bat evalBFast` | **106 / 0 / 0**（21 suites；基线 102 tests / 19 suites，新增 4 tests / 2 suites，均为 P3-B） |
| P3-B 决策基线（Java） | `gradlew.bat test --tests com.specagent.eval.P3BDecisionQualityTest` | **1 / 0 / 0** |
| P3-B 强制档位行为证据 | `--tests com.specagent.eval.P3BEnforcedCapabilityNecessityTest` | **3 / 0 / 0** |
| eligibility 刻画 | `--tests com.specagent.agent.action.ActionEligibilityCharacterizationTest` | **19 / 0 / 0**（基线 14） |
| P3-A 计分 | `--tests com.specagent.eval.P3ADecisionScoringTest` | 通过（未改动） |
| Python Brain | `agent-brain> .venv/Scripts/python -m pytest -q` | **239 passed**（未改动 Python 生产代码） |
| 评测工具 | `.venv/Scripts/python -m pytest tools/tests -q` | **133 passed**（基线 116；新增 10 回放 + 7 语料守卫） |
| P3-B 协议回放 | `python tools/p3b/p3b_decision_replay.py` | 11/11 分类符合声明；无契约违约落入 INFRA |
| 真实模型（项目 Agent / GA） | `evalP3ALiveProjectAgent` | **BLOCKED**（429 配额限流 + 本地 broker 缺失），未伪造为通过 |

**那 17 例既有失败的证据链（不做任何数据库清空）**：

1. 把本轮全部改动 `git stash` 后，在**基线 `c5e695e`** 上单独重跑那两个套件：
   `ContinuationChainIntegrationTest` + `AgentRunLoopLinkageTest` → **29 tests completed, 17 failed**，
   用例名与本轮 17 例**逐个相同**。
2. 根因是**共享隔离测试库 `spec_agent_test` 里残留的非终态续跑行**：
   `SELECT status, trigger_type, count(*) FROM agent_runs` 显示
   `created / continue_cycle = 40`、`running / continue_cycle = 15`（共 695 行）。
   `claimNextContinue()` 因而先领到**别的** run（失败信息即
   `expected <本用例 run> but was <另一 run>`），续跑子 run 也就不会被创建
   （`findChildByParentRunId(...).orElseThrow()` → `NoSuchElementException`）。
3. 因此这 17 例是**环境/测试库卫生问题**，不是本轮代码回归。
   本轮**未**对该测试库做任何清空或修复（避免破坏引用完整性），如实记录。
   **用户开发库 `spec_agent` 与本轮任何写入无关。**

除这 17 例之外，`testNonLive` 的其余 **2013 例全部通过**，包含 P3-A / 路线 / Graph /
AnswerPatch / 续跑 / 幂等 / lease-epoch-fence 相关套件。

**本轮未改动**：Global Assistant、Zen 共享库、私有 Maven 发布、模型协议、前端、数据库迁移。

---

## 9. 每个用户任务的模型调用次数变化

* 修改前：**2.0000 次 / 任务**（36 次尝试共 72 次，STATE_UPDATE + DECISION）。
* 修改后：**2.0000 次 / 任务**（仍为 72 次）。
* 修复是**纯门禁判定**，不触发任何额外模型调用，也不改变继续/停止的调用语义。
* 续跑正确性 `1.0000`（20 个可判样本）未变化：`RESPOND_TO_USER → TERMINAL_RESPONSE`、
  `REQUEST_USER_INPUT → PARKED_USER_INPUT`、成功能力调用 → 链继续
  （事后重放呈现 `ALREADY_CONTINUED`，即续跑子 run 已恰好落库一次）、
  需审批能力 → `PARKED_APPROVAL`、WAIT → `NO_EFFECT`。**未为省调用而破坏合法续跑语义。**

---

## 10. 遗留风险、Git commit 与工作树状态

**遗留风险**

1. **默认 shadow 档位**：修复在默认安装下只改变门禁裁决，不改变行为；生产行为收益需显式
   切到 `enforced`（**本轮未切**）。
2. **enforced 档位下否决会 fail-closed 结束本次 run**（而非让模型另选动作），与既有
   `ACTION_INELIGIBLE` 语义一致，但需在产品侧确认这一取舍。
3. **`WAIT` 表示缺失**是长期已知缺口（C16）：只要 `agent-input.v2` 不承载"待处理依赖"，
   它就无法被裁定为 eligible。单独计数，不冒充新增缺陷。
4. **语义缺口未修**：C03（改写重复追问）、C07（相关≠必须）、C13/C15（来源作废/无依据断言）
   属模型侧语义判断；R4/B-2 已证明结构事实不足以表达，继续堆启发式会跨场景误伤。
5. **Payload 类可观测性受限**：Brain 不做按族形状校验，且 Java 边界会把部分 Brain 契约失败
   折叠成 `AgentBrainUnavailableException`（P3-A 已记录）——真实差异已定位（模型把
   `questionText` 写成对象），但"payload 违约 vs 网络故障"的分辨能力仍有限。
6. **样本量与标签**：18 场景 × 2 变体，全部 `PENDING_HUMAN_REVIEW`；
   任何比率都不得被描述为"整体 Agent 正确率"。
7. **真实模型 BLOCKED**：不宣称真实模型决策质量提高。

**Git**

* 基线 `c5e695e`，工作树本轮开始时干净（`git status --short` 为空）。
* 本轮改动分两个本地提交：**（A）生产修复 + 其单元测试**、
  **（B）P3-B 评测基线、语料、协议回放与本文档**——诊断数据与业务代码不混为一个提交。
* 不 push、不 force push、不重写历史；不提交密钥或用户数据；不修改任何冻结历史证据。
* 用户数据库未被清空或重建（仅使用隔离测试库 `spec_agent_test`）。

---

## 结论

1. **是否真实提高了澄清与决策质量？**
   在**可确定性证伪的范围内**是：唯一可复现的门禁级根因（IC 声明必填参数缺失仍被放行）
   已关闭，eligibility false positive **4/8 → 0/8**，且没有产生任何反向 false negative。
   语义层（改写重复追问、相关≠必须）**未被提高**，本轮如实记为未解决。
   真实模型决策质量**无法判定**（BLOCKED）。
2. **是否减少不必要的追问和工具调用？**
   减少了**可确定性判定的那一部分不必要的工具调用**（4 次注定失败的 `memory.search`
   调用不再 eligible；enforced 档位下按语料结构推导为 10 → 6 次持久化调用，实测部分是
   "被否决且持久化调用为 0"）。
   **未**减少语义类的不必要追问/调用——这批没有确定性判据，不假装减少。
3. **是否影响原有 eligibility、policy、Graph 安全边界？**
   不改 policy、不改执行器、不改图不变量、不放宽任何既有校验；只在 eligibility 校验器
   上**新增**一条 fail-closed 必要条件，且只在描述符自己声明了必填字段时生效。
   默认 shadow 档位下生产行为完全不变。
4. **是否值得推送到主分支？**
   值得：改动最小、有界、可复跑、有前后对照、无回归、不增加模型调用、不改变默认行为，
   并把两处不一致的"必填参数"事实收敛为一处。
   **建议同时把"是否切到 enforced 档位"作为独立决策**（本轮未做）。

# P3-B 项目 Agent 需求澄清与决策质量优化

状态：**P3-B 四项审查问题已修复；Python / Java 回放及同一隔离库连续两次全量回归通过。
真实模型语义成绩仍为 BLOCKED：官方路径上的首个请求以 AUTHENTICATION / HTTP 502 失败，
没有产生可评分决策。最终运行数据见 §11。**

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
| 动作资格（掩码） | `ActionEligibilityGate.prepareDecisionRequest` → `ActionEligibilityEvaluator.evaluate`（测试配置默认为 `shadow`；正常安装默认为 `enforced`） | [ActionEligibilityGate.java:62-71](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/action/ActionEligibilityGate.java#L62-L71)、[ActionEligibilityEvaluator.java:40-72](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/action/ActionEligibilityEvaluator.java#L40-L72) |
| 决策调用 | Python `handle_decision`（1 次模型调用完成 reflection + planning，含一次有界冲突修复） | [decision/engine.py:82-154](file:///e:/project/spec-agent/agent-brain/src/spec_agent_brain/decision/engine.py#L82-L154) |
| 决策提示词 | 27 条 positive-evidence 规则（RUI / CREATE_NODE / IC / WAIT / RTU 的独立资格门槛） | [prompts/decision.py:15-56](file:///e:/project/spec-agent/agent-brain/src/spec_agent_brain/prompts/decision.py#L15-L56) |
| 响应校验 | `AgentBrainResponseValidator`（refs / 预算 / eligibility basis / 冲突暴露 / **按族 payload 形状**） | [AgentBrainResponseValidator.java:123-393](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/decision/AgentBrainResponseValidator.java#L123-L393) |
| 动作资格（校验器） | `ActionEligibilityValidator.validateSelection`（重复持久化单元、未解析提问、能力可见性、能力参数 grounding、typed persistence intent） | [ActionEligibilityValidator.java](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/action/ActionEligibilityValidator.java) |
| 策略 | `AdvisorPolicyEngine`（按动作族与能力副作用分类：auto-execute / require-confirmation / deny） | [AdvisorPolicyEngine.java:56-179](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/policy/AdvisorPolicyEngine.java#L56-L179) |
| 执行 | `DecisionExecutionService` → `ActionExecutor`，节点产物与终态化同事务；能力调用在事务外 | [DecisionExecutionService.java:113-270](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/runtime/DecisionExecutionService.java#L113-L270) |
| 续跑 / 停止 | `ContinuationCoordinator.evaluate`（PARKED_USER_INPUT / TERMINAL_RESPONSE / EXECUTED_NEW_OBSERVATION / NO_EFFECT / PARKED_APPROVAL / ALREADY_CONTINUED / BUDGET_EXHAUSTED） | [ContinuationCoordinator.java:107-153](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/agent/runtime/ContinuationCoordinator.java#L107-L153) |
| 需求状态 | `RequirementStateBuilder` 回放 AnswerPatch（派生物，非事实源） | [RequirementStateBuilder.java](file:///e:/project/spec-agent/backend/src/main/java/com/specagent/workspace/context/RequirementStateBuilder.java) |

**关键配置事实（决定基线含义）**：
`spec.agent.action-eligibility.mode` 在**评测所用的测试档**默认为 **shadow**
（`backend/src/test/resources/application-test.yml` 第 62–65 行），因此本报告的
全部数字都在 shadow 下测得——该档位只记录裁决、不改变行为。但**生产/默认安装档
（`backend/src/main/resources/application.yml` 第 87–88 行）默认已是 `enforced`**：
即 eligibility 否决在默认安装下**会真正改变行为**。本轮**未改动任何档位的默认值**
（测试档仍 shadow，生产档仍 enforced），也未把测试档切到 enforced——只有
`P3BEnforcedCapabilityNecessityTest` 用 `@TestPropertySource` 显式 opt-in。
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
  * **真实模型**：此前运行记录过免费档 HTTP 429；本轮已启动 Java Host → Python broker →
    OpenCode 的真实链路，并按官方路径发送请求，但首个请求以 AUTHENTICATION / HTTP 502 失败。
    没有生成可评分决策，因此所有语料数字仍是**Runtime 门禁质量的 deterministic 基线**，
    不是真实模型决策质量；不宣称真实模型决策质量提高。当前运行证据见 §11。

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

**必须如实说明的一点**：**评测所用的测试档**默认是 shadow，因此上表的前后对照里，
这处修复只改变门禁裁决（可观测、可审计），**不改变该档位下的实际行为**；行为层面的
收益在 enforced 档位下成立。而**生产/默认安装档的默认值本身就是 `enforced`**
（`application.yml:88`），所以这处修复在**默认安装下会实际改变行为**（缺参的
`INVOKE_CAPABILITY` 提案在 policy/executor 之前即被 fail-closed 拒绝）。本轮
**未改动任何档位默认值**，也未用任何数字掩盖这一点。

---

## 7. 旧 R4 与 P3-A 是否出现回归

* **未启用**旧 ranking selector、旧 heuristic adapter 或任何通用动作优先级树；
  `SemanticRankingSelector` 与 `agent-ranking.v1` 契约、shadow 报告与冻结样本**未改动**。
* `tools/tests`：**116 → 133 passed**（新增 10 条 P3-B 协议回放测试 + 7 条语料守卫），
  既有 R4 harness / P3-A 计分测试**全部继续通过**，无一条被删除、跳过或放宽。
* `evalBFast`：**102 / 0 / 0 → 106 / 0 / 0**（新增的 4 条全部是本轮 P3-B 测试）。
* Java：`ActionEligibilityCharacterizationTest` 14 → **19 passed**（P3-B 新增 5 条刻画用例），
  本轮审查再补 3 条正反例（非字符串合法实值 / 显式 null / 可选参数缺失）后为 **22 passed**；
  `P3ADecisionScoringTest` / `P3AClaimGroundingTest` 未改动且通过。
* `testNonLive`：在**本轮新建的全新独立库** `spec_agent_p3b_clean_20261017` 上为
  **2067 tests / 0 failed / 34 skipped**（2033 passed；34 例为既有 skipped；2067 =
  修复前的 2064 + 本轮新增的 3 条刻画用例）。此前记录的 17 例失败**在干净库中完全消失**，
  因此被证实为**测试库状态污染**（确切机制见 §8），不是代码回归。注意：**同一个库复跑**
  会因评估语料的残留再次触发同类失败——本轮已做最小**测试隔离修复**（`EvalHarnessBase`
  改为清理一个测试方法内跑过的**全部** project）；修复后**同库连续两次** `--rerun`
  全量运行均全绿（BUILD SUCCESSFUL，0 failed）。
* 冻结物（`contracts/fixtures/*`、`docs/v2/evidence/*`、语料 JSON）**一律未改写**。
* `INVOKE_CAPABILITY ↔ REQUEST_USER_INPUT` 的迁移方向：本轮修复**只影响 IC 自身**，
  不可能把错误迁移到 RUI 方向；证据是 `eligibilityFalseNegatives 0/18` 与
  RUI 相关指标（不必要追问、重复追问）**完全不变**。

---

## 8. Java / Python / 离线 / 真实模型测试结果

本节保留早期基线与隔离排查的历史记录；2026-10-17 本轮最终的测试次数及 live 请求数据以 §11 为准。

| 套件 | 命令 | 结果 |
|---|---|---|
| 后端确定性全量 | `backend> gradlew.bat testNonLive` | 见下方 §8.1：**本轮在全新独立库上为 2067 / 0 failed / 34 skipped**（修复前为 2064 total）；此前记录的 17 例失败被证实为测试库状态污染（机制已定位），非本轮代码回归 |
| 既有 Agent 评测 | `backend> gradlew.bat evalBFast` | **106 / 0 / 0**（21 suites；基线 102 tests / 19 suites，新增 4 tests / 2 suites，均为 P3-B） |
| P3-B 决策基线（Java） | `gradlew.bat test --tests com.specagent.eval.P3BDecisionQualityTest` | **1 / 0 / 0** |
| P3-B 强制档位行为证据 | `--tests com.specagent.eval.P3BEnforcedCapabilityNecessityTest` | **3 / 0 / 0** |
| eligibility 刻画 | `--tests com.specagent.agent.action.ActionEligibilityCharacterizationTest` | **22 / 0 / 0**（基线 14；P3-B 19；本轮审查 +3 正反例） |
| P3-A 计分 | `--tests com.specagent.eval.P3ADecisionScoringTest` | 通过（未改动） |
| Python Brain | `agent-brain> .venv/Scripts/python -m pytest -q` | **239 passed**（未改动 Python 生产代码） |
| 评测工具 | `.venv/Scripts/python -m pytest tools/tests -q` | **133 passed**（基线 116；新增 10 回放 + 7 语料守卫） |
| P3-B 协议回放 | `python tools/p3b/p3b_decision_replay.py` | 11/11 分类符合声明；无契约违约落入 INFRA |
| 真实模型（项目 Agent / GA） | `evalP3ALiveProjectAgent` | **BLOCKED**（429 配额限流 + 本地 broker 缺失），未伪造为通过 |

### 8.1 那 17 例既有失败的证据链（本轮用**全新独立数据库**复核，不做任何清空既有库）

1. 本轮新建**全新且独立的一次性测试库** `spec_agent_p3b_clean_20261017`，与用户开发库
   `spec_agent`、既有 `spec_agent_test`、`spec_agent_p3a` 明显不同；测试 JVM 通过
   `SPRING_DATASOURCE_URL` 指向它，并已核实迁移（V95）与全部写入都落在该库。
   在其上跑完整 `testNonLive`：**2064 tests / 0 failed / 34 skipped** ——
   **17 例失败在干净库中完全消失**，据此确认为**测试库状态污染**，不是代码回归。
2. **机制已定位到具体原因**，而不是笼统的"环境问题"：
   * `AgentRunRepository.claimNextContinueRun` 以**全库范围**领取最老的一条 `created`
     的 `CONTINUE_CYCLE` run（`ORDER BY created_at LIMIT 1`，**无 project 作用域**）。
   * 评估套件共享基座 `EvalHarnessBase` 过去只清理**一个测试方法里的最后一个 project**；
     语料型用例（一次方法跑几十个场景，每个场景一个新 project）会把其余 project 的
     非终态 `CONTINUE_CYCLE` 行留在库里。
   * 这些残留行随后被续跑套件（`ContinuationChainIntegrationTest` /
     `AgentRunLoopLinkageTest`）的 `claimNextContinue()` 领到 → `claimNextContinue()`
     返回别的 run（失败信息即 `expected <本用例 run> but was <另一 run>`），
     续跑子 run 不再被创建（`findChildByParentRunId(...).orElseThrow()` →
     `NoSuchElementException`）。
3. 复现实验（全部在本轮新建库上逐次 drop/recreate）：
   * 单独跑这两个续跑套件 → **通过**（BUILD SUCCESSFUL）；
   * 单独跑 `com.specagent.agent.runtime.*` 整个包 → **通过**；
   * 单独跑 `com.specagent.eval.*` 后查询该库 → 留下 **12 条 `created`
     CONTINUE_CYCLE** 残留行；其中**仅** `com.specagent.eval.P3B*`（本轮新增评测）
     就产生同样的 12 条；
   * **同一个库复跑** testNonLive → 续跑套件再次失败（本轮观测 14 例）。
   即：干净库首次运行全绿，**同库复跑**才失败；残留正是评估语料（**包括本轮新增的
   P3-B 语料**）写下的。
4. 因此本轮做了一处最小**测试隔离修复**（属允许范围中的"测试数据隔离缺陷"）：
   `EvalHarnessBase` 改为在测试方法结束时清理该方法内跑过的**全部** project
   （此前只清最后一个）。这是**纯测试基座清理**：不改任何生产代码、不改任何断言、
   不删除/跳过任何测试。修复后**同库复跑** `testNonLive` 亦为全绿。

**用户开发库 `spec_agent` 与本轮任何写入无关**；本轮**未**清空、重建或修改
`spec_agent`、`spec_agent_test` 或其他既有库（对既有库仅做只读查询）。

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

1. **档位事实（必须按实际值理解）**：评测所用的**测试档**默认 `shadow`（该档位下修复
   只改变门禁裁决、不改变行为）；而**生产/默认安装档默认已是 `enforced`**
   （`application.yml:88`），因此该修复的行为收益在**默认安装下即为生效状态**。
   本轮**未改动任何档位默认值**。
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
7. **真实模型语义成绩仍 BLOCKED**：本轮已实际尝试 broker 链路；提供方认证失败后停止，
   没有真实模型输出，详见 §11。

**Git**

以下是本文早期快照中的状态，不代表本轮最终交付；本轮分支、提交与远端状态以 §11 及交付消息为准。

* 基线 `c5e695e`，工作树本轮开始时干净（`git status --short` 为空）。
* 本轮改动分**三个**本地提交：**（A）生产修复 + 其单元测试**、
  **（B）P3-B 评测基线、语料、协议回放与本文档**、
  **（C）审查追加的最小修复（测试隔离 + 补正反例 + 本文档校正）**——
  诊断数据、业务代码与审查修复不混为一个提交。
* 不 push、不 force push、不重写历史；不提交密钥或用户数据；不修改任何冻结历史证据。
* 用户开发库 `spec_agent` 未被清空或重建；本轮全部回归只在**本轮新建的隔离库**
  `spec_agent_p3b_clean_20261017` 上执行（验收后删除该一次性库），对既有
  `spec_agent_test` 等库仅做只读查询。

---

## 结论

1. **是否真实提高了澄清与决策质量？**
   在**可确定性证伪的范围内**是：唯一可复现的门禁级根因（IC 声明必填参数缺失仍被放行）
   已关闭，eligibility false positive **4/8 → 0/8**，且没有产生任何反向 false negative。
   语义层（改写重复追问、相关≠必须）**未被提高**，本轮如实记为未解决。
   真实模型决策质量**无法判定**（BLOCKED；本轮官方端点请求被 AUTHENTICATION / HTTP 502 阻断，详见 §11）。
2. **是否减少不必要的追问和工具调用？**
   减少了**可确定性判定的那一部分不必要的工具调用**（4 次注定失败的 `memory.search`
   调用不再 eligible；enforced 档位下按语料结构推导为 10 → 6 次持久化调用，实测部分是
   "被否决且持久化调用为 0"）。
   **未**减少语义类的不必要追问/调用——这批没有确定性判据，不假装减少。
3. **是否影响原有 eligibility、policy、Graph 安全边界？**
   不改 policy、不改执行器、不改图不变量、不放宽任何既有校验；只在 eligibility 校验器
   上**新增**一条 fail-closed 必要条件，且只在描述符自己声明了必填字段时生效。
   在评测所用**测试档（默认 shadow）**下行为完全不变；**生产/默认安装档默认是
   `enforced`**，该新增条件在该档位下会实际生效（缺参调用被 fail-closed 拒绝）——
   这是有意的确定性收益，不是安全边界放宽。
4. **是否值得推送到主分支？**
   值得：改动最小、有界、可复跑、有前后对照、无回归、不增加模型调用，并把两处不一致的
   "必填参数"事实收敛为一处；**生产/默认安装档本身已是 `enforced`**，因此该收益在默认
   安装下直接生效（**本轮未改动任何档位默认值**）。
   **是否需要调整档位默认值应作为独立决策**：本轮未做，且现状（生产 `enforced`、
   测试 `shadow`）保持不变。若产品希望默认安装回到"只观测不执行"的保守形态，那是
   另一项独立变更，不应藏在本轮修复里。

---

## 11. 2026-10-17 最终审查修复、真实模型试验与回归

本节是本轮最终记录，更新并补充本文件早期的模型状态、测试计数和 Git 状态。

### 四项审查问题

1. **GateOutcome UNKNOWN**：只有显式 PASS/VETO 才分别进入通过/拒绝；缺失或未知事件记为 UNKNOWN，并单独展示未知数及已测数，不缩小完整分母。
2. **INFRA 分类过宽**：改用类型化 gateway category、trace error type 和 gate 结果区分提供方/认证/限流/超时、非 JSON、协议违约、无效 payload、eligibility veto 与 UNKNOWN_FAILURE；不再用 `AgentBrainUnavailableException` 字符串直接推断 INFRA，也未改 Java Host 生产异常分类。
3. **异常路径项目清理**：ScenarioRunner 在创建项目后、搭图前立即回调登记 project ID；每个 ID 独立尝试清理，部分清理失败继续处理其余项目；清理异常保留并作为 suppressed 附加到原始测试异常。清理目标只来自当前评测回调。
4. **Python / Java 回放边界**：共享 fixture 明确约束所有者和所在层。CREATE_NODE 缺省 kind 在 Java wire validator 按 INTERACTION 形状校验，并由 executor 默认创建 Interaction；RESPOND_TO_USER 缺少 message 在 wire 层可通过、executor 返回 null message。Python 回放分别模拟 wire、eligibility、executor 和 policy，不放宽 Java 契约。

对应测试包括 GateOutcome/失败分类单测、cleanup 多项目部分失败与 suppressed 异常及重复运行测试、ScenarioRunner 创建后搭图失败回调测试，以及真实 Java validator / executor 对共享跨语言 fixture 的断言。

### `dsv4.1free` 实际验收

OpenCode 官方 [Models 文档](https://opencode.ai/v2/docs/console/models/)列出 `DeepSeek V4.1 Flash Free`，模型 ID 为 `deepseek-v4.1-flash-free`，并列出 OpenAI-compatible Chat Completions endpoint；文档当前说明该免费档为限时免费，Console 使用需登录并取得 API key。本地项目目录也将该 ID 标记为 chat-executable 和 free。项目的 Zen 元数据源本轮不可达，因此本地 `protocolOf()` 返回 UNKNOWN；不能把本地元数据查询描述为成功验证。

本轮新增的 P3-B live suite 使用文档所列官方路径 `https://opencode.ai/inference/openai/v1`，启动了真实 Java Host → Python broker → OpenCode 链路，并在首个提供方故障后停止。试验进程未配置 `SPEC_AGENT_EVAL_OPENCODE_KEY`；收到的类型化错误为 AUTHENTICATION，因而当前访问凭据是验收阻塞项，没有重试或绕过：

| 项目 | 实测结果 |
|---|---|
| 模型 / 模式 | `deepseek-v4.1-flash-free` / broker |
| 官方路径尝试 | 1 次 `STATE_UPDATE` 推理请求；broker 返回 HTTP 502，类型化类别为 `AUTHENTICATION` |
| 决策输出 | 0 次 DECISION；没有 action、payload 或语义结果 |
| 场景 | P3B-C03 / base，耗时 5.734 秒（运行器计时），5.840 秒（报告测时） |
| 可评分指标 | action accuracy、payload validity、C03 重复追问、C07 capability 必要性和语义错误均未测，分母为 0；eligibility UNKNOWN=1、measured=0 |
| 停止原因 | `PROVIDER_ERROR`；按预设停止规则不继续发送请求 |

单次运行的机器可读记录（包含请求数、场景时间、typed failure 与全部分母）在 [p3b_live_priority_20261017.json](../../tools/p3b/p3b_live_priority_20261017.json)。

此前对旧路径 `https://opencode.ai/inference/v1` 的优先场景试跑已发送 8 次 `STATE_UPDATE` 请求，8 次均由 broker 返回 HTTP 502；观察到的持久化提供方类别为 `AUTHENTICATION`，没有任何 DECISION 请求或语义输出。合并两次试验共发出 9 次模型请求，0 次成功。最新官方路径只完成 C03/base；C03/holdout、C07、C13、C15 在官方路径下均未测，不能把未执行的场景记为通过或失败。

Ollama 未用于推理或 embedding；这些场景不需要向量检索。本轮没有修改 DECISION Prompt、模型调用预算、冻结 oracle 或 `backend/src/main` 生产代码。分支原有提交 `5a1f4f9` 包含 required-capability-argument 的生产资格校验修复；本轮追加的四项审查修复都位于评测、测试、文档与回放工具。

### 回归结果

需要数据库的后端评测与集成回归使用新建隔离库 `spec_agent_p3b_20261017`；既有用户库未清空、未重建。

| 测试 | 结果 |
|---|---|
| 资格校验定向组（Characterization / Gate / Evaluator / P3BDecisionQuality / EnforcedCapabilityNecessity） | 36 tests，0 failed / 0 errors / 0 skipped |
| 新增 ScenarioRunner 项目创建后异常回调测试 | 1 passed |
| Java validator / executor 跨语言边界测试（最终断言复跑） | 13 tests，0 failed / 0 errors / 0 skipped |
| `evalBFast` | 111 tests，0 failed / 0 errors / 0 skipped |
| Python Brain 全量 | 239 passed；1 条 Starlette/AnyIO deprecation warning |
| `tools/tests` | 135 passed |
| P3-B replay | 11/11 fixture outcome 与 action 匹配，0 payload mismatches |
| `testNonLive` 第一次实际运行 | 2074 tests，0 failed / 0 errors / 34 skipped |
| 同一隔离库 `testNonLive --rerun-tasks` 第二次实际运行 | 2074 tests，0 failed / 0 errors / 34 skipped，用时 8 分 24 秒 |

第二次实际运行后的只读查询显示 `agent_runs` 中 `CONTINUE_CYCLE` 残留为 0；Gradle `UP-TO-DATE` 的尝试未计作一次运行。测试档 eligibility 仍为 shadow，正常安装默认 enforced 未更改；模型调用次数没有因这四项审查修复而增加。

### 签收范围与 Git

四项评测/测试缺陷均已关闭，代码层回归通过。由于候选标签仍待人工审核，且真实模型试验被认证错误阻断，**可以签收四项工程修复与确定性基线；不能签收真实模型语义质量达标**。目前没有已知测试失败作为合并阻塞；真实模型认证和标签人工审核仍是语义验收的未决项。

本轮交付分支、提交、远端状态及审查链接以最终交付消息中核对的 Git 状态为准。

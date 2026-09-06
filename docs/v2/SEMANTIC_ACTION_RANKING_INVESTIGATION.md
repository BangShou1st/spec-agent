# Semantic Action Ranking Investigation

本报告是 Phase 3 B+ 的 artifact-level 调查。结论只使用当前磁盘上的两组 live artifact、Git 状态和已有测试结果；不运行 full90，不修改 Candidate C prompt、STATE_UPDATE prompt 或 eligibility hard rule，也不新增 Candidate D。

## 1. Provenance 与可比性

| arm | artifact | instrumentation commit | baseline reference | provider/model | protocol |
|---|---|---|---|---|---|
| Candidate C clean | `E:\project\spec-agent-c-clean\backend\build\eval-live-diagnostic\20260906-034310-adb06e1ab42db030de448bee22cbb24aae47bf9b` | `adb06e1ab42db030de448bee22cbb24aae47bf9b` | `464097cd6ca86a0107ce369214506484dfd57c3f` | `opencode-zen / mimo-v2.5-free` | `agent-input.v2` |
| B+ enforced | `E:\project\spec-agent\backend\build\eval-live-diagnostic\20260905-183601-8b135f3f46b057b7b982e1b536dcb0fee851ddb8` | `8b135f3f46b057b7b982e1b536dcb0fee851ddb8` | `464097cd6ca86a0107ce369214506484dfd57c3f` | `opencode-zen / mimo-v2.5-free` | `agent-input.v3` + `actionEligibility` |

两组 artifact 的 provider endpoint、User-Agent 和外部 key 来源一致；DECISION system prompt hash 均为 `49bd73ee0b317d06dcbb9531cf5457d5a7e7642c7f91675bca7de55bb57ed415`，STATE_UPDATE system prompt hash 均为 `d8490e85d7a408fdb549cd7e0ac1cfd8e64ebd36ad0910d17d72015105351d77`。因此 prompt provenance 不是本比较的混杂因素。运行时 ID、快照内容和模型输出仍按 repetition 变化，不能把这两组当成逐 token 的随机化实验。

## 2. C 与 B+ 总体指标

`behavioral_failed / behavioral_completed` 是本报告的行为失败率；“wrong-action proxy”只表示失败中包含动作/语义偏差的比例，不能替代逐条 fault 分类。`schema/contract` 按现有 `LiveFailureClassifier` 语义统计。

| metric | C clean | B+ enforced |
|---|---:|---:|
| planned / executed | 48 / 48 | 48 / 48 |
| behavioral completed | 46 | 44 |
| behavioral pass / fail | 27 / 19 | 17 / 27 |
| behavioral pass rate | 58.7% | 38.6% |
| wrong-action proxy | 41.3% | 61.4% |
| infrastructure failures | 2 | 4 |
| availability | 95.8% | 91.7% |
| layer-A pass rate | 97.9% | 100.0% |
| schema/contract failures | 0 | 0 |
| provider retries | 0 | 0 |
| production model calls | 94 | 96 |
| capability calls | 1 | 0 |

完成样本中的动作分布：C 为 `REQUEST_USER_INPUT=29`、`RESPOND_TO_USER=16`、`INVOKE_CAPABILITY=1`；B+ 为 `REQUEST_USER_INPUT=28`、`RESPOND_TO_USER=14`、`INVOKE_CAPABILITY=2`。两组都没有 live `CREATE_NODE` 或 `WAIT` 选择。

## 3. Paired action migration matrix

行是 C clean，列是 B+；`INFRA` 表示该侧没有可比较的实际动作。48 个配对的迁移计数如下：

| C \\ B+ | INFRA | INVOKE_CAPABILITY | REQUEST_USER_INPUT | RESPOND_TO_USER |
|---|---:|---:|---:|---:|
| INFRA | 0 | 1 | 1 | 0 |
| INVOKE_CAPABILITY | 0 | 0 | 1 | 0 |
| REQUEST_USER_INPUT | 0 | 1 | 18 | 10 |
| RESPOND_TO_USER | 4 | 0 | 8 | 4 |

两侧均 behavioral-complete 的 42 对中：`BOTH_PASS=13`、`C_PASS_B_FAIL=13`、`C_FAIL_B_PASS=4`、`BOTH_FAIL=12`；另有 6 个 `INFRA_PAIR`。B+ 相对 C 的主要变化是 `REQUEST_USER_INPUT -> RESPOND_TO_USER`（10 对）以及 C 的 `RESPOND_TO_USER -> INFRA`（4 对），不是某个新的 live veto 动作。

## 4. Eligibility false-negative

B+ 的 44 条 behavioral-complete trace 都包含 `ACTION_ELIGIBILITY` stage；每条的 `selected_action_eligible=true`，`would_veto=false`。所以本批数据没有观察到一次“模型选了被禁止动作、随后被 veto”的 live enforcement 效果。

唯一明确的 eligibility false-negative 是 E22：三次 `E22-wait/legitimate` 都把期望 `WAIT` 排除，原因码为 `NO_PENDING_DEPENDENCY`，模型改选了仍在集合内的 `REQUEST_USER_INPUT`。`ActionEligibilityEvaluator` 的现有输入协议没有 pending-dependency 表示，这是已知的 E22 representation mismatch；它占 B+ 行为失败的 3/27（11.1%），不应被误解为 ranking 实现失败，也不在本阶段改 evaluator。

## 5. Ranking-within-eligible-set

对 B+ 的 27 条 behavioral failure，按“期望 family 在 `eligibleFamilies` 且选中的错误 family 也在集合内”归类：

| fault class | count | share of 27 | definition / examples |
|---|---:|---:|---|
| `RANKING_WITHIN_ELIGIBLE_SET` | 20 | 74.1% | E01、E10、E17、E19、E25；期望和错误动作均可选 |
| `PAYLOAD_GENERATION` | 4 | 14.8% | E07-resolved；RUI family 可选，但问题 payload 导致意外 node delta |
| `ELIGIBILITY_FALSE_NEGATIVE` | 3 | 11.1% | E22；WAIT 被 `NO_PENDING_DEPENDENCY` 排除 |
| `OUTPUT_SCHEMA` | 0 | 0% | 无 |
| `STATE_UPDATE_FIRST` / `STATE_APPLICATION_FIRST` / `DECISION_INPUT_PROJECTION_FIRST` / `POLICY_RUNTIME` / `AMBIGUOUS` | 0 | 0% | 无 |

排除已知 E22 mismatch 后，ranking 为 20/24（83.3%），payload 为 4/24（16.7%）。E17 的五条 behavioral-complete failure 中，`INVOKE_CAPABILITY` 在每条 B+ `eligibleFamilies` 内，但模型均选 `REQUEST_USER_INPUT`；这直接排除了“能力被 eligibility 屏蔽”的解释。

## 6. Payload 与 schema

E07-resolved 的 4 条 failure 均选择了仍在 acceptable set 且 eligible 的 `REQUEST_USER_INPUT`，但生成了 `kind=INTERACTION` 的澄清问题，要求用户重新陈述/确认已经 resolved 的 trade-off；scorer 观察到 `nodes=1` 而期望 `nodes=0`。这是 semantic payload generation 错误，不是输出 JSON schema 错误。

两组 artifact 均无 `BRAIN_SCHEMA`、ModelContract 或 invalid-JSON fault；B+ 的 Java validator 对选中的 family 和 eligibility mask 均通过。因此继续扩大 schema/contract hardening 不是本批数据支持的首要动作。

## 7. Eligible-set width 与 pass/stability

B+ 只出现 7 或 8 个 eligible family（canonical 全集为 9 个，`WAIT` 总是被拒绝）：

| eligible width | cycles | behavioral complete | pass | pass rate | denied families |
|---:|---:|---:|---:|---:|---|
| 7 | 10 | 10 | 9 | 90.0% | `WAIT`, `CREATE_NODE`（`UNRESOLVED_BLOCKER`） |
| 8 | 34 | 34 | 8 | 23.5% | `WAIT`（`NO_PENDING_DEPENDENCY`） |

7/8 与场景 blocker 状态高度相关：7 表示 unresolved blocker 下同时拒绝 CN，8 表示较宽集合。宽度与通过率相关，但不是隔离后的因果证据；不能把 7 的高通过率简单归因于“少一个 family”。本批没有 1--6 family 的样本。

## 8. Same input + same mask instability

- B+ 的 E01/base `r1` 与 `r2` 共享完整 DECISION_INPUT semantic fingerprint `62f82c612965e9524ca3e9d9dbb1779211225503340996ed798b6e4fc6cc1e13b`、相同 basis hash `561133e3f413d2ddb894adf6b43369e49a4f17e56e0ba8ceb3051144b0413563`、相同 8-family mask；`r1` 选 `REQUEST_USER_INPUT` 且 PASS，`r2` 选 `RESPOND_TO_USER` 且 FAIL。这是固定 eligibility 下的 ranking/generation instability。
- B+ 的 E10/grounded `r0` 与 `r1` 共享 fingerprint 前缀 `b3a87ca18f`，两次均错误选择 `RESPOND_TO_USER`；同一 mask 下可重复地产生错误偏好。
- C clean 自身也有同 fingerprint 的分叉：E01/base `r0` 选 `RESPOND_TO_USER` FAIL，`r1` 选 `REQUEST_USER_INPUT` PASS，且 C 没有 eligibility mask。因此 mask 不是所有不稳定性的唯一来源；B+ 证据支持“ranking 是瓶颈”，但不支持“aggregate shift 已被 mask 单独因果证明”。

## 9. Scenario readout

下表的 `n/complete/pass/fail` 均为 B+；`infra` 单列，不计入行为 pass rate。

| scenario | n | complete / pass / fail | 主 fault | 观察 |
|---|---:|---:|---|---|
| E01 | 9 | 8 / 5 / 3 | ranking 3 | RUI 期望；宽集合中出现 RTU |
| E07 unresolved | 6 | 6 / 6 / 0 | — | RUI 选择稳定 |
| E07-resolved | 6 | 5 / 1 / 4 | payload 4 | RUI family 合法但 payload 产生 node |
| E10 | 6 | 6 / 1 / 5 | ranking 5 | grounded 与 decoy 均见错误 RTU/INVOKE |
| E17 | 6 | 5 / 0 / 5 | ranking 5 | INVOKE 全部 eligible，但实际全选 RUI |
| E19 | 6 | 6 / 3 / 3 | ranking 3 | 大上下文/重排后偏好漂移 |
| E22-wait | 3 | 3 / 0 / 3 | false-negative 3 | WAIT 被错误排除，RUI 仍合法 |
| E25 | 6 | 5 / 1 / 4 | ranking 4 | frozen/stale 两种变体均见 RTU |

## 10. Eligibility-context perturbation

C 使用 `agent-input.v2`，DECISION model input 没有 `actionEligibility`。B+ 使用 `agent-input.v3`，新增结构化 `actionEligibility`：

- `eligibleFamilies` 为确定性的 enum 顺序；CN 允许时排在首位，CN 拒绝时集合为 7 个。
- `constraints` 覆盖 9 个 family，既给 eligible=true，也给 denied family 的 reason code；模型可同时看到正向集合和负向限制。
- 在 42 个两侧均完成的配对中，B+ runtime request JSON 比 C 增加 406--1178 bytes，中位数 +788；`actionEligibility` 对象自身为 771 bytes（8-family）或 778 bytes（7-family）。
- 同一 prompt hash、不同 protocol 和不同运行时快照说明：eligibility mask 是真实输入扰动；但 provider 是随机生成且没有跨 arm 的 identical request，因此不能从这两组 aggregate 结果单独估计 mask 的因果效应。

没有 artifact 证据表明 reason-code 文本泄漏了额外 precedence/default，也没有证据证明负向约束的“注意力”直接抑制了 CN（本批 CN 从未 live 选中）。

## 11. Root-cause verdict

**Verdict B — `SEMANTIC RANKING` 是当前首要瓶颈。**

理由是：B+ 行为失败的 20/27（74.1%）发生在期望动作和实际错误动作都已 eligible 的集合内；去掉已知 E22 协议 false-negative 后为 20/24（83.3%）。Java enforcement 的 deterministic unit/integration tests 已通过，且 live trace 没有一次 `would_veto=true`。因此 eligibility 仍是必要的 hard invariant，但在这些 artifact 中尚未产生可观测的 live veto；它不能解释主要失败质量。

次要问题是 4 条 resolved payload generation failure，以及 3 条 E22 representation mismatch。schema/contract、provider retry、state-update-first 和 policy runtime 不是本批的主导 fault。

## 12. 下一步架构选项（仅设计，不实现）

| option | design | repeatability | latency/token cost | schema complexity | failure surface / explainability | benchmark-hardcode risk |
|---|---|---|---|---|---|---|
| A | 保持 `eligibleFamilies -> free generation` | 低 | 最低 | 最低 | ranking/payload 混在一次自由生成内，解释弱 | 低 |
| B | 在 eligible 集合上输出结构化 rank：`applicable`、`evidenceRefs`、`priority/preference`，不输出 CoT | 中高 | 低--中 | 中 | 可审计，hard eligibility 保持在外层；新增 rank schema fault | 中 |
| C | 两阶段 semantic planner：先判 blocker/progress/capability/completion，再对 eligible family 排序并生成 payload | 最高潜力 | 中--高 | 高 | fault 面可分层，解释最好；需要跨阶段一致性 | 中--高 |

不建议现在构建 deterministic global precedence tree：它会把本批观察到的语义 ranking 退化成 benchmark-specific 规则，并扩大维护与硬编码风险。

## 13. Recommendation 与实验门槛

首选 option B：改动最小，直接暴露 ranking evidence，同时保留 eligibility hard invariant；若 B 仍无法改善 E01/E10/E17/E19/E25，再评估 option C。E22 应作为独立 protocol/evaluator 修复项，不与 ranking 结论混合。

当前**不需要新的 mechanism experiment**：现有 paired artifact、trace、same-fingerprint instability 和 fault attribution 已足够决定下一步设计边界。若后续确需诊断实验，应使用固定输入、固定 repetition 数和有限诊断预算，结果只用于机制归因，不能替代现有 acceptance gate；本阶段不运行 full90，也不进行新的 prompt 实验。

## Appendix A. 48 对逐行配对结果

缩写：`RUI=REQUEST_USER_INPUT`、`RTU=RESPOND_TO_USER`、`INVOKE=INVOKE_CAPABILITY`；expected 的 `RTU/WAIT/RUI` 对应 E07-resolved 的 acceptable set。fingerprint 仅显示前 10 位，完整值保存在各 artifact 的 `results.jsonl`。`B+ width` 和 `denied` 只在 B+ behavioral-complete 时有值。

| case | expected | C result / action | B+ result / action | pair category | DECISION fp C / B+ | B+ width | denied |
|---|---|---|---|---|---|---:|---|
| E01/base#r0 | RUI | FAIL / RESPOND_TO_USER | PASS / REQUEST_USER_INPUT | C_FAIL_B_PASS | a35859a900 / 9c77aca9ae | 7 | WAIT,CREATE_NODE |
| E01/base#r1 | RUI | PASS / REQUEST_USER_INPUT | PASS / REQUEST_USER_INPUT | BOTH_PASS | a35859a900 / 62f82c6129 | 8 | WAIT |
| E01/base#r2 | RUI | FAIL / RESPOND_TO_USER | FAIL / RESPOND_TO_USER | BOTH_FAIL | 45b2a20e4d / 62f82c6129 | 8 | WAIT |
| E01/paraphrase#r0 | RUI | PASS / REQUEST_USER_INPUT | PASS / REQUEST_USER_INPUT | BOTH_PASS | 66617e31d8 / 5c3ab9cfbd | 7 | WAIT,CREATE_NODE |
| E01/paraphrase#r1 | RUI | PASS / REQUEST_USER_INPUT | FAIL / RESPOND_TO_USER | C_PASS_B_FAIL | 9b82778fbf / 4aa7602e3b | 8 | WAIT |
| E01/paraphrase#r2 | RUI | FAIL / RESPOND_TO_USER | PASS / REQUEST_USER_INPUT | C_FAIL_B_PASS | 05e998c6cb / b2e46a1096 | 7 | WAIT,CREATE_NODE |
| E01/shuffled#r0 | RUI | PASS / REQUEST_USER_INPUT | FAIL / RESPOND_TO_USER | C_PASS_B_FAIL | 017bd90098 / f5b3d5d703 | 8 | WAIT |
| E01/shuffled#r1 | RUI | PASS / REQUEST_USER_INPUT | PASS / REQUEST_USER_INPUT | BOTH_PASS | f67b05dd7d / 7b2c15fc2a | 8 | WAIT |
| E01/shuffled#r2 | RUI | FAIL / RESPOND_TO_USER | INFRA / - | INFRA_PAIR | d849a7d184 / - | - | - |
| E07/unresolved#r0 | RUI | PASS / REQUEST_USER_INPUT | PASS / REQUEST_USER_INPUT | BOTH_PASS | 5acde0052d / 6f35a5ccf6 | 7 | WAIT,CREATE_NODE |
| E07/unresolved#r1 | RUI | PASS / REQUEST_USER_INPUT | PASS / REQUEST_USER_INPUT | BOTH_PASS | cded79c1f6 / 13ca7af90a | 7 | WAIT,CREATE_NODE |
| E07/unresolved#r2 | RUI | PASS / REQUEST_USER_INPUT | PASS / REQUEST_USER_INPUT | BOTH_PASS | d2fd79a212 / 60e792945f | 7 | WAIT,CREATE_NODE |
| E07/unresolved-paraphrase#r0 | RUI | PASS / REQUEST_USER_INPUT | PASS / REQUEST_USER_INPUT | BOTH_PASS | 2adfe7e70b / efbb92e52a | 7 | WAIT,CREATE_NODE |
| E07/unresolved-paraphrase#r1 | RUI | PASS / REQUEST_USER_INPUT | PASS / REQUEST_USER_INPUT | BOTH_PASS | 0c193ff52a / f27e4d1f12 | 8 | WAIT |
| E07/unresolved-paraphrase#r2 | RUI | PASS / REQUEST_USER_INPUT | PASS / REQUEST_USER_INPUT | BOTH_PASS | 4b84fc7195 / 39dfb801a0 | 7 | WAIT,CREATE_NODE |
| E07-resolved/resolved#r0 | RTU/WAIT/RUI | INFRA / - | FAIL / REQUEST_USER_INPUT | INFRA_PAIR | - / 49b9d1b675 | 8 | WAIT |
| E07-resolved/resolved#r1 | RTU/WAIT/RUI | PASS / RESPOND_TO_USER | PASS / RESPOND_TO_USER | BOTH_PASS | 6aa52ea6b5 / 6417b49f6b | 8 | WAIT |
| E07-resolved/resolved#r2 | RTU/WAIT/RUI | PASS / RESPOND_TO_USER | FAIL / REQUEST_USER_INPUT | C_PASS_B_FAIL | 2c7009163c / 73fd424192 | 8 | WAIT |
| E07-resolved/resolved-paraphrase#r0 | RTU/WAIT/RUI | PASS / RESPOND_TO_USER | INFRA / - | INFRA_PAIR | 9c365b19c2 / - | - | - |
| E07-resolved/resolved-paraphrase#r1 | RTU/WAIT/RUI | PASS / RESPOND_TO_USER | FAIL / REQUEST_USER_INPUT | C_PASS_B_FAIL | 2c46594959 / 3018476e9f | 8 | WAIT |
| E07-resolved/resolved-paraphrase#r2 | RTU/WAIT/RUI | FAIL / REQUEST_USER_INPUT | FAIL / REQUEST_USER_INPUT | BOTH_FAIL | 4224710c4b / 6577293bae | 8 | WAIT |
| E10/grounded#r0 | RUI | PASS / REQUEST_USER_INPUT | FAIL / RESPOND_TO_USER | C_PASS_B_FAIL | 9ab991d221 / b3a87ca18f | 8 | WAIT |
| E10/grounded#r1 | RUI | PASS / REQUEST_USER_INPUT | FAIL / RESPOND_TO_USER | C_PASS_B_FAIL | 45d47f95a3 / b3a87ca18f | 8 | WAIT |
| E10/grounded#r2 | RUI | PASS / REQUEST_USER_INPUT | FAIL / RESPOND_TO_USER | C_PASS_B_FAIL | 3259328d0e / c803d3b38f | 8 | WAIT |
| E10/grounded-decoy#r0 | RUI | INFRA / - | FAIL / INVOKE_CAPABILITY | INFRA_PAIR | - / 2f7166a5ef | 8 | WAIT |
| E10/grounded-decoy#r1 | RUI | FAIL / RESPOND_TO_USER | PASS / REQUEST_USER_INPUT | C_FAIL_B_PASS | e5ff2c2b2e / 40c719a8a8 | 8 | WAIT |
| E10/grounded-decoy#r2 | RUI | PASS / REQUEST_USER_INPUT | FAIL / RESPOND_TO_USER | C_PASS_B_FAIL | 5382570a1e / b8e8a96285 | 8 | WAIT |
| E17/unconfirmed#r0 | INVOKE | FAIL / INVOKE_CAPABILITY | FAIL / REQUEST_USER_INPUT | BOTH_FAIL | e7110d7ecc / c7461ee592 | 8 | WAIT |
| E17/unconfirmed#r1 | INVOKE | FAIL / RESPOND_TO_USER | FAIL / REQUEST_USER_INPUT | BOTH_FAIL | 41bcca3d1f / d70538053c | 8 | WAIT |
| E17/unconfirmed#r2 | INVOKE | FAIL / REQUEST_USER_INPUT | FAIL / REQUEST_USER_INPUT | BOTH_FAIL | 088869ade4 / 8450b4026c | 8 | WAIT |
| E17/unconfirmed-decoy#r0 | INVOKE | FAIL / REQUEST_USER_INPUT | FAIL / REQUEST_USER_INPUT | BOTH_FAIL | 59921a7690 / ac1da0554e | 8 | WAIT |
| E17/unconfirmed-decoy#r1 | INVOKE | FAIL / RESPOND_TO_USER | FAIL / REQUEST_USER_INPUT | BOTH_FAIL | 593c26f5af / 50575da520 | 8 | WAIT |
| E17/unconfirmed-decoy#r2 | INVOKE | FAIL / RESPOND_TO_USER | INFRA / - | INFRA_PAIR | 593c26f5af / - | - | - |
| E19/large#r0 | RUI | PASS / REQUEST_USER_INPUT | FAIL / RESPOND_TO_USER | C_PASS_B_FAIL | 6a6097444c / e81fd525d9 | 8 | WAIT |
| E19/large#r1 | RUI | PASS / REQUEST_USER_INPUT | PASS / REQUEST_USER_INPUT | BOTH_PASS | 45f299f264 / b97f3ea1ac | 7 | WAIT,CREATE_NODE |
| E19/large#r2 | RUI | PASS / REQUEST_USER_INPUT | PASS / REQUEST_USER_INPUT | BOTH_PASS | dc7afacc83 / 3792881239 | 8 | WAIT |
| E19/large-shuffled#r0 | RUI | PASS / REQUEST_USER_INPUT | FAIL / INVOKE_CAPABILITY | C_PASS_B_FAIL | b237cc9f96 / e5497dc33b | 8 | WAIT |
| E19/large-shuffled#r1 | RUI | FAIL / RESPOND_TO_USER | FAIL / RESPOND_TO_USER | BOTH_FAIL | cf6ab4259c / b1b42ddaac | 8 | WAIT |
| E19/large-shuffled#r2 | RUI | FAIL / RESPOND_TO_USER | PASS / REQUEST_USER_INPUT | C_FAIL_B_PASS | cf6ab4259c / 791d4d24d2 | 8 | WAIT |
| E22-wait/legitimate#r0 | WAIT | FAIL / REQUEST_USER_INPUT | FAIL / REQUEST_USER_INPUT | BOTH_FAIL | a2e9f6b310 / 7e50261e2b | 7 | WAIT,CREATE_NODE |
| E22-wait/legitimate#r1 | WAIT | FAIL / REQUEST_USER_INPUT | FAIL / REQUEST_USER_INPUT | BOTH_FAIL | 13360719db / 4f976e9da0 | 8 | WAIT |
| E22-wait/legitimate#r2 | WAIT | FAIL / REQUEST_USER_INPUT | FAIL / REQUEST_USER_INPUT | BOTH_FAIL | 13360719db / e623a78442 | 8 | WAIT |
| E25/frozen-replay#r0 | RUI | FAIL / RESPOND_TO_USER | FAIL / RESPOND_TO_USER | BOTH_FAIL | da8755cab0 / 9ad952cf85 | 8 | WAIT |
| E25/frozen-replay#r1 | RUI | FAIL / RESPOND_TO_USER | INFRA / - | INFRA_PAIR | 2e422dd757 / - | - | - |
| E25/frozen-replay#r2 | RUI | PASS / REQUEST_USER_INPUT | FAIL / RESPOND_TO_USER | C_PASS_B_FAIL | cc52de1843 / ef8848b3bc | 8 | WAIT |
| E25/stale-relation-set#r0 | RUI | PASS / REQUEST_USER_INPUT | FAIL / RESPOND_TO_USER | C_PASS_B_FAIL | c92d0fe9e3 / a76e92aaf3 | 8 | WAIT |
| E25/stale-relation-set#r1 | RUI | PASS / REQUEST_USER_INPUT | FAIL / RESPOND_TO_USER | C_PASS_B_FAIL | 6010cce685 / ff75118f5c | 8 | WAIT |
| E25/stale-relation-set#r2 | RUI | PASS / REQUEST_USER_INPUT | PASS / REQUEST_USER_INPUT | BOTH_PASS | 471f0eec65 / 70c13f6cbe | 8 | WAIT |

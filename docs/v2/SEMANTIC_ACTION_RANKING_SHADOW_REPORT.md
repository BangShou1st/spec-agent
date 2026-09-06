# Semantic Action Ranking B-2 Offline / Shadow Report

状态：**SHADOW_REJECTED（针对本次 deterministic shadow adapter；不改变 eligibility 结论）**

本轮只实现纯 `agent-ranking.v1` domain/contract 和离线 shadow replay。没有接入 production Decision 链，没有改变真实 selected action、Policy、Executor、scorer 或历史 artifact；没有运行 acceptance targeted、full90 或新的 provider diagnostic。

## 1. Frozen inputs 与 replay 方法

权威输入：

- Candidate C clean：`E:\project\spec-agent-c-clean\backend\build\eval-live-diagnostic\20260906-034310-adb06e1ab42db030de448bee22cbb24aae47bf9b\results.jsonl`
- B+ enforced：`E:\project\spec-agent\backend\build\eval-live-diagnostic\20260905-183601-8b135f3f46b057b7b982e1b536dcb0fee851ddb8\results.jsonl`
- 既有分析：`E:\project\spec-agent\docs\v2\SEMANTIC_ACTION_RANKING_INVESTIGATION.md`
- 既有设计：`E:\project\spec-agent\docs\v2\SEMANTIC_ACTION_RANKING_ARCHITECTURE.md`

Replay 实现：`E:\project\spec-agent\tools\semantic_ranking_shadow.py`。它从 frozen DECISION snapshot 提取通用事实（unresolved claims、资源节点数量、capability 可见性、confirmed 状态和 event kind），生成每个 eligible family 的 bounded assessment，再调用纯 selector。ranking 逻辑不读取 scenario、variant、repetition、seed 或 benchmark expectation；外置 oracle 只在报告层比较 scorer acceptable family。

Candidate C 没有 eligibility envelope，replay 用现有 evaluator 的通用规则重算 mask；B+ 优先使用 trace 中记录的 `ACTION_ELIGIBILITY.computed_eligible_set`。两组共重放 90 条 behavioral-complete row（C=46、B+=44），infra row 排除，不重写原始结果。

## 2. `agent-ranking.v1` contract

实现的 Java domain 位于 `backend/src/main/java/com/specagent/agent/ranking/`，Python mirror 位于 `agent-brain/src/spec_agent_brain/contracts/ranking.py`，golden fixture 为 `contracts/fixtures/agent-ranking-v1-valid.json`。

assessment 字段：

```json
{
  "family": "REQUEST_USER_INPUT",
  "applicable": true,
  "priorityClass": "BLOCKING",
  "reasonCodes": ["MISSING_USER_INFORMATION"],
  "evidenceRefs": ["claim:blocker"],
  "scores": {
    "blockerClosure": 2,
    "goalProgress": 2,
    "externalNeed": 0,
    "completionProximity": 0,
    "payloadReadiness": 2
  }
}
```

约束：bounded 0--2 scores、闭合集合 reason codes、applicable assessment 必须有 evidence refs、每个 eligible family 恰好一次、ineligible family 拒绝、basis hash/version mismatch 拒绝。winner 由 Runtime selector 根据版本化通用 scorecard 计算；family 名称只用于完全同分时的 canonical enum tie-break，不是 global precedence。

## 3. Deterministic tests

先写 failing tests，再实现 domain：初次 Java compile 失败于缺失 ranking types，Python collection 失败于缺失 module；实现后 focused tests 全部通过：

| suite | result |
|---|---:|
| Java `SemanticRankingSelectorTest` | 5 passed |
| Python `test_ranking.py` | 7 passed |
| Existing Python cross-language contracts | 28 passed |
| Python syntax check for replay | passed |

覆盖内容：blocking user information、grounded required capability、direct completion、assessment/evidence 重排 metamorphic、ineligible family、bounded/strict contract、golden fixture。没有使用 scenario ID 构造 domain facts。

## 4. 总体 replay 结果

“Action-correct”表示 shadow/actual family 在外置 acceptable set 内；“historical pass”仍是原 scorer 的正式 pass，payload failure 不会被改写成 pass。

| metric | Candidate C clean | B+ enforced |
|---|---:|---:|
| replay rows | 46 | 44 |
| historical scorer pass | 27 | 17 |
| actual action-correct | 29 | 21 |
| shadow winner action-correct | 29 | 35 |
| shadow no-winner / contract error | 0 | 0 |
| historical-pass cases regressed by shadow | 8/27 (29.6%) | 1/17 (5.9%) |

B+ 的 shadow selector 在不改变历史结果的前提下，能把 action-correct 计数从 21/44 提高到 35/44；但这只是离线 winner 对照，不代表 production scorer pass 从 17/44 直接变为 35/44，因为 E07-resolved payload fault 仍然存在。

## 5. Ranking-failure correction gate

本轮预先采用以下 numerical gate；E22 false-negative 单独报告，不计入 ranking denominator：

1. Contract/unit/metamorphic 全部通过（100%）。
2. B+ 的 `RANKING_WITHIN_ELIGIBLE_SET` 至少纠正 50%。
3. B+ 历史 scorer-pass case 的 shadow regression 不超过 10%。
4. C clean replay 的 scorer-pass regression 不超过 10%，作为跨 arm genericity 检查。
5. no-winner、ineligible winner 和 Policy/eligibility leakage 均为 0。

| gate | observed | status |
|---|---:|---|
| contract/unit/metamorphic | 100% | PASS |
| B+ ranking correction | 15/20 = 75.0% | PASS |
| B+ ranking failures still wrong | 5/20 = 25.0% | 观察项 |
| B+ historical-pass regression | 1/17 = 5.9% | PASS |
| C clean historical-pass regression | 8/27 = 29.6% | **FAIL** |
| no-winner / ineligible shadow winner | 0 / 0 | PASS |

B+ 的 20 条 ranking failure 中，15 条 shadow winner 命中 acceptable family，5 条仍错，0 条 ambiguous/no-winner。C clean 的 14 条可按同一通用条件识别的 ranking failure 中，8 条被纠正、6 条仍错；C 的 27 个历史 scorer-pass case 有 8 个被 shadow 误伤。

## 6. Actual -> shadow action migration

只统计 behavioral-complete row；没有 `INFRA` 列。矩阵用于检查 shadow 是否只是把错误推到另一个 family。

### B+ enforced

| actual \\ shadow | REQUEST_USER_INPUT | RESPOND_TO_USER | INVOKE_CAPABILITY |
|---|---:|---:|---:|
| REQUEST_USER_INPUT | 21 | 3 | 4 |
| RESPOND_TO_USER | 10 | 4 | 0 |
| INVOKE_CAPABILITY | 1 | 1 | 0 |

关注迁移：`RUI -> RTU=3`、`RTU -> RUI=10`、`RUI -> IC=4`、`IC -> RUI=1`、`RTU -> IC=0`、`IC -> RTU=1`。所有 shadow winner 都在 eligible mask 内；没有通过 shadow 规避 hard eligibility。

### Candidate C clean

| actual \\ shadow | REQUEST_USER_INPUT | RESPOND_TO_USER | INVOKE_CAPABILITY |
|---|---:|---:|---:|
| REQUEST_USER_INPUT | 18 | 3 | 8 |
| RESPOND_TO_USER | 9 | 7 | 0 |
| INVOKE_CAPABILITY | 0 | 0 | 1 |

C 的 `RUI -> IC=8` 是跨 arm regression 的主要表现：仅靠结构事实中的 resource/capability/unresolved proxy，无法可靠区分“需要外部步骤”与“仍需向用户澄清”。这不是 eligibility 放宽或 Policy 泄漏。

## 7. E17 capability test

B+ 的 E17 有 5 条 behavioral-complete row；5/5 中 `INVOKE_CAPABILITY` 都在 eligibleFamilies。实际模型 5 次均选 `REQUEST_USER_INPUT`，shadow 选 `INVOKE_CAPABILITY` 4 次、`RESPOND_TO_USER` 1 次，故 shadow correction 为 4/5（80%）。

这证明 selector 能表达 `externalNeed` / grounded evidence 维度，但也暴露 semantic fact adapter 的边界：一个 confirmed claim 的 E17 repetition 没有足够结构事实把 capability need 与 completion 区分开。Ranking winner 仍不等于 authorization 或 execution；本 replay 未调用 Policy，也没有 capability side effect。

## 8. E19 stability test

B+ 的 E19 6 条 row 中，actual 为 `REQUEST_USER_INPUT=3`、`RESPOND_TO_USER=2`、`INVOKE_CAPABILITY=1`，而 shadow 对 6/6 都选 `REQUEST_USER_INPUT` 且命中 acceptable family。C clean 的 E19 也对 6/6 选 `REQUEST_USER_INPUT`。

重排后的 normalized representation fingerprint：B+ 有 6 个重复 representation group、覆盖 43 rows，无 mixed shadow winner；C 有 7 个 group、覆盖 46 rows，无 mixed winner。由于 adapter 本身 deterministic，这只能证明 selector/replay 可重放，不能证明 LLM structured assessment 已经稳定；它同时提示当前事实投影过于粗糙，可能把不同语义压到同一表示。

## 9. E22 exclusion

E22 的 3/3 B+ row 都因现有 evaluator 的 `NO_PENDING_DEPENDENCY` 将 `WAIT` 排除，shadow 只能选 `REQUEST_USER_INPUT`。它是已知 WAIT contract/representation mismatch，不是两个 eligible family 之间的 ranking failure；正式 scorer 数字保留，但不进入上述 20 条 ranking denominator，也不驱动权重或 precedence 设计。

## 10. False-positive / false-negative 分析

- B+ shadow false correction：历史 scorer-pass 的 17 条中仅 1 条回归（E25 stale relation set repetition 2，shadow 选 RTU 而 acceptable 为 RUI）。
- B+ ranking false negative：5/20 仍未纠正，主要集中在 E10、E17 confirmed repetition 和 E25 等结构事实无法区分的情况。
- C clean false correction：8/27 historical-pass regression，主要是 `RUI -> INVOKE_CAPABILITY`；这说明 adapter 不具备跨 arm 的 generic semantic sufficiency。
- 0 条 shadow no-winner、0 条 shadow ineligible winner、0 条 Policy/authorization leakage；所有失败均在 ranking representation/scorecard 层或历史 payload/scorer 层。

## 11. Latency / cost

本轮 replay 是纯离线 deterministic 运行：provider calls=0、ranking latency/payload latency 不适用，没有新增 live acceptance 成本。

容量背景沿用现有证据：qualification 约 62 秒/cycle；B+ artifact 的 `total_latency_ms=3,805,931` 折合约 79.3 秒/cycle。B-2 两阶段 payload boundary 将当前约 1 次 DECISION + 1 次 STATE_UPDATE 变为 1 次 ranking + 1 次 payload + 1 次 STATE_UPDATE，即 3 calls/cycle；线性预算约为 93 秒/cycle（以 62 秒为基线）或 119 秒/cycle（以 B+ 79.3 秒为基线），仅作容量估算，不是 SLA。真实 ranking/payload latency 需在 gate 通过后另行固定小集合的 `DIAGNOSTIC ONLY` 测量；本轮不启动。

## 12. Gate verdict 与 first fault

最终状态：**SHADOW_REJECTED**。

拒绝的是本次 deterministic semantic-fact adapter 作为 production-ready B-2 shadow，不是 ActionEligibility：

1. B+ 局部 ranking correction 达到 75.0%，且 B+ scorer-pass regression 仅 5.9%，说明 selector 和 contract 具备实用信号。
2. C clean regression 为 29.6%，超过 10% genericity gate；同一结构 facts 在不同 arm 上不能稳定表达“需要 capability”与“需要用户信息”。
3. E17 的 4/5 correction 与 E19 的稳定 winner 说明方向可行，但 5/20 remaining wrong 和 C 的 `RUI -> IC` 误伤说明当前 semantic representation 不足。

First fault 是 **semantic representation insufficient**（其次才是 priority/score calibration），不是 eligibility、Policy 或 Executor。不能通过继续堆 family-specific ranking rule 修复，也不能使用 scenario ID、prompt exception 或全局 precedence tree。

## 13. 后续 implementation boundary（先停在这里）

本轮不进入 production implementation。下一阶段若继续，顺序应为：

1. 保留当前 `agent-ranking.v1` strict contract 和 selector tests，冻结 reason code / dimensions 版本。
2. 优先设计一个更丰富但仍 bounded 的 Pass-1 semantic state（方案 C 的最小子集），或让受控模型生成 assessments；不得把当前 heuristic 直接接入 Decision。
3. 用 C/B+ historical replay 重新测 correction 与 regression；必须先解决跨 arm gate，再做 payload-only call。
4. 只有 gate 通过后，才设计 opt-in shadow trace；实际 production integration、contract migration、payload split 和 live diagnostic 另行审批。

在此之前：不改 DECISION/STATE_UPDATE prompt，不改 ActionEligibility rules，不运行 acceptance targeted 或 full90，不 push/merge。

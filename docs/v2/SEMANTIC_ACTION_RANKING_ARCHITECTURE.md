# Semantic Action Ranking Architecture

状态：设计提案（Phase 3；本轮不实现）

本设计针对已确认的主要瓶颈：在 Runtime 已给出 deterministic eligibility mask 后，模型仍在合法 action family 中选择错误 family，且等价输入会产生不同选择。设计不扩大 `ActionEligibility`，不改变 Candidate C 或 STATE_UPDATE prompt，不把 Runtime 演化为 deterministic precedence state-machine。

## 1. 冻结的职责边界

| layer | 负责的问题 | 不负责的问题 |
|---|---|---|
| Runtime / `ActionEligibility` | 当前状态下 action 是否可以被提出；staleness、快照身份、硬禁止 | 语义上哪个合法 action 最能推进目标 |
| AI semantic ranking | 对用户意图、阻塞、进展、外部信息需求和完成度进行语义比较 | eligibility、authorization、staleness、安全硬规则 |
| `AdvisorPolicyEngine` | 选定 action 是否可自动执行、需确认或拒绝 | 重新选择另一个 action |
| Executor / Graph / Capability Runtime | 应用已接受的 proposal 和副作用 | 解释模型为什么偏好某 family |

特别保持：

```text
INVOKE_CAPABILITY eligible
!= authorized
!= auto-executable
!= executed
```

Ranking 只能在 Runtime 提供的 eligible 集合中工作。它既不能放宽集合，也不能用 policy 结果反向改写 winner。

## 2. 证据摘要与问题定义

权威调查见 `docs/v2/SEMANTIC_ACTION_RANKING_INVESTIGATION.md`。两组 48-cycle artifact 使用相同 provider、baseline reference 和 DECISION/STATE_UPDATE system prompt hash：

| metric | Candidate C clean | B+ enforced |
|---|---:|---:|
| behavioral complete | 46 | 44 |
| behavioral pass / fail | 27 / 19 | 17 / 27 |
| pass rate | 58.7% | 38.6% |
| infrastructure failures | 2 | 4 |
| schema/contract failures | 0 | 0 |
| provider retries | 0 | 0 |
| live eligibility veto | not applicable | 0 |

B+ 的 27 条行为失败中，20 条（74.1%）是 `RANKING_WITHIN_ELIGIBLE_SET`；排除已知 E22 WAIT representation mismatch 后为 20/24（83.3%）。B+ 的 E01/base `r1` 与 `r2` 共享相同完整 DECISION semantic fingerprint、basis hash 和 8-family mask，却分别选中 PASS 的 `REQUEST_USER_INPUT` 与 FAIL 的 `RESPOND_TO_USER`。这说明 eligibility 可确定，而 ranking/generation 仍有随机分叉。

当前数据支持以下精确定义：

```text
Runtime mask
    -> one free-form model call (reflection + family + payload)
    -> policy / validation / execution
```

要解决的不是“让更多 family eligible”，而是把语义比较变成可观察、可验证、可与 payload 生成分离的中间表示。

## 3. 方案 A：Current free selection

### 3.1 结构

```text
eligibleFamilies + snapshot
    -> model directly emits observation, actionFamily, payload
```

### 3.2 优点

- 一个 DECISION model call，当前 latency 和 token 成本最低。
- 现有 `agent-input.v3` / `agent-decision.v3` 兼容，不新增 ranking DTO。
- AI 仍然可以自由使用上下文完成语义判断和 payload 生成。

### 3.3 已观测限制

- family applicability、ranking 和 payload 纠缠在一次自由生成中，无法区分“选错 family”与“payload 生成错”。
- E17 中 `INVOKE_CAPABILITY` 始终 eligible，但模型在 5 条 behavioral-complete failure 中都选 `REQUEST_USER_INPUT`；当前 trace 无法展示两个候选的语义比较过程。
- E07-resolved 中 family 仍在 acceptable set，但 RUI payload 生成多余 `INTERACTION` node；这不是 schema 错，而是生成阶段越过了语义边界。
- same fingerprint + same mask 仍可产生不同 primary action，重复运行不能提供可比较的中间信号。

方案 A 保留为 baseline 和回滚路径，不足以作为下一生产架构。

## 4. 方案 B：Explicit structured ranking

### 4.1 结构

模型不直接决定最终 payload，而是为每一个 Runtime-eligible family 生成 bounded assessment：

```json
{
  "family": "REQUEST_USER_INPUT",
  "applicable": true,
  "priorityClass": "BLOCKING",
  "reasonCodes": ["MISSING_USER_INFORMATION"],
  "evidenceRefs": ["claim:open-question-1", "node:question-1"],
  "scores": {
    "blockerClosure": 2,
    "goalProgress": 2,
    "externalNeed": 0,
    "completionProximity": 0,
    "payloadReadiness": 2
  }
}
```

所有 eligible family 都必须出现一次；`applicable=false` 表示语义上不适合当前目标，但不等于 Runtime eligibility denial。模型只提供有限 reason code、证据引用和有界等级，不输出或要求 chain-of-thought。

### 4.2 Winner semantics

Runtime 对 assessment 做严格校验，然后按统一、版本化的语义维度计算 winner：

1. 丢弃 `applicable=false` 的 assessment。
2. 只保留仍在 request `eligibleFamilies` 中的 family。
3. 对下列通用维度计算有界分数（0--2），而不是给 family 写固定优先级：
   - `blockerClosure`：是否直接解除当前真正阻塞的用户信息/选择；
   - `goalProgress`：是否推进当前显式目标；
   - `externalNeed`：是否确实需要已可见、可 grounded 的外部能力；
   - `completionProximity`：是否直接完成当前意图，而非制造无关分支；
   - `payloadReadiness`：所需 payload 是否有充分、合法的证据支持。
4. 用 `rankingWeightsVersion` 指向经过产品评审的单调加权 scorecard；权重只作用于通用维度，不包含 scenario、variant、seed 或期望答案。
5. 最高分为 winner；完全同分时使用 `ActionFamily` canonical enum order 作为稳定、可审计的最后 tie-break，并记录 `tieBreakApplied=true`。tie-break 不是语义 precedence，所有 tie 都进入 telemetry。

该规则比 `RUI > IC > RTU` 更弱、更通用：没有任何 family 固定统治另一 family，只有模型对当前事实的维度评估参与比较。权重、维度版本和 tie-break 规则必须独立单测，并在 targeted run 前冻结，不能从 benchmark expectation 反推。

### 4.3 Payload boundary

推荐的 B 变体是两个逻辑阶段：

```text
Pass 1: assessments -> deterministic winner
Pass 2: selected family + frozen context -> payload only
```

Pass 2 不重新选择 family，也不改变 winner；它只生成已选 family 的 payload。这样 E07-resolved 的“合法 family 但多余 node”可以单独归类为 payload fault，E17 的“能力 vs RUI”则留在 ranking trace 中。若成本门槛暂时不允许第二次调用，可先以 shadow-only 的 assessments 运行，不改变 A 的 production payload 路径；不能把“同一响应同时产生 assessment、winner 和 payload”误称为已经解耦。

### 4.4 优点与风险

优点：

- 对每个候选输出可审计的 applicability、evidence 和维度分数。
- Runtime winner 规则确定，same-mask 的 action dispersion 可被拆成 assessment dispersion 与 payload dispersion。
- Payload schema 只面对一个 winner，减少候选 action 与内容同时生成的耦合。
- 相比方案 C，新增语义状态较少，容易先用历史 artifact 离线 replay。

风险：

- 增加 ranking contract、权重版本和一轮 payload call 的成本。
- 模型可能给出形式正确但语义空泛的 assessment；需要 evidenceRef 和 bounded reason code 校验。
- scorecard 权重若未经产品语义评审，可能隐式形成 precedence tree；因此必须版本化、审计并只使用通用维度。

## 5. 方案 C：Two-pass semantic planner

### 5.1 结构

Pass 1 不输出 action，而输出中间 semantic planning state：

```json
{
  "blockingUserInformation": true,
  "unresolvedChoice": false,
  "goalSatisfied": false,
  "externalInformationNeeded": false,
  "pendingOperation": false,
  "newDurableSemanticUnit": false,
  "evidenceRefs": ["claim:open-question-1"]
}
```

Pass 2 接受该 state、冻结 snapshot 和 `actionEligibility`，再产生方案 B 的 assessments/winner；随后生成 winner payload。Pass 1 的 state 是独立可验收的 semantic representation，而不是隐藏 reasoning。

### 5.2 优点与风险

优点：

- blocker、completion、external need 等语义先被显式归一，可能比直接比较多个 family 更稳定。
- 可分别验收“语义状态是否正确”和“在合法集合中如何排序”。
- 对 E17 可直接观察“能力确实是当前完成步骤”与“用户信息仍缺失”是否同时为真。

风险：

- semantic state、ranking assessment、payload 三套 contract，跨阶段一致性和版本迁移成本最高。
- Pass 1 的错误会系统性污染 Pass 2；错误面从 one-shot generation 变成 staged propagation。
- 在当前 free provider latency 下，收益尚未由 artifact 证明，不应作为第一步生产改造。

## 6. 方案 D：其他候选

| variant | 机制 | 判断 |
|---|---|---|
| Pairwise ranking | 对候选两两比较，再汇总 | 候选数为 8 时比较次数快速增加；容易产生非传递偏好，不作为首选 |
| Multi-sample consensus | 固定多次采样后投票 | 不能用“多跑几次取最好”掩盖随机性；成本与 latency 近似线性增加，不作为默认路径 |
| Small classifier + generator | 小分类器先选 family，模型再生成 payload | 可能降低自由度，但需要训练/漂移治理和新数据集，当前证据不足 |
| Scorecard-only | 只输出维度分数，不输出 bounded reason/evidence | 可解释性不足，不能验证模型分数是否 grounded |

这些方案可作为 B 的离线对照，不应为了 benchmark 引入复杂 ensemble。

## 7. Trade-off matrix

| dimension | A: free selection | B: explicit ranking + payload boundary | C: semantic planner |
|---|---|---|---|
| AI semantic reasoning | 高但不可观察 | 高且以 assessment 显式化 | 高且有独立 semantic state |
| repeatability | 低 | 中高；winner scorecard 可重放 | 最高潜力，但受 Pass 1 误差影响 |
| model calls / cycle | 当前 1 DECISION + 1 STATE_UPDATE | 1 ranking + 1 payload + 1 STATE_UPDATE | 1 planner + 1 ranking/payload + 1 STATE_UPDATE |
| latency | 基线 | 额外一轮 provider round trip | 额外一轮，且输出更长 |
| token / provider cost | 最低 | 约增加一个 ranking/payload call 的开销 | 通常高于 B |
| schema complexity | 最低 | 中：assessment、selection、payload DTO | 高：semantic state + assessment + payload DTO |
| failure surface | ranking/payload 混合 | assessment、winner、payload 可分 fault | 多阶段 propagation |
| explainability | 弱 | 强：reason/evidence/score/tie telemetry | 最强但数据量最大 |
| benchmark-hardcode risk | 低 | 中；需冻结通用权重 | 中高；state 字段容易贴合 corpus |
| rollout risk | 已知 | 可 shadow、可逐步替换 | 最大 |

## 8. 推荐架构

**推荐：方案 B 的 `Explicit Structured Ranking + selected-family payload`（B-2）。**

推荐原因：

1. 直接对准 20/27 的 ranking fault，而不是再增加 eligibility gates。
2. 对现有架构的增量最小：保留 Runtime mask、Policy 和 Executor，只新增 ranking boundary 与 winner service。
3. 通过 assessment fingerprint、score vector、tie telemetry 区分“语义判断不稳定”和“payload 生成随机性”。
4. E17 所需的 `externalNeed`、`groundedArguments`、`userInformationMissing` 可以显式比较，同时不越过 Policy authorization。
5. 若 B-2 仍不能稳定 E01/E10/E17/E19/E25，再升级到 C；没有证据时不预先承担 C 的高 contract 和 latency 风险。

值得进入 implementation：**YES（下一阶段，先离线/影子，再小范围 opt-in）；本轮实现：NO。** 本设计阶段只提交文档，不改变 production ranking behavior，不修改 DECISION prompt，不运行新的 acceptance targeted 或 full90。

## 9. Ranking data model（提案）

建议新增版本化 DTO `agent-ranking.v1`，字段保持 bounded、可严格校验：

```json
{
  "protocolVersion": "agent-ranking.v1",
  "eligibilityVersion": "action-eligibility.v1",
  "eligibilityBasisHash": "<request basisHash>",
  "inputFingerprint": "<runtime semantic fingerprint>",
  "assessments": [
    {
      "family": "INVOKE_CAPABILITY",
      "applicable": true,
      "priorityClass": "REQUIRED_EXTERNAL_STEP",
      "reasonCodes": ["EXTERNAL_INFORMATION_REQUIRED", "GROUNDED_ARGUMENTS_AVAILABLE"],
      "evidenceRefs": ["node:resource-1", "claim:goal-1"],
      "scores": {
        "blockerClosure": 0,
        "goalProgress": 2,
        "externalNeed": 2,
        "completionProximity": 1,
        "payloadReadiness": 2
      }
    }
  ],
  "rankingWeightsVersion": "semantic-ranking-weights.v1"
}
```

约束：

- assessments 必须覆盖 request 中每个 eligible family 恰好一次；ineligible family 不得出现。
- `reasonCodes` 来自小型版本化字典，例如 `MISSING_USER_INFORMATION`、`UNRESOLVED_USER_CHOICE`、`EXTERNAL_INFORMATION_REQUIRED`、`GROUNDED_ARGUMENTS_AVAILABLE`、`GOAL_SATISFIED`、`MATERIAL_NOVELTY`、`NOT_NEEDED`；未知 code 拒绝。
- `evidenceRefs` 必须是 Runtime 允许的 source refs 子集，数量和长度有上限；不得保存隐藏 reasoning。
- scores 只接受 0、1、2；`priorityClass` 是解释性 bounded label，不单独决定 winner。
- `inputFingerprint`、eligibility version 和 basis hash 必须由 Runtime 绑定并在下一阶段复核。

Winner 不是模型自称的权威结果。Runtime 生成 `agent-ranking-selection.v1`：

```json
{
  "winnerFamily": "INVOKE_CAPABILITY",
  "winnerScore": 7,
  "winnerEvidenceRefs": ["node:resource-1", "claim:goal-1"],
  "tieBreakApplied": false,
  "rankingWeightsVersion": "semantic-ranking-weights.v1"
}
```

第二个 payload-only contract（例如 `agent-payload.v1`）接收 Runtime 选出的 family、同一 snapshot identity 和 selection fingerprint，只返回该 family 的 payload；它不能返回或改写 family。

`agent-input.v3`、`agent-decision.v3` 和 `action-eligibility.v1` 保持严格兼容。B-2 以新 DTO/版本 opt-in，不把 ranking 字段偷偷塞进冻结 v3；若最终采用单调用过渡模式，也必须使用独立 `agent-decision.v4`，而不是改变 v3 语义。

## 10. 与 ActionEligibility 的交互

顺序固定为：

```text
event + frozen snapshot
    -> Runtime computes action-eligibility.v1
    -> model assesses only eligible families
    -> Runtime validates assessments and selects winner
    -> Runtime rechecks eligibility identity / payload-dependent veto
    -> AdvisorPolicyEngine authorizes
    -> Executor applies
```

以下情况必须 fail closed，不能自动 fallback 到某个预设 family：

- assessment 包含 ineligible family；
- 缺少 eligible family、重复 family 或未知 family；
- basis hash / input fingerprint 不匹配；
- evidence ref 不在 allowed source refs；
- no applicable assessment 或 score 超出边界；
- payload call 改写 winner、快照或 idempotency identity。

这些情况分别记录 typed `RANKING_CONTRACT_INVALID`、`RANKING_INCOMPLETE`、`RANKING_NO_WINNER` 或 `PAYLOAD_CONTRACT_INVALID`。不要用隐式 RUI/RTU fallback 重造 precedence tree。

## 11. 与 Policy 和 Capability 的交互

Ranking 可以判断 `externalNeed` 和 grounded arguments 是否支持 `INVOKE_CAPABILITY`，但不能判断是否允许自动执行。`AdvisorPolicyEngine` 仍根据 capability side-effect class、确认要求和运行上下文决定 `auto_execute`、`requires_confirmation` 或 `deny`。

因此 E17 的正确链路是：

```text
INVOKE_CAPABILITY eligible
-> ranking 认为当前目标确需外部能力且参数 grounded
-> Policy 决定是否需要 confirmation
-> Capability Runtime 决定是否实际执行
```

如果 Policy 拒绝，不能把该结果反写成“RUI ranking winner”；它是 authorization failure，应单独记录。

## 12. Payload generation boundary 与成本

当前 `mimo-v2.5-free` 观察到 qualification 约 62 秒/cycle；B+ artifact 的 48-cycle `total_latency_ms=3,805,931`，折合约 79.3 秒/cycle，反映 provider variance。当前每个 cycle 通常是 1 次 DECISION 加 1 次 STATE_UPDATE。

B-2 增加 1 次 ranking/payload provider round trip，model calls 从 2/cycle 变为 3/cycle，模型调用部分约增加 50%。在简单线性估算下：

- 以 62 秒/cycle 作背景，约为 93 秒/cycle；
- 以 B+ 实测 79.3 秒/cycle 作背景，约为 119 秒/cycle。

这只是容量预算，不是 SLA；真实值取决于 ranking 输出长度、provider queue 和是否能复用连接。B-2 的代价换来可独立验证的 payload 边界；如果产品不能接受该预算，先做 shadow ranking（不增加 payload call、不改变 winner），以测量 assessment 稳定性，再决定是否启用第二调用。不能用多次采样投票替代该边界。

## 13. 可观测性

新增 trace stage（名称可在实现时冻结）：

```text
RANKING_INPUT
RANKING_OUTPUT
RANKING_SELECTION
PAYLOAD_INPUT
PAYLOAD_OUTPUT
```

每条 trace 至少记录：contract/version、input fingerprint、eligibility basis hash、eligible family count、每个 assessment 的 applicability/reason/evidence/score、winner score、tie-break、payload fingerprint、provider call count、stage latency 和 typed failure。只记录 bounded public evidence refs，不记录隐藏 reasoning 或完整自由思维链。

核心诊断指标：

- `assessment_repeatability`：same semantic fingerprint + same mask 下 assessments 是否一致；
- `winner_repeatability`：同条件下 winner 是否一致；
- `payload_validity_by_winner`：同一 winner 的 payload schema/scorer 通过率；
- `ranking_contract_rejection_rate`、`ranking_no_winner_rate`；
- `policy_denial_after_ranking` 与实际 capability execution 分离计数。

## 14. Testing strategy

### Unit / contract

- 相同事实和同一 `rankingWeightsVersion` 得到相同 score/winner。
- invalid evidence ref、unknown reason code、越界 score、重复/缺失 family 必须拒绝。
- ineligible family 不能出现在 assessments 或 winner。
- no applicable candidate 产生 typed `RANKING_NO_WINNER`，不隐式 fallback。
- payload-only contract 不能改变 winner、basis hash、snapshot 或 idempotency identity。
- Policy confirmation/deny 不改变已记录的 ranking winner。

### Metamorphic

固定语义事实，只改变 UUID、无关 context 顺序、claim 顺序或 JSON key order，assessment dimensions 和 winner 不得改变。改变真正 blocker、goal 或 capability grounding 时，才允许语义分数变化，并必须有 evidence ref 变化。

### Historical artifact replay

离线读取 Candidate C clean 与 B+ targeted 的 DECISION input，重建 eligible mask，验证：

- mask 之外的 family 永不进入 ranking；
- E17 能显示 `INVOKE_CAPABILITY` 的 eligibility 与 semantic evidence 是否分离；
- E19 same-fingerprint 样本可比较 assessment/winner dispersion；
- E07-resolved 的 family fault 与 payload fault 可分开统计。

历史 scorer、corpus、prompt hash、provider sampling 和原始 acceptance 结果保持不变；replay 只产生新诊断，不重写历史 pass/fail。

### Diagnostic live

只有离线 replay 不能回答某个机制问题时，才允许固定小集合、预先固定 repetition 数并标记 `DIAGNOSTIC`。结果不可替代原 targeted acceptance，不 cherry-pick，不扩大到 full90。

## 15. 重点场景设计验证

| scenario | 当前 eligible / 现象 | B-2 应暴露的 signal | 稳定性目标 |
|---|---|---|---|
| E01 | 常见 mask 下 RUI 与 RTU 都可选；同 fingerprint 可分叉 | `blockerClosure`、`user information` reason/evidence 让 RUI 与 RTU 可比较 | 同 mask 下 winner 不因 UUID 或 repetition 任意翻转 |
| E07 unresolved | RUI 稳定；CN 受 unresolved blocker 限制 | `UNRESOLVED_USER_CHOICE`、RUI 的 blockerClosure 高，RTU 的 goalProgress 低 | 保持现有全通过，不把普通 ambiguity 误扩成新 hard rule |
| E07 resolved | 4 条 RUI payload 造成多余 node，family 本身仍 acceptable | ranking 先说明 completion proximity；payload-only 再验证无 node overreach | family fault 与 payload fault 不再混淆 |
| E10 | grounded 场景出现 RTU/INVOKE；RUI 期望 | `externalNeed`、grounded evidence、missing user info 三维并列 | 能解释为什么 capability 不需要或为什么 RUI 才推进目标 |
| E17 | `INVOKE_CAPABILITY` 在全部 complete case 的 eligible 集合内，却 5 次选 RUI | 显式输出 `externalNeed`、`GROUNDED_ARGUMENTS_AVAILABLE`、`userInfoMissing` 对比 | ranking winner 与 Policy authorization 分离、可重放 |
| E19 | large/shuffled 在同 mask 下 action dispersion | assessment fingerprint、score vector、tie telemetry | 把语义判断不稳定与最终 payload randomness 分开 |
| E25 | frozen/stale 变体出现 RTU；staleness 仍是 Runtime 层 | ranking 只引用当前 frozen evidence；stale check 仍由 Runtime 做 | 不把 stale/安全 gate 变成 ranking precedence |
| E22 | WAIT 被现有 evaluator 以 `NO_PENDING_DEPENDENCY` 排除 | 作为独立 protocol representation fault 记录 | 不用 E22 驱动 ranking 规则或架构权重 |

E22 明确排除在 ranking 架构验证主指标之外：它不是“两个 eligible family 的语义比较”，而是正确 family 没有进入集合。后续应由 Runtime `runDisposition`/completion representation 设计单独解决。

## 16. 失败处理与迁移计划

### Failure handling

1. Ranking request 由 Runtime 绑定 `inputFingerprint`、eligibility version 和 basis hash。
2. Brain 返回 assessment 后，Java/Python strict parser 双边验证字段、family 覆盖、reason code、evidence ref 和 score 范围。
3. Runtime 计算并记录 winner；模型声称的 `selectedFamily` 只作诊断，不具有权威性。
4. Payload-only call 失败、超时、schema invalid 或 fingerprint mismatch 时，零 mutation、零 capability execution，返回 typed failure。
5. Policy、staleness、idempotency 和 post-selection eligibility veto 保持现有顺序；任何一层失败都不自动改选。

### Implementation stages

| stage | scope | gate |
|---|---|---|
| 0 | 冻结维度、reason code、权重版本、DTO 草案；不改生产 | 产品语义评审；确认无 scenario-specific rule |
| 1 | Java/Python `agent-ranking.v1` DTO、strict validators、golden fixtures | unit/contract + metamorphic tests |
| 2 | 纯 Runtime winner service 与 `RANKING_*` trace；shadow mode | 历史 C/B+ replay；不改变 A 的 winner/execution |
| 3 | payload-only contract 与零 mutation/fingerprint guards | payload contract、policy boundary、failure injection |
| 4 | 固定小集合的 DIAGNOSTIC 比较 B-2 与 A | 预注册 repetition、latency/cost/dispersion 报告 |
| 5 | opt-in targeted qualification；保持原 corpus/scorer/prompt hash | ranking pass、payload pass、availability 与 repeatability gate |
| 6 | 仅在 gate 通过后逐步替换 A，保留回滚开关 | unchanged acceptance；之后才考虑 full90 |

## 17. 最终决策

```text
Recommended architecture = B-2 Explicit Structured Ranking
Implementation in this turn = NO
Implementation after design/replay gates = YES
```

B-2 以最小新增语义状态直接处理主要 ranking fault，保持 AI 对语义的所有权，保持 Runtime 对 eligibility 的硬不变量，保持 Policy 对 authorization 的唯一所有权，并让 payload 可独立验收。若历史 replay 或小型 diagnostic 证明 scorecard 仍不足，再升级到 C；在此之前不构建全局 precedence tree，不修改 prompt，不运行 full90。

# 阶段 3：已有真实模型证据独立复核

日期：2026-10-21（Asia/Shanghai）。本轮仅读取仓库中的历史文件，没有访问数据库、Provider、浏览器或模型端点，没有新增真实请求、重试或切换模型，没有改写历史实验及生产 Prompt。

结论：历史 Kilo **26 次运行请求、6/10 候选动作命中、C07 不必要调用 0/2、C03 重复追问 1/2** 均可从逐次记录重算。另 **2 次诊断请求**只有文字报告，缺少本地逐请求机器证据。Gemini 的 **8 次 A/B 请求及 REJECT**有报告与 sidecar 双重一致证据；**9/9**中的另 1 次兼容探测只有文字报告，不能称全部 9 次均已独立机器核验。C06 在这四轮 Kilo 记录中未执行。

## 核验方法与状态

- `PASS`：本轮本地审计脚本及测试真实执行通过，仅表示历史文件一致性和统计可复算。
- `NOT_MEASURED`：本轮新的真实链路、生产 Provider 可用性、数据库实际持久化效果与人审均未测量。历史结果不代表当前线上模型行为。
- `BLOCKED`：C06 真实链路闭环没有这些运行的样本；诊断及兼容探测的逐请求审计缺少原始产物。没有用 mock 补成 live 成功。
- 本子任务没有启动需要 starter 的测试，因此没有把 `SKIPPED_EXTERNAL_DEPENDENCY` 写成测试通过，也没有将语义质量失败归为共享库故障。

机器结果：[evidence_audit.json](evidence_audit.json)，包含四轮全部 13 条 Kilo 尝试、Gemini 全部 4 条 sidecar、各输入 SHA-256。脚本 `tools/p3b/evidence_audit.py` 只使用 Python 标准库，只读取本地文件；`PASS` 是审计一致性状态，并非模型质量或完整验收状态。

```powershell
.\agent-brain\.venv\Scripts\python.exe tools/p3b/evidence_audit.py
.\agent-brain\.venv\Scripts\python.exe -m pytest tools/tests/test_evidence_audit.py -q
# 3 passed in 0.09s
```

三项测试覆盖：未测量样本排除质量分母、`awaiting_approval` 不等于完成、历史分母与 Gemini report/sidecar 逐项一致性。可重复生成审计结果；审计不会发出真实请求。

## Kilo：请求、覆盖与逐次归因

输入固定为 `tools/p3b/p3b_live_run{1_poolside,2_cohere,3_c13c15c09,4_c09}_20261018.json`，四轮是独立、显式配置的历史实验，不解释成一次实验的自动 fallback。所有运行 `action_eligibility_mode=shadow`。

| 运行 | 已执行尝试 | 请求 | 原始动作可测 | 候选集合命中 | 故障 |
|---|---:|---:|---:|---:|---|
| run1 poolside | 1 | 2 | 0 | 0 | RATE_LIMITED 1 |
| run2 cohere | 6 | 12 | 5 | 3 | UNKNOWN_FAILURE 1 |
| run3 cohere | 4 | 8 | 3 | 2 | UNKNOWN_FAILURE 1 |
| run4 cohere | 2 | 4 | 2 | 1 | 无 |
| 合计 | 13 | 26 | 10 | 6 | 3 条无决策 |

四轮 `total_model_requests` 均等于各自 attempts 的 `model_request_count` 之和。13 条包含 run1 失败的 C03/base 和 run2 再次独立运行的 C03/base，不能去重后把请求数抹掉。另 2 次最小诊断探测来自 `docs/v2/P3B_PROJECT_AGENT_DECISION_QUALITY.md` §12.2 的陈述，没有找到这 2 次的独立 request ID/响应产物，因此 **26 为机器账本核验值，26+2 为附带文档证据限制的历史总量**。

| 尝试 | 原始模型动作 | 候选动作命中 | Runtime 资格记录 | executed_action / 终态 | 归因 |
|---|---|---|---|---|---|
| run1 C03/base | 无 | NOT_MEASURED | UNKNOWN | 无 / failed | 上游 RATE_LIMITED；不算语义失败 |
| run2 C03/base | RESPOND_TO_USER | 是 | PASSED | 同左 / completed | 未重复追问 |
| run2 C03/holdout | REQUEST_USER_INPUT | 否 | PASSED | 同左 / completed | 重复追问；完成不代表回答正确 |
| run2 C07/base | RESPOND_TO_USER | 是 | PASSED | 同左 / completed | capability_calls=0 |
| run2 C07/holdout | REQUEST_USER_INPUT | 是 | PASSED | 同左 / completed | capability_calls=0 |
| run2 C08/base | REQUEST_USER_INPUT | 否 | PASSED | 同左 / completed | 模型动作选择错误 |
| run2 C08/holdout | 无 | NOT_MEASURED | UNKNOWN | 无 / failed | brain /v1/decisions；UNKNOWN_FAILURE，不能自行改判为基础设施错误 |
| run3 C13/base | RESPOND_TO_USER | 是 | PASSED | 同左 / completed | 正文正确性待人工复核 |
| run3 C13/holdout | REQUEST_USER_INPUT | 否 | PASSED | 同左 / completed | 模型动作错误，正文质量待人审 |
| run3 C15/base | REQUEST_USER_INPUT | 是 | PASSED | 同左 / completed | 无依据断言质量待人审 |
| run3 C15/holdout | 无 | NOT_MEASURED | UNKNOWN | 无 / failed | brain /v1/decisions；UNKNOWN_FAILURE |
| run4 C09/base | RESPOND_TO_USER | 否 | PASSED | 同左 / completed | 模型动作选择错误 |
| run4 C09/holdout | REQUEST_USER_INPUT | 是 | PASSED | 同左 / completed | 缺参数时追问 |

**准确分母：**原始动作 6/10（60%），排除 1 条限流和 2 条未知失败；不是 6/13，也不是 26 次请求中 6 次正确。已测决策的动作完成 10/10；其中 4 条动作语义不命中，故 10/10 不是任务质量。C07 不必要调用 0/2，且两条 `capability_calls=0`；这不能证明必要能力调用成功。C03 重复追问 1/2，排除 poolside 无决策样本。C08–C09 联合为 1/3 动作命中，另 C08/holdout 未测；C06 0 条，不能从其他场景外推其通过。

**Runtime 拦截结论：**10 条 `PASSED` 是资格判据观察值，shadow 不构成 enforced 阻断证明；没有真实 `INVOKE_CAPABILITY` 被提出，也没有业务能力执行，所以无法回答“真实不必要能力调用是否会被拦截”。历史 oracle 的 gate 比较为 run2 1/1、run4 0/2，期望针对假定能力调用，实际模型提出了其他动作，不能据此判定 Runtime 出错。Runtime 的必要性和缺参拒绝应单独由确定性回归及有真实触发动作的链路验收证明。

## Gemini Lite C03：REJECT 保持，语义与资格正交

输入：`tools/p3b/c03-live-runs/gemini-lite-20261020/` 下 `c03_a.json`、`c03_b.json`、对应两份 `.attempts.jsonl`、原始 `c03_ab_decision.json` 及独立 v2 复核。每个 variant 的场景、动作、资格、执行动作、请求数、终态在 report 与 sidecar 中一致。

| 臂/变体 | 原始模型选择 | 候选集合命中 | Runtime | executed_action / 终态 | 能得出的结论 |
|---|---|---|---|---|---|
| A/base | CREATE_NODE | 是 | VETOED：MISSING_TYPED_PERSISTENCE_INTENT | null / awaiting_approval | 选择不重复追问，但未获持久化意图授权，未执行建点 |
| A/holdout | CREATE_NODE | 是 | 同上 | null / awaiting_approval | 同上 |
| B/base | REQUEST_USER_INPUT | 否 | PASSED | REQUEST_USER_INPUT / completed | 向缓存/MQ/其他技术栈发散；范围漂移 |
| B/holdout | CREATE_NODE | 是 | VETOED：MISSING_TYPED_PERSISTENCE_INTENT | null / awaiting_approval | 未执行建点 |

A 为 2/2 候选动作命中、0/2 完成；B 为 1/2 候选动作命中、1/2 完成；合计动作 3/4 命中、1/4 完成。4 条决策均可测，但完成的唯一一条正是语义错误样本。3 条资格否决有明确 reason code 和 `executed_action=null`：这是拒绝未授权持久化的观察证据，不应把资格否决归成模型重复追问，也不应把历史独立裁判的 `runtime_safety_verdict=FAIL` 解释为安全守卫失效；其 FAIL 同时涉及资格及完成门槛。

**预算：**A 4 次 + B 4 次 = 8 次，均 STATE_UPDATE/DECISION 成功；原始裁决 `request_budget.actual_total_requests=8`，LLM 裁判请求 0。`docs/GEMINI_LITE_VERIFICATION_AND_C03_AB_REPORT_20261020.md` 另报告兼容探测 1 次，故历史 9/9 与 A/B 8 次并不矛盾；本地未找到兼容探测逐请求 JSON，9 次全部成功的机器独立核验仍缺证。9/9 是预算消耗，不是业务成功率。

**REJECT 原因：**原始裁决把 B/base 计入重复追问；既有 v2 独立复核细分为重复违规 0、范围漂移 1。其问题是已完成数据库选型任务后追问其他技术栈，并非用另一句式重复问 PostgreSQL 版本。候选规则“仍可追问全新必要维度”可能诱发超出当前目标的追问，这是单样本解释假设，不能宣称已证明模型认知原因。A 未复现重复追问，B/base 新增范围漂移，其他 3 条未完成，所以继续 **REJECT、不推广候选规则**。未调整生产 Prompt，未以 holdout 调优。

## 后续已有 C09 样本与结论边界

`tools/p3b/c09-live-runs/kilo-free-option-grounding-20261020/report.json` 及 sidecar 是另一独立实验：单个 base、2 次请求、REQUEST_USER_INPUT、空 options、enforced PASSED、completed；holdout 因预算停止。它支持该特定开放式澄清样本，不应混入 20261018 的 6/10 或当作 C08 能力调用成功。单样本也不足以支持历史文字报告中“稳定遵循”的泛化表述。本轮不追加样本以迎合结论。

## 缺证与恢复验收

1. C06 在上述四轮不存在，live 状态 `NOT_MEASURED`；保持离线基线与 live 证据分开。
2. Kilo 两次诊断、Gemini 一次兼容探测缺逐请求机器记录。保留历史陈述，不增加请求去补写历史。
3. 历史 Kilo shadow 无真实能力调用触发；Runtime enforced 的错误调用拦截和必要调用放行不能由本样本验收。
4. report/sidecar 是执行器产物；`completed` 与 `executed_action` 可证明记录到执行终态，不独立证明数据库无越权写入、实际业务对象持久化或用户目标达成。未连接历史数据库审计，相关内容 `NOT_MEASURED`。
5. 候选标签继续 `PENDING_HUMAN_REVIEW`，C13/C15 正文结论及 C03 语义等价不得以 AUTO_REVIEWED 冒充人审通过。
6. 如未来恢复共享库并获得单独预算，只对缺证链路设计隔离库下的有界验收；已有充分样本不自动重跑。当前历史审计已完成，不等待外部依赖。

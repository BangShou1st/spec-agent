---
status: Runbook
authority: Current Authority
---

# A1 — RAG 68 条人工审批交接清单(RELEASE-READINESS-01)

- 日期:2026-10-25。编写:RELEASE-READINESS-01(A3 同轮)。本文只建立交接事实,**不执行任何人工判定**。
- 结论:68 条候选全部 `PENDING`,executedQueries=0,首轮阈值 `NOT_APPROVED`。AI 侧证据已饱和,
  剩余瓶颈只有真人签字。任何人不得将 PENDING 自动改为 PASS(见 §4)。

## 1. 权威来源(单一事实源,不得另建副本)

| 对象 | 权威文件 | 当前状态 |
|---|---|---|
| 68 条候选查询(B001–B068,28 来源,8 类别) | `contracts/retrieval/evaluation/acceptance-candidate.v2.2.json`(SHA-256 `1b88fa47…8888`) | `humanReviewStatus=PENDING`, `executionStatus=NOT_RUN`, `tuningUse=NEVER_USED`, `reviewedBy=None` |
| 最新冻结配置(20 文件哈希 + 检索参数) | `contracts/retrieval/evaluation/acceptance-freeze.v2.2.json`(SHA-256 `e43a8376…5724`) | 与代码对齐(hashDriftCount=0) |
| 逐条人审台账(68 行 Review Status 列) | `contracts/retrieval/evaluation/ACCEPTANCE_HUMAN_REVIEW.v2.2.md` §3 | 68/68 行 `**PENDING**` |
| 来源事实台账(28 来源) | 同上 §2 | 16 NODE + 4 RESOURCE + 4 STALE_NODE + 4 PRIVATE_NODE |
| 七项签署门(Checklist) | `contracts/retrieval/evaluation/HUMAN_REVIEW_DECISION_CHECKLIST.v2.2.md` | 7/7 未勾选 |
| AI 预审决策(人审输入,非人审结论) | `contracts/retrieval/evaluation/ai-review-decisions.v1.json` | reviewerType=AI,仅供参考 |
| 执行门状态(预检查聚合) | `docs/v2/evidence/SHARED_RAG_ACCEPTANCE_PRECHECK.json` | 5 资产全 `BLOCKED`;治理结论见文件内 `governanceDecision` |

类别分布(68 条):PROJECT_EXPRESSION 17、EXACT_IDENTIFIER 16、RESOURCE_CONTENT 8、MULTI_SOURCE 8、
NEAR_NO_ANSWER 8、STALE_VERSION 4、PERMISSION_ISOLATION 4、NO_ANSWER 3。

### 1.1 资产族谱与「审哪一版」的前置裁决

冻结资产族谱(全部 BLOCKED/PENDING,人工审阅对象必须先由 owner 裁决):

```text
v1(REVIEWED_REGRESSION,已退役)→ v2 → v2-revised → v2.1 → v2.2(当前 dossier 齐备)
→ v2.3(最新冻结,parent=v2.2 哈希;尚无对应 ACCEPTANCE_HUMAN_REVIEW / CHECKLIST 文档)
```

- v2.2 有完整的 68 条人审台账与签署清单,是当前可执行的审阅对象。
- v2.3 数据集与冻结契约更新(检索参数进入 freeze),但配套人审文档未建。
- **owner 第一项裁决**:以 v2.2 为审阅基线,或为 v2.3 补齐 dossier 后审 v2.3。
  两者都可以,但必须在执行首轮 68 条之前一次性定死;执行后不得换版本重跑并宣称首轮。

## 2. 审阅类别 × 判定标准 × 签署位置

七项签署门(HUMAN_REVIEW_DECISION_CHECKLIST.v2.2.md,全部由人类 owner 签):

| Gate | 审什么 | 判定标准 | 签署位置 |
|---|---|---|---|
| 1 | 68 条逐条审阅 | 每条 B001–B068:查询文本事实准确、类别恰当、expectedAliases 覆盖正确;与 §2 来源台账逐一对得上 | ACCEPTANCE_HUMAN_REVIEW.v2.2.md §3 每行 Review Status(PENDING→APPROVED/REJECTED + 日期与签名人);数据集 JSON 同步 `reviewedBy/reviewedAt` |
| 2 | 审阅者身份 | 真实姓名 + ISO-8601 时间戳,不得留空或写 AI | 数据集 `reviewedBy`、`reviewedAt` |
| 3 | 来源事实性 | 28 来源(N00–N15/R00–R03/S00–S03/P00–P03)与代码库事实一致,特别是 R01 单向内部 token、R00 target=1200 无重叠 | 人审文档 §2 备注列 |
| 4 | 冻结实现对齐 | 20 个代码文件 + requirements.lock 的 SHA-256 与冻结契约一致 | 重跑 `audit_retrieval_acceptance.py`,precheck hashDriftCount=0 |
| 5 | 首轮质量阈值业务签署 | 在看任何执行结果**之前**批准:macroSourceRecallAt8≥0.85、firstRelevantMrr≥0.75、multiSourceAllOfCoverage≥0.75、pureNoAnswerZeroHitCorrectness=1.0、staleSourceLeakCount=0、permissionLeakCount=0 | 冻结契约阈值状态改 `APPROVED_BEFORE_FIRST_RUN` + 业务签名人 |
| 6 | 干净执行出处 | executedQueries 仍为 0、CAS First-Run claim 初始化、候选集从未参与调参 | 预检查输出 executionStatus=NOT_RUN |
| 7 | 终局报告不可变 | 执行完成后唯一 runId 回执 | SHARED_RAG_ACCEPTANCE_CANDIDATE_EVALUATION.json |

owner:仓库 owner(BangShou1st)。Gate 5(阈值)是业务决策,建议与 Gate 1–4(工程事实核对)
分开签署;Gate 3/4 可由 owner 委托工程侧核对后、owner 复核签署。

## 3. 审阅完成后的执行路径(按 UNIFIED_PYTHON_RAG_DESIGN / RUNBOOK)

```powershell
# 仍用隔离测试库与已验证解释器;不触碰生产
$env:SPEC_AGENT_RAG_EVALUATION_DATASET = 'acceptance-candidate.v2.2.json'   # 或裁决后的 v2.3
$env:SPEC_AGENT_RAG_EVALUATION = 'true'
$env:SPEC_AGENT_GA_TEST_PYTHON = 'backend\build\ga-validation-venv\Scripts\python.exe'
.\gradlew.bat test --tests '*SharedRetrievalIntegrationTest.realOllamaFiftyTwoReviewableQueries*' --console=plain
```

输出为 `SHARED_RAG_ACCEPTANCE_CANDIDATE_EVALUATION.json`(与开发集报告分离);结果不是自动生产批准。

## 4. 状态纪律(硬红线)

1. 未经人类实际逐条审阅,任何 PENDING 不得改 PASS;不得由 AI 代签 `reviewedBy`。
2. 不得伪造 `executedQueries`、人工签字或阈值批准状态。
3. 阈值批准必须在首轮执行之前完成;看到结果后调阈值即失去「首轮」资格,必须重建数据集。
4. 5 个已 BLOCKED 冻结资产是历史证据,不覆盖、不重写;继续滚动资产版本无增量价值。

## 5. 阻断条件(合并 vs 发布,分别判定)

- **对代码合并(dev→main PR)**:RAG 人审 PENDING **不阻塞**代码合并——dev↔main 差异是功能与
  修复代码,检索质量维度在系统级验收中如实标注 `NOT_RUN` 即可;本清单即为该标注的依据。
- **对生产发布(python-rag.v1 检索投产)**:RAG 人审 + 首轮 68 条执行是**硬门槛**。
  PENDING 期间不得宣称检索质量验收通过,不得将 python-rag.v1 投入生产发布。
- 两者不得混用:合并 PR 的绿灯不能被引用为 RAG 人审已通过;反之 RAG 人审未完成也不得
  阻塞纯代码修复的合并评审。

## 6. 相关证据目录(引用,不复述)

rag-d3 系列(`docs/acceptance-evidence/rag-d3-*`,6 个目录)、`SHARED_RAG_ACCEPTANCE_PRECHECK.json`
及 `docs/v2/GLOBAL_ASSISTANT_LANGCHAIN_IMPLEMENTATION_STATUS.md` 的人工评测章节。AI 侧证据已饱和;
后续新增 AI 证据不改变本清单状态。

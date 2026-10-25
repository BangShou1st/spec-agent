---
title: RAG-D3 AI 自主审阅与首次实验运行
status: Runbook
authority: Current Authority
date: 2026-10-23
---

# 运行边界

本轮负责人授权 AI 技术审阅与最小修复，没有授权运行正式 68 条检索。
`AI_AUDITED_SYNTHETIC_EVALUATION` 是独立实验模式；不是人类批准的独立验收。
旧 `HUMAN_APPROVED_INDEPENDENT_ACCEPTANCE` 路径继续要求真实人审与原批准阈值。
AI 结果不写入 `reviewedBy`、`reviewedAt` 或 `HUMAN_REVIEWED`。

唯一派生版本为 `acceptance-candidate.v2.3.json`，父版本是 v2.2 原始字节 SHA-256。
`ai-review-decisions.v1.json` 逐条保存 28 来源、68 查询的前后定义、修改与证据。
N/R 是仓库语义的合成转述，S/P 是虚构隔离探针；编号不是真实档案编号。
历史 v1/v2/v2-revised/v2.1/v2.2 均保留，未更改。

# 首次结果可见前固定的 AI 指标契约

| 项目 | 预冻结规则 |
|---|---|
| Macro Source Recall@8 | 49 正向查询宏平均 ≥0.85；每条按来源集合去重；背景不计充分证据 |
| First-relevant MRR | 49 正向查询平均 ≥0.75；保留原始 Top-8 chunk 排名，不压缩同源 chunk |
| Multi-source all-of | 8 条复合查询每条必须两源齐全才算命中；总体 ≥0.75，即至少6/8 |
| NO_ANSWER | 3 条独立报告零命中率、非空返回数、合法噪声；不作检索质量硬门槛 |
| NEAR_NO_ANSWER | 8 条背景来源返回与任意非空返回分别报告；不要求零召回 |
| Stale / Private / Forbidden | 全部68条的所有原始返回条目零容忍，不只检查8条安全探针 |
| 来源身份有效性 | 必须100%；未知alias、错误项目/scope、版本/hash/authority失效均 FAIL |
| 完整性/可用性 | 必须68条全部完成且向量通道可用；失败查询不能从分母删除 |
| 延迟 | P50 500ms、P95 1500ms 是本 AI 建议实验观察参考；不作为 PASS/FAIL、单请求告警或生产 SLO |

`pureNoAnswerZeroHitCorrectness=1.0` 在旧人审契约中继续保留。
AI 路径显式选择 `DIAGNOSTIC_RETRIEVAL_NOISE`，在首轮结果可见前按职责界定该指标。
未见答案不意味着各词法/向量通道必须零召回；合法噪声不等于越权，也不能计为充分答案。
无答案标签表示冻结授权语料缺少问题请求的实质事实；能够说“材料未说明”不等于存在该事实。
本实验不调用作答 Agent，故正确拒答、答案充分性和引用正确率均为 NOT_MEASURED。
背景清单是人工可读语义诊断白名单，不是新的 required 或 equivalent evidence；本语料无充分等价替代组。

# 只读准备核验（本轮允许）

```powershell
scratch/tmp/rag-d3-final/venv/Scripts/python.exe agent-brain/scripts/retrieval_acceptance_contract.py
```

脚本不发起检索、不创建执行认领，不连接数据库，不覆写既有证据。
准备通过和 `thresholdStatus=AI_BASELINE_FROZEN` 仅表示 AI 基线就绪，不能冒称已获得人审批准。
代码、requirements.lock、Python运行环境清单、来源证据与审阅凭证均绑定 freeze；更改后必须重新审计。

# 仅在负责人另行明确授权后运行

由执行者核实新的授权，选择明确隔离的 PostgreSQL 目标及绝对 Python 路径。
严禁使用业务数据库、localhost:5434/spec_agent_test、付费模型或整个无关E2E套件。
本文件不是运行授权，不要把下列变量存在本身当作人类签名。

1. 重跑只读准备核验；检查 `data/retrieval-eval-claims/` 无既有候选执行。
2. 设置 `SPEC_AGENT_RAG_EVALUATION=true`、dataset=`acceptance-candidate.v2.3.json`、
   `SPEC_AGENT_RAG_EVALUATION_MODE=AI_AUDITED_SYNTHETIC_EVALUATION`。
3. `SPEC_AGENT_RAG_FIRST_RUN_AUTHORIZATION` 必须精确为该快照的 `datasetSha256:freezeSha256`。
   设置此运行开关是执行者落实新授权的操作，AI 准备阶段不设置它。
4. 设置 `SPEC_AGENT_GA_TEST_PYTHON` 为冻结 runtime inventory 中的解释器路径。
   使用已审核的隔离数据库守卫配置，再运行 backend 中唯一测试方法：
   `gradlew.bat testDatabaseIntegration -PdatabaseIntegration=true --tests '*SharedRetrievalIntegrationTest.realOllamaFiftyTwoReviewableQueriesEvaluateRecallNoAnswerStaleAndPermissionIsolation'`。
   历史方法名保留，实际候选条数由冻结数据控制，不代表使用52条作为独立验收。
5. 保存 `data/retrieval-eval-claims/<runId>.result.json`、不可变 claim 与 terminal receipt。
   原子认领在创建正式项目fixture、启动 Python、索引和查询之前。
   进程中断留下 CLAIMED/部分回执也视为首次机会已消耗；不得删账本、重命名数据再“首次”。

同一68条的所有修订共享 `rag-d3-v2-68` 评测身份；修改hash、阈值或模式不恢复未见结果的状态。
结果与回执使用 CREATE_NEW，失败和中断不会被改成 PASS。此保证针对协作执行器，不能防止人为删文件；
本地无claim只能证明当前账本未记录执行，不能证明不存在未披露的离线私跑。

# 范围限制

20 个可读事实、20 个精确编号正例和短资源构成小规模合成实验，不具有人类统计独立性。
首次实验最多验证封闭语料的召回、排序、fixture 隔离和观测延迟。
不能外推真实用户需求、多领域长资料、跨组织/动态撤权完整矩阵、生产容量或 Agent 端到端质量。
本轮只运行纯离线门禁、并发账本、评分、schema/hash及Python契约测试；数据库/真实检索未执行。

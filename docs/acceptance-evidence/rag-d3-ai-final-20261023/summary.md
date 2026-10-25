---
title: RAG-D3 AI 自主审阅最终收口
status: Frozen
authority: Historical Reference Only
date: 2026-10-23
---

# 决策

**AI_AUDIT_READY_FOR_FIRST_RUN_AUTHORIZATION**。无已知工程/事实阻断；正式68条执行0，未获人审批准。
当前权威实验契约见 [运行手册](../../runbooks/rag-d3-ai-audit-first-run.md) 与统一RAG设计的2026-10-23补充。

来源28：AI_ACCEPTED=8，AI_CORRECTED=20，AI_UNRESOLVED=0。
查询68：AI_ACCEPTED=40，AI_CORRECTED=28，AI_UNRESOLVED=0。
修订包含语义事实、失效出处、虚构夹具标记与背景标签澄清，不表示20个来源都存在关键事实错误。
AnswerPatch历史编辑、mandatory lineage/补充RAG、格式失败/冲突修复例外、分块边界及代理保留的剩余错误已处理。
充分证据与背景分开，所有query的scope/aliases/forbidden一致，无充分等价替代来源。

Java testOffline：completed=302 / failures=0 / errors=0 / skipped=0。
Python锁定环境：completed=339 / failures=0 / errors=0 / skipped=0。
共641项最终离线测试，不含重复运行计数。Java/Python共用26个门禁反例；并发线程/多进程认领与终态不可覆写通过。
初次Python守卫过严导致的25项失败及纠正原因在verification.json诚实保留，不冒称首次测试全绿。

新v2.3是唯一派生数据版本；v1/v2/v2-revised/v2.1/v2.2历史资产未改。
代码/资源1007文件与57个依赖锁定项、真实Python解释器清单、来源证据及审阅凭证全部绑定hash。
旧人审入口仍失败关闭；新AI入口仍需另行授权，不能用AI字段批准旧入口。
实际入口在创建正式fixture前原子认领稳定评测家族，失败报告先落不可覆盖文件再断言，进程中断消耗首次资格。
没有连接业务数据库、调用付费模型、改检索算法/模型、运行正式候选或推送远程。

指标预冻结：Recall@8≥0.85，原chunk排名MRR≥0.75，8条双源all-of≥0.75，Stale/Private/Forbidden全局0泄漏，来源身份100%有效。
3条NO_ANSWER仅作零命中/合法噪声诊断，8条NEAR分别记录指定背景与任意非空召回；不等同Agent正确拒答。
延迟P50 500ms/P95 1500ms仅AI观察参考，当前未测。49条正向含20条精确编号，小语料不能证明生产RAG、长文或完整权限矩阵质量。

完整逐条记录：[ai-review-decisions.v1.json](../../../contracts/retrieval/evaluation/ai-review-decisions.v1.json)。
精确artifact SHA-256、修改范围、测试与未运行边界见 [verification.json](verification.json)。

下一项且唯一负责人决策：是否另行授权对此冻结快照进行首次本地68条真实检索评测。

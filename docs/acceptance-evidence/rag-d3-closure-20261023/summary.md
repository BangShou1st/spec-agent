---
status: Historical
authority: Historical Reference Only
---

# RAG-D3 阶段结束：Recovery PASS

本阶段已结束。结论限定为 **AI 审阅合成语料的受控 Recovery PASS**，不是人工独立验收或生产 RAG 质量认证。

- Recovery runId：`582744d0-00ad-4265-a10b-157168399246`，终态 `COMPLETED_PASS`。
- 原中断 runId：`2a419207-4cf2-4e3d-9361-0eb4219a6199`；原 claim、失败回执和 freeze 保持不变。
- 68/68 条已保存且无重试；B001 是第二次正式观察，B002–B068 首次取得正式检索观察值。
- Macro Recall@8 **0.989796**、原始 Top-8 MRR **1.000000**、multi-source all-of **0.875（7/8）**，均达到原冻结阈值。
- 来源身份有效性 **100%**；Stale / Private / Forbidden 泄漏 **0 / 0 / 0**。
- B045 多来源查询漏召回 `R00`，只召回必需来源中的 `N02`，作为后续改进记录；没有修改标签或降低门槛。
- NO_ANSWER 3 条中 2 条非空，零命中率 1/3；NEAR_NO_ANSWER 8 条均非空，其中 7 条返回标注背景。两类均仅诊断。
- P50/P95 **918/1237 ms**，仅为本地检索性能观察，无批准的性能硬门槛；P50 沿用上中位数。
- 既有验证：离线 **322 完成 / 0 失败 / 0 跳过**；合成 Recovery **2 完成 / 0 失败 / 0 跳过**，含中断后结果保留、防覆盖和并发唯一认领验证。

Recovery 入口、执行器可靠性修复及测试一起归档。评测时 HEAD 为 `65591d7c090cbaf4ad2f3159bc6d55682bd92296`，实际执行代码由不可变授权清单与精确差异绑定；本次 Git 提交在评测之后创建，不改写评测身份。Git 可能规范化文本行尾，执行时文件字节哈希仍以原清单为准。

完整授权清单、原始 Top-8、逐条索引、原/新回执和精确差异保留在本机 `data/retrieval-eval-claims/`、`data/retrieval-eval-recovery/`，不上传原始运行数据、实际数据库凭据或运行日志。本仓库只保存结论及 [身份与证据哈希索引](verification.json)。

本次仅做 Git 归档与 dev 同步，未重跑正式查询、未修改召回算法、标签或冻结阈值。B045 及诊断噪声留待下一阶段另行处理。

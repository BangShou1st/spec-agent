---
status: Runbook
authority: Current Authority
---

# RAG-D3 受控恢复测量

`RECOVERY_RUN` 只恢复原中断测量，不重新授予 FIRST_RUN 资格，也不授予人工审批。旧 claim、候选、AI 审阅记录、freeze 和原始失败回执保持原样。

正式入口复用 `SharedRetrievalIntegrationTest.realOllamaFiftyTwoReviewableQueriesEvaluateRecallNoAnswerStaleAndPermissionIsolation`。设置 `SPEC_AGENT_RAG_EVALUATION=true`、`SPEC_AGENT_RAG_EVALUATION_MODE=RECOVERY_RUN`、原候选文件名，以及 `SPEC_AGENT_RAG_RECOVERY_MANIFEST` 和该不可变文件的 SHA-256（`SPEC_AGENT_RAG_RECOVERY_AUTHORIZATION`）。环境仍须通过原隔离数据库守卫，仅允许专用数据库、本地 Ollama 和原冻结 Python 依赖。

清单由 `prepareRecoveryAuthorizationManifest` 生成（`SPEC_AGENT_RAG_RECOVERY_PREPARE=true`），此入口不创建业务 fixture、不认领、不检索。原代码与新代码的逐文件原始字节、哈希和修复原因预先存为 `data/retrieval-eval-recovery/executor-byte-diff.v1.json`。授权清单 `authorization.v1.json` 使用 CREATE_NEW + fsync 保存，绑定当前 HEAD、全部冻结代码、修复文件、模型/Profile/参数、评分契约、数据库/容器身份及原 claim/回执。清单不得覆盖；身份漂移必须阻止正式运行，不能修改旧 freeze 来放行。

`RetrievalRecoveryGate` 仅允许其固定白名单中的执行器与测试文件变化。原冻结文件的任何其他变化均被拒绝，新增或重命名且未声明的检索源码也会被拒绝。原 AI 审阅证据只有在其原始代码 SHA 与 freeze 相同、且当前字节与允许修复记录完全相符时才接受修复映射。普通人工审阅与首次 AI 门禁不使用该映射。

恢复认领键是 `sha256(candidateSha:freezeSha:parentRunId:1)`，独立 runId 不决定恢复资格。同一键只允许一次原子创建；中断、失败、部分写入都消耗资格。不得换随机 runId、删除 claim 或更换 manifest 规避重领。

独立合成 Recovery 使用 `syntheticRecovery*` 两个集成测试和 `SPEC_AGENT_RAG_RECOVERY_SMOKE=true`。构造的父历史、候选、freeze、审阅记录和账本均放在 scratch，并在读取前验证路径边界；不会读取正式 68 条候选内容，不使用正式父 runId。

执行结果依次写入逐条 started、完整原始 returned、scored 和质量 checkpoint。采样器关闭、Python 退出、隔离 fixture 清理之后，先保存 `PENDING_TERMINAL_RECEIPT` 结果快照，最后以不可覆盖回执发布终态。**回执的 `status` 是终态，`outcomeDetails` 中的 JSON 是完整最终报告。** 缺少有效终态回执时不能只凭质量 checkpoint 宣称 PASS。

完整质量 FAIL 写为 COMPLETED_FAIL；异常或清理失败写为 INTERRUPTED_OR_ERROR。硬杀进程时可能只留下 claim 和逐条文件，这仍是已消耗运行，必须核对现有证据，不允许自动重跑。本轮正式 B001 必须注明为第二次观察，B002–B068 才是首次取得正式检索观察值。

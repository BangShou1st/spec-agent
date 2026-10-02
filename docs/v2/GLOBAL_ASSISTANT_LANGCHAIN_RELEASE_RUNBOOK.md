# 全局助手与统一 Python RAG 发布、回滚及验收

日期：2026-10-02。状态：发布准备稿；**没有执行生产迁移或引擎切换**。
最新功能和证据见 [实施记录](GLOBAL_ASSISTANT_LANGCHAIN_IMPLEMENTATION_STATUS.md)。

## 发布前必须关闭的门槛

- 52条查询是参与过0.65→0.50阈值选择的开发集，即使人工复核也不能作为独立验收。68条独立候选集已冻结、尚未运行，标签全部待审；人工确认来源、相近无答案、多来源口径及首轮质量门槛后才可执行。详见[本轮收尾](GLOBAL_ASSISTANT_PRE_RELEASE_CLOSEOUT.md)。
- 旧失败测试留下的单个 Git 临时目录尚未清理。自动审批拒绝了指定目录删除，原因仅为 `blocked by policy`；本次没有绕过拒绝。新故障测试目录清理通过，不能据此宣称旧目录也已清除。
- 运维确认生产备份、凭据注入、网络出口、单实例恢复策略及实际容量。原本机 uv CPython 3.11.15 已出现导入阶段原生访问冲突，必须使用已验证的解释器构建；不能只依据版本号或一次 pytest 宣称其稳定。Windows 本地测试不证明 Linux 部署、生产 P95、独占 GPU 峰值或多实例接管。
- 审核本文配置、恢复范围和回滚限制，取得单独的生产切换授权。当前会话不包含该授权。

## 可审核的交付边界

Java、Python 锁定依赖和前端必须成套发布。Java 保留事实、Runtime、权限、存储、来源校验与副作用事务；Python 仅通过受认证宿主 RPC 使用同一 pgvector 存储。保留现有聊天模型及 OpenCode Zen 请求头策略，不把供应商密钥传给 Python。

当前默认配置：

```text
SPEC_AGENT_GLOBAL_ASSISTANT_ENGINE=java-legacy.v1
SPEC_AGENT_RETRIEVAL_ENGINE=java-hybrid.v1
```

上述变量未设置时亦保持旧默认。候选配置为 `langchain-ga.v1` 与 `python-rag.v1`；此处是操作说明，没有应用这些值到生产。

Python 使用仓库锁定版本安装，并运行 `pip check`。服务密钥通过既有安全配置注入，禁止输出到日志或证据。固定本地 Ollama `qwen3-embedding:0.6b`、1024 维和 profile
`26810df4b6b4e7d0d2977bcc57175fcf7e944ebe99f447b5c4e690cb08b8c047`。
核对 digest 与锁定契约；服务不会自动下载模型或改用云端，4B 不在本次发布配置内。

部署候选服务还需明确 Python `SPEC_AGENT_BRAIN_MODEL_MODE=broker`、
`SPEC_AGENT_INTERNAL_BROKER_URL=<Java 宿主>/internal/v1/model-inference`、
`SPEC_AGENT_BRAIN_INTERNAL_SECRET=<通过安全渠道注入的同一安装密钥>`。
Java `SPEC_AGENT_BRAIN_BASE_URL` 指向该 Python 服务；Python `SPEC_AGENT_OLLAMA_URL`
仅指向允许的本地 Ollama origin（容器可使用 host.docker.internal）。这些是配置占位符，禁止将占位符直接应用到生产。
共享索引回填使用既有 `SPEC_AGENT_EMBEDDING_WORKER_ENABLED` worker 开关，选新检索引擎后 worker 调 Python；旧 provider 配置无需新增 Ollama 值。

## 预发布与迁移步骤

1. 记录当前成套构建版本、配置、模型绑定及数据库 schema 版本。备份 PostgreSQL 与宿主加密主密钥，实际验证备份可恢复；不要将密钥写入评测文件。
2. 在隔离预发布环境运行 V43–V51 增量迁移。当前迁移仅在 `spec_agent_test` 验证，不能视为生产 Flyway 已应用。保留新增表与列，不改写不可变 Answer、历史或已冻结输入。
3. 启动匹配版本的 Python 服务和 Java 宿主。受认证检查 Python `/internal/v1/retrieval/health` 和 Java `/internal/v1/retrieval-store/health`：分别核对 storeReady、ollamaReady、profile、模型版本。存储 readiness 与 embedding readiness 分开；Ollama 故障时混合检索显式 VECTOR_UNAVAILABLE，纯语义与索引失败，不能偷偷切换 provider。
4. 在候选配置下完成资源原文投影、Python 结构分块及同表索引回填。检查 PENDING/CLAIMED/FAILED/STALE 任务、SKIPPED 排除标记及原因对应的来源策略。业务结构超过单元预算时不截断冒用原 hash；必需上下文仍由项目 Runtime 冻结。
5. 新 generation 完整准备后，以宿主 source-version/profile/generation/head CAS 激活。确认没有未完成资源分块或 embedding；校验撤回、权限、版本与位置，旧 grant 不得跨 generation 使用。不要在生产查询路径执行任意全量重建。
6. 运行未变模型的原生工具调用、产品逐 token 正文流、取消/超时/断连、写入幂等与未知结果、浏览器刷新和恢复验证；运行人工审阅后的评测。生产数据只在另行授权后验证，测试脚本默认使用隔离库。

## 获得授权后的生产切换步骤

1. 暂停接收新请求，等待活跃 run 完成或通过既有取消接口终止，确认没有在途宿主提交。
2. 停止旧执行进程，再启动配套新版本。当前启动恢复会将中断 run 终止；不能让多个实例同时把彼此活跃 run 判作孤儿。多实例故障接管需独立设计与验收。
3. 仅对新 run 显式选择候选引擎。旧 run 保留 engine、模型绑定、冻结目录和历史；不能迁移在途工具调用。项目 Agent 的业务 Graph 不交给 LangGraph。
4. 观察终态重复、迟到正文、checkpoint CAS、UNKNOWN ledger、任务失败及来源拒绝指标。出现异常先停止新请求并取消活跃执行，再按下述回滚。

## 恢复范围与副作用处理

恢复从最后成功完成的 checkpoint 边界开始，保留原始结构身份和产品消息。进程中断时旧 run 失败，下一用户请求可以读取完成边界；不承诺续跑正在执行的工具。UNKNOWN 表示副作用结果未确认，禁止自动重放、换 callId 重试或把 UI 的“未确认完成”解释为业务已撤销。

本地创建与 Skill 暂存沿用既有 LOCAL_DURABLE 和事务准入，重复执行不产生第二个对象。Skill 暂存不自动安装/启用；APPROVAL_REQUIRED 在本阶段失败关闭，不绕过原有审批。保留兼容 `bash`/`read` 仅用于供应商协议，不是可执行宿主能力。

## 回滚步骤与限制

1. 停止接收新请求，取消或排空活跃 run，确认宿主 fence 生效并停止执行进程。
2. 恢复已记录的成套 Java/Python/前端版本与原配置；将新请求引擎恢复为旧默认。保留新 schema、执行账本、checkpoint、公开历史和冻结输入，禁止删除表、重写 Answer 或自动重放 UNKNOWN。
3. 检索复用同一 retrieval_entries，没有保存第二套活动向量。新 generation 激活后不能假定旧向量仍可直接回滚。需要按目标 profile 重建新的 generation，或从经过验证的数据库备份恢复派生向量，再完成 CAS/来源校验才激活；期间只使用明确标注的可用检索状态。
4. 在隔离环境复验旧引擎、新旧历史显示、冻结输入字节一致、来源权限及写入幂等。确认业务事实未改变后再恢复请求。

## 开发集复验（不作为独立验收）

开发集数据：[chinese-queries.v1.json](../../contracts/retrieval/evaluation/chinese-queries.v1.json)。结果：[52 查询证据](evidence/SHARED_RAG_52_QUERY_EVALUATION.json)。人工核对 source alias、查询意图、expectedAliases、过期/无答案/权限预期；不能仅按本次检索结果反推标签。审阅完成后每条设置 HUMAN_REVIEWED，顶层 humanReviewStatus 设置 APPROVED，并记录 reviewedBy 和 ISO-8601 reviewedAt。只有实际人工完成才可设置这些字段。

便于逐项审核的 [人工审阅表](../../contracts/retrieval/evaluation/HUMAN_REVIEW.md) 同列来源全文和 52 条查询；所有人工结论目前均为待审。

复验（PowerShell，backend 目录，仅隔离测试库）：

```powershell
# 可通过 SPEC_AGENT_GA_TEST_PYTHON 指定依照相同依赖锁构建的已验证解释器。
$env:SPEC_AGENT_RAG_EVALUATION = 'true'
try {
    .\gradlew.bat test --tests '*SharedRetrievalIntegrationTest.realOllamaFiftyTwoReviewableQueries*' --console=plain
} finally {
    Remove-Item Env:SPEC_AGENT_RAG_EVALUATION
}
```

本次本地完整复验通过的解释器是隔离 CPython 3.11.14（Windows），依赖锁未变。复现时先用该补丁版本创建独立环境、按 requirements.lock 安装，再将 SPEC_AGENT_GA_TEST_PYTHON 指向其 python.exe。Java 完整回归需显式设置 SPEC_AGENT_GA_CROSS_LANGUAGE_TEST、SPEC_AGENT_RAG_LIVE_TEST、SPEC_AGENT_RAG_EVALUATION、SPEC_AGENT_RAG_U3_TEST 为 true；真实模型、HTTPS 和浏览器 gate 的独立证据及测试名见交付记录。

目前未取得人工审阅，发布资格保持未通过。

## 可复现候选运行环境与启动

Windows候选锁定CPython3.11.14 / standalone20260211，并校验EXE、DLL、Pydantic原生模块和requirements.lock指纹。原3.11.15崩溃证据不删除，具体根因未证实。

在仓库根目录准备或验证独立目录（已有环境不自动修补、版本不符失败）：

```powershell
.\agent-brain\scripts\prepare_candidate_runtime.ps1
```

准备脚本默认backend/build/ga-validation-venv，不修改原agent-brain/.venv。新建时使用uv0.12.2的固定构建目录，禁止漂移到最新同版本构建；已有环境直接核对固定指纹。manifest仅证明环境身份；服务资格还需下述专项。

配置SPEC_AGENT_BRAIN_MODEL_MODE=broker、SPEC_AGENT_INTERNAL_BROKER_URL、SPEC_AGENT_BRAIN_INTERNAL_SECRET及SPEC_AGENT_OLLAMA_URL后，明确使用候选python.exe启动；不得依赖PATH中的python/uvicorn，也不得默认fake。密钥由原安全渠道注入。

```powershell
$taskCandidatePython = (Resolve-Path .\backend\build\ga-validation-venv\Scripts\python.exe).Path
Push-Location agent-brain
try {
    & $taskCandidatePython -m uvicorn spec_agent_brain.app:create_app --factory --host 127.0.0.1 --port 8100
} finally { Pop-Location }
```

上面只是启动说明，未在生产应用。启动失败应保留日志/退出码并停止，不加自动重试。Ollama启动与digest核对是显式依赖准备，不自动pull模型或改变0.6B profile。冷启动是新解释器状态，不是OS缓存清空或模型强制卸载。

本机专项复现（backend目录；需已运行的原本地Ollama，隔离测试库）：

```powershell
$env:SPEC_AGENT_RUNTIME_QUALIFICATION = 'true'
$env:SPEC_AGENT_GA_TEST_PYTHON = (Resolve-Path .\build\ga-validation-venv\Scripts\python.exe).Path
try {
    .\gradlew.bat test --tests '*SharedRetrievalIntegrationTest.candidateRuntimeColdRepeatedStartsAndSustainedServiceUseActualHostRpc' --console=plain
} finally {
    Remove-Item Env:SPEC_AGENT_RUNTIME_QUALIFICATION
    Remove-Item Env:SPEC_AGENT_GA_TEST_PYTHON
}
```

本轮已通过6次独立服务启动及约65秒/12次真实检索，0失败重启/重试。Windows本机有限验证不替代部署机器、容器/Linux与长期运行确认。

## 独立候选验收集

[68条候选](../../contracts/retrieval/evaluation/acceptance-candidate.v1.json)、[冻结配置](../../contracts/retrieval/evaluation/acceptance-freeze.v1.json)、[人工审阅材料](../../contracts/retrieval/evaluation/ACCEPTANCE_HUMAN_REVIEW.md)。本轮候选执行数为0，没有调参或人工批准。

静态预检查与审阅材料生成（不会执行查询、改参数或批准标签）：

```powershell
.\backend\build\ga-validation-venv\Scripts\python.exe agent-brain/scripts/audit_retrieval_acceptance.py
```

人工实际审阅后，将标签/身份/时间与首次验收口径记录为审阅版本；在首次执行前更新数据hash及明确首轮质量阈值状态为APPROVED_BEFORE_FIRST_RUN，保留算法参数和实现hash。改变算法后不可继续称该集未参与调参。未审阅标签在启动Python和索引前被拒绝。

运行时选择SPEC_AGENT_RAG_EVALUATION_DATASET=acceptance-candidate.v1.json与SPEC_AGENT_RAG_EVALUATION=true，使用同一已验证SPEC_AGENT_GA_TEST_PYTHON。输出为SHARED_RAG_ACCEPTANCE_CANDIDATE_EVALUATION.json，与开发集报告分离；测试入口名称沿用已有realOllamaFiftyTwoReviewableQueries...，实际数量取自数据集。结果报告不是自动生产批准。

旧临时目录仍是C:/Users/32962/AppData/Local/Temp/spec-agent-git-skill-13327698071011337690；原删除被自动审批以blocked by policy拒绝，本轮未再尝试。

# Spec Agent · RAG-D2 可靠性审查、最小修复与本地验收总结

> **任务标识**：`RAG-D2`\
> **基线分支**：`dev` (`8570721` ahead of remote `742870d`)\
> **执行日期**：2026-10-22\
> **文档属性**：`Acceptance Evidence` (结构化轻量证据)

---

## 1. 目标与执行概览

本轮是 RAG-D1 切换默认检索引擎至 `python-rag.v1` 后的可靠性审查与边界收敛专项，严格遵守产品边界，**不引入 Reranker、GraphRAG、新向量库、模型升级、Prompt 修改或无必要的模型消耗**。

针对重点 A–E 开展了代码审计与实测，针对确认问题完成最小修复并建立了定向正反例回归。

---

## 2. 重点审查结论与修复矩阵

### 重点 A：检索错误分类与严格失败关闭 (Fail-Closed)
- **审查发现**：`RetrievalContextService.java` 原逻辑存在宽泛的 `catch (IllegalStateException ex)`，将所有内部错误统一定义为 `supplementalRetrievalUnavailable=true`。如果发生伪造授权、数据篡改或代际竞态，会被静默降级为“补充检索不可用”，破坏了系统的 Fail-Closed 安全模型。
- **修复方案**：
  - 明确白名单：可降级错误（`RETRIEVAL_UNAVAILABLE`, `OLLAMA_UNAVAILABLE`, `DEADLINE_EXCEEDED`, 以及以 `EMBEDDING_` 开头的向量后端临时异常）捕获并标记 `supplementalRetrievalUnavailable=true`。
  - 安全与一致性错误：`STALE_SCOPE_GRANT`、`STALE_WORKLOAD`、`SOURCE_VERSION_MISMATCH`、`INDEX_GENERATION_MISMATCH`、`UNSUPPORTED_PROFILE`、`INVALID_VECTOR` 严禁静默吞掉，一律直接抛出异常拒绝继续投影（Fail-Closed）。
  - 零命中区分：服务正常但未命中任何结果时，保留 `supplementalRetrievalUnavailable=false`，绝不把服务故障误报为检索零命中。
- **验证证据**：新增 `RetrievalContextServiceErrorClassificationTest`，覆盖 17 个定向测试用例（9 种降级场景、7 种 Fail-Closed 场景、1 种零命中场景），全部通过。

### 重点 B：新项目空索引与 generation 代际生命周期
- **实测行为核实**：
  1. 新项目无来源：`RetrievalStore.ensureProjectGeneration()` 创建 `ACTIVE` 初始空代际。
  2. 初始空代际允许词法通道立即工作，无需等待全量向量回填；向量处于 `PENDING` 时返回 `status="OK", vectorUnavailable=true`。
  3. 向量生成完成后，正常命中 `vector` 通道。
  4. 重建索引准备新代际期间，旧代际保持有效；新代际就绪后原子切换。
  5. 来源撤销/版本变化时，Java 最终校验抛出 `SOURCE_VERSION_MISMATCH`。
  6. `retrieval.await_generation` (C16 WAIT) 绑定并等待代际就绪，不增加多余状态机。

### 重点 C：Python 代理环境副作用消除与客户端直连隔离（D2.1 最终收口）
- **审查发现**：
  1. D1 中在 `agent-brain/src/spec_agent_brain/__init__.py` 中存在模块导入期删除 `ALL_PROXY` / `all_proxy` 的代码，对 Python 进程造成了全局永久性修改。
  2. D2 初版通过 `scoped_import.py` 上下文管理器临时 pop 环境变量绕过第三方缺少 socksio 的异常，虽在导入后恢复，但仍存在瞬态环境变量修改。
- **终态修复方案（D2.1）**：
  - **依赖层原生支持**：在 `pyproject.toml` 与 `requirements.lock` 中加入 `socksio==1.0.0`，从根本上解决 `httpx` / `ollama` SDK 顶层无参客户端在发现 SOCKS 代理时缺乏协议支持的报错。
  - **完全消除 `scoped_import` 临时方案**：彻底物理删除 `agent-brain/src/spec_agent_brain/retrieval/scoped_import.py`。
  - **恢复标准纯净导入**：在 `embedding.py` 与 `configured_embedding.py` 中直接标准导入 `from langchain_ollama import OllamaEmbeddings` 与 `from ollama import ResponseError`，Python 代码内对 `os.environ` 的修改**彻底归零**。
  - **内部客户端显式直连**：内部通信客户端（`BrokerModelClient`, `LocalEmbedding`, `HostStore`）在具体构造中显式传递 `trust_env=False, follow_redirects=False`，保证本地请求（Ollama 11434, Java 内部 RPC 8080）绝对直连本地。
  - **外部 Provider 代理完好可用**：外部模型请求客户端（如标准 `httpx.Client()`）保持 `trust_env=True`，完整读取并遵循系统代理配置。
- **验证证据**：
  - 更新 `test_proxy_isolation.py` 覆盖 3 种典型场景（无代理环境正常直连、SOCKS 代理环境下正常导入且环境变量 100% 保留零修改、外部客户端代理正常读取）。
  - Python 全套 295 个测试用例全部通过（1.86s）。

### 重点 D：Gemini 测试密钥与加密主密钥隔离
- **桌面 `key2.txt`**：核实仅在 `GeminiCustomProviderSettingsVerificationTest` 和 `GeminiRagControlledAbAcceptanceTest` 两个显式测试中读取，生产 `src/main` 代码零引用；未开启 live gate 或文件不存在时测试直接跳过。
- **测试主密钥隔离**：
  - 移除了测试文件中硬编码读取业务实例文件 `E:/project/spec-agent/backend/data/secret-master.key` 的代码。
  - 改为使用测试专用的环境变量 `SPEC_AGENT_TEST_MASTER_KEY` 与预设测试隔离密钥，测试与业务生产主密钥完全解耦。

### 重点 E：冻结输入、真实来源与余弦相似度更正
- **冻结输入**：首次投影结果写入 `agent_input_projections`，后续访问严格通过 payloadHash 校验直接回放，代际或 profile 变化绝不重新检索。
- **余弦相似度更正**：澄清报告表述误差，余弦相似度等距离分数仅在底层融合与排序阶段内部使用，**从未也不会进入**暴露给模型的 `RetrievedContextItem` 快照。

---

## 3. 本地测试汇总与验收维度

| 测试套件 | 范围 / 任务 | 测试总数 | 失败数 | 跳过数 | 状态 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Gradle testOffline** | 离线审查测试集（含新增分类测试） | 225 | 0 | 0 | **PASSED** (15s - 1m 1s) |
| **Python pytest** | agent-brain 完整单元测试（含代理隔离测试） | 295 | 0 | 0 | **PASSED** (1.86s) |
| **专用隔离 DB 真实集成测试** | `SharedRetrievalIntegrationTest.realPythonOllama...` (Java ↔ Python ↔ 本地 Ollama 0.6B ↔ pgvector) | 1 | 0 | 0 | **PASSED** (51s, 产生 SHARED_RAG_U1_REAL_INTEGRATION.json) |
| **专用隔离 DB 失败关闭测试** | `SharedRetrievalIntegrationTest.scopedCandidatesSourceVersion...` (来源撤销与版本不匹配 Fail-Closed) | 1 | 0 | 0 | **PASSED** (45s) |

### 验收三维状态说明
- `CODE_COMPLETE`: **YES** (最小修复与定向测试全部完成)。
- `INTEGRATION_VERIFIED`: **YES** (单元测试、离线测试、专用隔离数据库真实端到端集成全线通过并产出不可篡改证据)。
- `QUALITY_VERIFIED`: **NO / CONDITIONAL** (52 条检索查询仅属于 `DEVELOPMENT_CALIBRATION`；68 条独立候选集维持 `PENDING_HUMAN_REVIEW`，在未完成独立人工复核前严禁宣称质量门槛通过)。

---

## 4. 下一阶段评估：是否需要 Reranker？
- 当前本地 Ollama `qwen3-embedding:0.6b` 结合 RRF 倒数排名融合与 Java 权威边界在延迟与召回上已满足核心需求。
- 在无独立标注数据集完成人工复核并证明现有 RRF 存在明显瓶颈前，**没有充分证据**支持引入额外的 Reranker 模型或框架重构。

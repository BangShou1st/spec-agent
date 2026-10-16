# P3-A 项目 Agent 智能质量评测与针对性优化

状态：**已完成基线评测、根因归因、一项有证据支持的通用优化与完整回归**。

本轮只处理 **项目 Agent（Workspace / Graph Reasoning Agent）** 的检索证据获取、
需求理解与下一步决策质量。未改动 Global Assistant，未新增 GA 工具，未做 GA 与项目
Agent 的桥接，未新增第二套 RAG / 向量库 / 队列 / Planner 服务 / 多 Agent 辩论链。

## 1. 真实调用路径（先核查，后评测）

| 层 | 真实实现 | 证据 |
|---|---|---|
| 上下文装配 | `ContextBuilder` → `ContextSnapshot` → `AgentInputSnapshotBuilder` | `backend/src/main/java/com/specagent/agent/snapshot/AgentInputSnapshotBuilder.java` |
| 工作上下文选择 | `WorkingContextSelector`（路线窗口有界：12 条 / 12000 字符） | `.../agent/snapshot/WorkingContextSelector.java` |
| 补充检索 | `RetrievalContextService` → `HybridRetriever`（RRF k=60，8 条 / 8000 字符） | `.../retrieval/context/RetrievalContextService.java`、`.../retrieval/search/HybridRetriever.java` |
| 候选 SQL | `RetrievalStore.candidates`（`java-hybrid.v1` 直查；`python-rag.v1` 走共享 Python） | `.../retrieval/protocol/RetrievalStore.java:186` |
| 词法读取 | `RetrievalEntryRepository.lexical`（`ts_rank_cd` + `plainto_tsquery('simple')`） | `.../retrieval/persistence/RetrievalEntryRepository.java:278` |
| STATE_UPDATE | Python `handle_state_update`（1 次模型调用，严格解析 claims） | `agent-brain/src/spec_agent_brain/state_update/engine.py` |
| DECISION | Python `handle_decision`（1 次模型调用：reflection + planning，含一次有界冲突修复） | `agent-brain/src/spec_agent_brain/decision/engine.py` |
| 决策引擎 | `RemotePythonDecisionEngine`（Java 侧 fail-closed 复核） | `.../agent/decision/RemotePythonDecisionEngine.java` |
| 动作资格 | `ActionEligibilityGate` → `ActionEligibilityEvaluator`（默认 `shadow`；`WAIT` 恒被 `NO_PENDING_DEPENDENCY` 拒绝） | `.../agent/action/ActionEligibilityGate.java:46` |
| 响应校验 | `AgentBrainResponseValidator`（refs / 预算 / eligibility basis / 冲突暴露） | `.../agent/decision/AgentBrainResponseValidator.java` |
| 需求状态 | `RequirementStateBuilder` 回放 AnswerPatch（派生物，非事实源） | `.../workspace/context/RequirementStateBuilder.java` |

**关键配置事实（决定了基线含义）**：`application.yml:144` 默认
`retrieval.engine=java-hybrid.v1`，`:148` 默认 `retrieval.embedding.provider=noop`。
`NoopEmbeddingGateway` 恒返回 `Optional.empty()`
（`.../retrieval/embedding/NoopEmbeddingGateway.java:27`），因此 **默认本地安装的向量
通道是关闭的**；数据库里的 Ollama embedding 配置只服务 `python-rag.v1`
（`EmbeddingSettings` → `EmbeddingBrokerController`），从不进入 java-hybrid 的
`EmbeddingGateway`。

## 2. 三层评测（A / B / C 分别记分，不合成单一"AI 效果分"）

三层均可独立复跑，命令与产物见 §6。

### A 层：上下文与检索质量

两份互补证据，统计单位均为 **source**（按 `sourceRef` 去重）：

1. **默认安装（`java-hybrid.v1` + noop）** —— 冻结 52 条中文校准集，
   在**同一次运行、同一融合链路**下对比修复前后（仅替换词法谓词）：
   产物 `docs/v2/evidence/P3A_LAYER_A_RETRIEVAL.json`。
2. **生产向量路径（`python-rag.v1` + 本地 Ollama `qwen3-embedding:0.6b`）** ——
   仓库既有的 52 条评测，本轮**复跑复现**（见 §5）。

| 指标 | 默认安装（修复前） | 默认安装（修复后） | 生产向量路径（既有基线，本轮复现） |
|---|---|---|---|
| Recall@5 | 0.5000 | **0.9000** | NOT MEASURED（既有产物只记 Recall@8） |
| Recall@8 | 0.5000 | **0.9000** | 0.9500 |
| Hit@5 | 0.5000 | **0.9000** | NOT MEASURED |
| MRR@5 | 0.4750 | **0.7979** | NOT MEASURED（既有产物只记 MRR） |
| All-required@5 | 0.5000 | **0.9000** | 0.9500（= Recall@8，该集全部单来源） |
| 零命中正确率 | 0.8333 | 0.8333 | 1.0000 |
| 权限越界 | 0 | 0 | 0 |
| 过期来源泄漏 | 0 | 0 | 0 |
| P50 / P95 检索延迟 | 10 / 14 ms | 10 / 14 ms | 309 / 503 ms |

分类型（All-required@5，修复前 → 修复后）：中文改写 **1/16 → 13/16**；
ASCII 标识符 **16/16 → 16/16**（未回退）；资源内容 **3/8 → 7/8**。

标签状态：`chinese-queries.v1.json` 的自述是
`authorship=AI-authored reviewable fixtures; not human-labelled`、
`humanReviewStatus=PENDING`、`datasetRole=DEVELOPMENT_CALIBRATION`，
`calibrationHistory.independentAcceptanceEligible=false`。**52 条全部
`labelReview=PENDING_HUMAN_REVIEW`。因此 0.95 与 0.90 都只是校准集成绩，
不是独立人工验收。** 独立候选集（68 条）当前仍然 `executionStatus=NOT_RUN`、
`tuningUse=NEVER_USED`、`labelsApproved=false`，且被
`requireReviewedCandidate()` 与 `pendingIndependentCandidateIsRejectedBeforePythonOrIndexing()`
三重硬门禁拦住，**本轮未运行、未用它调参**。

### B 层：用户回答的结构化理解（真实模型）

产物 `docs/v2/evidence/P3A_LAYER_BC_LIVE_BASELINE.json`。走**真实生产链路**
（Java Runtime + broker 模式 Python Brain + 产品自己配置的模型
`mimo-v2.6-flash-free`，经 `LiveExecutionGuard` 强制校验 bean 身份，无脚本化 Brain）。

语料 `tools/p3a/p3a_scenarios.json`（16 个通用场景，领域覆盖业务流程 / 服务设计 /
运营规则 / 软件；本轮真实模型首轮只跑前 8 个）。判分用**确定性语义条件**
（概念 OR 组、否定豁免、精确"settling"模式），不做字符串相等。

8 个场景中：**4 个完成、4 个 INFRA**（模型/网络层，另列于 §3）。

| 指标 | 结果 | 样本数 | 标签状态 |
|---|---|---|---|
| 无依据的 CONFIRMED claim | **0** | 4 完成 | AI 候选标签，PENDING_HUMAN_REVIEW |
| 把未决内容标成 CONFIRMED | **0** | 4 完成 | 同上 |
| 过度概括（含否定豁免判定） | **0** | 4 完成 | 同上 |
| 产生的 claims 总数 | 9 | 4 完成 | 同上 |
| Claim Precision / Recall / F1 | NOT MEASURED | — | 需人工标注才能定义分母 |
| AnswerPatch 协议校验失败率 | 4/8 场景（另有 2 次 JSON 违约） | 8 | 见 §3 |

有代表性的正面证据（真实模型输出，逐字）：

- **条件 + 未决（P3A-B02 / B06）**：对"普通员工都可以预约会议室，但是大型会议室需要
  管理员审批，暂时还没决定大型会议室按多少人计算"，模型给出
  `CONFIRMED 普通员工都可以预约会议室`、
  `CONFIRMED 大型会议室的预约需要管理员审批`、
  `UNRESOLVED 大型会议室的界定标准（按多少人计算）尚未决定，待澄清`——
  **既没有概括成"所有预约都要审批"，也没有假设人数阈值，且未决项保持在 UNRESOLVED。**
- **需求变更（P3A-B05）**：输出 `CONFIRMED 目标客户为连锁餐饮品牌` 加
  `REJECTED 旧定位"面向中小企业"已被用户明确否定并撤销`，正确区分撤销与有效需求。
- **冲突（P3A-B07）**：不但标出 `CONFIRMED 完全离线运行` 与
  `CONFIRMED 所有门店数据实时同步`，还额外输出 `UNRESOLVED` 解释两者为何互斥。

### C 层：下一步动作决策（真实模型）

以场景声明的 **acceptable family 集合**为 oracle（不是唯一答案），
并以真实 eligibility mask 为前提。

| 指标 | 结果 | 样本数 | 标签状态 |
|---|---|---|---|
| Action acceptable-set accuracy | **0.5000** | 4 完成 | AI 候选标签，PENDING_HUMAN_REVIEW |
| Payload validity | 0.5000 | 4 完成 | 同上 |
| Eligibility false negative / false positive | 0 / 0 | 4 完成 | 由 `ACTION_ELIGIBILITY` trace 得出 |
| 重复追问率 | NOT MEASURED | — | 需多轮场景 |
| 无依据工具调用率 | NOT MEASURED | — | 需能力场景 |
| 实际执行成功率 | 4/8 场景完成 | 8 | 见 §3 |
| P50 / P95 端到端延迟 | 44137 / 47241 ms | 4 完成 | — |

## 3. 失败类型统计（真实案例与根因）

**先分清类别**：本轮 8 个真实场景里，`INFRA` 4 个、`COMPLETED` 4 个。
INFRA 一律**不计为决策错误**。

> 证据口径说明：Java 边界会把 Brain 层的多种失败折叠成统一的
> `AgentBrainUnavailableException`，因此 `P3A_LAYER_BC_LIVE_BASELINE.json`
> 里 4 条 INFRA 的 `infraClass` 都记为 `BRAIN_UNAVAILABLE`。下表中更细的
> 根因（非 JSON、502、读超时、未知字段）取自**同一轮** Brain 服务日志的原始
> 报错行，不是推测；两者指向同一次运行。

| # | 场景 | 类别 | 真实表现 | 根因 |
|---|---|---|---|---|
| 1 | P3A-B01 | C（决策） | 对"把售后维修响应时间从两天缩短到四小时以内"，选 `INVOKE_CAPABILITY`（不在 acceptable 集合） | Claims 提取正确（`CONFIRMED 将售后维修的响应时间从两天缩短到四小时以内`），但把"有信息可查"当成"必须现在调能力"。与既有 R4/Semantic Ranking 记录的 `RUI→IC` 同族：**相关 ≠ 必须** |
| 2 | P3A-B05 | C（payload） | 动作未产出，run 以 `AgentContractException: questionText must be a string` 失败 | 模型在 `REQUEST_USER_INPUT` 的 `questionText` 位置输出了非字符串；Java 契约 fail-closed 正确拦截，属**结构化 payload 生成**缺陷 |
| 3 | P3A-B02 | B（协议 / INFRA） | `BrainContractError: model output is not valid JSON` ×1 | 模型单次输出非 JSON；Brain fail-closed，**未污染持久化状态** |
| 4 | P3A-B03 | B（协议 / INFRA） | `model output is not valid JSON` ×1 | 同上 |
| 5 | P3A-B08 | B（协议 / INFRA） | `STATE_UPDATE contract: Extra inputs are not permitted`（多出一个 `note` 字段） | 严格契约拒绝未知字段——正确行为；属模型**输出形状**违约，非理解错误 |
| 6 | P3A-B04 | INFRA | `ModelClientError: inference broker returned status 502` | Provider 侧故障 |
| 7 | P3A-B08 | INFRA | `BrokerTimeoutError: inference broker timeout` | 真实模型延迟超过 broker 读超时（单次 128–208 秒的历史记录已在代码注释中） |
| 8 | P3A-B02 / B03 / B04 | INFRA | `AgentBrainUnavailableException` | 上述 3、4、5、6、7 折叠到 Java 边界的统一表现 |

**A 层主导根因（本轮优化对象，证据最强）**：

`retrieval_entries.search_vector` 由
`to_tsvector('simple', coalesce(content,''))` 生成
（`V40__retrieval_runtime.sql:20-21`），而 `simple` 配置**没有中文分词器**，
会把每个由标点/空白分隔的中文连续串保留成**整句一个 token**。实测：

```
to_tsvector('simple','用户取消后不得继续追加回答正文。规则编号 REQ-107。')
  -> '-107':4 'req':3 '用户取消后不得继续追加回答正文':1 '规则编号':2
plainto_tsquery('simple','终止对话后的正文有什么约束？')
  -> '终止对话后的正文有什么约束'
=> 398 行里命中 0 行
```

因此任何**非逐字节等于整句**的中文查询都匹配不到词法通道；ASCII 标识符
（如 `REQ-107`）因为分词器会切开非中文而不受影响。trigram 通道同样是字节级
（`similarity` / `word_similarity` 阈值 0.15），中文改写实测两者的相似度均为 0。

**因次生影响**：默认安装下项目 Agent 对中文几乎只能靠 `graph`（按 ref 直取）与
偶然的逐字重合，这才是 A 层修复前 Recall@5 仅 0.50 的原因。

## 4. 已实施的优化（附失败证据）

### 优化 1（主要，A 层，通用）：CJK 感知的词法通道

| 文件 | 改动 |
|---|---|
| `backend/src/main/resources/db/migration/V94__cjk_aware_lexical_lane.sql` | **新增**。`cjk_bigrams(text)`（中文二元组，非 CJK 输入返回空串）+ `cjk_tsquery(text)`（保留原 ASCII AND 语义，OR 上中文 bigram），重建 `search_vector` 为 `content + cjk_bigrams(content)`，重建 GIN 索引 |
| `backend/src/main/java/com/specagent/retrieval/persistence/RetrievalEntryRepository.java:278-294` | `lexical()` 的谓词与排序由 `plainto_tsquery('simple',:queryText)` 改为 `cjk_tsquery(:queryText)` |
| `backend/src/main/java/com/specagent/retrieval/protocol/RetrievalStore.java:209-214` | 共享路径 lexical lane 同样改用 `cjk_tsquery` |

**为什么这是通用修复而非业务分支**：它只改变"中文如何被切分成检索词"，
不引入任何业务词表、场景 ID、固定措辞或 benchmark 识别；对**不含中文**的输入，
`cjk_bigrams` 返回空串，`cjk_tsquery` 退化为与 `plainto_tsquery('simple',…)`
**完全等价**（有专门回归用例锁定这一点）。改动只作用于
`retrieval_entries` —— 一个明确声明为"可删除、可重建的派生物、绝不是事实源"的表
（`V40:48-49`）；规范事实、授权、scope、authority 与来源校验一律未动。

**对应失败证据**：冻结 52 条校准集上，中文改写类 All-required@5 从 **1/16 提升到 13/16**，
整体 Recall@5 **0.50 → 0.90**、MRR@5 **0.475 → 0.7979**，
而 ASCII 标识符保持 **16/16 不变**、零命中正确率保持 0.8333、越界与过期来源均为 0。

**已如实记录的残余代价**：bigram 召回依赖"至少共享一个双字词"，因此只共享常见
疑问词（如"什么"）的无关中文也会命中。这被固定为一条显式用例
（`cjkLexicalLaneKeepsBoundedPrecisionForCompletelyUnrelatedQueries`），
并记录：真正零共享 bigram 的查询仍返回空。在冻结 52 条集上该代价**可测为零**
（修复前后零命中正确率都是 0.8333）。**未使用任何阈值掩盖它** ——
实测"相关对"与"无关对"的共享 bigram 数都是 1，任何阈值都会同时伤害两者。

### 优化 2（次要，评测工具链）：`LiveExecutionGuard` 与生产装配对齐

`LiveExecutionGuard.requireRemoteProvider` 只接受 `OpenCodeModelInferenceGateway`，
但生产装配已把 `RoutingModelInferenceGateway`（`@Primary`，按激活提供商委派）
作为权威入口，导致**真实 live 链路被误判为 fake**、P3-A 真实模型评测无法进行。

- `backend/src/eval/java/com/specagent/eval/LiveExecutionGuard.java`：改为接受
  **封闭集合** {`RoutingModelInferenceGateway`, `OpenCodeModelInferenceGateway`}，
  并新增"未知网关仍必须拒绝"的用例，避免放宽成"不是 fake 就算 live"。
- 这是评测工具修复，**不改变任何生产行为**。

## 5. 优化前后对比：哪些来自代码，哪些来自运行条件

| 变化 | 来源 | 证据 |
|---|---|---|
| A 层 Recall@5 0.50 → 0.90、MRR@5 0.475 → 0.7979 | **实际代码**（V94 + 两处查询点） | `P3A_LAYER_A_RETRIEVAL.json` 的 `before` / `after`，**同一次运行、同一融合链路**，唯一差别是词法谓词 |
| 中文改写 1/16 → 13/16、资源内容 3/8 → 7/8 | **实际代码** | 同上，逐场景 `retrievedAliasesBefore/After` |
| ASCII 16/16、零命中 0.8333、越界/过期 0 不变 | **实际代码**（无回退） | 同上 |
| B 层 forbidden=0、unknownAsserted=0（从首次的 1 / 2 降为 0） | **评测工具（oracle）修正**，不是 Agent 改进 | 首次判定把"明确撤销旧定位"误判为过度概括、把"主题词出现"误判为"把未知说成已知"；修正后 Agent 行为**未被改动** |
| 生产向量路径 Recall@8 = 0.95 复现 | **运行条件**（本地 Ollama + pgvector） | 本轮真实复跑，与既有产物一致 |
| C 层 acceptable-set accuracy 0.50 | **运行条件**（真实模型首轮 8 场景） | `P3A_LAYER_BC_LIVE_BASELINE.json` |

> 生产向量路径复跑细节：`datasetSha256=7747542f…`（当前文件），
> `recallAt8=0.95`、`mrr=0.95`、`javaLegacyLexicalRecallAt8=0.525`、
> `zeroHitCorrectness=1.0`、`canonicalStaleLeaks=0`、`permissionLeaks=0`、
> `p50=309ms`、`p95=503ms`、`productionEngineChanged=false`。
> **注意**：该产物会覆盖 `SHARED_RAG_52_QUERY_EVALUATION.json`，本轮已复跑后
> **恢复原文件**，未把它当作新的验收成绩。

## 6. 可靠性和回归

| 套件 | 命令 | 结果 |
|---|---|---|
| 后端确定性全量 | `backend> gradlew.bat testNonLive` | **2025 tests / 2 failed / 34 skipped** |
| 其中失败 | `GlobalAssistantSliceFStreamTest.threadRunMessageRefreshRecovery`、`GlobalAssistantSseHardeningTest.lastEventIdCursorReplaysOnlyLaterEvents` | **基线 a1c1696 上同样失败**（已 stash 后在干净树复现），与本轮改动无关；属既有 GA SSE 时序不稳定 |
| P3-A A 层 | `backend> gradlew.bat evalP3ARetrieval` | 5 tests / 0 fail |
| P3-A B+C 层（真实模型，非阻塞） | `backend> gradlew.bat evalP3ALiveProjectAgent` | 1 test / 0 fail；4 完成 / 4 INFRA |
| 既有检索关卡 | `backend> gradlew.bat evalRetrievalFast` | BUILD SUCCESSFUL；Recall@K 0.909、MRR 0.95、污染 0 |
| 既有 Agent 评测 | `backend> gradlew.bat evalBFast` | 14/14 passed，调用预算 0 违规 |
| Python Brain | `agent-brain> .venv/Scripts/python -m pytest -q` | **239 passed** |
| 评测工具 | `python -m pytest tools/tests -q` | **108 passed** |
| 真实 52 条向量评测 | `SPEC_AGENT_RAG_EVALUATION=true` + `SharedRetrievalIntegrationTest` | PASS |

`LiveExecutionGuardTest` 8 tests / 0 fail（含新增的 routing-gateway 接受与未知网关拒绝）。

**数据库与用户数据**：真实模型评测在**独立数据库** `spec_agent_p3a` 上运行
（从 dev 库 schema-only 复制 + 只搬 1 行加密凭据与身份行，`projects=0`），
**未写入、未清空、未重建用户 dev 数据**（`spec_agent` 的 40 个项目保持原样）。
未提交任何 API key、密码、密钥文件或用户敏感内容。

## 7. 当前遗留问题（分类）

**需要下一步产品优化**

1. **C 层决策质量 0.50，是当前最大瓶颈**：主要形态是"把有信息可查当成必须现在调能力"
   （`INVOKE_CAPABILITY` 出现在 acceptable 集合之外），与既有 R4 记录的
   `RUI→IC` 同族根因——**语义表示不足**，而非 eligibility 过窄。
2. **C 层 payload 缺陷**：`REQUEST_USER_INPUT.questionText` 收到非字符串
   （`questionText must be a string`）。属结构化 payload 生成，修复方向是契约表达与
   生成约束，不是放宽 Java 校验。
3. **A 层剩余 0.90**：残留 3/16 中文改写未召回，且 trigram 通道对中文改写
   实测相似度为 0（字节级阈值对中文基本无效）——这是下一个 A 层候选点，
   但不应在缺乏人工标签前继续"堆算法"。
4. **bigram 精度残余**：只共享常见疑问词的无关中文会命中。已量化、已固定用例，
   未掩盖。

**需要人工标签审核**

5. 52 条校准集全部 `PENDING_HUMAN_REVIEW`，`independentAcceptanceEligible=false`；
   68 条独立候选集 `executionStatus=NOT_RUN`，人工标签未批准、验收阈值未定
   （`thresholdStatus=NOT_APPROVED`）。**在人工审核完成前，本轮所有 A 层数字都只是
   校准集成绩。**
6. 本轮 B/C 的 16 个场景与候选标签同为 AI 编写、`PENDING_HUMAN_REVIEW`，
   其中 acceptable family 集合尤其需要人工确认。
7. `P3A_LAYER_BC_LIVE_BASELINE.json` 的 4 个完成样本量太小，
   acceptable-set accuracy 的置信区间很宽，不应据此下结论。

**需要外部环境解决**

8. **测试档数据库凭据已失效**：`spec_agent_test.opencode_settings` 的密钥返回
   HTTP 401（该库 worker 未运行，398 行 embedding 全为 `PENDING`）。
   因此真实模型评测只能走独立库 + 安装主密钥文件；这是环境事实，不是代码缺陷。
9. **模型服务延迟与不稳定**：8 个场景中出现 1 次 502、1 次 broker 读超时、
   2 次非 JSON 输出。真实模型单轮延迟 28–47 秒（含超时场景），
   P95 已达 47 秒；扩大样本前必须先评估耗时与费用。
10. **既有 GA SSE 时序不稳定**：`testNonLive` 的 2 个失败在基线上同样存在，
    属既有问题，本轮未修改 GA。

**对照基线的一致性提示（未修改任何冻结物）**

11. `contracts/retrieval/evaluation/acceptance-freeze.v1.json` 的 `codeHashes`
    中 5/7 与当前文件不一致；`chinese-queries.v1.json` 的哈希也与
    `GLOBAL_ASSISTANT_RELEASE_VERIFICATION.json` 记录的不同。
    这些是**已提交的既有漂移**（工作树在该文件上原本干净），
    在人工批准标签并解锁 68 条验收前必须先处理，**本轮未改动冻结物**。

## 8. 复跑入口

```bat
:: A 层：冻结 52 条中文校准集 + 修复前后对照（无需真实模型）
cd backend
gradlew.bat evalP3ARetrieval
:: -> backend/build/eval-p3a/layer-a-retrieval.json

:: B + C 层：真实模型（需 broker 模式 brain + 产品已配置模型）
::   1) 独立库（不得指向含用户项目的库）
::   2) brain 指向本套件的 broker 端口
set SPEC_AGENT_BRAIN_MODEL_MODE=broker
set SPEC_AGENT_INTERNAL_BROKER_URL=http://localhost:18084/internal/v1/model-inference
set SPEC_AGENT_BRAIN_INTERNAL_SECRET=<data\internal-secret.txt>
cd agent-brain && .venv\Scripts\python -m uvicorn spec_agent_brain.app:app --port 8100
cd backend
gradlew.bat evalP3ALiveProjectAgent
:: -> backend/build/eval-p3a/layer-bc-live.json
```

评测工具随本轮一起提交：`tools/p3a/p3a_scenarios.json`（场景与候选标签）、
`tools/p3a/p3a_live_bc_evaluation.py`（HTTP 驱动版，供不启动评测源集时使用）。

## 9. 结论

- **A 层**：找到并修复了一个通用、可复现、有量化证据的根因（中文词法分词）。
  默认安装 Recall@5 **0.50 → 0.90**，MRR@5 **0.475 → 0.7979**，
  ASCII 行为与所有安全边界不回退。
- **B 层**：真实模型在条件、否定、需求变更、冲突、未决定义上的结构化理解
  **表现良好**（无依据 CONFIRMED = 0、未决被误标 CONFIRMED = 0、过度概括 = 0）；
  主要 B 层问题是**输出形状/协议违约**（非 JSON、未知字段、非字符串 payload），
  由 Brain 与 Java 契约 fail-closed 正确拦截。
- **C 层**：acceptable-set accuracy 0.50，是**当前最大质量瓶颈**，
  形态是"相关被当成必须"，与既有 R4 记录一致；本轮**未**通过放宽 eligibility
  或 Java 授权来"提高"它。
- 未达到的参考门槛（补充检索 Recall@5 ≥85%、Claim Precision ≥90%、
  acceptable-set ≥85%）**不宣称达标**：A 层 0.90 缺人工标签、B 层 Precision 无分母、
  C 层样本量与分数都不足。

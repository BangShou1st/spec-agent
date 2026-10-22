<!--
status: Proposal
authority: Current Authority (Pending Human Review)
canonical-link: contracts/retrieval/evaluation/ACCEPTANCE_HUMAN_REVIEW.v2-revised.md
-->

# Spec Agent · 独立检索验收集 (v2 修订版) 人工审阅材料

> **审阅状态**：`PENDING`（待人工审阅）  
> **数据集角色**：`INDEPENDENT_ACCEPTANCE_CANDIDATE` (SHA256: `59d9ea47db9cb6c51649ea526730ae4954618c86da559f396946e6522229f9a6`)  
> **父数据集**：`acceptance-candidate.v2.json` (SHA256: `e34a3d6172cac38c70d21cdf789ca64dc5d2ebe931dcc8a0b407a5a00c25165e`)  
> **关联冻结文件**：`acceptance-freeze.v2-revised.json`  
> **执行状态**：`NOT_RUN`（独立查询运行数为 0，未曾参与算法或参数调优）  
> **首次运行原则**：必须先由项目负责人完成人工审阅并签署《HUMAN_REVIEW_DECISION_CHECKLIST.v2-revised.md》，方可执行首次独立验收运行。

---

## 一、来源清单 (28 个来源修订定义及规范出处)

| Alias | 类型 (Kind) | 来源正文 | 规范文档依据 / 修订说明 |
|---|---|---|---|
| `N00` | **NODE** | Workspace Unit具有稳定UUID身份；节点软删除仅撤回可见性而保留历史溯源。检索sourceVersion为派生来源的版本标识，不意味着每个Node实体具有version字段。 验收夹具标识 FACT-3100。 | `docs/v2/NODE_MODEL_V2.md`<br>*Corrected: Node entity has no version column; retrieval sourceVersion is derived source identifier.* |
| `N01` | **NODE** | Java Runtime根据冻结事件与输入确定动作能否提议，并重验准入；AdvisorPolicy独立决定自动执行、确认或拒绝。WAIT只允许已存在且获准的pending依赖，Graph lineage不得合并或rejoin。 验收夹具标识 FACT-3101。 | `docs/v2/AGENT_RUNTIME.md`<br>*Corrected: Graph lineage merging/rejoining is strictly forbidden; Action Eligibility only gates proposals.* |
| `N02` | **NODE** | 当前固定检索配置中vector候选lane使用最大余弦距离0.50；lexical/trigram通道不受此距离统一截断。 验收夹具标识 FACT-3102。 | `docs/v2/UNIFIED_PYTHON_RAG_DESIGN.md`<br>*Corrected: 0.50 applies specifically to vector lane, lexical/trigram lane is not truncated by this distance.* |
| `N03` | **NODE** | 索引重建Worker通过RebuildLeaseCoordinator认领embedding_rebuilds并以epoch/lease/worker围栏写入；过期行可被接管或按attempt策略重排/失败，区别于全局Agent任务调度。 验收夹具标识 FACT-3103。 | `backend/src/main/java/com/specagent/retrieval/workload/RebuildLeaseCoordinator.java`<br>*Corrected: RebuildLeaseCoordinator coordinates embedding_rebuilds tasks, not global multi-worker scheduling.* |
| `N04` | **NODE** | 运行时模型网关对所有供应商响应实施 Fail-Closed 校验；格式不合规的结构化输出直接标记为失败。 验收夹具标识 FACT-3104。 | `docs/v2/AGENT_RUNTIME_ARCHITECTURE.md`<br>*保留原规范事实* |
| `N05` | **NODE** | 跨项目检索授权通过 RetrievalScopeGrant 显式下发；无当前项目授权的私有语料在 SQL 层直接阻断。 验收夹具标识 FACT-3105。 | `docs/v2/PHASE2_IDENTITY_AUTHORIZATION_DESIGN.md`<br>*保留原规范事实* |
| `N06` | **NODE** | 全局助手在 LangChain 架构下将会话记忆与持久化 Checkpoint 绑定；单步失败不会破坏历史有效状态。 验收夹具标识 FACT-3106。 | `docs/v2/GLOBAL_ASSISTANT_LANGCHAIN_REDESIGN.md`<br>*保留原规范事实* |
| `N07` | **NODE** | 词法通道使用CJK双字bigram增强无中文分词器时的召回；无共享bigram仍可能漏召回，共享常见双字也可能误召回。 验收夹具标识 FACT-3107。 | `backend/src/main/resources/db/migration/V94__cjk_aware_lexical_lane.sql`<br>*Corrected: CJK bigram enhances lexical recall without segmenter; does not guarantee 100% natural language recall.* |
| `N08` | **NODE** | 固定项目profile使用qwen3-embedding:0.6b和1024维向量；归一化后按float32存储，以小端float32字节计算SHA-256检查数据一致性，checksum不认证模型或内容真实性。 验收夹具标识 FACT-3108。 | `docs/v2/UNIFIED_PYTHON_RAG_DESIGN.md`<br>*Corrected: Little-endian float32 checksum validates data transmission integrity, not authenticity of model.* |
| `N09` | **NODE** | C16-WAIT由超时定时器或事件通知唤醒并重新评估条件；超时唤醒不等于任务取消。 验收夹具标识 FACT-3109。 | `backend/src/main/java/com/specagent/agent/action/ActionEligibility.java`<br>*Corrected: Timeout timer wakes up and re-evaluates condition or degrades; timeout does not equal cancellation.* |
| `N10` | **NODE** | 混合检索融合采用标准 Reciprocal Rank Fusion (RRF)，平滑常数 k 固定配置为 60。 验收夹具标识 FACT-3110。 | `docs/v2/UNIFIED_PYTHON_RAG_DESIGN.md`<br>*保留原规范事实* |
| `N11` | **NODE** | Answer历史修改通过AnswerPatch追加记录，不覆写原Answer；通用Node属性不使用AnswerPatch修改。 验收夹具标识 FACT-3111。 | `docs/CONTEXT_RULES.md`<br>*Corrected: AnswerPatch is dedicated to Answer recovery checkpoints, not general Node property mutation.* |
| `N12` | **NODE** | 外部 Web 检索工具 Tavily 仅在全局助手明确授权时调用；获取的网页内容按外部证据级别标记。 验收夹具标识 FACT-3112。 | `docs/v2/GLOBAL_ASSISTANT_SINGLE_ENGINE_AND_WEB_TOOLS_PLAN.md`<br>*保留原规范事实* |
| `N13` | **NODE** | 项目 Agent 对当前路线的上下文重构采用线性祖先溯源；被剪枝或分叉的同级路线节点排除在上下文窗口之外。 验收夹具标识 FACT-3113。 | `docs/v2/GRAPH_ROUTE_CONTEXT_INTEGRITY_INVESTIGATION.md`<br>*保留原规范事实* |
| `N14` | **NODE** | 内部HTTP客户端配置请求与连接超时预算；超时未响应抛出超时异常，系统未引入独立circuit breaker熔断器。 验收夹具标识 FACT-3114。 | `backend/src/main/java/com/specagent/model/provider/HttpOpenCodeZenTransport.java`<br>*Corrected: Timeout budgets raise HTTP timeout exceptions; no standalone circuit breaker component exists.* |
| `N15` | **NODE** | 内部服务客户端显式采用DIRECT直连以防代理劫持；全局SOCKS代理环境变量保持保留，不静默删除用户环境配置。 验收夹具标识 FACT-3115。 | `agent-brain/requirements.lock`<br>*Corrected: Internal clients explicitly use DIRECT connection while preserving global proxy environment.* |
| `R00` | **RESOURCE** | # 分块规范文档<br><br>文本切分采用固定长度窗口；单块最大长度受配置控制，实现中不使用100字符滑动重叠。文档归档编号 DOC-CHUNK-800。 | `agent-brain/src/spec_agent_brain/retrieval/chunks.py`<br>*Corrected: Real chunking implementation does not use 100-char sliding overlap.* |
| `R01` | **RESOURCE** | # 存储认证规范<br><br>Python与Java节点通信使用内部共享Secret Token进行请求头校验，防止未授权跨进程调用；鉴权为单向请求认证而非双向握手。文档归档编号 DOC-AUTH-SHARED。 | `backend/src/main/java/com/specagent/retrieval/protocol/RetrievalWire.java`<br>*Corrected: Auth is unidirectional X-Spec-Agent-Internal-Token header verification, not bidirectional handshake.* |
| `R02` | **RESOURCE** | # 索引迁移规范<br><br>当底层向量索引发生结构升级时，保留旧版本索引直至新 generation 整体校验通过后再行原子切换。文档归档编号 MIG-803。<br> | `docs/v2/LOCAL_DEPLOYMENT_SERVICE_CONFIGURATION_GUIDE.md`<br>*保留原规范事实* |
| `R03` | **RESOURCE** | # 性能基准规范<br><br>本地CPU环境下的检索性能观察目标为P50参考值500毫秒；该值属于观察指标而非强制SLO或单次超时告警门槛。文档归档编号 DOC-PERF-P50。 | `docs/acceptance-evidence/rag-d3-sol-independent-review/independent-review.md`<br>*Corrected: P50 500ms is an observation reference target, not an enforced SLO or timeout alert trigger.* |
| `S00` | **STALE_NODE** | 已撤回实验特性 OBS-STALE-0：曾尝试在检索层直接合并图谱社区发现算法；该特性已正式废弃。 | `隔离测试夹具`<br>*保留原规范事实* |
| `S01` | **STALE_NODE** | 已撤回实验特性 OBS-STALE-1：曾尝试通过多轮 LLM 改写扩展查询词；现已改为确定性 CJK Bigram。 | `隔离测试夹具`<br>*保留原规范事实* |
| `S02` | **STALE_NODE** | 已撤回实验特性 OBS-STALE-2：曾计划使用云端 Embedding API 作为默认；现已全面收敛至本地 Ollama。 | `隔离测试夹具`<br>*保留原规范事实* |
| `S03` | **STALE_NODE** | 已撤回实验特性 OBS-STALE-3：曾设计客户端直接连接 pgvector 数据库；现已改为受保护宿主 RPC。 | `隔离测试夹具`<br>*保留原规范事实* |
| `P00` | **PRIVATE_NODE** | 其他项目绝密配置 CONF-PRIV-0：金融交易网关对接私钥与支付通道路由。 | `隔离测试夹具`<br>*保留原规范事实* |
| `P01` | **PRIVATE_NODE** | 其他项目绝密配置 CONF-PRIV-1：企业专属人事薪酬核算公式与薪资职级对照表。 | `隔离测试夹具`<br>*保留原规范事实* |
| `P02` | **PRIVATE_NODE** | 其他项目绝密配置 CONF-PRIV-2：供应链独家供货协议价格表与年度返点政策。 | `隔离测试夹具`<br>*保留原规范事实* |
| `P03` | **PRIVATE_NODE** | 其他项目绝密配置 CONF-PRIV-3：未经公开的并购谈判意向书与股权质押清册。 | `隔离测试夹具`<br>*保留原规范事实* |

---

## 二、候选查询逐条审阅材料 (共 68 条)

### 项目概念与规则表达
> 覆盖节点模型、Action Eligibility 守卫、向量初筛、租约调度、Fail-Closed 网关、授权过滤等核心语义。

#### `B001` · 节点在图谱里做软删除后，历史上的溯源链条还会保留吗？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N00']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：对应 N00，表达核心溯源链与软删除可见性分离原则。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B002` · 根据Graph执行与守卫规则，系统是否允许模型自动合并分支Lineage？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N01']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：纠正虚假前提：分支Lineage始终禁止合并，Action Eligibility独立仲裁动作提议。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B003` · 向量初筛时，相距太远的节点是在哪个距离阈值被直接筛掉的？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N02']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：对应 N02，明确 0.50 向量距离截断。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B004` · RebuildLeaseCoordinator 在 embedding_rebuilds 场景中如何进行租约加锁与过期接管？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N03']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：纠正范围：RebuildLeaseCoordinator专用于embedding_rebuilds工作负载，非全局Worker调度。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B005` · 模型返回的结构化输出如果不符合契约，网关会怎么处理？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N04']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：对应 N04，体现 Fail-Closed 安全校验原则。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B006` · 没有给当前项目授权的私有文档，SQL 查询能直接阻断吗？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N05']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：对应 N05，明确 RetrievalScopeGrant 与 SQL 授权过滤。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B007` · 全局助手的会话中某一步执行失败，之前的检查点状态会丢失吗？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N06']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：对应 N06，LangChain Checkpoint 状态持久化机制。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B008` · CJK Bigram 双字切分在中文检索中起到什么增强作用，是否存在漏召回或误召回风险？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N07']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：纠正绝对化表述：双字切分增强无分词器时的召回，但无共享bigram仍可漏召回，共享常见字可误召回。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B009` · 本地运行的 Qwen 嵌入模型输出多少维度的向量，如何做校验？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N08']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：对应 N08，固定 1024 维 float32 及小端校验和。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B010` · 等待协定 C16-WAIT 在发生超时后如何唤醒并评估条件，是否直接取消任务？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N09']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：纠正错误事实：C16-WAIT超时由定时器唤醒重新评估条件，超时唤醒不等于任务取消。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B011` · 词法路和向量路做混合检索融合时，使用的 RRF 平滑常数是多少？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N10']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：对应 N10，固定 RRF k=60。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B012` · Answer 历史修改如何通过 AnswerPatch 追加记录，通用节点属性是否适用？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N11']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：纠正错误概念：AnswerPatch专用于Answer历史恢复，通用Node属性不适用AnswerPatch。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B013` · 调用外部 Tavily 网页搜索工具获得的内容，在系统里算什么级别的证据？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N12']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：对应 N12，明确 EXTERNAL_EVIDENCE 外部证据标记。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B014` · 项目 Agent 构建上下文时，分叉路线上的同级节点会被包含进来吗？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N13']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：对应 N13，线性祖先溯源与同级路线隔离。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B015` · 向本地 Broker 发起模型调用如果卡住了，客户端有什么保护措施？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N14']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：对应 N14，明确独立超时预算与主动熔断机制。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B016` · 系统如何防止本地代理环境变量影响 Python 检索服务的网络通信？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N15']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：对应 N15，子进程启动时移除全局 SOCKS 代理。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B051` · 本次结构化输出不合规被直接标记失败后，系统是否自动重试修复，还是结束本次调用？
- **类别**：`PROJECT_EXPRESSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N04']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：转为正向查询：N04明确说明不合规直接标记失败并不做自动重试。正向分母调为49。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

### 精确标识符与特定配置
> 覆盖具体算法编号、常数及固定配置的精准召回表达。

#### `B017` · FACT-3100
- **类别**：`EXACT_IDENTIFIER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N00']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：精确标识符匹配 N00。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B018` · FACT-3101
- **类别**：`EXACT_IDENTIFIER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N01']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：精确标识符匹配 N01。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B019` · FACT-3102
- **类别**：`EXACT_IDENTIFIER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N02']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：精确标识符匹配 N02。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B020` · FACT-3103
- **类别**：`EXACT_IDENTIFIER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N03']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：精确标识符匹配 N03。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B021` · FACT-3104
- **类别**：`EXACT_IDENTIFIER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N04']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：精确标识符匹配 N04。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B022` · FACT-3105
- **类别**：`EXACT_IDENTIFIER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N05']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：精确标识符匹配 N05。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B023` · FACT-3106
- **类别**：`EXACT_IDENTIFIER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N06']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：精确标识符匹配 N06。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B024` · FACT-3107
- **类别**：`EXACT_IDENTIFIER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N07']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：精确标识符匹配 N07。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B025` · FACT-3108
- **类别**：`EXACT_IDENTIFIER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N08']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：精确标识符匹配 N08。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B026` · FACT-3109
- **类别**：`EXACT_IDENTIFIER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N09']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：精确标识符匹配 N09。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B027` · FACT-3110
- **类别**：`EXACT_IDENTIFIER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N10']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：精确标识符匹配 N10。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B028` · FACT-3111
- **类别**：`EXACT_IDENTIFIER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N11']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：精确标识符匹配 N11。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B029` · FACT-3112
- **类别**：`EXACT_IDENTIFIER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N12']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：精确标识符匹配 N12。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B030` · FACT-3113
- **类别**：`EXACT_IDENTIFIER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N13']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：精确标识符匹配 N13。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B031` · FACT-3114
- **类别**：`EXACT_IDENTIFIER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N14']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：精确标识符匹配 N14。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B032` · FACT-3115
- **类别**：`EXACT_IDENTIFIER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N15']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：精确标识符匹配 N15。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

### 多来源组合与复合推导
> 覆盖需同时命中两项或以上必需证据的复合问法。

#### `B041` · Workspace Unit 的稳定身份与分支合并限制规则分别由哪些机制说明？
- **类别**：`MULTI_SOURCE`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N00', 'N11']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：澄清复合问法：N00定义稳定UUID身份与软删除溯源，N01定义禁止分支合并。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B042` · 初筛阶段的向量距离截断阈值和混合检索的 RRF 平滑参数各是多少？
- **类别**：`MULTI_SOURCE`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N02', 'N10']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：all-of 必须同时命中 N02 与 N10。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B043` · 项目范围授权边界与全局会话 Checkpoint 机制分别如何设计？
- **类别**：`MULTI_SOURCE`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N05', 'N15']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：澄清复合问法：N05定义项目隔离与范围收紧，N06定义持久化Checkpoint。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B044` · CJK Bigram 双字召回机制与向量小端字节序一致性校验分别如何实现？
- **类别**：`MULTI_SOURCE`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N07', 'N08']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：澄清复合问法：N07定义双字切分增强，N08定义小端float32一致性校验。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B045` · Vector 候选 lane 的 0.50 距离与 RRF 融合的 k=60 分别如何配置？
- **类别**：`MULTI_SOURCE`
- **检索范围**：`['PROJECT', 'RESOURCE']`
- **期望充分证据 (Expected)**：`['R00', 'N02']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：澄清复合问法：N02定义vector候选lane最大距离0.50，N10定义RRF融合k=60。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B046` · Python 宿主鉴权请求头名称与向量代际原子切换的时机分别是什么？
- **类别**：`MULTI_SOURCE`
- **检索范围**：`['RESOURCE']`
- **期望充分证据 (Expected)**：`['R01', 'R02']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：双 RESOURCE all-of：必须同时命中 R01 与 R02。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B047` · 全局助手的 Checkpoint 持久化绑定与宿主异步任务延期唤醒队列各起什么作用？
- **类别**：`MULTI_SOURCE`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N06', 'N09']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：all-of 必须同时命中 N06 与 N09。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B048` · 格式不合规的结构化输出与 Broker 请求超时，客户端及校验边界分别如何处理？
- **类别**：`MULTI_SOURCE`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`['N04', 'N14']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：澄清复合问法：N04定义Fail-Closed直接失败，N14定义请求超时抛出异常（无独立熔断器）。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

### 外部资源与长文本切分
> 覆盖分块规范、共享Token单向鉴权、索引升级原子切换、P50观察目标等文档。

#### `B033` · CHK-801
- **类别**：`RESOURCE_CONTENT`
- **检索范围**：`['RESOURCE']`
- **期望充分证据 (Expected)**：`['R00']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：标识符 CHK-801 对应 R00。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B034` · 根据分块规范文档，文本切分是否采用 100 字符重叠窗口来维持上下文？
- **类别**：`RESOURCE_CONTENT`
- **检索范围**：`['RESOURCE']`
- **期望充分证据 (Expected)**：`['R00']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：纠正虚假数值：实际分块实现不使用100字符滑动重叠。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B035` · SEC-802
- **类别**：`RESOURCE_CONTENT`
- **检索范围**：`['RESOURCE']`
- **期望充分证据 (Expected)**：`['R01']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：标识符 SEC-802 对应 R01。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B036` · 内部共享 Token 在通信中采用何种校验方式来防止未授权跨进程调用？
- **类别**：`RESOURCE_CONTENT`
- **检索范围**：`['RESOURCE']`
- **期望充分证据 (Expected)**：`['R01']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：纠正握手概念：通信鉴权为请求头单向校验，非双向握手。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B037` · MIG-803
- **类别**：`RESOURCE_CONTENT`
- **检索范围**：`['RESOURCE']`
- **期望充分证据 (Expected)**：`['R02']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：标识符 MIG-803 对应 R02。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B038` · 向量索引结构升级期间，旧版本的索引何时被切换替换？
- **类别**：`RESOURCE_CONTENT`
- **检索范围**：`['RESOURCE']`
- **期望充分证据 (Expected)**：`['R02']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：对应 R02，考核新代际校验通过后的原子切换。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B039` · PERF-804
- **类别**：`RESOURCE_CONTENT`
- **检索范围**：`['RESOURCE']`
- **期望充分证据 (Expected)**：`['R03']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：标识符 PERF-804 对应 R03。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B040` · 本地 CPU 环境下的检索 P50 参考目标 500ms 属于强制 SLO 违约门槛还是观察指标？
- **类别**：`RESOURCE_CONTENT`
- **检索范围**：`['RESOURCE']`
- **期望充分证据 (Expected)**：`['R03']`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：纠正指标定性：P50 500ms为观察参考目标，非强制违约告警门槛。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

### 近主题诊断 (语料不足以回答)
> 同技术域近主题查询，冻结语料不足以充分回答；用于诊断模型是否正确拒答，不预设严格零命中。

#### `B049` · 软删除的节点在数据库中保留多少个自然月后会被彻底物理粉碎？
- **类别**：`NEAR_NO_ANSWER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：近主题无答案：涉及软删除与版本溯源(N00)，但语料未提及物理删除月数。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B050` · 仅据冻结租约协调文本，是否定义抢占过期租约前还需额外仲裁等待，以及具体毫秒数？
- **类别**：`NEAR_NO_ANSWER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：近主题诊断：冻结语料未定义仲裁心跳毫秒数，允许返回N03作为背景，但不计入充分证据。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B052` · CJK Bigram 双字切分支持的最大汉字总长度是多少个字符？
- **类别**：`NEAR_NO_ANSWER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：近主题无答案：涉及 CJK 切分(N07)，但语料未提及总字数上限。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B053` · 仅据冻结分块文本，是否说明普通用户可以在设置页修改分块参数？
- **类别**：`NEAR_NO_ANSWER`
- **检索范围**：`['RESOURCE']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：近主题诊断：去除不存在的100字符重叠前提，语料未说明普通用户自定义分块设置。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B054` · X-Spec-Agent-Internal-Token 令牌每隔几小时轮换一次？
- **类别**：`NEAR_NO_ANSWER`
- **检索范围**：`['RESOURCE']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：近主题无答案：涉及内部令牌(R01)，但语料未定义轮换周期。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B055` · 仅据冻结的 Web 工具说明，是否给出单次 web.search 返回搜索摘要的条数上限？
- **类别**：`NEAR_NO_ANSWER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：近主题诊断：区分搜索摘要与正文Fetch，冻结语料未给出具体条数上限。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B056` · 仅据性能观察目标说明，是否另行定义单次请求延迟告警机制和接收角色？
- **类别**：`NEAR_NO_ANSWER`
- **检索范围**：`['RESOURCE']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：近主题诊断：去除超过500ms触发告警发给工程师的虚构前提。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B066` · 仅据冻结语料，是否明确给出前端部署的副本数量？
- **类别**：`NEAR_NO_ANSWER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：转为近主题诊断：前端微服务架构属于软件技术域近主题，但冻结语料未定义生产集群副本数量。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

### 已撤回特性隔离
> 验证已软删除或撤回的历史节点绝不泄漏至检索结果。

#### `B057` · OBS-STALE-0
- **类别**：`STALE_VERSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：撤回版本标识符，禁止泄漏 S00。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B058` · OBS-STALE-1
- **类别**：`STALE_VERSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：撤回版本标识符，禁止泄漏 S01。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B059` · OBS-STALE-2
- **类别**：`STALE_VERSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：撤回版本标识符，禁止泄漏 S02。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B060` · OBS-STALE-3
- **类别**：`STALE_VERSION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：撤回版本标识符，禁止泄漏 S03。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

### 跨项目多租户隔离
> 验证无授权的其他项目私有节点绝不跨租户泄漏。

#### `B061` · CONF-PRIV-0
- **类别**：`PERMISSION_ISOLATION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：越权隔离标识符，禁止泄漏 P00。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B062` · CONF-PRIV-1
- **类别**：`PERMISSION_ISOLATION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：越权隔离标识符，禁止泄漏 P01。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B063` · CONF-PRIV-2
- **类别**：`PERMISSION_ISOLATION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：越权隔离标识符，禁止泄漏 P02。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B064` · CONF-PRIV-3
- **类别**：`PERMISSION_ISOLATION`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：越权隔离标识符，禁止泄漏 P03。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

### 纯无答案与词法抗噪
> 与系统语料完全无关的负例查询；绝不命中STALE/PRIVATE，不计入充分证据。

#### `B065` · 公司新版差旅报销标准中，高铁二等座每天餐补是多少元？
- **类别**：`NO_ANSWER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：纯负样本：完全不相关业务问题，要求严格 0 命中。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B067` · 客户退货退款需要哪一级技术主管签字审批？
- **类别**：`NO_ANSWER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：纯负样本：完全不相关业务问题，要求严格 0 命中。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`

#### `B068` · 本季度对外投资协议中的保密期限具体到哪一年哪一月？
- **类别**：`NO_ANSWER`
- **检索范围**：`['PROJECT']`
- **期望充分证据 (Expected)**：`[]`
- **禁止返回来源 (Forbidden)**：`['S00', 'S01', 'S02', 'S03', 'P00', 'P01', 'P02', 'P03']`
- **审阅要点**：纯负样本：完全不相关业务问题，要求严格 0 命中。
- **人工审阅状态**：`PENDING_HUMAN_REVIEW`


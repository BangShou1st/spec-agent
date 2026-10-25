# RAG-D3 Independent Acceptance Human Review Dossier (v2.1 Revised)

> Status: PENDING_HUMAN_REVIEW
> Candidate Dataset: `acceptance-candidate.v2.1.json` (SHA-256: `3162ce2b67e15f3ad31ab277e0ddaf56700cc609d0ddec386d0a9c232088e0f4`)
> Freeze Configuration: `acceptance-freeze.v2.1.json`
> Parent Candidate: `acceptance-candidate.v2-revised.json`
> Review Decision: **PENDING (Queries Executed: 0, Human Review Required Before Execution)**

## 1. Revision Summary (v2.1)
- **Restored Document Identifiers**: Restored CHK-801 (R00), SEC-802 (R01), MIG-803 (R02), PERF-804 (R03) to ensure 1-to-1 exact identifier matching across sources and queries.
- **Accurate Chunking Semantics (R00)**: Document text strictly aligned with `chunks.py`: target=1200, paragraph/sentence boundaries, no overlap, UTF-16 code unit offsets.
- **Explicit Internal Token (R01)**: Body text explicitly documents `X-Spec-Agent-Internal-Token` header for mutual host-worker authentication.
- **Restored Multi-Source Diversity**: B041 (N00+N11), B043 (N05+N15), B045 (R00+N02), B046 (R01+R02).
- **Decoupled Diagnostic Categories**: 49 Positive, 8 Near-No-Answer, 3 Pure No-Answer, 4 Stale Version, 4 Permission Isolation queries.
- **Execution Gate Status**: Strict Fail-Closed with CAS Execution Claim protection; Candidate Queries Executed = 0.

## 2. Source Ledger (28 Sources)
| Alias | Kind | ID / Ref | Summary | Expected Evidence In |
|---|---|---|---|---|
| `N00` | NODE | Canonical fixture | Workspace Unit具有稳定UUID身份；节点软删除仅撤回可见性而保留历史溯源。检索sourceVersion为... | v2.1 |
| `N01` | NODE | Canonical fixture | Java Runtime根据冻结事件与输入确定动作能否提议，并重验准入；AdvisorPolicy独立决定自动执行、确认... | v2.1 |
| `N02` | NODE | Canonical fixture | 当前固定检索配置中vector候选lane使用最大余弦距离0.50；lexical/trigram通道不受此距离统一截断... | v2.1 |
| `N03` | NODE | Canonical fixture | 索引重建Worker通过RebuildLeaseCoordinator认领embedding_rebuilds并以epo... | v2.1 |
| `N04` | NODE | Canonical fixture | 运行时模型网关对所有供应商响应实施 Fail-Closed 校验；格式不合规的结构化输出直接标记为失败。 验收夹具标识 ... | v2.1 |
| `N05` | NODE | Canonical fixture | 跨项目检索授权通过 RetrievalScopeGrant 显式下发；无当前项目授权的私有语料在 SQL 层直接阻断。 ... | v2.1 |
| `N06` | NODE | Canonical fixture | 全局助手在 LangChain 架构下将会话记忆与持久化 Checkpoint 绑定；单步失败不会破坏历史有效状态。 验... | v2.1 |
| `N07` | NODE | Canonical fixture | 词法通道使用CJK双字bigram增强无中文分词器时的召回；无共享bigram仍可能漏召回，共享常见双字也可能误召回。 ... | v2.1 |
| `N08` | NODE | Canonical fixture | 固定项目profile使用qwen3-embedding:0.6b和1024维向量；归一化后按float32存储，以小端... | v2.1 |
| `N09` | NODE | Canonical fixture | C16-WAIT由超时定时器或事件通知唤醒并重新评估条件；超时唤醒不等于任务取消。 验收夹具标识 FACT-3109。... | v2.1 |
| `N10` | NODE | Canonical fixture | 混合检索融合采用标准 Reciprocal Rank Fusion (RRF)，平滑常数 k 固定配置为 60。 验收夹... | v2.1 |
| `N11` | NODE | Canonical fixture | Answer历史修改通过AnswerPatch追加记录，不覆写原Answer；通用Node属性不使用AnswerPatc... | v2.1 |
| `N12` | NODE | Canonical fixture | 外部 Web 检索工具 Tavily 仅在全局助手明确授权时调用；获取的网页内容按外部证据级别标记。 验收夹具标识 FA... | v2.1 |
| `N13` | NODE | Canonical fixture | 项目 Agent 对当前路线的上下文重构采用线性祖先溯源；被剪枝或分叉的同级路线节点排除在上下文窗口之外。 验收夹具标识... | v2.1 |
| `N14` | NODE | Canonical fixture | 内部HTTP客户端配置请求与连接超时预算；超时未响应抛出超时异常，系统未引入独立circuit breaker熔断器。 ... | v2.1 |
| `N15` | NODE | Canonical fixture | 内部服务客户端显式采用DIRECT直连以防代理劫持；全局SOCKS代理环境变量保持保留，不静默删除用户环境配置。 验收夹... | v2.1 |
| `R00` | RESOURCE | Restored CHK-801; real target=1200, no-overlap, UTF-16 offsets per chunks.py | # 分块规范文档  检索系统的正文分块基于 chunks.py 实现，默认目标块大小 target 为 1200。分块策... | v2.1 |
| `R01` | RESOURCE | Restored SEC-802; text explicitly includes X-Spec-Agent-Internal-Token | # 宿主存储认证规范  Python 检索服务与 Java 宿主之间采用内部安全令牌 X-Spec-Agent-Inte... | v2.1 |
| `R02` | RESOURCE | Restored MIG-803; generation atomic switch | # 索引迁移规范  当底层向量索引发生结构升级时，保留旧版本索引直至新 generation 整体校验通过后再行原子切换... | v2.1 |
| `R03` | RESOURCE | Restored PERF-804; P50 500ms observation target | # 性能基准规范  单次检索请求在本地 CPU 环境下的目标端到端 P50 延迟需小于等于 500 毫秒。该数值作为性能... | v2.1 |
| `S00` | STALE_NODE | Canonical fixture | 已撤回实验特性 OBS-STALE-0：曾尝试在检索层直接合并图谱社区发现算法；该特性已正式废弃。... | v2.1 |
| `S01` | STALE_NODE | Canonical fixture | 已撤回实验特性 OBS-STALE-1：曾尝试通过多轮 LLM 改写扩展查询词；现已改为确定性 CJK Bigram。... | v2.1 |
| `S02` | STALE_NODE | Canonical fixture | 已撤回实验特性 OBS-STALE-2：曾计划使用云端 Embedding API 作为默认；现已全面收敛至本地 Oll... | v2.1 |
| `S03` | STALE_NODE | Canonical fixture | 已撤回实验特性 OBS-STALE-3：曾设计客户端直接连接 pgvector 数据库；现已改为受保护宿主 RPC。... | v2.1 |
| `P00` | PRIVATE_NODE | Canonical fixture | 其他项目绝密配置 CONF-PRIV-0：金融交易网关对接私钥与支付通道路由。... | v2.1 |
| `P01` | PRIVATE_NODE | Canonical fixture | 其他项目绝密配置 CONF-PRIV-1：企业专属人事薪酬核算公式与薪资职级对照表。... | v2.1 |
| `P02` | PRIVATE_NODE | Canonical fixture | 其他项目绝密配置 CONF-PRIV-2：供应链独家供货协议价格表与年度返点政策。... | v2.1 |
| `P03` | PRIVATE_NODE | Canonical fixture | 其他项目绝密配置 CONF-PRIV-3：未经公开的并购谈判意向书与股权质押清册。... | v2.1 |

## 3. Query Review Ledger (68 Queries)
| ID | Category | Scopes | Expected Aliases | Query Text | Review Status |
|---|---|---|---|---|---|
| `B001` | `PROJECT_EXPRESSION` | `PROJECT` | `N00` | 节点在图谱里做软删除后，历史上的溯源链条还会保留吗？ | **PENDING** |
| `B002` | `PROJECT_EXPRESSION` | `PROJECT` | `N01` | 根据Graph执行与守卫规则，系统是否允许模型自动合并分支Lineage？ | **PENDING** |
| `B003` | `PROJECT_EXPRESSION` | `PROJECT` | `N02` | 向量初筛时，相距太远的节点是在哪个距离阈值被直接筛掉的？ | **PENDING** |
| `B004` | `PROJECT_EXPRESSION` | `PROJECT` | `N03` | RebuildLeaseCoordinator 在 embedding_rebuilds 场景中如何进行租约加锁与过期接管？ | **PENDING** |
| `B005` | `PROJECT_EXPRESSION` | `PROJECT` | `N04` | 模型返回的结构化输出如果不符合契约，网关会怎么处理？ | **PENDING** |
| `B006` | `PROJECT_EXPRESSION` | `PROJECT` | `N05` | 没有给当前项目授权的私有文档，SQL 查询能直接阻断吗？ | **PENDING** |
| `B007` | `PROJECT_EXPRESSION` | `PROJECT` | `N06` | 全局助手的会话中某一步执行失败，之前的检查点状态会丢失吗？ | **PENDING** |
| `B008` | `PROJECT_EXPRESSION` | `PROJECT` | `N07` | CJK Bigram 双字切分在中文检索中起到什么增强作用，是否存在漏召回或误召回风险？ | **PENDING** |
| `B009` | `PROJECT_EXPRESSION` | `PROJECT` | `N08` | 本地运行的 Qwen 嵌入模型输出多少维度的向量，如何做校验？ | **PENDING** |
| `B010` | `PROJECT_EXPRESSION` | `PROJECT` | `N09` | 等待协定 C16-WAIT 在发生超时后如何唤醒并评估条件，是否直接取消任务？ | **PENDING** |
| `B011` | `PROJECT_EXPRESSION` | `PROJECT` | `N10` | 词法路和向量路做混合检索融合时，使用的 RRF 平滑常数是多少？ | **PENDING** |
| `B012` | `PROJECT_EXPRESSION` | `PROJECT` | `N11` | Answer 历史修改如何通过 AnswerPatch 追加记录，通用节点属性是否适用？ | **PENDING** |
| `B013` | `PROJECT_EXPRESSION` | `PROJECT` | `N12` | 调用外部 Tavily 网页搜索工具获得的内容，在系统里算什么级别的证据？ | **PENDING** |
| `B014` | `PROJECT_EXPRESSION` | `PROJECT` | `N13` | 项目 Agent 构建上下文时，分叉路线上的同级节点会被包含进来吗？ | **PENDING** |
| `B015` | `PROJECT_EXPRESSION` | `PROJECT` | `N14` | 向本地 Broker 发起模型调用如果卡住了，客户端有什么保护措施？ | **PENDING** |
| `B016` | `PROJECT_EXPRESSION` | `PROJECT` | `N15` | 系统如何防止本地代理环境变量影响 Python 检索服务的网络通信？ | **PENDING** |
| `B017` | `EXACT_IDENTIFIER` | `PROJECT` | `N00` | FACT-3100 | **PENDING** |
| `B018` | `EXACT_IDENTIFIER` | `PROJECT` | `N01` | FACT-3101 | **PENDING** |
| `B019` | `EXACT_IDENTIFIER` | `PROJECT` | `N02` | FACT-3102 | **PENDING** |
| `B020` | `EXACT_IDENTIFIER` | `PROJECT` | `N03` | FACT-3103 | **PENDING** |
| `B021` | `EXACT_IDENTIFIER` | `PROJECT` | `N04` | FACT-3104 | **PENDING** |
| `B022` | `EXACT_IDENTIFIER` | `PROJECT` | `N05` | FACT-3105 | **PENDING** |
| `B023` | `EXACT_IDENTIFIER` | `PROJECT` | `N06` | FACT-3106 | **PENDING** |
| `B024` | `EXACT_IDENTIFIER` | `PROJECT` | `N07` | FACT-3107 | **PENDING** |
| `B025` | `EXACT_IDENTIFIER` | `PROJECT` | `N08` | FACT-3108 | **PENDING** |
| `B026` | `EXACT_IDENTIFIER` | `PROJECT` | `N09` | FACT-3109 | **PENDING** |
| `B027` | `EXACT_IDENTIFIER` | `PROJECT` | `N10` | FACT-3110 | **PENDING** |
| `B028` | `EXACT_IDENTIFIER` | `PROJECT` | `N11` | FACT-3111 | **PENDING** |
| `B029` | `EXACT_IDENTIFIER` | `PROJECT` | `N12` | FACT-3112 | **PENDING** |
| `B030` | `EXACT_IDENTIFIER` | `PROJECT` | `N13` | FACT-3113 | **PENDING** |
| `B031` | `EXACT_IDENTIFIER` | `PROJECT` | `N14` | FACT-3114 | **PENDING** |
| `B032` | `EXACT_IDENTIFIER` | `PROJECT` | `N15` | FACT-3115 | **PENDING** |
| `B033` | `RESOURCE_CONTENT` | `RESOURCE` | `R00` | CHK-801 | **PENDING** |
| `B034` | `RESOURCE_CONTENT` | `RESOURCE` | `R00` | 根据分块规范文档 CHK-801，文本切分的默认目标块大小 target 为何值，且块间是否使用重叠窗口？ | **PENDING** |
| `B035` | `RESOURCE_CONTENT` | `RESOURCE` | `R01` | SEC-802 | **PENDING** |
| `B036` | `RESOURCE_CONTENT` | `RESOURCE` | `R01` | Python 检索服务与 Java 宿主通信时使用什么安全令牌请求头进行握手鉴权？ | **PENDING** |
| `B037` | `RESOURCE_CONTENT` | `RESOURCE` | `R02` | MIG-803 | **PENDING** |
| `B038` | `RESOURCE_CONTENT` | `RESOURCE` | `R02` | 向量索引结构升级期间，旧版本的索引何时被切换替换？ | **PENDING** |
| `B039` | `RESOURCE_CONTENT` | `RESOURCE` | `R03` | PERF-804 | **PENDING** |
| `B040` | `RESOURCE_CONTENT` | `RESOURCE` | `R03` | 系统对本地 CPU 环境下单次检索的 P50 延迟目标要求是多少？ | **PENDING** |
| `B041` | `MULTI_SOURCE` | `PROJECT` | `N00, N11` | Workspace Unit 的软删除可见性与历史溯源规则，以及 Answer 历史修改为何必须使用 AnswerPatch 追加？ | **PENDING** |
| `B042` | `MULTI_SOURCE` | `PROJECT` | `N02, N10` | 初筛阶段的向量距离截断阈值和混合检索的 RRF 平滑参数各是多少？ | **PENDING** |
| `B043` | `MULTI_SOURCE` | `PROJECT` | `N05, N15` | 跨项目检索授权如何通过 RetrievalScopeGrant 显式阻断私有语料，以及内部客户端如何采用 DIRECT 直连防止代理劫持？ | **PENDING** |
| `B044` | `MULTI_SOURCE` | `PROJECT` | `N07, N08` | CJK Bigram 双字召回机制与向量小端字节序一致性校验分别如何实现？ | **PENDING** |
| `B045` | `MULTI_SOURCE` | `PROJECT, RESOURCE` | `R00, N02` | 根据分块规范文档 CHK-801，文本切分的默认目标块大小 target 为何值；以及当前固定检索配置中 vector 候选 lane 的最大余弦距离是多少？ | **PENDING** |
| `B046` | `MULTI_SOURCE` | `RESOURCE` | `R01, R02` | Python 检索服务在鉴权请求头中携带什么安全令牌，以及向量索引结构升级期间旧版本索引何时被原子切换？ | **PENDING** |
| `B047` | `MULTI_SOURCE` | `PROJECT` | `N06, N09` | 全局助手的 Checkpoint 持久化绑定与宿主异步任务延期唤醒队列各起什么作用？ | **PENDING** |
| `B048` | `MULTI_SOURCE` | `PROJECT` | `N04, N14` | 格式不合规的结构化输出与 Broker 请求超时，客户端及校验边界分别如何处理？ | **PENDING** |
| `B049` | `NEAR_NO_ANSWER` | `PROJECT` | `*(Empty)*` | 软删除的节点在数据库中保留多少个自然月后会被彻底物理粉碎？ | **PENDING** |
| `B050` | `NEAR_NO_ANSWER` | `PROJECT` | `*(Empty)*` | 仅据冻结租约协调文本，是否定义抢占过期租约前还需额外仲裁等待，以及具体毫秒数？ | **PENDING** |
| `B051` | `PROJECT_EXPRESSION` | `PROJECT` | `N04` | 本次结构化输出不合规被直接标记失败后，系统是否自动重试修复，还是结束本次调用？ | **PENDING** |
| `B052` | `NEAR_NO_ANSWER` | `PROJECT` | `*(Empty)*` | CJK Bigram 双字切分支持的最大汉字总长度是多少个字符？ | **PENDING** |
| `B053` | `NEAR_NO_ANSWER` | `RESOURCE` | `*(Empty)*` | 长文本分块规范 CHK-801 中，普通用户是否可以在前端界面自定义修改默认切分目标块大小或开启重叠窗口？ | **PENDING** |
| `B054` | `NEAR_NO_ANSWER` | `RESOURCE` | `*(Empty)*` | X-Spec-Agent-Internal-Token 令牌每隔几小时轮换一次？ | **PENDING** |
| `B055` | `NEAR_NO_ANSWER` | `PROJECT` | `*(Empty)*` | 仅据冻结的 Web 工具说明，是否给出单次 web.search 返回搜索摘要的条数上限？ | **PENDING** |
| `B056` | `NEAR_NO_ANSWER` | `RESOURCE` | `*(Empty)*` | 仅据性能观察目标说明，是否另行定义单次请求延迟告警机制和接收角色？ | **PENDING** |
| `B057` | `STALE_VERSION` | `PROJECT` | `*(Empty)*` | OBS-STALE-0 | **PENDING** |
| `B058` | `STALE_VERSION` | `PROJECT` | `*(Empty)*` | OBS-STALE-1 | **PENDING** |
| `B059` | `STALE_VERSION` | `PROJECT` | `*(Empty)*` | OBS-STALE-2 | **PENDING** |
| `B060` | `STALE_VERSION` | `PROJECT` | `*(Empty)*` | OBS-STALE-3 | **PENDING** |
| `B061` | `PERMISSION_ISOLATION` | `PROJECT` | `*(Empty)*` | CONF-PRIV-0 | **PENDING** |
| `B062` | `PERMISSION_ISOLATION` | `PROJECT` | `*(Empty)*` | CONF-PRIV-1 | **PENDING** |
| `B063` | `PERMISSION_ISOLATION` | `PROJECT` | `*(Empty)*` | CONF-PRIV-2 | **PENDING** |
| `B064` | `PERMISSION_ISOLATION` | `PROJECT` | `*(Empty)*` | CONF-PRIV-3 | **PENDING** |
| `B065` | `NO_ANSWER` | `PROJECT` | `*(Empty)*` | 公司新版差旅报销标准中，高铁二等座每天餐补是多少元？ | **PENDING** |
| `B066` | `NEAR_NO_ANSWER` | `PROJECT` | `*(Empty)*` | 仅据冻结语料，是否明确给出前端部署的副本数量？ | **PENDING** |
| `B067` | `NO_ANSWER` | `PROJECT` | `*(Empty)*` | 客户退货退款需要哪一级技术主管签字审批？ | **PENDING** |
| `B068` | `NO_ANSWER` | `PROJECT` | `*(Empty)*` | 本季度对外投资协议中的保密期限具体到哪一年哪一月？ | **PENDING** |

## 4. Human Review Sign-Off Block
```text
Reviewer Name    : [PENDING - MUST BE REAL HUMAN AUDITOR]
Review Timestamp : [PENDING - MUST BE VALID ISO-8601 UPON SIGN-OFF]
Audit Decision   : PENDING (Formal candidate execution blocked until signed)
```

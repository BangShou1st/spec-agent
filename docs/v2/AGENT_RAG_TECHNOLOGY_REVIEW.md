# Agent 与 RAG 技术演进评审

> 日期：2026-10-01。依据当前实施记录及官方技术文档进行设计评审；本次不修改运行时代码、不重复执行应用测试。
> 定位：补充技术选型与演进门槛，不替代统一 RAG 设计、运行时边界或实施验收记录。

## 1. 结论与当前状态

继续当前 `create_agent + middleware + LangGraph checkpoint`、共享 Python RAG、本地 Ollama embedding、Java pgvector 的方向。不因为框架更新而重开阶段 0，不引入第二套索引，不迁移 Java 项目运行时。

LangChain v1 将 `create_agent` 作为标准 Agent 入口，替代旧 `langgraph.prebuilt.create_react_agent`，通过 middleware 管理上下文、工具与模型调用；底层使用 LangGraph。当前新组件采用的是这一方向，而不是旧式链 API。[官方 v1 说明](https://docs.langchain.com/oss/python/releases/langchain-v1)

实施记录中的 1024 维真实 embedding、批量与中文查询验证证明基础接入可用；少量样例不证明项目检索质量达标。记录报告的 Python 283 项、后端 1660 项通过，也不替代真实聊天模型工具调用、宿主 RPC、持久化恢复及生产切换验收。统一检索契约已经固化，但完整 Python RAG 算法与生产链路仍有待实现。详见 [实施记录](GLOBAL_ASSISTANT_LANGCHAIN_IMPLEMENTATION_STATUS.md)。

## 2. RAG 的变化在哪里

RAG 是向模型提供外部证据的方式，不是某个固定框架类。LangChain 官方仍介绍两步 RAG、Agentic RAG 与 Hybrid RAG，也允许将已有数据库或搜索系统包装为工具，无须重建知识库。[官方检索说明](https://docs.langchain.com/oss/python/deepagents/retrieval)

应避免把学习项目的固定切片、单次向量 TopK、独立摘要后再次生成，直接当作完整生产方案。我们需要根据数据结构组织证据，保留标识符检索、权限、版本、引用与上下文预算。

两种“混合”需要区分：混合检索是词法、向量等候选通道融合；Hybrid RAG 是检索与生成流程中加入验证、查询调整等步骤。具备前者不意味着后者已经实现。

## 3. 技术取舍

| 技术 | 本项目决定 | 引入条件与边界 |
|---|---|---|
| 结构化分块与来源元数据 | 放入统一 RAG 基线 | 优先按标题、段落、Node/Answer/Claim/Resource 边界组织；保留来源身份、版本、位置和授权 scope |
| 词法、trigram、向量及已有图关系检索 | 保留，按评测调优 | RRF 编排迁 Python，SQL 候选留 Java；现有 tsvector 不称为 BM25 |
| Agentic RAG | 全局助手的工具检索入口适用 | Agent 按需检索、必要时调整查询；每次 scope、次数、返回量和最终来源均受宿主约束 |
| 固定检索与冻结输入 | 项目 Agent 首次上下文投影继续使用 | 必需事实由 Runtime 保证；显式 memory.search 仍可用；回放使用已冻结输入，不重新检索 |
| 父子块及邻近内容展开 | 作为后续检索优化 | 候选命中但上下文不完整时增加；展开仍受同一权限、来源版本与 token 预算限制 |
| Reranker | 首期不强制 | 正确证据已进入候选集，但排名差时评估；比较收益与本地资源、p95 延迟 |
| 查询改写、多轮检索、自我检查 | 按错误类型引入 | 确认查询表达造成漏检后增加有界重试；不让每次请求固定多跑几个模型 |
| Contextual Retrieval | 先做保留上下文的分块，再评估模型增强 | 先补确定性的标题与来源描述；模型生成的解释不得升级为 canonical 事实 |
| GraphRAG | 暂不增加完整知识图谱管线 | 跨文档关联或大语料综述出现明确需求且评测获益时，再设计独立派生索引 |
| 长上下文直接读取 | 小范围、预算允许时作为可选路径 | 不能绕过授权与冻结语义，也不能默认装入所有项目历史 |
| Deep Agents | 保留为后续评估项 | 多步骤长任务、文件工作区、计划或子 Agent 确有收益时再接入宿主能力 |

上述引入时机是本项目的设计判断，不是框架官方要求。

Contextual Retrieval 的原始方法为块生成解释性上下文，再用于向量及词法检索。我们的首期确定性标题/来源补充是较低成本的适配，不能说已经完整实现该方法。[Anthropic 原始说明](https://www.anthropic.com/engineering/contextual-retrieval)

Microsoft GraphRAG 从文本提取实体、关系、社区与摘要，以支持关联分析与语料级问题。它的模型派生知识图谱，与本项目 Java 持有的 Node/Route/Answer 业务事实图不是同一概念。先利用已有、经过验证的业务关系进行有界扩展；以后新增 GraphRAG，也只能是可重建的派生证据层。[Microsoft GraphRAG](https://microsoft.github.io/graphrag/)

Deep Agents 在 LangChain/LangGraph 上提供更完整的任务执行组合，包括规划、上下文卸载、文件系统和子 Agent。它可以配置后端与工具，并不要求放开任意文件或 shell 权限。当前应用助手优先完成原生工具、宿主执行与恢复；若未来使用 Deep Agents，仍需适配同一 Java 权限及副作用边界。[Deep Agents 官方说明](https://docs.langchain.com/oss/python/deepagents/overview)

## 4. 分工不能因框架更新而变化

- Java：项目 Graph/Route/Node/Answer 运行时、权限、事实、事务、能力执行、来源核验、检索存储与索引任务管理。
- Python：全局助手 Agent 编排、共享检索算法、Ollama embedding、排名与上下文组织，通过宿主 RPC 使用存储和能力。
- LangGraph：Python Agent 的执行状态、checkpoint 与恢复设施；其流程图不替代 Java 的业务 Graph。

项目 Agent 和全局助手共用检索实现及索引规则，但不必使用相同的检索触发流程。项目首次投影需要可控证据与冻结；全局助手适合按需工具检索。框架 checkpoint 也不自动解决 Java 事务、重复工具副作用或宿主租约，这些必须通过集成验收。

## 5. 当前实施顺序

1. 完成真实聊天模型原生工具调用及 broker 适配验证，明确流式、取消、失败及重复工具调用行为。
2. 打通宿主 RPC、权限与生命周期、checkpoint 持久化和恢复，验证执行预算及副作用幂等。
3. 按 [统一 RAG 设计](UNIFIED_PYTHON_RAG_DESIGN.md) 推进 U1–U3：来源投影、结构化分块、真实索引、混合检索、profile/generation、CAS 与旧实现对照。
4. 建立至少 50 条人工标注查询，覆盖中文改写、精确标识符、资源内容、关系问题、过期版本、无答案及跨项目/路线越权尝试。
5. 达到既定质量与运行时门槛后切换生产；根据错误报告选择额外技术。

评估分开测候选 Recall@K、排名 MRR、答案引用与事实一致性，以及端到端延迟、调用次数和成本。越权证据泄露必须为零；无答案测试要验证合理拒答。冷启动、embedding 耗时、检索耗时和在线端到端 p95 分开记录。

检索失败先定位：索引缺失修索引；分块丢上下文修分块；候选召回不足查查询和通道；候选正确但排名差再评估 reranker；证据充分而答案错则查上下文及生成。不能用升级模型或框架统一替代诊断。

## 6. 版本与模型升级策略

LangChain/LangGraph v1 有版本兼容与弃用政策；实验性、内部或不同成熟度组件需要单独判断。Deep Agents 的演进节奏也不能直接套用稳定核心的兼容承诺。[官方版本政策](https://docs.langchain.com/oss/python/versioning)

当前迁移使用锁定依赖。计划升级时检查官方变更与弃用项，在隔离验证中覆盖真实模型工具调用、消息转换、middleware、异步/流式、checkpoint codec、暂停恢复与宿主副作用，再更新锁文件。明确缺陷或安全修复优先处理，普通新功能以项目收益决定引入时间。

`retrieval.v1` 是应用契约，不追随框架版本编号。当前固定 0.6B/1024 维属于已验收 profile；未来更换模型、维度或影响向量语义的预处理时，创建新 profile/generation 并重建和评测。复用索引存储不意味着混用不兼容的向量。

## 7. 下一会话交接约束

继续当前阶段，不重置已完成基础组件。首期补结构化分块、来源引用和效果评估；Agentic 检索采用现有有界工具循环。暂不强制 Deep Agents、完整 GraphRAG、reranker 或额外反思模型。新的技术只有在明确失败类型、测试集、延迟预算及宿主边界之后才进入实施范围。

## 实施状态更新（2026-10-02）

本评审的职责与技术取舍继续有效。当前共享 Python RAG U1–U3 功能、真实本地 Ollama/宿主 RPC/pgvector、实际模型按需双检索工具和轮次来源展示已落地；前文少量 probe/初始组件状态是历史信息。52 条可审阅校准查询已实际运行，人工标签仍待审；不据此标记生产质量验收完成。当前权威状态见 [实施记录](GLOBAL_ASSISTANT_LANGCHAIN_IMPLEMENTATION_STATUS.md)，部署回滚见 [发布手册](GLOBAL_ASSISTANT_LANGCHAIN_RELEASE_RUNBOOK.md)。

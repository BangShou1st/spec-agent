---
status: Historical
authority: Historical Reference Only
---

# RAG-D4-R1：D4_R1_PARTIAL

2026-10-23，本地 `dev`，起点 `efd500d5ab2df1d070039306be79fc7531c44965`，远端基线 `4a914283195bb0690f42f13718e2272a5b26c105`。建议暂不关闭 D4：工程回归已恢复，真实 Gemini 的项目只读回答得到改善证据，但引用交付和全局助手工具链仍有缺口。未推送、创建 PR、部署或连接业务数据库，未重跑 D3 68 条正式查询。

## 修复及必要性

| 文件 | 根因与修改 |
|---|---|
| `backend/src/test/java/com/specagent/testing/SharedPythonRetrievalFixture.java` | 启动匹配随机端口 Java 宿主的真实 Python，使用宿主 RPC 与真实资源分块任务；清理自己创建的项目/进程。明确注入向量不可用，验证共享 Python 的词法降级，不调用真实 embedding |
| `AgentInputSnapshotBuilderIntegrationTest.java`、`RetrievalV1HardeningIntegrationTest.java` | 去除 RPC 线程不可见的测试事务，显式完成真实分块；修正 sibling node 的 PROJECT scope + origin route 断言，并排除 sibling Answer；未切回旧检索引擎 |
| `agent-brain/tests/test_retrieval_acceptance_contract.py` | 将历史 D3 代码/解释器身份与当前开发回归分开；仍校验原始数据/审阅 hash、人工门禁和 schema，并新增临时夹具的非法代码漂移拒绝测试。未改历史 hash 或生产 preflight |
| 两类现有 Python prompt | 同一次调用要求完整事实引用、多来源覆盖、部分回答与未知、故障说明、帮助/实时状态区分，禁止臆造推荐理由或让用户重复提供已有信息。没有第二个 Agent、Critic 或新增默认串行调用 |
| `.gitattributes` | 历史 `acceptance-candidate.v1.json` 的冻结 hash 来自 CRLF；仅固定其跨平台检出格式，保持冻结字节，不修改旧候选或 hash |
| `RetrievalRecoveryGateTest.java`、`RetrievalEvaluationSnapshotTest.java` | 创建临时目录的父目录；离线检查历史 inventory/hash，并验证缺失解释器拒绝，取消对原机器 Windows 解释器实际存在的依赖。执行资格的 runtime 守卫保留 |
| `GeminiRagControlledAbAcceptanceTest.java`、`backend/build.gradle.kts` | 原真实 Gemini A/B 类被确定性 `evalRetrievalFast` 广泛匹配；增加 external-service、显式环境开关，并从该任务排除 live tag，防止 CI 意外访问真实模型 |
| `.github/workflows/ci.yml`、`tools/tests/test_ci_validation.py` | Python 安装使用既有锁文件；补上 eval-baseline 的 Python/brain 安装及明确解释器，静态检查同时约束 backend/eval-baseline 和 live 排除规则 |
| `RagD4R1GeminiDiscoveryTest.java`、`RagD4R1GeminiBehaviorTest.java`、`contracts/retrieval/evaluation/rag-d4-r1-behavior.v1.json` | 只读认证目录检查和有界真实项目链路观察；场景在首次推理前冻结。只通过现有 Custom 服务加密保存到新隔离库，凭据不进入 Python；完成后清空临时凭据并恢复该隔离库原 provider |

旧 D4 的来源校验、warnings、向量状态传播及 confirmed 来源守卫全部保留。实际检查的路径：Java RunWorker → NodeQueryService → ContextBuilder → SharedRetrievalHost/Python RAG/Java Store 与最终来源校验 → 首次持久化 AgentInputSnapshot → RemotePythonDecisionEngine → Python DECISION → 授权票据保护的 Java inference broker → RoutingModelInferenceGateway/CustomInferenceGateway → Gemini → Python/Java 契约校验 → Runtime Policy → 只读结果终态持久化。同快照回放读取冻结投影。

## 工程验证

以下均为本轮修复后的实际计数；不同任务有重叠，不能相加当独立样本。

| 范围 | tests / failures / errors / skipped |
|---|---:|
| 默认 python-rag.v1 定向回归 | **97 / 0 / 0 / 0** |
| Java testOffline，含架构、引用/权限及 confirmed 门禁 | **348 / 0 / 0 / 0** |
| evalRetrievalFast，共享 Python 真实夹具 | **122 / 0 / 0 / 0** |
| Python 全部单元测试，锁定环境 | **340 / 0 / 0 / 0** |
| tools/tests，包括 CI 静态契约 | **239 / 0 / 0 / 0** |
| 节点查询 API/Runtime + 两个真实 Python GA framework/checkpoint/business RPC 测试，模型为 fake | **12 / 0 / 0 / 0** |
| Gemini 认证目录，0 次推理 | **1 / 0 / 0 / 0** |

CI 实际排查 run `39883551872` 的 brain/backend/eval-baseline 失败日志。上述为本地对应验证，**不是远端 CI 已转绿**；未推送，未触发远端 run，也未运行整个 testDatabaseIntegration、全部浏览器或生产组合。

初次环境连接拒绝、旧 ROUTE 断言失败、目录检查的网络/`models/` 前缀断言失败及静态 CI 命令断言失败均保留于 scratch 日志。修复后结果没有冒充首次独立验收。

## Gemini 首次行为观察

凭据可用：**true**。当前应用无安全可读取的既有 Gemini 配置，按授权只读 Desktop/key2.txt，经现有 Java 服务认证目录及真实推理验证；未输出凭据或后缀。Google AI Studio 的匹配项目为免费层级，目标模型配额页显示 15 RPM / 500 RPD；未切换付费层级或模型。参考[官方计费说明](https://ai.google.dev/gemini-api/docs/billing/)，密钥存在本身不证明免费，故同时核对项目层级。

请求模型 **gemini-3.5-flash-lite**，认证目录返回 `models/gemini-3.5-flash-lite`。实际部署版本未由现有 gateway DTO 暴露，记为未知。10 个新场景均为 **AI_AUDITED_SYNTHETIC_BEHAVIOR_EVALUATION**，由同一开发 AI 设计/审阅；没有人工标注、额外 judge 模型或统计独立性。

覆盖：合成帮助资源、两个必需资源、背景不足、无答案、推荐诱导、跨项目、撤回资源、真实检索连接故障注入、未确认用户意图、冲突与澄清。每场景实际运行 Java 快照、真实 Python 检索/Brain、Java broker、Gemini 和 NodeQuery Runtime/持久化；不是预置 ToolMessage 或合成 allowlist。向量端点刻意不可用：9 个场景经过真实 Python 词法降级，1 个场景在检索连接失败后继续经健康的 Python Brain 推理。没有使用本轮结果完善检索算法。

| 指标 | 首次观察 |
|---|---:|
| 行为通过/失败 | **9/10，通过9，失败R102** |
| 必需事实 | **6/6 事实片段；3/3 有事实要求的场景** |
| 必需结构化引用 | **5/5 来源；3/3 必需引用场景** |
| 未知/拒绝无依据结论 | **8/8**，AI 语义审阅 |
| 全部结构化引用 | **13 条，非法0**，均属于实际冻结 allowlist |
| 非规范正文引用 | **2 处/1场景（R102）**，未作为合法 sourceRef 认领 |
| 无依据断言 | **0/10 场景**，AI 审阅，不能外推为语义证明 |
| 跨项目/撤回内容哨兵泄漏 | **0/2 探针**，两项实际检索为空；未测试完整 Private 身份矩阵 |
| Node/Answer/Patch/Route 未变化 | **10/10**，比较真实数据库前后内容；不包含测试夹具创建，未把关系表/Project 指针纳入该 hash |
| 同快照回放一致 | **10/10** |

冻结的事实、结构化引用身份/完整性、隔离哨兵、调用预算与回放等机器断言在10个场景中均满足；上表9/10是包含AI正文审阅的综合行为结果，不是纯机器评分。R102 的两个事实和 action.sourceRefs 正确，但正文写成 `chunk-0:<resourceId>`，不是真实规范引用。全部首次观察结束后，补充通用 Prompt 约束：包括正文在内的引用串必须逐字复制，不能重排片段或改写前缀。未复测模型，R102 仍失败，修订的实际行为效果未获验证；原 freeze、标签及输出未改。R108 正确解释故障，但泄露内部状态名，仍有文案质量问题。R110 在只读回答中请求选择，没有自动确认任一候选，也未验收完整后续交互确认流程。

首个场景完成后，第二个场景准备阶段触发“不能把资源挂在历史节点”守卫。修正夹具为挂当前 tip 后，仅继续从未推理的 R102–R110；R101 原始输出没有覆盖或复测。恢复逻辑拒绝重跑已有 dispatch 或已完成观察，要求场景和 Prompt hash 与原 freeze 相同。这是同一组首次观察的续接，不是两组独立实验。

总计 **12 次底层 Gemini 请求：2 次现有兼容性探测 + 10 次场景推理**；失败请求0，重试0，工具调用0（自动上下文检索不计为 Agent Capability 调用），无额外裁判调用。10 次行为推理共观察到输入 **45,193** / 输出 **2,209** tokens，探测 tokens 未由现有接口提供。处于已核对免费层级，估算额外费用 **USD 0**，无账单级实付凭证。完成耗时 nearest-rank **P50 4,065 ms / P95 5,523 ms（n=10）**。非流式路径未测首个有效正文/首 token，不用0代替；未验证 Gemini SSE 或原生工具调用。

旧 qwen3.5:4b 的 16/28、7/9 事实、12/17 未知及3/9引用原样保留。新场景事实/引用/未知表现支持改善，但模型、数据与宿主边界不同，**不能据此作严格因果或生产质量比较**。

## 未关闭的缺口

1. 当前 Custom GA 目标固定 TEXT_CHAT，`ChatCompletionsGaTextTransport.requireTextOnly` 拒绝原生 tools/history。没有绕过门禁，因此 Gemini + 全局助手 create_agent 的真实工具调用未验收；另两个 GA 跨语言通过项使用 fake 模型。
2. 项目只读查询 `NodeQueryService`/终态服务仅持久化正文，`NodeQueryRunController.queryResultView` 只投影正文；正确的模型 sourceRefs 尚未交付该查询 UI。不能把本轮模型引用分数当成浏览器来源卡片闭环。将此能力补齐涉及持久化结果、当前来源验证与复用既有卡片，未在此轮扩展引用机制。
3. 来源身份合法、授权及版本校验不证明语义蕴含。旧 D428 仍是有效反例；legacy 空引用/合法 Answer 引用无法证明任意文本被用户确认的问题仍存在。复用 RagD4ClaimAuthorityTest、来源授权/版本、撤权、冻结、幂等、Policy 与 Patch/Spec gate 的确定性测试，不宣称完整 confirmed 写入安全已认证。
4. 真实模型仅覆盖 NODE_QUERY，不覆盖 STATE_UPDATE/用户确认后 Graph 写入、完整跨 Route/Private/外部网页矩阵、过长资源全部事实、重放副作用的全链路和产品浏览器展示。此轮真实 Graph 是只读不变验证，不能替代写门禁验收。

原始输入、模型输出、引用、事件、耗时、token 与判断：`scratch/rag-d4-r1/gemini-first-observation/{freeze.json,requests.jsonl,results.jsonl,review.json,execution.json}`。其他日志/XML/只读计费证据保留在 `scratch/rag-d4-r1/`，不提交重型报告。机器摘要见本目录 verification.json。

D3 候选、冻结、认领/回执及 D4 首轮数据未改；用户原有 `SHARED_RAG_ACCEPTANCE_PRECHECK.json` 修改及无关未跟踪文件保留。隔离容器仅为本轮新建的 `spec-agent-rag-d4-r1-20261023`、55434、`spec_agent_p3b_live_rag_d4_r1_20261023`，无业务卷；凭据已清空，结束时移除自己创建的容器。最终本地交付 HEAD 见 `git log -1` 与交付回复，未推送。

# 全局助手单引擎收敛与联网工具设计

> 日期：2026-10-02。状态：下一开发会话的实施设计，本会话仅写文档。
> 用户明确范围：全局助手采用Python/LangChain，删除其旧Java引擎；项目Agent暂时不能动。项目用于学习，本轮不增加生产发布、人工评测或旧临时目录清理门槛。

## 1. 本轮决定

全局助手仅保留Python `create_agent`编排。删除旧Java决策循环、旧JSON决策协议和执行分支；Java保留应用宿主职责。默认启动必须能用上新助手，Python不可用则明确报错，不回退旧引擎。

增加 `web.search` 和 `web.fetch`，首期采用学习项目已经使用的Tavily。已有项目工具、Skill发现/暂存、来源卡片及轮次过程保持。联网结果是外部参考，不自动写入项目Graph或RAG索引。

项目Agent的Runtime、Brain调用、Graph/Route/Answer、冻结输入、模型配置与检索引擎选择保持现状。尤其不把 `SPEC_AGENT_RETRIEVAL_ENGINE` 默认值改成Python来顺手影响项目Agent。

## 2. 学习材料再次核对与采用项

| 资料 | 已确认代码 | 本轮判断 |
|---|---|---|
| 智扫通Agent项目 | create_agent、middleware、rag_summarize、Ollama embedding | 继续采用标准Agent循环和检索工具思路；其fetch_external_data读取使用记录，不是通用网页搜索 |
| 全家桶第2章2.5_tools | `langchain_tavily.TavilySearch`及包装的web_search | 网页搜索是此前遗漏的明确参考，补入本轮 |
| 全家桶第2章2.7_实战 | TavilySearch作为Agent工具，实际搜索菜谱的保存输出 | 采用按需调用搜索、利用结果回答，不复制“每次都搜索”的领域提示词 |
| 全家桶memory/Middleware | checkpoint、SummarizationMiddleware、动态模型/工具 | checkpoint与摘要已经落地；保留。动态切换模型不在本轮，当前模型不变 |
| EmailFriend | messages+updates、发送前HITL | 借鉴事件分流；不接邮件发送、不新增另一套审批或暂停恢复 |
| marry-ai | 工具调用/结果组件、useStream、历史与UI事件 | 轮次过程已实现；扩展网页来源卡片，不改React或直接连接LangGraph Server |
| 私厨前端及app API | Markdown正文；chat API部分仍为pass | 仅作教学参考，不当完整生产实现 |

## 3. 当代接口选择与过时判断

- 使用 `langchain.agents.create_agent`、middleware、标准工具、LangGraph checkpoint。LangChain v1的推荐入口已经是create_agent，而非旧create_react_agent或AgentExecutor链式教程。[官方v1说明](https://docs.langchain.com/oss/python/releases/langchain-v1)
- Tavily的当前独立集成包是langchain-tavily，含Search/Extract等工具。教学示例的TavilySearch仍是有效参考，不抄旧community中的TavilySearchResults路径。[官方集成仓库](https://github.com/tavily-ai/langchain-tavily)
- 官方流式文档推荐新应用评估event streaming，但本地已验证的正文sink/工具RPC/checkpoint无需为追新重写；v3 projection存在实验性接口。保持现有流式，未来按收益单独验证。[流式文档](https://docs.langchain.com/oss/python/langchain/streaming)、[LangGraph参考](https://reference.langchain.com/python/langgraph/stream)
- 不强制升级到最新所有依赖。保持当前锁；确需新增包时核对Python3.11、LangChain及Pydantic兼容并锁定经过验证的版本。开发时重新核对官方接口，禁止按旧教程记忆猜参数。

## 4. 收敛后的职责

```text
前端请求 / SSE
    ↓
Java 全局助手生命周期、Coordinator、认证RPC、公开事件
    ↓
Python create_agent + middleware + checkpoint适配
    ├─ 聊天模型 → Java模型broker → 当前供应商
    ├─ 业务工具 → Java Capability宿主
    ├─ 联网工具 → Java Tavily服务适配 → Search / Extract API
    └─ 已有共享Python RAG → Java scoped store RPC / pgvector
```

“删除Java引擎”只删除Java自主决策编排，不删除Java宿主。工具业务逻辑、外部API适配和契约校验仍需代码，框架不能替代应用事实规则。Python用现有StructuredTool/HostTools运行工具，这属于真实LangChain集成。

首期选择**Java持有Tavily凭据及出站HTTP适配**，复用现有Capability执行方式，避免为一个SDK搭建供应商协议代理。Python不获得Tavily或聊天模型密钥。因此本轮参考TavilySearch的工具能力与官方Search/Extract接口，不声称已经直接使用langchain-tavily Python客户端，也不为名义使用SDK增加无实际用途的依赖。

该选择服务于既有凭据与宿主边界；将来若确需Python直接使用官方SDK，先单独调整凭据/外部执行边界，不在本轮隐式改变。

## 5. 旧代码删除与保留

删除前先做引用盘点；以下是候选，不按目录整包删除：

| 对象 | 处理 |
|---|---|
| GlobalAssistantRuntime | 删除旧六步循环与旧TOOL/CLARIFY/NAVIGATE/FINAL处理 |
| GlobalAssistantBrain | 删除旧JSON推理、repair及旧摘要入口 |
| GlobalAssistantDecision及其Parser/Schema/Validator/SemanticsAdapter | 删除仅供旧GA协议的类型；提取新宿主仍需要的UI校验或通用常量后删除 |
| GlobalAssistantPromptRenderer、AssistantTextStreamDecoder、GlobalAssistantObservation | 无新路径引用后删除旧提示词/JSON正文解码/观察模型 |
| GlobalAssistantRuntimeProperties | 检查新路径依赖；保留或重命名实际共用预算，不删除有效预算限制 |
| GlobalAssistantContextBuilder / GlobalAssistantContext | 新GaExecutionPreparation及Controller仍复用；提取UiRequest、结构化身份与必要宿主投影到中性GA DTO，再删旧提示词上下文路径 |
| GlobalAssistantModelTargetResolver / ModelException | 分辨是否用于新broker或生命周期；只删除旧专属部分 |
| RunDispatcher | 改为直接调用GaExecutionCoordinator，删除旧runtime注入、engine二选一和fallback |
| GaExecutionCoordinator/Preparation/Completion/Store、GaCheckpointStore | 保留，它们是宿主执行协调、预算/租约、完成核验与存储 |
| GaNativeModelBroker、GaCapabilityBroker、GaHostRpcController、原生供应商适配 | 保留当前真实模型路径及Zen兼容请求头/工具要求 |
| 会话/消息/事件仓库、Lifecycle、Recovery、取消/steer、Capability平台 | 保留，不把它们误认成旧决策引擎 |

旧引擎专属测试删除或改写为新入口测试；仍验证权限、幂等、终态、历史兼容的测试迁移保留。项目Agent测试和共享model/retrieval代码不因GA清理批量删除。

## 6. 配置、schema与历史

1. 新GA始终是langchain-ga.v1。建议移除二选一配置；保留引擎版本作为执行记录字段。如需兼容环境变量，只接受langchain-ga.v1或未设置，显式旧值报清楚错误，不能忽略旧值继续错误预期。
2. 当前V43给run.engine_version默认java-legacy.v1；RunRepository创建不显式写该列，GaExecutionStore.initialize还要求CREATED行为旧值，随后改成新值。必须一并修复：新run创建直接标新值，初始化校验新值。使用新增Flyway迁移修改默认，不改已应用V43，不仅改Dispatcher。
3. 历史旧run保留原engine_version及消息/事件，前端继续读取历史；不改写成新执行、不重放工具、不删除历史表。新创建与重复dispatch/取消窗口继续测试。
4. start-dev.bat及实际文档中的启动入口启动/核对独立Python3.11.14候选服务、Java、前端；通过实际新run/checkpoint证明日常启动是新路径。不得输出密钥或杀掉不能确认归属的进程。
5. 保留原Python环境，未证实崩溃根因；不自动重试崩溃。既有凭据、聊天模型和本地embedding不变。

## 7. 项目侧隔离与GA检索

当前help.search及project.content.discover同时依赖GA和项目retrieval.engine两个开关。删除GA旧引擎后，不能为启用它们修改项目retrieval默认。

让GA工具按其自己的依赖readiness选择是否暴露，复用SharedRetrievalHost/PythonRetrievalClient，不新增第二套检索算法或索引。HELP的独立语料可以经既有有界jobs准备；项目内容只读现有已准备且profile/generation匹配的授权索引。

若项目内容索引未准备，明确说明内容检索不可用，保留名称搜索和概要查询，不自动对所有项目重建/切换generation来“修复”。不要改变项目source projector、embedding worker选择、HybridRetriever引擎、memory.search、AgentInputSnapshotBuilder或冻结回放。后续项目RAG切换另行授权。

共享模块可以调用，不能借此改变项目行为。最终交付注明GA两个检索工具实际可用状态；不可用不能假装返回空结果或静默让项目走Python。

## 8. 网页工具契约

### web.search

- 模型参数：query必填（1–2000字符），可选topic（general/news）、timeRange（day/week/month/year）、limit（1–5）。首次以basic深度和默认5条执行；昂贵参数、provider base URL及凭据不由模型选择。
- Java调用Tavily Search，固定include_answer=false、默认不取全文/图片，返回标题、URL、摘要、可用发布时间、获取时间及截断标记；score只作供应商元数据，不当事实置信度。
- 允许中文query，模型按需要搜索；只问工作区项目时优先应用工具，询问当前网页信息时使用搜索。
- 空结果是有效观察；认证、限流、超时、服务失败是明确FAILED，不能假称没有结果。[官方Search API](https://docs.tavily.com/documentation/api-reference/endpoint/search)

### web.fetch

- 模型参数url必填，query可选。每次只读取一个公开HTTP(S)页面，通过Tavily Extract取得正文；不自行实现登录浏览器、递归crawler或任意requests.get抓取器。
- 既可读取搜索所得URL，也可读取用户给定公开URL。拒绝含凭据URL、file/data/localhost/回环/私网目标；供应商端提取不等于应用可以放开任意目的地，返回canonical URL也校验。
- 返回URL、标题（若provider提供）、正文、获取时间、截断标记、失败原因。初始正文预算12000字符，搜索总摘要8000字符；按真实响应与现有RPC总量上限适配，不能把摘要标为已读取全文。
- 明确处理partial/failed_results，网页JavaScript/登录墙无法取得时如实说明，不退化为shell/browser工具。[官方Extract API](https://docs.tavily.com/documentation/api-reference/endpoint/extract)

搜索和读取是只读应用能力，但可能消耗供应商额度；本轮无自动重试/无限查询。默认单次20秒以内并受run剩余时间限制，使用现有事务外prepare，网络等待不持有run锁；取消后迟到结果不能写入活动run。

未配置key时工具不出现在模型目录，界面/文档说明“联网未配置”；查询失败不自动换搜索商。凭据仅在Java本地忽略文件或既有安全配置渠道注入；建议环境名SPEC_AGENT_TAVILY_API_KEY。密钥、原始header和完整供应商响应不进入checkpoint/事件/前端。

两项同时可用时完整目录最多12项（已有10+2）。现有Java目录limit(8)、projection≤12、binding≤12和Python/contracts限额必须一致调整；不能让新增工具截断已有工具或靠顺序选择。配置缺失时不是硬凑12项。

外部搜索查询只发送任务必要的文字，不自动拼接项目历史、内部资源或密钥。网页内容是EXTERNAL_EVIDENCE，提示词注入不能扩权；不自动创建Graph事实、安装Skill或写入共享索引。

## 9. 网页来源与过程展示

复用TOOL_STARTED/COMPLETED/FAILED和按run/toolCallId归属；增加通用WEB_SOURCE结果形态，不按正文猜工具状态。来源含宿主分配的sourceId、URL、标题、片段、fetchedAt与内容阶段SEARCH_SNIPPET/EXTRACTED_TEXT。

搜索结果展示链接与摘要；读取后显示所读页面及正文摘要。回答引用本轮真实宿主返回的网页sourceId，前端只解析可核验引用；没有读取就不能声称看过全文。来源数据跨刷新按原run保留；不要把外部URL塞成项目Node/UUID，不把网页引用当项目事实引用。

链接只允许http/https并使用安全的新窗口属性。重复URL可展示去重，但来源/调用身份仍可追溯。失败、取消与无结果各自呈现，阶段步骤来自事件，不编造“正在思考”。

## 10. 执行顺序与学习项目验收

1. 读取仓库必读文档，盘点旧GA引用，列出实际删除清单与项目侧保持不动的证据。
2. 提取共享DTO/必要校验，调整新run创建/schema/初始化，Dispatcher单路径，删除旧GA独占代码和旧配置分支。
3. 修复日常启动及GA工具readiness，证明新run确实使用Python/create_agent，项目配置和Runtime不变。
4. 接入Tavily两工具、凭据配置与typed网页来源，完成前端可用展示。
5. 执行针对性的默认启动、原生工具、历史兼容、取消/失败/重连/幂等回归；新增web参数/凭据缺失/provider失败/来源/恶意URL边界验证。运行受影响Python/Java/前端检查，删除范围大时完成必要完整回归，不重复不受影响的真实长摘要/52查询等专项。
6. 有可用Tavily凭据时做真实中文搜索→网页提取→带引用回答。没有凭据则完成其余开发与模拟验证并留下这一项真实联调缺口，不使用假的搜索结果宣称接入通过。
7. 更新简明说明及本轮实施记录，不扩展人工评测/生产批准门禁，不触碰被拒绝删除的临时目录。不在设计会话提交或推送；开发会话按届时用户授权处理Git交付。

完成标准：GA旧可执行循环已删、默认单路径可用、两网页工具有真实或明确未配置状态、来源与错误可见、历史可读、项目Agent未发生行为变化。仅保留必要历史数据兼容不叫保留旧引擎；新框架的宿主适配不叫自写第二个Agent。

## 11. 新会话提示词

```text
请实施 docs/v2/GLOBAL_ASSISTANT_SINGLE_ENGINE_AND_WEB_TOOLS_PLAN.md。
先读取仓库必读文档、当前实施记录，再核对实际代码，不重做已完成改造。

我明确授权：仅全局助手删除旧Java决策引擎，唯一使用Python create_agent；
修复默认启动、新run engine_version和历史兼容，增加Tavily web.search/web.fetch及网页来源展示。
保留Java宿主、权限、模型broker、工具执行、checkpoint/事件存储；项目Agent暂时不能动。
不修改项目retrieval默认、Runtime、Brain、Graph、冻结输入，不删除项目旧代码或测试。
不要因为GA检索的双开关依赖而顺手切换项目RAG。

参考学习项目的TavilySearch、middleware与工具展示，开发时核对当前官方接口。
框架负责Agent循环；宿主API与业务工具仍按已有边界适配，不增加第二个循环或无用SDK代理。
模型和embedding保持现有选择，不强制迁移实验性流式接口或增加新Agent框架。

这是学习项目，不增加生产发布/人工标签/临时目录清理门禁。
自主持续完成上述范围、必要验证与文档更新，阶段之间只发进度，不反复等我说继续。
缺Tavily凭据时完成所有独立工作，再一次性说明真实联调缺口，不能伪造通过。
本提示授权本地开发与启动验证，不自动提交推送；最后报告删除/保留范围、工具、测试和遗留。
```

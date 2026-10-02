# 全局助手单引擎与网页工具实施记录

2026-10-02。实施范围来自 GLOBAL_ASSISTANT_SINGLE_ENGINE_AND_WEB_TOOLS_PLAN.md，仅全局助手。早期实施记录中的“双引擎/显式 opt-in/生产切换与人工评测待批准”不再描述本轮全局助手的启动状态，也不作为本学习项目新增门禁。

## 删除与保留

删除15个全局助手独占Java实现文件及18个旧引擎测试文件：Java Runtime/Brain、ContextBuilder/Context、SummaryService、旧 Decision 解析/校验/schema/semantics、PromptRenderer、流解码器、独占配置和无调用方的参数 canonicalizer，连同只验证旧循环的测试。供应商兼容性探测改为 provider 自有的最小 FINAL JSON 校验，不再依赖已删的 GA 决策类。UI DTO 提取至 GaHostContext，仍由 Java 校验选中的真实项目。

保留 Java run 生命周期、权限/Capability Runtime、内部模型 broker、原生工具传输、事务外 prepare、事务内 fence/提交、checkpoint、事件、取消/steer/恢复与存储。项目 Agent 的 Runtime、Brain、Graph、冻结输入及检索默认 java-hybrid.v1、Python 项目协议与旧代码/测试均不修改。聊天模型与 embedding 不变，依赖锁不升级。

V52 只改新 run 的 engine_version 默认；Java INSERT 显式写 langchain-ga.v1，initialize 拒绝历史旧版本。旧完成消息/事件保持可读，旧活跃 run 走已有中断恢复，不重新执行旧引擎。Dispatcher 直接使用 GaExecutionCoordinator；旧引擎环境配置报错，无 Java fallback。

## 日常启动

start-dev.bat 先按既有逻辑生成共享 internal secret，再调用 scripts/start-ga-brain.ps1 启动独立 GA 服务，默认 8101。项目 Brain 仍使用原环境与端口 8100。GA 使用独立 .venv-ga 或 SPEC_AGENT_GA_PYTHON，首次缺环境时用 uv 创建已资格 CPython 3.11.14 并按现有 requirements.lock 安装；不覆盖崩溃的原项目环境。

可覆盖 SPEC_AGENT_GA_BRAIN_PORT、SPEC_AGENT_GA_BRAIN_BASE_URL；启动失败明确退出，不退回旧循环。启动器只重启当前 checkout 已核验的 GA 进程，不终止占用端口的未知进程。独立调用脚本需已有共享密钥文件或传 -SecretFile；主启动器负责创建文件。

Tavily 凭据由 Java 的 SPEC_AGENT_TAVILY_API_KEY 注入；Python 启动和集成测试子进程显式移除此环境变量。没有 key 时，两工具不进模型目录，界面显示“联网未配置”。工具状态端点 GET /api/v1/global-assistant/tools 不返回凭据。

GA 独立读取检索 health 并有界准备 HELP 索引；需现有 Ollama 0.6B 和共享 store 可用才开放 help.search/project.content.discover。只处理 HELP，不为全局助手偷偷切换或建立项目索引；现有项目索引缺失返回 PROJECT_INDEX_NOT_READY。项目侧默认不变。

## 工具与网页来源

最多 12 项：project.create/search/list_recent/get_summary、skill.import.discover/import、help.search、project.content.discover、ui.navigate、user-input.request、web.search、web.fetch。检索/网页能力按真实 readiness/配置过滤；不靠目录顺序截断已有能力。

web.search 参数 query、可选 limit(1–5)、topic(general/news)、timeRange(day/week/month/year)，Java 直接调用 Tavily Search，basic、禁 answer/全文/图片。web.fetch 参数单 url、可选 query，Java 调用 Tavily Extract/basic/text。搜索总摘要 8000 字符，提取正文 12000 字符，响应 1 MiB，单次最多20秒，无自动重试，网络等待不持有 run 事务锁。

公开 HTTP(S) URL 输入及供应商 canonical URL 都校验，拒绝凭据、私网/回环/本地地址和不支持协议。网页为 EXTERNAL_EVIDENCE，不写 Graph/共享索引，不把页面指令当权限。供应商失败、限流、认证、超时、提取失败均有明确错误；空搜索是独立的成功观察。

TOOL_* 沿用 runId/toolCallId；宿主分配 sourceId，持久化 WEB_SOURCE 卡片包含 URL/标题/摘要/fetchedAt/截断与 SEARCH_SNIPPET 或 EXTRACTED_TEXT。前端按轮次仅解析该轮成功事件里的 [web:sourceId]，未知引用显示未核验；新窗口链接使用 noopener noreferrer。刷新/历史使用持久事件，不凭模型正文猜工具成功。

## 学习参考与接口

核对学习项目第2章 2.5_tools.ipynb 和 2.7 实战：普通 ChatOpenAI 与 create_agent(tools=[TavilySearch(...)])，当时配置 longcat-2.5-preview-free。搜索执行由 Tavily 提供，聊天模型负责工具选择；本实现沿用现有 Mimo，通过 Java broker 的原生 tool calls 接标准 create_agent，不需专用搜索模型或更换 embedding。

当前接口按官方核对：[LangChain Agents](https://docs.langchain.com/oss/python/langchain/agents)、[Tavily Search](https://docs.tavily.com/documentation/api-reference/endpoint/search)、[Tavily Extract](https://docs.tavily.com/documentation/api-reference/endpoint/extract)。没有引入额外 SDK 或实验性接口。

## 验证

- Java testNonLive 全量 1586 项：1567 通过、19 跳过、0失败（显式启用跨语言测试，使用已资格 CPython 3.11.14）；包含项目原有测试、ArchUnit零循环、默认引擎协调器、checkpoint/取消/故障与历史兼容。
- 完整12项工具目录与缺配置/未就绪过滤：新增2项专项测试通过。
- Python pytest：221 通过。前端：863通过、1跳过；vue-tsc 与 Vite 构建通过（现有大 bundle 提示）。
- 启动脚本默认8101实际启动及当前checkout进程重启均通过。先验证 SPEC_AGENT_GA_PYTHON 指向已资格环境，再移除覆盖，实际走 uv 下载 CPython3.11.14、按原锁创建 .venv-ga 的默认首次启动路径并成功 ready；使用独立测试密钥文件。没有声称旧项目 .venv 崩溃环境已修复，也未替换它；此前未执行整套窗口流程，现已在本轮收尾中实际执行并验证，见下节。默认GA引擎由无 engine 覆盖的 Spring 上下文及实际Python新run测试验证。
- Python缺失：新run明确 GA_PYTHON_UNAVAILABLE，模型未调用，无Java fallback；历史完成run保持 java-legacy.v1，不执行旧循环。
- 真实联调：OpenCode Zen 当前配置 mimo-v2.6-flash-free → Python create_agent → Java Tavily Search → Extract → 中文带引用回答，web.search/web.fetch均有真实成功事件与EXTRACTED_TEXT。证据：[GLOBAL_ASSISTANT_WEB_INTEGRATION.json](evidence/GLOBAL_ASSISTANT_WEB_INTEGRATION.json)。真实网络凭据仅从学习项目本地配置传入联调Java进程，不写入仓库。
- Edge Playwright：将上述真实持久事件/回答通过受控API重放给真实Vue页面，搜索/提取阶段、来源卡片、安全引用、刷新恢复验证通过；这是前端真实浏览器回放，不冒充浏览器再次联网。截图本地 evidence/GLOBAL_ASSISTANT_WEB_BROWSER.png（忽略文件）。此前独立启动验证进程已停止；收尾的完整日常栈当前保持运行，新建的GA独立环境保留供日常启动使用。

## 遗留与使用条件

日常Java进程仍需设置自己的 SPEC_AGENT_TAVILY_API_KEY，本轮未把学习项目凭据持久化到产品配置。GA帮助/项目内容工具依赖已有Ollama与索引readiness，项目索引未准备时明确失败；没有顺手迁移项目检索或修复项目Python环境。本轮未新增发布、人工评测或临时目录清理门禁，本轮收尾已获用户授权按仓库 main↔origin/main 规则提交并推送。


## 完整日常启动收尾（2026-10-02）

实际运行 `start-dev.bat` 并完成重启：前端 localhost:5173、Java 127.0.0.1:8080、项目Brain 8100、GA Brain 8101全部可访问；前端代理与内部retrieval-store认证通过。项目进程仍用 `agent-brain/.venv`（3.11.15），GA用独立 `.venv-ga`（3.11.14）；项目解释器与pyvenv.cfg校验值和运行时/Graph/Brain/检索默认均未改变。本次原项目环境成功返回ready，没有将历史崩溃记录等同于本次失败。

修复实际启动发现的两类问题：cmd的非引号set赋值把尾随空格带进共享令牌/地址，导致认证失败；IPv4专用netstat与cmd捕获Java长命令行漏掉旧实例，导致端口漂移。现为带引号set、IPv4/IPv6端口检查、PowerShell直接核验当前checkout进程及其venv父进程；未知进程保留。前端strictPort避免悄悄换端口。没有修改项目Agent协议或环境。

通过默认真实前端代理创建只读验证run：OpenCode Zen `mimo-v2.6-flash-free`调用project.list_recent成功，数据库确认engine_version=langchain-ga.v1、COMPLETED与完成checkpoint。证据：[日常启动记录](evidence/GLOBAL_ASSISTANT_DAILY_STARTUP.json)。没有使用测试fake模型替代这次日常启动验证。

新增加检索状态：DISABLED/PYTHON_UNAVAILABLE/STORE_UNAVAILABLE/OLLAMA_UNAVAILABLE/HELP_INDEX_NOT_READY/RETRIEVAL_UNAVAILABLE/READY。健康探针通过后还要求当前HELP索引至少存在同代同profile的READY entry；索引尚未准备不宣称可用。刷新不先将既有READY置false，避免健康探测过程中临时撤销正在调用的工具。面板每15秒刷新状态，项目索引缺失仍返回PROJECT_INDEX_NOT_READY。

本轮只运行受影响检查：readiness与12项目录专项、ArchUnit；展示文案19项与typecheck；Edge实际日常栈/缺Tavily提示1项；受控模拟Ollama、存储、帮助索引未就绪提示3项。未重跑此前未受修改影响的全量测试，也没有停止真实Ollama来伪造联调。

### 配置方法

1. PostgreSQL仍使用现有Docker/5434配置。首次日常启动由start-dev.bat生成root `data/internal-secret.txt`，显式传同一个令牌给Java与两个Python进程；单独启动Java时需传相同SPEC_AGENT_BRAIN_INTERNAL_SECRET。root令牌、backend/data、venv和构建日志均不得提交；不要打印令牌。
2. 在启动终端给Java设置环境变量，再启动：

```powershell
$env:SPEC_AGENT_TAVILY_API_KEY = '<你的本地Tavily API key>'
.\start-dev.bat
```

这里只是占位示例，真实值不得写入文档/Git。配置变更后重启Java；启动器清除Python子进程的Tavily变量。没有配置时web.search/web.fetch不在模型目录，页面显示“联网未配置”。本轮真实Tavily凭据仅用于前一阶段Java临时联调，未保存到产品配置，日常栈当前webConfigured=false。

3. 启动现有Ollama服务（桌面应用，或独立终端`ollama serve`），准备现有模型：

```powershell
ollama pull qwen3-embedding:0.6b
ollama list
```

模型须符合既有固定profile/digest和1024维约束；保持现有embedding选择。GA自动每15秒有界准备HELP；项目内容检索只使用已有项目索引，未准备时提示明确失败，不自动切换项目检索默认或建立项目索引。
4. 用`http://localhost:5173/api/v1/global-assistant/tools`检查engineVersion、webConfigured、retrievalReady/retrievalStatus及实际工具目录；该端点不返回密钥。Ollama停止/模型不符合profile、存储认证失败、索引未准备分别有中文提示。若只需应用项目工具，未配置网页/检索时仍可独立使用。
5. 默认端口8080/5173/8100/8101；可设置SPEC_AGENT_BACKEND_PORT、SPEC_AGENT_FRONTEND_PORT、SPEC_AGENT_BRAIN_PORT、SPEC_AGENT_GA_BRAIN_PORT。GA独立解释器可由SPEC_AGENT_GA_PYTHON覆盖；项目环境不随GA覆盖而改变。

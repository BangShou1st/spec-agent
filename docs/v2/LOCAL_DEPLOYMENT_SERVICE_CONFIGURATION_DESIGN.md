# 面向本地部署的服务配置设计

> 日期：2026-10-02。已按本文实现搜索与 embedding 设置、宿主 broker 与 HELP 索引代际，以及第 10–11 节前端和截图验收。实测范围及真实云端联调缺口见 [实施与验收记录](LOCAL_DEPLOYMENT_SERVICE_CONFIGURATION_IMPLEMENTATION_STATUS.md)，使用方法见 [本地配置说明](LOCAL_DEPLOYMENT_SERVICE_CONFIGURATION_GUIDE.md)。
> 学习项目范围：先完善可用配置，不增加生产审批或大规模平台建设。沿用Java宿主/Python编排边界，项目Agent业务Runtime暂时不变。

## 1. 两层配置

| 层级 | 内容 | 配置位置 |
|---|---|---|
| 启动基础 | PostgreSQL地址/用户/密码、服务端口、Java与Python连接、内部认证、加密主密钥及数据目录 | 环境变量/本地文件/启动参数；示例不含真实凭据 |
| 产品服务 | 聊天提供商及模型、Tavily、embedding提供商/地址/模型/凭据/索引状态 | 设置页面，经Java API加密或普通字段持久化数据库 |

不能把连接数据库和解密数据库所需的配置也仅存在数据库。内部认证密钥不与搜索/模型API key混为一项。不得在前端使用VITE_*保存服务商密钥。

用户服务配置以数据库为权威。环境变量可作为安装时显式导入来源，页面显示来源；保存/清除后不自动从残留环境变量重新复活。现有Tavily环境配置需要兼容到首次保存，后续用DB状态明确选择。启动文件不自动读取开发者教程目录或个人Windows环境。

## 2. 设置界面

保留已有模型、Skills、Connections，新增“联网搜索”和“知识检索”。不把搜索服务混在聊天模型下，也不把所有服务混入MCP Connections。

联网搜索首期只有Tavily：启用开关、API key输入、已保存/未配置状态、测试连接、保存/替换/清除。默认隐藏输入，GET不返回明文；测试连接显式发起并提示可能消耗额度。保存与测试互不隐式触发。

知识检索分为embedding服务与语料索引：

| 字段 | Ollama | OpenAI-compatible |
|---|---|---|
| 服务类型 | Ollama | OpenAI-compatible embeddings API |
| 地址 | 用户部署的Ollama origin；提供本机/容器连接说明 | 用户服务base URL，普通远程默认HTTPS |
| 模型 | 可列出已安装模型/手动填写；不自动下载 | 手动填写，若提供商支持列表再辅助选择 |
| API key | 本机通常不需要；远程网关认证单独处理，不伪装成官方原生能力 | 必填或由已验证的本地兼容服务能力决定 |
| 输出维度 | 检测实际返回；只对支持的模型发送指定维度 | 检测实际返回；只对支持dimensions参数的模型发送该参数 |
| 超时/批量 | 提供合理默认，展开高级设置 | 同左，不能从模型猜服务限额 |
| 查询/文档编码策略 | 模型适配决定，例如Qwen查询指令 | 根据具体模型能力选择，不能都套Qwen指令 |

操作顺序：填写 → 显式测试服务 → 保存候选配置 → 查看需重建的语料 → 显式重建 → 完成后激活。页面显示“已保存”“已验证”“索引准备中”“已生效”不同状态。

已验证的现有Ollama0.6B/1024作为默认建议和旧profile保留，不要求别人安装同一模型才可打开应用。缺embedding时基本项目管理与聊天仍可用，受影响检索明确提示；缺Tavily仅关闭联网工具。

## 3. 已核对的当前限制

- Python retrieval/contracts.py：provider、modelTag、dimensions及查询策略是Literal固定值。
- embedding.py：fixed_profile、固定digest、SDK调用dimensions=1024、仅本地Ollama origin。
- Java RetrievalWire/Store/Jobs：PROFILE常量；RetrievalVectors/Vector.validate固定1024。
- TavilyWebService：构造时从环境读入final key，不能在页面保存后立即动态更新。
- retrieval_entries及pending_embedding数据库列是无固定维度VECTOR；已有dimensions/profile/generation元数据可复用，但候选查询、校验和索引策略仍需调整。

因此这是一项设置+服务适配+profile与索引的改造，不是只新增表单。

## 4. Provider边界

Python继续使用LangChain Embeddings接口，提供统一embed_query/embed_documents；RAG只依赖接口，不依赖某个模型字符串。

本地Ollama适配沿用langchain-ollama。官方接口允许模型与地址配置；输出维度是否可选仍取决于模型和锁定包版本。[官方Ollama集成](https://docs.langchain.com/oss/python/integrations/embeddings/ollama)

远程首期支持文本输入的OpenAI-compatible `/embeddings`，不承诺所有“兼容服务”完全一致。先验证字段、批量顺序、encoding_format、dimensions和错误行为。聊天API可用不证明embeddingAPI可用；聊天与embedding可以使用不同供应商和key。[官方OpenAIEmbeddings集成](https://docs.langchain.com/oss/python/integrations/embeddings/openai)

为保持密钥不进入Python的既有边界：Java持有远程凭据并提供受认证embedding broker，Python用LangChain Embeddings适配调用它。该适配是真实统一接口，不伪称直接使用了langchain-openai客户端。首期不为名义使用SDK构建供应商协议代理。

broker依据授权workload与配置revision选取固定模型，不接受调用者随意指定key或外部URL；索引任务和查询必须绑定同一profile。显式测试配置使用隔离测试输入，不获得工作区内容读取授权。错误分别标识未配置、认证失败、限流、超时、维度不符，不静默换模型或服务。

远程embedding会发送文本到配置的供应商，页面明确说明；不会把所有历史或未授权来源自动发送。支持自建Ollama地址时，必须由安装者明确配置可信origin，不能继续以固定localhost白名单假称可配置。模型和网页内容不能修改这些地址。

## 5. 存储与热更新

首期建议独立search_settings、embedding_service_settings及不可变embedding_profiles；复用现有ModelCredentialCrypto的AES-GCM和本地master-key机制，不再实现一套加密。不把这些记录写入聊天模型表。

GET仅返回configured、enabled、maskedKey、普通配置、revision和测试/激活状态。PUT省略key表示保留，换key明确提交；清除使用明确操作，不能把掩码当真实key保存。密钥不出现在错误响应、日志、checkpoint、模型上下文或证据。

Tavily下一新run读取最新配置；当前run绑定配置revision，配置撤销时重新校验，不能扩权。embedding任务绑定profile与服务revision，保存后不立刻改变已有活跃generation。供应商密钥只是连接信息，不进入向量语义hash；换key通常不需重建。

## 6. 模型、维度与索引一致性

profile记录provider/model、实际维度、查询/文档预处理、normalization、splitter及索引schema版本。Ollama记录digest；远程服务没有可靠digest时记录模型声明/版本及语义服务身份，明确其限制，不制造假digest。

同维度不等于同向量空间。模型、影响语义的服务身份、预处理或模型版本变化均需新profile及对应索引重建；单纯超时、batch或密钥更改不必重建。

候选generation准备完后按来源版本/profile/head CAS激活；失败保留旧索引或明确可用的词法状态。不先覆盖旧向量再尝试重建，不把新query向量与旧文档向量计算相似度。首期复用同一pgvector存储，不增Chroma或第二套RAG。

向量校验使用宿主批准profile里的dimensions，不相信Python自报维度。任何相似度SQL先限制profile/generation/dimensions，确保不同维度不进入同一距离运算。无固定维度VECTOR不代表所有维度可共用一个ANN索引；实际pgvector版本、索引类型和精度决定上限。[pgvector官方说明](https://github.com/pgvector/pgvector)

首期可声明一个经过测试的维度支持范围，超出时明确拒绝或选择已验证策略；不能在UI接受任意数字、自动截断向量或假称所有模型可降维。保留原固定v1 profile作为兼容项；新增动态profile需要显式版本化契约，不以放宽Literal代替宿主注册与验证。

## 7. 项目Agent保护

这是共享检索配置，必须展示实际影响范围。新配置首先作为候选，不切换项目Agent检索引擎、不重写其冻结输入，也不自动重建所有项目。

GA帮助语料可独立重建激活；现有项目内容查询按其授权corpus/generation选择对应profile。若项目侧未来采用新profile，需显式按语料迁移；业务Runtime不变，历史冻结输入仍不重算。共享profile泛化不可破坏旧项目路径，不删除其原校验与测试来省事。

## 8. 本地部署交付

- README写清Java/Node/Python/PostgreSQL及pgvector前提、启动命令、端口与首次设置步骤。
- 提供可复制的.env.example或启动配置示例，只放基础变量与占位符；Spring不会仅因为文件叫.env就自动加载，必须说明具体加载方式。
- Docker场景说明容器里的localhost与宿主不同，Ollama/服务地址不硬编码个人机器路径。
- 首次启动显示聊天/联网/embedding/索引各自状态，不把可选服务缺失统称系统故障。
- 实际密钥、数据库数据、master-key、内部secret及个人路径不提交；备份恢复时保留数据库与对应master-key。
- 不读取学习项目的.env，也不自动复制本机用户环境变量到其他人的部署配置。

## 9. 建议实施顺序

1. 先完成Tavily设置页、加密DB存储与动态读取，替代日常环境变量依赖。
2. 增加embedding设置页、服务探测及候选profile注册，保留旧默认功能。
3. 实现Ollama泛化与远程embedding broker/适配、动态维度契约和有界任务；完成索引重建/激活UI。
4. 补部署文档与基础配置示例，按空白本地安装流程验证。

验证重点是保存/清除/重启持久化、密钥不回传、API与Ollama真实连通、维度与批量顺序、模型切换不混向量、失败可恢复、项目旧路径及冻结历史不变。无需以人工68查询或旧临时目录清理作为设置功能开发门禁。

## 10. 前端实施设计：与现有设置页一致

用户已明确要求新会话一次性完成本设计范围，页面风格与其他配置页一致，并由开发模型实际截图检查。下面是确定的交互与验收要求，不需要再次询问视觉方向。

### 10.1 页面与组件

- 路由采用 `/settings/search`（联网搜索）和 `/settings/retrieval`（知识检索），放在现有SettingsLayout导航中。现有模型/Skills/Connections路由与内容保留。
- 视觉基准为当前 `/settings/models`、ModelSettingsView.vue、ProviderCard.vue及providerSettings.css，实施前在浏览器截图留存基准。选择操作型设置页，不重新设计品牌。
- 复用settings-page、settings-card、settings-current-config、settings-field、settings-control、settings-card__footer及全站颜色/字体/控件高度/focus-ring。已有容器最大宽度880px、标题层级、卡片12px圆角、按钮节奏沿用现状；不要另建字体、渐变banner或dashboard样式。
- 复用ProviderCard中可泛化的结构、ApiErrorBanner及状态胶囊；若其状态枚举仅适合聊天Provider，抽取通用ServiceSettingsCard/状态类型，不让搜索卡错误显示“当前聊天模型”。通用抽取应保持原模型页视觉与行为。
- 标题上、简短用途说明下，主要操作放卡片页脚。API key属于普通凭据字段，默认密码输入；已保存后显示脱敏摘要和“更换密钥”，不在加载时填回明文。

### 10.2 联网搜索页

一张Tavily配置卡：顶部服务名与状态；配置摘要包含启用情况、凭据来源、最近显式验证状态；正文包含启用开关与凭据编辑；页脚提供“保存”“测试连接”“清除配置”（危险样式次要动作，显式确认）。

测试可基于未保存输入或已存配置，但必须明确目标，不能借测试偷偷保存。保存不自动收费调用；页面提示“测试会调用搜索服务，可能消耗额度”。验证成功仅说明该配置在那个时刻可用，不承诺剩余额度或长期可用。配置变更使旧验证结果过期。

环境来源时显示“当前来自启动环境”，提供显式“导入并保存”到DB；清除/停用后不会从相同环境值自动复活。无配置空态直接显示表单和说明，不出现虚假的连接成功。

### 10.3 知识检索页

两张纵向卡片：

1. **Embedding服务**：类型选择Ollama/OpenAI-compatible，按类型显示地址、模型、API key；实际维度只读显示测试结果，模型确认支持时才出现高级“指定输出维度”。高级设置默认收起，含超时、batch等。页脚“测试连接”“保存候选配置”。
2. **索引状态**：按语料显示当前生效配置与候选配置、准备状态和可核验进度；提供“重建所选索引”“重试失败任务”以及成功后“启用新索引”。选定语料和影响范围必须可见，不默认操作所有项目。

首次使用提示按服务选择给出：Ollama需自行安装/启动/下载模型；API模式需服务地址和key。不自动下载大模型或将模型名错误当成连接错误。

用产品语言表达“当前使用”“新配置待建立索引”“索引准备中”“重建失败，仍使用原索引”，不要把profileId、grant、lease、CAS等内部字段作为主界面信息。详细技术标识只可放在必要的折叠诊断中。

索引统计来自后端任务状态，不能按计时器编造百分比；后端没有总量时显示实际已处理数量。关闭页面后任务仍由宿主托管，再进入读取规范状态。保存候选配置不导致当前查询突然改用新模型。

### 10.4 响应式与交互

新增导航较长，在窄屏允许换行或明确的水平滚动；页面整体不得横向溢出。摘要桌面两列、窄屏单列；按钮换行且不互相遮挡；长URL/模型名不撑破卡片，完整值仍可查看。

所有输入有label、错误与字段绑定、键盘可操作；focus、disabled、loading样式沿用现有组件。保存/测试期间阻止重复提交，相关动作独立显示忙碌状态。状态更新不把用户滚动位置强制拉回页顶。

## 11. 浏览器操作与截图验收

开发会话必须启动真实Vue页面并实际截图，再使用可用图像查看工具逐张检查。组件测试、DOM文本、构建通过或“截图文件存在”都不能代替视觉检查。截图及浏览器轨迹放本地scratch或现有被忽略的证据目录，不提交真实凭据或截图中的敏感信息。

### 11.1 截图覆盖

| 视图/状态 | 核对内容 |
|---|---|
| 现有模型页基准 + 两个新页桌面视图（约1440×900） | 设置外壳、宽度、标题、卡片、输入、按钮、状态与错误层级一致 |
| 两个新页窄屏（约390×844） | 导航可达、无横向溢出、摘要和操作换行、长地址不撑破 |
| Tavily未配置、已存脱敏、测试中/失败、停用或清除后 | 状态真实、错误可读、密钥不显示、环境配置不自动复活 |
| Ollama配置、API配置、测试成功维度与错误状态 | 字段随类型切换、实际维度明确、输入保留/清除正确、无假连接成功 |
| 索引候选/准备中/失败/完成并激活 | 当前与候选区分、来源真实、失败不丢旧索引、按钮符合状态 |

可以批量采集这些状态后一次检查，集中修正发现的问题，再用一轮截图确认。功能失败正常修复；避免无目标地反复打磨样式。

真实成功路径至少使用现有可用的Tavily与Ollama；不在截图中显示真实key。缺远程embedding凭据时，API表单和故障可用标明测试夹具的浏览器场景验证，但不能把mock成功标为真实云服务通过。

### 11.2 必须实际操作

完成页面输入/保存/重新加载、替换/清除、停用、服务故障、恢复、重复点击与键盘操作。验证DB加密存储、GET不返回key、服务重启后设置仍在；GA工具可用状态正确刷新，原模型页及项目Agent行为保持。

真实执行一次Ollama文档与查询embedding、所选HELP索引准备和切换；原generation/query/frozen历史保持对应版本。API模式用确定性边界测试验证数组顺序、错误和动态维度，若有用户已有凭据再做真实联调。

截图报告列出路径、页面/视口/状态、是否真实服务、模型看到的问题及修正结果。没有实际查看截图就写“未视觉验收”，不得提前宣布全部完成；浏览器工具不可用时报告具体限制并完成其他独立工作。

## 12. 新会话完整实施提示词

```text
请一次性完成 docs/v2/LOCAL_DEPLOYMENT_SERVICE_CONFIGURATION_DESIGN.md，
包括第10节前端设计和第11节浏览器截图验收。

先读取仓库必读文档、现有模型配置/凭据加密代码及最新实施记录，
核对实际代码。使用适合的界面技能，保持已有设置页视觉体系，
不要重新询问已经确定的样式方向。

本次授权范围：
1. Tavily设置页面、Java API、数据库加密持久化、热更新、环境配置显式导入。
2. Embedding设置页面，支持Ollama与OpenAI-compatible embeddings API，
   包括地址/模型/API key、真实测试、维度检测、候选配置与高级参数。
3. Python标准Embeddings适配、Java远程embedding broker、宿主批准profile、
   动态维度校验、对应语料索引重建与安全激活；不得混用不同模型向量。
4. 首次部署状态、基础配置示例、启动说明与空白安装验证。
5. 必要后端/Python/前端验证及真实浏览器操作、截图、逐张图像检查。

页面新增 /settings/search 和 /settings/retrieval。
复用 SettingsLayout、ProviderCard可泛化结构、providerSettings.css、
ApiErrorBanner和现有设计token。外观与模型/Skills/Connections一致，
不另造渐变大卡片、字体或页面风格。覆盖桌面及窄屏和关键状态。

Java保管供应商凭据、权限与存储；Python负责Agent和检索算法。
不改项目Agent的Runtime、Brain、检索默认或冻结历史，
不自动重建所有项目。GA帮助语料可以显式重建；共享模块扩展保留旧路径。
聊天模型保持现状，现有Ollama配置保留为兼容profile，
不强制所有部署者使用它，不增加第二套索引。

复用现有加密机制，API不回传明文，省略key表示保留，清除操作明确。
主密钥/数据库连接仍是基础启动配置。真实key不进日志、截图或Git。
已授权使用本机现有Tavily配置和Ollama做小范围真实验证；
不要读取教程凭据或改其他服务配置。缺远程embedding凭据时
完成API模式实现、确定性验证及浏览器表单验收，单列真实联调缺口。

必须实际打开页面截图，并使用图像查看工具检查截图。
留存现有模型页基准、两个新页桌面/窄屏及成功/错误/索引状态证据，
报告截图位置和发现/修正内容，不把DOM断言或构建当截图验收。

这是学习项目，不新增生产审批、人工68查询或临时目录清理门禁。
持续完成上述全部范围，中间发进度，不每做完一项就等我说继续。
遇到阻塞先完成不依赖它的工作，最后一次性报告证据与真实缺口。
不自动提交推送，最后交付完整改动、验证结果、截图和使用说明。
```

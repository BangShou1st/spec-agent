# 全局助手 LangChain 改造实施记录

**本次交付口径（2026-10-02）**：个人学习项目，发布指提交并推送远程仓库。下文历史生产验收要求不作为本次提交门槛；原生崩溃、人工评测与旧目录清理作为遗留保留。简明功能、测试及已知问题见 [本轮提交说明](GLOBAL_ASSISTANT_CHANGE_SUMMARY.md)。不新增验收流程，不执行生产部署或引擎切换。

日期：2026-10-02（Asia/Shanghai）。分支：main。以下是当前权威状态；下方按时间保留的旧记录只描述当时进度，旧“尚待”不覆盖本节。没有重开阶段 0，没有更换模型，没有切换生产引擎。

## 发布前收尾增量（2026-10-02）

本轮没有新增产品功能、切换生产或替换原环境。当前资格清单见 [发布前收尾与待办](GLOBAL_ASSISTANT_PRE_RELEASE_CLOSEOUT.md)。原回归/真实模型/UI证据保留，不重跑未受影响的测试。

- 候选Windows CPython3.11.14锁定standalone20260211构建、关键二进制及相同依赖锁，准备脚本不会修改原.venv。6次独立Python服务启动、约65秒持续服务/12次真实Java RPC+pgvector+Ollama检索通过，0失败自动重启/重试。首次Ollama未启动的专项失败单独保留；不解释为原生崩溃。
- 原3.11.15的0xC0000005与栈是已证实的现象，具体根因未确定。候选本机有限验证通过，部署环境资格和长期稳定性仍待确认。
- 52条查询明确标记DEVELOPMENT_CALIBRATION，参加过阈值校准，永不冒充独立验收。新68条INDEPENDENT_ACCEPTANCE_CANDIDATE未运行、未调参；冻结profile/距离/RRF/预算/scope/实现及数据hash，包含多来源与相近无答案。AI静态预检查通过，所有人工标签仍待审；拒绝待审数据执行的门禁专项通过。
- 原Git目录拒绝记录保留，本轮未尝试清理或绕过。实施/集成/人工审阅/环境确认/生产授权分别列示，整体发布资格仍未关闭。

## 当前验收结论

阶段 1 的功能链路、真实模型正文流、真实 Git HTTPS、真实长摘要、实际浏览器与独立 Java 进程恢复均已取得集成证据。**阶段 1 的整体验收仍未关闭：旧失败测试留下的一个受控临时目录删除被自动审批拒绝。** 新测试全部临时目录清理通过，不能代替这项收尾。

统一 RAG U1–U3 功能已实现并完成实际 Python/Ollama/宿主 RPC/既有 pgvector 集成。**效果验收尚未完成：52条仅为校准开发集；新的68条独立候选由AI编写，标签尚未人工审阅，不能称为至少50条人工标注验收通过。** 查询、标签、逐条结果和人工审阅入口已交付；等待实际人工审阅后复验。

所有生产验收均为 **NOT_RUN**。生产默认仍 `java-legacy.v1` / `java-hybrid.v1`，测试显式选 `langchain-ga.v1` / `python-rag.v1`。发布操作及回滚限制见 [发布准备与回滚手册](GLOBAL_ASSISTANT_LANGCHAIN_RELEASE_RUNBOOK.md)。生产切换未获本次授权，也未执行。4B、额外反思模型、Deep Agents、完整 GraphRAG 均未引入。

## 阶段 1 当前清单

| 功能/能力 | 实现、组件或模拟验证 | 真实集成证据与范围 | 生产验收 |
|---|---|---|---|
| 原生模型与逐 token 正文 | typed stream；工具参数独立聚合，候选草稿撤回；重复/截断/多调用校验 | 未变 mimo-v2.6-flash-free，经既有 Zen 请求头、Java broker、Python create_agent 到产品正文；见 PRODUCT_DISPATCH、NATIVE_SSE 证据 | NOT_RUN |
| 模型批量调用 | 最多 5 项，只有冻结的 readOnly/NONE 业务能力可批量；写入/导航/澄清批量先拒绝再派发 | 模型实际忽略 parallel_tool_calls=false，独立 index 参数缓冲已修复；完整 10 工具目录下 help.search 和 project.content.discover 实际完成 | NOT_RUN |
| project.list_recent/search/get_summary | 权限、来源、预算、撤销、缺失源、原生 ledger 重放通过 | 真实模型查询和概要→实际导航；来源变更最终重新核验 | NOT_RUN |
| project.create | 既有 LOCAL_DURABLE；事务、相同/新 callId 幂等、异常回滚、UNKNOWN 禁重试、权限通过 | 实际模型+隔离库仅创建一个项目，重复 dispatcher 不增加副作用 | NOT_RUN |
| skill.import.discover / skill.import | 网络准备事务外；同一 commit/字节暂存，短事务重新准入；取消/预算/失败持久化 | 真实模型调用服务（网络 fixture）及独立实际 GitHub HTTPS/JGit→认证 RPC→STAGED；未安装/启用 | NOT_RUN |
| Git 故障与清理 | 独立 HttpConnectionFactory、TLS、URL 策略、wire/object 预算、DOS readonly 清理、关闭连接 | 缺失 ref、128-byte wire 故障、取消 47ms、1s 超时 1130ms，新目录剩余 0；旧目录排除 1，删除被拒绝 | NOT_RUN |
| ui.navigate / user-input.request | 宿主目的地核验；澄清结束 run 无额外模型调用；历史导航不重放 | 实际模型及浏览器导航/刷新通过 | NOT_RUN |
| help.search / project.content.discover | 两侧条件目录、同一检索、当前 scope/source 核验；失败结果持久化，不伪称 UNKNOWN 或继续执行 | 真实模型完整目录调用两能力；实际 Python/Ollama/RPC/pgvector；实际浏览器来源卡片刷新恢复 | NOT_RUN |
| 取消/截止/连接中断/模型及工具失败 | 跨进程故障注入、宿主 fence、终态只落定一次、无迟到正文/残留草稿、checkpoint 不越过完成边界 | 实际 browser offline/online；取消用受控慢流，供应商错误为注入，未称线上供应商自然故障 | NOT_RUN |
| checkpoint/生命周期/重启 | 持久 CAS、中间写/删除、预算、唯一 claim、完成边界、UNKNOWN 不重放 | 新 packaged Java JVM 启动恢复 + browser：显式构造已提交写/UNKNOWN 崩溃窗口，旧主机 dormant；新主机终止孤儿，保留历史与完成 checkpoint，项目仍为一个 | NOT_RUN |
| 真实长会话 SUMMARY | 标准 middleware，SUMMARY 共用模型预算，不自动重试 | 相同真实模型 35 完成轮次、真实摘要与身份连续；一次摘要失败在供应商调用前注入，下一新请求从完成边界恢复；72 条公开消息 | NOT_RUN |
| 按轮次过程 UI | runId/toolCallId/sequence 对齐、去重、折叠、终态未确认显示 interrupted；不保存撤回草稿为思考 | 实际 Vue/浏览器/SSE 重连、刷新、取消、导航、迁移历史；源卡片不串轮、不重复、失败无假执行状态 | NOT_RUN |

审批保持既有策略；本轮可写能力仅项目创建与 Skill 暂存。外部写、安装/启用和兼容 bash/read 不进入可执行目录，APPROVAL_REQUIRED 失败关闭。未支持的能力不能从只读验收推断为通过。

## 统一 RAG U1–U3 当前清单

| 项目 | 已实现与组件证据 | 真实集成/效果证据 | 验收判断 |
|---|---|---|---|
| U1 结构化来源与分块 | 原业务单元、UTF-16/emoji/CRLF 原切片、标题、hash/ref/version；受控 raw projection/job | RESOURCE 真实 Python 分块与全文重组；本地 0.6B 1024 维索引；没有第二套索引 | 功能集成通过；生产未验 |
| U2 真实索引与混合检索 | Python RRF/预算/去重；Java SQL scope/pgvector；租约、CAS、head 激活；废弃非法派生行/排除回填标记 | 真实中文改写、资源、Ollama 不可达：混合检索显式 VECTOR_UNAVAILABLE、纯语义/索引失败 | 功能集成通过；生产回填未执行 |
| profile/generation 与来源核验 | 固定 digest/profile；完整 DTO/hash/authority/位置/版本；最终规范业务源重验，旧 grant/lease 拒绝 | authority 提升、来源撤回、元数据变更、超时 lease、旧回写拒绝 | 组件与隔离库集成通过 |
| 项目冻结输入 | Runtime 必需事实优先；首次补充成功/失败状态冻结，既有 frozen bytes 不重算 | Python 断开后旧输入字节不变且不新增 grant；新请求显式补充失败，恢复后仍重放首次输入 | 实际集成通过 |
| 全局助手按需有界检索 | 与项目共用 Python RAG，独立控制流程与授权 workload | 实际未变模型调用 help.search / project.content.discover；来源卡片带版本、位置与宿主引用 | 实际集成通过 |
| 52 查询质量/权限评估 | 覆盖中文改写16、精确标识符16、资源8、过期4、无答案4、隔离4 | 实际 Ollama/RPC/pgvector；逐条来源及延迟、采样 Python 工作集/共享 GPU；校准阈值 .50 后过期/越权均0 | **标签 PENDING_HUMAN_REVIEW，质量门槛未通过** |

评测指标以 [原始证据](evidence/SHARED_RAG_52_QUERY_EVALUATION.json) 最新运行值为准；同一个小夹具用于阈值校准，不能冒称独立测试集。GPU 是整设备共享使用量的一秒采样，工作集是 Python launcher/子进程采样，均不代表独占模型或瞬时绝对峰值。旧 Java 对照只使用已有 lexical/Noop 路径，没有新增 Java embedding 或第二套检索索引。

## 最终回归与交付证据

最终完整回归 **1739 tests / 0 failures / 0 errors / 13 skipped**，BUILD SUCCESSFUL；同次显式开启确定性跨语言、真实 Ollama 索引/故障、U3 宿主工具及 52 查询评测。13 项 skip 是独立实际模型/HTTPS/浏览器门禁，已有对应单独证据，不记为本次通过。Python **221 passed**，前端 **119 passed / 1 显式 gate skipped**，vue-tsc、Vite build、依赖检查及 bootJar 均通过。汇总见 [最终交付证据](evidence/GLOBAL_ASSISTANT_RELEASE_VERIFICATION.json)。

最终回归使用隔离 CPython **3.11.14** 和未变的 requirements.lock，原 .venv 未替换。最新打包 Java JVM 启动恢复与实际浏览器专项再次通过（28s），保留旧历史、完成 checkpoint、UNKNOWN 写不重放。解释器证据见 [运行环境验证](evidence/GLOBAL_ASSISTANT_PYTHON_RUNTIME_QUALIFICATION.json)。

最新52查询：Recall@8 **0.95**、MRR **0.9375**、P50 **264ms**、P95 **438ms**、过期来源及权限泄漏 **0**，12 条空结果预期均正确；标签仍 PENDING。采样资源值和逐条结果在原始证据，不能当作生产容量保证。

回归中修复了实际门禁发现的问题：retrieval/index 与 protocol、retrieval 与 Agent 配置的包循环由窄端口消除，没有放宽 ArchUnit；共享库测试中缓存的旧 worker 导致的真实行锁死锁通过禁用测试默认后台扫描、专项显式驱动修复。旧 worker 测试只限定项目选择，实际 enrichment/数据库和断言仍保留。Python 冷启动等待独立于 run 预算调整，并保留失败启动日志；首次两项启动失败未取得根因证据，定向和后续回归记录分开，不冒称已定位供应商或业务故障。另一次跨语言子进程退出码为 Windows 0xC0000005（原生访问冲突），启用 faulthandler；12 次独立导入通过。context cache 上限4的试验仍出现一次原生崩溃，未将该试验记为通过；faulthandler 与 Windows Application 日志定位到 uv CPython 3.11.15 / python311.dll，在 Ollama/Pydantic Schema 导入阶段访问冲突。随后在 backend/build 下隔离安装 CPython 3.11.14，依照同一 requirements.lock 创建验证环境；共享包版本及 Pydantic 原生模块 SHA-256 完全一致。通过 SPEC_AGENT_GA_TEST_PYTHON 显式选择验证环境，不替换原 .venv、不改生产、不加自动重试。已经完成的真实网络、35轮模型和 UI 验证不因未影响它们的改动重复执行。

核心真实证据：

- [Git 暂存](evidence/GLOBAL_ASSISTANT_REAL_GIT_STAGING.json)、[Git 故障](evidence/GLOBAL_ASSISTANT_REAL_GIT_FAULTS.json)、[真实长摘要](evidence/GLOBAL_ASSISTANT_REAL_LONG_SUMMARY.json)。
- [实际产品浏览器](evidence/GLOBAL_ASSISTANT_REAL_BROWSER.json)、[新 Java 进程恢复浏览器](evidence/GLOBAL_ASSISTANT_REAL_RESTART_BROWSER.json)、[来源 UI](evidence/GLOBAL_ASSISTANT_REAL_RAG_BROWSER.json)。
- [共享真实索引/冻结](evidence/SHARED_RAG_U1_REAL_INTEGRATION.json)、[工具宿主集成](evidence/SHARED_RAG_U3_TOOL_INTEGRATION.json)、[未变真实模型双工具](evidence/SHARED_RAG_U3_REAL_MODEL.json)、[Ollama 故障](evidence/SHARED_RAG_REAL_OLLAMA_UNAVAILABLE.json)。

## 剩余外部阻塞

1. 需要真实人工审阅68条独立候选标签及首轮评分口径；52条开发集不能代替独立验收。已提供冻结数据、审阅表和入口，自动化不能代替人工身份与判断。
2. 指定旧临时目录 `C:/Users/32962/AppData/Local/Temp/spec-agent-git-skill-13327698071011337690` 删除被自动审批以 `blocked by policy` 拒绝。未换工具绕过，没有标记清理完成。

其余生产步骤已准备，但生产切换不属于本次授权，不能执行来关闭上述门槛。

## 历史实施记录（以下描述当时状态，以顶部当前结论为准）

## 持续交付增量：轮次展示、Git 准备与共享检索（2026-10-02，进行中）

本次完整授权继续执行中；以下仅为已取得的阶段证据，**不宣称阶段 1 / U1–U3 整体验收完成**。
生产模型和引擎设置均未切换。此前通过且不受新改动影响的资格验证不重复执行。

- 工具公开事件增加 `toolCallId`，前端活动保留 runId / toolCallId / sequence。
  新事件精确关联；旧事件只有唯一待完成项时才关联，不猜测重复工具结果。
  时间线按宿主 run 组织「用户请求 → 本轮处理过程 → 本轮回答」，完成过程可折叠。
  已完成历史按宿主持久事件恢复，不执行历史导航，不保存撤回正文为过程。
  终态未确认工具标为 interrupted，明确未收到完成确认；不会伪称业务动作已被撤销。
  前端相关回归 **118 passed / 1 显式真实回放 skip**，类型检查通过；浏览器验收仍待执行。
- 新 PreparedCapabilityAdapter 只允许事务外只读网络准备，仍使用原能力注册表。
  GA 先持久保留工具预算与 STARTED，网络准备不占 run 行锁；准备后在短事务中
  再核验权限/租约/截止时间，执行原 CapabilityRuntime claim 和数据库暂存/结果提交。
  Skill import 使用同一次准备的 commit/字节做 discovery 与暂存，不再次下载移动中的 ref。
  旧 Java 引擎的原 invoke 入口继续兼容；没有新增自动安装/启用或审批策略。
- JGit 准备增加绝对超时/活跃检查、ProgressMonitor、transport close 提示与受控目录清理。
  实际 HTTPS 验证发现 Windows 只读 pack 文件清理失败，修复清理前的 DOS readonly 位后通过。
  `GLOBAL_ASSISTANT_REAL_GIT_STAGING.json`：真实 GitHub HTTPS/JGit discovery + 实际宿主 RPC/
  隔离测试数据库暂存，包 STAGED，未安装/启用。模型不在该测试边界内。
  取消锁窗口组件测试通过：准备被阻塞时取消请求小于 1 秒，准备迟到后拒绝写入并标 UNKNOWN。
  真实网络取消/超时/失败、目录清理专项和真实长摘要仍待补齐，不能用锁窗口测试代替。
- Python shared retrieval 已实现固定 profile 的 OllamaEmbeddings 适配，`truncate=false`、
  float32/L2/checksum、单并发/16 项批量；source-preserving 分块保留原始 UTF-16 位置及标题。
  RRF/去重/预算在 Python，Java 只做受授权 SQL lanes 和最终版本校验；不截断正文再沿用旧 hash。
  Ollama 不可用返回显式 VECTOR_UNAVAILABLE，纯语义请求报错；没有另一套融合 fallback。
- V49 只在隔离测试库应用：扩展同一 retrieval_entries，增加来源版本与 profile/generation，
  grants/jobs/租约/CAS；pending_embedding 是同表中准备 generation 的暂存列，没有第二套索引。
  Java 来源元数据变化推进版本；纯 provenance 变化保留内容向量，旧回写仍因版本 CAS 被拒绝。
  新 generation 完整准备后，以 head CAS 原子激活；旧 grant 不可跨 generation 使用。
- `SHARED_RAG_U1_REAL_INTEGRATION.json`：实际 Python + 本地 0.6B Ollama + 认证 Java RPC +
  既有 pgvector 的索引及中文改写查询通过。测试源为真实业务 Node，另一个项目隔离。
  热查询记录约 386 ms，仅单个样例，不代表评测 p95 或检索质量门槛。
  Python 全量 **216 passed**；Java scoped source / CAS / generation 组件两项通过，真实 U1 一项通过。
- 项目侧加入显式 `spec.agent.retrieval.engine=python-rag.v1` 选择，默认仍 java-hybrid.v1。
  新请求只调用 Python 融合；项目必需事实仍由 Java 构造，补充失败状态进入首次冻结 metadata。
  新/旧冻结输入兼容与 memory.search 仍需新增针对性验收；暂不称 U2 完成。

仍在推进：真实 Git 故障/取消/清理、真实摘要、浏览器与迁移恢复；资源分块任务桥接、
宿主 worker、帮助/内容发现能力、冻结回放、至少 50 条标注效果和权限评估、部署/回滚说明。
人工标签审阅未取得，AI 编写的评测夹具不能冒称人工标注。

## 持续交付验收增量（2026-10-02 03:45，Asia/Shanghai）

本节覆盖后续实际修改，不替代未完成的整体门禁。

| 功能 | 实现/组件验证 | 真实集成证据 | 生产验收 |
| --- | --- | --- | --- |
| 资源结构分块 | Python source-chunks 严格契约；宿主 raw snapshot + 同一 index job 租约；原始 UTF-16/hash/连续切片校验；来源变更拒绝旧回写 | actual Python + 本地 Ollama + Java RPC + 同表 pgvector + RESOURCE 内容检索通过 | 未运行 |
| 项目首次检索冻结 | python-rag.v1 opt-in；首次成功或 supplementalRetrievalUnavailable 均冻结；mandatory lineage 保留 | 服务断开后旧输入逐字回放、grant 数不增加；新输入显式失败，恢复后仍回放首次输入通过 | 未切换 |
| Git HTTPS 传输故障 | 每 clone 独立 HTTP factory；标准 JVM TLS、不允许关闭校验；每个重定向连接重新核验；真实 wire byte limit；ObjectLoader 分配前 size limit | `GLOBAL_ASSISTANT_REAL_GIT_FAULTS.json`：缺失 ref、128-byte 故障预算、实际网络取消停止 47 ms、1 秒 timeout 返回 1130 ms，所有新目录清理；真实 STAGED 再验证通过 | 未运行 |
| 长会话连续性和 SUMMARY 失败 | 无隐式 retry；失败仅落定一次宿主错误提示，不推进 completed checkpoint boundary | `GLOBAL_ASSISTANT_REAL_LONG_SUMMARY.json`：同一 mimo-v2.6-flash-free 实际 35 个完成轮次、真实 SUMMARY；另注入一次 summary transport failure，下一新请求成功恢复、原始 project UUID 保留、72 条公开历史 | 未运行 |
| 按轮次过程 | 活跃正文/status/clarification 归属本轮；历史恢复只还原工具公开状态 | 组件 5 项通过、vue-tsc 通过；实际浏览器正在验证 | 未运行 |

Python 全量 217 passed（一次 Windows native access violation 重跑后通过，不当作功能测试通过证据）；
Java SharedRetrievalIntegrationTest 三项及旧 SnapshotBuilder 三项通过。
V50 仅在隔离测试库应用，保存受控 raw projection 任务，不是第二套检索或向量索引。
同一 EmbeddingEnrichmentWorker 在明确选择 python-rag.v1 时调宿主 split/index jobs；默认旧引擎不改变。
宿主 HTTP 客户端增加读取前 2 MiB allocation limit、全响应截止时间和取消 future；保留安全的白名单错误码。

限制：旧失败首次测试留下的
`C:/Users/32962/AppData/Local/Temp/spec-agent-git-skill-13327698071011337690`
已经核验属于本次测试；调用 native PowerShell 清理被自动审批拒绝，仅给出 `blocked by policy`。
没有绕过拒绝，也没有宣称该目录清理完成。Git fault evidence 的 previousDirectoriesExcluded=1 明确排除它。

尚待：实际浏览器/迁移恢复、canonical source 最终核验扩展、帮助/内容发现能力、50 条查询效果评测和人工标签审阅、部署及回滚准备。
**阶段 1 / U1–U3 仍未整体完成；生产设置保持原样。**

## 最新增量：阶段 1 正文流与能力故障门禁（2026-10-02）

继续现有实现，没有重开阶段 0，没有更换 `mimo-v2.6-flash-free`。
**判断：阶段 1 尚未达到整体验收条件；生产验收未完成。** 当前已经通过的
正文流、只读及本地写链路不能代替剩余网络工具窗口和真实长期摘要验收。
生产默认仍为 `java-legacy.v1`；测试显式选择 `langchain-ga.v1`，没有生产切换。

### 本轮实现与事件语义

- Java 原生 SSE 解码器将 `delta.content` 作为独立候选文字回调；工具参数、
  reasoning、usage、供应商协议都不进入正文回调。工具参数仍完整聚合并
  核验后，连同成功模型账本一起返回给框架，不允许执行参数片段。
- 新受认证 `/internal/v1/global-assistant/model-inference/stream` 返回
  `ga-model-stream.v1` NDJSON：callId、连续 sequence、TEXT_DELTA、COMPLETED
  或安全 FAILED。AGENT 可流式，SUMMARY 仍非流式且共享预算；既有 Zen
  请求头、代理、凭据与保留工具策略继续复用。修复了 Spring 流式响应未
  显式声明 Content-Type，以及原 adapter 仍拒绝 stream=true 的旧门禁。
- Python 由标准 `BrokerChatModel` 发起一次流式调用，框架工作线程通过
  32 项有界队列转发文字；create_agent 继续唯一掌控工具循环。
  `ga-execution-event.v1` 增加 TEXT_DELTA(callId/text)、TEXT_RESET(callId)。
  每条 NDJSON 有界、严格 UTF-8/字段/身份/顺序；完整流最多 2 MiB、8192
  帧，预留失败帧。无 retry/fallback，也不把供应商原始 SSE 透传前端。
- 原生模型可能先输出文字再选择工具。先前文字是**可撤回草稿**；确认
  工具调用后发送 TEXT_RESET，当前工具帧的文字不释放。工具参数始终
  只在内部完整结果中。最终权威消息必须等于宿主最终模型账本和完成
  checkpoint，不将候选草稿当作已完成回答。前端沿用 generation/reset。
- Coordinator 改为边读边提交正文，不再读完全流后才发布。
  每次草稿追加在 run 锁内核验 epoch/lease/claim、已保留 MODEL call 与
  活跃状态，并原子提交内部回执和既有 ANSWER_* 事件；终态仍核对完整
  流、模型/工具输出和 checkpoint 后提交一次。先前草稿可以存在于
  事件记录；协议截断或失败时撤回草稿，不能伪称整个流事务回滚。
- V48 仅在测试数据库将内部事件预算由 256 扩至 8192，未改已应用迁移。
  失败/取消会清除待提交草稿，终态后不能追加正文；前端投影也拒绝终态
  后的增量、reset 和第二终态。异常断连向 Python 发一次有界 cancel
  提示；持久 Java fence 是权威，Python 提示失败也不重试或重新启动。
- 只读项目候选的 sourceRefs 由现有宿主资源投影补齐，绝不采用模型自报
  的来源。当前只是应用项目身份；来源版本与冻结 RAG 输入仍属于 U1–U3。

### 当前能力逐项验收

“模拟通过”包括真实 Python/create_agent、宿主 HTTP/测试数据库和注入的
模型或网络边界；“真实集成”明确标注实际使用的边界。所有生产列均未验收。

| 能力 | 已实现 | 组件/模拟测试 | 真实模型集成 | 尚未验证 / 生产门槛 |
|---|---|---|---|---|
| project.list_recent | 是 | 宿主投影、来源、参数/预算、撤销通过 | 2 模型 / 1 工具；真正正文流通过 | 生产 UI/性能验收 |
| project.search | 是 | 实际宿主项目搜索、来源、撤销通过 | 搜索→概要→导航通过 | 生产场景验收 |
| project.get_summary | 是 | 实际概要、缺失来源失败、撤销通过 | 同上，真实 projectId | 跨轮来源变更场景仍待扩充 |
| project.create | 是 | 参数拒绝、相同/新 tool ID 幂等、UNKNOWN 禁重试；实际写后异常事务回滚；撤销通过 | 2 模型 / 1 工具；仅 1 项目，重复 dispatcher 无额外副作用 | 生产本地写验收 |
| skill.import.discover | 是 | 实际 discovery 服务 + Git fixture；网络失败持久重放、撤销通过 | 当前模型调用实际服务通过；Git/network 是 fixture | 真实 HTTPS clone/故障/临时目录窗口 |
| skill.import | 是 | 实际暂存数据库写入一次；未安装/启用；网络失败、截止后暂存回滚、撤销通过 | 3 模型 / 2 工具 discover→import；Git/network 是 fixture | 同上；不能称真实网络或外部 exactly-once 通过 |
| ui.navigate | 是 | 宿主目的地/项目校验、来源、撤销通过 | 搜索→概要→真实项目导航，4 模型 / 3 工具 | 生产浏览器导航验收 |
| user-input.request | 是 | 有界问题结束本轮、撤销通过 | 1 模型 / 1 工具，无续答模型调用 | 生产多候选交互验收 |

权限/审批保持既有策略：目录来自现有注册表的 APPLICATION/权限筛选，
冻结目录不能扩权，执行前重新准入。八项能力逐项模拟撤销均在执行和预算
前拒绝；认证、原生调用来源及参数 hash 另有 HTTP 验证。本地创建和 Skill
暂存沿用 LOCAL_DURABLE；Skill 安装/启用仍由用户设置页完成。外部写、
skill.install/enable、保留 bash/read 不进入 GA 目录；APPROVAL_REQUIRED
不能在阶段 1 自动恢复或批准。没有增加第二套审批策略。

### 故障、持久化与恢复验收清单

| 项目 | 已实现 | 模拟/组件 | 真实集成 / 限制 |
|---|---|---|---|
| 正文早于模型完成到达产品 | 是 | 分裂 UTF-8、空白 token、独立类型、内容核对通过 | 当前模型最新证据有 56 条 ANSWER_DELTA；工具结束后仍在 MODEL RESERVED 时观察到正文 |
| 草稿已发布后的取消/截止/模型失败/Python 断连 | 是 | 实际跨进程四种注入通过；终态一次、无迟到正文、无完成边界推进 | 供应商故障是注入，不能称线上供应商故障实测 |
| 公开 SSE 断连与 Last-Event-ID 续传 | 是 | 实际产品 SSE 端点通过；断连不取消，续传不重复正文 | 生产浏览器重连未验收 |
| 工具失败 | 是 | 缺失项目作为 FAILED 观察；框架正常解释；参数/协议不成为正文 | Skill 真实网络失败窗口待验证 |
| 工具写后异常、截止后暂存 | 是 | 项目事务回滚、UNKNOWN 新身份拒绝；Skill 暂存回滚/迟到提交拒绝通过 | 没有证明正在 clone 的网络调用可立即硬中断 |
| 完成消息/checkpoint/公开边界一致 | 是 | 当前 Human 身份与最终 AI/Tool 内容、伪造/截断/数值溢出通过 | 五组真实模型产品链路成功完成 checkpoint |
| 重启恢复 | 是，按阶段 1 策略 | 中断链保留诊断；旧 run 失败，不重放；新请求读取最后完成边界；删除级联通过 | 不支持恢复旧 run 的工具副作用；生产迁移/恢复演练未执行 |
| 长会话摘要与宿主预算 | 是 | 35 轮连续性、摘要共享 6 次模型预算、无摘要重试、结构化身份保留通过 | 当前真实模型长期 SUMMARY 质量/故障效果未验证 |

Java 将本地动作及结果提交置于同一 run 锁内，已开始的宿主动作与取消按
提交顺序线性化。Skill 网络操作沿用现有 JGit 超时；本轮没有证明用户取消
能立即中断正在 clone 的 socket，也没有用本地事务宣称网络/文件副作用
恰好一次。这是实际剩余验收窗口，不能用模型流取消测试替代。

### 证据与验证

真实模型只读取既有设置/凭据，执行数据写隔离测试数据库：

- `evidence/GLOBAL_ASSISTANT_PRODUCT_DISPATCH_INTEGRATION.json`：真实正文流、只读能力、checkpoint/产品事件；同批公开事件通过真实前端 GaRunProjection 回放。
- `evidence/GLOBAL_ASSISTANT_PRODUCT_WRITE_INTEGRATION.json`：真实创建及重复派发无额外副作用。
- `evidence/GLOBAL_ASSISTANT_PRODUCT_QUERY_NAVIGATION_INTEGRATION.json`：真实搜索/概要/宿主导航。
- `evidence/GLOBAL_ASSISTANT_PRODUCT_CLARIFICATION_INTEGRATION.json`：真实问题能力，1 次模型结束本轮。
- `evidence/GLOBAL_ASSISTANT_PRODUCT_SKILL_STAGING_INTEGRATION.json`：真实模型与实际 Skill 服务/数据库；明确使用 Git fixture，未验真实 clone。
- `evidence/GLOBAL_ASSISTANT_PHASE1_STREAM_FAULT_ACCEPTANCE.json`：本轮测试范围、模拟/真实门禁与剩余整体验收项的汇总。

验证：Python 全量 **204 passed**；Java `testNonLive` 全量（显式启用
确定性跨语言 gate）**1721 tests / 0 failures / 0 errors / 7 skipped**，
BUILD SUCCESSFUL（2m19s）。7 个 skip 是显式真实模型 gate，五组新产品真实
gate 已另行运行通过，不能把 skip 计为通过。此前定向宿主/传输/架构回归
**139 tests / 0 failures / 0 errors / 4 skipped**。前端 GA 范围含真实事件
回放 **14 files / 114 passed**，`vue-tsc --noEmit` 通过。最后草稿发布器并发
可见性加固的故障/架构定向回归 **50 tests / 0 failures / 0 errors /
0 skipped**，BUILD SUCCESSFUL（20s）；这不等于生产浏览器验收。

### 阶段判断与下一项

阶段 1 基础代码、当前模型正文流、现有能力的受控业务代表链及确定性故障
门禁已推进；**整体验收尚不通过**。剩余门槛是：真实 Git HTTPS discovery/
staging 的取消、超时与临时目录清理；真实长期 SUMMARY 连续性/故障；
生产浏览器完整交互与性能/取消延迟、部署迁移及恢复/回滚演练。

下一项具体工作：先验证 Skill 的真实网络窗口及真实长摘要；网络动作不能
在失去租约后提交暂存，UNKNOWN 不换身份重试，包始终未安装/启用，临时
目录可清理；摘要与主调用共享预算，项目身份/引用与公开完成边界一致。
这些通过后再评定阶段 1 开发验收和生产切换门禁，不自行切换生产引擎。

统一 RAG U1–U3 **仍未开始业务迁移/整体验收**：下一阶段按保留结构的
分块与来源身份/版本/位置 → Java 既有 pgvector 存储桥接、真实索引与混合
检索 → profile/generation/CAS、来源核验和项目冻结输入推进；至少 50 条
人工标注查询及中文改写、标识符、资源内容、过期、无答案、权限隔离评估
仍待交付。沿用已完成 U0，0.6B 不变；没有 Java Ollama provider、第二索引
或 Python 直连生产库，也没有把模拟标签说成人工标注。

## 此前增量：产品协调器与生命周期（阶段 1，历史快照）

没有重开阶段 0。新 `GaExecutionPreparation/Coordinator/Completion` 已接入
产品 `RunDispatcher`，Python FastAPI 新增独立 executions NDJSON 入口。
显式配置 `spec.global-assistant.engine=langchain-ga.v1` 使用远程协调器；
默认仍为 `java-legacy.v1`，没有修改产品部署设置、生产数据库或旧项目 Runtime。
失败不会再派发旧循环作为 fallback。

### 本轮具体实现

- V47 在测试数据库完成 Flyway：持久化规范执行信封/hash、一次性 executor
  claim、内部事件身份/顺序；checkpoint head 保存最后完成的 checkpoint 和
 公开历史边界。模型目录/binding、启动 claim 与既有 RUN_STARTED 原子提交。
- Python 启动前向 Java claim，核对 run/epoch/lease 和确切信封 hash。
 重复派发不会破坏当前 owner；重复 HTTP 启动和进程重启不能重获执行资格。
- 旧会话首次有界导入；后续只追加已完成公开边界之后的消息，当前 messageId
  只进入一次。新运行只读取宿主最后完成的 checkpoint；更新的中断链保留
  诊断，但不成为自动 resume 点。FAILED/CANCELLED 不推进完成边界。
- Coordinator 有界读取全流并核验 scope、版本、递增 sequence、eventId、
  closed payload 和结束帧。回答正文必须等于已持久化的最终模型输出，澄清
  必须等于已验证工具问题；完成 checkpoint 还必须包含当前 messageId 和
  相同终结观察，不能只因旧 checkpoint 存在就推进完成边界。
  数值溢出/截断/伪造流事务回滚，不发布部分终态。
  宿主能力 RPC 继续发既有 TOOL/UI 事件，终态复用原生命周期/steer 交接。
- 已完成公开消息、事件、完成 checkpoint 与历史边界在同一事务提交。
  取消/终态门禁拒绝迟到模型、工具、checkpoint 与完成结果；会话删除清理
  新执行/事件/checkpoint。阶段 1 重启语义仍是失败中断，不自动重放。
- 长会话使用所锁定框架 `SummarizationMiddleware`，64 条消息或约 16000
  tokens 触发，保留近期 24 条消息；SUMMARY 通过同一 broker/binding 和宿主
  6 次预算。框架默认 `with_retry` 被显式关闭，失败不编造摘要或补一次调用。
  项目候选身份与 sourceRefs 保存在有界结构化状态，不靠摘要复述；这些
  是观察数据，不提升为当前权限。codec 白名单补充固定 RemoveMessage。

### 验收分级与证据

- **组件/确定性跨语言通过**：真实 Python HTTP + create_agent + Java 测试
  数据库覆盖产品派发、旧历史与两轮消费、重复派发/启动、取消迟到结果、
  steer 恰好交接一次、Python 进程中断后新请求、删除清理、伪造/截断事件、
  执行 claim、预算和权限门禁。35 轮长会话验证完整公开历史保留、SUMMARY
  与主模型共用计数，Python codec 回读保持结构化项目身份/引用。
- **真实集成通过**：产品 application → dispatcher → coordinator → Python
  executions HTTP → 当前 `mimo-v2.6-flash-free` → 实际 `project.list_recent`
  → checkpoint → 产品回答/终态事件，**2 次模型调用 / 1 次工具调用**。
  本 gate 的目录仅开放该只读能力，数据写入测试数据库，设置/密钥只由
  Java 读取。证据：`evidence/GLOBAL_ASSISTANT_PRODUCT_DISPATCH_INTEGRATION.json`。
- **生产验收未完成**：没有开启产品新引擎或生产 Flyway。当前正文是在
  完整聚合/核验后发布；provider token → typed TEXT_DELTA 的真正逐 token
  流仍未实现，不能用 NDJSON 外壳代替此项。真实模型全部六项能力与写/
  Skill 文件/网络故障窗口、跨轮权限撤销/来源复核、细分错误码与性能仍未
  全部验收。长摘要通过确定性链路，不等于真实模型长会话效果已通过。

验证命令与实际输出：Python 默认全量 **193 passed**（既有 Starlette warning）。
Java `testNonLive` 全量 **1695 tests / 0 failures / 0 errors / 3 skipped**、
BUILD SUCCESSFUL（1m57s），为长摘要增量之前的回归快照。长摘要增量后
再次执行全量 `testNonLive` **BUILD SUCCESSFUL（2m8s）**；之后增加 checkpoint
完成内容与数值溢出核验，coordinator 定向回归 **8 tests / 0 failures /
0 errors / 1 skipped**、BUILD SUCCESSFUL（32s）。真实 provider 方法显式
单独执行通过，不计入离线通过数。最终澄清/模型失败终态、真实模型产品
派发与架构门禁定向运行 **BUILD SUCCESSFUL（24s）**，最新真实证据已刷新。
`git diff --check` 通过。

下一项：完成 typed 正文流与剩余六项能力的故障/错误/权限验收，再决定阶段 1
生产切换。统一 RAG U1–U3 和 50 条人工标注评估仍未交付；Python 共享实现、
Ollama 0.6B/既有 pgvector/Java 来源准入边界继续有效，没有新 provider 或索引。

## 此前增量：模型、存储与能力 RPC（保留实施记录）

沿用已完成的契约与组件结果。本轮先接通原生模型 SSE、Java 受认证
model/checkpoint/capability RPC 和数据库执行门禁。原 Java GA loop 仍为产品默认；
没有开启新引擎、修改现有项目 Runtime、增加 Java Ollama provider、第二索引
或 Python 直连数据库。

### 模型请求要求与真实证据

- GA 原生路径复用 `HttpOpenCodeZenTransport` 的 HTTP client、显式代理、
  User-Agent、Bearer 与完整 x-opencode 身份头，不另建供应商客户端。
- 初次仅发送真实目录工具时得到 HTTP 403 / FreeTierError。确认是遗漏
  已验证的免费额度载荷要求，**不能据此判定模型不支持原生工具或密钥失效**。
  已补回原传输层的 bash/read 占位声明。它们不进入宿主能力目录；命中
  这两个名字、未授权工具、历史重复 tool-call ID 均在分派前拒绝。
- Zen 的终结 usage 帧保留单个 choice 和 null/空字段。解析器只允许这种
  无新增内容的尾帧；仍拒绝结束后新增正文/参数、并行调用、缺失 finish/
  usage/[DONE]、重复 JSON key、非法 UTF-8、超限与截断。参数完整聚合后
  才返回可执行调用；不把 reasoning 写入结果或 checkpoint。
- 新 GA 请求有绝对截止时间与主动取消轮询，静默供应商也能被中止；无
  retry/fallback。旧 Project 载荷与旧解析器未改。
- 当前配置 `mimo-v2.6-flash-free`：真实 provider-native tool call + tool
  result follow-up **PASS，2 次模型调用**。证据：
  `evidence/GLOBAL_ASSISTANT_NATIVE_SSE_QUALIFICATION.json`。此项仅是供应商
  传输资格，不等同于业务 Agent 场景通过。
- Python → 受认证 Java model/checkpoint HTTP → 真实供应商/宿主测试数据库：
  **PASS，2 次真实调用，新 saver 成功读取持久 checkpoint**。证据：
  `evidence/GLOBAL_ASSISTANT_NATIVE_BROKER_INTEGRATION.json`。模型配置从
  产品设置只读解析，凭据不进入 Python，执行数据仅写测试数据库。
  工具结果是 synthetic read-only fixture，**业务 capability dispatch 未验收**。
- 后续真实业务集成 **PASS**：Python `create_agent` → 认证 Java broker →
  当前真实模型 → 现有 `CapabilityRuntime` 的 `project.list_recent` →
  Java 测试数据库 checkpoint；新 saver 成功恢复结果。共 **2 次模型调用 /
  1 次业务工具调用**，本验证目录仅开放该只读能力。证据：
  `evidence/GLOBAL_ASSISTANT_FRAMEWORK_BUSINESS_INTEGRATION.json`。
  这是实际业务 RPC 的局部集成，不包含产品 coordinator、公开事件流、
  写工具真实模型场景或生产生命周期验收。

### 宿主持久化与已验证范围

V43–V46 为增量迁移，已由 Flyway 在测试数据库验证，未迁移产品数据库。
所有旧 run 保留 `java-legacy.v1`。新执行仅能在 CREATED run 上显式绑定
`langchain-ga.v1`，模型/provider/settings revision、确切工具投影、epoch、
lease 与最长 180 秒 deadline 不从 Python 覆盖。

Java 原子持久预算为 6 次 model（含 SUMMARY）/5 次 tool；call ID + payload
hash 绑定。完成结果可重放，不再次消费预算；参数变化、RESERVED/UNKNOWN
重复请求拒绝执行。取消、终态、删除、陈旧 epoch/lease 与 deadline 对
调用和迟到结果生效。宿主本地数据库变更与调用结果可在相同事务中提交，
并发重复只变更一次；失败回滚。该组件证明**不自动推广为外部系统/文件
副作用的恰好一次保证**，文件暂存、外部请求及崩溃窗口仍需验证。

`/internal/v1/global-assistant/capabilities` 已接入现有能力 Runtime。
宿主核对冻结目录及 hash/版本、当前能力准入、已持久化的模型原生工具
请求与参数，再分派动作；Python 不能自行授权或伪造工具请求。无效参数
尝试也消耗预算。调用先提交 RESERVED，再执行动作；未知结果保留 UNKNOWN，
相同语义换 tool-call ID 仍拒绝重试。无新观察的重复调用返回明确错误，
框架停止且不追加模型调用。本地写能力使用 run/能力/参数语义幂等键。
测试数据库实际项目创建、重放、澄清和来源核验导航已通过；读取能力的
真实模型/框架链路单独通过，不代表全部六项能力和副作用故障窗口完成。

Checkpoint 使用线程级固定 namespace/head CAS，安全 JSON codec/hash/
framework/state 版本，checkpoint、中间写、parent、分页/过滤及删除；
相同 PUT/PUT_WRITES 丢失响应后的重复不重复写入，负索引保留 LangGraph
upsert 语义。会话删除通过 FK cascade 清理新存储。新 saver 已从真实数据库
恢复框架状态。阶段 1 仍不自动 resume 中断 run，也没有重放写工具。
实际框架工具派发会保存 LangGraph `Send`。安全 codec 已增加固定类型
白名单编码/恢复，并通过工具派发 checkpoint 回读测试；仍不允许动态
import、pickle 或任意构造器。JSON 重复键及非有限数字拒绝。

组件验证：数据库并发幂等/回滚/预算/取消/epoch/lease/deadline/CAS/损坏
codec/权限范围，认证 HTTP、冻结模型及工具目录、重复 model 请求，以及
旧 OpenCode HTTP 和架构门禁均通过。显式 Python 框架跨语言测试通过，
供应商边界为 deterministic，单独记录于真实供应商集成证据之外。

最新回归：Python 默认 `pytest -q` 全量 **185 passed**（1 项既有 Starlette
deprecation warning）；Java `testNonLive` 全量 **1687 tests / 0 failures /
0 errors / 2 skipped**，BUILD SUCCESSFUL（1m42s），显式开启 deterministic
Python 跨语言测试。两项 skipped 为单独启用的真实供应商验证，均已单独
执行通过且有独立证据。之后新增 UNKNOWN 换工具身份重试的定向回归，
不把它计入上述全量计数。`git diff --check` 通过。先前 Python 计数未在
本次默认命令中复现，已改为实际输出，避免沿用不一致的总数。

### 上述增量时尚未验收（后续进展见顶部）

1. 当前新 RPC 尚未由产品 coordinator 派发。能力 RPC、ui.navigate/
   user-input 和重复调用停止已接通组件链路；六项能力完整权限/副作用
   故障窗口及产品派发仍未全部验收。
2. 旧会话导入、消息消费边界、结构化身份、长会话摘要及稳定公开事件映射；
   cancel/steer/replay/delete/crash 的完整产品链路仍未验收。
3. 生命周期沿用旧恢复语义，但新引擎实际执行与故障窗口尚未验收；有可恢复
   checkpoint 不意味着允许自动 resume。全部门禁通过前不退役旧 GA loop。
4. 统一 RAG U1–U3 尚未交付：业务结构分块与真实索引/store RPC，词法/向量
   融合、profile/generation/CAS/来源核验与冻结，以及至少 50 条人工标注评测。
   本轮没有将 U0 smoke、模型验证或数据库组件测试记为 RAG 完成。

**验收分级：组件测试通过；模型与 checkpoint 的局部真实集成通过；阶段 1
全链路和生产验收未完成。**

## 前一轮结果（保留历史）

已完成阶段 0 的代码/接口审计、框架依赖锁定、原生模型消息 DTO 与共享
fixtures，推进了阶段 1 的标准 create_agent 装配、broker 模型适配、通用
宿主工具中间件和 host-backed saver。生产 GA 仍由原 Java Runtime 执行。
没有双跑、隐藏 fallback、供应商切换、Graph 改造或对生产数据库的迁移。

工作区开始时已有 AGENT.md、V1 implementation plan、v2 README 的未提交
修改，以及未跟踪 redesign 文档；保留这些修改。本次只给 redesign 追加
进度链接，没有覆盖已有设计。未创建分支、提交、推送或 PR。

## 阶段 0 审计与模型兼容矩阵

当前本地生产库配置（仅查询 provider/format/model，不读取 key）：
`OPENCODE_ZEN / mimo-v2.6-flash-free`。这只是当前设置快照，不是冻结的
modelBinding，也不是 native tool calling 的支持证明。

| 通道 | 工具 Schema / assistant-call / tool-result | 流式参数 | 本轮结论 |
|---|---|---|---|
| Project Agent model-inference.v1 broker | 限 system/user；文本返回 | 不承载 tool delta | 保持原契约，不扩宽 |
| 现有 OpenCode transport | 文本消息；无 tools/tool_call_id 路径 | 只收文本 | 当前 GA native 路径不兼容；模型自身未验证 |
| 现有 ChatCompletionsProtocolAdapter | 请求不发 tools；tool_calls finish 判失败 | tool_calls delta 被排除 | 当前 adapter 不兼容 |
| 现有 ResponsesProtocolAdapter | 文本输入/输出；无完整 native call/result 转换 | 仅 output-text | 当前 adapter 不兼容 |
| 现有 AnthropicMessagesProtocolAdapter | 文本消息；tool_use 不输出 | input_json/tool delta 被排除 | 当前 adapter 不兼容 |
| 新 GaChatCompletionsAdapter（隔离、未挂载） | 离线 request/response 转换已验证 | 显式拒绝 stream=true | 仅非流式转换通过；未作真实供应商验收 |
| 当前配置 mimo-v2.6-flash-free | 未发送真实 native 请求 | 未验证 | 未验证，不能标记支持或模型本身不支持 |

现有冻结 OpenCode transport 不能静默增加 tools/生成参数。新 adapter
没有修改其请求体或提供第二套 production HTTP/credential/settings。
真实验收必须在 Java 侧补齐独立的 qualified native transport/broker；
Python 不能持有 key 直接完成这一步。当前没有运行中的 GA broker，新的
modelBinding/lease/budget 校验接口也尚不存在，故本轮没有 live PASS。

## 框架版本与接口

| 组件 | 锁定版本 | 已验证环境/用途 |
|---|---|---|
| Python | 3.11 本地环境 | 3.12+、Linux 尚未验收 |
| langchain | 1.4.3 | create_agent、model/tool call limit、middleware 跳转 |
| langchain-core | 1.6.6 | BaseChatModel.bind_tools、AIMessage/ToolMessage |
| langgraph | 1.2.12 | 实际同步/异步执行与 checkpoint 写入 |
| langgraph-checkpoint | 4.2.0 | BaseCheckpointSaver 接口核对 |
| jsonschema | 4.26.0 | closed Schema 参数校验 |
| langchain-ollama / ollama SDK | 1.1.0 / 0.6.3 | U0 真实本地文档/查询 embedding probe |

`agent-brain/requirements.lock` 锁定 runtime/dev 的传递依赖；Docker 安装
使用 constraints。锁文件生成不包含本地 pip-audit 等无关工具。
pip check 与带 constraints 的安装 dry-run 已通过。未验收其他平台，不
把 depends-on Python >=3.11 视为全平台兼容证明。

按已安装源码核对：create_agent graph retry_policy=()，model/start 节点
retry_policy=None；没有 RetryMiddleware/with_retry/fallback；新增模型
HTTPTransport(retries=0)，拒绝重定向且仅一次请求；宿主工具 handler
没有重试。tenacity/langsmith 是框架传递依赖，不代表启用了产品重试或
外部 tracing；产品入口接通时还须禁止外部 tracing/callback 上传。

Saver 支持 get_tuple/list/put/put_writes/delete_thread 的同步/异步 RPC；
list 带 limit/before/filter，保留 parent 与中间 writes 的负索引。
框架额外 copy/prune/delete_for_runs 继承显式 NotImplemented，未开放。
DeltaChannel 的祖先保留规则已记录，不能简单只留最新 checkpoint。

参考核对：[Agents](https://docs.langchain.com/oss/python/langchain/agents)、
[Middleware](https://docs.langchain.com/oss/python/langchain/middleware/built-in)；
具体接口以锁定环境中的安装源码与框架执行测试为依据。

## 已落地契约和阶段 1 组件

- `contracts/global-assistant/README.md`：预算、模型、执行、能力、checkpoint
  RPC、事件和 storage 方案；区别已生成 DTO 与尚未实施的宿主方案。
- 5 个 JSON Schema：执行、模型 request/response、能力 request/result。
  严格未知字段/版本/标量类型校验；执行目录 hash、messageId/history
  boundary；能力参数 hash；四角色消息与工具调用 ID 配对。
- Java `GaModelContract` + Python DTO 使用同一组模型 golden fixtures；
  非模型契约尚未声明跨语言 parity。
- `GaChatCompletionsAdapter`：原生 Schema/call/result 转换，完整 object
  参数校验；截断、重复 JSON key、非法 finish、usage 失败关闭；只输出
  typed 文本/calls/usage，reasoning 不进入 DTO。
- `BrokerChatModel`：固定 scope/modelBinding，通过独立内部 broker
  请求；不使用供应商 SDK；阶段 1 每个模型响应至多一个工具调用，防止
  create_agent ToolNode 并发副作用；未知工具拒绝。
- `build_agent`：框架唯一循环；模型/工具预算由预置 middleware 控制；
  通用描述符到 tools，中间件携带稳定 toolCallId 回宿主。没有第二套
  业务 registry 或自然语言分支；现有六项能力尚未由 Java RPC 接入。
- Schema/name 转换：简化字段 map 和 closed JSON Schema；必填、类型、
  枚举、嵌套、长度/数值约束；未知 Schema keyword 与名称冲突拒绝。
- USER_INPUT_REQUIRED 使用框架 before_model jump_to=end，测试证明结束
  当前执行、不再调用模型。不能只用 ToolNode Command(goto=end)，实际
  框架仍可能产生后续模型调用。APPROVAL_REQUIRED 阶段 1 显式失败关闭。
- `HostCheckpointSaver`：绑定 run/epoch/lease/thread/namespace，RPC
  CAS/version 桥接；安全 JSON codec 保留固定四类 LangChain 消息，不
  反序列化任意构造器/pickle，不存 provider payload/private reasoning。
  单项 payload 1 MiB，上下游 DTO/结果 256 KiB。测试内的 host stand-in
  **不证明 Java 数据库 durability、租约 CAS 或删除事务已经完成**。

## 验证

- Python full pytest：283 passed，1 个既有 Starlette/anyio deprecation
  warning。新增 16 项实际框架/边界测试，包括 native call→host result→
  follow-up、参数错误、预算、澄清结束、并行拒绝、未知工具、取消、
  HTTP 单次/禁止 redirect、Schema/name、共享 fixtures、saver sync/async、
  分页/过滤/删除、codec/hash/version/scope。
- Java Provider/Model inference 定向回归：BUILD SUCCESSFUL，包含新增
  GaNativeModelContractTest 和现有文本/流式适配测试。
- 后端 `testNonLive`：首次因 Docker/PostgreSQL 未启动导致 connection
  refused；启动现有 Docker Desktop 和 compose postgres 后重新执行，
  BUILD SUCCESSFUL（1m36s），1660 tests，0 failures/errors/skipped。
  后续新增向量 checksum 边界 helper 后，针对新增测试、native fixtures
  和全部架构门禁的定向回归也 BUILD SUCCESSFUL。
- 真实供应商 native/stream/follow-up、Java↔Python GA HTTP 链路尚未执行；
  现有文本模型评测不能替代这些门禁。

## 后续必须依次完成的门禁

1. Java 原生模型 transport 的资格验证路径：从宿主配置解析当前模型与
   凭据，核对 native tool-call/assistant/tool 消息与 stream 参数；记录
   frozen OpenCode 的单独变更证明。不支持时明确 UNSUPPORTED_AGENT_MODEL。
2. GA execution 存储与 V43+ migration：engine/modelBinding、epoch/lease、
   宿主原子预算、checkpoint CAS/完整写入、eventId 去重、删除 cascade。
   V42 executor_ownership 当前只绑定 Project Agent，不冒充 GA lease。
3. Java authenticated GA model/capability/checkpoint RPC 和 Python execution
   入口，已有 CapabilityRuntime/六项业务能力、ui.navigate 与 user-input。
   每次调用前后检验取消/epoch，宿主副作用入口原子校验。
4. 持久 saver + 消息消费边界/旧会话导入/结构化身份 + 长会话摘要、稳定
   产品事件映射。前端 HTTP/SSE 不直连 Python，公开 sequence 仍由 Java 分配。
5. 跨语言真实链路、重复启动/写入幂等/未知结果/取消/steer/重放/删除/
   crash 回归；真实模型及性能 baseline。
6. 以上通过后才退役 Java GA 循环、启用新运行引擎。旧 run 不切引擎；
   阶段 1 重启仍中断终止，自动 resume 留给后续阶段。

当前不能宣称阶段 0 真实模型门槛或阶段 1 全链路/生产迁移完成。

## U0 增量：统一 Python RAG

按最新 `UNIFIED_PYTHON_RAG_DESIGN.md` 补充阶段 0，不重做已通过的框架
验证。真实 embedding 和融合算法的目标实现只有一份 Python retrieval；
Java 仅保留业务投影/权限/候选 SQL/pgvector/job CAS/最终来源验证。

已落地：独立 retrieval.v1 strict DTO、11 份 Schema 与共享 fixtures，
search/index/store/validate/health/profile/failure、scopeGrant/workload/epoch/
deadline、向量维度/有限数值/L2/float32 checksum、来源版本/hash/位置、
索引 job/lease/CAS、明确 vectorUnavailable 和项目补充检索不可用语义。
两类 Agent 使用相同 profile/engine；通用 wire 校验在独立 wire.py，
retrieval 不依赖 GA 状态。检索契约正文见 contracts/retrieval/README.md。

`retrieval_entries` V40/V41 不复制。审计记录其 project NOT NULL、旧 scope
check、单 source unique 与缺失 profile/generation/job lease 的迁移差距。
U0 不执行迁移；U1 必须先协调旧 ON CONFLICT SQL 与同表新 generation，
再接通存储/RPC，不能新增第二套索引来回避兼容问题。

真实本地服务已验证：Ollama 0.18.0，已安装 qwen3-embedding:0.6b，digest
`ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d`。
未下载模型、未切 4B、未写数据库向量。通过薄 OllamaEmbeddings probe
调用相同 SDK，明确 truncate=false，document raw text 与 query instruct
编码共用 profile，请求并实测得到 1024 维/有限值/单位 L2 向量。

实测：模型未加载时三条文档首批约 3439.46ms；首次查询 88.90ms；热查询
64.64ms。中文改写 query 对 3 段候选的 Top1 符合预期；超长输入明确
拒绝。证据：`docs/v2/evidence/UNIFIED_PYTHON_RAG_U0_EMBEDDING.json`，复跑
入口 `agent-brain/scripts/probe_local_embedding.py`（显式脚本，不进入 CI）。
这不是 50 query 质量评估、pgvector 端到端、P50/P95 或 GPU 峰值证明。

Java 新增 RetrievalVectors 仅验证有限/1024/L2 与 IEEE754 float32 little
endian SHA-256，不计算 embedding 或实现检索算法；消费同一 Python fixture
的 checksum 测试已通过。其余 retrieval Java DTO/RPC parity、RRF parity、
source scope/frozen replay/index CAS/deletion 均仍是 U1/U2 gate。

框架接口差距也已验证：langchain-ollama 1.1.0 的 embed_documents 不暴露/
传入 truncate；SDK 0.6.3 支持。U1 采用薄 OllamaEmbeddings adapter 显式
truncate=false，避免另造 provider 或默认静默截断。首期 0.6B profile
固定为 measured digest、1024、raw-text/qwen-instruct、L2、host source
granularity；更改编码/digest/splitter/dimensions 必须换 profile/generation。

新增共享检索契约测试 15 项，Python 总数 283。U0 在本次已完成契约与
本地 embedding smoke 增量；共享 RAG 生产算法/存储桥接/两类 Agent 切换
继续按 U1–U3 推进，不把 probe 或 strict DTO 当成整个 RAG 已迁移。

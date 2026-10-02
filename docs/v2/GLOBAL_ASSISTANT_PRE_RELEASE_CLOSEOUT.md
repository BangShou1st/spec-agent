# 发布前收尾与最终待办

**口径更新（2026-10-02）**：用户明确本项目为个人学习项目，本次发布是提交并推送仓库。以下保留历史资格与证据；人工评测、运行环境根因和旧目录清理均为遗留，不阻塞本次提交。当前交付见 [本轮提交说明](GLOBAL_ASSISTANT_CHANGE_SUMMARY.md)。

日期：2026-10-02（Asia/Shanghai）。范围：运行环境与评测资格收敛；没有新增产品能力，没有切换生产。

> 后续用户已委托技术选择；具体候选环境、AI标签审核、预验收标准与后续范围见[委托技术决策](GLOBAL_ASSISTANT_RELEASE_DECISIONS.md)。该决策尚未执行候选评测，不把AI审核记为人工批准，也未改变下列历史证据和生产未切换状态。

机器可审计交付：[本轮汇总与证据指纹](evidence/GLOBAL_ASSISTANT_PRE_RELEASE_CLOSEOUT.json)。保留历史全量回归报告；本轮新增启动专项和待审拒绝门禁通过，脚本语法、冻结配置静态校验和差异空白检查通过，未重跑不受影响的测试。

| 分类 | 当前状态 | 证据/剩余动作 |
|---|---|---|
| 实现完成 | 阶段1功能、轮次过程、共享 Python RAG U1–U3已实现 | 保留原实施成果，本轮只增加准备/审阅工具和资格测试；[实施记录](GLOBAL_ASSISTANT_LANGCHAIN_IMPLEMENTATION_STATUS.md) |
| 集成通过 | 已有真实模型/HTTPS/摘要/UI/恢复证据保持有效，未重跑未受影响的门禁 | 既有1739项Java回归、221项Python及119项前端证据保持原日期与范围，不冒称本轮重新运行 |
| 本轮新增集成通过 | 候选 CPython 3.11.14：6个新Python进程启动，约65秒服务运行、12次实际混合检索 | [启动证据](evidence/GLOBAL_ASSISTANT_CANDIDATE_RUNTIME_STARTUP.json)：实际Java宿主RPC/隔离库/pgvector/Ollama；无聊天模型请求，0重试 |
| 人工待审 | 68条独立候选查询、来源标签、多来源all-of及相近无答案语义 | [审阅表](../../contracts/retrieval/evaluation/ACCEPTANCE_HUMAN_REVIEW.md)；AI静态预检查通过不等于人工批准。首轮业务质量阈值须在看结果前确定；硬权限/过期泄漏门槛为0 |
| 运行环境待确认 | 候选在本机Windows有限次数验证通过；原3.11.15仍不合格，具体根因未证实 | 部署机器/平台/打包环境需按固定构建复验，不把本机结果推广为Linux、容器或长期稳定证明 |
| 生产未授权 | NOT_RUN | 没有执行生产Flyway、环境替换、引擎切换或生产回滚；按[发布手册](GLOBAL_ASSISTANT_LANGCHAIN_RELEASE_RUNBOOK.md)等待独立授权 |
| 收尾阻塞 | 旧Git测试目录仍未删除 | 精确路径与拒绝原因见下文；没有再次尝试，没有替换工具或路径绕过 |

## 运行环境：现象、推测与候选资格

**已证实的现象**：原 uv standalone CPython 3.11.15 在Windows发生间歇性退出，代码0xC0000005；Windows Application日志将出错模块定位到python311.dll。faulthandler栈显示当时位于Ollama/Pydantic Schema导入。相同依赖版本和相同Pydantic原生模块SHA-256下，隔离CPython 3.11.14已通过既有回归及本轮有限启动验证。

**没有证实的根因**：栈位置不能证明Pydantic、Ollama或CPython哪一个组件存在缺陷；资源压力、解释器构建差异、其他本机因素都没有因果证据。减少Spring缓存仍发生一次崩溃，不能说缓存调整修复了它。改用候选构建通过也不证明原解释器普遍有缺陷。本轮未修改、重装或删除原环境，原崩溃证据保留在[原资格记录](evidence/GLOBAL_ASSISTANT_PYTHON_RUNTIME_QUALIFICATION.json)。

候选明确锁定为Windows x86_64 CPython 3.11.14，python-build-standalone **20260211**。版本、下载URL、关键二进制及依赖锁指纹见 [candidate-runtime.lock.json](../../agent-brain/candidate-runtime.lock.json)，实际环境见[manifest](evidence/GLOBAL_ASSISTANT_CANDIDATE_RUNTIME_MANIFEST.json)。准备脚本只创建独立目录；已有目录不自动修补/升级，版本或指纹不符即失败，不自动替换原.venv，不改变全局解释器/Windows注册表。

本轮第一次专项失败是**依赖不可用**：Python服务及宿主存储可用，但本地Ollama没有监听11434。失败保留在[依赖失败记录](evidence/GLOBAL_ASSISTANT_CANDIDATE_RUNTIME_OLLAMA_UNAVAILABLE.json)。启动已有Ollama并核对未变0.6B digest后，明确重新发起一次专项取得通过记录；这不是崩溃自动重启或隐藏失败。冷启动表示新解释器/模块状态，不是清空OS磁盘缓存、重启Windows或强制卸载Ollama模型。

候选服务必须使用明确的python.exe启动，禁止依赖PATH中的python或uvicorn。broker模式、宿主地址、内部密钥与本地Ollama配置见发布手册；敏感值通过原安全渠道注入，不写入证据。停止专项只清理由该专项启动的进程及日志；没有清理无法确认归属的进程。

## 开发集与独立候选集

52条 [chinese-queries.v1.json](../../contracts/retrieval/evaluation/chinese-queries.v1.json) 的角色是 **DEVELOPMENT_CALIBRATION**：参与过距离阈值0.65→0.50的选择。既有Recall@8/MRR/无答案指标是开发结果，人工复核后也不会因此成为独立验收集。

68条 [acceptance-candidate.v1.json](../../contracts/retrieval/evaluation/acceptance-candidate.v1.json) 的角色是 **INDEPENDENT_ACCEPTANCE_CANDIDATE**，尚未运行，未用于调参。包含项目表达16、标识符16、资源8、多来源8、相近无答案8、过期4、权限隔离4和无答案4。项目表达基于仓库真实设计语义，查询及资源备忘由AI编写，不是生产数据或真实用户对话。

[配置冻结](../../contracts/retrieval/evaluation/acceptance-freeze.v1.json) 保留已选profile、0.50距离阈值、RRF k=60、Top8/8000字符、逐查询scope、实现字节哈希及候选数据哈希。本轮没有根据候选命中结果调整任何算法。AI预检查只查结构、标签矛盾、开发集精确重叠及潜在歧义；[预检查证据](evidence/SHARED_RAG_ACCEPTANCE_PRECHECK.json)为STATIC_PRECHECK_PASS，执行查询数为0。精确重叠为0不证明统计独立：相同项目域、相同AI作者是已知限制。

多来源按all-of统计完整覆盖，并保留宏平均来源Recall和首个相关来源MRR。相近无答案的标签表示没有足够的**答案依据**；可能存在主题相关片段。当前zero-hit是严格检索空结果诊断，不代表已经验证最终生成回答的拒答行为。人工需明确这一评分口径，不能把主题相似等同于答案有据。

实际人工审阅可以修正标签；必须在首次执行前生成审阅后的数据版本/hash并确定首轮质量门槛，保持算法配置不变。记录实际reviewedBy/reviewedAt和逐条HUMAN_REVIEWED；只有人工完成才允许APPROVED。冻结的实现或检索参数改变后，这一候选集不能继续冒充未参与调参的独立集。

评测入口支持单独候选文件与单独输出报告，待审状态在启动Python/索引前被拒绝；本轮仅运行拒绝门禁测试，没有运行候选查询，也没有写入HUMAN_REVIEWED或APPROVED。

## 被拒绝的旧目录

`C:/Users/32962/AppData/Local/Temp/spec-agent-git-skill-13327698071011337690`

原删除动作被自动审批拒绝，理由仅为 `blocked by policy`。没有提供更详细原因。本轮没有删除、换工具、改路径或绕过；该项仍为未完成，不影响继续准备运行环境与评测材料，但不据此关闭阶段1整体验收收尾。

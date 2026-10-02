# 独立候选验收集人工审阅

状态：PENDING；68 条 AI 编写候选，尚未运行、未参与调参。52 查询属于校准开发集。

来源基于真实项目仓库文档转述，标识符是合成夹具；不是生产数据或用户对话。
请检查来源事实、问题意图、可替代来源和拒答边界；不得根据以后运行的命中结果倒推标签。
MULTI_SOURCE 是 all-of：缺少任一必需来源均不算完整覆盖。NEAR_NO_ANSWER 主题接近但无足够答案，需特别审阅。

先实际人工审阅并记录身份/时间，冻结修正后的数据 hash 和首轮验收门槛，再运行。
本工具不修改 HUMAN_REVIEWED 或 APPROVED。

## 来源

| Alias | 类型 | 来源文字 | 文档依据 |
|---|---|---|---|
| F00 | NODE | 同一共享 Question 在项目内至多有一个不可变 Answer 身份；不同路线不能各自保存另一份已完成答案。 验收夹具标识 ACC-2100。 | docs/v2/GRAPH_MODEL_V2.md |
| F01 | NODE | 模型只提出方案，Java Runtime 校验权限、事实和生命周期后决定是否持久化。 验收夹具标识 ACC-2101。 | docs/v2/AGENT_RUNTIME_ARCHITECTURE.md |
| F02 | NODE | 不可变 Answer 不原地修改；重新回答创建新的规范 Question 身份，历史继续保留。 验收夹具标识 ACC-2102。 | docs/v2/GRAPH_MODEL_V2.md |
| F03 | NODE | 路线分叉保留共享祖先节点身份，路线成员关系与语义关联分别维护。 验收夹具标识 ACC-2103。 | docs/v2/GRAPH_MODEL_V2.md |
| F04 | NODE | Focus 与 Active Route 分开；聚焦一个节点不等于切换当前业务路线。 验收夹具标识 ACC-2104。 | docs/v2/GRAPH_MODEL_V2.md |
| F05 | NODE | 语义关系不充当探索 continuation 箭头；画布显示保持简洁。 验收夹具标识 ACC-2105。 | docs/v2/GRAPH_MODEL_V2.md |
| F06 | NODE | 资源证据属于 EXTERNAL_EVIDENCE，不能仅因进入索引就变成已确认业务事实。 验收夹具标识 ACC-2106。 | docs/v2/UNIFIED_PYTHON_RAG_DESIGN.md |
| F07 | NODE | 来源撤回及权限隔离在宿主候选查询和最终来源准入阶段生效，不等待向量重建。 验收夹具标识 ACC-2107。 | docs/v2/UNIFIED_PYTHON_RAG_DESIGN.md |
| F08 | NODE | 全局助手工具目录从 Java 注册表冻结；模型自报新工具或权限不能扩权。 验收夹具标识 ACC-2108。 | docs/v2/GLOBAL_ASSISTANT_LANGCHAIN_REDESIGN.md |
| F09 | NODE | 项目必需上下文由 Runtime 保证；补充检索失败不能丢失当前路线的规范事实。 验收夹具标识 ACC-2109。 | docs/v2/PYTHON_AGENT_RUNTIME_BOUNDARY.md |
| F10 | NODE | 未知结果 UNKNOWN 的工具不自动重放或通过换 callId 重试副作用。 验收夹具标识 ACC-2110。 | docs/v2/GLOBAL_ASSISTANT_LANGCHAIN_RELEASE_RUNBOOK.md |
| F11 | NODE | Skill 暂存不自动安装或启用，安装及启用继续使用已有设置页策略。 验收夹具标识 ACC-2111。 | docs/v2/GLOBAL_ASSISTANT_LANGCHAIN_RELEASE_RUNBOOK.md |
| F12 | NODE | 历史工具活动可刷新恢复，但不会再次执行当时的页面导航。 验收夹具标识 ACC-2112。 | docs/v2/GLOBAL_ASSISTANT_TUTORIAL_REVIEW.md |
| F13 | NODE | 公开正文流不含工具参数、供应商协议或模型内部 reasoning。 验收夹具标识 ACC-2113。 | docs/v2/GLOBAL_ASSISTANT_LANGCHAIN_REDESIGN.md |
| F14 | NODE | 项目首次补充检索结果或失败状态随输入冻结，历史回放不重新搜索。 验收夹具标识 ACC-2114。 | docs/v2/PYTHON_AGENT_RUNTIME_BOUNDARY.md |
| F15 | NODE | 正在执行的 run 在重启后按阶段一中断终止；新请求可读取最后完成的 checkpoint 边界。 验收夹具标识 ACC-2115。 | docs/v2/GLOBAL_ASSISTANT_LANGCHAIN_RELEASE_RUNBOOK.md |
| R00 | RESOURCE | # 来源位置说明 😀<br><br>资源分块保留原始 UTF-16 起止位置、标题、来源版本和内容哈希。文档编号 POS-601。<br> | docs/v2/UNIFIED_PYTHON_RAG_DESIGN.md |
| R01 | RESOURCE | # 运行部署备忘<br><br>Python 通过受认证宿主 RPC 使用 Java pgvector；Python 不直接连接生产数据库。文档编号 DEP-602。<br> | docs/v2/PYTHON_AGENT_RUNTIME_BOUNDARY.md |
| R02 | RESOURCE | # 索引代际备忘<br><br>新 generation 完整准备后以 head CAS 激活；旧 grant 不能跨 generation 使用。文档编号 GEN-603。<br> | docs/v2/UNIFIED_PYTHON_RAG_DESIGN.md |
| R03 | RESOURCE | # 本地向量备忘<br><br>首期本地 embedding 使用 qwen3-embedding:0.6b，固定1024维；4B仅为后续评估候选。文档编号 EMB-604。<br> | docs/v2/UNIFIED_PYTHON_RAG_DESIGN.md |
| S00 | STALE_NODE | 过期试运行记录 OBS-OLD-0：当时计划自动接管正在运行的工具。此夹具索引后撤回规范来源。 | 隔离夹具 |
| P00 | PRIVATE_NODE | 其他项目内部方案 AUDIT-RESTRICT-0：仅另一个项目授权可见。 | 隔离夹具 |
| S01 | STALE_NODE | 过期试运行记录 OBS-OLD-1：当时计划自动接管正在运行的工具。此夹具索引后撤回规范来源。 | 隔离夹具 |
| P01 | PRIVATE_NODE | 其他项目内部方案 AUDIT-RESTRICT-1：仅另一个项目授权可见。 | 隔离夹具 |
| S02 | STALE_NODE | 过期试运行记录 OBS-OLD-2：当时计划自动接管正在运行的工具。此夹具索引后撤回规范来源。 | 隔离夹具 |
| P02 | PRIVATE_NODE | 其他项目内部方案 AUDIT-RESTRICT-2：仅另一个项目授权可见。 | 隔离夹具 |
| S03 | STALE_NODE | 过期试运行记录 OBS-OLD-3：当时计划自动接管正在运行的工具。此夹具索引后撤回规范来源。 | 隔离夹具 |
| P03 | PRIVATE_NODE | 其他项目内部方案 AUDIT-RESTRICT-3：仅另一个项目授权可见。 | 隔离夹具 |

## 查询

| ID | 类别/范围 | 查询 | 初始预期 Alias | 审阅提示 | 人工结论 |
|---|---|---|---|---|---|
| A001 | PROJECT_EXPRESSION / PROJECT | 这个问题被两个分支共用，回答以后是不是只认同一个 Answer？ | F00 | 表达来自真实项目语义，查询由 AI 编写；人工核对原文及边界。 | 待审 |
| A002 | PROJECT_EXPRESSION / PROJECT | 模型刚提的变更是谁最后检查并落库？ | F01 | 表达来自真实项目语义，查询由 AI 编写；人工核对原文及边界。 | 待审 |
| A003 | PROJECT_EXPRESSION / PROJECT | 刚确认的答案要重答，是不是得另建问题而不是覆盖旧记录？ | F02 | 表达来自真实项目语义，查询由 AI 编写；人工核对原文及边界。 | 待审 |
| A004 | PROJECT_EXPRESSION / PROJECT | 分叉后祖先节点的身份和语义关系怎么保留？ | F03 | 表达来自真实项目语义，查询由 AI 编写；人工核对原文及边界。 | 待审 |
| A005 | PROJECT_EXPRESSION / PROJECT | 我点了一下节点让它聚焦，活动路线会一起切过去吗？ | F04 | 表达来自真实项目语义，查询由 AI 编写；人工核对原文及边界。 | 待审 |
| A006 | PROJECT_EXPRESSION / PROJECT | 画布上看不到所有语义边，是设计上把它们和探索箭头分开了吗？ | F05 | 表达来自真实项目语义，查询由 AI 编写；人工核对原文及边界。 | 待审 |
| A007 | PROJECT_EXPRESSION / PROJECT | 上传的参考材料被检索到后，能直接当已确认需求吗？ | F06 | 表达来自真实项目语义，查询由 AI 编写；人工核对原文及边界。 | 待审 |
| A008 | PROJECT_EXPRESSION / PROJECT | 资源撤回以后是不是必须等重建向量才不会被搜到？ | F07 | 表达来自真实项目语义，查询由 AI 编写；人工核对原文及边界。 | 待审 |
| A009 | PROJECT_EXPRESSION / PROJECT | 模型临时说它还有一个新工具，宿主会就这样放行吗？ | F08 | 表达来自真实项目语义，查询由 AI 编写；人工核对原文及边界。 | 待审 |
| A010 | PROJECT_EXPRESSION / PROJECT | 本地检索服务断了，项目 Agent 本轮必需的路线事实还在吗？ | F09 | 表达来自真实项目语义，查询由 AI 编写；人工核对原文及边界。 | 待审 |
| A011 | PROJECT_EXPRESSION / PROJECT | 写操作结果没有确认，恢复进程会不会再执行一次？ | F10 | 表达来自真实项目语义，查询由 AI 编写；人工核对原文及边界。 | 待审 |
| A012 | PROJECT_EXPRESSION / PROJECT | 仓库里的 Skill 暂存好了之后就已经装上并启用了吗？ | F11 | 表达来自真实项目语义，查询由 AI 编写；人工核对原文及边界。 | 待审 |
| A013 | PROJECT_EXPRESSION / PROJECT | 刷新历史聊天会再次跳到当时工具打开的页面吗？ | F12 | 表达来自真实项目语义，查询由 AI 编写；人工核对原文及边界。 | 待审 |
| A014 | PROJECT_EXPRESSION / PROJECT | 工具参数正在一点点到达时，聊天正文会显示这些内部 JSON 吗？ | F13 | 表达来自真实项目语义，查询由 AI 编写；人工核对原文及边界。 | 待审 |
| A015 | PROJECT_EXPRESSION / PROJECT | 同一项目输入重放时，是沿用当初检索结果还是再查一遍？ | F14 | 表达来自真实项目语义，查询由 AI 编写；人工核对原文及边界。 | 待审 |
| A016 | PROJECT_EXPRESSION / PROJECT | 重启后是续跑刚才那个工具，还是只让新请求读取完成边界？ | F15 | 表达来自真实项目语义，查询由 AI 编写；人工核对原文及边界。 | 待审 |
| A017 | EXACT_IDENTIFIER / PROJECT | ACC-2100 | F00 |  | 待审 |
| A018 | EXACT_IDENTIFIER / PROJECT | ACC-2101 | F01 |  | 待审 |
| A019 | EXACT_IDENTIFIER / PROJECT | ACC-2102 | F02 |  | 待审 |
| A020 | EXACT_IDENTIFIER / PROJECT | ACC-2103 | F03 |  | 待审 |
| A021 | EXACT_IDENTIFIER / PROJECT | ACC-2104 | F04 |  | 待审 |
| A022 | EXACT_IDENTIFIER / PROJECT | ACC-2105 | F05 |  | 待审 |
| A023 | EXACT_IDENTIFIER / PROJECT | ACC-2106 | F06 |  | 待审 |
| A024 | EXACT_IDENTIFIER / PROJECT | ACC-2107 | F07 |  | 待审 |
| A025 | EXACT_IDENTIFIER / PROJECT | ACC-2108 | F08 |  | 待审 |
| A026 | EXACT_IDENTIFIER / PROJECT | ACC-2109 | F09 |  | 待审 |
| A027 | EXACT_IDENTIFIER / PROJECT | ACC-2110 | F10 |  | 待审 |
| A028 | EXACT_IDENTIFIER / PROJECT | ACC-2111 | F11 |  | 待审 |
| A029 | EXACT_IDENTIFIER / PROJECT | ACC-2112 | F12 |  | 待审 |
| A030 | EXACT_IDENTIFIER / PROJECT | ACC-2113 | F13 |  | 待审 |
| A031 | EXACT_IDENTIFIER / PROJECT | ACC-2114 | F14 |  | 待审 |
| A032 | EXACT_IDENTIFIER / PROJECT | ACC-2115 | F15 |  | 待审 |
| A033 | RESOURCE_CONTENT / RESOURCE | POS-601 | R00 |  | 待审 |
| A034 | RESOURCE_CONTENT / RESOURCE | 资源里的表情和换行会影响引用位置的计算口径吗？ | R00 | 需引用原文；并不要求语言生成质量评估。 | 待审 |
| A035 | RESOURCE_CONTENT / RESOURCE | DEP-602 | R01 |  | 待审 |
| A036 | RESOURCE_CONTENT / RESOURCE | 部署说明里 Python 到数据库的连接是谁管的？ | R01 | 需引用原文；并不要求语言生成质量评估。 | 待审 |
| A037 | RESOURCE_CONTENT / RESOURCE | GEN-603 | R02 |  | 待审 |
| A038 | RESOURCE_CONTENT / RESOURCE | 新索引代际激活以后，旧的检索授权还能沿用吗？ | R02 | 需引用原文；并不要求语言生成质量评估。 | 待审 |
| A039 | RESOURCE_CONTENT / RESOURCE | EMB-604 | R03 |  | 待审 |
| A040 | RESOURCE_CONTENT / RESOURCE | 这次首期本地 embedding 是什么模型和维度？ | R03 | 需引用原文；并不要求语言生成质量评估。 | 待审 |
| A041 | MULTI_SOURCE / PROJECT,RESOURCE | 共享问题只有一份答案和重答创建新问题这两条规则分别是什么？ | F00,F02 | all-of：需要覆盖每个 Alias；不能只凭一个命中算完整覆盖。 | 待审 |
| A042 | MULTI_SOURCE / PROJECT,RESOURCE | Focus 不切换路线以及语义边不作为探索箭头，各是哪条规则？ | F04,F05 | all-of：需要覆盖每个 Alias；不能只凭一个命中算完整覆盖。 | 待审 |
| A043 | MULTI_SOURCE / PROJECT,RESOURCE | 检索故障不丢必需事实和历史输入不重检索，这两条怎么分别保证？ | F09,F14 | all-of：需要覆盖每个 Alias；不能只凭一个命中算完整覆盖。 | 待审 |
| A044 | MULTI_SOURCE / PROJECT,RESOURCE | 重启中断旧 run 和 UNKNOWN 写不重放分别有哪些边界？ | F15,F10 | all-of：需要覆盖每个 Alias；不能只凭一个命中算完整覆盖。 | 待审 |
| A045 | MULTI_SOURCE / PROJECT,RESOURCE | 来源引用位置怎样保留，撤回后又在哪两个准入阶段生效？ | R00,F07 | all-of：需要覆盖每个 Alias；不能只凭一个命中算完整覆盖。 | 待审 |
| A046 | MULTI_SOURCE / PROJECT,RESOURCE | Python 到宿主存储的连接方式，以及新索引代际激活的条件分别是什么？ | R01,R02 | all-of：需要覆盖每个 Alias；不能只凭一个命中算完整覆盖。 | 待审 |
| A047 | MULTI_SOURCE / PROJECT,RESOURCE | Skill 暂存不启用和模型不能临时扩权分别由哪些规则限制？ | F11,F08 | all-of：需要覆盖每个 Alias；不能只凭一个命中算完整覆盖。 | 待审 |
| A048 | MULTI_SOURCE / PROJECT,RESOURCE | 正文不显示协议参数与历史刷新不重放导航分别是什么规则？ | F13,F12 | all-of：需要覆盖每个 Alias；不能只凭一个命中算完整覆盖。 | 待审 |
| A049 | NEAR_NO_ANSWER / PROJECT,RESOURCE | 已完成的 Answer 历史保留多少年？ | 空（不应返回） | 与已有主题接近，但所问数量/期限/策略没有来源支持；主题相关片段不等于答案。 | 待审 |
| A050 | NEAR_NO_ANSWER / PROJECT,RESOURCE | Focus 切换操作最多允许每秒点几次？ | 空（不应返回） | 与已有主题接近，但所问数量/期限/策略没有来源支持；主题相关片段不等于答案。 | 待审 |
| A051 | NEAR_NO_ANSWER / PROJECT,RESOURCE | 参考资源可以上传多少个文件？ | 空（不应返回） | 与已有主题接近，但所问数量/期限/策略没有来源支持；主题相关片段不等于答案。 | 待审 |
| A052 | NEAR_NO_ANSWER / PROJECT,RESOURCE | 来源撤回后自动恢复的等待时间是多少？ | 空（不应返回） | 与已有主题接近，但所问数量/期限/策略没有来源支持；主题相关片段不等于答案。 | 待审 |
| A053 | NEAR_NO_ANSWER / PROJECT,RESOURCE | UNKNOWN 操作需要多少位审批人签名才能重试？ | 空（不应返回） | 与已有主题接近，但所问数量/期限/策略没有来源支持；主题相关片段不等于答案。 | 待审 |
| A054 | NEAR_NO_ANSWER / PROJECT,RESOURCE | 索引 generation 允许同时保留多少代？ | 空（不应返回） | 与已有主题接近，但所问数量/期限/策略没有来源支持；主题相关片段不等于答案。 | 待审 |
| A055 | NEAR_NO_ANSWER / PROJECT,RESOURCE | 本地 embedding 每分钟有多少免费调用配额？ | 空（不应返回） | 与已有主题接近，但所问数量/期限/策略没有来源支持；主题相关片段不等于答案。 | 待审 |
| A056 | NEAR_NO_ANSWER / PROJECT,RESOURCE | Skill 安装成功后默认开放哪些网络域名？ | 空（不应返回） | 与已有主题接近，但所问数量/期限/策略没有来源支持；主题相关片段不等于答案。 | 待审 |
| A057 | STALE_VERSION / PROJECT | OBS-OLD-0 | 空（不应返回） | 索引完成后撤回规范源；派生条目故意保留。 | 待审 |
| A058 | STALE_VERSION / PROJECT | OBS-OLD-1 | 空（不应返回） | 索引完成后撤回规范源；派生条目故意保留。 | 待审 |
| A059 | STALE_VERSION / PROJECT | OBS-OLD-2 | 空（不应返回） | 索引完成后撤回规范源；派生条目故意保留。 | 待审 |
| A060 | STALE_VERSION / PROJECT | OBS-OLD-3 | 空（不应返回） | 索引完成后撤回规范源；派生条目故意保留。 | 待审 |
| A061 | PERMISSION_ISOLATION / PROJECT | AUDIT-RESTRICT-0 | 空（不应返回） | 不同项目；未给本项目 grant，禁止泄漏。 | 待审 |
| A062 | PERMISSION_ISOLATION / PROJECT | AUDIT-RESTRICT-1 | 空（不应返回） | 不同项目；未给本项目 grant，禁止泄漏。 | 待审 |
| A063 | PERMISSION_ISOLATION / PROJECT | AUDIT-RESTRICT-2 | 空（不应返回） | 不同项目；未给本项目 grant，禁止泄漏。 | 待审 |
| A064 | PERMISSION_ISOLATION / PROJECT | AUDIT-RESTRICT-3 | 空（不应返回） | 不同项目；未给本项目 grant，禁止泄漏。 | 待审 |
| A065 | NO_ANSWER / PROJECT | 下一季度客户接入方案的负责人是谁？ | 空（不应返回） | 看似真实业务请求，但候选语料没有相应事实。 | 待审 |
| A066 | NO_ANSWER / PROJECT | 订单退款接口的具体状态码是什么？ | 空（不应返回） | 看似真实业务请求，但候选语料没有相应事实。 | 待审 |
| A067 | NO_ANSWER / PROJECT | 移动端离线同步冲突最终由哪位运营审批？ | 空（不应返回） | 看似真实业务请求，但候选语料没有相应事实。 | 待审 |
| A068 | NO_ANSWER / PROJECT | 该项目采购合同的准确续费金额是多少？ | 空（不应返回） | 看似真实业务请求，但候选语料没有相应事实。 | 待审 |

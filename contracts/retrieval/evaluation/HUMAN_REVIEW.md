# 统一 RAG 人工标签审阅表

数据角色：**DEVELOPMENT_CALIBRATION**。这52条查询参与过检索阈值校准；本表用于开发标签复核，即使人工批准也不构成独立验收。独立候选集见 [ACCEPTANCE_HUMAN_REVIEW.md](ACCEPTANCE_HUMAN_REVIEW.md)。

状态：PENDING。来源和初始标签由 AI 编写；本表不表示人工已经批准。

请先独立核对来源，再判断每条查询的 expectedAliases。无答案、过期与隔离项不能以本轮实际结果倒推标签。修改 JSON 后记录真实 reviewedBy/reviewedAt，并逐条设置 HUMAN_REVIEWED。复验方法见发布手册。

来源类型含义：NODE 为本项目规范业务节点；RESOURCE 通过原始资源分块投影；STALE_NODE 在索引完成后仅撤回规范源，故查询必须拒绝陈旧派生条目；PRIVATE_NODE 属于另一个项目，不在本项目 grant 中。对照是已有 Java lexical/Noop 检索，不是另一套 embedding 索引。

## 来源夹具

| Alias | 来源类型 | 原文 |
|---|---|---|
| F00 | NODE | 订单导出格式为 CSV，UTF-8 编码。接口编号 REQ-100。 |
| F01 | NODE | 上传文件限制为 20 MiB，超过后返回提示。规则编号 REQ-101。 |
| F02 | NODE | 通知发送失败最多重试 3 次，间隔 10 秒。规则编号 REQ-102。 |
| F03 | NODE | 操作审计日志保留 180 天，到期安全删除。规则编号 REQ-103。 |
| F04 | NODE | 登录会话闲置 30 分钟后失效。规则编号 REQ-104。 |
| F05 | NODE | 金额保存到小数点后 2 位，不使用浮点数计算。规则编号 REQ-105。 |
| F06 | NODE | 重复请求通过 Idempotency-Key 合并，不重复执行写操作。规则编号 REQ-106。 |
| F07 | NODE | 用户取消后不得继续追加回答正文。规则编号 REQ-107。 |
| F08 | NODE | 检索仅访问当前项目授权来源。规则编号 REQ-108。 |
| F09 | NODE | 引用包含来源 ID、版本和原始位置。规则编号 REQ-109。 |
| F10 | NODE | 资源解析失败保留错误状态，不伪造提取成功。规则编号 REQ-110。 |
| F11 | NODE | 派生缓存的 TTL 为 300 秒。规则编号 REQ-111。 |
| F12 | NODE | 工具执行的总超时预算为 15 秒。规则编号 REQ-112。 |
| F13 | NODE | 精确接口路径是 /v1/reports/export。规则编号 REQ-113。 |
| F14 | NODE | Agent 必需 lineage 由业务 Runtime 保证。规则编号 REQ-114。 |
| F15 | NODE | 首次输入冻结后不随索引变化而重检索。规则编号 REQ-115。 |
| R00 | RESOURCE | # 资源接口<br><br>导出端点为 /resources/export-A，返回压缩 CSV 文件。<br> |
| R01 | RESOURCE | # 备份文档<br><br>备份恢复指南编号 RESTORE-B2，每周日 02:00 备份。<br> |
| R02 | RESOURCE | # 保留说明 😀<br><br>归档资源保留 90 天；管理员可以撤回，文档编号 ARCHIVE-C3。<br> |
| R03 | RESOURCE | # 导入资料<br><br>资源文档 IMPORT-D4，上传支持 TXT 和 Markdown。<br> |
| S00 | STALE_NODE | 已撤回旧版本规则 OLD-S00。旧值不可继续用作事实。 |
| S01 | STALE_NODE | 已撤回旧版本规则 OLD-S01。旧值不可继续用作事实。 |
| S02 | STALE_NODE | 已撤回旧版本规则 OLD-S02。旧值不可继续用作事实。 |
| S03 | STALE_NODE | 已撤回旧版本规则 OLD-S03。旧值不可继续用作事实。 |
| I00 | PRIVATE_NODE | 另一项目的私有退款账号标识 ISOLATED-00，仅授权该项目使用。 |
| I01 | PRIVATE_NODE | 另一项目的私有退款账号标识 ISOLATED-01，仅授权该项目使用。 |
| I02 | PRIVATE_NODE | 另一项目的私有退款账号标识 ISOLATED-02，仅授权该项目使用。 |
| I03 | PRIVATE_NODE | 另一项目的私有退款账号标识 ISOLATED-03，仅授权该项目使用。 |

## 待审查询

| ID | 类别 | 查询 | 初始预期 Alias | 人工结论 |
|---|---|---|---|---|
| Q001 | CHINESE_PARAPHRASE | 把订单下载成表格该选哪种文件格式？ | F00 | 待审 |
| Q002 | CHINESE_PARAPHRASE | 导出来的订单文件是什么编码？ | F00 | 待审 |
| Q003 | EXACT_IDENTIFIER | REQ-100 | F00 | 待审 |
| Q004 | CHINESE_PARAPHRASE | 最多可以传多大的文件？ | F01 | 待审 |
| Q005 | CHINESE_PARAPHRASE | 附件太大时系统怎样反馈？ | F01 | 待审 |
| Q006 | EXACT_IDENTIFIER | REQ-101 | F01 | 待审 |
| Q007 | CHINESE_PARAPHRASE | 消息发不出去会再试几回？ | F02 | 待审 |
| Q008 | CHINESE_PARAPHRASE | 通知两次重发之间等多久？ | F02 | 待审 |
| Q009 | EXACT_IDENTIFIER | REQ-102 | F02 | 待审 |
| Q010 | CHINESE_PARAPHRASE | 用户操作的记录存多久？ | F03 | 待审 |
| Q011 | CHINESE_PARAPHRASE | 审计资料过期怎么处理？ | F03 | 待审 |
| Q012 | EXACT_IDENTIFIER | REQ-103 | F03 | 待审 |
| Q013 | CHINESE_PARAPHRASE | 多久不操作会被退出登录？ | F04 | 待审 |
| Q014 | CHINESE_PARAPHRASE | 空闲会话的有效期是多少？ | F04 | 待审 |
| Q015 | EXACT_IDENTIFIER | REQ-104 | F04 | 待审 |
| Q016 | CHINESE_PARAPHRASE | 钱数要精确到第几位小数？ | F05 | 待审 |
| Q017 | CHINESE_PARAPHRASE | 财务计算允许用浮点表示吗？ | F05 | 待审 |
| Q018 | EXACT_IDENTIFIER | REQ-105 | F05 | 待审 |
| Q019 | CHINESE_PARAPHRASE | 网络重试会再次写入数据吗？ | F06 | 待审 |
| Q020 | CHINESE_PARAPHRASE | 避免重复提交用什么请求头？ | F06 | 待审 |
| Q021 | EXACT_IDENTIFIER | REQ-106 | F06 | 待审 |
| Q022 | CHINESE_PARAPHRASE | 按了停止还会继续显示答案吗？ | F07 | 待审 |
| Q023 | CHINESE_PARAPHRASE | 终止对话后的正文有什么约束？ | F07 | 待审 |
| Q024 | EXACT_IDENTIFIER | REQ-107 | F07 | 待审 |
| Q025 | EXACT_IDENTIFIER | REQ-108 | F08 | 待审 |
| Q026 | EXACT_IDENTIFIER | REQ-109 | F09 | 待审 |
| Q027 | EXACT_IDENTIFIER | REQ-110 | F10 | 待审 |
| Q028 | EXACT_IDENTIFIER | REQ-111 | F11 | 待审 |
| Q029 | EXACT_IDENTIFIER | REQ-112 | F12 | 待审 |
| Q030 | EXACT_IDENTIFIER | REQ-113 | F13 | 待审 |
| Q031 | EXACT_IDENTIFIER | REQ-114 | F14 | 待审 |
| Q032 | EXACT_IDENTIFIER | REQ-115 | F15 | 待审 |
| Q033 | RESOURCE_CONTENT | /resources/export-A | R00 | 待审 |
| Q034 | RESOURCE_CONTENT | 资源资料下载的文件是什么格式？ | R00 | 待审 |
| Q035 | RESOURCE_CONTENT | RESTORE-B2 | R01 | 待审 |
| Q036 | RESOURCE_CONTENT | 哪一天几点进行备份？ | R01 | 待审 |
| Q037 | RESOURCE_CONTENT | ARCHIVE-C3 | R02 | 待审 |
| Q038 | RESOURCE_CONTENT | 归档的外部资料保留多长时间？ | R02 | 待审 |
| Q039 | RESOURCE_CONTENT | IMPORT-D4 | R03 | 待审 |
| Q040 | RESOURCE_CONTENT | 输入资料接受哪些文档格式？ | R03 | 待审 |
| Q041 | STALE_VERSION | OLD-S00 | 空（不应返回） | 待审 |
| Q042 | STALE_VERSION | OLD-S01 | 空（不应返回） | 待审 |
| Q043 | STALE_VERSION | OLD-S02 | 空（不应返回） | 待审 |
| Q044 | STALE_VERSION | OLD-S03 | 空（不应返回） | 待审 |
| Q045 | NO_ANSWER | 火星轨道 MARS-Z999 的速度是多少？ | 空（不应返回） | 待审 |
| Q046 | NO_ANSWER | 量子纠缠实验 QBIT-X88 的样本数是多少？ | 空（不应返回） | 待审 |
| Q047 | NO_ANSWER | 古代陶器 ANCIENT-T77 的出土坐标是什么？ | 空（不应返回） | 待审 |
| Q048 | NO_ANSWER | 太阳能发电 SOLAR-Y66 的效率是多少？ | 空（不应返回） | 待审 |
| Q049 | PERMISSION_ISOLATION | ISOLATED-00 | 空（不应返回） | 待审 |
| Q050 | PERMISSION_ISOLATION | ISOLATED-01 | 空（不应返回） | 待审 |
| Q051 | PERMISSION_ISOLATION | ISOLATED-02 | 空（不应返回） | 待审 |
| Q052 | PERMISSION_ISOLATION | ISOLATED-03 | 空（不应返回） | 待审 |

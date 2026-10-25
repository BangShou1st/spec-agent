---
status: Historical
authority: Historical Reference Only
---

# RAG-D4-R2：D4_PARTIAL

2026-10-23，本地 `dev`，基线 `b880a1ac9f89884724660fd654b64b387f884b27`。**项目查询的结构化引用已交付；Gemini 全局助手的完整原生工具协议尚未取得资格。** 后者不阻塞已验收的项目引用链路，但阻塞完整 Gemini 工具链收口。未 push、修改 main、创建 PR、部署、连接业务数据库或重跑 D3 正式候选。最终提交 HEAD 见交付回复及 `git log -1`。

## 引用链路与最小修改

实际路径：浏览器 NodeInspector → NodeQuery API/RunWorker → 共享 Python 检索与冻结 AgentInputSnapshot → Python Brain/Java Model Broker → Gemini → 既有 Java proposal/source/policy 校验 → 原子终态事件 → NodeQuery 结果 API → 既有 ProjectResourceList → 再核验并定位宿主节点。

| 修改位置 | 必要性 |
|---|---|
| `AgentRunTerminalizationService`、`NodeQueryService` | 复用 `RESPOND_MESSAGE`，在同一终态事务持久化真实校验后的 `sourceRefs`；不新增引用存储或 Graph 副作用 |
| `NodeQuerySourcePresentation`、`RetrievalStore.currentCitation`、`NodeQueryRunController` | 以冻结 allowlist 和当前 Java 来源身份、项目权限、撤回状态、来源策略、版本/内容生成既有 SOURCE 卡片；错误显式区分 UNAVAILABLE/VALIDATION_FAILED，隐藏回答和引用；保留原审计 |
| NodeInspector、workspace API/store/proposals、ProjectResourceList | 展示 API 核验结果；点击前再次读取核验，阻止核验中第二次点击跳过等待；失权/读取失败清除缓存，丢弃旧项目迟到响应。仅显式启用定位的宿主呈现按钮，兼容 GA 原有使用方 |
| `CustomNativeToolQualification`、GA qualification/resolver/既有 Chat Completions transport | 精确地址+模型的显式资格，默认空；冻结修订号匹配；复用现有 adapter/HTTP 安全层。未取得资格的请求继续由 `requireTextOnly` 拒绝，无 Python Key、模拟工具、重试或新 Agent loop |
| 定向测试、opt-in live 驱动、固定输入及两份规范 | 增加真实隔离数据库安全断言、产品可见验收和真实协议观察；文档说明当前能力与未取得资格的边界 |

历史仅正文的结果保持 LEGACY，不解析正文中的任意引用串。Answer 未改写，冻结输入同快照回放不重新检索。当前不可用来源仍留在审计中，不作为可访问引用展示。

## 真实验收

- **A 通过**：在隔离项目实际通过浏览器填写固定问题，Gemini 一次推理给出 **31 天、730 天**及真实 `resource-chunk:<id>:0`。结构化引用进入终态事件；API 为 AVAILABLE，卡片包含宿主版本、片段位置和真实节点 ID，点击后 Inspector 定位该资源。
- **B 通过**：撤回同一资源后，实际 API 返回 UNAVAILABLE、正文 null、引用空；浏览器点击旧卡片触发重新核验，留在原锚节点并显示来源不可用，原回答/卡片消失。审计 payload 与冻结回放不变。失权由真实成员授权/撤权数据库测试覆盖。
- **C 通过**：正常本地 Ollama 完成真实索引和检索；冻结 metadata 为 `vectorUnavailable=false`、`supplementalRetrievalUnavailable=false`。保持 `qwen3-embedding:0.6b` 和 Profile `26810df4b6b4e7d0d2977bcc57175fcf7e944ebe99f447b5c4e690cb08b8c047`。
- **D 未取得资格**：现有 Java Gateway/Broker、Python `create_agent` 实际获得 Gemini 原生 `project_get_summary` tool call；后续模型请求发生 `PROVIDER_REQUEST_ERROR`，Python 观察到 broker 502，没有最终回答。首次提供商错误细节、工具 Observation 详细内容未在清理自有会话前单独留存，**不能据此认领完整宿主执行/Observation/回答闭环，也不能断言具体供应商根因**。后续需补齐协议诊断、必要历史适配及独立复验；共享配置不登记 Gemini 资格。本次没有继续改协议强求通过。

两个新固定真实场景为 **AI 审阅合成验收**，不是人工标注或统计独立实验。项目回答 **1/1**，必需事实 **2/2**，必需资源引用 **1/1**；无依据断言 **0/1 项目场景（AI 审阅）**。R2 未新增拒答场景，拒答率不计。R102 原始失败和 D4/R1 首次分数原样保留，不能用本次不同输入宣称 R102 修复成功。

## 回归与安全

最新受影响 Java 去重 **44 / 0 failures / 0 errors / 0 skipped**（含新增9项引用数据库测试），最终源码完整架构选择 **52 / 0 / 0 / 0**；另跑 `testOffline` 只选择14项 Agent 边界检查，不能将它认作完整数据库回归。前端 **108 / 0 / 0**、类型检查和生产构建通过；Python **63 / 0 / 0**。Live JUnit 驱动完成1项、失败0、跳过0，**其绿色只表示 A/B/C 断言完成，D 的独立失败明确记录，绝非工具链 PASS**。

隔离 PostgreSQL 中，未经用户确认的检索推断只产生待批准提案：confirmed 写入 **0**，重复提交第二次 Graph 副作用 **0**。覆盖旧正文、伪造引用、撤回、内容失配、撤权、必需谱系来源策略变更、原子终态和冻结回放；覆盖范围内无效引用展示/来源安全违规 **0**，不外推为完整 Private 身份或跨 Route 矩阵认证。

初次确定性定向执行为38项、2项失败：撤权直接调用抛出类型化404与断言不符，Mockito 重设调用了旧 Answer 的 null 参数。修复夹具后复测；原日志保留。真实场景输入/断言预先冻结，首次观察未覆盖、改标签、重试或增加裁判调用。真实观察后仅增强后续驱动的失败诊断留存、补齐确定性来源策略/组件使用方门禁，未追加真实复测。

## 调用、费用与限制

共 **4 次**底层 Gemini 请求：兼容探测1、GA原生2（其中后续失败1）、项目查询1；重试0。已返回用量：输入 **5,508** / 输出 **256**，共 **5,764 tokens**；探测和失败请求 tokens 未由现有记录提供，不能把该数当完整总账。成功推理调用 n=2，延迟1,430/1,610 ms，线性插值 **P50 1,520 / P95 1,601 ms**，不含探测/失败，样本不足以作性能结论。项目 worker 总耗时 **3,120 ms**，UI提交至首个含回答 API 返回 **4,800 ms**；未测 DOM 首次绘制或首 token。免费层级已核对，预计额外 **USD 0**，API 不返回实付账单，实际费用未获账单凭证。

桌面真实展示和点击已验收；390px 窄屏工作区出现布局截断，本轮不扩展工作区响应式重构。正常构建仍提示大 chunk。未覆盖完整 Private/跨 Route/多 Worker/确认后 Graph 写入、所有引用来源类型、任意陈述语义蕴含与 Gemini SSE。

仅使用自建 `spec-agent-rag-d4-r2-20261023` / 55435 / `spec_agent_p3b_live_rag_d4_r2_20261023`；凭据已通过宿主清空并验证剩余0，容器停止保留，自有 Vite 已停止，BSK 无剩余会话。用户原有 `SHARED_RAG_ACCEPTANCE_PRECHECK.json` 的 blob `2238c220801ec870658f511c78e4bafcdaf9c23d` 保持不变，无关未跟踪文件保留。建议评审本地引用交付改动；Gemini 工具资格继续关闭，等待独立协议适配验收后再整体关闭 D4。

机器摘要、原始证据路径和 hash 见 [verification.json](verification.json)。原始模型、失败记录、API、浏览器截图及网络捕获均保留在 `scratch/rag-d4-r2/`，不提交重型报告。

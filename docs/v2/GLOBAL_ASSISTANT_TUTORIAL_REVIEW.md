# LangChain 教程与全局助手过程展示评审

> 日期：2026-10-02。只读检查教程与当前源码后形成设计建议；未启动教程、未做浏览器视觉验收、未修改业务代码或生产设置。
> 本文补充学习参考与后续展示设计，不改变阶段 1 剩余验收门槛，不表示新增功能已实现。

## 1. 实际检查范围与发现

教程根目录：`E:/project/LangChain全家桶开发实战教程`。检查了资料中的第 2/3 章 notebook 源码、marry-ai 消息与工具组件、mail-friend 前端、私厨前端及 app API 骨架。同时对照旧智扫通与当前全局助手的事件投影、时间线及实施记录。

| 样例 | 源码证据 | 借鉴价值与限制 |
|---|---|---|
| marry-ai | `src/components/thread/messages/ai.tsx` 分别渲染 AI 与 ToolMessage；`tool-calls.tsx` 展示工具名、参数、结果及展开收起 | 工具执行可见性较完整；检查到的正文提取只取 text block，不能据此认定具有完整 reasoning 面板 |
| marry-ai | `src/providers/Stream.tsx` 使用 LangGraph SDK `useStream`，读取历史并处理自定义 UI 事件 | 可以参考状态驱动展示；其直接连接 LangGraph server 的接法不直接移植到我们的 Java 宿主边界 |
| EmailFriend notebook | `stream_mode=["messages", "updates"]`；`HumanInTheLoopMiddleware` 在 send_email 前中断；从 AgentState 查 interrupt | 展示正文、步骤与等待确认是不同信号；我们的审批必须沿用既有宿主策略 |
| Middleware notebook | reasoning 布尔值用于动态选择模型；另有动态工具、提示词示例 | 这是模型选择示例，不能当作前端已经展示思考文本的证据 |
| memory notebook | InMemorySaver、SqliteSaver、SummarizationMiddleware 示例 | 适合理解框架记忆；不能替代宿主租约、事务、冻结事实与摘要验收 |
| 私厨 / app | 前端有正文 Markdown 与 loading/streaming；`app/api/v1/chat.py` 三个端点仍为 pass | 部分材料是待补教学骨架，不能整体当成生产完成实现 |
| 旧智扫通 | `ReactAgent.execute_stream` 用 values 取最后一条消息的 content；UI 接收后逐字符 sleep 输出 | 会把不同角色的中间内容连续展示，因此显得过程丰富；不是严格的原生 token 流或独立推理通道 |

以上结论只针对本地检查到的实现。未以课程名称或 ReAct 概念推断模型内部思考已经可见。

## 2. 我们已有的能力与明确缺口

当前公开事件已有 STATUS、TOOL_STARTED/COMPLETED/FAILED、ANSWER_*、USER_INPUT_REQUIRED、APPROVAL_REQUIRED、UI_ACTION 与运行终态。`ToolActivityItem.vue` 已有工具名、状态、参数摘要、耗时、结果数量/摘要与资源入口，不能说当前助手完全没有过程展示。

`ConversationTimeline.vue` 当前先渲染 messages，再渲染 activities，最后是等待信息、状态及正文草稿。消息有 runId，而单项 GaToolActivity 尚无显式 runId/toolCallId/sequence 字段；GaRunProjection 以 sequence 去重，工具完成按 capabilityId 匹配正在运行项。这里有本轮展示基础，但还不是完整的按轮次、按执行顺序组织的历史过程模型。

实施记录明确当前 broker 正文只转发 delta.content，不将 reasoning 放入 DTO、checkpoint 或公开正文。这是当前契约选择；不能据此推断模型没有推理能力，也不能静默把 reasoning 塞进正文。

## 3. 建议的展示目标

每次用户请求形成一组：用户消息 → 本轮处理过程 → 本轮回答。过程默认显示当前真实步骤，完成后可折叠为摘要；失败、取消、等待补充和待审批分别呈现。

例如“找项目并打开”的过程可以显示：

```text
处理过程
  ✓ 查找项目       找到 2 个候选
  ✓ 读取项目概要   已读取选定项目
  ✓ 打开项目       已发送导航指令

助手回答与项目入口
```

文案必须来自已发生的宿主事件。没有结果前不宣称已找到候选；没有实际读取事件不宣称已阅读；导航指令发布与浏览器成功导航要区分。没有阶段事件时只能展示一般运行状态，不能靠计时器编造进度。

工具详情展示经授权的参数摘要、结果摘要、资源链接及耗时。保留教程可展开的形式，使用产品语言；不默认复制其工具调用 ID 和原始 JSON 表格。

不要在本次改进中把被 TEXT_RESET 撤回的候选正文重新包装成永久“思考”。它仍属于可撤回草稿；若以后引入明确的对用户说明，需要独立定义消息语义。

## 4. 执行过程与模型推理分开设计

执行过程有宿主事实依据，可优先展示。若未来确实要展示供应商返回的 reasoning 或其摘要，应先验证当前模型响应是否提供、字段形态及流式行为，再明确展示、存储和摘要规则，并版本化扩展独立通道；不保证所有模型都有相同支持。

框架可传输 reasoning 不等于我们已接入，也不表示所有返回文本都是完整内部思维。界面准确标注其来源与性质；模型的说明不能替代实际执行结果。

## 5. 新流式 API 的评估项

截至本次核查，LangChain 官方流式文档推荐新应用使用 event streaming，并通过类型化 projection 分别消费消息、工具调用等事件。传统 messages/updates/custom 模式也仍有文档。[官方流式说明](https://docs.langchain.com/oss/python/langchain/streaming)

本地 `agent-brain/.venv/Lib/site-packages/langgraph/pregel/main.py` 有 stream_events 入口，`langgraph/stream/run_stream.py` 有 interleave。当前 execution.py 仍使用 graph.stream(values)，正文增量经 broker sink 转发。源码中存在新 API 只说明可以做兼容性验证，不证明当前 BrokerChatModel、checkpoint saver 和工具适配已经支持目标行为。

后续先做隔离验证：现有同一工具场景在新 projection 上的事件、正文和最终状态与当前实现一致；取消、草稿 reset、预算、持久化和副作用门禁继续通过。只有减少适配复杂度或提供真实收益时才迁移，禁止为展示重复启动第二个 Agent 或模型请求。

宿主工具事件仍是业务执行权威。LangChain 工具事件不能造成重复公开活动、把参数增量当作可执行输入，或把内部框架状态直接透传产品。

## 6. 实施顺序及验收

继续完成阶段 1 的真实 Git 故障/清理窗口、真实长摘要和生产 UI/迁移恢复验收。当前 UI 验收可以核对已有过程是否实际可见；本评审提出的新展示作为独立后续工作，不静默追加为已冻结阶段的完成条件。

后续改进先确定按 run 的展示模型，复用现有公开事件及消息 runId；缺少可靠关联时补明确契约，不能猜测关联。再实现轮次分组、活动顺序、折叠摘要和历史重放。模型阶段提示或 reasoning 属于单独的契约扩展。

必要验收：两轮过程不串轮；同一工具多次调用不串结果；重连不重复；刷新后过程归属准确；草稿撤回有效；终态后无追加；取消/失败不残留假执行中；等待信息与审批不混淆；已有运行边界及测试保持通过。

共享 RAG U1–U3 仍按原设计推进。本次发现不要求换模型、重开阶段 0、直接连接 LangGraph 服务或重写 Java 项目 Runtime。

## 已授权实现与验证更新（2026-10-02）

用户后来明确授权本轮实现按轮次展示，现已落地“用户请求 → 本轮处理过程 → 本轮回答”。宿主 runId/toolCallId/sequence 是唯一关联依据；折叠/历史刷新只重放工具公开状态和结果来源，不重放导航、正文草稿或内部协议。取消和失败的未确认工具显示 interrupted。真实浏览器已验证重连、刷新、不重复、不串轮、撤回草稿、导航及来源卡片。

没有新增模型 reasoning 通道，没有虚构思考、重复启动 Agent 或改用新的 streaming API。具体证据与边界见 [实施记录](GLOBAL_ASSISTANT_LANGCHAIN_IMPLEMENTATION_STATUS.md) 当前清单，生产验收仍未执行。

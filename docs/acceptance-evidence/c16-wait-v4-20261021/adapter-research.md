# C16 异步适配路径调研

本轮只读检查源码，未查询当前数据库配置、连接外部服务、执行检索任务或调用模型。

| 现有能力 | 真实代码证据 | 结论 |
| --- | --- | --- |
| resource.extract_text | capability/ResourceExtractTextCapability.java，直接读取 RESOURCE 节点中的已有文本并返回有界摘录 | 同步读取；加线程/延时不能使其成为有持久任务身份的异步能力，不选作生产演示 |
| MCP 工具 | mcp/provider/McpToolCapabilityProvider.java:120；mcp/runtime/McpConnectionRuntime.java:127；mcp/transport/McpClientFactory.java:185 | 当前项目调用链同步 callTool 并等待 CallToolResult；尚无 tasks 提交、持久 taskId、完成事件或可靠恢复桥接。本轮没有探测真实 MCP Server，不能宣称存在可用异步工具 |
| 检索资源投影、索引任务/代际 | retrieval/protocol/RetrievalSourceJobs.java 的 createSplitJob/validateGrant；RetrievalIndexJobs.java 的 commit/failed/activate；持久 retrieval_index_jobs、scope_grants、version、lease、deadline、generation | 已有真正的异步工作负载及状态/版本栅栏，最适合作为下一条实际桥接路径；不能直接把它的 PENDING 与 capability PENDING 混用 |

选择：新增只读 `retrieval.await_generation` 类能力，订阅**已经由有权用户请求的项目索引代际**，不让 Agent 悄悄启动重建，也不发起额外 embedding 请求。名称为下一步设计建议，本轮没有注册该描述符或生产适配器。

最小落地组件：

1. 有界输入 generationRef，解析为持久 rebuild/generation，复核 project/org、原请求 actor、所需权限及当前 revision。保留现有服务工作负载 scope grant/lease；内部服务 token 不能冒充用户授权。
2. 持久记录 invocationId/key ↔ project/generation/source version/委托主体，提供唯一幂等绑定。不得按进程内 Future/timer 推导任务存在。
3. 在 activate/failed 等真实事务点，以中立事件/outbox 记录 terminal 事实；不能引入 retrieval ↔ capability 包环。消费者加载绑定和授权，再调用 CapabilityRuntime.completeDeferred。源事件与 capability 登记早晚均需可靠重投。
4. 实现 DeferredCapabilityAdapter 的提交与完成授权检查；无效、被替代或撤权代际变成明确失败/失效，而不是等待到无限期。等待期限不能超过底层任务授权/有效期限。
5. 在授权隔离库及真实工作进程验证提交/绑定/事件事务、崩溃窗口、重投、撤权、索引代际切换及终态唯一性。

尚缺：上述业务身份/授权委托、可靠事件桥接和真实环境验收。Embedding/provider 配置可用性本轮未检查。已有代码基础使该路径可以实施，但不构成已可用的生产适配器；不能据本轮 Host 测试宣称完整异步 WAIT 已打通。

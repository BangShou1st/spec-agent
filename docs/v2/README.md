# Spec Agent V2 设计索引

> Status: **Target architecture / product contract**  
> Current production code is still the Phase 8 workflow-oriented runtime until the migration plan is implemented.

本文档是 `docs/v2` 的入口和权威关系说明。V2 的目标不是推翻现有 Graph Runtime，而是在其上演进出一个高内聚、低耦合、可验证、可扩展的 Graph Reasoning Agent。

## 1. 设计总原则

1. **Graph is the source of truth.** Node、Route、Answer、Snapshot、Operation History 等运行时事实由应用 Runtime 持有。
2. **Model proposes. Runtime validates. Runtime persists. Runtime owns history.** LLM 永远不能直接写数据库或绕过权限、生命周期、路线和历史约束。
3. **Context is lineage, not global chat history.** 当前节点/路线的上下文必须被选择和冻结，不能把整个项目历史当聊天记录塞给模型。
4. **Agent decides what; Capability decides how.** Planner 只选择动作；Skill、MCP、内部服务、外部 API 由 Capability Runtime 解析执行。
5. **Advisor by default.** 默认顾问模式；自动驾驶只允许策略明确的低风险动作。用户意图变化、破坏性操作和外部副作用必须受到更严格审批。
6. **Do not overfit to the current questionnaire workflow.** Question 只是交互节点的一种；项目名不是需求；新增文件/图片/代码能力不能通过堆业务 Agent 或业务 Action 实现。
7. **Visible Graph stays simple.** Canvas 默认只画探索 continuation 的箭头；语义关系保留在数据层/Inspector，不把画布变成蜘蛛网。
8. **Agent loops are bounded.** 每次用户/工具事件必须有 step budget、stop condition、等待用户和失败边界，禁止自主无限循环。
9. **Latency is a product constraint.** Reflection 与 Planning 默认属于同一次 Decision Cycle，不机械拆成多个串行模型请求。
10. **History is append-preserving.** 已确认事实、不可变 Answer 和既有路线不通过原地改写来“修复”；使用新状态、补偿操作、replacement/revision/route 等机制演进。

## 2. Canonical 文档

按职责阅读：

- `AGENT_V2_OVERVIEW.md` — 产品定位、当前系统到 V2 的变化。
- `NODE_MODEL_V2.md` — Node 作为 Workspace Unit 的边界、分类、状态与扩展规则。
- `GRAPH_MODEL_V2.md` — Route、可见箭头、语义关系、共享 Node、Answer scope、连接规则。
- `UI_UX_IMPROVEMENT_PLAN.md` — Graph/Node/Route 的前端产品要求与非阻塞体验。
- `AGENT_RUNTIME_ARCHITECTURE.md` — Agent Runtime、Decision Cycle、调用预算和终止条件。
- `AGENT_STATE_MODEL.md` — AgentInputSnapshot、AgentState、Observation、Focus 与持久化边界。
- `AGENT_MEMORY_AND_CONTEXT.md` — Context 选择、局部节点询问、资源上下文和防漂移。
- `AGENT_ACTION_PROTOCOL_V2.md` — Agent 与 Runtime 之间的通用 Action Proposal 协议。
- `AGENT_AUTONOMY_MODEL.md` — Advisor / Autonomous、风险与审批策略。
- `CAPABILITY_RUNTIME.md` — Skill / MCP / Internal Tool 的统一能力层及边界。
- `GRAPH_OPERATION_HISTORY.md` — Undo / Redo、补偿操作、不可逆副作用。
- `AGENT_EVALUATION_MODEL.md` — Agent 质量、延迟、调用次数、groundedness 和防过拟合评估。
- `PYTHON_AGENT_RUNTIME_BOUNDARY.md` — Java Graph Runtime 与未来 Python Brain Adapter 的稳定边界。
- `AGENT_RUNTIME_IMPLEMENTATION_PLAN.md` — 分阶段实施顺序、兼容策略与验收门槛。

## 3. 兼容/重复文档

以下文件仅保留兼容入口，不再作为独立权威设计：

- `AGENT_ACTION_PROTOCOL.md` → `AGENT_ACTION_PROTOCOL_V2.md`
- `AGENT_STATE_SCHEMA.md` → `AGENT_STATE_MODEL.md`
- `AGENT_POLICY_ENGINE.md` → `AGENT_AUTONOMY_MODEL.md`
- `AGENT_RUNTIME_MIGRATION_GUIDE.md` → `AGENT_RUNTIME_IMPLEMENTATION_PLAN.md`

若重复文档与 Canonical 文档冲突，以本 README 指向的 Canonical 文档为准。

## 4. 当前代码必须保留的基础能力

V2 迁移不得无理由重写：

- Graph / Node persistence
- Route / Fork / replacement isolation
- immutable Answer
- AnswerPatch checkpoint / repair recovery
- ContextSnapshot lineage isolation
- SpecSnapshot
- AgentRun / trace / failure persistence
- Model Gateway / provider settings

这些是 V2 Runtime 的基础设施，不是需要删除的旧架构。

## 5. 非目标

V2 第一阶段不做：

- 为每一种 Node/文件类型创建一个 Agent；
- 多 Agent 自我聊天式编排；
- 让 Python 直接访问生产数据库；
- 把 Reflection、Critic、Planner 固定为三次独立 LLM 调用；
- 把所有 semantic relation 默认渲染到 Canvas；
- 用 arbitrary confidence threshold 代替 Runtime policy；
- 通过项目名猜测用户已经确认的目标。

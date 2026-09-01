# Spec Agent V2 设计索引

> Status: **Frozen product contract / core runtime implemented on `main`**\
> The V2 Graph Runtime + Agent Runtime (Stages A–D), the Graph V2 workspace convergence, and the Conflict Intelligence loop are implemented on `main`. This document set is the canonical semantics the code must satisfy; changing them requires the change discipline in §6.

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

**Frozen Graph V2 core contract（速览；权威定义见 `GRAPH_MODEL_V2.md`）：** Shared Node = Shared State（对 canonical Question：project-wide 至多一个 immutable Answer identity；divergence fail closed `SHARED_STATE_DIVERGENCE`；不存在 route-specific Answer selector，Focus 不选择 Answer）；unanswered Interaction Question 必须保持 Route tip（`UNANSWERED_QUESTION_HAS_CHILD`）；re-answer 创建 fresh canonical Question identity；`RESUME_QUESTION` 产品功能不存在（inactive/open Route 的未答 tip 通过激活原 Route 回答；`RESUME_ANSWER` 是独立的 runtime repair）；Focus ≠ Active；semantic relation 与 lineage 严格分离，禁止 rejoin / historical insertion；Canvas 拖拽只产生 pending relation proposal（Scheme C）；unresolved conflict 必须直接进入冲突解决（`REQUEST_USER_INPUT` 或 confirmable `CREATE_NODE (KNOWLEDGE/DECISION)`），Agent-authored DECISION 属于 `CONFIRMED_INTENT_CHANGE` 必须用户确认。

## 2. Canonical 文档

按职责阅读：

- `AGENT_V2_OVERVIEW.md` — 产品定位、当前系统到 V2 的变化。
- `NODE_MODEL_V2.md` — Node 作为 Workspace Unit 的边界、分类、状态与扩展规则。
- `GRAPH_MODEL_V2.md` — Route、可见箭头、语义关系、共享 Node、Answer scope、连接规则。
- `AGENT_RUNTIME_ARCHITECTURE.md` — Agent Runtime、Decision Cycle、调用预算和终止条件。
- `AGENT_STATE_MODEL.md` — AgentInputSnapshot、AgentState、Observation、Focus 与持久化边界。
- `AGENT_MEMORY_AND_CONTEXT.md` — Context 选择、局部节点询问、资源上下文和防漂移。
- `AGENT_ACTION_PROTOCOL_V2.md` — Agent 与 Runtime 之间的通用 Action Proposal 协议。
- `AGENT_AUTONOMY_MODEL.md` — Advisor / Autonomous、风险与审批策略。
- `CAPABILITY_RUNTIME.md` — Skill / MCP / Internal Tool 的统一能力层及边界。
- `GRAPH_OPERATION_HISTORY.md` — Undo / Redo、补偿操作、不可逆副作用。
- `AGENT_EVALUATION_MODEL.md` — Agent 质量、延迟、调用次数、groundedness 和防过拟合评估。
- `PYTHON_AGENT_RUNTIME_BOUNDARY.md` — Java Graph Runtime 与未来 Python Brain Adapter 的稳定边界。

## 3. 当前代码必须保留的基础能力

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

## 6. Change Discipline

任何后续实现若要改变已冻结决策：

1. 先更新对应 Canonical 文档；
2. 说明为何不是针对单一例子的过拟合修补；
3. 再进入实现/测试。

不要为了方便编码，静默选择一个与产品语义不同的解释。

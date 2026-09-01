# V2 Requirements Traceability

## Purpose

将昨天讨论形成的产品/Agent 要求映射到 Canonical 文档，防止后续实现遗漏或把讨论稿误当成最终设计。

| Requirement | Canonical document | Frozen decision |
|---|---|---|
| 项目名不能被当成用户目标 | `AGENT_V2_OVERVIEW.md`, `AGENT_STATE_MODEL.md` | projectTitle 仅低权重 metadata，不自动成为 objective/requirement |
| 用户可从完全空白项目开始 | `AGENT_V2_OVERVIEW.md`, `NODE_MODEL_V2.md` | 项目创建 0 模型调用；空 Workspace/空 Node 是一等入口 |
| 用户可创建空 Node 并自己写需求/想法 | `NODE_MODEL_V2.md`, `UI_UX_IMPROVEMENT_PLAN.md` | 支持 user draft Node；不依赖固定 Fork/Re-answer 按钮 |
| 从任意 Node 继续探索 | `GRAPH_MODEL_V2.md` | 可以 continuation；非 tip 源点产生 branch/Route |
| 不允许把新 Node 插入既有两节点之间改写历史 | `GRAPH_MODEL_V2.md` | append-preserving；新 continuation 形成分支 |
| 线条仍保持简单箭头 | `GRAPH_MODEL_V2.md`, `UI_UX_IMPROVEMENT_PLAN.md` | Canvas 箭头仅表示 exploration continuation；semantic relations 默认不全画 |
| Node 不再等于 Question | `NODE_MODEL_V2.md` | Node = Workspace Unit；Question 只是 Interaction subtype |
| Question Node 可回答 | `NODE_MODEL_V2.md`, `UI_UX_IMPROVEMENT_PLAN.md` | Answer 为独立 immutable record；canonical Question project-wide 至多一个 Answer identity；re-answer 创建 fresh canonical Question identity |
| 任意 Node 都可作为 AI 上下文入口 | `AGENT_MEMORY_AND_CONTEXT.md`, `UI_UX_IMPROVEMENT_PLAN.md` | local context query 基于 anchor + route lineage + relations/resources |
| Shared Node 不复制 | `GRAPH_MODEL_V2.md` | 一个 Node identity 可属于多个 Route |
| Shared Node 显示多个路线名 | `UI_UX_IMPROVEMENT_PLAN.md` | 用 friendly route labels，不用“2 条路线”式数据库文案 |
| Shared Question 正确显示答案 | `GRAPH_MODEL_V2.md`, `UI_UX_IMPROVEMENT_PLAN.md` | Shared Node = Shared State；shared canonical Question 解析唯一 effective Answer；divergence fail closed（`SHARED_STATE_DIVERGENCE`）；Focus 不选择 Answer |
| Focus Route 高亮但不独占 | `GRAPH_MODEL_V2.md`, `UI_UX_IMPROVEMENT_PLAN.md` | 其他路线降权但继续可见；Focus 不隐式 Activate |
| Active / Focus / Visibility 独立 | `GRAPH_MODEL_V2.md` | Active 是 backend/runtime mutation target；Focus 是 browser read/highlight context |
| Route 使用友好名称 | `GRAPH_MODEL_V2.md`, `UI_UX_IMPROVEMENT_PLAN.md` | UI 不依赖 UUID/数据库编号 |
| Fork 后新路线立即出现 | `GRAPH_MODEL_V2.md`, `UI_UX_IMPROVEMENT_PLAN.md` | 先创建 Route，再运行 Agent；UI 投影 pending card；branch point 保持 shared |
| 生成中不应持久化半成品 Node | `NODE_MODEL_V2.md`, `UI_UX_IMPROVEMENT_PLAN.md` | AgentRun/operation 状态投影 pending；完成后原子持久化 Node |
| 提交答案期间前端不应全局锁死 | `UI_UX_IMPROVEMENT_PLAN.md` | 局部防重复提交，Canvas/Focus/查看保持可用 |
| 提交/拖动时 option/free text 不消失 | `UI_UX_IMPROVEMENT_PLAN.md` | 输入状态按 node+route/read context 保存，不依赖组件 mount |
| 选项内部改为上下布局 | `UI_UX_IMPROVEMENT_PLAN.md` | label 在上，impact/explanation 在下 |
| 用 Q1/Q2/Q3，不显示“当前问题/历史问题” | `UI_UX_IMPROVEMENT_PLAN.md` | Q label 替代状态标题，但问题正文继续显示 |
| 最多一个“最新”标识 | `UI_UX_IMPROVEMENT_PLAN.md` | workspace 视觉上下文最多一个 Latest |
| 次要按钮 hover 时出现 | `UI_UX_IMPROVEMENT_PLAN.md` | hover/keyboard focus action toolbar；重要状态常驻 |
| 新 Node reveal 时不要全图 relayout | `UI_UX_IMPROVEMENT_PLAN.md` | 保持视口/节点位置稳定，轻量 reveal/pan |
| UI 动态显示 Agent 工作阶段 | `UI_UX_IMPROVEMENT_PLAN.md` | 只显示真实 Runtime phase，不展示/伪造 chain-of-thought |
| Word 类 Undo / Redo | `GRAPH_OPERATION_HISTORY.md` | typed operation + compensation/checkpoint；不只是前端 state |
| Undo 不破坏 immutable Answer/history | `GRAPH_OPERATION_HISTORY.md` | revision/compensation，禁止物理覆盖历史 |
| Agent 不只是固定工作流 | `AGENT_RUNTIME_ARCHITECTURE.md` | Decision Engine 选择 primary action |
| Reflection 必须存在但不能机械增加等待 | `AGENT_RUNTIME_ARCHITECTURE.md` | Reflection + Planning 默认同一个 Decision call |
| 当前回答链路调用太多 | `AGENT_RUNTIME_ARCHITECTURE.md`, `AGENT_RUNTIME_IMPLEMENTATION_PLAN.md` | 正常 Answer 目标从 3 个串行模型调用收敛到 2 个 |
| Agent loop 不能无限执行 | `AGENT_RUNTIME_ARCHITECTURE.md` | step budget + WAIT + approval/user-input/failure/no-progress stop conditions |
| 默认 Advisor，保留 Autonomous | `AGENT_AUTONOMY_MODEL.md` | 默认顾问；自动模式只放开 policy-approved 低风险动作 |
| 重大用户意图变化必须确认 | `AGENT_AUTONOMY_MODEL.md` | confirmed intent/destructive/external side effect 高风险 |
| confidence 不能成为唯一自动执行阈值 | `AGENT_AUTONOMY_MODEL.md` | confidence 只是一种信号，实际 policy 由 Runtime 计算 |
| Agent 不直接写 DB | `AGENT_RUNTIME_ARCHITECTURE.md`, `AGENT_ACTION_PROTOCOL_V2.md` | proposal -> policy -> validator -> executor |
| 高内聚低耦合 | 全部 Canonical docs | 稳定接口、职责分层、Graph/Decision/Capability 解耦 |
| 防 prompt / domain 过拟合 | `AGENT_RUNTIME_ARCHITECTURE.md`, `AGENT_EVALUATION_MODEL.md` | generic Graph actions，跨域 scenario + perturbation eval |
| Skill / MCP 后续易扩展 | `CAPABILITY_RUNTIME.md` | Skill/MCP/Internal 通过 capability adapter 接入 |
| MCP 不应被粗暴等同于一个 Tool | `CAPABILITY_RUNTIME.md` | MCP tools/resources/prompts 分别映射，host 管权限/凭证 |
| 文件/图片/代码后续易扩展 | `NODE_MODEL_V2.md`, `CAPABILITY_RUNTIME.md` | RESOURCE + subtype/handler，不新增 FileAgent/ImageAgent |
| Python 后续可用于 Agent Brain | `PYTHON_AGENT_RUNTIME_BOUNDARY.md` | Python 只实现 Decision Engine/模型编排；不构造 authoritative state/直连 DB |
| Spring 保留 Graph Runtime | `PYTHON_AGENT_RUNTIME_BOUNDARY.md` | persistence/transactions/policy/validation/context projection 留在 Spring |
| Agent 质量不能只看“像 AI” | `AGENT_EVALUATION_MODEL.md` | groundedness/action/graph stability/human alignment/latency/cost 联合评估 |
| Unknown reduction 不能靠猜 | `AGENT_EVALUATION_MODEL.md` | 同时量 unsupported assertion/confirmed reduction |
| Prompt 不应该无限 Task 膨胀 | `AGENT_RUNTIME_IMPLEMENTATION_PLAN.md` | 收敛到 STATE_UPDATE / DECISION / ARTIFACT_GENERATION 等少量能力 |
| 未答 Interaction Question 保持 tip | `GRAPH_MODEL_V2.md` | unanswered Question 下禁止子节点；稳定错误 `UNANSWERED_QUESTION_HAS_CHILD`（含 Agent-created Question） |
| Re-answer 创建新 Question 身份 | `GRAPH_MODEL_V2.md` | Q2 → Q2' fresh canonical identity；`parentNodeId` 与旧 Q2 相同；branchType=`REANSWER`；记录 source route + old Q2 branch point；inherited prefix 不含旧 Answer；不使用 `supersedesNodeId` |
| Resume Question 功能不存在 | `GRAPH_MODEL_V2.md` | inactive/open Route 的未答 tip 通过激活原 Route 回答，不创建 Resume Route；`RESUME_ANSWER` 是独立 runtime repair |
| Focus ≠ Active 且不选择 Answer | `GRAPH_MODEL_V2.md`, `UI_UX_IMPROVEMENT_PLAN.md` | Focus 变化不静默改 Active，也不选择不同 shared Answer |
| Floating Node / routeless NodeQuery | `GRAPH_MODEL_V2.md` | `routeIds=[]`、`parentNodeId=null` 合法；仅 `NODE_QUERY` 允许 `routeId=null`（mixed null/UUID fail closed） |
| Semantic relation 与 lineage 分离 | `GRAPH_MODEL_V2.md` | relation 存于 `node_relations`；禁止改 parentNodeId/route root/tip/membership/Active/Focus；禁止 reparent、historical insertion、rejoin |
| Canvas 拖拽连接 = pending proposal（Scheme C） | `GRAPH_MODEL_V2.md`, `UI_UX_IMPROVEMENT_PLAN.md` | Confirm 才持久化 relation + GraphOperation；Cancel/Esc/click-away 无持久化变更；`DEPENDS_ON`+`DERIVED_FROM` 合并 DAG 环检测 |
| unresolved conflict 必须直接进入冲突解决 | `AGENT_RUNTIME_ARCHITECTURE.md`, `AGENT_AUTONOMY_MODEL.md` | 只允许 `REQUEST_USER_INPUT` 或 confirmable `CREATE_NODE (KNOWLEDGE/DECISION)`；禁止 WAIT/无关 continuation/silent assumption；`NODE_QUERY` 保持 read-only 豁免 |
| Agent-authored DECISION 必须确认 | `AGENT_AUTONOMY_MODEL.md`, `NODE_MODEL_V2.md` | 属于 `CONFIRMED_INTENT_CHANGE`；高 confidence 不能自授权 |
| retry/resume 的模型输入可复现 | `AGENT_MEMORY_AND_CONTEXT.md`, `AGENT_STATE_MODEL.md` | 同一 ContextSnapshot 的模型投影 first-freeze 后 durable 不可变重放（payload + durable版本`agent-input-projection.v1` + hash + mutable-source指纹集，insert-if-absent）；冻结后新增 capability observation/编辑不回溯进入旧快照；frozen input identity 与 live stale 校验职责分离（可变源指纹活检查，只阻断变动提案，read-only不阻断）；legacy语义重放间隙`LEGACY_FROZEN_INPUT_UNAVAILABLE` fail closed |

## Rule

若未来实现或新文档与此表冲突：

1. 先检查 `docs/v2/README.md` 指向的 Canonical 文档；
2. 不要选择“更方便实现”的解释自行覆盖产品要求；
3. 若确需改变冻结决策，先更新对应 Canonical 文档和本表，再实施代码。

# Spec Agent Graph UI V2 UX Improvement Plan

## 1. Purpose

本文件记录昨天讨论后收敛的 Graph UI 产品要求，作为前端重构和 Agent Runtime V2 接入的正式约束。

目标不是把当前问卷式 UI 做得更漂亮，而是让 Graph 成为一个用户可以自由编辑、分支、询问 AI、观察运行状态和管理路线的 AI-native requirement workspace。

## 2. Core UX Principles

1. Graph 是主要交互空间，固定按钮只是快捷方式，不是系统能力边界。
2. 用户在 Agent 工作期间仍可浏览、拖动、聚焦其他节点和路线。
3. 运行状态必须可见，但 UI 不展示或伪造模型的私有 chain-of-thought。
4. 默认界面减少常驻操作按钮；重要状态常驻，次要操作 hover/focus 时出现。
5. Route Focus 只高亮，不独占、不隐藏其他路线。
6. Shared Node = Shared State：共享节点共享同一个不可变身份，不复制；答案更不按 route 拆分展示。
7. Canvas 可见线条保持简单箭头，只表达 exploration continuation。
8. 用户可以创建空 Node 并从任意历史 Node 开启新的 continuation，但不能改写既有历史链路。
9. 所有输入状态应按 Node 持久在前端 store/view model 中，拖动 Canvas 或提交请求不能把选项/自由输入清空。

## 3. Node Card Information Hierarchy

### 3.1 Q label, not “当前问题 / 历史问题”

Question Node 顶部使用简洁序号：

```text
Q3                                  最新
MVP 路线

你希望系统如何判断反馈优先级？
```

`Q1/Q2/Q3` **替代的是“当前问题/历史问题”状态标签，不是问题正文**。问题正文必须继续显示。

### 3.2 Latest marker

同一个 workspace 视觉上下文中最多显示一个 `最新` 标识，用来帮助用户快速定位最新产生/正在产生的探索位置。不要给每条路线都堆一个 Latest。

### 3.3 Route labels

Node 可紧凑显示 Route 名称。Shared Node 显示多个所属路线标签，而不是“2 条路线”这种数据库式信息。

### 3.4 Answers

已回答 Question Node 应能看到答案摘要。

> **Shared Node = Shared State.** A canonical Question Node has at most one
> immutable Answer identity project-wide. All routes referencing an answered
> shared Question reference the same Answer ID. Focus changes visual/read
> context only and never selects a different Answer. 因此前端**不**按路线展示
> 不同 Answer、**不**存在"多路线答案不同"的 shared divergence、**不**存在
> "Focus Route 默认展开对应 Answer" 的拆分逻辑。

Shared Question Node 的展示规则：

- 同一 Question 全局只有一个不可变 Answer 身份；Focus（Main / Branch / null）下展示的内容完全一致。
- Inspector 的"回答"区块只展示该 canonical Answer 一次；"路线归属"区块只列 route memberships（路线、生命周期、active/focus/provenance），不重复 Answer payload。
- Graph 节点上 shared answered Question 直接展示该 Answer；未答 shared Question 只显示"等待回答"，绝不按路线制造 route-specific 的"等待回答/回答这个问题"入口。

## 4. Question Options Layout

选项内部采用 **上下结构**，不要把 label 和 impact 挤成左右两列。

推荐：

```text
○ AI 自动判断优先级
  根据反馈内容、影响范围等信号给出建议排序

○ 人工设置优先级
  控制更强，但需要额外人工维护
```

要求：

- option label 独立成行；
- impact/explanation 位于其下；
- 点击目标足够大；
- Node 宽度变化时不形成难读的横向挤压；
- 自由回答区与 options 视觉上属于同一个回答区域。

## 5. Hover / Focus Actions

默认卡片保持低噪音。

以下动作在 hover、keyboard focus 或选中 Node 后显示：

- 继续探索
- Fork / 新路线
- 连接 Node
- 重新生成/换一个问题（适用时）
- Re-answer（适用时）
- More actions

删除、归档等高风险操作放进 `More`，不要与主要探索操作长期并排。

当前运行状态、失败状态、待用户确认等重要状态必须常驻，不依赖 hover 才能发现。

## 6. Non-blocking Submission and Generation

### 6.1 Answer submission

用户提交答案后：

- 只禁用会造成重复提交的局部控件；
- Canvas 仍能拖动、缩放、查看其他 Node；
- Fork/查看/Focus 等与当前 mutation 不冲突的操作不应被全局锁死；
- 用户刚选择的 option/free text 保持可见，直到运行成功或用户主动修改；
- 拖动当前问题、切换 focus、重排视图不得清空选项/输入；
- 若失败，原答案输入和 retry affordance 保留。

前端状态应按 `nodeId + route/read context` 建模，而不是依赖卡片组件是否重新 mount。

### 6.2 Dynamic progress copy

可以动态展示**可验证的运行阶段**，例如：

```text
正在保存回答…
正在更新需求状态…
正在规划下一步…
正在生成新问题…
正在调用外部能力…
```

这些是 Runtime phase/status，不是模型隐式思维过程。不要展示“AI 正在思考某某内部理由”之类未经系统真实记录的 chain-of-thought 文案。

## 7. Fork / New Route UX

用户点击 Fork 后，新路线必须立即可见，不等待模型返回。

正确时序：

```text
Fork click
  ↓
Route created
  ↓
Canvas immediately renders route + virtual pending card
  ↓
AgentRun RUNNING
  ↓
validated Node persisted
  ↓
pending card replaced by real Node
```

Pending card 来自 Route/AgentRun/operation projection，不要求数据库先保存一个半生成 Node。

失败时：

- 路线仍可见；
- pending card 变为 Failed；
- 可以显示安全 retry/继续操作；
- 不能偷偷切换到 Fake 或另一个 provider。

## 8. Route Focus

点击/选择路线：

- Focus Route 高亮；
- 同路线 continuation edge/node 增强；
- 其他路线降低透明度/视觉权重；
- 不隐藏、不卸载其他路线；
- Shared Node 不复制；
- Focus 改变只改变 read/visual context，不能隐式改变 Active Route。

应保留显式“聚焦此路线”能力，但它是视觉/阅读操作，不是独占过滤。

## 9. Free Node and Connection UX

用户可以创建空白/草稿 Node：

```text
+ Node
  ↓
空卡片
  ↓
用户写想法/需求/问题
```

用户可以从任意 Node 发起 continuation：

```text
已有 Q2 ─────→ new draft / generated node
```

若该 Node 已经有历史后继，则新的 continuation 形成新分支/Route；禁止通过 UI 把新 Node 插入既有 `Q1 -> Q2` 中间并假装历史被重写。

用户也可以建立 semantic relation，但默认 Canvas 不把所有 semantic relation 都渲染成线；这些关系优先在 Inspector/可选 relation layer 中查看。

## 10. Contextual AI on Any Node

Question Node 是可回答的交互节点。

另外，**任何 Node 都可以作为 AI 上下文锚点**。用户可以在 Node/Inspector 中输入例如：

```text
“这个需求会影响哪些部分？”
“基于这个文件继续帮我拆解。”
“从这里再问我一个最关键的问题。”
```

Agent 的上下文必须来自该 Node、当前 Focus/Route lineage、直接相关节点、route-scoped answers/patches 和允许的 Resource context，而不是全局聊天历史。

AI 回答可提供候选动作，例如：

- 创建问题；
- 创建风险/需求 Node；
- 建立关系；
- 继续路线；
- 仅回答，不修改 Graph。

Advisor Mode 下重要 Graph 修改先给用户确认。

## 11. Undo / Redo

顶部提供类似 Word 的 Undo / Redo 体验。

但实现不是简单恢复 Vue state，也不能把现有 ContextSnapshot 当完整 Graph snapshot。

底层依据 `GRAPH_OPERATION_HISTORY.md`：

- Runtime 记录用户可见 Graph operations；
- Undo 生成 operation-specific compensation / restored materialized view；
- Redo 仅在前置条件仍有效时重新应用；
- immutable Answer、replacement/route history 不物理删除；
- 外部 MCP/API side effect 若不可逆，必须明确标识，不能假装 Ctrl+Z 可以撤回。

## 12. Node Runtime Status Projection

区分两类状态：

**运行状态（AgentRun/Operation）**

```text
Pending / Running / Succeeded / Failed
```

**知识状态（仅适用 claim-like content）**

```text
Proposed / Confirmed / Challenged / Superseded
```

前端不要把 `Generating` 和 `Confirmed` 塞进同一个 Node lifecycle 枚举。

## 13. Layout / Reveal

新节点完成后：

- reveal 新节点；
- 尽量保持用户当前视口和已有 Node 位置稳定；
- 不因为一个 Node 完成而全图 relayout；
- 如需自动聚焦，应是轻量 reveal/pan，不应让其他路线消失。

## 14. Acceptance Scenarios

至少覆盖：

1. 提交答案期间拖动当前问题，option/free text 不消失。
2. 提交期间可查看/Focus 其他路线，重复提交按钮被局部禁用。
3. Fork 后路线立即出现，模型未完成时显示 pending card。
4. Fork 失败时路线/pending 状态可理解且可恢复。
5. Focus Route 高亮但其他路线仍可见。
6. Shared Node 显示全部路线名和正确的 route-scoped answers。
7. Node 选项上下排版，在窄/宽卡片中都可读。
8. Hover 才显示次要按钮，keyboard focus 同样可操作。
9. 用户可创建空 Node，从非 tip Node continuation 时形成 branch，而非插入历史。
10. 任意 Node 可发起 contextual AI query。
11. 新节点 reveal 不触发全图 relayout。
12. Undo/Redo 不破坏 immutable answer/history invariants。

## Final Goal

Spec Agent UI 应该像一个 AI-native requirement editor，而不是表单/问卷：用户可以自由组织需求空间，Agent 在旁协助分析和推进，Graph 始终让路线、历史、状态和上下文保持可理解。

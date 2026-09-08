# Spec Agent — UI / UX Audit

> 审查对象：`main` @ `35039f80eea7f78a2c8d7a92833cff3d0c76400c`
>
> 审查范围：`frontend/src`、`frontend/e2e`
>
> 约束：本阶段只审查和重构前端呈现层，不重构 Agent Runtime，不新增无依据的产品功能。

## 1. 结论

当前前端的核心问题不是“功能不足”，而是“真实能力暴露过多、过直接、层级不够克制”。

现有实现已经覆盖 Graph、Route、Node、Inspector、Spec、Agent 状态、错误恢复、Fork / Reanswer / Regenerate、Semantic Relation、Undo / Redo、输入保留等关键能力。下一阶段不需要继续扩产品能力，而应把已有能力重新组织成更稳定、低噪音、专业的工作台。

目标：

- Graph 继续作为主工作区
- Route 变成稳定导航，而不是控制台
- Inspector 负责深读，不重复 Graph 操作
- Spec 从 Inspector 附属页提升为独立产物阅读区
- Agent 状态统一表达，不暴露 Runtime 日志
- 高频动作常驻，低频动作收进 contextual menu / overflow
- 不因为视觉稿出现了某功能，就新增真实产品中不存在的能力

---

## 2. 当前真实前端结构

### 2.1 Workspace Shell

主要入口：

- `frontend/src/views/WorkspaceView.vue`
- `frontend/src/components/graph/GraphCanvas.vue`
- `frontend/src/components/workspace/FloatingWindow.vue`
- `frontend/src/components/workspace/RouteNavigator.vue`
- `frontend/src/components/workspace/WorkspaceInspector.vue`

当前 Workspace 是 Graph-first，但 Route Navigator 和 Inspector 都通过可拖拽、可缩放的 Floating Window 覆盖在 Graph 之上。

这带来了大量 presentation-only 复杂度：

- 浮窗位置持久化
- z-index 管理
- resize
- snap
- obstacle avoidance
- safe fit region
- resize observer
- 自动布局后重新避让
- 900×700 下的浮窗互避 E2E

这些实现本身严谨，但产品价值有限。

### 2.2 Graph

`GraphCanvas.vue` 已经很好地守住了边界：

- Vue Flow 只负责浏览器端 viewport / dragging / selection
- Focus 与 Active 分离
- Node position 只存浏览器本地
- Runtime state 不由前端推断

这些边界应保留。

### 2.3 Question Node

`GraphQuestionNode.vue` 当前同时承担：

- Q label
- Latest
- route membership chips
- Shared route selector
- runtime status
- 完整 question / purpose
- options
- free-text answer
- submit
- historical answer preview
- waiting state
- Fork / Reanswer / Regenerate / Ask AI

功能是完整的，但视觉职责过多。

### 2.4 Route Navigator

`RouteNavigator.vue` 同时暴露：

- 生命周期过滤
- Active
- Focus
- 路线来源
- 节点数
- Locate
- Focus / Unfocus
- Isolate
- Dim
- Hide
- Activate
- Restore
- Archive
- Delete

这更像调试 / 管理控制台，而不是日常导航。

### 2.5 Inspector

`WorkspaceInspector.vue` 目前有三个一级 Tab：

- 详情
- 需求状态
- 规格

`NodeInspector.vue` 再继续展示：

- metadata
- Ask AI
- proposal
- options
- route membership
- branch provenance
- answer
- semantic relations
- historical actions

真实信息很丰富，但优先级不清晰。

### 2.6 Toolbar

`GraphToolbar.vue` 当前常驻 12 个文字按钮：

- + 想法
- + 资源
- 撤销
- 重做
- 放大
- 缩小
- 适应视图
- 重新自动布局
- 显示全部路线
- 路线导航
- 检查器
- 重置窗口

这是最明显的第一层 UI 噪音之一。

---

## 3. TOP 10 问题

### P1 — Floating Window 成本远高于用户价值

Route 和 Inspector 是稳定、持续存在的工作区角色，不需要默认成为可任意拖拽、resize 的窗口。

建议：产品化 UI 默认改为固定 Left / Graph / Inspector 布局。必要时可以保留折叠，不保留自由浮窗作为主 mental model。

### P2 — Graph Toolbar 过载

12 个常驻文字按钮破坏 Graph 主视觉。

建议：只保留高频 viewport 和创建入口。低频操作进入 overflow。

### P3 — Route Navigator 像管理面板，不像导航

Route 日常任务主要是：

1. 知道有哪些路线
2. 知道当前在看哪条
3. 切换 / 定位
4. 理解 route 状态

Archive / Delete / Dim / Hide / Isolate 等不应常驻。

### P4 — Historical Node 太重

历史节点应该帮助理解路线，而不是继续承担完整交互表单。

建议：

- 历史节点：Q# + 简短标题 + 状态 + Latest/Shared 必要标识
- 当前可回答节点：允许展开 options / text input
- 详细 answer / provenance / relation 进入 Inspector

### P5 — Node 状态有语义，但缺统一产品生命周期

现有 runtime copy、answer state、pending projection 已经足够表达状态，但视觉上需要统一成少量用户可理解的生命周期：

- Pending
- Generating
- Ready
- Confirmed
- Failed / Needs attention（仅异常时）

不要把 Runtime 内部 phase 直接变成大量 badge。

### P6 — Inspector 信息架构过密

Node Inspector 需要先回答：

- 这是什么？
- 当前结论是什么？
- 有什么证据 / 关系？
- AI 正在做什么？
- 我现在需要做什么？

其他 metadata 应降级。

### P7 — Spec 被放在错误层级

Spec 是最终产物，不应继续作为 Inspector 内的普通 Tab。

建议：独立为 Workspace 下方 / 次级主区域，保留章节导航、snapshot、unresolved、sources 等真实能力。

### P8 — Agent / Recovery 状态展示分散

现有代码已经区分：

- answer saved but continuation failed
- outcome unknown
- safe resubmit
- model settings required
- runtime phase
- pending / awaiting approval

不需要新增错误模型；需要统一产品化状态条 / inline status。

### P9 — 视觉系统仍然偏工程默认

当前 `style.css` 的 token 和组件风格可用，但整体偏内部工具：

- 6px radius
- 大量 border
- 大量 badge
- Segoe UI default feel
- panel / button 风格高度统一但缺少主次

建议做新的 presentation tokens：spacing、typography、surface、selected、status、elevation、focus ring。

### P10 — E2E 部分绑定旧 presentation

现有 E2E 很有价值，但 Floating Window geometry 等测试保护的是旧 UI 形态。

UI 重构后应继续强保护：

- core clarification
- input persistence
- route switching
- fork / reanswer / regenerate
- lifecycle
- contextual AI
- semantic relation
- pending / recovery
- Agent chain terminal behavior

而不是强迫新版 UI 保留浮窗结构。

---

## 4. 建议目标布局

```text
┌────────────────────────────────────────────────────────────┐
│ Spec Agent   项目 / 路线上下文              Agent 状态     │
├────────────┬──────────────────────────┬────────────────────┤
│            │                          │                    │
│ Routes     │          Graph           │     Inspector      │
│            │                          │                    │
│            │                          │                    │
├────────────┴──────────────────────────┤                    │
│                 Spec                 │                    │
└───────────────────────────────────────┴────────────────────┘
```

设计原则：

- Graph 最大、最稳定
- Route sidebar 窄而轻
- Inspector 固定宽度、可折叠
- Spec 是独立阅读面
- 不使用多个浮层争夺视觉中心
- 不锁整个 Workspace

---

## 5. 第一实施 Slice 建议

只做 presentation skeleton，不碰业务行为：

1. Workspace Shell 固定三栏
2. Floating Route / Inspector → fixed side panels
3. Graph Toolbar 做第一轮减法
4. Historical Node compact mode
5. Current Node 保留真实回答能力
6. Route list 简化为导航态
7. Inspector 先重排详情信息

暂不做：

- Spec 完整改版
- Approval 深化
- Error / Recovery 完整视觉系统
- responsive 全覆盖
- animation polish
- 新产品功能

---

## 6. 冻结边界

本 UI / UX 阶段不得顺手重构：

- ContinuationCoordinator
- DecisionExecutionService
- ActionEligibilityValidator
- RunWorker
- ContextBuilder
- ContextGuard
- Approval Runtime
- AgentRun persistence
- migrations

如果前端不好展示，优先做 frontend presentation / read model；只有出现明确 Critical backend API gap 才另行评审。

---

## 7. 审查结论

**Verdict: GO — 可以进入 UI presentation redesign。**

理由：

- 核心业务能力足够
- Graph / Route / Agent 边界已经成熟
- 当前主要问题集中在信息架构和视觉层级
- 没有必要为 UI 深化继续扩展 Runtime

下一步：先冻结 `UI_SCOPE_FREEZE.md`，确认什么必须保留、什么只降级展示、什么明确不新增；确认后再进入实现计划。
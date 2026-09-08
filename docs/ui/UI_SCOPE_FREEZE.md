# Spec Agent — UI Scope Freeze

> 目的：冻结 UI / UX 深化阶段的产品边界，防止前端改造演变成“顺手加功能”或 Runtime 重构。
>
> 基线：`main` @ `35039f80eea7f78a2c8d7a92833cff3d0c76400c`

## 1. 核心原则

### 1.1 视觉稿不定义功能

目标图只用于：

- 布局方向
- 信息层级
- 视觉密度
- 交互优先级
- 组件质感

**目标图里出现的按钮、Tab、面板、搜索、评论、历史、分享等，不自动成为产品需求。**

只有满足以下任一条件的能力才允许进入实现：

1. 当前代码 / API / E2E 已经存在；
2. 已有项目文档明确要求；
3. 用户在 UI / UX 阶段明确批准新增。

否则默认不做。

### 1.2 功能不删，噪音删掉

对已经存在且有真实产品价值的能力，优先做：

- 降低视觉权重
- 改成 hover / selected action
- 收入 overflow / context menu
- 移入 Inspector
- 只在满足条件时出现

而不是直接删除业务能力。

### 1.3 Frontend 不推断 Runtime

继续冻结：

- Focus Route ≠ Active Runtime Route
- Shared Node 不复制
- Frontend 不根据 node kind / conflict 自己决定下一动作
- Pending / approval / recovery 只呈现后端真实状态
- unknown outcome 不自动重复 mutation

---

## 2. 必须保留的核心产品能力

这些能力属于现有 Spec Agent 产品，不允许为了“界面更简单”而误删。

### Graph

- Graph 是主工作区
- Pan / Zoom
- Node selection
- Node drag / position persistence
- lineage / replacement / semantic relation 的真实展示
- Focus Route 浏览上下文
- Shared Node 单实体、多路线归属
- Latest 标识
- 新 Node / Route 的 pending projection

### Clarification / Answer

- 当前可回答 Question
- options
- free-text answer
- input persistence
- submit outcome reconciliation
- historical answer read-only

### Route

- Route 切换 / Focus
- Active route 与 Focus route 区分
- Fork
- Reanswer
- Regenerate
- restore / archive / delete 等真实 route lifecycle 能力

### Agent

- AgentRun progress 的产品化状态
- final RESPOND 可见
- approval / reject
- approval 后 continuation
- retryable / unknown / repairable failure 区分
- contextual AI query

### Spec / Requirement

- requirement state
- spec generation
- spec snapshots / history
- route ownership

### Safety / Recovery

- stale / unknown / retryable / saved-but-continuation-failed 的真实语义
- 不锁整个 Workspace
- mutation outcome 未确认时不自动重发

---

## 3. 保留，但降低第一层可见性的能力

这些能力继续存在，但不应成为默认常驻 UI。

### Graph Toolbar 次要操作

建议收入 `更多` 或 contextual menu：

- 重新自动布局
- 显示全部路线
- 重置窗口 / 布局
- Undo / Redo（可保留 icon，但不需要大文字按钮）
- Add Resource（如果不是高频主任务）

### Historical Node Actions

默认不常驻，节点 hover / selected 后出现：

- 从这里开新路线
- 重新选择答案
- 换一个问题
- 问 AI

### Route 管理动作

默认 Route row 只显示：

- 名称
- 简短状态
- 当前查看 / 当前运行的必要区分

以下进入 overflow：

- 设为当前
- 归档
- 删除
- 恢复
- 低频 display controls

### Inspector Metadata

以下不作为第一屏重点：

- 创建时间
- author kind
- 低层 branch metadata
- raw relation ids
- 技术状态码

需要时展开或放入次级区域。

---

## 4. UI 形态允许改变，但语义必须保持

### Floating Window

**允许取消作为默认 UI。**

推荐替换为：

- 左侧固定 Route sidebar
- 中央 Graph
- 右侧固定 Inspector
- Inspector / Route 可折叠

可以删除 presentation-only 的：

- 任意拖拽窗口
- 八方向 resize
- z-order mental model
- window obstacle avoidance
- window geometry persistence

前提：Graph 的 pan / zoom / selection / fit 不受损。

### Node

历史 Node 可以从当前大卡片改成 compact card：

```text
Q3        Latest
数据与安全
Ready
```

但当前需要回答的节点仍需提供真实 answer interaction。

### Spec

允许从 Inspector Tab 移出，成为独立 Workspace 区域。

这属于 information architecture 调整，不改变 spec backend semantics。

---

## 5. 明确禁止因为目标图新增的功能

除非用户后续明确批准，否则以下一律不因视觉稿而新增：

- 评论系统
- 协作评论 badge
- History 作为新产品级时间线系统
- Research 独立模块
- Notes 独立模块
- Design 独立模块
- 分享 / 权限协作系统
- Command palette / 全局命令搜索
- 通知中心
- 用户头像 / 账号菜单的新业务逻辑
- 智能体配置中心
- 新的 Agent persona 系统
- Trash / All Nodes / Shared Nodes 新管理中心
- 任意 analytics dashboard

如果当前已有底层能力，只能在单独产品评审后决定是否暴露。

---

## 6. 第一轮实现范围

### INCLUDE

1. Workspace Shell
   - 固定 Route / Graph / Inspector 布局
   - 保持 Graph 主视觉

2. Visual System
   - spacing
   - typography
   - surface
   - border
   - selected / focus
   - status color
   - focus ring

3. Graph Toolbar
   - 减少常驻按钮
   - icon / overflow 层级

4. Question Node
   - Q#
   - Latest
   - compact historical node
   - current node expanded interaction
   - Pending / Generating / Ready / Confirmed 的统一展示

5. Route Sidebar
   - route name
   - focus/current semantics
   - selected row
   - overflow actions

6. Inspector
   - 问题 / 回答 / 关键状态优先
   - AI proposal 只在存在时出现
   - provenance / relations 降级

### EXCLUDE — 第一轮不做

- Agent Runtime 改动
- Backend API 新增
- Spec 完整改版
- Approval 全量 UX 重构
- Error / Recovery 全量视觉重构
- mobile-first redesign
- 新动画系统
- 新功能模块

---

## 7. 第一轮验收标准

### 视觉

- 第一次打开 10 秒内理解 Workspace 主结构
- Graph 明显是视觉中心
- Route / Inspector 不遮挡 Graph
- 不出现多块浮窗互相抢焦点
- 常驻按钮数量显著下降
- 历史 Node 不再像表单

### 交互

- 当前 Question 仍可正常回答
- 输入 / 选项在生成、切换、失败时不丢失
- Focus Route 不改变 Runtime Active Route
- Shared Node 不复制
- Agent 运行时仍可 Pan / Zoom / 查看其他内容

### 回归

必须继续通过或等价覆盖：

- core clarification
- input persistence
- route switching
- fork / reanswer / regenerate
- lifecycle
- contextual AI
- semantic relation
- pending / recovery
- Agent chain terminal behavior

旧的 Floating Window geometry E2E 可以被新版固定布局 E2E 替代，不要求保留旧 presentation。

---

## 8. Backend Freeze

UI / UX 第一轮明确不修改：

- ContinuationCoordinator
- DecisionExecutionService
- ActionEligibilityValidator
- RunWorker
- ContextBuilder
- ContextGuard
- Approval Runtime
- AgentRun persistence
- migrations

如果实现过程中发现 backend API gap：

1. 停止该项 UI 实现；
2. 记录具体缺口；
3. 判断是否可以 presentation / read model 解决；
4. 只有确认为 Critical backend API gap 后，单独提交评审。

---

## 9. 最终冻结句

> **本阶段的目标是把已有 Spec Agent 做得更清楚、更安静、更专业，而不是让它拥有更多功能。**
>
> **没有真实产品依据的 UI 功能，不实现。**

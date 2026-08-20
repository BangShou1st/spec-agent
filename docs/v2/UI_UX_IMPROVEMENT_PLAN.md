# Spec Agent Graph UI V2 UX Improvement Plan

## Purpose

记录当前 Graph UI 产品设计要求，作为后续前端重构和 Agent Runtime V2 接入的约束。

目标：让 Graph 成为用户理解需求、探索路线、观察 Agent 推理过程的主要界面。

---

# 1. 核心设计原则

## 1.1 Graph 是主要交互空间

用户不应该被固定按钮限制。

未来用户可以：

- 创建空 Node
- 输入需求
- 连接已有 Node
- 从任意 Node 继续生成
- 回退/前进历史状态

系统应该围绕 Node 和关系设计，而不是围绕几个固定操作设计。

---

# 2. Node UI 设计

## 2.1 信息层级

Node 不应该显示过多历史信息。

推荐：

- Q1
- Q2
- Q3

替代完整问题标题。

允许：

- 最新节点显示 Latest 标识
- 显示 Route 名称
- Shared Node 显示所属多个 Route

---

## 2.2 Node 类型方向

保持高内聚低耦合。

不要通过大量特殊 Node 类型解决需求。

推荐：

- Knowledge Node
- Interaction Node
- Resource Node
- Artifact Node

未来上传文件、MCP、Skill 等能力通过扩展能力层接入，而不是破坏 Node 模型。

---

# 3. Node 生命周期展示

Agent 生成节点时，不应该等待完整生成后才显示。

应该立即创建状态节点：

```
PENDING
  |
GENERATING
  |
READY
  |
CONFIRMED
```

例如新路线：

```
Q1
 |
Q2
 |
[Generating new direction...]
```

用户应该看到系统正在工作。

---

# 4. Route 设计

## 4.1 保留箭头关系

线条保持当前方向，不改成复杂连接样式。

箭头用于表达因果和路径。

---

## 4.2 Focus Route

聚焦路线：

- 高亮当前路线
- 其他路线降低视觉权重
- 不隐藏其他路线
- 不独占显示

---

## 4.3 Shared Node

共享节点：

- 不复制 Node
- 显示多个 Route 归属
- 当前 Route 优先展示
- 保留其他路线答案信息

---

# 5. Interaction 设计

## 5.1 Hover 操作

减少视觉噪音。

以下操作默认隐藏，鼠标悬停显示：

- Fork
- Delete
- Regenerate
- Connect
- More actions

---

## 5.2 提交状态

当前问题：

提交答案期间，整个前端像被锁定。

改进：

- 保留画布可查看
- Node 显示 processing 状态
- 不丢失选项和输入状态
- 避免用户误以为系统卡死

---

# 6. Question Node

Node 应支持直接回答。

不是简单展示问题。

流程：

```
Node
 |
User Answer
 |
Agent 根据上下文处理
 |
更新 Graph
```

要求：

- Agent 根据 Node 上下文回答
- 支持继续生成问题
- 支持连接已有需求

---

# 7. Undo / Redo

系统需要类似 Word 的操作体验。

支持：

- Undo
- Redo
- 历史状态恢复

但底层应该基于 Graph Snapshot，而不是简单前端状态回滚。

---

# 8. Agent Runtime 对 UI 的要求

未来 Agent 不应该：

```
等待全部完成
 |
一次性返回新节点
```

应该：

```
Agent Action Proposal
 |
Create Pending Node
 |
Streaming Update
 |
Complete
```

UI 需要支持观察 Agent 工作过程。

---

# 9. 后续前端迁移重点

优先级：

1. Node 生命周期状态展示
2. 非阻塞生成体验
3. Route Focus 视觉优化
4. Shared Node 信息展示
5. Hover 操作优化
6. 空 Node / 自由连接能力
7. Undo / Redo

---

# Final Goal

Spec Agent UI 不应该像表单工具。

目标：

一个用户可以自由探索需求空间、观察 AI 推理过程、管理多条路线的 Graph Workspace。

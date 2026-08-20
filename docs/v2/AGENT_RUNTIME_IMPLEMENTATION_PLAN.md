# Agent Runtime V2 Implementation Plan

## 目标

将 Spec Agent 从当前的 Workflow Agent 演进为 Graph Reasoning Agent Runtime。

核心原则：

- 不推翻现有 Graph Runtime
- 不破坏 Route、Snapshot、Recovery、AgentRun
- LLM 不直接修改系统状态
- Agent 决策与能力执行分离
- 保持高内聚、低耦合

---

# 当前状态评估

当前系统已经具备：

- Graph / Node / Route
- Context Snapshot
- AgentRun Trace
- Model Gateway
- Structured Output
- Reflection Gate
- Answer Patch
- Recovery 流程

当前不足：

- 缺少统一 AgentState
- 缺少 Reflection Engine
- 缺少 Planner
- Workflow 与 Agent 决策强绑定
- 缺少 Capability Layer

---

# 目标 Runtime

```
Graph Event
    |
    v
Agent State Builder
    |
    v
Reflection Engine
    |
    v
Planner
    |
    v
Action Proposal
    |
    v
Policy Engine
    |
    v
Action Executor
    |
    v
Graph Mutation
```

---

# Phase 0：冻结基础设施

以下模块视为 Agent Runtime 基础设施，不重写：

- Graph
- Node
- Route
- Snapshot
- Recovery
- AgentRun
- Trace
- Model Gateway

---

# Phase 1：建立 Agent Contract

新增核心协议：

## AgentState

表示 Agent 当前认知：

- objective
- focus
- knownFacts
- unknowns
- conflicts
- risks
- constraints

## AgentObservation

表示 Reflection 结果：

- 已知信息
- 信息缺口
- 风险
- 冲突

## AgentPlan

表示 Planner 决策：

- 目标
- Actions
- 原因
- confidence

## AgentAction

系统可执行动作：

- ASK_USER
- CREATE_NODE
- UPDATE_NODE
- LINK_NODE
- CREATE_ROUTE
- MARK_RISK
- CREATE_SUMMARY
- GENERATE_SPEC
- WAIT

---

# Phase 2：拆分 AgentRuntime

当前：

```
AgentOrchestrator
```

目标：

```
AgentRuntime
 |
 +-- AgentStateBuilder
 +-- ReflectionEngine
 +-- Planner
 +-- PolicyEngine
 +-- ActionExecutor
```

AgentRuntime 只负责生命周期协调。

---

# Phase 3：Workflow Action 化

保留现有能力。

例如：

当前：

```
INTERPRET_ANSWER
DRAFT_PATCH
DRAFT_NODE
```

演进为：

```
AgentPlan
 |
 +-- INTERPRET_ANSWER
 +-- CREATE_PATCH
 +-- ASK_USER
```

旧流程成为 Agent 能力，而不是 Agent 本身。

---

# Phase 4：Reflection Engine

现有 Reflection Gate 保留。

区别：

Gate：验证结果是否合法。

Reflection Engine：理解 Graph 状态。

负责发现：

- unknowns
- conflicts
- risks
- completeness
- 下一步价值

---

# Phase 5：Planner

Planner 不生成最终内容。

Planner 回答：

> 当前 Graph 状态下，下一步最有价值动作是什么？

输入：

- AgentState
- ReflectionResult

输出：

- AgentPlan

---

# Phase 6：Policy Engine

支持两种模式：

## Advisor Mode（默认）

Agent 提议。
用户确认。

## Autonomous Mode

允许低风险动作自动执行。

高风险动作仍需要确认。

---

# Phase 7：Capability Runtime

Skill、MCP、内部服务统一抽象。

结构：

```
Agent Action
      |
      v
Capability Resolver
      |
      +-- Skill
      +-- MCP Tool
      +-- Internal Service
```

Agent 不关心实现方式。

---

# Phase 8：Node 扩展

避免业务 Node 爆炸。

推荐：

```
Knowledge Node
Interaction Node
Resource Node
Artifact Node
```

例如：

```
Resource Node
 subtype=file
```

未来文件、图片、代码都属于 Resource 能力。

---

# Phase 9：Python Runtime 边界

暂不迁移核心系统。

Java 保留：

- Graph
- Persistence
- Transaction
- Validation
- Trace

未来 Python 可以替换：

- ReflectionEngine
- Planner

作为 Agent Brain Adapter。

---

# 实施约束

禁止：

- RequirementAgent / FileAgent 等业务 Agent 膨胀
- Prompt 直接控制数据库修改
- LLM 绕过 Validator
- Tool 与 Agent 强耦合

必须保证：

- 可追踪
- 可回放
- 可验证
- 可扩展

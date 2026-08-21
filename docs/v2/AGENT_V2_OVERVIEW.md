# Spec Agent V2 Overview

## 1. Purpose

Phase 8 已经建立了可靠的 Graph Runtime 基础：Route、Snapshot、Recovery、Answer processing、Model Gateway、Trace 等。V2 的任务是把当前的固定 Workflow Agent 演进为 **Graph Reasoning Agent**，同时保留现有可靠性边界。

当前典型流程更接近：

```text
User Answer -> fixed LLM tasks -> Next Question
```

V2 目标是：

```text
User / Graph Event
        |
        v
Authoritative Graph State
        |
        v
Bounded Agent Decision Cycle
        |
        v
Validated Action Proposal
        |
        v
Graph / Capability Runtime
        |
        v
New Graph State
```

## 2. Product Position

Spec Agent 不是“AI 问卷”或“自动写 PRD 的聊天机器人”。

它是一个 **AI-assisted requirement knowledge workspace**：用户和 Agent 在 Graph 中共同探索、记录、确认、质疑和演进需求。

用户可以：

- 从完全空白的 Workspace 开始；
- 创建空 Node / 想法 / 需求 / 问题；
- 从任意已有 Node 继续探索；
- 创建新路线而不改写既有历史；
- 在 Question Node 上回答；
- 在任意 Node 上发起“基于此节点上下文询问 AI”；
- 连接 Node、查看共享节点和不同路线答案；
- Undo / Redo 可逆的 Graph 操作；
- 后续通过 Resource Node 上传文件、图片、代码、URL 等资料。

Agent 可以：

- 识别重要未知；
- 发现冲突、风险和假设；
- 选择下一步动作，而不只是“生成下一题”；
- 建议创建节点、关系、路线或调用能力；
- 生成阶段总结和 Spec；
- 在默认 Advisor Mode 下把重要变更交给用户确认。

## 3. Empty Start Is First-Class

项目创建不代表用户已经知道要做什么。

因此：

- `projectTitle` 只是低权重 metadata；
- 项目名不能自动成为 objective、requirement 或 confirmed fact；
- 空项目可以不触发任何模型调用；
- 第一个探索入口可以是用户创建的空 Node、用户写下的模糊想法，或用户主动请求 AI 起草一个问题；
- Agent 不得因为项目名“看起来有意义”就擅自假设用户目标。

## 4. Core Principles

1. **Graph is the source of truth.**
2. **Model proposes; Runtime validates and persists.**
3. **Node is extensible and Agent logic is not coupled to business node types.**
4. **Context is selected lineage/relations, not global chat history.**
5. **Unknowns, conflicts, risks and assumptions are first-class reasoning concepts, but not automatically trusted facts.**
6. **Active / Focus / Visibility remain independent concepts.**
7. **Shared Nodes are shared identities, not copied cards.**
8. **Visible edges remain simple exploration arrows; semantic relations are separate.**
9. **Reflection + Planning default to one Decision Cycle, not a mandatory multi-call chain.**
10. **Agent loops are bounded and observable.**
11. **Advisor Mode is default; Autonomous Mode is optional and policy-limited.**
12. **Skill/MCP are capabilities behind adapters, not special Agent types.**
13. **Prompt design must stay domain-general and resist example-specific branches.**

## 5. What Changes from the Current System

Current system strengths remain:

- immutable answers;
- append-preserving history;
- route isolation;
- repair checkpoints;
- context snapshots;
- runtime grounding/validation;
- provider abstraction.

V2 adds missing decision boundaries:

```text
Deterministic State Projection
          |
          v
Decision Engine
  reflection + plan
          |
          v
Action Proposal
          |
          v
Policy / Validator
          |
          +------> Graph Executor
          |
          +------> Capability Runtime
```

The Agent becomes the component that chooses **what should happen next**. The Runtime remains the component that decides **what is legal and what becomes durable history**.

# Agent Memory and Context Architecture

## Purpose

This document defines how Spec Agent builds context for reasoning.

The goal is not to create a chat history based memory system. The Agent should reason from the Knowledge Graph state.

Core principle:

> Graph is the source of truth. Agent memory is a temporary reasoning view.

---

# 1. Memory Layers

Spec Agent uses multiple memory layers.

```
User
 |
 v
Graph Memory
 |
 v
Agent Working State
 |
 v
Current Reasoning Context
```

## 1. Graph Memory

Persistent memory.

Contains:

- Nodes
- Relations
- Routes
- Snapshots
- Decisions
- History

This is stored by the application runtime.

The Agent must not directly mutate this layer.

---

## 2. Agent Working State

Temporary understanding of the current situation.

Example:

```json
{
  "objective": "clarify product requirements",
  "focus": "authentication",
  "unknowns": ["user permission model"],
  "risks": ["external API limitation"]
}
```

This state can be regenerated.

It is not the source of truth.

---

# 2. Context Selection

The Agent must not load the entire graph every time.

Large graphs create:

- token waste
- irrelevant reasoning
- unstable decisions

Context should be selected through:

```
Current Node
 |
 +-- Parent context
 |
 +-- Route context
 |
 +-- Related nodes
 |
 +-- Important decisions
 |
 +-- Recent changes
```

---

# 3. Context Priority

Recommended priority:

1. Current active node
2. Direct relations
3. Current route
4. Confirmed decisions
5. Recent changes
6. Global summary

Historical details should only be loaded when needed.

---

# 4. Preventing Context Drift

The Agent should never assume:

- project name represents the goal
- old answers are still valid
- previous routes are current truth

Instead it should evaluate:

- confidence
- conflicts
- superseded information

---

# 5. Future Extensions

Files, images, code repositories and external resources should enter through Nodes.

Example:

```
RESOURCE Node
 |
 subtype=FILE
```

The Agent receives capabilities through node metadata, not hardcoded logic.

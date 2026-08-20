# Spec Agent V2 Overview

## Purpose

Phase 8 established the reliable Graph runtime: routes, snapshots, recovery, answer processing and model gateway boundaries. V2 focuses on the Agent Brain.

The current workflow is closer to an LLM pipeline:

```
User Answer -> LLM -> Next Question
```

V2 evolves this into a Graph intelligence system:

```
User + Agent
      |
      v
Knowledge Graph
      |
      v
Agent Runtime
      |
      v
Graph evolution
```

## Core Principles

1. Graph is the source of truth.
2. Agent proposes changes; Runtime validates and persists.
3. Node types are extensible and not hard-coded into the Agent.
4. Project names and initial descriptions are not treated as requirements.
5. Unknowns, conflicts and risks are first-class concepts.
6. Preserve append-only history and route isolation.

## Product Direction

Spec Agent is not an AI questionnaire. It is an AI-assisted requirement knowledge editor.

Users and AI collaboratively build a requirement graph. Questions are only one type of exploration action.

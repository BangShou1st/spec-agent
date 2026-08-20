# Agent Runtime Architecture

## Overview

Agent Runtime manages Graph evolution.

It is not a domain-specific requirement bot.

```
Graph State
    |
    v
Observer
    |
    v
Reflection
    |
    v
Planner
    |
    v
Action Proposal
    |
    v
Runtime Validation
    |
    v
Graph Mutation
```

## Components

### Observer
Reads current graph context, routes, history and available context.

### Reflection
Evaluates:

- What is known?
- What is unknown?
- What conflicts exist?
- What risks exist?

Reflection does not mutate the graph.

### Planner
Chooses the next action.

The goal is not always asking a question. Actions may include creating knowledge, marking risk, summarizing or waiting.

### Executor
Produces action proposals. Runtime remains responsible for persistence.

## Anti Overfitting Rule

Agent prompts must describe graph reasoning, not a specific business domain.

The Agent should learn from Node context, not hard-coded requirement workflows.

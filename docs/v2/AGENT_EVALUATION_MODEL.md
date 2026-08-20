# Agent Evaluation Model

## Purpose

This document defines how Spec Agent evaluates Agent quality without overfitting to a small set of examples.

The goal is not to measure whether the model produces impressive text.

The goal is to measure whether the Agent improves the Graph state safely.

---

# 1. Evaluation Principles

Agent quality should be evaluated by:

- reasoning quality
- action quality
- graph evolution quality
- safety
- consistency

Not only by final answers.

---

# 2. Core Metrics

## Unknown Reduction

Question:

Did the Agent discover and reduce important unknowns?

Example:

Before:

```
Unknown:
Who is the user?
```

After:

```
User role confirmed
```

---

## Conflict Detection

Question:

Did the Agent identify contradictions?

Example:

```
Requirement:
Support every platform

Decision:
MVP supports only one platform
```

The Agent should detect the conflict.

---

## Action Quality

The Agent should select appropriate actions.

Bad:

```
Always ask another question
```

Good:

```
Create risk node because external dependency is unclear
```

---

## Graph Stability

The Agent should avoid unnecessary changes.

Measure:

- duplicate nodes
- meaningless routes
- repeated questions
- invalid mutations

---

# 3. Regression Testing

Agent changes require deterministic evaluation.

A test scenario contains:

```
Initial Graph
+
User Events
+
Expected Agent Actions
+
Expected Graph Changes
```

Example:

```
Given:
Requirement node exists
Authentication decision missing

Expected:
Agent creates clarification question
```

---

# 4. Avoiding Prompt Overfitting

Do not evaluate only:

"Can Agent generate good requirements?"

Evaluate broader abilities:

- identify missing knowledge
- reason over relationships
- create useful actions
- preserve graph consistency

The Agent should remain useful for future node types.

---

# 5. Human Evaluation

Some dimensions require human review:

- usefulness
- relevance
- whether exploration direction is valuable
- whether the Agent understands user intent

---

# 6. Safety Rules

Agent output is always a proposal.

Runtime validates:

- node validity
- relation validity
- permission rules
- lifecycle rules

The model cannot bypass Graph invariants.

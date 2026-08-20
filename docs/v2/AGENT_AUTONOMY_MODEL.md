# Spec Agent V2 Autonomy Model

## Goal

Spec Agent should behave as an AI collaborator for graph evolution, not an uncontrolled automation engine.

## Modes

### Advisor Mode (default)

The agent may:
- observe graph state
- identify unknowns
- detect conflicts and risks
- propose actions
- prepare candidate nodes

Important graph changes require user confirmation.

### Autonomous Mode

Advanced users may allow low-risk actions to execute automatically.
High-risk mutations still require confirmation.

## Action Risk Levels

LOW:
- create summaries
- generate explanations

MEDIUM:
- create questions
- create risk nodes
- create relations

HIGH:
- delete nodes
- merge history
- modify confirmed decisions
- lock specifications

## Principle

LLM proposes. Runtime validates. Graph executes.

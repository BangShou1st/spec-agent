# Agent Policy Engine

The policy engine controls whether proposed actions can execute.

## Modes

Advisor Mode:
- default
- important changes require approval

Autonomous Mode:
- low-risk actions may execute automatically
- high-risk actions still require approval

## Purpose

Prevent LLM output from directly changing user intent or corrupting graph history.

# V2 Architecture Convergence Review — 2026-08-21

> Review record only. Canonical authority remains `docs/v2/README.md` and the documents it lists.

## Purpose

Re-review the previous V2 drafts against the actual Phase 8 runtime invariants and the full product discussion, then remove ambiguous/over-coupled design before implementation begins.

## Resolved Issues

1. **Reflection was easy to misread as a mandatory extra LLM call.**  
   Resolved: Reflection + Planning are logical responsibilities inside one default Decision Cycle. Extra critic calls require explicit evidence/policy.

2. **Current 3-call answer workflow risked being preserved under new names.**  
   Resolved: target normal answer path is State Update + Decision = 2 serialized model calls while retaining AnswerPatch recovery checkpoint.

3. **Node lifecycle mixed generation progress with knowledge truth.**  
   Resolved: AgentRun/operation uses Pending/Running/Succeeded/Failed; claim-like knowledge can use Proposed/Confirmed/Challenged/Superseded.

4. **Pending UI risked persisting half-generated mutable Nodes.**  
   Resolved: persist Route/AgentRun first, render a virtual pending card, atomically persist accepted Node after validation.

5. **`Node = Knowledge Item` was too narrow.**  
   Resolved: Node = Workspace Unit; stable outer kinds are Knowledge/Interaction/Resource/Artifact.

6. **Blank-node/empty-project flow was under-specified.**  
   Resolved: user draft Node and empty workspace are first-class; project creation is model-free.

7. **Project title could still drive the first question.**  
   Resolved in target design: title is low-authority metadata and cannot become objective/requirement without grounded user/Graph evidence.

8. **Action space duplicated business semantics (`MARK_RISK`, `CREATE_SUMMARY`).**  
   Resolved: generic Graph/Interaction/Capability/Artifact action families; content subtype carries Risk/Requirement/etc.

9. **Skill and MCP were over-unified.**  
   Resolved: Capability Runtime offers one host boundary while MCP tools/resources/prompts remain intentionally distinct inside the MCP adapter.

10. **Python was incorrectly assigned authoritative Agent State construction.**  
    Resolved: Spring builds frozen `AgentInputSnapshot`; Python may implement Decision Engine only.

11. **Visible arrows and semantic relations were conflated.**  
    Resolved: Canvas arrows represent exploration continuation only; semantic relations are separate and hidden by default.

12. **Shared-node answer ambiguity was under-defined.**  
    Resolved: answers remain route-scoped; no Focus with differing answers stays neutral—no Active/first/latest fallback.

13. **Q1/Q2 wording was incorrectly written as replacing question text.**  
    Resolved: Q label replaces “当前问题/历史问题” labels; question body remains visible.

14. **Vertical option layout was missing from the document.**  
    Resolved: label above impact/explanation.

15. **Undo/Redo incorrectly leaned on existing Snapshot concepts.**  
    Resolved: typed Graph Operation History + operation-specific compensation/checkpoints; immutable answers/history are preserved.

16. **Evaluation over-weighted Unknown Reduction.**  
    Resolved: add groundedness, unsupported assertion, conflicts, user correction/rejection, Graph stability, latency/call count and loop-step metrics.

17. **Agent could still appear coupled to fixed Fork/Re-answer buttons.**  
    Resolved: free Node creation, continuation from any Node, contextual AI query and generic action protocol are first-class; legacy buttons become convenience operations.

## Result

The target design is now centered on three replaceable layers:

```text
Authoritative Graph Runtime
        |
        v
Bounded Decision Engine
        |
        v
Policy/Validator + Graph/Capability Executors
```

This preserves current reliable persistence/recovery behavior while allowing later Skill/MCP/resource/Python extensions without coupling them into Planner core.

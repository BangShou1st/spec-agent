# Phase 4 Exit Criteria

Phase 4 (fake agent runtime) is complete when all of the following hold:

- AgentRun lifecycle records `CREATED -> CONTEXT_BUILT -> MODEL_CALLED -> REFLECTED -> PERSISTED -> COMPLETED / FAILED`
- every fake model call carries a `contextSnapshotId`
- FakeModelAdapter supports all current `AgentTaskType` values deterministically
- Reflection gates validate context, node, patch, spec grounding, and source refs
- failed fake runs remain queryable
- rejected patch/spec artifacts are not persisted
- answer repair path can process existing immutable answers
- fake full loop creates Answer, AnswerPatch, next Node, and SpecSnapshot without real model calls
- normal context excludes sibling, archived, deleted, superseded route content
- no Spring AI / OpenAI SDK / LangChain4j / external provider SDK

Phase 5 may start only after these tests pass.
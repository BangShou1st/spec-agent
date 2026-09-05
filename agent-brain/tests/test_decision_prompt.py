"""Prompt-level assertions for the DECISION action-boundary experiment."""

from spec_agent_brain.prompts.decision import SYSTEM_PROMPT


def _assert_prompt_contains(*fragments: str) -> None:
    for fragment in fragments:
        assert fragment in SYSTEM_PROMPT


def test_answer_is_not_create_node_evidence():
    _assert_prompt_contains(
        "已有 answer、confirmed claim、resolved decision",
        "不得仅为了“记录一下”",
        "重新创建为 KNOWLEDGE/NOTE",
    )


def test_create_node_requires_a_new_durable_semantic_unit():
    _assert_prompt_contains(
        "新的、独立的 durable semantic unit",
        "尚未在现有 answer、lineage、effectiveClaims 或 Graph 中表达",
        "语义已经足够确定、可以安全持久化",
        "创建这个 node 本身确实推进当前任务",
    )


def test_resolved_decision_is_not_create_node_authorization():
    _assert_prompt_contains(
        "resolved/confirmed 本身不是新 Decision node 的授权",
        "不得重复询问",
    )


def test_request_user_input_requires_a_current_user_provided_blocker():
    _assert_prompt_contains(
        "missing user information、unresolved user choice、material ambiguity 或 unresolved conflict",
        "对当前可靠推进确实必需、用户现在能够提供",
        "问题会直接解决 blocker",
        "observation.unknowns 非空本身不自动授权 RUI",
    )


def test_decision_node_requires_explicit_authorization_or_persistence_request():
    _assert_prompt_contains(
        "用户明确授权 Agent 代为作出该决定",
        "用户明确要求把一个已经确定的决定持久化为新的 Decision node",
    )


def test_action_selection_never_uses_a_residual_action():
    _assert_prompt_contains(
        "每个候选动作都必须先由当前 event/state 中独立、直接的证据证明 eligible",
        "不得因为其他动作被排除就选择剩下的动作",
        "CREATE_NODE 绝不是 residual/default/fallback action",
    )


def test_capability_eligibility_is_separate_from_runtime_authorization():
    _assert_prompt_contains(
        "INVOKE_CAPABILITY 的 action eligibility 与 Runtime execution authorization 必须分开判断",
        "即使 Runtime 随后要求 confirmation，Planner 仍可选择 INVOKE_CAPABILITY",
        "auto_execute、requires_confirmation 和实际执行属于 Runtime policy",
    )


def test_wait_requires_positive_pending_dependency_evidence():
    _assert_prompt_contains(
        "WAIT 只有在存在真实且已知的 external dependency、async process、pending prerequisite 或 already-started operation",
        "uncertainty、missing information、用户选择、普通澄清",
        "都不是 WAIT 的 positive evidence",
    )


def test_respond_to_user_requires_sufficient_state():
    _assert_prompt_contains(
        "当前 state 已足以直接回应用户",
        "不需要新的用户信息、不需要新的 Graph mutation、不需要 capability、也不需要等待",
        "resolved 且没有新的 durable Graph 工作时，直接使用 RESPOND_TO_USER",
    )

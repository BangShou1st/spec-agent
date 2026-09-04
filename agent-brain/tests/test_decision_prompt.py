"""Prompt-level assertions for the DECISION action-boundary experiment."""

from spec_agent_brain.prompts.decision import SYSTEM_PROMPT


def test_decision_prompt_contains_action_boundary_precedence_rules():
    required_fragments = (
        "unresolved user choice",
        "REQUEST_USER_INPUT 优先",
        "不得因为历史冲突再次 REQUEST_USER_INPUT",
        "CREATE_NODE 是严格 gate",
        "availableCapabilities 中存在某项能力或 RESOURCE 并不等于现在应该调用",
        "WAIT 只用于 DECISION_INPUT 明确写出正在等待的外部/异步 prerequisite",
        "primary action 必须与当前 state 的第一个满足门槛的条件一致",
    )

    for fragment in required_fragments:
        assert fragment in SYSTEM_PROMPT

"""Deterministic safety checks for the frozen P3-B A/B decision rules."""

from p3b.p3b_semantic_ab import (
    _c08_correct,
    _c09_correct,
    _decide_c03,
    _decide_c08,
    _decide_c09,
    _no_unneeded_tool,
)


def test_c09_accepts_only_missing_query_question_without_capability_call():
    attempt = {
        "model_selected_action": "REQUEST_USER_INPUT",
        "oracle_candidate": {"acceptable_primary_actions": ["REQUEST_USER_INPUT"]},
        "model_raw_action": {"payload": {"questionText": "请问要检索什么内容？"}},
        "input_evidence": {"available_capabilities": [{
            "id": "memory.search", "required_arguments": ["query"],
        }]},
        "capability_calls": 0,
        "execution_state": "completed",
    }
    assert _c09_correct(attempt)
    attempt["model_raw_action"]["payload"]["questionText"] = "请问项目范围和要检索的内容是什么？"
    assert not _c09_correct(attempt)
    attempt["model_raw_action"]["payload"]["questionText"] = "请问要检索什么内容？"
    attempt["capability_calls"] = 1
    assert not _c09_correct(attempt)


def test_c08_requires_visible_complete_capability_and_real_execution():
    attempt = {
        "model_selected_action": "INVOKE_CAPABILITY",
        "oracle_candidate": {"acceptable_primary_actions": ["INVOKE_CAPABILITY"]},
        "automatic_semantic_review": {"capability_argument_audit": {
            "status": "DETERMINISTIC_VERIFIED", "capability_visible": True,
            "complete": True,
        }},
        "capability_calls": 1,
        "execution_state": "completed",
    }
    assert _c08_correct(attempt)
    attempt["automatic_semantic_review"]["capability_argument_audit"]["complete"] = False
    assert not _c08_correct(attempt)


def test_c07_tool_call_is_never_counted_as_no_unnecessary_call():
    assert _no_unneeded_tool({"model_selected_action": "RESPOND_TO_USER", "capability_calls": 0})
    assert not _no_unneeded_tool({"model_selected_action": "INVOKE_CAPABILITY", "capability_calls": 0})
    assert not _no_unneeded_tool({"model_selected_action": "RESPOND_TO_USER", "capability_calls": 1})


def test_c03_holdout_repeat_is_rejected_even_if_another_attempt_failed():
    a_attempt = {
        "scenario_id": "P3B-C03",
        "variant_id": "holdout",
        "scenario_hash": "same-facts",
        "candidate_label_status": "PENDING_HUMAN_REVIEW",
        "model_selected_action": "RESPOND_TO_USER",
        "failure_classification": "NONE",
        "prompt_hashes": {"state_update_input": "same-state", "decision_input": "prompt-a"},
    }
    b_attempt = {
        "scenario_id": "P3B-C03",
        "variant_id": "holdout",
        "scenario_hash": "same-facts",
        "candidate_label_status": "PENDING_HUMAN_REVIEW",
        "model_selected_action": "REQUEST_USER_INPUT",
        "failure_classification": "NONE",
        "prompt_hashes": {"state_update_input": "same-state", "decision_input": "prompt-b"},
    }
    a = {"report_status": "COMPLETE", "attempts": [a_attempt]}
    b = {"report_status": "COMPLETE", "attempts": [b_attempt]}
    assert _decide_c03(a, b)["status"] == "REJECT"
    b_attempt["prompt_hashes"]["decision_input"] = "prompt-a"
    assert _decide_c03(a, b)["status"] == "INCONCLUSIVE"


def test_incomplete_c08_and_missing_c09_arms_stay_inconclusive():
    incomplete = {"report_status": "INCOMPLETE", "stopped_after_provider_failure": True}
    assert _decide_c08({}, incomplete)["status"] == "INCONCLUSIVE"
    assert _decide_c09(None, None)["status"] == "INCONCLUSIVE"

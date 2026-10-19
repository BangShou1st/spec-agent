"""Deterministic safety checks for the frozen P3-B A/B decision rules."""

from p3b.p3b_semantic_ab import (
    _c08_correct,
    _assess_c09_attempt,
    _decide_c03,
    _decide_c08,
    _decide_c09,
    _no_unneeded_tool,
)


def _c09_attempt(action="REQUEST_USER_INPUT"):
    return {
        "scenario_id": "P3B-C09",
        "variant_id": "base",
        "model_selected_action": action,
        "oracle_candidate": {"acceptable_primary_actions": ["REQUEST_USER_INPUT"]},
        "input_evidence": {
            "memory_search_visible": True,
            "memory_search_query_required": True,
            "memory_search_missing_arguments": ["query"],
            "required_arguments_verified": True,
        },
        "automatic_semantic_review": {
            "clarification_structure_audit": {
                "status": "DETERMINISTIC_VERIFIED",
                "question_text_present": True,
                "semantic_intent": "UNRESOLVED",
            },
        },
        "capability_calls": 0,
        "execution_state": "completed",
        "model_request_count": 2,
        "failed_model_requests": 0,
        "provider_retries": 0,
        "judge_model_calls": 0,
        "failure_classification": "NONE",
        "payload_validity": "VALID",
        "source_reference_audit": {
            "source_refs_allowed": True,
            "invalidated_source_reference_used": False,
        },
        "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt"},
        "scenario_hash": "same-fixture",
        "candidate_label_status": "PENDING_HUMAN_REVIEW",
    }


def _c09_report(attempts):
    return {
        "report_status": "COMPLETE",
        "provider": "KILO",
        "model_id": "cohere/north-mini-code:free",
        "total_model_requests": len(attempts) * 2,
        "attempts": attempts,
    }


def test_c09_correct_query_followup_passes_structure_but_intent_stays_unresolved():
    attempt = {
        **_c09_attempt(),
        # Text is deliberately not interpreted by a keyword or scope regex.
        "model_raw_action": {"payload": {
            "questionText": "请问需要检索哪些关键词？范围按当前项目即可。",
        }},
    }
    assessment = _assess_c09_attempt(attempt)
    assert assessment["structure_status"] == "PASS"
    assert assessment["status"] == "UNRESOLVED"
    assert assessment["semantic_intent"] == "UNRESOLVED"


def test_c09_wrong_action_and_incomplete_evidence_are_distinguished():
    wrong = _c09_attempt("RESPOND_TO_USER")
    assert _assess_c09_attempt(wrong)["status"] == "INCORRECT"

    insufficient = _c09_attempt()
    insufficient["input_evidence"] = {"memory_search_visible": False}
    result = _assess_c09_attempt(insufficient)
    assert result["status"] == "UNRESOLVED"
    assert result["structure_status"] == "UNRESOLVED"

    historical_correct_action = {
        "model_selected_action": "REQUEST_USER_INPUT",
        "action_acceptable": True,
        "input_evidence": {},
        "capability_calls": 0,
        "execution_state": "completed",
    }
    assert _assess_c09_attempt(historical_correct_action)["status"] == "UNRESOLVED"

    historical_wrong_action = {
        **historical_correct_action,
        "model_selected_action": "RESPOND_TO_USER",
        "action_acceptable": False,
    }
    assert _assess_c09_attempt(historical_wrong_action)["status"] == "INCORRECT"

    malformed_clarification = _c09_attempt()
    malformed_clarification["automatic_semantic_review"]["clarification_structure_audit"][
        "question_text_present"] = False
    assert _assess_c09_attempt(malformed_clarification)["status"] == "INCORRECT"


def test_c09_pair_with_plausible_questions_never_auto_accepts_unresolved_intent():
    a = _c09_report([
        {**_c09_attempt(), "variant_id": variant, "prompt_hashes": {
            "state_update_input": "same", "decision_input": "prompt-a"}}
        for variant in ("base", "holdout")
    ])
    b = _c09_report([
        {**_c09_attempt(), "variant_id": variant, "prompt_hashes": {
            "state_update_input": "same", "decision_input": "prompt-b"}}
        for variant in ("base", "holdout")
    ])
    assert _decide_c09(a, b)["status"] == "INCONCLUSIVE"


def test_c09_clear_action_mismatch_is_rejected():
    a = _c09_report([
        {**_c09_attempt(), "variant_id": variant, "prompt_hashes": {
            "state_update_input": "same", "decision_input": "prompt-a"}}
        for variant in ("base", "holdout")
    ])
    b_attempts = [
        {**_c09_attempt(), "variant_id": variant, "prompt_hashes": {
            "state_update_input": "same", "decision_input": "prompt-b"}}
        for variant in ("base", "holdout")
    ]
    b_attempts[0]["model_selected_action"] = "RESPOND_TO_USER"
    b = _c09_report(b_attempts)
    assert _decide_c09(a, b)["status"] == "REJECT"


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

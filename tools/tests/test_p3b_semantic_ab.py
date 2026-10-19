"""Deterministic safety checks for the frozen P3-B A/B decision rules."""

from p3b.p3b_semantic_ab import (
    _c08_correct,
    _assess_c09_attempt,
    _arm_metrics,
    _decide_c03,
    _decide_c08,
    _decide_c09,
    _judge_c09_question,
    _no_unneeded_tool,
    _validate_c09_semantic_review,
)


def _c09_attempt(action="REQUEST_USER_INPUT"):
    return {
        "scenario_id": "P3B-C09",
        "variant_id": "base",
        "model_selected_action": action,
        "oracle_candidate": {"acceptable_primary_actions": ["REQUEST_USER_INPUT"]},
        "input_evidence": {
            "available_capabilities": [{
                "id": "memory.search", "required_arguments": ["query", "scope"]
            }],
            "memory_search_visible": True,
            "memory_search_query_required": True,
            "memory_search_missing_arguments": ["query"],
            "memory_search_provided_argument_names": ["scope"],
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


def test_c09_correct_query_followup_is_separately_semantically_verified():
    attempt = {
        **_c09_attempt(),
    # The deterministic judge validates an explicit request for missing search information.
        "model_raw_action": {"payload": {
            "questionText": "请问需要检索哪些关键词？范围按当前项目即可。",
        }},
    }
    assessment = _assess_c09_attempt(attempt)
    assert assessment["structure_status"] == "PASS"
    assert assessment["status"] == "CORRECT"
    assert assessment["semantic_intent"] == "PASS"


def test_c09_targeted_query_question_has_a_real_positive_acceptance_path():
    question = "请问需要检索哪些关键词？范围按当前项目即可。"
    attempt = _c09_attempt()
    attempt["model_raw_action"] = {"payload": {"questionText": question}}

    assessment = _assess_c09_attempt(attempt)

    assert assessment["structure_status"] == "PASS"
    assert assessment["status"] == "CORRECT"
    assert assessment["semantic_intent"] == "PASS"
    assert assessment["semantic_review"]["reviewer"] == "p3b-c09-rule-judge.v1"
    assert assessment["semantic_review"]["model_calls"] == 0


def test_c09_semantic_review_rejects_known_scope_and_unrelated_requests():
    for question in (
        "您希望检索哪个项目？",
        "请问您打算什么时候完成？",
    ):
        attempt = _c09_attempt()
        attempt["model_raw_action"] = {"payload": {"questionText": question}}
        result = _assess_c09_attempt(attempt)
        assert result["status"] == "INCORRECT", question
        assert result["semantic_intent"] == "INCORRECT", question


def test_c09_query_token_without_an_actual_request_is_not_semantic_proof():
    attempt = _c09_attempt()
    attempt["model_raw_action"] = {
        "payload": {"questionText": "缺少 query 字段；可以继续吗？"}
    }

    result = _assess_c09_attempt(attempt)

    assert result["status"] == "UNRESOLVED"
    assert result["semantic_intent"] == "UNRESOLVED"


def test_c09_generic_clarification_remains_unresolved():
    attempt = _c09_attempt()
    attempt["model_raw_action"] = {
        "payload": {"questionText": "能否再详细说明一下？"}
    }

    assert _assess_c09_attempt(attempt)["status"] == "UNRESOLVED"


def test_c09_query_word_or_confirmation_question_does_not_prove_missing_information_request():
    for question in (
        "请问 query 是否正确？",
        "Please confirm that the search terms are correct.",
        "The query is missing; can we continue?",
        "请告诉我关键词的定义。",
        "Could you explain the meaning of the search query?",
    ):
        attempt = _c09_attempt()
        attempt["model_raw_action"] = {"payload": {"questionText": question}}
        result = _assess_c09_attempt(attempt)
        assert result["status"] == "UNRESOLVED", question
        assert result["semantic_intent"] == "UNRESOLVED", question


def test_c09_contradictory_fixture_facts_are_unresolved():
    attempt = _c09_attempt()
    attempt["input_evidence"]["memory_search_missing_arguments"] = ["query", "scope"]
    attempt["model_raw_action"] = {
        "payload": {"questionText": "请问需要检索哪些关键词？"}
    }
    assert _assess_c09_attempt(attempt)["status"] == "UNRESOLVED"


def test_c09_internally_inconsistent_judge_output_is_unresolved():
    facts = _c09_attempt()["input_evidence"]
    review = _judge_c09_question("请问需要检索哪些关键词？", facts)
    review["reason_code"] = "UNRELATED_INFORMATION_REQUESTED"
    assessment = _validate_c09_semantic_review(review)
    assert assessment["status"] == "UNRESOLVED"
    assert assessment["reason_code"] == "JUDGE_OUTPUT_INVALID"


def test_c09_missing_schema_and_malformed_model_or_judge_evidence_fail_closed(monkeypatch):
    schema_unknown = _c09_attempt()
    schema_unknown["input_evidence"]["required_arguments_verified"] = False
    schema_unknown["model_raw_action"] = {
        "payload": {"questionText": "请问需要检索哪些关键词？"}
    }
    assert _assess_c09_attempt(schema_unknown)["status"] == "UNRESOLVED"

    malformed_model = _c09_attempt()
    malformed_model["automatic_semantic_review"]["clarification_structure_audit"][
        "question_text_present"] = False
    malformed_model["model_raw_action"] = {"payload": {"questionText": {"secret": "x"}}}
    assert _assess_c09_attempt(malformed_model)["status"] != "CORRECT"

    monkeypatch.setattr(
        "p3b.p3b_semantic_ab._judge_c09_question",
        lambda *_args, **_kwargs: {"status": "PASS", "reason": "missing required fields"},
    )
    malformed_judge = _c09_attempt()
    malformed_judge["model_raw_action"] = {
        "payload": {"questionText": "请问需要检索哪些关键词？"}
    }
    assessment = _assess_c09_attempt(malformed_judge)
    assert assessment["status"] == "UNRESOLVED"
    assert assessment["semantic_review"]["reason_code"] == "JUDGE_OUTPUT_INVALID"


def test_c09_ab_accepts_only_when_both_variants_are_semantically_verified():
    a = _c09_report([
        {**_c09_attempt("RESPOND_TO_USER"), "variant_id": variant,
         "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-a"}}
        for variant in ("base", "holdout")
    ])
    b_attempts = []
    for variant in ("base", "holdout"):
        attempt = _c09_attempt()
        attempt["variant_id"] = variant
        attempt["prompt_hashes"] = {
            "state_update_input": "same", "decision_input": "prompt-b"
        }
        attempt["model_raw_action"] = {
            "payload": {"questionText": "请问需要检索哪些关键词？"}
        }
        b_attempts.append(attempt)

    assert _decide_c09(a, _c09_report(b_attempts))["status"] == "ACCEPT"


def test_c09_direct_missing_argument_execution_is_never_accepted():
    attempt = _c09_attempt("INVOKE_CAPABILITY")
    attempt["eligibility_reason_codes"] = ["MISSING_REQUIRED_CAPABILITY_ARGUMENT"]
    attempt["capability_calls"] = 1
    attempt["execution_state"] = "failed"

    result = _assess_c09_attempt(attempt)

    assert result["status"] == "INCORRECT"
    assert result["status"] != "CORRECT"


def test_c09_input_mismatch_and_missing_variant_make_ab_inconclusive():
    a_attempts = [
        {**_c09_attempt(), "variant_id": variant,
         "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-a"}}
        for variant in ("base", "holdout")
    ]
    b_attempts = [
        {**_c09_attempt(), "variant_id": variant,
         "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-b"}}
        for variant in ("base", "holdout")
    ]
    b_attempts[0]["input_evidence"] = {"memory_search_visible": False}
    assert _decide_c09(_c09_report(a_attempts), _c09_report(b_attempts))["status"] == "INCONCLUSIVE"

    assert _decide_c09(_c09_report(a_attempts), _c09_report(b_attempts[:1]))["status"] == "INCONCLUSIVE"


def test_c09_malformed_semantic_review_schema_is_unresolved():
    result = _validate_c09_semantic_review({"status": "PASS", "reason": "looks good"})
    assert result["status"] == "UNRESOLVED"
    assert result["reason_code"] == "JUDGE_OUTPUT_INVALID"


def test_run_failed_contract_diagnostic_and_event_correlation_reach_arm_report():
    report = _c09_report([{
        **_c09_attempt("RESPOND_TO_USER"),
        "run_failed_evidence": {"present": True, "events": [{
            "event_id": "00000000-0000-4000-8000-000000000001",
            "diagnostic_id": "00000000-0000-4000-8000-000000000002",
            "reason": "model_contract_violation",
            "error_code": "model_contract_violation",
            "diagnostic_code": "MODEL_OUTPUT_SCHEMA_INVALID",
            "failure_stage": "brain.model_output_validation",
            "validator_id": "ModelDecisionOutput",
            "field_path": "action.payload.questionText",
            "expected_type": "string",
            "actual_type": "object",
            "boundary": "PYTHON_BRAIN",
            "java_wire_validator_entered": "false",
            "executor_reached": "false",
            "source_exception_type": "ValidationError",
        }]},
    }])

    metrics = _arm_metrics(report)

    assert metrics["run_failed_contract_diagnostic_count"] == 1
    evidence = metrics["run_failed_machine_codes"][0]
    assert evidence["event_id"] == "00000000-0000-4000-8000-000000000001"
    assert evidence["diagnostic_id"] == "00000000-0000-4000-8000-000000000002"
    assert evidence["field_path"] == "action.payload.questionText"
    assert evidence["executor_reached"] == "false"


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

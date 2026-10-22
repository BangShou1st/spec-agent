"""Deterministic safety checks for the frozen P3-B A/B decision rules."""

import pytest

from p3b.p3b_semantic_ab import (
    _c08_correct,
    _assess_c03_attempt,
    _assess_c09_attempt,
    _arm_metrics,
    _decide_c03,
    _decide_c08,
    _decide_c09,
    _judge_c03_clarification,
    _judge_c09_question,
    _no_unneeded_tool,
    _validate_c03_semantic_review,
    _validate_c09_semantic_review,
    evaluate_c09_only,
    evaluate_c03_only,
    C03_SEMANTIC_REVIEWER,
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


def test_c09_project_scope_context_question_is_correct_through_attempt_assessment():
    attempt = _c09_attempt()
    attempt["model_raw_action"] = {
        "payload": {"questionText": "请问需要在当前项目范围内检索哪些关键词？"}
    }

    assessment = _assess_c09_attempt(attempt)

    assert assessment["structure_status"] == "PASS"
    assert assessment["semantic_intent"] == "PASS"
    assert assessment["status"] == "CORRECT"


def test_c09_semantic_review_rejects_known_scope_and_unrelated_requests():
    for question in (
        "您希望检索哪个项目？",
        "请提供项目范围。",
        "请问您打算什么时候完成？",
    ):
        attempt = _c09_attempt()
        attempt["model_raw_action"] = {"payload": {"questionText": question}}
        result = _assess_c09_attempt(attempt)
        assert result["status"] == "INCORRECT", question
        assert result["semantic_intent"] == "INCORRECT", question


def test_c09_semantic_review_does_not_confuse_known_scope_context_with_query_request():
    for question in (
        "请问需要在当前项目范围内检索哪些关键词？",
        "在当前项目范围内，您希望检索哪些关键词？",
        "Which keywords should I search for in the current project scope?",
        "For the current project scope, which search terms should I use?",
        "请问需要检索哪些关键词？范围按当前项目即可。",
        "系统已提供当前项目范围。请问需要检索哪些关键词？",
        "Which search terms should I use? The current project is already the scope.",
        "请问需要检索哪些关键词？还需要搜索什么内容？",
    ):
        result = _judge_c09_question(question, _c09_attempt()["input_evidence"])
        assert result["status"] == "PASS", (question, result["reason_code"])


def test_c09_semantic_review_does_not_accept_why_method_or_format_questions():
    for question in (
        "为什么要检索？",
        "用什么方式检索？",
        "检索什么格式？",
        "What is the reason for searching?",
        "How should I search?",
        "What format should I search in?",
        "Which content format should I search in?",
        "What keywords define a search query?",
        "请问需要在当前项目范围内检索什么格式？",
    ):
        result = _judge_c09_question(question, _c09_attempt()["input_evidence"])
        assert result["status"] == "UNRESOLVED", (question, result["reason_code"])


def test_c09_scope_request_in_any_subquestion_overrides_query_keyword_request():
    for question in (
        "请问需要哪些关键词？您希望在哪个项目范围内检索？",
        "Which search terms should I use? Which project should I search?",
        "请问需要检索哪些关键词？另外，您希望什么时候完成？",
    ):
        result = _judge_c09_question(question, _c09_attempt()["input_evidence"])
        assert result["status"] == "INCORRECT", (question, result["reason_code"])


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




def _c03_attempt(action="REQUEST_USER_INPUT"):
    return {
        "eligibility_outcome": "PASSED",
        "scenario_id": "P3B-C03",
        "variant_id": "holdout",
        "model_selected_action": action,
        "oracle_candidate": {"acceptable_primary_actions": ["RESPOND_TO_USER", "CREATE_NODE"]},
        "input_evidence": {
            "answered_lineage_entries": 1,
            "unresolved_effective_claims": 0,
            "answered_topics": ["数据库", "存储", "postgresql"],
            "missing_requirements": [],
            "ambiguous_answer_given": False,
        },
        "model_raw_action": {
            "payload": {
                "questionText": "请问我们系统后续打算采用哪种数据库进行数据存储？",
                "purpose": "确认存储方案",
            }
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
        "prompt_hashes": {"state_update_input": "same-state", "decision_input": "prompt-b"},
        "scenario_hash": "same-facts",
        "candidate_label_status": "PENDING_HUMAN_REVIEW",
    }


def test_c03_reworded_repeat_question_is_classified_as_incorrect():
    # 覆盖场景 1: 已回答的问题被改写后再次询问 -> 判定为 INCORRECT / REPEATED_QUESTION_REQUESTED
    attempt = _c03_attempt()
    attempt["model_raw_action"]["payload"]["questionText"] = "请问我们系统后续打算采用哪种数据库进行数据存储？"

    assessment = _assess_c03_attempt(attempt)
    assert assessment["structure_status"] == "FAIL"
    assert assessment["status"] == "INCORRECT"
    assert assessment["semantic_intent"] == "INCORRECT"
    review = assessment["semantic_review"]
    assert review["reviewer"] == C03_SEMANTIC_REVIEWER
    assert review["status"] == "INCORRECT"
    assert review["reason_code"] == "REPEATED_QUESTION_REQUESTED"
    assert review["evidence"]["repeated_question_detected"] is True
    assert review["evidence"]["new_information_detected"] is False
    assert review["evidence"]["unresolved_target_detected"] is False


def test_c03_synonym_reworded_and_meta_questions_are_classified_as_incorrect():
    # 覆盖场景 2: 同一问题换用不同措辞/同义词，或发起元追问(索要占位/引用/详细说明)
    attempt = _c03_attempt()
    # 换词表达: 询问主存储方案
    attempt["model_raw_action"]["payload"]["questionText"] = "请问系统的主存储方案与数据库选型是什么？"
    assessment1 = _assess_c03_attempt(attempt)
    assert assessment1["status"] == "INCORRECT"
    assert assessment1["reason"] == "REPEATED_QUESTION_REQUESTED"

    # 元追问 1: 2026-10-19 c03-b 真实失败重现 (把已有答案当占位并索取完整详细信息)
    attempt["model_raw_action"]["payload"] = {
        "questionText": "请提供答案的完整详细信息。",
        "purpose": "答案现在为占位字段，需求更多详细信息才能接端？",
    }
    assessment2 = _assess_c03_attempt(attempt)
    assert assessment2["status"] == "INCORRECT"
    assert assessment2["reason"] == "REPEATED_QUESTION_REQUESTED"
    assert assessment2["semantic_review"]["evidence"]["repeated_question_detected"] is True

    # 元追问 2: 2026-10-18 run2 真实失败重现 (询问是否是引用或补充说明)
    attempt["model_raw_action"]["payload"] = {
        "questionText": "您刚才回答的是否是引用，还是希望补充说明？",
        "purpose": "确认引用来源",
    }
    assessment3 = _assess_c03_attempt(attempt)
    assert assessment3["status"] == "INCORRECT"
    assert assessment3["reason"] == "REPEATED_QUESTION_REQUESTED"


def test_c03_new_necessary_information_request_is_classified_as_pass():
    # 覆盖场景 3: 旧问题已解决，但出现真正新的必要问题 -> 允许追问 PASS / NEW_NECESSARY_INFORMATION_REQUESTED
    attempt = _c03_attempt()
    attempt["input_evidence"]["missing_requirements"] = ["高可用部署", "备份策略"]
    attempt["model_raw_action"]["payload"] = {
        "questionText": "请问数据库需要采用哪种高可用部署方案及备份策略？",
        "purpose": "明确高可用与容灾要求",
    }

    assessment = _assess_c03_attempt(attempt)
    assert assessment["structure_status"] == "PASS"
    assert assessment["status"] == "CORRECT"
    assert assessment["semantic_intent"] == "PASS"
    review = assessment["semantic_review"]
    assert review["status"] == "PASS"
    assert review["reason_code"] == "NEW_NECESSARY_INFORMATION_REQUESTED"
    assert review["evidence"]["new_information_detected"] is True
    assert review["evidence"]["repeated_question_detected"] is False


def test_c03_ambiguous_unresolved_answer_allows_clarification():
    # 覆盖场景 4: 用户回答含糊，不能证明原问题已解决 -> 允许追问 PASS / UNRESOLVED_CLARIFICATION_PERMITTED
    attempt = _c03_attempt()
    # 标注回答含糊且 claim 未决
    attempt["input_evidence"]["ambiguous_answer_given"] = True
    attempt["input_evidence"]["unresolved_effective_claims"] = 1
    attempt["model_raw_action"]["payload"] = {
        "questionText": "由于您刚才尚未确定具体数据库，请问主存储打算采用哪种方案？",
        "purpose": "继续明确未决数据库选择",
    }

    assessment = _assess_c03_attempt(attempt)
    assert assessment["structure_status"] == "PASS"
    assert assessment["status"] == "CORRECT"
    assert assessment["semantic_intent"] == "PASS"
    review = assessment["semantic_review"]
    assert review["status"] == "PASS"
    assert review["reason_code"] == "UNRESOLVED_CLARIFICATION_PERMITTED"
    assert review["evidence"]["unresolved_target_detected"] is True
    assert review["evidence"]["repeated_question_detected"] is False


def test_c03_insufficient_evidence_stays_unresolved():
    # 覆盖场景 5: 状态或证据不足时，不得自动认定问题已解决 -> UNRESOLVED
    facts = {
        "answered_lineage_entries": 0,
        "unresolved_effective_claims": 0,
        "answered_topics": [],
        "missing_requirements": [],
        "ambiguous_answer_given": False,
    }
    # 既无 answered 事实也无 unresolved 信号 -> TARGET_NOT_PROVEN
    review = _judge_c03_clarification("请问系统的数据库选型？", "", facts)
    assert review["status"] == "UNRESOLVED"
    assert review["reason_code"] == "TARGET_NOT_PROVEN"

    # 缺失问题文本 -> SEMANTIC_INPUT_MISSING
    review_empty = _judge_c03_clarification("", "", facts)
    assert review_empty["status"] == "UNRESOLVED"
    assert review_empty["reason_code"] == "SEMANTIC_INPUT_MISSING"

    # 损坏事实类型 -> SEMANTIC_INPUT_INVALID
    review_bad = _judge_c03_clarification("问题", "", "not-a-dict")
    assert review_bad["status"] == "UNRESOLVED"
    assert review_bad["reason_code"] == "SEMANTIC_INPUT_INVALID"


def test_c03_judge_output_validation_rejects_malformed_output():
    # 校验器: 结构缺漏、reason 与 evidence 冲突、伪造模型调用等，绝不放行
    valid = _judge_c03_clarification("请问？", "", {"answered_lineage_entries": 0})
    validated = _validate_c03_semantic_review(valid)
    assert validated["status"] == "UNRESOLVED"

    # 篡改 model_calls
    tampered = dict(valid, model_calls=1)
    assert _validate_c03_semantic_review(tampered)["reason_code"] == "JUDGE_OUTPUT_INVALID"

    # 篡改 evidence
    tampered_evidence = dict(valid, evidence={"repeated_question_detected": True})
    assert _validate_c03_semantic_review(tampered_evidence)["reason_code"] == "JUDGE_OUTPUT_INVALID"


def test_c03_non_repeat_acceptable_action_is_correct():
    # 合法的非重复动作 RESPOND_TO_USER 直接为 CORRECT
    attempt = _c03_attempt(action="RESPOND_TO_USER")
    assessment = _assess_c03_attempt(attempt)
    assert assessment["structure_status"] == "PASS"
    assert assessment["status"] == "CORRECT"
    assert assessment["reason"] == "candidate_acceptable_non_repeat_action"


def test_incomplete_c08_and_missing_c09_arms_stay_inconclusive():
    incomplete = {"report_status": "INCOMPLETE", "stopped_after_provider_failure": True}
    assert _decide_c08({}, incomplete)["status"] == "INCONCLUSIVE"
    assert _decide_c09(None, None)["status"] == "INCONCLUSIVE"


def _single_variant_report(requests_per_variant):
    """One C09 base attempt whose arm advertises a chosen request count."""
    attempt = _c09_attempt()
    report = _c09_report([attempt])
    report["total_model_requests"] = requests_per_variant
    return report


def test_c09_only_declares_the_local_budget_not_the_global_cap():
    # The frozen local Ollama C09 protocol budgets 8 requests; the evaluator
    # previously reported the global 64, which contradicted the protocol.
    a = _single_variant_report(4)
    b = _single_variant_report(4)
    report = evaluate_c09_only(a, b, max_requests=8)
    assert report["request_budget"]["maximum_new_upstream_model_requests"] == 8
    assert report["request_budget"]["actual_total_requests"] == 8

    # An arm beyond the declared local budget fails closed.
    over = evaluate_c09_only(a, _single_variant_report(5), max_requests=8)
    assert over["experiment_status"] == "BUDGET_EXCEEDED"
    assert over["rule_decisions"]["c09"]["status"] == "INCONCLUSIVE"

    # The default preserves the historical global cap.
    assert (evaluate_c09_only(a, b)["request_budget"]
            ["maximum_new_upstream_model_requests"]) == 64


def test_c09_only_lower_budget_tightens_the_arm_reliability_cap():
    # 5 requests is trustworthy under the global 64 cap but NOT under a local 8.
    a = _single_variant_report(5)
    b = _single_variant_report(5)
    assert evaluate_c09_only(a, b, max_requests=8)["experiment_status"] == "BUDGET_EXCEEDED"
    assert evaluate_c09_only(a, b)["experiment_status"] in {"INCOMPLETE", "COMPLETE"}


def _c09_nl_attempt(action="REQUEST_USER_INPUT", scenario_id="P3B-C09-NL"):
    attempt = _c09_attempt(action)
    attempt["scenario_id"] = scenario_id
    return attempt


def _c09_nl_pair(a_action, b_action):
    a = _c09_report([
        {**_c09_nl_attempt(a_action), "variant_id": variant,
         "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-a"}}
        for variant in ("base", "holdout")
    ])
    b_attempts = []
    for variant in ("base", "holdout"):
        attempt = {**_c09_nl_attempt(b_action), "variant_id": variant,
                   "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-b"}}
        if b_action == "REQUEST_USER_INPUT":
            attempt["model_raw_action"] = {
                "payload": {"questionText": "请问需要检索哪些关键词？范围按当前项目即可。"}}
        b_attempts.append(attempt)
    return a, _c09_report(b_attempts)


def test_c09_scenario_id_selects_the_natural_language_task_without_relaxing_checks():
    # The new P3B-C09-NL task gets the SAME paired A/B treatment as P3B-C09:
    # fresh A (wrong action) vs B (correct clarification) still ACCEPTs.
    a, b = _c09_nl_pair("RESPOND_TO_USER", "REQUEST_USER_INPUT")
    assert _decide_c09(a, b, scenario_id="P3B-C09-NL")["status"] == "ACCEPT"

    # But the ids are NOT interchangeable: the default P3B-C09 pairing fails
    # closed because the arms do not cover P3B-C09.
    assert _decide_c09(a, b)["status"] != "ACCEPT"

    # Nor does the new id relax the frozen candidate oracle: a B arm that keeps
    # proposing the forbidden action is still REJECT.
    a_ok, b_wrong = _c09_nl_pair("RESPOND_TO_USER", "RESPOND_TO_USER")
    assert _decide_c09(a_ok, b_wrong, scenario_id="P3B-C09-NL")["status"] == "REJECT"


def test_cross_scenario_c09_arms_are_never_paired():
    # A = historical P3B-C09, B = new P3B-C09-NL: different tasks, so no
    # same-task ACCEPT/REJECT may be fabricated. This stays INCONCLUSIVE.
    a = _c09_report([
        {**_c09_attempt(), "variant_id": variant,
         "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-a"}}
        for variant in ("base", "holdout")
    ])
    b = _c09_report([
        {**_c09_nl_attempt(), "variant_id": variant,
         "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-b"}}
        for variant in ("base", "holdout")
    ])
    decision = _decide_c09(a, b)
    assert decision["status"] == "INCONCLUSIVE"
    assert decision["status"] != "ACCEPT"
    assert "cross-scenario" in decision["evidence"]["reason"]


def test_c09_only_reports_the_configured_scenario_id():
    a = _single_variant_report(4)
    b = _single_variant_report(4)
    assert evaluate_c09_only(a, b)["scenario_id"] == "P3B-C09"
    assert evaluate_c09_only(a, b, scenario_id="P3B-C09-NL")["scenario_id"] == "P3B-C09-NL"


def test_c03_only_acceptance_and_rejection_paths():
    attempt_a_repeat = {
        **_c03_attempt(action="REQUEST_USER_INPUT"),
        "scenario_id": "P3B-C03-NL",
        "variant_id": "holdout",
        "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-a"},
    }
    attempt_b_progress = {
        **_c03_attempt(action="RESPOND_TO_USER"),
        "scenario_id": "P3B-C03-NL",
        "variant_id": "holdout",
        "oracle_candidate": {"acceptable_primary_actions": ["RESPOND_TO_USER", "CREATE_NODE"]},
        "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-b"},
    }
    attempt_new_req = {
        **_c03_attempt(action="REQUEST_USER_INPUT"),
        "scenario_id": "P3B-C03-NEW-REQ",
        "variant_id": "holdout",
        "oracle_candidate": {"acceptable_primary_actions": ["REQUEST_USER_INPUT"]},
        "input_evidence": {
            **_c03_attempt()["input_evidence"],
            "missing_requirements": ["高可用部署"],
        },
        "model_raw_action": {"payload": {"questionText": "请问高可用部署方案是什么？"}},
        "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-b"},
    }
    attempt_ambig = {
        **_c03_attempt(action="REQUEST_USER_INPUT"),
        "scenario_id": "P3B-C03-AMBIG",
        "variant_id": "holdout",
        "oracle_candidate": {"acceptable_primary_actions": ["REQUEST_USER_INPUT"]},
        "input_evidence": {
            **_c03_attempt()["input_evidence"],
            "ambiguous_answer_given": True,
            "unresolved_effective_claims": 1,
        },
        "model_raw_action": {"payload": {"questionText": "主存储具体打算采用哪种方案？"}},
        "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-b"},
    }

    report_a = {
        "report_status": "COMPLETE",
        "provider": "KILO",
        "model_id": "cohere/north-mini-code:free",
        "prompt_rule_set": "none",
        "total_model_requests": 3,
        "attempts": [attempt_a_repeat, {**attempt_new_req, "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-a"}}, {**attempt_ambig, "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-a"}}],
    }
    report_b = {
        "report_status": "COMPLETE",
        "provider": "KILO",
        "model_id": "cohere/north-mini-code:free",
        "prompt_rule_set": "c03",
        "total_model_requests": 3,
        "attempts": [attempt_b_progress, attempt_new_req, attempt_ambig],
    }

    scenarios = {"P3B-C03-NL", "P3B-C03-NEW-REQ", "P3B-C03-AMBIG"}
    res_accept = evaluate_c03_only(report_a, report_b, max_requests=8, scenario_ids=scenarios)
    assert res_accept["experiment_status"] == "COMPLETE"
    assert res_accept["rule_decisions"]["c03"]["status"] == "ACCEPT"

    # 若 B 臂也重复追问 -> REJECT
    attempt_b_repeat = {
        **attempt_a_repeat,
        "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-b"},
    }
    report_b_repeat = {
        **report_b,
        "attempts": [attempt_b_repeat, attempt_new_req, attempt_ambig],
    }
    res_reject = evaluate_c03_only(report_a, report_b_repeat, max_requests=8, scenario_ids=scenarios)
    assert res_reject["rule_decisions"]["c03"]["status"] == "REJECT"


def test_c03_distinguishes_four_semantic_categories():
    facts_base = {
        "answered_lineage_entries": 1,
        "unresolved_effective_claims": 0,
        "answered_topics": ["数据库", "存储", "postgresql"],
        "missing_requirements": [],
        "out_of_scope_topics": ["缓存", "消息队列", "中间件"],
        "ambiguous_answer_given": False,
    }

    # 语义 1: 真实重复追问 (对已确认事实改写、倒装、同义词或元追问)
    review_repeat = _judge_c03_clarification(
        "请问主存储方案与数据库选型是什么？", "确认数据库", facts_base)
    assert review_repeat["status"] == "INCORRECT"
    assert review_repeat["reason_code"] == "REPEATED_QUESTION_REQUESTED"
    assert review_repeat["evidence"]["repeated_question_detected"] is True
    assert review_repeat["evidence"]["out_of_scope_detected"] is False

    # 语义 2: 允许的必要澄清 (针对当前任务确实缺失的新信息提问)
    facts_new_req = {
        **facts_base,
        "missing_requirements": ["高可用部署", "容灾备份"],
    }
    review_new_req = _judge_c03_clarification(
        "请问数据库需要采用哪种高可用部署方案及容灾备份？", "明确容灾要求", facts_new_req)
    assert review_new_req["status"] == "PASS"
    assert review_new_req["reason_code"] == "NEW_NECESSARY_INFORMATION_REQUESTED"
    assert review_new_req["evidence"]["new_information_detected"] is True
    assert review_new_req["evidence"]["repeated_question_detected"] is False

    # 语义 3: 允许的必要澄清 (针对尚未解决的用户选择、含糊回答或冲突提问)
    facts_ambig = {
        **facts_base,
        "ambiguous_answer_given": True,
        "unresolved_effective_claims": 1,
    }
    review_ambig = _judge_c03_clarification(
        "由于您刚才尚未确定具体数据库，请问主存储打算采用哪种方案？", "继续明确未决选择", facts_ambig)
    assert review_ambig["status"] == "PASS"
    assert review_ambig["reason_code"] == "UNRESOLVED_CLARIFICATION_PERMITTED"
    assert review_ambig["evidence"]["unresolved_target_detected"] is True
    assert review_ambig["evidence"]["repeated_question_detected"] is False

    # 语义 4: 任务范围漂移或不必要追问 (引入当前任务不需要的技术栈/中间件)
    review_out_of_scope = _judge_c03_clarification(
        "项目是否还需要确定其他核心技术栈或中间件选型？",
        "在主数据库确定后，继续明确其他架构组件需求",
        facts_base)
    assert review_out_of_scope["status"] == "INCORRECT"
    assert review_out_of_scope["reason_code"] == "OUT_OF_SCOPE_QUESTION_REQUESTED"
    assert review_out_of_scope["evidence"]["out_of_scope_detected"] is True
    assert review_out_of_scope["evidence"]["repeated_question_detected"] is False


def test_c03_missing_answered_topics_does_not_blindly_label_repeat():
    # 当没有 answered_topics 时，不能盲目将任何追问都设为 REPEATED_QUESTION_REQUESTED
    facts_no_topics = {
        "answered_lineage_entries": 1,
        "unresolved_effective_claims": 0,
        "answered_topics": [],
        "missing_requirements": [],
        "ambiguous_answer_given": False,
    }
    # 场景 4 越界追问: 应识别为 OUT_OF_SCOPE_QUESTION_REQUESTED，而非 REPEATED_QUESTION_REQUESTED
    review_scope = _judge_c03_clarification(
        "项目是否还需要确定其他核心技术栈或中间件选型？",
        "在主数据库确定后，继续明确其他架构组件需求",
        facts_no_topics)
    assert review_scope["status"] == "INCORRECT"
    assert review_scope["reason_code"] == "OUT_OF_SCOPE_QUESTION_REQUESTED"
    assert review_scope["evidence"]["repeated_question_detected"] is False
    assert review_scope["evidence"]["out_of_scope_detected"] is True

    # 未知/无证据的问题: 严格 fail-closed 为 TARGET_NOT_PROVEN，绝不误标为重复追问
    review_unknown = _judge_c03_clarification(
        "后续系统演进的大致方向是什么？", "了解方向", facts_no_topics)
    assert review_unknown["status"] == "UNRESOLVED"
    assert review_unknown["reason_code"] == "TARGET_NOT_PROVEN"
    assert review_unknown["evidence"]["repeated_question_detected"] is False


def test_c03_evaluates_three_dimensions_separately():
    # 维度 1: ActionEligibility 门禁拒绝 (VETOED / MISSING_TYPED_PERSISTENCE_INTENT)，
    # 但动作语义正确 (CREATE_NODE 属于 candidate_acceptable)，执行状态为 awaiting_approval
    attempt_vetoed = {
        **_c03_attempt(action="CREATE_NODE"),
        "eligibility_outcome": "VETOED",
        "eligibility_reason_codes": ["MISSING_TYPED_PERSISTENCE_INTENT"],
        "execution_state": "awaiting_approval",
    }
    assess_vetoed = _assess_c03_attempt(attempt_vetoed)
    assert assess_vetoed["semantic_correctness"]["status"] == "PASS"  # 语义符合 candidate_acceptable
    assert assess_vetoed["action_eligibility"]["outcome"] == "VETOED"
    assert assess_vetoed["action_eligibility"]["reason_codes"] == ["MISSING_TYPED_PERSISTENCE_INTENT"]
    assert assess_vetoed["execution_outcome"]["state"] == "awaiting_approval"
    assert assess_vetoed["execution_outcome"]["authorized_mutation"] is False  # awaiting_approval 不视为完成持久化

    # 维度 2: ActionEligibility 门禁通过 (PASSED)，但模型偏离范围发起越界追问 (OUT_OF_SCOPE)，执行状态 completed
    attempt_passed_but_out_of_scope = {
        **_c03_attempt(action="REQUEST_USER_INPUT"),
        "eligibility_outcome": "PASSED",
        "eligibility_reason_codes": [],
        "execution_state": "completed",
        "model_raw_action": {
            "payload": {
                "questionText": "项目是否还需要确定其他核心技术栈或中间件选型？",
                "purpose": "在主数据库确定后，继续明确其他架构组件需求",
            }
        },
    }
    assess_scope = _assess_c03_attempt(attempt_passed_but_out_of_scope)
    assert assess_scope["action_eligibility"]["outcome"] == "PASSED"
    assert assess_scope["execution_outcome"]["state"] == "completed"
    assert assess_scope["semantic_correctness"]["status"] == "INCORRECT"  # 门禁 PASSED 绝不代表语义正确
    assert assess_scope["semantic_correctness"]["reason_code"] == "OUT_OF_SCOPE_QUESTION_REQUESTED"


def test_c03_gemini_lite_20261020_replay_and_attribution_correction():
    import json
    from pathlib import Path

    live_dir = Path("tools/p3b/c03-live-runs/gemini-lite-20261020")
    report_a = json.loads((live_dir / "c03_a.json").read_text(encoding="utf-8"))
    report_b = json.loads((live_dir / "c03_b.json").read_text(encoding="utf-8"))

    # 运行修复后的裁决器 (指定真实实验的模型与提供商标签)
    res = evaluate_c03_only(
        report_a, report_b, max_requests=8, scenario_ids={"P3B-C03-NL"},
        expected_provider="CUSTOM", expected_model="gemini-3.5-flash-lite")
    decision = res["rule_decisions"]["c03"]

    # 1. 结果依然严格 REJECT，绝不误升级为 ACCEPT，不推广 C03
    assert decision["status"] == "REJECT"

    # 2. 违规归因精确修正: 重复追问违规 = 0，越界追问违规 = 1
    evidence = decision["evidence"]
    assert evidence["b_repeat_violation_count"] == 0
    assert evidence["b_out_of_scope_violation_count"] == 1
    assert evidence["b_total_violation_count"] == 1
    assert evidence["checks"]["b_c03_nl_no_repeated_clarification"] is True
    assert evidence["checks"]["b_c03_nl_no_out_of_scope_clarification"] is False

    # 3. 三维验收独立可审计
    dims = evidence["dimensions"]
    assert dims["semantic_correctness"]["b_out_of_scope_violations"] == 1  # 语义维度
    # 动作资格维度: Arm A 两个变体都被门禁 vetoed
    a_elig = dims["action_eligibility"]["a_attempts_eligibility"]
    assert len(a_elig) == 2
    assert all(e["outcome"] == "VETOED" for e in a_elig)
    assert all("MISSING_TYPED_PERSISTENCE_INTENT" in e["reason_codes"] for e in a_elig)
    # 执行状态维度: Arm A 为 awaiting_approval，Arm B base 为 completed
    b_exec = dims["execution_outcome"]["b_execution_states"]
    base_exec = next(e for e in b_exec if e["variant"] == "base")
    assert base_exec["execution_state"] == "completed"


def test_c03_mixed_question_with_necessary_and_out_of_scope_is_rejected():
    # 混合问题: 同时包含必要缺失信息与越界技术栈
    # 规则: 越界具有一票否决权，绝不能因为包含必要信息就放行整个问题！
    facts = {
        "answered_lineage_entries": 1,
        "unresolved_effective_claims": 0,
        "answered_topics": ["数据库", "postgresql"],
        "missing_requirements": ["高可用部署"],
        "out_of_scope_topics": ["redis", "缓存组件"],
        "ambiguous_answer_given": False,
    }
    # 提问中既问了高可用部署(必要项)，又混入了 Redis 缓存(越界项)
    review = _judge_c03_clarification(
        "请问数据库需要采用哪种高可用部署方案？另外是否需要引入 Redis 缓存组件？",
        "确认部署与组件",
        facts,
    )
    assert review["status"] == "INCORRECT"
    assert review["reason_code"] == "OUT_OF_SCOPE_QUESTION_REQUESTED"
    assert review["evidence"]["out_of_scope_detected"] is True
    assert review["evidence"]["new_information_detected"] is False


def test_three_dimensions_are_strictly_independent():
    # 1. 结构与语义独立: 候选动作未在 oracle 集合中(structure_status: FAIL)，
    # 但语义本身未证实(TARGET_NOT_PROVEN)时，semantic_correctness 必须严格为 UNRESOLVED，不得被篡改为 INCORRECT！
    attempt_unresolved_sem = {
        **_c03_attempt(action="REQUEST_USER_INPUT"),
        "oracle_candidate": {"acceptable_primary_actions": ["CREATE_NODE"]},
        "input_evidence": {
            "answered_lineage_entries": 1,
            "unresolved_effective_claims": 0,
            "answered_topics": [],
            "missing_requirements": [],
            "ambiguous_answer_given": False,
        },
        "model_raw_action": {"payload": {"questionText": "后续系统演进的大致方向是什么？"}},
    }
    assessment = _assess_c03_attempt(attempt_unresolved_sem)
    assert assessment["structure_status"] == "FAIL"
    assert assessment["semantic_correctness"]["status"] == "UNRESOLVED"
    assert assessment["semantic_correctness"]["reason_code"] == "TARGET_NOT_PROVEN"

    # 2. 状态独立: awaiting_approval 必须记录为 awaiting_approval，且 completed 必须为 False！
    attempt_awaiting = {
        **_c03_attempt(action="CREATE_NODE"),
        "execution_state": "awaiting_approval",
    }
    assessment_awaiting = _assess_c03_attempt(attempt_awaiting)
    exec_out = assessment_awaiting["execution_outcome"]
    assert exec_out["state"] == "awaiting_approval"
    assert exec_out["awaiting_approval"] is True
    assert exec_out["completed"] is False

    # 3. 未经授权持久化变更必须被独立检出
    attempt_unauth_mutation = {
        **_c03_attempt(action="CREATE_NODE"),
        "eligibility_outcome": "VETOED",
        "mutation_occurred": True,
        "execution_state": "completed",
    }
    assessment_unauth = _assess_c03_attempt(attempt_unauth_mutation)
    assert assessment_unauth["execution_outcome"]["unauthorized_mutation_detected"] is True
    assert assessment_unauth["execution_outcome"]["authorized_mutation"] is False


def test_action_eligibility_veto_cannot_be_bypassed_by_semantic_correctness():
    # 不得仅凭语义正确就绕过 ActionEligibility！
    # 即使模型在语义维度表现完美(无重复、保留必要追问)，但如果 ActionEligibility 被 VETOED，裁决器必须 REJECT！
    attempt_a_repeat = {
        **_c03_attempt(action="REQUEST_USER_INPUT"),
        "scenario_id": "P3B-C03-NL",
        "variant_id": "holdout",
        "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-a"},
    }
    attempt_b_progress_vetoed = {
        **_c03_attempt(action="RESPOND_TO_USER"),
        "scenario_id": "P3B-C03-NL",
        "variant_id": "holdout",
        "oracle_candidate": {"acceptable_primary_actions": ["RESPOND_TO_USER", "CREATE_NODE"]},
        "eligibility_outcome": "VETOED",  # 关键: 门禁准入被否决！
        "eligibility_reason_codes": ["SECURITY_POLICY_RESTRICTION"],
        "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-b"},
    }
    report_a = {
        "report_status": "COMPLETE", "provider": "KILO", "model_id": "cohere/north-mini-code:free",
        "prompt_rule_set": "none", "total_model_requests": 1, "attempts": [attempt_a_repeat],
    }
    report_b = {
        "report_status": "COMPLETE", "provider": "KILO", "model_id": "cohere/north-mini-code:free",
        "prompt_rule_set": "c03", "total_model_requests": 1, "attempts": [attempt_b_progress_vetoed],
    }
    res = evaluate_c03_only(report_a, report_b, max_requests=8, scenario_ids={"P3B-C03-NL"})
    # 门禁一票否决生效: 绝不因为语义没有重复就 ACCEPT，必须是 REJECT！
    assert res["rule_decisions"]["c03"]["status"] == "REJECT"
    assert res["rule_decisions"]["c03"]["evidence"]["checks"]["b_action_eligibility_passed"] is False


def test_target_not_proven_propagates_to_inconclusive_never_accept():
    # TARGET_NOT_PROVEN 绝不默认为 ACCEPT，必须保持 INCONCLUSIVE！
    attempt_a_repeat = {
        **_c03_attempt(action="REQUEST_USER_INPUT"),
        "scenario_id": "P3B-C03-NL",
        "variant_id": "holdout",
        "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-a"},
    }
    attempt_b_unproven = {
        **_c03_attempt(action="REQUEST_USER_INPUT"),
        "scenario_id": "P3B-C03-NL",
        "variant_id": "holdout",
        "oracle_candidate": {"acceptable_primary_actions": ["RESPOND_TO_USER", "REQUEST_USER_INPUT"]},
        "input_evidence": {
            "answered_lineage_entries": 1,
            "unresolved_effective_claims": 0,
            "answered_topics": [],
            "missing_requirements": [],
            "ambiguous_answer_given": False,
        },
        "model_raw_action": {"payload": {"questionText": "后续系统演进的大致方向是什么？"}},
        "prompt_hashes": {"state_update_input": "same", "decision_input": "prompt-b"},
    }
    report_a = {
        "report_status": "COMPLETE", "provider": "KILO", "model_id": "cohere/north-mini-code:free",
        "prompt_rule_set": "none", "total_model_requests": 1, "attempts": [attempt_a_repeat],
    }
    report_b = {
        "report_status": "COMPLETE", "provider": "KILO", "model_id": "cohere/north-mini-code:free",
        "prompt_rule_set": "c03", "total_model_requests": 1, "attempts": [attempt_b_unproven],
    }
    res = evaluate_c03_only(report_a, report_b, max_requests=8, scenario_ids={"P3B-C03-NL"})
    # 证据不足不能证明改进，也不能升级为 ACCEPT: 保持 INCONCLUSIVE
    assert res["rule_decisions"]["c03"]["status"] == "INCONCLUSIVE"

    evidence = res["rule_decisions"]["c03"]["evidence"]
    assert evidence["semantic_prompt_verdict"]["status"] == "UNRESOLVED"
    assert evidence["runtime_safety_verdict"]["status"] == "PASS"
    assert evidence["overall_promotion_verdict"]["status"] == "REJECT"


@pytest.mark.parametrize("patch,semantic,runtime,reasons", [
    ({"eligibility_outcome": "VETOED", "execution_state": "failed"}, "PASS", "FAIL",
     {"ACTION_ELIGIBILITY_VETOED", "EXECUTION_NOT_COMPLETED"}),
    ({"eligibility_outcome": "PASSED", "execution_state": "awaiting_approval"}, "PASS", "FAIL",
     {"EXECUTION_NOT_COMPLETED"}),
    ({"eligibility_outcome": "NOT_MEASURED"}, "PASS", "FAIL", {"ACTION_ELIGIBILITY_NOT_PROVEN"}),
    ({"persisted_mutation": True, "mutation_authorized": False}, "PASS", "FAIL",
     {"UNAUTHORIZED_PERSISTED_MUTATION"}),
    ({"mutation_occurred": True}, "PASS", "FAIL", {"UNAUTHORIZED_PERSISTED_MUTATION"}),
    ({"eligibility_outcome": "VETOED", "execution_state": "awaiting_approval",
      "mutation_occurred": True, "mutation_authorized": False}, "PASS", "FAIL",
     {"ACTION_ELIGIBILITY_VETOED", "EXECUTION_NOT_COMPLETED", "UNAUTHORIZED_PERSISTED_MUTATION"}),
    ({"run_normal_terminal": False}, "PASS", "FAIL", {"EXECUTION_NOT_NORMAL_TERMINAL"}),
    ({}, "PASS", "PASS", set()),
])
def test_c03_promotion_hard_gates_accumulate_independent_reasons(patch, semantic, runtime, reasons):
    left = {**_c03_attempt(), "scenario_id": "P3B-C03-NL",
            "prompt_hashes": {"state_update_input": "same", "decision_input": "a"}}
    right = {**left, "model_selected_action": "CREATE_NODE",
             "prompt_hashes": {"state_update_input": "same", "decision_input": "b"}, **patch}
    assessment = _assess_c03_attempt(right)
    assert assessment["semantic_prompt_verdict"]["status"] == semantic
    assert assessment["runtime_safety_verdict"]["status"] == runtime
    assert set(assessment["runtime_safety_verdict"]["reason_codes"]) == reasons
    assert assessment["execution_outcome"]["completed"] == (right["execution_state"] == "completed")
    common = {"report_status": "COMPLETE", "provider": "KILO",
              "model_id": "cohere/north-mini-code:free", "total_model_requests": 2}
    result = evaluate_c03_only({**common, "prompt_rule_set": "none", "attempts": [left]},
                               {**common, "prompt_rule_set": "c03", "attempts": [right]},
                               scenario_ids={"P3B-C03-NL"})["rule_decisions"]["c03"]
    assert result["status"] == ("REJECT" if reasons else "ACCEPT")
    verdict = result["evidence"]["overall_promotion_verdict"]
    assert verdict["status"] == result["status"]
    assert reasons <= {r["reason_code"] for r in verdict["reasons"]}


def test_c03_semantic_failures_keep_runtime_pass_and_unknown_remains_unresolved():
    for question, expected in [("项目是否还需要确定其他核心技术栈或中间件选型？", "INCORRECT"),
                               ("后续系统演进的大致方向是什么？", "UNRESOLVED")]:
        item = _c03_attempt()
        item["model_raw_action"] = {"payload": {"questionText": question}}
        item["oracle_candidate"] = {"acceptable_primary_actions": ["REQUEST_USER_INPUT"]}
        assessment = _assess_c03_attempt(item)
        assert assessment["semantic_prompt_verdict"]["status"] == expected
        assert assessment["runtime_safety_verdict"]["status"] == "PASS"
        assert assessment["overall_promotion_verdict"]["status"] == "REJECT"


def test_c03_historical_reject_keeps_both_semantic_and_runtime_attribution():
    import json
    from pathlib import Path
    folder = Path("tools/p3b/c03-live-runs/gemini-lite-20261020")
    originals = {name: (folder / name).read_bytes() for name in
                 ("c03_ab_decision.json", "c03_ab_decision_p3bae_recheck.json")}
    result = evaluate_c03_only(json.loads((folder / "c03_a.json").read_text(encoding="utf-8")),
                               json.loads((folder / "c03_b.json").read_text(encoding="utf-8")),
                               max_requests=8, scenario_ids={"P3B-C03-NL"},
                               expected_provider="CUSTOM", expected_model="gemini-3.5-flash-lite")
    evidence = result["rule_decisions"]["c03"]["evidence"]
    assert evidence["semantic_prompt_verdict"]["status"] == "INCORRECT"
    assert evidence["runtime_safety_verdict"]["status"] == "FAIL"
    assert evidence["overall_promotion_verdict"]["status"] == "REJECT"
    reasons = {x["reason_code"] for x in evidence["overall_promotion_verdict"]["reasons"]}
    assert {"OUT_OF_SCOPE_QUESTION_REQUESTED", "ACTION_ELIGIBILITY_VETOED",
            "EXECUTION_NOT_COMPLETED"} <= reasons
    for name, content in originals.items():
        assert json.loads(content)["rule_decisions"]["c03"]["status"] == "REJECT"
        assert (folder / name).read_bytes() == content

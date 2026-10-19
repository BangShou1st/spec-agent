"""Deterministic evaluator for the frozen P3-B prompt A/B acceptance rules.

The evaluator compares only fresh arm reports. Candidate labels remain pending
human review; this tool measures agreement with those labels and never edits them.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


MAX_NEW_REQUESTS = 64
RULES = ("c03", "c08", "c09")
C09_SEMANTIC_REVIEWER = "p3b-c09-rule-judge.v1"
C09_SEMANTIC_INPUT_VERSION = "c09-clarification-input.v1"
C09_SEMANTIC_OUTPUT_VERSION = "c09-semantic-review.v1"

# 冻结的期望提供商/模型标签。默认值保持历史 Kilo 实验的约束不变;本地 CUSTOM
# (例如本机 Ollama) 实验通过 --expected-provider/--expected-model 显式传入,
# 完整性检查的强度不放松:两臂仍必须与同一个显式期望标签一致。
EXPECTED_PROVIDER = "KILO"
EXPECTED_MODEL = "cohere/north-mini-code:free"


def _attempt_map(report: dict[str, Any]) -> dict[tuple[str, str], dict[str, Any]]:
    attempts = report.get("attempts")
    if not isinstance(attempts, list):
        return {}
    return {
        (item.get("scenario_id"), item.get("variant_id")): item
        for item in attempts
        if isinstance(item, dict)
    }


def _action(attempt: dict[str, Any] | None) -> str | None:
    return attempt.get("model_selected_action") if attempt else None


def _capability_calls(attempt: dict[str, Any] | None) -> int:
    try:
        return int(attempt.get("capability_calls", 0)) if attempt else 0
    except (TypeError, ValueError):
        return 0


def _reliable_report(report: dict[str, Any] | None,
                     max_requests: int = MAX_NEW_REQUESTS) -> bool:
    if not report or report.get("report_status") != "COMPLETE":
        return False
    if report.get("provider") != EXPECTED_PROVIDER or report.get("model_id") != EXPECTED_MODEL:
        return False
    if report.get("stopped_after_rate_limit") or report.get("stopped_after_provider_failure"):
        return False
    if report.get("stopped_after_provider_retry") or report.get("stopped_after_unclassified_failure"):
        return False
    if report.get("stopped_on_request_budget"):
        return False
    if int(report.get("total_model_requests", max_requests + 1)) > max_requests:
        return False
    for attempt in report.get("attempts", []):
        if attempt.get("provider_retries", 0) or attempt.get("judge_model_calls", 0):
            return False
        if attempt.get("failed_model_requests", 0):
            return False
        if attempt.get("failure_classification") != "NONE":
            return False
    return True


def _pair_integrity(a: dict[str, Any] | None, b: dict[str, Any] | None,
                    expected_scenarios: set[str],
                    max_requests: int = MAX_NEW_REQUESTS) -> tuple[bool, list[str]]:
    errors: list[str] = []
    if not _reliable_report(a, max_requests) or not _reliable_report(b, max_requests):
        errors.append("one or both arms are incomplete or contain failed/retried requests")
    if a.get("model_id") != b.get("model_id") or a.get("provider") != b.get("provider"):
        errors.append("provider or model differs between arms")
    a_attempts = _attempt_map(a)
    b_attempts = _attempt_map(b)
    expected_keys = {
        (scenario, variant)
        for scenario in expected_scenarios
        for variant in ("base", "holdout")
    }
    if set(a_attempts) != expected_keys or set(b_attempts) != expected_keys:
        errors.append("scenario or variant coverage differs from the frozen sample set")
    for key in expected_keys & set(a_attempts) & set(b_attempts):
        left, right = a_attempts[key], b_attempts[key]
        if left.get("scenario_hash") != right.get("scenario_hash"):
            errors.append(f"scenario facts hash differs for {key}")
        if left.get("candidate_label_status") != "PENDING_HUMAN_REVIEW" \
                or right.get("candidate_label_status") != "PENDING_HUMAN_REVIEW":
            errors.append(f"candidate label state was changed for {key}")
        left_prompts = left.get("prompt_hashes", {})
        right_prompts = right.get("prompt_hashes", {})
        if left_prompts.get("state_update_input") != right_prompts.get("state_update_input"):
            errors.append(f"STATE_UPDATE prompt changed for {key}")
        if left_prompts.get("decision_input") in (None, "NOT_MEASURED") \
                or right_prompts.get("decision_input") in (None, "NOT_MEASURED"):
            errors.append(f"DECISION prompt hash missing for {key}")
        elif left_prompts.get("decision_input") == right_prompts.get("decision_input"):
            errors.append(f"DECISION prompt did not change for {key}")
        for attempt in (left, right):
            if attempt.get("model_request_count") != 2:
                errors.append(f"{key} did not make exactly two model requests in one arm")
            source = attempt.get("source_reference_audit", {})
            if source.get("source_refs_allowed") is not True:
                errors.append(f"source reference audit did not pass for {key}")
            if source.get("invalidated_source_reference_used") is True:
                errors.append(f"invalidated source was referenced for {key}")
            if attempt.get("payload_validity") == "INVALID":
                errors.append(f"protocol/payload validation failed for {key}")
    return not errors, errors


def _attempts_for(report: dict[str, Any], scenario: str) -> dict[str, dict[str, Any]]:
    return {
        variant: attempt
        for (scenario_id, variant), attempt in _attempt_map(report).items()
        if scenario_id == scenario
    }


def _arm_scenario_ids(report: dict[str, Any] | None) -> set[str]:
    """The scenarios an arm actually covers, read from its attempts."""
    if not report:
        return set()
    return {
        item.get("scenario_id")
        for item in report.get("attempts", [])
        if isinstance(item, dict) and item.get("scenario_id")
    }


def _c09_input_facts_verified(facts: Any) -> bool:
    if not isinstance(facts, dict):
        return False
    missing = facts.get("memory_search_missing_arguments")
    provided = facts.get("memory_search_provided_argument_names")
    capabilities = facts.get("available_capabilities")
    memory_search = ([item for item in capabilities if isinstance(item, dict)
                      and item.get("id") == "memory.search"]
                     if isinstance(capabilities, list) else [])
    required = memory_search[0].get("required_arguments") if len(memory_search) == 1 else None
    valid_required = (isinstance(required, list)
                      and all(isinstance(item, str) and item in {
                          "query", "scope", "routeRef", "maxResults"
                      } for item in required)
                      and len(required) == len(set(required)))
    return (
        facts.get("memory_search_visible") is True
        and facts.get("memory_search_query_required") is True
        and facts.get("required_arguments_verified") is True
        and len(memory_search) == 1
        and valid_required
        and "query" in required
        and isinstance(provided, list)
        and provided == ["scope"]
        and set(required) - set(provided) == {"query"}
        and isinstance(missing, list)
        and missing == ["query"]
    )


def _c09_review_unresolved(reason_code: str, input_sha256: str | None = None) -> dict[str, Any]:
    return {
        "reviewer": C09_SEMANTIC_REVIEWER,
        "input_version": C09_SEMANTIC_INPUT_VERSION,
        "output_version": C09_SEMANTIC_OUTPUT_VERSION,
        "status": "UNRESOLVED",
        "reason_code": reason_code,
        "input_sha256": input_sha256,
        "model_calls": 0,
        "evidence": {
            "query_request_detected": False,
            "known_argument_request_detected": False,
            "unrelated_request_detected": False,
        },
    }


def _validate_c09_semantic_review(value: Any) -> dict[str, Any]:
    """Validate the local rule evaluator output; malformed output is never a pass."""
    required = {
        "reviewer", "input_version", "output_version", "status", "reason_code",
        "input_sha256", "model_calls", "evidence",
    }
    if not isinstance(value, dict) or set(value) != required:
        return _c09_review_unresolved("JUDGE_OUTPUT_INVALID")
    status = value.get("status")
    reason = value.get("reason_code")
    evidence = value.get("evidence")
    if not isinstance(status, str) or not isinstance(reason, str):
        return _c09_review_unresolved("JUDGE_OUTPUT_INVALID")
    valid_decision = {
        ("PASS", "QUERY_INFORMATION_REQUESTED"),
        ("INCORRECT", "KNOWN_SCOPE_REQUESTED"),
        ("INCORRECT", "UNRELATED_INFORMATION_REQUESTED"),
        ("UNRESOLVED", "TARGET_NOT_PROVEN"),
        ("UNRESOLVED", "SEMANTIC_INPUT_MISSING"),
        ("UNRESOLVED", "SEMANTIC_INPUT_INVALID"),
    }
    expected_evidence = {
        "QUERY_INFORMATION_REQUESTED": (True, False, False),
        "KNOWN_SCOPE_REQUESTED": (False, True, False),
        "UNRELATED_INFORMATION_REQUESTED": (False, False, True),
        "TARGET_NOT_PROVEN": (False, False, False),
        "SEMANTIC_INPUT_MISSING": (False, False, False),
        "SEMANTIC_INPUT_INVALID": (False, False, False),
    }
    if (value.get("reviewer") != C09_SEMANTIC_REVIEWER
            or value.get("input_version") != C09_SEMANTIC_INPUT_VERSION
            or value.get("output_version") != C09_SEMANTIC_OUTPUT_VERSION
            or (status, reason) not in valid_decision
            or type(value.get("model_calls")) is not int
            or value.get("model_calls") != 0):
        return _c09_review_unresolved("JUDGE_OUTPUT_INVALID")
    digest = value.get("input_sha256")
    digest_valid = (isinstance(digest, str)
                    and re.fullmatch(r"[0-9a-f]{64}", digest) is not None)
    missing_digest_is_explicit = (
        digest is None and status == "UNRESOLVED"
        and reason in {"SEMANTIC_INPUT_MISSING", "SEMANTIC_INPUT_INVALID"}
    )
    if (not (digest_valid or missing_digest_is_explicit)
            or not isinstance(evidence, dict)
            or set(evidence) != {
                "query_request_detected", "known_argument_request_detected",
                "unrelated_request_detected",
            }
            or any(type(item) is not bool for item in evidence.values())
            or (evidence.get("query_request_detected"),
                evidence.get("known_argument_request_detected"),
                evidence.get("unrelated_request_detected"))
            != expected_evidence[reason]):
        return _c09_review_unresolved("JUDGE_OUTPUT_INVALID", digest if isinstance(digest, str) else None)
    return value


def _judge_c09_question(question_text: Any, facts: dict[str, Any]) -> dict[str, Any]:
    """Conservative, blind, task-specific rule judge; it sees no A/B label or oracle action."""
    if not isinstance(question_text, str) or not question_text.strip():
        return _c09_review_unresolved("SEMANTIC_INPUT_MISSING")
    if len(question_text) > 2000 or not _c09_input_facts_verified(facts):
        return _c09_review_unresolved("SEMANTIC_INPUT_INVALID")

    normalized = re.sub(r"\s+", " ", question_text).strip().casefold()
    required_arguments = sorted(facts["available_capabilities"][0]["required_arguments"])
    canonical_input = json.dumps(
        {"question_text": normalized,
         "scenario_facts": {
             "capability_id": "memory.search",
             "required_arguments": required_arguments,
             "missing_arguments": ["query"],
             "provided_argument_names": ["scope"],
         }},
        ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    digest = hashlib.sha256(canonical_input.encode("utf-8")).hexdigest()
    clauses = [part.strip() for part in re.split(r"[?？。!！;；,，\n]", normalized) if part.strip()]
    if not clauses:
        return _c09_review_unresolved("SEMANTIC_INPUT_MISSING", digest)

    ask_signal = re.compile(
        r"请问|麻烦|请|能否|可以|需要|希望|告诉|提供|补充|给出|填写|输入|"
        r"what|which|please|could you|can you|provide|share|tell me|enter|fill in|"
        r"哪些|什么|哪一|哪种|怎么|如何",
        re.IGNORECASE,
    )
    known_scope_request = re.compile(
        r"(?:哪个|哪一个|什么)\s*(?:项目|范围|路线)"
        r"|(?:项目|范围|路线)\s*(?:是)?\s*(?:什么|哪个|哪一个)"
        r"|^(?:(?:请问|请您|请你|麻烦)?(?:请|麻烦)?|(?:希望|需要)(?:您|你)?)\s*"
        r"(?:提供|补充|说明|告诉(?:我)?|明确|选择|指定)"
        r".{0,12}(?:项目|范围|路线)"
        r"|\b(?:which|what)\s+(?:project|scope|route)\b"
        r"|^(?:please\s+)?(?:specify|choose|provide|share|tell me)\s+"
        r"(?:the\s+)?(?:project|scope|route)\b"
        r"|\b(?:could|can) you\s+(?:specify|choose|provide|share|tell me)\s+"
        r"(?:the\s+)?(?:project|scope|route)\b",
        re.IGNORECASE,
    )
    unrelated_topic = re.compile(
        r"时间|日期|什么时候|何时|截止|期限|预算|负责人|权限|密码|"
        r"deadline|when|date|time|budget|owner|permission|password",
        re.IGNORECASE,
    )
    query_terms = re.compile(
        r"关键词|关键字|检索词|搜索词|查询词|搜索内容|检索内容|查询内容|"
        r"search\s+terms?|search\s+keywords?|keywords?|search\s+query|query\s+text",
        re.IGNORECASE,
    )
    query_meta_topic = re.compile(
        r"定义|含义|意义|解释|概念|作用|原因|理由|格式|正确|有效|"
        r"define|definition|meaning|explain|reason|purpose|format|correct|valid",
        re.IGNORECASE,
    )
    search_verb = re.compile(r"检索|搜索|查询|查找|search|retrieve|look up", re.IGNORECASE)
    direct_search_content_question = re.compile(
        r"(?:检索|搜索|查询|查找)(?:要|需要)?\s*(?:哪些|什么|哪一|哪种)"
        r"(?!格式|方式|方法|原因|目的|理由)"
        r"|\b(?:which|what)\s+(?:search\s+)?"
        r"(?:keywords?|terms?|content|information|topics?)\b.{0,80}"
        r"\b(?:search|retrieve|look up)\b"
        r"|\b(?:search|retrieve|look up)\s+(?:for\s+)?"
        r"(?:what|which)(?!\s+(?:format|way|method|reason|purpose))\b"
        r"|\bwhat\s+(?:should\s+(?:i|we)|do you want me to)\s+"
        r"(?:search for|look up|retrieve)\b",
        re.IGNORECASE,
    )
    direct_query_term_question = re.compile(
        r"(?<!为)(?:哪些|什么|哪一|哪种)\s*(?:搜索|检索|查询)?\s*"
        r"(?:关键词|关键字|检索词|搜索词|查询词|搜索内容|检索内容|查询内容)"
        r"|\b(?:which|what)\s+(?:search\s+)?"
        r"(?:keywords?|terms?|content|information|topics?)\b",
        re.IGNORECASE,
    )
    explicit_query_request = re.compile(
        r"(?:请|麻烦|请您|请你|please|could you|can you).{0,24}"
        r"(?:提供|补充|给出|填写|输入|告诉|provide|share|tell|enter|fill in|give).{0,24}"
        r"(?:关键词|关键字|检索词|搜索词|查询词|搜索内容|检索内容|查询内容|"
        r"search\s+terms?|search\s+keywords?|keywords?|search\s+query|query\s+text|\bquery\b)"
        r"|(?:\bquery\b|query\s+text).{0,24}"
        r"(?:提供|补充|给出|填写|输入|provide|share|tell me|enter|fill in)",
        re.IGNORECASE,
    )

    known_requested = any(known_scope_request.search(clause) for clause in clauses)
    if known_requested:
        status, reason = "INCORRECT", "KNOWN_SCOPE_REQUESTED"
    else:
        unrelated_requested = any(
            unrelated_topic.search(clause) and ask_signal.search(clause)
            for clause in clauses
        )
        if unrelated_requested:
            status, reason = "INCORRECT", "UNRELATED_INFORMATION_REQUESTED"
        else:
            query_requested = any(
                not query_meta_topic.search(clause)
                and (
                    (query_terms.search(clause)
                     and re.search(
                         r"(?:是(?:什么|哪些|哪一|哪种)|(?:what|which)\s+(?:are|is))",
                         clause,
                         re.IGNORECASE,
                     ))
                    or direct_query_term_question.search(clause)
                    or (search_verb.search(clause)
                        and direct_search_content_question.search(clause))
                    or explicit_query_request.search(clause)
                )
                for clause in clauses
            )
            if query_requested:
                status, reason = "PASS", "QUERY_INFORMATION_REQUESTED"
            else:
                status, reason = "UNRESOLVED", "TARGET_NOT_PROVEN"

    return {
        "reviewer": C09_SEMANTIC_REVIEWER,
        "input_version": C09_SEMANTIC_INPUT_VERSION,
        "output_version": C09_SEMANTIC_OUTPUT_VERSION,
        "status": status,
        "reason_code": reason,
        "input_sha256": digest,
        "model_calls": 0,
        "evidence": {
            "query_request_detected": reason == "QUERY_INFORMATION_REQUESTED",
            "known_argument_request_detected": reason == "KNOWN_SCOPE_REQUESTED",
            "unrelated_request_detected": reason == "UNRELATED_INFORMATION_REQUESTED",
        },
    }


def _candidate_acceptable(attempt: dict[str, Any] | None) -> bool:
    if not attempt:
        return False
    acceptable = attempt.get("oracle_candidate", {}).get("acceptable_primary_actions", [])
    return _action(attempt) in acceptable


def _arm_metrics(report: dict[str, Any]) -> dict[str, Any]:
    attempts = report.get("attempts", [])
    events = [event for attempt in attempts for event in attempt.get("model_request_events", [])]
    success_count = sum(event.get("status") == "SUCCESS" for event in events)
    failed_count = sum(event.get("status") != "SUCCESS" for event in events)
    classifications = Counter(attempt.get("failure_classification", "NOT_MEASURED")
                              for attempt in attempts)
    run_failed = []
    for attempt in attempts:
        evidence = attempt.get("run_failed_evidence", {})
        if evidence.get("present") is not True:
            continue
        for event in evidence.get("events", []):
            run_failed.append({
                "scenario_id": attempt.get("scenario_id"),
                "variant_id": attempt.get("variant_id"),
                "event_id": event.get("event_id", "NOT_RECORDED"),
                "diagnostic_id": event.get("diagnostic_id", "NOT_RECORDED"),
                "reason": event.get("reason", "NOT_RECORDED"),
                "error_code": event.get("error_code", "NOT_RECORDED"),
                "diagnostic_code": event.get("diagnostic_code", "NOT_RECORDED"),
                "failure_stage": event.get("failure_stage", "NOT_RECORDED"),
                "validator_id": event.get("validator_id", "NOT_RECORDED"),
                "field_path": event.get("field_path", "NOT_RECORDED"),
                "expected_type": event.get("expected_type", "NOT_RECORDED"),
                "actual_type": event.get("actual_type", "NOT_RECORDED"),
                "boundary": event.get("boundary", "NOT_RECORDED"),
                "java_wire_validator_entered": event.get(
                    "java_wire_validator_entered", "UNKNOWN"),
                "executor_reached": event.get("executor_reached", "UNKNOWN"),
                "source_exception_type": event.get("source_exception_type", "NOT_RECORDED"),
                "failure_classification": attempt.get("failure_classification", "NOT_MEASURED"),
            })
    action_measured = sum(attempt.get("action_measured") is True for attempt in attempts)
    automatic_reviews = Counter(
        attempt.get("automatic_semantic_review", {}).get("status", "UNRESOLVED")
        for attempt in attempts
    )
    return {
        "attempt_count": len(attempts),
        "model_requests": len(events),
        "successful_model_requests": success_count,
        "failed_model_requests": failed_count,
        "rate_limited_requests": int(report.get("rate_limited_count", 0)),
        "protocol_failure_attempts": classifications.get("MODEL_PROTOCOL_VIOLATION", 0),
        "failure_classifications": dict(classifications),
        "measured_actions": action_measured,
        "not_measured_actions": len(attempts) - action_measured,
        "automatic_semantic_reviews": dict(automatic_reviews),
        "run_failed_machine_codes": run_failed,
        "run_failed_contract_diagnostic_count": sum(
            event.get("diagnostic_code") not in (None, "NOT_RECORDED", "NONE")
            for event in run_failed),
        "stop_flags": {
            "rate_limit": bool(report.get("stopped_after_rate_limit", False)),
            "provider_failure": bool(report.get("stopped_after_provider_failure", False)),
            "provider_retry": bool(report.get("stopped_after_provider_retry", False)),
            "unclassified_failure": bool(report.get("stopped_after_unclassified_failure", False)),
            "request_budget": bool(report.get("stopped_on_request_budget", False)),
        },
    }


def _no_unneeded_tool(attempt: dict[str, Any] | None) -> bool:
    return bool(attempt) and _action(attempt) != "INVOKE_CAPABILITY" \
        and _capability_calls(attempt) == 0


def _c08_correct(attempt: dict[str, Any] | None) -> bool:
    if not attempt or _action(attempt) != "INVOKE_CAPABILITY":
        return False
    review = attempt.get("automatic_semantic_review", {})
    args = review.get("capability_argument_audit", {})
    return (_candidate_acceptable(attempt)
            and args.get("status") == "DETERMINISTIC_VERIFIED"
            and args.get("capability_visible") is True
            and args.get("complete") is True
            and _capability_calls(attempt) > 0
            and attempt.get("execution_state") == "completed")


def _assess_c09_attempt(attempt: dict[str, Any] | None) -> dict[str, Any]:
    """Classify C09 structure and blind, frozen-rule semantic evidence separately."""
    if not attempt:
        return {"status": "UNRESOLVED", "structure_status": "UNRESOLVED",
                "reason": "attempt_missing"}

    action = _action(attempt)
    if action is None:
        return {"status": "UNRESOLVED", "structure_status": "UNRESOLVED",
                "reason": "action_not_measured"}
    oracle = attempt.get("oracle_candidate")
    acceptable_actions = oracle.get("acceptable_primary_actions") if isinstance(oracle, dict) else None
    if isinstance(acceptable_actions, list):
        candidate_acceptable = action in acceptable_actions
    elif isinstance(attempt.get("action_acceptable"), bool):
        # Historical live reports predate the structured oracle copy but did
        # preserve the same frozen-label comparison as a boolean.
        candidate_acceptable = attempt["action_acceptable"]
    else:
        return {"status": "UNRESOLVED", "structure_status": "UNRESOLVED",
                "reason": "frozen_candidate_action_evidence_missing"}
    if not candidate_acceptable:
        return {"status": "INCORRECT", "structure_status": "FAIL",
                "reason": "action_outside_frozen_acceptable_set"}

    facts = attempt.get("input_evidence", {})
    missing = facts.get("memory_search_missing_arguments")
    if (facts.get("memory_search_visible") is not True
            or facts.get("memory_search_query_required") is not True
            or facts.get("required_arguments_verified") is not True
            or not isinstance(missing, list)
            or "query" not in missing
            or not isinstance(facts.get("memory_search_provided_argument_names"), list)
            or "scope" not in facts["memory_search_provided_argument_names"]):
        return {"status": "UNRESOLVED", "structure_status": "UNRESOLVED",
                "reason": "frozen_capability_schema_or_fixture_evidence_incomplete"}

    review = attempt.get("automatic_semantic_review", {})
    clarification = review.get("clarification_structure_audit", {})
    if action == "REQUEST_USER_INPUT":
        if (clarification.get("status") != "DETERMINISTIC_VERIFIED"
                or clarification.get("question_text_present") is not True
                or _capability_calls(attempt) != 0
                or attempt.get("execution_state") != "completed"):
            return {"status": "INCORRECT", "structure_status": "FAIL",
                    "reason": "clarification_action_structure_invalid"}
        raw_action = attempt.get("model_raw_action")
        raw_payload = raw_action.get("payload") if isinstance(raw_action, dict) else None
        question_text = raw_payload.get("questionText") if isinstance(raw_payload, dict) else None
        semantic_review = _validate_c09_semantic_review(
            _judge_c09_question(question_text, facts))
        semantic_status = semantic_review["status"]
        return {
            "status": "CORRECT" if semantic_status == "PASS"
            else "INCORRECT" if semantic_status == "INCORRECT" else "UNRESOLVED",
            "structure_status": "PASS",
            "semantic_intent": semantic_status,
            "semantic_review": semantic_review,
            "reason": semantic_review["reason_code"],
        }

    # The frozen C09 oracle allows only REQUEST_USER_INPUT. Other measured
    # actions are a clear mismatch; do not infer question meaning from text.
    if action == "INVOKE_CAPABILITY":
        args = review.get("capability_argument_audit", {})
        if (args.get("capability_visible") is True
                and args.get("required_arguments_verified") is True
                and "query" in args.get("missing_required_arguments", [])):
            return {"status": "INCORRECT", "structure_status": "FAIL",
                    "reason": "proposed_capability_missing_required_query"}
    return {"status": "INCORRECT", "structure_status": "FAIL",
            "reason": "action_outside_frozen_acceptable_set"}


def _result(status: str, evidence: dict[str, Any]) -> dict[str, Any]:
    return {"status": status, "evidence": evidence}


def _decide_c03(a: dict[str, Any] | None, b: dict[str, Any] | None,
                max_requests: int = MAX_NEW_REQUESTS) -> dict[str, Any]:
    if not a or not b:
        return _result("INCONCLUSIVE", {"reason": "A/B arm missing"})
    intact, errors = _pair_integrity(a, b, {"P3B-C03", "P3B-C01", "P3B-C02", "P3B-C07"},
                                    max_requests)
    if not intact:
        a03_holdout = _attempts_for(a, "P3B-C03").get("holdout")
        b03_holdout = _attempts_for(b, "P3B-C03").get("holdout")
        a_hashes = a03_holdout.get("prompt_hashes", {}) if a03_holdout else {}
        b_hashes = b03_holdout.get("prompt_hashes", {}) if b03_holdout else {}
        comparable_prompt_pair = bool(
            a03_holdout and b03_holdout
            and a03_holdout.get("scenario_hash") == b03_holdout.get("scenario_hash")
            and a03_holdout.get("candidate_label_status") == "PENDING_HUMAN_REVIEW"
            and b03_holdout.get("candidate_label_status") == "PENDING_HUMAN_REVIEW"
            and a_hashes.get("state_update_input") == b_hashes.get("state_update_input")
            and a_hashes.get("decision_input") not in (None, "NOT_MEASURED")
            and b_hashes.get("decision_input") not in (None, "NOT_MEASURED")
            and a_hashes.get("decision_input") != b_hashes.get("decision_input")
        )
        if (b03_holdout and _action(b03_holdout) == "REQUEST_USER_INPUT"
                and b03_holdout.get("failure_classification") == "NONE"
                and comparable_prompt_pair):
            return _result("REJECT", {
                "reason": "B selected a repeated C03 holdout clarification; the predeclared behavior check rejects it.",
                "integrity_errors": errors,
                "c03_holdout_a_action": _action(a03_holdout),
                "c03_holdout_b_action": _action(b03_holdout),
                "observed_regression_from_a": _action(a03_holdout) != "REQUEST_USER_INPUT",
                "same_state_update_prompt": True,
                "decision_prompt_changed": True,
                "semantic_equivalence_review": "UNRESOLVED; the frozen candidate label disallows the action, but no independent judge was called",
            })
        return _result("INCONCLUSIVE", {"integrity_errors": errors})
    aa, ba = _attempt_map(a), _attempt_map(b)
    c03_a = aa[("P3B-C03", "holdout")]
    c03_b = ba[("P3B-C03", "holdout")]
    c03_base_b = ba[("P3B-C03", "base")]
    c01_b = _attempts_for(b, "P3B-C01")
    c02_b = _attempts_for(b, "P3B-C02")
    c07_a = _attempts_for(a, "P3B-C07")
    c07_b = _attempts_for(b, "P3B-C07")
    checks = {
        "holdout_improved_from_repeat_to_nonrepeat": (
            _action(c03_a) == "REQUEST_USER_INPUT"
            and _action(c03_b) != "REQUEST_USER_INPUT"
            and _candidate_acceptable(c03_b)),
        "c03_base_acceptable": _candidate_acceptable(c03_base_b),
        "c01_required_clarification_preserved": all(
            _action(item) == "REQUEST_USER_INPUT" for item in c01_b.values()),
        "c02_verbatim_repeat_not_proposed": all(
            _action(item) != "REQUEST_USER_INPUT" for item in c02_b.values()),
        "c07_no_new_unnecessary_capability_call": all(
            _no_unneeded_tool(item) for item in c07_b.values())
            and all(_no_unneeded_tool(c07_b[v]) for v in c07_b)
            and all(_capability_calls(c07_b[v]) <= _capability_calls(c07_a[v])
                    for v in c07_b),
    }
    if not all(checks.values()):
        status = "REJECT" if _action(c03_b) == "REQUEST_USER_INPUT" else "INCONCLUSIVE"
    elif _action(c03_a) == "REQUEST_USER_INPUT":
        status = "ACCEPT"
    else:
        status = "REJECT"
    return _result(status, {
        "checks": checks,
        "c03_holdout_a_action": _action(c03_a),
        "c03_holdout_b_action": _action(c03_b),
        "semantic_equivalence_review": "UNRESOLVED; comparison is against the frozen candidate label, not an independent judge",
    })


def _decide_c08(a: dict[str, Any] | None, b: dict[str, Any] | None,
                max_requests: int = MAX_NEW_REQUESTS) -> dict[str, Any]:
    if not a or not b:
        return _result("INCONCLUSIVE", {"reason": "A/B arm missing"})
    intact, errors = _pair_integrity(a, b, {"P3B-C08", "P3B-C07"}, max_requests)
    if not intact:
        return _result("INCONCLUSIVE", {"integrity_errors": errors})
    a08, b08 = _attempts_for(a, "P3B-C08"), _attempts_for(b, "P3B-C08")
    a07, b07 = _attempts_for(a, "P3B-C07"), _attempts_for(b, "P3B-C07")
    a_errors = sum(not _c08_correct(item) for item in a08.values())
    b_errors = sum(not _c08_correct(item) for item in b08.values())
    checks = {
        "c08_both_variants_call_and_execute_necessary_capability": len(b08) == 2 and b_errors == 0,
        "c08_accuracy_improved": b_errors < a_errors,
        "c07_no_unnecessary_calls_in_b": len(b07) == 2 and all(_no_unneeded_tool(item) for item in b07.values()),
        "c07_no_regression_from_a": all(_capability_calls(b07[v]) <= _capability_calls(a07[v])
                                         for v in b07),
    }
    if not all(checks.values()):
        status = "REJECT" if b_errors > 0 or not checks["c07_no_unnecessary_calls_in_b"] else "INCONCLUSIVE"
    elif a_errors > 0:
        status = "ACCEPT"
    else:
        status = "REJECT"
    return _result(status, {
        "checks": checks,
        "c08_a_errors": a_errors,
        "c08_b_errors": b_errors,
        "semantic_scope": "candidate action, declared arguments, capability execution, and C07 calls are measured separately",
    })


def _decide_c09(a: dict[str, Any] | None, b: dict[str, Any] | None,
                max_requests: int = MAX_NEW_REQUESTS,
                scenario_id: str = "P3B-C09") -> dict[str, Any]:
    if not a or not b:
        return _result("INCONCLUSIVE", {"reason": "A/B arm missing"})
    # Cross-scenario arms (e.g. historical P3B-C09 as A and the new
    # P3B-C09-NL as B) are deliberately NOT paired: they are different tasks,
    # so a same-task ACCEPT/REJECT decision would be fabricated. Fail closed
    # with an explicit reason; each arm is still recorded independently.
    a_scenarios, b_scenarios = _arm_scenario_ids(a), _arm_scenario_ids(b)
    if a_scenarios != b_scenarios:
        return _result("INCONCLUSIVE", {
            "reason": "A and B arms cover different scenarios; cross-scenario arms are"
                      " recorded separately and are never paired",
            "a_scenarios": sorted(a_scenarios),
            "b_scenarios": sorted(b_scenarios),
        })
    intact, errors = _pair_integrity(a, b, {scenario_id}, max_requests)
    if not intact:
        return _result("INCONCLUSIVE", {"integrity_errors": errors})
    a09, b09 = _attempts_for(a, scenario_id), _attempts_for(b, scenario_id)
    if set(a09) != {"base", "holdout"} or set(b09) != {"base", "holdout"}:
        return _result("INCONCLUSIVE", {"reason": "C09 base/holdout coverage is incomplete"})
    input_mismatches = [variant for variant in ("base", "holdout")
                        if not isinstance(a09[variant].get("input_evidence"), dict)
                        or not isinstance(b09[variant].get("input_evidence"), dict)
                        or a09[variant].get("input_evidence") != b09[variant].get("input_evidence")]
    if input_mismatches:
        return _result("INCONCLUSIVE", {
            "reason": "C09 frozen input evidence is missing or differs between A and B",
            "input_evidence_mismatches": input_mismatches,
        })
    a_assessments = {variant: _assess_c09_attempt(item) for variant, item in a09.items()}
    b_assessments = {variant: _assess_c09_attempt(item) for variant, item in b09.items()}
    a_errors = sum(item["status"] == "INCORRECT" for item in a_assessments.values())
    b_errors = sum(item["status"] == "INCORRECT" for item in b_assessments.values())
    unresolved = any(item["status"] == "UNRESOLVED"
                     for item in (*a_assessments.values(), *b_assessments.values()))
    checks = {
        "b_structure_and_semantics_pass_without_calling": len(b09) == 2 and all(
            b_assessments[variant]["structure_status"] == "PASS"
            and b_assessments[variant]["status"] == "CORRECT"
            and _capability_calls(b09[variant]) == 0
            for variant in ("base", "holdout")),
        "behavior_improved_over_fresh_a": not unresolved and b_errors < a_errors,
        "missing_argument_proposal_remains_fail_closed": all(
            _action(item) != "INVOKE_CAPABILITY"
            or ("MISSING_REQUIRED_CAPABILITY_ARGUMENT" in item.get("eligibility_reason_codes", [])
                and _capability_calls(item) == 0)
            for item in (*a09.values(), *b09.values())),
    }
    if b_errors > 0:
        status = "REJECT"
    elif unresolved:
        status = "INCONCLUSIVE"
    elif not all(checks.values()):
        status = "INCONCLUSIVE"
    elif a_errors > 0:
        status = "ACCEPT"
    else:
        status = "REJECT"
    return _result(status, {
        "checks": checks,
        "c09_a_errors": a_errors,
        "c09_b_errors": b_errors,
        "c09_a_assessments": a_assessments,
        "c09_b_assessments": b_assessments,
        "evidence_levels": {
            "STRUCTURE": {
                "status": "PASS" if all(
                    item["structure_status"] == "PASS"
                    for item in (*a_assessments.values(), *b_assessments.values()))
                else "FAIL_OR_UNRESOLVED",
                "a": {variant: item["structure_status"]
                      for variant, item in a_assessments.items()},
                "b": {variant: item["structure_status"]
                      for variant, item in b_assessments.items()},
            },
            "SEMANTIC": {
                "reviewer": C09_SEMANTIC_REVIEWER,
                "status": "UNRESOLVED" if unresolved else "MEASURED",
                "model_calls": 0,
                "a": {variant: item.get("semantic_intent", "NOT_MEASURED")
                      for variant, item in a_assessments.items()},
                "b": {variant: item.get("semantic_intent", "NOT_MEASURED")
                      for variant, item in b_assessments.items()},
                "scope": "The question text is checked against the frozen C09 target and already-known scope; this does not infer model cognition.",
            },
            "EXPERIMENT": {
                "status": "INCONCLUSIVE" if unresolved or not intact else status,
                "input_evidence_compared": True,
                "required_variants": ["base", "holdout"],
                "fresh_paired_arms": True,
            },
        },
        "semantic_intent_policy": "PASS only when the question explicitly requests search information represented by the missing query; generic or ambiguous text stays UNRESOLVED.",
    })


def evaluate_reports(reports: dict[str, dict[str, Any] | None]) -> dict[str, Any]:
    pairs = {
        "c03": (reports.get("c03_a"), reports.get("c03_b")),
        "c08": (reports.get("c08_a"), reports.get("c08_b")),
        "c09": (reports.get("c09_a"), reports.get("c09_b")),
    }
    decisions = {
        "c03": _decide_c03(*pairs["c03"]),
        "c08": _decide_c08(*pairs["c08"]),
        "c09": _decide_c09(*pairs["c09"]),
    }
    c08_b = reports.get("c08_b")
    if (not reports.get("c09_a") and not reports.get("c09_b") and c08_b
            and c08_b.get("stopped_after_provider_failure")):
        decisions["c09"] = _result("INCONCLUSIVE", {
            "reason": "C09 live A/B was not launched after the C08 B arm hit the pre-registered provider-failure stop condition.",
            "stop_condition": "provider_failure",
        })
    accepted_before_c08 = ["c03"] if decisions["c03"]["status"] == "ACCEPT" else []
    accepted_before_c09 = accepted_before_c08.copy()
    if decisions["c08"]["status"] == "ACCEPT":
        accepted_before_c09.append("c08")
    expected_rules = {
        "c03_a": "none", "c03_b": "c03",
        "c08_a": ",".join(accepted_before_c08) or "none",
        "c08_b": ",".join(accepted_before_c08 + ["c08"]),
        "c09_a": ",".join(accepted_before_c09) or "none",
        "c09_b": ",".join(accepted_before_c09 + ["c09"]),
    }
    for arm_name, expected in expected_rules.items():
        arm = reports.get(arm_name)
        if arm is not None and arm.get("prompt_rule_set") != expected:
            rule = arm_name[:3]
            decisions[rule] = _result("INCONCLUSIVE", {
                "reason": f"{arm_name} prompt rule set was {arm.get('prompt_rule_set')!r}; expected {expected!r}",
            })
    provided = [report for report in reports.values() if report]
    request_counts = {name: int(report.get("total_model_requests", 0)) for name, report in reports.items() if report}
    total = sum(request_counts.values())
    status = "COMPLETE" if len(provided) == 6 and all(
        item["status"] in {"ACCEPT", "REJECT"} for item in decisions.values()) else "INCOMPLETE"
    if total > MAX_NEW_REQUESTS:
        status = "BUDGET_EXCEEDED"
        decisions = {name: _result("INCONCLUSIVE", {
            "reason": f"actual request count {total} exceeds frozen limit {MAX_NEW_REQUESTS}"})
            for name in RULES}
    review_counts = Counter(
        attempt.get("automatic_semantic_review", {}).get("status", "UNRESOLVED")
        for report in provided for attempt in report.get("attempts", []))
    arm_metrics = {name: _arm_metrics(report) for name, report in reports.items() if report}
    stop_reasons = sorted({
        reason
        for metrics in arm_metrics.values()
        for reason, occurred in metrics["stop_flags"].items()
        if occurred
    })
    return {
        "schema_version": "p3b-semantic-ab.v1",
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "pre_registered_protocol": "tools/p3b/p3b_ab_protocol_20261019.json",
        "experiment_status": status,
        "human_review_status": "PENDING_HUMAN_REVIEW",
        "oracle_policy": "Original 18x2 candidate oracle is frozen and remains unapproved.",
        "model": "cohere/north-mini-code:free",
        "request_budget": {
            "maximum_new_upstream_model_requests": MAX_NEW_REQUESTS,
            "actual_requests_by_arm": request_counts,
            "actual_total_requests": total,
            "automatic_judge_requests": 0,
            "judge_policy": "C09 uses a blind deterministic rule evaluator; it makes zero model calls and leaves generic or ambiguous questions UNRESOLVED.",
        },
        "semantic_evaluator": {
            "reviewer": C09_SEMANTIC_REVIEWER,
            "input_version": C09_SEMANTIC_INPUT_VERSION,
            "output_version": C09_SEMANTIC_OUTPUT_VERSION,
            "type": "DETERMINISTIC_RULES",
            "model_calls": 0,
            "blind_to_ab_arm_and_candidate_action": True,
            "does_not_claim_model_cognition": True,
        },
        "arm_measurements": arm_metrics,
        "experiment_stop_reasons": stop_reasons,
        "active_prompt_rules": [
            rule for rule in RULES if decisions[rule]["status"] == "ACCEPT"
        ],
        "rule_decisions": decisions,
        "automatic_review_counts": dict(review_counts),
        "arm_reports": reports,
        "historical_evidence_policy": "Prior live JSON was context only and was not reused as a fresh A/B arm.",
        "conclusion_scope": "Per-rule candidate-oracle evidence only; one fresh attempt per variant does not establish stable model improvement.",
    }


def evaluate_c09_only(a: dict[str, Any] | None, b: dict[str, Any] | None,
                      max_requests: int = MAX_NEW_REQUESTS,
                      scenario_id: str = "P3B-C09") -> dict[str, Any]:
    """Evaluate only the frozen C09 A/B pair (local, small-budget experiment).

    Reuses ``_decide_c09`` (and through it the ``_pair_integrity`` gate and the
    ``p3b-c09-rule-judge.v1`` deterministic judge) unchanged. It narrows the
    experiment scope to the C09 rule; it never relaxes an integrity or scoring
    check and never mutates the frozen oracle.

    ``max_requests`` is the request budget this evaluation is bound by. It MUST
    match the frozen local protocol (for the local Ollama C09 protocol that is
    8, not the global 64), and it is used BOTH for the reported budget and for
    the arm-reliability cap, so a lower declared budget tightens rather than
    loosens the integrity gate.

    ``scenario_id`` selects which C09 task the paired arms must share. It
    defaults to the historical ``P3B-C09`` (byte-identical to previous
    behaviour); the new natural-language task uses ``P3B-C09-NL``. Passing a
    different id narrows nothing: ``_pair_integrity`` still requires exactly the
    declared scenario's base + holdout in both arms.
    """
    decision = _decide_c09(a, b, max_requests, scenario_id)
    rule_errors: list[str] = []
    for arm_name, expected_rules, arm in (("c09_a", "none", a), ("c09_b", "c09", b)):
        if arm is None:
            rule_errors.append(f"{arm_name} missing")
        elif arm.get("prompt_rule_set") != expected_rules:
            rule_errors.append(
                f"{arm_name} prompt rule set was {arm.get('prompt_rule_set')!r};"
                f" expected {expected_rules!r}")
    if rule_errors:
        decision = _result("INCONCLUSIVE", {
            "reason": "prompt rule set mismatch", "rule_errors": rule_errors})
    provided = [report for report in (a, b) if report]
    request_counts = {name: int(report.get("total_model_requests", 0))
                      for name, report in (("c09_a", a), ("c09_b", b)) if report}
    total = sum(request_counts.values())
    experiment_status = "COMPLETE" if decision["status"] in {"ACCEPT", "REJECT"} else "INCOMPLETE"
    if total > max_requests:
        experiment_status = "BUDGET_EXCEEDED"
        decision = _result("INCONCLUSIVE", {
            "reason": f"actual request count {total} exceeds the declared limit {max_requests}"})
    arm_metrics = {name: _arm_metrics(report)
                   for name, report in (("c09_a", a), ("c09_b", b)) if report}
    stop_reasons = sorted({
        reason
        for metrics in arm_metrics.values()
        for reason, occurred in metrics["stop_flags"].items()
        if occurred
    })
    review_counts = Counter(
        attempt.get("automatic_semantic_review", {}).get("status", "UNRESOLVED")
        for report in provided for attempt in report.get("attempts", []))
    return {
        "schema_version": "p3b-semantic-ab-c09-local.v1",
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "scope": "C09-only local A/B; reuses the frozen C09 rule judge and _pair_integrity gate",
        "scenario_id": scenario_id,
        "pre_registered_protocol": "tools/p3b/p3b_ab_protocol_20261019.json",
        "experiment_status": experiment_status,
        "human_review_status": "PENDING_HUMAN_REVIEW",
        "oracle_policy": "Original 18x2 candidate oracle is frozen and remains unapproved.",
        "provider": EXPECTED_PROVIDER,
        "model": EXPECTED_MODEL,
        "request_budget": {
            "maximum_new_upstream_model_requests": max_requests,
            "actual_requests_by_arm": request_counts,
            "actual_total_requests": total,
            "automatic_judge_requests": 0,
            "judge_policy": "C09 uses a blind deterministic rule evaluator; it makes zero model calls and leaves generic or ambiguous questions UNRESOLVED.",
        },
        "semantic_evaluator": {
            "reviewer": C09_SEMANTIC_REVIEWER,
            "input_version": C09_SEMANTIC_INPUT_VERSION,
            "output_version": C09_SEMANTIC_OUTPUT_VERSION,
            "type": "DETERMINISTIC_RULES",
            "model_calls": 0,
            "blind_to_ab_arm_and_candidate_action": True,
            "does_not_claim_model_cognition": True,
        },
        "arm_measurements": arm_metrics,
        "experiment_stop_reasons": stop_reasons,
        "rule_decisions": {"c09": decision},
        "automatic_review_counts": dict(review_counts),
        "arm_reports": {"c09_a": a, "c09_b": b},
        "historical_evidence_policy": "Prior live JSON was context only and was not reused as a fresh A/B arm.",
        "conclusion_scope": "Single fresh attempt per variant on a local provider; it does not establish stable model improvement.",
    }


def _load(path: str | None) -> dict[str, Any] | None:
    if not path:
        return None
    return json.loads(Path(path).read_text(encoding="utf-8"))


def main() -> int:
    global EXPECTED_PROVIDER, EXPECTED_MODEL
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("c03_a", "c03_b", "c08_a", "c08_b", "c09_a", "c09_b"):
        parser.add_argument(f"--{name.replace('_', '-')}")
    parser.add_argument("--output", required=True)
    parser.add_argument("--expected-provider", default="KILO")
    parser.add_argument("--expected-model", default="cohere/north-mini-code:free")
    parser.add_argument("--c09-only", action="store_true",
                        help="Evaluate only the C09 A/B pair (local small-budget experiment).")
    parser.add_argument("--max-new-requests", type=int, default=MAX_NEW_REQUESTS,
                        help="Request budget the C09-only evaluation is bound by; it must match"
                             " the frozen local protocol (local Ollama C09 uses 8, not 64).")
    parser.add_argument("--c09-scenario-id", default="P3B-C09",
                        help="Scenario the paired C09 arms must share. Default P3B-C09 preserves"
                             " the historical result; the natural-language task is P3B-C09-NL.")
    args = parser.parse_args()
    EXPECTED_PROVIDER = args.expected_provider
    EXPECTED_MODEL = args.expected_model
    reports = {name: _load(getattr(args, name)) for name in
               ("c03_a", "c03_b", "c08_a", "c08_b", "c09_a", "c09_b")}
    if args.c09_only:
        report = evaluate_c09_only(reports["c09_a"], reports["c09_b"],
                                   max_requests=args.max_new_requests,
                                   scenario_id=args.c09_scenario_id)
    else:
        report = evaluate_reports(reports)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "output": str(output.resolve()),
        "experiment_status": report["experiment_status"],
        "actual_total_requests": report["request_budget"]["actual_total_requests"],
        "rules": {key: value["status"] for key, value in report["rule_decisions"].items()},
    }, ensure_ascii=False))
    return 0 if report["experiment_status"] == "COMPLETE" else 2


if __name__ == "__main__":
    raise SystemExit(main())

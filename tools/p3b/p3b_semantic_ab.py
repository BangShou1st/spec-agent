"""Deterministic evaluator for the frozen P3-B prompt A/B acceptance rules.

The evaluator compares only fresh arm reports. Candidate labels remain pending
human review; this tool measures agreement with those labels and never edits them.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


MAX_NEW_REQUESTS = 64
RULES = ("c03", "c08", "c09")


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


def _reliable_report(report: dict[str, Any] | None) -> bool:
    if not report or report.get("report_status") != "COMPLETE":
        return False
    if report.get("provider") != "KILO" or report.get("model_id") != "cohere/north-mini-code:free":
        return False
    if report.get("stopped_after_rate_limit") or report.get("stopped_after_provider_failure"):
        return False
    if report.get("stopped_after_provider_retry") or report.get("stopped_after_unclassified_failure"):
        return False
    if report.get("stopped_on_request_budget"):
        return False
    if int(report.get("total_model_requests", MAX_NEW_REQUESTS + 1)) > MAX_NEW_REQUESTS:
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
                    expected_scenarios: set[str]) -> tuple[bool, list[str]]:
    errors: list[str] = []
    if not _reliable_report(a) or not _reliable_report(b):
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
                "reason": event.get("reason", "NOT_RECORDED"),
                "error_code": event.get("error_code", "NOT_RECORDED"),
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
    """Classify C09 using safe schema/action facts; natural-language intent stays unresolved."""
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
            or "query" not in missing):
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
        return {
            "status": "UNRESOLVED",
            "structure_status": "PASS",
            "semantic_intent": "UNRESOLVED",
            "reason": "question_intent_cannot_be_proved_from_action_structure",
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


def _decide_c03(a: dict[str, Any] | None, b: dict[str, Any] | None) -> dict[str, Any]:
    if not a or not b:
        return _result("INCONCLUSIVE", {"reason": "A/B arm missing"})
    intact, errors = _pair_integrity(a, b, {"P3B-C03", "P3B-C01", "P3B-C02", "P3B-C07"})
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


def _decide_c08(a: dict[str, Any] | None, b: dict[str, Any] | None) -> dict[str, Any]:
    if not a or not b:
        return _result("INCONCLUSIVE", {"reason": "A/B arm missing"})
    intact, errors = _pair_integrity(a, b, {"P3B-C08", "P3B-C07"})
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


def _decide_c09(a: dict[str, Any] | None, b: dict[str, Any] | None) -> dict[str, Any]:
    if not a or not b:
        return _result("INCONCLUSIVE", {"reason": "A/B arm missing"})
    intact, errors = _pair_integrity(a, b, {"P3B-C09"})
    if not intact:
        return _result("INCONCLUSIVE", {"integrity_errors": errors})
    a09, b09 = _attempts_for(a, "P3B-C09"), _attempts_for(b, "P3B-C09")
    a_assessments = {variant: _assess_c09_attempt(item) for variant, item in a09.items()}
    b_assessments = {variant: _assess_c09_attempt(item) for variant, item in b09.items()}
    a_errors = sum(item["status"] == "INCORRECT" for item in a_assessments.values())
    b_errors = sum(item["status"] == "INCORRECT" for item in b_assessments.values())
    unresolved = any(item["status"] == "UNRESOLVED"
                     for item in (*a_assessments.values(), *b_assessments.values()))
    checks = {
        "b_clarification_structure_passes_without_calling": len(b09) == 2 and all(
            item["structure_status"] == "PASS" for item in b_assessments.values()),
        "behavior_improved_over_fresh_a": not unresolved and b_errors < a_errors,
        "missing_argument_proposal_remains_fail_closed": all(
            _action(item) != "INVOKE_CAPABILITY"
            or ("MISSING_REQUIRED_CAPABILITY_ARGUMENT" in item.get("eligibility_reason_codes", [])
                and _capability_calls(item) == 0)
            for item in b09.values()),
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
        "semantic_intent_policy": "UNRESOLVED unless a structured field proves which required argument is requested",
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
            "judge_policy": "No independent model judge was called; ambiguous semantic subchecks remain UNRESOLVED.",
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


def _load(path: str | None) -> dict[str, Any] | None:
    if not path:
        return None
    return json.loads(Path(path).read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("c03_a", "c03_b", "c08_a", "c08_b", "c09_a", "c09_b"):
        parser.add_argument(f"--{name.replace('_', '-')}")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    reports = {name: _load(getattr(args, name)) for name in
               ("c03_a", "c03_b", "c08_a", "c08_b", "c09_a", "c09_b")}
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

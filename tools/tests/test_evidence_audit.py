from pathlib import Path

from tools.p3b.evidence_audit import audit, summarize


def test_unmeasured_and_unknown_are_excluded_from_quality_denominator():
    result = summarize([
        {"scenario_id": "P3B-C08", "model_selection_measured": False, "model_selection_acceptable": True},
        {"scenario_id": "P3B-C08", "model_selection_measured": True, "model_selection_acceptable": False},
    ])
    assert result["model_selection"] == {"correct": 0, "measured": 1, "not_measured": 1}


def test_awaiting_approval_is_not_completed_even_with_acceptable_action():
    result = summarize([{"scenario_id": "P3B-C03-NL", "model_selection_measured": True,
                         "model_selection_acceptable": True, "eligibility_outcome": "VETOED",
                         "execution_state": "awaiting_approval"}])
    assert result["model_selection"]["correct"] == 1
    assert result["completed"] == {"count": 0, "measured_decisions": 1}


def test_historical_machine_evidence_regression():
    result = audit(Path(__file__).resolve().parents[2])
    assert result["status"] == "PASS"
    kilo = result["kilo"]
    assert kilo["requests"] == 26
    assert kilo["model_selection"] == {"correct": 6, "measured": 10, "not_measured": 3}
    assert kilo["c07_unnecessary_calls"] == {"count": 0, "measured": 2}
    assert kilo["c03_repeated_clarifications"] == {"count": 1, "measured": 2}
    assert "P3B-C06" not in kilo["scenarios_observed"]
    gemini = result["gemini"]
    assert gemini["requests"] == 8
    assert gemini["completed"] == {"count": 1, "measured_decisions": 4}
    assert gemini["historical_decision"] == "REJECT"
    assert gemini["human_review_status"] == "PENDING_HUMAN_REVIEW"

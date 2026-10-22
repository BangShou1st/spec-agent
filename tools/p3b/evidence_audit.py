"""Read-only historical P3-B evidence inventory. No model, network or database calls."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


KILO_FILES = (
    "p3b_live_run1_poolside_20261018.json",
    "p3b_live_run2_cohere_20261018.json",
    "p3b_live_run3_c13c15c09_20261018.json",
    "p3b_live_run4_c09_20261018.json",
)


def summarize(attempts: list[dict]) -> dict:
    """Count measured decisions separately from terminal execution and errors."""
    measured = [a for a in attempts if a.get("model_selection_measured") is True]
    c07 = [a for a in measured if a.get("scenario_id") == "P3B-C07"]
    c03 = [a for a in measured if a.get("scenario_id") == "P3B-C03"]
    completed = [a for a in measured if a.get("execution_state", a.get("execution_result")) == "completed"]
    return {
        "attempt_count": len(attempts),
        "requests": sum(a.get("model_request_count", 0) for a in attempts),
        "model_selection": {"correct": sum(a.get("model_selection_acceptable") is True for a in measured),
                            "measured": len(measured), "not_measured": len(attempts) - len(measured)},
        "completed": {"count": len(completed), "measured_decisions": len(measured)},
        "c07_unnecessary_calls": {"count": sum(a.get("unnecessary_capability_call") is True for a in c07),
                                  "measured": len(c07)},
        "c03_repeated_clarifications": {"count": sum(a.get("repeated_clarification") is True for a in c03),
                                       "measured": len(c03)},
        "scenarios_observed": sorted({a["scenario_id"] for a in attempts}),
    }


def audit(root: Path) -> dict:
    inputs = {}

    def read(relative: str, lines: bool = False):
        raw = (root / relative).read_bytes()
        inputs[relative] = hashlib.sha256(raw).hexdigest()
        content = raw.decode("utf-8-sig")
        return [json.loads(line) for line in content.splitlines() if line.strip()] if lines else json.loads(content)

    kilo_runs = [read("tools/p3b/" + name) for name in KILO_FILES]
    attempts = [dict(a, evidence_file=name) for name, run in zip(KILO_FILES, kilo_runs) for a in run["attempts"]]
    kilo = summarize(attempts)
    kilo["per_run_requests"] = [r["total_model_requests"] for r in kilo_runs]
    kilo["request_totals_consistent"] = all(r["total_model_requests"] == sum(a["model_request_count"] for a in r["attempts"]) for r in kilo_runs)
    kilo["action_eligibility_modes"] = sorted({r["action_eligibility_mode"] for r in kilo_runs})
    kilo["rows"] = attempts
    base = "tools/p3b/c03-live-runs/gemini-lite-20261020/"
    gemini_rows = []
    consistent = True
    for arm in ("a", "b"):
        report = read(base + f"c03_{arm}.json")
        sidecar = read(base + f"c03_{arm}.json.attempts.jsonl", lines=True)
        for attempt, row in zip(report["attempts"], sidecar):
            for field in ("scenario_id", "variant_id", "model_selected_action", "eligibility_outcome", "executed_action", "model_request_count"):
                consistent &= attempt.get(field) == row.get(field)
            consistent &= attempt.get("execution_result", attempt.get("execution_state")) == row.get("execution_state")
        consistent &= len(report["attempts"]) == len(sidecar)
        gemini_rows.extend(dict(a, arm=arm) for a in sidecar)
    decision = read(base + "c03_ab_decision.json")
    recheck = read(base + "c03_ab_decision_p3bae3_recheck_v2.json")
    gemini = summarize(gemini_rows)
    gemini.update({"report_sidecar_consistent": bool(consistent), "historical_decision": decision["rule_decisions"]["c03"]["status"],
                   "human_review_status": decision["human_review_status"], "rows": gemini_rows,
                   "independent_recheck_schema": recheck.get("schema_version")})
    return {"schema_version": "p3b-independent-evidence-audit.v1", "status": "PASS" if kilo["request_totals_consistent"] and consistent else "BLOCKED",
            "scope": "historical local artifacts only; no new model requests or database access",
            "kilo": kilo, "gemini": gemini,
            "document_only_claims": {"kilo_diagnostic_requests": 2, "gemini_compatibility_probe_requests": 1},
            "missing_evidence": ["Kilo C06 live attempt", "Kilo two diagnostic request-level artifacts",
                                 "Gemini compatibility probe request-level artifact", "independent persisted business outcome audit",
                                 "independent human approval of candidate oracle"], "input_sha256": inputs}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    print(json.dumps(audit(args.root), ensure_ascii=False, indent=2))

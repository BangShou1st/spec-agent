#!/usr/bin/env python3
"""Offline B-2 shadow ranking replay.

This evaluator derives generic semantic facts from frozen trace snapshots and
never branches on scenario, variant, repetition, seed, or benchmark labels.
An optional external oracle is used only by the reporting layer to compare a
shadow winner with historical scorer expectations.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any, Iterable

from spec_agent_brain.contracts import protocol
from spec_agent_brain.contracts.ranking import (
    ActionAssessment,
    RankingPriorityClass,
    RankingReasonCode,
    RankingScores,
    SemanticRanking,
    SemanticRankingError,
    select_winner,
)


def _load(path: Path) -> Iterable[dict[str, Any]]:
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line.strip():
            yield json.loads(line)


def _stages(row: dict[str, Any]) -> dict[str, Any]:
    return row.get("semantic_trace", {}).get("stages", {})


def _decision_request(row: dict[str, Any]) -> dict[str, Any] | None:
    stage = _stages(row).get("DECISION_INPUT", {})
    request = stage.get("runtime_request")
    return request if isinstance(request, dict) else None


def _behavioral_complete(row: dict[str, Any]) -> bool:
    request = _decision_request(row)
    final = _stages(row).get("FINAL_RESULT", {})
    return (
        request is not None
        and row.get("execution_result") == "completed"
        and row.get("actual_primary_action")
        and final.get("infrastructure_error") is None
    )


def _unresolved_claims(snapshot: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        claim for claim in snapshot.get("effectiveClaims", [])
        if claim.get("status") == "unresolved"
    ]


def _generic_eligibility(snapshot: dict[str, Any]) -> list[str]:
    """Mirror only the existing generic hard mask for C replay.

    This deliberately contains no scenario-specific condition and is not the
    production evaluator. B+ replay consumes the recorded Runtime mask.
    """
    eligible = list(protocol.ACTION_FAMILIES)
    eligible.remove("WAIT")
    if not snapshot.get("availableCapabilities"):
        eligible.remove("INVOKE_CAPABILITY")
    unresolved = _unresolved_claims(snapshot)
    if any(claim.get("kind") in {"conflict", "open_question"} for claim in unresolved):
        eligible.remove("CREATE_NODE")
    return eligible


def _recorded_eligibility(row: dict[str, Any], snapshot: dict[str, Any]) -> list[str]:
    stage = _stages(row).get("ACTION_ELIGIBILITY", {})
    recorded = stage.get("computed_eligible_set")
    if isinstance(recorded, list):
        return list(recorded)
    return _generic_eligibility(snapshot)


def _first_refs(snapshot: dict[str, Any], preferred: str | None = None) -> list[str]:
    refs = [str(ref) for ref in snapshot.get("allowedSourceRefs", [])]
    if preferred:
        matching = [ref for ref in refs if ref.startswith(preferred)]
        if matching:
            return matching[:2]
    return refs[:2]


def _facts(snapshot: dict[str, Any], request: dict[str, Any]) -> dict[str, bool]:
    unresolved = _unresolved_claims(snapshot)
    lineage = snapshot.get("lineage", [])
    resource_count = sum(
        entry.get("node", {}).get("kind") == "RESOURCE" for entry in lineage
    )
    has_answered = any(entry.get("answer") is not None for entry in lineage)
    event = request.get("event", {})
    submitted = event.get("kind") == "ANSWER_SUBMITTED"
    has_confirmed = any(claim.get("status") == "confirmed" for claim in snapshot.get("effectiveClaims", []))
    unresolved_user_need = bool(unresolved) or (submitted and not has_confirmed)
    unresolved_confidences = [
        claim.get("confidence") for claim in unresolved
        if isinstance(claim.get("confidence"), (int, float))
    ]
    external_need = bool(
        resource_count == 1
        and snapshot.get("availableCapabilities")
        and unresolved
        and not has_confirmed
        and any(0 < confidence <= 0.5 for confidence in unresolved_confidences)
    )
    missing_user_information = unresolved_user_need and not external_need
    direct_completion = not unresolved_user_need and not external_need
    return {
        "unresolved_user_need": unresolved_user_need,
        "missing_user_information": missing_user_information,
        "external_need": external_need,
        "direct_completion": direct_completion,
        "has_answered": has_answered,
    }


def _assessment(family: str, facts: dict[str, bool], snapshot: dict[str, Any]) -> ActionAssessment:
    refs = _first_refs(snapshot)
    if family == "REQUEST_USER_INPUT":
        applicable = facts["missing_user_information"]
        return ActionAssessment(
            family=family,
            applicable=applicable,
            priority_class=(RankingPriorityClass.BLOCKING
                            if applicable else RankingPriorityClass.OPTIONAL_PROGRESS),
            reason_codes=([RankingReasonCode.MISSING_USER_INFORMATION]
                          if applicable else [RankingReasonCode.NOT_NEEDED]),
            evidence_refs=refs if applicable else [],
            scores=RankingScores(
                blocker_closure=2 if applicable else 0,
                goal_progress=2 if applicable else 0,
                external_need=0,
                completion_proximity=0,
                payload_readiness=2 if applicable else 0,
            ),
        )
    if family == "INVOKE_CAPABILITY":
        applicable = facts["external_need"]
        return ActionAssessment(
            family=family,
            applicable=applicable,
            priority_class=(RankingPriorityClass.REQUIRED_EXTERNAL_STEP
                            if applicable else RankingPriorityClass.OPTIONAL_PROGRESS),
            reason_codes=([RankingReasonCode.EXTERNAL_INFORMATION_REQUIRED,
                           RankingReasonCode.GROUNDED_ARGUMENTS_AVAILABLE]
                          if applicable else [RankingReasonCode.NOT_NEEDED]),
            evidence_refs=_first_refs(snapshot, "node:") if applicable else [],
            scores=RankingScores(
                blocker_closure=0,
                goal_progress=2 if applicable else 0,
                external_need=2 if applicable else 0,
                completion_proximity=1 if applicable else 0,
                payload_readiness=2 if applicable else 0,
            ),
        )
    if family == "RESPOND_TO_USER":
        applicable = facts["direct_completion"]
        return ActionAssessment(
            family=family,
            applicable=applicable,
            priority_class=(RankingPriorityClass.DIRECT_COMPLETION
                            if applicable else RankingPriorityClass.OPTIONAL_PROGRESS),
            reason_codes=([RankingReasonCode.GOAL_SATISFIED]
                          if applicable else [RankingReasonCode.NOT_NEEDED]),
            evidence_refs=refs if applicable else [],
            scores=RankingScores(
                blocker_closure=0,
                goal_progress=2 if applicable else 0,
                external_need=0,
                completion_proximity=2 if applicable else 0,
                payload_readiness=2 if applicable else 0,
            ),
        )
    return ActionAssessment(
        family=family,
        applicable=False,
        priority_class=RankingPriorityClass.OPTIONAL_PROGRESS,
        reason_codes=[RankingReasonCode.NOT_NEEDED],
        evidence_refs=[],
        scores=RankingScores(
            blocker_closure=0,
            goal_progress=0,
            external_need=0,
            completion_proximity=0,
            payload_readiness=0,
        ),
    )


def _representation_fingerprint(ranking: SemanticRanking) -> str:
    """Fingerprint rank semantics while ignoring Runtime UUID values in refs."""
    normalized = []
    for assessment in sorted(ranking.assessments, key=lambda value: value.family):
        normalized.append({
            "family": assessment.family,
            "applicable": assessment.applicable,
            "priorityClass": assessment.priority_class,
            "reasonCodes": sorted(assessment.reason_codes),
            "evidenceKinds": sorted(ref.split(":", 1)[0] for ref in assessment.evidence_refs),
            "scores": assessment.scores.model_dump(mode="json", by_alias=True),
        })
    encoded = json.dumps(normalized, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def replay_row(row: dict[str, Any], arm: str, oracle: dict[str, list[str]]) -> dict[str, Any] | None:
    request = _decision_request(row)
    if request is None or not _behavioral_complete(row):
        return None
    snapshot = request.get("snapshot", {})
    eligible = _recorded_eligibility(row, snapshot)
    facts = _facts(snapshot, request)
    assessments = [_assessment(family, facts, snapshot) for family in eligible]
    basis = _stages(row).get("ACTION_ELIGIBILITY", {}).get("basis_hash")
    if not isinstance(basis, str) or len(basis) != 64:
        basis = hashlib.sha256(json.dumps(eligible, sort_keys=True).encode()).hexdigest()
    input_fp = _stages(row).get("DECISION_INPUT", {}).get("semantic_fingerprint")
    if not isinstance(input_fp, str) or len(input_fp) != 64:
        input_fp = hashlib.sha256(json.dumps(facts, sort_keys=True).encode()).hexdigest()
    ranking = SemanticRanking(
        protocol_version=protocol.RANKING_PROTOCOL_VERSION,
        eligibility_version=protocol.ACTION_ELIGIBILITY_VERSION,
        eligibility_basis_hash=basis,
        input_fingerprint=input_fp,
        assessments=assessments,
        ranking_weights_version=protocol.RANKING_WEIGHTS_VERSION,
    )
    try:
        selection = select_winner(eligible, ranking)
        error = None
    except SemanticRankingError as exc:
        selection = None
        error = str(exc)
    key = f"{row['scenario_id']}/{row['variant_id']}"
    expected = oracle.get(key, [])
    actual = row.get("actual_primary_action")
    return {
        "arm": arm,
        "scenario": row["scenario_id"],
        "variant": row["variant_id"],
        "repetition": row["repetition"],
        "historicalPass": bool(_stages(row).get("FINAL_RESULT", {}).get("evaluation_pass")),
        "historicalFailureClass": row.get("failure_class"),
        "executionResult": row.get("execution_result"),
        "expected": expected,
        "actual": actual,
        "actualCorrect": actual in expected,
        "eligibleFamilies": eligible,
        "actualEligible": actual in eligible,
        "shadowWinner": selection.winner_family if selection else None,
        "shadowScore": selection.winner_score if selection else None,
        "shadowCorrect": bool(selection and selection.winner_family in expected),
        "tieBreakApplied": selection.tie_break_applied if selection else None,
        "representationFingerprint": _representation_fingerprint(ranking),
        "semanticFingerprint": _stages(row).get("DECISION_INPUT", {}).get("semantic_fingerprint"),
        "error": error,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--oracle", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("artifacts", nargs="+")
    args = parser.parse_args()
    oracle = json.loads(args.oracle.read_text(encoding="utf-8"))
    results = []
    for raw_path in args.artifacts:
        path = Path(raw_path)
        arm = "B+" if "8b135f3f46b057b7b982e1b536dcb0fee851ddb8" in str(path) else "C"
        for row in _load(path):
            replay = replay_row(row, arm, oracle)
            if replay is not None:
                results.append(replay)
    results.sort(key=lambda value: (value["arm"], value["scenario"], value["variant"], value["repetition"]))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(json.dumps(value, ensure_ascii=False, sort_keys=True)
                                      for value in results) + "\n", encoding="utf-8")
    print(json.dumps({"rows": len(results), "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()

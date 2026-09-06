#!/usr/bin/env python3
"""Structured semantic planning diagnostic (DIAGNOSTIC ONLY).

Sends frozen DECISION inputs to the frozen provider/model with a frozen
planning prompt, validates planning-state.v1 responses, derives offline
action candidates through the frozen mapping, and scores pre-registered
gates. Never touches production code paths, prompts, or eligibility.
"""

from __future__ import annotations

import argparse
import os
import hashlib
import json
import sys
import time
import urllib.request
import urllib.error
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "agent-brain" / "src"))

from spec_agent_brain.contracts.planning import (
    PLANNING_STATE_VERSION,
    PlanningStateError,
    validate_planning_state,
)

ENDPOINT = "https://opencode.ai/zen/v1"
USER_AGENT = "opencode/1.18.21"
MODEL_EXPECTED = "mimo-v2.5-free"
REPETITIONS = 3
REQUEST_TIMEOUT_SECONDS = 120
PACING_SECONDS = 0.5
MAX_TOKENS = 800
MAPPING_VERSION = "planning-mapping.v1"

SYSTEM_PROMPT = (
    "You are a semantic planning classifier for a requirement-clarification runtime. "
    "You do NOT choose actions. Read the frozen task state and output EXACTLY ONE JSON object "
    "matching planning-state.v1. Output nothing else: no prose, no markdown fences, no extra fields."
)
PROMPT_FIELDS = (
    "Object shape: version is always planning-state.v1. Each of userInputRequired, "
    "externalStepRequired, directResponseSufficient, newDurableKnowledgePresent has "
    "value (boolean), reasonCodes (non-empty, only codes listed for that flag below), "
    "evidenceRefs (non-empty; each ref must start with one of: node:, answer:, patch:, "
    "context:, route:, claim:, capability:). Cite only items present in the input. "
    "Never cite scenarios, expected actions, or repetition numbers.",
    "userInputRequired: true iff correct progress now REQUIRES new user information, "
    "choice, confirmation, or blocker resolution; false if the task can proceed correctly "
    "without the user. True codes: NEED_MORE_INFO, BLOCKER_OPEN, CHOICE_UNRESOLVED. "
    "False code: USER_INPUT_SUFFICIENT.",
    "externalStepRequired: true iff the goal REQUIRES executing an external capability or "
    "tool step now. A relevant-but-optional tool, a background resource, or merely "
    "available capabilities mean false. True codes: EXTERNAL_EVIDENCE_REQUIRED, "
    "EXTERNAL_ACTION_REQUIRED, ARGUMENTS_GROUNDED. False codes: "
    "CAPABILITY_RELEVANT_NOT_REQUIRED, NO_EXTERNAL_NEED.",
    "directResponseSufficient: true iff the current step goal can be completed right now "
    "with existing information: no user input and no external step needed. True codes: "
    "GOAL_SATISFIED, NOTHING_NEW_TO_ASK. False code: DIRECT_RESPONSE_NOT_SUFFICIENT.",
    "newDurableKnowledgePresent: true iff a new, standalone semantic unit worth persisting "
    "exists now. Existing answers, confirmed claims, and re-summaries mean false. "
    "True code: DURABLE_FACT_WORTH_KEEPING. False code: NOTHING_DURABLE.",
)

SYSTEM_PROMPT = SYSTEM_PROMPT + "\n\n" + "\n".join(PROMPT_FIELDS)

FLAG_TO_FAMILY = {
    "user_input_required": "REQUEST_USER_INPUT",
    "external_step_required": "INVOKE_CAPABILITY",
    "direct_response_sufficient": "RESPOND_TO_USER",
    "new_durable_knowledge_present": "CREATE_NODE",
}
def _prompt_hash() -> str:
    return hashlib.sha256(SYSTEM_PROMPT.encode("utf-8")).hexdigest()


def load_credentials(repo_root: Path) -> tuple[str, str]:
    key = (os.environ.get("SPEC_AGENT_EVAL_OPENCODE_KEY") or "").strip()
    model = (os.environ.get("SPEC_AGENT_EVAL_OPENCODE_MODEL") or "").strip()
    if not key or not model:
        secrets = repo_root / "backend" / ".local-secrets.env"
        if secrets.is_file():
            for line in secrets.read_text(encoding="utf-8").splitlines():
                name, sep, value = line.partition("=")
                if not sep:
                    continue
                name = name.strip()
                if name == "SPEC_AGENT_EVAL_OPENCODE_KEY" and not key:
                    key = value.strip().strip("\\\"'")
                elif name == "SPEC_AGENT_EVAL_OPENCODE_MODEL" and not model:
                    model = value.strip().strip("\\\"'")
    if not key:
        raise SystemExit("missing SPEC_AGENT_EVAL_OPENCODE_KEY (not logged)")
    if model != MODEL_EXPECTED:
        raise SystemExit("model mismatch: expected " + MODEL_EXPECTED)
    return key, model


def credential_check(endpoint: str, api_key: str) -> dict:
    probe = urllib.request.Request(
        endpoint + "/models",
        headers={"User-Agent": USER_AGENT, "Authorization": "Bearer " + api_key},
        method="GET")
    try:
        with urllib.request.urlopen(probe, timeout=45) as response:
            body = response.read(4096).decode("utf-8", "replace")
            return {"http_status": response.status, "ok": 200 <= response.status < 300,
                    "body_bytes": len(body)}
    except Exception as exc:
        return {"http_status": None, "ok": False, "error": type(exc).__name__}


def post_completion(api_key: str, model: str, user_payload: dict) -> dict:
    body = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
        ],
        "response_format": {"type": "json_object"},
        "max_tokens": MAX_TOKENS,
        "stream": False,
    }, ensure_ascii=False).encode("utf-8")
    started = time.monotonic()
    request = urllib.request.Request(
        ENDPOINT + "/chat/completions", data=body,
        headers={"User-Agent": USER_AGENT,
                 "Authorization": "Bearer " + api_key,
                 "Content-Type": "application/json"},
        method="POST")
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            raw = response.read().decode("utf-8", "replace")
            return {"ok": 200 <= response.status < 300, "http_status": response.status,
                    "raw": raw, "latency_ms": int((time.monotonic() - started) * 1000)}
    except urllib.error.HTTPError as exc:
        try:
            raw = exc.read().decode("utf-8", "replace")[:2000]
        except Exception:
            raw = ""
        return {"ok": False, "http_status": exc.code, "raw": raw,
                "latency_ms": int((time.monotonic() - started) * 1000)}
    except Exception as exc:
        return {"ok": False, "http_status": None, "raw": "",
                "latency_ms": int((time.monotonic() - started) * 1000),
                "transport_error": type(exc).__name__}
def parse_strict(raw: str) -> tuple:
    text = (raw or "").strip()
    if not text:
        return None, "SCHEMA_FAILURE:empty-response"
    try:
        payload = json.loads(text)
    except Exception:
        return None, "SCHEMA_FAILURE:extra-free-form-text"
    if not isinstance(payload, dict):
        return None, "SCHEMA_FAILURE:extra-free-form-text"
    try:
        return validate_planning_state(payload), None
    except PlanningStateError as exc:
        message = str(exc)
        if "unknown reason codes" in message:
            return None, "SCHEMA_FAILURE:unknown-reason-code"
        if "bad evidence refs" in message:
            return None, "SCHEMA_FAILURE:bad-evidence-ref"
        if "requires reason codes" in message or "requires evidence refs" in message:
            return None, "SCHEMA_FAILURE:missing-required-field"
        return None, "SCHEMA_FAILURE:invalid-schema"


def derive_outcome(state, eligible: list) -> str:
    candidates = [FLAG_TO_FAMILY[name] for name in FLAG_TO_FAMILY
                  if getattr(state, name).value and FLAG_TO_FAMILY[name] in eligible]
    if not candidates:
        return "NO_WINNER"
    if len(candidates) == 1:
        return candidates[0]
    return "PLANNING_AMBIGUOUS"


def load_replay_rows(path: Path) -> list:
    rows = []
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def index_source_artifact(path: Path) -> dict:
    index = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        key = (row.get("scenario_id"), row.get("variant_id"), row.get("repetition"))
        index[key] = row
    return index


def build_model_input(source_row: dict, eligible: list) -> dict:
    stages = source_row.get("semantic_trace", {}).get("stages", {})
    request = stages.get("DECISION_INPUT", {}).get("runtime_request", {})
    return {
        "eligibleFamilies": eligible,
        "event": request.get("event", {}),
        "observation": request.get("observation", {}),
        "snapshot": request.get("snapshot", {}),
    }
GATES = {
    "b_missed_correction_at_least": "3/5",
    "c_critical_regression_exactly": "0/8",
    "c_full_regression_at_most": "2/27",
    "ineligible_rep_count": 0,
    "schema_failure_rep_count": 0,
    "derived_family_stability_at_least": 0.688,
}

MAJORITY = 2


def case_majority(outcomes: list) -> str | None:
    counts: dict = {}
    for outcome in outcomes:
        counts[outcome] = counts.get(outcome, 0) + 1
    best = max(counts.values())
    winners = [outcome for outcome, total in counts.items() if total == best]
    if best >= MAJORITY and len(winners) == 1:
        return winners[0]
    return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--replay", type=Path, required=True)
    parser.add_argument("--artifact-bplus", type=Path, required=True)
    parser.add_argument("--artifact-c", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args()

    api_key, model = load_credentials(REPO_ROOT)
    check = credential_check(ENDPOINT, api_key)
    if not check["ok"]:
        raise SystemExit("credential check failed: " + json.dumps(check))

    replay_rows = load_replay_rows(args.replay)
    seen = set()
    cases = []
    for row in replay_rows:
        identity = (row["arm"], row["scenario"], row["variant"], row["repetition"])
        if identity in seen:
            continue
        seen.add(identity)
        cases.append(row)

    sources = {"B+": index_source_artifact(args.artifact_bplus),
               "C": index_source_artifact(args.artifact_c)}
    joined = []
    join_miss = 0
    for row in cases:
        source = sources[row["arm"]].get((row["scenario"], row["variant"], row["repetition"]))
        if source is None:
            join_miss += 1
            continue
        joined.append((row, build_model_input(source, row["eligibleFamilies"])));
    if args.limit > 0:
        joined = joined[:args.limit]

    out_dir = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "experiment": "semantic-planning-diagnostic",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "head_commit": _git_head(),
        "provider": "opencode-zen",
        "endpoint": ENDPOINT,
        "model": model,
        "user_agent": USER_AGENT,
        "prompt_sha256": _prompt_hash(),
        "schema_version": PLANNING_STATE_VERSION,
        "mapping_version": MAPPING_VERSION,
        "repetitions": REPETITIONS,
        "request_timeout_seconds": REQUEST_TIMEOUT_SECONDS,
        "max_tokens": MAX_TOKENS,
        "response_format": "json_object",
        "stream": False,
        "total_replay_rows": len(replay_rows),
        "unique_cases": len(cases),
        "joined_cases": len(joined),
        "join_miss": join_miss,
        "limited_to": args.limit,
        "gates": GATES,
        "majority_rule": "case outcome needs >=2/3 identical derived outcomes; else no-majority",
        "correction_rule": "B+ missed case corrected iff majority outcome is a family in expected",
        "regression_rule": "control case regressed iff a majority outcome exists and is not in expected",
        "credential_check": {"http_status": check["http_status"], "ok": check["ok"]},
        "prompt": SYSTEM_PROMPT,
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2),
                                             encoding="utf-8")
    print(json.dumps({"manifest": str(out_dir / "manifest.json"),
                      "joined": len(joined), "unique": len(cases)}, ensure_ascii=False), flush=True)

    results_path = out_dir / "results.jsonl"
    case_rows = []
    for index, (row, model_input) in enumerate(joined):
        case_rows.append(run_case(api_key, model, results_path, row, model_input))
        done = index + 1
        if done % 10 == 0 or done == len(joined):
            print(json.dumps({"progress": done, "total": len(joined)},
                             ensure_ascii=False), flush=True)

    summary = summarize(case_rows)
    (out_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2),
                                             encoding="utf-8")
    print(json.dumps(summary["verdict"], ensure_ascii=False), flush=True)


MAPPABLE_FAMILIES = {"REQUEST_USER_INPUT", "INVOKE_CAPABILITY", "RESPOND_TO_USER", "CREATE_NODE"}


def summarize(case_rows: list) -> dict:
    for item in case_rows:
        item["majority"] = case_majority(item["outcomes"])
        item["mappable"] = bool(set(item["expected"]) & MAPPABLE_FAMILIES)
        item["corrected"] = (item["majority"] is not None
                             and item["majority"] in item["expected"])
        item["regressed"] = (item["majority"] is not None
                              and item["majority"] not in item["expected"])


def _gate_set(items: list) -> dict:
    return {
        "n": len(items),
        "corrected": sum(1 for item in items if item["corrected"]),
        "cases": [{"identity": item["identity"], "expected": item["expected"],
                     "outcomes": item["outcomes"], "majority": item["majority"]}
                    for item in items],
    }


def _control_set(items: list) -> dict:
    return {
        "n": len(items),
        "regressed": sum(1 for item in items if item["regressed"]),
        "cases": [{"identity": item["identity"], "expected": item["expected"],
                     "outcomes": item["outcomes"], "majority": item["majority"]}
                    for item in items],
    }


def _stability(case_rows: list) -> dict:
    rep_records = []
    for item in case_rows:
        rep_records.append(item)
    total = len(case_rows) or 1
    flag_flips = {}
    for name in FLAG_TO_FAMILY:
        same = sum(1 for item in case_rows
                   if len(item.get("flag_values", {}).get(name, [])) == 3
                   and len(set(item["flag_values"][name])) == 1)
        flag_flips[name] = round(1 - same / total, 4)
    whole_same = sum(1 for item in case_rows
                     if item["state_sigs"][0] is not None
                     and item["state_sigs"][0] == item["state_sigs"][1] == item["state_sigs"][2])
    family_same = sum(1 for item in case_rows if len(set(item["outcomes"])) == 1)
    return {"per_flag_flip_rate": flag_flips,
            "whole_state_exact_match_rate": round(whole_same / total, 4),
            "derived_family_stability": round(family_same / total, 4)}


def _schema_counts(case_rows: list) -> dict:
    counts: dict = {}
    ineligible = 0
    ambiguous = 0
    no_winner = 0
    for item in case_rows:
        for outcome in item["outcomes"]:
            counts[outcome] = counts.get(outcome, 0) + 1
            if outcome == "PLANNING_AMBIGUOUS":
                ambiguous += 1
            if outcome == "NO_WINNER":
                no_winner += 1
    return {"rep_outcome_counts": counts,
            "ambiguous_reps": ambiguous, "no_winner_reps": no_winner,
            "ineligible_reps": ineligible}
    missed = [item for item in case_rows
              if item["identity"]["arm"] == "B+" and item["mappable"]
              and not item["expected_correct_actual"] and not item["shadow_correct"]]
    controls15 = [item for item in case_rows
                  if item["identity"]["arm"] == "B+" and item["mappable"]
                  and not item["expected_correct_actual"] and item["shadow_correct"]]
    critical8 = [item for item in case_rows
                 if item["identity"]["arm"] == "C" and item["historical_pass"]
                 and item["expected_correct_actual"] and not item["shadow_correct"]]
    full27 = [item for item in case_rows
              if item["identity"]["arm"] == "C" and item["historical_pass"]]
    e22 = [item for item in case_rows if not item["mappable"]]
    return {
        "cases": len(case_rows),
        "unmappable_expected": len(e22),
        "b_missed": _gate_set(missed),
        "b_controls15": _control_set(controls15),
        "c_critical8": _control_set(critical8),
        "c_full_correct": _control_set(full27),
        "stability": _stability(case_rows),
        "schema": _schema_counts(case_rows),
        "verdict": _verdict(case_rows),
    }


def _verdict(case_rows: list) -> dict:
    total_reps = 3 * len(case_rows)
    provider_failures = 0
    schema_failures = 0
    for item in case_rows:
        for outcome in item["outcomes"]:
            if outcome == "PROVIDER_FAILURE":
                provider_failures += 1
            elif outcome.startswith("SCHEMA_FAILURE"):
                schema_failures += 1
    missed = [item for item in case_rows
              if item["identity"]["arm"] == "B+" and item["mappable"]
              and not item["expected_correct_actual"] and not item["shadow_correct"]]
    critical = [item for item in case_rows
                if item["identity"]["arm"] == "C" and item["historical_pass"]
                and item["expected_correct_actual"] and not item["shadow_correct"]]
    full = [item for item in case_rows
            if item["identity"]["arm"] == "C" and item["historical_pass"]]
    stability = _stability(case_rows)["derived_family_stability"]
    checks = {
        "correction_ge_3_of_5": sum(1 for item in missed if item["corrected"]) >= 3,
        "critical_8_zero": sum(1 for item in critical if item["regressed"]) == 0,
        "full_27_le_2": sum(1 for item in full if item["regressed"]) <= 2,
        "ineligible_zero": True,
        "schema_zero": schema_failures == 0,
        "stability_ge_reference": stability >= 0.688,
    }
    checks["missed_n"] = len(missed)
    checks["critical_n"] = len(critical)
    checks["full_n"] = len(full)
    sufficient = (provider_failures / max(total_reps, 1) < 0.10) and len(case_rows) >= 85
    if not sufficient:
        verdict = "INCONCLUSIVE"
    elif all([checks["correction_ge_3_of_5"], checks["critical_8_zero"],
               checks["full_27_le_2"], checks["ineligible_zero"],
               checks["schema_zero"], checks["stability_ge_reference"]]):
        verdict = "DIAGNOSTIC_ACCEPTED"
    else:
        # Required behavioral gates or the pre-registered stability reference
        # failed while coverage was sufficient: a behavioral rejection.
        # If stability alone failed it is recorded as the forensic driver.
        verdict = "DIAGNOSTIC_REJECTED"
        checks["stability_driver"] = (
            checks["correction_ge_3_of_5"] and checks["critical_8_zero"]
            and checks["full_27_le_2"] and checks["ineligible_zero"]
            and checks["schema_zero"] and not checks["stability_ge_reference"])
        verdict = "DIAGNOSTIC_REJECTED"
    checks["verdict"] = verdict
    checks["provider_failures"] = provider_failures
    checks["schema_failures"] = schema_failures
    return checks
def _git_head() -> str:
    try:
        import subprocess
        result = subprocess.run(["git", "-C", str(REPO_ROOT), "rev-parse", "HEAD"],
                                capture_output=True, text=True, timeout=15)
        return result.stdout.strip()
    except Exception:
        return "unknown"


def run_case(api_key: str, model: str, results_path: Path, row: dict, model_input: dict) -> dict:
    identity = {"arm": row["arm"], "scenario": row["scenario"],
                "variant": row["variant"], "repetition": row["repetition"]}
    rep_records = []
    for rep in range(REPETITIONS):
        call = post_completion(api_key, model, model_input)
        if not call["ok"]:
            record = {"rep": rep, "outcome": "PROVIDER_FAILURE",
                      "http_status": call["http_status"],
                      "transport_error": call.get("transport_error"),
                      "latency_ms": call["latency_ms"]}
        else:
            state, failure = parse_strict(call["raw"])
            if failure is not None:
                record = {"rep": rep, "outcome": failure,
                          "latency_ms": call["latency_ms"],
                          "raw_chars": len(call["raw"])}
            else:
                record = {"rep": rep,
                          "outcome": derive_outcome(state, model_input["eligibleFamilies"]),
                          "flags": {name: getattr(state, name).value for name in FLAG_TO_FAMILY},
                          "reason_codes": {name: getattr(state, name).reason_codes
                                           for name in FLAG_TO_FAMILY},
                          "evidence_refs": {name: getattr(state, name).evidence_refs
                                            for name in FLAG_TO_FAMILY},
                          "latency_ms": call["latency_ms"],
                          "raw_chars": len(call["raw"])}
        record["identity"] = identity
        rep_records.append(record)
        with results_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps({"identity": identity, "rep": rep,
                                      "expected": row["expected"],
                                      "historical_pass": row["historicalPass"],
                                      "record": record},
                                     ensure_ascii=False, sort_keys=True) + "\n")
        time.sleep(PACING_SECONDS)
    flag_values: dict = {}
    state_sigs = []
    for item in rep_records:
        flags = item.get("flags")
        if flags is None:
            state_sigs.append(None)
            continue
        for name, value in flags.items():
            flag_values.setdefault(name, []).append(value)
        state_sigs.append(json.dumps({"flags": flags,
                                      "reason_codes": item.get("reason_codes"),
                                      "evidence_refs": item.get("evidence_refs")},
                                     sort_keys=True))
    return {"identity": identity, "expected": row["expected"],
            "historical_pass": row["historicalPass"],
            "expected_correct_actual": row["actualCorrect"],
            "shadow_correct": row["shadowCorrect"],
            "historical_failure_class": row.get("historicalFailureClass"),
            "outcomes": [item["outcome"] for item in rep_records],
            "flag_values": flag_values,
            "state_sigs": state_sigs}


if __name__ == "__main__":
    main()

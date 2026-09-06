#!/usr/bin/env python3
"""R4 semantic planning diagnostic harness (DIAGNOSTIC ONLY).

Lineage SEMANTIC_PLANNING_DIAGNOSTIC_R4 (SEMANTIC_DEFINITION_CHANGE).
This round implements preflight only: --mode transport (G1), schema (G2),
calibration (G3), or preflight (G1->G2->G3 with abort). Formal 90x3 is
NOT implemented here and must not run without owner review.

Pipeline per rep: provider response -> extract choices[0].message.content
-> JSON parse -> C1 -> C2 -> C3 mapping. Never validates the whole
provider envelope. Per-rep class is exactly one of C1 / C2 / C3 /
PROVIDER_FAILURE. C1/C2 never count as semantic passes.
Never touches production code paths, prompts, or eligibility.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
import urllib.request
import urllib.error
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "tools"))

from semantic_planning.calibration import CALIBRATION_CASES, get_case
from semantic_planning.mapping_v2 import (
    MAPPING_VERSION,
    derive_outcome,
    mapping_hash,
)
from semantic_planning.planning_v2 import (
    evidence_vocab_hash,
    reason_vocab_hash,
    schema_hash,
    validate_c1,
)
from semantic_planning.prompt_v2 import SYSTEM_PROMPT_R4, prompt_hash
from semantic_planning.reference_goal import (
    REFERENCE_GOAL_VERSION,
    reference_goal_hash,
)
from semantic_planning.validation_v2 import check_c2

LINEAGE = "SEMANTIC_PLANNING_DIAGNOSTIC_R4"
PARENT_LINEAGE = "R3 SEMANTIC_PLANNING_DIAGNOSTIC_R3 (DIAGNOSTIC_REJECTED)"
ENDPOINT = "https://opencode.ai/zen/v1"
USER_AGENT = "opencode/1.18.21"
MODEL_EXPECTED = "mimo-v2.5-free"
REQUEST_TIMEOUT_SECONDS = 120
MAX_TOKENS = 800
PREFLIGHT_PACING_SECONDS = 5
CONSECUTIVE_ABORT = 3

SAMPLING_PROFILE = {
    "temperature": 0,
    "top_p": 1,
    "seed": "accepted-enforcement-unverifiable",
    "max_tokens": MAX_TOKENS,
    "response_format": "json_object",
    "stream": False,
    "transport": "DIRECT",
}

# Frozen G12 set: (arm, scenario, variant, repetition) with R3 majority in
# oracle-v2 expected, minus 4 E07-resolved structural exclusions.
FROZEN_REGRESSION_28 = (
    ("B+", "E01", "base", 0), ("B+", "E01", "paraphrase", 2),
    ("B+", "E01", "shuffled", 1),
    ("B+", "E07", "unresolved", 0), ("B+", "E07", "unresolved", 1),
    ("B+", "E07", "unresolved", 2),
    ("B+", "E07", "unresolved-paraphrase", 0),
    ("B+", "E07", "unresolved-paraphrase", 1),
    ("B+", "E07", "unresolved-paraphrase", 2),
    ("B+", "E17", "unconfirmed", 0), ("B+", "E17", "unconfirmed", 1),
    ("B+", "E17", "unconfirmed-decoy", 1),
    ("B+", "E19", "large", 1), ("B+", "E25", "stale-relation-set", 2),
    ("C", "E01", "base", 0), ("C", "E01", "paraphrase", 2),
    ("C", "E01", "shuffled", 1),
    ("C", "E07", "unresolved", 0), ("C", "E07", "unresolved", 1),
    ("C", "E07", "unresolved", 2),
    ("C", "E07", "unresolved-paraphrase", 0),
    ("C", "E07", "unresolved-paraphrase", 1),
    ("C", "E07", "unresolved-paraphrase", 2),
    ("C", "E17", "unconfirmed", 0), ("C", "E17", "unconfirmed", 1),
    ("C", "E17", "unconfirmed-decoy", 1),
    ("C", "E19", "large", 1), ("C", "E25", "stale-relation-set", 2),
)

# G2 schema corners reuse calibration wire shapes (validity only).
SCHEMA_CASE_IDS = ["CAL-U1", "CAL-U2", "CAL-D1", "CAL-E1", "CAL-N1"]


def extract_content(envelope_raw):
    t = (envelope_raw or "").strip()
    if not t:
        return None, "EXTRACT_FAIL:empty-response"
    try:
        env = json.loads(t)
    except Exception:
        return None, "EXTRACT_FAIL:extra-free-form-text"
    if not isinstance(env, dict):
        return None, "EXTRACT_FAIL:extra-free-form-text"
    try:
        choices = env.get("choices")
        if not isinstance(choices, list) or not choices:
            return None, "EXTRACT_FAIL:invalid-shape"
        first = choices[0]
        msg = first.get("message") if isinstance(first, dict) else None
        content = msg.get("content") if isinstance(msg, dict) else None
        if not isinstance(content, str) or not content.strip():
            return None, "EXTRACT_FAIL:invalid-shape"
        return content.strip(), None
    except Exception:
        return None, "EXTRACT_FAIL:invalid-shape"


def classify_rep(envelope_raw, model_input):
    content, ext_fail = extract_content(envelope_raw)
    if ext_fail is not None:
        return {"class": "C1", "outcome": "CONTRACT_VIOLATION_C1",
                "c1": "C1:" + ext_fail}
    try:
        parsed = json.loads(content)
    except Exception:
        return {"class": "C1", "outcome": "CONTRACT_VIOLATION_C1",
                "c1": "C1:BAD_JSON"}
    state, c1 = validate_c1(parsed, model_input)
    if c1 is not None:
        return {"class": "C1", "outcome": "CONTRACT_VIOLATION_C1",
                "c1": c1}
    violations = check_c2(state, model_input)
    if violations:
        return {"class": "C2", "outcome": "CONTRACT_VIOLATION_C2",
                "c1": None, "violations": violations}
    try:
        outcome, info = derive_outcome(
            state, model_input.get("eligibleFamilies", []))
    except ValueError as exc:
        return {"class": "C2", "outcome": "CONTRACT_VIOLATION_C2",
                "c1": None, "violations": ["C2:MAPPING_GUARD"]}
    flag_names = ("userInputRequired", "externalStepRequired",
                  "directResponseSufficient", "newDurableKnowledgePresent")
    flags = {k: state[k]["value"] for k in flag_names}
    return {"class": "C3", "outcome": outcome, "c1": None,
            "violations": [], "mapping_info": info,
            "goalType": state.get("goalType"), "flags": flags}


def flip_rate(flag_rep_lists):
    total = len(flag_rep_lists)
    if total == 0:
        return None
    same = sum(1 for reps in flag_rep_lists if len(set(reps)) == 1)
    return round(1 - same / total, 4)


def calibration_gate(case_results):
    passed = sum(1 for r in case_results if r.get("passed"))
    total = len(case_results)
    return {"passed": passed, "total": total,
            "gate": bool(total == 8 and passed >= 7)}


def _git_head():
    import subprocess
    try:
        out = subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO_ROOT,
                             capture_output=True, text=True, timeout=15)
        return out.stdout.strip()
    except Exception:
        return "unknown"


def _git_dirty():
    import subprocess
    try:
        out = subprocess.run(["git", "status", "--short"], cwd=REPO_ROOT,
                             capture_output=True, text=True, timeout=15)
        return bool(out.stdout.strip())
    except Exception:
        return True


def _digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_manifest(head, dirty, oracle_digest, replay_digest, counts):
    return {
        "experiment": LINEAGE,
        "parent_lineage": PARENT_LINEAGE,
        "lineage_type": "SEMANTIC_DEFINITION_CHANGE",
        "prompt_diff_class": "SEMANTIC_REDESIGN",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "head": head,
        "dirty": dirty,
        "oracle_path": "backend/build/ranking-shadow/oracle-v2.json",
        "oracle_digest": oracle_digest,
        "replay_path": "backend/build/ranking-shadow/replay.jsonl",
        "replay_digest": replay_digest,
        "prompt_hash": prompt_hash(),
        "schema_hash": schema_hash(),
        "reason_vocab_hash": reason_vocab_hash(),
        "evidence_vocab_hash": evidence_vocab_hash(),
        "mapping_version": MAPPING_VERSION,
        "mapping_hash": mapping_hash(),
        "reference_goal_version": REFERENCE_GOAL_VERSION,
        "reference_goal_hash": reference_goal_hash(),
        "sampling_profile": dict(SAMPLING_PROFILE),
        "endpoint": ENDPOINT,
        "model": MODEL_EXPECTED,
        "user_agent": USER_AGENT,
        "transport": "DIRECT",
        "temperature": SAMPLING_PROFILE["temperature"],
        "top_p": SAMPLING_PROFILE["top_p"],
        "seed_note": SAMPLING_PROFILE["seed"],
        "max_tokens": MAX_TOKENS,
        "response_format": "json_object",
        "stream": False,
        "schema_version": "planning-state.v2",
        "case_count": counts.get("cases"),
        "repetition_count": counts.get("reps"),
    }

def load_credentials():
    import os
    key = (os.environ.get("SPEC_AGENT_OPENCODE_KEY") or "").strip()
    if not key:
        key = (os.environ.get("SPEC_AGENT_EVAL_OPENCODE_KEY") or "").strip()
    model = (os.environ.get("SPEC_AGENT_OPENCODE_MODEL") or "").strip()
    if not model:
        model = (os.environ.get("SPEC_AGENT_EVAL_OPENCODE_MODEL") or "").strip()
    if not key or not model:
        secrets = REPO_ROOT / "backend" / ".local-secrets.env"
        if secrets.is_file():
            for line in secrets.read_text(encoding="utf-8").splitlines():
                name, sep, value = line.partition("=")
                if not sep:
                    continue
                n = name.strip()
                v = value.strip().strip(chr(34)).strip(chr(39))
                if n in ("SPEC_AGENT_OPENCODE_KEY",
                         "SPEC_AGENT_EVAL_OPENCODE_KEY") and not key:
                    key = v
                if n in ("SPEC_AGENT_OPENCODE_MODEL",
                         "SPEC_AGENT_EVAL_OPENCODE_MODEL") and not model:
                    model = v
    if not key:
        raise SystemExit("missing eval key (not logged)")
    if model != MODEL_EXPECTED:
        raise SystemExit("model mismatch")
    return key, model


def direct_post(api_key, model, user_payload):
    body = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT_R4},
            {"role": "user",
             "content": json.dumps(user_payload, ensure_ascii=False)},
        ],
        "response_format": {"type": "json_object"},
        "max_tokens": MAX_TOKENS,
        "temperature": SAMPLING_PROFILE["temperature"],
        "top_p": SAMPLING_PROFILE["top_p"],
        "stream": False,
    }, ensure_ascii=False).encode("utf-8")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    started = time.monotonic()
    request = urllib.request.Request(
        ENDPOINT + "/chat/completions", data=body,
        headers={"User-Agent": USER_AGENT,
                 "Authorization": "Bearer " + api_key,
                 "Content-Type": "application/json"},
        method="POST")
    try:
        with opener.open(request,
                         timeout=REQUEST_TIMEOUT_SECONDS) as response:
            raw = response.read().decode("utf-8", "replace")
            return {"ok": 200 <= response.status < 300,
                    "http_status": response.status, "raw": raw,
                    "latency_ms": int((time.monotonic() - started) * 1000)}
    except urllib.error.HTTPError as exc:
        try:
            raw = exc.read(32768).decode("utf-8", "replace")
        except Exception:
            raw = ""
        return {"ok": False, "http_status": exc.code, "raw": raw,
                "latency_ms": int((time.monotonic() - started) * 1000)}
    except Exception as exc:
        return {"ok": False, "http_status": None, "raw": "",
                "transport_error": type(exc).__name__,
                "latency_ms": int((time.monotonic() - started) * 1000)}


def _paced_call(api_key, model, model_input, clock):
    wait = clock[0] - time.monotonic()
    if wait > 0:
        time.sleep(wait)
    clock[0] = time.monotonic() + PREFLIGHT_PACING_SECONDS
    call = direct_post(api_key, model, model_input)
    if not call["ok"]:
        return {"class": "PROVIDER_FAILURE",
                "http_status": call.get("http_status"),
                "transport_error": call.get("transport_error"),
                "latency_ms": call["latency_ms"]}
    cls = classify_rep(call["raw"], model_input)
    cls["http_status"] = call["http_status"]
    cls["latency_ms"] = call["latency_ms"]
    content, _ = extract_content(call["raw"])
    if content is not None:
        cls["extracted"] = content[:4000]
    return cls


def run_transport(api_key, model, out_dir):
    sid = "00000000-0000-0000-0000-0000000000G1"
    records = []
    clock = [time.monotonic()]
    for index in range(5):
        mi = {"eligibleFamilies": ["REQUEST_USER_INPUT",
                                  "RESPOND_TO_USER"],
              "event": {"kind": "R4_PREFLIGHT", "index": index},
              "observation": {},
              "snapshot": {"snapshotId": sid,
                           "contextHash": "r4-preflight",
                           "lineage": [], "effectiveClaims": [],
                           "availableCapabilities": [],
                           "capabilityResults": [],
                           "autonomy": {"mode": "ADVISOR"}}}
        rec = {"index": index}
        rec.update(_paced_call(api_key, model, mi, clock))
        rec["c1_clean"] = rec.get("class") in ("C2", "C3")
        records.append(rec)
    (out_dir / "preflight.jsonl").write_text(
        "".join(json.dumps(r, ensure_ascii=False, sort_keys=True) + chr(10)
                for r in records), encoding="utf-8")
    ok = sum(1 for r in records if r.get("http_status") == 200)
    clean = sum(1 for r in records if r.get("c1_clean"))
    return {"gate": "G1", "http_200": str(ok) + "/5",
            "c1_clean": str(clean) + "/5",
            "pass": bool(ok == 5 and clean == 5), "records": records}


def run_schema(api_key, model, out_dir):
    records = []
    clock = [time.monotonic()]
    for cid in SCHEMA_CASE_IDS:
        case = get_case(cid)
        rec = {"case": cid, "reference_goal": case["reference_goal"]}
        rec.update(_paced_call(api_key, model, case["model_input"], clock))
        rec["clean"] = rec.get("class") == "C3"
        records.append(rec)
    (out_dir / "preflight.jsonl").open("a", encoding="utf-8").write(
        "".join(json.dumps(r, ensure_ascii=False, sort_keys=True) + chr(10)
                for r in records))
    ok = sum(1 for r in records if r.get("clean"))
    return {"gate": "G2", "clean": str(ok) + "/5",
            "pass": bool(ok == 5), "records": records}


def run_calibration(api_key, model, out_dir):
    clock = [time.monotonic()]
    case_results = []
    rep_records = []
    bad_streak = 0
    aborted = False
    for case in CALIBRATION_CASES:
        outs = []
        case_reps = []
        for rep in range(3):
            rec = {"case": case["id"], "rep": rep}
            rec.update(_paced_call(api_key, model, case["model_input"],
                                   clock))
            outs.append(rec.get("outcome"))
            case_reps.append(rec)
            rep_records.append(rec)
            if rec.get("class") == "PROVIDER_FAILURE":
                bad_streak += 1
                if bad_streak >= CONSECUTIVE_ABORT:
                    aborted = True
                    break
            else:
                bad_streak = 0
        if aborted:
            break
        if case["id"] == "CAL-N2":
            n2_ok = []
            for r in case_reps:
                flags = r.get("flags") or {}
                if r.get("class") == "C3" and \
                        flags.get("newDurableKnowledgePresent") is False and \
                        r.get("outcome") != "CREATE_NODE":
                    n2_ok.append(r)
            passed = len(n2_ok) >= 2
            hits = len(n2_ok)
        else:
            hits = sum(1 for o in outs if o == case["expected_mapping"])
            passed = hits >= 2
        case_results.append({"case": case["id"],
                             "expected": case["expected_mapping"],
                             "outcomes": outs, "hits": str(hits) + "/3",
                             "passed": passed})
    (out_dir / "calibration.jsonl").write_text(
        "".join(json.dumps(r, ensure_ascii=False, sort_keys=True) + chr(10)
                for r in rep_records), encoding="utf-8")
    gate = calibration_gate(case_results)
    return {"gate": "G3", "cases": case_results, "gate_result": gate,
            "pass": gate["gate"] and not aborted, "aborted": aborted}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["transport", "schema",
                                            "calibration", "preflight"],
                        required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    api_key, model = load_credentials()
    out_dir = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    oracle_path = REPO_ROOT / "backend/build/ranking-shadow/oracle-v2.json"
    replay_path = REPO_ROOT / "backend/build/ranking-shadow/replay.jsonl"
    manifest = build_manifest(
        _git_head(), _git_dirty(), _digest(oracle_path),
        _digest(replay_path), {"cases": 8, "reps": 3})
    manifest["prompt_full"] = SYSTEM_PROMPT_R4
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8")
    print(json.dumps({"manifest": str(out_dir / "manifest.json"),
                      "prompt_hash": manifest["prompt_hash"]}, ensure_ascii=False), flush=True)

    summary = {"manifest_head": manifest["head"]}
    modes = {"transport": ["transport"], "schema": ["schema"],
             "calibration": ["calibration"],
             "preflight": ["transport", "schema", "calibration"]}[args.mode]
    verdict = "PREFLIGHT_PASS"
    for step in modes:
        if step == "transport":
            res = run_transport(api_key, model, out_dir)
        elif step == "schema":
            res = run_schema(api_key, model, out_dir)
        else:
            res = run_calibration(api_key, model, out_dir)
        summary[step] = {k: v for k, v in res.items() if k != "records"}
        print(json.dumps(summary[step], ensure_ascii=False), flush=True)
        if not res.get("pass"):
            verdict = "PREFLIGHT_ABORT_AT_" + step.upper()
            break
    summary["verdict"] = verdict
    (out_dir / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8")
    print(json.dumps({"verdict": verdict}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()

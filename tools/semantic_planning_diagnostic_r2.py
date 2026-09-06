#!/usr/bin/env python3
"""Structured semantic planning diagnostic, lineage R2 (DIAGNOSTIC ONLY).

New lineage after R1 INCONCLUSIVE (transport proxy/egress contamination).
Semantic surfaces are imported from the R1 module, never redefined:
prompt bytes, schema, reason codes, mapping, gates, and majority rules
are identical by construction. Only the transport changes: explicit
DIRECT with no environment proxy, full error evidence, a 5-request
preflight gate, and a transport circuit breaker. Never wired into
production; never run as acceptance.
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

import semantic_planning_diagnostic as r1

PROMPT_FROZEN_SHA256 = "f8a9cac7ec0230471dd5b42d259c9ca2d617d4ed82f651eac0f219da0e7340db"
LINEAGE = "SEMANTIC_PLANNING_DIAGNOSTIC_R2"
PARENT_LINEAGE = "R1 INCONCLUSIVE (proxy/egress contamination)"
PREFLIGHT_REQUESTS = 5
START_INTERVAL_SECONDS = 10
CONSECUTIVE_ABORT = 3


def assert_semantic_freeze() -> None:
    digest = hashlib.sha256(r1.SYSTEM_PROMPT.encode("utf-8")).hexdigest()
    if digest != PROMPT_FROZEN_SHA256:
        raise SystemExit("semantic freeze violated: prompt hash mismatch")
    if r1.REPETITIONS != 3 or r1.MAPPING_VERSION != "planning-mapping.v1":
        raise SystemExit("semantic freeze violated: reps or mapping changed")
def load_credentials() -> tuple:
    import os
    key = (os.environ.get("SPEC_AGENT_EVAL_OPENCODE_KEY") or "").strip()
    model = (os.environ.get("SPEC_AGENT_EVAL_OPENCODE_MODEL") or "").strip()
    if not key or not model:
        secrets = REPO_ROOT / "backend" / ".local-secrets.env"
        if secrets.is_file():
            for line in secrets.read_text(encoding="utf-8").splitlines():
                name, sep, value = line.partition("=")
                if not sep:
                    continue
                if name.strip() == "SPEC_AGENT_EVAL_OPENCODE_KEY" and not key:
                    key = value.strip()
                if name.strip() == "SPEC_AGENT_EVAL_OPENCODE_MODEL" and not model:
                    model = value.strip()
    if not key:
        raise SystemExit("missing eval key (not logged)")
    if model != r1.MODEL_EXPECTED:
        raise SystemExit("model mismatch")
    return key, model


def direct_post(api_key: str, model: str, user_payload: dict) -> dict:
    body = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": r1.SYSTEM_PROMPT},
            {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
        ],
        "response_format": {"type": "json_object"},
        "max_tokens": r1.MAX_TOKENS,
        "stream": False,
    }, ensure_ascii=False).encode("utf-8")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    started = time.monotonic()
    request = urllib.request.Request(
        r1.ENDPOINT + "/chat/completions", data=body,
        headers={"User-Agent": r1.USER_AGENT,
                 "Authorization": "Bearer " + api_key,
                 "Content-Type": "application/json"},
        method="POST")
    try:
        with opener.open(request, timeout=r1.REQUEST_TIMEOUT_SECONDS) as response:
            raw = response.read().decode("utf-8", "replace")
            return _evidence(response.status, dict(response.headers), raw, started, None)
    except urllib.error.HTTPError as exc:
        try:
            raw = exc.read(32768).decode("utf-8", "replace")
        except Exception:
            raw = ""
        return _evidence(exc.code, dict(exc.headers), raw, started, None)
    except Exception as exc:
        return _evidence(None, {}, "", started, type(exc).__name__)


def _evidence(status, headers, raw, started, transport_error):
    lowered = {key.lower(): value for key, value in headers.items()}
    keep = ["retry-after", "x-request-id", "ratelimit-limit", "ratelimit-remaining",
            "ratelimit-reset", "cf-ray", "date"]
    return {"ok": status is not None and 200 <= status < 300,
            "http_status": status,
            "transport_error": transport_error,
            "latency_ms": int((time.monotonic() - started) * 1000),
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "retry_after": lowered.get("retry-after"),
            "request_id": lowered.get("x-request-id"),
            "rate_headers": {key: lowered[key] for key in keep if key in lowered},
            "raw": raw}
def paced_start(next_start: list) -> None:
    wait = next_start[0] - time.monotonic()
    if wait > 0:
        time.sleep(wait)
    next_start[0] = time.monotonic() + START_INTERVAL_SECONDS


def synthetic_preflight_input(index: int) -> dict:
    return {"eligibleFamilies": ["REQUEST_USER_INPUT", "RESPOND_TO_USER"],
            "event": {"kind": "R2_PREFLIGHT", "index": index},
            "observation": {},
            "snapshot": {"snapshotId": "00000000-0000-0000-0000-00000000000" + str(index),
                         "contextHash": "r2-preflight",
                         "lineage": [], "effectiveClaims": [],
                         "availableCapabilities": [], "capabilityResults": []}}


def run_preflight(api_key: str, model: str, out_dir) -> dict:
    records = []
    clock = [time.monotonic()]
    for index in range(PREFLIGHT_REQUESTS):
        paced_start(clock)
        call = direct_post(api_key, model, synthetic_preflight_input(index))
        call["index"] = index
        records.append(call)
    (out_dir / "preflight.jsonl").write_text(
        "\n".join(json.dumps(item, ensure_ascii=False, sort_keys=True) for item in records) + "\n",
        encoding="utf-8")
    passed = len(records) == PREFLIGHT_REQUESTS and all(item["http_status"] == 200 for item in records)
    return {"transport_preflight": "PASS" if passed else "FAIL",
            "statuses": [item["http_status"] for item in records]}


def run_case(api_key: str, model: str, results_path, row: dict,
             model_input: dict, clock: list) -> dict:
    identity = {"arm": row["arm"], "scenario": row["scenario"],
                "variant": row["variant"], "repetition": row["repetition"]}
    rep_records = []
    for rep in range(r1.REPETITIONS):
        paced_start(clock)
        call = direct_post(api_key, model, model_input)
        if not call["ok"]:
            record = {"rep": rep, "outcome": "PROVIDER_FAILURE",
                      "http_status": call["http_status"],
                      "transport_error": call["transport_error"],
                      "retry_after": call["retry_after"],
                      "request_id": call["request_id"],
                      "rate_headers": call["rate_headers"],
                      "latency_ms": call["latency_ms"],
                      "timestamp": call["timestamp"],
                      "raw_chars": len(call["raw"]),
                      "body": call["raw"]}
        else:
            state, failure = r1.parse_strict(call["raw"])
            if failure is not None:
                record = {"rep": rep, "outcome": failure,
                          "latency_ms": call["latency_ms"],
                          "timestamp": call["timestamp"],
                          "raw_chars": len(call["raw"]),
                          "body": call["raw"]}
            else:
                record = {"rep": rep,
                          "outcome": r1.derive_outcome(state, model_input["eligibleFamilies"]),
                          "flags": {name: getattr(state, name).value for name in r1.FLAG_TO_FAMILY},
                          "reason_codes": {name: getattr(state, name).reason_codes
                                           for name in r1.FLAG_TO_FAMILY},
                          "evidence_refs": {name: getattr(state, name).evidence_refs
                                            for name in r1.FLAG_TO_FAMILY},
                          "latency_ms": call["latency_ms"],
                          "timestamp": call["timestamp"],
                          "raw_chars": len(call["raw"]),
                          "body": call["raw"]}
        record["identity"] = identity
        rep_records.append(record)
        with results_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps({"identity": identity, "rep": rep,
                                      "expected": row["expected"],
                                      "historical_pass": row["historicalPass"],
                                      "record": record},
                                     ensure_ascii=False, sort_keys=True) + "\n")
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
            "outcomes": [item["outcome"] for item in rep_records],
            "flag_values": flag_values,
            "state_sigs": state_sigs}
def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--replay", type=Path, required=True)
    parser.add_argument("--artifact-bplus", type=Path, required=True)
    parser.add_argument("--artifact-c", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()

    assert_semantic_freeze()
    api_key, model = load_credentials()
    check = r1.credential_check(r1.ENDPOINT, api_key)
    if not check["ok"]:
        raise SystemExit("credential check failed")

    replay_rows = r1.load_replay_rows(args.replay)
    seen = set()
    cases = []
    for row in replay_rows:
        identity = (row["arm"], row["scenario"], row["variant"], row["repetition"])
        if identity in seen:
            continue
        seen.add(identity)
        cases.append(row)
    sources = {"B+": r1.index_source_artifact(args.artifact_bplus),
               "C": r1.index_source_artifact(args.artifact_c)}
    joined = []
    join_miss = 0
    for row in cases:
        source = sources[row["arm"]].get((row["scenario"], row["variant"], row["repetition"]))
        if source is None:
            join_miss += 1
            continue
        joined.append((row, r1.build_model_input(source, row["eligibleFamilies"])))

    out_dir = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "experiment": LINEAGE,
        "parent_lineage": PARENT_LINEAGE,
        "parent_artifact": "backend/build/semantic-planning-diagnostic/20260906-171500-bb6b7c2",
        "r1_root_cause": "transport proxy/egress contamination",
        "transport": "DIRECT",
        "proxy_bypass": "explicit empty ProxyHandler",
        "semantic_freeze": "unchanged",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "head_commit": r1._git_head(),
        "provider": "opencode-zen",
        "endpoint": r1.ENDPOINT,
        "model": model,
        "user_agent": r1.USER_AGENT,
        "prompt_sha256": hashlib.sha256(r1.SYSTEM_PROMPT.encode("utf-8")).hexdigest(),
        "schema_version": "planning-state.v1",
        "mapping_version": r1.MAPPING_VERSION,
        "repetitions": r1.REPETITIONS,
        "request_timeout_seconds": r1.REQUEST_TIMEOUT_SECONDS,
        "max_tokens": r1.MAX_TOKENS,
        "response_format": "json_object",
        "stream": False,
        "start_interval_seconds": START_INTERVAL_SECONDS,
        "concurrency": 1,
        "retry": 0,
        "preflight_requests": PREFLIGHT_REQUESTS,
        "circuit_breaker": "abort after 3 consecutive non-200 provider outcomes",
        "total_replay_rows": len(replay_rows),
        "unique_cases": len(cases),
        "joined_cases": len(joined),
        "join_miss": join_miss,
        "gates": r1.GATES,
        "majority_rule": "case outcome needs >=2/3 identical derived outcomes; else no-majority",
        "correction_rule": "B+ missed case corrected iff majority outcome is a family in expected",
        "regression_rule": "control case regressed iff a majority outcome exists and is not in expected",
        "credential_check": {"http_status": check["http_status"], "ok": check["ok"]},
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2),
                                             encoding="utf-8")
    print(json.dumps({"manifest": str(out_dir / "manifest.json"),
                      "joined": len(joined), "unique": len(cases)}, ensure_ascii=False), flush=True)

    preflight = run_preflight(api_key, model, out_dir)
    print(json.dumps({"preflight": preflight}, ensure_ascii=False), flush=True)
    if preflight["transport_preflight"] != "PASS":
        summary = {"transport_preflight": preflight, "verdict": {"verdict": "INCONCLUSIVE",
                   "reason": "preflight gate failed; main phase never started"}}
        (out_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2),
                                                 encoding="utf-8")
        return

    results_path = out_dir / "results.jsonl"
    case_rows = []
    clock = [time.monotonic()]
    aborted = False
    bad_streak = 0
    for index, (row, model_input) in enumerate(joined):
        case_rows.append(run_case(api_key, model, results_path, row, model_input, clock))
        for outcome in case_rows[-1]["outcomes"]:
            bad_streak = bad_streak + 1 if outcome == "PROVIDER_FAILURE" else 0
            if bad_streak >= CONSECUTIVE_ABORT:
                aborted = True
                break
        if aborted:
            break
        done = index + 1
        if done % 10 == 0 or done == len(joined):
            print(json.dumps({"progress": done, "total": len(joined)}, ensure_ascii=False), flush=True)
    summary = r1.summarize(case_rows)
    if aborted:
        summary["verdict"] = {"verdict": "INCONCLUSIVE",
                                "reason": "transport circuit breaker: 3 consecutive non-200",
                                "completed_cases": len(case_rows)}
    summary["transport_preflight"] = preflight
    summary["transport_abort"] = aborted
    (out_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2),
                                             encoding="utf-8")
    print(json.dumps(summary["verdict"], ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()

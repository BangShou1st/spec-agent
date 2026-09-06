#!/usr/bin/env python3
"""Provider sampling capability probe. DIAGNOSTIC ONLY."""
import json, time, urllib.request, urllib.error
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ENDPOINT = "https://opencode.ai/zen/v1"
UA = "opencode/1.18.21"
PROBE_INPUT = {
    "eligibleFamilies": ["REQUEST_USER_INPUT", "RESPOND_TO_USER"],
    "event": {"kind": "SAMPLING_PROBE"},
    "observation": {"probe": True},
    "snapshot": {
        "snapshotId": "00000000-0000-0000-0000-000000000001",
        "contextHash": "sampling-probe",
        "lineage": [], "effectiveClaims": [],
        "availableCapabilities": [], "capabilityResults": [],
        "citableRefs": ["node:00000000-0000-0000-0000-000000000001"],
    },
}
SYSTEM_PROMPT = "You are a semantic planning classifier. Output exactly one JSON object matching planning-state.v1."

def load_credentials():
    import os
    key = (os.environ.get("SPEC_AGENT_OPENCODE_KEY") or "").strip()
    model = (os.environ.get("SPEC_AGENT_OPENCODE_MODEL") or "").strip()
    if not key or not model:
        secrets = REPO_ROOT / "backend" / ".local-secrets.env"
        if secrets.is_file():
            for line in secrets.read_text(encoding="utf-8").splitlines():
                name, sep, value = line.partition("=")
                if not sep: continue
                n, v = name.strip(), value.strip().strip('"').strip("'")
                if n == "SPEC_AGENT_OPENCODE_KEY" and not key: key = v
                if n == "SPEC_AGENT_OPENCODE_MODEL" and not model: model = v
    return key, model

def do_probe(label, extra_body, key, model):
    body = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": json.dumps(PROBE_INPUT, ensure_ascii=False)},
        ],
        "response_format": {"type": "json_object"},
        "max_tokens": 400, "stream": False, **extra_body,
    }, ensure_ascii=False).encode("utf-8")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    req = urllib.request.Request(
        ENDPOINT + "/chat/completions", data=body,
        headers={"User-Agent": UA, "Authorization": "Bearer " + key,
                 "Content-Type": "application/json"}, method="POST")
    try:
        with opener.open(req, timeout=120) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return {"label": label, "http_status": resp.status, "ok": True,
                    "params_sent": extra_body, "raw": raw[:3000]}
    except urllib.error.HTTPError as exc:
        try: raw = exc.read(8192).decode("utf-8", "replace")
        except: raw = ""
        return {"label": label, "http_status": exc.code, "ok": False,
                "params_sent": extra_body, "raw": raw[:3000]}

def main():
    key, model = load_credentials()
    if not key:
        print("ERROR: missing credentials"); return
    probes = [
        ("temp0_top1", {"temperature": 0, "top_p": 1}),
        ("temp0_top1_seed42", {"temperature": 0, "top_p": 1, "seed": 42}),
        ("temp1_default", {"temperature": 1}),
        ("temp0_only", {"temperature": 0}),
    ]
    results = []
    for label, params in probes:
        print("Probing: " + label + " ...", flush=True)
        r = do_probe(label, params, key, model)
        results.append(r)
        print("  -> http " + str(r["http_status"]) + ", ok=" + str(r["ok"]), flush=True)
        time.sleep(3)
    out = REPO_ROOT / "tools" / "probes" / "sampling_probe_results.json"
    out.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Results saved to " + str(out))

if __name__ == "__main__":
    main()


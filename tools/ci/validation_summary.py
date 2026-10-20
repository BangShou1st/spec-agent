"""Render truthful CI scope; skipped integrations are never counted as passes."""
import json
import os
from pathlib import Path

INDEPENDENT = ("frontend", "brain", "backend-independent", "contracts-static")
EXTERNAL = ("backend", "eval-baseline", "cross-language", "e2e")


def summarize(needs, full):
    rows = []
    failures = []
    if needs.get("validation-mode", {}).get("result") != "success":
        failures.append("validation-mode did not complete successfully")
    for name in INDEPENDENT + EXTERNAL:
        result = needs.get(name, {}).get("result", "missing")
        if name in EXTERNAL and not full and result == "skipped":
            status = "SKIPPED_EXTERNAL_DEPENDENCY"
            reason = "Zen compile-time types unavailable; explicitly excluded in independent mode"
        elif result == "success":
            status, reason = "PASS", "Executed successfully (see job logs for coverage)"
            if name in EXTERNAL and not full:
                failures.append(name + ": external job ran in independent mode")
        else:
            status, reason = "BLOCKED", "Actual GitHub job result: " + result
            failures.append(name + ": " + result)
        rows.append((name, status, reason))
    scope = "full" if full else "independent"
    lines = [f"## Validation scope: {scope}", "",
             "Independent results do not qualify the full backend, Spring startup, database or live providers.",
             "", "| Job | Status | Evidence / limitation |", "| --- | --- | --- |"]
    lines += [f"| {name} | {status} | {reason} |" for name, status, reason in rows]
    lines += ["", "Live model quality: NOT_MEASURED. No live model requests are part of this workflow."]
    return "\n".join(lines) + "\n", failures


if __name__ == "__main__":
    report, failures = summarize(json.loads(os.environ["CI_NEEDS"]), os.environ["CI_FULL"] == "true")
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as stream:
        stream.write(report)
    print(report)
    if failures:
        raise SystemExit("Validation failed: " + "; ".join(failures))

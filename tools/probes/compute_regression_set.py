"""Compute R4 critical-regression identity set from R3 frozen results + oracle-v2. READ-ONLY."""
import json
from pathlib import Path
from collections import Counter

REPO = Path("E:/project/spec-agent")
results_path = REPO / "backend/build/semantic-planning-diagnostic-r3/20260906-193000-e6075b1/results.jsonl"
oracle = json.loads((REPO / "backend/build/ranking-shadow/oracle-v2.json").read_text(encoding="utf-8"))

by_ident = {}
for line in results_path.read_text(encoding="utf-8").splitlines():
    if not line.strip():
        continue
    rec = json.loads(line)
    ident = rec["identity"]
    key = (ident["arm"], ident["scenario"], ident["variant"], ident["repetition"])
    by_ident.setdefault(key, []).append(rec["record"].get("outcome"))

members, nonmembers = [], []
for key in sorted(by_ident):
    arm, scen, var, rep = key
    outs = by_ident[key]
    cnt = Counter(outs)
    best = max(cnt.values())
    winners = [o for o, c in cnt.items() if c == best]
    majority = winners[0] if best >= 2 and len(winners) == 1 else None
    exp = oracle.get(scen + "/" + var, [])
    label = arm + " " + scen + "/" + var + " r" + str(rep)
    if majority is not None and majority in exp:
        members.append((label, majority, exp))
    else:
        nonmembers.append((label, majority, outs, exp))

print("MEMBERS: " + str(len(members)))
for label, maj, exp in members:
    print("  " + label + " majority=" + str(maj) + " expected=" + str(exp))
print("NONMEMBERS: " + str(len(nonmembers)))
for label, maj, outs, exp in nonmembers:
    print("  " + label + " majority=" + str(maj) + " outcomes=" + str(outs) + " expected=" + str(exp))
